// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.RangeShowSetting
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.data.ViewingProfile
import dev.openflight.companion.core.insights.UnitSystem

/** Test tags, mirroring the reference's accessibility identifiers where it has them. */
@Suppress("TooManyFunctions") // One tag builder per parameterized control.
object RangeTestTags {
    const val EXIT = "range.exit"
    const val REPLAY = "range.replay"
    const val BALL_SPEED = "range.ballSpeed"
    const val CARRY = "range.carry"
    const val CLUB_SELECTOR = "range.clubSelector"
    const val CLUB_ERROR = "range.clubError"
    const val ESTIMATED = "range.estimated"
    const val READY_CARD = "range.readyCard"
    const val STATUS = "range.status"
    const val SCENE = "range.scene"
    const val CAMERA_MODE = "range.cameraMode"

    /** The full detail-metrics panel (club selector and the seven detail metrics). */
    const val METRICS_DETAIL = "range.metricsDetail"

    /** The one-line strip the detail metrics fold into while a ball flies and lands (plan R7b). */
    const val METRICS_COMPACT = "range.metricsCompact"

    // region Plan F8a1: replay, overlay and view controls.

    /** Opens the session picker. */
    const val HISTORY = "range.history"
    const val SESSION_SHEET = "range.sessionSheet"
    const val SESSIONS_EMPTY = "range.sessionsEmpty"
    const val OVERLAY_ALL = "range.overlayAll"

    /** The replay transport bar, or the overlay's bar. */
    const val TRANSPORT = "range.transport"
    const val LIVE = "range.live"
    const val PREVIOUS = "range.previous"
    const val PLAY_PAUSE = "range.playPause"
    const val NEXT = "range.next"
    const val POSITION = "range.position"

    /** The overlay's "Replay session" button. */
    const val REPLAY_SESSION = "range.replaySession"
    const val OVERLAY_TRUNCATED = "range.overlayTruncated"

    /** "New shot. Return to live." */
    const val NEW_LIVE_SHOT = "range.newLiveShot"

    /** Shown while the user has zoomed, panned or orbited; resets like a double tap. */
    const val RESET_VIEW = "range.resetView"

    /** The side pane's shot list (expanded windows). */
    const val SHOT_LIST = "range.shotList"

    /** The estimated roll-out's total label ("est. 285"). */
    const val ROLL_OUT = "range.rollOut"

    fun replaySession(id: String): String = "range.session.$id.replay"

    fun overlaySession(id: String): String = "range.session.$id.overlay"

    fun speed(speed: ReplaySpeed): String = "range.speed.${speed.name}"

    fun overlayClub(club: String?): String = "range.overlayClub.${club ?: "all"}"

    fun shot(id: String): String = "range.shot.$id"

    // endregion

    /** The side panel the metrics dock into on an expanded window in landscape (plan F1b). */
    const val METRICS_DOCK = "range.metricsDock"

    // region Plan F8d: range everywhere.

    /** "Simulate shot": only on a `--mock` Pi over Wi-Fi ([DrivingRangeUiState.canSimulate]). */
    const val SIMULATE = "range.simulate"

    /** Why the last simulate request failed. */
    const val SIMULATE_ERROR = "range.simulateError"

    // endregion

    /** Plan F8a2t: the Settings "Shot trail" preview, drawn by the range's own renderer. */
    const val TRAIL_PREVIEW = "range.trailPreview"

    // region Plan F8f: range quick settings.

    /** The gear button in the controls row. */
    const val QUICK_SETTINGS = "range.quickSettings"

    /** The panel: a sheet over the scene on compact windows, a side panel on expanded ones. */
    const val QUICK_SETTINGS_PANEL = "range.quickSettings.panel"

    /** The side panel's close button (expanded windows). */
    const val QUICK_SETTINGS_CLOSE = "range.quickSettings.close"

    const val QUICK_SHOW_TOTAL = "range.quickSettings.showTotal"
    const val QUICK_RESET_VIEW = "range.quickSettings.resetView"

    fun quickShow(show: RangeShowSetting): String = "range.quickSettings.show.${show.storageValue}"

    fun quickClub(club: String?): String = "range.quickSettings.club.${club ?: "all"}"

    fun quickTrail(style: ShotTrailStyle): String = "range.quickSettings.trail.${style.storageValue}"

    fun quickKeepLast(count: Int): String = "range.quickSettings.keepLast.$count"

    fun quickLandingEffect(effect: LandingEffect): String = "range.quickSettings.landing.${effect.storageValue}"

    fun quickTheme(theme: RangeThemeSetting): String = "range.quickSettings.theme.${theme.storageValue}"

    fun quickCamera(mode: RangeCameraMode): String = "range.quickSettings.camera.${mode.storageValue}"

    fun quickUnits(units: UnitSystem): String = "range.quickSettings.units.${units.name.lowercase()}"

    /** "Viewing profile": `…profile.follow_active`, `…profile.all_profiles`, `…profile.profile:<id>`. */
    fun quickViewingProfile(profile: ViewingProfile): String = "range.quickSettings.profile.${profile.storageValue}"

    // endregion
}
