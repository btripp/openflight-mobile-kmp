// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.designsystem.OfTheme
import org.koin.compose.koinInject

/**
 * The Android app shell: theme, the shot stream's lifecycle and the nav host. Koin must already be
 * started (`OpenFlightApplication`).
 *
 * Like the reference, streaming is foreground-only: the repository starts when the app comes to
 * the foreground and stops (disconnecting the transport) when it goes to the background. It runs
 * for every screen, not just the dashboard, so the range and calibration see live data too.
 */
@Composable
fun OpenFlightApp() {
    OfTheme {
        val repository = koinInject<ShotRepository>()
        LifecycleStartEffect(repository) {
            repository.start()
            onStopOrDispose { repository.stop() }
        }
        AppNavHost()
    }
}
