// SPDX-License-Identifier: AGPL-3.0-or-later
import UIKit
import XCTest

/// Plan F8b: the range's replay, overlay, view gestures and iPad side pane, on the shared
/// `DrivingRangeViewModel` (the iOS twin of Android's `RangeReplayTest`).
///
/// `--range-mode` opens the range at launch over `--preview-shot`'s live shot (driver, 264 yds);
/// `--preview-history` stores two sessions, "preview-current" holding, oldest first, a driver
/// (264 yds), a 7-iron (165 yds) and a driver (251 yds). `--preview-live-shots` has the preview Pi
/// hit a new shot every 5 s. Runs on an iPhone and an iPad simulator.
final class RangeReplayUITests: XCTestCase {
    private static let currentSession = "preview-current"

    override func setUp() {
        continueAfterFailure = false
    }

    // MARK: Replay

    /// Replay flies the session's shots oldest first and auto-advances, at the chosen speed; it
    /// stops after the last one, and Prev steps back.
    func testReplayPlaysTheSessionInOrder() {
        let app = launchRange()
        openSessions(app).buttons["range.session.\(Self.currentSession).replay"].tap()

        let position = element("range.position", in: app)
        XCTAssertTrue(position.waitForExistence(timeout: 10))
        wait(for: position, label: "1 / 3")
        assertCarry("264", in: app)

        app.buttons["range.speed.DOUBLE"].tap()
        XCTAssertTrue(app.buttons["range.speed.DOUBLE"].isSelected)

        wait(for: position, label: "2 / 3", timeout: 20)
        assertCarry("165", in: app)
        wait(for: position, label: "3 / 3", timeout: 20)
        assertCarry("251", in: app)

        // After the last shot the transport stops.
        let playPause = app.buttons["range.playPause"]
        expectation(for: NSPredicate(format: "label == %@", "Play"), evaluatedWith: playPause)
        waitForExpectations(timeout: 20)
        XCTAssertFalse(app.buttons["range.next"].isEnabled)

        app.buttons["range.previous"].tap()
        wait(for: position, label: "2 / 3")
        assertCarry("165", in: app)
    }

    // MARK: Overlay

    /// The overlay draws the session's shots at once, filters by club, and a tap on a landing
    /// selects that shot (through the scene's tap-to-select, not the accessibility action).
    func testOverlayTapOnALandingSelectsIt() {
        let app = launchRange()
        openSessions(app).buttons["range.session.\(Self.currentSession).overlay"].tap()

        let allClubs = app.buttons["range.overlayClub.all"]
        XCTAssertTrue(allClubs.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "label CONTAINS %@", "3"), evaluatedWith: allClubs)
        waitForExpectations(timeout: 10)
        XCTAssertTrue(app.buttons["range.replaySession"].exists)

        let landings = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Landing"))
        expectation(for: NSPredicate(format: "count == 3"), evaluatedWith: landings)
        waitForExpectations(timeout: 10)

        // The 7-iron's landing, well short of the two drivers'.
        let ironLanding = landings.matching(NSPredicate(format: "label CONTAINS %@", "7-Iron")).firstMatch
        XCTAssertTrue(ironLanding.exists)
        XCTAssertFalse(ironLanding.isSelected)
        ironLanding.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()

        expectation(for: NSPredicate(format: "selected == true"), evaluatedWith: ironLanding)
        waitForExpectations(timeout: 5)
        assertCarry("165", in: app)
        XCTAssertTrue(element("range.rollOut", in: app).waitForExistence(timeout: 5))

