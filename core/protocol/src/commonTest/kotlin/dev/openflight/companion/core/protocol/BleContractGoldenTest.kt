// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import assertk.all
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.pi.PowerState
import dev.openflight.companion.core.model.pi.ShotProcessingState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

/**
 * Plan R8e: the backend's cross-language BLE goldens (`tests/fixtures/ble_goldens/`, copied under
 * `src/commonTest/fixtures/openflight-ble`) decoded by this client, and this client's command
 * encoders checked against the backend's `client_to_server` goldens byte for byte.
 */
class BleContractGoldenTest {
    // region server -> client

    @Test
    fun everyServerGoldenReassemblesToItsPayloadInAnyOrder() {
        val goldens = BleGolden.all().filter { it.direction == "server_to_client" }
        assertThat(goldens).hasSize(SERVER_GOLDEN_COUNT)

        for (golden in goldens) {
            assertThat(golden.frames.all { it.size <= BleFrameReassembler.MAXIMUM_FRAME_SIZE }).isTrue()
            val inOrder = reassemble(golden.frames)
            val reversed = reassemble(golden.frames.asReversed())

            assertThat(inOrder?.toList(), golden.name).isEqualTo(golden.payload.toList())
            assertThat(reversed?.toList(), golden.name).isEqualTo(golden.payload.toList())
            assertThat(golden.frames.map { it.sequence() }.toSet(), golden.name).containsOnly(golden.sequence)
            // UTF-8 is decoded only after reassembly: the payload parses to exactly the message.
            assertThat(OpenFlightJson.parseToJsonElement(inOrder!!.decodeToString()), golden.name)
                .isEqualTo(golden.message)
        }
    }

    @Test
    fun theV1ShotGoldenDecodesLikeTheSharedFixture() {
        val shot = ShotEventDecoder().decode(serverPayload("v1_shot"))

        assertThat(shot).isNotNull().all {
            prop("schemaVersion") { it.schemaVersion }.isEqualTo(1)
            prop("eventId") { it.eventId }.isEqualTo("B0D91F0A-7950-4D7E-9DD5-AF9777C190E1")
            prop("ballSpeedMph") { it.ballSpeedMph }.isEqualTo(151.4)
            prop("final") { it.final }.isNull()
            prop("isProvisional") { it.isProvisional }.isFalse()
        }
    }

    @Test
    fun theV2ProvisionalAndFinalShotsShareAnEventIdAndBothPassTheDecoder() {
        val decoder = ShotEventDecoder()

        val provisional = decoder.decode(serverPayload("v2_shot_provisional"))
        val final = decoder.decode(serverPayload("v2_shot_final"))
        val replayedFinal = decoder.decode(serverPayload("v2_shot_final"))

        assertThat(provisional).isNotNull().all {
            prop("schemaVersion") { it.schemaVersion }.isEqualTo(2)
            prop("isProvisional") { it.isProvisional }.isTrue()
            prop("enrichment") { it.enrichment?.status }.isEqualTo("pending")
            prop("profileName") { it.profileName }.isEqualTo("Zoë")
        }
        assertThat(final).isNotNull().all {
            prop("eventId") { it.eventId }.isEqualTo(provisional!!.eventId)
            prop("final") { it.final }.isEqualTo(true)
            prop("shotNumber") { it.shotNumber }.isEqualTo(7)
            prop("profileId") { it.profileId }.isEqualTo("0f8e4b2a9c7d4e1f8a6b3c5d7e9f1a2b")
            prop("carryRange") { it.carryRange }.isEqualTo(listOf(144.0, 160.0))
            prop("launchAngleConfidence") { it.launchAngleConfidence }.isEqualTo(0.6)
            prop("spinSource") { it.spinSource }.isNull()
            prop("enrichment") { it.enrichment?.status }.isEqualTo("complete")
        }
        assertThat(replayedFinal).isNull()
    }

    @Test
    fun theShotV2ContractFixtureDecodes() {
        val shot = ShotEventDecoder().decode(BleContractFixtures.files.getValue("shot_v2"))

        assertThat(shot).isNotNull().all {
            prop("type") { it.type }.isEqualTo("shot")
            prop("final") { it.final }.isEqualTo(true)
            prop("club") { it.club }.isEqualTo("7-iron")
            prop("estimatedCarryYards") { it.estimatedCarryYards }.isEqualTo(152.0)
        }
    }

