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
import dev.openflight.companion.core.data.RangeShowSetting
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.data.ShotTrailStyle
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
            OpenFlightApp()
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
            previewPi = getBooleanExtra(EXTRA_PREVIEW_PI, false),
            rangeMode = getBooleanExtra(EXTRA_RANGE_MODE, false),
            previewFlight = getBooleanExtra(EXTRA_PREVIEW_FLIGHT, false),
            transport = TransportType.fromStorageValue(getStringExtra(EXTRA_TRANSPORT)),
            host = getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() },
            previewHistory = getBooleanExtra(EXTRA_PREVIEW_HISTORY, false),
            previewHistoryStuck = getBooleanExtra(EXTRA_PREVIEW_HISTORY_STUCK, false),
            previewPiSession = getBooleanExtra(EXTRA_PREVIEW_PI_SESSION, false),
            previewPiSessionStuck = getBooleanExtra(EXTRA_PREVIEW_PI_SESSION_STUCK, false),
            // Plan F8a2a: screenshots of each theme, with the flight held (iOS's arguments).
            rangeFreezeProgress =
                getFloatExtra(EXTRA_RANGE_FREEZE_PROGRESS, Float.NaN)
                    .takeIf { it.isFinite() }
                    ?.coerceIn(0f, 1f)
                    ?.toDouble(),
            rangeTheme = RangeThemeSetting.fromStorageValue(getStringExtra(EXTRA_RANGE_THEME)),
            // Plan F8a2t: screenshots of each shot trail style (iOS's --shot-trail).
            shotTrail = ShotTrailStyle.fromStorageValue(getStringExtra(EXTRA_SHOT_TRAIL)),
            // Plan F8f: the range's "Show" choice (iOS's --range-show).
            rangeShow = RangeShowSetting.fromStorageValue(getStringExtra(EXTRA_RANGE_SHOW)),
            previewProfiles = getBooleanExtra(EXTRA_PREVIEW_PROFILES, false),
        )

    private companion object {
        const val EXTRA_UI_TESTING = "ui_testing"
        const val EXTRA_PREVIEW_SHOT = "preview_shot"
        const val EXTRA_PREVIEW_PI = "preview_pi"
        const val EXTRA_RANGE_MODE = "range_mode"
        const val EXTRA_PREVIEW_FLIGHT = "preview_flight"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_HOST = "host"
        const val EXTRA_PREVIEW_HISTORY = "preview_history"
        const val EXTRA_PREVIEW_HISTORY_STUCK = "preview_history_stuck"
        const val EXTRA_PREVIEW_PI_SESSION = "preview_pi_session"
        const val EXTRA_PREVIEW_PI_SESSION_STUCK = "preview_pi_session_stuck"
        const val EXTRA_RANGE_FREEZE_PROGRESS = "range_freeze_progress"
        const val EXTRA_RANGE_THEME = "range_theme"
        const val EXTRA_SHOT_TRAIL = "shot_trail"
        const val EXTRA_RANGE_SHOW = "range_show"
        const val EXTRA_PREVIEW_PROFILES = "preview_profiles"

        var launchOptionsApplied = false
    }
}
