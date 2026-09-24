# OpenFlight Companion (Kotlin Multiplatform)

An Android + iOS companion app for [OpenFlight](https://openflight.dev), the DIY golf launch
monitor. It talks to the Pi over Bluetooth LE or Wi-Fi (SSE). Each platform has a native UI
(Jetpack Compose on Android, SwiftUI on iOS) over the same shared Kotlin Multiplatform
ViewModels, repositories and transports ([ADR 0001](docs/adr/0001-native-ui-shared-viewmodels.md)).
The SwiftUI screens are being ported (plan §8, R2–R3); until then the iOS app is a placeholder
that links the shared framework.

## Features

- **Live shots** over Bluetooth LE or Wi-Fi (Server-Sent Events), with automatic reconnect
  and backoff, and replay suppression on an automatic reconnect (but not on an explicit
  retry).
- **Dashboard**: transport picker (Bluetooth / Wi-Fi), connection status, the latest shot's
  primary metrics (ball speed, carry) and detail metrics (club speed, smash, launch
  vertical/horizontal, spin, spin axis, club path), and a previous-shots history (newest
  first, capped at 100, deduplicated across transport switches).
- **Club selection**, synced both ways: pick a club on the phone and it round-trips to the
  Pi; change it from the Pi's own browser UI and the phone updates too. The phone syncs the
  server's current club once per connection.
- **Phone-assisted TI radar tilt calibration**: sample the phone's gravity sensor at 60 Hz,
  wait for a stable 2-second average, and submit it to calibrate the TI IWR6843 radar's mount
  tilt — no external level needed.
- **Driving range**: a 2.5D ball-flight view (Compose `Canvas`, perspective-projected, not a
  3D engine) that resolves missing launch/spin values from per-club defaults, simulates the
  trajectory, and plays it back with a replay button and a club selector built into the
  overlay.
- **Accessibility**: metric tiles and shot-history rows announce as one merged phrase for
  TalkBack/VoiceOver (e.g. "Ball speed, 139.1 miles per hour"), the connection status chip is
  a live region that announces state changes on its own, and the layout holds up to 200%
  system font scale without clipping or overlapping content.

## Credits

- Upstream project: [jewbetcha/openflight](https://github.com/jewbetcha/openflight) (the
  launch monitor, Pi server and wire protocol).
- The iOS BLE companion app on
  [`jake-fishtech/openflight@feat/iOS-ble`](https://github.com/jake-fishtech/openflight/tree/feat/iOS-ble)
  is the reference implementation this app ports its behaviour from.

## License

AGPL-3.0-or-later (see [LICENSE](LICENSE)). This app ports logic from the AGPL upstream
project, so it is a derivative work under the same license. Kotlin sources carry an
`SPDX-License-Identifier: AGPL-3.0-or-later` header, which Spotless enforces.

## Layout

| Path | What |
|---|---|
| `androidApp/` | Android application: `MainActivity`, the Jetpack `navigation-compose` nav host, runtime permission prompts, launch-option intent extras, Koin start |
| `shared/` | KMP, no UI: common Koin bootstrap (`initKoin`), `LaunchOptions`, the preview repository, and the iOS umbrella framework `Shared.framework` (exports `core:model`/`core:data`/`core:insights` and every `feature:*` ViewModel module, plus `KoinHelper` for Swift) |
| `iosApp/` | Xcode project (SwiftUI), embeds `Shared.framework` through a Gradle build phase |
| `core/model`, `core/protocol` | Pure data types and the wire-protocol codec (frames, SSE parsing, control envelopes); no I/O |
| `core/ble`, `core/network` | The BLE (Kable-backed) and Wi-Fi/SSE transports, both implementing `core/protocol`'s `ShotTransport` |
| `core/data` | `ShotRepository`/`SettingsRepository` — the single source of truth the UI observes as `Flow`s |
| `core/flight` | Pure ball-flight math (RK4 simulator, camera projection) for the driving range |
| `core/sensors` | Gravity sensor `expect`/`actual` and the phone-orientation calibration math |
| `core/designsystem` | Android-only (Jetpack Compose): the `Of*` component wrappers every feature UI must use instead of raw Material3 |
| `feature/dashboard`, `feature/calibration`, `feature/range`, `feature/session` | KMP, shared presentation: each screen's `ViewModel`, `UiState`, events and pure helpers (no Compose in `commonMain`) |
| `feature/<name>/ui` | Android-only (Jetpack Compose): that screen's composables and its device UI tests |
| `build-logic/` | Gradle convention plugins (`openflight.*`) |
| `gradle/libs.versions.toml` | Single source of truth for versions |
| `tools/` | Dev helpers (`fire-mock-shot.py`, the BLE frame-golden generator) |
| `docs/hardware-test-matrix.md` | The human-gated checklist for real Pi/Android/iOS hardware |
| `plans/openflight-kmp-app.md` | The build plan — **§0 is the canonical wire-protocol reference** |

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

### Test commands

```bash
./gradlew allTests                                       # every module's host + iOS simulator tests
./gradlew :core:model:allTests :core:protocol:allTests    # a single module
./gradlew :feature:dashboard:ui:connectedDebugAndroidTest   # Compose UI tests, needs a booted emulator/device
./gradlew :feature:calibration:ui:connectedDebugAndroidTest
./gradlew :feature:range:ui:connectedDebugAndroidTest
./gradlew :androidApp:connectedDebugAndroidTest              # app-level flow (DrivingRangeFlowTest)
```

`allTests` runs `testAndroidHostTest` (JVM-hosted Android unit tests, no emulator needed) and
`iosSimulatorArm64Test` on every KMP module, `testDebugUnitTest` on the Android-only modules,
and `verifyNoComposeInCommonMain` (ADR 0001: shared code never mentions Compose). The
`connectedDebugAndroidTest` tasks are Compose UI tests and need a running Android emulator or
a connected device — CI doesn't run these; they run manually.

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
transport decodes.

**Host, per platform:**

| Platform | Host to enter in the app |
|---|---|
| Android emulator | `10.0.2.2:8091` — the emulator's alias for the host machine's `localhost` |
| iOS Simulator | `localhost:8091` (or `127.0.0.1:8091`) — the simulator shares the host's network namespace |
| A real phone | The Pi's actual LAN address, e.g. `raspberrypi.local:8080` (the app's default) or an IP |

Debug builds also accept launch-time hooks so UI tests and manual checks don't need a live
Pi at all:

```bash
# Android
adb shell am start -n dev.openflight.companion/.MainActivity \
  --ez preview_shot true --es transport wifi --es host 10.0.2.2:8091

# iOS (parsed by the shared LaunchOptions; the SwiftUI screens that use them arrive in R2)
# pass --ui-testing --preview-shot --transport wifi --host <host:port> as launch arguments
```

`preview_shot`/`--preview-shot` swaps in a fake repository with one canned shot, so the
dashboard, calibration and range screens all render real data without a server.

## Wire protocol

The wire protocol (BLE frame format, SSE event shapes, control envelopes, shot JSON schema,
calibration payload) is **not** re-documented here. It's ground truth is
[`plans/openflight-kmp-app.md` §0](plans/openflight-kmp-app.md), extracted from the reference
implementation's source and kept in sync with it — read that section before changing any
protocol code, rather than inferring behavior from this app's Kotlin alone.

## Known limitations

- **No authentication.** The app talks to the Pi with no auth of any kind, matching the
  upstream project's own security posture — it assumes a trusted home LAN.
- **Foreground-only BLE.** The BLE connection is torn down when the app leaves the
  foreground and rebuilt (via a fresh scan) when it returns. There is no background BLE
  session; this is a deliberate v1 scope cut, not a bug (see plan §7, "Out of scope").
- **Android 17 (targetSdk 37) needs the local-network permission.** Reaching the Pi over
  Wi-Fi requires the runtime `ACCESS_LOCAL_NETWORK` permission on Android 17+; without it,
  connection attempts silently time out instead of failing fast. The app requests this at the
  point of connecting, the same way it requests Bluetooth permissions.
- **The driving-range flight arcs are flat by design, matching the upstream iOS app.** The
  reference's `constrain()` scales a trajectory's carry distance (x/z) to match the server's
  reported yardage but does **not** rescale its height (y). This app ports that behavior
  faithfully rather than "fixing" it, since diverging from the reference here was flagged and
  intentionally left alone (see the plan's Step 9 execution note).
- **The mock server can show phantom clients for 15–35 seconds after a disconnect.** This is
  a behavior of the reference `openflight-server`'s `ShotStreamBroker`, which only removes a
  subscriber after a failed heartbeat write, not immediately on socket close — confirmed by
  `lsof` showing exactly one live connection per app instance throughout. It is **not** a
  leak in this app; if you see the Pi's client count lag behind reality for half a minute
  after closing the app, that's the server, not a bug here.
- **A specific Raspberry Pi kernel regresses BLE.** Kernel `6.18.34+rpt-rpi-2712` is known to
  break BLE advertising/GATT on the Pi side. If BLE rows in
  [`docs/hardware-test-matrix.md`](docs/hardware-test-matrix.md) fail, check `uname -r` on
  the Pi before assuming an app defect.

## Network security

The Pi serves plain HTTP on the local network, at a host the user types in (a `.local` name
or a bare IP).

- **iOS** uses `NSAllowsLocalNetworking`. That allows cleartext only to local names and
  private address ranges.
- **Android** has no equivalent. `network_security_config` cannot allow-list arbitrary IPs
  entered by the user, so cleartext traffic is permitted app-wide. The tradeoff is
  acceptable because the app only talks to the user's own device on the LAN and never sends
  credentials. Revisit it if the app ever talks to internet hosts.
