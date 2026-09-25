// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import app.cash.turbine.test
import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import dev.openflight.companion.core.ble.OpenFlightBleProfile.CONTROL_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.CONTROL_V2_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SHOT_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SHOT_V2_CHARACTERISTIC_UUID
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.pi.PowerState
import dev.openflight.companion.core.model.pi.ShotProcessingState
import dev.openflight.companion.core.protocol.SchemaV2Event
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
 * Plan R8e: schema v2 negotiation and the v2 link, against [ScriptedPi] (a fake Pi behind
 * [BleCentral]) and the backend's golden frames. The version-one fallback must stay exactly the
 * §0.3 behaviour that [BleShotTransportTest] and [BluetoothManagerTest] pin down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleSchemaV2Test {
    private val central = FakeBleCentral()
    private var nextRequest = 0

    private fun TestScope.transport() =
        BleShotTransport(
            central,
            { true },
            backgroundScope,
            BleTransportConfig(requestIds = { "req-${++nextRequest}" }),
        )

    private fun TestScope.connect(
        transport: BleShotTransport,
        link: FakePeripheralLink,
    ): FakePeripheralLink {
        central.advertise(link)
        transport.start()
        runCurrent()
        return link
    }

    // region Negotiation and fallback matrix

    @Test
    fun aV1OnlyPiConnectsOnTheV1PairWithoutHello() =
        runTest {
            val pi = ScriptedPi(schemaV2 = false)
            val transport = transport()
            val link = connect(transport, pi.link())

            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(transport.supportsControls.value).isTrue()
            assertThat(transport.schemaV2Active.value).isFalse()
            assertThat(
                link.subscriptions,
            ).containsExactlyInAnyOrder(SHOT_CHARACTERISTIC_UUID, CONTROL_CHARACTERISTIC_UUID)
            assertThat(link.writes).isEmpty()
        }

    @Test
    fun aV2PiNegotiatesWithHelloAndSubscribesOnlyTheV2Pair() =
        runTest {
            val pi = ScriptedPi()
            val transport = transport()
            val link = connect(transport, pi.link())

            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(transport.schemaV2Active.value).isTrue()
            assertThat(transport.supportsControls.value).isTrue()
            // The v2 control is subscribed first (hello), the v2 shot only after it succeeded.
            assertThat(link.subscriptions).containsExactly(CONTROL_V2_CHARACTERISTIC_UUID, SHOT_V2_CHARACTERISTIC_UUID)
            assertThat(link.subscriptions).doesNotContain(SHOT_CHARACTERISTIC_UUID)
            val hello = link.commandsWrittenTo(CONTROL_V2_CHARACTERISTIC_UUID).single().jsonObject
            assertThat(hello["type"]).isEqualTo(JsonPrimitive("hello"))
            assertThat(hello["schema_version"]).isEqualTo(JsonPrimitive(2))
            assertThat(hello["payload"]).isEqualTo(JsonObject(mapOf("client_schema_max" to JsonPrimitive(2))))
        }

    @Test
    fun helloRefusedFallsBackToV1AndUnsubscribesTheV2Control() =
        runTest {
            val pi = ScriptedPi().apply { hello = ScriptedPi.HelloAnswer.UNSUPPORTED }
            val transport = transport()
            val link = connect(transport, pi.link())

            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(transport.schemaV2Active.value).isFalse()
            assertThat(link.activeSubscriptions)
                .containsExactlyInAnyOrder(SHOT_CHARACTERISTIC_UUID, CONTROL_CHARACTERISTIC_UUID)

            // Controls now use the v1 characteristic and envelope, exactly as before R8e.
            val selection = async { transport.setClub(GolfClub.IRON_7) }
            runCurrent()
            assertThat(selection.await().club).isEqualTo(GolfClub.IRON_7)
            val setClub = link.commandsWrittenTo(CONTROL_CHARACTERISTIC_UUID).single().jsonObject
            assertThat(setClub["schema_version"]).isEqualTo(JsonPrimitive(1))
        }

    @Test
    fun helloTimeoutFallsBackToV1AfterTenSeconds() =
        runTest {
            val pi = ScriptedPi().apply { hello = ScriptedPi.HelloAnswer.SILENT }
            val transport = transport()
            val link = connect(transport, pi.link())
            assertThat(transport.state.value).isEqualTo(ConnectionState.Discovering)

            advanceTimeBy(9_999.milliseconds)
            runCurrent()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Discovering)

            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(transport.schemaV2Active.value).isFalse()
            assertThat(link.activeSubscriptions)
                .containsExactlyInAnyOrder(SHOT_CHARACTERISTIC_UUID, CONTROL_CHARACTERISTIC_UUID)
        }

    @Test
    fun aV2ControlThatCannotBeSubscribedFallsBackToV1() =
        runTest {
            val pi = ScriptedPi()
            val link = pi.link().apply { subscribeGate = kotlinx.coroutines.CompletableDeferred() }
            val transport = transport()
            connect(transport, link)

            // Notifications never get enabled: the subscription wait gives up after the timeout.
            advanceTimeBy(10.seconds)
            link.subscribeGate!!.complete(Unit)
            runCurrent()

            assertThat(transport.schemaV2Active.value).isFalse()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(link.writes).isEmpty()
        }

    // endregion

    // region The v2 link

    @Test
    fun provisionalThenFinalGoldenShotsBothArriveWithOneEventId() =
        runTest {
            val transport = transport()
            val link = connect(transport, ScriptedPi().link())

            transport.shots.test {
                link.notifyShotV2(goldenFrames("v2_shot_provisional"))
                runCurrent()
                val provisional = awaitItem()
                link.notifyShotV2(goldenFrames("v2_shot_final"))
                runCurrent()
                val final = awaitItem()

                assertThat(provisional.isProvisional).isTrue()
                assertThat(final.eventId).isEqualTo(provisional.eventId)
                assertThat(final.final).isEqualTo(true)
                assertThat(final.profileName).isEqualTo("Zoë")

                // The Pi replays its latest v2 shot when the shot characteristic is resubscribed.
                link.notifyShotV2(goldenFrames("v2_shot_final"))
                runCurrent()
                expectNoEvents()
            }
        }

    @Test
    fun clubCommandsUseTheV2CharacteristicAndEnvelope() =
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

            val written = link.commandsWrittenTo(CONTROL_V2_CHARACTERISTIC_UUID).map { it.jsonObject }
            assertThat(written.map { it["type"] })
                .containsExactly(JsonPrimitive("hello"), JsonPrimitive("get_club"), JsonPrimitive("set_club"))
            assertThat(written.all { it["schema_version"] == JsonPrimitive(2) }).isTrue()
            assertThat(link.commandsWrittenTo(CONTROL_CHARACTERISTIC_UUID)).isEmpty()
        }

    @Test
    fun profilesArriveAsAnEventAndSelectingAnUnknownProfileIsRejected() =
        runTest {
            val pi = ScriptedPi()
            val transport = transport()
            connect(transport, pi.link())

            transport.schemaEvents.test {
                val request = async { transport.requestProfiles() }
                runCurrent()
                request.await()
                assertThat(awaitItem()).isInstanceOf<SchemaV2Event.Profiles>().all {
                    prop("names") { event -> event.profiles.map { it.name } }.containsExactly("Zoë ⛳", "Sam")
                    prop("active") { it.activeProfileId }.isEqualTo("p1")
                }

                val select = async { transport.setActiveProfile("p2") }
                runCurrent()
                select.await()
                assertThat(awaitItem())
                    .isInstanceOf<SchemaV2Event.Profiles>()
                    .prop("active") { it.activeProfileId }
                    .isEqualTo("p2")

                val unknown = async { runCatching { transport.setActiveProfile("nope") } }
                runCurrent()
                assertThat(unknown.await().exceptionOrNull())
                    .isNotNull()
                    .isInstanceOf<BleControlException.Rejected>()
                    .hasMessage("Unknown profile")
                // The Pi broadcasts the unchanged roster even when it refuses.
                assertThat(awaitItem())
                    .isInstanceOf<SchemaV2Event.Profiles>()
                    .prop("active") { it.activeProfileId }
                    .isEqualTo("p2")
            }
        }

    @Test
    fun powerStatusIsReturnedAndPublishedOrRefusedWithoutABattery() =
        runTest {
            val pi = ScriptedPi()
            val transport = transport()
            connect(transport, pi.link())

            val missing = async { runCatching { transport.requestPowerStatus() } }
            runCurrent()
            assertThat(missing.await().exceptionOrNull())
                .isNotNull()
                .isInstanceOf<BleControlException.Rejected>()
                .hasMessage("Battery monitoring is not enabled")

            pi.powerJson = """{"available":true,"battery_percent":76.5,"provider":"geekworm","state":"on_battery"}"""
            transport.schemaEvents.test {
                val reading = async { transport.requestPowerStatus() }
                runCurrent()
                assertThat(reading.await().state).isEqualTo(PowerState.ON_BATTERY)
                assertThat(awaitItem())
                    .isInstanceOf<SchemaV2Event.Power>()
                    .prop("percent") { it.status.batteryPercent }
                    .isEqualTo(76.5)
            }
        }

    @Test
    fun goldenV2EventsAreRoutedToSchemaEventsAndClub() =
        runTest {
            val transport = transport()
            val link = connect(transport, ScriptedPi().link())

            transport.schemaEvents.test {
                for (name in GOLDEN_EVENTS) link.notifyControlV2(goldenFrames(name))
                runCurrent()

                assertThat(awaitItem()).isEqualTo(SchemaV2Event.ShotProcessing(ShotProcessingState.CALCULATING))
                assertThat(awaitItem()).isInstanceOf<SchemaV2Event.Profiles>()
                assertThat(awaitItem()).isInstanceOf<SchemaV2Event.Power>()
                assertThat(awaitItem()).isEqualTo(SchemaV2Event.SessionCleared("0f8e4b2a9c7d4e1f8a6b3c5d7e9f1a2b"))
                assertThat(awaitItem()).isEqualTo(SchemaV2Event.ShotDeleted("2026-09-25T14:03:07.412345"))
                expectNoEvents()
            }
            assertThat(transport.activeClub.value).isEqualTo(GolfClub.IRON_7)
            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
        }

    @Test
    fun aMalformedOrUnknownV2EventIsDroppedWithoutAnError() =
        runTest {
            val pi = ScriptedPi()
            val transport = transport()
            val link = connect(transport, pi.link())

            transport.schemaEvents.test {
                pi.notifyEvent(link, """{"schema_version":2,"type":"shot_processing","state":"exploding"}""")
                pi.notifyEvent(link, """{"schema_version":2,"type":"from_the_future"}""")
                pi.notifyEvent(link, """{"schema_version":2,"type":"shot_deleted","timestamp":"t1"}""")
                runCurrent()

                assertThat(awaitItem()).isEqualTo(SchemaV2Event.ShotDeleted("t1"))
            }
            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
        }

    @Test
    fun v2CommandsKeepOneInFlightAndTheTenSecondTimeout() =
        runTest {
            val pi = ScriptedPi()
            val transport = transport()
            val link = connect(transport, pi.link())
            link.onWrite = null // The Pi stops answering.

            val first = async { runCatching { transport.requestProfiles() } }
            runCurrent()
            assertFailure { transport.setActiveProfile("p2") }.isInstanceOf<BleControlException.Busy>()

            advanceTimeBy(10.seconds)
            runCurrent()
            assertThat(first.await().exceptionOrNull()).isNotNull().isInstanceOf<BleControlException.TimedOut>()
        }

    @Test
    fun v2OnlyCommandsAreUnsupportedOnAV1Link() =
        runTest {
            val transport = transport()
            connect(transport, ScriptedPi(schemaV2 = false).link())

            assertFailure { transport.requestProfiles() }.isInstanceOf<BleControlException.Unsupported>()
            assertFailure { transport.setActiveProfile("p1") }.isInstanceOf<BleControlException.Unsupported>()
        }

    @Test
    fun aDisconnectMidMessageDropsThePartialShotAndRenegotiates() =
        runTest {
            val pi = ScriptedPi()
            val transport = transport()
            val first = connect(transport, pi.link())

            transport.shots.test {
                // Half of the final shot, then the link drops.
                first.notifyShotV2(goldenFrames("v2_shot_final").take(HALF_A_SHOT))
                runCurrent()
                first.dropConnection()
                runCurrent()
                assertThat(transport.schemaV2Active.value).isFalse()
                expectNoEvents()

                val second = pi.link()
                central.advertise(second)
                advanceTimeBy(1.seconds)
                runCurrent()
                assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
                assertThat(transport.schemaV2Active.value).isTrue()

                // The Pi replays the whole message on the new subscription (a new sequence).
                pi.notify(second, SHOT_V2_CHARACTERISTIC_UUID, goldenPayload("v2_shot_final"))
                runCurrent()
                assertThat(awaitItem().final).isEqualTo(true)
                expectNoEvents()
            }
            assertThat(pi.commandTypes()).containsExactly("hello", "hello")
        }

    @Test
    fun aDisconnectDuringHelloFailsItAndTheNextConnectionNegotiatesAgain() =
        runTest {
            val pi = ScriptedPi().apply { hello = ScriptedPi.HelloAnswer.SILENT }
            val transport = transport()
            val first = connect(transport, pi.link())
            assertThat(transport.state.value).isEqualTo(ConnectionState.Discovering)

            first.dropConnection()
            runCurrent()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Scanning)

            pi.hello = ScriptedPi.HelloAnswer.V2
            central.advertise(pi.link())
            advanceTimeBy(1.seconds)
            runCurrent()
            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(transport.schemaV2Active.value).isTrue()
        }

    // endregion

    private fun goldenFrames(name: String): List<ByteArray> = BleGoldenFrames.frames(name)

    private fun goldenPayload(name: String): String = BleGoldenFrames.payload(name)

    private companion object {
        const val HALF_A_SHOT = 19

        /** In the order they are notified; `v2_event_club_changed` only updates [BleShotTransport.activeClub]. */
        val GOLDEN_EVENTS =
            listOf(
                "v2_event_shot_processing",
                "v2_event_profiles",
                "v2_event_club_changed",
                "v2_event_power_status",
                "v2_event_session_cleared",
                "v2_event_shot_deleted",
            )
    }
}
