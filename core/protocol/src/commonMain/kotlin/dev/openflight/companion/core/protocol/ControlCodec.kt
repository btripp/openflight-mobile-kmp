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

/** `{"schema_version":1,"type":"club_changed","club":"7-iron"}` (plan §0.1). */
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
object ControlCodec {
    const val TYPE_SET_CLUB = "set_club"
    const val TYPE_GET_CLUB = "get_club"
    const val TYPE_CALIBRATION = "iwr6843_orientation_calibration"
    const val TYPE_CLUB_CHANGED = "club_changed"

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

    fun decodeResponse(payload: ByteArray): ControlResponseEnvelope =
        OpenFlightJson.decodeFromString(ControlResponseEnvelope.serializer(), payload.decodeToString())

    fun decodeClubResult(response: ControlResponseEnvelope): ClubSelection =
        OpenFlightJson.decodeFromJsonElement(ClubSelection.serializer(), resultOf(response))

    fun decodeCalibrationResult(response: ControlResponseEnvelope): CalibrationResult =
        OpenFlightJson.decodeFromJsonElement(CalibrationResult.serializer(), resultOf(response))

    fun decodeClubChangedEvent(payload: ByteArray): GolfClub {
        val event = OpenFlightJson.decodeFromString(ClubChangedEvent.serializer(), payload.decodeToString())
        requireSupportedSchema(event.schemaVersion)
        if (event.type != TYPE_CLUB_CHANGED) throw ControlDecodeError.NotClubChanged
        return event.club
    }

    private fun resultOf(response: ControlResponseEnvelope): JsonElement {
        requireSupportedSchema(response.schemaVersion)
        requireOk(response)
        return response.result ?: throw ControlDecodeError.MissingResult
    }

    private fun requireSupportedSchema(schemaVersion: Int) {
        if (schemaVersion != 1) throw ControlDecodeError.UnsupportedSchema(schemaVersion)
    }

    private fun requireOk(response: ControlResponseEnvelope) {
        if (!response.ok) throw ControlDecodeError.ServerError(response.error ?: "unknown control error")
    }
}
