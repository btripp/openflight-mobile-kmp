// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class ConnectionStateTest {
    @Test
    fun idleUnavailableAndErrorCanRetry() {
        assertThat(ConnectionState.Idle.canRetry).isTrue()
        assertThat(ConnectionState.Unavailable("Bluetooth permission is required").canRetry).isTrue()
        assertThat(ConnectionState.Error("boom").canRetry).isTrue()
    }

    @Test
    fun scanningConnectingDiscoveringAndConnectedCannotRetry() {
        assertThat(ConnectionState.Scanning.canRetry).isFalse()
        assertThat(ConnectionState.Connecting.canRetry).isFalse()
        assertThat(ConnectionState.Discovering.canRetry).isFalse()
        assertThat(ConnectionState.Connected.canRetry).isFalse()
    }

    @Test
    fun descriptionsMatchTheReferenceCopy() {
        assertThat(ConnectionState.Idle.description).isEqualTo("Ready")
        assertThat(ConnectionState.Scanning.description).isEqualTo("Looking for OpenFlight")
        assertThat(ConnectionState.Connecting.description).isEqualTo("Connecting")
        assertThat(ConnectionState.Discovering.description).isEqualTo("Preparing shot notifications")
        assertThat(ConnectionState.Connected.description).isEqualTo("Connected")
        assertThat(ConnectionState.Unavailable("no bluetooth").description).isEqualTo("no bluetooth")
        assertThat(ConnectionState.Error("decode failed").description).isEqualTo("decode failed")
    }
}
