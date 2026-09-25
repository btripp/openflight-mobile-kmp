// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.CloudUploadState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.RadarConfigUpdate
import dev.openflight.companion.core.network.WifiShotTransport
import dev.openflight.companion.core.network.openFlightHttpClient
import dev.openflight.companion.core.socketio.KtorWebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Manual R6a E2E oracle against a live `openflight-server --mock`. **Not** part of CI; keep it
 * `@Ignore`d.
 *
 * To run it: `uv run openflight-server --mock --web-port 8092` in the reference clone, remove
 * `@Ignore`, then
 * ```
 * ./gradlew :core:data:testAndroidHostTest --tests "*ManualPiSessionE2ETest*" -i | grep pi-e2e
 * ```
 * It drives the real [DefaultPiSessionRepository] (Ktor OkHttp WebSocket) through every command
 * and prints what the server answered, checks that SSE shots find their Socket.IO detail, and
 * finally sends `shutdown`, which exits the mock server.
 */
class ManualPiSessionE2ETest {
    @Ignore
    @Test
    @Suppress("LongMethod") // A linear script of the manual steps.
    fun drivesEveryCommandAgainstALiveMockServer() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val httpClient = openFlightHttpClient()
            val repository =
                DefaultPiSessionRepository(
                    settings = FakeSettingsRepository(transport = TransportType.WIFI, host = HOST),
                    socketFactory = socketIoPiSocketFactory(KtorWebSocketTransport(httpClient)),
                    cameraSource = KtorPiCameraSource(httpClient),
                    scope = scope,
                    log = { log("repository log: $it") },
                )
            val notices = mutableListOf<PiNotice>()
            repository.notices
                .onEach {
                    notices += it
                    log("notice: $it")
                }.launchIn(scope)
            repository.linkState.onEach { log("link: $it") }.launchIn(scope)

            // SSE in parallel, to check enrichment of the stream's shots.
            val sse = WifiShotTransport(HOST, httpClient)
            val sseShots = mutableListOf<ShotEvent>()
            sse.shots
                .onEach {
                    sseShots += it
                    log("sse shot ${it.eventId} ts=${it.timestamp}")
                }.launchIn(scope)
            sse.start()

            repository.start()
            await("connected") { repository.linkState.value == PiLinkState.Connected }
            log("transport: websocket (${KtorWebSocketTransport.url("http://$HOST")})")
            await("session_state") { repository.stats.value != null }
            log(
                "session: shots=${repository.sessionShots.value.size} stats=${repository.stats.value} " +
                    "profile=${repository.profiles.value.activeProfile?.name} mock=${repository.mockMode.value}",
            )
            log("trigger_status: ${repository.triggerStatus.value}")
            log("radar_config: ${repository.radarConfig.value}")

            val before = repository.sessionShots.value.size
            repository.simulateShot()
            repository.simulateShot()
            await("two shots") { repository.sessionShots.value.size == before + 2 }
            val newest = repository.sessionShots.value.first()
            log("shot detail: $newest")
            log("stats after shots: ${repository.stats.value}")

            await("sse shots") { sseShots.size >= 2 }
            for (shot in sseShots.takeLast(2)) {
                log(
                    "enrichment ${shot.eventId}: detail=${repository
                        .detailFor(
                            shot,
                        )?.let {
                            "found conf=${it.launchAngleConfidence} spinQuality=${it.spinQuality} " +
                                "carryRange=${it.carryRange}"
                        } ?: "MISSING"}",
                )
            }

            val toDelete = repository.sessionShots.value.last()
            repository.deleteShot(toDelete.timestamp)
            await("delete") { repository.deletionState.value == DeletionState.Deleted(toDelete.timestamp) }
            log(
                "after delete: shots=${repository.sessionShots.value.size} " +
                    "stats.shot_count=${repository.stats.value?.shotCount}",
            )
            repository.dismissDeletion()
            repository.deleteShot("1999-01-01T00:00:00")
            await("delete error") { repository.deletionState.value is DeletionState.Failed }

            repository.addProfile("  Live Tester  ")
            await("profile") {
                repository.profiles.value.activeProfile
                    ?.name == "Live Tester"
            }
            log("profiles: ${repository.profiles.value}")

            repository.setTrainingImplement("stack-100g")
            await("implement") { repository.trainingImplement.value != null }
            log("training implement: ${repository.trainingImplement.value}")
            repository.setTrainingImplement("pogo-stick")
            await("implement error") { notices.any { it is PiNotice.TrainingImplementFailed } }

            repository.refreshRadarConfig()
            repository.setRadarConfig(RadarConfigUpdate(minSpeed = 12))
            await("radar error") { notices.any { it is PiNotice.RadarConfigFailed } }
            log("radar_config after set: ${repository.radarConfig.value}")

            repository.uploadCloud()
            await("cloud", timeoutMs = 30_000) { repository.cloudUploadStatus.value.state != CloudUploadState.RUNNING }
            log("cloud: ${repository.cloudUploadStatus.value}")

            repository.toggleDebug()
            await("debug on") { repository.debugState.value.enabled }
            log("debug: ${repository.debugState.value}")
            repository.toggleDebug()
            await("debug off") { !repository.debugState.value.enabled }

            repository.toggleCamera()
            repository.toggleCameraStream()
            repository.refreshCameraStatus()
            delay(500)
            log("camera: ${repository.cameraStatus.value}")
            val frames =
                runCatching { withTimeoutOrNull(5_000) { repository.cameraFrames().take(1).toList() } }
            log(
                "camera frames: ${frames.fold(
                    { "got ${it?.size ?: "timeout"}" },
                    { "failed: ${it::class.simpleName}: ${it.message}" },
                )}",
            )

            repository.clearSession(repository.profiles.value.activeProfileId)
            await("clear") { repository.clearState.value is ClearState.Cleared }
            log(
                "after clear: shots=${repository.sessionShots.value.size} stats=${repository.stats.value} " +
                    "details=${repository.shotDetails.value.size}",
            )

            // Last: this stops the mock server process (os._exit after 0.5 s).
            repository.shutdown()
            await("shutdown ack") { notices.any { it is PiNotice.ShuttingDown } }
            await("link drops", timeoutMs = 60_000) { repository.linkState.value is PiLinkState.Reconnecting }
            log("after shutdown: ${repository.linkState.value}")

            repository.stop()
            sse.disconnect()
            await("idle") { repository.linkState.value == PiLinkState.Idle }
            scope.cancel()
            httpClient.close()
            log("done; notices=$notices")
        }

    private suspend fun await(
        what: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(POLL_MS)
        }
        log("ok: $what")
    }

    private fun log(message: String) = println("[pi-e2e] $message")

    private companion object {
        const val HOST = "localhost:8092"
        const val POLL_MS = 50L
    }
}
