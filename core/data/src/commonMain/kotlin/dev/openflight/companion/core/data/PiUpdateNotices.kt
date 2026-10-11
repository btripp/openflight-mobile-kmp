// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.pi.PiUpdateNotice
import dev.openflight.companion.core.protocol.SchemaV2Codec

/**
 * The [PiUpdateNotice] for a Pi that reported [features] (`ShotTransport.piFeatures`), or `null`
 * when they're unknown (not connected yet) or the Pi misses nothing.
 *
 * [overWifi]: the Pi's Socket.IO session reports every row deleted, even while the phone was away
 * ([PiSessionRepository.removedRows]), so over Wi-Fi only shots can be missed.
 * [stockPi]: the Wi-Fi stream answered 404, a stock upstream Pi that reports no features and has
 * no catch-up.
 */
internal fun piUpdateNoticeFor(
    features: Set<String>?,
    overWifi: Boolean = false,
    stockPi: Boolean = false,
): PiUpdateNotice? {
    val known = if (stockPi) emptySet() else features ?: return null
    val notice =
        PiUpdateNotice(
            missesShots = SchemaV2Codec.FEATURE_SHOT_CATCH_UP !in known,
            missesDeletions = !overWifi && !stockPi && SchemaV2Codec.FEATURE_DELETION_CATCH_UP !in known,
        )
    return notice.takeIf { it.missesShots || it.missesDeletions }
}
