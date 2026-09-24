// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.sensors

import android.os.Build

actual fun deviceModel(): String = Build.MODEL
