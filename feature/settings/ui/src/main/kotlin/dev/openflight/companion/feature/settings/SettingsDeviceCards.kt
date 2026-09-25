// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfDisabledReason
import dev.openflight.companion.core.designsystem.OfIcon
import dev.openflight.companion.core.designsystem.OfIcons
import dev.openflight.companion.core.designsystem.OfNotice
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextButton
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.StatusTone

// Plan R8f's device section (Expo `device.tsx`): launch monitor, power and stopping OpenFlight.

private val MIN_TOUCH_TARGET = 48.dp

/** `TriggerCard`: waits for the Pi's report instead of showing zeros. */
@Composable
internal fun LaunchMonitorCard(trigger: TriggerCard) {
    OfCard(modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.TRIGGER), contentSpacing = OfSpacing.Md) {
        SectionTitle("LAUNCH MONITOR")
        when (trigger) {
            is TriggerCard.Loaded -> {
                trigger.rows.forEach { InfoRow(it.label, it.value) }
            }

            TriggerCard.Waiting -> {
                OfText(
                    text = TriggerCard.WAITING_TEXT,
                    role = OfTextRole.BodySmall,
                    color = OfColorTokens.CreamDim,
                    modifier = Modifier.testTag(SettingsTestTags.TRIGGER_WAITING),
                )
            }

            is TriggerCard.Unavailable -> {
                OfDisabledReason(trigger.reason)
            }
        }
    }
}

/** `PowerCard`: only once a `power_status` arrived; a low battery adds a warning icon, not just a colour. */
@Composable
internal fun PowerStatusCard(power: PowerCard) {
    OfCard(modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.POWER), contentSpacing = OfSpacing.Md) {
        SectionTitle("POWER")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
        ) {
            if (power.warning) OfIcon(OfIcons.Warning, contentDescription = "Warning", tint = OfColorTokens.Warning)
            InfoRow("State", power.stateLabel, Modifier.weight(1f).testTag(SettingsTestTags.POWER_STATE))
        }
        power.rows.forEach { InfoRow(it.label, it.value) }
    }
}

/** Stopping OpenFlight (`ShutdownSection`): the button, then the pending, done or failed outcome. */
@Composable
internal fun ShutdownCard(
    shutdown: ShutdownSettings,
    onEvent: (SettingsEvent) -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        SectionTitle("OPENFLIGHT SERVER")
        when (val phase = shutdown.phase) {
            is ShutdownPhase.Pending -> {
                OfNotice(
                    title = ShutdownPhase.PENDING_TEXT,
                    detail = phase.target,
                    tone = StatusTone.InProgress,
                    busy = true,
                    modifier = Modifier.testTag(SettingsTestTags.SHUTDOWN_PENDING),
                )
            }

            is ShutdownPhase.Done -> {
                OfNotice(
                    title = ShutdownPhase.DONE_TITLE,
                    detail = ShutdownPhase.DONE_DETAIL,
                    tone = StatusTone.Positive,
                    modifier = Modifier.testTag(SettingsTestTags.SHUTDOWN_DONE),
                ) {
                    OfTextButton(
                        text = "OK",
                        onClick = { onEvent(SettingsEvent.DismissShutdown) },
                        modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET).testTag(SettingsTestTags.SHUTDOWN_DISMISS),
                    )
                }
            }

            is ShutdownPhase.Failed -> {
                OfNotice(
                    title = ShutdownPhase.FAILED_TITLE,
                    detail = "${phase.reason} ${ShutdownPhase.STILL_RUNNING}",
                    tone = StatusTone.Negative,
                    modifier = Modifier.testTag(SettingsTestTags.SHUTDOWN_FAILED),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
                        OfOutlinedButton(
                            text = "Try again",
                            onClick = { onEvent(SettingsEvent.RetryShutdown) },
                            modifier =
                                Modifier
                                    .heightIn(
                                        min = MIN_TOUCH_TARGET,
                                    ).testTag(SettingsTestTags.SHUTDOWN_RETRY),
                        )
                        OfTextButton(
                            text = "Dismiss",
                            onClick = { onEvent(SettingsEvent.DismissShutdown) },
                            modifier =
                                Modifier
                                    .heightIn(
                                        min = MIN_TOUCH_TARGET,
                                    ).testTag(SettingsTestTags.SHUTDOWN_DISMISS),
                        )
                    }
                }
            }

            ShutdownPhase.Idle, ShutdownPhase.Confirming -> {
                OfText(
                    text = "Stops the OpenFlight server. The Pi itself stays on.",
                    role = OfTextRole.BodySmall,
                    color = OfColorTokens.CreamDim,
                )
                OfTextButton(
                    text = "Stop OpenFlight",
                    destructive = true,
                    enabled = shutdown.shutdown.isAvailable,
                    onClick = { onEvent(SettingsEvent.RequestShutdown) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = MIN_TOUCH_TARGET,
                            ).testTag(SettingsTestTags.SHUTDOWN),
                )
                shutdown.shutdown.disabledReason?.let { OfDisabledReason(it) }
            }
        }
    }
}
