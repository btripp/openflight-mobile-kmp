// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// My Bag (plan F5): the first open seeds the default 14-club bag and lists it with the conditions
/// card. Plan F1d: Bag is one of four top-level destinations, so it's never under "More".
final class BagUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testTheBagListShowsTheSeededDefaultBagAndTheConditionsCard() {
        let app = Self.openBag()

        let driver = app.descendants(matching: .any)["bag.club.driver"]
        XCTAssertTrue(driver.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Driver"].exists || driver.label.contains("Driver"), "row: \(driver.label)")
        // The list is lazy: the 7-iron is below the fold on an iPhone, so check the next row down.
        XCTAssertTrue(app.descendants(matching: .any)["bag.club.3-wood"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["bag.conditions.summary"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["bag.openAnalysis"].exists)
    }

    static func openBag() -> XCUIApplication {
        let app = AppNav.launch(["--ui-testing", "--preview-shot"])
        AppNav.open(.bag, in: app)
        return app
    }
}
