// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.openflight.companion.feature.dashboard.DashboardRoute
import kotlinx.serialization.Serializable

/** The dashboard: the start destination. */
@Serializable
data object Dashboard

/** Phone-assisted TI radar tilt calibration (Step 8c). */
@Serializable
data object Calibration

/** The driving-range ball-flight view (Step 9). */
@Serializable
data object Range

/**
 * The app's navigation graph. Each feature destination is one `composable<Route>` line, so the
 * calibration and range steps each replace only their own placeholder line.
 */
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val onBack: () -> Unit = { navController.popBackStack() }
    NavHost(navController = navController, startDestination = Dashboard) {
        composable<Dashboard> {
            DashboardRoute(
                onOpenCalibration = { navController.navigate(Calibration) },
                onOpenRange = { navController.navigate(Range) },
                transportPermissionRequest = {
                    transport,
                    onGranted,
                    ->
                    TransportPermissionRequest(transport, onGranted)
                },
            )
        }
        composable<Calibration> { PlaceholderScreen(title = "Calibrate TI Radar", onBack = onBack) }
        composable<Range> { PlaceholderScreen(title = "Driving Range", onBack = onBack) }
    }
}
