// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import kotlin.math.round

/**
 * A club's launch and spin priors, copied from the backend's `CLUB_PHYSICS`
 * (`src/openflight/clubs/physics.py` at 7ca4b40), which the backend documents as the TrackMan
 * baseline launch and amateur ball speed plus the TrackMan PGA Tour spin averages. The backend
 * uses the same numbers when a launch angle or spin is missing, so [FlightInputResolver]'s
 * estimated flight is the one the server's carry assumes (plan F2b). `ClubPhysicsParityTest`
 * checks this table against the generated `BackendFlightOracle`.
 *
 * @property optimalLaunchDegrees the baseline launch at [averageBallSpeedMph].
 * @property launchDegreesPerMph slower shots launch higher, faster ones lower.
 * @property typicalSpinRpm the tour spin average, the fallback when spin is missing.
 * @property isDriverOrWood the server's calculated spin overstates these clubs, so the resolver
 *   uses [typicalSpinRpm] instead.
 */
internal data class ClubPhysics(
    val optimalLaunchDegrees: Double,
    val averageBallSpeedMph: Double,
    val launchDegreesPerMph: Double,
    val typicalSpinRpm: Double,
    val isDriverOrWood: Boolean = false,
) {
    /**
     * The backend's `estimate_launch_angle` speed term (`server.py:473-523`):
     * `max(5, round(optimal − (v − average) · perMph, 1))`. Its smash and spin terms need club
     * speed and a trusted spin, so they're left out.
     */
    fun defaultLaunchDegrees(ballSpeedMph: Double): Double {
        val launch = optimalLaunchDegrees - (ballSpeedMph - averageBallSpeedMph) * launchDegreesPerMph
        return maxOf(MINIMUM_LAUNCH_DEGREES, round(launch * TENTHS) / TENTHS)
    }

    companion object {
        private const val MINIMUM_LAUNCH_DEGREES = 5.0
        private const val TENTHS = 10.0

        val DRIVER = ClubPhysics(11.0, 143.0, 0.15, 2_700.0, isDriverOrWood = true)
        val WOOD_3 = ClubPhysics(12.5, 135.0, 0.18, 3_500.0, isDriverOrWood = true)
        val WOOD_5 = ClubPhysics(14.0, 128.0, 0.20, 4_200.0, isDriverOrWood = true)
        val WOOD_7 = ClubPhysics(15.5, 122.0, 0.20, 4_800.0, isDriverOrWood = true)
        val HYBRID_3 = ClubPhysics(13.5, 123.0, 0.22, 4_400.0)
        val HYBRID_5 = ClubPhysics(15.0, 118.0, 0.22, 4_900.0)
        val HYBRID_7 = ClubPhysics(16.5, 112.0, 0.25, 5_300.0)
        val HYBRID_9 = ClubPhysics(18.0, 106.0, 0.25, 5_800.0)
        val IRON_2 = ClubPhysics(13.0, 120.0, 0.25, 4_000.0)
        val IRON_3 = ClubPhysics(14.5, 118.0, 0.25, 4_500.0)
        val IRON_4 = ClubPhysics(16.0, 114.0, 0.28, 5_000.0)
        val IRON_5 = ClubPhysics(17.5, 110.0, 0.28, 5_400.0)
        val IRON_6 = ClubPhysics(19.0, 105.0, 0.30, 6_000.0)
        val IRON_7 = ClubPhysics(20.5, 100.0, 0.30, 6_500.0)
        val IRON_8 = ClubPhysics(23.0, 94.0, 0.30, 7_500.0)
        val IRON_9 = ClubPhysics(25.5, 88.0, 0.30, 8_500.0)
        val PW = ClubPhysics(28.0, 82.0, 0.30, 9_000.0)
        val GW = ClubPhysics(30.0, 76.0, 0.30, 9_500.0)
        val SW = ClubPhysics(32.0, 73.0, 0.30, 10_000.0)
        val LW = ClubPhysics(35.0, 70.0, 0.30, 10_500.0)
        val UNKNOWN = ClubPhysics(18.0, 120.0, 0.25, 5_000.0)

        /** By wire value (`GolfClub.wireValue`) and the aliases the reference resolver accepted. */
        private val BY_CLUB: Map<String, ClubPhysics> =
            mapOf(
                "driver" to DRIVER,
                "3-wood" to WOOD_3,
                "5-wood" to WOOD_5,
                "7-wood" to WOOD_7,
                "3-hybrid" to HYBRID_3,
                "5-hybrid" to HYBRID_5,
                "7-hybrid" to HYBRID_7,
                "9-hybrid" to HYBRID_9,
                "2-iron" to IRON_2,
                "3-iron" to IRON_3,
                "4-iron" to IRON_4,
                "5-iron" to IRON_5,
                "6-iron" to IRON_6,
                "7-iron" to IRON_7,
                "8-iron" to IRON_8,
                "9-iron" to IRON_9,
                "iron-2" to IRON_2,
                "iron-3" to IRON_3,
                "iron-4" to IRON_4,
                "iron-5" to IRON_5,
                "iron-6" to IRON_6,
                "iron-7" to IRON_7,
                "iron-8" to IRON_8,
                "iron-9" to IRON_9,
                "pw" to PW,
                "gw" to GW,
                "sw" to SW,
                "lw" to LW,
                "pitching-wedge" to PW,
                "gap-wedge" to GW,
                "sand-wedge" to SW,
                "lob-wedge" to LW,
            )

        /** Case-insensitive; `_` and spaces count as `-`. Anything else is [UNKNOWN]. */
        fun forClub(club: String): ClubPhysics =
            BY_CLUB[club.lowercase().replace('_', '-').replace(' ', '-')] ?: UNKNOWN
    }
}
