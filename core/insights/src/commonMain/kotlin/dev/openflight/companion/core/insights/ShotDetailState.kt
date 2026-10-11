// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import dev.openflight.companion.core.flight.FlightMeasurements
import dev.openflight.companion.core.flight.FlightParameter
import dev.openflight.companion.core.flight.ShotFlightPath
import dev.openflight.companion.core.flight.ShotFlightPlanner
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.ShotMetricFormatter
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlin.math.abs

/**
 * Everything the shot detail shows for one shot, from whichever source it came from (a live
 * [ShotEvent], a Pi session row or a stored history row). `null` means "not measured".
 */
data class ShotDetailInput(
    val id: String,
    val shotNumber: Int?,
    val club: String,
    val timestamp: String,
    val ballSpeedMph: Double?,
    val carryYards: Double?,
    val carrySpinAdjustedYards: Double? = null,
    val clubSpeedMph: Double? = null,
    val smashFactor: Double? = null,
    val launchAngleVertical: Double? = null,
    val launchAngleHorizontal: Double? = null,
    val spinRpm: Double? = null,
    val spinAxisDeg: Double? = null,
    val spinSource: String? = null,
    val clubPathDeg: Double? = null,
    val swingSpeedMph: Double? = null,
) {
    /** What the flight needs, or `null` for a swing-speed rep or without a ball speed and carry. */
    val measurements: FlightMeasurements?
        get() {
            val speed = ballSpeedMph
            val carry = carryYards
            if (swingSpeedMph != null || speed == null || carry == null) return null
            return FlightMeasurements(
                id = id,
                club = club,
                ballSpeedMph = speed,
                carryYards = carry,
                launchAngleVertical = launchAngleVertical,
                launchAngleHorizontal = launchAngleHorizontal,
                spinRpm = spinRpm,
                spinAxisDeg = spinAxisDeg,
                carrySpinAdjustedYards = carrySpinAdjustedYards,
                spinSource = spinSource,
            )
        }
}

/** A Pi or stored row as a shot detail; [id] defaults to its timestamp (the Pi's key). */
fun ShotDetail.toShotDetailInput(
    shotNumber: Int? = this.shotNumber,
    id: String = timestamp,
): ShotDetailInput =
    ShotDetailInput(
        id = id,
        shotNumber = shotNumber,
        club = club.orEmpty(),
        timestamp = timestamp,
        ballSpeedMph = ballSpeedMph,
        carryYards = estimatedCarryYards,
        carrySpinAdjustedYards = carrySpinAdjusted,
        clubSpeedMph = clubSpeedMph,
        smashFactor = smashFactor,
        launchAngleVertical = launchAngleVertical,
        launchAngleHorizontal = launchAngleHorizontal,
        spinRpm = spinRpm,
        spinAxisDeg = spinAxisDeg,
        spinSource = spinSource,
        clubPathDeg = clubPathDeg,
        swingSpeedMph = if (isSwingSpeed) swingSpeedMph else null,
    )

/** A live shot as a shot detail, filled in from the Pi's [detail] of it when known. */
fun ShotEvent.toShotDetailInput(
    shotNumber: Int?,
    detail: ShotDetail? = null,
): ShotDetailInput =
    ShotDetailInput(
        id = eventId,
        shotNumber = shotNumber,
        club = club,
        timestamp = timestamp,
        ballSpeedMph = ballSpeedMph,
        carryYards = estimatedCarryYards,
        carrySpinAdjustedYards = detail?.carrySpinAdjusted,
        clubSpeedMph = clubSpeedMph,
        smashFactor = smashFactor,
        launchAngleVertical = launchAngleVertical,
        launchAngleHorizontal = launchAngleHorizontal,
        spinRpm = spinRpm,
        spinAxisDeg = spinAxisDeg,
        spinSource = spinSource,
        clubPathDeg = clubPathDeg ?: detail?.clubPathDeg,
        swingSpeedMph = detail?.takeIf { it.isSwingSpeed }?.swingSpeedMph,
    )

/** One cell of the shot detail's metrics grid; [estimated] values are badged "est.". */
data class ShotDetailMetric(
    val label: String,
    val value: String,
    val unit: String = "",
    val estimated: Boolean = false,
)

