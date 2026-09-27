// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfChip
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfConfirmDialog
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfSwitchRow
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole

/**
 * Plan F14: Settings › Device › Demo mode: the switch (on and off at once, no relaunch), how often
 * the pretend Pi hits a shot by itself while it's on, and "Clear demo data" behind a confirmation.
 */
@Composable
internal fun DemoModeCard(
    demo: DemoSettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.DEMO_CARD), contentSpacing = OfSpacing.Md) {
        SectionTitle("DEMO MODE")
        OfSwitchRow(
            label = DemoSettingsUiState.TITLE,
            detail = DemoSettingsUiState.SUMMARY,
            checked = demo.enabled,
            onCheckedChange = { onEvent(SettingsEvent.SetDemoMode(it)) },
            modifier = Modifier.testTag(SettingsTestTags.DEMO_SWITCH),
        )
        if (demo.enabled) {
            OfText(
                text = DemoSettingsUiState.AUTO_FIRE_LABEL,
                role = OfTextRole.BodySmall,
                color = OfColorTokens.CreamDim,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
                modifier = Modifier.testTag(SettingsTestTags.DEMO_AUTO_FIRE),
            ) {
                demo.autoFireOptions.forEach { seconds ->
                    OfChip(
                        label = DemoSettingsUiState.autoFireLabel(seconds),
                        selected = seconds == demo.autoFireSeconds,
                        onClick = { onEvent(SettingsEvent.SetDemoAutoFire(seconds)) },
                        modifier = Modifier.testTag(SettingsTestTags.demoAutoFire(seconds)),
                    )
                }
            }
        }
        OfOutlinedButton(
            text = DemoSettingsUiState.CLEAR_LABEL,
            onClick = { onEvent(SettingsEvent.RequestClearDemoData) },
            modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.DEMO_CLEAR),
        )
    }
    if (demo.confirmingClear) {
        OfConfirmDialog(
            title = DemoSettingsUiState.CLEAR_CONFIRM_TITLE,
            message = DemoSettingsUiState.CLEAR_CONFIRM_MESSAGE,
            confirmLabel = DemoSettingsUiState.CLEAR_CONFIRM_ACTION,
            destructive = true,
            onConfirm = { onEvent(SettingsEvent.ConfirmClearDemoData) },
            onDismiss = { onEvent(SettingsEvent.CancelClearDemoData) },
            confirmTag = SettingsTestTags.DEMO_CLEAR_CONFIRM,
            dismissTag = SettingsTestTags.DEMO_CLEAR_CANCEL,
        )
    }
}
