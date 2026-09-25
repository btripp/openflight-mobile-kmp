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

        // VoiceOver reads the value with its unit (the shared `SessionStatTile.spokenValue`).
        XCTAssertEqual(stat(app, "Shots").value as? String, "1")
        XCTAssertEqual(stat(app, "Avg Ball (mph)").value as? String, "151.4 mph")
        XCTAssertEqual(stat(app, "Avg Carry (yds)").value as? String, "264 yds")
        XCTAssertEqual(stat(app, "Avg Smash").value as? String, "1.47")
        XCTAssertEqual(stat(app, "Min Ball (mph)").value as? String, "151.4 mph")
        // One shot has no spread: "—", read as "not available".
        XCTAssertEqual(stat(app, "Ball Std Dev (mph)").value as? String, "not available")
        XCTAssertTrue(app.buttons["session.tab.all"].exists)
        XCTAssertTrue(app.buttons["session.tab.driver"].exists)
        XCTAssertTrue(Self.reveal(app.descendants(matching: .any)[Self.previewShotRow], in: app))
        // Not a --mock Pi: no Simulate Shot.
        XCTAssertFalse(app.buttons["session.simulate"].exists)
    }

    func testPreviewShotIsOnTheDispersionChartAndItsRowSelectsIt() {
        let app = Self.openSession()

        let chart = app.descendants(matching: .any)["session.dispersion"]
        XCTAssertTrue(chart.waitForExistence(timeout: 5))
        XCTAssertTrue(chart.label.contains("1 shot: Driver"), "chart: \(chart.label)")

        let row = app.descendants(matching: .any)[Self.previewShotRow]
        XCTAssertTrue(Self.reveal(row, in: app))
        row.tap()

        // The card sits under the club chips, above where the list has scrolled to.
        let card = app.descendants(matching: .any)["session.selected"]
        XCTAssertTrue(Self.reveal(card, in: app, scrollingUp: true))
        XCTAssertTrue(app.staticTexts["Shot 1 · Driver"].exists)
        app.buttons["session.selected.close"].tap()
        XCTAssertTrue(card.waitForNonExistence(timeout: 5))
    }

    /// Plan R8f: VoiceOver reads the chart per club, and the selected dot as the chart's value.
    func testTheChartIsSummarisedPerClubAndItsSelectionIsItsValue() {
        let app = Self.openSession()

        let chart = app.descendants(matching: .any)["session.dispersion"]
        XCTAssertTrue(chart.waitForExistence(timeout: 5))
        XCTAssertTrue(chart.label.contains("Driver: 1 shot, 264 yds average carry"), "chart: \(chart.label)")
        XCTAssertEqual(chart.value as? String, "No shot selected")

        let row = app.descendants(matching: .any)[Self.previewShotRow]
        XCTAssertTrue(Self.reveal(row, in: app))
        row.tap()

        XCTAssertTrue(Self.reveal(chart, in: app, scrollingUp: true))
        let value = chart.value as? String ?? ""
        XCTAssertTrue(value.hasPrefix("Shot 1, Driver, 264 yds carry"), "value: \(value)")
    }

    /// Plan R8f: a delete asks first, then says it's done.
    func testDeleteOnTheShotCardAsksThenRemovesTheShot() {
        let app = Self.openSession()
        let row = app.descendants(matching: .any)[Self.previewShotRow]
        XCTAssertTrue(Self.reveal(row, in: app))
        row.tap()
        let delete = app.buttons["session.selected.delete"]
        XCTAssertTrue(Self.reveal(delete, in: app, scrollingUp: true))

        delete.tap()

        XCTAssertTrue(app.staticTexts["Delete shot #1?"].waitForExistence(timeout: 5))
        Self.confirmDialog(app)
        XCTAssertTrue(app.staticTexts["No shots recorded yet"].waitForExistence(timeout: 5))
        let done = app.descendants(matching: .any)["session.action.done"]
        XCTAssertTrue(Self.reveal(done, in: app, scrollingUp: true))
        app.buttons["session.action.dismiss"].tap()
        XCTAssertTrue(done.waitForNonExistence(timeout: 5))
    }

    func testSwipeToDeleteAsksAndCancelKeepsTheRow() {
        let app = Self.openSession()

        let row = app.descendants(matching: .any)[Self.previewShotRow]
        XCTAssertTrue(Self.reveal(row, in: app))
        row.swipeLeft()
        let delete = app.buttons["Delete"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        delete.tap()

        XCTAssertTrue(app.staticTexts["Delete shot #1?"].waitForExistence(timeout: 5))
        Self.cancelDialog(app)
        XCTAssertTrue(Self.reveal(row, in: app))

        row.swipeLeft()
        XCTAssertTrue(app.buttons["Delete"].waitForExistence(timeout: 5))
        app.buttons["Delete"].tap()
        Self.confirmDialog(app)
        XCTAssertTrue(app.staticTexts["No shots recorded yet"].waitForExistence(timeout: 5))
        XCTAssertFalse(row.exists)
    }

    func testClearAsksForConfirmationAndCancelKeepsTheShots() {
        let app = Self.openSession()

        let clear = app.buttons["session.clear"]
        XCTAssertTrue(Self.reveal(clear, in: app))
        clear.tap()

        XCTAssertTrue(app.staticTexts["Clear session?"].waitForExistence(timeout: 5))
        XCTAssertTrue(
            app.staticTexts["This session's list on this phone is emptied. Saved sessions stay in History."].exists
        )
        Self.cancelDialog(app)
        XCTAssertTrue(Self.reveal(app.descendants(matching: .any)[Self.previewShotRow], in: app))

        clear.tap()
        XCTAssertTrue(app.staticTexts["Clear session?"].waitForExistence(timeout: 5))
        Self.confirmDialog(app)
        XCTAssertTrue(app.staticTexts["No shots recorded yet"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["This phone's list is cleared."].waitForExistence(timeout: 5))
    }

    /// Plan R8e: over Bluetooth (a read-and-select link) delete and clear are disabled with a reason.
    func testOverBluetoothClearIsDisabledWithAWifiOnlyReason() {
        let app = Self.openSession(transport: "bluetooth")

        let clear = app.buttons["session.clear"]
        XCTAssertTrue(Self.reveal(clear, in: app))
        XCTAssertFalse(clear.isEnabled)
        XCTAssertTrue(app.descendants(matching: .any)["session.edit.disabledReason"].exists)

        let row = app.descendants(matching: .any)[Self.previewShotRow]
        XCTAssertTrue(Self.reveal(row, in: app))
        row.swipeLeft()
        XCTAssertFalse(app.buttons["Delete"].waitForExistence(timeout: 2))
        XCTAssertTrue(row.exists)
    }

    /// Over Bluetooth a shot can still be selected on the chart, but its card can't delete it.
    func testOverBluetoothTheShotCardsDeleteIsDisabled() {
        let app = Self.openSession(transport: "bluetooth")
        let row = app.descendants(matching: .any)[Self.previewShotRow]
        XCTAssertTrue(Self.reveal(row, in: app))
        row.tap()

        let delete = app.buttons["session.selected.delete"]
        XCTAssertTrue(Self.reveal(delete, in: app, scrollingUp: true))
        XCTAssertFalse(delete.isEnabled)
    }

    /// The dispersion chart sits above the list, so on smaller phones the actions and rows start
    /// below the fold (and a `List` only creates the rows it shows): scroll until [element] exists.
    static func reveal(_ element: XCUIElement, in app: XCUIApplication, scrollingUp: Bool = false) -> Bool {
        for _ in 0..<6 where !element.exists {
            if scrollingUp { app.swipeDown() } else { app.swipeUp() }
        }
        return element.waitForExistence(timeout: 5)
    }

    /// Delete and clear need Wi-Fi (plan R8e), so these tests pin the transport rather than
    /// inheriting whatever an earlier run saved.
    static func openSession(transport: String = "wifi", extraArguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot", "--transport", transport] + extraArguments
        app.launch()
        let tab = app.tabBars.buttons["Session"]
        XCTAssertTrue(tab.waitForExistence(timeout: 10))
        tab.tap()
        return app
    }

    /// Taps the open confirmation dialog's destructive button.
    static func confirmDialog(_ app: XCUIApplication) {
        let confirm = app.buttons["session.action.confirm"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        confirm.tap()
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
