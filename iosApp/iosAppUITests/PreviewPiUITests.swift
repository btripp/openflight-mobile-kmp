// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Plan R8f under `--preview-pi`: a connected preview Pi (roster Ann/Bob/Cara with Ann active, a
/// battery on 78 %, a rolling-buffer trigger status, a swing being calculated) drives the profile
/// picker, the processing indicator, the device cards and stopping OpenFlight without hardware.
/// Each test attaches a screenshot of the state it checks (`r8fa-ios-*`).
final class PreviewPiUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    // MARK: Dashboard

    func testTheProcessingIndicatorSaysWhatThePiIsDoing() {
        let app = launch()

        let processing = app.descendants(matching: .any)["dashboard.processing"]
        XCTAssertTrue(processing.waitForExistence(timeout: 5))
        XCTAssertTrue(processing.label.contains("Shot captured"), "processing: \(processing.label)")
        XCTAssertTrue(processing.label.contains("Calculating metrics"), "processing: \(processing.label)")
        attach(app, "r8fa-ios-processing")
    }

    func testPickingAProfileSwitchesToIt() {
        let app = launch()
        let profile = app.buttons["dashboard.profile"]
        scroll(app, to: profile)
        XCTAssertTrue(profile.label.contains("Ann"), "profile: \(profile.label)")

        profile.tap()
        let bob = app.buttons["dashboard.profile.row.preview-bob"]
        XCTAssertTrue(bob.waitForExistence(timeout: 5))
        attach(app, "r8fa-ios-profile-sheet")
        bob.tap()

        XCTAssertTrue(bob.waitForNonExistence(timeout: 5))
        let switched = NSPredicate(format: "label CONTAINS 'Bob'")
        expectation(for: switched, evaluatedWith: profile)
        waitForExpectations(timeout: 5)
    }

    func testAddingAProfileMakesItActiveAndABlankNameIsRefused() {
        let app = launch()
        openProfiles(app)
        app.buttons["dashboard.profile.add"].tap()

        let field = app.textFields["dashboard.profile.name"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["dashboard.profile.save"].isEnabled, "a blank name can't be added")
        field.typeText("  Dee  ")
        attach(app, "r8fa-ios-profile-add")
        app.buttons["dashboard.profile.save"].tap()

        // The Pi makes the new profile active; the sheet is back on the roster.
        let dee = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'dashboard.profile.row.' AND label CONTAINS 'Dee'"))
            .firstMatch
        XCTAssertTrue(dee.waitForExistence(timeout: 5))
        XCTAssertTrue(dee.isSelected, "the added profile is active")
        app.buttons["dashboard.profile.done"].tap()
        XCTAssertTrue(app.buttons["dashboard.profile"].label.contains("Dee"))
    }

    func testRemovingAProfileIsConfirmedFirst() {
        let app = launch()
        openProfiles(app)
        let cara = app.buttons["dashboard.profile.row.preview-cara"]
        XCTAssertTrue(cara.waitForExistence(timeout: 5))
        cara.swipeLeft()
        app.buttons["Remove"].firstMatch.tap()

        let confirm = app.buttons["dashboard.profile.removeConfirm"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        attach(app, "r8fa-ios-profile-remove")
        confirm.tap()

        XCTAssertTrue(cara.waitForNonExistence(timeout: 5))
        // The active profile never offers Remove.
        let ann = app.buttons["dashboard.profile.row.preview-ann"]
        ann.swipeLeft()
        XCTAssertFalse(app.buttons["Remove"].exists)
    }

    // MARK: Settings

    func testPowerAndLaunchMonitorCardsShowThePisReport() {
        let app = launch()
        app.tabBars.buttons["Settings"].tap()

        // The launch monitor card comes first; the List unloads rows scrolled far off screen.
        let mode = app.staticTexts["Rolling buffer"]
        scroll(app, to: mode)
        XCTAssertTrue(app.staticTexts["/dev/ttyUSB0"].exists)

        let power = app.descendants(matching: .any)["settings.power.state"]
        scroll(app, to: power)
        XCTAssertTrue(power.label.contains("On battery"), "power: \(power.label)")
        scroll(app, to: app.staticTexts["3.91 V"])
        XCTAssertTrue(app.staticTexts["78%"].exists)
        attach(app, "r8fa-ios-power")
    }

    func testStoppingOpenFlightGoesThroughPendingToDone() {
        let app = launch()
        app.tabBars.buttons["Settings"].tap()
        let stop = app.buttons["settings.shutdown"]
        scroll(app, to: stop)
        XCTAssertTrue(stop.isEnabled)
        stop.tap()

        // iOS 26 may present the dialog as a popover that lists the action twice.
        let confirm = app.buttons["settings.shutdown.confirm"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        confirm.tap()

        let pending = app.descendants(matching: .any)["settings.shutdown.pending"]
        XCTAssertTrue(pending.waitForExistence(timeout: 5))
        attach(app, "r8fa-ios-shutdown-pending")
        let done = app.descendants(matching: .any)["settings.shutdown.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 10))
        XCTAssertTrue(done.label.contains("the Pi stays on"), "done: \(done.label)")
        attach(app, "r8fa-ios-shutdown-done")

        app.buttons["settings.shutdown.dismiss"].tap()
        XCTAssertTrue(stop.waitForExistence(timeout: 5))
    }

    // MARK: Helpers

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-pi"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Settings"].waitForExistence(timeout: 10))
        return app
    }

    private func openProfiles(_ app: XCUIApplication) {
        let profile = app.buttons["dashboard.profile"]
        scroll(app, to: profile)
        profile.tap()
        XCTAssertTrue(app.buttons["dashboard.profile.add"].waitForExistence(timeout: 5))
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
