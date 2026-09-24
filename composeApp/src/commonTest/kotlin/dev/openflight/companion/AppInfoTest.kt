// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class AppInfoTest {
    @Test
    fun titleIsOpenFlight() {
        assertThat(AppInfo.TITLE).isEqualTo("OpenFlight")
    }

    @Test
    fun subtitleAppendsPlatformName() {
        assertThat(AppInfo.subtitle("Android 36")).isEqualTo("Launch monitor companion · Android 36")
    }
}
