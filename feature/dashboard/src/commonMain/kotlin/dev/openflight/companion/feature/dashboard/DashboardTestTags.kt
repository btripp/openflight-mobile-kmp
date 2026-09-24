// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

/** Test tags, mirroring the reference's accessibility identifiers where it has them. */
object DashboardTestTags {
    const val RANGE = "dashboard.range"
    const val CALIBRATE_RADAR = "dashboard.calibrateRadar"
    const val CLUB_SELECTOR = "dashboard.clubSelector"
    const val CLUB_ERROR = "dashboard.clubError"
    const val RETRY = "dashboard.retry"
    const val PROGRESS = "dashboard.progress"
    const val HOST_FIELD = "dashboard.host"
    const val EMPTY_STATE = "dashboard.emptyState"
    const val LATEST_SHOT = "dashboard.latestShot"
    const val PREVIOUS_SHOTS = "dashboard.previousShots"

    /** A detail metric in the latest-shot grid, by its title (for example "Smash"). */
    fun metric(title: String): String = "dashboard.metric.$title"
}
