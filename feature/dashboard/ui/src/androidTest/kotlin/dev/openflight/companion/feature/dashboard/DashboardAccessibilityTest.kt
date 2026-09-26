// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.model.ConnectionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R8f leftover, fixed in plan F1b: the monospace Wi-Fi host field ("raspberrypi.local:8080")
 * clipped its own text at a 200 % system font scale, because `OfTextField` left the monospace
 * style's line height at the value tuned for the proportional body font. See `OfTextField.kt`.
 */
@RunWith(AndroidJUnit4::class)
class DashboardAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OfTheme {
                    DashboardScreen(
                        uiState =
                            DashboardUiState.Waiting(
                                ConnectionPanelState(transport = TransportType.WIFI, state = ConnectionState.Idle),
                            ),
                        onEvent = {},
                        onOpenCalibration = {},
                        onOpenRange = {},
                    )
                }
            }
        }
    }

    @Test
    fun given200PercentText_whenShown_thenTheHostFieldIsNotClipped() {
        show(fontScale = 2f)

        val text = SettingsRepository.DEFAULT_HOST
        val node = composeRule.onAllNodes(hasText(text), useUnmergedTree = true)[0]
        node.performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        node
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        assertTrue(layouts.isNotEmpty(), "\"$text\" has no text layout")
        for (layout in layouts) {
            assertFalse(layout.didOverflowHeight, "\"$text\" is cut off at 200 % text")
        }
    }
}
