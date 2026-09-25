// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import kotlinx.coroutines.flow.Flow

/** The OpenFlight GATT profile (plan §0.1, `ios/OpenFlight/BluetoothManager.swift`). */
internal object OpenFlightBleProfile {
    const val SERVICE_UUID = "b6f633f2-e6e3-45ae-84b4-968ecca2d9c7"
    const val SHOT_CHARACTERISTIC_UUID = "2b28f67e-9011-41d2-98ed-562b47d7a5e4"
    const val CONTROL_CHARACTERISTIC_UUID = "7e3b5d6c-7f10-4d4a-9c39-25e2b77f4a11"

    /** Schema v2's own pair in the same service (backend "Schema v2 design decision"). */
    const val SHOT_V2_CHARACTERISTIC_UUID = "ed365fe6-3abf-4fc3-8e44-d9525a22dabd"
    const val CONTROL_V2_CHARACTERISTIC_UUID = "7ba96e63-12c2-4ce0-bb84-3513c7fd1474"
}

/**
 * The Bluetooth adapter's power/authorization state, modelled on CoreBluetooth's `CBManagerState`
 * because the reference's user-facing messages are keyed on it.
 */
internal enum class BleAdapterState {
    PoweredOn,
    PoweredOff,
    Unauthorized,
    Unsupported,
    Resetting,

    /** CoreBluetooth reports this before its first real state; the reference maps it to idle. */
    Unknown,

    /** Anything else a platform can report (Swift's `@unknown default`). */
    Unavailable,
}

/** Thrown by a [BleCentral] scan when a platform requirement (not a transient failure) is unmet. */
internal class BleUnavailableException(
    message: String,
) : Exception(message)

/**
 * The central-role operations [BleShotTransport] needs. Kable implements this in production
 * ([KableBleCentral]); tests drive the state machine with a fake under virtual time.
 */
internal interface BleCentral {
    /** Emits the current adapter state on collection, then every change. */
    val adapterState: Flow<BleAdapterState>

    fun currentAdapterState(): BleAdapterState

    /**
     * Scans for peripherals advertising [serviceUuid]. Collecting the first element and cancelling
     * the collection stops the scan (the reference's `stopScan()` on first discovery).
     */
    fun scan(serviceUuid: String): Flow<BlePeripheralLink>
}

/** One discovered peripheral. Implementations must be single-use: one connection per instance. */
internal interface BlePeripheralLink {
    /** Connects and discovers services, suspending until done. Throws on failure. */
    suspend fun connect()

    /** Suspends until the connection established by [connect] drops. */
    suspend fun awaitDisconnected()

    /** The characteristic UUIDs discovered in [serviceUuid], or `null` if the service is absent. */
    fun characteristicUuids(serviceUuid: String): Set<String>?

    /**
     * Enables notifications on a characteristic. [onSubscription] runs once notifications are
     * enabled, which is the transport's "subscribed" signal. The flow fails if enabling them fails.
     */
    fun observe(
        serviceUuid: String,
        characteristicUuid: String,
        onSubscription: suspend () -> Unit,
    ): Flow<ByteArray>

    /** Writes one frame with response, suspending until the peripheral acknowledges it. */
    suspend fun writeWithResponse(
        serviceUuid: String,
        characteristicUuid: String,
        data: ByteArray,
    )

    /** Disconnects (if connected) and frees platform resources. Never throws. */
    suspend fun release()
}

/**
 * Whether the app holds the runtime permissions scanning and connecting need. Android checks
 * `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` (API 31+) or location (API 30 and lower); iOS always
 * answers `true` because CoreBluetooth reports authorization through [BleAdapterState.Unauthorized].
 * This only *checks*: the transport never requests permissions.
 */
internal fun interface BlePermissionChecker {
    fun hasBluetoothPermissions(): Boolean
}
