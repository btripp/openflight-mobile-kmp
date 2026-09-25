// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

/**
 * Builds OpenFlight HTTP(S) endpoint URLs from whatever a user typed: a bare hostname, a host and
 * port, or a full URL. Ported from `WiFiShotClient.endpointURL` (plan §0.2), and since plan R8d
 * gated by [EndpointPolicy], so every transport that builds its URL here (SSE, Socket.IO, HTTP
 * control, camera) refuses the same addresses before Ktor sees them:
 *
 * - The host is trimmed.
 * - `http://` is assumed when there is no scheme.
 * - Only `http` and `https` schemes are accepted; `http` only for local-network hosts.
 * - The port defaults to `8080`, but **only** for `http`; an explicit port (on either scheme) is
 *   always preserved, and `https` with no explicit port is left to its own default.
 * - Any query or fragment on a pasted URL is dropped, and the path is always replaced with
 *   [path].
 */
object EndpointUrl {
    /** Returns the built URL string, or `null` when [host] cannot be used (see [EndpointPolicy]). */
    fun build(
        host: String,
        path: String,
    ): String? = (EndpointPolicy.evaluate(host) as? EndpointDecision.Allowed)?.endpoint?.url(path)

    /**
     * Like [build], but a refused [host] throws.
     *
     * @throws OpenFlightHttpError.InvalidHost carrying [EndpointPolicy]'s user-facing reason.
     */
    fun require(
        host: String,
        path: String,
    ): String =
        when (val decision = EndpointPolicy.evaluate(host)) {
            is EndpointDecision.Allowed -> decision.endpoint.url(path)
            is EndpointDecision.Rejected -> throw OpenFlightHttpError.InvalidHost(host, decision.reason)
        }
}
