// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Issue #80: the range at the largest accessibility text size (AX3). The top bar (Exit, Follow,
/// History, Replay, settings) used to run off the screen, so the settings button couldn't be
/// tapped and Exit became an empty stretched pill; the status pill and the Carry tile were cut off.
///
/// Each test also attaches a screenshot, so the default-size and AX3 layouts can be compared.
final class RangeLargeTextUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testTopBarAndMetricsFitAtAccessibilityText() {
        let app = launchRange(size: "UICTContentSizeCategoryAccessibilityXXXL")
        attachScreenshot(named: "range-ax3", of: app)

        let screen = app.windows.firstMatch.frame
        for identifier in ["range.exit", "range.quickSettings"] {
            let control = app.buttons[identifier]
            XCTAssertTrue(control.isHittable, "\(identifier) not tappable at AX3")
            XCTAssertTrue(screen.contains(control.frame), "\(identifier) off screen: \(control.frame) in \(screen)")
        }
        // Exit keeps its name for VoiceOver, even when it shows only its icon.
        XCTAssertEqual(app.buttons["range.exit"].label, "Exit")
        for identifier in ["range.status", "range.ballSpeed", "range.carry"] {
            let frame = element(identifier, in: app).frame
            XCTAssertTrue(screen.contains(frame), "\(identifier) cut off: \(frame) in \(screen)")
        }
    }

    /// The controls that don't fit at AX3 (the camera mode, History and Replay) stay reachable.
    func testCollapsedControlsStayReachableAtAccessibilityText() {
        let app = launchRange(size: "UICTContentSizeCategoryAccessibilityXXXL")
        let more = app.buttons["range.moreControls"]
        XCTAssertTrue(more.isHittable, "the overflow menu isn't tappable")
        more.tap()
        XCTAssertTrue(app.buttons["History"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Replay shot"].exists)
        XCTAssertTrue(
            app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "camera")).firstMatch.exists,
            "no camera mode in the menu"
        )
    }

    /// The angle metrics are named for VoiceOver and say "degrees" instead of a bare "°", like the
    /// Dashboard's metrics.
    func testAngleMetricsHaveSpokenLabels() {
        let app = launchRange(size: "UICTContentSizeCategoryL")
        attachScreenshot(named: "range-default", of: app)

        for title in ["Launch", "Direction", "Path", "Spin axis"] {
            let metric = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label == %@ AND value CONTAINS %@", title, "degrees"))
                .firstMatch
            XCTAssertTrue(metric.exists, "no spoken label for \(title)")
        }
    }

    // MARK: Helpers

    private func launchRange(size: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "--ui-testing", "--preview-shot", "--range-mode",
            "-UIPreferredContentSizeCategoryName", size,
        ]
        app.launch()
        XCTAssertTrue(app.buttons["range.exit"].waitForExistence(timeout: 15), size)
        return app
    }

    private func element(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    private func attachScreenshot(named name: String, of app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
