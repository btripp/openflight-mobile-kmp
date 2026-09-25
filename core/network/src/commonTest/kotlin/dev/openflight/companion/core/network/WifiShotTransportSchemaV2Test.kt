// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.prop
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.pi.ShotProcessingState
import dev.openflight.companion.core.protocol.SchemaV2Event
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Plan R8e: SSE `?schema=2` (backend `docs/ios-ble.md` "Wi-Fi: `?schema=2`"), the `400` fallback
 * for a Pi that predates it, and a Pi that ignores the query and keeps sending v1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiShotTransportSchemaV2Test {
    private val requested = mutableListOf<String>()

    private fun engine(respond: (url: String) -> Pair<HttpStatusCode, String>): MockEngine =
        MockEngine { request ->
            val url = request.url.toString()
            requested += url
            val (status, body) = respond(url)
            respond(
                content = ByteReadChannel(body.encodeToByteArray()),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }

    @Test
    fun theStreamIsRequestedWithSchemaTwoAndCarriesV2ShotsAndEvents() =
        runTest {
            val transport =
                wifiShotTransport(engine { HttpStatusCode.OK to V2_STREAM }, testScheduler)

            transport.schemaEvents.test {
                transport.shots.test {
                    transport.start()
                    val provisional = awaitItem()
                    val final = awaitItem()
                    assertThat(provisional.isProvisional).isEqualTo(true)
                    assertThat(final.eventId).isEqualTo(provisional.eventId)
                    assertThat(final.final).isEqualTo(true)
                    cancelAndIgnoreRemainingEvents()
                }
                assertThat(awaitItem())
                    .isInstanceOf<SchemaV2Event.Profiles>()
                    .prop("active") { it.activeProfileId }
                    .isEqualTo("p1")
                assertThat(awaitItem()).isEqualTo(SchemaV2Event.ShotProcessing(ShotProcessingState.CAPTURING))
                assertThat(awaitItem()).isEqualTo(SchemaV2Event.ShotDeleted("2026-09-25T14:03:07.412345"))
                assertThat(awaitItem()).isEqualTo(SchemaV2Event.SessionCleared("p1"))
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(transport.activeClub.value).isEqualTo(GolfClub.IRON_7)
            assertThat(requested.first()).isEqualTo("http://pi.local:8080/api/shots/stream?schema=2")
            transport.disconnect()
        }

    @Test
    fun aPiThatRejectsSchemaTwoIsAskedForTheV1StreamRightAway() =
        runTest {
            val transport =
                wifiShotTransport(
                    engine { url ->
                        if (url.endsWith("?schema=2")) {
                            HttpStatusCode.BadRequest to """{"error":"Unsupported schema"}"""
                        } else {
                            HttpStatusCode.OK to sseShotChunk("11111111-1111-1111-1111-111111111111")
                        }
                    },
                    testScheduler,
                )

            transport.shots.test {
                transport.start()
                assertThat(awaitItem().schemaVersion).isEqualTo(1)
                cancelAndIgnoreRemainingEvents()
            }
            runCurrent()
            assertThat(requested.take(2)).containsExactly(
                "http://pi.local:8080/api/shots/stream?schema=2",
                "http://pi.local:8080/api/shots/stream",
            )
            transport.disconnect()
        }

    @Test
    fun aPiThatIgnoresTheQueryKeepsWorkingOnV1() =
        runTest {
            val transport =
                wifiShotTransport(
                    engine {
                        HttpStatusCode.OK to
                            sseClubChangedChunk("pw") + sseShotChunk("11111111-1111-1111-1111-111111111111")
                    },
                    testScheduler,
                )

            transport.shots.test {
                transport.start()
                assertThat(awaitItem().schemaVersion).isEqualTo(1)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(transport.activeClub.value).isEqualTo(GolfClub.PITCHING_WEDGE)
            assertThat(requested.first()).isEqualTo("http://pi.local:8080/api/shots/stream?schema=2")
            transport.disconnect()
        }

    @Test
    fun aMalformedV2EventIsDroppedAndTheNextOneStillArrives() =
        runTest {
            val stream =
                sse("shot_processing", """{"schema_version":2,"type":"shot_processing","state":"exploding"}""") +
                    sse("shot_deleted", """{"schema_version":2,"type":"shot_deleted","timestamp":"t1"}""")
            val transport = wifiShotTransport(engine { HttpStatusCode.OK to stream }, testScheduler)

            transport.schemaEvents.test {
                transport.start()
                assertThat(awaitItem()).isEqualTo(SchemaV2Event.ShotDeleted("t1"))
                cancelAndIgnoreRemainingEvents()
            }
            transport.disconnect()
        }

    private companion object {
        fun sse(
            name: String,
            data: String,
        ): String = "event: $name\ndata: $data\n\n"

        const val V2_SHOT =
            """"ball_speed_mph":106.1,"club":"7-iron","estimated_carry_yards":152,""" +
                """"event_id":"05dd37ec-49ed-596b-b1a4-953d54e4f239","profile_id":"p1","profile_name":"Zoë",""" +
                """"schema_version":2,"shot_number":7,"timestamp":"2026-09-25T14:03:07.412345","type":"shot""""

        // What the backend's v2 stream opens with (club, profiles), then a shot's two versions and events.
        val V2_STREAM =
            ": ping\n\n" +
                sse("club_changed", """{"club":"7-iron","schema_version":2,"type":"club_changed"}""") +
                sse(
                    "profiles",
                    """{"active_profile_id":"p1","profiles":[{"id":"p1","name":"Zoë ⛳"}],""" +
                        """"schema_version":2,"type":"profiles"}""",
                ) +
                sse("shot_processing", """{"schema_version":2,"state":"capturing","type":"shot_processing"}""") +
                sse("shot", """{$V2_SHOT,"final":false,"enrichment":{"status":"pending"}}""") +
                sse("shot", """{$V2_SHOT,"final":true,"enrichment":{"status":"complete"}}""") +
                sse(
                    "shot_deleted",
                    """{"schema_version":2,"timestamp":"2026-09-25T14:03:07.412345","type":"shot_deleted"}""",
                ) +
                sse("session_cleared", """{"profile_id":"p1","schema_version":2,"type":"session_cleared"}""")
    }
}
