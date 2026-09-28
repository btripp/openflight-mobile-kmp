// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.DefaultFinalShotStream
import dev.openflight.companion.core.data.DemoModeRepository
import dev.openflight.companion.core.data.DemoSeedSession
import dev.openflight.companion.core.data.DemoShotHistoryRepository
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.ShotProcessingState
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Plan F14: Demo mode switches the app's Pi-facing repositories at runtime, its pretend Pi reports
 * shots through the live path (provisional, then final), and its data stays apart from the real
 * history.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoModeTest {
    /** Lets the pretend Pi (on the background scope) finish whatever it started. */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_MILLIS)
        runCurrent()
    }

    /** The real shot repository, counting its lifecycle calls. */
    private class RecordingShotRepository(
        private val fake: FakeShotRepository = FakeShotRepository(),
    ) : ShotRepository by fake {
        var starts = 0
        var stops = 0

        val fakeHistory get() = fake

        override fun start() {
            starts++
        }

        override fun stop() {
            stops++
        }
    }

    /** Demo mode's history: records what's filed and what's seeded. */
    private class RecordingDemoHistory(
        val inner: FakeShotHistoryRepository = FakeShotHistoryRepository(),
    ) : DemoShotHistoryRepository,
        ShotHistoryRepository by inner {
        val recorded = mutableListOf<Pair<ShotEvent, ShotDetail?>>()
        val seeded = mutableListOf<DemoSeedSession>()
        var sessionsStarted = 0

        override fun startSession(
            host: String?,
            transport: TransportType,
        ) {
            sessionsStarted++
            inner.startSession(host, transport)
        }

        override fun record(
            shot: ShotEvent,
            detail: ShotDetail?,
        ) {
            recorded += shot to detail
        }

        override suspend fun seedIfEmpty(sessions: List<DemoSeedSession>): Boolean {
            if (seeded.isNotEmpty()) return false
            seeded += sessions
            sessions.forEachIndexed { index, session ->
                inner.put(
                    "demo-$index",
                    session.shots.mapIndexed { row, detail -> HistoryShot(row.toLong(), "demo-$index", null, detail) },
                    startedAtEpochMillis = session.startedAtEpochMillis,
                )
            }
            return true
        }

        override fun clearAll() {
            seeded.clear()
            inner.clearAll()
        }
    }

    private class Harness(
        scope: TestScope,
        persistedDemo: Boolean = false,
    ) {
        val settings = FakeSettingsRepository(club = GolfClub.IRON_7).also { it.demoMode.value = persistedDemo }
        val realShots = RecordingShotRepository()
        val realPi = FakePiSessionRepository()
        val realHistory = FakeShotHistoryRepository()
        val demoHistory = RecordingDemoHistory()
        val controller =
            DemoController(
                settings = settings,
                history = demoHistory,
                mainScope = scope.backgroundScope,
                workScope = scope.backgroundScope,
                clock = { NOW },
                seed = 11L,
                offsetMillis = { 0L },
            )
        val shots = DemoSwitchingShotRepository(realShots, controller.shots, controller)
        val pi = DemoSwitchingPiSessionRepository(realPi, controller.pi, controller)
        val history = DemoSwitchingShotHistoryRepository(realHistory, demoHistory, controller)
    }

    private fun runDemoTest(
        persistedDemo: Boolean = false,
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest { body(Harness(this, persistedDemo)) }

    @Test
    fun theRealPiConnectsOnceTheStoredModeIsKnownAndDemoModeIsOffByDefault() =
        runDemoTest { h ->
            h.shots.start()
            assertThat(h.realShots.starts).isEqualTo(0)

            runCurrent()

            assertThat(h.realShots.starts).isEqualTo(1)
            assertThat(h.controller.enabled.value).isFalse()
            assertThat(h.shots.connectionState.value).isEqualTo(ConnectionState.Idle)
        }

    @Test
    fun aStoredDemoModeComesBackWithoutEverConnectingTheRealPi() =
        runDemoTest(persistedDemo = true) { h ->
            h.shots.start()
            advanceTimeBy(DemoTiming().connectMillis + 1)

            assertThat(h.controller.enabled.value).isTrue()
            assertThat(h.realShots.starts).isEqualTo(0)
            assertThat(h.shots.connectionState.value).isEqualTo(ConnectionState.Connected)
            assertThat(h.pi.linkState.value).isEqualTo(PiLinkState.Connected)
        }

    @Test
    fun turningDemoModeOnAndOffSwitchesEveryRepositoryAtRuntime() =
        runDemoTest { h ->
            h.shots.start()
            runCurrent()
            h.realShots.fakeHistory.setHistory(listOf(realShot()))

            h.controller.setEnabled(true)
            assertThat(h.realShots.stops).isEqualTo(1)
            assertThat(h.shots.history.value).isEmpty()
            assertThat(h.pi.mockMode.value).isEqualTo(true)
            assertThat(h.settings.demoMode.value).isTrue()
            advanceTimeBy(DemoTiming().connectMillis + 1)
            assertThat(h.shots.connectionState.value).isEqualTo(ConnectionState.Connected)
            assertThat(h.pi.profiles.value.profiles).hasSize(3)

            h.controller.setEnabled(false)
            assertThat(h.realShots.starts).isEqualTo(2)
            assertThat(
                h.shots.history.value
                    .map { it.eventId },
            ).containsExactly(REAL_EVENT)
            assertThat(h.controller.shots.connectionState.value).isEqualTo(ConnectionState.Idle)
            assertThat(h.settings.demoMode.value).isFalse()
        }

    @Test
    fun aDemoShotArrivesProvisionalThenFinalUnderOneEventIdAndIsFiledInDemoHistory() =
        runDemoTest { h ->
            h.shots.start()
            h.controller.setEnabled(true)
            advanceTimeBy(DemoTiming().connectMillis + 1)
            val finals = DefaultFinalShotStream(h.shots.history)

            finals.finalShots().test {
                h.controller.hitShot()
                runCurrent()
                assertThat(h.pi.shotProcessing.value).isEqualTo(ShotProcessingState.CAPTURING)
                advanceTimeBy(DemoTiming().captureMillis + 1)
                assertThat(h.pi.shotProcessing.value).isEqualTo(ShotProcessingState.CALCULATING)
                advanceTimeBy(DemoTiming().calculateMillis + 1)

                val provisional =
                    h.shots.history.value
                        .single()
                assertThat(provisional.final).isEqualTo(false)
                assertThat(provisional.launchAngleVertical).isNull()
                assertThat(provisional.club).isEqualTo("7-iron")
                assertThat(h.pi.shotProcessing.value).isNull()
                expectNoEvents()

                advanceTimeBy(DemoTiming().enrichMillis + 1)
                val final = awaitItem()
                assertThat(final.eventId).isEqualTo(provisional.eventId)
                assertThat(final.final).isEqualTo(true)
                assertThat(final.launchAngleVertical).isNotNull()
                assertThat(final.profileId).isEqualTo("preview-ann")
                assertThat(h.shots.history.value).hasSize(1)
            }
            assertThat(h.demoHistory.recorded.map { it.first.final }).containsExactly(false, true)
            assertThat(
                h.pi.sessionShots.value
                    .single()
                    .angleSource,
            ).isEqualTo(DemoShots.MOCK_SOURCE)
            assertThat(h.realHistory.sessions().first()).isEmpty()
        }

    @Test
    fun simulateOnTheDemoPiHitsAShotForTheActiveProfile() =
        runDemoTest { h ->
            h.shots.start()
            h.controller.setEnabled(true)
            advanceTimeBy(DemoTiming().connectMillis + 1)

            h.pi.setActiveProfile("preview-bob")
            h.pi.simulateShot()
            settle()

            assertThat(
                h.shots.history.value
                    .single()
                    .profileName,
            ).isEqualTo("Bob")
            assertThat(
                h.pi.triggerStatus.value
                    ?.triggersAccepted,
            ).isEqualTo(1)
        }

    @Test
    fun aClubChangeIsConfirmedAndTheNextShotUsesIt() =
        runDemoTest { h ->
            h.shots.start()
            h.controller.setEnabled(true)
            advanceTimeBy(DemoTiming().connectMillis + 1)

            val selection = h.shots.setClub(GolfClub.DRIVER)

            assertThat(selection.club).isEqualTo(GolfClub.DRIVER)
            assertThat(h.settings.selectedClub.value).isEqualTo(GolfClub.DRIVER)
            assertThat(h.shots.activeClub.value).isEqualTo(GolfClub.DRIVER)
            assertThat(h.pi.club.value).isEqualTo("driver")
            h.controller.hitShot()
            settle()
            assertThat(
                h.shots.history.value
                    .single()
                    .club,
            ).isEqualTo("driver")
        }

    @Test
    fun deletesAndClearsAreConfirmedByTheDemoPi() =
        runDemoTest { h ->
            h.shots.start()
            h.controller.setEnabled(true)
            advanceTimeBy(DemoTiming().connectMillis + 1)
            repeat(2) {
                h.controller.hitShot()
                settle()
            }
            val (newest, oldest) = h.shots.history.value

            h.shots.deleteShotByTimestamp(oldest.timestamp)
            runCurrent()
            assertThat(h.pi.deletionState.value).isEqualTo(DeletionState.Pending(oldest.timestamp))
            settle()
            assertThat(h.pi.deletionState.value).isEqualTo(DeletionState.Deleted(oldest.timestamp))
            assertThat(
                h.shots.history.value
                    .map { it.eventId },
            ).containsExactly(newest.eventId)

            h.shots.clearHistory()
            settle()
            assertThat(h.pi.clearState.value).isEqualTo(ClearState.Cleared("preview-ann"))
            assertThat(h.shots.history.value).isEmpty()
            assertThat(h.demoHistory.inner.deletedTimestamps).containsExactly(oldest.timestamp, newest.timestamp)
        }

    @Test
    fun turningDemoModeOnSeedsPastSessionsOnceAndTheHistoryFollowsTheMode() =
        runDemoTest { h ->
            h.realHistory.put("mine", listOf(HistoryShot(1, "mine", null, ShotDetail(timestamp = "t", club = "pw"))))
            h.shots.start()

            h.controller.setEnabled(true)
            advanceTimeBy(DemoTiming().connectMillis + 1)

            val demoSessions = h.history.sessions().first()
            assertThat(demoSessions.size).isGreaterThanOrEqualTo(3)
            assertThat(demoSessions.map { it.id }.none { it == "mine" }).isTrue()
            assertThat(
                h.demoHistory.seeded
                    .flatMap { it.shots }
                    .mapNotNull { it.profileId }
                    .toSet()
                    .sorted(),
            ).containsExactly("preview-ann", "preview-bob", "preview-cara")
            assertThat(h.demoHistory.sessionsStarted).isEqualTo(1)

            h.controller.setEnabled(false)
            assertThat(
                h.history
                    .sessions()
                    .first()
                    .map { it.id },
            ).containsExactly("mine")

            h.controller.setEnabled(true)
            advanceTimeBy(DemoTiming().connectMillis + 1)
            assertThat(h.demoHistory.seeded).hasSize(demoSessions.size)
        }

    @Test
    fun clearDemoDataWipesTheDemoSessionsAndTheLiveOne() =
        runDemoTest { h ->
            h.shots.start()
            h.controller.setEnabled(true)
            advanceTimeBy(DemoTiming().connectMillis + 1)
            h.controller.hitShot()
            settle()

            h.controller.clearDemoData()

            assertThat(h.history.sessions().first()).isEmpty()
            assertThat(h.shots.history.value).isEmpty()
            assertThat(h.pi.sessionShots.value).isEmpty()
        }

    @Test
    fun autoFireHitsAShotEveryFewSecondsOnlyWhileDemoModeIsOn() =
        runDemoTest { h ->
            h.shots.start()
            h.controller.setEnabled(true)
            advanceTimeBy(DemoTiming().connectMillis + 1)

            h.controller.setAutoFireSeconds(5)
            assertThat(h.settings.demoAutoFireSeconds.value).isEqualTo(5)
            advanceTimeBy(5_000L * 2 + 10_000L)
            assertThat(h.shots.history.value.size).isGreaterThanOrEqualTo(2)

            h.controller.setEnabled(false)
            val count = h.controller.shots.history.value.size
            advanceTimeBy(30_000L)
            assertThat(h.controller.shots.history.value.size).isEqualTo(count)

            h.controller.setAutoFireSeconds(7)
            assertThat(h.controller.autoFireSeconds.value).isEqualTo(5)
        }

    @Test
    fun aShutdownIsAcceptedThenTheDemoPiDropsUntilRetry() =
        runDemoTest { h ->
            h.shots.start()
            h.controller.setEnabled(true)
            advanceTimeBy(DemoTiming().connectMillis + 1)

            h.shots.shutdownPi(DemoModeRepository.DEMO_HOST)
            settle()
            assertThat(h.shots.connectionState.value).isInstanceOf(ConnectionState.Error::class)
            assertThat(h.pi.linkState.value).isEqualTo(PiLinkState.Idle)

            h.shots.retry()
            settle()
            assertThat(h.shots.connectionState.value).isEqualTo(ConnectionState.Connected)
        }

    @Test
    fun calibrationSaysItNeedsHardware() =
        runDemoTest { h ->
            h.shots.start()
            h.controller.setEnabled(true)
            advanceTimeBy(DemoTiming().connectMillis + 1)

            val failure = runCatching { h.shots.submitCalibration(dummyMeasurement()) }.exceptionOrNull()

            assertThat(failure?.message).isEqualTo(DemoShotRepository.CALIBRATION_NEEDS_HARDWARE)
        }

    @Test
    fun switchedFlowsReadTheLiveSideSynchronously() =
        runDemoTest { h ->
            assertThat(h.pi.linkState).isNotNull()
            h.realPi.linkState.value = PiLinkState.Reconnecting(1, 1_000, "down")
            assertThat(h.pi.linkState.value).isInstanceOf(PiLinkState.Reconnecting::class)

            h.controller.setEnabled(true)
            assertThat(h.pi.linkState.value).isSameInstanceAs(h.controller.pi.linkState.value)
        }

    @Test
    fun theSeededHistoryIsTheSameEveryTime() {
        val profiles = PreviewDevicePiSessionRepository().profiles.value.profiles
        val first = DemoHistorySeed.sessions(NOW, profiles) { 0L }
        val second = DemoHistorySeed.sessions(NOW, profiles) { 0L }

        assertThat(first.map { session -> session.shots.map { it.estimatedCarryYards } })
            .isEqualTo(second.map { session -> session.shots.map { it.estimatedCarryYards } })
        assertThat(
            first
                .flatMap { it.shots }
                .mapNotNull { it.club }
                .toSet()
                .size,
        ).isGreaterThanOrEqualTo(10)
    }

    @Test
    fun timestampsAreNaiveLocalIsoWithMicroseconds() {
        assertThat(naiveLocalTimestamp(0L) { 0L }).isEqualTo("1970-01-01T00:00:00.000000")
        assertThat(naiveLocalTimestamp(NOW) { 0L }).isEqualTo("2026-09-27T12:00:00.000000")
        assertThat(naiveLocalTimestamp(NOW + 1_234L) { -3_600_000L }).isEqualTo("2026-09-27T11:00:01.234000")
        assertThat(naiveLocalTimestamp(951_782_400_000L) { 0L }).isEqualTo("2000-02-29T00:00:00.000000")
    }

    private companion object {
        /** 2026-09-27T12:00:00Z. */
        const val NOW = 1_790_510_400_000L
        const val SETTLE_MILLIS = 10_000L
        const val REAL_EVENT = "11111111-1111-4111-8111-111111111111"

        fun realShot() =
            ShotEvent(
                schemaVersion = 1,
                eventId = REAL_EVENT,
                timestamp = "2026-09-27T10:00:00",
                club = "driver",
                ballSpeedMph = 150.0,
                estimatedCarryYards = 240.0,
            )

        fun dummyMeasurement() =
            PhoneOrientationMeasurement(
                mountTiltDeg = 0.0,
                rollDeg = 0.0,
                gravityXG = 0.0,
                gravityYG = 0.0,
                gravityZG = -1.0,
                tiltStddevDeg = 0.0,
                rollStddevDeg = 0.0,
                sampleCount = 1,
                measuredAt = "2026-09-27T12:00:00Z",
                deviceModel = "test",
            )
    }
}
