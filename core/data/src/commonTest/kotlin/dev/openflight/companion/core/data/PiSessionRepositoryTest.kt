// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import app.cash.turbine.test
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import assertk.assertions.startsWith
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.CameraStatus
import dev.openflight.companion.core.model.pi.CloudUploadState
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.RadarConfigUpdate
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.socketio.SocketConnectionState
import dev.openflight.companion.core.socketio.SocketEvent
import dev.openflight.companion.core.socketio.SocketNotConnectedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PiSessionRepositoryTest {
    private class Harness(
        scope: CoroutineScope,
        transport: TransportType,
        host: String,
    ) {
        val settings = FakeSettingsRepository(transport = transport, host = host)
        val sockets = mutableListOf<FakePiSocket>()
        val cameraHosts = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val repository =
            DefaultPiSessionRepository(
                settings = settings,
                socketFactory = { socketHost, _ -> FakePiSocket(socketHost).also { sockets += it } },
                cameraSource = { cameraHost ->
                    cameraHosts += cameraHost
                    flowOf(byteArrayOf(1), byteArrayOf(2))
                },
                scope = scope,
                log = { logs += it },
            )

        val socket: FakePiSocket get() = sockets.last()

        /** Connects the current socket and forgets the automatic on-connect requests. */
        fun connected(): FakePiSocket =
            socket.apply {
                serverAcks()
                emitted.clear()
            }
    }

    private fun runPiTest(
        transport: TransportType = TransportType.WIFI,
        host: String = "pi.local:8080",
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness(backgroundScope, transport, host)
        harness.repository.start()
        body(harness)
    }

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
    fun onConnectItRequestsSessionTriggerStatusAndRadarConfigLikeTheWebUi() =
        runPiTest { h ->
            h.socket.serverAcks()

            assertThat(h.socket.emittedNames).containsExactly("get_session", "get_trigger_status", "get_radar_config")
        }

    @Test
    fun aReconnectRequestsTheInitialStateAgain() =
        runPiTest { h ->
            h.socket.serverAcks()
            h.socket.state.value = SocketConnectionState.Reconnecting(1, 1_000, "Connection closed")
            assertThat(h.repository.linkState.value).isEqualTo(PiLinkState.Reconnecting(1, 1_000, "Connection closed"))

            h.socket.serverAcks()

            assertThat(h.socket.emittedNames.count { it == "get_session" }).isEqualTo(2)
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
                    { h.repository.clearSession() },
                    { h.repository.setPlayer("Ann") },
                    { h.repository.toggleCamera() },
                    { h.repository.uploadCloud() },
                    { h.repository.shutdown() },
                )
            for (command in commands) {
                assertFailure { command() }
                    .isInstanceOf(WifiOnlyFeatureException::class)
                    .prop(WifiOnlyFeatureException::reason)
                    .isEqualTo(WifiOnlyFeatureException.Reason.BLUETOOTH)
            }
            assertFailure { h.repository.cameraFrames().toList() }.isInstanceOf(WifiOnlyFeatureException::class)
        }

    @Test
    fun commandsFailWithNotConnectedUntilTheServerAcks() =
        runPiTest { h ->
            assertFailure { h.repository.simulateShot() }
                .isInstanceOf(WifiOnlyFeatureException::class)
                .prop(WifiOnlyFeatureException::reason)
                .isEqualTo(WifiOnlyFeatureException.Reason.NOT_CONNECTED)
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
    fun aHostChangeReconnectsToTheNewHostWithAFreshSession() =
        runPiTest { h ->
            h.connected().serverFrame(PiFixtures.SHOT_FRAME)

            h.settings.hostState.value = "10.0.0.9:8080"

            assertThat(h.sockets.map { it.host }).containsExactly("pi.local:8080", "10.0.0.9:8080")
            assertThat(h.sockets.first().disconnectCount).isEqualTo(1)
            assertThat(h.repository.sessionShots.value).isEmpty()
        }

    @Test
    fun stopDisconnectsButKeepsTheLastState() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)

            h.repository.stop()

            assertThat(socket.disconnectCount).isEqualTo(1)
            assertThat(h.repository.linkState.value).isEqualTo(PiLinkState.Idle)
            assertThat(h.repository.sessionShots.value.size).isEqualTo(1)
        }

    @Test
    fun anUnusableHostLeavesTheLinkIdle() =
        runTest(UnconfinedTestDispatcher()) {
            val repository =
                DefaultPiSessionRepository(
                    settings = FakeSettingsRepository(transport = TransportType.WIFI, host = " "),
                    socketFactory = { _, _ -> null },
                    cameraSource = { flowOf() },
                    scope = backgroundScope,
                )
            repository.start()

            assertThat(repository.linkState.value).isEqualTo(PiLinkState.Idle)
        }

    // endregion

    // region session state

    @Test
    fun theOnConnectSessionStateFillsSessionStatsPlayerMockModeAndCamera() =
        runPiTest { h ->
            h.connected().serverFrame(PiFixtures.CONNECT_SESSION_STATE_FRAME)

            assertThat(h.repository.sessionShots.value).isEmpty()
            assertThat(h.repository.stats.value).isEqualTo(SessionStats.EMPTY)
            assertThat(h.repository.playerName.value).isEqualTo("Player 1")
            assertThat(h.repository.mockMode.value).isEqualTo(true)
            assertThat(h.repository.cameraStatus.value).isEqualTo(CameraStatus(available = false))
            assertThat(h.repository.debugState.value.enabled).isFalse()
        }

    @Test
    fun sessionStateListsShotsNewestFirst() =
        runPiTest { h ->
            h.connected().serverFrame(
                """42["session_state",{"stats":{"shot_count":2},"shots":[{"timestamp":"a"},{"timestamp":"b"}]}]""",
            )

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
                    ballSpeedMph = 143.3,
                    estimatedCarryYards = 243.0,
                )

            val detail = h.repository.detailFor(sseShot)

            assertThat(detail?.launchAngleConfidence).isEqualTo(0.72)
            assertThat(detail?.spinQuality).isEqualTo("medium")
            assertThat(h.repository.detailFor(sseShot.copy(timestamp = "2026-09-24T15:38:33.264796"))).isNull()
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
    fun sessionClearedEmptiesTheSessionButKeepsTheEnrichmentIndex() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)

            socket.server("session_cleared")

            assertThat(h.repository.sessionShots.value).isEmpty()
            assertThat(h.repository.stats.value).isEqualTo(SessionStats.EMPTY)
            assertThat(h.repository.shotDetails.value.keys).containsOnly(PiFixtures.SHOT_TIMESTAMP)
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
    fun playerAndTrainingImplementChanges() =
        runPiTest { h ->
            val socket = h.connected()
            socket.server("player_changed", """{"player_name":"Ann"}""")
            socket.server("training_implement_changed", """{"implement":"stack-100g","label":"Stack 100g"}""")

            assertThat(h.repository.playerName.value).isEqualTo("Ann")
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
            socket.server("player_changed", """{"player_name":"Ann"}""")

            assertThat(h.logs.single()).startsWith("Dropped malformed 'shot'")
            assertThat(h.repository.playerName.value).isEqualTo("Ann")
        }

    // endregion

    // region devices, debug, sim, cloud

    @Test
    fun cameraStatusMergesPartialUpdatesAndBallDetection() =
        runPiTest { h ->
            val socket = h.connected()
            socket.server("camera_status", """{"enabled":true,"available":true,"streaming":false}""")
            socket.server("ball_detection", """{"detected":true,"confidence":0.83}""")
            socket.server("camera_status", """{"enabled":true,"available":true,"streaming":true}""")

            assertThat(h.repository.cameraStatus.value).isEqualTo(
                CameraStatus(
                    available = true,
                    enabled = true,
                    streaming = true,
                    ballDetected = true,
                    ballConfidence = 0.83,
                ),
            )

            socket.server("camera_status", """{"enabled":false,"available":false,"error":"Camera not initialized"}""")
            assertThat(h.repository.cameraStatus.value.error).isEqualTo("Camera not initialized")
            assertThat(h.repository.cameraStatus.value.available).isFalse()
        }

    @Test
    fun triggerDiagnosticsAppendAndCountLikeTheWebDebugStore() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.TRIGGER_STATUS_FRAME)
            socket.server("trigger_diagnostic", """{"accepted":true,"reason":"ok"}""")
            socket.server("trigger_diagnostic", """{"accepted":false,"reason":"too slow"}""")

            assertThat(h.repository.triggerStatus.value).isEqualTo(
                TriggerStatus(mode = "mock", triggersTotal = 2, triggersAccepted = 1, triggersRejected = 1),
            )
            assertThat(
                h.repository.debugState.value.triggerDiagnostics
                    .map { it.reason },
            ).containsExactly("ok", "too slow")
        }

    @Test
    fun debugFeedsFillWhileEnabledAndClearWhenDisabled() =
        runPiTest { h ->
            val socket = h.connected()
            socket.server("debug_toggled", """{"enabled":true,"log_path":"/home/pi/openflight_logs/debug.jsonl"}""")
            repeat(55) { socket.server("debug_reading", """{"speed":$it,"direction":"outbound","magnitude":1}""") }
            socket.server(
                "debug_shot",
                """{"type":"shot","radar":{"ball_speed_mph":140.1},"camera":null,"club":"driver"}""",
            )

            val enabled = h.repository.debugState.value
            assertThat(enabled.enabled).isTrue()
            assertThat(enabled.logPath).isEqualTo("/home/pi/openflight_logs/debug.jsonl")
            assertThat(enabled.readings.size).isEqualTo(50)
            assertThat(enabled.readings.first().speed).isEqualTo(5.0)
            assertThat(
                enabled.shotLogs
                    .single()
                    .radar
                    ?.ballSpeedMph,
            ).isEqualTo(140.1)

            socket.server("debug_toggled", """{"enabled":false}""")

            val disabled = h.repository.debugState.value
            assertThat(disabled.enabled).isFalse()
            assertThat(disabled.readings).isEmpty()
            assertThat(disabled.shotLogs).isEmpty()
        }

    @Test
    fun simStatusIsKeptPerTargetWithTheLatestShotAndPlayer() =
        runPiTest { h ->
            val socket = h.connected()
            socket.server("sim_status", """{"target":"gspro","state":"connecting","host":"10.0.0.2","port":921}""")
            socket.server("sim_status", """{"target":"gspro","state":"connected","host":"10.0.0.2","port":921}""")
            socket.server(
                "sim_shot",
                """{"target":"gspro","shot_number":3,"fields":["BallSpeed"],"values":{"BallSpeed":143.3},""" +
                    """"provenance":{"BallSpeed":"measured"}}""",
            )
            socket.server("sim_player", """{"target":"gspro","handed":"RH","club":"7-iron"}""")

            val sim = h.repository.simState.value
            assertThat(sim.connectors.keys).containsOnly("gspro")
            assertThat(sim.connectors.getValue("gspro").state).isEqualTo("connected")
            assertThat(sim.latestShot?.shotNumber).isEqualTo(3)
            assertThat(sim.latestPlayer?.club).isEqualTo("7-iron")
        }

    @Test
    fun radarConfigAndCloudStatusMirrorTheServer() =
        runPiTest { h ->
            val socket = h.connected()
            socket.server("radar_config", """{"min_speed":10,"max_speed":220,"min_magnitude":0,"transmit_power":0}""")
            socket.server("cloud_upload_status", """{"state":"complete","message":"Nothing to upload.","summary":{}}""")

            assertThat(h.repository.radarConfig.value).isEqualTo(RadarConfig(minSpeed = 10, maxSpeed = 220))
            assertThat(h.repository.cloudUploadStatus.value.message).isEqualTo("Nothing to upload.")
        }

    @Test
    fun serverErrorsArriveAsNotices() =
        runPiTest { h ->
            val socket = h.connected()
            h.repository.notices.test {
                socket.server("delete_shot_error", """{"error":"Shot not found"}""")
                socket.server("radar_config_error", """{"error":"Radar not connected"}""")

                assertThat(awaitItem()).isEqualTo(PiNotice.DeleteShotFailed("Shot not found"))
                assertThat(awaitItem()).isEqualTo(PiNotice.RadarConfigFailed("Radar not connected"))
            }
        }

    // endregion

    // region commands

    @Test
    fun commandsEmitTheWebUisEventNamesAndPayloads() =
        runPiTest { h ->
            val socket = h.connected()
            val repository = h.repository

            repository.simulateShot()
            repository.deleteShot(PiFixtures.SHOT_TIMESTAMP)
            repository.clearSession()
            repository.setPlayer("Ann")
            repository.setTrainingImplement("stack-100g")
            repository.toggleCamera()
            repository.toggleCameraStream()
            repository.refreshCameraStatus()
            repository.refreshRadarConfig()
            repository.toggleDebug()
            repository.refreshSession()
            repository.shutdown()

            assertThat(socket.emitted).containsExactly(
                "simulate_shot" to null,
                "delete_shot" to buildJsonObject { put("timestamp", PiFixtures.SHOT_TIMESTAMP) },
                "clear_session" to null,
                "set_player" to buildJsonObject { put("player_name", "Ann") },
                "set_training_implement" to buildJsonObject { put("implement", "stack-100g") },
                "toggle_camera" to null,
                "toggle_camera_stream" to null,
                "get_camera_status" to null,
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
            assertThat(
                h.repository.cloudUploadStatus.value,
            ).isEqualTo(CloudUploadStatus(CloudUploadState.RUNNING, "Uploading..."))
        }

    @Test
    fun commandsFailWithNotConnectedWhileReconnecting() =
        runPiTest { h ->
            h.connected().state.value = SocketConnectionState.Reconnecting(1, 1_000, "Connection closed")

            assertFailure { h.repository.clearSession() }
                .isInstanceOf(WifiOnlyFeatureException::class)
                .prop(WifiOnlyFeatureException::reason)
                .isEqualTo(WifiOnlyFeatureException.Reason.NOT_CONNECTED)
        }

    @Test
    fun cameraFramesStreamFromTheCurrentHost() =
        runPiTest { h ->
            val frames: Flow<ByteArray> = h.repository.cameraFrames()

            assertThat(frames.toList().map { it.toList() }).containsExactly(listOf<Byte>(1), listOf<Byte>(2))
            assertThat(h.cameraHosts).containsExactly("pi.local:8080")
        }

    // endregion
}
