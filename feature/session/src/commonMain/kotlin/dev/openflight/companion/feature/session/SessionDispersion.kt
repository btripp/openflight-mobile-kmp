// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import dev.openflight.companion.core.flight.BallFlightSimulator
import dev.openflight.companion.core.flight.FlightInputResolutionError
import dev.openflight.companion.core.flight.FlightInputResolver
import dev.openflight.companion.core.flight.FlightMeasurements
import dev.openflight.companion.core.flight.FlightParameter
import dev.openflight.companion.core.flight.toFlightMeasurements
import dev.openflight.companion.core.insights.DispersionEllipse
import dev.openflight.companion.core.insights.DispersionSample
import dev.openflight.companion.core.insights.DispersionViewport
import dev.openflight.companion.core.insights.MIN_SHOTS_FOR_ELLIPSE
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.computeDispersionEllipse
import dev.openflight.companion.core.insights.computeDispersionViewport
import dev.openflight.companion.core.insights.convertDistanceFromYards
import dev.openflight.companion.core.insights.distanceUnitLabel
import dev.openflight.companion.core.insights.findDispersionOutliers
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.ShotMetricFormatter
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlin.math.abs

/**
 * One shot on the dispersion chart.
 *
 * @property id the same id as its [SessionShotRow], so a dot and its row select together.
 * @property colorIndex the club's colour slot: clubs in this session, in bag order (driver
 *   first), so a club keeps its colour when the tab filters the others out.
 * @property offlineYards positive right of the target line. Nothing on the wire reports it: it's
 *   the landing point of `core:flight`'s simulated trajectory, scaled to the reported carry.
 * @property sideEstimated the shot reported neither horizontal launch nor spin axis, so it sits on
 *   the target line by default rather than by measurement.
 * @property possibleBadRead it lands far outside the rest of its club (`findDispersionOutliers`):
 *   worth a look, and left out of the club's ellipse and spread. Never deleted automatically.
 */
data class DispersionPoint(
    val id: String,
    val shotNumber: Int,
    val club: String,
    val shortLabel: String,
    val colorIndex: Int,
    val carryYards: Double,
    val offlineYards: Double,
    val sideEstimated: Boolean,
    val possibleBadRead: Boolean = false,
) {
    val sample: DispersionSample get() = DispersionSample(offlineYards, carryYards)
}

/** One club's typical landing area. */
data class ClubDispersion(
    val club: String,
    val colorIndex: Int,
    val ellipse: DispersionEllipse,
)

/**
 * The dispersion chart for the selected tab.
 *
 * @property points newest first, like the shot list.
 * @property ellipses one per club with enough shots, in bag order.
 * @property estimatedSideCount how many [points] are [DispersionPoint.sideEstimated].
 * @property possibleBadReadCount how many [points] are [DispersionPoint.possibleBadRead].
 * @property clubSpread the selected club's spread; `null` on the "All" tab.
 */
data class SessionDispersionUiState(
    val points: List<DispersionPoint>,
    val ellipses: List<ClubDispersion>,
    val viewport: DispersionViewport,
    val estimatedSideCount: Int,
    val possibleBadReadCount: Int = 0,
    val clubSpread: ClubSpread? = null,
)

/**
 * One club's spread, leaving out [DispersionPoint.possibleBadRead] shots.
 *
 * @property avgCarryYards over every other shot.
 * @property avgOfflineYards the side-to-side bias, positive right, over shots with a measured side;
 *   `null` when none has one.
 * @property widthYards how wide the club's typical landing area (its ellipse) is, left to right;
 *   `null` below [MIN_SHOTS_FOR_ELLIPSE] measured shots.
 * @property depthYards how deep that area is, short to long; `null` alongside [widthYards].
 * @property excludedCount how many possible bad reads were left out.
 */
