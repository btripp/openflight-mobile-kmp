// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Starts the Koin graph once per process. `core:data` builds the Bluetooth transport and the
 * settings DataStore from the Android context.
 */
class OpenFlightApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@OpenFlightApplication)
            modules(appModules)
        }
    }
}
