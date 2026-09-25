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
  and CI's grep guard catch Compose. Nothing catches the rest automatically.
- Keep `expect`/`actual` rare and small. The repo's pattern is an `expect` factory or Koin module
  (`expect val platformDataModule: Module`, `expect fun openFlightHttpClient(): HttpClient`,
  `expect fun deviceModel(): String`). Prefer an interface in `commonMain`, with the platform
  implementation bound in the platform Koin module, over a new `expect class`.
- `actual` declarations live in `<File>.android.kt` / `<File>.ios.kt` under the same package.
- A feature module's `commonMain` holds only the `ViewModel`, `UiState`, events, effects and pure
  helpers. ViewModels expose `uiState: StateFlow<…>`, take intents through a single
  `onEvent(event)`, and emit one-shot effects as `val effects: Flow<…>` (a `Channel`'s
  `receiveAsFlow()`). Display formatting goes in pure helpers, not in the UI layers.
- A new ViewModel, or a new state/effect flow Swift must observe, needs a
  `@NativeCoroutinesState` / `@NativeCoroutines` extension in
  `shared/src/iosMain/kotlin/dev/openflight/companion/NativeViewModels.kt`, and new ViewModels a
  `KoinHelper` accessor. Without them, iOS can't see it.
- `iosMain` cinterop code opts in locally with `@OptIn(ExperimentalForeignApi::class)` (or
  `BetaInteropApi`). Don't add module-wide opt-ins.
- Protocol and wire-format facts come from `plans/openflight-kmp-app.md` §0. Don't infer them.
- `commonTest`: `kotlin.test` + assertk, Turbine for flows, `runTest`, fakes from `core:testing`.
  These run on both the Android host and the iOS simulator (`allTests`), so no JVM-only APIs.
