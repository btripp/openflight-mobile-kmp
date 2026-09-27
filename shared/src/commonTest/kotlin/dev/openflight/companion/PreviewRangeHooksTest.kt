// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import dev.openflight.companion.core.testing.FakeSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Plan F8b's range launch hooks: `--preview-live-shots` and `--preview-history-bulk`. */
class PreviewRangeHooksTest {
    private fun previewShots() = PreviewShotRepository(FakeSettingsRepository(), showPreviewShot = true)

    @Test
    fun liveShotsArriveOneIntervalApartAsNewShots() =
        runTest {
            val repository =
                LocalEditsShotRepository(
                    previewShots(),
                    scope = backgroundScope,
                    liveShotIntervalMillis = INTERVAL,
                )

            assertThat(repository.latestShot.value?.eventId).isEqualTo(PreviewShotRepository.PREVIEW_SHOT.eventId)

            testScheduler.advanceTimeBy(INTERVAL - 1)
            assertThat(repository.latestShot.value?.eventId).isEqualTo(PreviewShotRepository.PREVIEW_SHOT.eventId)
            testScheduler.advanceTimeBy(2)
            val first = repository.latestShot.value
            assertThat(first?.eventId).isEqualTo(PreviewShotRepository.liveShot(1).eventId)

            testScheduler.advanceTimeBy(INTERVAL)
            val second = repository.latestShot.value
            assertThat(second?.eventId).isEqualTo(PreviewShotRepository.liveShot(2).eventId)
            assertThat(second?.timestamp).isNotEqualTo(first?.timestamp)
            assertThat(repository.history.value.map { it.eventId })
                .containsExactly(
                    PreviewShotRepository.liveShot(2).eventId,
                    PreviewShotRepository.liveShot(1).eventId,
                    PreviewShotRepository.PREVIEW_SHOT.eventId,
                )
        }

    @Test
    fun withoutAnIntervalNoShotArrives() =
        runTest {
            val repository = LocalEditsShotRepository(previewShots(), scope = backgroundScope)

            testScheduler.advanceTimeBy(INTERVAL * 3)

            assertThat(repository.history.value.size).isEqualTo(1)
        }

    @Test
    fun bulkHistoryAddsAnOlderSessionPastTheOverlayCap() =
        runTest {
            val repository =
                PreviewShotHistoryRepository(scope = backgroundScope, bulkShots = LaunchOptions.PREVIEW_BULK_SHOTS)

            val sessions = repository.sessions().first()
            assertThat(sessions.map { it.id }).containsExactly(
                PreviewShotHistoryRepository.CURRENT_SESSION,
                PreviewShotHistoryRepository.OLDER_SESSION,
                PreviewShotHistoryRepository.BULK_SESSION,
            )
            val bulk = repository.shots(PreviewShotHistoryRepository.BULK_SESSION).first()
            assertThat(bulk.size).isEqualTo(LaunchOptions.PREVIEW_BULK_SHOTS)
            // Unique row ids, clear of the other sessions', and every shot flyable.
            assertThat(bulk.map { it.id }.toSet().size).isEqualTo(bulk.size)
            assertThat(bulk.count { it.id < 1_000 }).isEqualTo(0)
            assertThat(bulk.count { it.detail.ballSpeedMph == null || it.detail.estimatedCarryYards == null })
                .isEqualTo(0)
            assertThat(bulk.map { it.detail.club }.toSet().size).isEqualTo(6)
        }

    @Test
    fun withoutBulkThereAreTheTwoPreviewSessions() =
        runTest {
            val repository = PreviewShotHistoryRepository(scope = backgroundScope)

            assertThat(repository.sessions().first().size).isEqualTo(2)
            assertThat(repository.shots(PreviewShotHistoryRepository.BULK_SESSION).first().firstOrNull()).isNull()
        }

    @Test
    fun mockPiSimulatesANewDifferentShotEachTime() =
        runTest {
            val shots = LocalEditsShotRepository(previewShots(), scope = backgroundScope)
            val pi =
                PreviewDevicePiSessionRepository(mockMode = true) { number ->
                    shots.deliver(PreviewShotRepository.simulatedShot(number))
                }

            assertThat(pi.mockMode.value).isEqualTo(true)
            pi.simulateShot()
            val first = shots.latestShot.value
            pi.simulateShot()
            val second = shots.latestShot.value

            assertThat(first?.eventId).isEqualTo(PreviewShotRepository.simulatedShot(1).eventId)
            assertThat(second?.eventId).isEqualTo(PreviewShotRepository.simulatedShot(2).eventId)
            assertThat(second?.timestamp).isNotEqualTo(first?.timestamp)
            // Different flights: neither is the preview shot's, nor each other's.
            val carries =
                listOf(PreviewShotRepository.PREVIEW_SHOT, first, second).map { it?.estimatedCarryYards }
            assertThat(carries.toSet().size).isEqualTo(3)
            assertThat(shots.history.value.size).isEqualTo(3)
        }

    @Test
    fun thePreviewPiIsNotAMockByDefault() {
        assertThat(PreviewDevicePiSessionRepository().mockMode.value).isEqualTo(false)
    }

    private companion object {
        const val INTERVAL = 5_000L
    }
}
