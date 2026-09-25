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
    const val HELP_LINK = "dashboard.helpLink"
    const val CLUB_CONFIRMATION = "dashboard.clubConfirmation"
    const val CLUB_CONFIRM = "dashboard.clubConfirm"
    const val LOCAL_NETWORK_DENIED = "dashboard.localNetworkDenied"
    const val OPEN_SETTINGS = "dashboard.openSettings"
    const val PROCESSING = "dashboard.processing"
    const val CONNECTION_PROBLEM = "dashboard.connectionProblem"
    const val PROFILE_BUTTON = "dashboard.profile"
    const val PROFILE_SHEET = "dashboard.profile.sheet"
    const val PROFILE_ADD = "dashboard.profile.add"
    const val PROFILE_DONE = "dashboard.profile.done"
    const val PROFILE_NAME_FIELD = "dashboard.profile.name"
    const val PROFILE_SAVE = "dashboard.profile.save"
    const val PROFILE_CANCEL = "dashboard.profile.cancel"
    const val PROFILE_FORM_ERROR = "dashboard.profile.formError"
    const val PROFILE_NOTICE = "dashboard.profile.notice"
    const val PROFILE_REMOVE_CONFIRM = "dashboard.profile.removeConfirm"
    const val PROFILE_LOADING = "dashboard.profile.loading"

    /** A profile row in the sheet, by profile id. */
    fun profileRow(id: String): String = "dashboard.profile.row.$id"

    fun profileRename(id: String): String = "dashboard.profile.rename.$id"

    fun profileRemove(id: String): String = "dashboard.profile.remove.$id"

    /** A tap-to-fill host hint, by its host (for example "192.168.4.1:8080"). */
    fun hostHint(host: String): String = "dashboard.hostHint.$host"

    /** A detail metric in the latest-shot grid, by its title (for example "Smash"). */
    fun metric(title: String): String = "dashboard.metric.$title"
}
