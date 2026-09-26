// SPDX-License-Identifier: AGPL-3.0-or-later
import UIKit
import XCTest

/// Plan F1d: opens a top-level destination through whichever shell the app shows. An iPhone has a
/// tab bar; an iPad in full screen (a regular `horizontalSizeClass`) has the `NavigationSplitView`
/// sidebar instead (plan F1c), which in portrait may first need showing; and an iPad in a compact
/// split on iOS 18+ may show the tab bar as a floating tab strip rather than a `tabBars` element.
/// The labels and order are the app's `AppTab` (the same as Android's `TopLevelDestination`).
enum AppNav {
    enum Tab: String, CaseIterable {
        case practice, sessions, bag, settings

        var label: String {
            switch self {
            case .practice: "Practice"
            case .sessions: "Sessions"
            case .bag: "Bag"
            case .settings: "Settings"
            }
        }

        /// `AppNavigationTags.sidebarItem(_:)` in the app.
        var sidebarIdentifier: String { "app.nav.sidebar.\(rawValue)" }
    }

    /// Whether this run's device is an iPad (where the full-screen app shows the sidebar).
    static var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    /// Launches `app` with `arguments` and waits until the shell's navigation is up.
    @discardableResult
    static func launch(
        _ arguments: [String],
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = arguments
        app.launch()
        XCTAssertTrue(waitForShell(app), "no tab bar or sidebar after launch", file: file, line: line)
        return app
    }

    /// Waits until the tab bar, the floating tab strip or the sidebar shows `tab` (Settings by
    /// default: it's always the last entry, so the whole shell is up once it's there), or, on an
    /// iPad in portrait, until the split view's sidebar toggle is there to bring the sidebar in.
    static func waitForShell(_ app: XCUIApplication, tab: Tab = .settings, timeout: TimeInterval = 10) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if entry(tab, in: app) != nil || sidebarToggle(in: app) != nil { return true }
            // A portrait iPad on Practice (no navigation bar, so no sidebar button): the shell is
            // up once the dashboard is.
            if isPad && app.buttons["dashboard.range"].exists { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        } while Date() < deadline
        return false
    }

    /// Opens `tab`: its tab bar button, its floating tab or its sidebar row, whichever is showing
    /// (showing the sidebar first when it's tucked away).
    static func open(_ tab: Tab, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        guard waitForShell(app, tab: tab) else {
            XCTFail("no tab bar or sidebar for \(tab.label)", file: file, line: line)
            return
        }
        let revealed = entry(tab, in: app).map({ !$0.isHittable }) ?? true
        if revealed { revealSidebar(in: app) }
        let deadline = Date().addingTimeInterval(5)
        while entry(tab, in: app).map({ !$0.isHittable }) ?? true, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        guard let element = entry(tab, in: app) else {
            XCTFail("no navigation entry for \(tab.label)", file: file, line: line)
            return
        }
        element.tap()
        if revealed || isPortraitPad(app) { dismissSidebar(element, in: app) }
    }

    /// A portrait iPad: the split view shows its sidebar by displacing the detail, not beside it.
    private static func isPortraitPad(_ app: XCUIApplication) -> Bool {
        guard isPad else { return false }
        let window = app.windows.firstMatch.frame
        return window.height > window.width
    }

    /// A sidebar over a portrait iPad (shown at launch, or brought in) displaces the detail, and the
    /// detail's taps only put it away again. Put it away now (its "Hide Sidebar" button, or a tap
    /// on the detail's trailing edge), so the test's next tap lands on the screen it just opened.
    private static func dismissSidebar(_ item: XCUIElement, in app: XCUIApplication) {
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        guard item.exists && item.isHittable else { return }
        let hide = app.buttons["Hide Sidebar"].firstMatch
        if hide.exists && hide.isHittable {
            hide.tap()
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap()
        }
        let gone = NSPredicate(format: "exists == false OR isHittable == false")
        _ = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: gone, object: item)], timeout: 3)
    }

    /// The element that opens `tab`, or nil while none is on screen.
    private static func entry(_ tab: Tab, in app: XCUIApplication) -> XCUIElement? {
        let sidebarItem = app.descendants(matching: .any)[tab.sidebarIdentifier]
        if sidebarItem.exists { return sidebarItem }
        let tabBarButton = app.tabBars.buttons[tab.label]
        if tabBarButton.exists { return tabBarButton }
        // iPadOS 18+'s floating tab strip isn't a `tabBars` element; its tabs are plain buttons.
        if isPad {
            let floating = app.buttons[tab.label].firstMatch
            if floating.exists { return floating }
        }
        return nil
    }

    /// Brings a tucked-away iPad sidebar in: its navigation bar button where the screen has a
    /// navigation bar, otherwise the split view's swipe in from the leading edge.
    private static func revealSidebar(in app: XCUIApplication) {
        if let toggle = sidebarToggle(in: app) {
            toggle.tap()
            return
        }
        guard isPad else { return }
        let edge = app.coordinate(withNormalizedOffset: CGVector(dx: 0.005, dy: 0.5))
        edge.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.5)))
    }

    /// In portrait an iPad's balanced split view starts with the sidebar tucked away behind the
    /// navigation bar's sidebar button ("Show Sidebar"); nil when there's none (an iPhone, or the
    /// sidebar already showing).
    static func sidebarToggle(in app: XCUIApplication) -> XCUIElement? {
        guard isPad else { return nil }
        let sidebar = NSPredicate(format: "identifier CONTAINS[c] 'sidebar' OR label CONTAINS[c] 'sidebar'")
        let toggle = app.navigationBars.buttons.matching(sidebar).firstMatch
        return toggle.exists && toggle.isHittable ? toggle : nil
    }
}
