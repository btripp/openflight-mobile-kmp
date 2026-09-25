// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.ProfileRuleException
import dev.openflight.companion.core.model.pi.CameraCaptureSettings
import dev.openflight.companion.core.model.pi.CameraPreview
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.PowerState
import dev.openflight.companion.core.model.pi.PowerStatus
import dev.openflight.companion.core.model.pi.Profile
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A connected Pi for the `--preview-pi` launch hook (plan R8f part A): the profile picker, the device
 * cards and stopping OpenFlight can be driven in UI tests and screenshots without a Pi. It reports
 * a live link, a three-profile roster, a battery, a rolling-buffer trigger status, debug mode off
 * and a swing being calculated. Profile commands follow the server's rules (profiles.py): add
 * makes the new profile active; removing the active or last profile is refused silently (the
 * roster doesn't change), like the Pi. Everything else is a no-op. Debug launch hooks only.
 */
@Suppress("TooManyFunctions") // Mirrors the PiSessionRepository surface.
internal class PreviewDevicePiSessionRepository : PiSessionRepository {
    override val linkState: StateFlow<PiLinkState> = MutableStateFlow(PiLinkState.Connected)
    override val bluetoothSchemaV2: StateFlow<Boolean> = MutableStateFlow(false)
    override val sessionShots: StateFlow<List<ShotDetail>> = MutableStateFlow(emptyList())
    override val shotDetails: StateFlow<Map<String, ShotDetail>> = MutableStateFlow(emptyMap())
    override val stats: StateFlow<SessionStats?> = MutableStateFlow(null)
    private val roster =
        MutableStateFlow(
            ProfilesState(
                profiles =
                    listOf(
                        Profile(id = "preview-ann", name = "Ann"),
                        Profile(id = "preview-bob", name = "Bob"),
                        Profile(id = "preview-cara", name = "Cara"),
                    ),
                activeProfileId = "preview-ann",
                loaded = true,
            ),
        )
    override val profiles: StateFlow<ProfilesState> = roster
    override val club: StateFlow<String?> = MutableStateFlow("driver")
    override val shotProcessing: StateFlow<ShotProcessingState?> = MutableStateFlow(ShotProcessingState.CALCULATING)
    override val powerStatus: StateFlow<PowerStatus?> =
        MutableStateFlow(
            PowerStatus(
                available = true,
                provider = "geekworm",
                state = PowerState.ON_BATTERY,
                batteryPercent = 78.0,
                batteryVoltageV = 3.91,
                externalPower = false,
            ),
        )
    override val deletionState: StateFlow<DeletionState> = MutableStateFlow(DeletionState.Idle)
    override val clearState: StateFlow<ClearState> = MutableStateFlow(ClearState.Idle)
    override val trainingImplement: StateFlow<TrainingImplement?> = MutableStateFlow(null)
    override val latestSwingSpeed: StateFlow<SwingSpeedReading?> = MutableStateFlow(null)
    override val triggerStatus: StateFlow<TriggerStatus?> =
        MutableStateFlow(
            TriggerStatus(
                mode = "rolling-buffer",
                triggerType = "audio",
                radarConnected = true,
                radarPort = "/dev/ttyUSB0",
                triggersTotal = 12,
                triggersAccepted = 9,
                triggersRejected = 3,
            ),
        )
    override val cameraCaptureSettings: StateFlow<CameraCaptureSettings?> = MutableStateFlow(null)
    override val simState: StateFlow<SimState> = MutableStateFlow(SimState())
    override val radarConfig: StateFlow<RadarConfig?> =
        MutableStateFlow(RadarConfig(minSpeed = 10, maxSpeed = 120, minMagnitude = 400, transmitPower = 0))
    override val debugState: StateFlow<DebugState> = MutableStateFlow(DebugState(enabled = false, loaded = true))
    override val cloudUploadStatus: StateFlow<CloudUploadStatus> = MutableStateFlow(CloudUploadStatus())
    override val mockMode: StateFlow<Boolean?> = MutableStateFlow(false)
    override val notices: SharedFlow<PiNotice> = MutableSharedFlow()

    override fun start() = Unit

    override fun stop() = Unit

    override suspend fun refreshSession() = Unit

    override suspend fun deleteShot(timestamp: String) = Unit

    override fun dismissDeletion() = Unit

    override suspend fun clearSession(profileId: String) = Unit

    override fun dismissClear() = Unit

    override suspend fun setActiveProfile(profileId: String) {
        roster.update { state ->
            if (state.profiles.any { it.id == profileId }) state.copy(activeProfileId = profileId) else state
        }
    }

    override suspend fun addProfile(name: String) {
        val valid = valid(name)
        if (!roster.value.canAdd) throw ProfileRuleException(ProfileRuleException.Rule.TOO_MANY_PROFILES)
        val id = "preview-${roster.value.profiles.size + 1}"
        roster.update { it.copy(profiles = it.profiles + Profile(id = id, name = valid), activeProfileId = id) }
    }

    override suspend fun renameProfile(
        profileId: String,
        name: String,
    ) {
        val valid = valid(name)
        roster.update { state ->
            state.copy(profiles = state.profiles.map { if (it.id == profileId) it.copy(name = valid) else it })
        }
    }

    /** Refused like the Pi: the active profile and the last one stay, with no error. */
    override suspend fun removeProfile(profileId: String) {
        roster.update { state ->
            if (profileId == state.activeProfileId || state.profiles.size <= 1) {
                state
            } else {
                state.copy(profiles = state.profiles.filterNot { it.id == profileId })
            }
        }
    }

    override suspend fun simulateShot() = Unit

    override suspend fun setTrainingImplement(implement: String) = Unit

    override suspend fun refreshCameraCaptureSettings() = Unit

    override suspend fun refreshRadarConfig() = Unit

    override suspend fun setRadarConfig(update: RadarConfigUpdate) = Unit

    override suspend fun toggleDebug() = Unit

    override suspend fun uploadCloud() = Unit

    override suspend fun shutdown() = Unit

    override suspend fun cameraPreview(): CameraPreview = CameraPreview.CaptureNotEnabled

    override suspend fun prepareReplay(replayId: String): String =
        throw UnsupportedOperationException("Replays need a real OpenFlight Pi.")

    private fun valid(name: String): String =
        when (val check = ProfileRules.checkName(name)) {
            is ProfileNameCheck.Valid -> check.name
            ProfileNameCheck.Blank -> throw ProfileRuleException(ProfileRuleException.Rule.BLANK_NAME)
            ProfileNameCheck.TooLong -> throw ProfileRuleException(ProfileRuleException.Rule.NAME_TOO_LONG)
        }
}
