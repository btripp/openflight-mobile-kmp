// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

/**
 * Errors from the Wi-Fi HTTP/SSE transport, ported from `WiFiShotClient.WiFiShotError` and
 * `RadarCalibrationClientError`/`ClubSelectionClient` (plan §0.2). Both the stream connection and
 * the `/api/club`/`/api/calibration/...` control calls share this single error type.
 */
sealed class OpenFlightHttpError(
    message: String,
) : Exception(message) {
    /**
     * [host] could not be turned into a usable URL, or [EndpointPolicy] refused it; [reason] is the
     * user-facing sentence (see [EndpointDecision.Rejected.reason]).
     */
    data class InvalidHost(
        val host: String,
        val reason: String = EndpointPolicy.rejectionReason(host) ?: "\"$host\" is not a valid address.",
    ) : OpenFlightHttpError(reason)

    /**
     * A non-200 response. [serverMessage] is the `error` field from a `{"error":"..."}` body when
     * the server sent one; 503 on the shot stream always means "too many devices" even without a
     * body.
     */
    data class UnexpectedStatus(
        val statusCode: Int,
        val serverMessage: String? = null,
    ) : OpenFlightHttpError(serverMessage ?: defaultStatusMessage(statusCode))

    /** The stream's byte channel ended cleanly; the run loop reconnects after this. */
    data object StreamEnded : OpenFlightHttpError("OpenFlight closed the connection.")

    private companion object {
        fun defaultStatusMessage(statusCode: Int): String =
            if (statusCode == HTTP_TOO_MANY_DEVICES) {
                "Too many devices are streaming shots."
            } else {
                "OpenFlight returned HTTP $statusCode."
            }

        const val HTTP_TOO_MANY_DEVICES = 503
    }
}
