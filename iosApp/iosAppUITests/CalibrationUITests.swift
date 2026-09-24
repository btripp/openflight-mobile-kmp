// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Calibration smoke tests (plan R3a). The simulator has no device motion, so the shared
/// `CalibrationViewModel` reports `SensorUiState.Unavailable` and the screen shows
/// "Motion unavailable" with Apply disabled, the same as `--iwr6843`-less server verification.
final class CalibrationUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testMotionUnavailableShowsAndApplyIsDisabled() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot"]
        app.launch()

        navigateToCalibration(app)

        XCTAssertTrue(app.staticTexts["Motion unavailable"].waitForExistence(timeout: 10))

        let apply = app.buttons["calibration.apply"]
        XCTAssertTrue(apply.waitForExistence(timeout: 5))
        XCTAssertFalse(apply.isEnabled)
    }

    func testDoneReturnsToTheDashboard() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot"]
        app.launch()

        navigateToCalibration(app)

        let done = app.buttons["calibration.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        done.tap()

        // Back on the dashboard: the calibrate button is visible again.
        XCTAssertTrue(app.buttons["dashboard.calibrateRadar"].waitForExistence(timeout: 5))
    }

    private func navigateToCalibration(_ app: XCUIApplication) {
        let calibrate = app.buttons["dashboard.calibrateRadar"]
        XCTAssertTrue(calibrate.waitForExistence(timeout: 10))
        calibrate.tap()
        XCTAssertTrue(app.navigationBars["Calibrate TI Radar"].waitForExistence(timeout: 5))
    }
}
