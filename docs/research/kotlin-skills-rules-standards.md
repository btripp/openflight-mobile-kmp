# Kotlin skills, rules and standards for this repo

Research notes, 2026-09-30. Question: which Claude Code skills, `.claude/rules` content and Kotlin
lint/standards should this KMP repo (native Compose + SwiftUI UIs, shared ViewModels) adopt?
Every claim links to the source that owns it. "Measured" means I ran it against this tree
(read-only; no repo files changed). Anything I couldn't confirm is marked **unverified**.

## 0. Follow-up (2026-09-30): what was adopted, and corrections

- **Adopted** (branch `chore/kotlin-standards-guardrails`): #2 Android Lint, gated with
  warnings as errors; #3 module-graph-assert, with a negative test run (a core→feature edge and a
  feature→feature edge both fail); #5 the `kotlin-coroutines.md`, `testing.md` and
  `designsystem-components.md` rules; the Games section moved to `.claude/rules/games.md`; #8
  `GlobalCoroutineUsage` + `DataClassShouldBeImmutable` (0 findings). #1 compose-rules is on its
  own branch. The rest are tracked as GitHub issues.
- **Correction to #4:** widening `verifyNoComposeInCommonMain` isn't needed. A probe file in
  `core:model/commonMain` using `java.util.UUID` and `android.os.Build` failed
  `compileCommonMainKotlinMetadata` and `compileKotlinIosSimulatorArm64` with "Unresolved
  reference" (Android's compile accepts it). The compiler already catches fully qualified uses,
  which an import regex would miss.
- **Correction to §4.1:** the repo names private flows `mutableX` (about 80 sites) and `_x` only
  twice, so the rule keeps `mutableX` rather than the conventions' `_x`. Tests mostly use
  `UnconfinedTestDispatcher` + `setMain`, not `StandardTestDispatcher`.
- **#6 resolved:** `kotlin-agent-skills@Kotlin` skills load in the session now; no action left.

## 1. TL;DR (ranked)

| # | Adopt | Effort | Why |
|---|---|---|---|
| 1 | **compose-rules for ktlint** via Spotless: `io.nlopez.compose.rules:ktlint:0.6.7` | S | Built for ktlint 1.8.0, the version this repo pins. Measured: **22 findings** in the UI code, including `content-slot-reused` in `OfListDetailPane.kt` (slot state gets lost when the compact/expanded branch flips). The detekt flavour isn't usable here (see §5). |
| 2 | **Add Android Lint to CI** (`lintDebug` on `:androidApp` and every `feature:*:ui` / `core:designsystem`) | S | Not in CI or `/verify` today. The Compose runtime ships lint detectors (unremembered state, coroutine creation in composition, `StateFlow.value` in composition, …). Measured `:feature:dashboard:ui:lintDebug`: 0 errors, 1 warning, so it's cheap to gate. |
| 3 | **Enforce invariant 4 with `com.jraska.module.graph.assertion` 2.9.1** | S | "core never depends on feature / feature never depends on feature" lives only in prose today. This plugin checks it as part of `check`, and it supports KMP configurations. |
| 4 | **Widen `verifyNoComposeInCommonMain`** to also reject `android.*`, `java.*`, `javax.*` imports in `commonMain` | XS | `kmp-shared-code.md` says "Nothing catches the rest automatically". Measured: 0 such imports across 239 commonMain files, so it passes today. |
| 5 | **New `.claude/rules/kotlin-coroutines.md` and `testing.md`** | S | detekt's coroutine rules need type resolution, which **never runs on `commonMain`** in detekt 1.23.8 (§5), so the rules file is the only guard for shared ViewModel code. A testing rule also counters the installed ecc skills that push Kotest/MockK/Kover. |
| 6 | **Actually install `kotlin-agent-skills@Kotlin`** (JetBrains) | XS | `.claude/settings.json` already enables it, but on this machine it isn't in `installed_plugins.json` and its cache is marked orphaned, so none of its skills load. Useful here: `kotlin-tooling-native-build-performance`. |
| 7 | **Selected Google `android/skills`** (edge-to-edge, r8-analyzer, android-permissions-security) | XS | These are official skills. Pick individual skills; don't enable the whole plugin, because `adaptive` and `testing-setup` push Nav3/Hilt/Robolectric (§3). |
| 8 | Turn on a few default-off detekt rules that don't need type resolution | XS | `GlobalCoroutineUsage`, `DataClassShouldBeImmutable` (UiState), `ForbiddenSuppress` (optional). |
| 9 | Try `-Xreturn-value-checker=check` / `extraWarnings` | M | Experimental. Measure the warning count before gating on it. |

