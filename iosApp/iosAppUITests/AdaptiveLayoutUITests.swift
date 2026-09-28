// SPDX-License-Identifier: AGPL-3.0-or-later
import UIKit
import XCTest

/// Plan F1c: the iPad shell (a `NavigationSplitView` sidebar, where Android shows a navigation
/// rail) and per-screen list-detail (`AppListDetailPane`, Session history's list-detail), plus plan
/// F1d's navigation: the same four destinations everywhere, never a "More" tab on an iPhone, and a
/// truly full-screen Range on an iPad.
///
/// Each test is for one idiom and skips itself (rather than fails) on the other, because what it
/// checks doesn't exist there: only an iPad reaches a `.regular` `horizontalSizeClass` on its own in
/// full screen (the sidebar, list-detail, the sidebar collapsing), and only an iPhone has the
/// compact tab bar whose sixth entry would overflow into "More".
final class AdaptiveLayoutUITests: XCTestCase {
    /// The simulator's orientation outlives the test, so a test that rotates it puts it back.
    private var startOrientation: UIDeviceOrientation = .portrait

    override func setUp() {
        continueAfterFailure = false
        startOrientation = XCUIDevice.shared.orientation
    }

    override func tearDown() {
        if XCUIDevice.shared.orientation != startOrientation, startOrientation.isValidInterfaceOrientation {
            XCUIDevice.shared.orientation = startOrientation
        }
        super.tearDown()
    }

    /// The sidebar replaces the tab bar (plan F1a's Android rail) and lists the same four
    /// top-level destinations, in the same order, as `AppNavHost.kt`'s `TopLevelDestination`.
    func testTheSidebarShowsTheTopLevelDestinations() throws {
        try XCTSkipUnless(AppNav.isPad, "iPad only: needs a regular horizontalSizeClass.")
        // Landscape: the balanced split shows the sidebar beside the detail (portrait tucks it away).
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = AppNav.launch(["--ui-testing", "--preview-shot"])

        let sidebar = app.descendants(matching: .any)["app.nav.sidebar"]
        XCTAssertTrue(sidebar.waitForExistence(timeout: 10))
        let items = AppNav.Tab.allCases.map { app.descendants(matching: .any)[$0.sidebarIdentifier] }
        for (tab, item) in zip(AppNav.Tab.allCases, items) {
            XCTAssertTrue(item.exists, "missing sidebar item: \(tab.label)")
            XCTAssertTrue(item.label.contains(tab.label), "sidebar item: \(item.label)")
        }
        // Top to bottom in `AppTab` order: Practice · Sessions · Bag · Settings.
        let tops = items.map(\.frame.minY)
        XCTAssertEqual(tops, tops.sorted(), "sidebar order: \(items.map(\.label))")
        // No tab bar on a regular width: the sidebar replaces it.
        XCTAssertFalse(app.tabBars.buttons["Practice"].exists)
    }

    /// Plan F1d: four tab bar entries, labelled and ordered like Android's bottom bar, so none
    /// overflows into the system "More" tab (six did before F1d, which hid Bag and Settings).
    func testNoMoreTabOnIPhone() throws {
        try XCTSkipIf(AppNav.isPad, "iPhone only: a full-screen iPad shows the sidebar, not a tab bar.")
        let app = AppNav.launch(["--ui-testing", "--preview-shot"])

        let buttons = app.tabBars.firstMatch.buttons
        XCTAssertEqual(buttons.count, AppNav.Tab.allCases.count)
        XCTAssertEqual(
            (0..<buttons.count).map { buttons.element(boundBy: $0).label },
            AppNav.Tab.allCases.map(\.label)
        )
        XCTAssertFalse(app.tabBars.buttons["More"].exists)
    }

    /// Plan F1d: on an iPad the pushed Range collapses the split view, so the sidebar is gone while
    /// it's up, and it comes back after Exit. Landscape, where the balanced split shows the sidebar
    /// beside the detail from the start. A `--range-mode` launch starts collapsed too.
    func testRangeHidesSidebarOnIPad() throws {
        try XCTSkipUnless(AppNav.isPad, "iPad only: an iPhone has no sidebar to hide.")
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = AppNav.launch(["--ui-testing", "--preview-shot"])
        let practice = app.descendants(matching: .any)[AppNav.Tab.practice.sidebarIdentifier]
        XCTAssertTrue(practice.isHittable, "the sidebar shows beside the dashboard")

        let range = app.buttons["dashboard.range"]
        XCTAssertTrue(range.waitForExistence(timeout: 5))
        range.tap()
        let exit = app.buttons["range.exit"]
        XCTAssertTrue(exit.waitForExistence(timeout: 5))
        XCTAssertTrue(waitUntilHidden(practice), "the sidebar is still on screen over the Range")

        exit.tap()
        XCTAssertTrue(range.waitForExistence(timeout: 5))
        XCTAssertTrue(waitUntilHittable(practice), "the sidebar didn't come back after the Range")

        app.terminate()
        app.launchArguments = ["--ui-testing", "--preview-shot", "--range-mode"]
        app.launch()
        XCTAssertTrue(app.buttons["range.exit"].waitForExistence(timeout: 10))
        XCTAssertTrue(waitUntilHidden(practice), "--range-mode opened the Range beside the sidebar")
    }

    private func waitUntilHidden(_ element: XCUIElement) -> Bool {
        let hidden = NSPredicate(format: "exists == false OR isHittable == false")
        return XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: hidden, object: element)], timeout: 5) == .completed
    }

    private func waitUntilHittable(_ element: XCUIElement) -> Bool {
        let hittable = NSPredicate(format: "exists == true AND isHittable == true")
        return XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: hittable, object: element)], timeout: 5) == .completed
    }

    /// Session history: the stored-session list and its detail side by side (plan F1: "Session /
    /// Session history: list-detail on expanded"), both on screen at once, before and after a
    /// session is picked.
    func testSessionHistoryShowsListAndDetailSideBySide() throws {
        try XCTSkipUnless(AppNav.isPad, "iPad only: needs a regular horizontalSizeClass.")
        let app = AppNav.launch(["--ui-testing", "--preview-shot", "--preview-history"])

        AppNav.open(.sessions, in: app)
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
        XCTAssertEqual(source.label, "Network · raspberrypi.local:8080")

        // Plan F1d: picking another session replaces the detail (it used to keep the first
        // session's view model), and a tap anywhere on a short row picks it.
        let title = app.staticTexts["session.history.detail.title"]
        let currentTitle = title.label
        app.descendants(matching: .any)["session.history.session.preview-older"].tap()
        let changed = NSPredicate(format: "label != %@", currentTitle)
        XCTAssertEqual(
            XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: changed, object: title)], timeout: 5),
            .completed,
            "the detail still shows \(currentTitle)"
        )
    }
}
