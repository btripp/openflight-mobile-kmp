// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

/** The text half of [LocalNetworkDenial]; the `NSError` half is `LocalNetworkDenialIosTest`. */
class LocalNetworkDenialTest {
    @Test
    fun theUrlSessionPathDescriptionIsRecognised() {
        // As URLSession logs it (Apple Developer Forums thread 771835).
        val description =
            "Error Domain=NSURLErrorDomain Code=-1009 \"The Internet connection appears to be offline.\" " +
                "UserInfo={NSUnderlyingError=0x302d79d40 {Error Domain=kCFErrorDomainCFNetwork Code=-1009 " +
                "\"(null)\" UserInfo={_NSURLErrorNWPathKey=unsatisfied (Local network prohibited), " +
                "interface: en0[802.11], ipv4, uses wifi, _kCFStreamErrorCodeKey=50, _kCFStreamErrorDomainKey=1}}}"
        assertThat(LocalNetworkDenial.isDeniedMessage(description)).isTrue()
    }

    @Test
    fun beingOfflineIsNotADenial() {
        val offline =
            "Error Domain=NSURLErrorDomain Code=-1009 \"The Internet connection appears to be offline.\" " +
                "UserInfo={_NSURLErrorNWPathKey=unsatisfied (No network route)}"
        assertThat(LocalNetworkDenial.isDeniedMessage(offline)).isFalse()
        assertThat(LocalNetworkDenial.isDeniedMessage(null)).isFalse()
    }

    @Test
    fun aDenialAnywhereInTheCauseChainCounts() {
        val cause = IllegalStateException("unsatisfied (Local network prohibited)")
        assertThat(LocalNetworkDenial.isDenied(RuntimeException("wrapped", cause))).isTrue()
        assertThat(LocalNetworkDenial.isDenied(RuntimeException("Connection refused"))).isFalse()
    }
}
