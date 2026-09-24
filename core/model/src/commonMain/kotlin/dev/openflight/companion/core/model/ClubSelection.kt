// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import kotlinx.serialization.Serializable

/**
 * The "club result" shape returned by `set_club`/`get_club`, both over BLE control responses and
 * `GET`/`POST /api/club`: `{"status":"...","club":"7-iron"}`.
 */
@Serializable
data class ClubSelection(
    val status: String,
    val club: GolfClub,
)
