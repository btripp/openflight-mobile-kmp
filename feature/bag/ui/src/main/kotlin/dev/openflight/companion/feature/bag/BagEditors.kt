// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openflight.companion.core.designsystem.OfBottomSheet
import dev.openflight.companion.core.designsystem.OfButton
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfChip
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfDropdownMenu
import dev.openflight.companion.core.designsystem.OfListDetailPane
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfPill
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSegmentedPicker
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextButton
import dev.openflight.companion.core.designsystem.OfTextField
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.designsystem.rememberOfWindowClass
import dev.openflight.companion.core.insights.GapFlag
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.GolfClub
import org.koin.androidx.compose.koinViewModel

// The My Bag sheets: the manual conditions editor and a club's make, model and loft (plan F5).

@Composable
internal fun ConditionsEditor(
    card: ConditionsCardState,
    onSave: (ConditionsForm) -> Unit,
    onDismiss: () -> Unit,
) {
    var form by remember(card.form) { mutableStateOf(card.form) }
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
    OfBottomSheet(onDismiss = onDismiss) {
        OfText(text = "Conditions", role = OfTextRole.Title)
        LabeledField("Altitude (${card.altitudeUnit})", form.altitude, BagTestTags.CONDITIONS_ALTITUDE, numbers) {
            form = form.copy(altitude = it)
        }
        LabeledField(
            "Temperature (${card.temperatureUnit})",
            form.temperature,
            BagTestTags.CONDITIONS_TEMPERATURE,
            numbers,
        ) {
            form = form.copy(temperature = it)
        }
        LabeledField("Wind speed (${card.windUnit})", form.windSpeed, BagTestTags.CONDITIONS_WIND_SPEED, numbers) {
            form = form.copy(windSpeed = it)
        }
        LabeledField("Wind from (degrees, 0 = north)", form.windFrom, BagTestTags.CONDITIONS_WIND_FROM, numbers) {
            form = form.copy(windFrom = it)
        }
        LabeledField(
            "Target direction (degrees; blank = not set)",
            form.targetBearing,
            BagTestTags.CONDITIONS_TARGET,
            numbers,
        ) {
            form = form.copy(targetBearing = it)
        }
        OfText(text = "Landing area", role = OfTextRole.Label, color = OfColorTokens.CreamDim)
        OfSegmentedPicker(
            options = Firmness.entries.map(BagCopy::surfaceOption),
            selected = BagCopy.surfaceOption(form.surface),
            onSelect = { label ->
                Firmness.entries.firstOrNull { BagCopy.surfaceOption(it) == label }?.let {
                    form =
                        form.copy(surface = it)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        card.formError?.let {
            OfText(
                text = it,
                role = OfTextRole.BodySmall,
                color = OfColorTokens.Danger,
                modifier = Modifier.testTag(BagTestTags.CONDITIONS_ERROR),
            )
        }
        OfButton(text = "Save", onClick = {
            onSave(form)
        }, modifier = Modifier.fillMaxWidth().testTag(BagTestTags.CONDITIONS_SAVE))
    }
}

@Composable
internal fun ClubEditor(
    row: BagClubRow,
    onSave: (make: String, model: String, loft: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var make by remember(row.id) { mutableStateOf(row.make.orEmpty()) }
    var model by remember(row.id) { mutableStateOf(row.model.orEmpty()) }
    var loft by remember(row.id) { mutableStateOf(row.loftDeg?.toString().orEmpty()) }
    OfBottomSheet(onDismiss = onDismiss) {
        OfText(text = row.name, role = OfTextRole.Title)
        LabeledField("Make", make, "bag.edit.make") { make = it }
        LabeledField("Model", model, "bag.edit.model") { model = it }
        LabeledField("Loft (degrees)", loft, "bag.edit.loft", KeyboardOptions(keyboardType = KeyboardType.Decimal)) {
            loft =
                it
        }
        OfButton(text = "Save", onClick = { onSave(make, model, loft) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    tag: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs)) {
        OfText(text = label, role = OfTextRole.Label, color = OfColorTokens.CreamDim)
        OfTextField(
            value = value,
            onValueChange = onValueChange,
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}
