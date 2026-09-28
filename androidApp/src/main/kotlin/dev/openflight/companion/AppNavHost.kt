// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.designsystem.OfAdaptiveScaffold
import dev.openflight.companion.core.designsystem.OfIcons
import dev.openflight.companion.core.designsystem.OfNavigationItem
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.designsystem.rememberOfWindowClass
import dev.openflight.companion.feature.bag.BagRoute
import dev.openflight.companion.feature.bag.ClubAnalysisRoute
import dev.openflight.companion.feature.bag.ClubDetailRoute
import dev.openflight.companion.feature.calibration.CalibrationRoute
import dev.openflight.companion.feature.camera.CameraRoute
import dev.openflight.companion.feature.dashboard.DashboardRoute
import dev.openflight.companion.feature.range.DrivingRangeRoute
import dev.openflight.companion.feature.range.ShotTrailPreviewCanvas
import dev.openflight.companion.feature.session.SessionHistoryDetailRoute
import dev.openflight.companion.feature.session.SessionHistoryRoute
import dev.openflight.companion.feature.session.SessionRoute
import dev.openflight.companion.feature.settings.SettingsRoute
import dev.openflight.companion.feature.training.TrainingRoute
import kotlinx.serialization.Serializable
import org.koin.compose.getKoin

/** The dashboard: the start destination, shown as Practice (plan F1d). */
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

/** Swing-speed training (R6c, Wi-Fi only), pushed from Practice's overflow menu (plan F1d). */
@Serializable
data object Training

/** The Pi's camera feed and ball detection (R6c, Wi-Fi only), pushed from Settings (plan F1d). */
@Serializable
data object Camera

/** Units, connection info and the Pi's Wi-Fi-only controls (R5b/R6c). */
@Serializable
data object Settings

/** My Bag: the active bag, its gapping and the conditions card (plan F5). */
@Serializable
data object Bag

/** Club Analysis: the bag's clubs ranked, with the gapping insights (plan F5). */
@Serializable
data object BagAnalysis

/** One club's distances, pushed from Club Analysis (plan F5). */
@Serializable
data class BagClubDetail(
    val wireValue: String,
)

/**
 * The top-level destinations, in bar/rail order: plan F1d's Practice · Sessions · Bag · Settings,
 * the same labels and order as iOS's `AppTab` on every form factor. F9b/F9c insert Play at index 1.
 * They show the app's navigation (a bottom bar on phones, a rail on tablets, plan F1a). Every other
 * route (Range, Calibration, Training, Camera, the session history) is pushed full-screen over them
 * and hides it.
 */
internal enum class TopLevelDestination(
    val label: String,
    val icon: ImageVector,
    val route: Any,
    val testTag: String,
) {
    PRACTICE("Practice", OfIcons.Gauge, Dashboard, AppNavTags.PRACTICE),
    SESSIONS("Sessions", OfIcons.Session, Session, AppNavTags.SESSIONS),
    BAG("Bag", OfIcons.Bag, Bag, AppNavTags.BAG),
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
    const val PRACTICE = "app.nav.practice"
    const val SESSIONS = "app.nav.sessions"
    const val BAG = "app.nav.bag"
    const val SETTINGS = "app.nav.settings"
}

