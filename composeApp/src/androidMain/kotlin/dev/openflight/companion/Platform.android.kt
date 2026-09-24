// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import android.os.Build

actual fun platformName(): String = "Android ${Build.VERSION.SDK_INT}"
