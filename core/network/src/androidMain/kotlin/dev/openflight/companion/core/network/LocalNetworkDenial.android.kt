// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

/** Android has no Local Network permission gating plain sockets on the API levels this app targets. */
internal actual fun isDeniedOnPlatform(error: Throwable): Boolean = false
