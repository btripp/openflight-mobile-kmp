// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * A transport-free [ShotRepository] for the `--ui-testing`/`--preview-shot` launch hooks: it
 * reports Connected, never starts a transport, and confirms every club change locally.
 *
 * @param showPreviewShot seeds the history with [PREVIEW_SHOT], like the reference's `.preview`.
 */
internal class PreviewShotRepository(
    private val settings: SettingsRepository,
    showPreviewShot: Boolean,
) : ShotRepository {
    private val shots = if (showPreviewShot) listOf(PREVIEW_SHOT) else emptyList()

    override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)
    override val history: StateFlow<List<ShotEvent>> = MutableStateFlow(shots)
    override val latestShot: StateFlow<ShotEvent?> = MutableStateFlow(shots.firstOrNull())
    override val activeClub: StateFlow<GolfClub?> = MutableStateFlow(null)
    override val supportsControls: StateFlow<Boolean> = MutableStateFlow(true)

    override fun start() = Unit

    override fun stop() = Unit

    override fun retry() = Unit

    override fun disconnect() = Unit

    override suspend fun setClub(club: GolfClub): ClubSelection {
        settings.setSelectedClub(club)
        return ClubSelection(status = "ok", club = club)
    }

    override suspend fun currentClub(): ClubSelection =
        ClubSelection(status = "ok", club = settings.selectedClub.first())

    override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult =
        throw UnsupportedOperationException("Calibration needs a real OpenFlight Pi.")

    /** Plan R8f: a preview Pi accepts the stop after a short, visible wait (the pending state). */
    override suspend fun shutdownPi(target: String) {
        delay(PREVIEW_SHUTDOWN_MILLIS)
    }

    companion object {
        private const val PREVIEW_SHUTDOWN_MILLIS = 1_500L

        /**
         * The reference's `ShotEvent.preview` (ShotEvent.swift:44-58), which carries the same numbers
         * as the `shot_v1.json` contract fixture; the fixture's event id keeps it stable.
         */
        val PREVIEW_SHOT: ShotEvent =
            ShotEvent(
                schemaVersion = 1,
                eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
                timestamp = "2026-07-29T19:42:10",
                club = "driver",
                ballSpeedMph = 151.4,
                clubSpeedMph = 103.2,
                smashFactor = 1.47,
                estimatedCarryYards = 264.0,
                launchAngleVertical = 12.6,
                launchAngleHorizontal = -1.3,
                spinRpm = 2380.0,
                clubPathDeg = 2.1,
                spinAxisDeg = -3.4,
            )

        /**
         * `--preview-live-shots` shot [number] (1-based): [PREVIEW_SHOT] with its own event id (a
         * UUID ending in [number], as `ShotEvent` requires) and a later timestamp, one second apart,
         * so each one is a new shot.
         */
        fun liveShot(number: Int): ShotEvent =
            PREVIEW_SHOT.copy(
                eventId = "00000000-0000-4000-8000-${number.toString().padStart(UUID_TAIL_DIGITS, '0')}",
                timestamp =
                    "2026-07-29T20:${twoDigits(number / SECONDS_PER_MINUTE % MINUTES_PER_HOUR)}:" +
                        twoDigits(number % SECONDS_PER_MINUTE),
            )

        /**
         * `--preview-pi-mock` simulated shot [number] (1-based, plan F8d-B): a new event id and
         * timestamp, like [liveShot], and numbers that differ from [PREVIEW_SHOT] and from the
         * previous simulated shot: the club, speeds, carry and direction cycle through
         * [SIMULATED_SHOTS], so each one flies a visibly different flight.
         */
        fun simulatedShot(number: Int): ShotEvent =
            SIMULATED_SHOTS[(number - 1).mod(SIMULATED_SHOTS.size)].copy(
                eventId = "00000000-0000-4000-9000-${number.toString().padStart(UUID_TAIL_DIGITS, '0')}",
                timestamp =
                    "2026-07-29T21:${twoDigits(number / SECONDS_PER_MINUTE % MINUTES_PER_HOUR)}:" +
                        twoDigits(number % SECONDS_PER_MINUTE),
            )

        @Suppress("MagicNumber") // Made-up shots, like PREVIEW_SHOT.
        private val SIMULATED_SHOTS: List<ShotEvent> by lazy {
            listOf(
                PREVIEW_SHOT.copy(
                    club = "7-iron",
                    ballSpeedMph = 118.2,
                    clubSpeedMph = 86.0,
                    smashFactor = 1.37,
                    estimatedCarryYards = 165.0,
                    launchAngleVertical = 17.8,
                    launchAngleHorizontal = 2.4,
                    spinRpm = 6_400.0,
                    clubPathDeg = 1.2,
                    spinAxisDeg = 4.1,
                ),
                PREVIEW_SHOT.copy(
                    club = "driver",
                    ballSpeedMph = 158.6,
                    clubSpeedMph = 107.5,
                    smashFactor = 1.48,
                    estimatedCarryYards = 281.0,
                    launchAngleVertical = 11.2,
                    launchAngleHorizontal = -3.0,
                    spinRpm = 2_150.0,
                    clubPathDeg = -2.4,
                    spinAxisDeg = -6.2,
                ),
                PREVIEW_SHOT.copy(
                    club = "pw",
                    ballSpeedMph = 98.4,
                    clubSpeedMph = 78.1,
                    smashFactor = 1.26,
                    estimatedCarryYards = 124.0,
                    launchAngleVertical = 24.5,
                    launchAngleHorizontal = 0.6,
                    spinRpm = 8_900.0,
                    clubPathDeg = 0.4,
                    spinAxisDeg = 1.0,
                ),
            )
        }

        private const val UUID_TAIL_DIGITS = 12
        private const val SECONDS_PER_MINUTE = 60
        private const val MINUTES_PER_HOUR = 60

        private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')
    }
}
