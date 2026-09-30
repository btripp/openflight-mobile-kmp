// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionErrorKind
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.ShotHistory
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.network.OpenFlightHttpError
import dev.openflight.companion.core.network.PiControlClient
import dev.openflight.companion.core.protocol.SchemaV2Commands
import dev.openflight.companion.core.protocol.SchemaV2Event
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
 *
 * Plan R6b: [start]/[stop] also start and stop [piSession] (it follows the same settings and is
 * active only on Wi-Fi, on the same host).
 *
 * Plan R8c: while the Pi's link is connected, [deleteShot]/[deleteShotByTimestamp] and
 * [clearHistory] are **server-confirmed**: the request goes to [piSession] and [history] changes
 * only once its [PiSessionRepository.deletionState] / [PiSessionRepository.clearState] reports
 * success. Without a Pi link they stay local (optimistic) edits.
 *
 * Plan R8h: [history] is the current session's cache; every shot is also written through to
 * [persistentHistory] (SSE/BLE shots with their Socket.IO detail when known, plus the Pi's live
 * `shot`/`shot_update`), each connect of the transport or of the Pi's Socket.IO link starts a
 * history session, and deletes and per-profile clears
 * are mirrored there once they take effect here. A local-only [clearHistory] leaves the stored
 * history alone ("clear all history" is [ShotHistoryRepository.clearAll]).
 *
 * Plan R8e (schema v2, BLE or SSE `?schema=2`): a final shot replaces its provisional version in
 * [history] (same `event_id`), `shot_deleted` removes the shot with that timestamp, and
 * `session_cleared` removes that profile's shots, whoever deleted or cleared them; both are
 * mirrored into [persistentHistory] too. After the club sync on connect, a v2 BLE link also asks
 * for the profile roster and the power status (failures are logged: a Pi without `--battery`
 * refuses the latter).
 *
 * Plan R8j (stock upstream Pi: no `/api/shots/stream`, no `/api/club`): when the Wi-Fi stream
 * answers 404 ([ConnectionErrorKind.STREAM_UNAVAILABLE]; the transport then stops probing until a
 * retry, a host change or the next start) and the Pi's Socket.IO link is connected, the Pi's live
 * `shot`/`shot_update` feed [history] and [latestShot] (stored once, by the [PiSessionRepository.liveShots]
 * collector), [connectionState] shows the link's state instead of the 404, and the club follows the
 * Pi's `session_state`/`club_changed`. A club call that gets 404/405 from `/api/club` switches to
 * Socket.IO `set_club` for the rest of the connection. With SSE and `/api/club` present (fork
 * backend) nothing changes: SSE feeds [history] and Socket.IO only enriches, so no shot is doubled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // The ShotRepository surface (9) plus the session helpers.
