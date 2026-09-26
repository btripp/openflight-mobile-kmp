// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.RangeSceneDescription
import dev.openflight.companion.core.flight.Vec3
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import kotlin.math.sqrt

/**
 * A stored shot as the [ShotEvent] the range flies and the metrics overlay shows, or `null` for
 * a row without the ball speed and carry a flight needs (a swing-speed rep). Its event id is the
 * stored SSE/BLE id, or [historyEventId] when the shot only came over Socket.IO (or was imported).
 */
fun HistoryShot.toRangeShotEvent(): ShotEvent? {
    val ballSpeed = detail.ballSpeedMph
    val carry = detail.estimatedCarryYards
    if (detail.isSwingSpeed || ballSpeed == null || carry == null) return null
    return ShotEvent(
        schemaVersion = HISTORY_SCHEMA_VERSION,
        eventId = eventId?.takeIf { UUID_PATTERN.matches(it) } ?: historyEventId(id),
        timestamp = detail.timestamp,
        club = detail.club.orEmpty(),
        ballSpeedMph = ballSpeed,
        clubSpeedMph = detail.clubSpeedMph,
        smashFactor = detail.smashFactor,
        estimatedCarryYards = carry,
        launchAngleVertical = detail.launchAngleVertical,
        launchAngleHorizontal = detail.launchAngleHorizontal,
        spinRpm = detail.spinRpm,
        clubPathDeg = detail.clubPathDeg,
        spinAxisDeg = detail.spinAxisDeg,
        shotNumber = detail.shotNumber,
    )
}

/**
 * A stable, UUID-shaped event id for stored row [rowId] ([ShotEvent] requires a UUID):
 * `00000000-0000-4000-8000-<row id in hex>`.
 */
fun historyEventId(rowId: Long): String =
    "00000000-0000-4000-8000-" +
        rowId
            .toULong()
            .toString(HEX_RADIX)
            .padStart(HEX_ID_LENGTH, '0')
            .takeLast(HEX_ID_LENGTH)

/** The list row for a stored shot, [number] being its 1-based position. */
fun HistoryShot.toRangeShotItem(number: Int): RangeShotItem {
    val club = detail.club.orEmpty()
    return RangeShotItem(
        id = id.toString(),
        number = number,
        club = club,
        clubLabel = if (club.isEmpty()) "Unknown club" else GolfClub.displayNameFor(club),
        carryYards = detail.estimatedCarryYards,
        ballSpeedMph = detail.ballSpeedMph,
        timestamp = detail.timestamp,
        flyable = toRangeShotEvent() != null,
    )
}

/** Clubs ordered driver first (the [GolfClub] order), unknown wire values last, alphabetically. */
fun orderClubs(clubs: Collection<String>): List<String> =
    clubs.distinct().sortedWith(
        compareBy<String>({ GolfClub.fromWireValue(it)?.ordinal ?: Int.MAX_VALUE }, { it }),
    )

/**
 * This trajectory resampled to [count] evenly timed points (the first and the landing kept), for
 * the overlay's static tracers. A trajectory with fewer points than that is returned unchanged.
 */
fun FlightTrajectory.downsampled(count: Int): FlightTrajectory {
    if (count < 2 || points.size <= count) return this
    val sampled =
        (0 until count).mapNotNull { index ->
            if (index == count - 1) points.last() else point(flightTime * index / (count - 1))
        }
    return copy(points = sampled)
}

/**
 * Where the F2 estimated roll-out ends, in simulator space (+z downrange): [rollYards] on from
 * the landing point along the ball's horizontal direction at landing. Straight downrange when the
 * landing velocity has no horizontal part.
 */
fun rollOutEnd(
    trajectory: FlightTrajectory,
    rollYards: Double,
): Vec3 {
    val landing = trajectory.points.lastOrNull() ?: return Vec3.ZERO
    val velocity = landing.velocityMetersPerSecond
    val horizontal = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
    val rollMeters = rollYards.coerceAtLeast(0.0) * RangeSceneDescription.YARDS_TO_METERS
    val directionX = if (horizontal > MIN_HORIZONTAL_SPEED) velocity.x / horizontal else 0.0
    val directionZ = if (horizontal > MIN_HORIZONTAL_SPEED) velocity.z / horizontal else 1.0
    val position = landing.positionMeters
    return Vec3(position.x + directionX * rollMeters, 0.0, position.z + directionZ * rollMeters)
}

/**
 * The overlay shot whose landing is nearest to the tap at ([x], [y]) through [projection], within
 * [maxDistancePixels]; `null` when none is that close.
 */
fun nearestOverlayLanding(
    projection: RangeProjection,
    flights: List<OverlayFlight>,
    x: Float,
    y: Float,
    maxDistancePixels: Float,
): String? {
    var best: String? = null
    var bestDistance = maxDistancePixels * maxDistancePixels
    for (flight in flights) {
        val landing =
            flight.trajectory.points
                .lastOrNull()
                ?.positionMeters ?: continue
        val scene = RangeProjection.flightToScene(landing)
        val screen = projection.project(scene.x, 0.0, scene.z)
        val dx = screen.x - x
        val dy = screen.y - y
        val distance = dx * dx + dy * dy
        // NaN (behind the camera) never compares as close enough.
        if (screen.isSpecified && distance <= bestDistance) {
            bestDistance = distance
            best = flight.shotId
        }
    }
    return best
}

private const val HISTORY_SCHEMA_VERSION = 1
private const val HEX_RADIX = 16
private const val HEX_ID_LENGTH = 12
private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private const val MIN_HORIZONTAL_SPEED = 1e-6
