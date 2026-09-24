// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Settings, Camera and Training (plan R6c) under `--ui-testing --preview-shot`. The preview
/// repository never starts the Pi's Socket.IO session, so every Wi-Fi-only control must render
/// disabled with the shared reason "Not connected" (the shutdown confirmation itself is exercised
/// in the live mock run, where the session is up).
final class PiScreensUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    // MARK: Settings

    func testUnitsToggleChangesTheDashboardUnits() {
        let app = launch()
        addTeardownBlock { self.selectUnits(app, "Imperial (mph, yds)") }

        selectUnits(app, "Metric (km/h, m)")
        app.tabBars.buttons["Dashboard"].tap()
        let ballSpeed = app.descendants(matching: .any)["dashboard.ballSpeed"]
        XCTAssertTrue(ballSpeed.waitForExistence(timeout: 5))
        // 151.4 mph × 1.60934 = 243.65 km/h; 264 yds × 0.9144 = 241.4 m.
        XCTAssertEqual(ballSpeed.value as? String, "243.7 kilometers per hour")
        XCTAssertEqual(app.descendants(matching: .any)["dashboard.carry"].value as? String, "241 meters")
        XCTAssertEqual(
            app.descendants(matching: .any)["dashboard.metric.Club speed"].value as? String,
            "166.1 km/h"
        )

        selectUnits(app, "Imperial (mph, yds)")
        app.tabBars.buttons["Dashboard"].tap()
        XCTAssertEqual(ballSpeed.value as? String, "151.4 miles per hour")
    }

    func testShutdownAndPiControlsAreDisabledWithoutThePiSession() {
        let app = launch()
        app.tabBars.buttons["Settings"].tap()

        let liveSession = app.descendants(matching: .any)["settings.liveSession"]
        XCTAssertTrue(liveSession.waitForExistence(timeout: 5))
        XCTAssertTrue(liveSession.label.contains("Not connected"), "live session: \(liveSession.label)")
        XCTAssertFalse(app.buttons["settings.player.set"].isEnabled)

        let shutdown = app.buttons["settings.shutdown"]
        let reason = app.staticTexts["settings.shutdown.reason"]
        scroll(app, to: reason)
        XCTAssertFalse(shutdown.isEnabled)
        XCTAssertTrue(reason.label.contains("Not connected"), "reason: \(reason.label)")
        XCTAssertFalse(app.buttons["settings.cloud.upload"].isEnabled)
    }

    // MARK: Camera

    func testCameraShowsTheOfflineStateWithDisabledToggles() {
        let app = launch()
        app.tabBars.buttons["Camera"].tap()

        let title = app.staticTexts["camera.phaseTitle"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        XCTAssertEqual(title.label, "Camera Offline")
        XCTAssertTrue(app.staticTexts["The camera needs the Pi's live Wi-Fi session (Not connected)."].exists)
        XCTAssertEqual(app.descendants(matching: .any)["camera.ballStatus"].label, "Camera Off")

        let detection = app.switches["camera.toggleCamera"]
        scroll(app, to: detection)
        XCTAssertFalse(detection.isEnabled)
        XCTAssertFalse(app.switches["camera.toggleStream"].isEnabled)
    }

    // MARK: Training

    func testTrainingRendersThePickerDisabledWithoutThePiSession() {
        let app = launch()
        app.tabBars.buttons["Training"].tap()

        let player = app.staticTexts["training.player"]
        XCTAssertTrue(player.waitForExistence(timeout: 5))
        XCTAssertEqual(player.label, "Player 1")
        XCTAssertEqual(app.staticTexts["training.count"].label, "No swings yet")
        XCTAssertEqual(app.descendants(matching: .any)["training.last"].value as? String, "no swings")
        XCTAssertTrue(app.staticTexts["Waiting for the Pi's trigger mode"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["training.availability"].label.contains("Not connected"))
        XCTAssertEqual(app.staticTexts["training.selectedImplement"].label, "Driver")

        let driver = app.buttons["training.implement.driver"]
        XCTAssertTrue(driver.exists)
        XCTAssertFalse(driver.isEnabled)
        XCTAssertTrue(app.staticTexts["SuperSpeed"].exists)
    }

    // MARK: Helpers

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Settings"].waitForExistence(timeout: 10))
        return app
    }

    private func selectUnits(_ app: XCUIApplication, _ label: String) {
        app.tabBars.buttons["Settings"].tap()
        let option = app.segmentedControls["settings.units"].buttons[label]
        XCTAssertTrue(option.waitForExistence(timeout: 5))
        option.tap()
        let selected = NSPredicate(format: "isSelected == true")
        expectation(for: selected, evaluatedWith: option)
        waitForExpectations(timeout: 5)
    }

    private func scroll(_ app: XCUIApplication, to element: XCUIElement) {
        var attempts = 0
        while !(element.exists && element.isHittable), attempts < 8 {
            app.swipeUp()
            attempts += 1
        }
        XCTAssertTrue(element.exists, "\(element) not found")
    }
}
