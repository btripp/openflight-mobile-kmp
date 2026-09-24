// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import dev.openflight.companion.core.ble.OpenFlightBleProfile.CONTROL_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SERVICE_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SHOT_CHARACTERISTIC_UUID
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.protocol.BleFrameError
import dev.openflight.companion.core.protocol.BleFrameReassembler
import dev.openflight.companion.core.protocol.ControlCodec
import dev.openflight.companion.core.protocol.ControlDecodeError
import dev.openflight.companion.core.protocol.ControlResponseEnvelope
import dev.openflight.companion.core.protocol.ShotDecodeError
import dev.openflight.companion.core.protocol.ShotEventDecoder
import dev.openflight.companion.core.protocol.ShotTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
 */
@Suppress("TooManyFunctions") // One state machine, split by lifecycle phase for readability.
class BleShotTransport internal constructor(
    private val central: BleCentral,
    private val permissions: BlePermissionChecker,
    private val scope: CoroutineScope,
    config: BleTransportConfig = BleTransportConfig(),
) : ShotTransport {
    private val reconnectDelay = config.reconnectDelay
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

    private val shotReassembler = BleFrameReassembler()
    private val decoder = ShotEventDecoder()
    private val control =
        ControlChannel(config.requestIds, config.controlTimeout, config.initialControlSequence)

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
        link = null
        controlCharacteristicPresent = false
        mutableSupportsControls.value = false
        mutableActiveClub.value = null
        shotReassembler.reset()
        // The control reassembler is reset by fail() when a request was pending; reset it
        // unconditionally too so a half-received event can't leak into the next connection.
        control.resetFrames()
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
        // Checked before observing: Kable's observe() on an absent characteristic fails the flow
        // with NoSuchElementException, and an older Pi has no control characteristic at all.
        controlCharacteristicPresent = CONTROL_CHARACTERISTIC_UUID in characteristics
        connectionScope.launch { observeShots(peripheral) }
        if (controlCharacteristicPresent) connectionScope.launch { observeControl(peripheral) }
    }

    private suspend fun observeShots(peripheral: BlePeripheralLink) {
        peripheral
            .observe(SERVICE_UUID, SHOT_CHARACTERISTIC_UUID) { mutableState.value = ConnectionState.Connected }
            .catch { error -> mutableState.value = ConnectionState.Error(error.message ?: SUBSCRIBE_FAILED) }
            .collect { frame -> receiveShotFrame(frame) }
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
        when (val inbound = control.receive(frame)) {
            is ControlInbound.ClubChanged -> mutableActiveClub.value = inbound.club
            is ControlInbound.ClubChangedInvalid -> mutableState.value = ConnectionState.Error(inbound.message)
            ControlInbound.Consumed -> Unit
        }
    }

    // endregion

    private suspend fun sendControl(encode: (requestId: String) -> ByteArray): ControlResponseEnvelope =
        withContext(confined) {
            val peripheral = link
            if (mutableState.value != ConnectionState.Connected || peripheral == null) {
                throw BleControlException.Unavailable()
            }
            if (!controlCharacteristicPresent) throw BleControlException.Unsupported()
            control.send(encode) { frame ->
                peripheral.writeWithResponse(SERVICE_UUID, CONTROL_CHARACTERISTIC_UUID, frame)
            }
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
