// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.HistorySession
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.ImportedSession
import dev.openflight.companion.core.data.PiLiveShot
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.ShotWindow
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.CameraCaptureSettings
import dev.openflight.companion.core.model.pi.CameraPreview
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.PowerStatus
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plan F14: what the Pi-facing repositories switch on. [useDemo] is the live mode; listeners hear
 * each flip (on the caller's thread, the main thread), and [whenRestored] runs an action once the
 * persisted mode is known, so the app never connects the real Pi only to switch to the demo a moment
 * later.
 */
internal interface DemoSwitch {
    val useDemo: StateFlow<Boolean>

    fun onModeChange(listener: (demo: Boolean) -> Unit)

    fun whenRestored(action: () -> Unit)
}

/**
 * Plan F14: the app's [ShotRepository]: the [real] one, or Demo mode's [demo] one while
 * [DemoSwitch.useDemo] is on. Flipping the mode while started stops the side that was live and
 * starts the other, so the real transport is closed for as long as the demo runs and reconnects
 * when it ends. Every screen keeps using this one instance.
 */
@Suppress("TooManyFunctions") // Mirrors the ShotRepository surface.
internal class DemoSwitchingShotRepository(
    private val real: ShotRepository,
    private val demo: ShotRepository,
    private val switch: DemoSwitch,
) : ShotRepository {
    private var started = false

    init {
        switch.onModeChange { demoOn ->
            if (started) {
                (if (demoOn) real else demo).stop()
                active().start()
            }
        }
    }

    private fun active(): ShotRepository = if (switch.useDemo.value) demo else real

    private fun <T> switched(pick: (ShotRepository) -> StateFlow<T>): StateFlow<T> =
        DemoSwitchedStateFlow(switch.useDemo, pick(real), pick(demo))

    override val connectionState: StateFlow<ConnectionState> = switched { it.connectionState }
    override val history: StateFlow<List<ShotEvent>> = switched { it.history }
    override val latestShot: StateFlow<ShotEvent?> = switched { it.latestShot }
    override val activeClub: StateFlow<GolfClub?> = switched { it.activeClub }
    override val supportsControls: StateFlow<Boolean> = switched { it.supportsControls }

    override fun start() {
        started = true
        switch.whenRestored { if (started) active().start() }
    }

    override fun stop() {
        started = false
        active().stop()
    }

    override fun retry() {
        started = true
        active().retry()
    }

    override fun disconnect() = active().disconnect()

    override suspend fun setClub(club: GolfClub): ClubSelection = active().setClub(club)

    override suspend fun currentClub(): ClubSelection = active().currentClub()

    override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult =
        active().submitCalibration(measurement)

    override fun deleteShot(eventId: String) = active().deleteShot(eventId)

    override fun deleteShotByTimestamp(timestamp: String) = active().deleteShotByTimestamp(timestamp)

    override fun clearHistory() = active().clearHistory()

    override suspend fun shutdownPi() = active().shutdownPi()

    override suspend fun shutdownPi(target: String) = active().shutdownPi(target)
}

/** Plan F14: the app's [PiSessionRepository], switched like [DemoSwitchingShotRepository]. */
@Suppress("TooManyFunctions") // Mirrors the PiSessionRepository surface.
internal class DemoSwitchingPiSessionRepository(
    private val real: PiSessionRepository,
    private val demo: PiSessionRepository,
    private val switch: DemoSwitch,
) : PiSessionRepository {
    private fun active(): PiSessionRepository = if (switch.useDemo.value) demo else real

    private fun <T> switched(pick: (PiSessionRepository) -> StateFlow<T>): StateFlow<T> =
        DemoSwitchedStateFlow(switch.useDemo, pick(real), pick(demo))

    override val linkState: StateFlow<PiLinkState> = switched { it.linkState }
    override val bluetoothSchemaV2: StateFlow<Boolean> = switched { it.bluetoothSchemaV2 }
    override val sessionShots: StateFlow<List<ShotDetail>> = switched { it.sessionShots }
    override val shotDetails: StateFlow<Map<String, ShotDetail>> = switched { it.shotDetails }
    override val stats: StateFlow<SessionStats?> = switched { it.stats }
    override val profiles: StateFlow<ProfilesState> = switched { it.profiles }
    override val club: StateFlow<String?> = switched { it.club }
    override val shotProcessing: StateFlow<ShotProcessingState?> = switched { it.shotProcessing }
    override val powerStatus: StateFlow<PowerStatus?> = switched { it.powerStatus }
    override val deletionState: StateFlow<DeletionState> = switched { it.deletionState }
    override val clearState: StateFlow<ClearState> = switched { it.clearState }
    override val trainingImplement: StateFlow<TrainingImplement?> = switched { it.trainingImplement }
    override val latestSwingSpeed: StateFlow<SwingSpeedReading?> = switched { it.latestSwingSpeed }
    override val triggerStatus: StateFlow<TriggerStatus?> = switched { it.triggerStatus }
    override val cameraCaptureSettings: StateFlow<CameraCaptureSettings?> = switched { it.cameraCaptureSettings }
    override val simState: StateFlow<SimState> = switched { it.simState }
    override val radarConfig: StateFlow<RadarConfig?> = switched { it.radarConfig }
    override val debugState: StateFlow<DebugState> = switched { it.debugState }
    override val cloudUploadStatus: StateFlow<CloudUploadStatus> = switched { it.cloudUploadStatus }
    override val mockMode: StateFlow<Boolean?> = switched { it.mockMode }
    override val notices: SharedFlow<PiNotice> = DemoSwitchedSharedFlow(switch.useDemo, real.notices, demo.notices)
    override val liveShots: Flow<PiLiveShot> = demoSwitched(switch.useDemo, { real.liveShots }, { demo.liveShots })

    override fun detailFor(shot: ShotEvent): ShotDetail? = active().detailFor(shot)

    override fun start() = active().start()

    override fun stop() = active().stop()

    override suspend fun refreshSession() = active().refreshSession()

    override suspend fun deleteShot(timestamp: String) = active().deleteShot(timestamp)

    override fun dismissDeletion() = active().dismissDeletion()

    override suspend fun clearSession(profileId: String) = active().clearSession(profileId)

    override fun dismissClear() = active().dismissClear()

    override suspend fun setActiveProfile(profileId: String) = active().setActiveProfile(profileId)

    override suspend fun addProfile(name: String) = active().addProfile(name)

    override suspend fun renameProfile(
        profileId: String,
        name: String,
    ) = active().renameProfile(profileId, name)

    override suspend fun removeProfile(profileId: String) = active().removeProfile(profileId)

    override suspend fun simulateShot() = active().simulateShot()

    override suspend fun setClub(club: String) = active().setClub(club)

    override suspend fun setTrainingImplement(implement: String) = active().setTrainingImplement(implement)

    override suspend fun refreshCameraCaptureSettings() = active().refreshCameraCaptureSettings()

    override suspend fun refreshRadarConfig() = active().refreshRadarConfig()

    override suspend fun setRadarConfig(update: RadarConfigUpdate) = active().setRadarConfig(update)

    override suspend fun toggleDebug() = active().toggleDebug()

    override suspend fun uploadCloud() = active().uploadCloud()

    override suspend fun shutdown() = active().shutdown()

    override suspend fun cameraPreview(): CameraPreview = active().cameraPreview()

    override suspend fun prepareReplay(replayId: String): String = active().prepareReplay(replayId)
}

/**
 * Plan F14: the app's [ShotHistoryRepository]: the player's own history, or Demo mode's
 * (`source = 'DEMO'`) while Demo mode is on. Lists and stats follow the mode, so turning Demo mode
 * off hides every demo session and its shots. Imports always go to the player's history.
 */
@Suppress("TooManyFunctions") // Mirrors the ShotHistoryRepository surface.
internal class DemoSwitchingShotHistoryRepository(
    private val real: ShotHistoryRepository,
    private val demo: ShotHistoryRepository,
    private val switch: DemoSwitch,
) : ShotHistoryRepository {
    private fun active(): ShotHistoryRepository = if (switch.useDemo.value) demo else real

    override val isPersistent: StateFlow<Boolean> =
        DemoSwitchedStateFlow(switch.useDemo, real.isPersistent, demo.isPersistent)
    override val currentSessionId: StateFlow<String?> =
        DemoSwitchedStateFlow(switch.useDemo, real.currentSessionId, demo.currentSessionId)

    override fun sessions(includeImported: Boolean): Flow<List<HistorySession>> =
        demoSwitched(switch.useDemo, { real.sessions(includeImported) }, { demo.sessions(includeImported) })

    override fun shots(
        sessionId: String,
        profileId: String?,
    ): Flow<List<HistoryShot>> =
        demoSwitched(switch.useDemo, { real.shots(sessionId, profileId) }, { demo.shots(sessionId, profileId) })

    override fun shotsForClub(
        club: GolfClub,
        window: ShotWindow,
        profileId: String?,
    ): Flow<List<HistoryShot>> =
        demoSwitched(
            switch.useDemo,
            { real.shotsForClub(club, window, profileId) },
            { demo.shotsForClub(club, window, profileId) },
        )

    override fun startSession(
        host: String?,
        transport: TransportType,
    ) = active().startSession(host, transport)

    override fun record(
        shot: ShotEvent,
        detail: ShotDetail?,
    ) = active().record(shot, detail)

    override fun record(shot: PiLiveShot) = active().record(shot)

    override fun deleteShots(timestamps: Collection<String>) = active().deleteShots(timestamps)

    override fun clearAll() = active().clearAll()

    override fun clearImported() = real.clearImported()

    override fun setStarred(
        shotId: Long,
        starred: Boolean,
    ) = active().setStarred(shotId, starred)

    override fun setNote(
        shotId: Long,
        note: String?,
    ) = active().setNote(shotId, note)

    override fun setIncludeInStats(
        sessionId: String,
        include: Boolean,
    ) = active().setIncludeInStats(sessionId, include)

    override fun setSessionNote(
        sessionId: String,
        note: String?,
    ) = active().setSessionNote(sessionId, note)

    override suspend fun importSession(session: ImportedSession): String? = real.importSession(session)
}
