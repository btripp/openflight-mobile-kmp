// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.compose.runtime.Composable
import dev.openflight.companion.core.data.TransportType

/**
 * Asks for the runtime permissions [transport] needs while composed, and calls [onGranted] after
 * the user grants them (the transport then needs a retry to connect).
 *
 * - Android, Bluetooth: the permissions `core:ble` lists (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, or
 *   location on API 30 and lower).
 * - Android, Wi-Fi: `ACCESS_LOCAL_NETWORK` on API 37+, where an app targeting 37 can't open a
 *   socket to a LAN address (the Pi) without it; connects just time out.
 * - iOS: nothing. CoreBluetooth and the local-network prompt appear on first use by themselves.
 */
@Composable
expect fun TransportPermissionRequest(
    transport: TransportType,
    onGranted: () -> Unit,
)
