// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.network.PiControlClient
import dev.openflight.companion.core.protocol.SchemaV2Event
import dev.openflight.companion.core.protocol.ShotTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** A scripted transport. [events] is shared between fakes so tests can assert cross-transport order. */
internal class FakeShotTransport(
    private val name: String,
    private val events: MutableList<String> = mutableListOf(),
) : ShotTransport {
    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val shots = MutableSharedFlow<ShotEvent>(extraBufferCapacity = 256)
    override val activeClub = MutableStateFlow<GolfClub?>(null)
    override val supportsControls = MutableStateFlow(false)
    override val schemaEvents = MutableSharedFlow<SchemaV2Event>(extraBufferCapacity = 64)

    var startCount = 0
        private set
    var disconnectCount = 0
        private set
    var retryCount = 0
        private set
    var currentClubCalls = 0
        private set
    val setClubCalls = mutableListOf<GolfClub>()

    var setClubResponse: suspend (GolfClub) -> ClubSelection = { ClubSelection(status = "ok", club = it) }
    var currentClubResponse: suspend () -> ClubSelection = { ClubSelection(status = "ok", club = GolfClub.DRIVER) }
    var calibrationResponse: suspend (PhoneOrientationMeasurement) -> CalibrationResult = {
        error("no calibration response scripted")
    }

    override fun start() {
        startCount++
        events += "$name.start"
    }

    override fun retry() {
        retryCount++
        events += "$name.retry"
    }

    override fun disconnect() {
        disconnectCount++
        events += "$name.disconnect"
        state.value = ConnectionState.Idle
    }

    override suspend fun setClub(club: GolfClub): ClubSelection {
        setClubCalls += club
        return setClubResponse(club)
    }

    override suspend fun currentClub(): ClubSelection {
        currentClubCalls++
        return currentClubResponse()
    }

    override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult =
        calibrationResponse(measurement)
}

internal class FakeSettingsRepository(
    transport: TransportType = SettingsRepository.DEFAULT_TRANSPORT,
    host: String = SettingsRepository.DEFAULT_HOST,
    club: GolfClub = SettingsRepository.DEFAULT_CLUB,
    units: UnitSystem = SettingsRepository.DEFAULT_UNITS,
) : SettingsRepository {
    val transportState = MutableStateFlow(transport)
    val hostState = MutableStateFlow(host)
    val clubState = MutableStateFlow(club)
    val unitsState = MutableStateFlow(units)

    /** Every club written, in order, so tests can assert "persisted exactly once". */
    val clubWrites = mutableListOf<GolfClub>()

    override val transport: Flow<TransportType> = transportState
    override val host: Flow<String> = hostState
    override val selectedClub: Flow<GolfClub> = clubState
    override val units: Flow<UnitSystem> = unitsState

    override suspend fun setTransport(transport: TransportType) {
        transportState.value = transport
    }

    override suspend fun setHost(host: String) {
        hostState.value = host
    }

    /** Every [rememberConnectedHost] call, in order. */
    val connectedHosts = mutableListOf<String>()

    override suspend fun rememberConnectedHost(host: String) {
        connectedHosts += host
    }

    override suspend fun setSelectedClub(club: GolfClub) {
        clubWrites += club
        clubState.value = club
    }

    override suspend fun setUnits(units: UnitSystem) {
        unitsState.value = units
    }
}

/** A [PiControlClient] whose `shutdown` always answers the given [status] (default: success). */
internal fun fakePiControlClient(
    status: String = "shutting_down",
    requestedUrls: MutableList<String>? = null,
): PiControlClient {
    val engine =
        MockEngine { request ->
            requestedUrls?.add(request.url.toString())
            respond(
                content = """{"status":"$status"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
    val client =
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
    return PiControlClient(client)
}

/** A valid UUID event id that encodes [number], so assertions stay readable. */
internal fun shotId(number: Int): String = "00000000-0000-4000-8000-" + number.toString().padStart(12, '0')

internal fun shot(number: Int): ShotEvent =
    ShotEvent(
        schemaVersion = 1,
        eventId = shotId(number),
        timestamp = "2026-08-05T23:54:00",
        club = "driver",
        ballSpeedMph = 140.0,
        estimatedCarryYards = 250.0,
    )
