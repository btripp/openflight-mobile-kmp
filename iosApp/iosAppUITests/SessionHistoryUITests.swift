// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Session history tests (plans R8h, R8f). `--preview-history` swaps in an in-memory history with
/// two stored sessions: today's Wi-Fi session (the current one, Ann and Bo, 3 shots) and an older
/// one-shot Bluetooth session. Its deletes and "clear all" land after 2 s, so the pending state
/// shows; with `--preview-history-stuck` they never land and the screens report it.
final class SessionHistoryUITests: XCTestCase {
    static let current = "session.history.session.preview-current"
    static let older = "session.history.session.preview-older"

    override func setUp() {
        continueAfterFailure = false
    }

    func testTheListShowsEachSessionsDayTimesSourceAndTheCurrentBadge() {
        let app = Self.openHistory()

        let current = app.descendants(matching: .any)[Self.current]
        XCTAssertTrue(current.waitForExistence(timeout: 5))
        // One VoiceOver stop per row, spelled out (the shared `accessibilityLabel`).
        XCTAssertTrue(
            current.label.contains(
                "Friday 25 September 2026, 10:03 to 10:45, 3 shots, Wi-Fi, raspberrypi.local:8080, current session"
            ),
            "row: \(current.label)"
        )
        let older = app.descendants(matching: .any)[Self.older]
        XCTAssertTrue(older.label.contains("Monday 21 September 2026, 18:12, 1 shot, Bluetooth"), "row: \(older.label)")
    }

    func testTheDetailFiltersByProfileAndShowsHowItWasRecorded() {
        let app = Self.openHistory()
        app.descendants(matching: .any)[Self.current].tap()

        let source = app.staticTexts["session.history.detail.source"]
        XCTAssertTrue(source.waitForExistence(timeout: 5))
        XCTAssertEqual(source.label, "Wi-Fi · raspberrypi.local:8080")
        XCTAssertTrue(app.descendants(matching: .any)["session.history.current"].exists)
        XCTAssertEqual(stat(app, "Shots").value as? String, "3")

        app.buttons["session.history.profile.ann"].tap()

        XCTAssertTrue(app.buttons["session.history.profile.ann"].isSelected)
        XCTAssertTrue(waitForValue(stat(app, "Shots"), "2"))
        XCTAssertFalse(app.descendants(matching: .any)["session.shot.2026-09-25T10:45:12.100000"].exists)
    }

    func testClearAllAsksThenShowsPendingThenDone() {
        let app = Self.openHistory()
        let clear = app.buttons["session.history.clearAll"]
        XCTAssertTrue(clear.waitForExistence(timeout: 5))

        clear.tap()
        XCTAssertTrue(app.staticTexts["Clear all history?"].waitForExistence(timeout: 5))
        SessionUITests.confirmDialog(app)

        XCTAssertTrue(app.descendants(matching: .any)["session.action.pending"].waitForExistence(timeout: 2))
        XCTAssertFalse(clear.isEnabled)
        XCTAssertTrue(app.descendants(matching: .any)["session.action.done"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["History cleared."].exists)
        XCTAssertTrue(app.descendants(matching: .any)["session.history.empty"].exists)
    }

    func testAStoredShotDeleteAsksThenShowsPendingThenDone() {
        let app = Self.openHistory()
        app.descendants(matching: .any)[Self.older].tap()
        let row = app.descendants(matching: .any)["session.shot.2026-09-21T18:12:05.000000"]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        // The row starts under the floating tab bar, which takes horizontal swipes itself (it
        // switches tabs): scroll it up into the open first.
        app.swipeUp()
        app.swipeUp()
        XCTAssertTrue(row.isHittable)

        row.swipeLeft()
        XCTAssertTrue(app.buttons["Delete"].waitForExistence(timeout: 5))
        app.buttons["Delete"].tap()
        XCTAssertTrue(app.staticTexts["Delete shot #1?"].waitForExistence(timeout: 5))
        SessionUITests.confirmDialog(app)

        XCTAssertTrue(app.descendants(matching: .any)["session.action.pending"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.staticTexts["Shot #1 deleted."].waitForExistence(timeout: 10))
    }

    /// Storage that never reflects the change: the failure says so and offers a retry.
    func testAClearAllTheStoreNeverReflectsFailsWithRetry() {
        let app = Self.openHistory(stuck: true)
        let clear = app.buttons["session.history.clearAll"]
        XCTAssertTrue(clear.waitForExistence(timeout: 5))
        clear.tap()
        SessionUITests.confirmDialog(app)

        XCTAssertTrue(app.descendants(matching: .any)["session.action.failed"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["Couldn't clear history"].exists)
        XCTAssertTrue(app.buttons["session.action.retry"].exists)
        app.buttons["session.action.dismiss"].tap()
        XCTAssertTrue(app.descendants(matching: .any)[Self.current].exists)
    }

    static func openHistory(stuck: Bool = false) -> XCUIApplication {
        let app = SessionUITests.openSession(extraArguments: [stuck ? "--preview-history-stuck" : "--preview-history"])
        let open = app.buttons["session.history.open"]
        XCTAssertTrue(open.waitForExistence(timeout: 5))
        open.tap()
        return app
    }

    private func stat(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        app.descendants(matching: .any)["session.stat.\(label)"]
    }

    private func waitForValue(_ element: XCUIElement, _ value: String) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", value), object: element)
        return XCTWaiter.wait(for: [expectation], timeout: 5) == .completed
    }
}
