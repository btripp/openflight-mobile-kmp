// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import dev.openflight.companion.core.protocol.OpenFlightJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The backend's server-to-client golden frames (`core/protocol/src/commonTest/fixtures/openflight-ble`,
 * generated into [BleContractFixtures] for this module too), fed to the transport as the Pi would.
 */
internal object BleGoldenFrames {
    fun frames(name: String): List<ByteArray> =
        (golden(name).getValue("frames_hex") as JsonArray).map { it.jsonPrimitive.content.hexToBytes() }

    /** The reassembled payload as text (UTF-8, decoded after reassembly). */
    fun payload(name: String): String =
        golden(name)
            .getValue("payload_hex")
            .jsonPrimitive.content
            .hexToBytes()
            .decodeToString()

    private fun golden(name: String): JsonObject =
        OpenFlightJson.parseToJsonElement(BleContractFixtures.files.getValue("ble_goldens/$name")).jsonObject
}
