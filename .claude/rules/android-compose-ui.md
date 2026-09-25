---
paths:
  - "feature/*/ui/**/*.kt"
  - "core/designsystem/**/*.kt"
  - "androidApp/**/*.kt"
---

# Android Jetpack Compose UI (feature:*:ui, core:designsystem, androidApp)

- Use the `core:designsystem` wrappers (`OfScaffold`, `OfButton`, `OfCard`, `OfChip`,
  `OfMetricPrimary`, …). Never import `androidx.compose.material3` in a feature UI module; if a
  wrapper is missing, add it to `core:designsystem` first, with a `@Preview`.
- Screens split into a `<Name>Route` (gets the ViewModel with `koinViewModel()`, collects
  `uiState` with `collectAsStateWithLifecycle()`, collects `effects`, wires navigation) and a
  stateless `<Name>Screen(uiState, onEvent, …, modifier: Modifier = Modifier)` that tests and
  previews call directly.
- A `:ui` module depends on its own `feature:<name>` plus `core:*`, never on another feature.
  Navigation between features goes through lambdas the `androidApp` NavHost supplies.
- Test tags shared with iOS live in `<Name>TestTags` in the feature's `commonMain`; Android-only
  ones go in `<Name>UiTags` in the `:ui` module. Don't hard-code tag strings in tests.
- Device UI tests (`src/androidTest`) use `createAndroidComposeRule<ComponentActivity>()` and
  `given_when_then` / `when_then` names (e.g. `givenAShot_whenShown_thenMetricsShow`). Run with
  `./gradlew :feature:<name>:ui:connectedDebugAndroidTest` (emulator needed; not in `allTests`
  or CI).
- Port expected values from the reference `ios/OpenFlightTests`. Don't invent new numbers.
