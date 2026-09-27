// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.UIKit.UIDevice

actual fun platformName(): String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion

actual fun localUtcOffsetMillis(epochMillis: Long): Long {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / MILLIS_PER_SECOND)
    return NSTimeZone.localTimeZone.secondsFromGMTForDate(date) * MILLIS_PER_SECOND.toLong()
}

private const val MILLIS_PER_SECOND = 1000.0
