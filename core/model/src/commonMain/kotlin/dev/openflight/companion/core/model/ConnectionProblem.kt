// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import dev.openflight.companion.core.model.pi.PiLinkState

/**
 * Why the phone can't reach the Pi, in words, with what the user can do about it (plan R8f). The
 * connection card and Settings render it as text plus an icon, never as a colour alone.
 *
 * Built from the shot stream's [ConnectionState] and the Socket.IO [PiLinkState] by [of]; a
 * refused address (plan R8d endpoint policy) wins over everything else because no retry can fix
 * it.
 *
 * @property title a short headline, e.g. "Address not allowed".
 * @property detail the transport's own reason, verbatim.
 */
data class ConnectionProblem(
    val kind: Kind,
    val title: String,
    val detail: String,
) {
    enum class Kind {
        /** The endpoint policy refused the address before connecting: fix the address. */
        ADDRESS_REJECTED,

        /** The platform's local-network permission is off: open the app's settings. */
        LOCAL_NETWORK_DENIED,

        /** Anything else that failed: Retry may help. */
        CONNECTION_FAILED,
    }

    companion object {
        const val ADDRESS_REJECTED_TITLE: String = "Address not allowed"
        const val LOCAL_NETWORK_DENIED_TITLE: String = "Local network access is off"
        const val CONNECTION_FAILED_TITLE: String = "Can't reach the Pi"

        /** The problem to show for this pair of states, or `null` when nothing is wrong (yet). */
        fun of(
            state: ConnectionState,
            link: PiLinkState,
        ): ConnectionProblem? {
            val error = state as? ConnectionState.Error
            return when {
                link is PiLinkState.Rejected -> {
                    ConnectionProblem(Kind.ADDRESS_REJECTED, ADDRESS_REJECTED_TITLE, link.reason)
                }

                error?.kind == ConnectionErrorKind.ENDPOINT_REJECTED -> {
                    ConnectionProblem(Kind.ADDRESS_REJECTED, ADDRESS_REJECTED_TITLE, error.description)
                }

                error?.kind == ConnectionErrorKind.LOCAL_NETWORK_DENIED -> {
                    ConnectionProblem(Kind.LOCAL_NETWORK_DENIED, LOCAL_NETWORK_DENIED_TITLE, error.description)
                }

                link is PiLinkState.Reconnecting && link.localNetworkDenied -> {
                    ConnectionProblem(Kind.LOCAL_NETWORK_DENIED, LOCAL_NETWORK_DENIED_TITLE, link.reason)
                }

                // A working live session means the Pi is reachable even when the SSE stream isn't
                // (a current backend has none), so a stream error alone isn't the problem to show.
                error != null && link != PiLinkState.Connected -> {
                    ConnectionProblem(Kind.CONNECTION_FAILED, CONNECTION_FAILED_TITLE, error.description)
                }

                else -> {
                    null
                }
            }
        }
    }
}