data class ClubSpread(
    val club: String,
    val clubName: String,
    val shotCount: Int,
    val avgCarryYards: Double,
    val avgOfflineYards: Double?,
    val widthYards: Double?,
    val depthYards: Double?,
    val excludedCount: Int,
)

/**
 * The card for the shot selected on the chart or in the list. `null` fields weren't reported;
 * [possibleBadRead] mirrors its [DispersionPoint].
 */
data class SelectedShotCard(
    val id: String,
    val shotNumber: Int,
    val clubName: String,
    val carryYards: Double?,
    val spinRpm: Double?,
    val clubSpeedMph: Double?,
    val possibleBadRead: Boolean = false,
)

/**
 * Builds [DispersionPoint]s, simulating each shot's flight once: results are kept per
 * [FlightMeasurements] (which includes the shot's id), and dropped once the shot leaves the session.
 * Not thread-safe; [SessionViewModel] only calls it from its state flow.
 */
internal class DispersionCalculator(
    private val resolver: FlightInputResolver = FlightInputResolver(),
    private val simulator: BallFlightSimulator = BallFlightSimulator(),
) {
    private var landings: Map<FlightMeasurements, Landing?> = emptyMap()

    /** Points for the phone's history (newest first); swing reps (known from the Pi) are left out. */
    fun localPoints(
        history: List<ShotEvent>,
        details: Map<String, ShotDetail>,
    ): List<DispersionPoint> {
        val candidates =
            history.mapIndexedNotNull { index, shot ->
                if (details[shot.timestamp]?.isSwingSpeed == true) return@mapIndexedNotNull null
                Candidate(shotNumber = history.size - index, measurements = shot.toFlightMeasurements())
            }
        return points(candidates)
    }

    /** Points for the Pi's session (newest first); swing reps and rows missing speed or carry are left out. */
    fun piPoints(session: List<ShotDetail>): List<DispersionPoint> {
        val candidates =
            session.mapIndexedNotNull { index, detail ->
                val ballSpeed = detail.ballSpeedMph
                val carry = detail.estimatedCarryYards
                if (detail.isSwingSpeed || ballSpeed == null || carry == null) return@mapIndexedNotNull null
                Candidate(
                    shotNumber = session.size - index,
                    measurements =
                        FlightMeasurements(
                            id = detail.timestamp,
                            club = detail.club.orEmpty(),
                            ballSpeedMph = ballSpeed,
                            carryYards = carry,
                            launchAngleVertical = detail.launchAngleVertical,
                            launchAngleHorizontal = detail.launchAngleHorizontal,
                            spinRpm = detail.spinRpm,
                            spinAxisDeg = detail.spinAxisDeg,
                        ),
                )
            }
        return points(candidates)
    }

    private fun points(candidates: List<Candidate>): List<DispersionPoint> {
        val previous = landings
        val next = HashMap<FlightMeasurements, Landing?>(candidates.size)
        for (candidate in candidates) {
            val key = candidate.measurements
            next[key] = if (key in previous) previous[key] else land(key)
        }
        landings = next

        val colorIndexes = clubColorIndexes(candidates.map { it.measurements.club })
        return candidates.mapNotNull { candidate ->
            val measurements = candidate.measurements
            val landing = next[measurements] ?: return@mapNotNull null
            DispersionPoint(
                id = measurements.id,
                shotNumber = candidate.shotNumber,
                club = measurements.club,
                shortLabel = GolfClub.shortLabelFor(measurements.club),
                colorIndex = colorIndexes.getValue(measurements.club),
                carryYards = measurements.carryYards,
                offlineYards = landing.offlineYards,
                sideEstimated = landing.sideEstimated,
            )
        }
    }

    /** `null` when the shot can't be flown (no usable ball speed or carry). */
    private fun land(measurements: FlightMeasurements): Landing? {
        val input =
            try {
                resolver.resolve(measurements)
            } catch (_: FlightInputResolutionError) {
                return null
            }
        val trajectory = simulator.simulate(input)
        val estimated = input.provenance.estimatedParameters
        return Landing(
            offlineYards = trajectory.lateralMeters / METERS_PER_YARD,
            sideEstimated = FlightParameter.HORIZONTAL_LAUNCH in estimated && FlightParameter.SPIN_AXIS in estimated,
        )
    }

    private data class Candidate(
        val shotNumber: Int,
        val measurements: FlightMeasurements,
    )

    private data class Landing(
        val offlineYards: Double,
        val sideEstimated: Boolean,
    )

    private companion object {
        const val METERS_PER_YARD = 0.9144
    }
}