        // The driver filter leaves the two drivers.
        app.buttons["range.overlayClub.driver"].tap()
        expectation(for: NSPredicate(format: "count == 2"), evaluatedWith: landings)
        waitForExpectations(timeout: 10)
    }

    /// Plan F8b performance check: `--preview-history-bulk` stores a 220-shot session; its overlay
    /// draws the newest 200 (the cap) and says so, and stays live under a slow one-finger pan (plan
    /// F8a2p; an orbit before) and a pinch. The redraw rate and render cost are in the
    /// `RangeCanvas` log ("range redraws …").
    func testBulkOverlayDrawsTheCapUnderAGesture() {
        let app = launchRange(extra: ["--preview-history-bulk"])
        let sheet = openSessions(app)
        let bulk = sheet.buttons["range.session.preview-bulk.overlay"]
        XCTAssertTrue(bulk.waitForExistence(timeout: 5))
        bulk.tap()

        let allClubs = app.buttons["range.overlayClub.all"]
        XCTAssertTrue(allClubs.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "label CONTAINS %@", "200"), evaluatedWith: allClubs)
        waitForExpectations(timeout: 20)
        XCTAssertTrue(element("range.overlayTruncated", in: app).exists)

        let scene = element("range.scene", in: app)
        let start = scene.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.45))
        start.press(
            forDuration: 0.05,
            thenDragTo: scene.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.45)),
            withVelocity: 150,
            thenHoldForDuration: 0.1
        )
        expectation(for: NSPredicate(format: "value CONTAINS %@", "orbit"), evaluatedWith: scene)
        waitForExpectations(timeout: 5)
        scene.pinch(withScale: 1.8, velocity: 0.5)
        XCTAssertTrue(app.buttons["range.resetView"].waitForExistence(timeout: 5))
    }

    // MARK: View gestures

    /// A pinch zooms the view (the follow camera steps aside), a double tap resets it; plan F8a2p:
    /// a one-finger drag pans like a map, and the "Reset view" chip resets that.
    func testPinchThenDoubleTapResetsTheView() {
        let app = launchRange()
        let scene = element("range.scene", in: app)
        XCTAssertTrue(scene.waitForExistence(timeout: 10))
        XCTAssertEqual(scene.value as? String, "Default view")
        XCTAssertFalse(app.buttons["range.resetView"].exists)

        scene.pinch(withScale: 2.5, velocity: 2)
        expectation(for: NSPredicate(format: "value BEGINSWITH %@", "Zoom"), evaluatedWith: scene)
        waitForExpectations(timeout: 5)
        let zoom = zoomPercent(scene)
        XCTAssertGreaterThan(zoom, 120, "value: \(String(describing: scene.value))")
        XCTAssertTrue(app.buttons["range.resetView"].exists)

        scene.doubleTap()
        expectation(for: NSPredicate(format: "value == %@", "Default view"), evaluatedWith: scene)
        waitForExpectations(timeout: 5)
        XCTAssertFalse(app.buttons["range.resetView"].exists)

        // One finger across the scene slides the range with it: the view moves left, no orbit.
        let start = scene.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.6))
        start.press(forDuration: 0.05, thenDragTo: scene.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.6)))
        expectation(
            for: NSPredicate(format: "value CONTAINS %@ AND value CONTAINS %@", "orbit 0 degrees", "metres left"),
            evaluatedWith: scene
        )
        waitForExpectations(timeout: 5)

        app.buttons["range.resetView"].tap()
        expectation(for: NSPredicate(format: "value == %@", "Default view"), evaluatedWith: scene)
        waitForExpectations(timeout: 5)
    }

    /// Plan F8a2p: a one-finger drag down the scene pulls the far range toward the viewer (the view
    /// moves downrange), in live mode, and zoom stays pinch-only (no zoom buttons on screen).
    func testOneFingerDragDownPansDownrange() {
        let app = launchRange()
        let scene = element("range.scene", in: app)
        XCTAssertTrue(scene.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["Zoom in"].exists)
        XCTAssertFalse(app.buttons["Zoom out"].exists)

        let start = scene.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.55))
        start.press(forDuration: 0.05, thenDragTo: scene.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.72)))
        expectation(
            for: NSPredicate(format: "value BEGINSWITH %@ AND value CONTAINS %@", "Zoom 100 percent", "metres downrange"),
            evaluatedWith: scene
        )
        waitForExpectations(timeout: 5)
        XCTAssertTrue(app.buttons["range.resetView"].exists)

        scene.doubleTap()
        expectation(for: NSPredicate(format: "value == %@", "Default view"), evaluatedWith: scene)
        waitForExpectations(timeout: 5)
    }

    // MARK: Live shots during replay

    /// A live shot arriving in the overlay never yanks the user out: it offers "New shot · Return
    /// to live", which flies it.
    func testNewLiveShotChipReturnsToLive() {
        let app = launchRange(extra: ["--preview-live-shots"])
        openSessions(app).buttons["range.session.\(Self.currentSession).overlay"].tap()

        let transport = element("range.transport", in: app)
        XCTAssertTrue(transport.waitForExistence(timeout: 10))
        let chip = app.buttons["range.newLiveShot"]
        XCTAssertTrue(chip.waitForExistence(timeout: 15))
        // Still in the overlay.
        XCTAssertTrue(transport.exists)

        chip.tap()
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: transport)
        expectation(for: gone, evaluatedWith: chip)
        waitForExpectations(timeout: 5)
        let status = element("range.status", in: app)
        let flying = NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@", "Ball in flight", "Calculating flight")
        expectation(for: flying, evaluatedWith: status)
        waitForExpectations(timeout: 5)
    }

    // MARK: iPad side pane

    /// On a regular width (an iPad) replay lists the session's shots beside the scene and a tap on
    /// one jumps to it; an iPhone shows the scene alone.
    func testSidePaneListsTheShotsOnIPadAndSelects() {
        let app = launchRange()
        openSessions(app).buttons["range.session.\(Self.currentSession).replay"].tap()
        let position = element("range.position", in: app)
        XCTAssertTrue(position.waitForExistence(timeout: 10))

        let list = element("range.shotList", in: app)
        guard UIDevice.current.userInterfaceIdiom == .pad else {
            XCTAssertFalse(list.exists)
            return
        }
        XCTAssertTrue(list.waitForExistence(timeout: 5))
        for id in ["1", "2", "3"] {
            XCTAssertTrue(app.buttons["range.shot.\(id)"].exists, "row \(id)")
        }
        let last = app.buttons["range.shot.3"]
        last.tap()
        wait(for: position, label: "3 / 3")
        assertCarry("251", in: app)
        expectation(for: NSPredicate(format: "selected == true"), evaluatedWith: last)
        waitForExpectations(timeout: 5)

        // Back to live: the pane goes with the transport.
        app.buttons["range.live"].tap()
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: list)
        waitForExpectations(timeout: 5)
    }

    // MARK: Helpers

    private func launchRange(extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot", "--range-mode", "--preview-history"] + extra
        app.launch()
        XCTAssertTrue(app.buttons["range.history"].waitForExistence(timeout: 15))
        return app
    }

    /// Opens the session picker and returns the app once it shows the preview sessions.
    @discardableResult
    private func openSessions(_ app: XCUIApplication) -> XCUIApplication {
        app.buttons["range.history"].tap()
        XCTAssertTrue(app.buttons["range.session.\(Self.currentSession).replay"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["range.overlayAll"].exists)
        return app
    }

    private func element(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    private func wait(
        for element: XCUIElement,
        label: String,
        timeout: TimeInterval = 10,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let deadline = Date().addingTimeInterval(timeout)
        while element.label != label, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        XCTAssertEqual(element.label, label, file: file, line: line)
    }

    private func assertCarry(_ yards: String, in app: XCUIApplication) {
        let carry = element("range.carry", in: app)
        expectation(for: NSPredicate(format: "label CONTAINS %@", yards), evaluatedWith: carry)
        waitForExpectations(timeout: 5)
    }

    /// The zoom in the scene's value, "Zoom 250 percent, orbit 0 degrees".
    private func zoomPercent(_ scene: XCUIElement) -> Int {
        let value = scene.value as? String ?? ""
        let digits = value.drop { !$0.isNumber }.prefix { $0.isNumber }
        return Int(digits) ?? 0
    }
}
