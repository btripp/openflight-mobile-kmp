// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

/**
 * Whether a Wi-Fi-only (Socket.IO) action can run right now, and why not (plan R6b): every screen
 * renders a disabled control with [disabledReason] instead of hiding it.
 */
sealed interface PiFeatureAvailability {
    val isAvailable: Boolean

    /** The short explanation for a disabled control, or `null` when [isAvailable]. */
    val disabledReason: String?

    data object Available : PiFeatureAvailability {
        override val isAvailable: Boolean = true
        override val disabledReason: String? = null
    }

    data class Unavailable(
        val reason: String,
    ) : PiFeatureAvailability {
        override val isAvailable: Boolean = false
        override val disabledReason: String? = reason
    }

    companion object {
        /** The selected transport is Bluetooth. */
        const val REQUIRES_WIFI: String = "Requires Wi-Fi"

        /** On Wi-Fi, but the Socket.IO link isn't up (yet). */
        const val NOT_CONNECTED: String = "Not connected"

        /** [link]'s availability: only [PiLinkState.Connected] allows Wi-Fi-only actions. */
        fun of(link: PiLinkState): PiFeatureAvailability =
            when (link) {
                PiLinkState.Connected -> Available
                PiLinkState.WifiOnly -> Unavailable(REQUIRES_WIFI)
                PiLinkState.Idle, PiLinkState.Connecting, is PiLinkState.Reconnecting -> Unavailable(NOT_CONNECTED)
            }
    }
}
