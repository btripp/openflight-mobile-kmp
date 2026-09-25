// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.socketio.SocketConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

/**
 * Server-confirmed delete and per-profile clear over [DefaultPiSessionRepository], ported from the
 * Expo app's `socket.test.ts` "deleting a shot" (`feat/delete-shot` e525d90) and "clearing a
 * profile from the session" (`feat/stats-tab` b610cd3), against the captured [PiFixtures].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PiSessionDeleteAndClearTest {
    private class Harness(
        scope: CoroutineScope,
    ) {
        val settings = FakeSettingsRepository(transport = TransportType.WIFI, host = "pi.local:8080")
        val sockets = mutableListOf<FakePiSocket>()
        val repository =
            DefaultPiSessionRepository(
                settings = settings,
                socketFactory = { host, _ -> FakePiSocket(host).also { sockets += it } },
                cameraSource = FakePiCameraSource(),
                scope = scope,
            )
        val socket: FakePiSocket get() = sockets.last()

        val deletion: DeletionState get() = repository.deletionState.value
        val clear: ClearState get() = repository.clearState.value

        fun sessionTimestamps(): List<String> = repository.sessionShots.value.map { it.timestamp }

        fun drop() {
            socket.state.value = SocketConnectionState.Reconnecting(1, 500, "Connection closed")
        }

        fun reconnect() {
            socket.serverAcks()
        }
    }

    /** Connected, with both captured shots in the session and the on-connect requests forgotten. */
    private fun runSessionTest(body: suspend TestScope.(Harness) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(backgroundScope)
            h.repository.start()
            h.socket.serverAcks()
            h.socket.serverFrame(PiFixtures.SHOT_FRAME)
            h.socket.serverFrame(PiFixtures.SECOND_SHOT_FRAME)
            h.socket.emitted.clear()
            body(h)
        }

    // region deleting a shot

    @Test
    fun deleteAsksTheServerForTheShotByItsTimestamp() =
        runSessionTest { h ->
            h.repository.deleteShot(PiFixtures.SHOT_TIMESTAMP)

            assertThat(h.socket.emitted).containsExactly(
                "delete_shot" to buildJsonObject { put("timestamp", PiFixtures.SHOT_TIMESTAMP) },
            )
            assertThat(h.deletion).isEqualTo(DeletionState.Pending(PiFixtures.SHOT_TIMESTAMP))
            // Nothing is removed before the server confirms.
            assertThat(h.sessionTimestamps().size).isEqualTo(2)
        }

    @Test
    fun withoutALiveConnectionNothingIsSentAndNothingWaits() =
        runSessionTest { h ->
            h.drop()

            assertFailure { h.repository.deleteShot(PiFixtures.SHOT_TIMESTAMP) }
                .isInstanceOf(WifiOnlyFeatureException::class)
            h.reconnect()

            assertThat(
                h.socket.emittedNames
                    .filter { it == "delete_shot" }
                    .size,
            ).isEqualTo(0)
            assertThat(h.deletion).isEqualTo(DeletionState.Idle)
        }

    @Test
    fun oneDeletionIsSentAtATime() =
        runSessionTest { h ->
            h.repository.deleteShot(PiFixtures.SHOT_TIMESTAMP)
            h.repository.deleteShot(PiFixtures.SECOND_SHOT_TIMESTAMP)

            assertThat(h.socket.emittedNames).containsExactly("delete_shot")
            assertThat(h.deletion).isEqualTo(DeletionState.Pending(PiFixtures.SHOT_TIMESTAMP))
        }

    @Test
    fun aSessionStateWithoutTheShotConfirmsTheDeletion() =
        runSessionTest { h ->
            h.repository.deleteShot(PiFixtures.SHOT_TIMESTAMP)

            h.socket.serverFrame(PiFixtures.SESSION_STATE_AFTER_DELETE_FRAME)

            assertThat(h.deletion).isEqualTo(DeletionState.Deleted(PiFixtures.SHOT_TIMESTAMP))
            assertThat(h.sessionTimestamps()).containsExactly()
        }

    @Test
    fun aSessionStateThatStillHoldsTheShotKeepsItWaiting() =
        runSessionTest { h ->
            h.repository.deleteShot(PiFixtures.SHOT_TIMESTAMP)

            // Another client connecting also produces a session_state.
            h.socket.serverFrame(PiFixtures.SESSION_STATE_AFTER_CLEAR_FRAME)

            assertThat(h.deletion).isEqualTo(DeletionState.Pending(PiFixtures.SHOT_TIMESTAMP))
        }

    @Test
    fun aRepeatedSessionStateLeavesTheConfirmedDeletionAlone() =
        runSessionTest { h ->
            h.repository.deleteShot(PiFixtures.SHOT_TIMESTAMP)
            h.socket.serverFrame(PiFixtures.SESSION_STATE_AFTER_DELETE_FRAME)

            h.socket.serverFrame(PiFixtures.SESSION_STATE_AFTER_DELETE_FRAME)

            assertThat(h.deletion).isEqualTo(DeletionState.Deleted(PiFixtures.SHOT_TIMESTAMP))
        }

    @Test
    fun theServersRefusalIsReportedWithItsReason() =
        runSessionTest { h ->
            h.repository.deleteShot(PiFixtures.SHOT_TIMESTAMP)

            h.socket.serverFrame(PiFixtures.DELETE_SHOT_ERROR_FRAME)

            assertThat(h.deletion).isEqualTo(DeletionState.Failed(PiFixtures.SHOT_TIMESTAMP, "Shot not found"))
            assertThat(h.sessionTimestamps().size).isEqualTo(2)
        }

    @Test
    fun aMalformedRefusalStillFailsWithAReasonOfItsOwn() =
        runSessionTest { h ->
            for (payload in listOf(null, "null", "{}", """{"error":42}""", """{"error":""}""")) {
                h.repository.dismissDeletion()
                h.repository.deleteShot(PiFixtures.SHOT_TIMESTAMP)

                h.socket.server("delete_shot_error", payload)

                assertThat(h.deletion).isEqualTo(
                    DeletionState.Failed(PiFixtures.SHOT_TIMESTAMP, DeletionState.SERVER_REFUSED),
                )
            }
        }

    @Test
    fun anotherClientsRefusalIsIgnoredWhenNothingOfOursWaits() =
        runSessionTest { h ->
            h.socket.serverFrame(PiFixtures.DELETE_SHOT_ERROR_FRAME)

            assertThat(h.deletion).isEqualTo(DeletionState.Idle)
        }

    @Test
    fun aRefusalAfterTheConfirmationIsIgnored() =
        runSessionTest { h ->
            h.repository.deleteShot(PiFixtures.SHOT_TIMESTAMP)
            h.socket.serverFrame(PiFixtures.SESSION_STATE_AFTER_DELETE_FRAME)

            h.socket.serverFrame(PiFixtures.DELETE_SHOT_ERROR_FRAME)

            assertThat(h.deletion).isEqualTo(DeletionState.Deleted(PiFixtures.SHOT_TIMESTAMP))
        }

    @Test
    fun aDropBeforeTheReplyFailsTheDeletion() =
        runSessionTest { h ->
            h.repository.deleteShot(PiFixtures.SHOT_TIMESTAMP)

            h.drop()

            assertThat(h.deletion).isEqualTo(
                DeletionState.Failed(PiFixtures.SHOT_TIMESTAMP, DeletionState.CONNECTION_DROPPED),
            )
        }

    @Test
    fun afterAReconnectTheShotStaysAndCanBeDeletedAgain() =
        runSessionTest { h ->
            h.repository.deleteShot(PiFixtures.SHOT_TIMESTAMP)
            h.drop()
            h.reconnect()
            h.socket.serverFrame(PiFixtures.SESSION_STATE_AFTER_CLEAR_FRAME) // The re-sync still holds it.
            assertThat(h.deletion).isInstanceOf(DeletionState.Failed::class)
            h.socket.emitted.clear()

            h.repository.deleteShot(PiFixtures.SHOT_TIMESTAMP)

            assertThat(h.socket.emittedNames).containsExactly("delete_shot")
            assertThat(h.deletion).isEqualTo(DeletionState.Pending(PiFixtures.SHOT_TIMESTAMP))
        }

    @Test
    fun stoppingOrSwitchingHostsFailsAnInFlightDeletion() =
        runSessionTest { h ->
            h.repository.deleteShot(PiFixtures.SHOT_TIMESTAMP)

            h.settings.hostState.value = "10.0.0.9:8080"

            assertThat(h.deletion).isInstanceOf(DeletionState.Failed::class)
        }

    // endregion

    // region clearing a profile from the session

    @Test
    fun clearAsksTheServerToClearOneProfileById() =
        runSessionTest { h ->
            h.repository.clearSession(PiFixtures.SAM_PROFILE_ID)

            assertThat(h.socket.emitted).containsExactly(
                "clear_session" to buildJsonObject { put("profile_id", PiFixtures.SAM_PROFILE_ID) },
            )
            assertThat(h.clear).isEqualTo(ClearState.Pending(PiFixtures.SAM_PROFILE_ID))
        }

    @Test
    fun theConfirmationKeepsTheOtherProfilesShots() =
        runSessionTest { h ->
            h.repository.clearSession(PiFixtures.SAM_PROFILE_ID)

            h.socket.serverFrame(PiFixtures.SESSION_CLEARED_FRAME)

            assertThat(h.clear).isEqualTo(ClearState.Cleared(PiFixtures.SAM_PROFILE_ID))
            assertThat(h.sessionTimestamps()).containsExactly(PiFixtures.SHOT_TIMESTAMP)
        }

    @Test
    fun aClearMadeOnAnotherClientIsAppliedNewestFirst() =
        runSessionTest { h ->
            h.socket.server(
                "session_cleared",
                """{"profile_id":"someone","shots":[{"timestamp":"t1"},{"timestamp":"t3"}]}""",
            )

            assertThat(h.sessionTimestamps()).containsExactly("t3", "t1")
            assertThat(h.clear).isEqualTo(ClearState.Idle)
        }

    @Test
    fun aClearOfAnotherProfileDoesNotConfirmOurs() =
        runSessionTest { h ->
            h.repository.clearSession(PiFixtures.DEFAULT_PROFILE_ID)

            h.socket.serverFrame(PiFixtures.SESSION_CLEARED_FRAME)
            assertThat(h.clear).isEqualTo(ClearState.Pending(PiFixtures.DEFAULT_PROFILE_ID))

            h.socket.server("session_cleared", """{"profile_id":"${PiFixtures.DEFAULT_PROFILE_ID}","shots":[]}""")
            assertThat(h.clear).isEqualTo(ClearState.Cleared(PiFixtures.DEFAULT_PROFILE_ID))
        }

    @Test
    fun aRepeatedBroadcastLeavesTheListAlone() =
        runSessionTest { h ->
            h.repository.clearSession(PiFixtures.SAM_PROFILE_ID)
            h.socket.serverFrame(PiFixtures.SESSION_CLEARED_FRAME)

            h.socket.serverFrame(PiFixtures.SESSION_CLEARED_FRAME)

            assertThat(h.clear).isEqualTo(ClearState.Cleared(PiFixtures.SAM_PROFILE_ID))
            assertThat(h.sessionTimestamps()).containsExactly(PiFixtures.SHOT_TIMESTAMP)
        }

    @Test
    fun withoutAShotListTheSessionIsAskedForAgainInsteadOfGuessed() =
        runSessionTest { h ->
            h.repository.clearSession(PiFixtures.SAM_PROFILE_ID)
            h.socket.emitted.clear()

            h.socket.server("session_cleared", """{"profile_id":"${PiFixtures.SAM_PROFILE_ID}","shots":"nope"}""")

            assertThat(h.clear).isEqualTo(ClearState.Cleared(PiFixtures.SAM_PROFILE_ID))
            // Nothing dropped locally on a guess; the re-sync decides.
            assertThat(h.sessionTimestamps().size).isEqualTo(2)
            assertThat(h.socket.emittedNames).containsExactly("get_session")
        }

    @Test
    fun anEmptyBroadcastFromAnOlderServerReSyncs() =
        runSessionTest { h ->
            h.socket.server("session_cleared")
            h.socket.server("session_cleared", "null")

            assertThat(h.sessionTimestamps().size).isEqualTo(2)
            assertThat(h.socket.emittedNames).containsExactly("get_session", "get_session")
        }

    @Test
    fun withoutALiveConnectionAClearIsNotSentNorReplayed() =
        runSessionTest { h ->
            h.drop()

            assertFailure { h.repository.clearSession(PiFixtures.SAM_PROFILE_ID) }
                .isInstanceOf(WifiOnlyFeatureException::class)
            h.reconnect()

            assertThat(
                h.socket.emittedNames
                    .filter { it == "clear_session" }
                    .size,
            ).isEqualTo(0)
            assertThat(h.clear).isEqualTo(ClearState.Idle)
        }

    @Test
    fun aBlankProfileIdIsRefused() =
        runSessionTest { h ->
            assertFailure { h.repository.clearSession(" ") }.isInstanceOf(IllegalArgumentException::class)
            assertThat(h.socket.emitted.size).isEqualTo(0)
        }

    @Test
    fun aDropBeforeTheConfirmationFailsTheClear() =
        runSessionTest { h ->
            h.repository.clearSession(PiFixtures.SAM_PROFILE_ID)

            h.drop()

            assertThat(h.clear).isEqualTo(
                ClearState.Failed(PiFixtures.SAM_PROFILE_ID, ClearState.CONNECTION_DROPPED),
            )
            // The reconnect's get_session shows the truth.
            h.socket.emitted.clear()
            h.reconnect()
            assertThat(h.socket.emittedNames.first()).isEqualTo("get_session")
        }

    @Test
    fun stoppingOrSwitchingHostsFailsAPendingClear() =
        runSessionTest { h ->
            h.repository.clearSession(PiFixtures.SAM_PROFILE_ID)

            h.repository.stop()

            assertThat(h.clear).isInstanceOf(ClearState.Failed::class)
        }

    @Test
    fun aClearWithoutAConfirmationGivesUpAfterTenSeconds() =
        runSessionTest { h ->
            h.repository.clearSession(PiFixtures.SAM_PROFILE_ID)

            advanceTimeBy(ClearState.TIMEOUT_MILLIS - 1)
            runCurrent()
            assertThat(h.clear).isEqualTo(ClearState.Pending(PiFixtures.SAM_PROFILE_ID))

            advanceTimeBy(1)
            runCurrent()
            assertThat(h.clear).isEqualTo(ClearState.Failed(PiFixtures.SAM_PROFILE_ID, ClearState.NO_CONFIRMATION))
        }

    @Test
    fun aConfirmationAfterGivingUpIsStillApplied() =
        runSessionTest { h ->
            h.repository.clearSession(PiFixtures.SAM_PROFILE_ID)
            advanceTimeBy(ClearState.TIMEOUT_MILLIS)
            runCurrent()

            h.socket.serverFrame(PiFixtures.SESSION_CLEARED_FRAME)

            assertThat(h.sessionTimestamps()).containsExactly(PiFixtures.SHOT_TIMESTAMP)
            assertThat(h.clear).isEqualTo(ClearState.Cleared(PiFixtures.SAM_PROFILE_ID))
        }

    @Test
    fun aConfirmedClearIsNotFailedByTheOldTimeout() =
        runSessionTest { h ->
            h.repository.clearSession(PiFixtures.SAM_PROFILE_ID)
            h.socket.serverFrame(PiFixtures.SESSION_CLEARED_FRAME)

            advanceTimeBy(ClearState.TIMEOUT_MILLIS + 1)
            runCurrent()

            assertThat(h.clear).isEqualTo(ClearState.Cleared(PiFixtures.SAM_PROFILE_ID))
        }

    // endregion
}
