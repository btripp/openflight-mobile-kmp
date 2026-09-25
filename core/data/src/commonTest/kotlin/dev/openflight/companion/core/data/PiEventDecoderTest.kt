// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.pi.CloudUploadState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.PowerState
import dev.openflight.companion.core.model.pi.SessionCleared
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.ShotProcessingState
import dev.openflight.companion.core.model.pi.SimStatus
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.socketio.SocketEvent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

class PiEventDecoderTest {
    @Test
    fun decodesTheFullShotToDictPayloadAndItsStats() {
        val event = decodePiEvent(PiFixtures.event(PiFixtures.SHOT_FRAME)) as PiEvent.Shot

        val shot = event.detail
        assertThat(shot.timestamp).isEqualTo(PiFixtures.SHOT_TIMESTAMP)
        assertThat(shot.shotNumber).isEqualTo(1)
        assertThat(shot.profileId).isEqualTo(PiFixtures.DEFAULT_PROFILE_ID)
        assertThat(shot.profileName).isEqualTo("Profile 1")
        assertThat(shot.ballSpeedMph).isEqualTo(116.2)
        assertThat(shot.clubSpeedMph).isEqualTo(78.6)
        assertThat(shot.smashFactor).isEqualTo(1.48)
        assertThat(shot.estimatedCarryYards).isEqualTo(179.0)
        assertThat(shot.carryRangeLow).isEqualTo(171.0)
        assertThat(shot.carryRangeHigh).isEqualTo(188.0)
        assertThat(shot.launchAngleConfidence).isEqualTo(0.66)
        assertThat(shot.angleSource).isEqualTo("mock")
        assertThat(shot.spinRpm).isEqualTo(2593.0)
        assertThat(shot.spinQuality).isEqualTo("high")
        assertThat(shot.spinSource).isNull()
        assertThat(shot.spinPhaseConfirmed).isEqualTo(false)
        assertThat(shot.impactTimestamp).isNull()
        assertThat(shot.cameraReplay).isNull()
        assertThat(shot.experimentalFusedStatus).isNull()
        assertThat(shot.isSwingSpeed).isFalse()
        assertThat(event.stats).isEqualTo(
            SessionStats(
                shotCount = 1,
                avgBallSpeed = 116.18442296964207,
                maxBallSpeed = 116.18442296964207,
                minBallSpeed = 116.18442296964207,
                stdDev = 0.0,
                avgClubSpeed = 78.59683464227986,
                avgSmashFactor = 1.4782328512138654,
                avgCarryEst = 179.49272086955148,
                avgSpinRpm = 2592.552990850858,
                spinDetectionRate = 1.0,
                mode = "mock",
            ),
        )
    }

    @Test
    fun decodesAShotUpdateWithTheSameNumberAndTimestamp() {
        val update = decodePiEvent(PiFixtures.event(PiFixtures.SHOT_UPDATE_FRAME)) as PiEvent.ShotUpdate
        val skipped = decodePiEvent(PiFixtures.event(PiFixtures.SHOT_UPDATE_SKIPPED_FRAME)) as PiEvent.ShotUpdate

        assertThat(update.detail.shotNumber).isEqualTo(1)
        assertThat(update.detail.timestamp).isEqualTo(PiFixtures.SHOT_TIMESTAMP)
        assertThat(update.detail.iwr6843HorizontalDeg).isEqualTo(2.1)
        // `pending` and `enrichment` are read by nothing, and don't break decoding.
        assertThat(skipped.detail.shotNumber).isEqualTo(1)
    }

    @Test
    fun decodesACameraReplayOnAShot() {
        val row =
            PiJson.decodeFromString(
                ShotDetail.serializer(),
                """{"timestamp":"t","camera_replay":{"id":"abc","frame_count":120,"trigger_frame":40,""" +
                    """"playback_fps":30,"duration_seconds":4.0,"display_mirror_horizontal":true}}""",
            )

        assertThat(row.cameraReplay?.id).isEqualTo("abc")
        assertThat(row.cameraReplay?.frameCount).isEqualTo(120)
        assertThat(row.cameraReplay?.videoUrl).isNull()
    }

