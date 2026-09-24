// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.openflight.companion.feature.calibration.CalibrationRoute
import dev.openflight.companion.feature.dashboard.DashboardRoute
import dev.openflight.companion.feature.range.DrivingRangeRoute
import kotlinx.serialization.Serializable
import org.koin.compose.getKoin

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
    val koin = getKoin()
    val launchOptions = remember(koin) { koin.launchOptions() }
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
        composable<Calibration> { CalibrationRoute(onBack = onBack) }
        composable<Range> { DrivingRangeRoute(onExit = onBack, autoplay = launchOptions.previewFlight) }
    }
    // `--range-mode`: open the range over the dashboard once, not again after a configuration change.
    var rangeModeHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(launchOptions.rangeMode) {
        if (launchOptions.rangeMode && !rangeModeHandled) {
            rangeModeHandled = true
            navController.navigate(Range)
        }
    }
}
