// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.flight.CarryAnchor
import dev.openflight.companion.core.flight.FlightInput
import dev.openflight.companion.core.flight.FlightInputResolver
import dev.openflight.companion.core.flight.FlightMeasurements
import dev.openflight.companion.core.flight.FlightPoint
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.PlannedShot
import dev.openflight.companion.core.flight.ShotDistanceEstimate
import dev.openflight.companion.core.flight.ShotDistanceEstimator
import dev.openflight.companion.core.flight.Vec3
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.TargetBearing
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeSettingsRepository(
    club: GolfClub = GolfClub.DRIVER,
) : SettingsRepository {
    override val transport = MutableStateFlow(TransportType.WIFI)
    override val host = MutableStateFlow(SettingsRepository.DEFAULT_HOST)
    override val selectedClub = MutableStateFlow(club)
    override val rangeCameraMode = MutableStateFlow(SettingsRepository.DEFAULT_RANGE_CAMERA_MODE)

    override suspend fun setRangeCameraMode(mode: RangeCameraMode) {
        rangeCameraMode.value = mode
    }

    override suspend fun setTransport(transport: TransportType) {
        this.transport.value = transport
    }

    override suspend fun setHost(host: String) {
        this.host.value = host
    }

    override suspend fun setSelectedClub(club: GolfClub) {
        selectedClub.value = club
    }
}

/** [latestShot] is driven by the test; club changes persist the Pi's answer, like the real repository. */
internal class FakeShotRepository(
    private val settings: FakeSettingsRepository,
    currentShot: ShotEvent? = null,
) : ShotRepository {
    override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    override val history = MutableStateFlow(listOfNotNull(currentShot))
    override val latestShot = MutableStateFlow(currentShot)
    override val activeClub = MutableStateFlow<GolfClub?>(null)
    override val supportsControls = MutableStateFlow(true)

    /** Replaced by tests to suspend or fail. */
    var setClubResponse: suspend (GolfClub) -> ClubSelection = { ClubSelection(status = "ok", club = it) }

    fun emit(shot: ShotEvent) {
        history.value = listOf(shot) + history.value
        latestShot.value = shot
    }

    override fun start() = Unit

    override fun stop() = Unit

    override fun retry() = Unit

    override fun disconnect() = Unit

    override suspend fun setClub(club: GolfClub): ClubSelection {
        val selection = setClubResponse(club)
        settings.setSelectedClub(selection.club)
        return selection
    }

    override suspend fun currentClub(): ClubSelection = ClubSelection(status = "ok", club = settings.selectedClub.value)

    override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult =
        error("not used by the range")
}

private var nextShotNumber = 0

/**
 * `makeDrivingRangeShot` from ios/OpenFlightTests/DrivingRangeTestFixtures.swift. The Swift
 * default event id is a fresh `UUID()`; here each call gets a fresh, deterministic id.
 */
internal fun makeDrivingRangeShot(
    eventId: String = "B0D91F0A-7950-4D7E-9DD5-" + (nextShotNumber++).toString().padStart(12, '0'),
    club: String = "driver",
    ballSpeedMph: Double = 151.4,
    carryYards: Double = 264.0,
    launchAngle: Double? = 12.6,
    horizontalLaunch: Double? = -1.3,
    spinRpm: Double? = 2_380.0,
    spinAxis: Double? = -3.4,
): ShotEvent =
    ShotEvent(
        schemaVersion = 1,
        eventId = eventId,
        timestamp = "2026-08-06T01:00:00",
        club = club,
        ballSpeedMph = ballSpeedMph,
        clubSpeedMph = 103.2,
        smashFactor = 1.47,
        estimatedCarryYards = carryYards,
        launchAngleVertical = launchAngle,
        launchAngleHorizontal = horizontalLaunch,
        spinRpm = spinRpm,
        clubPathDeg = 2.1,
        spinAxisDeg = spinAxis,
    )

/** `makeTestTrajectory(for:)` from DrivingRangeTestFixtures.swift. */
internal fun makeTestTrajectory(input: FlightInput): FlightTrajectory =
    FlightTrajectory(
        eventId = input.eventId,
        points =
            listOf(
                FlightPoint(time = 0.0, positionMeters = Vec3.ZERO, velocityMetersPerSecond = Vec3(0.0, 10.0, 40.0)),
                FlightPoint(
                    time = 1.0,
                    positionMeters = Vec3(0.0, 0.0, input.targetCarryMeters),
                    velocityMetersPerSecond = Vec3(0.0, -10.0, 30.0),
                ),
            ),
        provenance = input.provenance,
    )

/**
 * Plan F2b: the view model's flight plan with the real distance estimate and [simulation]'s flight
 * (by default [makeTestTrajectory]'s one-second arc) landing at the estimate's carry.
 */
internal fun testFlightPlan(
    measurements: FlightMeasurements,
    conditions: Conditions,
    bearing: TargetBearing?,
    simulation: (FlightInput) -> FlightTrajectory = ::makeTestTrajectory,
): PlannedShot? {
    val estimate = ShotDistanceEstimator().estimate(measurements, conditions, bearing) ?: return null
    val input = FlightInputResolver().resolve(measurements).copy(targetCarryMeters = estimate.carryYards * 0.9144)
    return PlannedShot(estimate, simulation(input))
}

/**
 * A flight plan with no physics at all: the shot's own table carry as the estimate (ISA, no roll)
 * and [makeTestTrajectory]'s arc. For tests about capping, ordering and state, not flight; the real
 * [ShotDistanceEstimator] runs two full simulations per shot, which is too slow for hundreds of shots
 * in a debug Kotlin/Native test binary on a CI runner (Turbine's 3 s wall-clock wait).
 */
internal fun cheapFlightPlan(
    measurements: FlightMeasurements,
    @Suppress("UNUSED_PARAMETER") conditions: Conditions,
    @Suppress("UNUSED_PARAMETER") bearing: TargetBearing?,
): PlannedShot {
    val carry = measurements.carryYards
    val input = FlightInputResolver().resolve(measurements).copy(targetCarryMeters = carry * 0.9144)
    val estimate =
        ShotDistanceEstimate(
            carryYards = carry,
            rollYards = 0.0,
            totalYards = carry,
            lateralDriftYards = 0.0,
            anchorCarryYards = carry,
            anchor = CarryAnchor.TABLE,
            isAdjusted = false,
            windApplied = false,
            windNeedsTargetBearing = false,
            airDensity = 1.225,
            flightProvenance = input.provenance,
        )
    return PlannedShot(estimate, makeTestTrajectory(input))
}