internal class DefaultShotRepository(
    private val settings: SettingsRepository,
    private val bluetoothTransport: ShotTransport,
    private val wifiTransportFactory: WifiTransportFactory,
    private val scope: CoroutineScope,
    private val piControl: PiControlClient,
    private val piSession: PiSessionRepository? = null,
    private val log: (String) -> Unit = {},
    private val persistentHistory: ShotHistoryRepository? = null,
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

    /**
     * The session cache behind [history] and [latestShot]. The shot collector, local deletes and
     * the Pi's confirmations change it from different threads, so every change goes through
     * [changeHistory], an atomic compare-and-set (#73).
     */
    private val shotHistory = MutableStateFlow(ShotHistory())
    private val activeTransport = MutableStateFlow<ShotTransport?>(null)

    /** The active transport's kind, so [shutdownPi] can refuse to run over Bluetooth. */
    private val activeTransportType = MutableStateFlow<TransportType?>(null)

    /** The active transport's own state; [connectionState] may show the Pi link's instead (R8j). */
    private val transportState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    /** R8j: this connection's `/api/club` answered 404, so club calls go over Socket.IO. */
    private val clubApiAbsent = MutableStateFlow(false)

    private val mutableLiveShotSource = MutableStateFlow(LiveShotSource.NONE)

    /** Plan R8j: where [history]'s live shots come from right now (for tests and MockServerIT). */
    internal val liveShotSource: StateFlow<LiveShotSource> = mutableLiveShotSource.asStateFlow()
    private val controlMutex = Mutex()
    private var sessionJob: Job? = null

    override fun start() {
        if (sessionJob?.isActive == true) return
        sessionJob =
            scope.launch {
                val pi = piSession
                val history = persistentHistory
                if (pi != null && history != null) {
                    launch { pi.liveShots.collect(history::record) }
                    // A current Pi (backend main) has no SSE stream, so on Wi-Fi its Socket.IO link
                    // is the connection that counts: each (re)connect of it starts a session too.
                    // An extra start before any shot is harmless (a session is stored with its first shot).
                    launch {
                        pi.linkState
                            .map { it == PiLinkState.Connected }
                            .distinctUntilChanged()
                            .collect { connected ->
                                if (connected) history.startSession(settings.host.first(), TransportType.WIFI)
                            }
                    }
                }
                combine(settings.transport, settings.host) { type, host ->
                    TransportKey(type, host.takeIf { type == TransportType.WIFI })
                }.distinctUntilChanged()
                    .flatMapLatest(::session)
                    .collect()
            }
        piSession?.start()
    }

    override fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        piSession?.stop()
    }

    override fun retry() {
        val transport = activeTransport.value
        if (transport == null) start() else transport.retry()
        // Issue #6: the Socket.IO link retries too, or it waits out a stuck attempt and its backoff.
        piSession?.retry()
    }

    override fun disconnect() {
        activeTransport.value?.disconnect()
    }

    override suspend fun setClub(club: GolfClub): ClubSelection {
        val transport = activeTransport.value ?: throw NoActiveTransportException()
        val selection =
            controlMutex.withLock {
                val pi = clubFallbackPi()
                if (pi != null && clubApiAbsent.value) {
                    setClubOverSocket(pi, club)
                } else {
                    clubApiOrFallback(pi, { transport.setClub(club) }) { setClubOverSocket(it, club) }
                }
            }
        settings.setSelectedClub(selection.club)
        return selection
    }

    override suspend fun currentClub(): ClubSelection {
        val transport = activeTransport.value ?: throw NoActiveTransportException()
        return readAndPersistClub(transport)
    }

    /** The Pi session a Wi-Fi club change can fall back to (plan R8j), or `null` on Bluetooth. */
    private fun clubFallbackPi(): PiSessionRepository? =
        piSession?.takeIf {
            activeTransportType.value ==
                TransportType.WIFI
        }

    /**
     * Runs [request] against `/api/club`; when that route is missing and a Pi session exists,
     * remembers that for this connection and runs [fallback] instead (a stock backend answers 404
     * to `GET` but 405 to `POST`: see [OpenFlightHttpError.UnexpectedStatus.isRouteAbsent]).
     */
    private suspend fun clubApiOrFallback(
        pi: PiSessionRepository?,
        request: suspend () -> ClubSelection,
        fallback: suspend (PiSessionRepository) -> ClubSelection,
    ): ClubSelection =
        try {
            request()
        } catch (missing: OpenFlightHttpError.UnexpectedStatus) {
            if (pi == null || !missing.isRouteAbsent) throw missing
            log("No /api/club on this Pi (HTTP ${missing.statusCode}): using Socket.IO for the club")
            clubApiAbsent.value = true
            fallback(pi)
        }

    /**
     * Plan R8j: `set_club` over Socket.IO, confirmed by the Pi's `club_changed` broadcast (the
     * server ignores an unknown club without replying, hence the timeout).
     */
    private suspend fun setClubOverSocket(
        pi: PiSessionRepository,
        club: GolfClub,
    ): ClubSelection {
        pi.setClub(club.wireValue)
        val confirmed =
            withTimeoutOrNull(CLUB_CONFIRMATION_TIMEOUT_MILLIS) { pi.club.first { it == club.wireValue } }
                ?: throw ClubChangeNotConfirmedException()
        return ClubSelection(status = CLUB_STATUS_OK, club = GolfClub.fromWireValue(confirmed) ?: club)
    }

    /** Plan R8j: the Pi's current club from its Socket.IO session (`session_state.club` / `club_changed`). */
    private fun clubFromSocket(pi: PiSessionRepository): ClubSelection {
        val club = checkNotNull(pi.club.value?.let(GolfClub::fromWireValue)) { "The Pi hasn't reported its club yet." }
        return ClubSelection(status = CLUB_STATUS_OK, club = club)
    }

    override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult {
        val transport = activeTransport.value ?: throw NoActiveTransportException()
        return controlMutex.withLock {
            try {
                transport.submitCalibration(measurement)
            } catch (missing: OpenFlightHttpError.UnexpectedStatus) {
                // A stock backend has no calibration route and, unlike the club, no Socket.IO
                // equivalent: say what's missing instead of "HTTP 405".
                if (activeTransportType.value == TransportType.WIFI && missing.isRouteAbsent) {
                    throw CalibrationUnsupportedException()
                }
                throw missing
            }
        }
    }

    override fun deleteShot(eventId: String) {
        val timestamp =
            shotHistory.value.shots
                .firstOrNull { it.eventId == eventId }
                ?.timestamp ?: return
        deleteConfirmed(timestamp) { it.eventId == eventId }
    }

    override fun deleteShotByTimestamp(timestamp: String) = deleteConfirmed(timestamp) { it.timestamp == timestamp }

    /**
     * Removes the local shots matching [matches]: at once without a Pi link, else only once the
     * Pi confirmed deleting [timestamp].
     */
    private fun deleteConfirmed(
        timestamp: String,
        matches: (ShotEvent) -> Boolean,
    ) {
        val pi = connectedPi()
        if (pi == null) {
            removeLocally(matches)
            persistentHistory?.deleteShot(timestamp)
            return
        }
        onPi("delete_shot") {
            pi.deleteShot(timestamp)
            // Settled once the state is no longer this shot's pending deletion.
            val outcome = pi.deletionState.first { it !is DeletionState.Pending || it.timestamp != timestamp }
            if (outcome is DeletionState.Deleted && outcome.timestamp == timestamp) {
                removeLocally(matches)
                persistentHistory?.deleteShot(timestamp)
            }
        }
    }

    /**
     * With a Pi link: `clear_session` for the **active profile** only (the server's session holds
     * every profile), then, once confirmed, drops the local shots the Pi filed under that profile.
     * Without one, or before the roster is known, it clears [history] locally.
     */
    override fun clearHistory() {
        val pi = connectedPi()
        val profileId =
            pi
                ?.profiles
                ?.value
                ?.activeProfileId
                .orEmpty()
        if (pi == null || profileId.isEmpty()) {
            changeHistory { ShotHistory(maximumCount = it.maximumCount) }
            return
        }
        // The rows the Pi is about to drop: its session's rows for this profile.
        val cleared =
            pi.sessionShots.value
                .filter { it.profileId == profileId }
                .map { it.timestamp }
        onPi("clear_session") {
            pi.clearSession(profileId)
            val outcome = pi.clearState.first { it !is ClearState.Pending || it.profileId != profileId }
            if (outcome is ClearState.Cleared && outcome.profileId == profileId) {
                removeLocally { pi.detailFor(it)?.profileId == profileId }
                persistentHistory?.deleteShots(cleared)
            }
        }
    }

    private fun connectedPi(): PiSessionRepository? = piSession?.takeIf { it.linkState.value == PiLinkState.Connected }

    private fun removeLocally(predicate: (ShotEvent) -> Boolean) {
        changeHistory { it.copy(shots = it.shots.filterNot(predicate)) }
    }

    /**
     * Applies [transform] to the session cache atomically ([transform] may run more than once, so
     * it must be pure) and publishes the result. Returns whether the history changed.
     */
    private fun changeHistory(transform: (ShotHistory) -> ShotHistory): Boolean {
        var changed = false
        shotHistory.update { current -> transform(current).also { changed = it !== current } }
        if (changed) publishHistory()
        return changed
    }

    /**
     * Copies the newest cache into [history] and [latestShot]. Another thread may publish an older
     * snapshot in between, so it repeats until the cache is unchanged after its write: the last
     * write is then always the newest one.
     */
    private fun publishHistory() {
        do {
            val current = shotHistory.value
            mutableHistory.value = current.shots
            mutableLatestShot.value = current.latestShot
        } while (shotHistory.value !== current)
    }

    /**
     * Runs a Pi request in [scope]; a failure to send (e.g. the link dropping in between) is logged.
     * The outcome a screen shows comes from [PiSessionRepository.deletionState]/`clearState`.
     */
    @Suppress("TooGenericExceptionCaught") // Nothing changed locally; a send failure is only logged.
    private fun onPi(
        name: String,
        request: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                request()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log("Pi $name failed: ${error.message ?: error}")
            }
        }
    }

    override suspend fun shutdownPi() {
        if (activeTransportType.value != TransportType.WIFI) throw PiShutdownUnsupportedException()
        shutdownPi(settings.host.first())
    }

    override suspend fun shutdownPi(target: String) {
        if (activeTransportType.value != TransportType.WIFI) throw PiShutdownUnsupportedException()
        piControl.shutdown(target)
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
            activeTransportType.value = key.type
            clubApiAbsent.value = false
            // R8j: the Socket.IO fallbacks only exist for a Wi-Fi transport with a Pi session.
            val pi = piSession?.takeIf { key.type == TransportType.WIFI }
            try {
                coroutineScope {
                    // Subscribe before start() so no shot or state change is missed.
                    launch(start = CoroutineStart.UNDISPATCHED) { transport.shots.collect { record(it) } }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        var previous: ConnectionState = ConnectionState.Idle
                        transport.state.collect { state ->
                            // R8h: every successful (re)connect is a new history session.
                            if (state == ConnectionState.Connected && previous != state) {
                                persistentHistory?.startSession(key.host, key.type)
                            }
                            previous = state
                            transportState.value = state
                            publishConnectionState(pi)
                        }
                    }
                    if (pi != null) followPiFallback(pi)
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        transport.schemaEvents.collect { applySchemaEvent(it) }
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
                activeTransportType.value = null
                transportState.value = ConnectionState.Idle
                mutableConnectionState.value = ConnectionState.Idle
                mutableSupportsControls.value = false
                mutableActiveClub.value = null
            }
        }

    private fun applySchemaEvent(event: SchemaV2Event) {
        when (event) {
            is SchemaV2Event.ShotDeleted -> {
                removeLocally { it.timestamp == event.timestamp }
                persistentHistory?.deleteShot(event.timestamp)
            }

            is SchemaV2Event.SessionCleared -> {
                val profileId = event.profileId ?: return
                val matches: (ShotEvent) -> Boolean = { shot ->
                    (shot.profileId ?: piSession?.detailFor(shot)?.profileId) == profileId
                }
                // The current session's rows for that profile, as the Pi just dropped them.
                val cleared =
                    shotHistory.value.shots
                        .filter(matches)
                        .map { it.timestamp }
                removeLocally(matches)
                persistentHistory?.deleteShots(cleared)
            }

            else -> {
                Unit
            }
        }
    }

    /**
     * @param writeThrough `false` for a Pi live shot: [start] already files every one of those in
     *   [persistentHistory], so it's written there exactly once.
     */
    private fun record(
        shot: ShotEvent,
        writeThrough: Boolean = true,
    ) {
        if (!changeHistory { it.record(shot) }) return
        if (writeThrough) persistentHistory?.record(shot, piSession?.detailFor(shot))
    }

    /**
     * Plan R8j: shows [connectionState] for the transport, except that a stream the Pi doesn't
     * serve (stock backend: SSE 404) is no error while the Socket.IO link carries the shots: then
     * the link's state stands in for it. Also settles [liveShotSource].
     */
    private fun publishConnectionState(pi: PiSessionRepository?) {
        val transport = transportState.value
        val link = pi?.linkState?.value
        val streamMissing = transport.isStreamUnavailable()
        mutableConnectionState.value = if (link != null && streamMissing) link.inPlaceOfStream() else transport
        mutableLiveShotSource.value =
            when {
                transport == ConnectionState.Connected -> LiveShotSource.SSE
                streamMissing && link == PiLinkState.Connected -> LiveShotSource.SOCKET_IO
                else -> LiveShotSource.NONE
            }
    }

    /**
     * Plan R8j, for one Wi-Fi connection: follows the Pi link into [connectionState]; while the Pi
     * serves no SSE stream, feeds its live `shot`/`shot_update` into [history] (upserted by
     * `event_id`, see [toShotEvent]); and while it has no SSE stream or no `/api/club`, takes the
     * club from its `session_state`/`club_changed`. With a working stream (fork backend) the stream
     * stays the only source and Socket.IO only enriches.
     */
    private fun CoroutineScope.followPiFallback(pi: PiSessionRepository) {
        launch(start = CoroutineStart.UNDISPATCHED) { pi.linkState.collect { publishConnectionState(pi) } }
        launch(start = CoroutineStart.UNDISPATCHED) {
            pi.liveShots.collect { live ->
                if (liveShotSource.value == LiveShotSource.SOCKET_IO) recordLiveShot(live)
            }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            combine(transportState, clubApiAbsent, pi.club) { transport, noClubApi, club ->
                club?.takeIf { transport.isStreamUnavailable() || noClubApi }?.let(GolfClub::fromWireValue)
            }.distinctUntilChanged()
                .collect { club ->
                    if (club != null) {
                        mutableActiveClub.value = club
                        settings.setSelectedClub(club)
                    }
                }
        }
    }

    /** A Pi live shot into [history]; a `shot_update` replaces the shot it finalizes. */
    private fun recordLiveShot(live: PiLiveShot) {
        val mapped = live.toShotEvent() ?: return
        val shots = shotHistory.value.shots
        val shot =
            if (live.isUpdate && shots.none { it.eventId == mapped.eventId }) {
                // Same id whenever timestamp and number match; else match on shot_number, then timestamp.
                val index =
                    shots.indexOfShot(
                        mapped.shotNumber,
                        mapped.timestamp,
                        ShotEvent::shotNumber,
                        ShotEvent::timestamp,
                    )
                if (index >= 0) mapped.copy(eventId = shots[index].eventId) else mapped
            } else {
                mapped
            }
        record(shot, writeThrough = false)
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

    /** The on-connect sync: the club, then (schema v2 BLE) the roster and the power status. */
    private suspend fun syncClub(transport: ShotTransport) {
        quietly("Club sync on connect") { readAndPersistClub(transport) }
        val v2 = (transport as? SchemaV2Commands)?.takeIf { it.schemaV2Active.value } ?: return
        quietly("get_profiles on connect") { controlMutex.withLock { v2.requestProfiles() } }
        quietly("get_power_status on connect") { controlMutex.withLock { v2.requestPowerStatus() } }
    }

    @Suppress("TooGenericExceptionCaught") // Any sync failure is logged, never surfaced as a connection error.
    private suspend fun quietly(
        what: String,
        request: suspend () -> Unit,
    ) {
        try {
            request()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log("$what failed: ${error.message ?: error}")
        }
    }

    private suspend fun readAndPersistClub(transport: ShotTransport): ClubSelection {
        val selection =
            controlMutex.withLock {
                val pi = clubFallbackPi()
                if (pi != null && clubApiAbsent.value) {
                    clubFromSocket(pi)
                } else {
                    clubApiOrFallback(pi, { transport.currentClub() }, ::clubFromSocket)
                }
            }
        settings.setSelectedClub(selection.club)
        return selection
    }

    private data class TransportKey(
        val type: TransportType,
        /** `null` for Bluetooth, so a host edit doesn't restart the Bluetooth session. */
        val host: String?,
    )

    private companion object {
        const val CLUB_STATUS_OK = "ok"

        /** Like the Wi-Fi transport's control-request timeout. */
        const val CLUB_CONFIRMATION_TIMEOUT_MILLIS = 10_000L
    }
}

