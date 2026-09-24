// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.protocol.BleFrameEncoder
import dev.openflight.companion.core.protocol.BleFrameError
import dev.openflight.companion.core.protocol.BleFrameReassembler
import dev.openflight.companion.core.protocol.ControlCodec
import dev.openflight.companion.core.protocol.ControlDecodeError
import dev.openflight.companion.core.protocol.ControlResponseEnvelope
import dev.openflight.companion.core.protocol.OpenFlightJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** What one control-characteristic notification amounted to, once reassembled. */
internal sealed interface ControlInbound {
    /** A fragment of a longer message, a response (matched or not), or a failure routed to the pending request. */
    data object Consumed : ControlInbound

    data class ClubChanged(
        val club: GolfClub,
    ) : ControlInbound

    /** A `club_changed` event the transport could not decode (plan §0.3: surfaces as an error). */
    data class ClubChangedInvalid(
        val message: String,
    ) : ControlInbound
}

/**
 * The phone-control request/response half of `BluetoothManager.swift`: one request in flight at a
 * time, a wrapping u16 frame sequence, a 10 s timeout, frames written with response one at a time,
 * and responses matched by `request_id`.
 *
 * Not thread-safe: [BleShotTransport] confines every call to one single-threaded dispatcher, the
 * same way the reference is `@MainActor`.
 */
internal class ControlChannel(
    private val requestIds: () -> String,
    private val timeout: kotlin.time.Duration,
    initialSequence: Int = 0,
) {
    private class PendingRequest(
        val requestId: String,
    ) {
        val response = CompletableDeferred<ControlResponseEnvelope>()
    }

    private var sequence = initialSequence and U16_MASK
    private val reassembler = BleFrameReassembler()
    private var pending: PendingRequest? = null

    /**
     * Encodes a request with a fresh id, writes its frames through [write] (each call must
     * suspend until the write-with-response callback), and awaits the matching response.
     *
     * @throws BleControlException.Busy when another request is still in flight.
     * @throws BleControlException.TimedOut when no response arrives within the timeout.
     */
    suspend fun send(
        encode: (requestId: String) -> ByteArray,
        write: suspend (ByteArray) -> Unit,
    ): ControlResponseEnvelope {
        if (pending != null) throw BleControlException.Busy()
        val request = PendingRequest(requestIds())
        val frameSequence = sequence
        sequence = (sequence + 1) and U16_MASK
        val frames = BleFrameEncoder.frames(encode(request.requestId), frameSequence)
        pending = request
        try {
            return withTimeout(timeout) {
                launch { writeFrames(frames, write, request) }
                request.response.await()
            }
        } catch (timeout: TimeoutCancellationException) {
            throw BleControlException.TimedOut(timeout)
        } finally {
            if (pending === request) finish()
        }
    }

    /** Routes one control notification: a `club_changed` event, a response, or a fragment. */
    fun receive(frame: ByteArray): ControlInbound {
        val payload = reassemble(frame)
        val json = payload?.let(::parseObject)
        return when {
            payload == null || json == null -> {
                ControlInbound.Consumed
            }

            (json["type"] as? JsonPrimitive)?.content == ControlCodec.TYPE_CLUB_CHANGED -> {
                decodeClubChanged(payload)
            }

            else -> {
                completeRequest(json)
                ControlInbound.Consumed
            }
        }
    }

    private fun reassemble(frame: ByteArray): ByteArray? =
        try {
            reassembler.append(frame)
        } catch (error: BleFrameError) {
            fail(BleControlException.InvalidResponse(error))
            null
        }

    /** Fails the in-flight request, if any (the reference's `failPendingControl`). */
    fun fail(error: BleControlException) {
        val request = pending ?: return
        finish()
        request.response.completeExceptionally(error)
    }

    /** Drops any half-received control message (on disconnect). */
    fun resetFrames() {
        reassembler.reset()
    }

    private suspend fun writeFrames(
        frames: List<ByteArray>,
        write: suspend (ByteArray) -> Unit,
        request: PendingRequest,
    ) {
        for (frame in frames) {
            try {
                write(frame)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                // Platform BLE stacks throw assorted IOException/IllegalStateException subtypes.
                if (pending === request) fail(BleControlException.Failed(error))
                return
            }
        }
    }

    private fun parseObject(payload: ByteArray): JsonObject? =
        try {
            OpenFlightJson.parseToJsonElement(payload.decodeToString()).jsonObject
        } catch (error: IllegalArgumentException) {
            // SerializationException (malformed JSON) and a non-object root both land here.
            fail(BleControlException.InvalidResponse(error))
            null
        }

    private fun decodeClubChanged(payload: ByteArray): ControlInbound =
        try {
            ControlInbound.ClubChanged(ControlCodec.decodeClubChangedEvent(payload))
        } catch (error: IllegalArgumentException) {
            // SerializationException (an unknown club, a missing field) is an IllegalArgumentException.
            ControlInbound.ClubChangedInvalid(error.message ?: INVALID_CLUB_EVENT)
        } catch (error: ControlDecodeError) {
            // schema_version != 1: the reference throws invalidResponse here.
            ControlInbound.ClubChangedInvalid(error.message ?: INVALID_CLUB_EVENT)
        }

    /** Ports `completeControlRequest`: a malformed envelope fails the pending request; a foreign id is ignored. */
    private fun completeRequest(json: JsonObject) {
        val schemaVersion = (json["schema_version"] as? JsonPrimitive)?.intOrNull
        val requestId = (json["request_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val ok = (json["ok"] as? JsonPrimitive)?.booleanOrNull
        if (schemaVersion != 1 || requestId == null || ok == null) {
            fail(BleControlException.InvalidResponse())
            return
        }
        val request = pending?.takeIf { it.requestId == requestId } ?: return
        when {
            !ok -> {
                val message = (json["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                fail(BleControlException.Rejected(message ?: DEFAULT_REJECTION))
            }

            json["result"] !is JsonObject -> {
                fail(BleControlException.InvalidResponse())
            }

            else -> {
                finish()
                request.response.complete(
                    ControlResponseEnvelope(requestId = requestId, ok = true, result = json["result"]),
                )
            }
        }
    }

    private fun finish() {
        pending = null
        reassembler.reset()
    }

    private companion object {
        const val U16_MASK = 0xFFFF
        const val DEFAULT_REJECTION = "OpenFlight rejected the phone command."
        const val INVALID_CLUB_EVENT = "OpenFlight sent an invalid club update."
    }
}
