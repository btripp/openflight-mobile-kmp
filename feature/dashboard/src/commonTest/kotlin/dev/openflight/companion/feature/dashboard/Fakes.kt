// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeSettingsRepository(
    transport: TransportType = TransportType.WIFI,
    host: String = SettingsRepository.DEFAULT_HOST,
    club: GolfClub = GolfClub.DRIVER,
) : SettingsRepository {
    override val transport = MutableStateFlow(transport)
    override val host = MutableStateFlow(host)
    override val selectedClub = MutableStateFlow(club)
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

/** Mirrors the real repository's club rule: persist only the Pi's confirmed answer. */
internal class FakeShotRepository(
    private val settings: FakeSettingsRepository,
) : ShotRepository {
    override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val history = MutableStateFlow(emptyList<ShotEvent>())
    override val latestShot = MutableStateFlow<ShotEvent?>(null)
    override val activeClub = MutableStateFlow<GolfClub?>(null)
    override val supportsControls = MutableStateFlow(false)

    var retryCount = 0
        private set
    val setClubCalls = mutableListOf<GolfClub>()

    /** Replaced by tests to suspend or fail. */
    var setClubResponse: suspend (GolfClub) -> ClubSelection = { ClubSelection(status = "ok", club = it) }

    override fun start() = Unit

    override fun stop() = Unit

    override fun retry() {
        retryCount++
    }

    override fun disconnect() = Unit

    override suspend fun setClub(club: GolfClub): ClubSelection {
        setClubCalls += club
        val selection = setClubResponse(club)
        settings.setSelectedClub(selection.club)
        return selection
    }

    override suspend fun currentClub(): ClubSelection = ClubSelection(status = "ok", club = settings.selectedClub.value)

    override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult =
        error("not used by the dashboard")
}

internal fun shot(
    id: Int,
    club: String = "driver",
    ballSpeedMph: Double = 151.4,
): ShotEvent =
    ShotEvent(
        schemaVersion = 1,
        eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C19%03d".format3(id),
        timestamp = "2026-07-29T19:42:10",
        club = club,
        ballSpeedMph = ballSpeedMph,
        estimatedCarryYards = 264.0,
    )

private fun String.format3(id: Int): String = replace("%03d", id.toString().padStart(3, '0'))
