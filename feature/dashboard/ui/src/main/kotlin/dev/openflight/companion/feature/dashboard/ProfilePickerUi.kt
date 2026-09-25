// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfBottomSheet
import dev.openflight.companion.core.designsystem.OfButton
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfDisabledReason
import dev.openflight.companion.core.designsystem.OfIcon
import dev.openflight.companion.core.designsystem.OfIcons
import dev.openflight.companion.core.designsystem.OfNotice
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfSpinner
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextButton
import dev.openflight.companion.core.designsystem.OfTextField
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.StatusTone

// Plan R8f: the profile picker beside the club (Expo `ProfilePicker.tsx`, `ProfileNameForm.tsx`).

private val MIN_TOUCH_TARGET = 48.dp

/** The trigger on the connection card: the active profile and "Change". */
@Composable
internal fun ProfileSelector(
    profile: ProfilePickerState,
    onEvent: (DashboardEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
        OfText(text = "PROFILE", role = OfTextRole.Eyebrow, color = OfColorTokens.CreamDim)
        OfOutlinedButton(
            text = "${profile.label} · Change",
            onClick = { onEvent(ProfilePickerEvent.Open) },
            enabled = profile.selection.isAvailable,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TOUCH_TARGET)
                    .testTag(DashboardTestTags.PROFILE_BUTTON),
        )
        profile.selection.disabledReason?.let { OfDisabledReason(it) }
        if (profile.sheet == ProfileSheet.Closed) profile.notice?.let { ProfileNotice(it, onEvent) }
    }
    if (profile.sheet != ProfileSheet.Closed) ProfileSheetContent(profile, onEvent)
}

@Composable
private fun ProfileSheetContent(
    profile: ProfilePickerState,
    onEvent: (DashboardEvent) -> Unit,
) {
    OfBottomSheet(
        onDismiss = { onEvent(ProfilePickerEvent.Close) },
        modifier = Modifier.testTag(DashboardTestTags.PROFILE_SHEET),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OfText(
                text = ProfilePickerState.TITLE,
                role = OfTextRole.Title,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (profile.sheet == ProfileSheet.List) {
                OfTextButton(
                    text = "Add",
                    onClick = { onEvent(ProfilePickerEvent.StartAdd) },
                    enabled = profile.addEnabled,
                    modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET).testTag(DashboardTestTags.PROFILE_ADD),
                )
            }
            OfTextButton(
                text = "Done",
                onClick = { onEvent(ProfilePickerEvent.Close) },
                modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET).testTag(DashboardTestTags.PROFILE_DONE),
            )
        }
        profile.notice?.let { ProfileNotice(it, onEvent) }
        when (val sheet = profile.sheet) {
            is ProfileSheet.Adding -> NameForm("Add a profile", "Add", sheet.draft, sheet.error, onEvent)
            is ProfileSheet.Renaming -> NameForm("Rename profile", "Save", sheet.draft, sheet.error, onEvent)
            is ProfileSheet.ConfirmingRemoval -> ConfirmRemoval(sheet, onEvent)
            ProfileSheet.List, ProfileSheet.Closed -> Roster(profile, onEvent)
        }
    }
}

