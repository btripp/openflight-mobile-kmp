// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * Darwin streaming finding (verify on a device/simulator once an app exists to host it, S7+):
 * Ktor 3.4 added "iOS streaming response support" to the Darwin engine -- the engine's
 * `NSURLSessionDataDelegateProtocol` delegate forwards each `didReceiveData` chunk into the
 * response's `ByteReadChannel` as it arrives, instead of buffering the whole body until the task
 * completes (ktorio/ktor commit `24206ef`, Ktor 3.4 changelog). Ktor 3.6.0 (this project's
 * version) carries that support, so [WifiShotTransport] reading `bodyAsChannel()` byte-by-byte
 * should see each SSE event as `didReceiveData` delivers it, matching the "not batched" exit
 * criterion. This was not verified against a live server on a simulator in this step -- no
 * composeApp/iosApp UI exists yet to host the call -- so treat it as a documented finding to
 * re-check manually once S7 wires this transport into the app shell.
 */
actual fun openFlightHttpClient(): HttpClient =
    HttpClient(Darwin) {
        installOpenFlightDefaults()
    }
