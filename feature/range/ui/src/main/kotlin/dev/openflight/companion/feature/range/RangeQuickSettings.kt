// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.RangeShowSetting
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.data.SHOT_TRAIL_KEEP_OPTIONS
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.data.pickerLabel
import dev.openflight.companion.core.data.shotTrailKeepLabel
import dev.openflight.companion.core.data.unitSystemLabel
import dev.openflight.companion.core.designsystem.OfBottomSheet
import dev.openflight.companion.core.designsystem.OfChip
import dev.openflight.companion.core.designsystem.OfClubPalette
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfIconButton
import dev.openflight.companion.core.designsystem.OfIcons
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfSwitchRow
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plan F8f: the range's quick settings, on a compact window as a sheet over the scene (not dimmed,
 * so the change shows as it's made; dismissed by a swipe down, a tap outside or Back), capped at
 * [maxHeight] so the tee view stays in sight.
 */
@Composable
internal fun RangeQuickSettingsSheet(
    uiState: DrivingRangeUiState,
    onEvent: (DrivingRangeEvent) -> Unit,
    onDismiss: () -> Unit,
    maxHeight: Dp,
) {
    OfBottomSheet(
        onDismiss = onDismiss,
        dimBehind = false,
        maxContentHeight = maxHeight,
        modifier = Modifier.testTag(RangeTestTags.QUICK_SETTINGS_PANEL),
    ) {
        RangeQuickSettingsContent(uiState, onEvent)
    }
}

/** Plan F8f: the same settings as a side panel beside the scene on an expanded window. */
@Composable
internal fun RangeQuickSettingsPanel(
    uiState: DrivingRangeUiState,
    onEvent: (DrivingRangeEvent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(OfColorTokens.BgCard)
                .testTag(RangeTestTags.QUICK_SETTINGS_PANEL),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = OfSpacing.Xl, end = OfSpacing.Sm, top = OfSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OfText(text = "Range settings", role = OfTextRole.Title, modifier = Modifier.weight(1f))
            OfOutlinedButton(
                text = "Done",
                onClick = onClose,
                modifier = Modifier.testTag(RangeTestTags.QUICK_SETTINGS_CLOSE),
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = OfSpacing.Xl, vertical = OfSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(OfSpacing.Md),
        ) {
            RangeQuickSettingsContent(uiState, onEvent)
        }
    }
}

/** The controls row's gear: opens the quick settings. */
@Composable
internal fun RangeQuickSettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OfIconButton(
        imageVector = OfIcons.Settings,
        contentDescription = "Range settings",
        onClick = onClick,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(percent = 50))
                .testTag(RangeTestTags.QUICK_SETTINGS),
    )
}

/**
 * Show, Trail, View and Numbers. Every control sends one [DrivingRangeEvent]; the view model
 * persists it through the key Settings › Practice uses, so the scene and Settings both follow.
 */
