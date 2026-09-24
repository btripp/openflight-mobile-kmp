// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.openflight.companion.core.data.TransportType
import kotlinx.coroutines.runBlocking
import org.koin.mp.KoinPlatform

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark-first (core:designsystem's OfTheme), so the status and
        // navigation bar icons must always be light, regardless of the system's
        // day/night setting. `enableEdgeToEdge()`'s default "auto" style otherwise
        // picks dark (invisible-on-dark) icons on some devices/OS versions.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        applyDebugLaunchOptions()
        setContent {
            App()
        }
    }

    /**
     * Debug builds only, once per process (not again on recreation): the intent extras documented
     * on [LaunchOptions], for example
     * `adb shell am start -n dev.openflight.companion/.MainActivity --es transport wifi --es host 10.0.2.2:8091`.
     */
    private fun applyDebugLaunchOptions() {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable || launchOptionsApplied) return
        launchOptionsApplied = true
        val options = intent.toLaunchOptions()
        if (options == LaunchOptions()) return
        runBlocking { KoinPlatform.getKoin().applyLaunchOptions(options) }
    }

    private fun Intent.toLaunchOptions(): LaunchOptions =
        LaunchOptions(
            uiTesting = getBooleanExtra(EXTRA_UI_TESTING, false),
            previewShot = getBooleanExtra(EXTRA_PREVIEW_SHOT, false),
            rangeMode = getBooleanExtra(EXTRA_RANGE_MODE, false),
            previewFlight = getBooleanExtra(EXTRA_PREVIEW_FLIGHT, false),
            transport = TransportType.fromStorageValue(getStringExtra(EXTRA_TRANSPORT)),
            host = getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() },
        )

    private companion object {
        const val EXTRA_UI_TESTING = "ui_testing"
        const val EXTRA_PREVIEW_SHOT = "preview_shot"
        const val EXTRA_RANGE_MODE = "range_mode"
        const val EXTRA_PREVIEW_FLIGHT = "preview_flight"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_HOST = "host"

        var launchOptionsApplied = false
    }
}
