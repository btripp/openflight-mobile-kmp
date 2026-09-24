// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Builds the Bluetooth transport. Hold one instance per app (Koin `single`), because it owns a
 * scan/connection session and a broadcast receiver while started.
 *
 * The caller (the UI) must request [requiredBluetoothPermissions] itself; until they are granted
 * the transport reports `ConnectionState.Unavailable("Bluetooth permission is required")`, and
 * `retry()` after the grant starts scanning.
 *
 * @param context any context; only its application context is kept.
 * @param scope where the state machine runs. Its dispatcher must be single-threaded; the default
 *   is a background dispatcher limited to one thread.
 */
@Suppress("FunctionNaming") // Factory function named after the type it builds (like Kable's Peripheral()).
fun BleShotTransport(
    context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
): BleShotTransport {
    val appContext = context.applicationContext
    return BleShotTransport(
        central = KableBleCentral(AndroidBleAdapterMonitor(appContext)),
        permissions = AndroidBlePermissionChecker(appContext),
        scope = scope,
    )
}

/**
 * The runtime permissions scanning and connecting need on this device's API level: `BLUETOOTH_SCAN`
 * and `BLUETOOTH_CONNECT` on API 31+, location on API 30 and lower (fine location from API 29,
 * which is what Kable checks before scanning).
 */
val requiredBluetoothPermissions: List<String>
    get() =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            else -> {
                listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

internal class AndroidBlePermissionChecker(
    private val context: Context,
) : BlePermissionChecker {
    override fun hasBluetoothPermissions(): Boolean =
        requiredBluetoothPermissions.all { permission -> isGranted(permission) } ||
            // Fine location implies coarse on API 28 and lower.
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
            )

    private fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/** Adapter power state from `BluetoothAdapter.getState()` and `ACTION_STATE_CHANGED`. */
internal class AndroidBleAdapterMonitor(
    private val context: Context,
) : BleAdapterMonitor {
    override val states: Flow<BleAdapterState> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        trySend(state.toAdapterState())
                    }
                }
            trySend(current())
            // ACTION_STATE_CHANGED is a protected system broadcast, so no export flag is needed.
            context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            awaitClose { context.unregisterReceiver(receiver) }
        }

    override fun current(): BleAdapterState {
        val hasBle = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        return if (!hasBle || adapter == null) BleAdapterState.Unsupported else adapter.state.toAdapterState()
    }

    /**
     * Android has no "resetting"/"unknown"; a transition in either direction reads as off until
     * `STATE_ON`, and "unauthorized" comes from [AndroidBlePermissionChecker], not the adapter.
     */
    private fun Int.toAdapterState(): BleAdapterState =
        when (this) {
            BluetoothAdapter.STATE_ON -> BleAdapterState.PoweredOn

            BluetoothAdapter.STATE_OFF,
            BluetoothAdapter.STATE_TURNING_OFF,
            BluetoothAdapter.STATE_TURNING_ON,
            -> BleAdapterState.PoweredOff

            else -> BleAdapterState.Unavailable
        }
}