/**
 * The Android app's navigation graph (Jetpack `navigation-compose`) inside the adaptive shell
 * ([OfAdaptiveScaffold], plan F1a): a bottom bar on phones and a navigation rail on tablets reach
 * the [TopLevelDestination]s. Each feature destination is one `composable<Route>` line hosting that
 * feature's `:ui` module route. Plan F1d: Range, Calibrate and Speed training are pushed from
 * Practice, Calibrate and Camera from Settings; each pushed screen's Done/back returns to where it
 * was opened from.
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
    val current = if (destination == null) TopLevelDestination.PRACTICE else TopLevelDestination.of(destination)
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
 * Switches top-level destination the usual Material way: the dashboard (Practice) stays at the
 * root, at most one other top-level screen sits on it (so system back returns to Practice), and a
 * screen left through the bar or rail keeps its state for when it's picked again.
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    if (destination == TopLevelDestination.PRACTICE) {
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
    // Plan F8d: "View on range" opens RangeReplay on the shot, in the live session's history session.
    val koin = getKoin()
    val history = remember(koin) { koin.get<ShotHistoryRepository>() }
    val viewLiveShotOnRange: (String) -> Unit = { shotId ->
        navController.navigate(RangeReplay(history.currentSessionId.value.orEmpty(), shotId))
    }
    val viewStoredShotOnRange: (String, String) -> Unit = { sessionId, shotId ->
        navController.navigate(RangeReplay(sessionId, shotId))
    }
    NavHost(navController = navController, startDestination = Dashboard) {
        composable<Dashboard> {
            DashboardRoute(
                onOpenCalibration = { navController.navigate(Calibration) },
                onOpenRange = { navController.navigate(Range) },
                onOpenTraining = { navController.navigate(Training) },
                transportPermissionRequest = {
                    transport,
                    onResult,
                    ->
                    TransportPermissionRequest(transport, onResult)
                },
                onViewOnRange = viewLiveShotOnRange,
            )
        }
        composable<Calibration> { CalibrationRoute(onBack = onBack) }
        composable<Range> {
            DrivingRangeRoute(
                onExit = onBack,
                autoplay = launchOptions.previewFlight,
                freezeProgress = launchOptions.rangeFreezeProgress,
            )
        }
        composable<Session> {
            SessionRoute(
                onBack = onBack,
                onShareCsv = rememberCsvSharer(),
                onOpenHistory = { navController.navigate(SessionHistory) },
                onViewOnRange = viewLiveShotOnRange,
            )
        }
        composable<SessionHistory> {
            SessionHistoryRoute(
                onBack = onBack,
                onOpenSession = { navController.navigate(SessionHistoryDetail(it)) },
                onShareCsv = rememberCsvSharer(),
                onReplayOnRange = { navController.navigate(RangeReplay(it)) },
                onViewOnRange = viewStoredShotOnRange,
            )
        }
        composable<SessionHistoryDetail> { entry ->
            SessionHistoryDetailRoute(
                sessionId = entry.toRoute<SessionHistoryDetail>().sessionId,
                onBack = onBack,
                onShareCsv = rememberCsvSharer(),
                onReplayOnRange = {
                    navController.navigate(
                        RangeReplay(entry.toRoute<SessionHistoryDetail>().sessionId),
                    )
                },
                onViewOnRange = { viewStoredShotOnRange(entry.toRoute<SessionHistoryDetail>().sessionId, it) },
            )
        }
        composable<Training> { TrainingRoute(onBack = onBack) }
        composable<Camera> { CameraRoute(onBack = onBack) }
        composable<Settings> {
            SettingsRoute(
                onBack = onBack,
                onOpenCalibration = { navController.navigate(Calibration) },
                onOpenCamera = { navController.navigate(Camera) },
                // Plan F8a2t: the shot trail preview, drawn by the range's own renderer.
                shotTrailPreview = { trail, theme ->
                    ShotTrailPreviewCanvas(
                        trail.selected,
                        trail.keepLast,
                        trail.landingEffect,
                        theme,
                        Modifier.fillMaxSize(),
                    )
                },
            )
        }
        composable<Bag> {
            BagRoute(onOpenAnalysis = { navController.navigate(BagAnalysis) }, onViewOnRange = viewStoredShotOnRange)
        }
        composable<BagAnalysis> {
            ClubAnalysisRoute(onBack = onBack, onOpenClub = { navController.navigate(BagClubDetail(it)) })
        }
        composable<BagClubDetail> { entry ->
            ClubDetailRoute(
                wireValue = entry.toRoute<BagClubDetail>().wireValue,
                onBack = onBack,
                onViewOnRange = viewStoredShotOnRange,
            )
        }
        composable<RangeReplay> { entry ->
            val route = entry.toRoute<RangeReplay>()
            DrivingRangeRoute(onExit = onBack, replaySessionId = route.sessionId, replayShotId = route.shotId)
        }
    }
}

/**
 * Plan F8a1: the driving range replaying stored session [sessionId] (Session history detail). Plan
 * F8d: with a [shotId] ("View on range") it opens paused on that shot instead.
 */
@Serializable
data class RangeReplay(
    val sessionId: String,
    val shotId: String? = null,
)
