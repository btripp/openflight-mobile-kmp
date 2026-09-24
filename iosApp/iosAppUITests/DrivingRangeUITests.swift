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
        XCTAssertTrue(app.staticTexts["1.47"].exists) // smash
        XCTAssertTrue(app.staticTexts["2,380"].exists) // spin

        // The flight plays (3.5-6 s), lands, dwells 1.25 s and returns to waiting; replay shows
        // again once nothing is preparing or flying.
        let status = app.descendants(matching: .any)["range.status"]
        XCTAssertTrue(status.waitForExistence(timeout: 5))
        let replay = app.buttons["range.replay"]
        XCTAssertTrue(replay.waitForExistence(timeout: 15))
        let landedOrWaiting = NSPredicate(
            format: "label CONTAINS %@ OR label CONTAINS %@", "Shot complete", "Ready for the next shot"
        )
        expectation(for: landedOrWaiting, evaluatedWith: status)
        waitForExpectations(timeout: 5)

        // Replay flies it again.
        replay.tap()
        let flying = NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@", "Ball in flight", "Calculating flight")
        expectation(for: flying, evaluatedWith: status)
        waitForExpectations(timeout: 5)
        XCTAssertFalse(app.buttons["range.replay"].exists)
    }
}
