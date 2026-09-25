// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionErrorKind
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.protocol.ControlCodec
import dev.openflight.companion.core.protocol.ShotEventDecoder
import dev.openflight.companion.core.protocol.ShotTransport
import dev.openflight.companion.core.protocol.SseByteStreamParser
import dev.openflight.companion.core.protocol.SseEvent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class ClubRequestBody(
    val club: GolfClub,
)

@Serializable
private data class ServerErrorBody(
    val error: String? = null,
)

/**
 * The Wi-Fi sibling of the BLE transport (S5), ported from
 * `ios/OpenFlight/{WiFiShotClient,RadarCalibrationClient,PhoneControl}.swift` (plan §0.2, §0.3,
 * Step 4). Each instance is bound to one [host]; `core:data` (S6) builds a new instance when the
 * host changes rather than mutating this one.
 *
 * SSE decision (plan Step 4, task 3): this reads raw bytes from `bodyAsChannel()` into the
 * existing [SseByteStreamParser] from `core:protocol` (S2), instead of Ktor's `sse {}` client
 * plugin. That parser is already ported from the reference and its existing tests
 * (`core:protocol`'s `SseEventParserTest`) cover CRLF line endings, comment-only heartbeats and
 * multi-line `data:` fields -- the exact bar the plan sets for choosing the plugin. The plugin
 * was not used because it owns its own session/reconnect lifecycle, which would fight the
 * transport's own backoff and `lastEventId`-preserving reconnect semantics (plan §0.3): this
 * transport needs to decide for itself when to reset the decoder, and the plugin does not expose
 * that distinction between an automatic retry and an explicit [retry].
 */
class WifiShotTransport(
    private val host: String,
    private val httpClient: HttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ShotTransport {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _shots = MutableSharedFlow<ShotEvent>(extraBufferCapacity = SHOT_REPLAY_BUFFER)
    override val shots: Flow<ShotEvent> = _shots.asSharedFlow()

    private val _activeClub = MutableStateFlow<GolfClub?>(null)
    override val activeClub: StateFlow<GolfClub?> = _activeClub.asStateFlow()

    // The Pi's Wi-Fi API always exposes the club/calibration control endpoints; there is no
    // discovery step the way there is over BLE.
    override val supportsControls: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    private val parser = SseByteStreamParser()
    private val decoder = ShotEventDecoder()

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + supervisorJob)
    private var streamJob: Job? = null

    override fun start() {
        if (streamJob?.isActive == true) return
        val url =
            when (val decision = EndpointPolicy.evaluate(host)) {
                is EndpointDecision.Allowed -> {
                    decision.endpoint.url(STREAM_PATH)
                }

                is EndpointDecision.Rejected -> {
                    // Plan R8d: a refused address never reaches Ktor; say why instead.
                    _state.value = ConnectionState.Error(decision.reason, ConnectionErrorKind.ENDPOINT_REJECTED)
                    return
                }
            }
        streamJob = scope.launch { run(url) }
    }

    override fun retry() {
        disconnect()
        start()
    }

    override fun disconnect() {
        streamJob?.cancel()
        streamJob = null
        parser.reset()
        decoder.reset()
        _activeClub.value = null
        _state.value = ConnectionState.Idle
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun run(url: String) {
        var reconnectDelay = INITIAL_RECONNECT_DELAY
        while (coroutineContext.isActive) {
            try {
                connect(url)
                // connect() only returns when the byte channel ends cleanly.
                reconnectDelay = INITIAL_RECONNECT_DELAY
                throw OpenFlightHttpError.StreamEnded
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Any failure -- a bad status, a decode error surfaced as an exception, or the
                // stream ending cleanly -- is retryable, so it is reported the same way here.
                if (!coroutineContext.isActive) return
                _state.value = error.toConnectionError()
                delay(reconnectDelay)
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAXIMUM_RECONNECT_DELAY)
            }
        }
    }

    private suspend fun connect(url: String) {
        _state.value = ConnectionState.Connecting
        parser.reset()

        httpClient
            .prepareRequest(url) {
                headers { append(HttpHeaders.Accept, "text/event-stream") }
                timeout {
                    // An infinite request timeout: the request "completes" only when the stream ends,
                    // which can be much later than any fixed deadline. socketTimeoutMillis is the real
                    // guard: three missed 15 s heartbeats (plan §0.2).
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = IDLE_TIMEOUT_MILLIS
                }
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    throw OpenFlightHttpError.UnexpectedStatus(response.status.value)
                }
                _state.value = ConnectionState.Connected
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(1)
                while (channel.readAvailable(buffer, 0, 1) != END_OF_STREAM) {
                    parser.append(buffer[0])?.let { receive(it) }
                }
            }
    }

    /**
     * Handles one parsed event. Kept separate from the network loop so it's directly testable,
     * mirroring `WiFiShotClient.receive(_:)`.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun receive(event: SseEvent) {
        when {
            event.name == ControlCodec.TYPE_CLUB_CHANGED -> {
                try {
                    _activeClub.value = ControlCodec.decodeClubChangedEvent(event.data.encodeToByteArray())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    _state.value = ConnectionState.Error(error.message ?: error.toString())
                }
            }

            event.name == null || event.name == "shot" -> {
                try {
                    val shot = decoder.decode(event.data)
                    if (shot != null) _shots.emit(shot)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    _state.value = ConnectionState.Error(error.message ?: error.toString())
                }
            }
        }
    }

    override suspend fun setClub(club: GolfClub): ClubSelection =
        controlRequest(CLUB_PATH) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(ClubRequestBody(club))
        }

    override suspend fun currentClub(): ClubSelection =
        controlRequest(CLUB_PATH) {
            method = HttpMethod.Get
        }

    override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult =
        controlRequest(CALIBRATION_PATH) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(measurement)
        }

    private suspend inline fun <reified T> controlRequest(
        path: String,
        crossinline configure: HttpRequestBuilder.() -> Unit,
    ): T {
        val url = EndpointUrl.require(host, path)
        val response =
            httpClient.request(url) {
                configure()
                timeout { requestTimeoutMillis = CONTROL_TIMEOUT_MILLIS }
            }
        if (response.status != HttpStatusCode.OK) {
            throw OpenFlightHttpError.UnexpectedStatus(response.status.value, serverErrorMessage(response))
        }
        return response.body()
    }

    companion object {
        const val STREAM_PATH = "/api/shots/stream"
        const val CLUB_PATH = "/api/club"
        const val CALIBRATION_PATH = "/api/calibration/iwr6843/orientation"

        private const val SHOT_REPLAY_BUFFER = 8
        private const val IDLE_TIMEOUT_MILLIS = 45_000L
        private const val CONTROL_TIMEOUT_MILLIS = 10_000L
        private const val END_OF_STREAM = -1

        private val INITIAL_RECONNECT_DELAY = 1.seconds
        private val MAXIMUM_RECONNECT_DELAY = 15.seconds
    }
}

/** A stream failure as a state; a denied iOS Local Network permission gets its own, actionable kind. */
private fun Throwable.toConnectionError(): ConnectionState.Error =
    if (LocalNetworkDenial.isDenied(this)) {
        ConnectionState.Error(LocalNetworkDenial.MESSAGE, ConnectionErrorKind.LOCAL_NETWORK_DENIED)
    } else {
        ConnectionState.Error(message ?: toString())
    }

private suspend fun serverErrorMessage(response: HttpResponse): String? =
    runCatching { response.body<ServerErrorBody>().error }.getOrNull()
