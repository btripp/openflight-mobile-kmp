// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import app.cash.turbine.test
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.single
import dev.openflight.companion.core.database.buildShotHistoryDatabase
import dev.openflight.companion.core.database.inMemoryShotHistoryDatabaseBuilder
import dev.openflight.companion.core.model.ConnectionErrorKind
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.network.OpenFlightHttpError
import dev.openflight.companion.core.socketio.SocketConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

/**
 * Plan R8j: Wi-Fi to a stock upstream Pi (no `/api/shots/stream`, no `/api/club`) versus the fork
 * backend (both), through the real [DefaultShotRepository] and [DefaultPiSessionRepository] over a
 * fake Wi-Fi transport and a fake Socket.IO socket.
 */
class StockPiFallbackTest {
    private class Harness(
        scope: CoroutineScope,
    ) {
        val settings =
            FakeSettingsRepository(transport = TransportType.WIFI, host = HOST, club = GolfClub.PITCHING_WEDGE)
        val ble = FakeShotTransport("ble")
        val wifiTransports = mutableListOf<FakeShotTransport>()
        val sockets = mutableListOf<FakePiSocket>()
        val piSession =
            DefaultPiSessionRepository(
                settings = settings,
                socketFactory = { socketHost, _ -> FakePiSocket(socketHost).also { sockets += it } },
                cameraSource = FakePiCameraSource(),
                scope = scope,
            )
        val history =
            DefaultShotHistoryRepository(
                openDatabase = { inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase() },
                scope = scope,
                log = {},
            )
        val repository =
            DefaultShotRepository(
                settings = settings,
                bluetoothTransport = ble,
                wifiTransportFactory = { host -> FakeShotTransport("wifi($host)").also { wifiTransports += it } },
                scope = scope,
                piControl = fakePiControlClient(),
                piSession = piSession,
                persistentHistory = history,
            )

        val wifi: FakeShotTransport get() = wifiTransports.last()
        val socket: FakePiSocket get() = sockets.last()

        /** The Wi-Fi transport got 404 from `/api/shots/stream`, as on a stock Pi. */
        fun sseMissing() {
            wifi.state.value = ConnectionState.Error(SSE_404, ConnectionErrorKind.STREAM_UNAVAILABLE)
        }

        fun piConnected(): FakePiSocket =
            socket.apply {
                serverAcks()
                emitted.clear()
            }

        /**
         * A stock backend without `/api/club` or the calibration route: its GET-only static
         * catch-all answers `GET` with 404 and `POST` with 405 (verified against upstream `main`
         * 7ca4b40 `--mock`).
         */
        fun noClubApi() {
            wifi.supportsControls.value = true
            wifi.setClubResponse = { routeAbsent(405) }
            wifi.currentClubResponse = { routeAbsent(404) }
            wifi.calibrationResponse = { routeAbsent(405) }
        }

        private fun routeAbsent(status: Int): Nothing = throw OpenFlightHttpError.UnexpectedStatus(status)

        suspend fun storedTimestamps(): List<String> {
            history.awaitWrites()
            val session = checkNotNull(history.currentSessionId.value)
            return history.shots(session).first().map { it.detail.timestamp }
        }
    }

    private fun runStockPiTest(body: suspend TestScope.(Harness) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val harness = Harness(backgroundScope)
            harness.repository.start()
            body(harness)
        }

    // (a) Stock Pi: SSE 404 + Socket.IO.

    @Test
    fun stockPiLiveShotsFeedHistoryAndLatestShot() =
        runStockPiTest { h ->
            h.sseMissing()
            val socket = h.piConnected()

            h.repository.history.test {
                assertThat(awaitItem()).isEmpty()
                socket.serverFrame(PiFixtures.SHOT_FRAME)
                val shot = awaitItem().single()
                assertThat(shot.timestamp).isEqualTo(PiFixtures.SHOT_TIMESTAMP)
                assertThat(shot.eventId).isEqualTo(SHOT_1_EVENT_ID)
                assertThat(shot.shotNumber).isEqualTo(1)
                assertThat(shot.club).isEqualTo("driver")
                assertThat(shot.ballSpeedMph).isEqualTo(116.2)
                assertThat(shot.estimatedCarryYards).isEqualTo(179.0)
                assertThat(shot.profileName).isEqualTo("Profile 1")
                assertThat(shot.final).isEqualTo(true)

                socket.serverFrame(PiFixtures.SECOND_SHOT_FRAME)
                assertThat(awaitItem().map { it.timestamp })
                    .containsExactly(PiFixtures.SECOND_SHOT_TIMESTAMP, PiFixtures.SHOT_TIMESTAMP)
            }
            assertThat(
                h.repository.latestShot.value
                    ?.timestamp,
            ).isEqualTo(PiFixtures.SECOND_SHOT_TIMESTAMP)
            assertThat(h.repository.liveShotSource.value).isEqualTo(LiveShotSource.SOCKET_IO)
        }

