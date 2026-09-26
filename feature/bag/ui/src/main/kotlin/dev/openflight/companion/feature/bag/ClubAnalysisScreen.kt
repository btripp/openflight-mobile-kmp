// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfClubPalette
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfContentWidth
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSegmentedPicker
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.designsystem.rememberOfWindowClass
import dev.openflight.companion.core.model.GolfClub
import org.koin.androidx.compose.koinViewModel

/** The Club Analysis destination: owns the [ClubAnalysisViewModel]. */
@Composable
fun ClubAnalysisRoute(
    onBack: () -> Unit,
    onOpenClub: (wireValue: String) -> Unit,
    viewModel: ClubAnalysisViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ClubAnalysisScreen(uiState = uiState, onEvent = viewModel::onEvent, onBack = onBack, onOpenClub = onOpenClub)
}

/**
 * The bag's clubs ranked longest first as bars scaled to the longest, with the gapping insights.
 * On an expanded window the bars and the insights sit side by side.
 */
@Composable
fun ClubAnalysisScreen(
    uiState: ClubAnalysisUiState,
    onEvent: (ClubAnalysisEvent) -> Unit,
    onBack: () -> Unit,
    onOpenClub: (wireValue: String) -> Unit,
    modifier: Modifier = Modifier,
    windowClass: OfWindowClass = rememberOfWindowClass(),
) {
    OfScaffold(
        modifier = modifier,
        topBar = {
            OfTopBar(
                title = "Club Analysis",
                eyebrow = "MY BAG",
                actions = {
                    OfOutlinedButton(
                        text = "Done",
                        onClick = onBack,
                        modifier = Modifier.padding(end = OfSpacing.Sm).testTag(BagTestTags.ANALYSIS_DONE),
                    )
                },
            )
        },
    ) { padding ->
        if (windowClass == OfWindowClass.EXPANDED) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                BarList(uiState, onEvent, onOpenClub, Modifier.weight(BARS_WEIGHT), showInsights = false)
                InsightList(uiState, Modifier.weight(1f - BARS_WEIGHT))
            }
        } else {
            OfContentWidth(Modifier.padding(padding)) {
                BarList(uiState, onEvent, onOpenClub, Modifier.fillMaxSize(), showInsights = true)
            }
        }
    }
}

