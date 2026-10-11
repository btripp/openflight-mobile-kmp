// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import dev.openflight.companion.core.insights.ShotDetailInput
import dev.openflight.companion.core.insights.toShotDetailInput
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ShotDetail

/**
 * The shot detail for row [id] of the phone's own history (newest first, numbered like
 * [localRows]), enriched from the Pi's details; `null` once that shot is gone.
 */
internal fun localDetailInput(
    id: String,
    history: List<ShotEvent>,
    details: Map<String, ShotDetail>,
): ShotDetailInput? {
    val index = history.indexOfFirst { it.eventId == id }
    if (index < 0) return null
    val shot = history[index]
    return shot.toShotDetailInput(shotNumber = history.size - index, detail = details[shot.timestamp])
}

/**
 * The shot detail for row [id] (a timestamp) of the Pi's session or a stored session, numbered like
 * [piRows]; `null` once that shot is gone.
 */
internal fun piDetailInput(
    id: String,
    session: List<ShotDetail>,
): ShotDetailInput? {
    val index = session.indexOfFirst { it.timestamp == id }
    if (index < 0) return null
    return session[index].toShotDetailInput(shotNumber = session.size - index)
}
