// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.GolfClub
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The control-channel response envelope: `{"schema_version":2,"request_id":...,"ok":bool,
 * "result":{...}|"error":"..."}` (plan §0.1, R8e). The BLE control characteristic always answers
 * in schema 2.
 */
@Serializable
data class ControlResponseEnvelope(
    @SerialName("schema_version") val schemaVersion: Int = SchemaV2Codec.SCHEMA_VERSION,
    @SerialName("request_id") val requestId: String,
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: String? = null,
)

/**
 * `{"schema_version":2,"type":"club_changed","club":"7-iron"}` on BLE and SSE `?schema=2`; `1` on
 * the SSE v1 stream. A missing `schema_version` reads as `1`, as it always has for SSE.
 */
@Serializable
data class ClubChangedEvent(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val type: String = ControlCodec.TYPE_CLUB_CHANGED,
    val club: GolfClub,
)

/** Errors from decoding a control-channel response or event. */
sealed class ControlDecodeError(
    message: String,
) : Exception(message) {
    data class UnsupportedSchema(
        val version: Int,
    ) : ControlDecodeError("Control schema version $version is not supported.")

    data class ServerError(
        val serverMessage: String,
    ) : ControlDecodeError(serverMessage)

    object MissingResult : ControlDecodeError("Control response is missing a result.")

    object NotClubChanged : ControlDecodeError("Not a club_changed event.")
}

/**
 * Decodes control responses (`get_club`/`set_club`, calibration) and the `club_changed` event,
 * ported from `ios/OpenFlight/PhoneControl.swift`. The BLE command encoders are
 * [SchemaV2Codec]'s (schema 2 only); `club_changed` is shared with the SSE stream, which still
 * accepts version one ([V1_AND_V2_SCHEMAS]).
 */
object ControlCodec {
    const val TYPE_SET_CLUB = "set_club"
    const val TYPE_GET_CLUB = "get_club"
    const val TYPE_CALIBRATION = "iwr6843_orientation_calibration"
    const val TYPE_CLUB_CHANGED = "club_changed"

    /** The BLE control characteristic: schema 2 only. */
    val V2_SCHEMAS: IntRange = SchemaV2Codec.SCHEMA_VERSION..SchemaV2Codec.SCHEMA_VERSION

    /** The SSE stream: `?schema=2`, or version one when the Pi answers it with `400` (plan R8e). */
    val V1_AND_V2_SCHEMAS: IntRange = 1..SchemaV2Codec.SCHEMA_VERSION

    fun decodeResponse(payload: ByteArray): ControlResponseEnvelope =
        OpenFlightJson.decodeFromString(ControlResponseEnvelope.serializer(), payload.decodeToString())

    fun decodeClubResult(
        response: ControlResponseEnvelope,
        schemas: IntRange = V2_SCHEMAS,
    ): ClubSelection = OpenFlightJson.decodeFromJsonElement(ClubSelection.serializer(), resultOf(response, schemas))

    fun decodeCalibrationResult(
        response: ControlResponseEnvelope,
        schemas: IntRange = V2_SCHEMAS,
    ): CalibrationResult =
        OpenFlightJson.decodeFromJsonElement(CalibrationResult.serializer(), resultOf(response, schemas))

    /** Decodes `club_changed`: [V2_SCHEMAS] on BLE, [V1_AND_V2_SCHEMAS] on the SSE stream. */
    fun decodeClubChangedEvent(
        payload: ByteArray,
        schemas: IntRange,
    ): GolfClub {
        val event = OpenFlightJson.decodeFromString(ClubChangedEvent.serializer(), payload.decodeToString())
        requireSupportedSchema(event.schemaVersion, schemas)
        if (event.type != TYPE_CLUB_CHANGED) throw ControlDecodeError.NotClubChanged
        return event.club
    }

    private fun resultOf(
        response: ControlResponseEnvelope,
        schemas: IntRange,
    ): JsonElement {
        requireSupportedSchema(response.schemaVersion, schemas)
        requireOk(response)
        return response.result ?: throw ControlDecodeError.MissingResult
    }

    private fun requireSupportedSchema(
        schemaVersion: Int,
        schemas: IntRange,
    ) {
        if (schemaVersion !in schemas) throw ControlDecodeError.UnsupportedSchema(schemaVersion)
    }

    private fun requireOk(response: ControlResponseEnvelope) {
        if (!response.ok) throw ControlDecodeError.ServerError(response.error ?: "unknown control error")
    }
}
