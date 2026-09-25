// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.openflight.companion.core.model.pi.PiLinkState
import kotlin.test.Test

class ConnectionProblemTest {
    @Test
    fun aRejectedLinkIsAnAddressProblemWithThePolicyReason() {
        val problem = ConnectionProblem.of(ConnectionState.Idle, PiLinkState.Rejected("Public addresses need HTTPS"))

        assertThat(problem).isEqualTo(
            ConnectionProblem(
                ConnectionProblem.Kind.ADDRESS_REJECTED,
                ConnectionProblem.ADDRESS_REJECTED_TITLE,
                "Public addresses need HTTPS",
            ),
        )
    }

    @Test
    fun aRejectedStreamEndpointIsAnAddressProblemEvenWhileTheLinkReconnects() {
        val state = ConnectionState.Error("Credentials aren't allowed", ConnectionErrorKind.ENDPOINT_REJECTED)
        val link = PiLinkState.Reconnecting(attempt = 1, retryInMillis = 500, reason = "timeout")

        val problem = ConnectionProblem.of(state, link)

        assertThat(problem?.kind).isEqualTo(ConnectionProblem.Kind.ADDRESS_REJECTED)
        assertThat(problem?.detail).isEqualTo("Credentials aren't allowed")
    }

    @Test
    fun aLocalNetworkDenialFromEitherLinkAsksForThePermission() {
        val fromStream =
            ConnectionProblem.of(
                ConnectionState.Error("denied", ConnectionErrorKind.LOCAL_NETWORK_DENIED),
                PiLinkState.Idle,
            )
        val fromLink =
            ConnectionProblem.of(
                ConnectionState.Connecting,
                PiLinkState.Reconnecting(1, 500, "Local network prohibited", localNetworkDenied = true),
            )

        assertThat(fromStream?.kind).isEqualTo(ConnectionProblem.Kind.LOCAL_NETWORK_DENIED)
        assertThat(fromLink?.kind).isEqualTo(ConnectionProblem.Kind.LOCAL_NETWORK_DENIED)
        assertThat(fromLink?.detail).isEqualTo("Local network prohibited")
    }

    @Test
    fun aStreamErrorIsAFailureUnlessTheLiveSessionIsUp() {
        val error = ConnectionState.Error("HTTP 404")

        assertThat(ConnectionProblem.of(error, PiLinkState.Idle)?.kind)
            .isEqualTo(ConnectionProblem.Kind.CONNECTION_FAILED)
        // A current backend has no SSE stream: its Socket.IO link is what counts.
        assertThat(ConnectionProblem.of(error, PiLinkState.Connected)).isNull()
    }

    @Test
    fun nothingIsWrongWhileConnectingOrConnected() {
        assertThat(ConnectionProblem.of(ConnectionState.Connecting, PiLinkState.Connecting)).isNull()
        assertThat(ConnectionProblem.of(ConnectionState.Connected, PiLinkState.WifiOnly)).isNull()
    }
}