    @Test
    fun stockPiShotUpdateEnrichesTheSameShotInPlaceAndIsStoredOnce() =
        runStockPiTest { h ->
            h.sseMissing()
            val socket = h.piConnected()

            h.repository.history.test {
                assertThat(awaitItem()).isEmpty()
                socket.serverFrame(PROVISIONAL_SHOT_FRAME)
                val provisional = awaitItem().single()
                assertThat(provisional.final).isEqualTo(false)
                assertThat(provisional.enrichment?.status).isEqualTo("pending")

                socket.serverFrame(PiFixtures.SHOT_UPDATE_SKIPPED_FRAME)
                val final = awaitItem().single()
                assertThat(final.eventId).isEqualTo(provisional.eventId)
                assertThat(final.final).isEqualTo(true)
                assertThat(final.enrichment?.status).isEqualTo("skipped")
                expectNoEvents()
            }
            assertThat(h.storedTimestamps()).containsExactly(PiFixtures.SHOT_TIMESTAMP)
        }

    @Test
    fun stockPiShotIsFinalOnceForCallOutsAndGames() =
        runStockPiTest { h ->
            h.sseMissing()
            val socket = h.piConnected()
            val stream = DefaultFinalShotStream(h.repository.history)

            stream.finalShots().test {
                socket.serverFrame(PROVISIONAL_SHOT_FRAME)
                expectNoEvents()
                socket.serverFrame(PiFixtures.SHOT_UPDATE_SKIPPED_FRAME)
                assertThat(awaitItem().eventId).isEqualTo(SHOT_1_EVENT_ID)
                // A mock Pi's simulate_shot: nothing pending, so final as it arrives.
                socket.serverFrame(PiFixtures.SECOND_SHOT_FRAME)
                assertThat(awaitItem().timestamp).isEqualTo(PiFixtures.SECOND_SHOT_TIMESTAMP)
                expectNoEvents()
            }
        }

    @Test
    fun stockPiSwingSpeedRepsAreNotBallFlights() =
        runStockPiTest { h ->
            h.sseMissing()
            h.piConnected().server("shot", SWING_SPEED_SHOT_PAYLOAD)

            assertThat(h.repository.history.value).isEmpty()
        }

    @Test
    fun liveShotsBeforeTheStreamIsKnownToBeMissingAreNotShown() =
        runStockPiTest { h ->
            // SSE is still connecting: whether it exists isn't known yet, so Socket.IO doesn't feed.
            h.wifi.state.value = ConnectionState.Connecting
            h.piConnected().serverFrame(PiFixtures.SHOT_FRAME)

            assertThat(h.repository.history.value).isEmpty()
            assertThat(h.repository.liveShotSource.value).isEqualTo(LiveShotSource.NONE)
        }

    // #68: a stock Pi has no shot_deleted stream: a kiosk delete arrives only as session_state.
    @Test
    fun stockPiKioskDeleteLeavesHistoryAndStoredHistory() =
        runStockPiTest { h ->
            h.sseMissing()
            val socket = h.piConnected()
            socket.serverFrame(PiFixtures.CONNECT_SESSION_STATE_FRAME)
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            socket.serverFrame(PiFixtures.SECOND_SHOT_FRAME)

            // The kiosk deletes shot #1: every client gets the session without it.
            socket.serverFrame(PiFixtures.sessionStateFrame(PiFixtures.SECOND_SHOT_FRAME))

            assertThat(
                h.repository.history.value
                    .map { it.timestamp },
            ).containsExactly(PiFixtures.SECOND_SHOT_TIMESTAMP)
            assertThat(h.storedTimestamps()).containsExactly(PiFixtures.SECOND_SHOT_TIMESTAMP)
        }

