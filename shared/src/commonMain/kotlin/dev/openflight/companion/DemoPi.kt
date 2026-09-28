// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.DemoModeRepository
import dev.openflight.companion.core.data.NoActiveTransportException
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.PiShutdownUnsupportedException
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.data.WifiOnlyFeatureException
import dev.openflight.companion.core.flight.DemoShot
import dev.openflight.companion.core.flight.DemoShotGenerator
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.EnrichmentProgress
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.DebugReading
import dev.openflight.companion.core.model.pi.DebugShotLog
import dev.openflight.companion.core.model.pi.DebugShotRadar
import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.Profile
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.RadarConfigUpdate
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.ShotProcessingState
import dev.openflight.companion.core.model.pi.TriggerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * How long Demo mode's pretend Pi takes over each step, so every pending, processing and
 * confirmation state can be seen, as with a real Pi. Tests shorten them.
 */
internal data class DemoTiming(
    val connectMillis: Long = 600,
    val captureMillis: Long = 350,
    val calculateMillis: Long = 650,
    /** From the provisional shot to its `shot_update`, the camera/IWR enrichment on a real Pi. */
    val enrichMillis: Long = 1_400,
    /** A delete, clear or club change's confirmation. */
    val answerMillis: Long = 700,
    val shutdownMillis: Long = 1_200,
)

/**
 * Plan F14: Demo mode's pretend Pi, over Wi-Fi in `mock_mode`. It reuses `--preview-pi-mock`'s
 * [PreviewDevicePiSessionRepository] for the roster (three switchable profiles), the battery and
 * the other device cards, and adds what a live demo needs: a link that connects, drops on a
 * shutdown and reconnects; the session's rows and their details; a club that follows confirmed
 * changes; the capture/calculate processing states; server-confirmed deletes and clears; and
 * working radar, trigger and debug cards.
 *
 * [DemoShotRepository] drives it; nothing here is a measurement.
 */
