// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeSettingsRepository(
    transport: TransportType = SettingsRepository.DEFAULT_TRANSPORT,
    host: String = SettingsRepository.DEFAULT_HOST,
    club: GolfClub = SettingsRepository.DEFAULT_CLUB,
    units: UnitSystem = SettingsRepository.DEFAULT_UNITS,
) : SettingsRepository {
    override val transport = MutableStateFlow(transport)
    override val host = MutableStateFlow(host)
    override val selectedClub = MutableStateFlow(club)
    override val units = MutableStateFlow(units)

    override suspend fun setTransport(transport: TransportType) {
        this.transport.value = transport
    }

    override suspend fun setHost(host: String) {
        this.host.value = host
    }

    override suspend fun setSelectedClub(club: GolfClub) {
        selectedClub.value = club
    }

    override suspend fun setUnits(units: UnitSystem) {
        this.units.value = units
    }
}

/** Tracks [deleteShotCalls]/[clearHistoryCalls] locally, mirroring the real `DefaultShotRepository`. */
internal class FakeShotRepository : ShotRepository {
    override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val history = MutableStateFlow(emptyList<ShotEvent>())
    override val latestShot = MutableStateFlow<ShotEvent?>(null)
    override val activeClub = MutableStateFlow<GolfClub?>(null)
    override val supportsControls = MutableStateFlow(false)

    val deleteShotCalls = mutableListOf<String>()
    var clearHistoryCalls = 0
        private set

    override fun start() = Unit

    override fun stop() = Unit

    override fun retry() = Unit

    override fun disconnect() = Unit

    override suspend fun setClub(club: GolfClub): ClubSelection = ClubSelection(status = "ok", club = club)

    override suspend fun currentClub(): ClubSelection = error("not used by the session screen")

    override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult =
        error("not used by the session screen")

    override fun deleteShot(eventId: String) {
        deleteShotCalls += eventId
        history.value = history.value.filterNot { it.eventId == eventId }
        latestShot.value = history.value.firstOrNull()
    }

    override fun clearHistory() {
        clearHistoryCalls++
        history.value = emptyList()
        latestShot.value = null
    }
}

/** A valid UUID event id that encodes [number], so assertions stay readable. */
internal fun shotId(number: Int): String = "00000000-0000-4000-8000-" + number.toString().padStart(12, '0')

internal fun shot(
    number: Int,
    club: String = "driver",
    ballSpeedMph: Double = 140.0,
): ShotEvent =
    ShotEvent(
        schemaVersion = 1,
        eventId = shotId(number),
        timestamp = "2026-08-05T23:54:00",
        club = club,
        ballSpeedMph = ballSpeedMph,
        estimatedCarryYards = 250.0,
    )
