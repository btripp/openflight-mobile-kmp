// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import XCTest
@testable import OpenFlight

/// The Swift ↔ Kotlin ViewModel bridge (plan R2): state collection on the main actor, event
/// forwarding and `onCleared` on release. Runs hosted in the app, which has started Koin.
@MainActor
final class ViewModelHostTests: XCTestCase {
    func testStartsWithTheViewModelsCurrentState() {
        let viewModel = KoinHelper().dashboardViewModel()
        let host = ViewModelHost(viewModel)

        XCTAssertTrue(host.state.connection.isEqual(viewModel.state.connection))
    }

    func testSendForwardsEventsAndStateUpdatesArriveOnTheMainActor() async throws {
        let host = ViewModelHost(KoinHelper().dashboardViewModel())

        host.send(DashboardEventHostEdited(text: "bridge-test.local:8080"))

        let deadline = Date().addingTimeInterval(5)
        while host.state.connection.hostText != "bridge-test.local:8080", Date() < deadline {
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertEqual(host.state.connection.hostText, "bridge-test.local:8080")
    }

    func testReleasingTheHostClearsTheViewModel() {
        let viewModel = KoinHelper().dashboardViewModel()
        let closeable = RecordingCloseable()
        viewModel.addCloseable(closeable: closeable)

        var host: ViewModelHost<DashboardViewModel>? = ViewModelHost(viewModel)
        XCTAssertNotNil(host)
        XCTAssertFalse(closeable.closed)

        host = nil

        // ViewModel.clear() closes its closeables, then calls onCleared().
        XCTAssertTrue(closeable.closed)
    }
}

private final class RecordingCloseable: NSObject, KotlinAutoCloseable {
    private(set) var closed = false

    func close() {
        closed = true
    }
}
