// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Issue #82: the gold buttons carry dark text (`Theme.bgDeep`) on gold, like Android's `OfButton`,
/// instead of `.borderedProminent`'s white on gold (about 2.1:1). Practice in Demo mode shows two
/// of them at once ("Hit a shot" and the club confirmation's "Looks right"), so Xcode's contrast
/// audit there fails on the old style and passes on `OfProminentButtonStyle`.
final class GoldButtonContrastUITests: XCTestCase {
    /// The audit's known false positives on Practice, by element. The audit samples the rendered
    /// pixels behind a label, and each of these draws on a translucent fill (`Theme.cream` at 7%
    /// for the cards, `Theme.gold` at 12% for the club confirmation, the `.bordered` tint) over the
    /// screen's diagonal gradient, which it misreads. Their tokens (cream or `creamDim` on the dark
    /// background, gold on dark) are all above 4.5:1. None of them is a filled gold button, so the
    /// filter can't hide the regression this test guards.
    private static let falsePositiveIdentifiers: Set<String> = [
        "dashboard.status", // "Connected" under the Pi name, on the connection card
        "dashboard.emptyState", // "Waiting for a shot", on its card
        "dashboard.exitDemo", // `.bordered` gold tint: a translucent gold capsule
    ]

    /// The same kind of false positive for elements without an accessibility identifier, matched
    /// by the start of their label.
    private static let falsePositiveLabelPrefixes = [
        "Demo mode: a pretend Pi", // the demo note on the connection card
        "The Pi files every shot", // the club confirmation's caption, on its 12% gold panel
        "Calibrate", // "Calibrate TI Radar", a `.bordered` gold-tinted button
    ]

    /// The gold buttons this test is about. Never filtered.
    private static let goldButtons = ["dashboard.hitShot", "dashboard.clubConfirm"]

    override func setUp() {
        continueAfterFailure = false
    }

    func testPracticeInDemoModePassesTheContrastAudit() throws {
        let app = AppNav.launch(["--demo-mode", "off", "--transport", "wifi", "--host", "127.0.0.1:9"])
        let tryDemo = app.buttons["dashboard.tryDemo"]
        scroll(app, to: tryDemo)
        tryDemo.tap()
        XCTAssertTrue(app.buttons["dashboard.hitShot"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["dashboard.clubConfirm"].waitForExistence(timeout: 10))
        app.swipeDown()
        for id in Self.goldButtons {
            scroll(app, to: app.buttons[id])
        }
        attach(app, "issue82-ios-practice")

        var ignored: [String] = []
        try app.performAccessibilityAudit(for: .contrast) { issue in
            guard let element = issue.element, Self.isKnownFalsePositive(element) else { return false }
            ignored.append(element.identifier.isEmpty ? element.label : element.identifier)
            return true
        }
        // Keep the filter honest: it only ever drops the elements listed above.
        XCTAssertTrue(ignored.allSatisfy { !Self.goldButtons.contains($0) })
    }

    /// The disabled state: the simulator has no device motion, so Calibration's Apply (the same
    /// filled style on `Theme.success`) is disabled. Its label must stay readable. The audit is
    /// scoped to that one button; the rest of the screen isn't this issue's.
    func testCalibrationDisabledApplyStaysReadable() throws {
        let app = AppNav.launch(["--ui-testing", "--preview-shot"])
        let calibrate = app.buttons["dashboard.calibrateRadar"]
        scroll(app, to: calibrate)
        calibrate.tap()
        let apply = app.buttons["calibration.apply"]
        XCTAssertTrue(apply.waitForExistence(timeout: 10))
        scroll(app, to: apply)
        XCTAssertFalse(apply.isEnabled)
        attach(app, "issue82-ios-calibration")

        try app.performAccessibilityAudit(for: .contrast) { issue in
            guard let element = issue.element else { return true }
            return element.identifier != "calibration.apply" && !element.label.hasPrefix("Apply Calibration")
        }
    }

    private static func isKnownFalsePositive(_ element: XCUIElement) -> Bool {
        if goldButtons.contains(element.identifier) { return false }
        if falsePositiveIdentifiers.contains(element.identifier) { return true }
        return falsePositiveLabelPrefixes.contains { element.label.hasPrefix($0) }
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
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
