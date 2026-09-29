// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import app.cash.turbine.test
import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import dev.openflight.companion.core.ble.OpenFlightBleProfile.CONTROL_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SHOT_CHARACTERISTIC_UUID
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.pi.PowerState
import dev.openflight.companion.core.model.pi.ShotProcessingState
import dev.openflight.companion.core.protocol.SchemaV2Codec
import dev.openflight.companion.core.protocol.SchemaV2Event
import kotlinx.coroutines.CompletableDeferred
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
 * Plan R8e: negotiation details and the schema 2 link (events, profiles, power, reconnects),
 * against [ScriptedPi] (a fake Pi behind [BleCentral]) and the backend's golden frames. The Pi's
 * contract itself (schema 2 only, no v1 fallback) is pinned in [BleShotTransportTest].
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

    // region Negotiation details (the contract itself is in BleShotTransportTest)

    @Test
    fun aPiThatStillHasTheV1PairIsDrivenOnlyOverSchema2() =
        runTest {
            val transport = transport()
            val pi = ScriptedPi(characteristics = ScriptedPi.SCHEMA_2_PAIR + ScriptedPi.REMOVED_V1_PAIR)
            val link = connect(transport, pi.link())

            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(link.subscriptions).containsExactly(CONTROL_CHARACTERISTIC_UUID, SHOT_CHARACTERISTIC_UUID)
            assertThat(link.writeTargets.toSet()).isEqualTo(setOf(CONTROL_CHARACTERISTIC_UUID))
        }

    @Test
    fun helloTimeoutIsAnErrorAfterTenSeconds() =
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
            assertThat(transport.state.value).isEqualTo(ConnectionState.Error(BleShotTransport.HANDSHAKE_TIMED_OUT))
            assertThat(transport.schemaV2Active.value).isFalse()
            assertThat(link.activeSubscriptions).isEmpty()
        }

    @Test
    fun controlThatNeverSubscribesIsAnErrorAfterTenSeconds() =
        runTest {
            val link = ScriptedPi().link().apply { subscribeGate = CompletableDeferred() }
            val transport = transport()
            connect(transport, link)

            advanceTimeBy(10.seconds)
            runCurrent()

            assertThat(transport.state.value).isEqualTo(ConnectionState.Error(BleShotTransport.HANDSHAKE_TIMED_OUT))
            assertThat(link.writes).isEmpty()
        }

    @Test
    fun controlSubscriptionFailureIsAnError() =
        runTest {
            val link = ScriptedPi().link().apply { controlSubscribeError = IllegalStateException("CCCD failed") }
            val transport = transport()
            connect(transport, link)

            assertThat(transport.state.value)
                .isEqualTo(ConnectionState.Error(BleShotTransport.CONTROL_SUBSCRIBE_FAILED))
            assertThat(transport.supportsControls.value).isFalse()
            assertThat(link.subscriptions).isEmpty()
            assertThat(link.writes).isEmpty()
        }

    @Test
    fun retryAfterAHandshakeErrorNegotiatesAgain() =
        runTest {
            val pi = ScriptedPi().apply { hello = ScriptedPi.HelloAnswer.UNSUPPORTED }
            val transport = transport()
            connect(transport, pi.link())
            assertThat(transport.state.value).isInstanceOf<ConnectionState.Error>()

            pi.hello = ScriptedPi.HelloAnswer.V2
            central.advertise(pi.link())
            transport.retry()
            runCurrent()

            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(pi.commandTypes()).containsExactly("hello", "hello")
        }

    @Test
    fun aVersionOneClubChangedIsRejectedOnTheSchema2Link() =
        runTest {
            val transport = transport()
            val link = connect(transport, ScriptedPi().link())

            link.notifyControl(makeBleFrames("""{"club":"pw","schema_version":1,"type":"club_changed"}""", 1))
            runCurrent()

            assertThat(transport.activeClub.value).isNull()
            assertThat(transport.state.value).isInstanceOf<ConnectionState.Error>()
        }

    @Test
    fun optionalHelloFeaturesAreDetectedButNeverRequired() =
        runTest {
            val bare = ScriptedPi().apply { helloResult = """{"features":[],"schema_version":2}""" }
            val transport = transport()
            connect(transport, bare.link())
            assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(transport.piFeatures.value).isEmpty()

            val later =
                ScriptedPi().apply {
                    helloResult = """{"features":["club","shot_catch_up"],"schema_version":2}"""
                }
            val next = transport()
            connect(next, later.link())
            assertThat(next.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(next.piFeatures.value).contains(SchemaV2Codec.FEATURE_SHOT_CATCH_UP)
        }

    // endregion

    // region The schema 2 link

    @Test
    fun provisionalThenFinalGoldenShotsBothArriveWithOneEventId() =
        runTest {
            val transport = transport()
            val link = connect(transport, ScriptedPi().link())

            transport.shots.test {
                link.notifyShot(goldenFrames("v2_shot_provisional"))
                runCurrent()
                val provisional = awaitItem()
                link.notifyShot(goldenFrames("v2_shot_final"))
                runCurrent()
                val final = awaitItem()

                assertThat(provisional.isProvisional).isTrue()
                assertThat(final.eventId).isEqualTo(provisional.eventId)
                assertThat(final.final).isEqualTo(true)
                assertThat(final.profileName).isEqualTo("Zoë")

                // The Pi replays its latest v2 shot when the shot characteristic is resubscribed.
                link.notifyShot(goldenFrames("v2_shot_final"))
                runCurrent()
                expectNoEvents()
            }
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
                for (name in GOLDEN_EVENTS) link.notifyControl(goldenFrames(name))
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
    fun aDisconnectMidMessageDropsThePartialShotAndRenegotiates() =
        runTest {
            val pi = ScriptedPi()
            val transport = transport()
            val first = connect(transport, pi.link())

            transport.shots.test {
                // Half of the final shot, then the link drops.
                first.notifyShot(goldenFrames("v2_shot_final").take(HALF_A_SHOT))
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
                pi.notify(second, SHOT_CHARACTERISTIC_UUID, goldenPayload("v2_shot_final"))
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
