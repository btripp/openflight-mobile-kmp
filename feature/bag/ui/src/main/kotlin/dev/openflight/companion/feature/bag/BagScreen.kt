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

/**
 * The My Bag destination (plan F5): owns the [BagViewModel], the selected club and the
 * conditions editor. On one pane, back closes the club detail before leaving the bag.
 */
@Composable
fun BagRoute(
    onOpenAnalysis: () -> Unit,
    windowClass: OfWindowClass = rememberOfWindowClass(),
    viewModel: BagViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedClub by rememberSaveable { mutableStateOf<String?>(null) }
    var editingConditions by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect == BagEffect.ConditionsSaved) editingConditions = false
        }
    }
    BackHandler(enabled = selectedClub != null && windowClass != OfWindowClass.EXPANDED) { selectedClub = null }
    BagScreen(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onOpenAnalysis = onOpenAnalysis,
        selectedClub = selectedClub,
        onSelectClub = { selectedClub = it },
        editingConditions = editingConditions,
        onEditConditions = { editingConditions = it },
        windowClass = windowClass,
        detail = { wireValue -> ClubDetailPane(wireValue) },
    )
}

/**
 * My Bag, stateless: the bag list (conditions card, clubs with their average carry and the gap
 * chip to the next club, editing) and the selected club's detail, side by side on an expanded
 * window ([OfListDetailPane]).
 *
 * @param detail the detail pane for a club's wire value (the real one owns a ViewModel).
 */
@Suppress("LongParameterList") // Stateless screen: state, callbacks and the detail slot.
@Composable
fun BagScreen(
    uiState: BagUiState,
    onEvent: (BagEvent) -> Unit,
    onOpenAnalysis: () -> Unit,
    selectedClub: String?,
    onSelectClub: (String?) -> Unit,
    editingConditions: Boolean,
    onEditConditions: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    windowClass: OfWindowClass = rememberOfWindowClass(),
    detail: @Composable (wireValue: String) -> Unit = {},
) {
    var editMode by rememberSaveable { mutableStateOf(false) }
    var editingClubId by rememberSaveable { mutableStateOf<String?>(null) }
    OfScaffold(
        modifier = modifier,
        topBar = {
            // Plan F1d: Bag is a top-level destination (the bar or rail stays on screen), so no Done.
            OfTopBar(title = "My Bag", eyebrow = "OPENFLIGHT")
        },
    ) { padding ->
        OfListDetailPane(
            hasSelection = selectedClub != null,
            windowClass = windowClass,
            modifier = Modifier.padding(padding),
            list = {
                BagList(
                    uiState = uiState,
                    onEvent = onEvent,
                    onOpenAnalysis = onOpenAnalysis,
                    selectedClub = selectedClub,
                    onSelectClub = onSelectClub,
                    onEditConditions = { onEditConditions(true) },
                    editMode = editMode,
                    onEditMode = { editMode = it },
                    onEditClub = { editingClubId = it },
                )
            },
            detail = {
                DetailPane(
                    selectedClub = selectedClub,
                    singlePane = windowClass != OfWindowClass.EXPANDED,
                    onClose = { onSelectClub(null) },
                    detail = detail,
                )
            },
        )
    }
    if (editingConditions) {
        ConditionsEditor(
            card = uiState.conditions,
            onSave = { onEvent(BagEvent.SaveConditions(it)) },
            onDismiss = { onEditConditions(false) },
        )
    }
    uiState.clubs.firstOrNull { it.id == editingClubId }?.let { row ->
        ClubEditor(
            row = row,
            onSave = { make, model, loft ->
                onEvent(BagEvent.EditClub(row.id, make, model, loft))
                editingClubId = null
            },
            onDismiss = { editingClubId = null },
        )
    }
}

