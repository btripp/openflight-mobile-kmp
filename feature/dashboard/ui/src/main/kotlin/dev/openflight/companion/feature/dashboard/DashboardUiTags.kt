// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

/**
 * Test tags for the Android-only dashboard additions (plans R5b/R6c). The shared ones stay in
 * [DashboardTestTags] (commonMain), which the iOS UI uses too.
 */
object DashboardUiTags {
    const val SESSION = "dashboard.nav.session"
    const val TRAINING = "dashboard.nav.training"
    const val CAMERA = "dashboard.nav.camera"
    const val SETTINGS = "dashboard.nav.settings"
    const val SHOT_FLASH = "dashboard.shotFlash"
    const val CLUB_CHIPS = "dashboard.clubChips"
    const val CARRY = "dashboard.carry"
    const val PLAYER = "dashboard.player"

    /** The confidence badge under a detail metric ("Launch" or "Spin"). */
    fun confidence(title: String): String = "dashboard.confidence.$title"

    /** The subtext under a detail metric, e.g. the spin source under "Spin". */
    fun subtext(title: String): String = "dashboard.subtext.$title"
}
