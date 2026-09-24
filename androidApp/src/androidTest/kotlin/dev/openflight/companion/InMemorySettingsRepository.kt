// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Settings kept in memory for the app flow tests, so no DataStore file outlives a test. They start
 * on Wi-Fi at the default host, which nothing answers on the emulator.
 */
internal class InMemorySettingsRepository : SettingsRepository {
    override val transport = MutableStateFlow(TransportType.WIFI)
    override val host = MutableStateFlow(SettingsRepository.DEFAULT_HOST)
    override val selectedClub = MutableStateFlow(GolfClub.DRIVER)
    override val units = MutableStateFlow(SettingsRepository.DEFAULT_UNITS)

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