    @Test
    fun decodesTheOnConnectSessionStateWithItsFlags() {
        val state = (decodePiEvent(PiFixtures.event(PiFixtures.CONNECT_SESSION_STATE_FRAME)) as PiEvent.Session).state

        assertThat(state.shots).isEmpty()
        assertThat(state.stats.shotCount).isEqualTo(0)
        assertThat(state.mockMode).isEqualTo(true)
        assertThat(state.debugMode).isEqualTo(false)
        assertThat(state.club).isEqualTo("driver")
    }

    @Test
    fun decodesTheProfilesSnapshotKeepingTheOpenSettings() {
        val snapshot =
            (
                decodePiEvent(
                    PiFixtures.event(PiFixtures.PROFILES_AFTER_ADD_FRAME),
                ) as PiEvent.Profiles
            ).snapshot

        assertThat(snapshot.profiles.map { it.name }).containsExactly("Profile 1", "Sam")
        assertThat(snapshot.activeProfileId).isEqualTo(PiFixtures.SAM_PROFILE_ID)
        assertThat(snapshot.profiles.first().createdAt).isEqualTo("2026-09-25T14:00:21Z")

        val settings = """{"bag":["driver","7-iron"],"nested":{"anything":1}}"""
        val withSettings =
            decode(
                "profiles",
                """{"profiles":[{"id":"p1","name":"Alex","created_at":"x","settings":$settings}],""" +
                    """"active_profile_id":"p1"}""",
            ) as PiEvent.Profiles
        assertThat(
            withSettings.snapshot.profiles
                .single()
                .settings,
        ).isEqualTo(json(settings).jsonObject)
    }

    @Test
    fun aProfilesSnapshotWithoutAListIsMalformed() {
        assertFailure {
            decode(
                "profiles",
                """{"active_profile_id":"p1"}""",
            )
        }.isInstanceOf(IllegalArgumentException::class)
        // A missing selection is "none yet", not a failure.
        val none = decode("profiles", """{"profiles":[]}""") as PiEvent.Profiles
        assertThat(none.snapshot.activeProfileId).isEqualTo("")
    }

