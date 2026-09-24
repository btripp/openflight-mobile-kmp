// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import app.cash.turbine.test
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.PiLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class ShotRepositoryTest {
    private class Harness(
        scope: CoroutineScope,
        transport: TransportType,
        host: String,
    ) {
        val events = mutableListOf<String>()
        val settings = FakeSettingsRepository(transport = transport, host = host)
        val ble = FakeShotTransport("ble", events)
        val wifiTransports = mutableListOf<Pair<String, FakeShotTransport>>()
        val logs = mutableListOf<String>()
        val sockets = mutableListOf<FakePiSocket>()
        val piSession =
            DefaultPiSessionRepository(
                settings = settings,
                socketFactory = { socketHost, _ -> FakePiSocket(socketHost).also { sockets += it } },
                cameraSource = { emptyFlow() },
                scope = scope,
            )
        val repository =
            DefaultShotRepository(
                settings = settings,
                bluetoothTransport = ble,
                wifiTransportFactory = { host ->
                    FakeShotTransport("wifi($host)", events).also { wifiTransports += host to it }
                },
                scope = scope,
                piControl = fakePiControlClient(),
                piSession = piSession,
                log = { logs += it },
            )

        val wifi: FakeShotTransport get() = wifiTransports.last().second

        /** The Pi's Socket.IO link, connected, with the automatic on-connect requests forgotten. */
        fun piConnected(): FakePiSocket =
            sockets.last().apply {
                serverAcks()
                emitted.clear()
            }
    }

    private fun runRepositoryTest(
        transport: TransportType = TransportType.BLUETOOTH,
        host: String = "pi.local",
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness(backgroundScope, transport, host)
        harness.repository.start()
        body(harness)
    }

    // region transport selection

    @Test
    fun startConnectsTheSelectedTransportOnly() =
        runRepositoryTest { h ->
            assertThat(h.ble.startCount).isEqualTo(1)
            assertThat(h.wifiTransports).isEmpty()
        }

    @Test
    fun startIsIdempotent() =
        runRepositoryTest { h ->
            h.repository.start()

            assertThat(h.ble.startCount).isEqualTo(1)
        }

    @Test
    fun switchingTransportDisconnectsTheOldOneBeforeStartingTheNewOne() =
        runRepositoryTest { h ->
            h.settings.setTransport(TransportType.WIFI)

            assertThat(h.events).containsExactly("ble.start", "ble.disconnect", "wifi(pi.local).start")
        }

    @Test
    fun switchingTransportCancelsTheOldCollection() =
        runRepositoryTest { h ->
            h.settings.setTransport(TransportType.WIFI)

            h.ble.state.value = ConnectionState.Error("stale")
            h.ble.shots.emit(shot(99))
            h.ble.activeClub.value = GolfClub.SAND_WEDGE

            assertThat(h.repository.connectionState.value).isEqualTo(ConnectionState.Idle)
            assertThat(h.repository.history.value).isEmpty()
            assertThat(h.repository.activeClub.value).isNull()
            assertThat(h.settings.clubWrites).isEmpty()
        }

    @Test
    fun aHostChangeBuildsANewWifiTransport() =
        runRepositoryTest(transport = TransportType.WIFI, host = "pi-a.local") { h ->
            val first = h.wifi

            h.settings.setHost("pi-b.local")

            assertThat(h.wifiTransports.map { it.first }).containsExactly("pi-a.local", "pi-b.local")
            assertThat(first.disconnectCount).isEqualTo(1)
            assertThat(h.wifi.startCount).isEqualTo(1)
            assertThat(h.events)
                .containsExactly("wifi(pi-a.local).start", "wifi(pi-a.local).disconnect", "wifi(pi-b.local).start")
        }

    @Test
    fun aHostChangeWhileOnBluetoothDoesNotRestartBluetooth() =
        runRepositoryTest { h ->
            h.settings.setHost("pi-b.local")

            assertThat(h.events).containsExactly("ble.start")
        }

    @Test
    fun stopDisconnectsTheActiveTransportAndReturnsToIdle() =
        runRepositoryTest { h ->
            h.ble.state.value = ConnectionState.Connected
            h.ble.supportsControls.value = true

            h.repository.stop()

            assertThat(h.ble.disconnectCount).isEqualTo(1)
            assertThat(h.repository.connectionState.value).isEqualTo(ConnectionState.Idle)
            assertThat(h.repository.supportsControls.value).isEqualTo(false)
        }

    @Test
    fun startAfterStopReconnects() =
        runRepositoryTest { h ->
            h.repository.stop()
            h.repository.start()

            assertThat(h.events).containsExactly("ble.start", "ble.disconnect", "ble.start")
        }

    @Test
    fun retryDelegatesToTheActiveTransport() =
        runRepositoryTest { h ->
            h.repository.retry()

            assertThat(h.ble.retryCount).isEqualTo(1)
        }

    @Test
    fun retryWhileStoppedStarts() =
        runRepositoryTest { h ->
            h.repository.stop()

            h.repository.retry()

            assertThat(h.ble.startCount).isEqualTo(2)
            assertThat(h.ble.retryCount).isEqualTo(0)
        }

    @Test
    fun disconnectDelegatesToTheActiveTransport() =
        runRepositoryTest { h ->
            h.repository.disconnect()

            assertThat(h.ble.disconnectCount).isEqualTo(1)
        }

    // endregion

    // region state

    @Test
    fun errorsPropagateToConnectionState() =
        runRepositoryTest { h ->
            h.repository.connectionState.test {
                assertThat(awaitItem()).isEqualTo(ConnectionState.Idle)

                h.ble.state.value = ConnectionState.Scanning
                assertThat(awaitItem()).isEqualTo(ConnectionState.Scanning)

                h.ble.state.value = ConnectionState.Error("Unsupported shot schema_version 2")
                assertThat(awaitItem()).isEqualTo(ConnectionState.Error("Unsupported shot schema_version 2"))
            }
        }

    @Test
    fun supportsControlsAndActiveClubMirrorTheActiveTransport() =
        runRepositoryTest { h ->
            h.ble.supportsControls.value = true
            h.ble.activeClub.value = GolfClub.IRON_7

            assertThat(h.repository.supportsControls.value).isTrue()
            assertThat(h.repository.activeClub.value).isEqualTo(GolfClub.IRON_7)
        }

    // endregion

    // region history

    @Test
    fun shotsAreRecordedNewestFirst() =
        runRepositoryTest { h ->
            h.repository.history.test {
                assertThat(awaitItem()).isEmpty()

                h.ble.shots.emit(shot(1))
                assertThat(awaitItem().map { it.eventId }).containsExactly(shotId(1))

                h.ble.shots.emit(shot(2))
                assertThat(awaitItem().map { it.eventId }).containsExactly(shotId(2), shotId(1))
            }
            assertThat(
                h.repository.latestShot.value
                    ?.eventId,
            ).isEqualTo(shotId(2))
        }

    @Test
    fun historySurvivesATransportSwitchWithoutDuplicates() =
        runRepositoryTest { h ->
            h.ble.shots.emit(shot(1))
            h.ble.shots.emit(shot(2))

            h.settings.setTransport(TransportType.WIFI)
            // The Pi replays its last shot to a new SSE client.
            h.wifi.shots.emit(shot(2))
            h.wifi.shots.emit(shot(3))

            assertThat(
                h.repository.history.value
                    .map { it.eventId },
            ).containsExactly(shotId(3), shotId(2), shotId(1))
            assertThat(
                h.repository.latestShot.value
                    ?.eventId,
            ).isEqualTo(shotId(3))
        }

    @Test
    fun historyIsCappedAtOneHundredShots() =
        runRepositoryTest { h ->
            repeat(105) { index -> h.ble.shots.emit(shot(index)) }

            val ids =
                h.repository.history.value
                    .map { it.eventId }
            assertThat(ids.size).isEqualTo(100)
            assertThat(ids.first()).isEqualTo(shotId(104))
            assertThat(ids.last()).isEqualTo(shotId(5))
        }

    // endregion

    // region club persistence

    @Test
    fun setClubPersistsTheClubTheServerConfirmed() =
        runRepositoryTest { h ->
            h.ble.setClubResponse = { ClubSelection(status = "ok", club = GolfClub.IRON_8) }

            val result = h.repository.setClub(GolfClub.IRON_7)

            assertThat(h.ble.setClubCalls).containsExactly(GolfClub.IRON_7)
            assertThat(result.club).isEqualTo(GolfClub.IRON_8)
            assertThat(h.settings.clubWrites).containsExactly(GolfClub.IRON_8)
        }

    @Test
    fun aFailedSetClubPersistsNothing() =
        runRepositoryTest { h ->
            h.ble.setClubResponse = { error("busy") }

            assertFailure { h.repository.setClub(GolfClub.IRON_7) }

            assertThat(h.settings.clubWrites).isEmpty()
            assertThat(h.settings.clubState.value).isEqualTo(GolfClub.DRIVER)
        }

    @Test
    fun controlCallsWhileStoppedFail() =
        runRepositoryTest { h ->
            h.repository.stop()

            assertFailure { h.repository.setClub(GolfClub.IRON_7) }.isInstanceOf<NoActiveTransportException>()
            assertThat(h.ble.setClubCalls).isEmpty()
        }

    @Test
    fun aClubChangedEventIsPersisted() =
        runRepositoryTest(transport = TransportType.WIFI) { h ->
            h.wifi.activeClub.value = GolfClub.PITCHING_WEDGE

            assertThat(h.settings.clubWrites).containsExactly(GolfClub.PITCHING_WEDGE)
        }

    @Test
    fun setClubUsesTheWifiTransportWhenWifiIsSelected() =
        runRepositoryTest(transport = TransportType.WIFI) { h ->
            h.repository.setClub(GolfClub.LOB_WEDGE)

            assertThat(h.wifi.setClubCalls).containsExactly(GolfClub.LOB_WEDGE)
            assertThat(h.ble.setClubCalls).isEmpty()
        }

    // endregion

    // region club sync on connect

    @Test
    fun wifiSyncsTheServerClubOnceWhenConnected() =
        runRepositoryTest(transport = TransportType.WIFI) { h ->
            h.wifi.currentClubResponse = { ClubSelection(status = "ok", club = GolfClub.IRON_5) }

            h.wifi.state.value = ConnectionState.Connecting
            assertThat(h.wifi.currentClubCalls).isEqualTo(0)

            h.wifi.state.value = ConnectionState.Connected
            assertThat(h.wifi.currentClubCalls).isEqualTo(1)
            // The server's club overwrites the stored driver.
            assertThat(h.settings.clubState.value).isEqualTo(GolfClub.IRON_5)
        }

    @Test
    fun anotherEmissionWithinTheSameConnectionDoesNotSyncAgain() =
        runRepositoryTest(transport = TransportType.WIFI) { h ->
            h.wifi.state.value = ConnectionState.Connected
            // A combined-upstream emission while still connected.
            h.wifi.supportsControls.value = true
            h.wifi.supportsControls.value = false

            assertThat(h.wifi.currentClubCalls).isEqualTo(1)
        }

    @Test
    fun aReconnectSyncsAgain() =
        runRepositoryTest(transport = TransportType.WIFI) { h ->
            h.wifi.state.value = ConnectionState.Connected
            h.wifi.state.value = ConnectionState.Error("OpenFlight closed the connection.")
            h.wifi.state.value = ConnectionState.Connecting
            h.wifi.state.value = ConnectionState.Connected

            assertThat(h.wifi.currentClubCalls).isEqualTo(2)
        }

    @Test
    fun bluetoothWaitsForControlSupportBeforeSyncing() =
        runRepositoryTest { h ->
            h.ble.currentClubResponse = { ClubSelection(status = "ok", club = GolfClub.HYBRID_3) }

            h.ble.state.value = ConnectionState.Connected
            assertThat(h.ble.currentClubCalls).isEqualTo(0)

            h.ble.supportsControls.value = true
            assertThat(h.ble.currentClubCalls).isEqualTo(1)
            assertThat(h.settings.clubState.value).isEqualTo(GolfClub.HYBRID_3)

            h.ble.supportsControls.value = false
            h.ble.supportsControls.value = true
            assertThat(h.ble.currentClubCalls).isEqualTo(1)
        }

    @Test
    fun bluetoothWithoutControlsNeverSyncs() =
        runRepositoryTest { h ->
            h.ble.state.value = ConnectionState.Connected

            assertThat(h.ble.currentClubCalls).isEqualTo(0)
            assertThat(h.settings.clubWrites).isEmpty()
        }

    @Test
    fun aFailedSyncIsLoggedAndLeavesTheConnectionAlone() =
        runRepositoryTest(transport = TransportType.WIFI) { h ->
            h.wifi.currentClubResponse = { error("OpenFlight returned HTTP 500.") }

            h.wifi.state.value = ConnectionState.Connected

            assertThat(h.wifi.currentClubCalls).isEqualTo(1)
            assertThat(h.repository.connectionState.value).isEqualTo(ConnectionState.Connected)
            assertThat(h.settings.clubWrites).isEmpty()
            assertThat(h.logs.size).isEqualTo(1)
        }

    // endregion

    // region local history edits (plan R5a)

    @Test
    fun deleteShotRemovesOnlyThatShot() =
        runRepositoryTest { h ->
            h.ble.shots.emit(shot(1))
            h.ble.shots.emit(shot(2))
            h.ble.shots.emit(shot(3))

            h.repository.deleteShot(shotId(2))

            assertThat(
                h.repository.history.value
                    .map { it.eventId },
            ).containsExactly(shotId(3), shotId(1))
            assertThat(
                h.repository.latestShot.value
                    ?.eventId,
            ).isEqualTo(shotId(3))
        }

    @Test
    fun deletingAnAbsentShotIsANoOp() =
        runRepositoryTest { h ->
            h.ble.shots.emit(shot(1))

            h.repository.deleteShot(shotId(99))

            assertThat(
                h.repository.history.value
                    .map { it.eventId },
            ).containsExactly(shotId(1))
        }

    @Test
    fun deletingTheLatestShotUpdatesLatestShot() =
        runRepositoryTest { h ->
            h.ble.shots.emit(shot(1))
            h.ble.shots.emit(shot(2))

            h.repository.deleteShot(shotId(2))

            assertThat(
                h.repository.latestShot.value
                    ?.eventId,
            ).isEqualTo(shotId(1))
        }

    @Test
    fun clearHistoryEmptiesTheHistoryAndLatestShot() =
        runRepositoryTest { h ->
            h.ble.shots.emit(shot(1))
            h.ble.shots.emit(shot(2))

            h.repository.clearHistory()

            assertThat(h.repository.history.value).isEmpty()
            assertThat(h.repository.latestShot.value).isNull()
        }

    // endregion

    // region Pi shutdown (plan R5a)

    @Test
    fun shutdownPiCallsTheControlClientOverWifi() =
        runRepositoryTest(transport = TransportType.WIFI, host = "pi.local:8091") { h ->
            h.repository.shutdownPi()
            // No exception: the fake control client answered 200 "shutting_down".
        }

    @Test
    fun shutdownPiOverBluetoothFailsWithoutCallingTheServer() =
        runRepositoryTest { h ->
            assertFailsWith<PiShutdownUnsupportedException> { h.repository.shutdownPi() }
        }

    @Test
    fun shutdownPiWhileStoppedFails() =
        runRepositoryTest(transport = TransportType.WIFI) { h ->
            h.repository.stop()

            assertFailsWith<PiShutdownUnsupportedException> { h.repository.shutdownPi() }
        }

    // endregion

    // region Pi session integration (plan R6b)

    @Test
    fun startAndStopDriveThePiSessionOnWifiWithTheSameHost() =
        runRepositoryTest(transport = TransportType.WIFI, host = "pi.local:8091") { h ->
            assertThat(h.sockets.map { it.host }).containsExactly("pi.local:8091")
            h.piConnected()
            assertThat(h.piSession.linkState.value).isEqualTo(PiLinkState.Connected)

            h.repository.stop()

            assertThat(h.sockets.single().disconnectCount).isEqualTo(1)
            assertThat(h.piSession.linkState.value).isEqualTo(PiLinkState.Idle)

            h.repository.start()

            assertThat(h.sockets).hasSize(2)
        }

    @Test
    fun onBluetoothThePiSessionStaysWifiOnlyWithoutASocket() =
        runRepositoryTest { h ->
            assertThat(h.sockets).isEmpty()
            assertThat(h.piSession.linkState.value).isEqualTo(PiLinkState.WifiOnly)
        }

    @Test
    fun whileThePiIsConnectedDeleteShotAlsoDeletesItOnThePiByTimestamp() =
        runRepositoryTest(transport = TransportType.WIFI) { h ->
            val socket = h.piConnected()
            h.wifi.shots.emit(timedShot(1, "2026-09-24T15:38:33.264795"))
            h.wifi.shots.emit(timedShot(2, "2026-09-24T15:39:00.000001"))

            h.repository.deleteShot(shotId(1))

            assertThat(
                h.repository.history.value
                    .map { it.eventId },
            ).containsExactly(shotId(2))
            assertThat(socket.emitted).containsExactly(
                "delete_shot" to buildJsonObject { put("timestamp", JsonPrimitive("2026-09-24T15:38:33.264795")) },
            )
        }

    @Test
    fun deleteShotByTimestampDeletesOnThePiAndTheMatchingLocalShot() =
        runRepositoryTest(transport = TransportType.WIFI) { h ->
            val socket = h.piConnected()
            h.wifi.shots.emit(timedShot(1, "2026-09-24T15:38:33.264795"))
            h.wifi.shots.emit(timedShot(2, "2026-09-24T15:39:00.000001"))

            h.repository.deleteShotByTimestamp("2026-09-24T15:39:00.000001")
            h.repository.deleteShotByTimestamp("2026-09-24T10:00:00") // Only on the Pi: no local shot has it.

            assertThat(
                h.repository.history.value
                    .map { it.eventId },
            ).containsExactly(shotId(1))
            assertThat(socket.emittedNames).containsExactly("delete_shot", "delete_shot")
        }

    @Test
    fun whileThePiIsConnectedClearHistoryAlsoClearsThePiSession() =
        runRepositoryTest(transport = TransportType.WIFI) { h ->
            val socket = h.piConnected()
            h.wifi.shots.emit(shot(1))

            h.repository.clearHistory()

            assertThat(h.repository.history.value).isEmpty()
            assertThat(socket.emittedNames).containsExactly("clear_session")
        }

    @Test
    fun whileThePiIsNotConnectedDeleteAndClearAreLocalOnly() =
        runRepositoryTest(transport = TransportType.WIFI) { h ->
            val socket = h.sockets.single() // Connecting, never acknowledged.
            h.wifi.shots.emit(shot(1))
            h.wifi.shots.emit(shot(2))

            h.repository.deleteShot(shotId(1))
            h.repository.deleteShotByTimestamp("2026-09-24T10:00:00")
            h.repository.clearHistory()

            assertThat(h.repository.history.value).isEmpty()
            assertThat(socket.emitted).isEmpty()
            assertThat(h.logs).isEmpty()
        }

    @Test
    fun onBluetoothDeleteAndClearAreLocalOnly() =
        runRepositoryTest { h ->
            h.ble.shots.emit(shot(1))
            h.ble.shots.emit(shot(2))

            h.repository.deleteShot(shotId(2))

            assertThat(
                h.repository.history.value
                    .map { it.eventId },
            ).containsExactly(shotId(1))
            assertThat(h.sockets).isEmpty()
        }

    // endregion
}

private fun timedShot(
    number: Int,
    timestamp: String,
): ShotEvent = shot(number).copy(timestamp = timestamp)