@Composable
private fun Roster(
    profile: ProfilePickerState,
    onEvent: (DashboardEvent) -> Unit,
) {
    if (!profile.loaded) {
        // The Pi never reports an empty roster, so "nothing yet" is a wait, not an empty state.
        OfSpinner(modifier = Modifier.testTag(DashboardTestTags.PROFILE_LOADING))
        return
    }
    if (!profile.canAdd) {
        OfText(text = ProfilePickerState.AT_CAPACITY_TEXT, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
    }
    profile.edits.disabledReason?.let { OfDisabledReason(it) }
    profile.rows.forEach { row -> ProfileRowItem(row, profile.edits.isAvailable, onEvent) }
}

@Composable
private fun ProfileRowItem(
    row: ProfileRow,
    editsAvailable: Boolean,
    onEvent: (DashboardEvent) -> Unit,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TOUCH_TARGET)
                    .selectable(
                        selected = row.active,
                        role = Role.RadioButton,
                        onClick = { onEvent(ProfilePickerEvent.Select(row.id)) },
                    ).testTag(DashboardTestTags.profileRow(row.id)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
        ) {
            // A check mark, not only the gold text, marks the active profile.
            if (row.active) OfIcon(OfIcons.Check, contentDescription = "Active", tint = OfColorTokens.Gold)
            OfText(
                text = row.name,
                role = OfTextRole.Body,
                color = if (row.active) OfColorTokens.Gold else OfColorTokens.Cream,
                modifier = Modifier.weight(1f),
            )
        }
        if (editsAvailable) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
                OfTextButton(
                    text = "Rename",
                    onClick = { onEvent(ProfilePickerEvent.StartRename(row.id)) },
                    modifier =
                        Modifier
                            .heightIn(
                                min = MIN_TOUCH_TARGET,
                            ).testTag(DashboardTestTags.profileRename(row.id)),
                )
                OfTextButton(
                    text = "Remove",
                    destructive = true,
                    enabled = row.removeBlockedReason == null,
                    onClick = { onEvent(ProfilePickerEvent.Remove(row.id)) },
                    modifier =
                        Modifier
                            .heightIn(
                                min = MIN_TOUCH_TARGET,
                            ).testTag(DashboardTestTags.profileRemove(row.id)),
                )
                row.removeBlockedReason?.let {
                    OfText(
                        text = it,
                        role = OfTextRole.BodySmall,
                        color = OfColorTokens.CreamDim,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
    }
}

/** Rendered inline in the sheet, not as a dialog over it (Expo `ProfileNameForm.tsx`). */
@Composable
private fun NameForm(
    title: String,
    confirmLabel: String,
    draft: String,
    error: String?,
    onEvent: (DashboardEvent) -> Unit,
) {
    OfText(text = title, role = OfTextRole.TitleSmall, modifier = Modifier.semantics { heading() })
    OfTextField(
        value = draft,
        onValueChange = { onEvent(ProfilePickerEvent.NameEdited(it)) },
        placeholder = "Name",
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
        onSubmit = { onEvent(ProfilePickerEvent.SubmitName) },
        modifier = Modifier.fillMaxWidth().testTag(DashboardTestTags.PROFILE_NAME_FIELD),
    )
    error?.let {
        OfNotice(
            title = it,
            tone = StatusTone.Negative,
            modifier = Modifier.testTag(DashboardTestTags.PROFILE_FORM_ERROR),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
        OfButton(
            text = confirmLabel,
            onClick = { onEvent(ProfilePickerEvent.SubmitName) },
            enabled = draft.isNotBlank(),
            modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET).testTag(DashboardTestTags.PROFILE_SAVE),
        )
        OfOutlinedButton(
            text = "Cancel",
            onClick = { onEvent(ProfilePickerEvent.CancelForm) },
            modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET).testTag(DashboardTestTags.PROFILE_CANCEL),
        )
    }
}

@Composable
private fun ConfirmRemoval(
    removal: ProfileSheet.ConfirmingRemoval,
    onEvent: (DashboardEvent) -> Unit,
) {
    OfText(
        text = "Remove “${removal.name}”?",
        role = OfTextRole.TitleSmall,
        modifier = Modifier.semantics { heading() },
    )
    OfText(
        text = "The Pi deletes this profile for every screen and phone connected to it.",
        role = OfTextRole.BodySmall,
        color = OfColorTokens.CreamDim,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
        OfOutlinedButton(
            text = "Remove",
            onClick = { onEvent(ProfilePickerEvent.ConfirmRemove) },
            modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET).testTag(DashboardTestTags.PROFILE_REMOVE_CONFIRM),
        )
        OfTextButton(
            text = "Cancel",
            onClick = { onEvent(ProfilePickerEvent.CancelForm) },
            modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET).testTag(DashboardTestTags.PROFILE_CANCEL),
        )
    }
}

@Composable
private fun ProfileNotice(
    text: String,
    onEvent: (DashboardEvent) -> Unit,
) {
    OfNotice(
        title = text,
        tone = StatusTone.InProgress,
        modifier = Modifier.testTag(DashboardTestTags.PROFILE_NOTICE),
    ) {
        OfTextButton(
            text = "OK",
            onClick = { onEvent(ProfilePickerEvent.DismissNotice) },
            modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET),
        )
    }
}
