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
    fun transportAndHostTakeTheFollowingArgument() {
        val options = LaunchOptions.fromArguments(listOf("app", "--transport", "wifi", "--host", "localhost:8091"))

        assertThat(options.transport).isEqualTo(TransportType.WIFI)
        assertThat(options.host).isEqualTo("localhost:8091")
        assertThat(options.usesFakeRepository).isFalse()
    }

    @Test
    fun missingOrUnknownValuesAreIgnored() {
        val options = LaunchOptions.fromArguments(listOf("app", "--transport", "carrier-pigeon", "--host"))

        assertThat(options.transport).isEqualTo(null)
        assertThat(options.host).isEqualTo(null)
        assertThat(LaunchOptions.fromArguments(listOf("--host", "--ui-testing")).host).isEqualTo(null)
    }
}
