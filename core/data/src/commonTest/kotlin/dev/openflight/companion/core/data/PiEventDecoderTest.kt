// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.pi.CloudUploadState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.SimStatus
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.socketio.SocketEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

class PiEventDecoderTest {
    @Test
    fun decodesTheFullShotToDictPayloadAndItsStats() {
        val event = decodePiEvent(PiFixtures.event(PiFixtures.SHOT_FRAME)) as PiEvent.Shot

        val shot = event.detail
        assertThat(shot.timestamp).isEqualTo(PiFixtures.SHOT_TIMESTAMP)
        assertThat(shot.ballSpeedMph).isEqualTo(143.3)
        assertThat(shot.estimatedCarryYards).isEqualTo(243.0)
        assertThat(shot.carryRangeLow).isEqualTo(231.0)
        assertThat(shot.carryRangeHigh).isEqualTo(255.0)
        assertThat(shot.launchAngleConfidence).isEqualTo(0.72)
        assertThat(shot.angleSource).isEqualTo("mock")
        assertThat(shot.spinRpm).isEqualTo(2836.0)
        assertThat(shot.spinQuality).isEqualTo("medium")
        assertThat(shot.spinSource).isNull()
        assertThat(shot.spinPhaseConfirmed).isEqualTo(false)
        assertThat(shot.playerName).isEqualTo("Player 1")
        assertThat(shot.isSwingSpeed).isFalse()
        assertThat(event.stats).isEqualTo(
            SessionStats(
                shotCount = 1,
                avgBallSpeed = 143.28745757452205,
                maxBallSpeed = 143.28745757452205,
                minBallSpeed = 143.28745757452205,
                stdDev = 0.0,
                avgClubSpeed = 98.27610134234705,
                avgSmashFactor = 1.4580091763650342,
                avgCarryEst = 243.30227105757902,
            ),
        )
    }

    @Test
    fun decodesTheOnConnectSessionStateWithItsFlags() {
        val state = (decodePiEvent(PiFixtures.event(PiFixtures.CONNECT_SESSION_STATE_FRAME)) as PiEvent.Session).state

        assertThat(state.shots).isEmpty()
        assertThat(state.stats).isEqualTo(SessionStats.EMPTY)
        assertThat(state.mockMode).isEqualTo(true)
        assertThat(state.cameraAvailable).isEqualTo(false)
        assertThat(state.playerName).isEqualTo("Player 1")
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
    fun decodesSessionClearedWithNoPayload() {
        assertThat(decodePiEvent(SocketEvent("session_cleared"))).isEqualTo(PiEvent.SessionCleared)
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
        assertThat(decode("delete_shot_error", """{"error":"Shot not found"}""")).isEqualTo(
            PiEvent.Notice(PiNotice.DeleteShotFailed("Shot not found")),
        )
        assertThat(decode("radar_config_error", """{"error":"Radar not connected"}""")).isEqualTo(
            PiEvent.Notice(PiNotice.RadarConfigFailed("Radar not connected")),
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
        assertThat(decode("debug_toggled", """{"enabled":true,"log_path":"/home/pi/l.jsonl"}""")).isEqualTo(
            PiEvent.Debug(enabled = true, logPath = "/home/pi/l.jsonl"),
        )
        assertThat(decode("debug_status", """{"enabled":false,"log_path":null}""")).isEqualTo(
            PiEvent.Debug(enabled = false, logPath = null),
        )
    }

    @Test
    fun ignoresEventsOwnedElsewhereAndUnknownOnes() {
        assertThat(decode("club_changed", """{"club":"driver"}""")).isNull()
        assertThat(decodePiEvent(SocketEvent("something_new", listOf(JsonPrimitive(1))))).isNull()
    }

    @Test
    fun aMalformedKnownPayloadThrows() {
        assertFailure { decodePiEvent(SocketEvent("shot", listOf(JsonPrimitive("nope")))) }
            .isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun sessionStateWithShotsKeepsServerOrder() {
        val frame =
            """42["session_state",{"stats":{"shot_count":2},""" +
                """"shots":[{"timestamp":"a"},{"timestamp":"b"}],"player_name":"P"}]"""

        val state = (decodePiEvent(PiFixtures.event(frame)) as PiEvent.Session).state

        assertThat(state.shots.map { it.timestamp }).containsExactly("a", "b")
    }

    private fun json(text: String) = PiJson.parseToJsonElement(text)

    private fun decode(
        name: String,
        payload: String,
    ) = decodePiEvent(SocketEvent(name, listOf(json(payload))))
}
