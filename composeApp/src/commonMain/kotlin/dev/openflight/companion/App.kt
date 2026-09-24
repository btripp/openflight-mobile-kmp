// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfStatusChip
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.designsystem.StatusTone

/**
 * App shell. For now it is a clean themed placeholder; later steps replace it with
 * the nav host and the DI graph, wired up through [dev.openflight.companion.core.designsystem]
 * wrappers throughout (invariant 5: no raw Material3 outside `core:designsystem`).
 *
 * Set [SHOW_DESIGN_SYSTEM_GALLERY] to `true` locally to preview every design-system
 * component on-device instead; never commit it `true`.
 */
@Composable
@Preview
fun App() {
    if (SHOW_DESIGN_SYSTEM_GALLERY) {
        DesignSystemGallery()
        return
    }
    OfTheme {
        OfScaffold(topBar = { OfTopBar(title = AppInfo.TITLE, eyebrow = "OPENFLIGHT") }) { padding ->
            val platform = remember { platformName() }
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .safeContentPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                OfCard {
                    OfStatusChip(
                        label = "Ready",
                        tone = StatusTone.Neutral,
                        detail = AppInfo.subtitle(platform),
                    )
                }
            }
        }
    }
}
