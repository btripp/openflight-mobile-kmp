// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import dev.openflight.companion.core.model.GolfClub

/**
 * A famous kind of shot to recreate in [GameMode.IconicShots].
 *
 * @property club the club it's played with (a suggestion; any club's shot is scored).
 * @property targetCarryYards the carry to hit.
 * @property curveYards where it lands relative to the target line, positive right (a fade or
 *   slice for a right-hander), negative left (a draw or hook); 0 is dead straight.
 * @property apexYards the peak height to match, or `null` when height doesn't matter.
 */
data class IconicShot(
    val id: String,
    val title: String,
    val description: String,
    val club: GolfClub,
    val targetCarryYards: Double,
    val curveYards: Double,
    val apexYards: Double? = null,
)

/**
 * The built-in iconic shots (plan F9, D6). Kotlin constants rather than a resource file, since
 * `commonMain` has no resource loader on iOS without Compose Resources (A15).
 *
 * **Content rule:** generic, descriptive scenarios only. No real players' names, tournament
 * trademarks or likenesses. The user may add their own later (F12).
 */
object IconicShotCatalog {
    val shots: List<IconicShot> =
        listOf(
            IconicShot(
                id = "fade-over-water",
                title = "The 250-yard fade over water",
                description = "Start it at the left edge of the lake and let it drift back onto the fairway.",
                club = GolfClub.DRIVER,
                targetCarryYards = 250.0,
                curveYards = 12.0,
                apexYards = 32.0,
            ),
            IconicShot(
                id = "draw-around-dogleg",
                title = "The draw around the dogleg",
                description = "Turn it right to left around the corner trees to cut the hole short.",
                club = GolfClub.DRIVER,
                targetCarryYards = 235.0,
                curveYards = -18.0,
            ),
            IconicShot(
                id = "fairway-wood-in-two",
                title = "Three-wood off the deck to reach in two",
                description = "A long, flat-lying fairway wood that has to carry the cross bunker.",
                club = GolfClub.WOOD_3,
                targetCarryYards = 220.0,
                curveYards = 0.0,
            ),
            IconicShot(
                id = "long-iron-island",
                title = "Long iron to the island green",
                description = "No bail-out: a high, straight long iron that stops on the green.",
                club = GolfClub.IRON_4,
                targetCarryYards = 190.0,
                curveYards = 0.0,
                apexYards = 28.0,
            ),
            IconicShot(
                id = "punch-under-branches",
                title = "The punch under the branches",
                description = "Keep it low under the overhanging limbs and run it up the fairway.",
                club = GolfClub.IRON_7,
                targetCarryYards = 130.0,
                curveYards = 0.0,
                apexYards = 10.0,
            ),
            IconicShot(
                id = "knockdown-into-wind",
                title = "The knockdown into the wind",
                description = "A three-quarter swing that flies flat and holds its line.",
                club = GolfClub.IRON_8,
                targetCarryYards = 125.0,
                curveYards = 0.0,
                apexYards = 15.0,
            ),
            IconicShot(
                id = "wedge-tucked-pin",
                title = "Wedge to the tucked pin",
                description = "The pin is cut right behind a bunker: fly it all the way and fade it in.",
                club = GolfClub.PITCHING_WEDGE,
                targetCarryYards = 110.0,
                curveYards = 4.0,
            ),
            IconicShot(
                id = "lob-over-bunker",
                title = "The high lob over the bunker",
                description = "Straight up and soft, with the green running away on the far side.",
                club = GolfClub.LOB_WEDGE,
                targetCarryYards = 55.0,
                curveYards = 0.0,
                apexYards = 20.0,
            ),
        )

    fun find(id: String): IconicShot? = shots.firstOrNull { it.id == id }
}
