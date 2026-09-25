# OpenFlight Companion

<img src="iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png" width="96" alt="OpenFlight Companion icon" align="left" hspace="12" />

An Android + iOS companion app for [OpenFlight](https://openflight.dev), the DIY golf launch
monitor. It talks to the Pi over Bluetooth LE or Wi-Fi (SSE + Socket.IO). Each platform has a
**native UI** — Jetpack Compose on Android, SwiftUI on iOS — over the same shared Kotlin
Multiplatform ViewModels, repositories and transports
([ADR 0001](docs/adr/0001-native-ui-shared-viewmodels.md)).

<br clear="left"/>

## Features

- **Live shots** over Bluetooth LE or Wi-Fi (Server-Sent Events), with automatic reconnect and
  capped exponential backoff, and replay suppression on an automatic reconnect (but not on an
  explicit Retry — a fresh install intentionally shows the Pi's latest shot right away).
- **Dashboard**: transport picker (Bluetooth / Wi-Fi), a live connection-status chip, the
  latest shot's primary metrics (ball speed, carry) and detail metrics (club speed, smash,
  launch vertical/horizontal, spin, spin axis, club path), confidence indicators and a carry
  range when the Pi reports them, and a previous-shots history (newest first, capped at 100,
  deduplicated across transport switches). A haptic and a gold flash mark each new shot.
- **Club selection**, synced both ways: pick a club on the phone and it round-trips to the Pi;
  change it from the Pi's own browser UI and the phone updates too. The phone syncs the
  server's current club once per connection. Per-club shot chips with counts sit on the
  dashboard.
- **Phone-assisted TI radar tilt calibration**: sample the phone's gravity sensor at 60 Hz,
  wait for a stable 2-second average, and submit it to calibrate the TI IWR6843 radar's mount
  tilt — no external level needed.
- **Driving range**: a ball-flight view (Compose `Canvas` 2.5D on Android, RealityKit 3D on
  iOS) that resolves missing launch/spin values from per-club defaults, simulates the
  trajectory with the reference's RK4 model, and plays it back with a replay button, a club
  selector, and a **camera mode toggle**: a fixed tee-side view, or a **follow camera** that
  chases the ball, frames the landing area in the final approach, and settles on a raised view
  of the landing spot lined up with the nearest yardage marker.
- **Units**: an Imperial (mph/yds) or Metric (km/h/m) toggle, applied everywhere metrics are
  shown.
- **Session**: shot history grouped by club, with per-club stats (count, average/max ball
  speed, average carry, average club speed, average smash), swipe-to-delete, clear-with-
  confirmation, and CSV export through the Android share sheet or the iOS `ShareLink`.
- **Session history**: every connection to the Pi starts a session that is stored on the phone
  (Room, survives relaunches), listed newest first with its date, shot count and first/last
  shot time; each stored session opens with the same club tabs, stats, rows and per-session CSV
  export, and "Clear all history" deletes them after a confirmation.
- **Wi-Fi-only features over the Pi's Socket.IO API** (`core:socketio` speaks the same
  Engine.IO v4 / Socket.IO v5 protocol as the Pi's own web UI): server-backed session history
  and stats, server-side delete/clear, confidence badges (`launch_angle_confidence`,
  `spin_quality`, `spin_source`, `carry_range`), a live camera preview (MJPEG), a swing-speed
  training mode with implement selection, player selection, simulator (GSPro) status, a
  radar/debug panel, cloud-upload status, and a Pi shutdown button behind a confirmation
  dialog. These are unavailable over BLE — the UI disables them with an explanation rather
  than hiding them silently, since BLE only carries the narrower shot/control schema.
- **Accessibility**: metric tiles and shot-history rows announce as one merged phrase for
  TalkBack/VoiceOver (e.g. "Ball speed, 139.1 miles per hour"), the connection status chip is
  a live region that announces state changes on its own, and the layout holds up to 200%
  system font scale without clipping or overlapping content.

## Module map

| Path | What |
|---|---|
| `androidApp/` | Android application: `MainActivity`, the Jetpack `navigation-compose` nav host and bottom bar (Dashboard/Session/Training/Camera/Settings, plus Calibrate and Range), runtime permission prompts, launch-option intent extras, Koin start, the app icon |
| `shared/` | KMP, no UI: common Koin bootstrap (`initKoin`), `LaunchOptions`, the preview repository, and the iOS umbrella framework `Shared.framework` (exports `core:model`/`core:data`/`core:insights`/`core:flight` and every `feature:*` ViewModel module, plus `KoinHelper` and the `NativeViewModels` bridge for Swift) |
| `iosApp/` | Xcode project (SwiftUI), embeds `Shared.framework` through a Gradle build phase; `Bridge/ViewModelHost.swift` collects shared `StateFlow`s via KMP-NativeCoroutines; the `AppIcon` asset catalog |
| `core/model` | Pure data types: `ShotEvent`, `GolfClub`, `ConnectionState`, calibration and Pi (`pi.*`) models. No I/O. |
| `core/protocol` | The wire-protocol codec: BLE frame encoder/reassembler, `ShotEventDecoder`, SSE parsing, control envelopes, the `ShotTransport` interface. No I/O. |
| `core/ble`, `core/network` | The BLE (Kable-backed) and Wi-Fi/SSE transports, both implementing `core/protocol`'s `ShotTransport` |
| `core/socketio` | A minimal Engine.IO v4 / Socket.IO v5 client (Ktor WebSocket), an MJPEG multipart parser, and `PiSessionRepository` for the Wi-Fi-only features above |
| `core/data` | `ShotRepository`/`SettingsRepository`/`PiSessionRepository`/`ShotHistoryRepository` — the single source of truth the UI observes as `Flow`s; enriches SSE/BLE shots with Socket.IO detail by timestamp and writes every shot through to the persistent history |
| `core/database` | The persistent multi-session shot history: a Room 3 KMP database (bundled SQLite) with sessions and shots, used only by `core/data`. Exported schemas live in `core/database/schemas/` |
| `core/flight` | Pure ball-flight math for the driving range: the RK4 simulator, the fixed-camera planner, and `FollowCameraPlanner` |
| `core/sensors` | Gravity sensor `expect`/`actual` and the phone-orientation calibration math (converts Android's `TYPE_GRAVITY` into iOS CoreMotion's convention) |
| `core/insights` | Units, per-club stats, CSV export, shot enrichment/confidence formatting — pure, depends only on `core:model` |
| `core/designsystem` | Android-only (Jetpack Compose): the `Of*` component wrappers every feature UI must use instead of raw Material3; bundles DM Serif Display + Outfit (OFL) |
| `core/testing` | Fake repositories shared by VM tests |
| `feature/dashboard`, `feature/calibration`, `feature/range`, `feature/session`, `feature/training`, `feature/camera`, `feature/settings` | KMP, shared presentation: each screen's `ViewModel`, `UiState`, events and pure helpers (no Compose in `commonMain`, enforced by `verifyNoComposeInCommonMain`) |
| `feature/<name>/ui` | Android-only (Jetpack Compose): that screen's composables and its device UI tests |
| `build-logic/` | Gradle convention plugins (`openflight.*`) |
| `gradle/libs.versions.toml` | Single source of truth for versions |
| `tools/` | Dev helpers (`fire-mock-shot.py`, the BLE frame-golden generator) |
| `docs/hardware-test-matrix.md` | The human-gated checklist for real Pi/Android/iOS hardware |
| `plans/openflight-kmp-app.md` | The build plan — **§0 is the canonical wire-protocol reference** |

```
androidApp (Jetpack NavHost, permissions, launch extras, Koin start)
    ├──> feature:dashboard:ui | feature:calibration:ui | feature:range:ui
    │        feature:session:ui | feature:training:ui | feature:camera:ui | feature:settings:ui
    │        └──> its own feature:<name> + core:designsystem + core:* (never another feature)
    └──> shared (KMP: initKoin, LaunchOptions, PreviewShotRepository)
iosApp (SwiftUI, Xcode) ──> Shared.framework = shared, exporting core:*/feature:* + KoinHelper
shared ──> feature:dashboard | calibration | range | session | training | camera | settings
             └──> core:data ──> core:ble / core:network / core:socketio ──> core:protocol ──> core:model
                        └──> core:database (Room 3 KMP)
feature:range ──> core:flight ; feature:calibration ──> core:sensors ; others ──> core:insights
```

## Building

Requirements: JDK 17+ (Android Studio's bundled JBR works), the Android SDK (`sdk.dir` in
`local.properties`), and Xcode 26+ for iOS.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # if no system JDK

./gradlew spotlessCheck detekt          # formatting + static analysis
./gradlew allTests                      # Android host tests + iOS simulator tests, every module
./gradlew :androidApp:assembleDebug     # Android APK
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64

xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Run `./gradlew spotlessApply` before committing. The Xcode build phase falls back to
Android Studio's JBR when `JAVA_HOME` is unset, because Xcode doesn't inherit your shell
environment.

### iOS signing

The checked-in project has no team configured, so a fresh checkout will show "no account for
team" or a provisioning error. Fix it once per machine:

1. In Xcode, **Xcode > Settings > Accounts**, add your Apple ID.
2. Select the **iosApp** project, then the **iosApp** target, then **Signing & Capabilities**.
3. Leave **Automatically manage signing** on and pick your own team.
4. Our bundle ID is `dev.openflight.companion`; if it's taken, use a unique reverse-DNS ID
   (e.g. `com.yourname.openflight`) instead.
5. If you'll run tests on a physical device, apply the same team to the `iosAppTests` and
   `iosAppUITests` targets too.
6. Xcode writes your team and bundle-ID changes into `iosApp/iosApp.xcodeproj/project.pbxproj`.
   Check `git diff` before committing and keep personal signing values out of unrelated
   commits.

Running on the simulator (`CODE_SIGNING_ALLOWED=NO`) needs none of this.

### Test commands

```bash
./gradlew allTests                                           # every module's host + iOS simulator tests
./gradlew :core:model:allTests :core:protocol:allTests        # a single module
./gradlew :feature:dashboard:ui:connectedDebugAndroidTest     # Compose UI tests, needs a booted emulator/device
./gradlew :feature:calibration:ui:connectedDebugAndroidTest
./gradlew :feature:range:ui:connectedDebugAndroidTest
./gradlew :feature:session:ui:connectedDebugAndroidTest
./gradlew :feature:training:ui:connectedDebugAndroidTest
./gradlew :feature:camera:ui:connectedDebugAndroidTest
./gradlew :feature:settings:ui:connectedDebugAndroidTest
./gradlew :androidApp:connectedDebugAndroidTest                # app-level flow (DrivingRangeFlowTest)
```

`allTests` runs `testAndroidHostTest` (JVM-hosted Android unit tests, no emulator needed) and
`iosSimulatorArm64Test` on every KMP module, `testDebugUnitTest` on the Android-only modules,
and `verifyNoComposeInCommonMain` (ADR 0001: shared code never mentions Compose). The
`connectedDebugAndroidTest` tasks are Compose UI tests and need a running Android emulator or
a connected device — CI doesn't run these; they run manually.

For iOS, first list the simulators actually installed on this Mac — **simulator names aren't
portable**, so never hard-code one from another machine or from this README:

```bash
xcrun simctl list devices available
```

Then paste a UUID from that output into the destination:

```bash
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,id=PASTE-SIMULATOR-UUID-HERE" \
  CODE_SIGNING_ALLOWED=NO

# unit tests only
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,id=PASTE-SIMULATOR-UUID-HERE" \
  -only-testing:iosAppTests \
  CODE_SIGNING_ALLOWED=NO
```

## Running against the mock server

The OpenFlight Pi server runs without radar hardware in mock mode. In an
[openflight](https://github.com/jake-fishtech/openflight/tree/feat/iOS-ble) checkout:

```bash
uv sync
uv run openflight-server --mock --web-port 8091   # --web-port matters if 8080 is already in use
curl -N http://localhost:8091/api/shots/stream     # sanity-check the SSE stream directly
```

Mock shots fire only through the Socket.IO event `simulate_shot` — there is no HTTP route for
it. Use the helper, which connects as a Socket.IO client and emits it for you:

```bash
uv run --with "python-socketio[client]" tools/fire-mock-shot.py -n 5 --url http://localhost:8091
```

Each shot then appears on the SSE stream as an `event: shot` line, which the app's Wi-Fi
transport decodes, and on the Socket.IO connection, which `core:socketio` enriches it with
(confidence, player, sim fields, etc.) matched by timestamp.

**Host, per platform:**

| Platform | Host to enter in the app |
|---|---|
| Android emulator | `10.0.2.2:8091` — the emulator's alias for the host machine's `localhost` |
| iOS Simulator | `localhost:8091` (or `127.0.0.1:8091`) — the simulator shares the host's network namespace |
| A real phone | The Pi's actual LAN address, e.g. `raspberrypi.local:8080` (the app's default) or an IP |

Debug builds also accept launch-time hooks so UI tests and manual checks don't need a live Pi
at all:

```bash
# Android
adb shell am start -n dev.openflight.companion/.MainActivity \
  --ez preview_shot true --es transport wifi --es host 10.0.2.2:8091

# iOS (NSProcessInfo.arguments, parsed by the shared LaunchOptions)
# pass --ui-testing --preview-shot --range-mode --preview-flight
#      --transport wifi --host <host:port> as launch arguments
```

`preview_shot`/`--preview-shot` swaps in a fake repository with one canned shot, so the
dashboard, calibration and range screens all render real data without a server.
`--ui-testing` additionally suppresses the automatic transport connection, so a UI test
controls exactly when (and whether) the app talks to anything.

### Verify the app

After building and pointing the app at a server (mock or real), confirm the core loop end to
end:

1. Reach **Connected** over the selected transport.
2. Fire or hit a shot and confirm it appears on the dashboard.
3. Open **Driving Range** and confirm the trajectory renders.
4. Change **Club for next shot** and confirm the Pi accepts it.
5. If an IWR6843 radar is attached and enabled (`--iwr6843`), run the phone calibration once
   on a physical device.

The Pi replays its most recent completed shot when the app connects, so a fresh install
doesn't need to wait for a new shot if one was already recorded during the current server run.

## Wire protocol

The wire protocol (BLE frame format, SSE event shapes, control envelopes, shot JSON schema,
calibration payload) is **not** re-documented here. Its ground truth is
[`plans/openflight-kmp-app.md` §0](plans/openflight-kmp-app.md), extracted from the reference
implementation's source and kept in sync with it — read that section before changing any
protocol code, rather than inferring behavior from this app's Kotlin alone.

## Troubleshooting

Adapted from the reference iOS app's own troubleshooting guide
(`jake-fishtech/openflight@feat/iOS-ble`, `ios/README.md`), for both platforms:

- **`Unable to find a device matching the provided destination specifier` (iOS).** Simulator
  names aren't portable between Macs. Run `xcrun simctl list devices available` and use an
  installed device's UUID, not a hard-coded name.
- **`CoreSimulatorService connection became invalid` (iOS).** Run `xcrun simctl shutdown all`,
  quit and reopen Xcode and Simulator, and retry. Confirm `xcode-select -p` points at
  `/Applications/Xcode.app/Contents/Developer`.
- **Xcode warns that App Intents metadata extraction was skipped.** Harmless; this app doesn't
  use `AppIntents.framework`.
- **"Looking for OpenFlight" never resolves (BLE).** Confirm the Pi was started with `--ble`
  (or the mock server has BLE enabled) and that `bluetoothctl show` on the Pi reports
  `Powered: yes`. Keep the app in the foreground during the initial scan — this app is
  foreground-only BLE by design (see Known limitations). Restart the app after changing the
  Pi's Bluetooth configuration.
- **Wi-Fi doesn't connect.**
  1. From another machine on the same network, run
     `curl -N http://<host>:8080/api/shots/stream` and confirm it opens with `: ping`.
  2. If `<hostname>.local` fails, use the Pi's IP address instead — some networks block mDNS.
  3. Put the phone and the Pi on the same network, and temporarily disable VPNs or client-
     isolation features that block local-device traffic.
  4. On iOS, re-enable OpenFlight under **Settings > Privacy & Security > Local Network**; on
     Android 17+, grant the local-network permission the app prompts for when connecting.
- **The stream returns 503 / "too many devices".** The Pi caps Server-Sent-Events clients at
  8 concurrent connections. Close other `curl`/browser tabs streaming `/api/shots/stream`.
- **Calibration is unavailable in the iOS Simulator.** The Simulator has no real motion
  sensors; use a physical iPhone for a meaningful calibration test. The Simulator still
  exercises the "Motion unavailable" UI state correctly.
- **A Raspberry Pi kernel regresses BLE.** Kernel `6.18.34+rpt-rpi-2712` is confirmed to break
  BLE advertising/GATT on the Pi. Check `uname -r` on the Pi before assuming an app defect;
  boot a different kernel (e.g. 6.12.x) or use Wi-Fi until it's fixed.
- **The mock server shows phantom clients for 15–35 seconds after a disconnect.** This is the
  reference server's `ShotStreamBroker`, which only drops a subscriber after a failed
  heartbeat write, not immediately on socket close. It is not a leak in this app.

## Known limitations

- **No authentication.** The app talks to the Pi with no auth of any kind, matching the
  upstream project's own security posture — it assumes a trusted home LAN.
- **Foreground-only BLE.** The BLE connection is torn down when the app leaves the foreground
  and rebuilt (via a fresh scan) when it returns. There is no background BLE session; this is
  a deliberate v1 scope cut, not a bug.
- **Android 17 (targetSdk 37) needs the local-network permission.** Reaching the Pi over Wi-Fi
  requires the runtime `ACCESS_LOCAL_NETWORK` permission on Android 17+; without it,
  connection attempts silently time out instead of failing fast. The app requests this at the
  point of connecting, the same way it requests Bluetooth permissions.
- **The driving-range flight arcs are flat by design, matching the upstream iOS app.** The
  reference's `constrain()` scales a trajectory's carry distance (x/z) to match the server's
  reported yardage but does **not** rescale its height (y). This app ports that behavior
  faithfully rather than "fixing" it, since diverging from the reference here was flagged and
  intentionally left alone.
- **The mock server can show phantom clients for 15–35 seconds after a disconnect.** See
  Troubleshooting above — this is `openflight-server`'s behavior, not this app's.
- **BLE-only mode lacks the Socket.IO features.** Session history/stats backed by the server,
  server-side delete/clear, the camera preview, training mode, player/simulator selection, the
  radar/debug panel, cloud upload and Pi shutdown all require the Pi's Socket.IO API, which is
  Wi-Fi only. Over BLE the UI disables these with an explanation instead of hiding them.
- **A specific Raspberry Pi kernel regresses BLE.** Kernel `6.18.34+rpt-rpi-2712` is known to
  break BLE advertising/GATT on the Pi side. If BLE rows in
  [`docs/hardware-test-matrix.md`](docs/hardware-test-matrix.md) fail, check `uname -r` on the
  Pi before assuming an app defect.
- **No Raspberry Pi is available yet for hardware verification.** Everything in
  [`docs/hardware-test-matrix.md`](docs/hardware-test-matrix.md) is simulator/emulator-only
  until then; see that file for the pending checklist.

## Network security

The Pi serves plain HTTP on the local network, at a host the user types in (a `.local` name or
a bare IP).

- **iOS** uses `NSAllowsLocalNetworking`. That allows cleartext only to local names and
  private address ranges.
- **Android** has no equivalent. `network_security_config` cannot allow-list arbitrary IPs
  entered by the user, so cleartext traffic is permitted app-wide. The tradeoff is acceptable
  because the app only talks to the user's own device on the LAN and never sends credentials.
  Revisit it if the app ever talks to internet hosts.

## Working with Claude Code

The repo ships a shared [Claude Code](https://code.claude.com) setup. It takes effect once you
trust the folder the first time you run `claude` here.

| Path | What it does |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Architecture, module rules, invariants and CI. Loaded every session. |
| [`.claude/rules/`](.claude/rules) | Path-scoped rules for shared KMP code, the Compose UI and the SwiftUI app. Each loads only when Claude reads a matching file. |
| [`.claude/settings.json`](.claude/settings.json) | Pre-approves `./gradlew`, `xcodebuild` and read-only git commands. Asks before `git commit` and `git push`. Blocks Gradle publish tasks and init scripts, force-push, `reset --hard`, and reading signing keys or `.env` files. |
| [`.claude/hooks/commit-gate.sh`](.claude/hooks/commit-gate.sh) | Before any `git commit` Claude makes, runs `spotlessCheck detekt allTests :androidApp:assembleDebug` (plus the iOS framework link on macOS) and blocks the commit unless Gradle exits 0. Markdown-only commits skip the build. Set `OPENFLIGHT_SKIP_COMMIT_GATE=1` to bypass it. |
| [`.claude/skills/verify`](.claude/skills/verify/SKILL.md) | `/verify` runs the same chain on demand, and the Xcode build too when iOS code changed. |

It also enables two plugins:
- **`swift-lsp`**, from Anthropic's official marketplace. It gives Claude Swift diagnostics
  through `sourcekit-lsp`, which ships with Xcode.
- **`kotlin-agent-skills`**, JetBrains' skills from
  [Kotlin/kotlin-agent-skills](https://github.com/Kotlin/kotlin-agent-skills), including
  Kotlin/Native build performance.

To opt out of either, set it to `false` under `enabledPlugins` in your own
`.claude/settings.local.json`. That file is gitignored, and so is `CLAUDE.local.md`; put
personal overrides in either one.

Optional extras, not enabled by default:
- `kotlin-lsp@claude-plugins-official`, Kotlin diagnostics via JetBrains'
  [kotlin-lsp](https://github.com/Kotlin/kotlin-lsp) (`brew install JetBrains/utils/kotlin-lsp`).
  It's alpha and doesn't support KMP source sets yet, so it mostly helps in the Android-only
  `:ui` modules.
- [chrisbanes/skills](https://github.com/chrisbanes/skills) for Compose performance, state, UI
  testing and Flow.
- [android/skills](https://github.com/android/skills) for Google's Android guidance, most of
  it outside this app's scope.

## Credits

- Upstream project: [jewbetcha/openflight](https://github.com/jewbetcha/openflight) (the
  launch monitor, Pi server and wire protocol).
- The iOS BLE companion app on
  [`jake-fishtech/openflight@feat/iOS-ble`](https://github.com/jake-fishtech/openflight/tree/feat/iOS-ble)
  (commit `b053194`) is the reference implementation this app ports its behaviour from.
- The app icon is built from the official OpenFlight logo/favicon
  (`ui/public/openflightlogo.svg` and `https://openflight.dev/icon.svg` in the upstream repo).

## License

AGPL-3.0-or-later (see [LICENSE](LICENSE)). This app ports logic from the AGPL upstream
project, so it is a derivative work under the same license. Kotlin sources carry an
`SPDX-License-Identifier: AGPL-3.0-or-later` header, which Spotless enforces.
