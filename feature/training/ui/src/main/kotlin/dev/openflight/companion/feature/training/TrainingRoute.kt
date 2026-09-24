// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.training

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

/** The training destination: owns the [TrainingViewModel] and hands [TrainingScreen] its state. */
@Composable
fun TrainingRoute(
    onBack: () -> Unit,
    viewModel: TrainingViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    TrainingScreen(uiState = uiState, onEvent = viewModel::onEvent, onBack = onBack)
}

/** Test tags for the Android training screen. */
object TrainingTestTags {
    const val DONE = "training.done"
    const val AVAILABILITY = "training.availability"
    const val MODE = "training.mode"
    const val ERROR = "training.error"
    const val SIMULATE = "training.simulate"
    const val LAST = "training.stat.last"
    const val BEST = "training.stat.best"
    const val AVERAGE = "training.stat.average"
    const val COUNT = "training.stat.count"

    /** An implement option, by its server key (e.g. `"stack-plus-10"`). */
    fun implement(id: String): String = "training.implement.$id"

    /** A picker group, by its name (e.g. "TheStack"). */
    fun group(name: String): String = "training.group.$name"
}
