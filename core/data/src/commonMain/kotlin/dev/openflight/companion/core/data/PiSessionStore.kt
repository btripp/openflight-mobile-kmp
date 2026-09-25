// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.pi.CameraCaptureSettings
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.PowerStatus
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.SessionCleared
import dev.openflight.companion.core.model.pi.SessionState
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.ShotProcessingState
import dev.openflight.companion.core.model.pi.SimState
import dev.openflight.companion.core.model.pi.SwingSpeedReading
import dev.openflight.companion.core.model.pi.TrainingImplement
import dev.openflight.companion.core.model.pi.TriggerStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Every flow [DefaultPiSessionRepository] exposes, and how each decoded [PiEvent] changes them.
 * Mirrors the Expo app's stores (`useSessionStore`, `useDeviceStore`, `useProfileStore`,
 * `useShotDeletionStore`) and the web UI's `socketService.ts`. Single-threaded: events are applied
 * in order by the socket's collector.
 *
 * @param resync asks the Pi for the whole session again (`get_session`), for a `session_cleared`
 *   whose remaining rows can't be read.
 */
@Suppress("TooManyFunctions") // One handler per event group.
internal class PiSessionStore(
    private val resync: () -> Unit,
) {
    val sessionShots = MutableStateFlow(emptyList<ShotDetail>())
    val shotDetails = MutableStateFlow(emptyMap<String, ShotDetail>())
    val stats = MutableStateFlow<SessionStats?>(null)
    val profiles = MutableStateFlow(ProfilesState())
    val club = MutableStateFlow<String?>(null)
    val shotProcessing = MutableStateFlow<ShotProcessingState?>(null)
    val powerStatus = MutableStateFlow<PowerStatus?>(null)
    val deletion = MutableStateFlow<DeletionState>(DeletionState.Idle)
    val clear = MutableStateFlow<ClearState>(ClearState.Idle)
    val trainingImplement = MutableStateFlow<TrainingImplement?>(null)
    val latestSwingSpeed = MutableStateFlow<SwingSpeedReading?>(null)
    val triggerStatus = MutableStateFlow<TriggerStatus?>(null)
    val cameraCaptureSettings = MutableStateFlow<CameraCaptureSettings?>(null)
    val simState = MutableStateFlow(SimState())
    val radarConfig = MutableStateFlow<RadarConfig?>(null)
    val debugState = MutableStateFlow(DebugState())
    val cloudUploadStatus = MutableStateFlow(CloudUploadStatus())
    val mockMode = MutableStateFlow<Boolean?>(null)
    val notices = MutableSharedFlow<PiNotice>(extraBufferCapacity = NOTICE_BUFFER)

    suspend fun apply(event: PiEvent) {
        when (event) {
            is PiEvent.Notice -> notices.emit(event.notice)

            is PiEvent.Shot, is PiEvent.ShotUpdate, is PiEvent.SwingSpeed, is PiEvent.Session, is PiEvent.Cleared,
            is PiEvent.DeleteShotFailed, is PiEvent.Processing,
            -> applySessionEvent(event)

            is PiEvent.Profiles, is PiEvent.ClubChanged, is PiEvent.Power, is PiEvent.TrainingImplementChanged,
            -> applyContextEvent(event)

            is PiEvent.Debug, is PiEvent.DebugReadingReceived, is PiEvent.DebugShotReceived,
            is PiEvent.Diagnostic, is PiEvent.Trigger, is PiEvent.Radar,
            -> applyDebugEvent(event)

            else -> applyDeviceEvent(event)
        }
    }

    /** The link left `Connected` (drop, host switch or stop): no reply to an in-flight request will come. */
    fun linkLost() {
        deletion.update { it.fail(DeletionState.CONNECTION_DROPPED) }
        clear.update {
            if (it is ClearState.Pending) {
                ClearState.Failed(
                    it.profileId,
                    ClearState.CONNECTION_DROPPED,
                )
            } else {
                it
            }
        }
    }

    /** A pending clear of [profileId] got no confirmation in time. */
    fun clearTimedOut(profileId: String) {
        clear.update {
            if (it is ClearState.Pending && it.profileId == profileId) {
                ClearState.Failed(profileId, ClearState.NO_CONFIRMATION)
            } else {
                it
            }
        }
    }

    /**
     * Forgets everything that described the previous Pi (a host or transport switch). The
     * deletion and clear outcomes stay: [linkLost] already failed anything in flight.
     */
    fun reset() {
        sessionShots.value = emptyList()
        shotDetails.value = emptyMap()
        stats.value = null
        profiles.value = ProfilesState()
        club.value = null
        shotProcessing.value = null
        powerStatus.value = null
        trainingImplement.value = null
        latestSwingSpeed.value = null
        triggerStatus.value = null
        cameraCaptureSettings.value = null
        simState.value = SimState()
        radarConfig.value = null
        debugState.value = DebugState()
        cloudUploadStatus.value = CloudUploadStatus()
        mockMode.value = null
    }

    private fun applySessionEvent(event: PiEvent) {
        when (event) {
            is PiEvent.Shot -> {
                // The next shot ends any capturing/calculating/failed indicator.
                shotProcessing.value = null
                onShot(event.detail)
                event.stats?.let { stats.value = it }
            }

            is PiEvent.ShotUpdate -> {
                upsert(event.detail)
                event.stats?.let { stats.value = it }
            }

            is PiEvent.Processing -> {
                shotProcessing.value = event.state
            }

            is PiEvent.SwingSpeed -> {
                // The rep also arrives as a `shot`; this only tracks the latest reading.
                latestSwingSpeed.value = event.reading
                event.stats?.let { stats.value = it }
            }

            is PiEvent.Session -> {
                onSessionState(event.state)
            }

            is PiEvent.Cleared -> {
                onSessionCleared(event.cleared)
            }

            is PiEvent.DeleteShotFailed -> {
                // Broadcast to every client: with nothing of ours pending it's another client's.
                deletion.update { it.fail(event.error ?: DeletionState.SERVER_REFUSED) }
            }

            else -> {
                Unit
            }
        }
    }

    private fun applyContextEvent(event: PiEvent) {
        when (event) {
            is PiEvent.Profiles -> {
                profiles.value =
                    ProfilesState(event.snapshot.profiles, event.snapshot.activeProfileId, loaded = true)
            }

            is PiEvent.ClubChanged -> {
                club.value = event.club
            }

            is PiEvent.Power -> {
                powerStatus.value = event.status
            }

            is PiEvent.TrainingImplementChanged -> {
                trainingImplement.value = event.implement
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
                debugState.update {
                    it.copy(readings = (it.readings + event.reading).takeLast(DebugState.MAX_READINGS))
                }
            }

            is PiEvent.DebugShotReceived -> {
                debugState.update {
                    it.copy(shotLogs = (it.shotLogs + event.log).takeLast(DebugState.MAX_SHOT_LOGS))
                }
            }

            is PiEvent.Diagnostic -> {
                onDiagnostic(event)
            }

            is PiEvent.Trigger -> {
                triggerStatus.value = event.status
            }

            is PiEvent.Radar -> {
                radarConfig.value = event.config
            }

            else -> {
                Unit
            }
        }
    }

    private fun applyDeviceEvent(event: PiEvent) {
        when (event) {
            is PiEvent.CameraSettings -> {
                cameraCaptureSettings.value = event.settings
            }

            is PiEvent.Sim -> {
                simState.update { it.copy(connectors = it.connectors + (event.status.target to event.status)) }
            }

            is PiEvent.SimShotSent -> {
                simState.update { it.copy(latestShot = event.shot) }
            }

            is PiEvent.SimPlayerChanged -> {
                simState.update { it.copy(latestPlayer = event.player) }
            }

            is PiEvent.Cloud -> {
                cloudUploadStatus.value = event.status
            }

            else -> {
                Unit
            }
        }
    }

    private fun onShot(detail: ShotDetail) {
        sessionShots.update { shots ->
            (listOf(detail) + shots.filterNot { it.timestamp == detail.timestamp })
                .take(PiSessionRepository.MAX_SESSION_SHOTS)
        }
        remember(listOf(detail))
    }

    /**
     * `shot_update` replaces its provisional `shot` in place: matched on `shot_number` (Expo
     * `replaceShot`), then on `timestamp` (the web UI's key; a swing-speed row has no number). An
     * update for a shot this client never saw is prepended rather than dropped.
     */
    private fun upsert(detail: ShotDetail) {
        sessionShots.update { shots ->
            val byNumber = detail.shotNumber?.let { number -> shots.indexOfFirst { it.shotNumber == number } } ?: -1
            val index = if (byNumber >= 0) byNumber else shots.indexOfFirst { it.timestamp == detail.timestamp }
            if (index >= 0) {
                shots.toMutableList().also { it[index] = detail }
            } else {
                (listOf(detail) + shots).take(PiSessionRepository.MAX_SESSION_SHOTS)
            }
        }
        remember(listOf(detail))
    }

    private fun onSessionState(state: SessionState) {
        replaceSession(state.shots)
        stats.value = state.stats
        // Older servers omit the club; keep what is shown rather than blank it.
        state.club?.let { club.value = it }
        state.mockMode?.let { mockMode.value = it }
        state.debugMode?.let { enabled -> debugState.update { it.copy(enabled = enabled, loaded = true) } }
        // The success reply to delete_shot. A session_state that still holds the shot (another
        // client connecting, say) isn't this deletion's answer, so it keeps waiting.
        val pending = deletion.value
        if (pending is DeletionState.Pending && state.shots.none { it.timestamp == pending.timestamp }) {
            deletion.update { it.succeed(pending.timestamp) }
        }
    }

    /**
     * Applies the remaining session verbatim, which keeps other profiles' rows. Without a readable
     * list nothing is dropped on a guess: the session is asked for again instead.
     */
    private fun onSessionCleared(cleared: SessionCleared) {
        val remaining = cleared.shots
        if (remaining == null) {
            resync()
        } else {
            replaceSession(remaining)
            // The broadcast carries no stats: all-profile server stats are stale unless nothing is left.
            stats.value = if (remaining.isEmpty()) SessionStats.EMPTY else null
        }
        val profileId = cleared.profileId ?: return
        clear.update { state ->
            val ours =
                (state is ClearState.Pending && state.profileId == profileId) ||
                    (state is ClearState.Failed && state.profileId == profileId)
            // A confirmation that arrives after giving up still tells the truth.
            if (ours) ClearState.Cleared(profileId) else state
        }
    }

    /** The server lists the session oldest first; the app shows newest first. */
    private fun replaceSession(oldestFirst: List<ShotDetail>) {
        sessionShots.value = oldestFirst.asReversed().take(PiSessionRepository.MAX_SESSION_SHOTS)
        remember(oldestFirst)
    }

    private fun onDiagnostic(event: PiEvent.Diagnostic) {
        val accepted = event.diagnostic.accepted
        debugState.update {
            it.copy(
                triggerDiagnostics =
                    (it.triggerDiagnostics + event.diagnostic).takeLast(DebugState.MAX_TRIGGER_DIAGNOSTICS),
            )
        }
        // useDebugStore.updateTriggerStatusStats: count it before the next trigger_status arrives.
        triggerStatus.update { status ->
            status?.copy(
                triggersTotal = status.triggersTotal + 1,
                triggersAccepted = status.triggersAccepted + if (accepted) 1 else 0,
                triggersRejected = status.triggersRejected + if (accepted) 0 else 1,
            )
        }
    }

    /**
     * `debug_status` carries `log_path` explicitly; `debug_toggled` omits it when switching off,
     * which reads as "no log" rather than "unchanged" (Expo `applyDebugStatus`).
     */
    private fun onDebugToggled(
        enabled: Boolean,
        logPath: String?,
    ) {
        debugState.update {
            // useDebugStore.clearDebugData on disable: readings and shot logs, not diagnostics.
            if (enabled) {
                it.copy(enabled = true, logPath = logPath, loaded = true)
            } else {
                it.copy(
                    enabled = false,
                    logPath = logPath,
                    loaded = true,
                    readings = emptyList(),
                    shotLogs = emptyList(),
                )
            }
        }
    }

    /** Adds [details] to the enrichment index, most recent last, keeping [PiSessionRepository.MAX_SESSION_SHOTS]. */
    private fun remember(details: List<ShotDetail>) {
        if (details.isEmpty()) return
        shotDetails.update { current ->
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

    private companion object {
        const val NOTICE_BUFFER = 16
    }
}
