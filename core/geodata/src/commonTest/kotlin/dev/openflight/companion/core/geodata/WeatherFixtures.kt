// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.geodata

/**
 * Open-Meteo forecast responses for `OpenMeteoWeatherClientTest` (plan F6, A15: Kotlin string
 * constants, no `commonTest` resources -- iOS/Native has no resource loader without Compose
 * Resources).
 *
 * **Captured** fixtures were recorded verbatim on 2026-09-25 with `curl` against
 * `https://api.open-meteo.com/v1/forecast`, params `latitude=<lat>`, `longitude=<lon>`,
 * `current=temperature_2m,relative_humidity_2m,surface_pressure,wind_speed_10m,wind_direction_10m`,
 * `wind_speed_unit=ms` and `timezone=UTC`.
 *
 * **HAND-BUILT** fixtures are responses the live API doesn't return for a valid request (429,
 * a truncated/garbled body) or that need a state a single capture can't produce.
 */
internal object WeatherFixtures {
    /** Denver, CO (39.7392, -104.9903): high altitude, so a good density-ratio sanity check. */
    const val DENVER_LAT = 39.7392
    const val DENVER_LON = -104.9903

    /** Captured 2026-09-25 for [DENVER_LAT]/[DENVER_LON]. */
    const val DENVER_RESPONSE: String =
        """{"latitude":39.746895,"longitude":-104.987076,"generationtime_ms":0.07319450378417969,""" +
            """"utc_offset_seconds":0,"timezone":"GMT","timezone_abbreviation":"GMT","elevation":1599.0,""" +
            """"current_units":{"time":"iso8601","interval":"seconds","temperature_2m":"°C",""" +
            """"relative_humidity_2m":"%","surface_pressure":"hPa","wind_speed_10m":"m/s",""" +
            """"wind_direction_10m":"°"},"current":{"time":"2026-09-25T23:00","interval":900,""" +
            """"temperature_2m":16.7,"relative_humidity_2m":72,"surface_pressure":837.6,""" +
            """"wind_speed_10m":3.74,"wind_direction_10m":164}}"""

    /** St Andrews, Scotland (56.3398, -2.7967): sea level, for contrast with Denver's altitude. */
    const val ST_ANDREWS_LAT = 56.3398
    const val ST_ANDREWS_LON = -2.7967

    /** Captured 2026-09-25 for [ST_ANDREWS_LAT]/[ST_ANDREWS_LON]. */
    const val ST_ANDREWS_RESPONSE: String =
        """{"latitude":56.33856,"longitude":-2.7920227,"generationtime_ms":5.170345306396484,""" +
            """"utc_offset_seconds":0,"timezone":"GMT","timezone_abbreviation":"GMT","elevation":23.0,""" +
            """"current_units":{"time":"iso8601","interval":"seconds","temperature_2m":"°C",""" +
            """"relative_humidity_2m":"%","surface_pressure":"hPa","wind_speed_10m":"m/s",""" +
            """"wind_direction_10m":"°"},"current":{"time":"2026-09-25T23:00","interval":900,""" +
            """"temperature_2m":11.9,"relative_humidity_2m":82,"surface_pressure":1011.9,""" +
            """"wind_speed_10m":5.20,"wind_direction_10m":220}}"""

    /** Captured 2026-09-25: an out-of-range latitude, Open-Meteo's `400` error shape. */
    const val INVALID_REQUEST_RESPONSE: String =
        """{"error":true,"reason":"Latitude must be in range of -90 to 90°. Given: 999.0."}"""

    /** HAND-BUILT: a body the client can't parse at all (truncated JSON). */
    const val MALFORMED_RESPONSE: String = """{"current":{"temperature_2m":16.7,"""

    /** HAND-BUILT: valid JSON, but missing the fields this client reads. */
    const val WRONG_SHAPE_RESPONSE: String = """{"latitude":39.7,"longitude":-104.9}"""
}