@Suppress("TooManyFunctions") // Mirrors the PiSessionRepository surface.
internal class DemoPiSessionRepository(
    private val scope: CoroutineScope,
    private val timing: DemoTiming = DemoTiming(),
    private val device: PreviewDevicePiSessionRepository = PreviewDevicePiSessionRepository(mockMode = true),
) : PiSessionRepository by device {
    val link = MutableStateFlow<PiLinkState>(PiLinkState.Idle)
    override val linkState: StateFlow<PiLinkState> = link
    override val sessionShots = MutableStateFlow<List<ShotDetail>>(emptyList())
    override val shotDetails = MutableStateFlow<Map<String, ShotDetail>>(emptyMap())
    override val club = MutableStateFlow<String?>(null)
    override val shotProcessing = MutableStateFlow<ShotProcessingState?>(null)
    override val deletionState = MutableStateFlow<DeletionState>(DeletionState.Idle)
    override val clearState = MutableStateFlow<ClearState>(ClearState.Idle)
    override val triggerStatus =
        MutableStateFlow<TriggerStatus?>(
            TriggerStatus(
                mode = "rolling-buffer",
                triggerType = "audio",
                radarConnected = true,
                radarPort = DEMO_RADAR_PORT,
            ),
        )
    override val radarConfig = MutableStateFlow<RadarConfig?>(device.radarConfig.value)
    override val debugState = MutableStateFlow(DebugState(enabled = false, loaded = true))
    override val mockMode = MutableStateFlow<Boolean?>(true)
    override val notices = MutableSharedFlow<PiNotice>(extraBufferCapacity = NOTICE_BUFFER)

    /** Called (on [scope]) for `simulate_shot`: the pretend Pi hits a shot. */
    var onSimulate: suspend () -> Unit = {}

    /** Called for a `set_club` over the link, with the confirmed club. */
    var onClubConfirmed: suspend (GolfClub) -> Unit = {}

    /** Called once a delete or clear is confirmed, with the timestamps that went. */
    var onRowsRemoved: (Collection<String>) -> Unit = {}

    private val connected: Boolean get() = link.value == PiLinkState.Connected

    private fun requireConnected() {
        if (!connected) throw WifiOnlyFeatureException(WifiOnlyFeatureException.Reason.NOT_CONNECTED)
    }

    /** The session's rows and details start empty, like a fresh `session_state`. */
    fun reset() {
        sessionShots.value = emptyList()
        shotDetails.value = emptyMap()
        shotProcessing.value = null
        deletionState.value = DeletionState.Idle
        clearState.value = ClearState.Idle
        triggerStatus.update { it?.copy(triggersTotal = 0, triggersAccepted = 0, triggersRejected = 0) }
        debugState.update { it.copy(readings = emptyList(), shotLogs = emptyList()) }
    }

    /** A `shot` or its `shot_update`: replaces the row with the same shot number, else prepends it. */
    fun upsert(detail: ShotDetail) {
        sessionShots.update { rows ->
            val index = rows.indexOfFirst { it.shotNumber == detail.shotNumber }
            if (index < 0) {
                (listOf(detail) + rows).take(PiSessionRepository.MAX_SESSION_SHOTS)
            } else {
                rows.toMutableList().also { it[index] = detail }
            }
        }
        shotDetails.update { (it + (detail.timestamp to detail)) }
    }

    /** The trigger and debug cards count a new swing, like the rolling-buffer monitor. */
    fun countSwing(shot: DemoShot) {
        triggerStatus.update {
            it?.copy(triggersTotal = it.triggersTotal + 1, triggersAccepted = it.triggersAccepted + 1)
        }
        debugState.update { state ->
            if (!state.enabled) return@update state
            state.copy(
                readings =
                    (state.readings + DebugReading(speed = shot.ballSpeedMph, direction = "outbound"))
                        .takeLast(DebugState.MAX_READINGS),
                shotLogs =
                    (
                        state.shotLogs +
                            DebugShotLog(
                                type = "shot",
                                club = shot.club,
                                radar =
                                    DebugShotRadar(
                                        ballSpeedMph = shot.ballSpeedMph,
                                        clubSpeedMph = shot.clubSpeedMph,
                                        smashFactor = shot.smashFactor,
                                    ),
                            )
                    ).takeLast(DebugState.MAX_SHOT_LOGS),
            )
        }
    }

    override suspend fun refreshSession() = requireConnected()

    override suspend fun deleteShot(timestamp: String) {
        requireConnected()
        if (deletionState.value is DeletionState.Pending) return
        deletionState.value = DeletionState.Pending(timestamp)
        scope.launch {
            delay(timing.answerMillis)
            sessionShots.update { rows -> rows.filterNot { it.timestamp == timestamp } }
            deletionState.update { it.succeed(timestamp) }
            onRowsRemoved(listOf(timestamp))
        }
    }

    override fun dismissDeletion() {
        deletionState.value = DeletionState.Idle
    }

    override suspend fun clearSession(profileId: String) {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        requireConnected()
        if (clearState.value is ClearState.Pending) return
        clearState.value = ClearState.Pending(profileId)
        scope.launch {
            delay(timing.answerMillis)
            val removed = sessionShots.value.filter { it.profileId == profileId }.map { it.timestamp }
            sessionShots.update { rows -> rows.filterNot { it.profileId == profileId } }
            clearState.update { if (it == ClearState.Pending(profileId)) ClearState.Cleared(profileId) else it }
            onRowsRemoved(removed)
        }
    }

    override fun dismissClear() {
        clearState.value = ClearState.Idle
    }

    override suspend fun setActiveProfile(profileId: String) {
        requireConnected()
        device.setActiveProfile(profileId)
    }

    override suspend fun simulateShot() {
        requireConnected()
        scope.launch { onSimulate() }
    }

    override suspend fun setClub(club: String) {
        requireConnected()
        val known = GolfClub.fromWireValue(club) ?: return
        scope.launch {
            delay(timing.answerMillis)
            this@DemoPiSessionRepository.club.value = known.wireValue
            onClubConfirmed(known)
        }
    }

    override suspend fun refreshRadarConfig() = requireConnected()

    override suspend fun setRadarConfig(update: RadarConfigUpdate) {
        requireConnected()
        radarConfig.update { config ->
            val current = config ?: RadarConfig()
            current.copy(
                minSpeed = update.minSpeed ?: current.minSpeed,
                maxSpeed = update.maxSpeed ?: current.maxSpeed,
                minMagnitude = update.minMagnitude ?: current.minMagnitude,
                transmitPower = update.transmitPower?.takeIf { it in 0..MAX_TRANSMIT_POWER } ?: current.transmitPower,
            )
        }
    }

    override suspend fun toggleDebug() {
        requireConnected()
        debugState.update { state ->
            if (state.enabled) {
                state.copy(enabled = false, logPath = null)
            } else {
                state.copy(enabled = true, logPath = DEMO_DEBUG_LOG)
            }
        }
    }

    override suspend fun shutdown() {
        requireConnected()
        notices.tryEmit(PiNotice.ShuttingDown("The demo Pi is shutting down."))
    }

    override suspend fun prepareReplay(replayId: String): String =
        throw UnsupportedOperationException("Shot replays need a real OpenFlight Pi with a camera.")

    companion object {
        /** Shown on the trigger card instead of a serial port: there is no radar in Demo mode. */
        const val DEMO_RADAR_PORT = "Demo (no hardware)"
        private const val DEMO_DEBUG_LOG = "demo-debug.jsonl"
        private const val NOTICE_BUFFER = 8
        private const val MAX_TRANSMIT_POWER = 7
    }
}

