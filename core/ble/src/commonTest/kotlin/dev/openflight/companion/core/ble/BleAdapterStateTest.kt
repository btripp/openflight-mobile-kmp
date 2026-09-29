// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import dev.openflight.companion.core.model.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** Step 5 task 5: the adapter and permission branches of [BleShotTransport]'s state machine. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleAdapterStateTest {
    private val central = FakeBleCentral()

    private fun TestScope.transport(permissions: BlePermissionChecker = BlePermissionChecker { true }) =
        BleShotTransport(central, permissions, backgroundScope)

    /** Starts the transport and completes a connection (and the `hello` handshake) to a schema 2 Pi. */
    private fun TestScope.connect(transport: BleShotTransport): FakePeripheralLink {
        val link = ScriptedPi().link()
        central.advertise(link)
        transport.start()
        runCurrent()
        return link
    }

    @Test
    fun adapterStatesMapToTheReferenceMessages() =
        runTest {
            val expected =
                mapOf(
                    BleAdapterState.PoweredOff to ConnectionState.Unavailable("Bluetooth is turned off"),
                    BleAdapterState.Unauthorized to ConnectionState.Unavailable("Bluetooth permission is required"),
                    BleAdapterState.Unsupported to ConnectionState.Unavailable("Bluetooth LE is not supported"),
                    BleAdapterState.Resetting to ConnectionState.Unavailable("Bluetooth is resetting"),
                    BleAdapterState.Unavailable to ConnectionState.Unavailable("Bluetooth is unavailable"),
                    BleAdapterState.Unknown to ConnectionState.Idle,
                )
            val transport = transport()
            central.adapter.value = BleAdapterState.Resetting
            transport.start()

            for ((adapter, state) in expected) {
                central.adapter.value = adapter
                runCurrent()
                assertThat(transport.state.value).isEqualTo(state)
            }
            assertThat(central.scannedServices).hasSize(0)
        }

    @Test
    fun missingPermissionReportsUnavailableWithoutScanning() =
        runTest {
            var granted = false
            val transport = transport(permissions = { granted })

            transport.start()
            runCurrent()

            assertThat(transport.state.value).isEqualTo(ConnectionState.Unavailable("Bluetooth permission is required"))
            assertThat(central.scannedServices).hasSize(0)

            granted = true
            transport.retry()
            runCurrent()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Scanning)
        }

    @Test
    fun unknownThenPoweredOnStartsScanningLikeTheReference() =
        runTest {
            central.adapter.value = BleAdapterState.Unknown
            val transport = transport()

            transport.start()
            runCurrent()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Idle)

            central.adapter.value = BleAdapterState.PoweredOn
            runCurrent()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Scanning)
            assertThat(central.scannedServices).containsExactly(OpenFlightBleProfile.SERVICE_UUID)
        }

    @Test
    fun adapterTurningOffMidConnectionTearsDownAndReportsUnavailable() =
        runTest {
            val transport = transport()
            val link = connect(transport)
            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)

            central.adapter.value = BleAdapterState.PoweredOff
            runCurrent()

            assertThat(transport.state.value).isEqualTo(ConnectionState.Unavailable("Bluetooth is turned off"))
            assertThat(transport.supportsControls.value).isFalse()
            assertThat(link.releaseCount).isEqualTo(1)
            advanceTimeBy(5.seconds)
            assertThat(central.scannedServices).hasSize(1)
        }

    @Test
    fun scanRequirementFailureReportsUnavailable() =
        runTest {
            central.scanError = BleUnavailableException("Turn on Location to find OpenFlight over Bluetooth")
            val transport = transport()

            transport.start()
            runCurrent()

            assertThat(transport.state.value)
                .isEqualTo(ConnectionState.Unavailable("Turn on Location to find OpenFlight over Bluetooth"))
        }
}