@Composable
private fun BarList(
    uiState: ClubAnalysisUiState,
    onEvent: (ClubAnalysisEvent) -> Unit,
    onOpenClub: (String) -> Unit,
    modifier: Modifier,
    showInsights: Boolean,
) {
    LazyColumn(
        modifier = modifier.testTag(BagTestTags.ANALYSIS_LIST),
        contentPadding = PaddingValues(horizontal = OfSpacing.Xl, vertical = OfSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
    ) {
        item(key = "window") {
            OfSegmentedPicker(
                options = uiState.windows.map { it.label },
                selected = uiState.window.label,
                onSelect = { label ->
                    uiState.windows.firstOrNull { it.label == label }?.let {
                        onEvent(
                            ClubAnalysisEvent.SelectWindow(it),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "metric") {
            OfSegmentedPicker(
                options = uiState.metrics.map { it.label },
                selected = uiState.metric.label,
                onSelect = { label ->
                    uiState.metrics.firstOrNull { it.label == label }?.let {
                        onEvent(
                            ClubAnalysisEvent.SelectMetric(it),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (uiState.adjustedForConditions) {
            item(key = "adjusted") {
                OfText(text = "Adjusted for conditions", role = OfTextRole.Label, color = OfColorTokens.CreamMuted)
            }
        }
        if (uiState.loaded && uiState.bars.isEmpty()) {
            item(key = "empty") {
                OfText(
                    text = "No shots with your bag's clubs yet.",
                    role = OfTextRole.BodySmall,
                    color = OfColorTokens.CreamDim,
                    modifier = Modifier.testTag(BagTestTags.ANALYSIS_EMPTY),
                )
            }
        }
        items(uiState.bars, key = { it.wireValue }) { bar -> BarRow(bar, onClick = { onOpenClub(bar.wireValue) }) }
        if (showInsights) item(key = "insights") { InsightCard(uiState) }
    }
}

@Composable
private fun InsightList(
    uiState: ClubAnalysisUiState,
    modifier: Modifier,
) {
    Column(modifier.padding(OfSpacing.Xl)) { InsightCard(uiState) }
}

@Composable
private fun InsightCard(uiState: ClubAnalysisUiState) {
    OfCard(modifier = Modifier.fillMaxWidth()) {
        OfText(text = "Gapping", role = OfTextRole.TitleSmall)
        if (uiState.insightTexts.isEmpty()) {
            OfText(text = "No gapping issues found.", role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
        }
        uiState.insightTexts.forEach { text ->
            OfText(text = text, role = OfTextRole.BodySmall, modifier = Modifier.testTag(BagTestTags.INSIGHT))
        }
        OfText(text = BagCopy.HEURISTIC_NOTE, role = OfTextRole.Label, color = OfColorTokens.CreamMuted)
        OfText(text = BagCopy.EXCLUDES_IMPORTED, role = OfTextRole.Label, color = OfColorTokens.CreamMuted)
    }
}

@Composable
private fun BarRow(
    bar: ClubBarRow,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(BagTestTags.bar(bar.wireValue))
                .clickable(role = Role.Button, onClickLabel = "Open club detail", onClick = onClick)
                .clearAndSetSemantics { contentDescription = bar.accessibilityLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
    ) {
        ClubBadge(bar.shortLabel, bar.colorIndex)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OfText(text = bar.name, role = OfTextRole.BodySmall, modifier = Modifier.weight(1f))
                OfText(text = bar.valueLabel, role = OfTextRole.TitleSmall, color = OfColorTokens.Gold)
                if (bar.estimated) EstimatedBadge()
            }
            Box(
                Modifier.fillMaxWidth().height(BAR_HEIGHT).background(OfColorTokens.BgHover, RoundedCornerShape(4.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(bar.fraction.toFloat())
                        .height(BAR_HEIGHT)
                        .background(OfClubPalette.color(bar.colorIndex), RoundedCornerShape(4.dp)),
                )
            }
            OfText(
                text = listOfNotNull(bar.plusMinusLabel, bar.shotCountLabel).joinToString(" · "),
                role = OfTextRole.Label,
                color = OfColorTokens.CreamMuted,
            )
        }
    }
}

private val BAR_HEIGHT = 12.dp
private const val BARS_WEIGHT = 0.6f

@Suppress("MagicNumber") // Preview fixture.
private fun previewAnalysis() =
    ClubAnalysisUiState(
        loaded = true,
        bars =
            listOf(GolfClub.DRIVER to 250.0, GolfClub.IRON_7 to 160.0, GolfClub.IRON_8 to 155.0).mapIndexed {
                index,
                (club, yards),
                ->
                ClubBarRow(
                    club,
                    club.wireValue,
                    club.displayName,
                    club.shortLabel,
                    yards,
                    "${yards.toInt()} yds",
                    "± 5 yds",
                    "8 shots",
                    yards / 250.0,
                    false,
                    index,
                )
            },
        insightTexts = listOf("7-Iron and 8-Iron are only 5 yds apart. Consider a different loft or dropping one."),
    )

@Preview(widthDp = 400, heightDp = 800)
@Composable
private fun ClubAnalysisPreview() {
    OfTheme { ClubAnalysisScreen(previewAnalysis(), {}, {}, {}, windowClass = OfWindowClass.COMPACT) }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun ClubAnalysisExpandedPreview() {
    OfTheme { ClubAnalysisScreen(previewAnalysis(), {}, {}, {}, windowClass = OfWindowClass.EXPANDED) }
}
