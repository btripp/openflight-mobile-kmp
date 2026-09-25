// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

/**
 * What a game in progress tells the shot call-outs (plan F-series, A2): whose turn it is and the
 * target the next shot is measured against. Pure data, so the games feature can publish it and
 * the call-outs read it without depending on each other.
 *
 * @property gameTitle the game being played, e.g. `"Target call-out"`.
 * @property targetYards the distance the next shot aims for, or `null` when the game has none.
 * @property playerName whose turn it is, or `null` for a single, unnamed player.
 */
data class CalloutContext(
    val gameTitle: String,
    val targetYards: Double? = null,
    val playerName: String? = null,
)
