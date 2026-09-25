// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.AppLifecycle
import dev.openflight.companion.core.model.ConnectionErrorKind
import dev.openflight.companion.core.model.ConnectionProblem
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PowerState
import dev.openflight.companion.core.model.pi.PowerStatus
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The device cards (plan R8f), with the payloads and labels of the Expo app's `device.tsx` and
 * `__tests__/useDeviceStore.test.ts`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicePanelsTest {
    /** `makePowerStatus()` in `useDeviceStore.test.ts`. */
    private val onBattery =
        PowerStatus(
            available = true,
            provider = "geekworm",
            state = PowerState.ON_BATTERY,
            batteryPercent = 78.0,
            batteryVoltageV = 3.91,
            externalPower = false,
            updatedAt = "2026-09-22T05:30:00Z",
        )

    /** `makeTriggerStatus()` in `useDeviceStore.test.ts`. */
    private val rollingBuffer =
        TriggerStatus(
            mode = "rolling-buffer",
            triggerType = "audio",
            radarConnected = true,
            radarPort = "/dev/ttyUSB0",
            triggersTotal = 12,
            triggersAccepted = 9,
            triggersRejected = 3,
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun everyPowerStateHasTheExpoLabel() {
        assertThat(PowerState.entries.map(DevicePanels::powerLabel)).containsExactly(
            "Plugged in",
            "On battery",
            "Battery low",
            "Battery critical",
            "No battery",
            "Unknown",
        )
    }

    @Test
    fun aBatteryShowsARoundedChargeAndTwoDecimalVolts() {
        val card = DevicePanels.power(onBattery.copy(batteryPercent = 77.5, batteryVoltageV = 3.906))

        assertThat(card?.stateLabel).isEqualTo("On battery")
        assertThat(card?.rows).isEqualTo(
            listOf(
                DeviceRow("Charge", "78%"),
                DeviceRow("Voltage", "3.91 V"),
                DeviceRow("Provider", "geekworm"),
            ),
        )
        assertThat(card?.warning).isEqualTo(false)
    }

    @Test
    fun aPiWithNoBatteryReportsItsAbsenceWithoutZeroRows() {
        // A mains-powered Pi reports available:false; 0% there would look like a dying Pi.
        val card =
            DevicePanels.power(
                onBattery.copy(
                    available = false,
                    state = PowerState.UNAVAILABLE,
                    batteryPercent = null,
                    batteryVoltageV = null,
                ),
            )

        assertThat(card?.stateLabel).isEqualTo("No battery")
        assertThat(card?.rows).isEqualTo(listOf(DeviceRow("Provider", "geekworm")))
    }

    @Test
    fun aMissingReadingIsAnEmDashAndAProviderErrorGetsARow() {
        val card =
            DevicePanels.power(
                onBattery.copy(state = PowerState.CRITICAL, batteryVoltageV = null, error = "I2C read failed"),
            )

        assertThat(card?.rows).isEqualTo(
            listOf(
                DeviceRow("Charge", "78%"),
                DeviceRow("Voltage", "—"),
                DeviceRow("Provider", "geekworm"),
                DeviceRow("Error", "I2C read failed"),
            ),
        )
        assertThat(card?.warning).isEqualTo(true)
    }

    @Test
    fun noPowerCardUntilTheFirstPowerStatus() {
        assertThat(DevicePanels.power(null)).isNull()
    }

    @Test
    fun voltsRoundLikeToFixed() {
        assertThat(DevicePanels.twoDecimals(3.91)).isEqualTo("3.91")
        assertThat(DevicePanels.twoDecimals(4.0)).isEqualTo("4.00")
        assertThat(DevicePanels.twoDecimals(12.345)).isEqualTo("12.35")
        assertThat(DevicePanels.twoDecimals(0.004)).isEqualTo("0.00")
    }

    @Test
    fun theTriggerCardShowsEveryRowWithLabels() {
        val card = DevicePanels.trigger(rollingBuffer, PiFeatureAvailability.Available)

        assertThat(card).isEqualTo(
            TriggerCard.Loaded(
                listOf(
                    DeviceRow("Mode", "Rolling buffer"),
                    DeviceRow("Radar", "Connected"),
                    DeviceRow("Port", "/dev/ttyUSB0"),
                    DeviceRow("Trigger", "audio"),
                    DeviceRow("Triggers seen", "12"),
                    DeviceRow("Accepted", "9"),
                    DeviceRow("Rejected", "3"),
                ),
            ),
        )
    }

    @Test
    fun mockModeReadsSimulatedAndMissingValuesReadAsDashes() {
        val mock =
            DevicePanels.trigger(
                TriggerStatus(mode = "mock", radarConnected = false),
                PiFeatureAvailability.Available,
            ) as TriggerCard.Loaded
        val swing =
            DevicePanels.trigger(TriggerStatus(mode = "swing-speed"), PiFeatureAvailability.Available)
                as TriggerCard.Loaded

        assertThat(mock.rows.take(4)).containsExactly(
            DeviceRow("Mode", "Mock mode"),
            DeviceRow("Radar", "Simulated"),
            DeviceRow("Port", "—"),
            DeviceRow("Trigger", "—"),
        )
        assertThat(swing.rows.take(2)).containsExactly(
            DeviceRow("Mode", "Swing speed"),
            DeviceRow("Radar", "Not connected"),
        )
    }

    @Test
    fun theTriggerCardWaitsWhileConnectedAndExplainsOtherwise() {
        assertThat(DevicePanels.trigger(null, PiFeatureAvailability.Available)).isEqualTo(TriggerCard.Waiting)
        assertThat(DevicePanels.trigger(null, PiFeatureAvailability.Unavailable("Requires Wi-Fi")))
            .isEqualTo(TriggerCard.Unavailable("Requires Wi-Fi"))
    }

    @Test
    fun theViewModelHidesDeviceCardsUntilTheyLoad() =
        runTest {
            val piSession = FakePiSessionRepository()
            val viewModel =
                SettingsViewModel(FakeShotRepository(), FakeSettingsRepository(), piSession, AppLifecycle())

            viewModel.uiState.test {
                val empty = awaitItem()
                assertThat(empty.power).isNull()
                assertThat(empty.trigger).isEqualTo(TriggerCard.Waiting)
                assertThat(empty.debug.loaded).isFalse()

                piSession.powerStatus.value = onBattery
                piSession.triggerStatus.value = rollingBuffer
                piSession.debugState.value = DebugState(enabled = false, loaded = true)
                var state = awaitItem()
                while (!state.debug.loaded) state = awaitItem()

                assertThat(state.power?.stateLabel).isEqualTo("On battery")
                assertThat(state.trigger is TriggerCard.Loaded).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun theViewModelNamesARejectedAddress() =
        runTest {
            val piSession = FakePiSessionRepository(PiLinkState.Rejected("Public addresses need HTTPS"))
            val shots =
                FakeShotRepository().apply {
                    connectionState.value =
                        ConnectionState.Error("Public addresses need HTTPS", ConnectionErrorKind.ENDPOINT_REJECTED)
                }
            val viewModel = SettingsViewModel(shots, FakeSettingsRepository(), piSession, AppLifecycle())

            viewModel.uiState.test {
                var state = awaitItem()
                while (state.connectionProblem == null) state = awaitItem()

                assertThat(state.connectionProblem?.kind).isEqualTo(ConnectionProblem.Kind.ADDRESS_REJECTED)
                assertThat(state.connectionProblem?.detail).isEqualTo("Public addresses need HTTPS")
                cancelAndIgnoreRemainingEvents()
            }
        }
}
