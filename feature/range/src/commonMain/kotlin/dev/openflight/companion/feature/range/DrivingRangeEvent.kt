// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.model.GolfClub

/** User and scene intents from the range, sent up to [DrivingRangeViewModel.onEvent]. */
sealed interface DrivingRangeEvent {
    /** The replay button: fly the displayed shot again. */
    data object Replay : DrivingRangeEvent

    /** The scene finished animating the active flight. */
    data object FlightCompleted : DrivingRangeEvent

    data class ClubSelected(
        val club: GolfClub,
    ) : DrivingRangeEvent

    /** The overlay's camera button: switch between the follow and the fixed camera (plan R7a). */
    data object ToggleCameraMode : DrivingRangeEvent

    /**
     * The platform's reduced-motion setting, sent by the screen when it opens and whenever it
     * changes. While it is on, the camera is fixed and the toggle is locked.
     */
    data class ReduceMotionChanged(
        val enabled: Boolean,
    ) : DrivingRangeEvent
}
