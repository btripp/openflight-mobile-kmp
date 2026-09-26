// SPDX-License-Identifier: AGPL-3.0-or-later
import UIKit
import XCTest

/// Plan F1c: the iPad shell (a `NavigationSplitView` sidebar, where Android shows a navigation
/// rail) and per-screen list-detail (`AppListDetailPane`, Session history's list-detail). These
/// only run on an iPad simulator: a `.regular` `horizontalSizeClass` is what switches
/// `AppRoot`/`SessionHistoryContent` into these layouts, and only an iPad reaches `.regular` on
/// its own in full screen, in either orientation, so both tests skip themselves (rather than
/// fail) on an iPhone destination.
final class AdaptiveLayoutUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// The sidebar replaces the tab bar (plan F1a's Android rail) and lists the same five
    /// top-level destinations, in the same order, as `AppNavHost.kt`'s `TopLevelDestination`.
    func testTheSidebarShowsTheTopLevelDestinations() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "iPad only: needs a regular horizontalSizeClass.")
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot"]
        app.launch()

        let sidebar = app.descendants(matching: .any)["app.nav.sidebar"]
        XCTAssertTrue(sidebar.waitForExistence(timeout: 10))
        for tab in ["dashboard", "session", "training", "camera", "settings"] {
            XCTAssertTrue(
                app.descendants(matching: .any)["app.nav.sidebar.\(tab)"].exists,
                "missing sidebar item: \(tab)"
            )
        }
        // No tab bar on a regular width: the sidebar replaces it.
        XCTAssertFalse(app.tabBars.buttons["Dashboard"].exists)
    }

    /// Session history: the stored-session list and its detail side by side (plan F1: "Session /
    /// Session history: list-detail on expanded"), both on screen at once, before and after a
    /// session is picked.
    func testSessionHistoryShowsListAndDetailSideBySide() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "iPad only: needs a regular horizontalSizeClass.")
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot", "--preview-history"]
        app.launch()

        app.descendants(matching: .any)["app.nav.sidebar.session"].tap()
        let open = app.buttons["session.history.open"]
        XCTAssertTrue(open.waitForExistence(timeout: 10))
        open.tap()

        let list = app.descendants(matching: .any)["app.listDetail.list"]
        XCTAssertTrue(list.waitForExistence(timeout: 5))
        // Nothing selected yet: the detail pane still exists (a placeholder), side by side.
        XCTAssertTrue(app.descendants(matching: .any)["app.listDetail.detail"].exists)

        let current = app.descendants(matching: .any)["session.history.session.preview-current"]
        XCTAssertTrue(current.waitForExistence(timeout: 5))
        current.tap()

        // Both panes are still on screen together, and the detail now shows that session.
        XCTAssertTrue(app.descendants(matching: .any)["app.listDetail.list"].exists)
        let source = app.staticTexts["session.history.detail.source"]
        XCTAssertTrue(source.waitForExistence(timeout: 5))
        XCTAssertEqual(source.label, "Wi-Fi · raspberrypi.local:8080")
    }
}
