// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import app.cash.turbine.test
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.ble.BleControlException
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.EnrichmentProgress
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PowerState
import dev.openflight.companion.core.model.pi.PowerStatus
import dev.openflight.companion.core.model.pi.Profile
import dev.openflight.companion.core.model.pi.ShotProcessingState
import dev.openflight.companion.core.protocol.SchemaV2Commands
import dev.openflight.companion.core.protocol.SchemaV2Event
import dev.openflight.companion.core.protocol.ShotTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Plan R8e: schema v2 events from BLE (and SSE `?schema=2`) reach the same [ShotRepository] and
 * [PiSessionRepository] flows as the Socket.IO path, and BLE stays read-and-select.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchemaV2DataTest {
    /** A BLE transport that negotiated (or not) schema v2; commands are recorded. */
    private class FakeV2Transport(
        v2: Boolean = true,
    ) : ShotTransport,
        SchemaV2Commands {
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override val shots = MutableSharedFlow<ShotEvent>(extraBufferCapacity = 64)
        override val activeClub = MutableStateFlow<GolfClub?>(null)
        override val supportsControls = MutableStateFlow(false)
        override val schemaEvents = MutableSharedFlow<SchemaV2Event>(extraBufferCapacity = 64)
        override val schemaV2Active = MutableStateFlow(v2)

        val commands = mutableListOf<String>()
        var powerFailure: Exception? = BleControlException.Rejected("Battery monitoring is not enabled")
        var busyAnswers = 0

        override fun start() = Unit

        override fun retry() = Unit

        override fun disconnect() {
            state.value = ConnectionState.Idle
        }

        override suspend fun setClub(club: GolfClub): ClubSelection {
            commands += "set_club"
            return ClubSelection("applied", club)
        }

        override suspend fun currentClub(): ClubSelection {
            commands += "get_club"
            return ClubSelection("current", GolfClub.IRON_7)
        }

        override suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult =
            error("not scripted")

        override suspend fun requestProfiles() {
            commands += "get_profiles"
        }

        override suspend fun requestPowerStatus(): PowerStatus {
            commands += "get_power_status"
            powerFailure?.let { throw it }
            return PowerStatus(available = true, state = PowerState.ON_BATTERY)
        }

        override suspend fun setActiveProfile(profileId: String) {
            if (busyAnswers-- > 0) throw BleControlException.Busy()
            commands += "set_active_profile:$profileId"
        }

        fun connect() {
            supportsControls.value = true
            state.value = ConnectionState.Connected
        }
    }

    private class Harness(
        scope: CoroutineScope,
        val ble: FakeV2Transport,
        transport: TransportType = TransportType.BLUETOOTH,
    ) {
        val settings = FakeSettingsRepository(transport = transport, host = "pi.local")
        val wifi = FakeV2Transport(v2 = false)
        val logs = mutableListOf<String>()
        val piSession =
            DefaultPiSessionRepository(
                settings = settings,
                socketFactory = { socketHost, _ -> FakePiSocket(socketHost) },
                cameraSource = FakePiCameraSource(),
                scope = scope,
                bluetooth = ble,
            )
        val repository =
            DefaultShotRepository(
                settings = settings,
                bluetoothTransport = ble,
                wifiTransportFactory = { wifi },
                scope = scope,
                piControl = fakePiControlClient(),
                piSession = piSession,
                log = { logs += it },
            )
    }

    private fun runV2Test(
        ble: FakeV2Transport = FakeV2Transport(),
        transport: TransportType = TransportType.BLUETOOTH,
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness(backgroundScope, ble, transport)
        harness.repository.start()
        body(harness)
    }

    // region ShotRepository

    @Test
    fun aFinalShotReplacesItsProvisionalInTheHistory() =
        runV2Test { h ->
            h.ble.shots.emit(v2Shot(final = false, ballSpeedMph = 100.0))
            h.ble.shots.emit(v2Shot(final = true, ballSpeedMph = 106.1))

            assertThat(
                h.repository.history.value
                    .map { it.ballSpeedMph },
            ).containsExactly(106.1)
            assertThat(
                h.repository.latestShot.value
                    ?.final,
            ).isEqualTo(true)
        }

    @Test
    fun shotDeletedRemovesTheShotWithThatTimestamp() =
        runV2Test { h ->
            h.ble.shots.emit(shot(1))
            h.ble.shots.emit(v2Shot(final = true, ballSpeedMph = 106.1))

            h.ble.schemaEvents.emit(SchemaV2Event.ShotDeleted(V2_TIMESTAMP))

            assertThat(
                h.repository.history.value
                    .map { it.eventId },
            ).containsExactly(shotId(1))
        }

    @Test
    fun sessionClearedRemovesOnlyThatProfilesShots() =
        runV2Test { h ->
            h.ble.shots.emit(v2Shot(final = true, ballSpeedMph = 106.1, profileId = "p1"))
            h.ble.shots.emit(
                v2Shot(final = true, ballSpeedMph = 90.0, profileId = "p2")
                    .copy(eventId = "15dd37ec-49ed-596b-b1a4-953d54e4f239", timestamp = "2026-09-25T14:04:00"),
            )

            h.ble.schemaEvents.emit(SchemaV2Event.SessionCleared("p1"))

            assertThat(
                h.repository.history.value
                    .map { it.profileId },
            ).containsExactly("p2")
        }

    @Test
    fun anSseShotDeletedAlsoRemovesTheShot() =
        runV2Test(transport = TransportType.WIFI) { h ->
            h.wifi.shots.emit(v2Shot(final = true, ballSpeedMph = 106.1))

            h.wifi.schemaEvents.emit(SchemaV2Event.ShotDeleted(V2_TIMESTAMP))

            assertThat(h.repository.history.value).containsExactly()
        }

    @Test
    fun aV2ConnectSyncsTheClubThenAsksForProfilesAndPowerAndLogsARefusal() =
        runV2Test { h ->
            h.ble.connect()

            assertThat(h.ble.commands).containsExactly("get_club", "get_profiles", "get_power_status")
            assertThat(h.logs).containsExactly("get_power_status on connect failed: Battery monitoring is not enabled")
            assertThat(h.repository.connectionState.value).isEqualTo(ConnectionState.Connected)
        }

    @Test
    fun aV1ConnectOnlySyncsTheClub() =
        runV2Test(ble = FakeV2Transport(v2 = false)) { h ->
            h.ble.connect()

            assertThat(h.ble.commands).containsExactly("get_club")
        }

    // endregion

    // region PiSessionRepository over Bluetooth

    @Test
    fun v2EventsFeedThePiSessionFlowsWhileTheLinkStaysWifiOnly() =
        runV2Test { h ->
            assertThat(h.piSession.linkState.value).isEqualTo(PiLinkState.WifiOnly)
            assertThat(h.piSession.bluetoothSchemaV2.value).isTrue()

            h.ble.schemaEvents.emit(SchemaV2Event.Profiles(listOf(Profile("p1", "Zoë"), Profile("p2", "Sam")), "p2"))
            h.ble.schemaEvents.emit(SchemaV2Event.ShotProcessing(ShotProcessingState.CALCULATING))
            h.ble.schemaEvents.emit(SchemaV2Event.Power(PowerStatus(available = true, state = PowerState.LOW)))
            h.ble.activeClub.value = GolfClub.IRON_7

            val profiles = h.piSession.profiles.value
            assertThat(profiles.loaded).isTrue()
            assertThat(profiles.activeProfile?.name).isEqualTo("Sam")
            assertThat(h.piSession.shotProcessing.value).isEqualTo(ShotProcessingState.CALCULATING)
            assertThat(
                h.piSession.powerStatus.value
                    ?.state,
            ).isEqualTo(PowerState.LOW)
            assertThat(h.piSession.club.value).isEqualTo("7-iron")
        }

    @Test
    fun aV2ShotEndsProcessingAndIndexesItsDetail() =
        runV2Test { h ->
            h.ble.schemaEvents.emit(SchemaV2Event.ShotProcessing(ShotProcessingState.CAPTURING))
            val shot = v2Shot(final = false, ballSpeedMph = 100.0, profileId = "p1")

            h.ble.shots.emit(shot)

            assertThat(h.piSession.shotProcessing.value).isNull()
            val detail = h.piSession.detailFor(shot)
            assertThat(detail).isNotNull()
            assertThat(detail?.profileName).isEqualTo("Zoë")
            assertThat(detail?.carryRange).isEqualTo(listOf(144.0, 160.0))
        }

    @Test
    fun selectingAProfileGoesOverBluetoothV2AndRetriesABusyLink() =
        runV2Test { h ->
            h.ble.busyAnswers = 2

            h.piSession.setActiveProfile("p2")

            assertThat(h.ble.commands).containsExactly("set_active_profile:p2")
        }

    @Test
    fun aLinkThatStaysBusyReportsBusy() =
        runV2Test { h ->
            h.ble.busyAnswers = Int.MAX_VALUE

            assertFailure { h.piSession.setActiveProfile("p2") }.isInstanceOf<BleControlException.Busy>()
        }

    @Test
    fun overBluetoothV1ProfileSelectionAndEveryEditAreWifiOnly() =
        runV2Test(ble = FakeV2Transport(v2 = false)) { h ->
            assertThat(h.piSession.bluetoothSchemaV2.value).isFalse()

            assertFailure { h.piSession.setActiveProfile("p2") }.isInstanceOf<WifiOnlyFeatureException>()
            assertFailure { h.piSession.addProfile("Ana") }.isInstanceOf<WifiOnlyFeatureException>()
            assertFailure { h.piSession.clearSession("p1") }.isInstanceOf<WifiOnlyFeatureException>()
            assertFailure { h.piSession.deleteShot(V2_TIMESTAMP) }.isInstanceOf<WifiOnlyFeatureException>()
        }

    @Test
    fun overBluetoothV2DestructiveCommandsStayWifiOnly() =
        runV2Test { h ->
            assertFailure { h.piSession.clearSession("p1") }.isInstanceOf<WifiOnlyFeatureException>()
            assertFailure { h.piSession.deleteShot(V2_TIMESTAMP) }.isInstanceOf<WifiOnlyFeatureException>()
            assertFailure { h.piSession.renameProfile("p1", "Ana") }.isInstanceOf<WifiOnlyFeatureException>()
            assertFailure { h.piSession.removeProfile("p1") }.isInstanceOf<WifiOnlyFeatureException>()
        }

    @Test
    fun switchingToWifiForgetsTheBluetoothPiAndItsSchemaV2Flag() =
        runV2Test { h ->
            h.ble.schemaEvents.emit(SchemaV2Event.Profiles(listOf(Profile("p1", "Zoë")), "p1"))
            h.piSession.bluetoothSchemaV2.test {
                assertThat(awaitItem()).isTrue()

                h.settings.setTransport(TransportType.WIFI)

                assertThat(awaitItem()).isFalse()
            }
            assertThat(h.piSession.profiles.value.loaded).isFalse()
            // Events from the Bluetooth transport no longer reach the Pi session.
            h.ble.schemaEvents.emit(SchemaV2Event.ShotProcessing(ShotProcessingState.FAILED))
            advanceTimeBy(1.milliseconds)
            assertThat(h.piSession.shotProcessing.value).isNull()
        }

    // endregion

    private companion object {
        const val V2_TIMESTAMP = "2026-09-25T14:03:07.412345"

        fun v2Shot(
            final: Boolean,
            ballSpeedMph: Double,
            profileId: String = "p1",
        ): ShotEvent =
            ShotEvent(
                schemaVersion = 2,
                eventId = "05dd37ec-49ed-596b-b1a4-953d54e4f239",
                timestamp = V2_TIMESTAMP,
                club = "7-iron",
                ballSpeedMph = ballSpeedMph,
                estimatedCarryYards = 152.0,
                type = "shot",
                final = final,
                shotNumber = 7,
                profileId = profileId,
                profileName = "Zoë",
                carryRange = listOf(144.0, 160.0),
                enrichment = EnrichmentProgress(if (final) "complete" else "pending"),
            )
    }
}
