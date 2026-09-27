// SPDX-License-Identifier: AGPL-3.0-or-later
import UIKit
import XCTest

/// Plan F8d-B: "View on range" from Practice and the session history, "Replay on range", Simulate
/// on a mock Pi, and the range controls' layout (the iOS twins of Android's `RangeEverywhereTest`,
/// `RangeEverywhereFlowTest` and the status-pill regression in `DrivingRangeScreenTest`).
///
/// `--preview-history` stores "preview-current", oldest first: a driver (264 yds, 10:03), a 7-iron
/// (165 yds, 10:21:40.5) and a driver (251 yds, 10:45). `--preview-pi-mock` is a connected mock
/// Pi whose Simulate delivers a new preview shot: a 7-iron (165 yds), then a driver (281 yds).
/// Runs on an iPhone and an iPad simulator; on an iPad the range hides the sidebar (plan F1d).
final class RangeEverywhereUITests: XCTestCase {
    private static let secondShot = "2026-09-25T10:21:40.500000"

    override func setUp() {
        continueAfterFailure = false
    }

    /// A stored shot's "View on range" opens the range on that shot, paused, with the transport
    /// ready; Exit returns to the session.
    func testViewOnRangeFromHistoryOpensThatShot() {
        let app = SessionHistoryUITests.openHistory()
        SessionHistoryUITests.openSession("session.history.session.preview-current", in: app)

        let view = reveal("session.shot.\(Self.secondShot).viewOnRange", in: app)
        view.tap()

        let position = element("range.position", in: app)
        XCTAssertTrue(position.waitForExistence(timeout: 10))
        wait(for: position, label: "2 / 3")
        assertCarry("165", in: app)
        XCTAssertEqual(app.buttons["range.playPause"].label, "Play")
        XCTAssertTrue(app.buttons["range.next"].isEnabled)
        XCTAssertTrue(app.buttons["range.previous"].isEnabled)
        assertFullScreen(app)

        app.buttons["range.exit"].tap()
        XCTAssertTrue(view.waitForExistence(timeout: 5))
    }

    /// The stored session's "Replay on range" (the pushed detail on an iPhone, the docked pane on an
    /// iPad) replays it from its first shot.
    func testReplayOnRangeFromHistoryStartsAtTheFirstShot() {
        let app = SessionHistoryUITests.openHistory()
        SessionHistoryUITests.openSession("session.history.session.preview-current", in: app)

        let replay = reveal("session.history.detail.replayOnRange", in: app)
        replay.tap()

        let position = element("range.position", in: app)
        XCTAssertTrue(position.waitForExistence(timeout: 10))
        wait(for: position, label: "1 / 3")
        assertCarry("264", in: app)
        assertFullScreen(app)
    }

    /// Practice's latest shot "View on range" opens the range replaying the current session, paused.
    func testPracticeViewOnRangeOpensTheRangePaused() {
        let app = AppNav.launch(["--ui-testing", "--preview-shot", "--preview-history"])
        let view = app.buttons["dashboard.viewOnRange"]
        XCTAssertTrue(view.waitForExistence(timeout: 10))
        view.tap()

        let playPause = app.buttons["range.playPause"]
        XCTAssertTrue(playPause.waitForExistence(timeout: 10))
        XCTAssertEqual(playPause.label, "Play")
        XCTAssertTrue(element("range.transport", in: app).exists)
        assertFullScreen(app)

        app.buttons["range.exit"].tap()
        XCTAssertTrue(view.waitForExistence(timeout: 5))
    }

    /// Bag → Club Detail: a recent shot's "View on range" opens that stored shot, paused.
    func testClubDetailRecentShotViewOnRangeOpensThatShot() {
        let app = AppNav.launch(["--ui-testing", "--preview-shot", "--preview-history"])
        AppNav.open(.bag, in: app)
        let driver = element("bag.club.driver", in: app)
        XCTAssertTrue(driver.waitForExistence(timeout: 10))
        driver.tap()

        // The current session's newest driver (row 3, 251 yds).
        let view = reveal("bag.detail.recent.3.viewOnRange", in: app)
        view.tap()

        let position = element("range.position", in: app)
        XCTAssertTrue(position.waitForExistence(timeout: 10))
        wait(for: position, label: "3 / 3")
        assertCarry("251", in: app)
        XCTAssertEqual(app.buttons["range.playPause"].label, "Play")
        assertFullScreen(app)
    }

