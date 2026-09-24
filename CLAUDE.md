# OpenFlight Companion (KMP): project conventions

Wire protocol facts, client behaviour to preserve and the step plan live in
`plans/openflight-kmp-app.md` (§0 = ground truth). Read that first. Don't infer protocol
details from memory.

## Build environment
- There's no system JDK on the dev machine. Use
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- `local.properties` (gitignored) holds `sdk.dir`.
- Package root and applicationId: `dev.openflight.companion`.

## Module graph (arrows point from a module to its dependencies; core never depends on feature/app)
```
androidApp ─┐
iosApp (Xcode, embeds ComposeApp.framework) ─┐
            └──> composeApp (app shell: nav host, DI graph, App())
                    ├──> feature:dashboard | feature:calibration | feature:range
                    │        └──> core:data ──> core:ble / core:network ──> core:protocol ──> core:model
                    ├──> feature:range ──> core:flight ──> core:model
                    └──> all feature:* ──> core:designsystem ; feature:calibration ──> core:sensors
```

## Adding a module
1. Add `include(":core:foo")` to `settings.gradle.kts`.
2. In `core/foo/build.gradle.kts`, add `plugins { alias(libs.plugins.openflight.kmp.library) }`
   (or `openflight.kmp.compose` for UI modules).
3. The namespace defaults to `dev.openflight.companion.core.foo`. Android + iosArm64 +
   iosSimulatorArm64 targets, Android host tests, the commonTest deps, spotless and detekt
   all come from the plugin.
4. Depend on other modules through typesafe accessors: `implementation(projects.core.model)`.

Convention plugin ids: `openflight.kmp.library`, `openflight.kmp.compose`,
`openflight.android.application`, `openflight.spotless`, `openflight.detekt`
(`build-logic/convention`).

## Tests
- commonTest: `kotlin.test` + **assertk** (Truth-style assertions), **Turbine** for Flows,
  `kotlinx-coroutines-test` `runTest`. Android-only tests may use Truth.
- No `test` prefix on test names. Instrumented/UI tests use `given_when_then` or `when_then`.
- Port assertions and numbers from the reference `ios/OpenFlightTests`. Don't reinvent them.

## Invariants (check after every change)
1. `./gradlew spotlessCheck detekt` passes. Run `spotlessApply` first.
2. `./gradlew allTests` passes. It runs `testAndroidHostTest` and `iosSimulatorArm64Test`.
3. `./gradlew :androidApp:assembleDebug :composeApp:linkDebugFrameworkIosSimulatorArm64`
   succeeds.
4. No `core:*` module depends on `feature:*`, `composeApp` or an app module.
5. Feature UIs use only the `core:designsystem` wrappers (`Of*`), never raw Material3.
6. No Android framework types in `commonMain`, and none in the public API of `core:data` or
   `core:model`.
7. Don't commit generated screenshot goldens (they're gitignored).
8. Every Kotlin file carries `// SPDX-License-Identifier: AGPL-3.0-or-later`. Spotless adds
   and enforces it.