    @Test
    fun clubChangedGoldensDecodeOnTheirCharacteristic() {
        assertThat(ControlCodec.decodeClubChangedEvent(serverPayload("v1_club_changed"))).isEqualTo(GolfClub.IRON_7)
        assertThat(
            ControlCodec.decodeClubChangedEvent(serverPayload("v2_event_club_changed"), ControlCodec.V1_AND_V2_SCHEMAS),
        ).isEqualTo(GolfClub.IRON_7)
    }

    @Test
    fun v2EventGoldensDecode() {
        val profiles = decodeEvent("v2_event_profiles")
        assertThat(profiles).isNotNull().isInstanceOf<SchemaV2Event.Profiles>().all {
            prop("names") { event -> event.profiles.map { it.name } }.containsExactly("Zoë ⛳", "Sam")
            prop("active") { it.activeProfileId }.isEqualTo("0f8e4b2a9c7d4e1f8a6b3c5d7e9f1a2b")
        }
        assertThat(decodeEvent("v2_event_profiles_worst_case"))
            .isNotNull()
            .isInstanceOf<SchemaV2Event.Profiles>()
            .prop("count") { it.profiles.size }
            .isEqualTo(12)
        assertThat(decodeEvent("v2_event_session_cleared"))
            .isEqualTo(SchemaV2Event.SessionCleared("0f8e4b2a9c7d4e1f8a6b3c5d7e9f1a2b"))
        assertThat(decodeEvent("v2_event_shot_deleted"))
            .isEqualTo(SchemaV2Event.ShotDeleted("2026-09-25T14:03:07.412345"))
        assertThat(decodeEvent("v2_event_shot_processing"))
            .isEqualTo(SchemaV2Event.ShotProcessing(ShotProcessingState.CALCULATING))
        assertThat(decodeEvent("v2_event_power_status")).isNotNull().isInstanceOf<SchemaV2Event.Power>().all {
            prop("state") { it.status.state }.isEqualTo(PowerState.ON_BATTERY)
            prop("percent") { it.status.batteryPercent }.isEqualTo(76.5)
            prop("voltage") { it.status.batteryVoltageV }.isEqualTo(3.98)
            prop("external") { it.status.externalPower }.isEqualTo(false)
        }
        // club_changed has its own decoder; it is not a SchemaV2Event.
        assertThat(decodeEvent("v2_event_club_changed")).isNull()
    }

    @Test
    fun responseGoldensDecode() {
        val getClub = ControlCodec.decodeResponse(serverPayload("v1_response_get_club"))
        assertThat(ControlCodec.decodeClubResult(getClub).club).isEqualTo(GolfClub.IRON_7)

        for (name in listOf("v1_response_hello", "v2_response_hello")) {
            val hello = SchemaV2Codec.decodeHelloResult(ControlCodec.decodeResponse(serverPayload(name)))
            assertThat(hello.schemaVersion, name).isEqualTo(2)
            assertThat(hello.features, name).containsExactly(
                "provisional_shots",
                "shot_processing",
                "profiles",
                "power_status",
                "shot_deleted",
                "club",
            )
            assertThat(hello.characteristics["shot"], name).isEqualTo("ED365FE6-3ABF-4FC3-8E44-D9525A22DABD")
            assertThat(hello.characteristics["control"], name).isEqualTo("7BA96E63-12C2-4CE0-BB84-3513C7FD1474")
        }

        val oldPi = ControlCodec.decodeResponse(serverPayload("v1_response_unknown_command"))
        assertThat(oldPi.ok).isFalse()
        assertThat(oldPi.error).isEqualTo("Unsupported phone command: hello")

        val refused = ControlCodec.decodeResponse(serverPayload("v2_response_error"))
        assertThat(refused.schemaVersion).isEqualTo(2)
        assertThat(refused.error).isEqualTo("Unknown profile")

        val selected = ControlCodec.decodeResponse(serverPayload("v2_response_set_active_profile"))
        assertThat(
            selected.result
                ?.jsonObject
                ?.get("active_profile_id")
                ?.jsonPrimitive
                ?.content,
        ).isEqualTo("7c1d9e3f5a2b4c6d8e0f1a3b5c7d9e1f")
    }

    // endregion

    // region client -> server

    @Test
    fun theV1HelloEncoderMatchesTheClientGoldenByteForByte() {
        val golden = BleGolden.named("client_v1_hello")

        val payload = ControlCodec.encodeHello(golden.requestId)

        assertThat(payload.toHex()).isEqualTo(golden.payload.toHex())
        assertThat(BleFrameEncoder.frames(payload, golden.sequence).map { it.toHex() })
            .isEqualTo(golden.frames.map { it.toHex() })
    }

