// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.compose.runtime.Composable
import dev.openflight.companion.core.data.TransportType

/**
 * CoreBluetooth (`NSBluetoothAlwaysUsageDescription`) and local networking
 * (`NSLocalNetworkUsageDescription`) prompt on first use, so there is nothing to request.
 */
@Composable
actual fun TransportPermissionRequest(
    transport: TransportType,
    onGranted: () -> Unit,
) = Unit
