// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Server-confirmed delete and clear on the live Session screen (plan R8f). `--preview-pi-session`
/// swaps in a connected Pi with two profiles (Ann active: a driver #1 and a 7-iron #2; the Pi's club
/// is the driver) that answers
/// a delete or clear after 4 s; with `--preview-pi-session-stuck` it never answers.
final class SessionPiActionsUITests: XCTestCase {
    static let driver = "session.shot.2026-09-25T11:00:00.000000"

    override func setUp() {
        continueAfterFailure = false
    }

    func testAPiDeleteAsksThenIsPendingWithEditingOffThenDone() {
        let app = Self.openPiSession()
        let source = app.descendants(matching: .any)["session.source"]
        XCTAssertTrue(source.waitForExistence(timeout: 5))
        XCTAssertTrue(source.label.contains("Ann's shots and stats"), "source: \(source.label)")

        // The Pi's club is the driver, so the tabs open on it (Expo `stats.tsx`).
        XCTAssertTrue(app.buttons["session.tab.driver"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["session.tab.driver"].isSelected)
        let row = app.descendants(matching: .any)[Self.driver]
        XCTAssertTrue(SessionUITests.reveal(row, in: app))
        row.tap()
        let delete = app.buttons["session.selected.delete"]
        XCTAssertTrue(SessionUITests.reveal(delete, in: app, scrollingUp: true))
        delete.tap()

        XCTAssertTrue(app.staticTexts["Delete shot #1?"].waitForExistence(timeout: 5))
        XCTAssertTrue(
            app.staticTexts["This Driver shot is removed from the Pi's session and from this phone."].exists
        )
        SessionUITests.confirmDialog(app)

        // Still on screen until the Pi answers, and off meanwhile: no second request.
        XCTAssertTrue(delete.waitForExistence(timeout: 2))
        XCTAssertFalse(delete.isEnabled, "Delete stays off while the delete is pending")
        let pending = app.descendants(matching: .any)["session.action.pending"]
        XCTAssertTrue(SessionUITests.reveal(pending, in: app, scrollingUp: true))
        XCTAssertTrue(app.staticTexts["Deleting shot #1…"].exists)

        XCTAssertTrue(app.staticTexts["Shot #1 deleted."].waitForExistence(timeout: 10))
        XCTAssertFalse(app.descendants(matching: .any)[Self.driver].exists)
    }

    func testAPiClearTheServerNeverConfirmsFailsWithTryAgain() {
        let app = Self.openPiSession(stuck: true)
        let clear = app.buttons["session.clear"]
        XCTAssertTrue(SessionUITests.reveal(clear, in: app))
        clear.tap()

        XCTAssertTrue(app.staticTexts["Clear Ann's session?"].waitForExistence(timeout: 5))
        SessionUITests.confirmDialog(app)

        // The outcome sits at the top of the screen; the Pi's 10 s confirmation window runs out.
        let pending = app.descendants(matching: .any)["session.action.pending"]
        XCTAssertTrue(SessionUITests.reveal(pending, in: app, scrollingUp: true))
        XCTAssertTrue(app.staticTexts["Clearing Ann's session…"].exists)
        let failed = app.descendants(matching: .any)["session.action.failed"]
        XCTAssertTrue(failed.waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Clear not confirmed"].exists)
        XCTAssertTrue(app.staticTexts["The Pi didn't confirm the clear."].exists)
        XCTAssertTrue(app.buttons["session.action.retry"].exists)
        app.buttons["session.action.dismiss"].tap()
        XCTAssertTrue(failed.waitForNonExistence(timeout: 5))
    }

    static func openPiSession(stuck: Bool = false) -> XCUIApplication {
        SessionUITests.openSession(extraArguments: [stuck ? "--preview-pi-session-stuck" : "--preview-pi-session"])
    }
}
