// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.training

/** User intents from the training screen, sent up to [TrainingViewModel.onEvent]. */
sealed interface TrainingEvent {
    /** Picks an implement by its [ImplementOption.id] (`set_training_implement`). */
    data class SelectImplement(
        val id: String,
    ) : TrainingEvent

    /** Asks a `--mock` Pi for a fake rep (`simulate_shot`); see [TrainingUiState.showSimulateSwing]. */
    data object SimulateSwing : TrainingEvent

    /** Clears [TrainingUiState.error]. */
    data object DismissError : TrainingEvent
}
