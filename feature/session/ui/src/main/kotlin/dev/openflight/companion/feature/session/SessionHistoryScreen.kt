// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfListDetailPane
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfPill
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextButton
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.designsystem.rememberOfWindowClass
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The session history destination (plan R8h): owns the [SessionHistoryViewModel].
 *
 * @param windowClass injectable for tests and previews; defaults to [rememberOfWindowClass]. On an
 *   [OfWindowClass.EXPANDED] window a tapped session opens in a docked detail pane beside the list
 *   ([OfListDetailPane], plan F1b) instead of being pushed as its own destination; [onOpenSession]
 *   is only used on narrower windows.
 */
@Composable
fun SessionHistoryRoute(
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    onShareCsv: (csv: String, filename: String) -> Unit,
    viewModel: SessionHistoryViewModel = koinViewModel(),
    windowClass: OfWindowClass = rememberOfWindowClass(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    if (windowClass == OfWindowClass.EXPANDED) {
        var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
        OfListDetailPane(
            hasSelection = false,
            windowClass = windowClass,
            list = {
                SessionHistoryScreen(
                    uiState = uiState,
                    onEvent = viewModel::onEvent,
                    onBack = onBack,
                    onOpenSession = { selectedSessionId = it },
                )
            },
            detail = {
                val sessionId = selectedSessionId
                if (sessionId == null) {
                    SessionHistoryDetailPlaceholder()
                } else {
                    SessionHistoryDetailPane(
                        sessionId = sessionId,
                        onClose = { selectedSessionId = null },
                        onShareCsv = onShareCsv,
                    )
                }
            },
        )
    } else {
        SessionHistoryScreen(
            uiState = uiState,
            onEvent = viewModel::onEvent,
            onBack = onBack,
            onOpenSession = onOpenSession,
        )
    }
}

/**
 * The docked detail pane's content on an expanded window (plan F1b): its own
 * [SessionHistoryDetailViewModel], a lightweight header (title and Close, instead of the pushed
 * screen's own [OfTopBar]) and [SessionHistoryDetailBody].
 */
@Composable
private fun SessionHistoryDetailPane(
    sessionId: String,
    onClose: () -> Unit,
    onShareCsv: (csv: String, filename: String) -> Unit,
    viewModel: SessionHistoryDetailViewModel = koinViewModel(key = "history:$sessionId") { parametersOf(sessionId) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val share by rememberUpdatedState(onShareCsv)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect -> if (effect is SessionEffect.CsvReady) share(effect.csv, effect.filename) }
    }
    Column(modifier = Modifier.fillMaxSize().testTag(SessionHistoryTestTags.DETAIL_PANE)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = OfSpacing.Xl, vertical = OfSpacing.Sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OfText(
                text = uiState.title.ifEmpty { "Session" },
                role = OfTextRole.Title,
                modifier = Modifier.testTag(SessionHistoryTestTags.DETAIL_TITLE),
            )
            OfTextButton(
                text = "Close",
                onClick = onClose,
                modifier = Modifier.testTag(SessionHistoryTestTags.DETAIL_BACK),
            )
        }
        SessionHistoryDetailBody(uiState = uiState, onEvent = viewModel::onEvent, modifier = Modifier.fillMaxSize())
    }
}

/** Nothing selected yet in the docked detail pane (plan F1b): a centered hint. */
@Composable
private fun SessionHistoryDetailPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().padding(OfSpacing.Xl).testTag(SessionHistoryTestTags.DETAIL_PANE),
        contentAlignment = Alignment.Center,
    ) {
        OfText(
            text = "Select a session to see its details.",
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(SessionHistoryTestTags.DETAIL_EMPTY),
        )
    }
}

/**
 * Every stored session, newest first: day, time range, shot count and how it was recorded, with
 * the current one badged. "Clear all history" asks first and shows its progress (plan R8f).
 */
