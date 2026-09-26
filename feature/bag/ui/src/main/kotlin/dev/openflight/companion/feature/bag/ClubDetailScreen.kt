// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfPill
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSegmentedPicker
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.insights.DispersionProjection
import dev.openflight.companion.core.insights.DispersionSample
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.computeDispersionEllipse
import dev.openflight.companion.core.insights.computeDispersionViewport
import dev.openflight.companion.core.insights.distanceUnitLabel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** Club Detail for [wireValue] as a pushed screen (from Club Analysis). */
@Composable
fun ClubDetailRoute(
    wireValue: String,
    onBack: () -> Unit,
    viewModel: ClubDetailViewModel = koinViewModel(key = "club-$wireValue") { parametersOf(wireValue) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ClubDetailScreen(uiState = uiState, onEvent = viewModel::onEvent, onBack = onBack)
}

/** The detail pane's content for [wireValue], owning its own [ClubDetailViewModel]. */
@Composable
fun ClubDetailPane(
    wireValue: String,
    modifier: Modifier = Modifier,
    viewModel: ClubDetailViewModel = koinViewModel(key = "club-$wireValue") { parametersOf(wireValue) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ClubDetailContent(uiState = uiState, onEvent = viewModel::onEvent, modifier = modifier)
}

@Composable
fun ClubDetailScreen(
    uiState: ClubDetailUiState,
    onEvent: (ClubDetailEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OfScaffold(
        modifier = modifier,
        topBar = {
            OfTopBar(
                title = uiState.clubName,
                eyebrow = "CLUB DETAIL",
                actions = {
                    OfOutlinedButton(
                        text = "Done",
                        onClick = onBack,
                        modifier = Modifier.padding(end = OfSpacing.Sm).testTag(BagTestTags.DETAIL_DONE),
                    )
                },
            )
        },
    ) { padding ->
        ClubDetailContent(uiState = uiState, onEvent = onEvent, modifier = Modifier.padding(padding))
    }
}

/** The club's summary, carry histogram, dispersion and recent shots. Stateless. */
@Composable
fun ClubDetailContent(
    uiState: ClubDetailUiState,
    onEvent: (ClubDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(BagTestTags.DETAIL),
        contentPadding = PaddingValues(horizontal = OfSpacing.Xl, vertical = OfSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Md),
    ) {
        item(key = "title") { OfText(text = uiState.clubName, role = OfTextRole.Title) }
        item(key = "window") {
            OfSegmentedPicker(
                options = uiState.windows.map { it.label },
                selected = uiState.window.label,
                onSelect = { label ->
                    uiState.windows.firstOrNull { it.label == label }?.let { onEvent(ClubDetailEvent.SelectWindow(it)) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (uiState.loaded && uiState.shotCount == 0) {
            item(key = "empty") {
                OfText(
                    text = "No shots with this club yet. Hit a few with it selected and they'll show here.",
                    role = OfTextRole.BodySmall,
                    color = OfColorTokens.CreamDim,
                    modifier = Modifier.testTag(BagTestTags.DETAIL_EMPTY),
                )
            }
            return@LazyColumn
        }
        item(key = "summary") { SummaryCard(uiState) }
        if (uiState.histogram.isNotEmpty()) item(key = "histogram") { HistogramCard(uiState.histogram, uiState) }
        uiState.dispersion?.let { dispersion -> item(key = "dispersion") { DispersionCard(dispersion) } }
        if (uiState.recentShots.isNotEmpty()) {
            item(key = "recentHeader") { OfText(text = "Recent shots", role = OfTextRole.TitleSmall) }
            items(uiState.recentShots, key = { it.id }) { RecentShot(it) }
        }
    }
}

@Composable
private fun SummaryCard(uiState: ClubDetailUiState) {
    OfCard(modifier = Modifier.fillMaxWidth().testTag(BagTestTags.DETAIL_SUMMARY)) {
        uiState.summaryLines.forEachIndexed { index, line ->
            OfText(
                text = line,
                role = if (index == 0) OfTextRole.TitleSmall else OfTextRole.BodySmall,
                color = if (index == 0) OfColorTokens.Gold else OfColorTokens.CreamDim,
            )
        }
        uiState.totalLabel?.let { total ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
            ) {
                OfText(text = total, role = OfTextRole.Body)
                EstimatedBadge()
            }
        }
        uiState.excludedLabel?.let { OfText(text = it, role = OfTextRole.BodySmall, color = OfColorTokens.Warning) }
        if (uiState.adjustedForConditions) {
            OfText(text = "Adjusted for conditions", role = OfTextRole.Label, color = OfColorTokens.CreamMuted)
        }
    }
}

@Composable
private fun HistogramCard(
    bins: List<HistogramBin>,
    uiState: ClubDetailUiState,
) {
    OfCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(BagTestTags.HISTOGRAM)
                .clearAndSetSemantics {
                    contentDescription =
                        "Carry distribution: " +
                        bins.filter { it.count > 0 }.joinToString(
                            "; ",
                        ) { "${it.label}, ${BagCopy.shotCount(it.count)}" }
                },
    ) {
        OfText(text = "Carry distribution", role = OfTextRole.TitleSmall)
        Row(
            modifier = Modifier.fillMaxWidth().height(HISTOGRAM_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bins.forEach { bin ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(bin.fraction.toFloat().coerceAtLeast(MIN_BAR_FRACTION))
                            .background(
                                if (bin.count == 0) OfColorTokens.BgHover else OfColorTokens.Gold,
                                RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                            ),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OfText(
                text = bins.first().label.substringBefore('–'),
                role = OfTextRole.Label,
                color = OfColorTokens.CreamMuted,
            )
            OfText(
                text = bins.last().label.substringAfter('–') + " " + distanceUnitLabel(uiState.units),
                role = OfTextRole.Label,
                color = OfColorTokens.CreamMuted,
            )
        }
    }
}

@Composable
private fun RecentShot(row: RecentShotRow) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(BagTestTags.RECENT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
    ) {
        OfText(
            text = row.timeLabel,
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
            modifier = Modifier.weight(1f),
        )
        OfText(text = row.carryLabel, role = OfTextRole.Body, color = OfColorTokens.Gold)
        row.totalLabel?.let {
            OfText(text = it, role = OfTextRole.BodySmall)
            EstimatedBadge()
        }
        row.sideLabel?.let { OfText(text = it, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim) }
        if (row.possibleBadRead) OfPill(label = "Bad read?", tone = StatusTone.Negative)
    }
}

private val HISTOGRAM_HEIGHT = 96.dp
private const val MIN_BAR_FRACTION = 0.04f

@Composable
private fun previewState(): ClubDetailUiState {
    val samples = listOf(DispersionSample(-4.0, 158.0), DispersionSample(3.0, 162.0), DispersionSample(1.0, 160.0))
    val ellipse = computeDispersionEllipse(samples)
    return ClubDetailUiState(
        loaded = true,
        wireValue = "7-iron",
        clubName = "7-Iron",
        shotCount = 3,
        summaryLines =
            listOf(
                "160 yds ± 2 yds carry",
                "Median 160 yds · middle 80% 158–162 yds",
                "Typically on line",
                "3 shots",
            ),
        totalLabel = "172 yds total",
        histogram = carryHistogram(listOf(158.0, 160.0, 162.0), UnitSystem.IMPERIAL),
        dispersion =
            ClubDispersionState(
                samples,
                ellipse,
                computeDispersionViewport(samples, listOfNotNull(ellipse))!!,
                "Dispersion",
            ),
        recentShots = listOf(RecentShotRow(1, "2026-09-25 10:00", "160 yds", "172 yds", "on line", false)),
    )
}

@Preview(widthDp = 400, heightDp = 800)
@Composable
private fun ClubDetailPreview() {
    OfTheme { ClubDetailScreen(uiState = previewState(), onEvent = {}, onBack = {}) }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun ClubDetailExpandedPreview() {
    OfTheme {
        ClubDetailScreen(
            uiState = previewState(),
            onEvent = {},
            onBack = {},
            modifier = Modifier.width(1280.dp),
        )
    }
}
