// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.data.WifiOnlyFeatureException.Reason
import dev.openflight.companion.core.model.pi.CameraStatus
import dev.openflight.companion.core.model.pi.CloudUploadState
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.RadarConfigUpdate
import dev.openflight.companion.core.model.pi.SessionState
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.SimState
import dev.openflight.companion.core.model.pi.SwingSpeedReading
import dev.openflight.companion.core.model.pi.TrainingImplement
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.socketio.SocketConnectionState
import dev.openflight.companion.core.socketio.SocketEvent
import dev.openflight.companion.core.socketio.SocketNotConnectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [PiSessionRepository] over one [PiSocket] per Wi-Fi host.
 *
 * It follows `(transport, host)` like [DefaultShotRepository]: a change cancels the current
 * connection first. Switching to Bluetooth or to another host clears every flow (the data belonged
 * to that Pi); [stop] keeps them, so returning to the foreground shows the last state until the
 * reconnect's `session_state` replaces it.
 *
 * Event handling mirrors the web UI's `socketService.ts` and its stores (b053194).
 */
@Suppress("TooManyFunctions") // The PiSessionRepository surface plus one handler per event group.
internal class DefaultPiSessionRepository(
    private val settings: SettingsRepository,
    private val socketFactory: PiSocketFactory,
    private val cameraSource: PiCameraSource,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : PiSessionRepository {
    private val mutableLinkState = MutableStateFlow<PiLinkState>(PiLinkState.Idle)
    private val mutableSessionShots = MutableStateFlow(emptyList<ShotDetail>())
    private val mutableShotDetails = MutableStateFlow(emptyMap<String, ShotDetail>())
    private val mutableStats = MutableStateFlow<SessionStats?>(null)
    private val mutablePlayerName = MutableStateFlow<String?>(null)
    private val mutableTrainingImplement = MutableStateFlow<TrainingImplement?>(null)
    private val mutableLatestSwingSpeed = MutableStateFlow<SwingSpeedReading?>(null)
    private val mutableTriggerStatus = MutableStateFlow<TriggerStatus?>(null)
    private val mutableCameraStatus = MutableStateFlow(CameraStatus())
    private val mutableSimState = MutableStateFlow(SimState())
    private val mutableRadarConfig = MutableStateFlow<RadarConfig?>(null)
    private val mutableDebugState = MutableStateFlow(DebugState())
    private val mutableCloudUploadStatus = MutableStateFlow(CloudUploadStatus())
    private val mutableMockMode = MutableStateFlow<Boolean?>(null)
    private val mutableNotices = MutableSharedFlow<PiNotice>(extraBufferCapacity = NOTICE_BUFFER)

    override val linkState: StateFlow<PiLinkState> = mutableLinkState.asStateFlow()
    override val sessionShots: StateFlow<List<ShotDetail>> = mutableSessionShots.asStateFlow()
    override val shotDetails: StateFlow<Map<String, ShotDetail>> = mutableShotDetails.asStateFlow()
    override val stats: StateFlow<SessionStats?> = mutableStats.asStateFlow()
    override val playerName: StateFlow<String?> = mutablePlayerName.asStateFlow()
    override val trainingImplement: StateFlow<TrainingImplement?> = mutableTrainingImplement.asStateFlow()
    override val latestSwingSpeed: StateFlow<SwingSpeedReading?> = mutableLatestSwingSpeed.asStateFlow()
    override val triggerStatus: StateFlow<TriggerStatus?> = mutableTriggerStatus.asStateFlow()
    override val cameraStatus: StateFlow<CameraStatus> = mutableCameraStatus.asStateFlow()
    override val simState: StateFlow<SimState> = mutableSimState.asStateFlow()
    override val radarConfig: StateFlow<RadarConfig?> = mutableRadarConfig.asStateFlow()
    override val debugState: StateFlow<DebugState> = mutableDebugState.asStateFlow()
    override val cloudUploadStatus: StateFlow<CloudUploadStatus> = mutableCloudUploadStatus.asStateFlow()
    override val mockMode: StateFlow<Boolean?> = mutableMockMode.asStateFlow()
    override val notices: SharedFlow<PiNotice> = mutableNotices.asSharedFlow()

    private val activeSocket = MutableStateFlow<PiSocket?>(null)
    private var currentKey: SessionKey? = null
    private var sessionJob: Job? = null

    override fun start() {
        if (sessionJob?.isActive == true) return
        sessionJob =
            scope.launch {
                combine(settings.transport, settings.host) { type, host ->
                    SessionKey(type, host.takeIf { type == TransportType.WIFI })
                }.distinctUntilChanged()
                    .collectLatest(::runSession)
            }
    }

    override fun stop() {
        sessionJob?.cancel()
        sessionJob = null
    }

    override suspend fun refreshSession() = command("get_session")

    override suspend fun deleteShot(timestamp: String) =
        command(
            "delete_shot",
            buildJsonObject {
                put("timestamp", timestamp)
            },
        )

    override suspend fun clearSession() = command("clear_session")

    override suspend fun simulateShot() = command("simulate_shot")

    override suspend fun setPlayer(name: String) = command("set_player", buildJsonObject { put("player_name", name) })

    override suspend fun setTrainingImplement(implement: String) =
        command("set_training_implement", buildJsonObject { put("implement", implement) })

    override suspend fun toggleCamera() = command("toggle_camera")

    override suspend fun toggleCameraStream() = command("toggle_camera_stream")

    override suspend fun refreshCameraStatus() = command("get_camera_status")

    override suspend fun refreshRadarConfig() = command("get_radar_config")

    override suspend fun setRadarConfig(update: RadarConfigUpdate) {
        val payload =
            JsonObject(
                buildMap {
                    update.minSpeed?.let { put("min_speed", JsonPrimitive(it)) }
                    update.maxSpeed?.let { put("max_speed", JsonPrimitive(it)) }
                    update.minMagnitude?.let { put("min_magnitude", JsonPrimitive(it)) }
                    update.transmitPower?.let { put("transmit_power", JsonPrimitive(it)) }
                },
            )
        command("set_radar_config", payload)
    }

    override suspend fun toggleDebug() = command("toggle_debug")

    override suspend fun uploadCloud() {
        val socket = connectedSocket()
        // Like the web UI: show "running" at once; the server confirms with the same state.
        mutableCloudUploadStatus.value = CloudUploadStatus(CloudUploadState.RUNNING, "Uploading...")
        send(socket, "upload_cloud", null)
    }

    override suspend fun shutdown() = command("shutdown")

    override fun cameraFrames(): Flow<ByteArray> =
        flow {
            val key = currentKey
            if (key?.type == TransportType.BLUETOOTH) throw WifiOnlyFeatureException(Reason.BLUETOOTH)
            val host = key?.host ?: throw WifiOnlyFeatureException(Reason.NOT_CONNECTED)
            emitAll(cameraSource.frames(host))
        }

    private suspend fun command(
        event: String,
        data: JsonElement? = null,
    ) = send(connectedSocket(), event, data)

    private fun connectedSocket(): PiSocket {
        if (currentKey?.type == TransportType.BLUETOOTH) throw WifiOnlyFeatureException(Reason.BLUETOOTH)
        val socket = activeSocket.value
        if (socket == null || socket.state.value !is SocketConnectionState.Connected) {
            throw WifiOnlyFeatureException(Reason.NOT_CONNECTED)
        }
        return socket
    }

    private suspend fun send(
        socket: PiSocket,
        event: String,
        data: JsonElement?,
    ) {
        try {
            socket.emit(event, data)
        } catch (_: SocketNotConnectedException) {
            throw WifiOnlyFeatureException(Reason.NOT_CONNECTED)
        }
    }

    /** One `(transport, host)` lifetime; cancelled by the next key or [stop]. */
    private suspend fun runSession(key: SessionKey) {
        val previous = currentKey
        currentKey = key
        if (previous != null && previous != key) clearSessionData()
        if (key.type == TransportType.BLUETOOTH) {
            mutableLinkState.value = PiLinkState.WifiOnly
            awaitCancellation()
        }
        coroutineScope {
            val socket = socketFactory.create(key.host.orEmpty(), this)
            if (socket == null) {
                mutableLinkState.value = PiLinkState.Idle
                awaitCancellation()
            }
            activeSocket.value = socket
            try {
                launch(start = CoroutineStart.UNDISPATCHED) { socket.events.collect(::handle) }
                launch(start = CoroutineStart.UNDISPATCHED) { followState(socket) }
                socket.connect()
                awaitCancellation()
            } finally {
                activeSocket.value = null
                socket.disconnect()
                mutableLinkState.value = PiLinkState.Idle
            }
        }
    }

    private suspend fun followState(socket: PiSocket): Nothing =
        coroutineScope {
            var wasConnected = false
            socket.state.collect { state ->
                mutableLinkState.value = state.toLinkState()
                val connected = state is SocketConnectionState.Connected
                // Like socketService.ts on 'connect': pull the session, trigger status and radar config.
                if (connected && !wasConnected) launch { requestInitialState(socket) }
                wasConnected = connected
            }
        }

    @Suppress("TooGenericExceptionCaught") // A failed request is logged; the next connect retries it.
    private suspend fun requestInitialState(socket: PiSocket) {
        for (event in INITIAL_REQUESTS) {
            try {
                socket.emit(event)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log("Initial $event failed: ${error.message ?: error}")
                return
            }
        }
    }

    private suspend fun handle(event: SocketEvent) {
        val decoded =
            try {
                decodePiEvent(event)
            } catch (error: IllegalArgumentException) {
                log("Dropped malformed '${event.name}': ${error.message}")
                null
            } ?: return
        apply(decoded)
    }

    private suspend fun apply(event: PiEvent) {
        when (event) {
            is PiEvent.Notice -> mutableNotices.emit(event.notice)

            is PiEvent.Shot, is PiEvent.SwingSpeed, is PiEvent.Session, PiEvent.SessionCleared,
            is PiEvent.PlayerChanged, is PiEvent.TrainingImplementChanged,
            -> applySessionEvent(event)

            is PiEvent.Debug, is PiEvent.DebugReadingReceived, is PiEvent.DebugShotReceived,
            is PiEvent.Diagnostic, is PiEvent.Trigger, is PiEvent.Radar,
            -> applyDebugEvent(event)

            else -> applyDeviceEvent(event)
        }
    }

    private fun applySessionEvent(event: PiEvent) {
        when (event) {
            is PiEvent.Shot -> {
                onShot(event.detail, event.stats)
            }

            is PiEvent.SwingSpeed -> {
                // The rep also arrives as a `shot`; this only tracks the latest reading.
                mutableLatestSwingSpeed.value = event.reading
                event.stats?.let { mutableStats.value = it }
            }

            is PiEvent.Session -> {
                onSessionState(event.state)
            }

            PiEvent.SessionCleared -> {
                mutableSessionShots.value = emptyList()
                mutableStats.value = SessionStats.EMPTY
            }

            is PiEvent.PlayerChanged -> {
                mutablePlayerName.value = event.playerName
            }

            is PiEvent.TrainingImplementChanged -> {
                mutableTrainingImplement.value = event.implement
            }

            else -> {
                Unit
            }
        }
    }

    private fun applyDebugEvent(event: PiEvent) {
        when (event) {
            is PiEvent.Debug -> {
                onDebugToggled(event.enabled, event.logPath)
            }

            is PiEvent.DebugReadingReceived -> {
                mutableDebugState.update {
                    it.copy(
                        readings = (it.readings + event.reading).takeLast(DebugState.MAX_READINGS),
                    )
                }
            }

            is PiEvent.DebugShotReceived -> {
                mutableDebugState.update {
                    it.copy(
                        shotLogs = (it.shotLogs + event.log).takeLast(DebugState.MAX_SHOT_LOGS),
                    )
                }
            }

            is PiEvent.Diagnostic -> {
                onDiagnostic(event)
            }

            is PiEvent.Trigger -> {
                mutableTriggerStatus.value = event.status
            }

            is PiEvent.Radar -> {
                mutableRadarConfig.value = event.config
            }

            else -> {
                Unit
            }
        }
    }

    private fun applyDeviceEvent(event: PiEvent) {
        when (event) {
            is PiEvent.Camera -> {
                mutableCameraStatus.update { it.merge(event.update) }
            }

            is PiEvent.Ball -> {
                mutableCameraStatus.update {
                    it.copy(ballDetected = event.detection.detected, ballConfidence = event.detection.confidence)
                }
            }

            is PiEvent.Sim -> {
                mutableSimState.update { it.copy(connectors = it.connectors + (event.status.target to event.status)) }
            }

            is PiEvent.SimShotSent -> {
                mutableSimState.update { it.copy(latestShot = event.shot) }
            }

            is PiEvent.SimPlayerChanged -> {
                mutableSimState.update { it.copy(latestPlayer = event.player) }
            }

            is PiEvent.Cloud -> {
                mutableCloudUploadStatus.value = event.status
            }

            else -> {
                Unit
            }
        }
    }

    private fun onShot(
        detail: ShotDetail,
        stats: SessionStats?,
    ) {
        mutableSessionShots.update { shots ->
            (
                listOf(
                    detail,
                ) + shots.filterNot { it.timestamp == detail.timestamp }
            ).take(PiSessionRepository.MAX_SESSION_SHOTS)
        }
        remember(listOf(detail))
        stats?.let { mutableStats.value = it }
    }

    private fun onSessionState(state: SessionState) {
        // The server lists the session oldest first; the app shows newest first.
        mutableSessionShots.value = state.shots.asReversed().take(PiSessionRepository.MAX_SESSION_SHOTS)
        remember(state.shots)
        mutableStats.value = state.stats
        state.playerName?.let { mutablePlayerName.value = it }
        state.mockMode?.let { mutableMockMode.value = it }
        state.debugMode?.let { enabled -> mutableDebugState.update { it.copy(enabled = enabled) } }
        // Like the web UI, the camera flags apply only when the on-connect variant carries them.
        state.cameraAvailable?.let { available ->
            mutableCameraStatus.update {
                it.copy(
                    available = available,
                    enabled = state.cameraEnabled ?: false,
                    streaming = state.cameraStreaming ?: false,
                    ballDetected = state.ballDetected ?: false,
                )
            }
        }
    }

    private fun onDiagnostic(event: PiEvent.Diagnostic) {
        val accepted = event.diagnostic.accepted
        mutableDebugState.update {
            it.copy(
                triggerDiagnostics =
                    (it.triggerDiagnostics + event.diagnostic).takeLast(
                        DebugState.MAX_TRIGGER_DIAGNOSTICS,
                    ),
            )
        }
        // useDebugStore.updateTriggerStatusStats: count it before the next trigger_status arrives.
        mutableTriggerStatus.update { status ->
            status?.copy(
                triggersTotal = status.triggersTotal + 1,
                triggersAccepted = status.triggersAccepted + if (accepted) 1 else 0,
                triggersRejected = status.triggersRejected + if (accepted) 0 else 1,
            )
        }
    }

    private fun onDebugToggled(
        enabled: Boolean,
        logPath: String?,
    ) {
        mutableDebugState.update {
            // useDebugStore.clearDebugData on disable: readings and shot logs, not diagnostics.
            if (enabled) {
                it.copy(enabled = true, logPath = logPath)
            } else {
                it.copy(enabled = false, logPath = logPath, readings = emptyList(), shotLogs = emptyList())
            }
        }
    }

    /** Adds [details] to the enrichment index, most recent last, keeping [PiSessionRepository.MAX_SESSION_SHOTS]. */
    private fun remember(details: List<ShotDetail>) {
        if (details.isEmpty()) return
        mutableShotDetails.update { current ->
            val merged = LinkedHashMap(current)
            for (detail in details) {
                merged.remove(detail.timestamp)
                merged[detail.timestamp] = detail
            }
            val overflow = merged.size - PiSessionRepository.MAX_SESSION_SHOTS
            if (overflow > 0) merged.keys.take(overflow).forEach(merged::remove)
            merged
        }
    }

    private fun clearSessionData() {
        mutableSessionShots.value = emptyList()
        mutableShotDetails.value = emptyMap()
        mutableStats.value = null
        mutablePlayerName.value = null
        mutableTrainingImplement.value = null
        mutableLatestSwingSpeed.value = null
        mutableTriggerStatus.value = null
        mutableCameraStatus.value = CameraStatus()
        mutableSimState.value = SimState()
        mutableRadarConfig.value = null
        mutableDebugState.value = DebugState()
        mutableCloudUploadStatus.value = CloudUploadStatus()
        mutableMockMode.value = null
    }

    private data class SessionKey(
        val type: TransportType,
        /** `null` on Bluetooth, so a host edit there changes nothing. */
        val host: String?,
    )

    private companion object {
        const val NOTICE_BUFFER = 16
        val INITIAL_REQUESTS = listOf("get_session", "get_trigger_status", "get_radar_config")
    }
}

private fun SocketConnectionState.toLinkState(): PiLinkState =
    when (this) {
        SocketConnectionState.Idle -> PiLinkState.Idle
        is SocketConnectionState.Connecting -> PiLinkState.Connecting
        is SocketConnectionState.Connected -> PiLinkState.Connected
        is SocketConnectionState.Reconnecting -> PiLinkState.Reconnecting(attempt, retryInMillis, reason)
    }

private fun CameraStatus.merge(update: CameraStatusPayload): CameraStatus =
    copy(
        available = update.available ?: available,
        enabled = update.enabled ?: enabled,
        streaming = update.streaming ?: streaming,
        ballDetected = update.ballDetected ?: ballDetected,
        ballConfidence = update.ballConfidence ?: ballConfidence,
        error = update.error,
    )
