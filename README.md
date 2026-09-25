# OpenFlight Companion

<img src="iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png" width="96" alt="OpenFlight Companion icon" align="left" hspace="12" />

An Android + iOS companion app for [OpenFlight](https://openflight.dev), the DIY golf launch
monitor. It talks to the Pi over Wi-Fi (the Pi's own Socket.IO API, plus Server-Sent Events on
backends that have them) or Bluetooth LE (schema v1 and v2). See
[Backend compatibility](#backend-compatibility) for which backend offers what. Each platform has a
**native UI** — Jetpack Compose on Android, SwiftUI on iOS — over the same shared Kotlin
Multiplatform ViewModels, repositories and transports
([ADR 0001](docs/adr/0001-native-ui-shared-viewmodels.md)).

<br clear="left"/>

## Features

- **Live shots over Wi-Fi or Bluetooth LE.** On Wi-Fi the app keeps one Socket.IO connection to
  the Pi (the API the Pi's own web UI uses) and, on backends that serve it, the SSE shot stream
  (`/api/shots/stream?schema=2`). Both reconnect on their own with capped exponential backoff
  (Socket.IO 0.5 s → 5 s, SSE 1 s → 15 s). An automatic reconnect doesn't re-add the shot the
  Pi replays on connect; an explicit Retry does, so a fresh install shows the latest shot at
  once. Over Bluetooth the app negotiates schema v2 when the Pi offers it and falls back to v1
  ([Bluetooth LE](#bluetooth-le-schema-v1-and-v2)).
- **Dashboard**: transport picker, a live connection-status chip, the latest shot's primary
  metrics (ball speed, carry) and detail metrics (club speed, smash, launch vertical/horizontal,
  spin, spin axis, club path), confidence indicators and a carry range when the Pi reports them,
  and previous shots (newest first, capped at 100, deduplicated across transport switches). A
  haptic and a gold flash mark each new shot.
  - A **processing indicator** shows the Pi's `shot_processing` state (capturing, calculating,
    failed) until the next shot arrives. A schema v2 provisional shot is replaced in place by
    its final version.
  - **Connection problems** each get their own message and action: an unreachable Pi, an
    address the endpoint policy refuses, iOS Local Network access denied, and Android's
    `ACCESS_LOCAL_NETWORK` denied (both with a button to open Settings). The connection card
    links to the build docs while idle and to troubleshooting on an error, and offers
    `192.168.4.1:8080` (the Pi's access point) and `192.168.1.100:8080` as tap-to-fill hosts.
  - **Simulate shot** appears only when the Pi runs `--mock`.
- **Profiles**: a picker beside the club selects the Pi's active profile, and adds, renames and
  removes profiles under the server's rules (at most 12 and never zero; names trimmed and at
  most 40 characters; the active profile and a profile with shots can't be removed). The
  dashboard, Session and stats show the active profile's shots. Over Bluetooth v2 the picker is
  select-only.
- **Club selection**, synced both ways: pick a club on the phone and it round-trips to the Pi
  (`POST /api/club` on Wi-Fi, see [Backend compatibility](#backend-compatibility)); change it on
  the kiosk or a simulator and the phone follows the Pi's `club_changed`. The phone never shows
  a club the Pi hasn't confirmed, and asks once per launch, after the first connect, whether the
  Pi's club is the right one. Per-club shot chips with counts sit on the dashboard.
- **Phone-assisted TI radar tilt calibration**: sample the phone's gravity sensor at 60 Hz,
  wait for a stable 2-second average, and submit it to calibrate the TI IWR6843 radar's mount
  tilt, with no external level needed.
- **Driving range**: a ball-flight view (Compose `Canvas` 2.5D on Android, RealityKit 3D on
  iOS) that resolves missing launch/spin values from per-club defaults, simulates the
  trajectory with the reference's RK4 model, and plays it back with a replay button, a club
  selector, and a **camera mode toggle**: a fixed tee-side view, or a **follow camera** that
  chases the ball, frames the landing area in the final approach, and settles on a raised view
  of the landing spot lined up with the nearest yardage marker.
- **Units**: an Imperial (mph/yds) or Metric (km/h/m) toggle, applied everywhere metrics are
  shown. It's a phone setting; it doesn't follow the kiosk's (see [Future work](#future-work)).
- **Session**: the Pi's session for the active profile, grouped by club, with per-club stats
  (count, average/min/max ball speed with a sample standard deviation, average carry, club
  speed and smash), a **shot dispersion map** (one dot per shot, a scatter ellipse per club and
  distance arcs, with a selected-shot card) and CSV export through the Android share sheet or
  the iOS `ShareLink`. While disconnected it says it shows the last session received.
  - **Delete and Clear are server-confirmed** on Wi-Fi: the row shows a pending state until the
    Pi confirms, and fails after 10 s, on a `delete_shot_error` or when the link drops. Clear
    removes only the active profile's rows. Without a Pi link both are local edits.
- **Session history**: every connection to the Pi starts a session that is stored on the phone
  (Room, survives relaunches), listed newest first with its date, shot count and first/last
  shot time, and filterable by profile. Each stored session opens with the same club tabs,
  stats, rows and per-session CSV export. Deleting a stored session or "Clear all history" asks
  first.
- **Device** (Settings): cards for power (a Pi started with `--battery`), trigger, radar and
  debug, each hidden until the Pi reports it. **Shut down the Pi** goes confirm → pending →
  done or failed, with a 10 s timeout and the host captured when you confirm, so a retry can't
  reach a Pi you switched to since. The link drop after the Pi answers is expected, not an
  error.
- **Camera** (Wi-Fi): the Pi's high-speed capture settings, a preview still
  (`/api/camera/preview.jpg`) polled only while the screen is visible, and each shot's replay
  video when the Pi recorded one.
- **More Wi-Fi-only features over Socket.IO**: a swing-speed training mode with implement
  selection, simulator (GSPro) status, the radar/debug panel and cloud-upload status. Over
  Bluetooth the UI disables what BLE can't carry, with an explanation, instead of hiding it.
- **Endpoint policy**: `http://` only to loopback, `.local` names, private (RFC 1918),
  link-local and IPv6 unique-local addresses; `https://` to any host. An address with a user
  name or password, or another scheme, is refused before any request. The app remembers a host
  only after it connected.
- **Lifecycle**: every transport disconnects when the app goes to the background and reconnects
  when it returns. A delete, clear or shutdown still pending then fails with "connection
  dropped" instead of hanging.
- **Accessibility**: metric tiles and shot-history rows announce as one merged phrase for
  TalkBack/VoiceOver (e.g. "Ball speed, 139.1 miles per hour"), the connection status chip is a
  live region that announces state changes on its own, touch targets are at least 48 dp / 44 pt,
  no status relies on colour alone, and reduced motion is respected.

## Backend compatibility

The app works with more than one OpenFlight server. What it can do depends on which one the Pi
runs:

| | Upstream `main` ([open-flight/openflight](https://github.com/open-flight/openflight)) | Fork branch `feat/phone-connectivity` ([btripp/openflight](https://github.com/btripp/openflight/tree/feat/phone-connectivity)) |
|---|---|---|
| Socket.IO session: snapshot, profiles, Session screen, server-confirmed delete/clear, stats, stored history, device cards, power, camera, training, shutdown | Yes | Yes |
| SSE shot stream (`/api/shots/stream`) | No: 404, which the connection chip shows as an error. Live shots still reach Session and the stored history over Socket.IO, but not the dashboard's latest-shot card or the range | Yes, with schema v2 (`?schema=2`) |
| Change the club from the phone over Wi-Fi (`/api/club`) | No: the request fails. Change it on the kiosk | Yes |
| Phone calibration (`/api/calibration/iwr6843/orientation`) | No | Yes |
| Bluetooth LE | None | Schema v1 and v2 |
| `MockServerIT` (pinned in CI) | `7ca4b40`: 22 steps pass, 4 skipped (the SSE and `/api/club` steps) | `07d5313`: all 26 pass |

The fork branch is upstream `main` plus the phone transport from
[`jake-fishtech/openflight@feat/iOS-ble`](https://github.com/jake-fishtech/openflight/tree/feat/iOS-ble)
(BLE v1, SSE, `/api/club`, phone calibration) and BLE schema v2. It isn't upstream yet. A Pi
running jake-fishtech's branch itself speaks BLE v1 and SSE v1; its Socket.IO API predates
profiles, so the profile, power and processing features stay empty there.

HTTPS (`--tls-cert`/`--tls-key`) is on a separate backend branch, `feat/lan-https`, not in
either column yet. See [Network security](#network-security).

### Bluetooth LE: schema v1 and v2

All four characteristics live in one GATT service, `b6f633f2-e6e3-45ae-84b4-968ecca2d9c7`:

| Characteristic | UUID | Use |
|---|---|---|
| v1 shot | `2b28f67e-9011-41d2-98ed-562b47d7a5e4` | notify: shot events |
| v1 control | `7e3b5d6c-7f10-4d4a-9c39-25e2b77f4a11` | write + notify: `set_club`, `get_club`, calibration, `club_changed` |
| v2 shot | `ed365fe6-3abf-4fc3-8e44-d9525a22dabd` | notify: v2 shot events |
| v2 control | `7ba96e63-12c2-4ce0-bb84-3513c7fd1474` | write + notify: v2 commands and events |

Negotiation, after discovery:
1. If the Pi has the v2 pair, the app subscribes to v2 control and sends
   `hello {client_schema_max: 2}` with the usual 10 s control timeout.
2. If the Pi answers schema 2, the app subscribes to the v2 shot characteristic only. It never
   subscribes to the v1 pair, so the Pi sends this phone no v1 traffic.
3. Anything else (`ok:false` from an older Pi, a timeout, a failed subscription, or no v2 pair)
   and the app unsubscribes from v2 control and carries on exactly as version one, which is
   what a jake-fishtech Pi gets.

Every message is split into 20-byte frames, which fits Android's default ATT MTU. The SSE
stream negotiates the same way through its query string: `?schema=2`, with a plain v1 stream
for a Pi that answers `400` (jake-fishtech's ignores the parameter and sends v1).

Schema v2 adds `shot_number`, the profile, and **provisional and final** shots that share an
`event_id` (the app replaces one with the other). It adds the events `club_changed`,
`profiles`, `shot_processing`, `power_status`, `session_cleared` and `shot_deleted`, and the
commands `get_club`/`set_club`, calibration, `get_profiles`, `set_active_profile` and
`get_power_status`. BLE has no authentication, so it is **read-and-select only**: the Pi
refuses `delete_shot` and `clear_session` over BLE, and the app disables Delete and Clear there
with a "Wi-Fi only" explanation. Deletes and clears made elsewhere still reach a v2 phone
through `shot_deleted` and `session_cleared`. The frame format is specified in the backend's
`docs/ios-ble.md`.

### Supported setups

- **Phone as the only interface to a headless Pi.** No screen on the Pi: the app does
  everything, over Wi-Fi (the Pi's LAN address, or `192.168.4.1:8080` when the Pi is its own
  access point) or Bluetooth.
- **Phone plus the Pi's kiosk.** Both are clients of the same server, and every server event is
  a broadcast. The club, active profile, debug mode and radar settings are global to the Pi, so
  a change on either shows on both, and a shot deleted or a session cleared on one disappears
  on the other.
- **Phone plus `/display`.** A TV or monitor shows the Pi's passive `/display` page in a
  browser while the phone does the controlling. The display follows the same broadcasts.

The units toggle is the one setting that doesn't cross over: the kiosk and `/display` keep
their own (see [Future work](#future-work)). Each setup has a row in
[`docs/hardware-test-matrix.md`](docs/hardware-test-matrix.md).

## Module map

| Path | What |
|---|---|
| `androidApp/` | Android application: `MainActivity`, the Jetpack `navigation-compose` nav host and bottom bar (Dashboard/Session/Training/Camera/Settings, plus Calibrate and Range), runtime permission prompts, launch-option intent extras, Koin start, the app icon |
| `shared/` | KMP, no UI: common Koin bootstrap (`initKoin`), `LaunchOptions`, the preview repository, and the iOS umbrella framework `Shared.framework` (exports `core:model`/`core:data`/`core:insights`/`core:flight` and every `feature:*` ViewModel module, plus `KoinHelper` and the `NativeViewModels` bridge for Swift) |
| `iosApp/` | Xcode project (SwiftUI), embeds `Shared.framework` through a Gradle build phase; `Bridge/ViewModelHost.swift` collects shared `StateFlow`s via KMP-NativeCoroutines; the `AppIcon` asset catalog |
| `core/model` | Pure data types: `ShotEvent`, `GolfClub`, `ConnectionState`, calibration and Pi (`pi.*`) models. No I/O. |
| `core/protocol` | The wire-protocol codec: BLE frame encoder/reassembler, `ShotEventDecoder`, SSE parsing, control envelopes, the `ShotTransport` interface. No I/O. |
| `core/ble`, `core/network` | The BLE (Kable-backed) and Wi-Fi/SSE transports, both implementing `core/protocol`'s `ShotTransport` |
| `core/socketio` | A minimal Engine.IO v4 / Socket.IO v5 client over a Ktor WebSocket, which `core/data`'s `PiSessionRepository` uses for the Pi's session API |
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
| `plans/openflight-kmp-app.md` | The build plan: **§0 is the wire-protocol reference, and §9.1 supersedes it for the current backend** |

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
feature:range, feature:session ──> core:flight ; feature:calibration ──> core:sensors ; others ──> core:insights
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
OPENFLIGHT_BACKEND_DIR=~/Developer/oss/openflight ./gradlew mockServerIT   # against a live mock server, see below
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

### Integration test against a real mock server (`MockServerIT`)

`MockServerIT` (`core/data/src/androidHostTest`) runs the app's real data layer
(`DefaultShotRepository`, `DefaultPiSessionRepository`, the Ktor SSE/HTTP/Socket.IO clients,
DataStore settings and an in-memory Room history) against a live `openflight-server --mock`
from a backend checkout. It isn't part of `allTests`; it has its own task:

```bash
cd ~/Developer/oss/openflight && uv sync          # once per backend checkout
OPENFLIGHT_BACKEND_DIR=~/Developer/oss/openflight ./gradlew mockServerIT
# or: ./gradlew mockServerIT -Popenflight.backendDir=/path/to/openflight
```

Without a backend directory the task is skipped with a message. The test starts the server on
a free port with a temporary `HOME`, `--log-dir` and `--profiles-path` (plus `--battery
geekworm`, which reports `power_status` "unavailable" without the hardware), waits for it, and
walks: connect → snapshot (`session_state`, `profiles`, `trigger_status`, debug, power) →
`simulate_shot` → the shot over Socket.IO, into the stored history and over SSE → club change
→ add, rename, select and remove a profile → server-confirmed `delete_shot` (and
`delete_shot_error`) → `clear_session` for the active profile → debug, radar, training and
camera commands → disconnect and reconnect → `POST /api/shutdown` and the expected link drop.
Steps a backend can't support (SSE and `/api/club` on upstream `main`) are reported as skipped,
and the log ends with a PASS/SKIP summary. The server is killed on every path. CI runs it on
Linux against both pinned backends (`.github/workflows/mock-server-it.yml`) when app code
changes.

The job needs no Pi, emulator or simulator. It lives in the Android host-test source set
because that's the one JVM source set that can reach `core:data`'s internals and the Android
actuals of Ktor, DataStore and Room; a separate JVM module would need a `jvm()` target on
`core:data` and everything under it.

### Contract goldens

- **BLE/SSE goldens from the backend** (`core/protocol/src/commonTest/fixtures/openflight-ble/`):
  the backend's cross-language fixtures, copied verbatim. The
  [README there](core/protocol/src/commonTest/fixtures/openflight-ble/README.md) has the refresh
  steps; afterwards run `./gradlew :core:protocol:allTests :core:ble:allTests`.
- **v1 frame goldens** (`GoldenFrameTest`): regenerate with `tools/gen-frame-goldens.py` from
  the reference encoder; the script's header has the command.
- Screenshots taken during manual or device checks are build artifacts, not source: don't
  commit them.

### Launch options for previews and UI tests

Debug builds read launch hooks, so UI tests and manual checks don't need a live Pi. iOS takes
them as launch arguments; Android as intent extras (`--ez <name> true`, `--es transport wifi`,
`--es host <host:port>`) with the same names in snake_case:

| iOS argument | Android extra | Effect |
|---|---|---|
| `--ui-testing` | `ui_testing` | A fake shot repository; no transport ever starts |
| `--preview-shot` | `preview_shot` | Like `--ui-testing`, with one canned shot |
| `--preview-pi` | `preview_pi` | Like `--ui-testing`, plus a fake Pi with a profile roster, power and trigger status and a swing being calculated (picker, device cards, shutdown) |
| `--preview-pi-session` / `--preview-pi-session-stuck` | `preview_pi_session` / `preview_pi_session_stuck` | With `--ui-testing`: a connected fake Pi whose deletes and clears are confirmed after a short delay, or never |
| `--preview-history` / `--preview-history-stuck` | `preview_history` / `preview_history_stuck` | Two stored sessions whose deletes land after a delay, or never |
| `--range-mode`, `--preview-flight` | `range_mode`, `preview_flight` | Open the driving range and fly the shot |
| `--transport wifi\|bluetooth`, `--host <host:port>` | `transport`, `host` | Seed the settings before the UI starts |

```bash
adb shell am start -n dev.openflight.companion/.MainActivity \
  --ez preview_pi true --es transport wifi --es host 10.0.2.2:8091
```

## Running against the mock server

The OpenFlight Pi server runs without radar hardware in mock mode. Use a checkout of
[upstream `main`](https://github.com/open-flight/openflight), or of the
[`feat/phone-connectivity`](https://github.com/btripp/openflight/tree/feat/phone-connectivity)
fork branch for SSE, `/api/club` and Bluetooth (see [Backend compatibility](#backend-compatibility)):

```bash
uv sync
uv run openflight-server --mock --web-port 8091   # --web-port matters if 8080 is already in use
curl -N http://localhost:8091/api/shots/stream     # fork branch only: sanity-check the SSE stream
```

Add `--battery geekworm` to see the power card: without the battery HAT the Pi reports the
power status as unavailable. Keep the server's files out of your home directory with
`--log-dir` and `--profiles-path` if you like.

Mock shots fire only through the Socket.IO event `simulate_shot`; there is no HTTP route for
it. The app shows a **Simulate shot** button when the Pi runs `--mock`, or use the helper, which
connects as a Socket.IO client and emits it for you:

```bash
uv run --with "python-socketio[client]" tools/fire-mock-shot.py -n 5 --url http://localhost:8091
```

Each shot reaches the app over Socket.IO (with its profile, confidence and the other
`shot_to_dict` fields) and, on the fork branch, also over SSE, where it is matched to the
Socket.IO detail by timestamp.

**Host, per platform:**

| Platform | Host to enter in the app |
|---|---|
| Android emulator | `10.0.2.2:8091`, the emulator's alias for the host machine's `localhost` |
| iOS Simulator | `localhost:8091` (or `127.0.0.1:8091`); the simulator shares the host's network namespace |
| A real phone | The Pi's actual LAN address, e.g. `raspberrypi.local:8080` (the app's default) or an IP |

To run without any server, use the [launch options](#launch-options-for-previews-and-ui-tests).

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
implementation's source, with **§9.1 superseding it** wherever the current backend differs
(profiles, `shot_update`, `shot_processing`, `power_status`, the camera API). BLE schema v2 is
specified in the backend's `docs/ios-ble.md` and pinned here by the copied goldens. Read those
before changing any protocol code, rather than inferring behavior from this app's Kotlin alone.

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
     `curl "http://<host>:8080/socket.io/?EIO=4&transport=polling"` and confirm it answers
     with a `0{"sid":...}` handshake. On the fork branch, `curl -N http://<host>:8080/api/shots/stream`
     should also open with `: ping`.
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
- **Bluetooth carries less than Wi-Fi.** BLE schema v2 brings the club, profile selection,
  processing and power status, and provisional/final shots. The Pi's session and its stats,
  delete and clear, profile edits, the camera, training mode, simulator status, the radar/debug
  panel, cloud upload and Pi shutdown still need the Pi's Socket.IO API, which is Wi-Fi only;
  a v1 Pi carries only shots and the club. Over BLE the UI disables these with an explanation
  instead of hiding them.
- **On upstream `main` the dashboard gets no live shots over Wi-Fi.** Its latest-shot card and
  the range read the SSE/BLE shot stream, which upstream doesn't serve, so they stay empty and
  the connection chip shows the 404; Session and the stored history still fill over Socket.IO.
  See [Backend compatibility](#backend-compatibility).
- **A specific Raspberry Pi kernel regresses BLE.** Kernel `6.18.34+rpt-rpi-2712` is known to
  break BLE advertising/GATT on the Pi side. If BLE rows in
  [`docs/hardware-test-matrix.md`](docs/hardware-test-matrix.md) fail, check `uname -r` on the
  Pi before assuming an app defect.
- **No Raspberry Pi is available yet for hardware verification.** Everything in
  [`docs/hardware-test-matrix.md`](docs/hardware-test-matrix.md) is simulator/emulator-only
  until then; see that file for the pending checklist.

## Network security

The Pi serves plain HTTP on the local network, at a host the user types in (a `.local` name or
a bare IP), or HTTPS when the operator gives it a certificate.

- **Endpoint policy (both platforms).** `core:network`'s `EndpointPolicy` checks every address
  before SSE, HTTP control, the camera or Socket.IO sees it: `http://` only to loopback,
  `.local` names, RFC 1918, `169.254.0.0/16`, `fe80::/10` and `fc00::/7`; `https://` to any
  host; no user name or password in the address and no other scheme.
- **iOS** uses `NSAllowsLocalNetworking`, which allows cleartext only to local names and
  private address ranges.
- **Android** has no equivalent: `network_security_config` can't allow-list IPs the user types
  in, so cleartext is permitted app-wide there and the endpoint policy is what keeps `http://`
  on the LAN. The config also trusts **user-installed CAs**, for the private-CA HTTPS below.

### HTTPS with a private CA

The backend branch `feat/lan-https` (not upstream yet) adds `--tls-cert` and `--tls-key`: the
server then speaks HTTPS, and Socket.IO over `wss://`, on the same `--web-port`, never both
HTTP and HTTPS. The certificate is yours to provision, so the phone has to trust the CA that
signed it. This path isn't verified on devices yet; it's a row in the hardware matrix.

1. Make a CA and a server certificate whose subject alternative names cover exactly what you'll
   type in the app, e.g. `raspberrypi.local` and the Pi's IP. [mkcert](https://github.com/FiloSottile/mkcert)
   does both: `mkcert raspberrypi.local 192.168.1.50`, with the CA in `$(mkcert -CAROOT)/rootCA.pem`.
   iOS also needs TLS 1.2 or newer, a SHA-256 signature, an RSA key of at least 2048 bits (or
   ECDSA P-256) and a validity of at most 825 days; mkcert's defaults meet all of these.
2. Start the Pi with `openflight-server --tls-cert <cert.pem> --tls-key <key.pem>`.
3. **Android:** copy `rootCA.pem` to the phone and install it under **Settings > Security >
   More security settings > Encryption & credentials > Install a certificate > CA certificate**
   (the path varies by manufacturer).
4. **iOS:** send `rootCA.pem` to the iPhone (AirDrop or Mail), install the profile under
   **Settings > General > VPN & Device Management**, then turn on full trust for it under
   **Settings > General > About > Certificate Trust Settings**.
5. Enter `https://raspberrypi.local:8080` (your host and port) in the app.

## Future work

- **Unit preference sync with the kiosk.** The phone's Imperial/Metric toggle is a phone
  setting, and the Pi's web UI keeps its own per browser. Syncing them needs the server to own
  the setting (a Socket.IO event or a profile setting) first.

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
