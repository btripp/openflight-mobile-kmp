// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dev.openflight.companion.core.data.AppLifecycle
import dev.openflight.companion.core.data.LifecycleConnectionPolicy
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.designsystem.rememberOfWindowClass
import org.koin.compose.koinInject

/**
 * The Android app shell: theme, the shared connection policy's lifecycle source and the nav host.
 * Koin must already be started (`OpenFlightApplication`).
 *
 * Streaming is foreground-only for every screen, not just the dashboard (plan R8d): the shared
 * [LifecycleConnectionPolicy] connects on [AppLifecycle.onForeground] and disconnects on
 * [AppLifecycle.onBackground]. This feeds it from [ProcessLifecycleOwner] (`ON_START`/`ON_STOP`),
 * which ignores rotations and brief activity switches, using this composition's Koin graph (the one
 * the debug launch options and instrumented tests set up).
 *
 * @param windowClass the phone or tablet layout (plan F1a); tests force one, the app follows the window.
 */
@Composable
fun OpenFlightApp(windowClass: OfWindowClass = rememberOfWindowClass()) {
    OfTheme {
        AppLifecycleSource()
        AppNavHost(windowClass = windowClass)
    }
}

@Composable
private fun AppLifecycleSource() {
    val lifecycle = koinInject<AppLifecycle>()
    val policy = koinInject<LifecycleConnectionPolicy>()
    // Plan F7: resolved and started here, not from OpenFlightApplication.onCreate(), for the same
    // reason `policy` is: this composition's Koin graph is the one a debug launch hook or an
    // instrumented test may have replaced (see OpenFlightApplication's note).
    val shotCallouts = koinInject<ShotCalloutCoordinator>()
    val activity = LocalActivity.current
    DisposableEffect(lifecycle, policy, shotCallouts) {
        policy.start()
        shotCallouts.start()
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> lifecycle.onForeground()
                    Lifecycle.Event.ON_STOP -> lifecycle.onBackground()
                    else -> Unit
                }
            }
        // Delivers the current state at once: ON_START when the process is already visible.
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        processLifecycle.addObserver(observer)
        onDispose {
            processLifecycle.removeObserver(observer)
            // A rotation keeps the connection; leaving the app (back, finish) ends it now rather
            // than when the process eventually stops.
            if (activity?.isChangingConfigurations != true) lifecycle.onBackground()
        }
    }
}
