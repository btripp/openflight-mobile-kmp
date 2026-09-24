// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.ShotHistory
import dev.openflight.companion.core.protocol.ShotTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Builds a Wi-Fi transport bound to one host; a host change builds a new one (plan §0.2). */
internal fun interface WifiTransportFactory {
    fun create(host: String): ShotTransport
}

/**
 * [ShotRepository] over one Bluetooth transport (a single long-lived instance) and a Wi-Fi
 * transport per host.
 *
 * While started, it follows `(transport, host)` from [settings] with `flatMapLatest`: a change
 * cancels the current session, which disconnects the old transport, and only then starts the new
 * one. The host is ignored for Bluetooth, so editing it while on Bluetooth reconnects nothing.
 *
 * Club rules (plan §0.3, Step 6 task 3; ContentView.swift:107-121, 309-353):
 * - A `club_changed` from the Pi is persisted as the selected club.
 * - [setClub] persists only after the Pi confirms, using the club in its response.
 * - Once per connection, when the state becomes [ConnectionState.Connected] (and for Bluetooth,
 *   once [ShotTransport.supportsControls] is true), it reads the Pi's club and persists it. A
 *   failure is logged and leaves the connection state alone.
 * - Control calls are serialized, because the BLE transport rejects a second in-flight command
 *   with `busy` and the sync-on-connect must not race a user's club change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // The ShotRepository surface (9) plus the session helpers.
internal class DefaultShotRepository(
    private val settings: SettingsRepository,
    private val bluetoothTransport: ShotTransport,
    private val wifiTransportFactory: WifiTransportFactory,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : ShotRepository {
    private val mutableConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val mutableHistory = MutableStateFlow(emptyList<ShotEvent>())
    private val mutableLatestShot = MutableStateFlow<ShotEvent?>(null)
    private val mutableActiveClub = MutableStateFlow<GolfClub?>(null)
    private val mutableSupportsControls = MutableStateFlow(false)

    override val connectionState: StateFlow<ConnectionState> = mutableConnectionState.asStateFlow()
    override val history: StateFlow<List<ShotEvent>> = mutableHistory.asStateFlow()
    override val latestShot: StateFlow<ShotEvent?> = mutableLatestShot.asStateFlow()
    override val activeClub: StateFlow<GolfClub?> = mutableActiveClub.asStateFlow()
    override val supportsControls: StateFlow<Boolean> = mutableSupportsControls.asStateFlow()

    // Only the current session's shot collector writes this, and sessions never overlap.
    private var shotHistory = ShotHistory()
    private val activeTransport = MutableStateFlow<ShotTransport?>(null)
    private val controlMutex = Mutex()
    private var sessionJob: Job? = null

    override fun start() {
        if (sessionJob?.isActive == true) return
        sessionJob =
            scope.launch {
                combine(settings.transport, settings.host) { type, host ->
                    TransportKey(type, host.takeIf { type == TransportType.WIFI })
                }.distinctUntilChanged()
                    .flatMapLatest(::session)
                    .collect()
            }
    }

    override fun stop() {
        sessionJob?.cancel()
        sessionJob = null
    }

    override fun retry() {
        val transport = activeTransport.value
        if (transport == null) start() else transport.retry()
    }

    override fun disconnect() {
        activeTransport.value?.disconnect()
    }

    override suspend fun setClub(club: GolfClub): ClubSelection {
        val transport = activeTransport.value ?: throw NoActiveTransportException()
        val selection = controlMutex.withLock { transport.setClub(club) }
        settings.setSelectedClub(selection.club)
        return selection
    }

    override suspend fun currentClub(): ClubSelection {
        val transport = activeTransport.value ?: throw NoActiveTransportException()
        return readAndPersistClub(transport)
    }

    override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult {
        val transport = activeTransport.value ?: throw NoActiveTransportException()
        return controlMutex.withLock { transport.submitCalibration(measurement) }
    }

    /** One transport's lifetime: mirror its flows until cancelled, then disconnect it. */
    private fun session(key: TransportKey): Flow<Nothing> =
        flow {
            val transport =
                when (key.type) {
                    TransportType.BLUETOOTH -> bluetoothTransport
                    TransportType.WIFI -> wifiTransportFactory.create(key.host.orEmpty())
                }
            activeTransport.value = transport
            try {
                coroutineScope {
                    // Subscribe before start() so no shot or state change is missed.
                    launch(start = CoroutineStart.UNDISPATCHED) { transport.shots.collect { record(it) } }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        transport.state.collect { mutableConnectionState.value = it }
                    }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        transport.supportsControls.collect { mutableSupportsControls.value = it }
                    }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        transport.activeClub.collect { club ->
                            mutableActiveClub.value = club
                            if (club != null) settings.setSelectedClub(club)
                        }
                    }
                    launch(start = CoroutineStart.UNDISPATCHED) { syncClubOnConnect(transport, key.type) }
                    transport.start()
                }
            } finally {
                transport.disconnect()
                activeTransport.value = null
                mutableConnectionState.value = ConnectionState.Idle
                mutableSupportsControls.value = false
                mutableActiveClub.value = null
            }
        }

    private fun record(shot: ShotEvent) {
        val updated = shotHistory.record(shot)
        if (updated === shotHistory) return
        shotHistory = updated
        mutableHistory.value = updated.shots
        mutableLatestShot.value = updated.latestShot
    }

    private suspend fun syncClubOnConnect(
        transport: ShotTransport,
        type: TransportType,
    ) = coroutineScope {
        var syncedThisConnection = false
        combine(transport.state, transport.supportsControls, ::Pair).collect { (state, controls) ->
            when {
                state != ConnectionState.Connected -> {
                    syncedThisConnection = false
                }

                syncedThisConnection -> {
                    Unit
                }

                type == TransportType.BLUETOOTH && !controls -> {
                    Unit
                }

                else -> {
                    syncedThisConnection = true
                    launch { syncClub(transport) }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any sync failure is logged, never surfaced as a connection error.
    private suspend fun syncClub(transport: ShotTransport) {
        try {
            readAndPersistClub(transport)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log("Club sync on connect failed: ${error.message ?: error}")
        }
    }

    private suspend fun readAndPersistClub(transport: ShotTransport): ClubSelection {
        val selection = controlMutex.withLock { transport.currentClub() }
        settings.setSelectedClub(selection.club)
        return selection
    }

    private data class TransportKey(
        val type: TransportType,
        /** `null` for Bluetooth, so a host edit doesn't restart the Bluetooth session. */
        val host: String?,
    )
}
