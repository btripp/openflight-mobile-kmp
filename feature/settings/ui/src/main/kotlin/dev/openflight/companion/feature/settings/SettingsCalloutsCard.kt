// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.openflight.companion.core.data.CalloutTrigger
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfChip
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfDropdownMenu
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfSegmentedPicker
import dev.openflight.companion.core.designsystem.OfSlider
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfSwitchRow
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextButton
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.speech.Voice
import dev.openflight.companion.core.speech.VoiceQuality
import kotlin.math.roundToInt

// Plan F7: audio call-outs, in its own file (not appended to SettingsScreen.kt) to keep that
// file's diff mergeable and under detekt's TooManyFunctions threshold (§4a A7).

private val CALLOUT_TRIGGER_LABELS =
    mapOf(CalloutTrigger.EVERY_SHOT to "Every shot", CalloutTrigger.GAMES_ONLY to "Games only")

@Composable
internal fun AudioCalloutsCard(
    callouts: CalloutSettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    windowClass: OfWindowClass,
) {
    OfCard(modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.CALLOUTS), contentSpacing = OfSpacing.Md) {
        SectionTitle("AUDIO CALL-OUTS")
        OfSwitchRow(
            label = "Speak shot results",
            checked = callouts.enabled,
            onCheckedChange = { checked -> onEvent(SettingsEvent.SetCalloutsEnabled(checked)) },
            // Plan A16: this is deliberate, not a bug — call-outs play like a coaching or
            // navigation app's voice, not a notification the silent switch should mute.
            detail = "Call-outs play even with the silent switch on.",
            modifier = Modifier.testTag(SettingsTestTags.CALLOUTS_ENABLED),
        )
        if (callouts.enabled) {
            OfSegmentedPicker(
                options = CALLOUT_TRIGGER_LABELS.values.toList(),
                selected = CALLOUT_TRIGGER_LABELS.getValue(callouts.trigger),
                onSelect = { label ->
                    val picked = CALLOUT_TRIGGER_LABELS.entries.first { it.value == label }.key
                    if (picked != callouts.trigger) onEvent(SettingsEvent.SetCalloutTrigger(picked))
                },
                modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.CALLOUTS_TRIGGER),
            )
            if (windowClass == OfWindowClass.EXPANDED) {
                Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Lg)) {
                    Column(modifier = Modifier.weight(1f)) { VoicePicker(callouts, onEvent) }
                    Column(modifier = Modifier.weight(1f)) { RateSlider(callouts.rate, onEvent) }
                }
            } else {
                VoicePicker(callouts, onEvent)
                RateSlider(callouts.rate, onEvent)
            }
            OfText(
                text = callouts.previewText.ifEmpty { "Select at least one field below" },
                role = OfTextRole.BodySmall,
                color = OfColorTokens.CreamDim,
                modifier = Modifier.testTag(SettingsTestTags.CALLOUTS_PREVIEW_TEXT),
            )
            FieldChecklist(callouts.fields, onEvent, windowClass)
        }
    }
}

@Composable
private fun VoicePicker(
    callouts: CalloutSettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    // Flattening the locale groups keeps them adjacent in the dropdown ("grouped by locale");
    // OfDropdownMenu is primitives-only (plan design system contract) so there's no section header.
    val options = callouts.voiceGroups.flatMap { it.voices }
    val selectedLabel = options.firstOrNull { it.id == callouts.selectedVoiceId }?.let(::voiceLabel) ?: "Default voice"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
        OfDropdownMenu(
            label = "VOICE",
            selected = selectedLabel,
            options = listOf("Default voice") + options.map(::voiceLabel),
            onSelect = { label ->
                val voice = options.firstOrNull { voiceLabel(it) == label }
                onEvent(SettingsEvent.SetCalloutVoice(voice?.id))
            },
            modifier = Modifier.weight(1f).testTag(SettingsTestTags.CALLOUTS_VOICE),
        )
        OfOutlinedButton(
            text = "Preview",
            onClick = { onEvent(SettingsEvent.PreviewCallout) },
            modifier = Modifier.testTag(SettingsTestTags.CALLOUTS_PREVIEW_BUTTON),
        )
    }
}

private fun voiceLabel(voice: Voice): String = "${voice.locale} — ${voice.displayName} (${voice.quality.badge()})"

private fun VoiceQuality.badge(): String =
    when (this) {
        VoiceQuality.PREMIUM -> "Premium"
        VoiceQuality.ENHANCED -> "Enhanced"
        VoiceQuality.DEFAULT -> "Standard"
    }

@Composable
private fun RateSlider(
    rate: Float,
    onEvent: (SettingsEvent) -> Unit,
) {
    OfSlider(
        label = "Speech rate",
        value = (rate * RATE_PERCENT).roundToInt(),
        range = RATE_MIN_PERCENT..RATE_MAX_PERCENT,
        step = RATE_STEP_PERCENT,
        unit = "%",
        onValueCommitted = { percent -> onEvent(SettingsEvent.SetCalloutRate(percent / RATE_PERCENT)) },
        modifier = Modifier.testTag(SettingsTestTags.CALLOUTS_RATE),
    )
}

private const val RATE_PERCENT = 100f
private const val RATE_MIN_PERCENT = 50
private const val RATE_MAX_PERCENT = 200
private const val RATE_STEP_PERCENT = 5

@Composable
private fun FieldChecklist(
    fields: List<CalloutFieldRow>,
    onEvent: (SettingsEvent) -> Unit,
    windowClass: OfWindowClass,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
        OfText(text = "Fields to speak, in order", role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
        if (windowClass == OfWindowClass.EXPANDED) {
            // Two columns on a tablet, so a ten-field checklist doesn't need a long scroll.
            val (left, right) = fields.withIndex().partition { (index, _) -> index % 2 == 0 }
            Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Lg)) {
                Column(modifier = Modifier.weight(1f)) { left.forEach { (_, row) -> FieldRow(row, onEvent) } }
                Column(modifier = Modifier.weight(1f)) { right.forEach { (_, row) -> FieldRow(row, onEvent) } }
            }
        } else {
            fields.forEach { row -> FieldRow(row, onEvent) }
        }
    }
}

@Composable
private fun FieldRow(
    row: CalloutFieldRow,
    onEvent: (SettingsEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.calloutField(row.field)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
    ) {
        OfChip(
            label = row.label,
            selected = row.selected,
            onClick = { onEvent(SettingsEvent.ToggleCalloutField(row.field)) },
            minTouchTarget = true,
            modifier = Modifier.weight(1f).testTag(SettingsTestTags.calloutFieldToggle(row.field)),
        )
        if (row.selected) {
            OfTextButton(
                text = "▲",
                onClick = { onEvent(SettingsEvent.MoveCalloutField(row.field, up = true)) },
                enabled = row.canMoveUp,
                modifier = Modifier.testTag(SettingsTestTags.calloutFieldMoveUp(row.field)),
            )
            OfTextButton(
                text = "▼",
                onClick = { onEvent(SettingsEvent.MoveCalloutField(row.field, up = false)) },
                enabled = row.canMoveDown,
                modifier = Modifier.testTag(SettingsTestTags.calloutFieldMoveDown(row.field)),
            )
        }
    }
}
