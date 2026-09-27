// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

/** Human-readable OS name and version, e.g. "Android 36" or "iOS 26.0". */
expect fun platformName(): String

/** Plan F14: the device time zone's offset from UTC at [epochMillis], for Demo mode's shot timestamps. */
expect fun localUtcOffsetMillis(epochMillis: Long): Long
