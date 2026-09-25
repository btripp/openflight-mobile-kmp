// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

/**
 * Recognises iOS's Local Network privacy denial (Apple TN3179) in a request failure, so the UI can
 * send the user to Settings instead of offering a Retry that can't work (plan R8d).
 *
 * URLSession (Ktor's Darwin engine) reports a denied Local Network permission as
 * `NSURLErrorDomain` code `-1009` (`NSURLErrorNotConnectedToInternet`, "The Internet connection
 * appears to be offline."), whose underlying `kCFErrorDomainCFNetwork` `-1009` error carries
 * `_NSURLErrorNWPathKey = "unsatisfied (Local network prohibited), ..."` and the stream error POSIX
 * `50` (`ENETDOWN`). Code `-1009` alone also means "no network at all", so only the path's
 * "Local network prohibited" reason (Network framework's
 * `NWPath.UnsatisfiedReason.localNetworkDenied`) counts. Android has no such permission here.
 */
object LocalNetworkDenial {
    /** Shown instead of the raw URLSession error. */
    const val MESSAGE: String =
        "OpenFlight isn't allowed to use your local network. Turn on Local Network for OpenFlight in Settings."

    /** The unsatisfied path's reason as URLSession prints it in `_NSURLErrorNWPathKey`. */
    const val PATH_REASON: String = "Local network prohibited"

    /** Whether [error] (or anything in its cause chain) is the Local Network denial. */
    fun isDenied(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(MAX_CAUSES).any {
            isDeniedOnPlatform(it) ||
                isDeniedMessage(it.message)
        }

    /**
     * Text-only check, for layers that only kept a failure's message (the Socket.IO reconnect
     * reason); Ktor's Darwin exception message embeds the whole `NSError` description.
     */
    fun isDeniedMessage(message: String?): Boolean = message?.contains(PATH_REASON, ignoreCase = true) == true

    private const val MAX_CAUSES = 8
}

/** The platform's structured check of one throwable (not its causes); `false` where there is none. */
internal expect fun isDeniedOnPlatform(error: Throwable): Boolean
