// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import dev.openflight.companion.core.model.pi.ShotProcessingState

/**
 * What the Pi is doing with the last swing (plan R8f): the `shot_processing` states from the
 * rolling-buffer monitor, worded like the web UI's `ShotProcessingArea.tsx`. There is no
 * "complete" state; the next shot clears it. The haptic and gold flash stay on the shot itself.
 *
 * @property failed the Pi gave up on the capture: the UI shows a warning icon instead of a spinner.
 */
data class ProcessingIndicator(
    val state: ShotProcessingState,
    val title: String,
    val detail: String,
) {
    val failed: Boolean get() = state == ShotProcessingState.FAILED

    companion object {
        /** `null` while nothing is being processed. */
        fun of(state: ShotProcessingState?): ProcessingIndicator? =
            when (state) {
                null -> {
                    null
                }

                ShotProcessingState.CAPTURING -> {
                    ProcessingIndicator(state, "Impact detected", "Capturing radar data…")
                }

                ShotProcessingState.CALCULATING -> {
                    ProcessingIndicator(state, "Shot captured", "Calculating metrics…")
                }

                ShotProcessingState.FAILED -> {
                    ProcessingIndicator(
                        state,
                        "Shot not measured",
                        "The Pi couldn't read that swing. Hit again when ready.",
                    )
                }
            }
    }
}
