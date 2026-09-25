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

    /** A failure; [kind] tells the UI when it can offer more than Retry (plan R8d). */
    data class Error(
        override val description: String,
        val kind: ConnectionErrorKind = ConnectionErrorKind.OTHER,
    ) : ConnectionState {
        override val canRetry: Boolean = true
    }
}

/** Why a [ConnectionState.Error] happened, where that changes what the user can do about it. */
enum class ConnectionErrorKind {
    OTHER,

    /** The address was refused before any request (plan R8d endpoint policy): fix the address. */
    ENDPOINT_REJECTED,

    /** iOS: the user denied Local Network access, so every LAN request fails: open Settings. */
    LOCAL_NETWORK_DENIED,
}
