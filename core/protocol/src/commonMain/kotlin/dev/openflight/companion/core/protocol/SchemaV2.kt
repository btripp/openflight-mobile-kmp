// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import dev.openflight.companion.core.model.pi.PowerStatus
import dev.openflight.companion.core.model.pi.Profile
import dev.openflight.companion.core.model.pi.ShotProcessingState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * One schema v2 event (backend `docs/ios-ble.md` "v2 events"): notified on the BLE v2 control
 * characteristic, or sent as an SSE event named after its `type` on `?schema=2`. Events carry a
 * `type` and never a `request_id`. `club_changed` is not here: both transports already route it to
 * [ShotTransport.activeClub].
 */
sealed interface SchemaV2Event {
    /** `profiles`: ids and names only over BLE (no `created_at`/`settings`). */
    data class Profiles(
        val profiles: List<Profile>,
        val activeProfileId: String,
    ) : SchemaV2Event

    /** `session_cleared`: the profile whose shots the kiosk or a Wi-Fi client cleared. */
    data class SessionCleared(
        val profileId: String?,
    ) : SchemaV2Event

    /** `shot_deleted`: the removed shot's timestamp (the delete key). */
    data class ShotDeleted(
        val timestamp: String,
    ) : SchemaV2Event

    /** `shot_processing`: rolling-buffer progress; the next shot ends it. */
    data class ShotProcessing(
        val state: ShotProcessingState,
    ) : SchemaV2Event

    /** `power_status`, or the result of a `get_power_status` command. */
    data class Power(
        val status: PowerStatus,
    ) : SchemaV2Event
}

/** The `hello` result: the negotiated schema, the Pi's features and (for 2) the v2 pair. */
@Serializable
data class HelloResult(
    @SerialName("schema_version") val schemaVersion: Int,
    val features: List<String> = emptyList(),
    val characteristics: Map<String, String> = emptyMap(),
)

/**
 * v2 events decode leniently: unknown keys (`schema_version`, `type`) are ignored and an unknown
 * `power_status.state` a newer Pi may add reads as [dev.openflight.companion.core.model.pi.PowerState.UNKNOWN].
 */
private val V2Json: Json =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

@Serializable
private data class ProfilesEventBody(
    val profiles: List<ProfileEntry> = emptyList(),
    @SerialName("active_profile_id") val activeProfileId: String? = null,
)

@Serializable
private data class ProfileEntry(
    val id: String,
    val name: String,
)

@Serializable
private data class ShotProcessingBody(
    val state: ShotProcessingState,
)

/**
 * Encodes schema v2 commands and decodes v2 events (backend `src/openflight/ble/protocol.py`,
 * `docs/ios-ble.md` "Schema v2"). v2 messages are compact JSON with sorted keys and raw UTF-8
 * text, like the server's `encode_message_v2`; decode UTF-8 only after reassembly.
 */
@Suppress("TooManyFunctions") // One encoder per command, one decoder per result or event.
object SchemaV2Codec {
    const val SCHEMA_VERSION = 2

    const val TYPE_HELLO = "hello"
    const val TYPE_GET_PROFILES = "get_profiles"
    const val TYPE_SET_ACTIVE_PROFILE = "set_active_profile"
    const val TYPE_GET_POWER_STATUS = "get_power_status"

    const val EVENT_PROFILES = "profiles"
    const val EVENT_SESSION_CLEARED = "session_cleared"
    const val EVENT_SHOT_DELETED = "shot_deleted"
    const val EVENT_SHOT_PROCESSING = "shot_processing"
    const val EVENT_POWER_STATUS = "power_status"

    /** Every v2 event `type`, `club_changed` and `shot` included (SSE event names on `?schema=2`). */
    val EVENT_TYPES: Set<String> =
        setOf(
            "shot",
            ControlCodec.TYPE_CLUB_CHANGED,
            EVENT_PROFILES,
            EVENT_SESSION_CLEARED,
            EVENT_SHOT_DELETED,
            EVENT_SHOT_PROCESSING,
            EVENT_POWER_STATUS,
        )

    /**
     * A v2 command envelope, `{"payload":…,"request_id":…,"schema_version":2,"type":…}` with every
     * object's keys sorted, byte-identical to what the server's own encoder would send.
     */
    fun encodeCommand(
        type: String,
        requestId: String,
        payload: JsonObject = JsonObject(emptyMap()),
    ): ByteArray {
        val envelope =
            JsonObject(
                mapOf(
                    "payload" to payload,
                    "request_id" to JsonPrimitive(requestId),
                    "schema_version" to JsonPrimitive(SCHEMA_VERSION),
                    "type" to JsonPrimitive(type),
                ),
            )
        return OpenFlightJson.encodeToString(JsonElement.serializer(), sortKeys(envelope)).encodeToByteArray()
    }

    /** `hello {client_schema_max: 2}` on the v2 control characteristic. */
    fun encodeHello(requestId: String): ByteArray =
        encodeCommand(TYPE_HELLO, requestId, JsonObject(mapOf("client_schema_max" to JsonPrimitive(SCHEMA_VERSION))))

