// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

/**
 * Builds OpenFlight HTTP(S) endpoint URLs from whatever a user typed: a bare hostname, a host and
 * port, or a full URL. Ported from `WiFiShotClient.endpointURL` (plan §0.2):
 *
 * - The host is trimmed.
 * - `http://` is assumed when there is no scheme.
 * - Only `http` and `https` schemes are accepted.
 * - The port defaults to `8080`, but **only** for `http`; an explicit port (on either scheme) is
 *   always preserved, and `https` with no explicit port is left to its own default.
 * - Any query or fragment on a pasted URL is dropped, and the path is always replaced with
 *   [path].
 */
object EndpointUrl {
    private const val DEFAULT_HTTP_PORT = 8080
    private const val SCHEME_SEPARATOR = "://"

    /** Returns the built URL string, or `null` when [host] cannot be used. */
    fun build(
        host: String,
        path: String,
    ): String? {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return null
        return assembledUrl(trimmed, path)
    }

    private fun assembledUrl(
        trimmed: String,
        path: String,
    ): String? {
        val scheme = schemeOf(trimmed) ?: return null
        return hostAndPortOf(trimmed, scheme)?.let { (hostPart, port) -> assemble(scheme, hostPart, port, path) }
    }

    private fun assemble(
        scheme: String,
        hostPart: String,
        port: Int?,
        path: String,
    ): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val portSegment = port?.let { ":$it" } ?: ""
        return "$scheme://$hostPart$portSegment$normalizedPath"
    }

    private fun schemeOf(trimmed: String): String? {
        val separatorIndex = trimmed.indexOf(SCHEME_SEPARATOR)
        val scheme = if (separatorIndex >= 0) trimmed.substring(0, separatorIndex).lowercase() else "http"
        return scheme.takeIf { it == "http" || it == "https" }
    }

    private fun hostAndPortOf(
        trimmed: String,
        scheme: String,
    ): Pair<String, Int?>? = authorityOf(trimmed)?.let { authority -> hostAndPortFrom(authority, scheme) }

    private fun authorityOf(trimmed: String): String? {
        val separatorIndex = trimmed.indexOf(SCHEME_SEPARATOR)
        val afterScheme =
            if (separatorIndex >= 0) trimmed.substring(separatorIndex + SCHEME_SEPARATOR.length) else trimmed
        val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) afterScheme else afterScheme.substring(0, authorityEnd)
        return authority.takeIf { it.isNotEmpty() }
    }

    private fun hostAndPortFrom(
        authority: String,
        scheme: String,
    ): Pair<String, Int?>? {
        val colonIndex = authority.lastIndexOf(':')
        val hostPart = if (colonIndex >= 0) authority.substring(0, colonIndex) else authority
        val explicitPortText = if (colonIndex >= 0) authority.substring(colonIndex + 1) else null
        val explicitPort = explicitPortText?.toIntOrNull()
        val portIsInvalid = explicitPortText != null && explicitPort == null
        val port = explicitPort ?: DEFAULT_HTTP_PORT.takeIf { scheme == "http" }
        return (hostPart to port).takeIf { hostPart.isNotEmpty() && !portIsInvalid }
    }
}
