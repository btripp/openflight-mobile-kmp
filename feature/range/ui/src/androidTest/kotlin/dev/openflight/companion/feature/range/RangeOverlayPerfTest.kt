// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.flight.BallFlightSimulator
import dev.openflight.companion.core.flight.FlightInputResolver
import dev.openflight.companion.core.flight.FlightMeasurements
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sin

/**
 * Plan F8a1 perf budget: a 200-shot overlay while the camera orbits every frame (the worst case:
 * every trajectory is re-projected each frame). Frames are counted per Compose frame (each one
 * moves the camera) and with [FrameMetrics]; the measured rate is logged under [TAG] and reported
 * to the instrumentation status. Read it with `adb logcat -s F8a1Perf`.
 */
@RunWith(AndroidJUnit4::class)
class RangeOverlayPerfTest {
    @Test
    fun given200ShotOverlay_whenOrbitingEveryFrame_thenFrameRateIsMeasured() {
        val flights = overlayFlights(RangeBrowseState.OVERLAY_CAP)
        var orbiting by mutableStateOf(false)
        val composeFrames = AtomicInteger()
        // A plain activity, not the Compose test rule: the rule swaps in a virtual frame clock, and
        // this test needs the real Choreographer's frames.
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        lateinit var window: Window
        scenario.onActivity { activity ->
            window = activity.window
            activity.setContent { OrbitingOverlay(flights, composeFrames) { orbiting } }
        }
        Thread.sleep(SETTLE_MILLIS)

        val frames = AtomicInteger()
        val totalNanos = AtomicLong()
        val listener =
            Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                frames.incrementAndGet()
                totalNanos.addAndGet(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
            }
        scenario.onActivity {
            window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            orbiting = true
        }
        val framesAtStart = composeFrames.get()
        val startedAt = System.nanoTime()
        Thread.sleep(MEASURE_MILLIS)
        val elapsedSeconds = (System.nanoTime() - startedAt) / NANOS_PER_SECOND
        scenario.onActivity {
            window.removeOnFrameMetricsAvailableListener(listener)
            orbiting = false
        }
        scenario.close()
        // Let the activity finish tearing down before the next test class launches its own.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Each Compose frame changes the camera, so it re-projects all 200 trajectories and redraws.
        val composeCount = composeFrames.get() - framesAtStart
        val fps = composeCount / elapsedSeconds
        val count = frames.get()
        val meanFrameMillis = if (count == 0) 0.0 else totalNanos.get() / count / NANOS_PER_MILLI
        val report =
            (
                "200-shot overlay, orbiting: %.1f fps over %.1f s (%d Compose frames; FrameMetrics: %d frames, " +
                    "mean total %.2f ms)"
            ).format(fps, elapsedSeconds, composeCount, count, meanFrameMillis)
        Log.i(TAG, report)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("f8a1.perf", report) })
        // The rate depends on the host running the emulator, so the test only guards that the overlay
        // keeps rendering; the plan's 50 fps budget is judged from the logged number on an idle host.
        assert(composeCount > 0) { report }
    }

    /** [count] real simulated trajectories: seven clubs, spread in speed and direction. */
    private fun overlayFlights(count: Int): List<OverlayFlight> {
        val resolver = FlightInputResolver()
        val simulator = BallFlightSimulator()
        val clubs = listOf("driver", "3-wood", "5-iron", "7-iron", "9-iron", "pw", "sw")
        return (0 until count).map { index ->
            val club = clubs[index % clubs.size]
            val carry = 250.0 - (index % clubs.size) * 25 + (index % 5) * 3
            val input =
                resolver.resolve(
                    FlightMeasurements(
                        id = "B0D91F0A-7950-4D7E-9DD5-" + index.toString().padStart(12, '0'),
                        club = club,
                        ballSpeedMph = 90.0 + carry / 4,
                        carryYards = carry,
                        launchAngleHorizontal = (index % 9 - 4).toDouble(),
                        spinAxisDeg = (index % 7 - 3) * 2.0,
                    ),
                )
            OverlayFlight(
                shotId = index.toString(),
                club = club,
                colorIndex = index % clubs.size,
                trajectory = simulator.simulate(input).downsampled(RangeBrowseState.OVERLAY_TRAJECTORY_POINTS),
            )
        }
    }

    private companion object {
        const val TAG = "F8a1Perf"
        const val SETTLE_MILLIS = 1_500L
        const val MEASURE_MILLIS = 4_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}

/** The overlay screen whose camera orbits every frame while [orbiting] is true. */
@Composable
private fun OrbitingOverlay(
    flights: List<OverlayFlight>,
    frameCounter: AtomicInteger,
    orbiting: () -> Boolean,
) {
    OfTheme {
        var view by remember { mutableStateOf(ViewTransform.IDENTITY) }
        val active = orbiting()
        LaunchedEffect(active) {
            if (!active) return@LaunchedEffect
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                frameCounter.incrementAndGet()
                val seconds = (now - start) / 1_000_000_000.0
                view = ViewTransform(zoom = 1.5, orbitYawDegrees = 40 * sin(seconds * 2))
            }
        }
        DrivingRangeScreen(
            uiState =
                DrivingRangeUiState.Ready(
                    browse =
                        RangeBrowseState(
                            mode = RangeMode.Overlay(sessionId = null, club = null),
                            view = view,
                            overlayFlights = flights,
                            selectedShotId = flights.first().shotId,
                        ),
                ),
            reduceMotion = false,
            onEvent = {},
            onExit = {},
            windowClass = OfWindowClass.COMPACT,
        )
    }
}
