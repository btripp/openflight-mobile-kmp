// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

/** Test tags, mirroring the reference's accessibility identifiers where it has them. */
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
}
