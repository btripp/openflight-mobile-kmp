// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `sim_status`: one simulator connector (e.g. GSPro). The on-connect snapshot carries only
 * target/state/host/port; changes add attempt, retry delay and message.
 */
@Serializable
data class SimStatus(
    val target: String,
    /** `connected`, `connecting`, `reconnecting`, `disabled`, `stopped` or `error`. */
    val state: String,
    val host: String? = null,
    val port: Int? = null,
    val attempt: Int? = null,
    @SerialName("next_retry_in_s") val nextRetryInS: Double? = null,
    val message: String? = null,
)

/** `sim_shot`: a shot the Pi forwarded to a simulator, with each value's provenance. */
@Serializable
data class SimShot(
    val target: String,
    @SerialName("shot_number") val shotNumber: Int,
    val fields: List<String> = emptyList(),
    val values: Map<String, Double?> = emptyMap(),
    /** Field → `measured` or `estimated`. */
    val provenance: Map<String, String> = emptyMap(),
)

/** `sim_player`: a simulator changed the player's handedness or club. */
@Serializable
data class SimPlayer(
    val target: String,
    val handed: String? = null,
    val club: String? = null,
)

/** Everything the simulator connectors reported this connection. */
data class SimState(
    /** Latest status per connector target. */
    val connectors: Map<String, SimStatus> = emptyMap(),
    val latestShot: SimShot? = null,
    val latestPlayer: SimPlayer? = null,
)

/** State of a manual FlightWeb cloud upload (`cloud_upload_status`). */
@Serializable
enum class CloudUploadState {
    @SerialName("idle")
    IDLE,

    @SerialName("running")
    RUNNING,

    @SerialName("complete")
    COMPLETE,

    @SerialName("error")
    ERROR,
}

/** `cloud_upload_status`. [summary] is `cmd_push`'s result dict, present on completion. */
@Serializable
data class CloudUploadStatus(
    val state: CloudUploadState = CloudUploadState.IDLE,
    val message: String? = null,
    val summary: JsonObject? = null,
)
