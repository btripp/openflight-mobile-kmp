// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.darwin.NSObject

/**
 * Builds the Bluetooth transport. Hold one instance per app (Koin `single`).
 *
 * The host app's Info.plist must carry `NSBluetoothAlwaysUsageDescription`; iOS shows the
 * permission prompt the first time CoreBluetooth is used (on the first `start()`), and a denial
 * surfaces as `ConnectionState.Unavailable("Bluetooth permission is required")`.
 *
 * @param scope where the state machine runs. Its dispatcher must be single-threaded; the default
 *   is a background dispatcher limited to one thread.
 */
@Suppress("FunctionNaming") // Factory function named after the type it builds (like Kable's Peripheral()).
fun BleShotTransport(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
): BleShotTransport =
    BleShotTransport(
        central = KableBleCentral(CoreBluetoothAdapterMonitor()),
        // iOS reports authorization through CBManagerState.unauthorized instead.
        permissions = { true },
        scope = scope,
    )

/**
 * Adapter state from a dedicated `CBCentralManager` (Kable's own manager is internal). Like the
 * reference, it starts at `.unknown` (→ idle) and the first `centralManagerDidUpdateState` delivers
 * the real state. The manager is created lazily on first use so merely building the transport
 * doesn't trigger the Bluetooth permission prompt.
 */
internal class CoreBluetoothAdapterMonitor : BleAdapterMonitor {
    private val delegate = Delegate()

    // CBCentralManager holds its delegate weakly; this monitor keeps it alive.
    private val manager: CBCentralManager by lazy {
        CBCentralManager(
            delegate = delegate,
            queue = null,
            options = mapOf<Any?, Any?>(CBCentralManagerOptionShowPowerAlertKey to false),
        )
    }

    override val states: Flow<BleAdapterState> = delegate.state.onStart { manager }

    override fun current(): BleAdapterState = manager.state.toAdapterState()

    private class Delegate :
        NSObject(),
        CBCentralManagerDelegateProtocol {
        val state = MutableStateFlow(BleAdapterState.Unknown)

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            state.value = central.state.toAdapterState()
        }
    }
}

private fun CBManagerState.toAdapterState(): BleAdapterState =
    when (this) {
        CBManagerStatePoweredOn -> BleAdapterState.PoweredOn
        CBManagerStatePoweredOff -> BleAdapterState.PoweredOff
        CBManagerStateUnauthorized -> BleAdapterState.Unauthorized
        CBManagerStateUnsupported -> BleAdapterState.Unsupported
        CBManagerStateResetting -> BleAdapterState.Resetting
        CBManagerStateUnknown -> BleAdapterState.Unknown
        else -> BleAdapterState.Unavailable
    }
