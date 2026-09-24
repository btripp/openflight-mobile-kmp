// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.GravitySample
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.sensors.GravitySensor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow

internal class FakeSettingsRepository(
    transport: TransportType = TransportType.WIFI,
    host: String = SettingsRepository.DEFAULT_HOST,
) : SettingsRepository {
    override val transport = MutableStateFlow(transport)
    override val host = MutableStateFlow(host)
    override val selectedClub = MutableStateFlow(GolfClub.DRIVER)
    val hostWrites = mutableListOf<String>()

    override suspend fun setTransport(transport: TransportType) {
        this.transport.value = transport
    }

    override suspend fun setHost(host: String) {
        hostWrites += host
        this.host.value = host
    }

    override suspend fun setSelectedClub(club: GolfClub) {
        selectedClub.value = club
    }
}

internal class FakeShotRepository : ShotRepository {
    override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val history = MutableStateFlow(emptyList<ShotEvent>())
    override val latestShot = MutableStateFlow<ShotEvent?>(null)
    override val activeClub = MutableStateFlow<GolfClub?>(null)
    override val supportsControls = MutableStateFlow(false)

    var retryCount = 0
        private set
    val submittedMeasurements = mutableListOf<PhoneOrientationMeasurement>()

    /** Replaced by tests to change the submission's outcome. */
    var submitCalibrationResponse: suspend (PhoneOrientationMeasurement) -> CalibrationResult =
        { DEFAULT_CALIBRATION_RESULT }

    override fun start() = Unit

    override fun stop() = Unit

    override fun retry() {
        retryCount++
    }

    override fun disconnect() = Unit

    override suspend fun setClub(club: GolfClub): ClubSelection = error("not used by calibration")

    override suspend fun currentClub(): ClubSelection = error("not used by calibration")

    override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult {
        submittedMeasurements += measurement
        return submitCalibrationResponse(measurement)
    }

    companion object {
        val DEFAULT_CALIBRATION_RESULT =
            CalibrationResult(
                status = "ok",
                persistent = true,
                measuredMountTiltDeg = 12.0,
                enclosurePitchDeg = 1.5,
                configuredIwrTiltDeg = 10.5,
                rollDeg = 0.2,
                azimuthOffsetDeg = 0.0,
            )
    }
}

/** A scripted [GravitySensor]: [flow] replays a fixed sequence of samples, then completes. */
internal class FakeGravitySensor(
    override val isAvailable: Boolean = true,
    private val flow: Flow<GravitySample> = emptyFlow(),
) : GravitySensor {
    override fun samples(hz: Int): Flow<GravitySample> = flow
}

/** A phone held upright in portrait, flat against the reference surface: tilt 0, roll 0. */
internal val level = GravitySample(x = 0.0, y = -1.0, z = 0.0)

/** ~5.7° of roll: well past the 3° `MAXIMUM_ROLL_DEG` threshold, tilt still 0. */
internal val highRoll = GravitySample(x = 0.1, y = -0.995, z = 0.0)

/** One side of an alternating pair whose roll oscillates ~1.7°, giving a roll std dev > 0.5°. */
internal val noisyRollA = level
internal val noisyRollB = GravitySample(x = 0.03, y = -0.9995, z = 0.0)

internal fun stableSamples(
    sample: GravitySample = level,
    count: Int = 120,
): Flow<GravitySample> = List(count) { sample }.asFlow()

internal fun alternatingSamples(
    a: GravitySample,
    b: GravitySample,
    count: Int = 120,
): Flow<GravitySample> = List(count) { if (it % 2 == 0) a else b }.asFlow()
