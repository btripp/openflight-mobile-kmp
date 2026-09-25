// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.openflight.companion.core.ble.requiredBluetoothPermissions
import dev.openflight.companion.core.data.TransportType

/**
 * Asks for the runtime permissions [transport] needs while composed, and reports whether they're
 * granted through [onResult] (a grant then needs a retry to connect).
 *
 * - Bluetooth: the permissions `core:ble` lists (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, or
 *   location on API 30 and lower).
 * - Wi-Fi: `ACCESS_LOCAL_NETWORK` on API 37+, where an app targeting 37 can't open a socket to a
 *   LAN address (the Pi) without it. Per developer.android.com/privacy-and-security/local-network-permission
 *   a denial shows up only as TCP timeouts, which is why the answer is reported (plan R8f): the
 *   dashboard turns a denial into an "open app settings" state instead of retrying forever.
 *
 * The missing [transportPermissions] are requested once per transport per screen (surviving
 * configuration changes, so a rotation doesn't re-prompt). [onResult] runs with the prompt's
 * answer, and again on resume only when the answer changed since it was last reported (the user
 * flipped it in Settings), so a normal resume never triggers a reconnect.
 * (iOS needs nothing like this: CoreBluetooth and the local-network prompt appear on first use.)
 */
@Composable
fun TransportPermissionRequest(
    transport: TransportType,
    onResult: (granted: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    var requested by rememberSaveable(transport) { mutableStateOf(false) }
    // The last answer reported for this transport; null until the first one.
    var reported by rememberSaveable(transport) { mutableStateOf<Boolean?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.isNotEmpty() && results.values.all { it }
            reported = granted
            currentOnResult(granted)
        }
    LaunchedEffect(transport) {
        if (requested) return@LaunchedEffect
        val missing = context.missingPermissions(transport)
        if (missing.isNotEmpty()) {
            requested = true
            launcher.launch(missing.toTypedArray())
        }
    }
    LifecycleResumeEffect(transport) {
        val granted = context.missingPermissions(transport).isEmpty()
        // Only after the app asked, and only a change: back from Settings with a new answer.
        if (requested && reported != null && reported != granted) {
            reported = granted
            currentOnResult(granted)
        }
        onPauseOrDispose {}
    }
}

private fun Context.missingPermissions(transport: TransportType): List<String> =
    transportPermissions(transport).filter { permission ->
        checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
    }

private fun transportPermissions(transport: TransportType): List<String> =
    when (transport) {
        TransportType.BLUETOOTH -> {
            requiredBluetoothPermissions
        }

        TransportType.WIFI -> {
            if (Build.VERSION.SDK_INT >= API_LOCAL_NETWORK_PERMISSION) listOf(ACCESS_LOCAL_NETWORK) else emptyList()
        }
    }

/** Android 17 (API 37) gates LAN sockets behind this runtime permission for apps targeting 37. */
private const val API_LOCAL_NETWORK_PERMISSION = 37
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
