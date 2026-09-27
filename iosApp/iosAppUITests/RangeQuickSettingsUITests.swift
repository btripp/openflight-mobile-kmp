// SPDX-License-Identifier: AGPL-3.0-or-later
import UIKit
import XCTest

/// Plan F8f: the range quick settings (the iOS twin of Android's `RangeQuickSettingsTest`). The gear
/// in the controls row opens them: a sheet over the scene on an iPhone (swipe down to close), a side
/// panel beside the scene on an iPad (Done closes it). Changing the trail and the show mode updates the
/// scene without leaving the range.
///
/// `--range-mode --preview-shot --preview-flight` opens the range flying the preview driver, held
/// landed by `--range-freeze-progress 1`; `--preview-history` stores the current session's three
/// shots. A fake-repository launch starts the range on "Live" whatever an earlier test stored; the
/// trail goes back to Classic after each test (settings outlive the app).
final class RangeQuickSettingsUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--preview-shot", "--shot-trail", "classic"]
        app.launch()
        app.terminate()
        super.tearDown()
    }

    /// The gear opens the settings over (iPhone) or beside (iPad) the scene; a swipe down or Done
    /// closes them, and the range is still there.
    func testTheGearOpensTheQuickSettingsAndTheyClose() {
        let app = launchRange()
        app.buttons["range.quickSettings"].tap()
        let panel = element("range.quickSettings.panel", in: app)
        XCTAssertTrue(panel.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["range.quickSettings.show.live"].isSelected)

        if UIDevice.current.userInterfaceIdiom == .pad {
            // Beside the scene, not over it.
            let scene = element("range.scene", in: app)
            XCTAssertTrue(scene.exists)
            XCTAssertGreaterThanOrEqual(panel.frame.minX, scene.frame.maxX - 1, "the panel covers the scene")
            app.buttons["range.quickSettings.close"].tap()
        } else {
            panel.swipeDown(velocity: .fast)
        }
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: panel)
        waitForExpectations(timeout: 5)
        XCTAssertTrue(app.buttons["range.exit"].exists, "left the range")
    }

    /// Neon, picked in the panel, is drawn on the landed flight at once; "Last 5" overlays the current
    /// session (the transport and club filter appear), all without leaving the range.
    func testChangingTheTrailAndShowModeUpdatesTheSceneInPlace() {
        let app = launchRange()
        let scene = element("range.scene", in: app)
        XCTAssertTrue(scene.waitForExistence(timeout: 5))
        sleep(1)
        XCTAssertEqual(pinkPixels(scene.screenshot().image), 0, "classic has no neon pink")
        attachScreen("scene-before")

        app.buttons["range.quickSettings"].tap()
        let neon = app.buttons["range.quickSettings.trail.neon"]
        scrollTo(neon, in: app)
        neon.tap()
        waitUntilSelected(neon)
        attachScreen("panel-open")
        closePanel(app)
        waitUntil("the neon trail is drawn") { self.pinkPixels(scene.screenshot().image) > 40 }
        attachScreen("scene-after-trail")

        app.buttons["range.quickSettings"].tap()
        let lastFive = app.buttons["range.quickSettings.show.last_5"]
        XCTAssertTrue(lastFive.waitForExistence(timeout: 5))
        lastFive.tap()
        waitUntilSelected(lastFive)
        XCTAssertTrue(app.buttons["range.quickSettings.club.all"].waitForExistence(timeout: 5), "no club filter")
        closePanel(app)
        XCTAssertTrue(element("range.transport", in: app).waitForExistence(timeout: 5), "not overlaying")
        attachScreen("scene-after-show")
        XCTAssertTrue(app.buttons["range.exit"].exists, "left the range")

        // Back to live for the tests after this one.
        app.buttons["range.quickSettings"].tap()
        let live = app.buttons["range.quickSettings.show.live"]
        XCTAssertTrue(live.waitForExistence(timeout: 5))
        live.tap()
        waitUntilSelected(live)
    }

    /// Two people on one Pi (`--preview-profiles`: Ann active, Bo): the range shows the active
    /// profile's shots by default, a pinned profile's, or everyone's, chosen on this device only.
    /// The current session holds Ann's driver and 7-iron and Bo's driver.
    func testTheViewingProfileFiltersTheCurrentSessionsOverlay() {
        let app = launchRange(extra: ["--preview-profiles"])
        openPanel(app)
        let active = app.buttons["range.quickSettings.profile.follow_active"]
        XCTAssertTrue(active.waitForExistence(timeout: 5), "no viewing profile control")
        XCTAssertTrue(active.isSelected)
        app.buttons["range.quickSettings.show.this_session"].tap()
        closePanel(app)
        waitForOverlayCount("2", in: app)

        openPanel(app)
        let bo = app.buttons["range.quickSettings.profile.profile:bo"]
        XCTAssertTrue(bo.waitForExistence(timeout: 5))
        bo.tap()
        waitUntilSelected(bo)
        closePanel(app)
        waitForOverlayCount("1", in: app)

        openPanel(app)
        let everyone = app.buttons["range.quickSettings.profile.all_profiles"]
        XCTAssertTrue(everyone.waitForExistence(timeout: 5))
        everyone.tap()
        waitUntilSelected(everyone)
        closePanel(app)
        waitForOverlayCount("3", in: app)
    }

    // MARK: Helpers

    /// Keeps a full-screen screenshot in the result bundle (evidence for the panel and the scene).
    private func attachScreen(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func openPanel(_ app: XCUIApplication) {
        app.buttons["range.quickSettings"].tap()
        XCTAssertTrue(element("range.quickSettings.panel", in: app).waitForExistence(timeout: 5))
    }

    /// The overlay bar's "All clubs" chip counts the drawn shots.
    private func waitForOverlayCount(_ count: String, in app: XCUIApplication) {
        let allClubs = app.buttons["range.overlayClub.all"]
        XCTAssertTrue(allClubs.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "label ENDSWITH %@", count), evaluatedWith: allClubs)
        waitForExpectations(timeout: 10)
    }

    private func launchRange(extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "--ui-testing", "--preview-shot", "--range-mode", "--preview-history", "--preview-flight",
            "--range-freeze-progress", "1", "--shot-trail", "classic",
        ] + extra
        app.launch()
        XCTAssertTrue(app.buttons["range.quickSettings"].waitForExistence(timeout: 15))
        return app
    }

    private func closePanel(_ app: XCUIApplication) {
        let panel = element("range.quickSettings.panel", in: app)
        if UIDevice.current.userInterfaceIdiom == .pad {
            app.buttons["range.quickSettings.close"].tap()
        } else {
            // Drag the sheet down by its top edge (a swipe over the chips can land as a tap).
            var attempts = 0
            while panel.exists, attempts < 3 {
                let top = panel.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.01))
                top.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.98)))
                attempts += 1
                _ = panel.waitForNonExistence(timeout: 2)
            }
        }
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: panel)
        waitForExpectations(timeout: 5)
    }

    private func scrollTo(_ target: XCUIElement, in app: XCUIApplication) {
        let panel = element("range.quickSettings.panel", in: app)
        XCTAssertTrue(panel.waitForExistence(timeout: 5))
        var attempts = 0
        while !(target.exists && target.isHittable), attempts < 8 {
            panel.swipeUp(velocity: .slow)
            attempts += 1
        }
        XCTAssertTrue(target.isHittable, "\(target) not reachable")
    }

    private func element(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    private func waitUntilSelected(_ element: XCUIElement) {
        expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: element)
        waitForExpectations(timeout: 5)
    }

    private func waitUntil(_ message: String, timeout: TimeInterval = 10, _ condition: @escaping () -> Bool) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        XCTFail(message)
    }

    /// The neon tube's hot pink (1, 0.16, 0.78), which no theme's scene or the range's UI uses.
    private func pinkPixels(_ image: UIImage) -> Int {
        guard let cgImage = image.cgImage else { return 0 }
        let width = cgImage.width
        let height = cgImage.height
        var data = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(
            data: &data,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return 0 }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        var count = 0
        for index in stride(from: 0, to: data.count, by: 4) {
            let red = Double(data[index]) / 255
            let green = Double(data[index + 1]) / 255
            let blue = Double(data[index + 2]) / 255
            if red > 0.8 && green < 0.4 && blue > 0.55 { count += 1 }
        }
        return count
    }
}
