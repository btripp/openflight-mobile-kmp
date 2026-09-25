// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

/**
 * A Pi address that passed [EndpointPolicy]: the scheme, the host exactly as it goes into a URL
 * (IPv6 literals in brackets) and the port, if any.
 */
data class Endpoint(
    val scheme: String,
    val host: String,
    val port: Int?,
) {
    /** `scheme://host[:port]/path`; [path] gains a leading `/` if it lacks one. */
    fun url(path: String): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val portSegment = port?.let { ":$it" } ?: ""
        return "$scheme://$host$portSegment$normalizedPath"
    }
}

/** What [EndpointPolicy.evaluate] decided about one address. */
sealed interface EndpointDecision {
    data class Allowed(
        val endpoint: Endpoint,
    ) : EndpointDecision

    /** The address can't be used; [reason] is the sentence to show the user. */
    sealed interface Rejected : EndpointDecision {
        val reason: String
    }

    data object Blank : Rejected {
        override val reason: String = "Enter the address of your OpenFlight Pi."
    }

    data class Malformed(
        val input: String,
    ) : Rejected {
        override val reason: String = "\"$input\" is not a valid address."
    }

    data class UnsupportedScheme(
        val scheme: String,
    ) : Rejected {
        override val reason: String = "\"$scheme://\" addresses aren't supported. Use http:// or https://."
    }

    /** `user:password@host`: credentials would travel with every request, so they're refused outright. */
    data object Credentials : Rejected {
        override val reason: String = "Remove the user name and password from the address."
    }

    /** Plain `http` to a host that isn't on the local network. */
    data class CleartextToPublicHost(
        val host: String,
    ) : Rejected {
        override val reason: String =
            "http:// only works for a Pi on your local network. Use https:// to reach $host."
    }
}

/**
 * Which Pi addresses the app may talk to (plan R8d). Every transport (SSE, Socket.IO, HTTP control,
 * camera) builds its URL through [EndpointUrl], which asks this first, so a rejected address never
 * reaches Ktor.
 *
 * - `https` is allowed for any host.
 * - `http` (cleartext) only for a host on the local network: loopback (`127.0.0.0/8`, `::1`,
 *   `localhost`), mDNS `.local` names, RFC 1918 (`10/8`, `172.16/12`, `192.168/16`), IPv4
 *   link-local `169.254/16`, IPv6 link-local `fe80::/10` and unique-local `fc00::/7` (IPv4-mapped
 *   IPv6 follows the IPv4 rules). Any other name or address, including a bare single-label name
 *   that DNS might resolve anywhere, is public.
 * - Credentials (`user:pass@`), any other scheme and malformed input are rejected.
 *
 * Input is what a user types: a bare host, `host:port`, `[v6]:port`, or a full URL whose path,
 * query and fragment are dropped. A missing scheme means `http`; `http` without a port gets the
 * Pi's `8080` (plan §0.2), `https` keeps its own default.
 */
object EndpointPolicy {
    fun evaluate(input: String): EndpointDecision {
        val trimmed = input.trim()
        return when {
            trimmed.isEmpty() -> EndpointDecision.Blank
            trimmed.any { it.isWhitespace() || it.isISOControl() } -> EndpointDecision.Malformed(trimmed)
            else -> evaluateUrl(trimmed)
        }
    }

    private fun evaluateUrl(trimmed: String): EndpointDecision {
        val separator = trimmed.indexOf(SCHEME_SEPARATOR)
        val scheme = if (separator >= 0) trimmed.substring(0, separator).lowercase() else HTTP
        val rest = if (separator >= 0) trimmed.substring(separator + SCHEME_SEPARATOR.length) else trimmed
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
        return when {
            !SCHEME.matches(scheme) -> EndpointDecision.Malformed(trimmed)
            scheme != HTTP && scheme != HTTPS -> EndpointDecision.UnsupportedScheme(scheme)
            authority.contains('@') -> EndpointDecision.Credentials
            else -> decide(trimmed, scheme, parseAuthority(authority))
        }
    }

