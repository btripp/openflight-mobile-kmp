// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import dev.openflight.companion.core.ble.OpenFlightBleProfile.CONTROL_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.CONTROL_V2_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SERVICE_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SHOT_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SHOT_V2_CHARACTERISTIC_UUID
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.PowerStatus
import dev.openflight.companion.core.protocol.BleFrameError
import dev.openflight.companion.core.protocol.BleFrameReassembler
import dev.openflight.companion.core.protocol.ControlCodec
import dev.openflight.companion.core.protocol.ControlDecodeError
import dev.openflight.companion.core.protocol.ControlResponseEnvelope
import dev.openflight.companion.core.protocol.SchemaV2Codec
import dev.openflight.companion.core.protocol.SchemaV2Commands
import dev.openflight.companion.core.protocol.SchemaV2Event
import dev.openflight.companion.core.protocol.ShotDecodeError
import dev.openflight.companion.core.protocol.ShotEventDecoder
import dev.openflight.companion.core.protocol.ShotTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Timing and id knobs; production uses the defaults, tests shrink nothing and run on virtual time. */
internal data class BleTransportConfig(
    val reconnectDelay: Duration = 1.seconds,
    val controlTimeout: Duration = 10.seconds,
    val initialControlSequence: Int = 0,
    val requestIds: () -> String = ::lowercaseUuid,
)

@OptIn(ExperimentalUuidApi::class)
private fun lowercaseUuid(): String = Uuid.random().toString().lowercase()

/**
 * Live shots and phone controls over BLE, ported from `ios/OpenFlight/BluetoothManager.swift`
 * (plan §0.1, §0.3, step 5).
 *
 * Lifecycle: `start()` scans for the OpenFlight service, connects to the first advertiser,
 * subscribes to shot notifications (→ [ConnectionState.Connected]) and, when the Pi has it, to the
 * control characteristic (→ [supportsControls]). A dropped connection fails any pending control
 * request, resets both frame reassemblers (never the shot decoder, so the replay the Pi sends on
 * resubscribe is suppressed), and rescans after one second.
 *
 * Threading: every mutable field is touched only on [scope]'s dispatcher, which must be
 * single-threaded (the reference is `@MainActor`). The platform factories supply one.
 *
 * Platform notes:
 * - v1 is foreground-only like the reference: no CoreBluetooth state restoration and no
 *   `bluetooth-central` background mode (Kable's `CentralManager.configure { stateRestoration }`
 *   stays off).
 * - Frames are at most 20 bytes, which fits Android's default 23-byte ATT MTU (20-byte payload),
 *   so no larger MTU is requested.
 * - The transport never requests runtime permissions; it reports
 *   [ConnectionState.Unavailable] with [PERMISSION_REQUIRED] and leaves the prompt to the UI.
 *
 * Schema v2 (plan R8e, backend `docs/ios-ble.md` "Negotiation"): when discovery finds the v2
 * shot/control pair, the transport subscribes to the v2 control characteristic and sends `hello`
 * (with the usual 10 s control timeout) before anything else. On success it subscribes to the v2
 * shot characteristic only (→ [ConnectionState.Connected]); the v1 pair is never subscribed, so
 * the Pi sends this phone no v1 traffic. On `ok:false`, a timeout, a failed subscription or a Pi
 * without the v2 pair, it unsubscribes from v2 control and continues exactly as version one.
 * Over v2, control commands go to the v2 control characteristic in v2 envelopes, v2 events arrive
 * on [schemaEvents], and [SchemaV2Commands] adds the read-and-select commands.
 */