/** Colour slots for [clubs]: bag order (unknown clubs last, alphabetically), numbered from 0. */
internal fun clubColorIndexes(clubs: List<String>): Map<String, Int> =
    clubs
        .distinct()
        .sortedWith(compareBy({ GolfClub.fromWireValue(it)?.ordinal ?: Int.MAX_VALUE }, { it }))
        .withIndex()
        .associate { (index, club) -> club to index }

/**
 * The chart for [points] (already newest first), filtered to [selectedClub] (`null` = every
 * club), with distance arcs in [units]. Each club's possible bad reads are flagged; its ellipse
 * uses only its other shots with a measured side. `null` when there's nothing to plot.
 */
internal fun dispersionState(
    points: List<DispersionPoint>,
    selectedClub: String?,
    units: UnitSystem,
): SessionDispersionUiState? {
    val shown =
        (if (selectedClub == null) points else points.filter { it.club == selectedClub })
            .withPossibleBadReads()
    val ellipses =
        shown
            .groupBy { it.club }
            .mapNotNull { (club, clubPoints) ->
                computeDispersionEllipse(clubPoints.typical().map { it.sample })?.let {
                    ClubDispersion(club, clubPoints.first().colorIndex, it)
                }
            }.sortedBy { it.colorIndex }
    val viewport =
        computeDispersionViewport(
            samples = shown.map { it.sample },
            ellipses = ellipses.map { it.ellipse },
            unitsPerYard = convertDistanceFromYards(1.0, units),
        ) ?: return null
    return SessionDispersionUiState(
        points = shown,
        ellipses = ellipses,
        viewport = viewport,
        estimatedSideCount = shown.count { it.sideEstimated },
        possibleBadReadCount = shown.count { it.possibleBadRead },
        clubSpread = selectedClub?.let { club -> clubSpread(club, shown, ellipses.firstOrNull { it.club == club }) },
    )
}

/** Flags each club's possible bad reads (keeping the order). */
private fun List<DispersionPoint>.withPossibleBadReads(): List<DispersionPoint> {
    val flagged =
        groupBy { it.club }
            .values
            .flatMap { clubPoints ->
                findDispersionOutliers(clubPoints.map { it.sample }, clubPoints.map { !it.sideEstimated })
                    .map { clubPoints[it].id }
            }.toSet()
    return map { if (it.id in flagged) it.copy(possibleBadRead = true) else it }
}

/** The shots that shape a club's typical landing area: measured side, not a possible bad read. */
private fun List<DispersionPoint>.typical(): List<DispersionPoint> = filter { !it.sideEstimated && !it.possibleBadRead }

private fun clubSpread(
    club: String,
    shown: List<DispersionPoint>,
    ellipse: ClubDispersion?,
): ClubSpread? {
    val kept = shown.filter { it.club == club && !it.possibleBadRead }
    if (kept.isEmpty()) return null
    val measured = kept.filter { !it.sideEstimated }
    return ClubSpread(
        club = club,
        clubName = GolfClub.fromWireValue(club)?.displayName ?: club,
        shotCount = kept.size,
        avgCarryYards = kept.map { it.carryYards }.average(),
        avgOfflineYards = measured.takeIf { it.isNotEmpty() }?.map { it.offlineYards }?.average(),
        widthYards = ellipse?.ellipse?.let { it.halfExtentOfflineYards * 2 },
        depthYards = ellipse?.ellipse?.let { it.halfExtentCarryYards * 2 },
        excludedCount = shown.count { it.club == club && it.possibleBadRead },
    )
}