/**
 * Plan F14: Demo mode's [ShotRepository]: a pretend Pi over Wi-Fi that connects in a moment and
 * reports made-up shots ([DemoShotGenerator]) through the same live path as a real one:
 * - `shot_processing` capturing → calculating, then the provisional shot (OPS speeds and table
 *   carry, `final: false`, enrichment pending), then its final version with the launch, spin and
 *   spin axis (`final: true`), replacing it in [history] under the same event id. So call-outs,
 *   the range, View on range, Session and Bag all react as they would to a real Pi.
 * - each version is filed in Demo mode's own history ([demoHistory], `source = 'DEMO'`);
 * - club changes are confirmed after a moment; deletes and clears go through [pi]'s confirmation;
 * - a shutdown is accepted and the link then drops, until Retry starts the pretend Pi again.
 */
@OptIn(ExperimentalUuidApi::class)
@Suppress("TooManyFunctions", "LongParameterList") // Mirrors the ShotRepository surface.
internal class DemoShotRepository(
    private val settings: SettingsRepository,
    private val pi: DemoPiSessionRepository,
    private val demoHistory: ShotHistoryRepository,
    private val scope: CoroutineScope,
    seed: Long,
    private val clock: () -> Long,
    private val timing: DemoTiming = DemoTiming(),
    private val offsetMillis: (Long) -> Long = ::localUtcOffsetMillis,
) : ShotRepository {
    private val generator = DemoShotGenerator(seed)
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val shots = MutableStateFlow<List<ShotEvent>>(emptyList())
    private val latest = MutableStateFlow<ShotEvent?>(null)
    private val club = MutableStateFlow<GolfClub?>(null)
    private val firing = Mutex()
    private var running = false
    private var connectJob: Job? = null
    private var shotNumber = 0

    /** The last shot's time: a timestamp is the Pi's key for a shot, so no two may share one. */
    private var lastShotMillis = Long.MIN_VALUE

    override val connectionState: StateFlow<ConnectionState> = connection
    override val history: StateFlow<List<ShotEvent>> = shots
    override val latestShot: StateFlow<ShotEvent?> = latest
    override val activeClub: StateFlow<GolfClub?> = club
    override val supportsControls: StateFlow<Boolean> = MutableStateFlow(true)

    init {
        pi.onSimulate = { hit() }
        pi.onClubConfirmed = { confirmed -> confirmClub(confirmed) }
        pi.onRowsRemoved = { timestamps -> removeRows(timestamps) }
    }

    private val connected: Boolean get() = connection.value == ConnectionState.Connected

    /** A fresh live session: no shots, numbering from 1 (Demo mode turning on, or its data cleared). */
    fun reset() {
        shots.value = emptyList()
        latest.value = null
        shotNumber = 0
        pi.reset()
    }

    override fun start() {
        if (running) return
        running = true
        connect()
    }

    override fun stop() {
        running = false
        connectJob?.cancel()
        connection.value = ConnectionState.Idle
        pi.link.value = PiLinkState.Idle
    }

    override fun retry() {
        running = true
        if (!connected) connect()
    }

    override fun disconnect() {
        connectJob?.cancel()
        connection.value = ConnectionState.Idle
        pi.link.value = PiLinkState.Idle
    }

    private fun connect() {
        connectJob?.cancel()
        connection.value = ConnectionState.Connecting
        pi.link.value = PiLinkState.Connecting
        connectJob =
            scope.launch {
                delay(timing.connectMillis)
                val selected = settings.selectedClub.first()
                club.value = selected
                pi.club.value = selected.wireValue
                // Like a real (re)connect: a new history session for the shots that follow.
                demoHistory.startSession(DemoModeRepository.DEMO_HOST, TransportType.WIFI)
                connection.value = ConnectionState.Connected
                pi.link.value = PiLinkState.Connected
            }
    }

    override suspend fun setClub(club: GolfClub): ClubSelection {
        if (!connected) throw NoActiveTransportException()
        delay(timing.answerMillis)
        confirmClub(club)
        return ClubSelection(status = "ok", club = club)
    }

    private suspend fun confirmClub(confirmed: GolfClub) {
        settings.setSelectedClub(confirmed)
        club.value = confirmed
        pi.club.value = confirmed.wireValue
    }

    override suspend fun currentClub(): ClubSelection =
        ClubSelection(status = "ok", club = club.value ?: settings.selectedClub.first())

    override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult =
        throw UnsupportedOperationException(CALIBRATION_NEEDS_HARDWARE)

    override fun deleteShot(eventId: String) {
        val timestamp = shots.value.firstOrNull { it.eventId == eventId }?.timestamp ?: return
        deleteShotByTimestamp(timestamp)
    }

    override fun deleteShotByTimestamp(timestamp: String) {
        if (pi.link.value == PiLinkState.Connected) {
            scope.launch { pi.deleteShot(timestamp) }
        } else {
            removeRows(listOf(timestamp))
        }
    }

    override fun clearHistory() {
        val profileId = pi.profiles.value.activeProfileId
        if (pi.link.value == PiLinkState.Connected && profileId.isNotEmpty()) {
            scope.launch { pi.clearSession(profileId) }
        } else {
            removeRows(shots.value.map { it.timestamp })
        }
    }

    private fun removeRows(timestamps: Collection<String>) {
        if (timestamps.isEmpty()) return
        val gone = timestamps.toSet()
        shots.update { list -> list.filterNot { it.timestamp in gone } }
        latest.value = shots.value.firstOrNull()
        demoHistory.deleteShots(gone)
    }

    override suspend fun shutdownPi() = shutdownPi(DemoModeRepository.DEMO_HOST)

    /** Accepted like a Pi's 200; the link drops a moment later, as the server exits. */
    override suspend fun shutdownPi(target: String) {
        if (!connected) throw PiShutdownUnsupportedException()
        delay(timing.shutdownMillis)
        scope.launch {
            delay(timing.answerMillis)
            connectJob?.cancel()
            connection.value = ConnectionState.Error(PI_STOPPED)
            pi.link.value = PiLinkState.Idle
        }
    }

    /**
     * One swing with the selected club and the active profile, through the live path: processing,
     * the provisional shot, then its final version. Swings queue one after another. Ignored while the
     * pretend Pi isn't connected.
     */
    suspend fun hit() {
        if (!connected) return
        firing.withLock {
            if (!connected) return
            val swingClub = club.value ?: settings.selectedClub.first()
            val profile = pi.profiles.value.activeProfile
            pi.shotProcessing.value = ShotProcessingState.CAPTURING
            delay(timing.captureMillis)
            pi.shotProcessing.value = ShotProcessingState.CALCULATING
            delay(timing.calculateMillis)
            val shot = generator.next(swingClub.wireValue)
            shotNumber += 1
            val identity =
                DemoShotIdentity(
                    eventId = Uuid.random().toString(),
                    timestamp = naiveLocalTimestamp(nextShotMillis(), offsetMillis),
                    shotNumber = shotNumber,
                    profile = profile,
                )
            pi.shotProcessing.value = null
            report(DemoShots.event(shot, identity, final = false), DemoShots.detail(shot, identity, final = false))
            pi.countSwing(shot)
            delay(timing.enrichMillis)
            report(DemoShots.event(shot, identity, final = true), DemoShots.detail(shot, identity, final = true))
        }
    }

    private fun nextShotMillis(): Long {
        val millis = maxOf(clock(), lastShotMillis + 1)
        lastShotMillis = millis
        return millis
    }

    private fun report(
        event: ShotEvent,
        detail: ShotDetail,
    ) {
        shots.update { list ->
            val index = list.indexOfFirst { it.eventId == event.eventId }
            if (index < 0) {
                (listOf(event) + list).take(MAX_HISTORY)
            } else {
                list.toMutableList().also { it[index] = event }
            }
        }
        latest.value = shots.value.firstOrNull()
        pi.upsert(detail)
        demoHistory.record(event, detail)
    }

    companion object {
        const val CALIBRATION_NEEDS_HARDWARE =
            "Radar calibration needs a real OpenFlight Pi and radar. Demo mode has no hardware to level."
        const val PI_STOPPED = "The demo Pi stopped. Tap Retry to start it again."
        private const val MAX_HISTORY = 100
    }
}

