// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import dev.openflight.companion.core.model.ShotEvent

/** Ported from `ios/OpenFlight/ShotEventDecoder.swift`'s `ShotDecodeError`. */
sealed class ShotDecodeError(
    message: String,
) : Exception(message) {
    data class UnsupportedSchema(
        val version: Int,
    ) : ShotDecodeError("Shot schema version $version is not supported.")

    /** A v2 payload whose `type` isn't `"shot"`. */
    data class NotAShot(
        val type: String,
    ) : ShotDecodeError("Expected a shot, got a '$type' event.")
}

/**
 * Turns a complete shot payload into a [ShotEvent], rejecting unknown schema versions and
 * suppressing the replay every transport sends on connect. Ported from
 * `ios/OpenFlight/ShotEventDecoder.swift`.
 *
 * Both transports share this so the BLE and Wi-Fi paths cannot drift apart in how they validate
 * or de-duplicate what the Pi sends. Per plan §0.3: BLE never resets this decoder, and Wi-Fi
 * resets it only on an explicit user `disconnect()`/`retry()`, never inside the auto-reconnect
 * loop -- that's what suppresses the replay the server sends on every new connection.
 *
 * Plan R8e: schema 1 and 2 are accepted, anything else is rejected. A v2 provisional shot and its
 * final version share one `event_id`, so for v2 the replay key is the id **and** `final`: the final
 * version passes, a replay of either version doesn't. v1 keeps the id alone.
 */
class ShotEventDecoder {
    private var lastKey: Pair<String, Boolean?>? = null

    /** Returns the shot when it is new, or `null` when it repeats the last decoded one. */
    fun decode(payload: ByteArray): ShotEvent? = decode(payload.decodeToString())

    fun decode(payload: String): ShotEvent? {
        val shot = OpenFlightJson.decodeFromString(ShotEvent.serializer(), payload)
        if (shot.schemaVersion !in SUPPORTED_SCHEMAS) throw ShotDecodeError.UnsupportedSchema(shot.schemaVersion)
        val type = shot.type
        if (shot.schemaVersion >= 2 && type != null && type != SHOT_TYPE) throw ShotDecodeError.NotAShot(type)
        val key = shot.eventId to shot.final.takeIf { shot.schemaVersion >= 2 }
        if (key == lastKey) return null
        lastKey = key
        return shot
    }

    fun reset() {
        lastKey = null
    }

    private companion object {
        val SUPPORTED_SCHEMAS = 1..2
        const val SHOT_TYPE = "shot"
    }
}
