// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.ble.BleControlException
import dev.openflight.companion.core.data.WifiOnlyFeatureException.Reason
import dev.openflight.companion.core.model.pi.CameraCaptureSettings
import dev.openflight.companion.core.model.pi.CameraPreview
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.CloudUploadState
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.PowerStatus
import dev.openflight.companion.core.model.pi.ProfileNameCheck
import dev.openflight.companion.core.model.pi.ProfileRules
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.RadarConfigUpdate
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.ShotProcessingState
import dev.openflight.companion.core.model.pi.SimState
import dev.openflight.companion.core.model.pi.SwingSpeedReading
import dev.openflight.companion.core.model.pi.TrainingImplement
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.network.EndpointPolicy
import dev.openflight.companion.core.protocol.SchemaV2Commands
import dev.openflight.companion.core.protocol.ShotTransport
import dev.openflight.companion.core.socketio.SocketConnectionState
import dev.openflight.companion.core.socketio.SocketEvent
import dev.openflight.companion.core.socketio.SocketNotConnectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * to that Pi); [stop] and a transient drop keep them, so returning shows the last state until the
 * reconnect's snapshots replace it. Any request still waiting for a reply (a deletion, a clear)
 * fails as soon as the link leaves `Connected`, since the reply will never come.
 *
 * Event handling lives in [PiSessionStore] and mirrors the Expo app's `socket.ts` and stores
 * (plan §9.2).
 *
 * Plan R8e: on Bluetooth it mirrors [bluetooth]'s schema v2 events (and v2 shots, for [detailFor])
 * into the same store, and sends `set_active_profile` over it.
 */