    // #68: and a kiosk clear only as session_cleared, which keeps the other profiles' rows.
    @Test
    fun stockPiKioskClearLeavesThatProfilesShotsOnly() =
        runStockPiTest { h ->
            h.sseMissing()
            val socket = h.piConnected()
            socket.serverFrame(PiFixtures.CONNECT_SESSION_STATE_FRAME)
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            socket.serverFrame(PiFixtures.PROFILES_AFTER_ADD_FRAME)
            socket.serverFrame(PiFixtures.SECOND_SHOT_FRAME)

            // The kiosk clears Sam (shot #2); the default profile's shot #1 remains.
            socket.serverFrame(PiFixtures.SESSION_CLEARED_FRAME)

            assertThat(
                h.repository.history.value
                    .map { it.timestamp },
            ).containsExactly(PiFixtures.SHOT_TIMESTAMP)
            assertThat(h.storedTimestamps()).containsExactly(PiFixtures.SHOT_TIMESTAMP)
        }

    // (b) Fork: SSE + Socket.IO.

    @Test
    fun forkSseStaysTheOnlySourceWithoutDuplicates() =
        runStockPiTest { h ->
            h.wifi.state.value = ConnectionState.Connected
            val socket = h.piConnected()
            assertThat(h.repository.liveShotSource.value).isEqualTo(LiveShotSource.SSE)

            h.repository.history.test {
                assertThat(awaitItem()).isEmpty()
                socket.serverFrame(PiFixtures.SHOT_FRAME)
                socket.serverFrame(PiFixtures.SHOT_UPDATE_SKIPPED_FRAME)
                expectNoEvents()
                val sse =
                    ShotEvent(
                        schemaVersion = 1,
                        eventId = shotId(7),
                        timestamp = PiFixtures.SHOT_TIMESTAMP,
                        club = "driver",
                        ballSpeedMph = 116.2,
                        estimatedCarryYards = 179.0,
                    )
                h.wifi.shots.emit(sse)
                assertThat(awaitItem()).containsExactly(sse)
                expectNoEvents()
            }
            // Socket.IO still enriches the SSE shot.
            assertThat(
                h.piSession
                    .detailFor(
                        h.repository.history.value
                            .single(),
                    )?.profileName,
            ).isEqualTo("Profile 1")
            assertThat(h.storedTimestamps()).containsExactly(PiFixtures.SHOT_TIMESTAMP)
        }

    @Test
    fun forkClubChangesUseTheApiClubRouteNotSocketIo() =
        runStockPiTest { h ->
            h.wifi.state.value = ConnectionState.Connected
            h.wifi.supportsControls.value = true
            val socket = h.piConnected()

            val selection = h.repository.setClub(GolfClub.IRON_7)

            assertThat(selection.club).isEqualTo(GolfClub.IRON_7)
            assertThat(h.wifi.setClubCalls).containsExactly(GolfClub.IRON_7)
            assertThat(socket.emittedNames.filter { it == "set_club" }).isEmpty()
            assertThat(h.settings.clubState.value).isEqualTo(GolfClub.IRON_7)
        }

    // (c) Club via the Socket.IO fallback.

    @Test
    fun stockPiClubChangeFallsBackToSetClubAndFollowsClubChanged() =
        runStockPiTest { h ->
            h.noClubApi()
            h.sseMissing()
            val socket = h.piConnected()

            val change = async { h.repository.setClub(GolfClub.IRON_7) }
            assertThat(
                socket.emitted
                    .single { it.first == "set_club" }
                    .second
                    ?.jsonObject
                    ?.get("club")
                    ?.jsonPrimitive
                    ?.content,
            ).isEqualTo("7-iron")
            // Nothing is persisted before the Pi confirms (plan §9.2: no optimistic flip on Wi-Fi).
            assertThat(h.settings.clubState.value).isEqualTo(GolfClub.PITCHING_WEDGE)
            socket.serverFrame(PiFixtures.CLUB_CHANGED_FRAME)

            assertThat(change.await().club).isEqualTo(GolfClub.IRON_7)
            assertThat(h.settings.clubState.value).isEqualTo(GolfClub.IRON_7)
            assertThat(h.repository.activeClub.value).isEqualTo(GolfClub.IRON_7)

            // The 405 is remembered for this connection: the next change goes straight to Socket.IO.
            socket.emitted.clear()
            val again = async { h.repository.setClub(GolfClub.DRIVER) }
            socket.server("club_changed", """{"club":"driver"}""")
            assertThat(again.await().club).isEqualTo(GolfClub.DRIVER)
            assertThat(h.wifi.setClubCalls).containsExactly(GolfClub.IRON_7)
            assertThat(socket.emittedNames).containsExactly("set_club")
        }

