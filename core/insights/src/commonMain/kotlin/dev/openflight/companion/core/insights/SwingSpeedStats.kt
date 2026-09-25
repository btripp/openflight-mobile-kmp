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
 * @param profileId when non-blank, only reps filed under this profile count (exact id match; the
 *   server replaced the web UI's player-name filter with profiles).
 * @param trainingImplement when non-blank, only reps whose implement key, implement label or club
 *   match it (trimmed, case-insensitive).
 */
fun computeSwingSpeedStats(
    shots: List<ShotDetail>,
    profileId: String?,
    trainingImplement: String?,
): SwingSpeedStats {
    val implement = normalizeToken(trainingImplement)
    val speeds =
        shots
            .asSequence()
            .filter { it.isSwingSpeed }
            .filter { profileId.isNullOrBlank() || it.profileId == profileId }
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

private fun normalizeToken(value: String?): String = value?.trim()?.lowercase().orEmpty()

/**
 * The kiosk's `computeStats` over the Pi's session rows (the Socket.IO counterpart of
 * [computeClubStats]). A row missing its ball speed or carry is left out of that figure only.
 * The Pi's session holds every profile's rows, so filter them with [forProfile] first.
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
        minBallSpeedMph = ballSpeeds.minOrNull() ?: 0.0,
        stdDevBallSpeedMph = sampleStdDev(ballSpeeds),
    )
}

/**
 * The rows filed under [profileId], keeping their order. Two people sharing a bay produce one
 * session, so screens show only the active profile's rows; like the kiosk and the Expo app
 * (`stats.tsx` `profileShots`), a blank id (no profile known yet) gives an empty list rather than
 * a guess at whose shots these are.
 */
fun List<ShotDetail>.forProfile(profileId: String): List<ShotDetail> =
    if (profileId.isBlank()) emptyList() else filter { it.profileId == profileId }

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
        minBallSpeedMph = minBallSpeed ?: 0.0,
        stdDevBallSpeedMph = stdDev ?: 0.0,
    )

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
