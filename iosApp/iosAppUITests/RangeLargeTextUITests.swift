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

    /// Tester report (build 5, "Largest text size: layout issues appeared primarily in Range"): at
    /// AX5 the replay transport was pushed below the screen, so Prev and Next couldn't be reached,
    /// in portrait and in landscape. It now stays on screen, with Ball speed and Carry above it.
    func testReplayTransportStaysOnScreenAtAccessibilityText() {
        let app = launchRange(size: "UICTContentSizeCategoryAccessibilityXXXL", extra: ["--preview-history"])
        app.buttons["range.moreControls"].tap()
        XCTAssertTrue(app.buttons["History"].waitForExistence(timeout: 5))
        app.buttons["History"].tap()
        let replay = app.buttons["range.session.preview-current.replay"]
        XCTAssertTrue(replay.waitForExistence(timeout: 5))
        replay.tap()
        XCTAssertTrue(app.buttons["range.previous"].waitForExistence(timeout: 10))
        attachScreenshot(named: "range-ax5-replay", of: app)
        assertTransportOnScreen(in: app)

        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        sleep(2)
        attachScreenshot(named: "range-ax5-replay-landscape", of: app)
        assertTransportOnScreen(in: app)
    }

    // MARK: Helpers

    private func assertTransportOnScreen(in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let screen = app.windows.firstMatch.frame
        for identifier in ["range.live", "range.previous", "range.playPause", "range.next"] {
            let control = app.buttons[identifier]
            XCTAssertTrue(control.isHittable, "\(identifier) not tappable", file: file, line: line)
            XCTAssertTrue(
                screen.contains(control.frame),
                "\(identifier) off screen: \(control.frame) in \(screen)",
                file: file,
                line: line
            )
        }
        for identifier in ["range.ballSpeed", "range.carry"] {
            let frame = element(identifier, in: app).frame
            XCTAssertTrue(screen.contains(frame), "\(identifier) cut off: \(frame) in \(screen)", file: file, line: line)
        }
    }

    private func launchRange(size: String, extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "--ui-testing", "--preview-shot", "--range-mode",
            "-UIPreferredContentSizeCategoryName", size,
        ] + extra
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
