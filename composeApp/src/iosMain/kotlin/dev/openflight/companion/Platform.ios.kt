// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import platform.UIKit.UIDevice

actual fun platformName(): String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