    @Test
    fun theV2CommandEncoderMatchesTheGetProfilesGoldenByteForByte() {
        val golden = BleGolden.named("client_v2_get_profiles")

        val payload = SchemaV2Codec.encodeGetProfiles(golden.requestId)

        assertThat(payload.toHex()).isEqualTo(golden.payload.toHex())
        assertThat(BleFrameEncoder.frames(payload, golden.sequence).map { it.toHex() })
            .isEqualTo(golden.frames.map { it.toHex() })
    }

    /**
     * The backend hand-built this golden with unsorted keys and spaces "as a non-Python encoder may
     * send", so no canonical encoder reproduces its bytes. Ours sends the same message, compact
     * and sorted, and the golden's frames reassemble with this client's reassembler.
     */
    @Test
    fun theV2SetClubEncoderSendsTheGoldenMessage() {
        val golden = BleGolden.named("client_v2_set_club")

        val payload =
            SchemaV2Codec.toV2Command(ControlCodec.encodeSetClub(GolfClub.IRON_7, golden.requestId))

        assertThat(reassemble(golden.frames)?.toHex()).isEqualTo(golden.payload.toHex())
        assertThat(OpenFlightJson.parseToJsonElement(payload.decodeToString())).isEqualTo(golden.message)
        assertThat(payload.decodeToString()).isEqualTo(
            """{"payload":{"club":"7-iron"},"request_id":"${golden.requestId}","schema_version":2,"type":"set_club"}""",
        )
    }

    @Test
    fun theV2HelloAndSelectCommandsAreSortedV2Envelopes() {
        assertThat(SchemaV2Codec.encodeHello("r").decodeToString())
            .isEqualTo("""{"payload":{"client_schema_max":2},"request_id":"r","schema_version":2,"type":"hello"}""")
        assertThat(SchemaV2Codec.encodeSetActiveProfile("p1", "r").decodeToString())
            .isEqualTo(
                """{"payload":{"profile_id":"p1"},"request_id":"r","schema_version":2,"type":"set_active_profile"}""",
            )
        assertThat(SchemaV2Codec.encodeGetPowerStatus("r").decodeToString())
            .isEqualTo("""{"payload":{},"request_id":"r","schema_version":2,"type":"get_power_status"}""")
    }

    @Test
    fun aV2MessageLongerThanOneFrameStillFitsTheMessageBudget() {
        val worstCase = BleGolden.named("v2_event_profiles_worst_case")

        assertThat(worstCase.frames.size).isGreaterThan(1)
        assertThat(worstCase.frames.size).isLessThanOrEqualTo(BleFrameEncoder.MAXIMUM_FRAGMENT_COUNT)
    }

    // endregion

    private fun serverPayload(name: String): ByteArray = reassemble(BleGolden.named(name).frames)!!

    private fun decodeEvent(name: String): SchemaV2Event? =
        SchemaV2Codec.decodeEvent(serverPayload(name).decodeToString())

    private fun reassemble(frames: List<ByteArray>): ByteArray? {
        val reassembler = BleFrameReassembler()
        var message: ByteArray? = null
        for (frame in frames) message = reassembler.append(frame) ?: message
        return message
    }

    private companion object {
        const val SERVER_GOLDEN_COUNT = 17
    }
}

/** One backend golden file (`scripts/ble/generate_goldens.py` layout). */
internal class BleGolden(
    val name: String,
    json: JsonObject,
) {
    val direction: String = json.string("direction")
    val characteristic: String = json.string("characteristic")
    val sequence: Int = json.getValue("sequence").jsonPrimitive.int
    val message: JsonElement = json.getValue("message")
    val payload: ByteArray = json.string("payload_hex").hexToBytes()
    val frames: List<ByteArray> =
        (
            json.getValue(
                "frames_hex",
            ) as JsonArray
        ).map { it.jsonPrimitive.content.hexToBytes() }
    val requestId: String get() = message.jsonObject.string("request_id")

    companion object {
        private const val PREFIX = "ble_goldens/"

        fun all(): List<BleGolden> =
            BleContractFixtures.files
                .filterKeys { it.startsWith(PREFIX) }
                .map { (key, text) ->
                    BleGolden(key.removePrefix(PREFIX), OpenFlightJson.parseToJsonElement(text).jsonObject)
                }

        fun named(name: String): BleGolden =
            BleGolden(
                name,
                OpenFlightJson.parseToJsonElement(BleContractFixtures.files.getValue(PREFIX + name)).jsonObject,
            )
    }
}

private fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).content

internal fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { i -> ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte() }

internal fun ByteArray.toHex(): String = joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

private fun ByteArray.sequence(): Int = ((this[1].toInt() and 0xFF) shl 8) or (this[2].toInt() and 0xFF)