    fun encodeGetProfiles(requestId: String): ByteArray = encodeCommand(TYPE_GET_PROFILES, requestId)

    fun encodeSetActiveProfile(
        profileId: String,
        requestId: String,
    ): ByteArray =
        encodeCommand(TYPE_SET_ACTIVE_PROFILE, requestId, JsonObject(mapOf("profile_id" to JsonPrimitive(profileId))))

    fun encodeGetPowerStatus(requestId: String): ByteArray = encodeCommand(TYPE_GET_POWER_STATUS, requestId)

    /**
     * Re-encodes a version-one command (`set_club`, `get_club`, calibration) as a v2 envelope: the
     * same type, request id and payload. The v2 control characteristic also accepts v1 envelopes,
     * but answering in kind keeps one encoding per characteristic.
     */
    fun toV2Command(v1Command: ByteArray): ByteArray {
        val v1 = OpenFlightJson.parseToJsonElement(v1Command.decodeToString()).jsonObject
        val type = (v1["type"] as JsonPrimitive).content
        val requestId = (v1["request_id"] as JsonPrimitive).content
        return encodeCommand(type, requestId, v1["payload"]?.jsonObject ?: JsonObject(emptyMap()))
    }

    fun decodeHelloResult(response: ControlResponseEnvelope): HelloResult {
        if (!response.ok) throw ControlDecodeError.ServerError(response.error ?: "hello was refused")
        val result = response.result ?: throw ControlDecodeError.MissingResult
        return V2Json.decodeFromJsonElement(HelloResult.serializer(), result)
    }

    /** The `power_status` payload a `get_power_status` result carries. */
    fun decodePowerStatus(result: JsonElement): PowerStatus =
        V2Json.decodeFromJsonElement(PowerStatus.serializer(), result)

    /**
     * Decodes one v2 event object. Returns `null` for an event this client doesn't consume
     * (`club_changed`, `shot`, or a type a newer server added).
     *
     * @throws IllegalArgumentException for a known event with a malformed body.
     * @throws ControlDecodeError.UnsupportedSchema when `schema_version` isn't 2.
     */
    fun decodeEvent(json: JsonObject): SchemaV2Event? {
        val schema = (json["schema_version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        if (schema != SCHEMA_VERSION) throw ControlDecodeError.UnsupportedSchema(schema ?: 0)
        return when ((json["type"] as? JsonPrimitive)?.contentOrNull) {
            EVENT_PROFILES -> {
                val body = V2Json.decodeFromJsonElement<ProfilesEventBody>(json)
                SchemaV2Event.Profiles(
                    profiles = body.profiles.map { Profile(id = it.id, name = it.name) },
                    activeProfileId = body.activeProfileId.orEmpty(),
                )
            }

            EVENT_SESSION_CLEARED -> {
                SchemaV2Event.SessionCleared((json["profile_id"] as? JsonPrimitive)?.contentOrNull)
            }

            EVENT_SHOT_DELETED -> {
                val timestamp = (json["timestamp"] as? JsonPrimitive)?.contentOrNull
                require(!timestamp.isNullOrEmpty()) { "shot_deleted needs a timestamp" }
                SchemaV2Event.ShotDeleted(timestamp)
            }

            EVENT_SHOT_PROCESSING -> {
                SchemaV2Event.ShotProcessing(V2Json.decodeFromJsonElement<ShotProcessingBody>(json).state)
            }

            EVENT_POWER_STATUS -> {
                SchemaV2Event.Power(decodePowerStatus(json))
            }

            else -> {
                null
            }
        }
    }

    /** [decodeEvent] for a complete, reassembled UTF-8 payload. */
    fun decodeEvent(payload: String): SchemaV2Event? =
        decodeEvent(OpenFlightJson.parseToJsonElement(payload).jsonObject)

    private fun sortKeys(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.keys.sorted().associateWith { sortKeys(element.getValue(it)) })
            else -> element
        }
}

/**
 * The schema v2 commands a transport offers once it negotiated v2 (BLE v2). Read-and-select only:
 * over BLE the Pi refuses deleting, clearing and profile edits (backend "Security and scope").
 */
interface SchemaV2Commands {
    /** `true` while connected with schema v2 negotiated. */
    val schemaV2Active: StateFlow<Boolean>

    /** `get_profiles`: the roster arrives as a [SchemaV2Event.Profiles] event, not as the result. */
    suspend fun requestProfiles()

    /**
     * `get_power_status`. The reading is also published as a [SchemaV2Event.Power] event.
     *
     * @throws Exception the transport's control error when the Pi has no battery monitor or no
     *   reading yet (`ok:false`).
     */
    suspend fun requestPowerStatus(): PowerStatus

    /** `set_active_profile`; the Pi answers with a `profiles` event too (also when it refuses). */
    suspend fun setActiveProfile(profileId: String)
}
