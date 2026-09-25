// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import dev.openflight.companion.core.ble.OpenFlightBleProfile.CONTROL_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.CONTROL_V2_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SHOT_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SHOT_V2_CHARACTERISTIC_UUID
import dev.openflight.companion.core.protocol.BleFrameReassembler
import dev.openflight.companion.core.protocol.OpenFlightJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A scripted OpenFlight Pi behind [FakePeripheralLink] (plan R8e "Testing without hardware"). It
 * reassembles what the phone writes, answers like the backend's `BleShotPublisher` and dispatch
 * (`src/openflight/ble/publisher.py`, `docs/ios-ble.md`): responses in the characteristic's schema,
 * events before their command's response, one frame sequence per characteristic.
 *
 * [schemaV2] `false` is a Pi without the v2 pair (backend main + R8a, or jfish's fork).
 */
internal class ScriptedPi(
    private val schemaV2: Boolean = true,
) {
    enum class HelloAnswer {
        /** `{"schema_version":2,"features":[...],"characteristics":{...}}`. */
        V2,

        /** `ok:false` "Unsupported phone command: hello" (a Pi that predates negotiation). */
        UNSUPPORTED,

        /** Never answers. */
        SILENT,
    }

    var hello = HelloAnswer.V2
    var club = "driver"
    var profiles = listOf("p1" to "Zoë ⛳", "p2" to "Sam")
    var activeProfileId = "p1"

    /** The `get_power_status` result, or `null` for a Pi without `--battery`. */
    var powerJson: String? = null

    /** Every command the phone completed, with the characteristic it was written to. */
    val commands = mutableListOf<Pair<String, JsonObject>>()

    private val reassemblers = mutableMapOf<String, BleFrameReassembler>()
    private val sequences = mutableMapOf<String, Int>()

    /** A fresh single-use peripheral for the next connection. */
    fun link(): FakePeripheralLink {
        val characteristics =
            if (schemaV2) {
                setOf(
                    SHOT_CHARACTERISTIC_UUID,
                    CONTROL_CHARACTERISTIC_UUID,
                    SHOT_V2_CHARACTERISTIC_UUID,
                    CONTROL_V2_CHARACTERISTIC_UUID,
                )
            } else {
                setOf(SHOT_CHARACTERISTIC_UUID, CONTROL_CHARACTERISTIC_UUID)
            }
        reassemblers.clear()
        return FakePeripheralLink(characteristics).also { link ->
            link.onWrite = { uuid, frame -> receive(link, uuid, frame) }
        }
    }

    fun commandTypes(): List<String> = commands.map { (_, command) -> command.getValue("type").jsonPrimitive.content }

    /** Notifies a v2 event object (sorted keys like `encode_message_v2`) on the v2 control characteristic. */
    fun notifyEvent(
        link: FakePeripheralLink,
        json: String,
    ) = notify(link, CONTROL_V2_CHARACTERISTIC_UUID, json)

    fun notify(
        link: FakePeripheralLink,
        characteristicUuid: String,
        json: String,
    ) {
        val frames = makeBleFrames(json.encodeToByteArray(), nextSequence(characteristicUuid))
        when (characteristicUuid) {
            CONTROL_CHARACTERISTIC_UUID -> link.notifyControl(frames)
            CONTROL_V2_CHARACTERISTIC_UUID -> link.notifyControlV2(frames)
            SHOT_CHARACTERISTIC_UUID -> link.notifyShot(frames)
            else -> link.notifyShotV2(frames)
        }
    }

    private fun nextSequence(characteristicUuid: String): Int {
        val sequence = sequences[characteristicUuid] ?: 0
        sequences[characteristicUuid] = (sequence + 1) and 0xFFFF
        return sequence
    }

    private fun receive(
        link: FakePeripheralLink,
        characteristicUuid: String,
        frame: ByteArray,
    ) {
        val payload = reassemblers.getOrPut(characteristicUuid) { BleFrameReassembler() }.append(frame) ?: return
        val command = OpenFlightJson.parseToJsonElement(payload.decodeToString()).jsonObject
        commands += characteristicUuid to command
        answer(link, characteristicUuid, command)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per backend command.
    private fun answer(
        link: FakePeripheralLink,
        uuid: String,
        command: JsonObject,
    ) {
        val requestId = command.getValue("request_id").jsonPrimitive.content
        val payload = command["payload"]?.jsonObject ?: JsonObject(emptyMap())
        val schema = if (uuid == CONTROL_V2_CHARACTERISTIC_UUID) 2 else 1

        fun ok(result: String) =
            respond(link, uuid, """{"ok":true,"request_id":"$requestId","result":$result,"schema_version":$schema}""")

        fun error(message: String) =
            respond(
                link,
                uuid,
                """{"error":"$message","ok":false,"request_id":"$requestId","schema_version":$schema}""",
            )

        when (val type = command.getValue("type").jsonPrimitive.content) {
            "hello" -> {
                when (hello) {
                    HelloAnswer.V2 -> ok(HELLO_RESULT)
                    HelloAnswer.UNSUPPORTED -> error("Unsupported phone command: hello")
                    HelloAnswer.SILENT -> Unit
                }
            }

            "get_club" -> {
                ok("""{"club":"$club","status":"current"}""")
            }

            "set_club" -> {
                club = payload.string("club")
                notify(link, uuid, """{"club":"$club","schema_version":$schema,"type":"club_changed"}""")
                ok("""{"club":"$club","status":"applied"}""")
            }

            "get_profiles" -> {
                notifyEvent(link, profilesEvent())
                ok("""{"status":"sent"}""")
            }

            "set_active_profile" -> {
                val id = payload.string("profile_id")
                val known = profiles.any { it.first == id }
                if (known) activeProfileId = id
                notifyEvent(link, profilesEvent())
                if (known) ok("""{"active_profile_id":"$id","status":"applied"}""") else error("Unknown profile")
            }

            "get_power_status" -> {
                val power = powerJson
                if (power == null) error("Battery monitoring is not enabled") else ok(power)
            }

            else -> {
                error("Unsupported phone command: $type")
            }
        }
    }

    private fun respond(
        link: FakePeripheralLink,
        uuid: String,
        json: String,
    ) = notify(link, uuid, json)

    fun profilesEvent(): String {
        val entries = profiles.joinToString(",") { (id, name) -> """{"id":"$id","name":"$name"}""" }
        return """{"active_profile_id":"$activeProfileId","profiles":[$entries],"schema_version":2,"type":"profiles"}"""
    }

    private fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).content

    companion object {
        const val HELLO_RESULT =
            """{"characteristics":{"control":"7BA96E63-12C2-4CE0-BB84-3513C7FD1474",""" +
                """"shot":"ED365FE6-3ABF-4FC3-8E44-D9525A22DABD"},"features":["provisional_shots",""" +
                """"shot_processing","profiles","power_status","shot_deleted","club"],"schema_version":2}"""
    }
}

/** A written command as JSON, reassembled from the frames the transport wrote to [characteristicUuid]. */
internal fun FakePeripheralLink.commandsWrittenTo(characteristicUuid: String): List<JsonElement> =
    decodeWrittenCommands(writes.filterIndexed { index, _ -> writeTargets[index] == characteristicUuid })
