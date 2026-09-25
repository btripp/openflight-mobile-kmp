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

        /**
         * Plan R8e: Bluetooth is a read-and-select link (the Pi refuses destructive commands over
         * unauthenticated BLE), so deleting, clearing and editing profiles need Wi-Fi.
         */
        const val WIFI_ONLY_ON_BLUETOOTH: String = "Wi-Fi only: Bluetooth can't delete or edit on the Pi"

        /** [link]'s availability: only [PiLinkState.Connected] allows Wi-Fi-only actions. */
        fun of(link: PiLinkState): PiFeatureAvailability =
            when (link) {
                PiLinkState.Connected -> Available

                PiLinkState.WifiOnly -> Unavailable(REQUIRES_WIFI)

                PiLinkState.Idle, PiLinkState.Connecting, is PiLinkState.Reconnecting, is PiLinkState.Rejected,
                -> Unavailable(NOT_CONNECTED)
            }

        /**
         * Deleting a shot and clearing the session (plan R8e): never over Bluetooth. Otherwise they
         * act on the Pi's session over Socket.IO, or on the phone's own history without it.
         */
        fun forDeleteAndClear(overBluetooth: Boolean): PiFeatureAvailability =
            if (overBluetooth) Unavailable(WIFI_ONLY_ON_BLUETOOTH) else Available

        /** Adding, renaming and removing profiles: Socket.IO only, never over Bluetooth. */
        fun forProfileEdits(link: PiLinkState): PiFeatureAvailability =
            if (link == PiLinkState.WifiOnly) Unavailable(WIFI_ONLY_ON_BLUETOOTH) else of(link)

        /** Selecting the active profile: over Socket.IO, or over Bluetooth once the Pi speaks schema v2. */
        fun forProfileSelection(
            link: PiLinkState,
            bluetoothSchemaV2: Boolean,
        ): PiFeatureAvailability = if (link == PiLinkState.WifiOnly && bluetoothSchemaV2) Available else of(link)
    }
}
