// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Session screen tests (plans R5b/R6c). `--ui-testing --preview-shot` holds the reference's
/// preview shot (driver, 151.4 mph, 264 yds) in a phone-local history; the Pi's live session never
/// starts, so the screen shows the phone's own shots ("This phone").
final class SessionUITests: XCTestCase {
    static let previewShotRow = "session.shot.B0D91F0A-7950-4D7E-9DD5-AF9777C190E1"

    override func setUp() {
        continueAfterFailure = false
    }

    func testPreviewShotShowsStatsAndARow() {
        let app = Self.openSession()

        let source = app.descendants(matching: .any)["session.source"]
        XCTAssertTrue(source.waitForExistence(timeout: 5))
        XCTAssertTrue(source.label.contains("This phone"), "source: \(source.label)")

        XCTAssertEqual(stat(app, "Shots").value as? String, "1")
        XCTAssertEqual(stat(app, "Avg Ball (mph)").value as? String, "151.4")
        XCTAssertEqual(stat(app, "Avg Carry (yds)").value as? String, "264")
        XCTAssertEqual(stat(app, "Avg Smash").value as? String, "1.47")
        XCTAssertTrue(app.buttons["session.tab.all"].exists)
        XCTAssertTrue(app.buttons["session.tab.driver"].exists)
        XCTAssertTrue(app.descendants(matching: .any)[Self.previewShotRow].exists)
        // Not a --mock Pi: no Simulate Shot.
        XCTAssertFalse(app.buttons["session.simulate"].exists)
    }

    func testSwipeToDeleteRemovesTheRow() {
        let app = Self.openSession()

        let row = app.descendants(matching: .any)[Self.previewShotRow]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.swipeLeft()
        let delete = app.buttons["Delete"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        delete.tap()

        XCTAssertTrue(app.staticTexts["No shots recorded yet"].waitForExistence(timeout: 5))
        XCTAssertFalse(row.exists)
    }

    func testClearAsksForConfirmationAndCancelKeepsTheShots() {
        let app = Self.openSession()

        let clear = app.buttons["session.clear"]
        XCTAssertTrue(clear.waitForExistence(timeout: 5))
        clear.tap()

        XCTAssertTrue(app.staticTexts["Clear session?"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Every shot is removed from this phone."].exists)
        Self.cancelDialog(app)
        XCTAssertTrue(app.descendants(matching: .any)[Self.previewShotRow].waitForExistence(timeout: 5))

        clear.tap()
        XCTAssertTrue(app.staticTexts["Clear session?"].waitForExistence(timeout: 5))
        app.buttons["session.clear.confirm"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["No shots recorded yet"].waitForExistence(timeout: 5))
    }

    /// Plan R8e: over Bluetooth (a read-and-select link) delete and clear are disabled with a reason.
    func testOverBluetoothClearIsDisabledWithAWifiOnlyReason() {
        let app = Self.openSession(transport: "bluetooth")

        let clear = app.buttons["session.clear"]
        XCTAssertTrue(clear.waitForExistence(timeout: 5))
        XCTAssertFalse(clear.isEnabled)
        XCTAssertTrue(app.descendants(matching: .any)["session.edit.disabledReason"].exists)

        let row = app.descendants(matching: .any)[Self.previewShotRow]
        row.swipeLeft()
        XCTAssertFalse(app.buttons["Delete"].waitForExistence(timeout: 2))
        XCTAssertTrue(row.exists)
    }

    /// Delete and clear need Wi-Fi (plan R8e), so these tests pin the transport rather than
    /// inheriting whatever an earlier run saved.
    static func openSession(transport: String = "wifi") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot", "--transport", transport]
        app.launch()
        let tab = app.tabBars.buttons["Session"]
        XCTAssertTrue(tab.waitForExistence(timeout: 10))
        tab.tap()
        return app
    }

    /// Cancels the open confirmation dialog: its Cancel button where the system shows one, or a
    /// tap outside where iOS presents the dialog as a popover without one (iOS 26).
    static func cancelDialog(_ app: XCUIApplication) {
        let cancel = app.sheets.buttons["Cancel"]
        if cancel.exists {
            cancel.tap()
        } else {
            let outside = app.otherElements["PopoverDismissRegion"]
            XCTAssertTrue(outside.waitForExistence(timeout: 5))
            outside.tap()
        }
    }

    private func stat(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        app.descendants(matching: .any)["session.stat.\(label)"]
    }
}