/** Plan R8j: which link feeds [ShotRepository.history] on Wi-Fi. */
internal enum class LiveShotSource {
    /** Not connected (or Bluetooth, which isn't tracked here). */
    NONE,

    /** The SSE stream (fork backend, jfish Pi); Socket.IO only enriches. */
    SSE,

    /** A stock backend without SSE: the Pi's Socket.IO `shot`/`shot_update`. */
    SOCKET_IO,
}

private fun ConnectionState.isStreamUnavailable(): Boolean =
    this is ConnectionState.Error && kind == ConnectionErrorKind.STREAM_UNAVAILABLE

/** The shot-stream state to show while the Socket.IO link stands in for a missing SSE stream. */
private fun PiLinkState.inPlaceOfStream(): ConnectionState =
    when (this) {
        PiLinkState.Connected -> {
            ConnectionState.Connected
        }

        is PiLinkState.Reconnecting -> {
            ConnectionState.Error(
                reason,
                if (localNetworkDenied) ConnectionErrorKind.LOCAL_NETWORK_DENIED else ConnectionErrorKind.OTHER,
            )
        }

        is PiLinkState.Rejected -> {
            ConnectionState.Error(reason, ConnectionErrorKind.ENDPOINT_REJECTED)
        }

        PiLinkState.Idle, PiLinkState.Connecting, PiLinkState.WifiOnly -> {
            ConnectionState.Connecting
        }
    }
