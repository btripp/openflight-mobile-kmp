// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// Plan F14: the whole app in Demo mode, with no Pi anywhere and no preview hooks: the real
/// repositories, on Wi-Fi at an address nothing answers. "Try without a Pi" connects the pretend Pi
/// under the Demo badge; "Hit a shot" reports a made-up shot (labelled "Demo", spoken by the
/// call-outs through the `--callout-probe` engine, flown by View on range); Sessions and Bag show demo data; the profile switch files the next shot under the new
/// profile; calibration and the camera say they need hardware; and turning Demo mode off in
/// Settings hides all of it. Each test attaches a screenshot (`f14-ios-*`).
final class DemoModeUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testTryWithoutAPiConnectsTheDemoPiUnderTheBadgeAndExitReturnsToTheRealFlow() {
        let app = launch()
        enterDemo(app)
        XCTAssertTrue(app.descendants(matching: .any)["app.demoBadge"].exists)
        attach(app, "f14-ios-demo-connected")

        let exit = app.buttons["dashboard.exitDemo"]
        scroll(app, to: exit)
        exit.tap()
        XCTAssertTrue(app.descendants(matching: .any)["app.demoBadge"].waitForNonExistence(timeout: 10))
        let tryDemo = app.buttons["dashboard.tryDemo"]
        XCTAssertTrue(tryDemo.waitForExistence(timeout: 10))
        attach(app, "f14-ios-try-without-a-pi")
    }

    func testAHitShotIsLabelledDemoSpokenAndViewOnRangeFliesIt() {
        let app = launch()
        enterDemo(app)
        hitAShot(app)
        attach(app, "f14-ios-demo-shot")

        // The call-out speaks the shot once it's final (provisional first, then the shot_update).
        let callout = app.staticTexts["debug.calloutProbe"]
        XCTAssertTrue(callout.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "label CONTAINS 'yards'"), evaluatedWith: callout)
        waitForExpectations(timeout: 15)

        let viewOnRange = app.buttons["dashboard.viewOnRange"]
        scroll(app, to: viewOnRange)
        viewOnRange.tap()
        XCTAssertTrue(app.buttons["range.exit"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.descendants(matching: .any)["range.carry"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.descendants(matching: .any)["app.demoBadge"].exists)
        attach(app, "f14-ios-demo-range")
        app.buttons["range.exit"].tap()
        XCTAssertTrue(app.buttons["dashboard.hitShot"].waitForExistence(timeout: 10))
    }

    func testSessionsAndBagShowDemoData() {
        let app = launch()
        enterDemo(app)
        hitAShot(app)

        AppNav.open(.sessions, in: app)
        let shots = app.descendants(matching: .any)["session.stat.Shots"]
        XCTAssertTrue(shots.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "value == '1'"), evaluatedWith: shots)
        waitForExpectations(timeout: 10)
        attach(app, "f14-ios-demo-session")

        let history = app.buttons["session.history.open"].firstMatch
        XCTAssertTrue(history.waitForExistence(timeout: 10))
        history.tap()
        let demoRow = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'Demo Pi'")).firstMatch
        XCTAssertTrue(demoRow.waitForExistence(timeout: 15))
        attach(app, "f14-ios-demo-history")

        AppNav.open(.bag, in: app)
        let sevenIron = app.descendants(matching: .any)["bag.club.7-iron"]
        scroll(app, to: sevenIron)
        // The row's one VoiceOver stop sits inside the identified row.
        let carry = sevenIron.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS 'average carry'")).firstMatch
        XCTAssertTrue(carry.waitForExistence(timeout: 15))
        attach(app, "f14-ios-demo-bag")
    }

    func testSwitchingProfileFilesTheNextShotUnderIt() {
        let app = launch()
        enterDemo(app)

        let profile = app.buttons["dashboard.profile"]
        scroll(app, to: profile)
        profile.tap()
        let bob = app.buttons["dashboard.profile.row.preview-bob"]
        XCTAssertTrue(bob.waitForExistence(timeout: 10))
        bob.tap()
        XCTAssertTrue(bob.waitForNonExistence(timeout: 10))
        expectation(for: NSPredicate(format: "label CONTAINS 'Bob'"), evaluatedWith: profile)
        waitForExpectations(timeout: 10)

        hitAShot(app)
        let player = app.staticTexts["dashboard.player"]
        XCTAssertTrue(player.waitForExistence(timeout: 10))
        XCTAssertEqual(player.label, "Bob")
    }

    func testCalibrationAndTheCameraSayTheyNeedHardware() {
        let app = launch()
        enterDemo(app)

        let calibrate = app.buttons["dashboard.calibrateRadar"]
        scroll(app, to: calibrate)
        calibrate.tap()
        XCTAssertTrue(app.descendants(matching: .any)["calibration.needsHardware"].waitForExistence(timeout: 10))
        attach(app, "f14-ios-demo-calibration")
        app.buttons["calibration.done"].firstMatch.tap()

        AppNav.open(.settings, in: app)
        let camera = app.buttons["settings.openCamera"]
        scroll(app, to: camera)
        camera.tap()
        XCTAssertTrue(app.descendants(matching: .any)["camera.demoPlaceholder"].waitForExistence(timeout: 10))
        attach(app, "f14-ios-demo-camera")
    }

    func testTurningDemoModeOffInSettingsHidesItsData() {
        let app = launch()
        enterDemo(app)
        hitAShot(app)

        AppNav.open(.settings, in: app)
        let toggle = app.switches["settings.demo.switch"].firstMatch
        scroll(app, to: toggle)
        attach(app, "f14-ios-demo-settings")
        // The switch sits at the row's trailing edge; the row's centre is its label.
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        XCTAssertTrue(app.descendants(matching: .any)["app.demoBadge"].waitForNonExistence(timeout: 10))

        AppNav.open(.practice, in: app)
        XCTAssertTrue(app.buttons["dashboard.tryDemo"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.descendants(matching: .any)["dashboard.demoShotTag"].exists)

        AppNav.open(.sessions, in: app)
        let history = app.buttons["session.history.open"].firstMatch
        XCTAssertTrue(history.waitForExistence(timeout: 10))
        history.tap()
        let demoRow = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'Demo Pi'")).firstMatch
        XCTAssertTrue(demoRow.waitForNonExistence(timeout: 10))
    }

    // MARK: Helpers

    /// No preview hooks: the real repositories, on Wi-Fi at an address nothing answers, with Demo
    /// mode off to start, and call-outs on but written down (`--callout-probe`) instead of spoken.
    private func launch() -> XCUIApplication {
        AppNav.launch(["--demo-mode", "off", "--transport", "wifi", "--host", "127.0.0.1:9", "--callout-probe"])
    }

    /// "Try without a Pi", then waits for the pretend Pi to connect (the club menu turns on).
    private func enterDemo(_ app: XCUIApplication) {
        let tryDemo = app.buttons["dashboard.tryDemo"]
        scroll(app, to: tryDemo)
        tryDemo.tap()
        XCTAssertTrue(app.descendants(matching: .any)["app.demoBadge"].waitForExistence(timeout: 10))
        let hit = app.buttons["dashboard.hitShot"]
        XCTAssertTrue(hit.waitForExistence(timeout: 10))
        let club = app.descendants(matching: .any)["dashboard.clubSelector"].firstMatch
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: club)
        waitForExpectations(timeout: 10)
    }

    /// "Hit a shot", then waits for the Demo-tagged latest shot card.
    private func hitAShot(_ app: XCUIApplication) {
        app.swipeDown()
        let hit = app.buttons["dashboard.hitShot"]
        scroll(app, to: hit)
        hit.tap()
        XCTAssertTrue(app.descendants(matching: .any)["dashboard.demoShotTag"].waitForExistence(timeout: 15))
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
