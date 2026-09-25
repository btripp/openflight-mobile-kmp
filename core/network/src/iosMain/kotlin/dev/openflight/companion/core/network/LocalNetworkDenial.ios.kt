// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSUnderlyingErrorKey

/**
 * Walks a [DarwinHttpRequestException]'s `NSError` and its `NSUnderlyingError` chain for the
 * TN3179 Local Network denial: `NSURLErrorDomain`/`kCFErrorDomainCFNetwork` `-1009` whose
 * `_NSURLErrorNWPathKey` path reads "unsatisfied (Local network prohibited)".
 */
internal actual fun isDeniedOnPlatform(error: Throwable): Boolean {
    val origin = (error as? DarwinHttpRequestException)?.origin ?: return false
    return generateSequence(origin) { it.userInfo[NSUnderlyingErrorKey] as? NSError }
        .take(MAX_UNDERLYING)
        .any(::isLocalNetworkDenial)
}

internal fun isLocalNetworkDenial(error: NSError): Boolean {
    val urlDomain = error.domain == NSURLErrorDomain || error.domain == CFNETWORK_ERROR_DOMAIN
    if (!urlDomain || error.code != NSURLErrorNotConnectedToInternet) return false
    val path = error.userInfo[NW_PATH_KEY]?.toString()
    return LocalNetworkDenial.isDeniedMessage(path)
}

/** URLSession's private-but-logged userInfo key holding the unsatisfied `nw_path` (see TN3179). */
internal const val NW_PATH_KEY = "_NSURLErrorNWPathKey"

/** `kCFErrorDomainCFNetwork`, spelled out: CFNetwork's constant is a `CFStringRef`. */
internal const val CFNETWORK_ERROR_DOMAIN = "kCFErrorDomainCFNetwork"

private const val MAX_UNDERLYING = 8
