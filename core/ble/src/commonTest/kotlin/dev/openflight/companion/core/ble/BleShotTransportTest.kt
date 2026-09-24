// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import app.cash.turbine.test
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SHOT_CHARACTERISTIC_UUID
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.protocol.ControlCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Step 5 task 5: every branch of the BLE state machine, driven by fakes under virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleShotTransportTest {
    private val central = FakeBleCentral()
    private var nextRequest = 0

    private fun TestScope.transport(
        permissions: BlePermissionChecker = BlePermissionChecker { true },
        initialControlSequence: Int = 0,
    ) = BleShotTransport(
        central,
        permissions,
        backgroundScope,
        BleTransportConfig(initialControlSequence = initialControlSequence, requestIds = { "req-${++nextRequest}" }),
    )

    /** Starts the transport and completes a connection to [link]. */
    private fun TestScope.connect(
        transport: BleShotTransport,
        link: FakePeripheralLink = FakePeripheralLink(),
    ): FakePeripheralLink {
        central.advertise(link)
        transport.start()
        runCurrent()
        return link
    }

    // region Adapter and permissions

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

    // endregion

    // region Connection lifecycle

    @Test
    fun happyPathWalksScanningConnectingDiscoveringConnected() =
        runTest {
            val transport = transport()
            val link = FakePeripheralLink()
            link.connectGate = CompletableDeferred()
            link.subscribeGate = CompletableDeferred()

            transport.start()
            runCurrent()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Scanning)
            assertThat(central.activeScans).isEqualTo(1)

            central.advertise(link)
            runCurrent()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Connecting)
            // The reference stops scanning on the first discovery.
            assertThat(central.activeScans).isEqualTo(0)

            link.connectGate!!.complete(Unit)
            runCurrent()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Discovering)

            link.subscribeGate!!.complete(Unit)
            runCurrent()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(transport.supportsControls.value).isTrue()
        }

    @Test
    fun goldenFramesSplitAcrossNotificationsPublishOneShot() =
        runTest {
            val transport = transport()
            val link = connect(transport)

            transport.shots.test {
                link.notifyShot(GOLDEN_FRAME_HEX.map { it.hexToBytes() })
                runCurrent()

                val shot = awaitItem()
                assertThat(shot.eventId).isEqualTo("B0D91F0A-7950-4D7E-9DD5-AF9777C190E1")
                assertThat(shot.ballSpeedMph).isEqualTo(151.4)
                assertThat(shot.spinRpm).isEqualTo(2380.0)
                expectNoEvents()
            }
            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
        }

    @Test
    fun shotDecodeErrorSetsErrorState() =
        runTest {
            val transport = transport()
            val link = connect(transport)

            link.notifyShot(makeBleFrames("""{"schema_version":2}""", sequence = 1))
            runCurrent()

            assertThat(transport.state.value).isInstanceOf<ConnectionState.Error>()
        }

    @Test
    fun peripheralWithoutControlCharacteristicConnectsWithoutControls() =
        runTest {
            val transport = transport()
            connect(transport, FakePeripheralLink(characteristics = setOf(SHOT_CHARACTERISTIC_UUID)))

            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(transport.supportsControls.value).isFalse()
            assertFailure { transport.setClub(GolfClub.IRON_7) }.isInstanceOf<BleControlException.Unsupported>()
        }

    @Test
    fun missingServiceOrShotCharacteristicIsAnError() =
        runTest {
            val transport = transport()
            connect(transport, FakePeripheralLink(characteristics = null))
            assertThat(transport.state.value).isEqualTo(ConnectionState.Error("OpenFlight BLE service was not found"))

            val second = transport()
            connect(second, FakePeripheralLink(characteristics = emptySet()))
            assertThat(second.state.value)
                .isEqualTo(ConnectionState.Error("OpenFlight shot notifications were not found"))
        }

    @Test
    fun connectFailureReportsErrorAndRetriesAfterOneSecond() =
        runTest {
            val transport = transport()
            val failing = FakePeripheralLink().apply { connectError = IllegalStateException("GATT 133") }

            connect(transport, failing)
            assertThat(transport.state.value).isEqualTo(ConnectionState.Error("GATT 133"))
            assertThat(failing.releaseCount).isEqualTo(1)

            advanceTimeBy(999.milliseconds)
            runCurrent()
            assertThat(central.scannedServices).hasSize(1)

            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertThat(central.scannedServices).hasSize(2)
            assertThat(transport.state.value).isEqualTo(ConnectionState.Scanning)
        }

    @Test
    fun disconnectRescansAfterOneSecondAndClearsConnectionState() =
        runTest {
            val transport = transport()
            val link = connect(transport)
            link.notifyControl(makeBleFrames("""{"schema_version":1,"type":"club_changed","club":"pw"}""", 1))
            runCurrent()
            assertThat(transport.activeClub.value).isEqualTo(GolfClub.PITCHING_WEDGE)

            link.dropConnection()
            runCurrent()

            assertThat(transport.state.value).isEqualTo(ConnectionState.Scanning)
            assertThat(transport.activeClub.value).isNull()
            assertThat(transport.supportsControls.value).isFalse()
            assertThat(link.releaseCount).isEqualTo(1)

            advanceTimeBy(999.milliseconds)
            runCurrent()
            assertThat(central.scannedServices).hasSize(1)
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertThat(central.scannedServices).hasSize(2)

            val next = FakePeripheralLink()
            central.advertise(next)
            runCurrent()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
        }

    @Test
    fun shotDecoderIsNotResetAcrossReconnect() =
        runTest {
            val transport = transport()
            val first = connect(transport)

            transport.shots.test {
                first.notifyShot(makeBleFrames(shotJson("11111111-1111-1111-1111-111111111111"), sequence = 1))
                runCurrent()
                assertThat(awaitItem().eventId).isEqualTo("11111111-1111-1111-1111-111111111111")

                // Leave a half-received message behind: the shot reassembler must not carry it over.
                first.notifyShot(makeBleFrames(shotJson("22222222-2222-2222-2222-222222222222"), sequence = 2).take(1))
                runCurrent()
                first.dropConnection()
                val second = FakePeripheralLink()
                central.advertise(second)
                advanceTimeBy(1.seconds)
                runCurrent()
                assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)

                // The Pi replays its latest shot on subscribe; the kept decoder suppresses it.
                second.notifyShot(makeBleFrames(shotJson("11111111-1111-1111-1111-111111111111"), sequence = 0))
                runCurrent()
                expectNoEvents()

                second.notifyShot(makeBleFrames(shotJson("33333333-3333-3333-3333-333333333333"), sequence = 1))
                runCurrent()
                assertThat(awaitItem().eventId).isEqualTo("33333333-3333-3333-3333-333333333333")
            }
        }

    @Test
    fun explicitDisconnectGoesIdleAndDoesNotReconnect() =
        runTest {
            val transport = transport()
            val link = connect(transport)

            transport.disconnect()
            runCurrent()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Idle)
            assertThat(link.releaseCount).isEqualTo(1)

            advanceTimeBy(10.seconds)
            assertThat(central.scannedServices).hasSize(1)
            assertThat(transport.state.value).isEqualTo(ConnectionState.Idle)
        }

    @Test
    fun explicitDisconnectWhileScanningStopsTheScan() =
        runTest {
            val transport = transport()
            transport.start()
            runCurrent()
            assertThat(central.activeScans).isEqualTo(1)

            transport.disconnect()
            runCurrent()

            assertThat(central.activeScans).isEqualTo(0)
            assertThat(transport.state.value).isEqualTo(ConnectionState.Idle)
        }

    // endregion

    // region Control channel

    @Test
    fun controlRequestBeforeConnectingIsUnavailable() =
        runTest {
            val transport = transport()

            assertFailure { transport.setClub(GolfClub.DRIVER) }.isInstanceOf<BleControlException.Unavailable>()
        }

    @Test
    fun setClubWritesFramesOneAtATimeAndReturnsTheServerClub() =
        runTest {
            val transport = transport()
            val link = connect(transport)
            val permits = Channel<Unit>(Channel.UNLIMITED)
            link.writePermits = permits

            val result = async { transport.setClub(GolfClub.IRON_7) }
            runCurrent()
            val expectedFrames = ControlCodec.encodeSetClub(GolfClub.IRON_7, "req-1").size.let { (it + 14) / 15 }
            assertThat(link.writes).hasSize(0)

            // Each frame is written only after the previous write's callback.
            permits.trySend(Unit)
            runCurrent()
            assertThat(link.writes).hasSize(1)
            repeat(expectedFrames - 1) { permits.trySend(Unit) }
            runCurrent()
            assertThat(link.writes).hasSize(expectedFrames)

            val command = decodeWrittenCommands(link.writes).single()
            assertThat(command.requestId).isEqualTo("req-1")
            assertThat((command.getValue("type") as JsonPrimitive).content).isEqualTo("set_club")
            assertThat(link.writes.map { it.frameSequence() }.toSet()).isEqualTo(setOf(0))

            link.notifyControl(makeBleFrames(clubResponse("req-1", "7-iron"), sequence = 40))
            runCurrent()

            assertThat(result.await()).isEqualTo(ClubSelection(status = "ok", club = GolfClub.IRON_7))
            assertThat(transport.activeClub.value).isEqualTo(GolfClub.IRON_7)
        }

    @Test
    fun interleavedClubChangedAndUnmatchedResponseDoNotCompleteTheRequest() =
        runTest {
            val transport = transport()
            val link = connect(transport)

            val result = async { transport.setClub(GolfClub.IRON_7) }
            runCurrent()

            link.notifyControl(makeBleFrames("""{"schema_version":1,"type":"club_changed","club":"5-wood"}""", 1))
            runCurrent()
            assertThat(transport.activeClub.value).isEqualTo(GolfClub.WOOD_5)
            assertThat(result.isActive).isTrue()

            link.notifyControl(makeBleFrames(clubResponse("someone-else", "sw"), sequence = 2))
            runCurrent()
            assertThat(result.isActive).isTrue()

            link.notifyControl(makeBleFrames(clubResponse("req-1", "7-iron"), sequence = 3))
            runCurrent()
            assertThat(result.await().club).isEqualTo(GolfClub.IRON_7)
            assertThat(transport.activeClub.value).isEqualTo(GolfClub.IRON_7)
        }

    @Test
    fun invalidClubChangedEventSetsErrorState() =
        runTest {
            val transport = transport()
            val link = connect(transport)

            link.notifyControl(makeBleFrames("""{"schema_version":1,"type":"club_changed","club":"putter"}""", 1))
            runCurrent()

            assertThat(transport.state.value).isInstanceOf<ConnectionState.Error>()
        }

    @Test
    fun requestTimesOutAfterTenSecondsAndFreesTheChannel() =
        runTest {
            val transport = transport()
            val link = connect(transport)

            val result = async { runCatching { transport.currentClub() } }
            advanceTimeBy(9_999.milliseconds)
            runCurrent()
            assertThat(result.isActive).isTrue()

            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertThat(result.await().exceptionOrNull()).isNotNull().isInstanceOf<BleControlException.TimedOut>()

            val retry = async { transport.currentClub() }
            runCurrent()
            link.notifyControl(makeBleFrames(clubResponse("req-2", "driver"), sequence = 5))
            runCurrent()
            assertThat(retry.await().club).isEqualTo(GolfClub.DRIVER)
        }

    @Test
    fun secondRequestWhileOneIsInFlightFailsBusy() =
        runTest {
            val transport = transport()
            connect(transport)

            val first = async { runCatching { transport.setClub(GolfClub.DRIVER) } }
            runCurrent()

            assertFailure { transport.setClub(GolfClub.SAND_WEDGE) }.isInstanceOf<BleControlException.Busy>()
            assertThat(first.isActive).isTrue()
        }

    @Test
    fun disconnectMidRequestFailsItWithDisconnected() =
        runTest {
            val transport = transport()
            val link = connect(transport)

            val result = async { runCatching { transport.setClub(GolfClub.DRIVER) } }
            runCurrent()
            link.dropConnection()
            runCurrent()

            assertThat(result.await().exceptionOrNull()).isNotNull().isInstanceOf<BleControlException.Disconnected>()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Scanning)
        }

    @Test
    fun rejectedResponseSurfacesTheServerMessage() =
        runTest {
            val transport = transport()
            val link = connect(transport)

            val result = async { runCatching { transport.setClub(GolfClub.DRIVER) } }
            runCurrent()
            link.notifyControl(
                makeBleFrames("""{"error":"Club not allowed","ok":false,"request_id":"req-1","schema_version":1}""", 9),
            )
            runCurrent()

            val error = result.await().exceptionOrNull()
            assertThat(error).isNotNull().isInstanceOf<BleControlException.Rejected>()
            assertThat(error!!).hasMessage("Club not allowed")
        }

    @Test
    fun malformedEnvelopeFailsThePendingRequestAsInvalid() =
        runTest {
            val transport = transport()
            val link = connect(transport)

            val result = async { runCatching { transport.setClub(GolfClub.DRIVER) } }
            runCurrent()
            link.notifyControl(makeBleFrames("""{"ok":true,"request_id":"req-1","result":{}}""", 9))
            runCurrent()

            assertThat(result.await().exceptionOrNull()).isNotNull().isInstanceOf<BleControlException.InvalidResponse>()
        }

    @Test
    fun writeFailureFailsThePendingRequest() =
        runTest {
            val transport = transport()
            val link = connect(transport)
            link.writeError = IllegalStateException("write rejected")

            val error = runCatching { transport.setClub(GolfClub.DRIVER) }.exceptionOrNull()

            assertThat(error).isNotNull().isInstanceOf<BleControlException.Failed>()
            assertThat(error!!).hasMessage("write rejected")
        }

    @Test
    fun controlSubscriptionFailureDisablesControls() =
        runTest {
            val transport = transport()
            val link = FakePeripheralLink().apply { controlSubscribeError = IllegalStateException("CCCD failed") }

            connect(transport, link)

            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(transport.supportsControls.value).isFalse()
            assertFailure { transport.currentClub() }.isInstanceOf<BleControlException.Unsupported>()
        }

    @Test
    fun controlSequenceIsAU16ThatWraps() =
        runTest {
            val transport = transport(initialControlSequence = 0xFFFF)
            val link = connect(transport)

            val first = async { transport.currentClub() }
            runCurrent()
            link.notifyControl(makeBleFrames(clubResponse("req-1", "driver"), sequence = 1))
            runCurrent()
            first.await()
            val firstFrames = link.writes.size

            val second = async { transport.currentClub() }
            runCurrent()
            link.notifyControl(makeBleFrames(clubResponse("req-2", "driver"), sequence = 2))
            runCurrent()
            second.await()

            assertThat(
                link.writes
                    .take(firstFrames)
                    .map { it.frameSequence() }
                    .toSet(),
            ).isEqualTo(setOf(0xFFFF))
            assertThat(
                link.writes
                    .drop(firstFrames)
                    .map { it.frameSequence() }
                    .toSet(),
            ).isEqualTo(setOf(0))
        }

    @Test
    fun submitCalibrationDecodesTheCalibrationResult() =
        runTest {
            val transport = transport()
            val link = connect(transport)
            val measurement =
                PhoneOrientationMeasurement(
                    mountTiltDeg = 8.5,
                    rollDeg = 0.4,
                    gravityXG = 0.0,
                    gravityYG = -0.99,
                    gravityZG = -0.15,
                    tiltStddevDeg = 0.1,
                    rollStddevDeg = 0.1,
                    sampleCount = 120,
                    measuredAt = "2026-09-24T10:00:00Z",
                    deviceModel = "Pixel",
                )

            val result = async { transport.submitCalibration(measurement) }
            runCurrent()
            assertThat((decodeWrittenCommands(link.writes).single().getValue("type") as JsonPrimitive).content)
                .isEqualTo("iwr6843_orientation_calibration")
            val response =
                """{"ok":true,"request_id":"req-1","result":{"azimuth_offset_deg":0.0,""" +
                    """"configured_iwr_tilt_deg":8.5,"measured_mount_tilt_deg":8.5,"persistent":true,""" +
                    """"roll_deg":0.4,"status":"ok"},"schema_version":1}"""
            link.notifyControl(makeBleFrames(response, sequence = 7))
            runCurrent()

            assertThat(result.await().configuredIwrTiltDeg).isEqualTo(8.5)
        }

    // endregion
}
