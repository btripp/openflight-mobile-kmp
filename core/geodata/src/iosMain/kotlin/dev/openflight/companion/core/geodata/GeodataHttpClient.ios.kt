// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.geodata

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun geodataHttpClient(): HttpClient =
    HttpClient(Darwin) {
        installGeodataDefaults()
    }
