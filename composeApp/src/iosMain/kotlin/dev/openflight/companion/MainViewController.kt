// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi

/** Entry point called from Swift as `MainViewControllerKt.MainViewController()`. */
@Suppress("FunctionName", "ktlint:standard:function-naming")
fun MainViewController(): UIViewController {
    initKoin()
    return ComposeUIViewController { App() }
}

/**
 * Starts Koin once per process: SwiftUI may build the view controller again (a new scene, a
 * representable refresh), and a second `startKoin` throws. Debug builds then apply the
 * [LaunchOptions] from the process arguments.
 */
@OptIn(ExperimentalNativeApi::class)
private fun initKoin() {
    if (KoinPlatform.getKoinOrNull() != null) return
    val koin = startKoin { modules(appModules) }.koin
    if (Platform.isDebugBinary) {
        val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
        runBlocking { koin.applyLaunchOptions(LaunchOptions.fromArguments(arguments)) }
    }
}
