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
import androidx.navigation.toRoute
import dev.openflight.companion.feature.calibration.CalibrationRoute
import dev.openflight.companion.feature.camera.CameraRoute
import dev.openflight.companion.feature.dashboard.DashboardNavigation
import dev.openflight.companion.feature.dashboard.DashboardRoute
import dev.openflight.companion.feature.range.DrivingRangeRoute
import dev.openflight.companion.feature.session.SessionHistoryDetailRoute
import dev.openflight.companion.feature.session.SessionHistoryRoute
import dev.openflight.companion.feature.session.SessionRoute
import dev.openflight.companion.feature.settings.SettingsRoute
import dev.openflight.companion.feature.training.TrainingRoute
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

/** Session stats, shot list, delete/clear and CSV export (R5b/R6c). */
@Serializable
data object Session

/** Stored sessions, newest first (R8h). */
@Serializable
data object SessionHistory

/** One stored session's stats, shots and CSV export (R8h). */
@Serializable
data class SessionHistoryDetail(
    val sessionId: String,
)

/** Swing-speed training (R6c, Wi-Fi only). */
@Serializable
data object Training

/** The Pi's camera feed and ball detection (R6c, Wi-Fi only). */
@Serializable
data object Camera

/** Units, connection info and the Pi's Wi-Fi-only controls (R5b/R6c). */
@Serializable
data object Settings

/**
 * The Android app's navigation graph (Jetpack `navigation-compose`). Each feature destination is
 * one `composable<Route>` line hosting that feature's `:ui` module route. The dashboard's bottom bar
 * reaches Session, Training, Camera and Settings (R5b/R6c); Range and Calibrate stay where they were.
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
                navigation =
                    DashboardNavigation(
                        onOpenSession = { navController.navigate(Session) },
                        onOpenTraining = { navController.navigate(Training) },
                        onOpenCamera = { navController.navigate(Camera) },
                        onOpenSettings = { navController.navigate(Settings) },
                    ),
                transportPermissionRequest = {
                    transport,
                    onResult,
                    ->
                    TransportPermissionRequest(transport, onResult)
                },
            )
        }
        composable<Calibration> { CalibrationRoute(onBack = onBack) }
        composable<Range> { DrivingRangeRoute(onExit = onBack, autoplay = launchOptions.previewFlight) }
        composable<Session> {
            SessionRoute(
                onBack = onBack,
                onShareCsv = rememberCsvSharer(),
                onOpenHistory = { navController.navigate(SessionHistory) },
            )
        }
        composable<SessionHistory> {
            SessionHistoryRoute(
                onBack = onBack,
                onOpenSession = { navController.navigate(SessionHistoryDetail(it)) },
            )
        }
        composable<SessionHistoryDetail> { entry ->
            SessionHistoryDetailRoute(
                sessionId = entry.toRoute<SessionHistoryDetail>().sessionId,
                onBack = onBack,
                onShareCsv = rememberCsvSharer(),
            )
        }
        composable<Training> { TrainingRoute(onBack = onBack) }
        composable<Camera> { CameraRoute(onBack = onBack) }
        composable<Settings> { SettingsRoute(onBack = onBack) }
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
