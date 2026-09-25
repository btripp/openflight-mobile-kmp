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
import dev.openflight.companion.core.model.pi.CameraCaptureSettings
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
 * The Pi's context over [DefaultPiSessionRepository]: the selected club, the profile roster and
 * its commands, and device status (power, trigger, debug, sim, cloud). Ports the Expo app's
 * `socket.test.ts` "device status", "the selected club", "the profile roster" and "changing the
 * roster" cases, plus `useDeviceStore.test.ts` and `useProfileStore.test.ts`, against the captured
 * [PiFixtures].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PiSessionContextTest {
    // region the selected club

    @Test
    fun theClubFollowsTheSnapshotAndEveryClubChange() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.CONNECT_SESSION_STATE_FRAME)
            assertThat(h.repository.club.value).isEqualTo("driver")

            // A change made on another client (the kiosk, a simulator) is broadcast to this one too.
            socket.serverFrame(PiFixtures.CLUB_CHANGED_FRAME)

            assertThat(h.repository.club.value).isEqualTo("7-iron")
        }

    @Test
    fun aSnapshotWithoutAClubKeepsTheClub() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.CLUB_CHANGED_FRAME)

            socket.server("session_state", """{"stats":{},"shots":[]}""")

            assertThat(h.repository.club.value).isEqualTo("7-iron")
        }

    @Test
    fun aMalformedClubChangeIsIgnored() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.CLUB_CHANGED_FRAME)

            socket.server("club_changed", "{}")
            socket.server("club_changed", """{"club":7}""")
            socket.server("club_changed", "null")

            assertThat(h.repository.club.value).isEqualTo("7-iron")
            assertThat(h.logs).isEmpty()
        }

    // endregion

    // region profiles

    @Test
    fun theRosterMirrorsTheServersSnapshot() =
        runPiTest { h ->
            assertThat(h.repository.profiles.value.loaded).isFalse()
            val socket = h.connected()

            socket.serverFrame(PiFixtures.PROFILES_AFTER_ADD_FRAME)

            val roster = h.repository.profiles.value
            assertThat(roster.profiles.map { it.name }).containsExactly("Profile 1", "Sam")
            assertThat(roster.activeProfileId).isEqualTo(PiFixtures.SAM_PROFILE_ID)
            assertThat(roster.activeProfile?.name).isEqualTo("Sam")
            assertThat(roster.loaded).isTrue()
        }

    @Test
    fun aRepeatedOrSmallerSnapshotReplacesTheRoster() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.PROFILES_AFTER_ADD_FRAME)
            socket.serverFrame(PiFixtures.PROFILES_AFTER_ADD_FRAME)
            assertThat(h.repository.profiles.value.profiles.size).isEqualTo(2)

            // A profile removed on the kiosk disappears here too.
            socket.serverFrame(PiFixtures.CONNECT_PROFILES_FRAME)

            assertThat(
                h.repository.profiles.value.profiles
                    .map { it.name },
            ).containsExactly("Profile 1")
        }

    @Test
    fun aMalformedSnapshotKeepsTheLastGoodRoster() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.PROFILES_AFTER_ADD_FRAME)

            socket.server("profiles", """{"profiles":null}""")
            socket.server("profiles", "null")

            assertThat(h.repository.profiles.value.profiles.size).isEqualTo(2)
            assertThat(h.repository.profiles.value.loaded).isTrue()
        }

    @Test
    fun profileCommandsSendTheServersEventsAndPayloads() =
        runPiTest { h ->
            val socket = h.connected()

            h.repository.setActiveProfile("p2")
            h.repository.addProfile("  Sam  ")
            h.repository.renameProfile("p1", "Alexandra")
            h.repository.removeProfile("p2")

            assertThat(socket.emitted).containsExactly(
                "set_active_profile" to buildJsonObject { put("profile_id", "p2") },
                // Trimmed; and no set_active_profile follows, the server activates the new one.
                "add_profile" to buildJsonObject { put("name", "Sam") },
                "rename_profile" to
                    buildJsonObject {
                        put("profile_id", "p1")
                        put("name", "Alexandra")
                    },
                "remove_profile" to buildJsonObject { put("profile_id", "p2") },
            )
        }

    @Test
    fun profileNamesBreakingTheServersRulesAreNotSent() =
        runPiTest { h ->
            val socket = h.connected()

            assertFailure { h.repository.addProfile("   ") }
                .isInstanceOf(ProfileRuleException::class)
                .prop(ProfileRuleException::rule)
                .isEqualTo(ProfileRuleException.Rule.BLANK_NAME)
            assertFailure { h.repository.renameProfile("p1", "B".repeat(41)) }
                .isInstanceOf(ProfileRuleException::class)
                .prop(ProfileRuleException::rule)
                .isEqualTo(ProfileRuleException.Rule.NAME_TOO_LONG)
            assertThat(socket.emitted).isEmpty()

            h.repository.renameProfile("p1", " " + "B".repeat(40) + " ")
            assertThat(socket.emitted.single().second).isEqualTo(
                buildJsonObject {
                    put("profile_id", "p1")
                    put("name", "B".repeat(40))
                },
            )
        }

    @Test
    fun aThirteenthProfileIsNotSent() =
        runPiTest { h ->
            val socket = h.connected()
            val twelve = (1..12).joinToString(",") { """{"id":"p$it","name":"P$it","created_at":"x","settings":{}}""" }
            socket.server("profiles", """{"profiles":[$twelve],"active_profile_id":"p1"}""")

            assertFailure { h.repository.addProfile("Sam") }
                .isInstanceOf(ProfileRuleException::class)
                .prop(ProfileRuleException::rule)
                .isEqualTo(ProfileRuleException.Rule.TOO_MANY_PROFILES)
            assertThat(socket.emitted).isEmpty()
        }

    // endregion

    // region devices, debug, sim, cloud

    @Test
    fun powerStatusIsASnapshotAndNullMeansNotLoaded() =
        runPiTest { h ->
            assertThat(h.repository.powerStatus.value).isNull()
            val socket = h.connected()

            socket.serverFrame(PiFixtures.POWER_STATUS_FRAME)
            assertThat(
                h.repository.powerStatus.value
                    ?.batteryPercent,
            ).isEqualTo(78.0)

            // A mains-powered Pi: a real answer, not a missing one. Replaced wholesale.
            socket.server(
                "power_status",
                """{"available":false,"provider":"geekworm","state":"unavailable","battery_percent":null,""" +
                    """"battery_voltage_v":null,"external_power":null,"updated_at":"2026-09-22T05:31:00Z",""" +
                    """"error":null}""",
            )
            val status = h.repository.powerStatus.value
            assertThat(status?.available).isEqualTo(false)
            assertThat(status?.state).isEqualTo(PowerState.UNAVAILABLE)
            assertThat(status?.batteryPercent).isNull()
        }

    @Test
    fun aMalformedStatusKeepsTheLastReading() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.TRIGGER_STATUS_FRAME)
            socket.serverFrame(PiFixtures.POWER_STATUS_FRAME)

            socket.server("trigger_status", "null")
            socket.server("power_status")

            assertThat(
                h.repository.triggerStatus.value
                    ?.mode,
            ).isEqualTo("mock")
            assertThat(
                h.repository.powerStatus.value
                    ?.provider,
            ).isEqualTo("geekworm")
        }

    @Test
    fun theDebugStateIsKnownOnlyOnceThePiReportsIt() =
        runPiTest { h ->
            assertThat(h.repository.debugState.value.loaded).isFalse()
            val socket = h.connected()

            socket.serverFrame(PiFixtures.DEBUG_STATUS_FRAME)
            assertThat(h.repository.debugState.value.loaded).isTrue()
            assertThat(h.repository.debugState.value.enabled).isFalse()

            // A toggle made on another client.
            socket.serverFrame(PiFixtures.DEBUG_TOGGLED_ON_FRAME)
            assertThat(h.repository.debugState.value.logPath)
                .isEqualTo("/Users/btripp/openflight_logs/debug_20260925_100605.jsonl")

            // Switching off omits the path: a finished log must not look like a live one.
            socket.serverFrame(PiFixtures.DEBUG_TOGGLED_OFF_FRAME)
            assertThat(h.repository.debugState.value.enabled).isFalse()
            assertThat(h.repository.debugState.value.logPath).isNull()
        }

    @Test
    fun cameraCaptureSettingsMirrorTheServerAndErrorsAreNotices() =
        runPiTest { h ->
            val socket = h.connected()
            socket.serverFrame(PiFixtures.CAMERA_CAPTURE_SETTINGS_FRAME)

            assertThat(h.repository.cameraCaptureSettings.value).isEqualTo(
                CameraCaptureSettings(available = false, enabled = false, alignmentXPct = 50.0, alignmentYPct = 50.0),
            )
            h.repository.notices.test {
                socket.serverFrame(PiFixtures.CAMERA_CAPTURE_SETTINGS_ERROR_FRAME)
                assertThat(awaitItem()).isEqualTo(
                    PiNotice.CameraSettingsFailed("High-speed camera capture is not running"),
                )
            }

            h.settings.hostState.value = "10.0.0.9:8080"
            assertThat(h.repository.cameraCaptureSettings.value).isNull()
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
            socket.serverFrame(PiFixtures.DEBUG_TOGGLED_ON_FRAME)
            repeat(55) { socket.server("debug_reading", """{"speed":$it,"direction":"outbound","magnitude":1}""") }
            socket.server(
                "debug_shot",
                """{"type":"shot","radar":{"ball_speed_mph":140.1},"camera":null,"club":"driver"}""",
            )

            val enabled = h.repository.debugState.value
            assertThat(enabled.enabled).isTrue()
            assertThat(enabled.readings.size).isEqualTo(50)
            assertThat(enabled.readings.first().speed).isEqualTo(5.0)
            assertThat(
                enabled.shotLogs
                    .single()
                    .radar
                    ?.ballSpeedMph,
            ).isEqualTo(140.1)

            socket.serverFrame(PiFixtures.DEBUG_TOGGLED_OFF_FRAME)

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
            socket.serverFrame(PiFixtures.RADAR_CONFIG_FRAME)
            socket.server("cloud_upload_status", """{"state":"complete","message":"Nothing to upload.","summary":{}}""")

            assertThat(h.repository.radarConfig.value).isEqualTo(RadarConfig(minSpeed = 10, maxSpeed = 220))
            assertThat(h.repository.cloudUploadStatus.value.message).isEqualTo("Nothing to upload.")
        }

    @Test
    fun serverErrorsArriveAsNotices() =
        runPiTest { h ->
            val socket = h.connected()
            h.repository.notices.test {
                socket.serverFrame(PiFixtures.RADAR_CONFIG_ERROR_FRAME)
                socket.serverFrame(PiFixtures.TRAINING_IMPLEMENT_ERROR_FRAME)

                assertThat(awaitItem()).isEqualTo(PiNotice.RadarConfigFailed("Radar not connected"))
                assertThat(awaitItem()).isEqualTo(PiNotice.TrainingImplementFailed("Unknown training implement"))
            }
        }

    // endregion
}
