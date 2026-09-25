// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.database.buildShotHistoryDatabase
import dev.openflight.companion.core.database.inMemoryShotHistoryDatabaseBuilder
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.RadarConfigUpdate
import dev.openflight.companion.core.network.PiCameraClient
import dev.openflight.companion.core.network.PiControlClient
import dev.openflight.companion.core.network.WifiShotTransport
import dev.openflight.companion.core.network.openFlightHttpClient
import dev.openflight.companion.core.socketio.KtorWebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * The app's real data layer against a real `openflight-server --mock` (plan R8g).
 *
 * **Not part of `allTests`.** Run it with
 * ```
 * OPENFLIGHT_BACKEND_DIR=~/Developer/oss/openflight ./gradlew mockServerIT
 * ```
 * (or `-Popenflight.backendDir=...`). Without a backend checkout the Gradle task and this test
 * skip with a message. The checkout needs `uv sync` (or `uv` on the `PATH`).
 *
 * It builds the production graph by hand, like `dataModule` but without Android's `Context`:
 * [DefaultShotRepository] over a [WifiShotTransport] per host, [DefaultPiSessionRepository] over
 * the Ktor Socket.IO client, the real [DataStoreSettingsRepository] in a temporary file and a
 * [DefaultShotHistoryRepository] over an in-memory Room database. Only Bluetooth is a fake,
 * since the run never selects it.
 *
 * Backend features differ: stock upstream `main` has no SSE `/api/shots/stream` and no
 * `/api/club`, while the `feat/phone-connectivity` fork has both. Each step that needs one is
 * reported as skipped (with the reason) on a backend without it, instead of failing; everything
 * Socket.IO must pass on both. The run ends with `POST /api/shutdown`, which exits the server,
 * and the server is killed in every other path.
 */
class MockServerIT {
    private val steps = StepLog()

    @Test
    fun walksTheDataLayerAgainstAMockServer() {
        val backendDir = backendDir()
        assumeTrue(
            "MockServerIT skipped: set OPENFLIGHT_BACKEND_DIR (or -Popenflight.backendDir) to an openflight checkout",
            backendDir != null,
        )
        // --battery geekworm needs no hardware: without a battery the monitor still broadcasts
        // `power_status` (state "unavailable"), which is exactly the path a Pi without the HAT takes.
        OpenFlightMockServer
            .start(checkNotNull(backendDir), listOf("--battery", "geekworm"), READY_TIMEOUT_MILLIS)
            .use { server ->
                var finished = false
                try {
                    runBlocking { withTimeout(RUN_TIMEOUT_MILLIS) { Harness(server).walk() } }
                    finished = true
                } finally {
                    if (!finished) println("[mock-server-it] FAILED; server log tail:\n${server.logTail()}")
                    steps.printSummary(backendDir)
                }
            }
    }

