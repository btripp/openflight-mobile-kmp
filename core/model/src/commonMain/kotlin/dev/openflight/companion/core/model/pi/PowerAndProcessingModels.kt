// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `PowerState` (backend power/models.py:10). [UNKNOWN] is a value a newer server may add. */
@Serializable
enum class PowerState {
    @SerialName("plugged_in")
    PLUGGED_IN,

    @SerialName("on_battery")
    ON_BATTERY,

    @SerialName("low")
    LOW,

    @SerialName("critical")
    CRITICAL,

    /** A Pi with no battery provider: an answer, not a missing reading. */
    @SerialName("unavailable")
    UNAVAILABLE,

    UNKNOWN,
}

/**
 * `power_status` (`PowerStatus.to_dict`, power/models.py:30). Sent on connect when a monitor runs
 * and every 5 s after, only with `--battery geekworm`. Each payload is a complete snapshot.
 * Every measurement is nullable because a Pi without a battery HAT still reports its absence.
 *
 * @property updatedAt ISO-8601 UTC.
 */
@Serializable
data class PowerStatus(
    val available: Boolean = false,
    val provider: String = "",
    val state: PowerState = PowerState.UNKNOWN,
    @SerialName("battery_percent") val batteryPercent: Double? = null,
    @SerialName("battery_voltage_v") val batteryVoltageV: Double? = null,
    @SerialName("external_power") val externalPower: Boolean? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val error: String? = null,
)

/**
 * `shot_processing {state}` (server.py:2111, from the rolling-buffer monitor only). There is no
 * "complete" state: the next `shot` ends it.
 */
@Serializable
enum class ShotProcessingState {
    /** Sound trigger fired; the capture is being read. */
    @SerialName("capturing")
    CAPTURING,

    @SerialName("calculating")
    CALCULATING,

    @SerialName("failed")
    FAILED,
}

/**
 * The one shot deletion this phone asked the Pi for, and how it went (Expo
 * `useShotDeletionStore.ts`, `feat/delete-shot`).
 *
 * The server answers `delete_shot` with a broadcast that names no shot: `session_state` on
 * success, `delete_shot_error` otherwise. So only one deletion is in flight at a time and a reply
 * is matched to it by whether the shot is still in the session.
 */
sealed interface DeletionState {
    data object Idle : DeletionState

    /** Sent over a live connection; waiting for the server. */
    data class Pending(
        val timestamp: String,
    ) : DeletionState

    data class Deleted(
        val timestamp: String,
    ) : DeletionState

    /** The server refused ([reason] is its text), or the link dropped before it answered. */
    data class Failed(
        val timestamp: String,
        val reason: String,
    ) : DeletionState

    /** The request has left over a live connection. */
    fun begin(timestamp: String): DeletionState = Pending(timestamp)

    /**
     * The server confirmed [timestamp] is gone. Also applies over a failure for the same shot: a
     * drop noticed just after the confirmation must not contradict it. Anything else is unchanged.
     */
    fun succeed(timestamp: String): DeletionState =
        when {
            this is Pending && this.timestamp == timestamp -> Deleted(timestamp)
            this is Failed && this.timestamp == timestamp -> Deleted(timestamp)
            else -> this
        }

    /** Only a deletion still waiting on the server can fail (another client's refusal is ignored). */
    fun fail(reason: String): DeletionState = if (this is Pending) Failed(timestamp, reason) else this

    companion object {
        /** A `delete_shot_error` without a usable message. */
        const val SERVER_REFUSED: String = "The server could not delete this shot."

        /** The link dropped (or the host changed) before the server answered. */
        const val CONNECTION_DROPPED: String = "The connection dropped before the server replied."
    }
}

/**
 * A `clear_session {profile_id}` this phone sent (Expo `feat/stats-tab` `socket.ts`). The server
 * has no error reply, so a clear that is never confirmed fails after [TIMEOUT_MILLIS]. A failure
 * means "not confirmed", not "not cleared": the broadcast that may still follow is applied anyway.
 */
sealed interface ClearState {
    data object Idle : ClearState

    data class Pending(
        val profileId: String,
    ) : ClearState

    data class Cleared(
        val profileId: String,
    ) : ClearState

    data class Failed(
        val profileId: String,
        val reason: String,
    ) : ClearState

    companion object {
        const val TIMEOUT_MILLIS: Long = 10_000

        const val NO_CONFIRMATION: String = "The Pi didn't confirm the clear."

        const val CONNECTION_DROPPED: String = "The connection dropped before the Pi confirmed the clear."
    }
}
