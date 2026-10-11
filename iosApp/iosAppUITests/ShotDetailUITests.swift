// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// The shot detail sheet and the bag's shot shape. Identifiers mirror the shared
/// `ShotDetailTestTags` / `DashboardTestTags` / `BagTestTags`.
final class ShotDetailUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// Practice's latest shot card opens the sheet: its title, the flight and the view picker.
    func testPracticeDetailsOpensTheShotSheet() {
        let app = AppNav.launch(["--ui-testing", "--preview-shot"])
        AppNav.open(.practice, in: app)

        let details = app.buttons["dashboard.latest.details"]
        XCTAssertTrue(SessionUITests.reveal(details, in: app))
        details.tap()

        let title = app.descendants(matching: .any)["shotDetail.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        XCTAssertTrue(title.label.contains("Driver"), "title: \(title.label)")
        XCTAssertTrue(app.descendants(matching: .any)["shotDetail.flight"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["shotDetail.metric.Carry"].exists)

        // The camera angles switch without losing the flight.
        app.buttons["Top"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Tap to replay"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.descendants(matching: .any)["shotDetail.flight"].exists)

        app.buttons["shotDetail.close"].tap()
        XCTAssertTrue(title.waitForNonExistence(timeout: 5))
    }

    /// A club with shaped shots shows its Shot shape card. Demo mode's made-up shots always carry
    /// a spin axis, so their curve is measured.
    func testTheBagShowsAClubsShotShape() {
        let app = AppNav.launch(["--demo-mode", "on"])
        AppNav.open(.practice, in: app)
        let hit = app.buttons["Hit a shot"]
        XCTAssertTrue(hit.waitForExistence(timeout: 10))
        for _ in 0..<3 {
            hit.tap()
            RunLoop.current.run(until: Date().addingTimeInterval(1))
        }

        AppNav.open(.bag, in: app)
        let driver = app.descendants(matching: .any)["bag.club.driver"]
        XCTAssertTrue(driver.waitForExistence(timeout: 10))
        driver.tap()

        let pattern = app.descendants(matching: .any)["bag.detail.shapePattern"]
        XCTAssertTrue(SessionUITests.reveal(pattern, in: app))
        XCTAssertTrue(pattern.label.hasPrefix("Shot shape:"), "pattern: \(pattern.label)")
    }
}
