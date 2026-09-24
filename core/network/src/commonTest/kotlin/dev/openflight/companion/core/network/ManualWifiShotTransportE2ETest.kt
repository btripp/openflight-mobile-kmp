// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Manual Step 4 E2E oracle (plan §0.4, §0.3a, and Step 4's "Manual E2E" section). This is
 * **not** part of CI and must stay `@Ignore`d.
 *
 * To run it: start `uv run openflight-server --mock --web-port <port>` from the reference
 * clone, edit [MANUAL_E2E_HOST] below to `localhost:<port>`, remove `@Ignore`, then:
 * ```
 * ./gradlew :core:network:testAndroidHostTest --tests "*ManualWifiShotTransportE2ETest*"
 * ```
 * While it runs, fire shots with `tools/fire-mock-shot.py --url http://localhost:<port>` and,
 * separately, kill and restart the server process to exercise the auto-reconnect path (plan
 * §0.3: an automatic reconnect must not re-emit a replayed shot; only an explicit `retry()`
 * does). Watch stdout for `[manual-e2e]` lines. Restore `@Ignore` afterwards.
 */
class ManualWifiShotTransportE2ETest {
    @Ignore
    @Test
    fun listensAndPrintsLiveShotsForManualVerification() =
        runBlocking {
            val transport = WifiShotTransport(MANUAL_E2E_HOST, openFlightHttpClient())
            transport.state
                .onEach { println("[manual-e2e] state=$it") }
                .launchIn(this)
            transport.shots
                .onEach {
                    println("[manual-e2e] shot event_id=${it.eventId} ball_speed_mph=${it.ballSpeedMph}")
                }.launchIn(this)

            transport.start()
            delay(MANUAL_E2E_DURATION_MS)
            transport.disconnect()
        }

    private companion object {
        const val MANUAL_E2E_HOST = "localhost:8091"
        const val MANUAL_E2E_DURATION_MS = 60_000L
    }
}
