// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Issue #81: at an accessibility text size (AX3) a screen header built as a title and its buttons
/// on one row squeezed the title into a narrow column, so "Launch Monitor" and "Conditions" broke
/// mid-word, the Range label stacked one letter per line and the Conditions pill cut down to "M…".
/// At those sizes the header now puts its buttons under the title.
final class HeaderLargeTextUITests: XCTestCase {
    private static let ax3 = "UICTContentSizeCategoryAccessibilityXXXL"

    override func setUp() {
        continueAfterFailure = false
    }

    func testPracticeHeaderKeepsTheTitleAndRangeButtonWholeAtAX3() {
        let app = AppNav.launch(["--ui-testing", "--preview-shot", "-UIPreferredContentSizeCategoryName", Self.ax3])
        let range = app.buttons["dashboard.range"]
        XCTAssertTrue(range.waitForExistence(timeout: 10))
        let title = app.staticTexts["Launch Monitor"]
        XCTAssertTrue(title.exists)
        let window = app.windows.firstMatch.frame
        attachScreenshot(app, named: "practice-ax3")

        let titleFrame = title.frame
        let rangeFrame = range.frame
        print("#81 window \(window) title \(titleFrame) range \(rangeFrame)")
        // Whole words: at AX3 (the same point size on every device) a line of the title is about
        // 80 pt tall and "Monitor" about 204 pt wide. Broken mid-word it was four lines (318 pt)
        // in a 127 pt column; whole, it's at most two lines ("Launch" / "Monitor").
        XCTAssertGreaterThan(titleFrame.width, 180, "title squeezed: \(titleFrame)")
        XCTAssertLessThan(titleFrame.height, 240, "title broken over too many lines: \(titleFrame)")
        // The Range button reads left to right on one line, inside the screen.
        XCTAssertGreaterThanOrEqual(rangeFrame.width, rangeFrame.height, "Range label stacked: \(rangeFrame)")
        XCTAssertGreaterThanOrEqual(rangeFrame.minX, window.minX)
        XCTAssertLessThanOrEqual(rangeFrame.maxX, window.maxX)
        XCTAssertFalse(rangeFrame.intersects(titleFrame), "Range overlaps the title")

        // The latest shot card's header: "View on range" on whole-word lines beside its icon,
        // not "Vie" / "w on" / "rang" / "e" in a column next to the club.
        let viewOnRange = app.buttons["dashboard.viewOnRange"]
        for _ in 0 ..< 8 where !(viewOnRange.exists && viewOnRange.isHittable) { app.swipeUp() }
        XCTAssertTrue(viewOnRange.isHittable)
        let viewFrame = viewOnRange.frame
        print("#81 viewOnRange \(viewFrame)")
        XCTAssertGreaterThanOrEqual(viewFrame.width, viewFrame.height, "View on range stacked: \(viewFrame)")
    }

    func testBagConditionsHeaderKeepsTheTitleAndPillWholeAtAX3() {
        let app = AppNav.launch(["--ui-testing", "--preview-shot", "-UIPreferredContentSizeCategoryName", Self.ax3])
        AppNav.open(.bag, in: app)
        let card = app.descendants(matching: .any)["bag.conditions"]
        XCTAssertTrue(card.waitForExistence(timeout: 10))
        let title = card.staticTexts["Conditions"]
        let pill = card.staticTexts["Manual"]
        let edit = card.buttons["bag.conditions.edit"]
        XCTAssertTrue(title.exists)
        XCTAssertTrue(pill.exists)
        XCTAssertTrue(edit.exists)
        attachScreenshot(app, named: "bag-ax3")

        let titleFrame = title.frame
        let pillFrame = pill.frame
        print("#81 conditions title \(titleFrame) pill \(pillFrame) edit \(edit.frame)")
        // "Conditions" on one line: wider than it is tall by a margin, not two stacked halves.
        XCTAssertGreaterThan(titleFrame.width, titleFrame.height * 3, "Conditions broken: \(titleFrame)")
        // "Manual" in full, not "M…".
        XCTAssertGreaterThan(pillFrame.width, pillFrame.height * 2, "pill truncated: \(pillFrame)")
    }

    private func attachScreenshot(_ app: XCUIApplication, named name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
