// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfConfirmDialog
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import org.koin.androidx.compose.koinViewModel

/** The session history destination (plan R8h): owns the [SessionHistoryViewModel]. */
@Composable
fun SessionHistoryRoute(
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    viewModel: SessionHistoryViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SessionHistoryScreen(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onOpenSession = onOpenSession,
    )
}

/** Every stored session, newest first: date, shot count and first/last shot time. */
@Composable
fun SessionHistoryScreen(
    uiState: SessionHistoryUiState,
    onEvent: (SessionHistoryEvent) -> Unit,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
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
                        onClick = { confirmingClear = true },
                        modifier = Modifier.fillMaxWidth().testTag(SessionHistoryTestTags.CLEAR_ALL),
                    )
                }
            }
        }
    }
    if (confirmingClear) {
        OfConfirmDialog(
            title = "Clear all history?",
            message = "Every stored session is deleted from this phone. The Pi's session is not affected.",
            confirmLabel = "Clear all",
            destructive = true,
            onConfirm = {
                confirmingClear = false
                onEvent(SessionHistoryEvent.ClearAll)
            },
            onDismiss = { confirmingClear = false },
            confirmTag = SessionHistoryTestTags.CLEAR_ALL_CONFIRM,
            dismissTag = SessionHistoryTestTags.CLEAR_ALL_CANCEL,
        )
    }
}

@Composable
private fun SessionHistoryRowItem(
    session: SessionHistoryRow,
    onClick: () -> Unit,
) {
    OfCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Open session", onClick = onClick)
                .testTag(SessionHistoryTestTags.session(session.id)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OfSpacing.Md),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OfText(text = session.date, role = OfTextRole.TitleSmall)
                OfText(
                    text = listOfNotNull(session.timeRange, session.transportLabel).joinToString(" · "),
                    role = OfTextRole.BodySmall,
                    color = OfColorTokens.CreamDim,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                OfText(text = session.shotCountLabel, role = OfTextRole.TitleSmall, color = OfColorTokens.Gold)
                if (session.isCurrent) {
                    OfText(text = "Current", role = OfTextRole.Label, color = OfColorTokens.CreamDim)
                }
            }
        }
    }
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
                            SessionHistoryRow("a", "2026-09-25", "10:03 – 10:45", 24, "Wi-Fi", isCurrent = true),
                            SessionHistoryRow("b", "2026-09-21", "18:12", 1, "Bluetooth", isCurrent = false),
                        ),
                ),
            onEvent = {},
            onBack = {},
            onOpenSession = {},
        )
    }
}
