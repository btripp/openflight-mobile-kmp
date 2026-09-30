// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.DEFAULT_SHOT_TRAIL_KEEP_LAST
import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.model.ClubMenu
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.ShotMetricFormatter
import dev.openflight.companion.core.model.pi.PiBatteryWarning

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
    /** Replay speed (plan F8a1): the animation takes [playbackSeconds] / [speed]. 1 for live shots. */
    val speed: Double = 1.0,
    /** Plan F8a2t: the flight's (resolved) launch spin, for the spin-ribbon trail; `null` if unknown. */
    val spinRpm: Double? = null,
    /** Plan F8a2t: the shot's club as a club palette index, for the club-colour trail. */
    val clubColorIndex: Int = 0,
)

/**
 * The "NEXT CLUB" selector in the overlay (ContentView.swift:124-131 passes these to
 * `DrivingRangeView`).
 *
 * @property menu issue #15: what the picker lists, the active bag's clubs first (always including
 *   [selected]) and the rest under "All clubs"; all 20 flat without a bag.
 * @property selectionEnabled the transport is connected (ContentView.swift:128).
 * @property isChanging a `set_club` request is in flight.
 * @property error the last club request's failure.
 */
data class RangeClubState(
    val selected: GolfClub = SettingsRepository.DEFAULT_CLUB,
    val selectionEnabled: Boolean = false,
    val isChanging: Boolean = false,
    val error: String? = null,
    val menu: ClubMenu = ClubMenu.ALL,
)

/**
 * The range's virtual camera (plan R7a).
 *
 * @property mode the camera to render with: the user's choice, or [RangeCameraMode.FIXED] while
 *   reduced motion is on. Renderers get the pose for it from [RangeCameraRig].
 * @property locked reduced motion is on, so the camera is fixed and the toggle is disabled.
 * @property theme plan F8a2a: the look the renderers paint the scene with, the persisted
 *   [SettingsRepository.rangeTheme].
 * @property trail plan F8a2t: how the shot's trail is drawn.
 * @property numbers plan F8f: the units and whether the estimated total shows.
 */
data class RangeCameraState(
    val mode: RangeCameraMode = SettingsRepository.DEFAULT_RANGE_CAMERA_MODE,
    val locked: Boolean = false,
    val theme: RangeTheme = RangeTheme.DAY,
    val trail: RangeTrailState = RangeTrailState(),
    val numbers: RangeNumbers = RangeNumbers(),
)

/**
 * The shot trail (plan F8a2t): the persisted [SettingsRepository.shotTrail],
 * [SettingsRepository.shotTrailKeepLast] and [SettingsRepository.landingEffect], plus the earlier
 * live flights to keep faded on the range, newest first: at most [keepLast] of them, and only in
 * [RangeMode.Live] (replay and the overlay draw none).
 */
data class RangeTrailState(
    val style: ShotTrailStyle = ShotTrailStyle.DEFAULT,
    val keepLast: Int = DEFAULT_SHOT_TRAIL_KEEP_LAST,
    val landingEffect: LandingEffect = LandingEffect.DEFAULT,
    val priorFlights: List<ActiveFlight> = emptyList(),
)

/** What the range renders. */
sealed interface DrivingRangeUiState {
    val phase: RangePhase
    val displayedShot: ShotEvent?
    val activeFlight: ActiveFlight?
    val club: RangeClubState
    val camera: RangeCameraState

    /** Replay, overlay and the user's view (plan F8a1); [RangeMode.Live] by default. */
    val browse: RangeBrowseState

    /** The displayed shot's estimated roll-out (plan F2/F8a1), once computed. */
    val rollOut: RangeRollOut?

    /**
     * Plan F8d: the Simulate button. `true` only while the Pi's Socket.IO link is connected over
     * Wi-Fi and it runs `--mock` (the same availability as Session's and Games' simulate).
     */
    val canSimulate: Boolean

    /** Why the last [DrivingRangeEvent.SimulateShot] failed; cleared by the next one. */
    val simulateError: String?

    /** Issue #48: the Pi's battery is low or critical and nothing is charging it, or `null`. */
    val batteryWarning: PiBatteryWarning?

    val mode: RangeMode get() = browse.mode

    /** The camera to render with; see [RangeCameraState.mode]. */
    val cameraMode: RangeCameraMode get() = camera.mode

    /** Reduced motion has fixed the camera; see [RangeCameraState.locked]. */
    val cameraModeLocked: Boolean get() = camera.locked

