// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

/** Static app identity shown by the shell. */
object AppInfo {
    const val TITLE = "OpenFlight"
    const val TAGLINE = "Launch monitor companion"

    /** Subtitle on the placeholder screen, e.g. "Launch monitor companion · Android 36". */
    fun subtitle(platform: String): String = "$TAGLINE · $platform"
}
