// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** Envelope for a control command, ported from `ios/OpenFlight/PhoneControl.swift`. */
@Serializable
private data class ControlEnvelope<T>(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val type: String,
    @SerialName("request_id") val requestId: String,
    val payload: T,
)

/**
 * The control-channel response envelope: `{"schema_version":1,"request_id":...,"ok":bool,
 * "result":{...}|"error":"..."}` (plan §0.1).
 */
@Serializable
data class ControlResponseEnvelope(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("request_id") val requestId: String,
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: String? = null,
)

/** `{"schema_version":1,"type":"club_changed","club":"7-iron"}` (plan §0.1); `2` on the v2 pair and SSE `?schema=2`. */
@Serializable
data class ClubChangedEvent(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val type: String = ControlCodec.TYPE_CLUB_CHANGED,
    val club: GolfClub,
)

@Serializable
private data class ClubPayload(
    val club: GolfClub,
)

@Serializable
private class EmptyPayload

@Serializable
private data class HelloPayload(
    @SerialName("client_schema_max") val clientSchemaMax: Int = SchemaV2Codec.SCHEMA_VERSION,
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
 * Encodes `set_club`, `get_club` and `iwr6843_orientation_calibration` control envelopes, and
 * decodes both control responses and the `club_changed` event, ported from
 * `ios/OpenFlight/PhoneControl.swift`. Both BLE (framed) and Wi-Fi transports share this so
 * their wire representations of the control channel cannot drift apart.
 */
@Suppress("TooManyFunctions") // One encoder per command, one decoder per result or event.
object ControlCodec {
    const val TYPE_SET_CLUB = "set_club"
    const val TYPE_GET_CLUB = "get_club"
    const val TYPE_CALIBRATION = "iwr6843_orientation_calibration"
    const val TYPE_CLUB_CHANGED = "club_changed"
    const val TYPE_HELLO = "hello"

    /** Version one only: the v1 control characteristic and the default SSE stream. */
    val V1_SCHEMAS: IntRange = 1..1

    /** The v2 control characteristic and SSE `?schema=2` (plan R8e). */
    val V1_AND_V2_SCHEMAS: IntRange = 1..2

    fun encodeSetClub(
        club: GolfClub,
        requestId: String,
    ): ByteArray {
        val envelope = ControlEnvelope(type = TYPE_SET_CLUB, requestId = requestId, payload = ClubPayload(club))
        return OpenFlightJson
            .encodeToString(ControlEnvelope.serializer(ClubPayload.serializer()), envelope)
            .encodeToByteArray()
    }

    fun encodeGetClub(requestId: String): ByteArray {
        val envelope = ControlEnvelope(type = TYPE_GET_CLUB, requestId = requestId, payload = EmptyPayload())
        return OpenFlightJson
            .encodeToString(ControlEnvelope.serializer(EmptyPayload.serializer()), envelope)
            .encodeToByteArray()
    }

    fun encodeCalibration(
        measurement: PhoneOrientationMeasurement,
        requestId: String,
    ): ByteArray {
        val envelope =
            ControlEnvelope(type = TYPE_CALIBRATION, requestId = requestId, payload = measurement)
        return OpenFlightJson
            .encodeToString(ControlEnvelope.serializer(PhoneOrientationMeasurement.serializer()), envelope)
            .encodeToByteArray()
    }

    /**
     * `hello {client_schema_max: 2}` in a **version-one** envelope, for the v1 control
     * characteristic (backend "Negotiation"). The v2 characteristic gets [SchemaV2Codec.encodeHello].
     */
    fun encodeHello(requestId: String): ByteArray {
        val envelope = ControlEnvelope(type = TYPE_HELLO, requestId = requestId, payload = HelloPayload())
        return OpenFlightJson
            .encodeToString(ControlEnvelope.serializer(HelloPayload.serializer()), envelope)
            .encodeToByteArray()
    }

    fun decodeResponse(payload: ByteArray): ControlResponseEnvelope =
        OpenFlightJson.decodeFromString(ControlResponseEnvelope.serializer(), payload.decodeToString())

    fun decodeClubResult(
        response: ControlResponseEnvelope,
        schemas: IntRange = V1_SCHEMAS,
    ): ClubSelection = OpenFlightJson.decodeFromJsonElement(ClubSelection.serializer(), resultOf(response, schemas))

    fun decodeCalibrationResult(
        response: ControlResponseEnvelope,
        schemas: IntRange = V1_SCHEMAS,
    ): CalibrationResult =
        OpenFlightJson.decodeFromJsonElement(CalibrationResult.serializer(), resultOf(response, schemas))

    /** Decodes `club_changed`; [schemas] is [V1_SCHEMAS] unless the link negotiated v2. */
    fun decodeClubChangedEvent(
        payload: ByteArray,
        schemas: IntRange = V1_SCHEMAS,
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