@Suppress("TooManyFunctions") // The PiSessionRepository surface plus the connection loop.
internal class DefaultPiSessionRepository(
    private val settings: SettingsRepository,
    private val socketFactory: PiSocketFactory,
    private val cameraSource: PiCameraSource,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val bluetooth: ShotTransport? = null,
) : PiSessionRepository {
    private val mutableLinkState = MutableStateFlow<PiLinkState>(PiLinkState.Idle)
    private val mutableBluetoothSchemaV2 = MutableStateFlow(false)
    private val store = PiSessionStore(resync = ::resync)

    override val linkState: StateFlow<PiLinkState> = mutableLinkState.asStateFlow()
    override val bluetoothSchemaV2: StateFlow<Boolean> = mutableBluetoothSchemaV2.asStateFlow()
    override val sessionShots: StateFlow<List<ShotDetail>> = store.sessionShots.asStateFlow()
    override val shotDetails: StateFlow<Map<String, ShotDetail>> = store.shotDetails.asStateFlow()
    override val stats: StateFlow<SessionStats?> = store.stats.asStateFlow()
    override val profiles: StateFlow<ProfilesState> = store.profiles.asStateFlow()
    override val club: StateFlow<String?> = store.club.asStateFlow()
    override val shotProcessing: StateFlow<ShotProcessingState?> = store.shotProcessing.asStateFlow()
    override val powerStatus: StateFlow<PowerStatus?> = store.powerStatus.asStateFlow()
    override val deletionState: StateFlow<DeletionState> = store.deletion.asStateFlow()
    override val clearState: StateFlow<ClearState> = store.clear.asStateFlow()
    override val trainingImplement: StateFlow<TrainingImplement?> = store.trainingImplement.asStateFlow()
    override val latestSwingSpeed: StateFlow<SwingSpeedReading?> = store.latestSwingSpeed.asStateFlow()
    override val triggerStatus: StateFlow<TriggerStatus?> = store.triggerStatus.asStateFlow()
    override val cameraCaptureSettings: StateFlow<CameraCaptureSettings?> = store.cameraCaptureSettings.asStateFlow()
    override val simState: StateFlow<SimState> = store.simState.asStateFlow()
    override val radarConfig: StateFlow<RadarConfig?> = store.radarConfig.asStateFlow()
    override val debugState: StateFlow<DebugState> = store.debugState.asStateFlow()
    override val cloudUploadStatus: StateFlow<CloudUploadStatus> = store.cloudUploadStatus.asStateFlow()
    override val mockMode: StateFlow<Boolean?> = store.mockMode.asStateFlow()
    override val notices: SharedFlow<PiNotice> = store.notices.asSharedFlow()
    override val liveShots: Flow<PiLiveShot> = store.liveShots.asSharedFlow()

    private val activeSocket = MutableStateFlow<PiSocket?>(null)
    private var currentKey: SessionKey? = null
    private var sessionJob: Job? = null
    private var clearTimeout: Job? = null

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

    override suspend fun deleteShot(timestamp: String) {
        val socket = connectedSocket()
        // Neither reply names its shot, so a second request would make the first one's ambiguous.
        if (store.deletion.value is DeletionState.Pending) return
        val previous = store.deletion.value
        store.deletion.value = previous.begin(timestamp)
        try {
            send(socket, "delete_shot", buildJsonObject { put("timestamp", timestamp) })
        } catch (failure: WifiOnlyFeatureException) {
            store.deletion.value = previous
            throw failure
        }
    }

    override fun dismissDeletion() {
        store.deletion.value = DeletionState.Idle
    }

    override suspend fun clearSession(profileId: String) {
        require(profileId.isNotBlank()) { "clear_session needs a profile id" }
        val socket = connectedSocket()
        if (store.clear.value is ClearState.Pending) return
        val previous = store.clear.value
        store.clear.value = ClearState.Pending(profileId)
        try {
            send(socket, "clear_session", buildJsonObject { put("profile_id", profileId) })
        } catch (failure: WifiOnlyFeatureException) {
            store.clear.value = previous
            throw failure
        }
        clearTimeout?.cancel()
        clearTimeout =
            scope.launch {
                delay(ClearState.TIMEOUT_MILLIS)
                store.clearTimedOut(profileId)
            }
    }

    override fun dismissClear() {
        store.clear.value = ClearState.Idle
    }

    override suspend fun setActiveProfile(profileId: String) {
        if (currentKey?.type == TransportType.BLUETOOTH) {
            setActiveProfileOverBluetooth(profileId)
        } else {
            command("set_active_profile", buildJsonObject { put("profile_id", profileId) })
        }
    }

    /**
     * BLE allows one command in flight; the club/profile/power sync that runs on connect may hold
     * it briefly, so a `busy` answer is retried a few times before it reaches the caller.
     */
    private suspend fun setActiveProfileOverBluetooth(profileId: String) {
        val v2 = (bluetooth as? SchemaV2Commands)?.takeIf { it.schemaV2Active.value }
        if (v2 == null) throw WifiOnlyFeatureException(Reason.BLUETOOTH)
        var attempt = 0
        while (true) {
            try {
                v2.setActiveProfile(profileId)
                return
            } catch (busy: BleControlException.Busy) {
                if (++attempt >= BUSY_RETRIES) throw busy
                delay(BUSY_RETRY_DELAY_MILLIS)
            }
        }
    }

    override suspend fun addProfile(name: String) {
        val valid = validName(name)
        if (!store.profiles.value.canAdd) throw ProfileRuleException(ProfileRuleException.Rule.TOO_MANY_PROFILES)
        command("add_profile", buildJsonObject { put("name", valid) })
    }

    override suspend fun renameProfile(
        profileId: String,
        name: String,
    ) {
        val valid = validName(name)
        command(
            "rename_profile",
            buildJsonObject {
                put("profile_id", profileId)
                put("name", valid)
            },
        )
    }

    override suspend fun removeProfile(profileId: String) =
        command("remove_profile", buildJsonObject { put("profile_id", profileId) })

    override suspend fun simulateShot() = command("simulate_shot")

    override suspend fun setTrainingImplement(implement: String) =
        command("set_training_implement", buildJsonObject { put("implement", implement) })

    override suspend fun refreshCameraCaptureSettings() = command("get_camera_capture_settings")

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
        store.cloudUploadStatus.value = CloudUploadStatus(CloudUploadState.RUNNING, "Uploading...")
        send(socket, "upload_cloud", null)
    }

    override suspend fun shutdown() = command("shutdown")

    override suspend fun cameraPreview(): CameraPreview = cameraSource.preview(wifiHost())

    override suspend fun prepareReplay(replayId: String): String {
        val host = wifiHost()
        val replay = cameraSource.prepareReplay(host, replayId)
        return checkNotNull(cameraSource.videoUrl(host, replay)) { "The Pi returned no video for this replay." }
    }

    /** The Wi-Fi host the camera's HTTP API lives on. */
    private fun wifiHost(): String {
        val key = currentKey
        if (key?.type == TransportType.BLUETOOTH) throw WifiOnlyFeatureException(Reason.BLUETOOTH)
        return key?.host?.takeIf { it.isNotBlank() } ?: throw WifiOnlyFeatureException(Reason.NOT_CONNECTED)
    }

    private fun validName(name: String): String =
        when (val check = ProfileRules.checkName(name)) {
            is ProfileNameCheck.Valid -> check.name
            ProfileNameCheck.Blank -> throw ProfileRuleException(ProfileRuleException.Rule.BLANK_NAME)
            ProfileNameCheck.TooLong -> throw ProfileRuleException(ProfileRuleException.Rule.NAME_TOO_LONG)
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

    /** `get_session` for a `session_cleared` without readable rows; a failure waits for the next connect. */
    private fun resync() {
        val socket = activeSocket.value ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) { emitQuietly(socket, "get_session") }
    }

    /** One `(transport, host)` lifetime; cancelled by the next key or [stop]. */
    private suspend fun runSession(key: SessionKey) {
        val previous = currentKey
        currentKey = key
        if (previous != null && previous != key) store.reset()
        if (key.type == TransportType.BLUETOOTH) {
            mutableLinkState.value = PiLinkState.WifiOnly
            followBluetooth()
        }
        coroutineScope {
            val socket = socketFactory.create(key.host.orEmpty(), this)
            if (socket == null) {
                // Plan R8d: the endpoint policy refused the host, so nothing was opened; say why.
                val host = key.host.orEmpty()
                mutableLinkState.value = PiLinkState.Rejected(EndpointPolicy.rejectionReason(host) ?: UNUSABLE_HOST)
                awaitCancellation()
            }
            activeSocket.value = socket
            try {
                launch(start = CoroutineStart.UNDISPATCHED) { socket.events.collect(::handle) }
                launch(start = CoroutineStart.UNDISPATCHED) { followState(socket, key.host.orEmpty()) }
                socket.connect()
                awaitCancellation()
            } finally {
                activeSocket.value = null
                socket.disconnect()
                store.linkLost()
                mutableLinkState.value = PiLinkState.Idle
            }
        }
    }

    /** Plan R8e: mirrors a schema v2 BLE link into the store until cancelled. */
    private suspend fun followBluetooth(): Nothing {
        val link = bluetooth ?: awaitCancellation()
        try {
            coroutineScope {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    link.schemaEvents.collect { store.apply(it.toPiEvent()) }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    link.shots.collect { shot -> shot.toShotDetail()?.let { store.apply(PiEvent.BluetoothShot(it)) } }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    // The Pi's club, as the Socket.IO path reports it (a wire value).
                    link.activeClub.collect { club -> club?.let { store.club.value = it.wireValue } }
                }
                (link as? SchemaV2Commands)?.let { v2 ->
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        v2.schemaV2Active.collect { mutableBluetoothSchemaV2.value = it }
                    }
                }
                awaitCancellation()
            }
        } finally {
            mutableBluetoothSchemaV2.value = false
        }
    }

    private suspend fun followState(
        socket: PiSocket,
        host: String,
    ): Nothing =
        coroutineScope {
            var wasConnected = false
            socket.state.collect { state ->
                mutableLinkState.value = state.toLinkState()
                val connected = state is SocketConnectionState.Connected
                if (connected && !wasConnected) {
                    // Plan R8d (Expo socket.ts): only a host that connected is saved for the next launch.
                    launch { settings.rememberConnectedHost(host) }
                    // Like socket.ts on 'connect': re-sync everything the server doesn't push.
                    launch { requestInitialState(socket) }
                }
                if (!connected) store.linkLost()
                wasConnected = connected
            }
        }

    private suspend fun requestInitialState(socket: PiSocket) {
        for (event in INITIAL_REQUESTS) {
            if (!emitQuietly(socket, event)) return
        }
    }

    /** Emits a read-only request; a failure is logged (the next connect retries). */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun emitQuietly(
        socket: PiSocket,
        event: String,
    ): Boolean =
        try {
            socket.emit(event)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log("Request $event failed: ${error.message ?: error}")
            false
        }

    private suspend fun handle(event: SocketEvent) {
        val decoded =
            try {
                decodePiEvent(event)
            } catch (error: IllegalArgumentException) {
                log("Dropped malformed '${event.name}': ${error.message}")
                null
            } ?: return
        store.apply(decoded)
        // A settled clear (confirmed, or failed on a drop) needs no timeout any more.
        if (store.clear.value !is ClearState.Pending) {
            clearTimeout?.cancel()
            clearTimeout = null
        }
    }

    private data class SessionKey(
        val type: TransportType,
        /** `null` on Bluetooth, so a host edit there changes nothing. */
        val host: String?,
    )

    private companion object {
        const val UNUSABLE_HOST = "This address can't be used."
        const val BUSY_RETRIES = 5
        const val BUSY_RETRY_DELAY_MILLIS = 300L

        /**
         * Requested on every (re)connect. The server pushes `profiles`, `session_state` and
         * `trigger_status` on connect but not `radar_config` or the debug state, and a phone
         * joining a running session can't rely on having seen the pushes (plan §9.1, §9.2).
         */
        val INITIAL_REQUESTS =
            listOf(
                "get_session",
                "get_trigger_status",
                "get_radar_config",
                "get_debug_status",
                "get_profiles",
                "get_camera_capture_settings",
            )
    }
}

private fun SocketConnectionState.toLinkState(): PiLinkState =
    when (this) {
        SocketConnectionState.Idle -> {
            PiLinkState.Idle
        }

        is SocketConnectionState.Connecting -> {
            PiLinkState.Connecting
        }

        is SocketConnectionState.Connected -> {
            PiLinkState.Connected
        }

        is SocketConnectionState.Reconnecting -> {
            PiLinkState.Reconnecting(
                attempt,
                retryInMillis,
                reason,
                localNetworkDenied,
            )
        }
    }
