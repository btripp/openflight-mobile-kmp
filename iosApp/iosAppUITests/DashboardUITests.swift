// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Dashboard smoke tests (plan R2). `--ui-testing --preview-shot` swaps in the shared
/// `PreviewShotRepository`: it reports Connected, holds the reference's preview shot and never
/// starts a transport.
final class DashboardUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testPreviewShotShowsMetricsAndClubMenu() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot"]
        app.launch()

        // The reference's ShotEvent.preview (= shot_v1.json): driver, 151.4 mph, 264 yds.
        XCTAssertTrue(app.staticTexts["LATEST SHOT"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Driver"].exists)
        let ballSpeed = app.descendants(matching: .any)["dashboard.ballSpeed"]
        XCTAssertTrue(ballSpeed.exists)
        XCTAssertEqual(ballSpeed.label, "Ball Speed")
        XCTAssertEqual(ballSpeed.value as? String, "151.4 miles per hour")
        XCTAssertEqual(app.descendants(matching: .any)["dashboard.carry"].value as? String, "264 yards")
        XCTAssertEqual(metric(app, "Smash").value as? String, "1.47")
        XCTAssertEqual(metric(app, "Club speed").value as? String, "103.2 mph")
        XCTAssertEqual(metric(app, "Launch").value as? String, "12.6 degrees")
        XCTAssertEqual(metric(app, "Direction").value as? String, "-1.3 degrees")
        XCTAssertEqual(metric(app, "Spin").value as? String, "2,380 rpm")
        XCTAssertEqual(metric(app, "Club path").value as? String, "2.1 degrees")
        XCTAssertEqual(metric(app, "Spin axis").value as? String, "-3.4 degrees")

        // Connected (the preview repository) and no club change in flight: the menu is enabled.
        let clubMenu = app.buttons["dashboard.clubSelector"]
        XCTAssertTrue(clubMenu.exists)
        XCTAssertTrue(clubMenu.isEnabled)
        XCTAssertTrue(app.staticTexts["CLUB FOR NEXT SHOT"].exists)
        XCTAssertTrue(app.buttons["dashboard.calibrateRadar"].exists)
        XCTAssertTrue(app.buttons["dashboard.range"].exists)
        // Only one shot: no history card.
        XCTAssertFalse(app.descendants(matching: .any)["dashboard.previousShots"].exists)
    }

    func testUiTestingWithoutPreviewShotWaitsForAShot() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Waiting for a shot"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Connect to your OpenFlight Pi, then hit a ball."].exists)
    }

    func testCalibrateAndRangeOpenTheirScreensAndGoBack() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot"]
        app.launch()

        let calibrate = app.buttons["dashboard.calibrateRadar"]
        XCTAssertTrue(calibrate.waitForExistence(timeout: 10))
        calibrate.tap()
        XCTAssertTrue(app.navigationBars.buttons.firstMatch.waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()

        let range = app.buttons["dashboard.range"]
        XCTAssertTrue(range.waitForExistence(timeout: 5))
        range.tap()
        XCTAssertTrue(app.navigationBars.buttons.firstMatch.waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(range.waitForExistence(timeout: 5))
    }

    func testClubMenuSelectionUpdatesTheLabel() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot"]
        app.launch()

        let clubMenu = app.buttons["dashboard.clubSelector"]
        XCTAssertTrue(clubMenu.waitForExistence(timeout: 10))
        // The selected club persists across launches, so pick one that isn't selected yet.
        let target = clubMenu.label.contains("7-Iron") ? "Driver" : "7-Iron"
        clubMenu.tap()
        let item = Self.menuItem(app, target)
        XCTAssertTrue(item.waitForExistence(timeout: 5))
        item.tap()

        // The preview repository confirms locally and persists it; the label follows.
        let predicate = NSPredicate(format: "label CONTAINS %@", target)
        expectation(for: predicate, evaluatedWith: clubMenu)
        waitForExpectations(timeout: 5)
    }

    /// A club in the open menu (not the menu's own label, which may show the same name).
    static func menuItem(_ app: XCUIApplication, _ club: String) -> XCUIElement {
        app.buttons
            .matching(NSPredicate(format: "label == %@ AND identifier != %@", club, "dashboard.clubSelector"))
            .firstMatch
    }

    private func metric(_ app: XCUIApplication, _ title: String) -> XCUIElement {
        app.descendants(matching: .any)["dashboard.metric.\(title)"]
    }
}
