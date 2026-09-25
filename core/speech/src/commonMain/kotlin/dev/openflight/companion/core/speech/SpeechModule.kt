// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.speech

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The platform's [SpeechEngine] binding. Android builds `AndroidSpeechEngine` from
 * `androidContext()` (so the app must start Koin with `androidContext(...)`, as it already does
 * for `core:data`'s `BleShotTransport`); iOS builds `IosSpeechEngine` with no platform arguments.
 */
expect val platformSpeechModule: Module

/**
 * Everything `core:speech` provides: a single [SpeechEngine] (see [platformSpeechModule]). Wired
 * into the app graph the same way `core:data`'s `dataModule` is, from `shared`'s `initKoin`
 * (`AppKoin.kt`).
 */
val speechModule: Module =
    module {
        includes(platformSpeechModule)
    }