    @Test
    fun stockPiClubChangeWithoutConfirmationFailsAndPersistsNothing() =
        runStockPiTest { h ->
            h.noClubApi()
            h.sseMissing()
            h.piConnected()

            val change = async { runCatching { h.repository.setClub(GolfClub.IRON_7) } }
            advanceTimeBy(CLUB_TIMEOUT_MILLIS + 1)

            assertThat(change.await().exceptionOrNull()).isNotNull().isInstanceOf<ClubChangeNotConfirmedException>()
            assertThat(h.settings.clubWrites).isEmpty()
        }

    @Test
    fun stockPiClubFollowsSessionStateAndClubChangedWithoutGetApiClub() =
        runStockPiTest { h ->
            h.noClubApi()
            h.sseMissing()
            val socket = h.piConnected()

            socket.serverFrame(PiFixtures.CONNECT_SESSION_STATE_FRAME)
            assertThat(h.settings.clubState.value).isEqualTo(GolfClub.DRIVER)
            socket.serverFrame(PiFixtures.CLUB_CHANGED_FRAME)
            assertThat(h.settings.clubState.value).isEqualTo(GolfClub.IRON_7)
            assertThat(h.repository.activeClub.value).isEqualTo(GolfClub.IRON_7)
            // The on-connect GET /api/club runs only once the SSE stream connects, which it never does.
            assertThat(h.wifi.currentClubCalls).isEqualTo(0)
            assertThat(h.repository.currentClub().club).isEqualTo(GolfClub.IRON_7)
        }

    @Test
    fun bluetoothClubErrorsAreNotRoutedToSocketIo() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(backgroundScope)
            h.settings.transportState.value = TransportType.BLUETOOTH
            h.ble.setClubResponse = { throw OpenFlightHttpError.UnexpectedStatus(404) }
            h.repository.start()

