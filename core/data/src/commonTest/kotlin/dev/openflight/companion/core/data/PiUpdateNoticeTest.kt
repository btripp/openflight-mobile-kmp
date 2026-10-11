// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.prop
import dev.openflight.companion.core.model.pi.PiUpdateNotice
import dev.openflight.companion.core.protocol.SchemaV2Codec
import kotlin.test.Test

class PiUpdateNoticeTest {
    @Test
    fun unknownFeaturesGiveNoNotice() {
        assertThat(piUpdateNoticeFor(null)).isNull()
    }

    @Test
    fun aPiReportingNoFeaturesMissesBothKindsOfCatchUp() {
        assertThat(piUpdateNoticeFor(emptySet()))
            .isEqualTo(PiUpdateNotice(missesShots = true, missesDeletions = true))
    }

    @Test
    fun aPiWithBothKindsOfCatchUpNeedsNoUpdate() {
        assertThat(
            piUpdateNoticeFor(
                setOf(SchemaV2Codec.FEATURE_SHOT_CATCH_UP, SchemaV2Codec.FEATURE_DELETION_CATCH_UP, "club"),
            ),
        ).isNull()
    }

    @Test
    fun aStockPiMissesOnlyShotsBecauseItsSocketIoSessionReportsDeletions() {
        assertThat(piUpdateNoticeFor(null, overWifi = true, stockPi = true))
            .isEqualTo(PiUpdateNotice(missesShots = true, missesDeletions = false))
    }

    @Test
    fun overWifiOnlyShotCatchUpMattersBecauseSocketIoReportsDeletions() {
        assertThat(piUpdateNoticeFor(emptySet(), overWifi = true))
            .isEqualTo(PiUpdateNotice(missesShots = true, missesDeletions = false))
        assertThat(piUpdateNoticeFor(setOf(SchemaV2Codec.FEATURE_SHOT_CATCH_UP), overWifi = true)).isNull()
    }

    @Test
    fun theDetailNamesWhatTheOlderPiCantResend() {
        assertThat(piUpdateNoticeFor(emptySet()))
            .isNotNull()
            .prop(PiUpdateNotice::detail)
            .isEqualTo(
                "This Pi's OpenFlight can't resend shots or deletions you miss while your phone is disconnected. " +
                    "Update OpenFlight on the Pi to keep the app in sync.",
            )
        assertThat(piUpdateNoticeFor(setOf(SchemaV2Codec.FEATURE_SHOT_CATCH_UP)))
            .isNotNull()
            .prop(PiUpdateNotice::detail)
            .isEqualTo(
                "This Pi's OpenFlight can't resend deletions you miss while your phone is disconnected. " +
                    "Update OpenFlight on the Pi to keep the app in sync.",
            )
        assertThat(piUpdateNoticeFor(null, overWifi = true, stockPi = true))
            .isNotNull()
            .prop(PiUpdateNotice::detail)
            .isEqualTo(
                "This Pi's OpenFlight can't resend shots you miss while your phone is disconnected. " +
                    "Update OpenFlight on the Pi to keep the app in sync.",
            )
    }
}
