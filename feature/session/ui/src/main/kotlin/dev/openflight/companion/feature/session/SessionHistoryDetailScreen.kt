// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * One stored session (plan R8h): owns its [SessionHistoryDetailViewModel] and hands CSV exports to
 * the platform share sheet, like [SessionRoute].
 */
@Composable
fun SessionHistoryDetailRoute(
    sessionId: String,
    onBack: () -> Unit,
    onShareCsv: (csv: String, filename: String) -> Unit,
    viewModel: SessionHistoryDetailViewModel = koinViewModel(key = "history:$sessionId") { parametersOf(sessionId) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val share by rememberUpdatedState(onShareCsv)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is SessionEffect.CsvReady) share(effect.csv, effect.filename)
        }
    }
    SessionHistoryDetailScreen(uiState = uiState, onEvent = viewModel::onEvent, onBack = onBack)
}

/** The live Session screen's tabs, stats and rows over one stored session, plus its CSV export. */
@Composable
fun SessionHistoryDetailScreen(
    uiState: SessionHistoryDetailUiState,
    onEvent: (SessionHistoryDetailEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = uiState.session
    OfScaffold(
        modifier = modifier,
        topBar = {
            OfTopBar(
                title = uiState.title.ifEmpty { "Session" },
                eyebrow = "HISTORY",
                modifier = Modifier.testTag(SessionHistoryTestTags.DETAIL_TITLE),
                actions = {
                    OfOutlinedButton(
                        text = "Back",
                        onClick = onBack,
                        modifier = Modifier.padding(end = OfSpacing.Sm).testTag(SessionHistoryTestTags.DETAIL_BACK),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = OfSpacing.Xl, vertical = OfSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(OfSpacing.Lg),
        ) {
            item(key = "subtitle") {
                OfText(text = uiState.subtitle, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
            }
            if (session.hasShots) {
                item(key = "tabs") { ClubTabs(session) { onEvent(SessionHistoryDetailEvent.SelectClub(it)) } }
                item(key = "stats") { StatsCard(session) }
                item(key = "export") {
                    OfOutlinedButton(
                        text = "Export CSV",
                        onClick = { onEvent(SessionHistoryDetailEvent.ExportCsv) },
                        modifier = Modifier.fillMaxWidth().testTag(SessionHistoryTestTags.DETAIL_EXPORT),
                    )
                }
                item(key = "shotsHeader") {
                    OfText(text = "SHOTS · swipe left to delete", role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
                }
                items(session.shots, key = { it.id }) { shot ->
                    SessionShotRowItem(
                        shot = shot,
                        units = session.units,
                        onDelete = { onEvent(SessionHistoryDetailEvent.DeleteShot(shot.id)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun SessionHistoryDetailPreview() {
    OfTheme {
        SessionHistoryDetailScreen(
            uiState =
                SessionHistoryDetailUiState(
                    loaded = true,
                    title = "2026-09-25",
                    subtitle = "10:03 – 10:45 · 2 shots",
                    session = SessionUiState(allCount = previewRows.size, shots = previewRows),
                ),
            onEvent = {},
            onBack = {},
        )
    }
}