            assertFailure { h.repository.setClub(GolfClub.IRON_7) }.isInstanceOf<OpenFlightHttpError.UnexpectedStatus>()
            assertThat(h.sockets).isEmpty()
        }

    // (c2) Calibration: no route and no Socket.IO equivalent on a stock Pi.

    @Test
    fun stockPiCalibrationFailsWithAnExplanationNotHttp405() =
        runStockPiTest { h ->
            h.noClubApi()
            h.sseMissing()
            h.piConnected()

            assertFailure { h.repository.submitCalibration(MEASUREMENT) }
                .isInstanceOf<CalibrationUnsupportedException>()
                .hasMessage(CalibrationUnsupportedException.MESSAGE)
        }

    @Test
    fun otherCalibrationFailuresAreUnchanged() =
        runStockPiTest { h ->
            h.wifi.state.value = ConnectionState.Connected
            h.wifi.calibrationResponse = { throw OpenFlightHttpError.UnexpectedStatus(500, "tilt out of range") }

            assertFailure { h.repository.submitCalibration(MEASUREMENT) }
                .isEqualTo(OpenFlightHttpError.UnexpectedStatus(500, "tilt out of range"))
        }

    // (d) Connection status.

    @Test
    fun sse404IsNotAnErrorWhileTheSocketIoLinkIsConnected() =
        runStockPiTest { h ->
            h.repository.connectionState.test {
                assertThat(awaitItem()).isEqualTo(ConnectionState.Idle)
                h.wifi.state.value = ConnectionState.Connecting
                assertThat(awaitItem()).isEqualTo(ConnectionState.Connecting)
                // The 404 arrives while the Socket.IO link is still connecting: keep "Connecting".
                h.sseMissing()
                expectNoEvents()
                h.piConnected()
                assertThat(awaitItem()).isEqualTo(ConnectionState.Connected)

                // A dropped link is the error to show (its reason, not the 404).
                h.socket.state.value = SocketConnectionState.Reconnecting(1, 500, "Connection closed")
                assertThat(awaitItem()).isEqualTo(ConnectionState.Error("Connection closed"))
                h.socket.serverAcks()
                assertThat(awaitItem()).isEqualTo(ConnectionState.Connected)
                expectNoEvents()
            }
            // The transport isn't asked to retry the missing stream on its own.
            assertThat(h.wifi.retryCount).isEqualTo(0)
            assertThat(h.wifi.startCount).isEqualTo(1)
        }

    @Test
    fun otherSseErrorsAreUnchanged() =
        runStockPiTest { h ->
            h.piConnected()
            h.wifi.state.value = ConnectionState.Error("OpenFlight returned HTTP 500.")

            assertThat(
                h.repository.connectionState.value,
            ).isEqualTo(ConnectionState.Error("OpenFlight returned HTTP 500."))
            assertThat(h.repository.liveShotSource.value).isEqualTo(LiveShotSource.NONE)
        }

    @Test
    fun aHostChangeStartsAFreshProbe() =
        runStockPiTest { h ->
            h.sseMissing()
            h.piConnected()
            assertThat(h.repository.connectionState.value).isEqualTo(ConnectionState.Connected)

            h.settings.hostState.value = "10.0.0.9:8080"

            assertThat(h.wifiTransports).hasSize(2)
            assertThat(h.wifi.startCount).isEqualTo(1)
            // The new host's stream is unknown and its link not up yet: not Connected on the old link's word.
            assertThat(h.repository.connectionState.value).isEqualTo(ConnectionState.Idle)
            assertThat(h.repository.liveShotSource.value).isEqualTo(LiveShotSource.NONE)
        }

    // The mapping.

    @Test
    fun liveShotIdsMatchTheForkBackendsStableShotEventId() {
        // Values from Python: uuid.uuid5(UUID("D49C99A9-A305-49CA-A8C2-7D30B7645988"), f"{ts}#{n}").
        assertThat(stableShotEventId(PiFixtures.SHOT_TIMESTAMP, 1)).isEqualTo(SHOT_1_EVENT_ID)
        assertThat(
            stableShotEventId(PiFixtures.SECOND_SHOT_TIMESTAMP, 2),
        ).isEqualTo("065120be-3e62-5570-a859-5975ecaf644a")
        assertThat(stableShotEventId(PiFixtures.SHOT_TIMESTAMP, null)).isEqualTo("d3154a52-936c-5f56-a1a4-e2bf8d86ce62")
    }

    @Test
    fun aRowWithoutBallSpeedIsNotAShotEvent() {
        val detail =
            dev.openflight.companion.core.model.pi
                .ShotDetail(timestamp = "t", estimatedCarryYards = 100.0)
        assertThat(PiLiveShot(detail, null).toShotEvent()).isNull()
    }

    private companion object {
        const val HOST = "192.168.1.20:8080"
        val MEASUREMENT =
            PhoneOrientationMeasurement(
                mountTiltDeg = 12.0,
                rollDeg = 0.5,
                gravityXG = 0.01,
                gravityYG = -0.02,
                gravityZG = -0.99,
                tiltStddevDeg = 0.1,
                rollStddevDeg = 0.2,
                sampleCount = 120,
                measuredAt = "2026-09-28T00:00:00Z",
                deviceModel = "test",
            )
        const val SSE_404 = "OpenFlight returned HTTP 404: this Pi has no SSE shot stream."
        const val SHOT_1_EVENT_ID = "05e130db-e939-5537-acd3-d54cd64007c0"
        const val CLUB_TIMEOUT_MILLIS = 10_000L

        /** [PiFixtures.SHOT_FRAME] as an OPS-only `shot` still waiting for the IWR6843 (server.py:3393-3405). */
        val PROVISIONAL_SHOT_FRAME: String =
            PiFixtures.SHOT_FRAME.replaceFirst("},\"stats\":{", "},\"pending\":{\"iwr6843\":true},\"stats\":{")

        /** `swing_speed_to_shot_dict` (server.py:3640-3658): a rep, not a ball flight. */
        const val SWING_SPEED_SHOT_PAYLOAD =
            """{"shot":{"timestamp":"2026-09-24T16:00:00.000001","club":"Swing Speed","mode":"swing-speed",""" +
                """"ball_speed_mph":97.4,"estimated_carry_yards":0}}"""
    }
}
