// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plan F8a2t: the Practice group's "Shot trail" card: style, keep last shots, landing effect, preview. */
@RunWith(AndroidJUnit4::class)
class SettingsShotTrailTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<SettingsEvent>()
    private val previewed = mutableListOf<Pair<ShotTrailUiState, RangeThemeSetting>>()

    private fun show(
        state: SettingsUiState,
        windowClass: OfWindowClass = OfWindowClass.COMPACT,
    ) {
        composeRule.setContent {
            OfTheme {
                SettingsScreen(
                    uiState = state,
                    onEvent = { events += it },
                    onBack = {},
                    windowClass = windowClass,
                    shotTrailPreview = { trail, theme -> previewed += trail to theme },
                )
            }
        }
    }

    @Test
    fun givenClassic_whenCometIsPicked_thenTheShotTrailIsSaved() {
        show(previewSettingsState())

        composeRule
            .onNodeWithTag(SettingsTestTags.SHOT_TRAIL)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        for (label in listOf("Broadcast glow", "Spin ribbon", "Ground track")) {
            composeRule.onNode(hasText(label)).assertExists()
        }
        composeRule.onNodeWithText("Comet").performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.SetShotTrail(ShotTrailStyle.COMET)), events)
    }

    @Test
    fun givenKeepOffAndNoEffect_whenLast3AndBurstArePicked_thenBothAreSaved() {
        show(previewSettingsState())

        composeRule.onNodeWithTag(SettingsTestTags.SHOT_TRAIL_KEEP).performScrollTo()
        inside(SettingsTestTags.SHOT_TRAIL_KEEP, "Off").assertIsSelected()
        inside(SettingsTestTags.SHOT_TRAIL_KEEP, "Last 3").performClick()
        composeRule.onNodeWithTag(SettingsTestTags.LANDING_EFFECT).performScrollTo()
        inside(SettingsTestTags.LANDING_EFFECT, "Burst").performClick()

        assertEquals(
            listOf(SettingsEvent.SetShotTrailKeepLast(3), SettingsEvent.SetLandingEffect(LandingEffect.BURST)),
            events,
        )
    }

    @Test
    fun givenSmokeOnNight_whenShown_thenThePreviewDrawsThatChoice() {
        val trail = ShotTrailUiState(selected = ShotTrailStyle.SMOKE, keepLast = 3, landingEffect = LandingEffect.RING)
        show(previewSettingsState().copy(shotTrail = trail, rangeTheme = RangeThemeUiState(RangeThemeSetting.NIGHT)))

        composeRule.onNodeWithTag(SettingsTestTags.SHOT_TRAIL_PREVIEW).performScrollTo().assertExists()
        composeRule.onNodeWithTag(SettingsTestTags.SHOT_TRAIL).performScrollTo()
        composeRule.onNodeWithText("Smoke").assertIsDisplayed()
        inside(SettingsTestTags.SHOT_TRAIL_KEEP, "Last 3").assertIsSelected()
        inside(SettingsTestTags.LANDING_EFFECT, "Ring").assertIsSelected()
        assertEquals(trail to RangeThemeSetting.NIGHT, previewed.last())
    }

    @Test
    fun givenATablet_whenShown_thenThePreviewSitsBesideThePickers() {
        show(previewSettingsState(), OfWindowClass.EXPANDED)

        composeRule.onNodeWithTag(SettingsTestTags.SHOT_TRAIL_PREVIEW).performScrollTo()
        val preview = composeRule.onNodeWithTag(SettingsTestTags.SHOT_TRAIL_PREVIEW).getUnclippedBoundsInRoot()
        val picker = composeRule.onNodeWithTag(SettingsTestTags.SHOT_TRAIL).getUnclippedBoundsInRoot()
        assertTrue(picker.left >= preview.right, "picker ${picker.left} right of preview ${preview.right}")
    }

    private fun inside(
        tag: String,
        label: String,
    ) = composeRule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)))
}
