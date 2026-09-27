// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfDropdownMenu
import dev.openflight.companion.core.designsystem.OfSegmentedPicker
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfWindowClass

/**
 * Draws the range's shot trail preview for the picker's current choice over [RangeThemeSetting]'s
 * look. `androidApp` supplies it from `feature:range:ui` (a feature never depends on another).
 */
typealias ShotTrailPreview = @Composable (trail: ShotTrailUiState, theme: RangeThemeSetting) -> Unit

/**
 * Plan F8a2t: the Practice group's "Shot trail" card: the style, "Keep last shots" and the landing
 * effect, with a live [preview] drawn by the range's own renderer when one is given. On an
 * expanded window the preview sits beside the pickers.
 */
@Composable
internal fun ShotTrailCard(
    trail: ShotTrailUiState,
    theme: RangeThemeSetting,
    onEvent: (SettingsEvent) -> Unit,
    windowClass: OfWindowClass,
    preview: ShotTrailPreview?,
) {
    OfCard(
        modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.SHOT_TRAIL_CARD),
        contentSpacing = OfSpacing.Md,
    ) {
        SectionTitle("SHOT TRAIL")
        if (windowClass == OfWindowClass.EXPANDED && preview != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Lg)) {
                PreviewBox(trail, theme, preview, Modifier.weight(1f).height(EXPANDED_PREVIEW_HEIGHT.dp))
                ShotTrailPickers(trail, onEvent, Modifier.weight(1f))
            }
        } else {
            if (preview != null) PreviewBox(trail, theme, preview, Modifier.fillMaxWidth().height(PREVIEW_HEIGHT.dp))
            ShotTrailPickers(trail, onEvent, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PreviewBox(
    trail: ShotTrailUiState,
    theme: RangeThemeSetting,
    preview: ShotTrailPreview,
    modifier: Modifier,
) {
    Box(modifier = modifier.testTag(SettingsTestTags.SHOT_TRAIL_PREVIEW)) { preview(trail, theme) }
}

@Composable
private fun ShotTrailPickers(
    trail: ShotTrailUiState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(OfSpacing.Md)) {
        OfDropdownMenu(
            label = "Style",
            selected = trail.selectedLabel,
            options = trail.styles.map { it.label },
            onSelect = { label ->
                val picked = trail.styles.first { it.label == label }.style
                if (picked != trail.selected) onEvent(SettingsEvent.SetShotTrail(picked))
            },
            modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.SHOT_TRAIL),
        )
        OfText(text = "Keep last shots", role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
        OfSegmentedPicker(
            options = trail.keepOptions.map { it.label },
            selected = trail.keepLastLabel,
            onSelect = { label ->
                val picked = trail.keepOptions.first { it.label == label }.count
                if (picked != trail.keepLast) onEvent(SettingsEvent.SetShotTrailKeepLast(picked))
            },
            modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.SHOT_TRAIL_KEEP),
        )
        OfText(text = "Landing effect", role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
        OfSegmentedPicker(
            options = trail.landingEffects.map { it.label },
            selected = trail.landingEffectLabel,
            onSelect = { label ->
                val picked = trail.landingEffects.first { it.label == label }.effect
                if (picked != trail.landingEffect) onEvent(SettingsEvent.SetLandingEffect(picked))
            },
            modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.LANDING_EFFECT),
        )
    }
}

private const val PREVIEW_HEIGHT = 170
private const val EXPANDED_PREVIEW_HEIGHT = 220
