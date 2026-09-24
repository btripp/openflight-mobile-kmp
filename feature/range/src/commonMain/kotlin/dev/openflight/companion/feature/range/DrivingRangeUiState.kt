// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent

/**
 * The range's flight phase, ported from `DrivingRangeViewModel.Phase`
 * (ios/OpenFlight/DrivingRange/DrivingRangeViewModel.swift): `Waiting → Preparing → Flying →
 * Landed`, or [Unavailable] when a shot can't be simulated.
 */
sealed interface RangePhase {
    /** The status pill's text. */
    val label: String

    data object Waiting : RangePhase {
        override val label = "Ready for the next shot"
    }

    data object Preparing : RangePhase {
        override val label = "Calculating flight"
    }

    data object Flying : RangePhase {
        override val label = "Ball in flight"
    }

    data object Landed : RangePhase {
        override val label = "Shot complete"
    }

    data class Unavailable(
        val message: String,
    ) : RangePhase {
        override val label = message
    }
}

/**
 * A trajectory to animate. [playbackId] is new for every playback, including a replay of the same
 * shot: `FlightTrajectory.id` is the shot's event id here (the reference's is a fresh UUID per
 * simulation), so it can't tell two playbacks of one shot apart.
 */
data class ActiveFlight(
    val trajectory: FlightTrajectory,
    val playbackId: Long,
)

/**
 * The "NEXT CLUB" selector in the overlay (ContentView.swift:124-131 passes these to
 * `DrivingRangeView`).
 *
 * @property selectionEnabled the transport is connected (ContentView.swift:128).
 * @property isChanging a `set_club` request is in flight.
 * @property error the last club request's failure.
 */
data class RangeClubState(
    val selected: GolfClub = SettingsRepository.DEFAULT_CLUB,
    val selectionEnabled: Boolean = false,
    val isChanging: Boolean = false,
    val error: String? = null,
)

/**
 * The range's virtual camera (plan R7a).
 *
 * @property mode the camera to render with: the user's choice, or [RangeCameraMode.FIXED] while
 *   reduced motion is on. Renderers get the pose for it from [RangeCameraRig].
 * @property locked reduced motion is on, so the camera is fixed and the toggle is disabled.
 */
data class RangeCameraState(
    val mode: RangeCameraMode = SettingsRepository.DEFAULT_RANGE_CAMERA_MODE,
    val locked: Boolean = false,
)

/** What the range renders. */
sealed interface DrivingRangeUiState {
    val phase: RangePhase
    val displayedShot: ShotEvent?
    val activeFlight: ActiveFlight?
    val club: RangeClubState
    val camera: RangeCameraState

    /** The camera to render with; see [RangeCameraState.mode]. */
    val cameraMode: RangeCameraMode get() = camera.mode

    /** Reduced motion has fixed the camera; see [RangeCameraState.locked]. */
    val cameraModeLocked: Boolean get() = camera.locked

    /** No shot yet: the "Driving Range Ready" card. */
    data class Ready(
        override val club: RangeClubState = RangeClubState(),
        override val camera: RangeCameraState = RangeCameraState(),
    ) : DrivingRangeUiState {
        override val phase: RangePhase get() = RangePhase.Waiting
        override val displayedShot: ShotEvent? get() = null
        override val activeFlight: ActiveFlight? get() = null
    }

    /** A shot's metrics, and its flight while [activeFlight] is set. */
    data class Showing(
        val shot: ShotEvent,
        override val phase: RangePhase,
        override val activeFlight: ActiveFlight?,
        override val club: RangeClubState = RangeClubState(),
        override val camera: RangeCameraState = RangeCameraState(),
    ) : DrivingRangeUiState {
        override val displayedShot: ShotEvent get() = shot
    }
}

/** The replay button shows once there's a shot and nothing is being prepared or flown (DrivingRangeView.swift). */
val DrivingRangeUiState.canReplay: Boolean
    get() = displayedShot != null && phase != RangePhase.Preparing && phase != RangePhase.Flying

/** The "Estimated flight uses club defaults" badge (RangeMetricsOverlay.swift). */
val DrivingRangeUiState.usesEstimatedFlight: Boolean
    get() = activeFlight?.trajectory?.provenance?.usesEstimatedFlight == true
