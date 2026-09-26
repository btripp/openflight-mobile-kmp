// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.flow.Flow

private var nextShotId = 0L

/** A stored shot of [club] carrying [carry] yards, with measured launch and spin. */
internal fun historyShot(
    club: GolfClub,
    carry: Double,
    sessionId: String = "s1",
    horizontal: Double? = 0.0,
    timestamp: String = "2026-09-25T10:00:${(nextShotId % 60).toString().padStart(2, '0')}",
): HistoryShot {
    val id = ++nextShotId
    return HistoryShot(
        id = id,
        sessionId = sessionId,
        eventId = "e$id",
        detail =
            ShotDetail(
                timestamp = "$timestamp.$id",
                club = club.wireValue,
                ballSpeedMph = carry * BALL_SPEED_PER_CARRY_YARD,
                estimatedCarryYards = carry,
                launchAngleVertical = 16.0,
                launchAngleHorizontal = horizontal,
                spinRpm = 6500.0,
                spinAxisDeg = 0.0,
            ),
    )
}

/** Five shots of [club] at [carry] − 2, − 1, 0, + 1, + 2 yards: mean [carry]. */
internal fun fiveShots(
    club: GolfClub,
    carry: Double,
    sessionId: String = "s1",
): List<HistoryShot> = (-2..2).map { historyShot(club, carry + it, sessionId) }

private const val BALL_SPEED_PER_CARRY_YARD = 0.75

internal suspend fun <T> Flow<T>.testIgnoringRest(block: suspend ReceiveTurbine<T>.() -> Unit) =
    test {
        block()
        cancelAndIgnoreRemainingEvents()
    }

internal suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
