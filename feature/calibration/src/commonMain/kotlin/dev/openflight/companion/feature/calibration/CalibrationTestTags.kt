// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

/** Test tags for the calibration screen's Compose UI tests. */
object CalibrationTestTags {
    const val HOST_FIELD = "calibration.host"
    const val BLUETOOTH_CARD = "calibration.bluetoothCard"
    const val TILT_METRIC = "calibration.tiltMetric"
    const val ROLL_METRIC = "calibration.rollMetric"
    const val PROGRESS = "calibration.progress"
    const val APPLY = "calibration.apply"
    const val APPLIED_RESULT = "calibration.appliedResult"
    const val SUBMIT_ERROR = "calibration.submitError"
    const val MOTION_UNAVAILABLE = "calibration.motionUnavailable"

    /** Plan F1d: the pushed screen's Done (iOS already used this identifier). */
    const val DONE = "calibration.done"

    /** Plan F14: "needs a real Pi" in Demo mode. */
    const val NEEDS_HARDWARE = "calibration.needsHardware"
}