/** The card for [selectedId], if that shot is on the chart. */
internal fun selectedShotCard(
    selectedId: String?,
    dispersion: SessionDispersionUiState?,
    rows: List<SessionShotRow>,
): SelectedShotCard? {
    val onChart = selectedId != null && dispersion?.points?.any { it.id == selectedId } == true
    val row = rows.firstOrNull { onChart && it.id == selectedId } ?: return null
    return SelectedShotCard(
        id = row.id,
        shotNumber = row.shotNumber,
        clubName = GolfClub.fromWireValue(row.club)?.displayName ?: row.club,
        carryYards = row.carryYards,
        spinRpm = row.spinRpm,
        clubSpeedMph = row.clubSpeedMph,
        possibleBadRead = dispersion?.points?.any { it.id == row.id && it.possibleBadRead } == true,
    )
}

/** Dispersion wording shared by both platforms, so Android and iOS say the same thing. */
@Suppress("TooManyFunctions") // The shown and the spoken wording, with their small formatters.
object DispersionCopy {
    const val BAD_READ_NOTE: String =
        "Possible bad read: it lands far from this club's other shots. Delete it if the data looks wrong."

    private const val ON_LINE_UNITS = 0.5

    fun badReadCaption(count: Int): String =
        if (count == 1) {
            "1 possible bad read (amber ring). Tap it to check, and delete it if it's wrong."
        } else {
            "$count possible bad reads (amber rings). Tap one to check, and delete it if it's wrong."
        }

    /** How a screen reader asks to step through the dots (plan R8f). */
    const val NEXT_SHOT: String = "Next shot"
    const val PREVIOUS_SHOT: String = "Previous shot"
    const val CLEAR_SELECTION: String = "Clear selection"
    const val NO_SELECTION: String = "No shot selected"

    /**
     * What a screen reader says for the whole chart (plan R8f): the shot count and clubs, then, per
     * club in bag order, its count, average carry and average side (over shots with a measured
     * side), so the picture isn't the only way to read the dispersion. e.g. "Dispersion chart, 3
     * shots: Driver, 7-Iron. Driver: 2 shots, 257 yds average carry, 4 yds right on average.
     * 7-Iron: 1 shot, 165 yds average carry, side not measured."
     */
    fun chartSummary(
        dispersion: SessionDispersionUiState,
        units: UnitSystem,
    ): String {
        val points = dispersion.points
        val clubs = points.map { clubName(it.club) }.distinct().joinToString(", ")
        val header = "Dispersion chart, ${shotCount(points.size)}: $clubs."
        val perClub =
            points
                .groupBy { it.club }
                .values
                .sortedBy { clubPoints -> clubPoints.first().colorIndex }
                .map { clubPoints -> clubSummary(clubPoints, units) }
        return (listOf(header) + perClub).joinToString(" ")
    }

    /** "Driver: 2 shots, 257 yds average carry, 4 yds right on average." */
    private fun clubSummary(
        clubPoints: List<DispersionPoint>,
        units: UnitSystem,
    ): String {
        val measured = clubPoints.filter { !it.sideEstimated }
        val side =
            if (measured.isEmpty()) {
                "side not measured"
            } else {
                "${sideText(measured.map { it.offlineYards }.average(), units)} on average"
            }
        val badReads = clubPoints.count { it.possibleBadRead }
        val badReadText =
            when (badReads) {
                0 -> null
                1 -> "1 possible bad read"
                else -> "$badReads possible bad reads"
            }
        return listOfNotNull(
            "${clubName(clubPoints.first().club)}: ${shotCount(clubPoints.size)}",
            "${distanceText(clubPoints.map { it.carryYards }.average(), units)} average carry",
            side,
            badReadText,
        ).joinToString(", ") + "."
    }

