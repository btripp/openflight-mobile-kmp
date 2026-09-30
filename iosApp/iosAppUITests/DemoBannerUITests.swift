// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Issue #79: the Demo banner sits above every screen and pushes it down, so it never covers a
/// navigation bar (the History detail's back button, Bag's Edit, the iPad sidebar toggle) or the
/// Range's top controls. Each test attaches a screenshot (`i79-ios-*`).
final class DemoBannerUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testTheHistoryDetailBackButtonAndBagsEditSitBelowTheDemoBanner() {
        let app = launch()

        AppNav.open(.sessions, in: app)
        let history = app.buttons["session.history.open"].firstMatch
        XCTAssertTrue(history.waitForExistence(timeout: 10))
        history.tap()
        let row = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'session.history.session.'")).firstMatch
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND isHittable == true"), object: row)
        XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 15), .completed, "no stored demo session")
        row.tap()
        XCTAssertTrue(app.staticTexts["session.history.detail.source"].waitForExistence(timeout: 10))

        let back = app.navigationBars.buttons.firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 5), "no back button on the History detail")
        attach(app, "i79-ios-history-detail")
        assertBelowBanner(back, in: app)
        assertNavigationBarsBelowBanner(in: app)

        AppNav.open(.bag, in: app)
        XCTAssertTrue(app.navigationBars.buttons.firstMatch.waitForExistence(timeout: 10))
        attach(app, "i79-ios-bag")
        assertNavigationBarsBelowBanner(in: app)
    }

    func testTheRangeExitSitsBelowTheDemoBanner() {
        let app = launch()

        let rangeButton = app.buttons["dashboard.range"]
        XCTAssertTrue(rangeButton.waitForExistence(timeout: 10))
        rangeButton.tap()
        let exit = app.buttons["range.exit"]
        XCTAssertTrue(exit.waitForExistence(timeout: 15))
        attach(app, "i79-ios-range")
        assertBelowBanner(exit, in: app)

        exit.tap()
        XCTAssertTrue(rangeButton.waitForExistence(timeout: 10))
    }

    // MARK: Helpers

    /// Demo mode on from launch: the real repositories with the demo data, on Wi-Fi at an address
    /// nothing answers.
    private func launch() -> XCUIApplication {
        let app = AppNav.launch(["--demo-mode", "on", "--transport", "wifi", "--host", "127.0.0.1:9"])
        XCTAssertTrue(badge(app).waitForExistence(timeout: 10))
        return app
    }

    private func badge(_ app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)["app.demoBadge"]
    }

    /// `element` is tappable and none of it is under the banner.
    private func assertBelowBanner(
        _ element: XCUIElement,
        in app: XCUIApplication,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let banner = badge(app)
        XCTAssertTrue(banner.exists, "no Demo banner", file: file, line: line)
        XCTAssertTrue(element.isHittable, "\(element) is not hittable", file: file, line: line)
        XCTAssertGreaterThanOrEqual(
            element.frame.minY,
            banner.frame.maxY - 0.5,
            "\(element.identifier) \(element.label) at \(element.frame) is under the banner at \(banner.frame)",
            file: file,
            line: line
        )
    }

    /// Every navigation bar button on screen (back, Edit, the iPad sidebar toggle) clears the banner.
    private func assertNavigationBarsBelowBanner(in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let buttons = app.navigationBars.buttons.allElementsBoundByIndex.filter { $0.exists && !$0.frame.isEmpty }
        for button in buttons {
            assertBelowBanner(button, in: app, file: file, line: line)
        }
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
