// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.HistorySession
import dev.openflight.companion.core.flight.FlightTrajectory

/**
 * What the range shows (plan F8a).
 *
 * - [Live]: the phase machine of parent §0.3, one tracer, the R7 follow camera. The default, and
 *   unchanged by replay and overlay.
 * - [Replay]: a stored session's shots flown one at a time, oldest first.
 * - [Overlay]: up to [RangeBrowseState.OVERLAY_CAP] stored shots drawn at once as static
 *   trajectories.
 */
sealed interface RangeMode {
    data object Live : RangeMode

    /** Session [sessionId]'s shots, at [index] (0 = the session's first shot). */
    data class Replay(
        val sessionId: String,
        val index: Int,
    ) : RangeMode

    /**
     * Stored shots drawn at once: session [sessionId]'s, or every session's when it is `null`
     * ("all"); only [club]'s (a wire value, e.g. `"7-iron"`) when set.
     */
    data class Overlay(
        val sessionId: String?,
        val club: String?,
    ) : RangeMode
}

/** Replay playback speed: scales the flight animation and the landing dwell. */
@Suppress("MagicNumber") // The speeds are the values themselves.
enum class ReplaySpeed(
    val factor: Double,
    val label: String,
) {
    HALF(0.5, "0.5×"),
    NORMAL(1.0, "1×"),
    DOUBLE(2.0, "2×"),
}

/**
 * One stored shot in the replay or overlay list (the side pane on tablets).
 *
 * @property id the stored row's id, as a string ([dev.openflight.companion.core.data.HistoryShot.id]).
 * @property number 1-based position in the session (replay) or the list (overlay).
 * @property club the club's wire value; [clubLabel] is its display name.
 * @property carryYards the server's carry, `null` when missing.
 * @property flyable the shot has the ball speed and carry a flight needs.
 */
data class RangeShotItem(
    val id: String,
    val number: Int,
    val club: String,
    val clubLabel: String,
    val carryYards: Double?,
    val ballSpeedMph: Double?,
    val timestamp: String,
    val flyable: Boolean,
)

/** One stored session in the session picker. */
data class RangeSessionOption(
    val id: String,
    val title: String,
    val shotCount: Int,
) {
    companion object {
        /** "2026-09-25 10:03" from the Pi's naive ISO timestamp of the session's first shot. */
        fun of(session: HistorySession): RangeSessionOption =
            RangeSessionOption(
                id = session.id,
                title =
                    session.title?.takeIf { it.isNotBlank() }
                        ?: session.firstShotAt.take(TIMESTAMP_MINUTES_LENGTH).replace('T', ' '),
                shotCount = session.shotCount,
            )

        /** `yyyy-MM-ddTHH:mm`. */
        private const val TIMESTAMP_MINUTES_LENGTH = 16
    }
}

/**
 * A stored shot's trajectory for the overlay, precomputed once off the main thread and downsampled
 * to [RangeBrowseState.OVERLAY_TRAJECTORY_POINTS] points (simulator space, +z downrange).
 *
 * @property colorIndex the club's index among the overlay's clubs (driver first), for
 *   `OfClubPalette.color`.
 */
data class OverlayFlight(
    val shotId: String,
    val club: String,
    val colorIndex: Int,
    val trajectory: FlightTrajectory,
)

/**
 * The F2 estimated roll-out for the displayed shot: a carry dot, a roll segment and a total dot
 * labelled "est." Carry is the server's (or the conditions-adjusted) carry; roll and total are
 * always estimates.
 */
data class RangeRollOut(
    val carryYards: Double,
    val rollYards: Double,
    val totalYards: Double,
    val carryEstimated: Boolean,
) {
    /** The total dot's label: "est. 285". */
    val totalLabel: String get() = "est. ${totalYards.toInt()}"
}

/**
 * Everything the range adds on top of the live phase machine (plan F8a1): the mode, the user's
 * view transform, the session picker, the replay/overlay list and transport, and the "New shot.
 * Return to live" chip.
 *
 * @property shots the replay session's shots oldest first, or the overlay's shots newest first.
 * @property selectedShotId the replayed shot, or the highlighted overlay shot.
 * @property overlayFlights the overlay's precomputed trajectories, newest first.
 * @property overlayTruncated more shots matched than [OVERLAY_CAP]; only the newest are drawn.
 * @property overlayClubs the clubs the overlay can be filtered to, driver first.
 * @property newLiveShot a live shot arrived while replaying or overlaying.
 */
data class RangeBrowseState(
    val mode: RangeMode = RangeMode.Live,
    val view: ViewTransform = ViewTransform.IDENTITY,
    val sessions: List<RangeSessionOption> = emptyList(),
    val shots: List<RangeShotItem> = emptyList(),
    val selectedShotId: String? = null,
    val playing: Boolean = false,
    val speed: ReplaySpeed = ReplaySpeed.NORMAL,
    val loading: Boolean = false,
    val overlayFlights: List<OverlayFlight> = emptyList(),
    val overlayTruncated: Boolean = false,
    val overlayClubs: List<String> = emptyList(),
    val newLiveShot: Boolean = false,
) {
    val isLive: Boolean get() = mode == RangeMode.Live

    /** The follow camera is suspended while the user has zoomed, panned or orbited (plan F8a). */
    val userTransformed: Boolean get() = !view.isIdentity

    companion object {
        /**
         * The overlay draws at most this many shots, newest first. 200 keeps a whole long session
         * and the redraw inside the plan's 50 fps budget on a mid-range device.
         */
        const val OVERLAY_CAP = 200

        /** Points kept per overlay trajectory: smooth at range scale, cheap to re-project. */
        const val OVERLAY_TRAJECTORY_POINTS = 41
    }
}
