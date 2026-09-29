// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import app.cash.turbine.test
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.hasMessage
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.ble.OpenFlightBleProfile.CONTROL_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SHOT_CHARACTERISTIC_UUID
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionErrorKind
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.protocol.SchemaV2Codec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Step 5 task 5: every branch of the BLE state machine, driven by fakes under virtual time. BLE is
 * schema 2 only (plan R8e): every connection negotiates `hello` on the control characteristic
 * first, so the first command a test sends is `req-2` and its frames use the sequence after
 * `hello`'s.
 */
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

    /** A schema 2 Pi that answers only `hello`: each test answers its own commands by hand. */
    private fun handshakeOnlyLink(): FakePeripheralLink = ScriptedPi().apply { answersCommands = false }.link()

    /** Starts the transport and completes a connection (and the `hello` handshake) to [link]. */
    private fun TestScope.connect(
        transport: BleShotTransport,
        link: FakePeripheralLink = handshakeOnlyLink(),
    ): FakePeripheralLink {
        central.advertise(link)
        transport.start()
        runCurrent()
        return link
    }

    // region Connection lifecycle

    @Test
    fun happyPathWalksScanningConnectingDiscoveringConnected() =
        runTest {
            val transport = transport()
            val link = handshakeOnlyLink()
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
                link.notifyShot(BleGoldenFrames.frames("v2_shot_final"))
                runCurrent()

                val shot = awaitItem()
                assertThat(shot.schemaVersion).isEqualTo(2)
                assertThat(shot.final).isEqualTo(true)
                assertThat(shot.profileName).isEqualTo("Zoë")
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
    fun missingServiceOrCharacteristicsIsAnError() =
        runTest {
            val transport = transport()
            connect(transport, FakePeripheralLink(characteristics = null))
            assertThat(transport.state.value).isEqualTo(ConnectionState.Error("OpenFlight BLE service was not found"))

            for (characteristics in listOf(
                emptySet(),
                setOf(SHOT_CHARACTERISTIC_UUID),
                setOf(CONTROL_CHARACTERISTIC_UUID),
            )) {
                val next = transport()
                connect(next, FakePeripheralLink(characteristics = characteristics))
                assertThat(next.state.value).isEqualTo(NEEDS_SCHEMA_2)
                assertThat(next.supportsControls.value).isFalse()
            }
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
            link.notifyControl(makeBleFrames("""{"club":"pw","schema_version":2,"type":"club_changed"}""", 1))
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

            val next = handshakeOnlyLink()
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
                val second = handshakeOnlyLink()
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
            val helloFrames = link.writes.size
            val permits = Channel<Unit>(Channel.UNLIMITED)
            link.writePermits = permits

            val result = async { transport.setClub(GolfClub.IRON_7) }
            runCurrent()
            val expectedFrames = SchemaV2Codec.encodeSetClub(GolfClub.IRON_7, "req-2").size.let { (it + 14) / 15 }
            assertThat(link.writes).hasSize(helloFrames)

            // Each frame is written only after the previous write's callback.
            permits.trySend(Unit)
            runCurrent()
            assertThat(link.writes).hasSize(helloFrames + 1)
            repeat(expectedFrames - 1) { permits.trySend(Unit) }
            runCurrent()
            assertThat(link.writes).hasSize(helloFrames + expectedFrames)

            val setClubFrames = link.writes.drop(helloFrames)
            val command = decodeWrittenCommands(setClubFrames).single()
            assertThat(command.requestId).isEqualTo("req-2")
            assertThat((command.getValue("type") as JsonPrimitive).content).isEqualTo("set_club")
            assertThat(command.getValue("schema_version")).isEqualTo(JsonPrimitive(2))
            // hello used sequence 0.
            assertThat(setClubFrames.map { it.frameSequence() }.toSet()).isEqualTo(setOf(1))

            link.notifyControl(makeBleFrames(clubResponse("req-2", "7-iron"), sequence = 40))
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

            link.notifyControl(makeBleFrames("""{"club":"5-wood","schema_version":2,"type":"club_changed"}""", 1))
            runCurrent()
            assertThat(transport.activeClub.value).isEqualTo(GolfClub.WOOD_5)
            assertThat(result.isActive).isTrue()

            link.notifyControl(makeBleFrames(clubResponse("someone-else", "sw"), sequence = 2))
            runCurrent()
            assertThat(result.isActive).isTrue()

            link.notifyControl(makeBleFrames(clubResponse("req-2", "7-iron"), sequence = 3))
            runCurrent()
            assertThat(result.await().club).isEqualTo(GolfClub.IRON_7)
            assertThat(transport.activeClub.value).isEqualTo(GolfClub.IRON_7)
        }

    @Test
    fun invalidClubChangedEventSetsErrorState() =
        runTest {
            val transport = transport()
            val link = connect(transport)

            link.notifyControl(makeBleFrames("""{"club":"putter","schema_version":2,"type":"club_changed"}""", 1))
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
            link.notifyControl(makeBleFrames(clubResponse("req-3", "driver"), sequence = 5))
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
                makeBleFrames("""{"error":"Club not allowed","ok":false,"request_id":"req-2","schema_version":2}""", 9),
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
            link.notifyControl(makeBleFrames("""{"ok":true,"request_id":"req-2","result":{}}""", 9))
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
    fun controlSequenceIsAU16ThatWraps() =
        runTest {
            val transport = transport(initialControlSequence = 0xFFFF)
            val link = connect(transport)
            val helloFrames = link.writes.size

            val first = async { transport.currentClub() }
            runCurrent()
            link.notifyControl(makeBleFrames(clubResponse("req-2", "driver"), sequence = 1))
            runCurrent()
            first.await()
            val firstFrames = link.writes.size - helloFrames

            val second = async { transport.currentClub() }
            runCurrent()
            link.notifyControl(makeBleFrames(clubResponse("req-3", "driver"), sequence = 2))
            runCurrent()
            second.await()

            assertThat(
                link.writes
                    .take(helloFrames)
                    .map { it.frameSequence() }
                    .toSet(),
            ).isEqualTo(setOf(0xFFFF))
            assertThat(
                link.writes
                    .drop(helloFrames)
                    .take(firstFrames)
                    .map { it.frameSequence() }
                    .toSet(),
            ).isEqualTo(setOf(0))
            assertThat(
                link.writes
                    .drop(helloFrames + firstFrames)
                    .map { it.frameSequence() }
                    .toSet(),
            ).isEqualTo(setOf(1))
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
            val command = decodeWrittenCommands(link.writes).last()
            assertThat((command.getValue("type") as JsonPrimitive).content).isEqualTo("iwr6843_orientation_calibration")
            assertThat(command.getValue("schema_version")).isEqualTo(JsonPrimitive(2))
            assertThat(command.getValue("payload").jsonObject.getValue("mount_tilt_deg")).isEqualTo(JsonPrimitive(8.5))
            val response =
                """{"ok":true,"request_id":"req-2","result":{"azimuth_offset_deg":0.0,""" +
                    """"configured_iwr_tilt_deg":8.5,"measured_mount_tilt_deg":8.5,"persistent":true,""" +
                    """"roll_deg":0.4,"status":"ok"},"schema_version":2}"""
            link.notifyControl(makeBleFrames(response, sequence = 7))
            runCurrent()

            assertThat(result.await().configuredIwrTiltDeg).isEqualTo(8.5)
        }

    // endregion

    // region Schema 2 only: the Pi's contract (plan R8e)

    @Test
    fun aSchema2OnlyPiConnectsAndNegotiates() =
        runTest {
            val transport = transport()
            val link = connect(transport, ScriptedPi().link())

            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(transport.schemaV2Active.value).isTrue()
            assertThat(transport.supportsControls.value).isTrue()
            // Control is subscribed first (hello), shots only after the Pi answered with schema 2.
            assertThat(link.subscriptions).containsExactly(CONTROL_CHARACTERISTIC_UUID, SHOT_CHARACTERISTIC_UUID)
            val hello = link.commandsWrittenTo(CONTROL_CHARACTERISTIC_UUID).single().jsonObject
            assertThat(hello["type"]).isEqualTo(JsonPrimitive("hello"))
            assertThat(hello["schema_version"]).isEqualTo(JsonPrimitive(2))
            assertThat(hello["payload"]).isEqualTo(JsonObject(mapOf("client_schema_max" to JsonPrimitive(2))))
            assertThat(transport.piFeatures.value).isEqualTo(
                setOf("provisional_shots", "shot_processing", "profiles", "power_status", "shot_deleted", "club"),
            )
        }

    @Test
    fun aPiWithOnlyTheRemovedV1PairNeedsTheSchema2UpdateAndGetsNoFallback() =
        runTest {
            val transport = transport()
            val link = connect(transport, ScriptedPi(characteristics = ScriptedPi.REMOVED_V1_PAIR).link())

            assertThat(transport.state.value).isEqualTo(NEEDS_SCHEMA_2)
            assertThat(transport.state.value.description).contains("schema 2")
            assertThat(transport.state.value.description).contains("Network")
            assertThat(link.subscriptions).isEmpty()
            assertThat(link.writes).isEmpty()
            assertThat(transport.supportsControls.value).isFalse()
            assertFailure { transport.setClub(GolfClub.DRIVER) }.isInstanceOf<BleControlException.Unavailable>()

            // No reconnect loop: Retry can't fix an old Pi.
            advanceTimeBy(30.seconds)
            assertThat(central.scannedServices).hasSize(1)
            assertThat(transport.state.value).isEqualTo(NEEDS_SCHEMA_2)
        }

    @Test
    fun helloRefusedIsAnErrorAndUnsubscribesControl() =
        runTest {
            val pi = ScriptedPi().apply { hello = ScriptedPi.HelloAnswer.UNSUPPORTED }
            val transport = transport()
            val link = connect(transport, pi.link())

            assertThat(transport.state.value).isEqualTo(
                ConnectionState.Error(BleShotTransport.HANDSHAKE_REFUSED, ConnectionErrorKind.PI_UPDATE_REQUIRED),
            )
            assertThat(transport.schemaV2Active.value).isFalse()
            assertThat(transport.supportsControls.value).isFalse()
            assertThat(link.subscriptions).doesNotContain(SHOT_CHARACTERISTIC_UUID)
            assertThat(link.activeSubscriptions).isEmpty()
            assertThat(pi.commandTypes()).containsExactly("hello")
        }

    @Test
    fun shotsOnTheSchema2ShotCharacteristicAreDecoded() =
        runTest {
            val transport = transport()
            val link = connect(transport, ScriptedPi().link())

            transport.shots.test {
                link.notifyShot(BleGoldenFrames.frames("v2_shot_provisional"))
                runCurrent()
                assertThat(awaitItem().isProvisional).isTrue()
                link.notifyShot(BleGoldenFrames.frames("v2_shot_final"))
                runCurrent()
                assertThat(awaitItem().final).isEqualTo(true)
                expectNoEvents()
            }
        }

    @Test
    fun clubCommandsGoOverSchema2Control() =
        runTest {
            val pi = ScriptedPi()
            val transport = transport()
            val link = connect(transport, pi.link())

            val current = async { transport.currentClub() }
            runCurrent()
            assertThat(current.await().club).isEqualTo(GolfClub.DRIVER)

            val selection = async { transport.setClub(GolfClub.WOOD_3) }
            runCurrent()
            assertThat(selection.await().club).isEqualTo(GolfClub.WOOD_3)
            assertThat(transport.activeClub.value).isEqualTo(GolfClub.WOOD_3)

            // A club change on the Pi (kiosk or web UI) reaches the phone as a schema 2 event.
            pi.notifyEvent(link, """{"club":"pw","schema_version":2,"type":"club_changed"}""")
            runCurrent()
            assertThat(transport.activeClub.value).isEqualTo(GolfClub.PITCHING_WEDGE)

            val written = link.commandsWrittenTo(CONTROL_CHARACTERISTIC_UUID).map { it.jsonObject }
            assertThat(written.map { it["type"] })
                .containsExactly(JsonPrimitive("hello"), JsonPrimitive("get_club"), JsonPrimitive("set_club"))
            assertThat(written.all { it["schema_version"] == JsonPrimitive(2) }).isTrue()
            assertThat(link.writeTargets.toSet()).isEqualTo(setOf(CONTROL_CHARACTERISTIC_UUID))
        }

    // endregion

    private companion object {
        val NEEDS_SCHEMA_2 =
            ConnectionState.Error(BleShotTransport.PI_NEEDS_SCHEMA_2, ConnectionErrorKind.PI_UPDATE_REQUIRED)
    }
}
