// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Driving range smoke tests (plan R3b), ported from the reference `DrivingRangeUITests.swift`.
/// `--ui-testing --preview-shot` swaps in the shared `PreviewShotRepository` (Connected, holding
/// the reference's preview shot: driver, 151.4 mph, 264 yds).
final class DrivingRangeUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testEntersRangeShowsMetricsAndExits() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot"]
        app.launch()

        let rangeButton = app.buttons["dashboard.range"]
        XCTAssertTrue(rangeButton.waitForExistence(timeout: 10))
        rangeButton.tap()

        XCTAssertTrue(app.buttons["range.exit"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.descendants(matching: .any)["range.ballSpeed"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["range.carry"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["range.clubSelector"].exists)
        // The shot on screen when the range opens is shown without flying it.
        XCTAssertFalse(app.descendants(matching: .any)["range.readyCard"].exists)

        app.buttons["range.exit"].tap()
        XCTAssertTrue(rangeButton.waitForExistence(timeout: 5))
    }

    /// Plan R7b: the camera button flips Follow/Fixed, and the choice (shared settings, like
    /// Android's) is still there after leaving the range and coming back. Restores what it found,
    /// since the setting persists across launches.
    func testCameraToggleFlipsAndPersistsAcrossLeavingTheRange() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot"]
        app.launch()

        let rangeButton = app.buttons["dashboard.range"]
        XCTAssertTrue(rangeButton.waitForExistence(timeout: 10))
        rangeButton.tap()

        let toggle = app.buttons["range.cameraMode"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        XCTAssertTrue(toggle.isEnabled)
        let initial = toggle.label.contains("Follow") ? "Follow" : "Fixed"
        let flipped = initial == "Follow" ? "Fixed" : "Follow"
        XCTAssertTrue(toggle.label.contains(initial), "toggle label: \(toggle.label)")

        toggle.tap()
        expectation(for: NSPredicate(format: "label CONTAINS %@", flipped), evaluatedWith: toggle)
        waitForExpectations(timeout: 5)

        // Leave and come back: a new range screen (and ViewModel) reads the stored choice.
        app.buttons["range.exit"].tap()
        XCTAssertTrue(rangeButton.waitForExistence(timeout: 5))
        rangeButton.tap()
        let again = app.buttons["range.cameraMode"]
        XCTAssertTrue(again.waitForExistence(timeout: 5))
        expectation(for: NSPredicate(format: "label CONTAINS %@", flipped), evaluatedWith: again)
        waitForExpectations(timeout: 5)

        // Restore.
        again.tap()
        expectation(for: NSPredicate(format: "label CONTAINS %@", initial), evaluatedWith: again)
        waitForExpectations(timeout: 5)
    }

    func testRangeModeWithoutAShotShowsTheReadyCard() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--range-mode"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Driving Range Ready"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Hit a shot and its flight will appear here."].exists)
        XCTAssertTrue(app.staticTexts["Ready for the next shot"].exists)
        XCTAssertFalse(app.buttons["range.replay"].exists)
    }

    func testPreviewFlightFliesTheShotAndShowsItsMetrics() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot", "--range-mode", "--preview-flight"]
        app.launch()

        // --range-mode opens the range at launch; --preview-flight flies the displayed shot.
        let carry = app.descendants(matching: .any)["range.carry"]
        XCTAssertTrue(carry.waitForExistence(timeout: 10))
        XCTAssertTrue(carry.label.contains("264"), "carry label: \(carry.label)")
        XCTAssertTrue(app.descendants(matching: .any)["range.ballSpeed"].label.contains("151.4"))

        // In the air (plan R7b) the detail metrics fold into one strip so the landing area shows.
        let compact = app.descendants(matching: .any)["range.metricsCompact"]
        XCTAssertTrue(compact.waitForExistence(timeout: 5))
        XCTAssertTrue(compact.label.contains("2,380 rpm"), "compact label: \(compact.label)")
        XCTAssertFalse(app.descendants(matching: .any)["range.metricsDetail"].exists)
        XCTAssertFalse(app.descendants(matching: .any)["range.clubSelector"].exists)

        // The flight plays (3.5-6 s), lands, dwells 1.25 s and returns to waiting; replay shows
        // again once nothing is preparing or flying.
        let status = app.descendants(matching: .any)["range.status"]
        XCTAssertTrue(status.waitForExistence(timeout: 5))
        let replay = app.buttons["range.replay"]
        XCTAssertTrue(replay.waitForExistence(timeout: 15))
        let waiting = NSPredicate(format: "label CONTAINS %@", "Ready for the next shot")
        expectation(for: waiting, evaluatedWith: status)
        waitForExpectations(timeout: 5)

        // After the dwell the full panel is back.
        XCTAssertTrue(app.descendants(matching: .any)["range.metricsDetail"].waitForExistence(timeout: 3))
        XCTAssertFalse(compact.exists)
        XCTAssertTrue(app.descendants(matching: .any)["range.clubSelector"].exists)
        XCTAssertTrue(app.staticTexts["1.47"].exists) // smash
        XCTAssertTrue(app.staticTexts["2,380"].exists) // spin

        // Replay flies it again.
        replay.tap()
        let flying = NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@", "Ball in flight", "Calculating flight")
        expectation(for: flying, evaluatedWith: status)
        waitForExpectations(timeout: 5)
        XCTAssertFalse(app.buttons["range.replay"].exists)
    }
}
