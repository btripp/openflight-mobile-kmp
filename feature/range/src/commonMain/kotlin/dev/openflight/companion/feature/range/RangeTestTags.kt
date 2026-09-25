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
}
