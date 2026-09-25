// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuite
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview

/**
 * One top-level destination in an [OfAdaptiveScaffold]'s bottom bar or rail.
 *
 * @param testTag set on the entry so tests can tap it whichever navigation component shows it.
 */
data class OfNavigationItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val onClick: () -> Unit,
    val testTag: String,
)

/** Test tags for the navigation component an [OfAdaptiveScaffold] shows. */
object OfAdaptiveScaffoldTags {
    const val BOTTOM_BAR = "of.nav.bottomBar"
    const val RAIL = "of.nav.rail"
}

/** Which navigation component an [OfAdaptiveScaffold] shows. */
enum class OfNavigationLayout {
    BOTTOM_BAR,
    RAIL,
    NONE,
    ;

    companion object {
        /**
         * A bottom bar on a compact window, a rail on medium and expanded ones, and nothing when
         * the destination is full-screen (`showNavigation = false`).
         */
        fun of(
            windowClass: OfWindowClass,
            showNavigation: Boolean,
        ): OfNavigationLayout =
            when {
                !showNavigation -> NONE
                windowClass == OfWindowClass.COMPACT -> BOTTOM_BAR
                else -> RAIL
            }
    }
}

/**
 * The app's top-level navigation shell (Material 3 navigation suite): a bottom bar on phones and a
 * navigation rail on tablets, around [content] (typically the nav host). Each screen inside keeps
 * its own [OfScaffold]; the system bar insets the bar or rail pads itself for are consumed, so the
 * screens don't pad for them twice.
 *
 * @param showNavigation false hides the bar or rail, for full-screen or pushed destinations. The
 *   [content] keeps its place in the composition either way, so toggling it keeps the nav host's
 *   state.
 * @param windowClass injectable so tests and previews can force a layout; defaults to
 *   [rememberOfWindowClass].
 */
@Composable
fun OfAdaptiveScaffold(
    items: List<OfNavigationItem>,
    modifier: Modifier = Modifier,
    showNavigation: Boolean = true,
    windowClass: OfWindowClass = rememberOfWindowClass(),
    content: @Composable () -> Unit,
) {
    val layout = OfNavigationLayout.of(windowClass, showNavigation)
    val suiteType =
        when (layout) {
            OfNavigationLayout.BOTTOM_BAR -> NavigationSuiteType.ShortNavigationBarCompact
            OfNavigationLayout.RAIL -> NavigationSuiteType.WideNavigationRailCollapsed
            OfNavigationLayout.NONE -> NavigationSuiteType.None
        }
    val consumedInsets =
        when (layout) {
            OfNavigationLayout.BOTTOM_BAR -> ShortNavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)
            OfNavigationLayout.RAIL -> WideNavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)
            OfNavigationLayout.NONE -> WindowInsets(0, 0, 0, 0)
        }
    Box(modifier = modifier.fillMaxSize().background(OfColorTokens.BgDeep)) {
        NavigationSuiteScaffoldLayout(
            navigationSuiteType = suiteType,
            navigationSuite = {
                NavigationSuite(
                    navigationSuiteType = suiteType,
                    modifier =
                        Modifier.testTag(
                            if (layout ==
                                OfNavigationLayout.RAIL
                            ) {
                                OfAdaptiveScaffoldTags.RAIL
                            } else {
                                OfAdaptiveScaffoldTags.BOTTOM_BAR
                            },
                        ),
                    colors =
                        NavigationSuiteDefaults.colors(
                            shortNavigationBarContainerColor = OfColorTokens.BgCard,
                            shortNavigationBarContentColor = OfColorTokens.Cream,
                            wideNavigationRailColors =
                                WideNavigationRailDefaults.colors(
                                    containerColor = OfColorTokens.BgCard,
                                    contentColor = OfColorTokens.Cream,
                                ),
                        ),
                ) {
                    items.forEach { item ->
                        NavigationSuiteItem(
                            selected = item.selected,
                            onClick = item.onClick,
                            // Tinted by the item (LocalContentColor), so the selected entry stands out.
                            icon = { Icon(imageVector = item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                            navigationSuiteType = suiteType,
                            modifier = Modifier.testTag(item.testTag),
                        )
                    }
                }
            },
            content = {
                Box(Modifier.fillMaxSize().consumeWindowInsets(consumedInsets)) { content() }
            },
        )
    }
}

@Composable
private fun PreviewShell(windowClass: OfWindowClass) {
    OfTheme {
        OfAdaptiveScaffold(
            windowClass = windowClass,
            items =
                listOf(
                    OfNavigationItem("Home", OfIcons.Home, selected = true, onClick = {}, testTag = "home"),
                    OfNavigationItem("Session", OfIcons.Session, selected = false, onClick = {}, testTag = "session"),
                    OfNavigationItem(
                        "Training",
                        OfIcons.Training,
                        selected = false,
                        onClick = {},
                        testTag = "training",
                    ),
                    OfNavigationItem("Camera", OfIcons.Camera, selected = false, onClick = {}, testTag = "camera"),
                    OfNavigationItem(
                        "Settings",
                        OfIcons.Settings,
                        selected = false,
                        onClick = {},
                        testTag = "settings",
                    ),
                ),
        ) {
            OfScaffold(topBar = { OfTopBar(title = "Launch Monitor", eyebrow = "OPENFLIGHT") }) { padding ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OfText(text = "Content", modifier = Modifier.consumeWindowInsets(padding))
                }
            }
        }
    }
}

@Preview(widthDp = 400, heightDp = 800)
@Composable
private fun OfAdaptiveScaffoldCompactPreview() = PreviewShell(OfWindowClass.COMPACT)

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun OfAdaptiveScaffoldExpandedPreview() = PreviewShell(OfWindowClass.EXPANDED)
