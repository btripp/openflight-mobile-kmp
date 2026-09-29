// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.protocol.OpenFlightJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

/**
 * One-to-one port of `ios/OpenFlightTests/BluetoothManagerTests.swift`. The Swift tests call
 * `receive(_:)`/`receiveControl(_:)` directly; this port calls their internal equivalents. The
 * reference feeds version-one payloads; BLE is schema 2 only now, so these feed the backend's
 * schema 2 contract fixture (`shot_v2.json`) and a schema 2 `club_changed` instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothManagerTest {
    private fun TestScope.transport(central: FakeBleCentral) =
        BleShotTransport(central, permissions = { true }, scope = backgroundScope)

    @Test
    fun poweredOnCentralStartsServiceFilteredScan() =
        runTest {
            val central = FakeBleCentral(BleAdapterState.PoweredOn)
            val manager = transport(central)

            manager.start()
            runCurrent()

            assertThat(manager.state.value).isEqualTo(ConnectionState.Scanning)
            assertThat(central.scannedServices).containsExactly(OpenFlightBleProfile.SERVICE_UUID)
        }

    @Test
    fun unavailableStatesAreActionable() =
        runTest {
            val central = FakeBleCentral(BleAdapterState.PoweredOff)
            val manager = transport(central)

            manager.start()
            runCurrent()

            assertThat(manager.state.value).isEqualTo(ConnectionState.Unavailable("Bluetooth is turned off"))
            assertThat(manager.state.value.canRetry).isTrue()
        }

    @Test
    fun receivePublishesCompletedSharedFixture() =
        runTest {
            val manager = transport(FakeBleCentral(BleAdapterState.PoweredOn))
            val payload = SHOT_V2_FIXTURE_JSON.encodeToByteArray()

            manager.shots.test {
                for (frame in makeBleFrames(payload, sequence = 10)) manager.receiveShotFrame(frame)

                val latestShot = awaitItem()
                assertThat(latestShot.ballSpeedMph).isEqualTo(106.1)
                assertThat(latestShot.eventId).isEqualTo("05dd37ec-49ed-596b-b1a4-953d54e4f239")
                // shotHistory.shots.count == 1
                expectNoEvents()
            }
        }

    @Test
    fun receiveIgnoresReplayWithSameEventId() =
        runTest {
            val manager = transport(FakeBleCentral(BleAdapterState.PoweredOn))
            val original = SHOT_V2_FIXTURE_JSON.encodeToByteArray()
            val replayObject = OpenFlightJson.parseToJsonElement(SHOT_V2_FIXTURE_JSON).jsonObject
            val changedReplay =
                JsonObject(replayObject + ("ball_speed_mph" to JsonPrimitive(199.0))).toString().encodeToByteArray()

            manager.shots.test {
                for (frame in makeBleFrames(original, sequence = 10)) manager.receiveShotFrame(frame)
                for (frame in makeBleFrames(changedReplay, sequence = 11)) manager.receiveShotFrame(frame)

                assertThat(awaitItem().ballSpeedMph).isEqualTo(106.1)
                expectNoEvents()
            }
        }

    @Test
    fun receiveControlPublishesUnsolicitedClubChange() =
        runTest {
            val manager = transport(FakeBleCentral(BleAdapterState.PoweredOn))
            val payload = """{"club":"5-wood","schema_version":2,"type":"club_changed"}"""

            for (frame in makeBleFrames(payload, sequence = 12)) manager.receiveControlFrame(frame)

            assertThat(manager.activeClub.value).isEqualTo(GolfClub.WOOD_5)
        }
}
