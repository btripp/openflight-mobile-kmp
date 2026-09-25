// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

/** State of the Wi-Fi-only Socket.IO link to the Pi's live-session API. */
sealed interface PiLinkState {
    val description: String

    /** Not started (app in the background) or no host configured. */
    data object Idle : PiLinkState {
        override val description: String = "Not connected"
    }

    /** The selected transport is Bluetooth: these features need the Pi over Wi-Fi. */
    data object WifiOnly : PiLinkState {
        override val description: String = "Available on Wi-Fi only"
    }

    data object Connecting : PiLinkState {
        override val description: String = "Connecting"
    }

    data object Connected : PiLinkState {
        override val description: String = "Connected"
    }

    /** The link dropped or couldn't open; it retries on its own after [retryInMillis]. */
    data class Reconnecting(
        val attempt: Int,
        val retryInMillis: Long,
        val reason: String,
    ) : PiLinkState {
        override val description: String = "Reconnecting: $reason"
    }
}

/**
 * One-off messages the Pi sends in reply to a command (errors and acknowledgements), for a
 * snackbar or toast. Each [message] is the server's text verbatim.
 */
sealed interface PiNotice {
    val message: String

    /** `radar_config_error`, e.g. "Radar not connected" (always on `--mock`). */
    data class RadarConfigFailed(
        override val message: String,
    ) : PiNotice

    /** `training_implement_error`, e.g. "Unknown training implement". */
    data class TrainingImplementFailed(
        override val message: String,
    ) : PiNotice

    /** `sim_send_failed`: a connected simulator rejected the send. */
    data class SimSendFailed(
        val target: String,
        override val message: String,
    ) : PiNotice

    /** `sim_shot_dropped`: the shot lacked data every simulator needs. */
    data class SimShotDropped(
        override val message: String,
    ) : PiNotice

    /** `shutdown_ack`: the Pi is shutting down; the link will drop. */
    data class ShuttingDown(
        override val message: String,
    ) : PiNotice
}