    private fun backendDir(): File? =
        (System.getProperty("openflight.backendDir") ?: System.getenv("OPENFLIGHT_BACKEND_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it.replaceFirst("~", System.getProperty("user.home").orEmpty())) }

    private inner class Harness(
        private val server: OpenFlightMockServer,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val workDir = Files.createTempDirectory("openflight-mock-it-app").toFile()
        private val httpClient = openFlightHttpClient()
        private val settings =
            DataStoreSettingsRepository(
                createSettingsDataStore(
                    path = File(workDir, DataStoreSettingsRepository.FILE_NAME).absolutePath,
                    scope = scope,
                ),
            )
        private val history =
            DefaultShotHistoryRepository(
                openDatabase = { inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase() },
                scope = scope,
                log = { log("history: $it") },
            )
        private val pi =
            DefaultPiSessionRepository(
                settings = settings,
                socketFactory = socketIoPiSocketFactory(KtorWebSocketTransport(httpClient)),
                cameraSource = KtorPiCameraSource(PiCameraClient(httpClient)),
                scope = scope,
                log = { log("pi: $it") },
            )
        private val shots =
            DefaultShotRepository(
                settings = settings,
                bluetoothTransport = FakeShotTransport("unused-bluetooth"),
                wifiTransportFactory = WifiTransportFactory { host -> WifiShotTransport(host, httpClient) },
                scope = scope,
                piControl = PiControlClient(httpClient),
                piSession = pi,
                log = { log("shots: $it") },
                persistentHistory = history,
            )
        private val notices = mutableListOf<PiNotice>()
        private var hasSse = false
        private var hasClubApi = false

        suspend fun walk() {
            try {
                pi.notices.onEach { notices += it }.launchIn(scope)
                connect()
                snapshot()
                simulateShot()
                club()
                profiles()
                deleteShot()
                clearSession()
                deviceCommands()
                reconnect()
                shutdown()
            } finally {
                shots.stop()
                scope.cancel()
                httpClient.close()
                workDir.deleteRecursively()
            }
        }

        private suspend fun connect() {
            settings.setTransport(TransportType.WIFI)
            settings.setHost(server.host)
            shots.start()
            await("Socket.IO link") { pi.linkState.value == PiLinkState.Connected }
            steps.pass("connect: Socket.IO", "link Connected to ${server.host}")

            // The SSE stream either connects (fork) or answers 404 (stock main): both are final.
            await("SSE outcome") {
                shots.connectionState.value.let { it == ConnectionState.Connected || it is ConnectionState.Error }
            }
            when (val state = shots.connectionState.value) {
                ConnectionState.Connected -> {
                    hasSse = true
                    steps.pass("connect: SSE /api/shots/stream?schema=2", "Connected")
                }

                is ConnectionState.Error -> {
                    assertThat(state.description).contains("404")
                    steps.skip("connect: SSE /api/shots/stream", "backend has no SSE route (${state.description})")
                }

                else -> {
                    error("unexpected SSE state $state")
                }
            }
            hasClubApi = server.status("/api/club") == HTTP_OK
        }

        private suspend fun snapshot() {
            await("session_state") { pi.stats.value != null }
            assertThat(pi.mockMode.value).isEqualTo(true)
            assertThat(pi.club.value).isNotNull()
            steps.pass(
                "snapshot: session_state",
                "shots=${pi.sessionShots.value.size} mock_mode=true club=${pi.club.value}",
            )
            await("profiles") { pi.profiles.value.loaded && pi.profiles.value.activeProfile != null }
            steps.pass("snapshot: profiles", "active=${pi.profiles.value.activeProfile?.name}")
            await("trigger_status") { pi.triggerStatus.value != null }
            steps.pass("snapshot: trigger_status", "mode=${pi.triggerStatus.value?.mode}")
            await("debug status") { pi.debugState.value.loaded }
            steps.pass("snapshot: debug status loaded", "enabled=${pi.debugState.value.enabled}")
            await("power_status", POWER_TIMEOUT_MILLIS) { pi.powerStatus.value != null }
            val power = checkNotNull(pi.powerStatus.value)
            assertThat(power.provider).isEqualTo("geekworm")
            steps.pass(
                "snapshot: power_status (--battery geekworm)",
                "available=${power.available} state=${power.state}",
            )
            if (hasClubApi) {
                // The same read the repository runs once per connection, persisted to settings.
                val current = shots.currentClub()
                assertThat(settings.selectedClub.first()).isEqualTo(current.club)
                steps.pass("connect: club sync (GET /api/club)", "selected=${current.club.wireValue}")
            } else {
                steps.skip("connect: club sync (GET /api/club)", "backend has no /api/club")
            }
        }

        private suspend fun simulateShot() {
            val before = pi.sessionShots.value.size
            pi.simulateShot()
            await("simulated shot") { pi.sessionShots.value.size == before + 1 }
            val shot = pi.sessionShots.value.first()
            assertThat(shot.shotNumber).isNotNull()
            assertThat(shot.profileId).isEqualTo(pi.profiles.value.activeProfileId)
            steps.pass(
                "simulate_shot -> shot (Socket.IO)",
                "#${shot.shotNumber} ${shot.club} ${shot.ballSpeedMph} mph, profile=${shot.profileName}",
            )

            val sessionId = checkNotNull(history.currentSessionId.value) { "no history session started" }
            await("persistent history") {
                history.shots(sessionId).first().any { it.detail.timestamp == shot.timestamp }
            }
            steps.pass("history: live shot written through (Room)", "session=$sessionId")

            if (hasSse) {
                await("SSE shot") { shots.latestShot.value?.timestamp == shot.timestamp }
                val sseShot = checkNotNull(shots.latestShot.value)
                assertThat(pi.detailFor(sseShot)).isNotNull()
                assertThat(shots.history.value.map { it.timestamp }).contains(shot.timestamp)
                steps.pass(
                    "SSE shot enriched by timestamp",
                    "schema=${sseShot.schemaVersion} event=${sseShot.eventId}",
                )
            } else {
                // Without SSE nothing feeds ShotRepository.history, the dashboard's live feed: the
                // shot reaches only the Pi session and the stored history. Recorded, not asserted.
                delay(NO_SSE_SETTLE_MILLIS)
                val reached = shots.history.value.any { it.timestamp == shot.timestamp }
                steps.skip("SSE shot", "backend has no SSE route; ShotRepository.history got the shot: $reached")
            }
        }

        private suspend fun club() {
            if (!hasClubApi) {
                // The app changes clubs over Wi-Fi only through POST /api/club, which stock main lacks.
                assertFailure { shots.setClub(GolfClub.IRON_7) }
                steps.skip("set_club -> club_changed", "backend has no /api/club (the app's Wi-Fi club path)")
                return
            }
            val selection = shots.setClub(GolfClub.IRON_7)
            assertThat(selection.club).isEqualTo(GolfClub.IRON_7)
            await("club_changed over Socket.IO") { pi.club.value == GolfClub.IRON_7.wireValue }
            if (hasSse) await("club_changed over SSE") { shots.activeClub.value == GolfClub.IRON_7 }
            assertThat(settings.selectedClub.first()).isEqualTo(GolfClub.IRON_7)

            val before = pi.sessionShots.value.size
            pi.simulateShot()
            await("shot with the new club") { pi.sessionShots.value.size == before + 1 }
            assertThat(
                pi.sessionShots.value
                    .first()
                    .club,
            ).isEqualTo(GolfClub.IRON_7.wireValue)
            steps.pass("set_club -> club_changed", "POST /api/club 7-iron; next shot filed as 7-iron")
        }

        private suspend fun profiles() {
            val original = checkNotNull(pi.profiles.value.activeProfile)
            val count = pi.profiles.value.profiles.size

            assertFailure { pi.addProfile("   ") }.isInstanceOf(ProfileRuleException::class)
            pi.addProfile("  MockServerIT  ")
            await("add_profile") {
                pi.profiles.value.activeProfile
                    ?.name == "MockServerIT"
            }
            assertThat(pi.profiles.value.profiles.size).isEqualTo(count + 1)
            val added = checkNotNull(pi.profiles.value.activeProfile)
            steps.pass("add_profile (trimmed, becomes active)", "id=${added.id}")

            pi.renameProfile(added.id, "MockServerIT renamed")
            await("rename_profile") {
                pi.profiles.value.activeProfile
                    ?.name == "MockServerIT renamed"
            }
            steps.pass("rename_profile", "MockServerIT -> MockServerIT renamed")

            pi.setActiveProfile(original.id)
            await("set_active_profile") { pi.profiles.value.activeProfileId == original.id }
            steps.pass("set_active_profile", "back to ${original.name}")

            // The server refuses to remove the active profile: the roster comes back unchanged.
            pi.removeProfile(original.id)
            delay(REFUSAL_SETTLE_MILLIS)
            assertThat(
                pi.profiles.value.profiles
                    .map { it.id },
            ).contains(original.id)

            pi.removeProfile(added.id)
            await("remove_profile") {
                pi.profiles.value.profiles
                    .none { it.id == added.id }
            }
            steps.pass("remove_profile", "active profile refused; unused profile removed")
        }

        private suspend fun deleteShot() {
            val target =
                pi.sessionShots.value
                    .last()
                    .timestamp
            shots.deleteShotByTimestamp(target)
            await("delete confirmed") { pi.deletionState.value == DeletionState.Deleted(target) }
            assertThat(pi.sessionShots.value.map { it.timestamp }).doesNotContain(target)
            await("local history follows") { shots.history.value.none { it.timestamp == target } }
            val sessionId = checkNotNull(history.currentSessionId.value)
            await("persistent history follows") {
                history.shots(sessionId).first().none { it.detail.timestamp == target }
            }
            pi.dismissDeletion()
            steps.pass("delete_shot (server-confirmed)", "timestamp=$target")

            pi.deleteShot("1999-01-01T00:00:00")
            await("delete_shot_error") { pi.deletionState.value is DeletionState.Failed }
            pi.dismissDeletion()
            steps.pass("delete_shot_error", "unknown timestamp -> Failed")
        }

        private suspend fun clearSession() {
            val profileId = pi.profiles.value.activeProfileId
            // Give the clear something to remove, whatever the earlier steps left.
            val total = pi.sessionShots.value.size
            pi.simulateShot()
            await("a shot to clear") { pi.sessionShots.value.size == total + 1 }
            val before = pi.sessionShots.value.count { it.profileId == profileId }
            assertThat(before >= 1).isTrue()
            shots.clearHistory()
            await("clear confirmed") { pi.clearState.value == ClearState.Cleared(profileId) }
            assertThat(pi.sessionShots.value.none { it.profileId == profileId }).isTrue()
            await("local history cleared") { shots.history.value.none { pi.detailFor(it)?.profileId == profileId } }
            pi.dismissClear()
            steps.pass("clear_session (active profile)", "removed $before row(s) of profile $profileId")
        }

        private suspend fun deviceCommands() {
            pi.toggleDebug()
            await("debug on") { pi.debugState.value.enabled }
            pi.toggleDebug()
            await("debug off") { !pi.debugState.value.enabled }
            steps.pass("toggle_debug", "on -> off")

            pi.refreshRadarConfig()
            pi.setRadarConfig(RadarConfigUpdate(minSpeed = 12))
            await("radar_config_error in mock") { notices.any { it is PiNotice.RadarConfigFailed } }
            steps.pass("set_radar_config", "mock answers radar_config_error")

            pi.setTrainingImplement("stack-100g")
            await("training implement") { pi.trainingImplement.value != null }
            steps.pass("set_training_implement", "${pi.trainingImplement.value}")

            // Requested on every connect; a mock without a camera may answer with an error instead.
            await("camera capture settings") {
                pi.cameraCaptureSettings.value != null || notices.any { it is PiNotice.CameraSettingsFailed }
            }
            steps.pass(
                "get_camera_capture_settings",
                pi.cameraCaptureSettings.value?.let { "settings received" } ?: "camera_capture_settings_error",
            )
        }

        private suspend fun reconnect() {
            shots.stop()
            await("disconnected") {
                pi.linkState.value == PiLinkState.Idle && shots.connectionState.value == ConnectionState.Idle
            }
            shots.start()
            await("reconnected") { pi.linkState.value == PiLinkState.Connected }
            if (hasSse) await("SSE reconnected") { shots.connectionState.value == ConnectionState.Connected }
            pi.refreshSession()
            await("snapshot after reconnect") { pi.profiles.value.loaded && pi.stats.value != null }
            steps.pass("disconnect -> reconnect", "link and snapshot restored")
        }

        private suspend fun shutdown() {
            shots.shutdownPi(server.host)
            steps.pass("POST /api/shutdown", "200")
            await("link drops", SHUTDOWN_TIMEOUT_MILLIS) { pi.linkState.value is PiLinkState.Reconnecting }
            val exitCode = server.awaitExit(SHUTDOWN_TIMEOUT_MILLIS)
            assertThat(exitCode).isNotNull()
            if (hasSse) await("SSE drops") { shots.connectionState.value != ConnectionState.Connected }
            steps.pass("shutdown -> expected link drop", "link=${pi.linkState.value}; server exited ($exitCode)")
        }

        private suspend fun await(
            what: String,
            timeoutMillis: Long = STEP_TIMEOUT_MILLIS,
            condition: suspend () -> Boolean,
        ) {
            try {
                withTimeout(timeoutMillis) {
                    while (!condition()) delay(POLL_MILLIS)
                }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError(
                    "timed out after $timeoutMillis ms waiting for $what " +
                        "(link=${pi.linkState.value}, sse=${shots.connectionState.value})",
                    timeout,
                )
            }
        }
    }

    /** Collects each step's outcome for the summary a CI log (and the plan's Execution Log) quotes. */
    private class StepLog {
        private val rows = mutableListOf<Triple<String, String, String>>()

        fun pass(
            step: String,
            detail: String,
        ) = add("PASS", step, detail)

        fun skip(
            step: String,
            reason: String,
        ) = add("SKIP", step, reason)

        private fun add(
            outcome: String,
            step: String,
            detail: String,
        ) {
            rows += Triple(outcome, step, detail)
            log("$outcome $step: $detail")
        }

        fun printSummary(backendDir: File?) {
            log(
                "summary for $backendDir: ${rows.count {
                    it.first == "PASS"
                }} passed, ${rows.count { it.first == "SKIP" }} skipped",
            )
            rows.forEach { (outcome, step, detail) -> log("  $outcome  $step  ($detail)") }
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val READY_TIMEOUT_MILLIS = 120_000L
        const val RUN_TIMEOUT_MILLIS = 240_000L
        const val STEP_TIMEOUT_MILLIS = 15_000L
        const val POWER_TIMEOUT_MILLIS = 20_000L
        const val SHUTDOWN_TIMEOUT_MILLIS = 30_000L
        const val REFUSAL_SETTLE_MILLIS = 1_000L
        const val NO_SSE_SETTLE_MILLIS = 2_000L
        const val POLL_MILLIS = 50L

        fun log(message: String) = println("[mock-server-it] $message")
    }
}
