// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Plan F8a2t: the "Shot trail" picker in Settings › Practice with its live preview, and the choice
/// persisting across a relaunch (a shared `SettingsRepository` value, like Android's). Each test puts
/// the trail back to Classic, no kept trails and no landing effect, since the settings outlive the app.
final class ShotTrailUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testThePreviewShowsAndPickingCometPersistsAcrossRelaunch() {
        let first = AppNav.launch(["--ui-testing", "--preview-shot", "--shot-trail", "classic"])
        defer { restoreClassic() }
        let picker = shotTrailPicker(first)
        XCTAssertTrue(first.descendants(matching: .any)["settings.shotTrail.preview"].exists, "no preview")
        XCTAssertTrue(picker.label.contains("Classic"), picker.label)

        picker.tap()
        let comet = first.buttons["Comet"]
        XCTAssertTrue(comet.waitForExistence(timeout: 5))
        comet.tap()
        waitUntil(picker, labelContains: "Comet")
        first.terminate()

        // No seed this time: whatever is stored comes back.
        let second = AppNav.launch(["--ui-testing", "--preview-shot"])
        waitUntil(shotTrailPicker(second), labelContains: "Comet")
    }

    func testKeepLastAndTheLandingEffectAreOfferedAndPicked() {
        let app = AppNav.launch(["--ui-testing", "--preview-shot", "--shot-trail", "classic"])
        defer { restoreClassic() }
        _ = shotTrailPicker(app)
        let keep = segmented(app, "settings.shotTrail.keepLast")
        let effect = segmented(app, "settings.shotTrail.landingEffect")
        XCTAssertTrue(keep.buttons["Off"].isSelected)
        XCTAssertTrue(effect.buttons["Off"].isSelected)
        for label in ["Ring", "Burst"] { XCTAssertTrue(effect.buttons[label].exists, label) }

        keep.buttons["Last 3"].tap()
        effect.buttons["Ring"].tap()
        waitUntilSelected(keep.buttons["Last 3"])
        waitUntilSelected(effect.buttons["Ring"])

        // Back to the defaults for the tests after this one.
        keep.buttons["Off"].tap()
        effect.buttons["Off"].tap()
        waitUntilSelected(keep.buttons["Off"])
        waitUntilSelected(effect.buttons["Off"])
    }

    // MARK: Helpers

    /// The style menu, scrolled into view on the Settings screen.
    private func shotTrailPicker(_ app: XCUIApplication) -> XCUIElement {
        AppNav.open(.settings, in: app)
        let picker = app.buttons["settings.shotTrail"]
        var attempts = 0
        while !(picker.exists && picker.isHittable), attempts < 12 {
            app.swipeUp()
            attempts += 1
        }
        XCTAssertTrue(picker.exists, "shot trail picker not found")
        return picker
    }

    private func segmented(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        let control = app.segmentedControls[identifier]
        var attempts = 0
        while !(control.exists && control.isHittable), attempts < 6 {
            app.swipeUp()
            attempts += 1
        }
        XCTAssertTrue(control.exists, "\(identifier) not found")
        return control
    }

    private func waitUntil(_ element: XCUIElement, labelContains text: String) {
        expectation(for: NSPredicate(format: "label CONTAINS %@", text), evaluatedWith: element)
        waitForExpectations(timeout: 5)
    }

    private func waitUntilSelected(_ element: XCUIElement) {
        expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: element)
        waitForExpectations(timeout: 5)
    }

    /// Relaunches with the Classic seed so later tests (and the user's next launch) see the default.
    private func restoreClassic() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot", "--shot-trail", "classic"]
        app.launch()
        app.terminate()
    }
}