/**
 * The shot detail sheet's state: a title, the shot's [flight] and its [shapeLabel] ("Push fade"),
 * the full [metrics] grid, and [notes] on anything estimated rather than measured.
 *
 * @property flight `null` when the shot can't be flown (a swing-speed rep, or no ball speed or
 *   carry); the sheet then shows the metrics alone.
 */
data class ShotDetailState(
    val id: String,
    val title: String,
    val subtitle: String,
    val shapeLabel: String?,
    val shapeDescription: String?,
    val flight: ShotFlightPath?,
    val metrics: List<ShotDetailMetric>,
    val notes: List<String>,
    val accessibilitySummary: String,
)

/**
 * Builds [ShotDetailState]s. The flight comes from [ShotFlightPlanner], so it lands at the same
 * carry, in the same air, as the range and the bag show for that shot.
 */
class ShotDetailBuilder(
    private val planner: ShotFlightPlanner = ShotFlightPlanner(),
) {
    fun build(
        input: ShotDetailInput,
        conditions: Conditions,
        targetBearing: TargetBearing?,
        units: UnitSystem,
    ): ShotDetailState {
        val planned = input.measurements?.let { planner.plan(it, conditions, targetBearing) }
        val flight = planned?.let(ShotFlightPath::from)
        val heightEstimated =
            planned != null &&
                (planned.input.provenance.usesEstimatedFlight || planned.trajectory.carryFit?.shapeEstimated == true)
        val clubName = GolfClub.displayNameFor(input.club.ifEmpty { "Unknown" })
        val title = input.shotNumber?.let { "Shot $it · $clubName" } ?: clubName
        val metrics =
            buildList {
                if (input.swingSpeedMph != null) {
                    add(speed("Swing speed", input.swingSpeedMph, units))
                } else {
                    addDistances(input, planned?.estimate?.carryYards, planned?.estimate?.totalYards, flight, units)
                    addFlight(flight, heightEstimated, units)
                    addLaunch(input, units)
                }
            }
        val shape = flight?.shape
        val notes =
            notes(
                input = input,
                flight = flight,
                estimated =
                    planned
                        ?.input
                        ?.provenance
                        ?.estimatedParameters
                        .orEmpty(),
                heightEstimated = heightEstimated,
                adjusted = planned?.estimate?.isAdjusted == true,
            )
        return ShotDetailState(
            id = input.id,
            title = title,
            subtitle = input.timestamp.take(TIMESTAMP_MINUTES).replace('T', ' '),
            shapeLabel = shape?.label,
            shapeDescription = shape?.description,
            flight = flight,
            metrics = metrics,
            notes = notes,
            accessibilitySummary = summary(title, shape?.label, shape?.description, metrics),
        )
    }

    private fun MutableList<ShotDetailMetric>.addDistances(
        input: ShotDetailInput,
        carryYards: Double?,
        totalYards: Double?,
        flight: ShotFlightPath?,
        units: UnitSystem,
    ) {
        add(distance("Carry", carryYards ?: input.carryYards, units))
        add(distance("Total", totalYards, units).copy(estimated = totalYards != null))
        val sideMeasured = input.launchAngleHorizontal != null || input.spinAxisDeg != null
        val offline = flight?.takeIf { sideMeasured }?.landing?.x
        add(
            ShotDetailMetric(
                label = "Offline",
                value = offline?.let { formatSide(convertDistanceFromYards(it, units), 0) } ?: MISSING,
                unit = distanceUnitLabel(units),
            ),
        )
    }

    private fun MutableList<ShotDetailMetric>.addFlight(
        flight: ShotFlightPath?,
        estimated: Boolean,
        units: UnitSystem,
    ) {
        val metric = units == UnitSystem.METRIC
        val apex = flight?.apexYards?.let { if (metric) it * YARDS_TO_METERS else it * FEET_PER_YARD }
        val flown = flight != null && estimated
        add(ShotDetailMetric("Apex", ShotMetricFormatter.number(apex, 0), if (metric) "m" else "ft", flown))
        add(ShotDetailMetric("Descent", flight?.let { formatDegrees(it.descentDegrees, 0) } ?: MISSING, "", flown))
        add(ShotDetailMetric("Hang time", ShotMetricFormatter.number(flight?.flightTimeSeconds, 1), "s", flown))
    }

    private fun MutableList<ShotDetailMetric>.addLaunch(
        input: ShotDetailInput,
        units: UnitSystem,
    ) {
        add(speed("Ball speed", input.ballSpeedMph, units))
        add(speed("Club speed", input.clubSpeedMph, units))
        add(ShotDetailMetric("Smash", ShotMetricFormatter.number(input.smashFactor, 2)))
        add(ShotDetailMetric("Launch", input.launchAngleVertical?.let { formatDegrees(it) } ?: MISSING))
        add(ShotDetailMetric("Direction", input.launchAngleHorizontal?.let { formatSide(it, 1) + "°" } ?: MISSING))
        add(
            ShotDetailMetric(
                label = "Spin",
                value = ShotMetricFormatter.number(input.spinRpm, 0),
                unit = "rpm",
                estimated = input.spinRpm != null && input.spinSource in ESTIMATED_SPIN_SOURCES,
            ),
        )
        add(ShotDetailMetric("Spin axis", input.spinAxisDeg?.let { formatSide(it, 1) + "°" } ?: MISSING))
        add(ShotDetailMetric("Club path", input.clubPathDeg?.let { formatSide(it, 1) + "°" } ?: MISSING))
    }

    private fun notes(
        input: ShotDetailInput,
        flight: ShotFlightPath?,
        estimated: Set<FlightParameter>,
        heightEstimated: Boolean,
        adjusted: Boolean,
    ): List<String> =
        buildList {
            when {
                input.swingSpeedMph != null -> {
                    add("A swing-speed rep has no ball flight to draw.")
                }

                flight == null -> {
                    add("This shot has no ball speed or carry, so there's no flight to draw.")
                }

                else -> {
                    if (FlightParameter.SPIN_AXIS in estimated) {
                        add("Spin axis wasn't measured, so the flight is drawn without curve.")
                    }
                    if (FlightParameter.HORIZONTAL_LAUNCH in estimated) {
                        add("Launch direction wasn't measured, so the flight starts on the target line.")
                    }
                    if (heightEstimated) add("Launch or spin was estimated, so the height is approximate.")
                    if (adjusted) add("Distances are adjusted for your conditions.")
                }
            }
        }

    private fun summary(
        title: String,
        shape: String?,
        description: String?,
        metrics: List<ShotDetailMetric>,
    ): String {
        val shapePart = shape?.let { listOfNotNull(it, description).joinToString(": ") }
        val metricPart =
            metrics.filter { it.value != MISSING }.joinToString(", ") { metric ->
                listOf(metric.label, metric.value, metric.unit, if (metric.estimated) "estimated" else "")
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
            }
        return listOfNotNull(title, shapePart, metricPart.ifEmpty { null }).joinToString(". ")
    }

    private fun distance(
        label: String,
        yards: Double?,
        units: UnitSystem,
    ) = ShotDetailMetric(
        label = label,
        value = ShotMetricFormatter.number(yards?.let { convertDistanceFromYards(it, units) }, 0),
        unit = distanceUnitLabel(units),
    )

    private fun speed(
        label: String,
        mph: Double?,
        units: UnitSystem,
    ) = ShotDetailMetric(
        label = label,
        value = ShotMetricFormatter.number(mph?.let { convertSpeedFromMph(it, units) }, 1),
        unit = speedUnitLabel(units),
    )

    private companion object {
        const val MISSING = ShotMetricFormatter.MISSING
        const val TIMESTAMP_MINUTES = 16
        const val YARDS_TO_METERS = 0.9144
        const val FEET_PER_YARD = 3.0
        val ESTIMATED_SPIN_SOURCES = setOf("calculated", "club_typical")
    }
}

/**
 * A left/right value as golfers read it: "R 6", "L 2.1", or a plain "0" when it rounds onto the
 * line (positive is right).
 */
fun formatSide(
    value: Double,
    decimals: Int,
): String {
    val magnitude = ShotMetricFormatter.number(abs(value), decimals)
    return when {
        magnitude == ShotMetricFormatter.number(0.0, decimals) -> magnitude
        value > 0 -> "R $magnitude"
        else -> "L $magnitude"
    }
}
