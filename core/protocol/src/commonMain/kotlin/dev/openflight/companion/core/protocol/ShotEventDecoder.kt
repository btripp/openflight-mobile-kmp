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
 */
class ShotEventDecoder {
    private var lastEventId: String? = null

    /** Returns the shot when it is new, or `null` when it repeats the last decoded id. */
    fun decode(payload: ByteArray): ShotEvent? = decode(payload.decodeToString())

    fun decode(payload: String): ShotEvent? {
        val shot = OpenFlightJson.decodeFromString(ShotEvent.serializer(), payload)
        if (shot.schemaVersion != 1) throw ShotDecodeError.UnsupportedSchema(shot.schemaVersion)
        if (shot.eventId == lastEventId) return null
        lastEventId = shot.eventId
        return shot
    }

    fun reset() {
        lastEventId = null
    }
}
