---
name: new-feature-module
description: Scaffold a new feature end to end (KMP feature:<name> with ViewModel/UiState/events/effects, Android-only feature:<name>:ui with Route/Screen, Koin wiring, the iOS bridge, a SwiftUI view, the CLAUDE.md module lists), then run the /verify chain.
disable-model-invocation: true
argument-hint: "<name> (lowercase, e.g. drills)"
---

Scaffold `feature:<name>` and `feature:<name>:ui` and wire them into both apps. `<name>` is
lowercase letters only (the module-graph regex `:feature:([a-z]+)` relies on it); `<Name>` is its
PascalCase form. Package: `dev.openflight.companion.feature.<name>` in both modules.

Before writing, read one existing feature end to end as the pattern (`feature/training` is
small: `TrainingViewModel.kt`, `TrainingModule.kt`, `TrainingRoute.kt`, `TrainingView.swift`).
If `feature/<name>` already exists, stop and ask. Ask the user which `core:*` modules the
ViewModel needs and where the screen is reached from (a new top-level tab, or a pushed route
from which parent screen) when the arguments don't say.

Every Kotlin and Swift file starts with `// SPDX-License-Identifier: AGPL-3.0-or-later`.
Templates below are the minimum; add only what the feature needs.

## 1. Gradle modules

`settings.gradle.kts`: append (keep the `:ui` next to it, like `bag`):
```kotlin
include(":feature:<name>")
include(":feature:<name>:ui")
```

`feature/<name>/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.openflight.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // A feature depends on core modules only, never on another feature.
            implementation(projects.core.data)
            implementation(projects.core.model)
            implementation(libs.kotlinx.coroutines.core)
            // Shared presentation (ADR 0001): the KMP ViewModel base class, no Compose.
            api(libs.androidx.lifecycle.viewmodel)
            api(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
            implementation(libs.koin.core.viewmodel)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
```

`feature/<name>/ui/build.gradle.kts`:
```kotlin
plugins {
    // ADR 0001: the Android (Jetpack Compose) UI for the shared <Name>ViewModel.
    alias(libs.plugins.openflight.android.library.compose)
}

dependencies {
    // Its own shared feature module plus core modules only, never another feature.
    implementation(projects.feature.<name>)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.androidx.compose)
}
```

## 2. Shared feature (`feature/<name>/src/commonMain/kotlin/dev/openflight/companion/feature/<name>/`)

No Compose and no Android types here (`verifyNoComposeInCommonMain` and the iOS compile reject
them). Follow `.claude/rules/kmp-shared-code.md` and `kotlin-coroutines.md`.

`<Name>UiState.kt`:
```kotlin
/** What the <name> screen renders. */
data class <Name>UiState(
    val example: String = "",
)
```

`<Name>Event.kt`:
```kotlin
/** User intents from the <name> screen, sent up to [<Name>ViewModel.onEvent]. */
sealed interface <Name>Event {
    data object ExampleTapped : <Name>Event
}
```

`<Name>Effect.kt` (only if the screen needs one-shot effects; otherwise drop it, the `effects`
property and the `sideEffects` bridge in step 5):
```kotlin
/** One-shot effects for the <name> screen, read from [<Name>ViewModel.effects]. */
sealed interface <Name>Effect {
    data object ExampleDone : <Name>Effect
}
```

`<Name>ViewModel.kt`:
```kotlin
class <Name>ViewModel(
    private val settings: SettingsRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(<Name>UiState())
    val uiState: StateFlow<<Name>UiState> = mutableUiState.asStateFlow()

    private val effectChannel = Channel<<Name>Effect>(Channel.BUFFERED)
    val effects: Flow<<Name>Effect> = effectChannel.receiveAsFlow()

    fun onEvent(event: <Name>Event) {
        when (event) {
            <Name>Event.ExampleTapped -> viewModelScope.launch { effectChannel.send(<Name>Effect.ExampleDone) }
        }
    }
}
```
The Swift bridge relies on exactly these names: `uiState`, `effects` and `onEvent(event)`.

`<Name>TestTags.kt` (tags shared with iOS):
```kotlin
/** Test tags for the <name> screen, shared by Compose and SwiftUI. */
object <Name>TestTags {
    const val DONE = "<name>.done"
}
```

`<Name>Module.kt`:
```kotlin
/** Koin bindings for this feature. Needs `core:data`'s `dataModule` in the same graph. */
val <name>Module: Module =
    module {
        viewModelOf(::<Name>ViewModel)
    }
```
Use `viewModel { <Name>ViewModel(a = get(), …) }` instead when a constructor parameter has a
default that nothing in the graph provides (a dispatcher, a clock lambda; see `CalibrationModule.kt`).

`feature/<name>/src/commonTest/.../<Name>ViewModelTest.kt`: at least one test of real behaviour,
per `.claude/rules/testing.md` (assertk, Turbine, `Dispatchers.setMain` in `@BeforeTest`,
`resetMain()` in `@AfterTest`, fakes from `core:testing`, no `test` prefix). Pattern:
`TrainingViewModelTest.kt`.

## 3. Android UI (`feature/<name>/ui/src/main/kotlin/dev/openflight/companion/feature/<name>/`)