@Composable
fun SessionHistoryScreen(
    uiState: SessionHistoryUiState,
    onEvent: (SessionHistoryEvent) -> Unit,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OfScaffold(
        modifier = modifier,
        topBar = {
            OfTopBar(
                title = "History",
                eyebrow = "OPENFLIGHT",
                actions = {
                    OfOutlinedButton(
                        text = "Done",
                        onClick = onBack,
                        modifier = Modifier.padding(end = OfSpacing.Sm).testTag(SessionHistoryTestTags.DONE),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag(SessionHistoryTestTags.LIST),
            contentPadding = PaddingValues(horizontal = OfSpacing.Xl, vertical = OfSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(OfSpacing.Md),
        ) {
            if (uiState.action !is SessionActionState.Idle) {
                item(key = "action") {
                    SessionActionPanel(
                        state = uiState.action,
                        onRetry = { onEvent(SessionHistoryEvent.RetryAction) },
                        onDismiss = { onEvent(SessionHistoryEvent.DismissAction) },
                    )
                }
            }
            if (!uiState.isPersistent) {
                item(key = "notPersistent") {
                    OfText(
                        text =
                            "History can't be saved on this device right now; " +
                                "these sessions last until the app closes.",
                        role = OfTextRole.BodySmall,
                        color = OfColorTokens.Warning,
                        modifier = Modifier.testTag(SessionHistoryTestTags.NOT_PERSISTENT),
                    )
                }
            }
            if (uiState.loaded && uiState.sessions.isEmpty()) {
                item(key = "empty") { EmptyHistory() }
            }
            items(uiState.sessions, key = { it.id }) { session ->
                SessionHistoryRowItem(session, onClick = { onOpenSession(session.id) })
            }
            if (uiState.sessions.isNotEmpty()) {
                item(key = "clearAll") {
                    OfOutlinedButton(
                        text = "Clear all history",
                        onClick = { onEvent(SessionHistoryEvent.ClearAll) },
                        enabled = !uiState.action.isBusy,
                        modifier = Modifier.fillMaxWidth().testTag(SessionHistoryTestTags.CLEAR_ALL),
                    )
                }
            }
        }
    }
    SessionActionDialog(
        state = uiState.action,
        onConfirm = { onEvent(SessionHistoryEvent.ConfirmAction) },
        onCancel = { onEvent(SessionHistoryEvent.CancelAction) },
    )
}

/** One stored session, read by TalkBack as one sentence ([SessionHistoryRow.accessibilityLabel]). */
@Composable
private fun SessionHistoryRowItem(
    session: SessionHistoryRow,
    onClick: () -> Unit,
) {
    OfCard(
        modifier =
            Modifier
                .fillMaxWidth()
                // Outside the clearing modifier below, which drops every semantics modifier after it.
                .testTag(SessionHistoryTestTags.session(session.id))
                .clickable(role = Role.Button, onClickLabel = "Open session", onClick = onClick)
                .clearAndSetSemantics { contentDescription = session.accessibilityLabel },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OfSpacing.Md),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OfText(text = session.date, role = OfTextRole.TitleSmall)
                OfText(text = session.detailLine, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs)) {
                OfText(text = session.shotCountLabel, role = OfTextRole.TitleSmall, color = OfColorTokens.Gold)
                if (session.isCurrent) CurrentBadge()
            }
        }
    }
}

/** "Current": a labelled pill, the same on the list and the detail (and on iOS). */
@Composable
internal fun CurrentBadge(modifier: Modifier = Modifier) {
    OfPill(
        label = SessionHistoryRow.CURRENT_LABEL,
        tone = StatusTone.Positive,
        modifier = modifier.testTag(SessionHistoryTestTags.CURRENT),
    )
}

@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).testTag(SessionHistoryTestTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm, Alignment.CenterVertically),
    ) {
        OfText(text = "No sessions yet", role = OfTextRole.Title, textAlign = TextAlign.Center)
        OfText(
            text = "Every connection to the Pi starts a session; its shots are kept here.",
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview
@Composable
private fun SessionHistoryPreview() {
    OfTheme {
        SessionHistoryScreen(
            uiState =
                SessionHistoryUiState(
                    loaded = true,
                    sessions =
                        listOf(
                            SessionHistoryRow(
                                "a",
                                "Thu 25 Sep",
                                "10:03 – 10:45",
                                24,
                                "Wi-Fi",
                                isCurrent = true,
                                host = "raspberrypi.local:8080",
                            ),
                            SessionHistoryRow("b", "Sun 21 Sep", "18:12", 1, "Bluetooth", isCurrent = false),
                        ),
                ),
            onEvent = {},
            onBack = {},
            onOpenSession = {},
        )
    }
}