    /** One dot for a screen reader: "Shot 3, Driver, 250 yds carry, 4 yds right". */
    fun pointDescription(
        point: DispersionPoint,
        units: UnitSystem,
    ): String =
        listOfNotNull(
            "Shot ${point.shotNumber}",
            clubName(point.club),
            "${distanceText(point.carryYards, units)} carry",
            if (point.sideEstimated) "side not measured" else sideText(point.offlineYards, units),
            if (point.possibleBadRead) "possible bad read" else null,
        ).joinToString(", ")

    /** The selected dot's description, or [NO_SELECTION]. */
    fun selectionDescription(
        dispersion: SessionDispersionUiState,
        selectedId: String?,
        units: UnitSystem,
    ): String =
        dispersion.points.firstOrNull { it.id == selectedId }?.let { pointDescription(it, units) } ?: NO_SELECTION

    /**
     * The shot after (or before) [selectedId] in shot-number order, for stepping through the dots
     * without seeing them; with nothing selected, the oldest (or newest). `null` past either end.
     */
    fun adjacentShotId(
        points: List<DispersionPoint>,
        selectedId: String?,
        forward: Boolean,
    ): String? {
        val ordered = points.sortedBy { it.shotNumber }
        val index = ordered.indexOfFirst { it.id == selectedId }
        val next =
            when {
                index < 0 -> if (forward) ordered.firstOrNull() else ordered.lastOrNull()
                forward -> ordered.getOrNull(index + 1)
                else -> ordered.getOrNull(index - 1)
            }
        return next?.id
    }

    private fun shotCount(count: Int): String = if (count == 1) "1 shot" else "$count shots"

    private fun clubName(club: String): String = GolfClub.fromWireValue(club)?.displayName ?: club.ifEmpty { "Unknown" }

    private fun distanceText(
        yards: Double,
        units: UnitSystem,
    ): String = ShotMetricFormatter.number(convertDistanceFromYards(yards, units), 0) + " " + distanceUnitLabel(units)

    private fun sideText(
        offlineYards: Double,
        units: UnitSystem,
    ): String =
        when {
            abs(convertDistanceFromYards(offlineYards, units)) < ON_LINE_UNITS -> "on line"
            offlineYards > 0 -> "${distanceText(offlineYards, units)} right"
            else -> "${distanceText(-offlineYards, units)} left"
        }

    /** e.g. "5 shots · 162 yds avg carry · 1 yds right · 9 yds wide × 12 yds deep". */
    fun spreadSummary(
        spread: ClubSpread,
        units: UnitSystem,
    ): String {
        val unit = distanceUnitLabel(units)

        fun distance(yards: Double) = ShotMetricFormatter.number(convertDistanceFromYards(yards, units), 0) + " " + unit
        val parts = mutableListOf(if (spread.shotCount == 1) "1 shot" else "${spread.shotCount} shots")
        parts += "${distance(spread.avgCarryYards)} avg carry"
        spread.avgOfflineYards?.let { offline ->
            parts +=
                when {
                    abs(convertDistanceFromYards(offline, units)) < ON_LINE_UNITS -> "on line"
                    offline > 0 -> "${distance(offline)} right"
                    else -> "${distance(-offline)} left"
                }
        }
        val width = spread.widthYards
        val depth = spread.depthYards
        parts +=
            if (width != null && depth != null) {
                "${distance(width)} wide × ${distance(depth)} deep"
            } else {
                "spread needs $MIN_SHOTS_FOR_ELLIPSE shots with side data"
            }
        if (spread.excludedCount > 0) {
            parts +=
                if (spread.excludedCount ==
                    1
                ) {
                    "1 possible bad read left out"
                } else {
                    "${spread.excludedCount} possible bad reads left out"
                }
        }
        return parts.joinToString(" · ")
    }
}
