// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

/**
 * Stopping OpenFlight (`POST /api/shutdown`), as the Expo app's `device.tsx` `ShutdownSection`
 * does it (plan R8f): `idle → confirming → pending → done | failed`.
 *
 * The route stops the OpenFlight **server process**, not the Pi: the server answers 200, then
 * exits about 0.5 s later while the Pi's OS keeps running (plan §9.1). Everything is worded as
 * stopping OpenFlight so nobody reads it as safe to pull the power.
 *
 * The [target] (`host[:port]`) is captured when the user confirms, so a retry goes back to the Pi
 * the user confirmed stopping, never to one they switched to afterwards.
 */
sealed interface ShutdownPhase {
    data object Idle : ShutdownPhase

    /** Asking "Stop the OpenFlight server?"; nothing has been sent. */
    data object Confirming : ShutdownPhase

    data class Pending(
        val target: String,
    ) : ShutdownPhase

    /**
     * The server accepted the request and is exiting. The link drop that follows is the expected
     * outcome, not a failure.
     */
    data class Done(
        val target: String,
    ) : ShutdownPhase

    /** The server is still running: it refused, never answered in time, or the app backgrounded. */
    data class Failed(
        val target: String,
        val reason: String,
    ) : ShutdownPhase

    companion object {
        /** Generous enough for a busy Pi on a weak LAN link (Expo `services/shutdown.ts`). */
        const val TIMEOUT_MILLIS: Long = 10_000

        const val CONFIRM_TITLE: String = "Stop the OpenFlight server?"
        const val CONFIRM_MESSAGE: String =
            "This exits the OpenFlight server. The Pi itself stays on, and the current session is not kept on it."
        const val PENDING_TEXT: String = "Stopping OpenFlight…"
        const val DONE_TITLE: String = "OpenFlight stopped; the Pi stays on"
        const val DONE_DETAIL: String = "The server accepted the request and is exiting. The Pi itself stays on."
        const val FAILED_TITLE: String = "Could not stop OpenFlight"
        const val STILL_RUNNING: String = "The server is still running."
        const val TIMED_OUT: String = "The Pi didn't answer within 10 seconds."
        const val CONNECTION_DROPPED: String = "The connection dropped when the app went to the background."
        const val NO_TARGET: String = "No Pi address to stop."
    }
}