@Suppress("LongParameterList", "LongMethod") // One list with its editing controls.
@Composable
private fun BagList(
    uiState: BagUiState,
    onEvent: (BagEvent) -> Unit,
    onOpenAnalysis: () -> Unit,
    selectedClub: String?,
    onSelectClub: (String?) -> Unit,
    onEditConditions: () -> Unit,
    editMode: Boolean,
    onEditMode: (Boolean) -> Unit,
    onEditClub: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(BagTestTags.LIST),
        contentPadding = PaddingValues(horizontal = OfSpacing.Xl, vertical = OfSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
    ) {
        item(key = "conditions") { ConditionsCard(uiState.conditions, onEdit = onEditConditions) }
        uiState.error?.let { error ->
            item(key = "error") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OfText(
                        text = error,
                        role = OfTextRole.BodySmall,
                        color = OfColorTokens.Danger,
                        modifier = Modifier.weight(1f).testTag(BagTestTags.ERROR),
                    )
                    OfTextButton(text = "Dismiss", onClick = { onEvent(BagEvent.DismissError) })
                }
            }
        }
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
                ) {
                    OfText(
                        text = uiState.bagName,
                        role = OfTextRole.Title,
                        modifier = Modifier.weight(1f).testTag(BagTestTags.BAG_NAME),
                    )
                    OfTextButton(
                        text = if (editMode) "Finish" else "Edit",
                        onClick = { onEditMode(!editMode) },
                        modifier = Modifier.testTag(BagTestTags.EDIT_TOGGLE),
                    )
                }
                if (uiState.bags.size > 1) {
                    OfDropdownMenu(
                        label = "Bag",
                        selected = uiState.bagName,
                        options = uiState.bags.map { it.name },
                        onSelect = { name ->
                            uiState.bags.firstOrNull { it.name == name }?.let { onEvent(BagEvent.SelectBag(it.id)) }
                        },
                    )
                }
                OfOutlinedButton(
                    text = "Club analysis",
                    onClick = onOpenAnalysis,
                    modifier = Modifier.fillMaxWidth().testTag(BagTestTags.OPEN_ANALYSIS),
                )
                if (uiState.carryAdjusted) {
                    OfText(
                        text = "Carries adjusted for conditions",
                        role = OfTextRole.Label,
                        color = OfColorTokens.CreamMuted,
                    )
                }
            }
        }
        items(uiState.clubs, key = { it.id }) { row ->
            ClubRow(
                row = row,
                selected = row.wireValue == selectedClub,
                editMode = editMode,
                isFirst = row == uiState.clubs.first(),
                isLast = row == uiState.clubs.last(),
                onClick = { onSelectClub(row.wireValue) },
                onEvent = onEvent,
                onEditClub = { onEditClub(row.id) },
            )
        }
        if (editMode && uiState.addableClubs.isNotEmpty()) {
            item(key = "add") {
                Box(Modifier.testTag(BagTestTags.ADD_CLUB)) {
                    OfDropdownMenu(
                        label = "Add a club",
                        selected = "",
                        options = uiState.addableClubs.map { it.displayName },
                        onSelect = { name ->
                            GolfClub.entries.firstOrNull { it.displayName == name }?.let {
                                onEvent(
                                    BagEvent.AddClub(it),
                                )
                            }
                        },
                    )
                }
            }
        }
        item(key = "note") {
            OfText(text = BagCopy.EXCLUDES_IMPORTED, role = OfTextRole.Label, color = OfColorTokens.CreamMuted)
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ClubRow(
    row: BagClubRow,
    selected: Boolean,
    editMode: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onEvent: (BagEvent) -> Unit,
    onEditClub: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs)) {
        OfCard(
            elevated = selected,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(BagTestTags.club(row.wireValue))
                    .clickable(role = Role.Button, onClickLabel = "Open club detail", onClick = onClick),
        ) {
            Row(
                modifier = Modifier.clearAndSetSemantics { contentDescription = row.accessibilityLabel },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OfSpacing.Md),
            ) {
                ClubBadge(row.shortLabel, row.colorIndex)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OfText(text = row.name, role = OfTextRole.TitleSmall)
                    OfText(
                        text = row.makeModel ?: "Add make and model",
                        role = OfTextRole.BodySmall,
                        color = if (row.makeModel == null) OfColorTokens.CreamMuted else OfColorTokens.CreamDim,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    OfText(text = row.carryLabel, role = OfTextRole.TitleSmall, color = OfColorTokens.Gold)
                    OfText(
                        text = listOfNotNull(row.plusMinusLabel, row.shotCountLabel).joinToString(" · "),
                        role = OfTextRole.Label,
                        color = OfColorTokens.CreamMuted,
                    )
                }
            }
            if (editMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Xs)) {
                    OfTextButton(
                        text = "Up",
                        enabled = !isFirst,
                        onClick = { onEvent(BagEvent.MoveClub(row.id, up = true)) },
                        modifier = Modifier.testTag(BagTestTags.moveUp(row.wireValue)),
                    )
                    OfTextButton(
                        text = "Down",
                        enabled = !isLast,
                        onClick = { onEvent(BagEvent.MoveClub(row.id, up = false)) },
                        modifier = Modifier.testTag(BagTestTags.moveDown(row.wireValue)),
                    )
                    OfTextButton(text = "Details", onClick = onEditClub)
                    OfTextButton(
                        text = "Remove",
                        destructive = true,
                        onClick = { onEvent(BagEvent.RemoveClub(row.id)) },
                        modifier = Modifier.testTag(BagTestTags.remove(row.wireValue)),
                    )
                }
            }
        }
        row.gap?.let { gap ->
            OfPill(
                label = gap.label,
                tone = if (gap.flag == null) StatusTone.Neutral else StatusTone.Negative,
                modifier = Modifier.padding(start = OfSpacing.Xl).testTag(BagTestTags.gap(row.wireValue)),
            )
        }
    }
}

