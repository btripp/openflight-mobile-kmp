// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.openflight.companion.core.designsystem.OfAdaptiveScaffold
import dev.openflight.companion.core.designsystem.OfIcons
import dev.openflight.companion.core.designsystem.OfNavigationItem
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.designsystem.rememberOfWindowClass
import dev.openflight.companion.feature.calibration.CalibrationRoute
import dev.openflight.companion.feature.camera.CameraRoute
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
 * The top-level destinations, in bar/rail order (plan F1a): the dashboard plus the four screens its
 * bottom bar used to reach (plans R5b/R6c). They show the app's navigation (a bottom bar on phones,
 * a rail on tablets). Every other route (Range, Calibration, the session history) is pushed
 * full-screen over them and hides it.
 */
internal enum class TopLevelDestination(
    val label: String,
    val icon: ImageVector,
    val route: Any,
    val testTag: String,
) {
    HOME("Home", OfIcons.Home, Dashboard, AppNavTags.HOME),
    SESSION("Session", OfIcons.Session, Session, AppNavTags.SESSION),
    TRAINING("Training", OfIcons.Training, Training, AppNavTags.TRAINING),
    CAMERA("Camera", OfIcons.Camera, Camera, AppNavTags.CAMERA),
    SETTINGS("Settings", OfIcons.Settings, Settings, AppNavTags.SETTINGS),
    ;

    companion object {
        /** The top-level destination [destination] is, or null for a pushed route. */
        fun of(destination: NavDestination): TopLevelDestination? =
            entries.firstOrNull { top -> destination.hierarchy.any { it.hasRoute(top.route::class) } }
    }
}

/** Test tags for the app's top-level navigation entries (bottom bar or rail alike). */
object AppNavTags {
    const val HOME = "app.nav.home"
    const val SESSION = "app.nav.session"
    const val TRAINING = "app.nav.training"
    const val CAMERA = "app.nav.camera"
    const val SETTINGS = "app.nav.settings"
}

/**
 * The Android app's navigation graph (Jetpack `navigation-compose`) inside the adaptive shell
 * ([OfAdaptiveScaffold], plan F1a): a bottom bar on phones and a navigation rail on tablets reach
 * the [TopLevelDestination]s. Each feature destination is one `composable<Route>` line hosting that
 * feature's `:ui` module route. Range and Calibrate stay on the dashboard, and each screen's
 * Done/back still returns to the dashboard.
 *
 * @param windowClass injectable so device tests can force the phone or the tablet layout.
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    windowClass: OfWindowClass = rememberOfWindowClass(),
) {
    val koin = getKoin()
    val launchOptions = remember(koin) { koin.launchOptions() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    // Before the graph's first entry exists, the start destination (the dashboard) is on its way.
    val destination = backStackEntry?.destination
    val current = if (destination == null) TopLevelDestination.HOME else TopLevelDestination.of(destination)
    OfAdaptiveScaffold(
        windowClass = windowClass,
        showNavigation = current != null,
        items =
            TopLevelDestination.entries.map { top ->
                OfNavigationItem(
                    label = top.label,
                    icon = top.icon,
                    selected = top == current,
                    onClick = { navController.navigateToTopLevel(top) },
                    testTag = top.testTag,
                )
            },
    ) {
        AppGraph(navController, launchOptions)
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

/**
 * Switches top-level destination the usual Material way: the dashboard stays at the root, at most
 * one other top-level screen sits on it (so its Done/back returns to the dashboard), and a screen
 * left through the bar or rail keeps its state for when it's picked again.
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    if (destination == TopLevelDestination.HOME) {
        popBackStack<Dashboard>(inclusive = false)
        return
    }
    navigate(destination.route) {
        popUpTo<Dashboard> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppGraph(
    navController: NavHostController,
    launchOptions: LaunchOptions,
) {
    val onBack: () -> Unit = { navController.popBackStack() }
    NavHost(navController = navController, startDestination = Dashboard) {
        composable<Dashboard> {
            DashboardRoute(
                onOpenCalibration = { navController.navigate(Calibration) },
                onOpenRange = { navController.navigate(Range) },
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
}
