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

## Layouts (phones and tablets, plan F1a)

- Every new screen ships a compact **and** an expanded layout. Branch on `OfWindowClass`
  (`rememberOfWindowClass()`: COMPACT < 600 dp ≤ MEDIUM < 840 dp ≤ EXPANDED), and take it as a
  `windowClass: OfWindowClass = rememberOfWindowClass()` parameter so tests and previews can
  force either layout. Never stretch a phone layout across a tablet, and don't assume portrait.
- Use the `core:designsystem` building blocks: `OfListDetailPane` for list/detail (two panes only
  on EXPANDED), `OfContentWidth` (840 dp max, centered) for forms and text-heavy screens.
- Top-level navigation lives in the app shell (`AppNavHost`'s `OfAdaptiveScaffold`: bottom bar
  on compact, rail on medium/expanded). Feature screens never draw their own app navigation bar.
  A new top-level destination is a `TopLevelDestination` entry; anything else is a pushed route
  and hides the bar/rail.
- Every new screen needs a tablet device test (force `OfWindowClass.EXPANDED`, don't depend on
  the emulator's size) plus a `@Preview(widthDp = 1280, heightDp = 800)` next to the phone one.
