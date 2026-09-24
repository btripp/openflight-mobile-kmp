// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotDetail

/**
 * Swing-speed training stats, ported from the web UI's `computeSwingSpeedStats`
 * (`ui/src/types/shot.ts`). Speeds are in mph; all zero when [count] is 0.
 */
data class SwingSpeedStats(
    val count: Int,
    val lastSpeedMph: Double,
    val bestSpeedMph: Double,
    val avgSpeedMph: Double,
) {
    companion object {
        val EMPTY: SwingSpeedStats = SwingSpeedStats(0, 0.0, 0.0, 0.0)
    }
}

/** `getSwingSpeedMph`: a swing rep's speed is its club speed, falling back to its ball speed. */
val ShotDetail.swingSpeedMph: Double?
    get() = clubSpeedMph ?: ballSpeedMph

/**
 * Mirrors `computeSwingSpeedStats` + `filterSwingSpeedShots`. [shots] must be **oldest first**
 * (the server's order), so the last one is the latest rep.
 *
 * @param playerName when non-blank, only this player's reps count (a missing name is "Player 1";
 *   compared trimmed and case-insensitively).
 * @param trainingImplement when non-blank, only reps whose implement key, implement label or club
 *   match it (trimmed, case-insensitive).
 */
fun computeSwingSpeedStats(
    shots: List<ShotDetail>,
    playerName: String?,
    trainingImplement: String?,
): SwingSpeedStats {
    val player = normalizePlayer(playerName)
    val implement = normalizeToken(trainingImplement)
    val speeds =
        shots
            .asSequence()
            .filter { it.isSwingSpeed }
            .filter { playerName.isNullOrEmpty() || normalizePlayer(it.playerName) == player }
            .filter {
                implement.isEmpty() ||
                    normalizeToken(it.trainingImplement) == implement ||
                    normalizeToken(it.trainingImplementLabel) == implement ||
                    normalizeToken(it.club) == implement
            }.mapNotNull { it.swingSpeedMph }
            .toList()
    if (speeds.isEmpty()) return SwingSpeedStats.EMPTY
    return SwingSpeedStats(
        count = speeds.size,
        lastSpeedMph = speeds.last(),
        bestSpeedMph = speeds.max(),
        avgSpeedMph = speeds.average(),
    )
}

private fun normalizePlayer(name: String?): String = (name?.trim()?.ifEmpty { null } ?: DEFAULT_PLAYER).lowercase()

private fun normalizeToken(value: String?): String = value?.trim()?.lowercase().orEmpty()

/** The server's name for a session with no player set (`set_player` with a blank name). */
const val DEFAULT_PLAYER: String = "Player 1"

/**
 * The web UI's `computeStats` over the Pi's session rows (the Socket.IO counterpart of
 * [computeClubStats]). A row missing its ball speed or carry is left out of that average only.
 */
fun computeDetailStats(shots: List<ShotDetail>): ClubStats {
    if (shots.isEmpty()) return ClubStats.EMPTY
    val ballSpeeds = shots.mapNotNull { it.ballSpeedMph }
    val carries = shots.mapNotNull { it.estimatedCarryYards }
    val clubSpeeds = shots.mapNotNull { it.clubSpeedMph }
    val smashFactors = shots.mapNotNull { it.smashFactor }
    return ClubStats(
        shotCount = shots.size,
        avgBallSpeedMph = ballSpeeds.averageOrZero(),
        maxBallSpeedMph = ballSpeeds.maxOrNull() ?: 0.0,
        avgCarryYards = carries.averageOrZero(),
        avgClubSpeedMph = clubSpeeds.takeIf { it.isNotEmpty() }?.average(),
        avgSmashFactor = smashFactors.takeIf { it.isNotEmpty() }?.average(),
    )
}

/** [computeClubChips] for the Pi's session rows; a row without a club counts under `""`. */
fun computeDetailClubChips(shots: List<ShotDetail>): List<ClubChip> {
    val counts = LinkedHashMap<String, Int>()
    for (shot in shots) {
        val club = shot.club.orEmpty()
        counts[club] = (counts[club] ?: 0) + 1
    }
    return counts.map { (club, count) -> ClubChip(club, count) }
}

/** The server's session stats in the app's [ClubStats] shape (`null` averages become 0, like the web UI). */
fun SessionStats.toClubStats(): ClubStats =
    ClubStats(
        shotCount = shotCount,
        avgBallSpeedMph = avgBallSpeed ?: 0.0,
        maxBallSpeedMph = maxBallSpeed ?: 0.0,
        avgCarryYards = avgCarryEst ?: 0.0,
        avgClubSpeedMph = avgClubSpeed,
        avgSmashFactor = avgSmashFactor,
    )

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
