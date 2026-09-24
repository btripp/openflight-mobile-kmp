// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

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
import dev.openflight.companion.core.ble.requiredBluetoothPermissions
import dev.openflight.companion.core.data.TransportType

/**
 * Requests the missing [transportPermissions] once per transport per screen (surviving
 * configuration changes, so a rotation doesn't re-prompt). A full grant calls [onGranted].
 */
@Composable
actual fun TransportPermissionRequest(
    transport: TransportType,
    onGranted: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnGranted by rememberUpdatedState(onGranted)
    var requested by rememberSaveable(transport) { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.isNotEmpty() && results.values.all { it }) currentOnGranted()
        }
    LaunchedEffect(transport) {
        if (requested) return@LaunchedEffect
        val missing =
            transportPermissions(transport).filter { permission ->
                context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isNotEmpty()) {
            requested = true
            launcher.launch(missing.toTypedArray())
        }
    }
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
