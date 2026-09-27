// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.TransportType
import kotlin.test.Test

class LaunchOptionsTest {
    @Test
    fun noHookArgumentsMeanTheRealApp() {
        val options = LaunchOptions.fromArguments(listOf("/path/OpenFlight", "-NSDocumentRevisionsDebugMode", "YES"))

        assertThat(options).isEqualTo(LaunchOptions())
        assertThat(options.usesFakeRepository).isFalse()
    }

    @Test
    fun uiTestingAndPreviewShotEachSwapInTheFakeRepository() {
        assertThat(LaunchOptions.fromArguments(listOf("app", "--ui-testing")).usesFakeRepository).isTrue()
        val preview = LaunchOptions.fromArguments(listOf("app", "--preview-shot"))
        assertThat(preview.previewShot).isTrue()
        assertThat(preview.usesFakeRepository).isTrue()
    }

    @Test
    fun previewPiSwapsInTheFakeRepositoryToo() {
        val options = LaunchOptions.fromArguments(listOf("app", "--preview-pi"))

        assertThat(options.previewPi).isTrue()
        assertThat(options.usesFakeRepository).isTrue()
    }

    @Test
    fun rangeModeAndPreviewFlightAreFlagsThatKeepTheRealRepository() {
        val options = LaunchOptions.fromArguments(listOf("app", "--range-mode", "--preview-flight"))

        assertThat(options.rangeMode).isTrue()
        assertThat(options.previewFlight).isTrue()
        assertThat(options.usesFakeRepository).isFalse()
    }

    @Test
    fun transportAndHostTakeTheFollowingArgument() {
        val options = LaunchOptions.fromArguments(listOf("app", "--transport", "wifi", "--host", "localhost:8091"))

        assertThat(options.transport).isEqualTo(TransportType.WIFI)
        assertThat(options.host).isEqualTo("localhost:8091")
        assertThat(options.usesFakeRepository).isFalse()
    }

    @Test
    fun previewHistoryFlagsKeepTheRealShotRepository() {
        val slow = LaunchOptions.fromArguments(listOf("app", "--preview-history"))
        val stuck = LaunchOptions.fromArguments(listOf("app", "--preview-history-stuck"))

        assertThat(slow.previewHistory).isTrue()
        assertThat(slow.previewHistoryStuck).isFalse()
        assertThat(stuck.previewHistory).isFalse()
        assertThat(stuck.previewHistoryStuck).isTrue()
        assertThat(stuck.usesFakeRepository).isFalse()
    }

    @Test
    fun previewPiSessionFlagsAreSeparateFromTheShotRepository() {
        val answered = LaunchOptions.fromArguments(listOf("app", "--preview-pi-session"))
        val stuck = LaunchOptions.fromArguments(listOf("app", "--preview-pi-session-stuck"))

        assertThat(answered.previewPiSession).isTrue()
        assertThat(answered.previewPiSessionStuck).isFalse()
        assertThat(stuck.previewPiSessionStuck).isTrue()
        assertThat(stuck.usesFakeRepository).isFalse()
    }

    @Test
    fun missingOrUnknownValuesAreIgnored() {
        val options = LaunchOptions.fromArguments(listOf("app", "--transport", "carrier-pigeon", "--host"))

        assertThat(options.transport).isEqualTo(null)
        assertThat(options.host).isEqualTo(null)
        assertThat(LaunchOptions.fromArguments(listOf("--host", "--ui-testing")).host).isEqualTo(null)
    }

    @Test
    fun rangeFreezeProgressTakesAFractionClampedToTheFlight() {
        fun freeze(value: String) = LaunchOptions.fromArguments(listOf("app", "--range-freeze-progress", value))

        assertThat(freeze("0.5").rangeFreezeProgress).isEqualTo(0.5)
        assertThat(freeze("1.7").rangeFreezeProgress).isEqualTo(1.0)
        assertThat(freeze("-2").rangeFreezeProgress).isEqualTo(0.0)
        assertThat(freeze("half").rangeFreezeProgress).isEqualTo(null)
        assertThat(freeze("NaN").rangeFreezeProgress).isEqualTo(null)
        assertThat(LaunchOptions.fromArguments(listOf("app")).rangeFreezeProgress).isEqualTo(null)
        assertThat(freeze("0.5").usesFakeRepository).isFalse()
    }

    @Test
    fun rangeRealityKitIsAFlag() {
        assertThat(LaunchOptions.fromArguments(listOf("app", "--range-realitykit")).rangeRealityKit).isTrue()
        assertThat(LaunchOptions.fromArguments(listOf("app")).rangeRealityKit).isFalse()
    }

    @Test
    fun previewLiveShotsAndBulkHistoryAreFlags() {
        val options = LaunchOptions.fromArguments(listOf("app", "--preview-live-shots", "--preview-history-bulk"))

        assertThat(options.previewLiveShots).isTrue()
        assertThat(options.previewHistoryBulk).isTrue()
        assertThat(options.usesFakeRepository).isFalse()
        assertThat(LaunchOptions.fromArguments(listOf("app")).previewLiveShots).isFalse()
        assertThat(LaunchOptions.fromArguments(listOf("app")).previewHistoryBulk).isFalse()
    }

    @Test
    fun previewPiMockIsAFlagThatSwapsInTheFakeRepository() {
        val options = LaunchOptions.fromArguments(listOf("app", "--preview-pi-mock"))

        assertThat(options.previewPiMock).isTrue()
        assertThat(options.usesFakeRepository).isTrue()
        assertThat(LaunchOptions.fromArguments(listOf("app")).previewPiMock).isFalse()
    }
}
