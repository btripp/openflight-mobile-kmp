// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSError
import platform.Foundation.NSPOSIXErrorDomain
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSUnderlyingErrorKey
import kotlin.test.Test

/**
 * The `NSError` shape URLSession uses for a denied Local Network permission (Apple TN3179; logged
 * in Apple Developer Forums thread 771835): `NSURLErrorDomain -1009` wrapping
 * `kCFErrorDomainCFNetwork -1009` whose `_NSURLErrorNWPathKey` reads "unsatisfied (Local network
 * prohibited)".
 */
class LocalNetworkDenialIosTest {
    private fun cfNetworkError(path: String): NSError =
        NSError.errorWithDomain(
            CFNETWORK_ERROR_DOMAIN,
            NSURLErrorNotConnectedToInternet,
            mapOf<Any?, Any?>(NW_PATH_KEY to path, "_kCFStreamErrorCodeKey" to 50, "_kCFStreamErrorDomainKey" to 1),
        )

    private fun urlError(
        code: Long,
        underlying: NSError?,
    ): NSError =
        NSError.errorWithDomain(
            NSURLErrorDomain,
            code,
            underlying?.let { mapOf<Any?, Any?>(NSUnderlyingErrorKey to it) },
        )

    @Test
    fun theWrappedCfNetworkPathReasonIsADenial() {
        val error = urlError(NSURLErrorNotConnectedToInternet, cfNetworkError(DENIED_PATH))
        assertThat(isDeniedOnPlatform(DarwinHttpRequestException(error))).isTrue()
        assertThat(LocalNetworkDenial.isDenied(DarwinHttpRequestException(error))).isTrue()
    }

    @Test
    fun beingOfflineWithTheSameCodeIsNotADenial() {
        val error = urlError(NSURLErrorNotConnectedToInternet, cfNetworkError("unsatisfied (No network route)"))
        assertThat(isDeniedOnPlatform(DarwinHttpRequestException(error))).isFalse()
    }

    @Test
    fun anotherCodeIsNotADenialEvenWithThePathReason() {
        val error = urlError(NSURLErrorTimedOut, null)
        assertThat(isLocalNetworkDenial(error)).isFalse()
        val posix = NSError.errorWithDomain(NSPOSIXErrorDomain, NSURLErrorNotConnectedToInternet, null)
        assertThat(isLocalNetworkDenial(posix)).isFalse()
    }

    @Test
    fun aNonDarwinExceptionIsLeftToTheTextCheck() {
        assertThat(isDeniedOnPlatform(IllegalStateException(DENIED_PATH))).isFalse()
    }

    private companion object {
        const val DENIED_PATH = "unsatisfied (Local network prohibited), interface: en0[802.11], ipv4, uses wifi"
    }
}
