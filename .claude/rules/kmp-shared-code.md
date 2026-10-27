---
paths:
  - "**/src/commonMain/**/*.kt"
  - "**/src/commonTest/**/*.kt"
  - "**/src/iosMain/**/*.kt"
  - "**/src/androidMain/**/*.kt"
---

# Shared KMP code (commonMain and platform source sets)

- `commonMain` is pure Kotlin: no `android.*`, `androidx.compose.*`, `org.jetbrains.compose.*`,
  Apple `platform.*`, or `java.*` APIs without a common equivalent. `verifyNoComposeInCommonMain`
  and CI's grep guard catch Compose; the common and iOS compiles reject `android.*`/`java.*`
  (checked 2026-09-30), so a local Android-only build won't catch them. Run `allTests` or link iOS.
- Keep `expect`/`actual` rare and small. The repo's pattern is an `expect` factory or Koin module
  (`expect val platformDataModule: Module`, `expect fun openFlightHttpClient(): HttpClient`,
  `expect fun deviceModel(): String`). Prefer an interface in `commonMain`, with the platform
  implementation bound in the platform Koin module, over a new `expect class`.
- `actual` declarations live in `<File>.android.kt` / `<File>.ios.kt` under the same package.
- A feature module's `commonMain` holds only the `ViewModel`, `UiState`, events, effects and pure
  helpers. ViewModels expose `uiState: StateFlow<…>`, take intents through a single
  `onEvent(event)`, and emit one-shot effects as `val effects: Flow<…>` (a `Channel`'s
  `receiveAsFlow()`). Display formatting goes in pure helpers, not in the UI layers. The effects
  Channel deliberately departs from Android's "don't send events from the ViewModel to the UI"
  recommendation. Keep it (see `kotlin-coroutines.md`).
- A new ViewModel, or a new state/effect flow Swift must observe, needs a
  `@NativeCoroutinesState` / `@NativeCoroutines` extension in
  `shared/src/iosMain/kotlin/dev/openflight/companion/NativeViewModels.kt`, and new ViewModels a
  `KoinHelper` accessor. Without them, iOS can't see it.
- `iosMain` cinterop code opts in locally with `@OptIn(ExperimentalForeignApi::class)` (or
  `BetaInteropApi`). Don't add module-wide opt-ins.
- Protocol and wire-format facts come from `plans/openflight-kmp-app.md` §0. Don't infer them.
- `commonTest`: `kotlin.test` + assertk, Turbine for flows, `runTest`, fakes from `core:testing`.
  These run on both the Android host and the iOS simulator (`allTests`), so no JVM-only APIs.

## Pi backend compatibility (stock upstream vs #297)

Testers run **stock upstream** Pis. The README's "Backend compatibility" table is the source of
truth. Stock has Socket.IO and Bluetooth LE schema 2 (open-flight/openflight#285, Pi started with
`--ble`), but no BLE reconnect catch-up, no SSE `/api/shots/stream` and no `/api/club`. Those three
arrive with open-flight/openflight#297, not merged yet. Phone calibration
(`/api/calibration/iwr6843/orientation`) is on neither; it's open-flight/openflight#298. Every app
feature must work on stock or say in plain words why it can't. Never show a raw status code, and
never wait or spin forever.

- A missing route on stock answers **GET 404 but POST 405**, because the GET-only static catch-all
  `/<path:path>` still matches. Detect absence with `OpenFlightHttpError.UnexpectedStatus.isRouteAbsent`,
  never `statusCode == 404`. (Tester bug 2026-09: club changes and calibration showed
  "OpenFlight returned HTTP 405.")
- A fake's status codes and payloads must be copied from a real server response, with the backend
  commit it came from. Don't write down what you assume the server returns. `MockServerIT`'s
  route contract pins the real matrix (stock: 404/405/405; #297: 200/400 for `/api/club`, calibration still 405 until #298).
- An integration test drives the data layer in the **app's real call order**. Don't add setup
  calls the app never makes (a `currentClub()` read in `MockServerIT` hid the 405 by enabling
  the fallback early).
- A new Pi-dependent feature needs a stock-Pi case in `StockPiFallbackTest` (or its feature's
  VM test) and a `MockServerIT` step that runs against both backends.
- A state that can last forever (e.g. `ConnectionState.Scanning`) needs a timed, plain-words
  explanation (see `DashboardViewModel.BLUETOOTH_NOT_FOUND_AFTER_MILLIS`).