/** Who and when one demo shot is: the same for its provisional and final versions. */
internal data class DemoShotIdentity(
    val eventId: String,
    val timestamp: String,
    val shotNumber: Int,
    val profile: Profile?,
)

/** Plan F14: a [DemoShot] in the Pi's shapes, honestly marked as mock data (`angle_source: mock`). */
internal object DemoShots {
    /** The SSE/BLE v2 event: a provisional shot carries only the radar's speeds and the table carry. */
    fun event(
        shot: DemoShot,
        identity: DemoShotIdentity,
        final: Boolean,
    ): ShotEvent =
        ShotEvent(
            schemaVersion = 2,
            eventId = identity.eventId,
            timestamp = identity.timestamp,
            club = shot.club,
            ballSpeedMph = shot.ballSpeedMph,
            clubSpeedMph = shot.clubSpeedMph,
            smashFactor = shot.smashFactor,
            estimatedCarryYards = shot.carryYards,
            launchAngleVertical = shot.launchAngleVertical.takeIf { final },
            launchAngleHorizontal = shot.launchAngleHorizontal.takeIf { final },
            spinRpm = shot.spinRpm.takeIf { final },
            clubPathDeg = shot.clubPathDeg.takeIf { final },
            spinAxisDeg = shot.spinAxisDeg.takeIf { final },
            type = "shot",
            final = final,
            shotNumber = identity.shotNumber,
            profileId = identity.profile?.id,
            profileName = identity.profile?.name,
            enrichment = EnrichmentProgress(status = if (final) "complete" else "pending"),
        )