@Suppress("TooManyFunctions") // One state machine, split by lifecycle phase for readability.
class BleShotTransport internal constructor(
    private val central: BleCentral,
    private val permissions: BlePermissionChecker,
    private val scope: CoroutineScope,
    config: BleTransportConfig = BleTransportConfig(),
) : ShotTransport,
    SchemaV2Commands {
    private val reconnectDelay = config.reconnectDelay
    private val controlTimeout = config.controlTimeout
    private val confined: CoroutineContext =
        scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext

    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val mutableShots =
        MutableSharedFlow<ShotEvent>(extraBufferCapacity = SHOT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutableActiveClub = MutableStateFlow<GolfClub?>(null)
    private val mutableSupportsControls = MutableStateFlow(false)

    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    override val shots: Flow<ShotEvent> = mutableShots.asSharedFlow()
    override val activeClub: StateFlow<GolfClub?> = mutableActiveClub.asStateFlow()
    override val supportsControls: StateFlow<Boolean> = mutableSupportsControls.asStateFlow()

    private val mutableSchemaV2Active = MutableStateFlow(false)
    private val mutableSchemaEvents =
        MutableSharedFlow<SchemaV2Event>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val schemaV2Active: StateFlow<Boolean> = mutableSchemaV2Active.asStateFlow()
    override val schemaEvents: Flow<SchemaV2Event> = mutableSchemaEvents.asSharedFlow()

    private val shotReassembler = BleFrameReassembler()
    private val decoder = ShotEventDecoder()
    private val control =
        ControlChannel(config.requestIds, config.controlTimeout, config.initialControlSequence)

    /** The v2 control characteristic's own channel: its own sequence, reassembler and pending request. */
    private val controlV2 =
        ControlChannel(config.requestIds, config.controlTimeout, config.initialControlSequence, schemaV2 = true)

    private var adapterWatcher: Job? = null
    private var sessionJob: Job? = null
    private var reconnectJob: Job? = null

    /** The connected peripheral once discovery found the shot characteristic. */
    private var link: BlePeripheralLink? = null
    private var controlCharacteristicPresent = false

    override fun start() {
        scope.launch { startNow() }
    }

    override fun retry() {
        scope.launch {
            disconnectNow()
            startNow()
        }
    }

    override fun disconnect() {
        scope.launch { disconnectNow() }
    }

    override suspend fun setClub(club: GolfClub): ClubSelection {
        val response = sendControl { requestId -> ControlCodec.encodeSetClub(club, requestId) }
        return decodeResult { ControlCodec.decodeClubResult(response) }
            .also { selection -> mutableActiveClub.value = selection.club }
    }

    override suspend fun currentClub(): ClubSelection {
        val response = sendControl { requestId -> ControlCodec.encodeGetClub(requestId) }
        return decodeResult { ControlCodec.decodeClubResult(response) }
            .also { selection -> mutableActiveClub.value = selection.club }
    }

    override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult {
        val response = sendControl { requestId -> ControlCodec.encodeCalibration(measurement, requestId) }
        return decodeResult { ControlCodec.decodeCalibrationResult(response) }
    }

    override suspend fun requestProfiles() {
        sendSchemaV2 { requestId -> SchemaV2Codec.encodeGetProfiles(requestId) }
    }

    override suspend fun requestPowerStatus(): PowerStatus {
        val response = sendSchemaV2 { requestId -> SchemaV2Codec.encodeGetPowerStatus(requestId) }
        val status =
            decodeResult {
                SchemaV2Codec.decodePowerStatus(response.result ?: throw ControlDecodeError.MissingResult)
            }
        mutableSchemaEvents.tryEmit(SchemaV2Event.Power(status))
        return status
    }

    override suspend fun setActiveProfile(profileId: String) {
        sendSchemaV2 { requestId -> SchemaV2Codec.encodeSetActiveProfile(profileId, requestId) }
    }

    // region Lifecycle

    private fun startNow() {
        if (adapterWatcher?.isActive == true) {
            // Already watching: the reference's start() re-checks the adapter and scans.
            if (sessionJob?.isActive != true) beginScanIfReady()
            return
        }
        // The watcher's first emission is the current adapter state, which starts the scan when
        // the adapter is on (the reference's start() → handleCentralState).
        adapterWatcher =
            scope.launch {
                central.adapterState.distinctUntilChanged().collect { adapter -> onAdapterState(adapter) }
            }
    }

    private fun disconnectNow() {
        adapterWatcher?.cancel()
        adapterWatcher = null
        endSession()
        mutableState.value = ConnectionState.Idle
    }

    /** The reference's `handleCentralState`. */
    private fun onAdapterState(adapter: BleAdapterState) {
        if (adapter == BleAdapterState.PoweredOn) {
            if (sessionJob?.isActive != true && reconnectJob?.isActive != true) beginScanIfReady()
        } else {
            endSession()
            mutableState.value = adapter.toConnectionState()
        }
    }

    /** The reference's `startScanning`, plus the Android runtime-permission gate. */
    private fun beginScanIfReady() {
        if (!permissions.hasBluetoothPermissions()) {
            mutableState.value = ConnectionState.Unavailable(PERMISSION_REQUIRED)
            return
        }
        val adapter = central.currentAdapterState()
        if (adapter != BleAdapterState.PoweredOn) {
            mutableState.value = adapter.toConnectionState()
            return
        }
        reconnectJob?.cancel()
        reconnectJob = null
        sessionJob?.cancel()
        mutableState.value = ConnectionState.Scanning
        sessionJob = scope.launch { runSession() }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob =
            scope.launch {
                delay(reconnectDelay)
                reconnectJob = null
                startNow()
            }
    }

    /** Cancels any scan/connection and clears per-connection state without choosing a new [state]. */
    private fun endSession() {
        reconnectJob?.cancel()
        reconnectJob = null
        sessionJob?.cancel()
        sessionJob = null
        clearConnection()
    }

    private fun clearConnection() {
        control.fail(BleControlException.Disconnected())
        controlV2.fail(BleControlException.Disconnected())
        link = null
        controlCharacteristicPresent = false
        mutableSupportsControls.value = false
        mutableSchemaV2Active.value = false
        mutableActiveClub.value = null
        shotReassembler.reset()
        // The control reassembler is reset by fail() when a request was pending; reset it
        // unconditionally too so a half-received event can't leak into the next connection.
        control.resetFrames()
        controlV2.resetFrames()
        // The shot decoder is deliberately NOT reset (plan §0.3): its last event id suppresses
        // the replay the Pi sends when the next connection subscribes.
    }

    // endregion

    // region Session

    private suspend fun runSession() {
        val peripheral = scanForPeripheral() ?: return
        try {
            mutableState.value = ConnectionState.Connecting
            if (!connect(peripheral)) return
            coroutineScope {
                discoverAndObserve(peripheral, this)
                peripheral.awaitDisconnected()
                coroutineContext.cancelChildren()
            }
            onPeripheralDisconnected()
        } finally {
            withContext(NonCancellable) { peripheral.release() }
        }
    }

    private suspend fun scanForPeripheral(): BlePeripheralLink? =
        try {
            central.scan(SERVICE_UUID).first()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unavailable: BleUnavailableException) {
            mutableState.value = ConnectionState.Unavailable(unavailable.message ?: BLUETOOTH_UNAVAILABLE)
            null
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            // Platform scanners fail with assorted IllegalStateException/IOException subtypes.
            mutableState.value = ConnectionState.Error(error.message ?: SCAN_FAILED)
            scheduleReconnect()
            null
        }

    /** The reference's `didFailToConnect`: report the error and try again after the delay. */
    private suspend fun connect(peripheral: BlePeripheralLink): Boolean =
        try {
            peripheral.connect()
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            mutableState.value = ConnectionState.Error(error.message ?: CONNECT_FAILED)
            scheduleReconnect()
            false
        }

    /** The reference's `didDiscoverServices`/`didDiscoverCharacteristicsFor`/`setNotifyValue`. */
    private fun discoverAndObserve(
        peripheral: BlePeripheralLink,
        connectionScope: CoroutineScope,
    ) {
        mutableState.value = ConnectionState.Discovering
        val characteristics = peripheral.characteristicUuids(SERVICE_UUID)
        if (characteristics == null) {
            mutableState.value = ConnectionState.Error(SERVICE_NOT_FOUND)
            return
        }
        if (SHOT_CHARACTERISTIC_UUID !in characteristics) {
            mutableState.value = ConnectionState.Error(SHOTS_NOT_FOUND)
            return
        }
        link = peripheral
        // Plan R8e: discovering the v2 pair is itself the capability signal (backend "Negotiation").
        if (SHOT_V2_CHARACTERISTIC_UUID in characteristics && CONTROL_V2_CHARACTERISTIC_UUID in characteristics) {
            connectionScope.launch { negotiateSchemaV2(peripheral, connectionScope, characteristics) }
        } else {
            observeVersionOne(peripheral, connectionScope, characteristics)
        }
    }

    /** The version-one subscriptions, unchanged from the reference. */
    private fun observeVersionOne(
        peripheral: BlePeripheralLink,
        connectionScope: CoroutineScope,
        characteristics: Set<String>,
    ) {
        // Checked before observing: Kable's observe() on an absent characteristic fails the flow
        // with NoSuchElementException, and an older Pi has no control characteristic at all.
        controlCharacteristicPresent = CONTROL_CHARACTERISTIC_UUID in characteristics
        connectionScope.launch { observeShots(peripheral, SHOT_CHARACTERISTIC_UUID) }
        if (controlCharacteristicPresent) connectionScope.launch { observeControl(peripheral) }
    }

    /**
     * Subscribes to the v2 control characteristic and sends `hello`. Schema 2 → subscribe to the v2
     * shot characteristic only; anything else → drop the v2 subscription and fall back to v1.
     */
    private suspend fun negotiateSchemaV2(
        peripheral: BlePeripheralLink,
        connectionScope: CoroutineScope,
        characteristics: Set<String>,
    ) {
        val subscribed = CompletableDeferred<Unit>()
        val controlJob = connectionScope.launch { observeControlV2(peripheral, subscribed) }
        if (helloNegotiatesV2(peripheral, subscribed)) {
            controlCharacteristicPresent = true
            mutableSchemaV2Active.value = true
            mutableSupportsControls.value = true
            connectionScope.launch { observeShots(peripheral, SHOT_V2_CHARACTERISTIC_UUID) }
        } else {
            // Unsubscribing tells the Pi to stop v2 traffic to this phone (per-characteristic gating).
            controlJob.cancel()
            controlV2.resetFrames()
            observeVersionOne(peripheral, connectionScope, characteristics)
        }
    }

    /** `true` only when the Pi answered `hello` with schema 2 in time. */
    @Suppress("TooGenericExceptionCaught") // Any failure means "treat this Pi as version one".
    private suspend fun helloNegotiatesV2(
        peripheral: BlePeripheralLink,
        subscribed: CompletableDeferred<Unit>,
    ): Boolean =
        try {
            withTimeout(controlTimeout) { subscribed.await() }
            val response =
                controlV2.send({ requestId -> SchemaV2Codec.encodeHello(requestId) }) { frame ->
                    peripheral.writeWithResponse(SERVICE_UUID, CONTROL_V2_CHARACTERISTIC_UUID, frame)
                }
            SchemaV2Codec.decodeHelloResult(response).schemaVersion >= SchemaV2Codec.SCHEMA_VERSION
        } catch (_: TimeoutCancellationException) {
            // The subscription never completed (ControlChannel's own timeout is BleControlException).
            false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // ok:false (an older Pi: "Unsupported phone command: hello"), a hello timeout, a failed
            // write or subscription, or an unreadable result.
            false
        }

    private suspend fun observeShots(
        peripheral: BlePeripheralLink,
        characteristicUuid: String,
    ) {
        peripheral
            .observe(SERVICE_UUID, characteristicUuid) { mutableState.value = ConnectionState.Connected }
            .catch { error -> mutableState.value = ConnectionState.Error(error.message ?: SUBSCRIBE_FAILED) }
            .collect { frame -> receiveShotFrame(frame) }
    }

    private suspend fun observeControlV2(
        peripheral: BlePeripheralLink,
        subscribed: CompletableDeferred<Unit>,
    ) {
        peripheral
            .observe(SERVICE_UUID, CONTROL_V2_CHARACTERISTIC_UUID) { subscribed.complete(Unit) }
            .catch { error ->
                subscribed.completeExceptionally(error)
                if (mutableSchemaV2Active.value) {
                    controlCharacteristicPresent = false
                    mutableSupportsControls.value = false
                }
                controlV2.fail(BleControlException.Failed(error))
            }.collect { frame -> receiveV2ControlFrame(frame) }
    }

    private suspend fun observeControl(peripheral: BlePeripheralLink) {
        peripheral
            .observe(SERVICE_UUID, CONTROL_CHARACTERISTIC_UUID) { mutableSupportsControls.value = true }
            .catch { error ->
                controlCharacteristicPresent = false
                mutableSupportsControls.value = false
                control.fail(BleControlException.Failed(error))
            }.collect { frame -> receiveControlFrame(frame) }
    }

    /** The reference's `didDisconnectPeripheral`. */
    private fun onPeripheralDisconnected() {
        clearConnection()
        mutableState.value = ConnectionState.Scanning
        scheduleReconnect()
    }

    // endregion

    // region Inbound frames (internal so the ported BluetoothManagerTests can feed them directly)

    /** The reference's `receive(_:)`: reassemble, decode, de-duplicate, publish. */
    internal fun receiveShotFrame(frame: ByteArray) {
        try {
            shotReassembler.append(frame)?.let(decoder::decode)?.let(mutableShots::tryEmit)
        } catch (error: BleFrameError) {
            reportDecodeError(error)
        } catch (error: ShotDecodeError) {
            reportDecodeError(error)
        } catch (error: IllegalArgumentException) {
            // SerializationException and ShotEvent's UUID check are IllegalArgumentExceptions.
            reportDecodeError(error)
        }
    }

    private fun reportDecodeError(error: Exception) {
        mutableState.value = ConnectionState.Error(error.message ?: DECODE_FAILED)
    }

    /** The reference's `receiveControl(_:)`. */
    internal fun receiveControlFrame(frame: ByteArray) {
        route(control.receive(frame))
    }

    /** The v2 control characteristic: responses, `club_changed` and the other v2 events. */
    internal fun receiveV2ControlFrame(frame: ByteArray) {
        route(controlV2.receive(frame))
    }

    private fun route(inbound: ControlInbound) {
        when (inbound) {
            is ControlInbound.ClubChanged -> mutableActiveClub.value = inbound.club
            is ControlInbound.ClubChangedInvalid -> mutableState.value = ConnectionState.Error(inbound.message)
            is ControlInbound.Event -> mutableSchemaEvents.tryEmit(inbound.event)
            ControlInbound.Consumed -> Unit
        }
    }

    // endregion

    /**
     * Sends a version-one command ([encode] builds its v1 envelope). Over schema v2 it goes to the
     * v2 control characteristic, re-enveloped as v2.
     */
    private suspend fun sendControl(encode: (requestId: String) -> ByteArray): ControlResponseEnvelope =
        withContext(confined) {
            if (mutableSchemaV2Active.value) {
                sendOn(
                    controlV2,
                    CONTROL_V2_CHARACTERISTIC_UUID,
                ) { requestId -> SchemaV2Codec.toV2Command(encode(requestId)) }
            } else {
                sendOn(control, CONTROL_CHARACTERISTIC_UUID, encode)
            }
        }

    /** A v2-only command ([encode] builds its v2 envelope). */
    private suspend fun sendSchemaV2(encode: (requestId: String) -> ByteArray): ControlResponseEnvelope =
        withContext(confined) {
            if (!mutableSchemaV2Active.value && mutableState.value == ConnectionState.Connected) {
                throw BleControlException.Unsupported()
            }
            sendOn(controlV2, CONTROL_V2_CHARACTERISTIC_UUID, encode)
        }

    private suspend fun sendOn(
        channel: ControlChannel,
        characteristicUuid: String,
        encode: (requestId: String) -> ByteArray,
    ): ControlResponseEnvelope {
        val peripheral = link
        if (mutableState.value != ConnectionState.Connected || peripheral == null) {
            throw BleControlException.Unavailable()
        }
        if (!controlCharacteristicPresent) throw BleControlException.Unsupported()
        return channel.send(encode) { frame -> peripheral.writeWithResponse(SERVICE_UUID, characteristicUuid, frame) }
    }

    private inline fun <T> decodeResult(decode: () -> T): T =
        try {
            decode()
        } catch (error: ControlDecodeError) {
            throw BleControlException.InvalidResponse(error)
        } catch (error: IllegalArgumentException) {
            throw BleControlException.InvalidResponse(error)
        }

    companion object {
        const val PERMISSION_REQUIRED = "Bluetooth permission is required"
        private const val BLUETOOTH_UNAVAILABLE = "Bluetooth is unavailable"
        private const val SCAN_FAILED = "Could not scan for OpenFlight"
        private const val CONNECT_FAILED = "Could not connect to OpenFlight"
        private const val SERVICE_NOT_FOUND = "OpenFlight BLE service was not found"
        private const val SHOTS_NOT_FOUND = "OpenFlight shot notifications were not found"
        private const val SUBSCRIBE_FAILED = "Could not subscribe to OpenFlight shots"
        private const val DECODE_FAILED = "Could not read the shot from OpenFlight"
        private const val SHOT_BUFFER = 64
        private const val EVENT_BUFFER = 64
    }
}

/** The reference's `handleCentralState` messages, with `.unknown` → idle. */
internal fun BleAdapterState.toConnectionState(): ConnectionState =
    when (this) {
        BleAdapterState.PoweredOn -> ConnectionState.Idle
        BleAdapterState.PoweredOff -> ConnectionState.Unavailable("Bluetooth is turned off")
        BleAdapterState.Unauthorized -> ConnectionState.Unavailable(BleShotTransport.PERMISSION_REQUIRED)
        BleAdapterState.Unsupported -> ConnectionState.Unavailable("Bluetooth LE is not supported")
        BleAdapterState.Resetting -> ConnectionState.Unavailable("Bluetooth is resetting")
        BleAdapterState.Unknown -> ConnectionState.Idle
        BleAdapterState.Unavailable -> ConnectionState.Unavailable("Bluetooth is unavailable")
    }
