// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point called from Swift as `MainViewControllerKt.MainViewController()`. */
@Suppress("FunctionName", "ktlint:standard:function-naming")
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
