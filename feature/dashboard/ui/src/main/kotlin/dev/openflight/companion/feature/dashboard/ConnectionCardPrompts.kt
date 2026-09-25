// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import dev.openflight.companion.core.designsystem.OfButton
import dev.openflight.companion.core.designsystem.OfChip
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfNotice
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextButton
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.model.ConnectionProblem
import dev.openflight.companion.core.model.GolfClub

// Plan R8d's additions to the dashboard's connection card.

/**
 * Plan R8f: why the Pi can't be reached, in words with an icon, not only the red status dot. A
 * refused address says to fix the address; anything else says Retry may help.
 */
@Composable
internal fun ConnectionProblemNotice(problem: ConnectionProblem) {
    val hint =
        when (problem.kind) {
            ConnectionProblem.Kind.ADDRESS_REJECTED -> "Fix the address above, then press Go."
            ConnectionProblem.Kind.LOCAL_NETWORK_DENIED -> null
            ConnectionProblem.Kind.CONNECTION_FAILED -> "Check the Pi is on and on this network, then Retry."
        }
    OfNotice(
        title = problem.title,
        detail = listOfNotNull(problem.detail, hint).joinToString("\n"),
        tone = StatusTone.Negative,
        modifier = Modifier.testTag(DashboardTestTags.CONNECTION_PROBLEM),
    )
}

/**
 * Plan R8f: what the Pi is doing with the last swing. A spinner (a still ring with animations
 * removed) while capturing or calculating, a warning icon when it failed; announced politely.
 */
@Composable
internal fun ProcessingNotice(processing: ProcessingIndicator) {
    OfNotice(
        title = processing.title,
        detail = processing.detail,
        tone = if (processing.failed) StatusTone.Negative else StatusTone.InProgress,
        busy = !processing.failed,
        modifier = Modifier.testTag(DashboardTestTags.PROCESSING),
    )
}

/** Plan R8d: tap-to-fill host suggestions; they fill the field, Go still connects. */
@Composable
internal fun HostHints(
    hints: List<HostHint>,
    onEvent: (DashboardEvent) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (hint in hints) {
            OfChip(
                label = "${hint.label} ${hint.host}",
                onClick = { onEvent(DashboardEvent.HostHintSelected(hint.host)) },
                modifier = Modifier.testTag(DashboardTestTags.hostHint(hint.host)),
            )
        }
    }
}

/** iOS-only in practice (the flag comes from Darwin's Local Network denial); kept for shared state. */
@Composable
internal fun LocalNetworkDenied() {
    OfText(
        text = "Local network access is off for OpenFlight, so it can't reach your Pi.",
        role = OfTextRole.BodySmall,
        color = OfColorTokens.Danger,
        modifier = Modifier.testTag(DashboardTestTags.LOCAL_NETWORK_DENIED),
    )
}

/** Plan R8d: once per launch, after the first connection, confirm the club shots are filed under. */
@Composable
internal fun ClubConfirmationPrompt(
    club: GolfClub,
    onEvent: (DashboardEvent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(DashboardTestTags.CLUB_CONFIRMATION),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
    ) {
        OfText(text = "Is ${club.displayName} the right club?", role = OfTextRole.TitleSmall)
        OfText(
            text = "The Pi files every shot under this club. Pick another below if it's wrong.",
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
        )
        OfButton(
            text = "Looks right",
            onClick = { onEvent(DashboardEvent.ClubConfirmed) },
            modifier = Modifier.testTag(DashboardTestTags.CLUB_CONFIRM),
        )
    }
}

/** Plan R8d: the build guide before a connection, troubleshooting after a failure (Expo docs link). */
@Composable
internal fun HelpLink(link: ConnectionHelpLink) {
    val uriHandler = LocalUriHandler.current
    OfTextButton(
        text = link.label,
        // Never let a device without a browser break the dashboard.
        onClick = { runCatching { uriHandler.openUri(link.url) } },
        modifier = Modifier.testTag(DashboardTestTags.HELP_LINK),
    )
}
