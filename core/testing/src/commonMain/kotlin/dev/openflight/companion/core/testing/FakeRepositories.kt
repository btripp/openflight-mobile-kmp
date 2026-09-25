// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.testing

import dev.openflight.companion.core.data.CalloutTrigger
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.CalloutField
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import kotlinx.coroutines.flow.MutableStateFlow

/** A [SettingsRepository] over plain state flows. */
class FakeSettingsRepository(
    transport: TransportType = TransportType.WIFI,
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

    // Plan F4: audio call-outs, added at the end to keep this file's diff mergeable (§4a A7).

    override val calloutsEnabled = MutableStateFlow(SettingsRepository.DEFAULT_CALLOUTS_ENABLED)
    override val calloutVoiceId = MutableStateFlow<String?>(null)
    override val calloutRate = MutableStateFlow(SettingsRepository.DEFAULT_CALLOUT_RATE)
    override val calloutFields = MutableStateFlow(SettingsRepository.DEFAULT_CALLOUT_FIELDS)
    override val calloutTrigger = MutableStateFlow(SettingsRepository.DEFAULT_CALLOUT_TRIGGER)

    override suspend fun setCalloutsEnabled(enabled: Boolean) {
        calloutsEnabled.value = enabled
    }

    override suspend fun setCalloutVoiceId(voiceId: String?) {
        calloutVoiceId.value = voiceId
    }

    override suspend fun setCalloutRate(rate: Float) {
        calloutRate.value = rate
    }

    override suspend fun setCalloutFields(fields: List<CalloutField>) {
        calloutFields.value = fields
    }

    override suspend fun setCalloutTrigger(trigger: CalloutTrigger) {
        calloutTrigger.value = trigger
    }
}

/**
 * A [ShotRepository] over plain state flows. Delete and clear record their calls and change
 * [history] locally; the Pi-side routing is `DefaultShotRepository`'s and is tested in `core:data`.
 */
@Suppress("TooManyFunctions") // Mirrors the ShotRepository surface.
class FakeShotRepository : ShotRepository {
    override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val history = MutableStateFlow(emptyList<ShotEvent>())
    override val latestShot = MutableStateFlow<ShotEvent?>(null)
    override val activeClub = MutableStateFlow<GolfClub?>(null)
    override val supportsControls = MutableStateFlow(false)

    val deleteShotCalls = mutableListOf<String>()
    val deleteShotByTimestampCalls = mutableListOf<String>()
    var clearHistoryCalls = 0
        private set
    var shutdownCalls = 0
        private set

    override fun start() = Unit

    override fun stop() = Unit

    override fun retry() = Unit

    override fun disconnect() = Unit

    override suspend fun setClub(club: GolfClub): ClubSelection = ClubSelection(status = "ok", club = club)

    override suspend fun currentClub(): ClubSelection = ClubSelection(status = "ok", club = GolfClub.DRIVER)

    override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult =
        error("not used by these tests")

    override fun deleteShot(eventId: String) {
        deleteShotCalls += eventId
        setHistory(history.value.filterNot { it.eventId == eventId })
    }

    override fun deleteShotByTimestamp(timestamp: String) {
        deleteShotByTimestampCalls += timestamp
        setHistory(history.value.filterNot { it.timestamp == timestamp })
    }

    override fun clearHistory() {
        clearHistoryCalls++
        setHistory(emptyList())
    }

    override suspend fun shutdownPi() {
        shutdownCalls++
    }

    /** Every [shutdownPi] target, in order. */
    val shutdownTargets = mutableListOf<String>()

    /** Answers [shutdownPi] with a target: returns (the Pi's 200), throws, or suspends. */
    var shutdownResponse: suspend (String) -> Unit = {}

    override suspend fun shutdownPi(target: String) {
        shutdownTargets += target
        shutdownResponse(target)
    }

    /** Sets [history] (newest first) and [latestShot] together. */
    fun setHistory(newestFirst: List<ShotEvent>) {
        history.value = newestFirst
        latestShot.value = newestFirst.firstOrNull()
    }
}
