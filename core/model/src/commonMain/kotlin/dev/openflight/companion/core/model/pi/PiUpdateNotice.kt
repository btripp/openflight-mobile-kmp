// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

/**
 * The connected Pi runs an OpenFlight that predates catch-up after a reconnect: it doesn't resend
 * the shots, or the deletions, the phone missed while disconnected (open-flight/openflight#297).
 * The app still works with it; the Dashboard says so and suggests updating the Pi.
 *
 * Over Wi-Fi the Pi's Socket.IO session still reports deletions, so there only shots are missed;
 * over Bluetooth an older Pi misses both.
 *
 * @property missesShots the Pi can't resend shots taken while the phone was away.
 * @property missesDeletions the Pi can't tell the phone about shots deleted while it was away.
 */
data class PiUpdateNotice(
    val missesShots: Boolean,
    val missesDeletions: Boolean,
) {
    val title: String get() = TITLE

    /** What the older Pi can't do, and what to do about it. */
    val detail: String
        get() {
            val missed =
                when {
                    missesShots && missesDeletions -> "shots or deletions"
                    missesShots -> "shots"
                    else -> "deletions"
                }
            return "This Pi's OpenFlight can't resend $missed you miss while your phone is disconnected. " +
                "Update OpenFlight on the Pi to keep the app in sync."
        }

    companion object {
        const val TITLE = "Update OpenFlight on your Pi"
    }
}