@Composable
internal fun ColumnScope.RangeQuickSettingsContent(
    uiState: DrivingRangeUiState,
    onEvent: (DrivingRangeEvent) -> Unit,
) {
    val browse = uiState.browse
    val camera = uiState.camera
    val trail = camera.trail

    Section("SHOW")
    Choices {
        for (show in RangeShowSetting.entries) {
            Choice(show.pickerLabel, browse.show == show, RangeTestTags.quickShow(show)) {
                onEvent(DrivingRangeEvent.SetShow(show))
            }
        }
    }
    // Plan F8f: whose shots, on this device only (hidden without a roster).
    val profileChoices = browse.profiles.choices
    if (profileChoices.isNotEmpty()) {
        OfText(text = "Viewing profile", role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
        Choices {
            for (choice in profileChoices) {
                Choice(choice.label, choice.selected, RangeTestTags.quickViewingProfile(choice.profile)) {
                    onEvent(DrivingRangeEvent.SetViewingProfile(choice.profile))
                }
            }
        }
    }
    val overlay = browse.mode as? RangeMode.Overlay
    if (overlay == null) {
        Note("Pick a show option other than Live to filter by club.")
    } else {
        Choices {
            Choice("All clubs", overlay.club == null, RangeTestTags.quickClub(null)) {
                onEvent(DrivingRangeEvent.SetOverlayClub(null))
            }
            for (club in browse.overlayClubs) {
                Choice(GolfClub.displayNameFor(club), overlay.club == club, RangeTestTags.quickClub(club)) {
                    onEvent(DrivingRangeEvent.SetOverlayClub(club))
                }
            }
        }
    }

    Section("TRAIL")
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
    ) {
        for (style in ShotTrailStyle.entries) {
            TrailStyleChoice(style, camera.theme, selected = trail.style == style) {
                onEvent(DrivingRangeEvent.SetTrailStyle(style))
            }
        }
    }
    OfText(text = "Keep last shots", role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
    Choices {
        for (count in SHOT_TRAIL_KEEP_OPTIONS) {
            Choice(shotTrailKeepLabel(count), trail.keepLast == count, RangeTestTags.quickKeepLast(count)) {
                onEvent(DrivingRangeEvent.SetTrailKeepLast(count))
            }
        }
    }
    OfText(text = "Landing effect", role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
    Choices {
        for (effect in LandingEffect.entries) {
            Choice(effect.pickerLabel, trail.landingEffect == effect, RangeTestTags.quickLandingEffect(effect)) {
                onEvent(DrivingRangeEvent.SetLandingEffect(effect))
            }
        }
    }

    Section("VIEW")
    Choices {
        for (theme in RangeThemeSetting.entries) {
            Choice(theme.pickerLabel, camera.theme.setting == theme, RangeTestTags.quickTheme(theme)) {
                onEvent(DrivingRangeEvent.SetTheme(theme))
            }
        }
    }
    Choices {
        for (mode in RangeCameraMode.entries) {
            Choice(
                mode.pickerLabel,
                uiState.cameraMode == mode,
                RangeTestTags.quickCamera(mode),
                enabled = !uiState.cameraModeLocked,
            ) { onEvent(DrivingRangeEvent.SetCameraMode(mode)) }
        }
        OfOutlinedButton(
            text = "Reset view",
            onClick = { onEvent(DrivingRangeEvent.ResetView) },
            enabled = browse.userTransformed,
            modifier = Modifier.testTag(RangeTestTags.QUICK_RESET_VIEW),
        )
    }
    if (uiState.cameraModeLocked) Note("Reduced motion keeps the camera fixed.")

    Section("NUMBERS")
    OfSwitchRow(
        label = "Show total + roll (est.)",
        checked = camera.numbers.showTotal,
        onCheckedChange = { onEvent(DrivingRangeEvent.SetShowTotal(it)) },
        detail = "Estimated from carry, launch and spin",
        modifier = Modifier.testTag(RangeTestTags.QUICK_SHOW_TOTAL),
    )
    Choices {
        for (units in UnitSystem.entries) {
            Choice(unitSystemLabel(units), camera.numbers.units == units, RangeTestTags.quickUnits(units)) {
                onEvent(DrivingRangeEvent.SetUnits(units))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    OfText(
        text = title,
        role = OfTextRole.Eyebrow,
        color = OfColorTokens.Gold,
        modifier = Modifier.padding(top = OfSpacing.Sm),
    )
}

@Composable
private fun Note(text: String) {
    OfText(text = text, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
}

@Composable
private fun Choices(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs),
    ) { content() }
}

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OfChip(
        label = label,
        selected = selected,
        enabled = enabled,
        minTouchTarget = true,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}

@Preview(heightDp = 900)
@Composable
private fun RangeQuickSettingsPreview() {
    OfTheme {
        Column(
            modifier = Modifier.background(OfColorTokens.BgCard).padding(OfSpacing.Xl),
            verticalArrangement = Arrangement.spacedBy(OfSpacing.Md),
        ) {
            RangeQuickSettingsContent(DrivingRangeUiState.Ready(), onEvent = {})
        }
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun RangeQuickSettingsPanelTabletPreview() {
    OfTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            DrivingRangeScreen(
                uiState = DrivingRangeUiState.Ready(),
                reduceMotion = true,
                onEvent = {},
                onExit = {},
                modifier = Modifier.weight(1f),
                windowClass = dev.openflight.companion.core.designsystem.OfWindowClass.EXPANDED,
                initialQuickSettings = true,
            )
        }
    }
}
