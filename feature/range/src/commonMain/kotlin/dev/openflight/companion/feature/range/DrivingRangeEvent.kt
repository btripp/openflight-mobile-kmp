// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.RangeShowSetting
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.data.ViewingProfile
import dev.openflight.companion.core.insights.UnitSystem
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

    // region Plan F8a1: replay, overlay and view controls.

    /** Replay session [sessionId] from shot [index] (0 = its first shot), playing. */
    data class StartReplay(
        val sessionId: String,
        val index: Int = 0,
    ) : DrivingRangeEvent

    /** Overlay session [sessionId]'s shots, or every session's when `null`, optionally only [club]'s. */
    data class StartOverlay(
        val sessionId: String?,
        val club: String? = null,
    ) : DrivingRangeEvent

    /** Filter the overlay to [club] (a wire value), or show every club with `null`. */
    data class SetOverlayClub(
        val club: String?,
    ) : DrivingRangeEvent

    /** Back to the live range: the "New shot. Return to live" chip, or the Live button. */
    data object ReturnToLive : DrivingRangeEvent

    data object PlayPause : DrivingRangeEvent

    data object NextShot : DrivingRangeEvent

    data object PreviousShot : DrivingRangeEvent

    data class SetSpeed(
        val speed: ReplaySpeed,
    ) : DrivingRangeEvent

    /** A row in the shot list, or a tapped landing: replay jumps to it, overlay highlights it. */
    data class SelectShot(
        val shotId: String,
    ) : DrivingRangeEvent

    /** A gesture changed the view: the new transform, already clamped ([ViewTransform]). */
    data class ViewChanged(
        val view: ViewTransform,
    ) : DrivingRangeEvent

    /** Double tap: back to the default view, which resumes the follow camera. */
    data object ResetView : DrivingRangeEvent

    // endregion

    // region Plan F8d: range everywhere.

    /** Open [launch]'s session, paused on its shot when it names one ("View on range"). */
    data class Launch(
        val launch: RangeLaunch,
    ) : DrivingRangeEvent

    /**
     * Ask a `--mock` Pi for a simulated shot (only while [DrivingRangeUiState.canSimulate]). The
     * shot arrives through the normal live path; nothing is made up locally.
     */
    data object SimulateShot : DrivingRangeEvent

    /**
     * Tester request 2026-09-30: open or close the shot table ([DrivingRangeUiState.table]). A
     * row opens its shot with [Launch].
     */
    data class ShowTable(
        val open: Boolean,
    ) : DrivingRangeEvent

    // endregion

    // region Plan F8f: range quick settings. Each persists through the same SettingsRepository key
    // Settings › Practice uses, and the settings flows bring it back into the state.

    /**
     * "Show": live only, the current session's newest N or all of it, or every session, as the
     * overlay (keeping its club filter). Persisted, and restored when the range next opens.
     */
    data class SetShow(
        val show: RangeShowSetting,
    ) : DrivingRangeEvent

    data class SetTrailStyle(
        val style: ShotTrailStyle,
    ) : DrivingRangeEvent

    /** "Keep last shots": one of `SHOT_TRAIL_KEEP_OPTIONS`. */
    data class SetTrailKeepLast(
        val count: Int,
    ) : DrivingRangeEvent

    data class SetLandingEffect(
        val effect: LandingEffect,
    ) : DrivingRangeEvent

    data class SetTheme(
        val theme: RangeThemeSetting,
    ) : DrivingRangeEvent

    /** FIXED or FOLLOW (ignored while reduced motion fixes the camera). */
    data class SetCameraMode(
        val mode: RangeCameraMode,
    ) : DrivingRangeEvent

    data class SetUnits(
        val units: UnitSystem,
    ) : DrivingRangeEvent

    /** "Show total + roll (est.)". */
    data class SetShowTotal(
        val show: Boolean,
    ) : DrivingRangeEvent

    /** "Viewing profile": this device's choice only; the Pi's active profile never changes. */
    data class SetViewingProfile(
        val profile: ViewingProfile,
    ) : DrivingRangeEvent

    // endregion
}
