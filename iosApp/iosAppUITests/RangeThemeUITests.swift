// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Plan F8a2a: the range theme picker in Settings › Practice, and the choice persisting across a
/// relaunch (it's a shared `SettingsRepository` value, like Android's). Each test puts the theme
/// back to Day, since the setting outlives the app.
final class RangeThemeUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testPickingNightShowsItAndTheRangeStillDraws() {
        let app = AppNav.launch(["--ui-testing", "--preview-shot", "--range-theme", "day"])
        defer { restoreDay() }

        let night = themeOption(app, "Night")
        XCTAssertTrue(themeOption(app, "Day").isSelected)
        for label in ["Day", "Dusk", "Night", "Links"] {
            XCTAssertTrue(themeOption(app, label).exists, "\(label) missing")
        }
        night.tap()
        waitUntilSelected(night)

        // The range opens in the new theme without a hitch.
        AppNav.open(.practice, in: app)
        let rangeButton = app.buttons["dashboard.range"]
        XCTAssertTrue(rangeButton.waitForExistence(timeout: 10))
        rangeButton.tap()
        XCTAssertTrue(app.descendants(matching: .any)["range.scene"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["range.exit"].exists)
    }

    func testTheRangeThemePersistsAcrossRelaunch() {
        let first = AppNav.launch(["--ui-testing", "--preview-shot", "--range-theme", "day"])
        defer { restoreDay() }
        let links = themeOption(first, "Links")
        links.tap()
        waitUntilSelected(links)
        first.terminate()

        // No seed this time: whatever is stored comes back.
        let second = AppNav.launch(["--ui-testing", "--preview-shot"])
        let restored = themeOption(second, "Links")
        waitUntilSelected(restored)
        XCTAssertFalse(themeOption(second, "Day").isSelected)
    }

    // MARK: Helpers

    /// The picker's segment for `label`, scrolled into view on the Settings screen.
    private func themeOption(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        let picker = app.segmentedControls["settings.rangeTheme"]
        if !picker.exists || !picker.isHittable { AppNav.open(.settings, in: app) }
        var attempts = 0
        while !(picker.exists && picker.isHittable), attempts < 10 {
            app.swipeUp()
            attempts += 1
        }
        XCTAssertTrue(picker.exists, "range theme picker not found")
        return picker.buttons[label]
    }

    private func waitUntilSelected(_ element: XCUIElement) {
        expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: element)
        waitForExpectations(timeout: 5)
    }

    /// Relaunches with the Day seed so later tests (and the user's next launch) see the default.
    private func restoreDay() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot", "--range-theme", "day"]
        app.launch()
        app.terminate()
    }
}
