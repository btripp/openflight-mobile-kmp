// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.insights.UnitSystem

/*
 * The display names of the range settings, in one place for both pickers that set them: Settings ›
 * Practice (`feature:settings`) and the range's own quick settings (`feature:range`, plan F8f),
 * which never depend on each other.
 */

/** "Day", "Dusk", "Night", "Links". */
val RangeThemeSetting.pickerLabel: String
    get() =
        when (this) {
            RangeThemeSetting.DAY -> "Day"
            RangeThemeSetting.DUSK -> "Dusk"
            RangeThemeSetting.NIGHT -> "Night"
            RangeThemeSetting.LINKS -> "Links"
        }

/** The trail style's name, as the pickers list it. */
val ShotTrailStyle.pickerLabel: String
    get() =
        when (this) {
            ShotTrailStyle.CLASSIC -> "Classic"
            ShotTrailStyle.BROADCAST_GLOW -> "Broadcast glow"
            ShotTrailStyle.COMET -> "Comet"
            ShotTrailStyle.CLUB_COLOUR -> "Club colour"
            ShotTrailStyle.DOTTED -> "Dotted"
            ShotTrailStyle.SMOKE -> "Smoke"
            ShotTrailStyle.NEON -> "Neon"
            ShotTrailStyle.SPEED_HEAT -> "Speed heat"
            ShotTrailStyle.RAINBOW -> "Rainbow"
            ShotTrailStyle.SPIN_RIBBON -> "Spin ribbon"
            ShotTrailStyle.GROUND_TRACK -> "Ground track"
        }

/** "Off", "Ring", "Burst". */
val LandingEffect.pickerLabel: String
    get() =
        when (this) {
            LandingEffect.OFF -> "Off"
            LandingEffect.RING -> "Ring"
            LandingEffect.BURST -> "Burst"
        }

/** Plan F8f: "Live", "Last 5", "Last 10", "Last 20", "This session", "All sessions". */
val RangeShowSetting.pickerLabel: String
    get() =
        when (this) {
            RangeShowSetting.LIVE -> "Live"
            RangeShowSetting.THIS_SESSION -> "This session"
            RangeShowSetting.ALL_SESSIONS -> "All sessions"
            else -> "Last $lastShots"
        }

/** "Off" for none, else "Last 3" (one of [SHOT_TRAIL_KEEP_OPTIONS]). */
fun shotTrailKeepLabel(count: Int): String = if (count == 0) "Off" else "Last $count"

/** "Fixed" (the tee camera) or "Follow" (chases the ball), as the range's camera button says. */
val RangeCameraMode.pickerLabel: String
    get() =
        when (this) {
            RangeCameraMode.FIXED -> "Fixed"
            RangeCameraMode.FOLLOW -> "Follow"
        }

/** "Imperial (mph, yds)" or "Metric (km/h, m)", as Settings › Practice's units picker says. */
fun unitSystemLabel(units: UnitSystem): String =
    when (units) {
        UnitSystem.IMPERIAL -> "Imperial (mph, yds)"
        UnitSystem.METRIC -> "Metric (km/h, m)"
    }
