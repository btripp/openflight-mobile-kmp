---
paths:
  - "**/src/commonTest/**/*.kt"
  - "**/src/androidHostTest/**/*.kt"
  - "**/src/iosTest/**/*.kt"
  - "**/src/test/**/*.kt"
  - "**/src/androidTest/**/*.kt"
  - "core/testing/**/*.kt"
---

# Tests

This overrides any general Kotlin testing skill or plugin (some installed ones default to Kotest,
MockK or Kover). Here:

- **Stack:** `kotlin.test` + **assertk**, **Turbine** for flows, `kotlinx-coroutines-test`
  `runTest`. Android-only tests may use Truth. **Don't add** Kotest, MockK, Mockito, Robolectric,
  Kover or any other test framework or mocking library.
- **Fakes, not mocks.** Shared fakes live in `core:testing` (`FakeRepositories.kt`,
  `FakePiSessionRepository`, …). Extend those, or write a small fake in the test. Status codes and
  payloads a fake returns are copied from a real server response (see `kmp-shared-code.md`).
- **ViewModel tests:** `Dispatchers.setMain(dispatcher)` in `@BeforeTest`, `resetMain()` in
  `@AfterTest`, and pass the same `dispatcher` to the VM's injected dispatcher parameters
  (`computeDispatcher = dispatcher`). Most tests use `UnconfinedTestDispatcher`; use
  `StandardTestDispatcher` + `advanceUntilIdle()` when the test is about ordering. Collect
  long-lived flows in `backgroundScope`.
- **Flows:** assert with Turbine (`flow.test { awaitItem() … }`), not hand-rolled collectors or
  `toList()` on an infinite flow. No `Thread.sleep`/`runBlocking` for timing; use virtual time.
- **commonTest runs twice** (Android host and iOS simulator in `allTests`), so no JVM-only APIs.
- **Names:** no `test` prefix; plain camelCase sentences in unit tests
  (`anEmptyBagIsSeededWithTheDefaultFourteen`). Device UI tests use `given_when_then` /
  `when_then` on `createAndroidComposeRule<ComponentActivity>()`.
- **Expected values:** port them from the reference `ios/OpenFlightTests`. Don't invent numbers.
  Assert intended behaviour, not whatever the implementation happens to return.
