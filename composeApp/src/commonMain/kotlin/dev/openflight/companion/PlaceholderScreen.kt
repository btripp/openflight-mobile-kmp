// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTopBar

/** A stand-in destination for a feature that hasn't landed yet. */
@Composable
internal fun PlaceholderScreen(
    title: String,
    onBack: () -> Unit,
) {
    OfScaffold(topBar = { OfTopBar(title = title, eyebrow = "OPENFLIGHT") }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(OfSpacing.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OfSpacing.Lg, Alignment.CenterVertically),
        ) {
            OfText(
                text = "Coming soon",
                role = OfTextRole.Title,
                textAlign = TextAlign.Center,
            )
            OfText(
                text = "$title isn't available in this build yet.",
                role = OfTextRole.BodySmall,
                color = OfColorTokens.CreamDim,
                textAlign = TextAlign.Center,
            )
            OfOutlinedButton(text = "Back", onClick = onBack)
        }
    }
}
