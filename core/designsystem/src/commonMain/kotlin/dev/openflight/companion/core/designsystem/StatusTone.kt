// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

/**
 * The status tone a connection (or any other) state maps to. Kept as an enum of
 * meanings, not colors, so `core:data`'s `ConnectionState` never needs to know about
 * `core:designsystem`'s palette, and this module's components keep taking only
 * primitives and simple enums, never `core:model` types.
 */
enum class StatusTone {
    /** Connected / ready, for example a connected transport. */
    Positive,

    /** Working towards a state, for example scanning, connecting, discovering. */
    InProgress,

    /** An error or unavailable state. */
    Negative,

    /** Idle / no activity yet. */
    Neutral,
}