    private fun decide(
        trimmed: String,
        scheme: String,
        parsed: ParsedAuthority?,
    ): EndpointDecision =
        when {
            parsed == null -> {
                EndpointDecision.Malformed(trimmed)
            }

            scheme == HTTP && !parsed.isLocal -> {
                EndpointDecision.CleartextToPublicHost(parsed.displayHost)
            }

            else -> {
                val port = parsed.port ?: DEFAULT_HTTP_PORT.takeIf { scheme == HTTP }
                EndpointDecision.Allowed(Endpoint(scheme, parsed.urlHost, port))
            }
        }

    /** The user-facing reason [input] is refused, or `null` when it's allowed. */
    fun rejectionReason(input: String): String? = (evaluate(input) as? EndpointDecision.Rejected)?.reason

    private class ParsedAuthority(
        val urlHost: String,
        val displayHost: String,
        val port: Int?,
        val isLocal: Boolean,
    )

    private fun parseAuthority(authority: String): ParsedAuthority? {
        if (authority.isEmpty()) return null
        return when {
            authority.startsWith("[") -> parseBracketedIpv6(authority)
            authority.count { it == ':' } >= 2 -> ipv6Authority(authority, port = null)
            else -> parseHostAndPort(authority)
        }
    }

    @Suppress("ReturnCount") // One early exit per grammar rule reads clearer than nesting.
    private fun parseBracketedIpv6(authority: String): ParsedAuthority? {
        val close = authority.indexOf(']')
        if (close < 0) return null
        val after = authority.substring(close + 1)
        val port =
            when {
                after.isEmpty() -> null
                after.startsWith(":") -> parsePort(after.substring(1)) ?: return null
                else -> return null
            }
        return ipv6Authority(authority.substring(1, close), port)
    }

    @Suppress("ReturnCount") // One early exit per grammar rule reads clearer than nesting.
    private fun ipv6Authority(
        literal: String,
        port: Int?,
    ): ParsedAuthority? {
        val zoneIndex = literal.indexOf('%')
        val address = if (zoneIndex >= 0) literal.substring(0, zoneIndex) else literal
        val zone = if (zoneIndex >= 0) literal.substring(zoneIndex + 1).removePrefix("25") else null
        if (zone != null && !ZONE.matches(zone)) return null
        val bytes = Ipv6.parse(address) ?: return null
        val lower = address.lowercase()
        val urlLiteral = if (zone == null) lower else "$lower%25$zone"
        return ParsedAuthority(
            urlHost = "[$urlLiteral]",
            displayHost = lower,
            port = port,
            isLocal = Ipv6.isLocal(bytes),
        )
    }

    private fun parseHostAndPort(authority: String): ParsedAuthority? {
        val colon = authority.indexOf(':')
        val hostText = if (colon >= 0) authority.substring(0, colon) else authority
        val port = if (colon >= 0) parsePort(authority.substring(colon + 1)) ?: return null else null
        val host = hostText.lowercase().removeSuffix(".")
        val ipv4 = Ipv4.parse(host)
        return when {
            ipv4 != null -> ParsedAuthority(host, host, port, Ipv4.isLocal(ipv4))
            isHostName(host) -> ParsedAuthority(host, host, port, isLocalName(host))
            else -> null
        }
    }

    private fun parsePort(text: String): Int? {
        if (text.isEmpty() || text.length > MAX_PORT_DIGITS || !text.all { it in '0'..'9' }) return null
        return text.toInt().takeIf { it in 1..MAX_PORT }
    }

    private const val SCHEME_SEPARATOR = "://"
    private const val HTTP = "http"
    private const val HTTPS = "https"
    private const val DEFAULT_HTTP_PORT = 8080
    private const val MAX_PORT = 65_535
    private const val MAX_PORT_DIGITS = 5
    private val SCHEME = Regex("[a-z][a-z0-9+.-]*")
    private val ZONE = Regex("[A-Za-z0-9._~-]{1,32}")
}

/** A DNS name of letters, digits and inner hyphens (RFC 1123), at most 253 characters. */
private fun isHostName(host: String): Boolean =
    host.isNotEmpty() && host.length <= MAX_HOST_LENGTH && host.split('.').all { HOST_LABEL.matches(it) }

