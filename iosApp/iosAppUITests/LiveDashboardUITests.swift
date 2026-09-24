// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest

/// End-to-end against a running OpenFlight server (plan R2 exit criteria). Skipped unless
/// `OPENFLIGHT_LIVE_HOST` is set, e.g.:
///
///     uv run openflight-server --mock --web-port 8094
///     TEST_RUNNER_OPENFLIGHT_LIVE_HOST=localhost:8094 xcodebuild test … \
///         -only-testing:iosAppUITests/LiveDashboardUITests
///
/// Then `curl localhost:8094/api/club` reports `7-iron`.
final class LiveDashboardUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testWifiConnectsAndClubChangeReachesTheServer() throws {
        let liveHost = ProcessInfo.processInfo.environment["OPENFLIGHT_LIVE_HOST"] ?? ""
        try XCTSkipIf(liveHost.isEmpty, "Set OPENFLIGHT_LIVE_HOST (host:port of a running openflight-server).")

        let app = XCUIApplication()
        // The real repository (no --ui-testing): the app connects on its own.
        app.launch()

        // Wi-Fi transport.
        let wifi = app.segmentedControls["dashboard.transport"].buttons["Wi-Fi"]
        XCTAssertTrue(wifi.waitForExistence(timeout: 10))
        wifi.tap()

        // Enter the host and submit it (the field applies on submit only).
        let hostField = app.textFields["dashboard.host"]
        XCTAssertTrue(hostField.waitForExistence(timeout: 5))
        hostField.tap()
        if let current = hostField.value as? String, !current.isEmpty, current != hostField.placeholderValue {
            hostField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        hostField.typeText(liveHost + "\n")

        // Wait for Connected.
        let status = app.staticTexts["dashboard.status"]
        let connected = NSPredicate(format: "label == %@", "Connected")
        expectation(for: connected, evaluatedWith: status)
        waitForExpectations(timeout: 30)

        // The club menu is enabled once connected and the club sync has finished.
        let clubMenu = app.buttons["dashboard.clubSelector"]
        let enabled = NSPredicate(format: "isEnabled == true")
        expectation(for: enabled, evaluatedWith: clubMenu)
        waitForExpectations(timeout: 15)
        clubMenu.tap()

        let sevenIron = DashboardUITests.menuItem(app, "7-Iron")
        XCTAssertTrue(sevenIron.waitForExistence(timeout: 5))
        sevenIron.tap()

        // The server's answer is persisted as the selected club; the label follows.
        let selected = NSPredicate(format: "label CONTAINS %@", "7-Iron")
        expectation(for: selected, evaluatedWith: clubMenu)
        waitForExpectations(timeout: 15)
        XCTAssertFalse(app.buttons["dashboard.clubError"].exists)
    }
}