Follow `.claude/rules/android-compose-ui.md`: `Of*` wrappers only (no `androidx.compose.material3`
import), compact and expanded layouts via `OfWindowClass`, phone and tablet previews.

`<Name>Route.kt`:
```kotlin
/** The <name> destination: owns the [<Name>ViewModel] and hands [<Name>Screen] its state. */
@Composable
fun <Name>Route(
    onBack: () -> Unit,
    viewModel: <Name>ViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    <Name>Screen(uiState = uiState, onEvent = viewModel::onEvent, onBack = onBack)
}
```
Collect `viewModel.effects` in a `LaunchedEffect` here if the feature has effects.

`<Name>Screen.kt`: stateless `<Name>Screen(uiState, onEvent, onBack, modifier: Modifier = Modifier,
windowClass: OfWindowClass = rememberOfWindowClass())`, plus `<Name>Previews.kt` with a phone
preview and a `@Preview(widthDp = 1280, heightDp = 800)` one.

`feature/<name>/ui/src/androidTest/.../<Name>ScreenTest.kt`: `createAndroidComposeRule<ComponentActivity>()`,
`OfTheme { <Name>Screen(…) }`, `given_when_then` names, one compact test and one with
`OfWindowClass.EXPANDED` forced. Pattern: `CalibrationScreenTest.kt`.

## 4. App wiring (Android + Koin)

- `shared/build.gradle.kts`: add `export(projects.feature.<name>)` to the framework `export`
  list and `api(projects.feature.<name>)` to `commonMain.dependencies` (export needs api).
- `shared/src/commonMain/kotlin/dev/openflight/companion/AppKoin.kt`: import `<name>Module` and
  **append** it to `appModules` with a one-line comment (appending keeps the diff mergeable).
- `androidApp/build.gradle.kts`: `implementation(projects.feature.<name>.ui)` next to the other
  `feature.*.ui` lines.
- `androidApp/src/main/kotlin/dev/openflight/companion/AppNavHost.kt`: a `@Serializable data object
  <Name>` route with a doc comment, `composable<<Name>> { <Name>Route(onBack = onBack) }`, and an
  entry point: a nullable `onOpen<Name>` lambda on the parent screen, or a `TopLevelDestination`
  entry if the user asked for a new tab (five tabs at most).

## 5. iOS bridge and SwiftUI

- `shared/src/iosMain/kotlin/dev/openflight/companion/KoinHelper.kt`: import the VM and add
  `fun <name>ViewModel(): <Name>ViewModel = get()` (`get { parametersOf(…) }` for a VM that
  takes a runtime argument).
- `shared/src/iosMain/kotlin/dev/openflight/companion/NativeViewModels.kt`: import the types and add
  ```kotlin
  @NativeCoroutinesState
  val <Name>ViewModel.state: StateFlow<<Name>UiState>
      get() = uiState

  @NativeCoroutines
  val <Name>ViewModel.sideEffects: Flow<<Name>Effect>
      get() = effects
  ```
- `iosApp/iosApp/Bridge/ViewModelHost.swift`: `extension <Name>ViewModel: SharedViewModel {}`.
- `iosApp/iosApp/<Name>/<Name>View.swift` (the Xcode project uses synchronized folders, so no
  `project.pbxproj` edit), per `.claude/rules/ios-swiftui.md`:
  ```swift
  // SPDX-License-Identifier: AGPL-3.0-or-later
  import Shared
  import SwiftUI

  /// <Name>, over the shared `<Name>ViewModel`. Android's `<Name>Screen.kt` renders the same state.
  struct <Name>View: View {
      @StateObject private var host = ViewModelHost(KoinHelper().<name>ViewModel())

      var body: some View {
          <Name>Content(state: host.state, send: host.send)
              .navigationTitle("<Title>")
      }
  }

  struct <Name>Content: View {
      let state: <Name>UiState
      let send: (<Name>Event) -> Void

      var body: some View {
          // Theme/Components from DesignSystem/, identifiers from <Name>TestTags.shared.
      }
  }
  ```
  Reach it from `AppRoot.swift`: an `AppRoute` case rendered by `AppRouteDestination` (pushed),
  or an `AppTab` matching the Android `TopLevelDestination` (same label and order).

## 6. Docs

- `CLAUDE.md`: add `feature:<name>:ui` to the `androidApp` line and `<name>` to the `shared ──>`
  line of the module graph, add a `feature:<name> ──> …` arrow if it depends on anything beyond
  `core:data`/`core:model`, and add `<name>` to "Current `feature:*` modules".
- `CHANGELOG.md`: a line under `## [Unreleased]` if the screen is user-facing.

## 7. Verify

Run the `/verify` chain (`.claude/skills/verify/SKILL.md`; it is user-invoked only, so follow its
steps directly): `spotlessApply`, then the full invariant chain with its exit code captured, then
the `xcodebuild … build` (this skill always touches `iosApp/` and `shared/src/iosMain/`).
`:androidApp:assertModuleGraph` checks the new modules' dependencies automatically; a
`violates:` line means a feature depends on another feature, or a core module on a feature.
Report each step's exit code. Remind the user that device UI tests
(`./gradlew :feature:<name>:ui:connectedDebugAndroidTest`) and the Xcode test targets aren't part
of the chain.