    @Test
    fun decodesPowerStatusAndProcessing() {
        val power = (decodePiEvent(PiFixtures.event(PiFixtures.POWER_STATUS_FRAME)) as PiEvent.Power).status
        assertThat(power.state).isEqualTo(PowerState.ON_BATTERY)
        assertThat(power.batteryPercent).isEqualTo(78.0)
        assertThat(power.batteryVoltageV).isEqualTo(3.91)
        assertThat(power.externalPower).isEqualTo(false)

        val unknown =
            decode(
                "power_status",
                """{"available":true,"provider":"x","state":"exploding"}""",
            ) as PiEvent.Power
        assertThat(unknown.status.state).isEqualTo(PowerState.UNKNOWN)

        assertThat(decodePiEvent(PiFixtures.event(PiFixtures.SHOT_PROCESSING_FRAME))).isEqualTo(
            PiEvent.Processing(ShotProcessingState.CALCULATING),
        )
        assertFailure { decode("shot_processing", """{"state":"complete"}""") }
            .isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun decodesTriggerStatus() {
        assertThat(decodePiEvent(PiFixtures.event(PiFixtures.TRIGGER_STATUS_FRAME))).isEqualTo(
            PiEvent.Trigger(TriggerStatus(mode = "mock", radarConnected = false)),
        )
    }

    @Test
    fun decodesASwingSpeedRep() {
        val event = decodePiEvent(PiFixtures.event(PiFixtures.SWING_SPEED_FRAME)) as PiEvent.SwingSpeed

        assertThat(event.reading.peakSpeedMph).isEqualTo(97.4)
        assertThat(event.reading.trainingImplement).isEqualTo("stack-100g")
        assertThat(event.reading.profileName).isEqualTo("Profile 1")
        assertThat(event.stats?.avgClubSpeed).isEqualTo(97.4)
    }

    @Test
    fun decodesASwingSpeedShotRowAsASwingSpeedShotDetail() {
        val row =
            PiJson.decodeFromString(
                ShotDetail.serializer(),
                """{"ball_speed_mph":97.4,"club_speed_mph":97.4,"estimated_carry_yards":0,"carry_range":[0,0],""" +
                    """"club":"Stack 100g","timestamp":"t","mode":"swing-speed","swing_speed_reading_count":5,""" +
                    """"spin_peak_freq_rpm":null}""",
            )

        assertThat(row.isSwingSpeed).isTrue()
        assertThat(row.shotNumber).isNull()
        assertThat(row.swingSpeedReadingCount).isEqualTo(5)
    }

    @Test
    fun toleratesUnknownKeysMissingFieldsAndPythonNaN() {
        val row = PiJson.decodeFromString(ShotDetail.serializer(), """{"timestamp":"t","brand_new":1,"spin_snr":NaN}""")

        assertThat(row.timestamp).isEqualTo("t")
        assertThat(row.ballSpeedMph).isNull()
        assertThat(row.spinSnr?.isNaN()).isEqualTo(true)
    }

    @Test
    fun decodesSessionClearedWithTheRemainingSession() {
        val cleared = (decodePiEvent(PiFixtures.event(PiFixtures.SESSION_CLEARED_FRAME)) as PiEvent.Cleared).cleared

        assertThat(cleared.profileId).isEqualTo(PiFixtures.SAM_PROFILE_ID)
        assertThat(cleared.shots?.map { it.timestamp }).isNotNull().containsExactly(PiFixtures.SHOT_TIMESTAMP)
    }

    @Test
    fun aSessionClearedWithoutAUsableListStillDecodes() {
        // An older server sent no payload; a list that doesn't decode is treated the same way.
        assertThat(decodePiEvent(SocketEvent("session_cleared"))).isEqualTo(PiEvent.Cleared(SessionCleared(null, null)))
        assertThat(decodePiEvent(SocketEvent("session_cleared", listOf(JsonNull)))).isEqualTo(
            PiEvent.Cleared(SessionCleared(null, null)),
        )
        assertThat(decode("session_cleared", """{"profile_id":"alex","shots":"nope"}""")).isEqualTo(
            PiEvent.Cleared(SessionCleared("alex", null)),
        )
        assertThat(decode("session_cleared", """{"profile_id":"alex","shots":[{"no_timestamp":1}]}""")).isEqualTo(
            PiEvent.Cleared(SessionCleared("alex", null)),
        )
    }

    @Test
    fun aDeleteShotErrorAlwaysDecodesWithItsReasonOrNone() {
        assertThat(decodePiEvent(PiFixtures.event(PiFixtures.DELETE_SHOT_ERROR_FRAME))).isEqualTo(
            PiEvent.DeleteShotFailed("Shot not found"),
        )
        // Expo: "still fails, with a reason of its own, on a malformed refusal".
        val malformed =
            listOf(
                SocketEvent("delete_shot_error"),
                SocketEvent("delete_shot_error", listOf(JsonNull)),
                SocketEvent("delete_shot_error", listOf(buildJsonObject {})),
                SocketEvent("delete_shot_error", listOf(buildJsonObject { put("error", 42) })),
                SocketEvent("delete_shot_error", listOf(buildJsonObject { put("error", "") })),
            )
        for (event in malformed) assertThat(decodePiEvent(event)).isEqualTo(PiEvent.DeleteShotFailed(null))
    }

    @Test
    fun aClubChangeNeedsAStringClub() {
        assertThat(
            decodePiEvent(PiFixtures.event(PiFixtures.CLUB_CHANGED_FRAME)),
        ).isEqualTo(PiEvent.ClubChanged("7-iron"))
        assertThat(decode("club_changed", "{}")).isNull()
        assertThat(decode("club_changed", """{"club":7}""")).isNull()
        assertThat(decodePiEvent(SocketEvent("club_changed", listOf(JsonNull)))).isNull()
    }

    @Test
    fun mergesOnlyThePresentCameraFields() {
        val event = decode("camera_status", """{"enabled":false,"available":false,"error":"Camera not initialized"}""")

        assertThat(event).isEqualTo(
            PiEvent.Camera(CameraStatusPayload(available = false, enabled = false, error = "Camera not initialized")),
        )
    }

    @Test
    fun decodesSimStatusSnapshotsAndCloudStatus() {
        assertThat(
            decode("sim_status", """{"target":"gspro","state":"connected","host":"10.0.0.2","port":921}"""),
        ).isEqualTo(PiEvent.Sim(SimStatus(target = "gspro", state = "connected", host = "10.0.0.2", port = 921)))

        val cloud =
            decode(
                "cloud_upload_status",
                """{"state":"error","message":"Cloud uploader is not linked.","summary":{"skipped":"inactive"}}""",
            )
        assertThat((cloud as PiEvent.Cloud).status.state).isEqualTo(CloudUploadState.ERROR)
    }

    @Test
    fun anUnknownCloudStateFallsBackToIdleInsteadOfFailing() {
        val cloud = decode("cloud_upload_status", """{"state":"paused","message":"?"}""") as PiEvent.Cloud

        assertThat(cloud.status.state).isEqualTo(CloudUploadState.IDLE)
    }

    @Test
    fun mapsServerErrorsToNotices() {
        assertThat(decodePiEvent(PiFixtures.event(PiFixtures.RADAR_CONFIG_ERROR_FRAME))).isEqualTo(
            PiEvent.Notice(PiNotice.RadarConfigFailed("Radar not connected")),
        )
        assertThat(decodePiEvent(PiFixtures.event(PiFixtures.TRAINING_IMPLEMENT_ERROR_FRAME))).isEqualTo(
            PiEvent.Notice(PiNotice.TrainingImplementFailed("Unknown training implement")),
        )
        assertThat(decode("sim_send_failed", """{"target":"gspro","reason":"broken pipe"}""")).isEqualTo(
            PiEvent.Notice(PiNotice.SimSendFailed("gspro", "broken pipe")),
        )
        assertThat(decode("shutdown_ack", """{"message":"Shutting down..."}""")).isEqualTo(
            PiEvent.Notice(PiNotice.ShuttingDown("Shutting down...")),
        )
    }

    @Test
    fun debugToggledAndDebugStatusBothSetDebugMode() {
        assertThat(decodePiEvent(PiFixtures.event(PiFixtures.DEBUG_TOGGLED_ON_FRAME))).isEqualTo(
            PiEvent.Debug(enabled = true, logPath = "/Users/btripp/openflight_logs/debug_20260925_100605.jsonl"),
        )
        // Switching off omits log_path: that reads as "no log".
        assertThat(decodePiEvent(PiFixtures.event(PiFixtures.DEBUG_TOGGLED_OFF_FRAME))).isEqualTo(
            PiEvent.Debug(enabled = false, logPath = null),
        )
        assertThat(decodePiEvent(PiFixtures.event(PiFixtures.DEBUG_STATUS_FRAME))).isEqualTo(
            PiEvent.Debug(enabled = false, logPath = null),
        )
    }

    @Test
    fun decodesTrainingImplementChanges() {
        val changed = decodePiEvent(PiFixtures.event(PiFixtures.TRAINING_IMPLEMENT_CHANGED_FRAME))

        assertThat((changed as PiEvent.TrainingImplementChanged).implement.label).isEqualTo("Stack 100g")
    }

    @Test
    fun ignoresUnknownEvents() {
        assertThat(decodePiEvent(SocketEvent("something_new", listOf(JsonPrimitive(1))))).isNull()
        assertThat(decodePiEvent(SocketEvent("player_changed", listOf(json("""{"player_name":"Ann"}"""))))).isNull()
    }

    @Test
    fun aMalformedKnownPayloadThrows() {
        assertFailure { decodePiEvent(SocketEvent("shot", listOf(JsonPrimitive("nope")))) }
            .isInstanceOf(IllegalArgumentException::class)
        // Expo "ignores a malformed status rather than blanking the panel": the repository drops these.
        assertFailure { decodePiEvent(SocketEvent("trigger_status", listOf(JsonNull))) }
            .isInstanceOf(IllegalArgumentException::class)
        assertFailure { decodePiEvent(SocketEvent("power_status")) }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun sessionStateWithShotsKeepsServerOrder() {
        val state =
            (
                decodePiEvent(
                    PiFixtures.event(PiFixtures.SESSION_STATE_AFTER_CLEAR_FRAME),
                ) as PiEvent.Session
            ).state
        val frame =
            """42["session_state",{"stats":{"shot_count":2},"shots":[{"timestamp":"a"},{"timestamp":"b"}]}]"""

        assertThat(state.shots.map { it.shotNumber }).containsExactly(1)
        assertThat(state.club).isEqualTo("7-iron")
        assertThat(
            (decodePiEvent(PiFixtures.event(frame)) as PiEvent.Session).state.shots.map { it.timestamp },
        ).containsExactly("a", "b")
    }

    private fun json(text: String) = PiJson.parseToJsonElement(text)

    private fun decode(
        name: String,
        payload: String,
    ) = decodePiEvent(SocketEvent(name, listOf(json(payload))))
}
