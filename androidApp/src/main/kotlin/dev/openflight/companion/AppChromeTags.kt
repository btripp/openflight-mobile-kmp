// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole

/** Test tags for the app shell's own chrome. */
object AppChromeTags {
    /** Plan F14: the persistent "Demo" strip while Demo mode is on. */
    const val DEMO_BADGE = "app.demoBadge"
}

/**
 * Plan F14: the persistent Demo badge. While Demo mode is on it sits above every screen, behind the
 * status bar and in the app's accent colour, so no made-up shot is ever mistaken for a measurement,
 * on the range too. It takes the status bar's space itself; the screens below lay out under it.
 */
@Composable
internal fun DemoBanner(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(OfColorTokens.Gold)
                .statusBarsPadding()
                .padding(vertical = 3.dp)
                .semantics { contentDescription = DEMO_BANNER_DESCRIPTION }
                .testTag(AppChromeTags.DEMO_BADGE),
        contentAlignment = Alignment.Center,
    ) {
        OfText(
            text = DEMO_BANNER_TEXT,
            role = OfTextRole.Eyebrow,
            color = OfColorTokens.BgDeep,
            textAlign = TextAlign.Center,
        )
    }
}

private const val DEMO_BANNER_TEXT = "DEMO · MADE-UP SHOTS, NO PI"
private const val DEMO_BANNER_DESCRIPTION = "Demo mode is on. Shots are made up, not measured."
