// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.socketio.SocketConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Plan R8d's shared lifecycle policy: every transport disconnects in the background and reconnects
 * in the foreground, and anything still waiting for the Pi fails with "connection dropped". A fake
 * lifecycle source drives real repositories over fake transports and sockets.
 */
class AppLifecycleTest {
    private class Harness(
        scope: TestScope,
    ) {
        val lifecycle = MutableStateFlow(AppLifecycleState.BACKGROUND)
        val events = mutableListOf<String>()
        val settings = FakeSettingsRepository(transport = TransportType.WIFI, host = "pi.local:8080")
        val sockets = mutableListOf<FakePiSocket>()
        val piSession =
            DefaultPiSessionRepository(
                settings = settings,
                socketFactory = { host, _ -> FakePiSocket(host).also { sockets += it } },
                cameraSource = FakePiCameraSource(),
                scope = scope.backgroundScope,
            )
        val shots =
            DefaultShotRepository(
                settings = settings,
                bluetoothTransport = FakeShotTransport("ble", events),
                wifiTransportFactory = { host -> FakeShotTransport("wifi($host)", events) },
                scope = scope.backgroundScope,
                piControl = fakePiControlClient(),
                piSession = piSession,
            )
        val policy = LifecycleConnectionPolicy(lifecycle, shots, scope.backgroundScope)

        val socket: FakePiSocket get() = sockets.last()
    }

    private fun runLifecycleTest(body: suspend TestScope.(Harness) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val harness = Harness(this)
            harness.policy.start()
            body(harness)
        }

    @Test
    fun theLifecycleStartsInTheBackgroundAndReportsEachChange() =
        runTest {
            val lifecycle = AppLifecycle()
            lifecycle.state.test {
                assertThat(awaitItem()).isEqualTo(AppLifecycleState.BACKGROUND)
                lifecycle.onForeground()
                assertThat(awaitItem()).isEqualTo(AppLifecycleState.FOREGROUND)
                lifecycle.onBackground()
                assertThat(awaitItem()).isEqualTo(AppLifecycleState.BACKGROUND)
            }
        }

    @Test
    fun nothingConnectsUntilTheAppIsInTheForeground() =
        runLifecycleTest { h ->
            assertThat(h.events).isEqualTo(emptyList<String>())
            assertThat(h.sockets).isEqualTo(emptyList<FakePiSocket>())

            h.lifecycle.value = AppLifecycleState.FOREGROUND

            assertThat(h.events).containsExactly("wifi(pi.local:8080).start")
            assertThat(h.socket.connectCount).isEqualTo(1)
        }

    @Test
    fun backgroundDisconnectsEveryTransportAndForegroundReconnects() =
        runLifecycleTest { h ->
            h.lifecycle.value = AppLifecycleState.FOREGROUND
            h.socket.serverAcks()

            h.piSession.linkState.test {
                assertThat(awaitItem()).isEqualTo(PiLinkState.Connected)
                h.lifecycle.value = AppLifecycleState.BACKGROUND
                assertThat(awaitItem()).isEqualTo(PiLinkState.Idle)
            }
            assertThat(h.shots.connectionState.value).isEqualTo(ConnectionState.Idle)
            assertThat(h.events).containsExactly("wifi(pi.local:8080).start", "wifi(pi.local:8080).disconnect")
            assertThat(h.sockets.single().disconnectCount).isEqualTo(1)

            h.lifecycle.value = AppLifecycleState.FOREGROUND

            assertThat(h.events.last()).isEqualTo("wifi(pi.local:8080).start")
            assertThat(h.sockets.map { it.host }).containsExactly("pi.local:8080", "pi.local:8080")
            assertThat(h.socket.connectCount).isEqualTo(1)
        }

    @Test
    fun aRepeatedStateChangesNothing() =
        runLifecycleTest { h ->
            h.lifecycle.value = AppLifecycleState.FOREGROUND
            h.lifecycle.value = AppLifecycleState.FOREGROUND
            h.policy.start()

            assertThat(h.events).containsExactly("wifi(pi.local:8080).start")
            assertThat(h.sockets.size).isEqualTo(1)
        }

    @Test
    fun backgroundFailsAPendingDeleteAsConnectionDropped() =
        runLifecycleTest { h ->
            h.lifecycle.value = AppLifecycleState.FOREGROUND
            h.socket.serverAcks()
            h.socket.serverFrame(PiFixtures.SHOT_FRAME)
            h.piSession.deleteShot(PiFixtures.SHOT_TIMESTAMP)

            h.piSession.deletionState.test {
                assertThat(awaitItem()).isEqualTo(DeletionState.Pending(PiFixtures.SHOT_TIMESTAMP))
                h.lifecycle.value = AppLifecycleState.BACKGROUND
                assertThat(awaitItem()).isEqualTo(
                    DeletionState.Failed(PiFixtures.SHOT_TIMESTAMP, DeletionState.CONNECTION_DROPPED),
                )
            }
        }

    @Test
    fun backgroundFailsAPendingClearAsConnectionDropped() =
        runLifecycleTest { h ->
            h.lifecycle.value = AppLifecycleState.FOREGROUND
            h.socket.serverAcks()
            h.piSession.clearSession(PiFixtures.SAM_PROFILE_ID)

            h.piSession.clearState.test {
                assertThat(awaitItem()).isEqualTo(ClearState.Pending(PiFixtures.SAM_PROFILE_ID))
                h.lifecycle.value = AppLifecycleState.BACKGROUND
                assertThat(awaitItem()).isEqualTo(
                    ClearState.Failed(PiFixtures.SAM_PROFILE_ID, ClearState.CONNECTION_DROPPED),
                )
            }
        }

    @Test
    fun theSessionFlowsSurviveABackgroundRoundTrip() =
        runLifecycleTest { h ->
            h.lifecycle.value = AppLifecycleState.FOREGROUND
            h.socket.serverAcks()
            h.socket.serverFrame(PiFixtures.SHOT_FRAME)

            h.lifecycle.value = AppLifecycleState.BACKGROUND
            h.lifecycle.value = AppLifecycleState.FOREGROUND

            // Same host: nothing is forgotten until the reconnect's snapshot replaces it.
            assertThat(
                h.piSession.sessionShots.value
                    .map { it.timestamp },
            ).containsExactly(PiFixtures.SHOT_TIMESTAMP)
            h.socket.state.value = SocketConnectionState.Connected("sid-2")
            assertThat(h.socket.emittedNames.first()).isEqualTo("get_session")
        }
}
