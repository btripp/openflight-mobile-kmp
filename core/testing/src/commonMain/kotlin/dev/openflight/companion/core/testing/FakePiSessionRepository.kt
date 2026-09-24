// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.testing

import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.WifiOnlyFeatureException
import dev.openflight.companion.core.model.pi.CameraStatus
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.RadarConfigUpdate
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.SimState
import dev.openflight.companion.core.model.pi.SwingSpeedReading
import dev.openflight.companion.core.model.pi.TrainingImplement
import dev.openflight.companion.core.model.pi.TriggerStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A [PiSessionRepository] whose flows tests set directly. Commands are recorded in [commands]
 * (`"delete_shot:<timestamp>"`, `"set_player:<name>"`, ...) and, like the real repository, throw
 * [WifiOnlyFeatureException] unless [linkState] is [PiLinkState.Connected].
 */
@Suppress("TooManyFunctions") // Mirrors the PiSessionRepository surface.
class FakePiSessionRepository(
    link: PiLinkState = PiLinkState.Connected,
) : PiSessionRepository {
    override val linkState = MutableStateFlow(link)
    override val sessionShots = MutableStateFlow(emptyList<ShotDetail>())
    override val shotDetails = MutableStateFlow(emptyMap<String, ShotDetail>())
    override val stats = MutableStateFlow<SessionStats?>(null)
    override val playerName = MutableStateFlow<String?>(null)
    override val trainingImplement = MutableStateFlow<TrainingImplement?>(null)
    override val latestSwingSpeed = MutableStateFlow<SwingSpeedReading?>(null)
    override val triggerStatus = MutableStateFlow<TriggerStatus?>(null)
    override val cameraStatus = MutableStateFlow(CameraStatus())
    override val simState = MutableStateFlow(SimState())
    override val radarConfig = MutableStateFlow<RadarConfig?>(null)
    override val debugState = MutableStateFlow(DebugState())
    override val cloudUploadStatus = MutableStateFlow(CloudUploadStatus())
    override val mockMode = MutableStateFlow<Boolean?>(null)
    override val notices = MutableSharedFlow<PiNotice>(extraBufferCapacity = 16)

    /** Every command that reached the "server", in order. */
    val commands = mutableListOf<String>()

    /** What [cameraFrames] returns; each call counts in [cameraFrameCollections]. */
    var frames: Flow<ByteArray> = emptyFlow()
    var cameraFrameCollections = 0
        private set

    var started = false
        private set

    /** Sets [sessionShots] (newest first) and indexes them in [shotDetails], like a `session_state`. */
    fun setSession(newestFirst: List<ShotDetail>) {
        sessionShots.value = newestFirst
        shotDetails.value = shotDetails.value + newestFirst.associateBy { it.timestamp }
    }

    override fun start() {
        started = true
    }

    override fun stop() {
        started = false
    }

    override suspend fun refreshSession() = record("get_session")

    override suspend fun deleteShot(timestamp: String) = record("delete_shot:$timestamp")

    override suspend fun clearSession() = record("clear_session")

    override suspend fun simulateShot() = record("simulate_shot")

    override suspend fun setPlayer(name: String) = record("set_player:$name")

    override suspend fun setTrainingImplement(implement: String) = record("set_training_implement:$implement")

    override suspend fun toggleCamera() = record("toggle_camera")

    override suspend fun toggleCameraStream() = record("toggle_camera_stream")

    override suspend fun refreshCameraStatus() = record("get_camera_status")

    override suspend fun refreshRadarConfig() = record("get_radar_config")

    override suspend fun setRadarConfig(update: RadarConfigUpdate) = record("set_radar_config:$update")

    override suspend fun toggleDebug() = record("toggle_debug")

    override suspend fun uploadCloud() = record("upload_cloud")

    override suspend fun shutdown() = record("shutdown")

    override fun cameraFrames(): Flow<ByteArray> {
        cameraFrameCollections++
        return frames
    }

    private fun record(command: String) {
        when (linkState.value) {
            PiLinkState.Connected -> commands += command
            PiLinkState.WifiOnly -> throw WifiOnlyFeatureException(WifiOnlyFeatureException.Reason.BLUETOOTH)
            else -> throw WifiOnlyFeatureException(WifiOnlyFeatureException.Reason.NOT_CONNECTED)
        }
    }
}