    /** No shot yet: the "Driving Range Ready" card. */
    data class Ready(
        override val club: RangeClubState = RangeClubState(),
        override val camera: RangeCameraState = RangeCameraState(),
        override val browse: RangeBrowseState = RangeBrowseState(),
        override val canSimulate: Boolean = false,
        override val simulateError: String? = null,
        override val batteryWarning: PiBatteryWarning? = null,
    ) : DrivingRangeUiState {
        /** The pre-F8d shape, kept so Swift callers of `init(club:camera:browse:)` still compile. */
        constructor(
            club: RangeClubState,
            camera: RangeCameraState,
            browse: RangeBrowseState,
        ) : this(club, camera, browse, canSimulate = false, simulateError = null)

        override val rollOut: RangeRollOut? get() = null
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
        override val browse: RangeBrowseState = RangeBrowseState(),
        override val rollOut: RangeRollOut? = null,
        override val canSimulate: Boolean = false,
        override val simulateError: String? = null,
        override val batteryWarning: PiBatteryWarning? = null,
    ) : DrivingRangeUiState {
        /** The pre-F8d shape, kept so Swift callers of the seven-argument init still compile. */
        @Suppress("LongParameterList") // The primary constructor's shape before plan F8d.
        constructor(
            shot: ShotEvent,
            phase: RangePhase,
            activeFlight: ActiveFlight?,
            club: RangeClubState,
            camera: RangeCameraState,
            browse: RangeBrowseState,
            rollOut: RangeRollOut?,
        ) : this(shot, phase, activeFlight, club, camera, browse, rollOut, canSimulate = false, simulateError = null)

        override val displayedShot: ShotEvent get() = shot
    }
}

/** The replay button shows once there's a shot and nothing is being prepared or flown (DrivingRangeView.swift). */
val DrivingRangeUiState.canReplay: Boolean
    get() = displayedShot != null && phase != RangePhase.Preparing && phase != RangePhase.Flying

/** The "Estimated flight uses club defaults" badge (RangeMetricsOverlay.swift). */
val DrivingRangeUiState.usesEstimatedFlight: Boolean
    get() = activeFlight?.trajectory?.provenance?.usesEstimatedFlight == true

/**
 * Plan R7b: while the ball is in the air and through the landing dwell, the overlay folds its
 * detail metrics (the club selector, club speed, smash, launch, direction, spin, path and spin
 * axis) into one strip, [compactMetricsSummary], so the lower half of the scene, where the ball
 * lands, stays visible. The full panel comes back once the range is waiting (or preparing) again.
 */
val DrivingRangeUiState.compactMetrics: Boolean
    get() = phase == RangePhase.Flying || phase == RangePhase.Landed

/**
 * Plan F8a2p: how the overlay lays out the detail metrics (the club selector and the seven detail
 * values), the same on both platforms.
 *
 * - [STRIP]: [compactMetrics], one line while the ball flies and through the landing dwell.
 * - [ROW]: one row of small cells, in landscape (the range's pre-F8a2p landscape layout).
 * - [DENSE_GRID]: [columns] small cells a row, two rows, over a portrait scene. The pre-F8a2p
 *   two-column grid took four rows (about a quarter of a phone) while waiting for a shot, leaving
 *   the tee view a thin strip; this is half that.
 * - [GRID]: the roomy two-column grid, in the docked side panel (plan F1b/F1c), where it covers no
 *   scene, and over a portrait scene at large text sizes (issue #80), where four columns truncate
 *   every value.
 */
enum class RangeDetailLayout(
    val columns: Int,
) {
    STRIP(0),
    ROW(DETAIL_CELLS),
    DENSE_GRID(DENSE_COLUMNS),
    GRID(2),
}

/** The eight detail cells: the club selector and seven metrics. */
private const val DETAIL_CELLS = 8
private const val DENSE_COLUMNS = 4

/**
 * Plan F8a2p: the detail metrics' layout for this state: [RangeDetailLayout.STRIP] while
 * [compactMetrics] (in every layout), otherwise [RangeDetailLayout.GRID] in the [docked] side
 * panel, [RangeDetailLayout.ROW] in [landscape] and [RangeDetailLayout.DENSE_GRID] over a portrait
 * scene.
 *
 * Issue #80: with [largeText] (an accessibility Dynamic Type size on iOS, a font scale of 1.5 or
 * more on Android) the grids take half the columns, so the values fit instead of truncating: two
 * rows of four in landscape and the two-column grid over a portrait scene.
 */
fun DrivingRangeUiState.detailLayout(
    landscape: Boolean,
    docked: Boolean,
    largeText: Boolean,
): RangeDetailLayout =
    when {
        compactMetrics -> RangeDetailLayout.STRIP
        docked -> RangeDetailLayout.GRID
        landscape -> if (largeText) RangeDetailLayout.DENSE_GRID else RangeDetailLayout.ROW
        largeText -> RangeDetailLayout.GRID
        else -> RangeDetailLayout.DENSE_GRID
    }

/**
 * The compact strip's one line: "Club 103.2 mph · Launch 12.6° · Spin 2,380 rpm" ("—" when
 * missing), the club speed in the chosen units (plan F8f).
 */
val DrivingRangeUiState.compactMetricsSummary: String
    get() {
        val shot = displayedShot
        val numbers = camera.numbers
        val launch = ShotMetricFormatter.number(shot?.launchAngleVertical, decimals = 1)
        val launchText = if (launch == ShotMetricFormatter.MISSING) launch else "$launch°"
        return listOf(
            "Club ${withUnit(numbers.speed(shot?.clubSpeedMph), numbers.speedUnit)}",
            "Launch $launchText",
            "Spin ${withUnit(ShotMetricFormatter.number(shot?.spinRpm, decimals = 0), "rpm")}",
        ).joinToString(separator = " · ")
    }

private fun withUnit(
    value: String,
    unit: String,
): String = if (value == ShotMetricFormatter.MISSING) value else "$value $unit"