    /** The Socket.IO row for the same shot. */
    fun detail(
        shot: DemoShot,
        identity: DemoShotIdentity,
        final: Boolean = true,
    ): ShotDetail =
        ShotDetail(
            timestamp = identity.timestamp,
            shotNumber = identity.shotNumber,
            ballSpeedMph = shot.ballSpeedMph,
            clubSpeedMph = shot.clubSpeedMph,
            smashFactor = shot.smashFactor,
            estimatedCarryYards = shot.carryYards,
            club = shot.club,
            profileId = identity.profile?.id,
            profileName = identity.profile?.name,
            launchAngleVertical = shot.launchAngleVertical.takeIf { final },
            launchAngleHorizontal = shot.launchAngleHorizontal.takeIf { final },
            angleSource = MOCK_SOURCE.takeIf { final },
            clubPathDeg = shot.clubPathDeg.takeIf { final },
            spinAxisDeg = shot.spinAxisDeg.takeIf { final },
            spinRpm = shot.spinRpm.takeIf { final },
        )

    /** The backend's own label for made-up angles (`angle_source`). */
    const val MOCK_SOURCE = "mock"
}

/**
 * [epochMillis] as the Pi writes timestamps: naive local ISO-8601 with microseconds, e.g.
 * `2026-09-27T14:03:12.123000`, in the device's time zone ([offsetMillis]).
 */
@Suppress("MagicNumber") // The civil-from-days calendar algorithm (H. Hinnant).
internal fun naiveLocalTimestamp(
    epochMillis: Long,
    offsetMillis: (Long) -> Long = ::localUtcOffsetMillis,
): String {
    val local = epochMillis + offsetMillis(epochMillis)
    val days = local.floorDiv(86_400_000L)
    val millisOfDay = local.mod(86_400_000L)
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPart = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthPart + 2) / 5 + 1
    val month = if (monthPart < 10) monthPart + 3 else monthPart - 9
    val year = yearOfEra + era * 400 + (if (month <= 2) 1 else 0)
    val hours = millisOfDay / 3_600_000
    val minutes = millisOfDay / 60_000 % 60
    val seconds = millisOfDay / 1_000 % 60
    val micros = millisOfDay % 1_000 * 1_000

    fun pad(
        value: Long,
        width: Int,
    ) = value.toString().padStart(width, '0')
    return "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}T${pad(hours, 2)}:${pad(minutes, 2)}:${pad(seconds, 2)}." +
        pad(micros, 6)
}
