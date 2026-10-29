// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Issue #42: the Session screen at the largest accessibility text size, the iOS side of Android's
/// `SessionAccessibilityTest.given200PercentText_whenShown_thenStatsRowsAndActionsAreNotClipped`.
/// The stat tiles, the club chips and a failed action's banner have to stay on screen and readable.
///
/// Each test attaches a screenshot, so the layout can be checked by eye as well.
final class SessionLargeTextUITests: XCTestCase {
    private static let largestText = "UICTContentSizeCategoryAccessibilityXXXL"

    override func setUp() {
        continueAfterFailure = false
    }

    func testStatTilesAndClubChipsFitAtTheLargestText() {
        // A driver and a 7-iron, so there's a club chip for each (the Android test's "7-Iron").
        let app = SessionUITests.openSession(
            extraArguments: ["--preview-pi-session", "-UIPreferredContentSizeCategoryName", Self.largestText]
        )
        XCTAssertTrue(app.descendants(matching: .any)["session.source"].waitForExistence(timeout: 10))
        attachScreenshot(app, named: "session-largest-text")

        // The list is lazy: the chips below the fold aren't there until they're scrolled to.
        let ironChip = app.buttons["session.tab.7-iron"]
        XCTAssertTrue(SessionUITests.reveal(ironChip, in: app), "no 7-Iron chip")
        // The chips scroll sideways, so a chip can start past the edge; it must still read left to
        // right on one line, not stacked a few letters per line.
        XCTAssertGreaterThanOrEqual(ironChip.frame.width, ironChip.frame.height, "7-Iron chip stacked: \(ironChip.frame)")

        let window = app.windows.firstMatch.frame
        for label in ["Ball Std Dev (mph)", "Avg Carry (yds)", "Min Ball (mph)"] {
            let tile = app.descendants(matching: .any)["session.stat.\(label)"]
            XCTAssertTrue(SessionUITests.reveal(tile, in: app), "\(label) isn't shown")
            assertWithinWidth(tile.frame, of: window, label)
        }
    }

    func testExportAndClearReadWholeAtTheLargestText() {
        let app = SessionUITests.openSession(
            extraArguments: ["--preview-pi-session", "-UIPreferredContentSizeCategoryName", Self.largestText]
        )
        let export = app.descendants(matching: .any)["session.export"]
        let clear = app.buttons["session.clear"]
        XCTAssertTrue(scrollUntilHittable(clear, in: app), "no Clear button")
        XCTAssertTrue(export.exists, "no Export CSV button")
        attachScreenshot(app, named: "session-export-clear-largest-text")

        let window = app.windows.firstMatch.frame
        assertWithinWidth(export.frame, of: window, "Export CSV")
        assertWithinWidth(clear.frame, of: window, "Clear")
        // Each took half the row and broke mid-word ("C" / "lear", "E" / "xpor" / "t" / "CSV"):
        // at this size they stack, each with the full width.
        XCTAssertLessThanOrEqual(
            export.frame.maxY,
            clear.frame.minY,
            "Export CSV and Clear share a row: \(export.frame), \(clear.frame)"
        )
        // Full width wasn't enough with the icon: "Exp" / "ort CSV". A label that wraps makes its
        // button taller than the one-line Clear.
        XCTAssertLessThanOrEqual(
            export.frame.height,
            clear.frame.height * 1.2,
            "Export CSV wraps onto more lines than Clear: \(export.frame), \(clear.frame)"
        )

        // A shot row reads as one VoiceOver element, so its text can't be measured from here;
        // the screenshot shows the club, time and metrics for a check by eye.
        let row = app.descendants(matching: .any)[SessionPiActionsUITests.driver]
        XCTAssertTrue(SessionUITests.reveal(row, in: app), "no driver row")
        attachScreenshot(app, named: "session-shot-row-largest-text")
    }

    func testAFailedActionsBannerFitsAtTheLargestText() {
        // The stuck Pi never confirms, so the clear fails with a title, a reason and Try again.
        let app = SessionUITests.openSession(
            extraArguments: ["--preview-pi-session-stuck", "-UIPreferredContentSizeCategoryName", Self.largestText]
        )
        let clear = app.buttons["session.clear"]
        XCTAssertTrue(SessionUITests.reveal(clear, in: app), "no Clear button")
        clear.tap()
        SessionUITests.confirmDialog(app)

        let failed = app.descendants(matching: .any)["session.action.failed"]
        // The banner sits above the list, which is scrolled down to Clear at this size, and a lazy
        // list hasn't loaded it there. Scroll back to the top (the source card is always there),
        // then wait out the Pi's 10 s confirmation window.
        let source = app.descendants(matching: .any)["session.source"]
        XCTAssertTrue(SessionUITests.reveal(source, in: app, scrollingUp: true), "can't scroll back to the top")
        XCTAssertTrue(failed.waitForExistence(timeout: 20), "the clear never failed")
        attachScreenshot(app, named: "session-failed-action-largest-text")

        let window = app.windows.firstMatch.frame
        for text in ["Clear not confirmed", "The Pi didn't confirm the clear."] {
            let element = app.staticTexts[text]
            XCTAssertTrue(element.exists, "\"\(text)\" isn't shown")
            assertWithinWidth(element.frame, of: window, text)
        }
        // At this size the banner is taller than the screen, so its buttons start below the fold;
        // they have to be reachable by scrolling, as Android's test scrolls to each node.
        let retry = app.buttons["session.action.retry"]
        let dismiss = app.buttons["session.action.dismiss"]
        for button in [retry, dismiss] {
            XCTAssertTrue(scrollUntilHittable(button, in: app), "\(button.identifier) not tappable")
            assertWithinWidth(button.frame, of: window, button.identifier)
        }
        attachScreenshot(app, named: "session-failed-action-buttons-largest-text")
        // Side by side, "Try again" was cut to "Try a…" (the label still reads in full, so the
        // layout is what's checked): at this size the buttons stack, like Android's FlowRow wraps.
        XCTAssertLessThanOrEqual(
            retry.frame.maxY,
            dismiss.frame.minY,
            "Try again and OK share a row: \(retry.frame), \(dismiss.frame)"
        )
    }

    // MARK: Helpers

    /// One swipe at a time: `SessionUITests.makeHittable` swipes twice on an iPhone, which scrolls
    /// a banner near the top of the list past the top again.
    private func scrollUntilHittable(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        for _ in 0 ..< 4 where !(element.exists && element.isHittable) {
            app.swipeUp()
        }
        return element.exists && element.isHittable
    }

    private func assertWithinWidth(
        _ frame: CGRect,
        of window: CGRect,
        _ name: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertGreaterThanOrEqual(frame.minX, window.minX, "\(name) runs off the left: \(frame)", file: file, line: line)
        XCTAssertLessThanOrEqual(frame.maxX, window.maxX, "\(name) runs off the right: \(frame)", file: file, line: line)
    }

    private func attachScreenshot(_ app: XCUIApplication, named name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
