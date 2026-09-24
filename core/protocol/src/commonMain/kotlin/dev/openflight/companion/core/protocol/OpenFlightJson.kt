// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import kotlinx.serialization.json.Json

/**
 * The one `Json` instance every OpenFlight codec shares.
 *
 * `encodeDefaults = true` is load-bearing: without it, default properties like
 * `schema_version = 1` are omitted from encoded JSON, and the Pi rejects control envelopes and
 * calibration payloads that lack `"schema_version":1` (see plan §0.1, §0.3a).
 * `ignoreUnknownKeys = true` keeps forward compatibility with new server fields.
 * `explicitNulls` stays at its default (`true`) so an explicit server `null` and a genuinely
 * missing optional field both round-trip correctly.
 */
val OpenFlightJson: Json =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