Skip: Konsist (stalled), Slack lints, `detekt-formatting`, `detekt-rules-libraries`/`-ruleauthors`,
explicit API mode, detekt 2.0 alpha, ecc `kotlin-testing` / `compose-multiplatform-patterns` (§7).

## 2. What the repo already has

- **CLAUDE.md** (169 lines, under the docs' [200-line target](https://code.claude.com/docs/en/memory#write-effective-instructions)).
  It covers invariants, the module graph, the test stack, the commit gate and Games (F9).
- **`.claude/rules/`**: `android-compose-ui.md` (feature `:ui`, designsystem, androidApp),
  `ios-swiftui.md`, `kmp-shared-code.md` (commonMain purity, expect/actual, VM shape, stock-Pi compatibility).
- **`.claude/skills/verify`** (the invariant chain from real exit codes). The `.claude/hooks/commit-gate.sh`
  PreToolUse hook blocks `git commit` on failure.
- **settings.json**: permissions, the `Kotlin/kotlin-agent-skills` marketplace + `kotlin-agent-skills@Kotlin`
  enabled, and `swift-lsp`.
- **ktlint 1.8.0 through Spotless 8.10.2**, `ktlint_code_style = ktlint_official`, max line 120,
  Composable function-naming exemption, plus the SPDX header. `ktlint_official` "combines the best elements
  from the Kotlin Coding conventions and Android's Kotlin styleguide"
  ([ktlint 1.8.0 docs](https://github.com/ktlint/ktlint/blob/1.8.0/documentation/release-latest/docs/rules/code-styles.md)),
  so both official style guides are already enforced mechanically.
- **detekt 1.23.8** (latest stable; 2.0 is at
  [2.0.0-alpha.6](https://github.com/detekt/detekt/releases/tag/v2.0.0-alpha.6)), `buildUponDefaultConfig`,
  source = all of `src/`. Overrides: Composable/Test naming, `ForbiddenImport` of `material3.*` outside
  designsystem (invariant 5), MagicNumber/LongMethod/LongParameterList relaxed for Composables. **No detekt
  plugins** (no formatting, no compose rules). CI runs plain `detekt`.
- **Compiler**: no `allWarningsAsErrors`, no `extraWarnings`, no explicit API. `kotlin.code.style=official`.
- **Custom checks**: `verifyNoComposeInCommonMain` (build-logic) and a CI grep guard.
- **Not enforced by tooling**: invariant 4 (module graph), invariant 6 beyond Compose, Android Lint.

## 3. Agent skills

**Already installed (or meant to be):**

- **`Kotlin/kotlin-agent-skills`** ([repo](https://github.com/Kotlin/kotlin-agent-skills), Apache-2.0,
  JetBrains "Incubator", active: pushed 2026-09-29). It has 10 skills, all `kotlin-tooling-*` or backend.
  Relevant here: `kotlin-tooling-native-build-performance` (slow `linkDebug*` / XCFramework builds, K/N
  toolchain caching in CI). `kotlin-tooling-agp9-migration` is already done (AGP 9.3.3). CocoaPods→SPM,
  Kotlin Toolchain (Amper) and JPA don't apply. **Action:** `claude plugin install kotlin-agent-skills@Kotlin`.
  The project enables it, but this machine's `installed_plugins.json` has no entry and the cache has
  `.orphaned_at`, so its skills weren't in this session's skill list.

**Official, worth cherry-picking:**

- **`android/skills`** ([repo](https://github.com/android/skills), Apache-2.0, Google, v1.0.13 on
  2026-09-25). The README says it targets "use cases and workflows where evaluations show LLMs
  underperform" and deliberately skips basic Compose best practices. It installs with the Android CLI
  (`android skills add <name> --project=.`), and there's also a Claude marketplace
  (`.claude-plugin/marketplace.json`, name `android-skills`). Per skill:
  - Adopt: `system/edge-to-edge` (targetSdk 37), `performance/r8-analyzer` (release builds),
    `security/android-permissions-security` (BLE/location permission flows).
  - Use with care: `jetpack-compose/adaptive`. It steers toward Navigation 3 scenes and the MediaQuery API,
    while this repo uses `navigation-compose` 2.10.2 and its own `OfWindowClass`/`OfListDetailPane`.
    `testing/testing-setup` installs Hilt for non-multiplatform apps and suggests Robolectric, Roborazzi
    and MockK. It does analyse first and asks about Koin for multiplatform apps, but it would still fight
    the test stack.
  - Irrelevant: camera, TV, Wear, XR, Play, billing.
  - Recommendation: copy the three skills into `.claude/skills/` (Apache-2.0 content is fine in an
    AGPL-3.0 repo) rather than enabling the whole plugin. `skillOverrides` can't hide individual plugin
    skills ([docs](https://code.claude.com/docs/en/skills#override-skill-visibility-from-settings):
    "Plugin skills are not affected by `skillOverrides`").

**Checked, nothing for Kotlin:**

- [`anthropics/skills`](https://github.com/anthropics/skills): document, design and MCP skills only.
- [`JetBrains/junie-guidelines`](https://github.com/JetBrains/junie-guidelines): Go, Java/Spring,
  Django and Nuxt guidelines, no Kotlin/Android. Last push 2026-03-04.
- [`JetBrains/skills`](https://github.com/JetBrains/skills): a curated snapshot of upstream skills. Its
  Kotlin entries are Spring/JPA/backend or copies of `kotlin-agent-skills`. Its "compose" entry is
  Compose *Desktop* HTTP control.
- [`maxrave-dev/kotlin-footguns`](https://github.com/maxrave-dev/kotlin-footguns) (community,
  GPL-3.0): about Compose Multiplatform and the desktop JVM, which conflicts with ADR 0001's native UI.

**Project skills worth writing** (small, per the
[skills docs](https://code.claude.com/docs/en/skills): SKILL.md under 500 lines, `paths` frontmatter
supported):

- `new-feature-module`: scaffold `feature:<x>` + `feature:<x>:ui` + Koin module + `KoinHelper` accessor +
  `NativeViewModels.kt` entries + SwiftUI `ViewModelHost` extension. That sequence is spread across three
  rules files today.

## 4. `.claude/rules` content

Format facts ([memory docs](https://code.claude.com/docs/en/memory#path-specific-rules)): `paths` is the
only frontmatter field a rule reads. Path-scoped rules load when Claude **reads** a matching file. Rules
without `paths` load at launch. Keep them concrete and verifiable.

1. **`kotlin-coroutines.md`** (`paths: ["**/*.kt"]`). Distilled from Android's
   [coroutines best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
   (updated 2026-09-24):
   - Inject dispatchers. A constructor default such as `= Dispatchers.Default` is OK and is the repo's
     existing pattern (`DrivingRangeViewModel`).
   - Suspend functions must be main-safe.
   - ViewModels create coroutines in `viewModelScope`. Other layers expose `suspend` functions and `Flow`.
   - Don't expose `MutableStateFlow`; use the `_state`/`state` backing-property naming from
     [Kotlin conventions](https://kotlinlang.org/docs/coding-conventions.html#names-for-backing-properties).
   - No `GlobalScope`.
   - Never swallow `CancellationException` (`runCatching`/`catch (e: Exception)` inside suspend code
     must rethrow it).
   - Inject `StandardTestDispatcher` in tests.
   - Why a rule and not detekt: see §5 (detekt's coroutine checks are dead on `commonMain`).
2. **`testing.md`** (`paths: ["**/src/*Test/**/*.kt", "**/src/test/**/*.kt", "**/src/androidTest/**/*.kt"]`):
   - Restate the stack as prohibitions: kotlin.test + assertk + Turbine + `runTest`, fakes from
     `core:testing`; **no Kotest, MockK, Mockito, Kover or Robolectric**.
   - "Prefer fakes to mocks" and "test StateFlows" are Android's own
     [architecture recommendations](https://developer.android.com/topic/architecture/recommendations#testing).
   - Why: the globally installed ecc plugin's `kotlin-testing` skill and `/kotlin-test` command say
     "Write Kotest tests first… Kover". That's plugin content, so it can't be switched off per skill.
     A loaded rule is the counterweight.
3. **`designsystem-components.md`** (`paths: ["core/designsystem/**/*.kt"]`), from the androidx
   [Compose component API guidelines](https://github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-component-api-guidelines.md)
   and [Compose API guidelines](https://github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-api-guidelines.md):
   - `modifier: Modifier = Modifier` is the first optional parameter.
   - Emit XOR return a value.
   - Slot APIs (`content: @Composable () -> Unit`); never invoke a slot in two branches (wrap it in
     `movableContentOf`).
   - Stateless and hoisted, with defaults via a `*Defaults` object.
4. **Move the "Games (F9)" CLAUDE.md section** into `.claude/rules/games.md` with
   `paths: ["feature/games/**"]`. The memory docs say part-of-codebase guidance belongs in path-scoped
   rules. That trims the always-on CLAUDE.md.
5. **Record one deliberate deviation.** Android rates "Do not send events from the ViewModel to the UI"
   as *Strongly recommended*
   ([source](https://developer.android.com/topic/architecture/recommendations#ui-layer)). This repo's
   VMs emit one-shot `effects` through a `Channel` (ADR 0001, `kmp-shared-code.md`). Keep it, but say
   in the rule that it's intentional, so agents trained on Android guidance don't "fix" it.
6. Already covered, don't duplicate:
   - expect/actual: Kotlin's docs say prefer interfaces and keep expect/actual classes (Beta) rare
     ([source](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html)).
     `kmp-shared-code.md` already says this.
   - `.android.kt`/`.ios.kt` suffixes
     ([coding conventions](https://kotlinlang.org/docs/coding-conventions.html#source-file-names)): same.

Run `/doctor prompt-audit` after adding rules to catch contradictions across CLAUDE.md, rules and skills
([docs](https://code.claude.com/docs/en/memory#audit-your-instruction-files)).

## 5. Lint and static analysis

**compose-rules** ([repo](https://github.com/mrmans0n/compose-rules), Apache-2.0, maintained fork of
Twitter's rules, v0.6.7 on 2026-09-24):
- **ktlint flavour: adopt.** Its [matrix](https://github.com/mrmans0n/compose-rules/blob/main/docs/ktlint.md)
  lists 0.4.28+ for ktlint 1.8.0, and the 0.6.7 release notes list ktlint 1.8.0.
- Wiring: in `SpotlessConventionPlugin`, change `ktlint(ktlintVersion)` to
  `ktlint(ktlintVersion).customRuleSets(listOf("io.nlopez.compose.rules:ktlint:0.6.7"))`
  ([Spotless docs](https://github.com/diffplug/spotless/tree/main/plugin-gradle#ktlint)). Add a
  `compose-rules` version to `libs.versions.toml`.
- Scope: the rules only fire on `@Composable` code. To limit the run to `:ui`/designsystem/androidApp,
  apply the custom rule set only in the Android Compose convention plugins.
- Measured with `ktlint 1.8.0 -R ktlint-compose-0.6.7-all.jar` over `feature/*/ui`,
  `core/designsystem` and `androidApp`:

  | Rule | Count |
  |---|---|
  | `modifier-without-default-check` | 10 |
  | `multiple-emitters-check` | 5 |
  | `parameter-naming` | 3 |
  | `content-slot-reused` | 2 (`OfListDetailPane.kt:65-66`) |
  | `mutable-state-autoboxing` | 1 |
  | `modifier-missing-check` | 1 |

  Fix those or suppress them with a reason. Tell the rules about the `Of*` design-system emitters with
  `compose_content_emitters = OfScaffold,OfCard,…` in `.editorconfig` (same doc).
- **detekt flavour: don't adopt now.** 0.6.x targets detekt **2.0.0-alpha.6**. The last build for detekt
  1.23.8 is **0.4.23** (Kotlin syntax 2.0.21), which is stale for Kotlin 2.4.20
  ([detekt matrix](https://github.com/mrmans0n/compose-rules/blob/main/docs/detekt.md)).

**detekt: type-resolution gap (the main finding):**
- In 1.23.8, `InjectDispatcher`, `RedundantSuspendModifier`, `SuspendFunWithFlowReturnType`,
  `SleepInsteadOfDelay`, `SuspendFunSwallowedCancellation`, `ElseCaseInsteadOfExhaustiveWhen` and
  `ForbiddenMethodCall` are all `@RequiresTypeResolution` (checked in the v1.23.8 sources).
- Plain `detekt` "runs WITHOUT type resolution" and those rules "will not run"
  ([detekt docs](https://detekt.dev/docs/1.23.0/gettingstarted/type-resolution)).
- The KMP plugin does register `detektAndroidMain` ("EXPERIMENTAL … with type resolution"). Measured:
  it's **NO-SOURCE for `:feature:dashboard`** because it only covers `androidMain`, not `commonMain`.
- So every default-on coroutine rule is dead on shared code, which is why §4's coroutine rule matters.
- detekt 2.0 generates type-resolved tasks per JVM/Android *compilation*
  ([next docs](https://detekt.dev/docs/next/gettingstarted/type-resolution)), which *should* include
  `commonMain`. That's **unverified**, and 2.0 is still alpha. Revisit when 2.0 is stable (it would also
  unlock compose-rules 0.6.x for detekt).
- Cheap additions to `config/detekt/detekt.yml` (default-off in the
  [1.23.8 default config](https://github.com/detekt/detekt/blob/v1.23.8/detekt-core/src/main/resources/default-detekt-config.yml),
  no type resolution needed):
  - `coroutines>GlobalCoroutineUsage: active: true`
  - `style>DataClassShouldBeImmutable: active: true` (UiState should be immutable; check the fallout
    first)
- Skip:
  - `detekt-formatting`: it wraps ktlint, which Spotless already runs.
  - `detekt-rules-libraries`: for published libraries.
  - `detekt-rules-ruleauthors`: for writing detekt rules.

**Android Lint:**
- Measured: `lint`/`lintDebug` exist on `:androidApp`, the `:ui` modules and `core:designsystem`. The KMP
  modules only expose `lintAnalyzeAndroidHostTest`. Whether KMP `androidMain` can be linted is
  **unverified**.
- Compose runtime lint detectors ship in androidx
  ([`runtime-lint`](https://github.com/androidx/androidx/tree/androidx-main/compose/runtime/runtime-lint/src/main/java/androidx/compose/runtime/lint)):
  `UnrememberedState`, `ComposableCoroutineCreation`, `ComposableFlowOperator`,
  `ComposableStateFlowValue`, `AutoboxingStateCreation`, …
- Add `:androidApp:lintDebug` plus the `:ui` modules' `lintDebug` to CI's jvm job and to `/verify`.
  Set `lint { warningsAsErrors = true; abortOnError = true }` in the two Android convention plugins, or
  use a `lint-baseline.xml`.

**Slack lints** ([repo](https://github.com/slackhq/slack-lints), `com.slack.lint:slack-lint-checks:0.11.1`,
last release 2025-10): skip. Its checks centre on mocking, Moshi, Dagger and Retrofit, none of which this
repo uses, and the README warns some "may only really be relevant to Slack's codebase".

**ktlint experimental rules** (`ktlint_experimental=enabled`,
[1.8.0 list](https://github.com/ktlint/ktlint/blob/1.8.0/documentation/release-latest/docs/rules/experimental.md)):
optional and low value. They're formatting-only (`kdoc`, `when-entry-bracing`, `mixed-condition-operators`, …).

## 6. Compiler / build settings

All from [Kotlin Gradle compiler options](https://kotlinlang.org/docs/gradle-compiler-options.html),
set once in `KmpLibraryConventionPlugin` (`kotlin { compilerOptions { … } }`) and the Android plugins:

- `allWarningsAsErrors = true`: adopt only after a clean-build warning count (`grep '^w: '`) is zero. It
  would also need `kotlin.apple.xcodeCompatibility.nowarn` to stay as is. Current count **unverified**.
- `extraWarnings = true` (extra declaration/expression checks, Kotlin 2.1+): try it locally first.
- `-Xreturn-value-checker=check`: **experimental** unused-return-value checker
  ([docs](https://kotlinlang.org/docs/unused-return-value-checker.html)). `check` only flags
  `@MustUseReturnValues` APIs; `full` flags everything and is likely noisy with Compose/Koin DSLs.
  Worth a trial on `core:*`. KMP-target coverage is **unverified**.
- Skip:
  - Explicit API mode: the Kotlin docs aim it and the
    [library conventions](https://kotlinlang.org/docs/coding-conventions.html#coding-conventions-for-libraries)
    at published libraries, and these are internal app modules.
  - `-Xjsr305`: only matters for Java libraries with JSR-305 annotations.
  - `-Xexpect-actual-classes`: would hide the Beta warning that discourages expect classes.

## 7. Architecture tests

- **modules-graph-assert** ([repo](https://github.com/jraska/modules-graph-assert), Apache-2.0, 2.9.1 on
  2026-04-12, active). Apply `id("com.jraska.module.graph.assertion") version "2.9.1"` at the root with:
  - `restricted = [":core:.* -X> :feature:.*", ":core:.* -X> :shared", ":feature:([a-z]+).* -X> :feature:(?!\\1).*"]`
    (the regex is **unverified**; test it)
  - `configurations += setOf("commonMainImplementation", "commonMainApi")`, per the README's KMP section
  - `assertModuleGraph` runs under `check`, so add it to CI explicitly, since CI doesn't run `check`.
- **Konsist**: skip. The last release is 0.17.3 (2024-12-08) and the last commit on `main`/`develop` was
  2026-01-08 ([repo](https://github.com/LemonAppDev/konsist)). It has stalled relative to Kotlin 2.4.
  Its main uses here (no platform imports in commonMain, VM naming) are covered more cheaply by
  recommendation #4 and detekt `ForbiddenImport`.

## 8. What to skip, and why

| Item | Conflict |
|---|---|
| ecc `kotlin-testing` skill + `/kotlin-test` (`~/.claude/plugins/cache/ecc/ecc/2.0.0/skills/kotlin-testing`) | Kotest, MockK, Kover: CLAUDE.md mandates kotlin.test + assertk + Turbine and fakes |
| ecc `compose-multiplatform-patterns` | Compose Multiplatform: ADR 0001 bans it (`verifyNoComposeInCommonMain`) |
| ecc `android-clean-architecture` | UseCase-per-action and Hilt examples. The domain layer is optional here and DI is Koin. Fine as background, not as a rule |
| ecc `kotlin-coroutines-flows`, `kotlin-patterns` | Mostly consistent (structured concurrency, `stateIn(WhileSubscribed(5_000))`, Turbine). Harmless, but the repo rule in §4 should win |
| Google `testing-setup`, `adaptive` skills | Hilt/Robolectric/MockK and Nav3/MediaQuery against Koin, kotlin.test and `OfWindowClass` + navigation-compose |
| Android "Use Hilt" recommendation | Koin is required for KMP ViewModels |
| detekt 2.0 alpha / compose-rules detekt 0.6.x | Alpha, and CLAUDE.md's catalog note deliberately stays on 1.23.8 |
| Konsist, Slack lints, detekt-formatting/libraries/ruleauthors | See §5 and §7 |

The ecc items are plugin skills, so only `/plugin` (whole-plugin enable/disable) controls them, not
`skillOverrides` ([docs](https://code.claude.com/docs/en/skills#override-skill-visibility-from-settings)).
The practical mitigation is the explicit prohibitions in the §4 rules.
