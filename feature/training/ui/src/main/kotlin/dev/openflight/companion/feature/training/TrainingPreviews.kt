// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.training

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.insights.SwingSpeedStats
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.pi.PiFeatureAvailability

@Suppress("MagicNumber") // Sample data.
internal fun previewTrainingState(
    availability: PiFeatureAvailability = PiFeatureAvailability.Available,
    stats: SwingSpeedStats =
        SwingSpeedStats(count = 3, lastSpeedMph = 104.2, bestSpeedMph = 108.9, avgSpeedMph = 105.1),
    error: String? = null,
): TrainingUiState =
    TrainingUiState(
        availability = availability,
        units = UnitSystem.IMPERIAL,
        implementGroups = TrainingImplements.groups,
        selectedImplement = TrainingImplements.default,
        triggerMode = "swing-speed",
        isSwingSpeedMode = true,
        playerName = "Player 1",
        stats = stats,
        lastRep = SwingRep(104.2, "Driver", 12, 40.0, 250.0, "Player 1"),
        error = error,
        showSimulateSwing = true,
    )

@Preview
@Composable
private fun TrainingScreenPreview() {
    OfTheme { TrainingScreen(uiState = previewTrainingState(), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun TrainingOfflinePreview() {
    OfTheme {
        TrainingScreen(
            uiState =
                previewTrainingState(
                    availability = PiFeatureAvailability.Unavailable(PiFeatureAvailability.REQUIRES_WIFI),
                    stats = SwingSpeedStats.EMPTY,
                    error = "Unknown training implement",
                ),
            onEvent = {},
            onBack = {},
        )
    }
}