@Composable
private fun ConditionsCard(
    card: BagConditionsCardState,
    onEdit: () -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth().testTag(BagTestTags.CONDITIONS_CARD)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OfText(text = "Conditions", role = OfTextRole.TitleSmall, modifier = Modifier.weight(1f))
            OfChip(label = card.modeLabel)
            OfTextButton(text = "Edit", onClick = onEdit, modifier = Modifier.testTag(BagTestTags.CONDITIONS_EDIT))
        }
        OfText(
            text = card.summary,
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
            modifier = Modifier.testTag(BagTestTags.CONDITIONS_SUMMARY),
        )
        card.windNote?.let {
            OfText(
                text = it,
                role = OfTextRole.BodySmall,
                color = OfColorTokens.Warning,
                modifier = Modifier.testTag(BagTestTags.WIND_NOTE),
            )
        }
    }
}

@Composable
private fun DetailPane(
    selectedClub: String?,
    singlePane: Boolean,
    onClose: () -> Unit,
    detail: @Composable (String) -> Unit,
) {
    if (selectedClub == null) {
        Box(
            Modifier.fillMaxSize().padding(OfSpacing.Xl).testTag(BagTestTags.DETAIL_PLACEHOLDER),
            contentAlignment = Alignment.Center,
        ) {
            OfText(
                text = "Pick a club to see its distances.",
                role = OfTextRole.Body,
                color = OfColorTokens.CreamDim,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (singlePane) {
            OfTextButton(
                text = "Back to bag",
                onClick = onClose,
                modifier = Modifier.padding(start = OfSpacing.Md).testTag(BagTestTags.DETAIL_DONE),
            )
        }
        Box(Modifier.weight(1f)) { detail(selectedClub) }
    }
}

@Suppress("MagicNumber") // Preview fixture.
private fun previewBag(): BagUiState {
    val clubs = listOf(GolfClub.DRIVER to 250.0, GolfClub.IRON_7 to 160.0, GolfClub.IRON_8 to 155.0)
    return BagUiState(
        loaded = true,
        bagId = "bag-1",
        bagName = "My Bag",
        clubs =
            clubs.mapIndexed { index, (club, carry) ->
                BagClubRow(
                    id = "club-$index",
                    club = club,
                    wireValue = club.wireValue,
                    name = club.displayName,
                    shortLabel = club.shortLabel,
                    makeModel = if (index == 0) "Acme Rocket" else null,
                    make = null,
                    model = null,
                    loftDeg = null,
                    avgCarryYards = carry,
                    carryLabel = "${carry.toInt()} yds",
                    plusMinusLabel = "± 5 yds",
                    shotCountLabel = "8 shots",
                    colorIndex = index,
                    gap = if (index == 1) GapChipState("5 yds gap · tight", GapFlag.TOO_TIGHT) else null,
                )
            },
        conditions = BagConditionsCardState(summary = "Sea level · 59 °F · calm · normal turf"),
    )
}

@Preview(widthDp = 400, heightDp = 800)
@Composable
private fun BagPreview() {
    OfTheme { BagScreen(previewBag(), {}, {}, null, {}, false, {}, windowClass = OfWindowClass.COMPACT) }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun BagExpandedPreview() {
    OfTheme {
        BagScreen(previewBag(), {}, {}, "7-iron", {}, false, {}, windowClass = OfWindowClass.EXPANDED) {
            OfText(text = "7-Iron detail", role = OfTextRole.Title)
        }
    }
}
