// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotMetricFormatter
import dev.openflight.companion.core.model.pi.ShotDetail

/**
 * Tester request 2026-09-30: the range's shot table, every measured field of the current
 * session's shots without leaving the range. Cells are display text in the chosen units, so both
 * platforms draw the same table.
 *
 * @property headers one per column, in [RangeTableColumn] order, with its unit ("Ball mph").
 * @property rows newest first.
 * @property average the mean of each measured column over [rows] ("Avg", "", then one per
 *   column; "—" where no shot has the value), or `null` without rows.
 */
data class RangeShotTable(
    val sessionId: String?,
    val headers: List<String>,
    val rows: List<RangeTableRow>,
    val average: List<String>?,
) {
    companion object {
        /** The table for [shots] (one session's, newest first) in [numbers]' units. */
        fun of(
            sessionId: String?,
            shots: List<HistoryShot>,
            numbers: RangeNumbers,
        ): RangeShotTable {
            val measured = shots.filterNot { it.detail.isSwingSpeed }
            val rows =
                measured.mapIndexed { index, shot ->
                    val detail = shot.detail
                    val club = detail.club.orEmpty()
                    RangeTableRow(
                        id = shot.id.toString(),
                        cells =
                            listOf(
                                (measured.size - index).toString(),
                                if (club.isEmpty()) ShotMetricFormatter.MISSING else GolfClub.displayNameFor(club),
                            ) + RangeTableColumn.measured.map { it.format(it.value(detail), numbers) },
                    )
                }
            val average =
                measured.takeIf { it.isNotEmpty() }?.let { list ->
                    listOf("Avg", "") +
                        RangeTableColumn.measured.map { column ->
                            column.format(list.mapNotNull { column.value(it.detail) }.averageOrNull(), numbers)
                        }
                }
            return RangeShotTable(
                sessionId = sessionId,
                headers = RangeTableColumn.entries.map { it.header(numbers) },
                rows = rows,
                average = average,
            )
        }

        private fun List<Double>.averageOrNull(): Double? =
            filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.average()
    }
}

/**
 * One shot in [RangeShotTable].
 *
 * @property id the stored row's id ([HistoryShot.id]): a tap opens it on the range ([RangeLaunch.shotId]).
 * @property cells one per column, in [RangeTableColumn] order.
 */
data class RangeTableRow(
    val id: String,
    val cells: List<String>,
)

/** The shot table's columns, in the order shown. */
enum class RangeTableColumn {
    NUMBER,
    CLUB,
    BALL_SPEED,
    CLUB_SPEED,
    SMASH,
    CARRY,
    LAUNCH,
    DIRECTION,
    SPIN,
    SPIN_AXIS,
    CLUB_PATH,
    ;

    internal fun header(numbers: RangeNumbers): String =
        when (this) {
            NUMBER -> "#"
            CLUB -> "Club"
            BALL_SPEED -> "Ball ${numbers.speedUnit}"
            CLUB_SPEED -> "Club ${numbers.speedUnit}"
            SMASH -> "Smash"
            CARRY -> "Carry ${numbers.distanceUnit}"
            LAUNCH -> "Launch °"
            DIRECTION -> "Dir °"
            SPIN -> "Spin rpm"
            SPIN_AXIS -> "Axis °"
            CLUB_PATH -> "Path °"
        }

    /** The column's measurement, in the wire's imperial units; `null` for [NUMBER] and [CLUB]. */
    internal fun value(detail: ShotDetail): Double? =
        when (this) {
            NUMBER, CLUB -> null

            BALL_SPEED -> detail.ballSpeedMph

            CLUB_SPEED -> detail.clubSpeedMph

            SMASH -> detail.smashFactor

            // What the kiosk and the range's CARRY show: spin-adjusted when the Pi sent one.
            CARRY -> detail.carrySpinAdjusted?.takeIf { it.isFinite() && it > 0 } ?: detail.estimatedCarryYards

            LAUNCH -> detail.launchAngleVertical

            DIRECTION -> detail.launchAngleHorizontal

            SPIN -> detail.spinRpm

            SPIN_AXIS -> detail.spinAxisDeg

            CLUB_PATH -> detail.clubPathDeg
        }

    internal fun format(
        value: Double?,
        numbers: RangeNumbers,
    ): String =
        when (this) {
            BALL_SPEED, CLUB_SPEED -> numbers.speed(value)
            CARRY -> numbers.distance(value)
            SMASH -> ShotMetricFormatter.number(value, decimals = 2)
            LAUNCH -> ShotMetricFormatter.number(value, decimals = 1)
            DIRECTION, SPIN_AXIS, CLUB_PATH -> ShotMetricFormatter.number(value, decimals = 1, signed = true)
            SPIN -> ShotMetricFormatter.number(value, decimals = 0)
            NUMBER, CLUB -> ShotMetricFormatter.MISSING
        }

    internal companion object {
        /** The columns after # and Club. */
        val measured: List<RangeTableColumn> = entries.drop(2)
    }
}