    /// On a mock Pi the range offers Simulate; each tap flies a new, different shot live.
    func testSimulateInPreviewMockFliesANewShot() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-pi-mock", "--range-mode"]
        app.launch()

        let simulate = app.buttons["range.simulate"]
        XCTAssertTrue(simulate.waitForExistence(timeout: 15))
        XCTAssertTrue(element("range.readyCard", in: app).exists)

        simulate.tap()
        assertCarry("165", in: app, timeout: 10)
        XCTAssertFalse(element("range.readyCard", in: app).exists)

        simulate.tap()
        assertCarry("281", in: app, timeout: 10)
        XCTAssertFalse(element("range.simulateError", in: app).exists)
    }

    /// Without a mock Pi there's no Simulate button.
    func testNoSimulateWithoutAMockPi() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot", "--range-mode"]
        app.launch()
        XCTAssertTrue(app.buttons["range.history"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["range.simulate"].exists)
    }

    /// The status pill keeps a readable width, on one line beside the buttons or on its own line
    /// under them, with Follow, History and Replay all showing, at the default and the largest
    /// non-accessibility text size (it used to wrap to three lines on a tall iPhone).
    func testStatusPillStaysReadableWithEveryControl() {
        for size in ["UICTContentSizeCategoryL", "UICTContentSizeCategoryXXXL"] {
            let app = XCUIApplication()
            app.launchArguments = [
                "--ui-testing", "--preview-shot", "--range-mode",
                "-UIPreferredContentSizeCategoryName", size,
            ]
            app.launch()
            XCTAssertTrue(app.buttons["range.replay"].waitForExistence(timeout: 15), size)

            let status = element("range.status", in: app).frame
            XCTAssertGreaterThan(status.height, 0, size)
            XCTAssertGreaterThanOrEqual(status.width, 2 * status.height, "\(size): squeezed \(status)")
            for control in ["range.exit", "range.cameraMode", "range.history", "range.replay"] {
                let frame = element(control, in: app).frame
                XCTAssertFalse(status.intersects(frame), "\(size): the status overlaps \(control)")
            }
            // Clear of the metrics under the controls.
            XCTAssertFalse(status.intersects(element("range.carry", in: app).frame), "\(size): the status overlaps carry")
            app.terminate()
        }
    }

    // MARK: Helpers

    private func element(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    /// Scrolls the list until the button `identifier` is on screen and tappable, and returns it.
    @discardableResult
    private func reveal(
        _ identifier: String,
        in app: XCUIApplication,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> XCUIElement {
        let element = app.buttons[identifier]
        // A lazy list only has the rows on screen, so scroll until the row exists and is tappable.
        // On an iPad the list and the detail are separate lists side by side: scroll the one holding
        // the element (or, before it exists, the trailing detail list).
        _ = element.waitForExistence(timeout: 5)
        var swipes = 0
        while !(element.exists && element.isHittable), swipes < 8 {
            let holding = app.collectionViews.containing(NSPredicate(format: "identifier == %@", identifier)).firstMatch
            let list = holding.exists
                ? holding
                : app.collectionViews.allElementsBoundByIndex.max { $0.frame.minX < $1.frame.minX } ?? app.collectionViews.firstMatch
            list.swipeUp(velocity: .slow)
            swipes += 1
        }
        XCTAssertTrue(element.isHittable, "\(identifier) not tappable", file: file, line: line)
        return element
    }

    /// The range covers the whole app: no tab bar, and on an iPad no sidebar.
    private func assertFullScreen(_ app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(app.buttons["range.exit"].isHittable, file: file, line: line)
        XCTAssertFalse(app.tabBars.firstMatch.isHittable, "tab bar showing", file: file, line: line)
        let sidebar = element("app.nav.sidebar.practice", in: app)
        XCTAssertFalse(sidebar.exists && sidebar.isHittable, "sidebar showing", file: file, line: line)
    }

    private func wait(
        for element: XCUIElement,
        label: String,
        timeout: TimeInterval = 10,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let deadline = Date().addingTimeInterval(timeout)
        while element.label != label, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        XCTAssertEqual(element.label, label, file: file, line: line)
    }

    private func assertCarry(_ yards: String, in app: XCUIApplication, timeout: TimeInterval = 5) {
        let carry = element("range.carry", in: app)
        expectation(for: NSPredicate(format: "label CONTAINS %@", yards), evaluatedWith: carry)
        waitForExpectations(timeout: timeout)
    }
}
