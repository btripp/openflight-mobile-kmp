// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.sensors

import platform.UIKit.UIDevice

actual fun deviceModel(): String = UIDevice.currentDevice.model
