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

    companion object {
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
    }
}
