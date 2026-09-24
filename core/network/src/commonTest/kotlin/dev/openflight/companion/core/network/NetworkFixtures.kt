// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

internal const val DEFAULT_TEST_HOST = "pi.local:8080"

internal fun shotJson(
    eventId: String,
    ballSpeedMph: Double = 140.0,
): String =
    """{"schema_version":1,"event_id":"$eventId","timestamp":"2026-08-05T23:54:00","club":"driver",""" +
        """"ball_speed_mph":$ballSpeedMph,"estimated_carry_yards":250.0}"""

internal fun sseShotChunk(
    eventId: String,
    ballSpeedMph: Double = 140.0,
): String = "event: shot\ndata: ${shotJson(eventId, ballSpeedMph)}\n\n"

internal fun sseClubChangedChunk(club: String): String =
    "event: club_changed\ndata: {\"schema_version\":1,\"type\":\"club_changed\",\"club\":\"$club\"}\n\n"

internal fun sampleMeasurement(): PhoneOrientationMeasurement =
    PhoneOrientationMeasurement(
        mountTiltDeg = 12.5,
        rollDeg = 0.4,
        gravityXG = 0.01,
        gravityYG = -0.02,
        gravityZG = -0.99,
        tiltStddevDeg = 0.2,
        rollStddevDeg = 0.1,
        sampleCount = 120,
        measuredAt = "2026-08-05T23:54:00Z",
        deviceModel = "Pixel 9",
    )

/** Builds a [WifiShotTransport] whose internal run loop is driven by [scheduler]'s virtual time. */
internal fun wifiShotTransport(
    engine: MockEngine,
    scheduler: TestCoroutineScheduler,
    host: String = DEFAULT_TEST_HOST,
): WifiShotTransport {
    val client = HttpClient(engine) { installOpenFlightDefaults() }
    return WifiShotTransport(host = host, httpClient = client, dispatcher = StandardTestDispatcher(scheduler))
}