/** `localhost` and mDNS `.local` names (RFC 6762). */
private fun isLocalName(host: String): Boolean = host == "localhost" || (host.endsWith(".local") && host != ".local")

private const val MAX_HOST_LENGTH = 253
private val HOST_LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

/** Strict dotted-quad IPv4 (no octal, hex or short forms, which resolvers read differently). */
@Suppress("MagicNumber") // Octet counts and the RFC ranges are their own documentation.
internal object Ipv4 {
    private const val OCTETS = 4
    private const val MAX_OCTET = 255

    @Suppress("ReturnCount") // One early exit per grammar rule reads clearer than nesting.
    fun parse(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != OCTETS) return null
        val octets = IntArray(OCTETS)
        for ((index, part) in parts.withIndex()) {
            val valid = part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' }
            if (!valid || (part.length > 1 && part[0] == '0')) return null
            octets[index] = part.toInt().takeIf { it <= MAX_OCTET } ?: return null
        }
        return octets
    }

    fun isLocal(octets: IntArray): Boolean {
        val (a, b) = octets[0] to octets[1]
        return a == 127 || // loopback 127/8
            a == 10 || // RFC 1918 10/8
            (a == 172 && b in 16..31) || // RFC 1918 172.16/12
            (a == 192 && b == 168) || // RFC 1918 192.168/16
            (a == 169 && b == 254) // link-local 169.254/16
    }
}

/** IPv6 text to its 16 bytes (RFC 4291 §2.2, including `::` and an IPv4 tail). */
@Suppress("MagicNumber") // Byte offsets and prefixes are RFC 4291's.
internal object Ipv6 {
    private const val BYTES = 16
    private const val GROUPS = 8
    private const val MAX_GROUP_DIGITS = 4
    private const val HEX = 16
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF

    @Suppress("ReturnCount") // One early exit per grammar rule reads clearer than nesting.
    fun parse(text: String): IntArray? {
        if (text.isEmpty()) return null
        val doubleColon = text.indexOf("::")
        if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) return null
        val head = if (doubleColon >= 0) text.substring(0, doubleColon) else text
        val tail = if (doubleColon >= 0) text.substring(doubleColon + 2) else ""
        val headGroups = groups(head) ?: return null
        val tailGroups = groups(tail) ?: return null
        val total = headGroups.size + tailGroups.size
        if (doubleColon < 0 && total != GROUPS) return null
        if (doubleColon >= 0 && total > GROUPS - 1) return null
        val all = headGroups + IntArray(GROUPS - total).toList() + tailGroups
        return IntArray(BYTES) { index ->
            val group = all[index / 2]
            if (index % 2 == 0) (group shr BYTE_BITS) and BYTE_MASK else group and BYTE_MASK
        }
    }

    /** 16-bit groups; a trailing dotted quad counts as two. `null` when any part is invalid. */
    @Suppress("ReturnCount") // One early exit per grammar rule reads clearer than nesting.
    private fun groups(text: String): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val parts = text.split(':')
        val result = mutableListOf<Int>()
        for ((index, part) in parts.withIndex()) {
            if (index == parts.lastIndex && part.contains('.')) {
                val v4 = Ipv4.parse(part) ?: return null
                result += (v4[0] shl BYTE_BITS) or v4[1]
                result += (v4[2] shl BYTE_BITS) or v4[3]
            } else {
                val valid = part.isNotEmpty() && part.length <= MAX_GROUP_DIGITS
                result += (if (valid) part.toIntOrNull(HEX) else null) ?: return null
            }
        }
        return result
    }

    fun isLocal(bytes: IntArray): Boolean {
        val loopback = (0 until 15).all { bytes[it] == 0 } && bytes[15] == 1
        val linkLocal = bytes[0] == 0xFE && (bytes[1] and 0xC0) == 0x80 // fe80::/10
        val uniqueLocal = (bytes[0] and 0xFE) == 0xFC // fc00::/7
        val mapped = (0 until 10).all { bytes[it] == 0 } && bytes[10] == 0xFF && bytes[11] == 0xFF
        return loopback || linkLocal || uniqueLocal || (mapped && Ipv4.isLocal(bytes.copyOfRange(12, 16)))
    }
}
