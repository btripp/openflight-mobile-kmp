// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import app.cash.turbine.test
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import assertk.assertions.startsWith
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.CameraPreview
import dev.openflight.companion.core.model.pi.CameraReplay
import dev.openflight.companion.core.model.pi.CloudUploadState
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.PowerState
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.RadarConfigUpdate
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotProcessingState
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.socketio.SocketConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

/**
 * [DefaultPiSessionRepository] against a scripted socket: the link lifecycle, every event the Pi
 * sends and every command. Frames are the captured/hand-built [PiFixtures]. The connect/reconnect,
 * connected-only emit and `shot_update` cases port the Expo app's `socket.test.ts` and
 * `useSessionStore.test.ts`; the club, roster and device cases are in [PiSessionContextTest], the
 * delete and clear cases (`feat/delete-shot`, `feat/stats-tab`) in [PiSessionDeleteAndClearTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PiSessionRepositoryTest {
    // region link and transport

    @Test
    fun onWifiItConnectsASocketToTheConfiguredHost() =
        runPiTest { h ->
            assertThat(h.sockets.map { it.host }).containsExactly("pi.local:8080")
            assertThat(h.socket.connectCount).isEqualTo(1)
            assertThat(h.repository.linkState.value).isEqualTo(PiLinkState.Connecting)

            h.socket.serverAcks()

            assertThat(h.repository.linkState.value).isEqualTo(PiLinkState.Connected)
        }

    @Test
    fun onConnectItRequestsEverythingTheServerDoesNotPush() =
        runPiTest { h ->
            h.socket.serverAcks()

            assertThat(h.socket.emittedNames).containsExactly(
                "get_session",
                "get_trigger_status",
                "get_radar_config",
                "get_debug_status",
                "get_profiles",
                "get_camera_capture_settings",
            )
        }

    @Test
    fun aReconnectRequestsTheInitialStateAgain() =
        runPiTest { h ->
            h.connected()
            h.drop()
            assertThat(h.repository.linkState.value).isEqualTo(PiLinkState.Reconnecting(1, 500, "Connection closed"))

            h.socket.serverAcks()

            // The hardware, the roster or the debug mode may have changed while the phone was away.
            assertThat(h.socket.emittedNames).containsExactly(
                "get_session",
                "get_trigger_status",
                "get_radar_config",
                "get_debug_status",
                "get_profiles",
                "get_camera_capture_settings",
            )
        }

    @Test
    fun anUnchangedHostOrARepeatedStartKeepsTheOneSocket() =
        runPiTest { h ->
            h.connected()

            h.repository.start()
            h.settings.hostState.value = "pi.local:8080"

            assertThat(h.sockets.size).isEqualTo(1)
            assertThat(h.socket.disconnectCount).isEqualTo(0)
        }

    @Test
    fun onBluetoothTheLinkIsWifiOnlyAndNoSocketIsOpened() =
        runPiTest(transport = TransportType.BLUETOOTH) { h ->
            assertThat(h.sockets).isEmpty()
            assertThat(h.repository.linkState.value).isEqualTo(PiLinkState.WifiOnly)
        }

    @Test
    fun onBluetoothEveryCommandFailsWithWifiOnly() =
        runPiTest(transport = TransportType.BLUETOOTH) { h ->
            val commands: List<suspend () -> Unit> =
                listOf(
                    { h.repository.simulateShot() },
                    { h.repository.deleteShot("t") },
                    { h.repository.clearSession("p1") },
                    { h.repository.setActiveProfile("p1") },
                    { h.repository.addProfile("Sam") },
                    { h.repository.cameraPreview() },
                    { h.repository.prepareReplay("r1") },
                    { h.repository.uploadCloud() },
                    { h.repository.shutdown() },
                )
            for (command in commands) {
                assertFailure { command() }
                    .isInstanceOf(WifiOnlyFeatureException::class)
                    .prop(WifiOnlyFeatureException::reason)
                    .isEqualTo(WifiOnlyFeatureException.Reason.BLUETOOTH)
            }
        }

    @Test
    fun commandsFailWithNotConnectedUntilTheServerAcks() =
        runPiTest { h ->
            assertFailure { h.repository.simulateShot() }
                .isInstanceOf(WifiOnlyFeatureException::class)
                .prop(WifiOnlyFeatureException::reason)
                .isEqualTo(WifiOnlyFeatureException.Reason.NOT_CONNECTED)
            assertThat(h.socket.emitted).isEmpty()
        }

    @Test
    fun nothingTriedDuringATransientDropIsReplayedAfterTheReconnect() =
        runPiTest { h ->
            h.connected()
            h.drop()
            val commands: List<suspend () -> Unit> =
                listOf(
                    { h.repository.setActiveProfile("p2") },
                    { h.repository.addProfile("Sam") },
                    { h.repository.renameProfile("p1", "Alexandra") },
                    { h.repository.removeProfile("p2") },
                    { h.repository.toggleDebug() },
                    { h.repository.deleteShot("t") },
                    { h.repository.clearSession("p1") },
                )
            for (command in commands) {
                assertFailure { command() }
                    .isInstanceOf(WifiOnlyFeatureException::class)
                    .prop(WifiOnlyFeatureException::reason)
                    .isEqualTo(WifiOnlyFeatureException.Reason.NOT_CONNECTED)
            }

            h.socket.serverAcks()

            // Only the on-connect re-sync: none of the mutations went out late.
            assertThat(h.socket.emittedNames).containsExactly(
                "get_session",
                "get_trigger_status",
                "get_radar_config",
                "get_debug_status",
                "get_profiles",
                "get_camera_capture_settings",
            )
            h.repository.setActiveProfile("p2")
            assertThat(h.socket.emitted.last()).isEqualTo(
                "set_active_profile" to buildJsonObject { put("profile_id", "p2") },
            )
        }

    @Test
    fun switchingToBluetoothDisconnectsAndClearsTheSession() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            assertThat(h.repository.sessionShots.value.size).isEqualTo(1)

            h.settings.transportState.value = TransportType.BLUETOOTH

            assertThat(socket.disconnectCount).isEqualTo(1)
            assertThat(h.repository.linkState.value).isEqualTo(PiLinkState.WifiOnly)
            assertThat(h.repository.sessionShots.value).isEmpty()
            assertThat(h.repository.shotDetails.value).isEmpty()
            assertThat(h.repository.stats.value).isNull()
        }

    @Test
    fun aHostChangeForgetsEverythingThePreviousPiReported() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            socket.serverFrame(PiFixtures.PROFILES_AFTER_ADD_FRAME)
            socket.serverFrame(PiFixtures.POWER_STATUS_FRAME)
            socket.serverFrame(PiFixtures.CLUB_CHANGED_FRAME)
            socket.serverFrame(PiFixtures.TRIGGER_STATUS_FRAME)
            socket.serverFrame(PiFixtures.DEBUG_STATUS_FRAME)
            socket.serverFrame(PiFixtures.SHOT_PROCESSING_FRAME)
            // Everything was showing first, or "empty afterwards" would prove nothing.
            assertThat(h.repository.profiles.value.loaded).isTrue()
            assertThat(h.repository.powerStatus.value).isNotNull()

            h.settings.hostState.value = "10.0.0.9:8080"

            assertThat(h.sockets.map { it.host }).containsExactly("pi.local:8080", "10.0.0.9:8080")
            assertThat(h.sockets.first().disconnectCount).isEqualTo(1)
            assertThat(h.repository.sessionShots.value).isEmpty()
            assertThat(h.repository.profiles.value.profiles).isEmpty()
            assertThat(h.repository.profiles.value.loaded).isFalse()
            assertThat(h.repository.powerStatus.value).isNull()
            assertThat(h.repository.club.value).isNull()
            assertThat(h.repository.triggerStatus.value).isNull()
            assertThat(h.repository.debugState.value.loaded).isFalse()
            assertThat(h.repository.shotProcessing.value).isNull()
        }

    @Test
    fun aTransientDropKeepsTheRosterPowerClubAndDeviceStatus() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.PROFILES_AFTER_ADD_FRAME)
            socket.serverFrame(PiFixtures.POWER_STATUS_FRAME)
            socket.serverFrame(PiFixtures.CLUB_CHANGED_FRAME)
            socket.serverFrame(PiFixtures.TRIGGER_STATUS_FRAME)

            h.drop()

            assertThat(h.repository.profiles.value.profiles.size).isEqualTo(2)
            assertThat(
                h.repository.powerStatus.value
                    ?.provider,
            ).isEqualTo("geekworm")
            assertThat(h.repository.club.value).isEqualTo("7-iron")
            assertThat(h.repository.triggerStatus.value).isNotNull()
        }

    @Test
    fun stopDisconnectsButKeepsTheLastState() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            socket.serverFrame(PiFixtures.PROFILES_AFTER_ADD_FRAME)

            h.repository.stop()

            assertThat(socket.disconnectCount).isEqualTo(1)
            assertThat(h.repository.linkState.value).isEqualTo(PiLinkState.Idle)
            assertThat(h.repository.sessionShots.value.size).isEqualTo(1)
            assertThat(h.repository.profiles.value.loaded).isTrue()
        }

    @Test
    fun anUnusableHostLeavesTheLinkIdle() =
        runTest(UnconfinedTestDispatcher()) {
            val repository =
                DefaultPiSessionRepository(
                    settings = FakeSettingsRepository(transport = TransportType.WIFI, host = " "),
                    socketFactory = { _, _ -> null },
                    cameraSource = FakePiCameraSource(),
                    scope = backgroundScope,
                )
            repository.start()

            assertThat(repository.linkState.value).isEqualTo(PiLinkState.Idle)
        }

    // endregion

    // region session state and shots

    @Test
    fun theOnConnectSessionStateFillsSessionStatsMockModeDebugAndClub() =
        runPiTest { h ->
            h.connected().serverFrame(PiFixtures.CONNECT_SESSION_STATE_FRAME)

            assertThat(h.repository.sessionShots.value).isEmpty()
            assertThat(
                h.repository.stats.value
                    ?.shotCount,
            ).isEqualTo(0)
            assertThat(h.repository.mockMode.value).isEqualTo(true)
            assertThat(h.repository.club.value).isEqualTo("driver")
            assertThat(h.repository.debugState.value.enabled).isFalse()
            assertThat(h.repository.debugState.value.loaded).isTrue()
        }

    @Test
    fun sessionStateListsShotsNewestFirstAcrossProfiles() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            socket.serverFrame(PiFixtures.SECOND_SHOT_FRAME)
            socket.server("session_state", """{"stats":{},"shots":[{"timestamp":"a"},{"timestamp":"b"}]}""")

            assertThat(
                h.repository.sessionShots.value
                    .map { it.timestamp },
            ).containsExactly("b", "a")
        }

    @Test
    fun aShotIsPrependedIndexedByTimestampAndUpdatesStats() =
        runPiTest { h ->
            h.connected().serverFrame(PiFixtures.SHOT_FRAME)

            val shot =
                h.repository.sessionShots.value
                    .single()
            assertThat(shot.timestamp).isEqualTo(PiFixtures.SHOT_TIMESTAMP)
            assertThat(h.repository.shotDetails.value.keys).containsOnly(PiFixtures.SHOT_TIMESTAMP)
            assertThat(
                h.repository.stats.value
                    ?.shotCount,
            ).isEqualTo(1)
        }

    @Test
    fun detailForMatchesAnSseShotOnItsTimestamp() =
        runPiTest { h ->
            h.connected().serverFrame(PiFixtures.SHOT_FRAME)
            val sseShot =
                ShotEvent(
                    schemaVersion = 1,
                    eventId = "8f6dbe50-eb69-4a3e-b343-41731776e82f",
                    timestamp = PiFixtures.SHOT_TIMESTAMP,
                    club = "driver",
                    ballSpeedMph = 116.2,
                    estimatedCarryYards = 179.0,
                )

            val detail = h.repository.detailFor(sseShot)

            assertThat(detail?.launchAngleConfidence).isEqualTo(0.66)
            assertThat(detail?.spinQuality).isEqualTo("high")
            assertThat(detail?.profileName).isEqualTo("Profile 1")
            assertThat(h.repository.detailFor(sseShot.copy(timestamp = "2026-09-25T10:03:35.906613"))).isNull()
        }

    @Test
    fun theSameShotTwiceIsNotDuplicated() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            socket.serverFrame(PiFixtures.SHOT_FRAME)

            assertThat(h.repository.sessionShots.value.size).isEqualTo(1)
        }

    @Test
    fun anEnrichedUpdateReplacesItsShotInPlace() =
        runPiTest { h ->
            // Expo: "shows one shot, with the final measurements, across the whole sequence".
            val socket = h.connected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)

            socket.serverFrame(PiFixtures.SHOT_UPDATE_FRAME)

            val shot =
                h.repository.sessionShots.value
                    .single()
            assertThat(shot.iwr6843HorizontalDeg).isEqualTo(2.1)
            assertThat(h.repository.detailFor(sseShotAt(PiFixtures.SHOT_TIMESTAMP))?.iwr6843HorizontalDeg)
                .isEqualTo(2.1)
        }

    @Test
    fun aSkippedEnrichmentUpdateKeepsOneRow() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)

            socket.serverFrame(PiFixtures.SHOT_UPDATE_SKIPPED_FRAME)

            assertThat(h.repository.sessionShots.value.size).isEqualTo(1)
        }

    @Test
    fun anUpdateForAShotThatArrivedBeforeThisPhoneConnectedIsKept() =
        runPiTest { h ->
            h.connected().serverFrame(PiFixtures.SHOT_UPDATE_FRAME)

            assertThat(
                h.repository.sessionShots.value
                    .single()
                    .shotNumber,
            ).isEqualTo(1)
        }

    @Test
    fun anUpdateForAnEarlierShotLeavesTheOrderAlone() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            socket.serverFrame(PiFixtures.SECOND_SHOT_FRAME)

            socket.serverFrame(PiFixtures.SHOT_UPDATE_FRAME)

            assertThat(
                h.repository.sessionShots.value
                    .map { it.shotNumber },
            ).containsExactly(2, 1)
            assertThat(
                h.repository.sessionShots.value
                    .last()
                    .iwr6843HorizontalDeg,
            ).isEqualTo(2.1)
        }

    @Test
    fun anUnnumberedUpdateMatchesOnlyItsOwnTimestamp() =
        runPiTest { h ->
            // Swing-speed rows carry no shot_number; two unnumbered rows aren't the same shot.
            val socket = h.connected()
            socket.server("shot", """{"shot":{"timestamp":"t1","club":"driver"}}""")
            socket.serverFrame(PiFixtures.SHOT_FRAME)

            socket.server("shot_update", """{"shot":{"timestamp":"t9","club":"7 iron"}}""")
            socket.server("shot_update", """{"shot":{"timestamp":"t1","club":"Stack 100g"}}""")

            val shots = h.repository.sessionShots.value
            assertThat(shots.map { it.timestamp }).containsExactly("t9", PiFixtures.SHOT_TIMESTAMP, "t1")
            assertThat(shots.last().club).isEqualTo("Stack 100g")
            assertThat(shots[1].club).isEqualTo("driver")
        }

    @Test
    fun theProcessingIndicatorLastsUntilTheNextShot() =
        runPiTest { h ->
            val socket = h.connected()
            socket.server("shot_processing", """{"state":"capturing"}""")
            assertThat(h.repository.shotProcessing.value).isEqualTo(ShotProcessingState.CAPTURING)
            socket.serverFrame(PiFixtures.SHOT_PROCESSING_FRAME)
            assertThat(h.repository.shotProcessing.value).isEqualTo(ShotProcessingState.CALCULATING)

            socket.serverFrame(PiFixtures.SHOT_FRAME)

            assertThat(h.repository.shotProcessing.value).isNull()
        }

    @Test
    fun theEnrichmentIndexKeepsTheMostRecentTwoHundred() =
        runPiTest { h ->
            val socket = h.connected()
            val shots = (0 until 205).joinToString(",") { """{"timestamp":"t$it"}""" }
            socket.serverFrame("""42["session_state",{"stats":{},"shots":[$shots]}]""")

            val keys = h.repository.shotDetails.value.keys
            assertThat(keys.size).isEqualTo(200)
            assertThat(keys.first()).isEqualTo("t5")
            assertThat(
                h.repository.sessionShots.value
                    .first()
                    .timestamp,
            ).isEqualTo("t204")
            assertThat(h.repository.sessionShots.value.size).isEqualTo(200)
        }

    @Test
    fun swingSpeedTracksTheLatestRepAndStats() =
        runPiTest { h ->
            h.connected().serverFrame(PiFixtures.SWING_SPEED_FRAME)

            assertThat(
                h.repository.latestSwingSpeed.value
                    ?.peakSpeedMph,
            ).isEqualTo(97.4)
            assertThat(
                h.repository.stats.value
                    ?.avgClubSpeed,
            ).isEqualTo(97.4)
        }

    @Test
    fun trainingImplementChanges() =
        runPiTest { h ->
            h.connected().serverFrame(PiFixtures.TRAINING_IMPLEMENT_CHANGED_FRAME)

            assertThat(
                h.repository.trainingImplement.value
                    ?.label,
            ).isEqualTo("Stack 100g")
        }

    @Test
    fun aMalformedEventIsLoggedAndLaterEventsStillApply() =
        runPiTest { h ->
            val socket = h.connected()
            socket.server("shot", "\"nope\"")
            socket.serverFrame(PiFixtures.TRAINING_IMPLEMENT_CHANGED_FRAME)

            assertThat(h.logs.single()).startsWith("Dropped malformed 'shot'")
            assertThat(h.repository.trainingImplement.value).isNotNull()
        }

    // endregion

    // region commands

    @Test
    fun commandsEmitTheServersEventNamesAndPayloads() =
        runPiTest { h ->
            val socket = h.connected()
            val repository = h.repository

            repository.simulateShot()
            repository.setTrainingImplement("stack-100g")
            repository.refreshCameraCaptureSettings()
            repository.refreshRadarConfig()
            repository.toggleDebug()
            repository.refreshSession()
            repository.shutdown()

            assertThat(socket.emitted).containsExactly(
                "simulate_shot" to null,
                "set_training_implement" to buildJsonObject { put("implement", "stack-100g") },
                "get_camera_capture_settings" to null,
                "get_radar_config" to null,
                "toggle_debug" to null,
                "get_session" to null,
                "shutdown" to null,
            )
        }

    @Test
    fun setRadarConfigSendsOnlyTheChangedFields() =
        runPiTest { h ->
            val socket = h.connected()

            h.repository.setRadarConfig(RadarConfigUpdate(maxSpeed = 0, transmitPower = 3))

            assertThat(socket.emitted.single()).isEqualTo(
                "set_radar_config" to
                    buildJsonObject {
                        put("max_speed", JsonPrimitive(0))
                        put("transmit_power", JsonPrimitive(3))
                    },
            )
        }

    @Test
    fun uploadCloudShowsRunningImmediatelyLikeTheWebUi() =
        runPiTest { h ->
            val socket = h.connected()

            h.repository.uploadCloud()

            assertThat(socket.emittedNames).containsExactly("upload_cloud")
            assertThat(h.repository.cloudUploadStatus.value)
                .isEqualTo(CloudUploadStatus(CloudUploadState.RUNNING, "Uploading..."))
        }

    @Test
    fun theCameraPreviewAndReplaysUseTheWifiHostOverHttp() =
        runPiTest { h ->
            // Plain HTTP: no Socket.IO ack needed.
            assertThat(h.repository.cameraPreview()).isEqualTo(CameraPreview.Frame(byteArrayOf(1, 2)))
            h.camera.preview = CameraPreview.CameraNotRunning
            assertThat(h.repository.cameraPreview()).isEqualTo(CameraPreview.CameraNotRunning)

            assertThat(h.repository.prepareReplay("r1")).isEqualTo("http://pi.local:8080/api/camera/replays/r1/video")
            assertThat(h.camera.hosts).containsExactly("pi.local:8080", "pi.local:8080", "pi.local:8080")
        }

    @Test
    fun aReplayWithoutAVideoUrlFails() =
        runPiTest { h ->
            h.camera.replay = { CameraReplay(id = it) }

            assertFailure { h.repository.prepareReplay("r1") }.isInstanceOf(IllegalStateException::class)
        }

    @Test
    fun sessionStatsAfterAPerProfileClearAreUnknownUnlessNothingIsLeft() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)

            socket.serverFrame(PiFixtures.SESSION_CLEARED_FRAME)
            assertThat(h.repository.stats.value).isNull()

            socket.server("session_cleared", """{"profile_id":"x","shots":[]}""")
            assertThat(h.repository.stats.value).isEqualTo(SessionStats.EMPTY)
            assertThat(h.repository.shotDetails.value.keys).doesNotContain("x")
        }

    // endregion

    private fun sseShotAt(timestamp: String): ShotEvent =
        ShotEvent(
            schemaVersion = 1,
            eventId = "8f6dbe50-eb69-4a3e-b343-41731776e82f",
            timestamp = timestamp,
            club = "driver",
            ballSpeedMph = 116.2,
            estimatedCarryYards = 179.0,
        )
}
