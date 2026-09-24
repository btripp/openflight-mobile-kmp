// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

/**
 * Connection status shared by every shot transport, ported from
 * `ios/OpenFlight/ConnectionState.swift`, so the dashboard renders Bluetooth and Wi-Fi with one
 * code path.
 */
sealed interface ConnectionState {
    val description: String
    val canRetry: Boolean

    data object Idle : ConnectionState {
        override val description: String = "Ready"
        override val canRetry: Boolean = true
    }

    data class Unavailable(
        override val description: String,
    ) : ConnectionState {
        override val canRetry: Boolean = true
    }

    data object Scanning : ConnectionState {
        override val description: String = "Looking for OpenFlight"
        override val canRetry: Boolean = false
    }

    data object Connecting : ConnectionState {
        override val description: String = "Connecting"
        override val canRetry: Boolean = false
    }

    data object Discovering : ConnectionState {
        override val description: String = "Preparing shot notifications"
        override val canRetry: Boolean = false
    }

    data object Connected : ConnectionState {
        override val description: String = "Connected"
        override val canRetry: Boolean = false
    }

    data class Error(
        override val description: String,
    ) : ConnectionState {
        override val canRetry: Boolean = true
    }
}
