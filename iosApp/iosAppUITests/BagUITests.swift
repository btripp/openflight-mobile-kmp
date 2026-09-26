// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// My Bag (plan F5): the first open seeds the default 14-club bag and lists it with the conditions
/// card. On iPhone the sixth tab sits under "More", so the helper looks there too.
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
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot"]
        app.launch()
        let bag = app.tabBars.buttons["Bag"]
        // iPad draws its tabs as a floating bar, not a classic `tabBars` element.
        let floatingTab = app.buttons["Bag"].firstMatch
        if bag.waitForExistence(timeout: 10) {
            bag.tap()
        } else if floatingTab.exists {
            floatingTab.tap()
        } else {
            let more = app.tabBars.buttons["More"]
            XCTAssertTrue(more.waitForExistence(timeout: 5))
            more.tap()
            let entry = app.cells.staticTexts["Bag"].firstMatch
            XCTAssertTrue(entry.waitForExistence(timeout: 5))
            entry.tap()
        }
        return app
    }
}
