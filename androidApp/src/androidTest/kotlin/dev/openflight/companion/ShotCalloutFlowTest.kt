// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.speech.SpeechEngine
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import dev.openflight.companion.core.testing.FakeSpeechEngine
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.compose.KoinContext
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.assertEquals

/**
 * The shot call-out coordinator, wired through the real app graph (plan F7): [FakeSpeechEngine]
 * and [FakeShotRepository] are injected as a Koin test module, the same pattern
 * `DrivingRangeFlowTest` uses for [SettingsRepository]. `Bluetooth` sidesteps the dashboard's
 * Wi-Fi local-network permission prompt (this test needs no transport at all: [FakeShotRepository]
 * never starts one).
 */
@RunWith(AndroidJUnit4::class)
class ShotCalloutFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var shots: FakeShotRepository
    private lateinit var speech: FakeSpeechEngine
    private lateinit var settings: FakeSettingsRepository

    @Suppress("DEPRECATION") // KoinContext: see DrivingRangeFlowTest's note.
    private fun launch(calloutsEnabled: Boolean) {
        stopKoin()
        shots = FakeShotRepository()
        speech = FakeSpeechEngine()
        settings = FakeSettingsRepository(transport = TransportType.BLUETOOTH)
        settings.calloutsEnabled.value = calloutsEnabled
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val koin =
            initKoin(
                extraModules =
                    listOf(
                        module {
                            single<ShotRepository> { shots }
                            single<SettingsRepository> { settings }
                            single<SpeechEngine> { speech }
                        },
                    ),
            ) {
                androidContext(context)
            }
        // Plan F7: OpenFlightApplication.onCreate() calls this in production; this test's graph
        // replaces the process's original one (stopKoin() above), so it must call it again.
        koin.shotCallouts()
        composeRule.setContent { KoinContext(koin) { OpenFlightApp() } }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun given_calloutsEnabled_when_shotArrives_then_engineSpeaksComposedText() {
        launch(calloutsEnabled = true)

        shots.setHistory(listOf(SAMPLE_SHOT))

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) { speech.spoken.isNotEmpty() }
        assertEquals("250 yards, 140 miles per hour", speech.spoken.single().text)
    }

    @Test
    fun given_calloutsDisabled_when_shotArrives_then_theEngineStaysSilent() {
        launch(calloutsEnabled = false)

        shots.setHistory(listOf(SAMPLE_SHOT))

        // No positive wait for "nothing happens": give the coordinator a beat, on the UI thread's
        // own idling, then check nothing was ever spoken.
        composeRule.waitForIdle()
        assertEquals(emptyList(), speech.spoken)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L

        val SAMPLE_SHOT =
            ShotEvent(
                schemaVersion = 1,
                eventId = "00000000-0000-4000-8000-000000000001",
                timestamp = "2026-08-05T23:54:00",
                club = "driver",
                ballSpeedMph = 140.0,
                estimatedCarryYards = 250.0,
            )
    }
}
