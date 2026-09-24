# Blueprint: OpenFlight Companion App (Kotlin Multiplatform)

**Objective:** Build a Kotlin Multiplatform (Android + iOS) companion app for the
[OpenFlight](https://openflight.dev) DIY golf launch monitor. It should match what the
existing native SwiftUI app on `jake-fishtech/openflight@feat/iOS-ble` does: live shots over
BLE or Wi-Fi (SSE), club selection, phone-assisted TI radar tilt calibration, shot history,
and a driving-range ball-flight view.

**Project dir:** `/Users/btripp/Developer/oss/openflight-mobile-kmp` (empty when this plan
was written, and not yet a git repo)
**Reference implementation:** `github.com/jake-fishtech/openflight`, branch `feat/iOS-ble`,
commit `b053194`. Read it by cloning into a scratch dir. It is never a build dependency.
**Plan status:** FINAL (reviewed 2026-09-24). See the Review Log at the bottom.
**Created:** 2026-09-24

---

## 0. Ground truth extracted from the reference (read before any step)

Every step relies on these facts. They come from reading the reference branch's source, not
from memory. If a step finds the reference has changed, re-verify against the pinned commit
`b053194` first.

### 0.1 Wire protocol (source: `docs/ios-ble.md`, `src/openflight/ble/protocol.py`)

| Item | Value |
|---|---|
| BLE service UUID | `B6F633F2-E6E3-45AE-84B4-968ECCA2D9C7` |
| Shot characteristic (notify) | `2B28F67E-9011-41D2-98ED-562B47D7A5E4` |
| Control characteristic (write-with-response + notify) | `7E3B5D6C-7F10-4D4A-9C39-25E2B77F4A11` |
| Frame | 20 bytes max: 5-byte big-endian header `>BHBB` = version(1), u16 sequence, u8 index, u8 count; then ≤15 payload bytes |
| Max message | 15 × 255 = 3825 bytes |
| Reassembly rule | New sequence → discard partial message and start over; same sequence but a different count → reset and error; duplicate index overwrites; emit when all `count` fragments are present |
| Shot JSON (schema_version 1) | `schema_version, event_id(UUID), timestamp(ISO local, may have µs), club, ball_speed_mph, estimated_carry_yards` are **required**. `club_speed_mph, smash_factor, launch_angle_vertical, launch_angle_horizontal, spin_rpm, club_path_deg, spin_axis_deg` are **nullable** |
| Club event | `{"schema_version":1,"type":"club_changed","club":"7-iron"}`. Over BLE it arrives on the **control** characteristic. Over SSE it arrives as `event: club_changed` |
| Control request | `{"schema_version":1,"type":"set_club"\|"get_club"\|"iwr6843_orientation_calibration","request_id":"<lowercase uuid>","payload":{...}}` |
| Control response | `{"schema_version":1,"request_id":..., "ok":bool, "result":{...} \| "error":"..."}` |
| Club result | `{"status":"...","club":"7-iron"}` |
| Calibration result | `status, persistent, measured_mount_tilt_deg, enclosure_pitch_deg?, configured_iwr_tilt_deg, roll_deg, azimuth_offset_deg` |
| Club raw values | `driver, 3-wood, 5-wood, 7-wood, 3-hybrid, 5-hybrid, 7-hybrid, 9-hybrid, 2-iron … 9-iron, pw, gw, sw, lw` |

### 0.2 Wi-Fi transport

- `GET http://<host>[:8080]/api/shots/stream` streams SSE (`text/event-stream`). The stream
  opens with a `: ping` comment, replays the last shot, sends a heartbeat every 15 s, and
  returns **503** when more than 8 clients are connected.
- `GET|POST /api/club` with body `{"club":"7-iron"}` returns the club result above.
  `POST /api/calibration/iwr6843/orientation` returns the calibration result above. Error
  bodies look like `{"error":"..."}`.
- Host normalization follows `WiFiShotClient.endpointURL`: trim the input, add `http://` when
  there is no scheme, allow only http/https, default the port to **8080 only for http**, and
  drop any query or fragment. The default host is `raspberrypi.local:8080`.
- Reconnect backoff starts at 1 s, doubles each time, and caps at 15 s. The idle timeout is
  45 s (three missed heartbeats).

### 0.3 Client behavior to preserve

- `ShotEventDecoder` rejects `schema_version != 1`. It drops an event whose `event_id` equals
  the **last** decoded id, which suppresses the replay a server sends on connect.
  - **Automatic reconnects keep `lastEventId`.** That is what suppresses the replay.
  - BLE **never** resets the decoder (BluetoothManager.swift:115-130, 354-369 reset only the
    reassemblers).
  - Wi-Fi resets it only on an explicit user `disconnect()`/`retry()`, never inside the
    auto-reconnect loop.
- Decode errors and `club_changed` errors set `ConnectionState.error(msg)`. They are not
  swallowed.
- **On connect, the client syncs the current club**
  (ContentView.swift:107-111, 331-353).
  - It calls `currentClub()` (`get_club` or `GET /api/club`) once the state is `connected`.
    For BLE it also waits until `supportsPhoneControls` is true.
  - The server's club **overwrites** the locally stored club.
- The club menu is enabled when `state == connected && !isChanging` (ContentView.swift:282).
  It is not gated on `supportsControls`.
- The Wi-Fi host field applies on submit, not with a debounce.
- Wi-Fi backoff resets only after a clean stream end, not on a successful connect.
- CoreBluetooth `.unknown` maps to `idle`.
- Club display names come from Swift `.capitalized`: `driver`→"Driver", `7-iron`→"7-Iron",
  `3-wood`→"3-Wood". Wedges use explicit names: "Pitching Wedge", "Gap Wedge", "Sand Wedge",
  "Lob Wedge". The shot's `displayClub` also runs `_`→space and then capitalizes.
- `ShotHistory` is ordered newest first, capped at 100, and deduplicated by `event_id`.
- `ConnectionState` has these states: `idle, unavailable(msg), scanning, connecting,
  discovering, connected, error(msg)`. `canRetry` is true for idle, unavailable and error.
- BLE control commands:
  - Only one command can be in flight at a time. A second command fails with `busy`.
  - Each command times out after 10 s.
  - Frames are written **with response**, one at a time, and the next frame is written only
    after the write callback.
  - The control sequence is a u16 that wraps.
  - A disconnect fails the pending command.
- On BLE disconnect the app goes back to scanning after 1 s.
- Calibration (`PhoneOrientation.swift`):
  - Sample gravity at 60 Hz in a rolling window of the **120 most recent raw samples**.
  - Filtering to magnitudes within 0.8–1.2 g happens *inside* that window, and a
    measurement needs ≥ 120 valid samples. So the whole window must be valid.
  - The server re-checks that the mean gravity lies within 0.9–1.1 g.
  - `tilt = asin(clamp(-z/|g|))`, `roll = atan2(x, -y)`, both in degrees.
  - A measurement is ready to send when: n ≥ 120, the tilt and roll standard deviations are
    each ≤ 0.5°, |roll| ≤ 3°, and tilt is between −30° and 45°.
  - The display shows the stable average once live readings are within 0.5° of it.
- The payload keys include `schema_version, mount_tilt_deg, roll_deg, gravity_{x,y,z}_g,
  tilt_stddev_deg, roll_stddev_deg, sample_count, measured_at(ISO8601), device_model`.
- **The gravity convention is iOS CoreMotion's:** units are g, the vector points toward
  Earth, and a phone lying face-up reads z ≈ −1. The Pi recomputes the angles from the
  gravity vector, so Android readings **must** be converted to this convention.
- Driving range (`ios/OpenFlight/DrivingRange/*`):
  - Resolve missing launch and spin values from per-club defaults, and record provenance.
  - Integrate the flight with RK4 using dt = 1/120 s, air density ρ = 1.204, drag coefficient
    Cd = 0.24, lift slope 0.60, maximum lift coefficient 0.34, ball mass 0.04593 kg, ball
    radius 0.02135 m, and a 20 s cap.
  - Constrain the trajectory to the server's carry value.
  - Playback lasts `clamp(flightTime*0.68, 3.5, 6)` seconds.
  - The iOS app renders the scene with RealityKit.
  - The VM phase machine is `waiting → preparing → flying → landed`, plus `unavailable(msg)`.
    Only the **newest pending shot** plays after the current flight; any older queued shots
    are dropped.
  - Landing dwells for 1.25 s.
  - Reduced motion uses a 0.9 s playback.
  - `suspend()` runs on background or disappear.
  - The screen has replay and exit buttons, a club selector and club error inside the
    overlay, a landscape layout, and a "Driving Range Ready" card.
  - **One** tracer is drawn at a time.
  - UI-test launch hooks: `--ui-testing`, `--preview-shot`, `--range-mode`,
    `--preview-flight`.
  - Read `DrivingRangeViewModel.swift` for the exact values.

### 0.3a Server facts for verification

- Run the mock server with `uv run openflight-server --mock` (pyproject.toml:75). No radar is
  needed.
- Mock shots fire **only** through the Socket.IO event `simulate_shot`. There is no HTTP
  route.
- `/api/calibration/iwr6843/orientation` returns **409 "TI IWR6843 radar is not enabled"**
  unless the server runs with `--iwr6843`, which needs real TI hardware. **The mock server
  cannot verify a successful calibration.**
- The server rejects control envelopes and calibration payloads that lack
  `"schema_version":1` (phone_orientation.py:50, ble/publisher.py:316).

### 0.4 Test oracles available

- `ios/OpenFlightTests/Fixtures/shot_v1.json` is the shared contract fixture.
- `ios/OpenFlightTests/*Tests.swift` covers the frame reassembler, SSE parser, decoder,
  history, Wi-Fi client, **BluetoothManager (+ BLETestFixtures)**, phone orientation, flight
  simulator, input resolver, camera planner and range view model. The
  `OpenFlightUITests/DrivingRangeUITests.swift` file covers the UI. **Port the assertions and the numeric expectations. Do not rewrite
  them from scratch.**
- `tests/test_ble_protocol.py` tests the Python framing and is the authoritative encoder.
- The Pi server runs **without hardware** with `--mock`. Mock shots fire on the Socket.IO
  event `simulate_shot`. This is the end-to-end oracle for Wi-Fi.

### 0.5 License constraint

The upstream project is **AGPL-3.0-or-later**. Porting its Swift logic makes this app a
derivative work, so this repo is AGPL-3.0-or-later and must credit the upstream project and
the iOS contributor. Don't relicense.

### 0.6 Takeaways from reference commit `b053194` ("Updated readme for iOS app build": the `ios/README.md` setup and troubleshooting guide)

These were reviewed at the user's request on 2026-09-24. The commit changes documentation only
and leaves the protocol unchanged. Apply these points in R2 and R4:
- **Permissions.** Each one is prompted only by the feature that needs it:
  - Bluetooth for BLE
  - **Local Network** for Wi-Fi (`NSLocalNetworkUsageDescription`,
    `NSAllowsLocalNetworking`)
  - **Motion & Fitness** for calibration (`NSMotionUsageDescription`)

  The SwiftUI app (R2) must keep all three plist keys. A denied permission must show
  guidance to re-enable it in Settings.
- **`--ui-testing` suppresses the automatic connection** in UI tests. Keep that semantic in
  both apps.
- **Simulator names aren't portable.** Scripts and CI must pick a simulator **UUID** from
  `xcrun simctl list devices available`, never a hard-coded name. Document
  `-only-testing:` for unit-only runs.
- **Signing.** Contributors must choose their own team and a unique bundle ID. Keep personal
  signing values out of commits. Our bundle ID is `dev.openflight.companion`.
- **"Verify the app" checklist**, to reuse in `docs/hardware-test-matrix.md` and the README:
  1. reach Connected
  2. a shot appears
  3. the Driving Range shows the trajectory
  4. a club change is accepted
  5. calibration on a device with an IWR6843
- **The replay on connect is intended.** A fresh install shows the latest shot without
  waiting.
- **Troubleshooting entries to port** into our README (R4), adapted to both platforms:
  - the Pi kernel `6.18.34+rpt-rpi-2712` BLE regression; check `uname -r` first
  - "Looking for OpenFlight" (`--ble`, `bluetoothctl show`, stay in the foreground)
  - Wi-Fi not connecting (`curl -N …/api/shots/stream`, an IP instead of `.local`, VPNs and
    client isolation, the Local Network permission)
  - the 8-client limit returning 503
  - calibration unavailable in the simulator
  - the harmless App Intents build warning
  - `CoreSimulatorService connection became invalid` (fix it with `xcrun simctl shutdown all`)

---

## 1. Architecture decisions (locked unless a step's exit criteria prove one wrong)

| Decision | Choice | Why / alternative rejected |
|---|---|---|
| Shared UI | ~~Compose Multiplatform~~ **Superseded by ADR 0001 (§8): Jetpack Compose on Android, SwiftUI on iOS, shared KMP ViewModels** | One UI codebase. The native SwiftUI app already exists for anyone who wants pure native |
| BLE | **Kable** (`com.juul.kable`), hidden behind our own `BleCentral` interface | Kable is a mature KMP BLE library covering Android and iOS. The interface makes transports testable with fakes |
| HTTP/SSE | **Ktor client**: the OkHttp engine on Android, Darwin on iOS, and the SSE plugin *or* a hand-rolled byte-level parser | We port `SSEEventParser` either way. Use the Ktor SSE plugin only if it reliably handles comment heartbeats and CRLF. Step 4 decides using tests |
| JSON | kotlinx.serialization with **`encodeDefaults=true`** (otherwise `schema_version=1` defaults are left out and the server rejects the payload), `ignoreUnknownKeys=true`, and `explicitNulls=true` (the default) | Forward compatibility with new server fields |
| DI | Koin (KMP) | Lightweight and the standard choice for KMP |
| Persistence | `androidx.datastore:datastore-preferences-core` (KMP) | Stores transport, host and selected club, the same values iOS keeps in `@AppStorage` |
| Lifecycle/VM/Nav | JetBrains multiplatform `lifecycle-viewmodel-compose` and `navigation-compose` | Supports the UDF pattern from the global rules |
| Sensors | `expect/actual GravitySensor`: Android `Sensor.TYPE_GRAVITY`, iOS `CMMotionManager.deviceMotion.gravity` | Converts to the iOS convention in the Android actual |
| Range rendering | **Compose `Canvas` with a 2.5D perspective projection** in commonMain | A shared 3D engine is out of scope for v1. RealityKit parity is a stretch goal after v1 |
| Tests | `kotlin.test` + **assertk** (a Truth-style KMP library) in commonTest, **Turbine** for Flows, `kotlinx-coroutines-test` `runTest`. Android-only tests may use Truth | Truth is JVM-only and cannot run in commonTest |
| Formatting | Spotless + ktlint, plus detekt | Global rule: run the formatter before calling a change done |
| Build | Gradle version catalog + `build-logic/` convention plugins | Global rule |

**Module graph** (arrows point from a module to its dependencies; core never depends on
feature):

```
androidApp ─┐
iosApp(Xcode, embeds ComposeApp.framework) ─┐
            └──> composeApp (shared app shell: nav host, DI graph, App())
                    ├──> feature:dashboard ─┐
                    ├──> feature:calibration ├──> core:data ──> core:ble ─────┐
                    └──> feature:range ─────┘  (DataStore inside) └─> core:network ├─> core:protocol ──> core:model
                              │                                                │
                              └──> core:flight ──> core:model                  │
   all feature:* ──> core:designsystem ; feature:calibration ──> core:sensors ─┘
```

- `core:model`: pure data types (`ShotEvent`, `GolfClub`, `ConnectionState`,
  `PhoneOrientationMeasurement`, `CalibrationResult`, `ClubSelection`). No I/O.
- `core:protocol`: frame encoder and reassembler, `ShotEventDecoder`, SSE parser, control
  envelope codec, and the JSON config. No I/O.
- `core:ble` and `core:network`: the transports. Each exposes a `ShotTransport` interface.
- `core:data`: `ShotRepository` (single source of truth) and `SettingsRepository`.
- `core:flight`: the pure-math resolver, simulator and camera planner.

**Package root:** `dev.openflight.companion`. **Android applicationId:**
`dev.openflight.companion`. Change both in Step 1 if the user prefers other values.

---

## 2. Execution mode

- `git` and `gh` are available, and `gh` is authenticated as `btripp`.
- The project dir **is not a git repo yet**. Step 1 runs `git init`.
- **Creating a GitHub remote is outward-facing.** The executing agent must ask the user before
  running `gh repo create` and must use the name and visibility the user chooses.
  - With a remote: use **branch/PR mode**. Each step works on branch
    `step-N-<slug>` off `main`, opens a PR, and waits for green CI before merging.
  - Without a remote: use **local-branch mode**. Branch per step, run the verification
    commands locally, then `git merge --no-ff` into `main`.
- Commit messages end with the Claude co-author trailer defined in the session.

---

## 3. Dependency graph & parallelism

```
S1 bootstrap
 ├─> S2 core:model + core:protocol ─┬─> S4 Wi-Fi transport ─┐
 │                                  └─> S5 BLE transport ───┴─> S6 core:data ─┬─> S7 dashboard ─┐
 └─> S3 design system ───────────────────────────────────────────────────────┤                ├─> S10 E2E hardening & release
                                    S2 ─> S8a core:flight (pure) ─────────────┼─> S9 range ─────┤
                                    S2 ─> S8b core:sensors + calc ────────────┴─> S8c calibration UI ┘
```

| Wave | Steps (run in parallel inside a wave) | Shared-file risk |
|---|---|---|
| 1 | S1 | none |
| 2 | S2, S3 | Both add to `settings.gradle.kts`. The conflict is trivial; merge S2 first |
| 3 | S4, S5, S8a, S8b | Each adds a module to `settings.gradle.kts`. No other shared files |
| 4 | S6 | none |
| 5 | S7 first, **then** S8c and S9 in parallel | S7 creates `AppNavHost.kt`. S8c and S9 each add one route to it, so rebase and merge serially |
| 6 | S10 | none |

**Model tier:**
- Strongest model: S1 (foundation), S5 (BLE concurrency and platform quirks), S6 (the
  single-source-of-truth design), S8b (sensor convention math).
- Default model: all other steps.

---

## 4. Invariants (verify after EVERY step)

1. `./gradlew spotlessCheck detekt` passes.
2. `./gradlew allTests` passes (all KMP targets that can run on the host, including
   `iosSimulatorArm64Test` on macOS).
3. `./gradlew :androidApp:assembleDebug` succeeds, and
   `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` succeeds.
4. No `core:*` module depends on any `feature:*`, `composeApp` or app module. Check with
   `./gradlew :core:<m>:dependencies` or by grepping `build.gradle.kts` for
   `projects.feature`.
5. Feature UIs use only the `core:designsystem` wrappers (`Of*`) and never raw Material3
   components. Enforce with a detekt/ktlint custom rule or a CI grep for `import
   androidx.compose.material3.(Button|TextField|Card|…)` outside `core/designsystem`.
6. No Android framework types in `commonMain`, and none in the public API of `core:data` or
   `core:model`.
7. No generated screenshot goldens are committed (`.gitignore` covers them).
8. Every file ported from the reference carries an SPDX header
   `SPDX-License-Identifier: AGPL-3.0-or-later`.

---

## 5. Steps

### Step 1: Repo bootstrap, build logic, empty app on both platforms

- **Depends on:** none. **Model:** strongest.
- **Branch:** `step-1-bootstrap` (or commit straight to `main` because the repo is empty;
  that's acceptable for this step only).

**Context brief:** The project dir is empty. Build a KMP skeleton that launches a
"OpenFlight" placeholder screen on an Android emulator and an iOS simulator. Use the current
official KMP layout.
- Since AGP 9, the Android application module must be separate from the KMP shared module.
  That gives `androidApp/` (com.android.application) plus `composeApp/` (a KMP library that
  uses `com.android.kotlin.multiplatform.library`).
- **Before writing any build file, look up the latest stable versions** of the following
  with Context7 or Maven Central / the Gradle plugin portal, and record the date checked in
  `gradle/libs.versions.toml`:
  - Kotlin, AGP, Compose Multiplatform
  - Kable, Ktor, kotlinx.serialization, kotlinx.coroutines
  - Koin, DataStore, JetBrains lifecycle/navigation
  - Turbine, assertk, Spotless, ktlint, detekt
- Don't use versions from memory.
- **Start from the official JetBrains KMP wizard output** (kmp.jetbrains.com, Android + iOS
  with shared Compose UI) instead of hand-writing the `.xcodeproj`. Then restructure it into
  the layout below.
- The detekt convention plugin must set its `source` to include every KMP source set
  (`src/commonMain`, `src/androidMain`, `src/iosMain`, and the test sets). detekt's default
  covers only `src/main|test`.
- The KMP library convention plugin must **explicitly enable Android host tests**, and device
  tests where needed. The `com.android.kotlin.multiplatform.library` plugin enables neither by
  default. Verify the current DSL names. Also confirm that `allTests` actually runs the
  Android host tests: the output must list them.

**Tasks:**
1. `git init -b main`. Add a `.gitignore` for Gradle, IDE, Xcode, `local.properties`,
   `build/`, `*.xcuserstate`, and screenshot-test output dirs.
2. Add `LICENSE` with the full AGPL-3.0 text.
3. Add a `README.md` that covers:
   - what the app is
   - a link to openflight.dev
   - credit to upstream `jewbetcha/openflight` and to the iOS BLE work on
     `jake-fishtech/openflight@feat/iOS-ble`
   - build instructions
4. Add `CLAUDE.md` with project conventions: module graph, test stack, invariants from
   section 4, and the pointer "wire protocol facts live in plans/openflight-kmp-app.md §0".
5. Add the Gradle wrapper, `gradle/libs.versions.toml`, and `settings.gradle.kts` with
   typesafe project accessors.
6. Create `build-logic/convention` with these plugins:
   - `openflight.kmp.library`: sets up the Android library and the iOS targets
     `iosArm64` and `iosSimulatorArm64`, plus common test deps (kotlin.test, assertk,
     turbine, coroutines-test).
   - `openflight.kmp.compose`: adds Compose Multiplatform on top of the library plugin.
   - `openflight.android.application`
   - `openflight.spotless` and `openflight.detekt`
7. Create `composeApp/` with a commonMain `App()` composable and a `MainViewController()` in
   iosMain.
8. Create `androidApp/` with `MainActivity` hosting `App()`. Set `minSdk 26`, and set
   `targetSdk` and `compileSdk` to the latest stable.
9. Create `iosApp/`: an Xcode project that embeds `ComposeApp.framework` through the Gradle
   `embedAndSignAppleFrameworkForXcode` build phase. Set the deployment target to iOS 17 to
   match the reference. Copy these Info.plist keys from the reference
   `ios/OpenFlight-Info.plist`: `NSAppTransportSecurity/NSAllowsLocalNetworking`,
   `NSLocalNetworkUsageDescription`, `NSMotionUsageDescription`. Also add
   `NSBluetoothAlwaysUsageDescription`.
10. In the Android manifest:
    - Add `BLUETOOTH_SCAN` with `usesPermissionFlags="neverForLocation"`, `BLUETOOTH_CONNECT`,
      and `INTERNET`.
    - Add legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` capped at `maxSdkVersion=30`, and
      `ACCESS_FINE_LOCATION` capped at `maxSdkVersion=30` for BLE scanning on API ≤ 30.
    - Add a `network_security_config` that permits cleartext. The Pi serves plain HTTP on the
      LAN, and a hostname allow-list is impossible for user-entered IPs, so document the
      tradeoff in the README.
11. Add CI in `.github/workflows/ci.yml`:
    - An `ubuntu` job runs `spotlessCheck detekt allTests :androidApp:assembleDebug`, skipping
      the iOS test tasks on Linux.
    - A `macos` job runs `iosSimulatorArm64Test` and `xcodebuild build` for `iosApp` with the
      simulator destination and `CODE_SIGNING_ALLOWED=NO`.
12. Add `tools/fire-mock-shot.py`. It uses `python-socketio` to connect to a running
    `uv run openflight-server --mock` and emit `simulate_shot` N times, pausing about 1 s
    between shots. Document its use in the README.
    - Check that it works: run the server from the reference clone, run
      `curl -N localhost:8080/api/shots/stream`, and confirm that `event: shot` lines appear.
    - If the Wi-Fi SSE stream doesn't emit mock shots, record that in the README and in this
      plan's Review Log.
13. **Ask the user** before creating a GitHub remote (name and visibility). If they approve,
    run `gh repo create` and push.

**Verification:**
```bash
./gradlew spotlessCheck detekt allTests :androidApp:assembleDebug :composeApp:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

**Exit criteria:**
- Both commands succeed.
- The placeholder screen is visible on the Android emulator and the iOS simulator. Take a
  screenshot of each with the `run` skill.
- `libs.versions.toml` records the date the versions were checked.

**Rollback:** delete the created files. The directory was empty to begin with.

---

### Step 2: `core:model` + `core:protocol` (pure, TDD, contract fixtures)

- **Depends on:** S1. **Model:** default. **Parallel with:** S3.

**Context brief:**
- Port the reference's pure protocol logic into commonMain with no I/O, and write the tests
  first.
- The facts are in §0.1 and §0.3.
- Reference files:
  - `ios/OpenFlight/{ShotEvent,ShotEventDecoder,BLEFrameReassembler,SSEEventParser,ShotHistory,ConnectionState,PhoneControl}.swift`
  - `ios/OpenFlightTests/{BLEFrameReassemblerTests,SSEEventParserTests,ShotEventTests,ShotHistoryTests}.swift`
  - `src/openflight/ble/protocol.py`
- The Python encoder is authoritative for byte layout.

**Tasks:**
1. In `core:model`, add the following:
   - `ShotEvent`, marked `@Serializable` with `@SerialName` snake_case keys. Keep required
     and nullable fields exactly as in §0.1.
   - `eventId` as `String`, validated as a UUID on decode, so the model doesn't depend on
     `kotlin.uuid` stability.
   - `timestamp` as `String`.
   - A `displayClub` helper.
   - `GolfClub` as an enum with `serialName` equal to the raw wire value and a
     `displayName`. The exact strings are listed in §0.3; test all 20 values.
   - `ConnectionState` as a sealed interface with `description` and `canRetry`.
   - `ClubSelection`, `CalibrationResult` and `PhoneOrientationMeasurement`, each with the
     exact wire keys from §0.1/§0.3.
2. In `core:protocol`, add the following:
   - `BleFrameEncoder.frames(payload: ByteArray, sequence: UShort)`.
   - `BleFrameReassembler` with the reassembly rules from §0.1 and a `BleFrameError` sealed
     class.
   - `ShotEventDecoder`.
   - `SseEventParser` and `SseByteStreamParser`. The byte parser must preserve empty lines
     and strip a trailing CR.
   - `ControlCodec`, which encodes `set_club`, `get_club` and
     `iwr6843_orientation_calibration` envelopes and decodes both responses and the
     `club_changed` event from the control channel.
   - A shared `OpenFlightJson` instance with `encodeDefaults = true` and
     `ignoreUnknownKeys = true`. Add tests that assert the encoded control envelopes **and**
     the `PhoneOrientationMeasurement` payload contain `"schema_version":1`, and that the
     keys match §0.1/§0.3 exactly.
   - The **`ShotTransport` interface**, which this step owns so that S4 and S5 can run in
     parallel. Its contract:
     ```kotlin
     interface ShotTransport {
         val state: StateFlow<ConnectionState>     // decode / club_changed errors -> ConnectionState.Error
         val shots: Flow<ShotEvent>                 // decoded + de-duplicated (see §0.3 decoder rules)
         val activeClub: StateFlow<GolfClub?>
         val supportsControls: StateFlow<Boolean>
         fun start(); fun retry(); fun disconnect()
         suspend fun setClub(club: GolfClub): ClubSelection
         suspend fun currentClub(): ClubSelection
         suspend fun submitCalibration(m: PhoneOrientationMeasurement): CalibrationResult
     }
     ```
     Each Wi-Fi transport instance is bound to one host. A host change creates a new
     instance (see S6).
3. Copy `shot_v1.json` into `core/protocol/src/commonTest/resources`, or embed it as a Kotlin
   string constant if KMP test resources are awkward on iOS. Decode it in a test.
4. **Cross-language golden test.** Using the reference repo's Python, generate the frames for
   `shot_v1.json` compacted with `sort_keys`, at `sequence=0x0102`. Write the hex strings into
   `commonTest` as golden data. Assert that the Kotlin encoder produces byte-identical frames
   and that the reassembler round-trips them. Commit the generation script to
   `tools/gen-frame-goldens.py`. The script must load `src/openflight/ble/protocol.py`
   **by file path** with `importlib.util.spec_from_file_location`, or run through `uv run` in
   the reference clone. Importing the `openflight.ble` package pulls in the publisher and
   hardware dependencies.
5. Port every assertion from the four Swift test files listed above. Add these edge cases:
   - a frame of 4 bytes and a frame of 21 bytes (both rejected)
   - frame version 2 (rejected)
   - `count` = 0, and `index >= count`
   - a stale partial message replaced by a new sequence
   - duplicate fragments
   - the u16 sequence wrapping
   - CRLF SSE input
   - a multi-line `data:` field
   - unknown SSE fields
   - a comment-only heartbeat
   - `schema_version` 2 (rejected)
   - a replayed `event_id` (suppressed)
   - `null` optional fields
   - unknown extra JSON keys (ignored)

**Verification:** `./gradlew :core:model:allTests :core:protocol:allTests` plus the §4
invariants.

**Exit criteria:**
- All ported and new tests pass on the JVM/Android and iosSimulatorArm64 targets.
- The golden-frame test passes.
- Test names follow the global rule (no `test` prefix).

**Rollback:** revert the branch. Nothing else depends on this step yet.

---

### Step 3: `core:designsystem`

- **Depends on:** S1. **Model:** default. **Parallel with:** S2.

**Context brief:**
- Create the design-system module that every feature must use (invariant 5).
- Take the colors from the reference `docs/color_palette.html`. Its background is `#0a0a0f`;
  extract the full palette from the file.
- Take the typography and hierarchy from the reference `ContentView.swift`: `PrimaryMetric`,
  `DetailMetric` and `PreviousShotRow`. The app is dark-first.

**Tasks:**
1. Add `OfTheme` with a color scheme (dark primary, light secondary), typography, and spacing
   tokens.
2. Add wrappers: `OfButton`, `OfOutlinedButton`, `OfTextField`, `OfCard`,
   `OfSegmentedPicker` (for the transport picker), `OfDropdownMenu` (for clubs),
   `OfStatusChip` (for `ConnectionState`), `OfMetricPrimary`, `OfMetricDetail`, `OfScaffold`
   and `OfTopBar`.
3. Add a `@Preview` for each component. Use a common preview if the Compose Multiplatform
   version supports it; otherwise put previews in androidMain.
4. Add the invariant-5 lint: a detekt `ForbiddenImport` config that forbids the
   `androidx.compose.material3` component imports outside `core/designsystem`.

**Verification:** `./gradlew :core:designsystem:allTests detekt`. Temporarily render the
components in `App()`, or use a previews screen, on both platforms.

**Exit criteria:**
- The components render on Android and iOS.
- detekt fails when a test file outside designsystem imports `material3.Button`. Check this,
  then remove the test file.

**Rollback:** revert.

---

### Step 4: `core:network`: Wi-Fi SSE transport + HTTP control clients

- **Depends on:** S2. **Model:** default. **Parallel with:** S5, S8a, S8b.

**Context brief:**
- Implement the Wi-Fi transport with the Ktor client, following §0.2.
- Reference files: `ios/OpenFlight/{WiFiShotClient,RadarCalibrationClient,PhoneControl}.swift`
  and `ios/OpenFlightTests/WiFiShotClientTests.swift`.
- Implement the `ShotTransport` interface that S2 created in `core:protocol`. Don't redefine
  it here.

**Tasks:**
1. `EndpointUrl.build(host, path)` must match the normalization rules in §0.2 exactly. Port
   every `endpointURL` test case.
2. `WifiShotTransport(host: String, client: HttpClient, dispatcher)`:
   - Stream the body as bytes into `SseByteStreamParser`, with `Accept: text/event-stream`.
   - Route `event: shot` (or events with no name) to the decoder and `event: club_changed` to
     `activeClub`.
   - Treat 503 as the "Too many devices" error.
   - Reconnect with backoff 1 s → ×2 → 15 s cap. Reset the backoff only after a clean stream
     end, per §0.3.
   - Timeouts:
     - The stream uses `socketTimeoutMillis = 45_000` and an **infinite**
       `requestTimeoutMillis`. Otherwise the stream is killed every 45 s.
     - The club and calibration calls use a **10 s** request timeout.
     - Verify how the Darwin engine maps these timeouts, and whether Darwin buffers streamed
       bodies. A 3-shot manual test must show each shot arriving as it happens, not batched.
   - Auto-reconnect keeps the decoder's `lastEventId`. Only `disconnect()` and `retry()`
     reset it.
   - `supportsControls` is always true.
3. Decide between the Ktor SSE plugin and raw bytes. Write the Wi-Fi tests against
   `MockEngine` or a streamed `ByteReadChannel` first. Use the Ktor `sse {}` plugin only if it
   passes the CRLF, heartbeat and multi-line tests. Otherwise use the raw-bytes parser from
   S2. Record the decision in the PR description.
4. `setClub`/`currentClub` call `POST`/`GET /api/club`. `submitCalibration` calls `POST
   /api/calibration/iwr6843/orientation`. A non-200 response throws `OpenFlightHttpError`
   with the server's `error` message when one is present.
5. Tests use `runTest` with a `StandardTestDispatcher` and virtual time for backoff, Turbine
   for `state` and `shots`, and MockEngine. Cover:
   - replay suppression across an **automatic** reconnect: the replayed shot is not
     re-emitted
   - the replay being emitted again after an explicit `retry()`, which matches the reference
   - an invalid SSE payload setting `state` to `Error`
   - a 503 response
   - an invalid host
   - cancellation stopping the loop

**Verification:** `./gradlew :core:network:allTests`, then the manual E2E below.
```bash
# in the reference clone:
uv sync && uv run openflight-server --mock
curl -N http://localhost:8080/api/shots/stream   # sanity check the server
python tools/fire-mock-shot.py --host localhost:8080 --count 3
```
Run a throwaway JVM `main`, or a test tagged `@Ignore`d-by-default, to connect
`WifiShotTransport` to `localhost:8080` and print shots.

**Exit criteria:**
- Unit tests pass on all targets.
- The manual run shows 3 mock shots decoded, and a replayed shot is not duplicated after
  killing and restarting the server.

**Rollback:** revert. There are no downstream consumers yet.

---

### Step 5: `core:ble`: BLE transport over Kable

- **Depends on:** S2. **Model:** strongest. **Parallel with:** S4, S8a, S8b.

**Context brief:**
- Implement `BleShotTransport : ShotTransport` so it behaves like
  `ios/OpenFlight/BluetoothManager.swift`. Read that file in full. The rules are in §0.1 and
  §0.3.
- Wrap Kable behind an internal `BleCentral`/`BlePeripheralLink` interface so the state
  machine is unit-testable with a fake. Kable's own types must not leak into the public API.
- Handle the Android runtime permissions `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` (API 31+),
  and location on ≤ 30.
  - Expose the permission state as an `expect`/`actual`
    `BlePermissionController`, or as a Compose-side permission request in `composeApp`
    (e.g. moko-permissions, or a small hand-rolled androidMain helper).
  - **The transport itself must not request permissions.** It reports
    `ConnectionState.Unavailable("Bluetooth permission is required")`.

**Tasks:**
1. Build the state machine:
   - idle → scanning. Scan with a filter on the service UUID.
   - Take the first advertisement: stop the scan, then go to connecting.
   - Connect, then go to discovering.
   - Observe the shot characteristic; go to connected once the notification subscription is
     active.
   - Observe the control characteristic if it's present; set `supportsControls = true` if the
     subscription succeeds.
2. Map adapter states into the reference's messages: off, unauthorized, unsupported and
   resetting, with **unknown → `Idle`**.
   - Verify that the chosen Kable version still exposes `Bluetooth.availability`, or find its
     replacement.
   - On Android, "unauthorized" must come from a runtime-permission check, not from Kable.
   - Check `peripheral.services` for the control characteristic **before** calling `observe`
     on it. Observing an absent characteristic may throw; verify this.
   - Use Kable's `onSubscription` hook to signal "subscribed", which drives `Connected` and
     `supportsControls`.
   - Decode errors on the shot channel set `state = Error(msg)`, the same as the reference.
3. On disconnect:
   - Fail the pending control request with `disconnected`.
   - Reset both reassemblers, but **not** the shot decoder (§0.3).
   - Set `activeClub = null` and `supportsControls = false`.
   - Go to scanning and reconnect after 1 s.
4. Control channel:
   - Use a `Mutex`, or a single pending `CompletableDeferred`, so a second request fails
     fast with `busy`.
   - Wrap each request in `withTimeout(10.seconds)`.
   - Write frames sequentially with `WriteType.WithResponse`.
   - Match responses by `request_id`, and ignore unmatched ids.
   - Route `club_changed` events on the control channel to `activeClub` without completing
     the pending request.
   - A u16 control sequence counter wraps.
5. **Port `ios/OpenFlightTests/BluetoothManagerTests.swift` and `BLETestFixtures.swift`
   first.** Then add tests that use a `FakeBleCentral`, which replays scripted notification
   byte arrays. Cover:
   - happy path shots, including the S2 golden frames split across notifications
   - interleaved `club_changed` and a response
   - timeout
   - busy
   - disconnect mid-request
   - an unsupported peripheral (no control characteristic)
   - the reconnect cadence under virtual time
6. Platform notes to verify with Context7 or the Kable docs, and record in code comments:
   - Kable's iOS `CBCentralManager` restoration or background requirements. v1 is
     foreground only, like the reference.
   - Android MTU. Frames are ≤ 20 bytes, so the default MTU of 23 is enough; don't request a
     larger one.

**Verification:** `./gradlew :core:ble:allTests`. Manual hardware check, deferred to S10 if no
Pi is available: `scripts/start-kiosk.sh --ble --mock` on a Pi running a kernel other than
`6.18.34+rpt-rpi-2712`.

**Exit criteria:**
- The fake-driven tests cover every branch listed above and pass on all targets.
- No Kable type appears in `core:ble`'s public API. Check with `apiDump` from the Kotlin
  binary-compatibility-validator if it's configured, or by inspection.

**Rollback:** revert. S4 is independent.

---

### Step 6: `core:data`: repositories (single source of truth)

- **Depends on:** S4, S5. **Model:** strongest.

**Context brief:**
- The UI must not care which transport is active.
- The iOS reference keeps **separate** `ShotHistory` instances per transport. Unify them into
  one repository that follows the global rule "repository = single source of truth, exposes
  Flow".

**Tasks:**
1. `SettingsRepository` is backed by DataStore preferences and persists `transport`
   (`BLUETOOTH`|`WIFI`), `host` (default `raspberrypi.local:8080`) and `selectedClub`
   (default `driver`). It exposes each as a `Flow`. The DataStore file path is supplied
   through `expect`/`actual` (`Context.filesDir` or `NSDocumentDirectory`).
2. `ShotRepository`:
   - Observes settings and switches the active transport with `flatMapLatest` keyed on
     **(transport, host)**, so a host change builds a new `WifiShotTransport`. On a switch,
     disconnect the old transport, then start the new one.
   - The host flow sent to the repository changes only on submit, not on each keystroke.
   - Exposes `connectionState: StateFlow<ConnectionState>`,
     `history: StateFlow<List<ShotEvent>>` (newest first, capped at 100, deduplicated by
     `eventId` across transports), `latestShot`, `activeClub` and `supportsControls`.
   - Also exposes `retry()`, `setClub()`, `currentClub()` and `submitCalibration()`, each
     delegated to the active transport.
   - Decides whether history survives a transport switch. **Keep it**, and dedup protects
     against duplicates. This is a deliberate improvement over the reference; call it out in
     the PR.
3. Selected-club sync:
   - After a successful `setClub`, persist the club to settings. The reference only updates
     its saved selection after the server confirms the change.
   - When `activeClub` arrives from the server, update the persisted selection too.
   - **Sync on connect** (§0.3). When the state becomes `Connected`, and for BLE also once
     `supportsControls` is true, call `currentClub()` once per connection. The server's club
     overwrites the persisted one. A failure is logged but doesn't change the connection
     state.
   - Test it: connect, and assert one `currentClub` call and the settings update. A second
     `Connected` emission within the same connection makes no second call.
4. `ShotHistory` (pure) lives here or in `core:model`. Port `ShotHistoryTests`.
5. Koin module `dataModule` provides the transports, the repositories and a single
   `HttpClient` per platform engine.
6. Tests use fake transports and Turbine. Cover:
   - switching the transport cancels the old collection
   - no duplicate shots across a switch
   - errors propagate to `connectionState`
   - club persistence happens only on a confirmed response

**Verification:** `./gradlew :core:data:allTests`.

**Exit criteria:**
- Tests pass.
- `core:data`'s public API exposes only `core:model` types and Flows.

**Rollback:** revert. S4 and S5 remain intact.

---

### Step 7: `feature:dashboard` + app shell wiring

- **Depends on:** S3, S6. **Model:** default.

**Context brief:**
- Recreate the reference `ContentView.swift` dashboard in Compose Multiplatform. It has:
  - a transport picker (Bluetooth / Wi-Fi)
  - a host field shown when Wi-Fi is selected
  - a connection status with Retry
  - the latest shot's primary metrics (ball speed, carry) and detail metrics (club speed,
    smash, launch V/H, spin, spin axis, club path), with a clear "—" for nulls
  - a "Club for next shot" menu, enabled only when `state == Connected && !isChanging`
    (§0.3), with inline errors
  - a previous-shots list
  - entry points to Calibrate TI Radar and Driving Range. Keep them as placeholders until S8c
    and S9 land.
- Read `ContentView.swift` in full for copy and layout.

**Tasks:**
1. `DashboardViewModel` exposes `StateFlow<DashboardUiState>`, a sealed type:
   - `Waiting(connection)`
   - `Live(connection, latest, history, club, controlsEnabled, clubError?)`
   It accepts events through `onEvent(DashboardEvent)`: `TransportChanged`, `HostEdited`
   (local text only), `HostSubmitted` (persist and reconnect), `Retry`, `ClubSelected` and
   `DismissError`.
2. Build `DashboardScreen` stateless, taking `(uiState, onEvent)`. The screen composable
   takes the state; the route composable owns the VM.
3. In `composeApp`, add `AppNavHost` with typed routes `Dashboard`, `Calibration` and
   `Range`, plus the Koin setup (`startKoin` in the Android `Application` and in iOS
   `MainViewController`).
4. Handle lifecycle: start the transport on foreground and disconnect on background, the
   same as the reference, which is foreground-only. Use the lifecycle-aware collector.
5. Wire the Android BLE permission prompt at the route level when the transport is Bluetooth.
6. Tests:
   - VM tests with a fake repository and Turbine.
   - Compose UI tests. On Android use `createAndroidComposeRule<ComponentActivity>()`; common
     tests may use `runComposeUiTest`. Name them `given_when_then`. Cover the null-metric
     rendering, the club menu being disabled unless connected, and the Retry visibility rule
     from `canRetry`.
   - Android Compose UI tests need device tests (enabled in S1) and an emulator. Add a CI
     emulator job, for example `reactivecircus/android-emulator-runner`, or record that these
     tests run manually. Common `runComposeUiTest` runs on the iOS simulator and the
     JVM/desktop target, if one is configured.
7. Add debug-only launch hooks equivalent to the reference's `--ui-testing` and
   `--preview-shot`:
   - Android: intent extras.
   - iOS: `NSProcessInfo.arguments`.
   The hooks swap in a fake repository with a preview shot, so UI tests don't need a Pi.

**Verification:** run all tests, then run the manual flow. Start the mock server and the
Android emulator (host `10.0.2.2:8080`). On the iOS simulator use host `localhost:8080`. Run
`tools/fire-mock-shot.py` and confirm:
- the shot renders
- history grows
- a club change round-trips (`POST /api/club`), and the server log shows the new club

**Exit criteria:**
- The manual flow works on both platforms.
- There are screenshots from both platforms (not committed).
- Invariant 5 passes.

**Rollback:** revert. The app shell falls back to the S1 placeholder.

---

### Step 8a: `core:flight`: ball flight math (pure)

- **Depends on:** S2. **Model:** default. **Parallel with:** S4, S5, S8b.

**Context brief:**
- Port `ios/OpenFlight/DrivingRange/{BallFlightModels,BallFlightSimulator,FlightInputResolver,RangeCameraPlanner,RangeSceneDescription}.swift`
  and their tests.
- Replace `SIMD3<Double>` with a small `Vec3` value class that has operators.
- The constants are listed in §0.3. Keep them identical.

**Tasks:**
1. Port `Vec3`, `FlightInput`, `FlightPoint`, `FlightTrajectory` (including `point(at:)`
   interpolation and `playbackDuration`), `FlightInputProvenance`, the RK4
   `BallFlightSimulator` including the target-carry constraint, `FlightInputResolver` with
   the per-club defaults table (copy the values verbatim), `RangeCameraPlanner` and
   `RangeSceneDescription`.
2. Port every numeric assertion from `BallFlightSimulatorTests`,
   `FlightInputResolverTests`, `RangeCameraPlannerTests` and `RangeSceneDescriptionTests` with
   the same tolerances.
3. Add a test that runs the vacuum configuration and compares it against the closed-form
   projectile range, as an independent oracle.

**Verification:** `./gradlew :core:flight:allTests`.

**Exit criteria:** every ported assertion passes on both targets, with no tolerances
loosened.

**Rollback:** revert.

---

### Step 8b: `core:sensors` + orientation calculator

- **Depends on:** S2. **Model:** strongest. **Parallel with:** S4, S5, S8a.

**Context brief:**
- Port `PhoneOrientation.swift` (pure math) and build an `expect/actual` gravity source.
- **The payload must use iOS CoreMotion gravity conventions** (§0.3), because the Pi
  recomputes the angles from `gravity_{x,y,z}_g`.
- Working assumption to verify on a real device:
  - Android `TYPE_GRAVITY` shares CoreMotion's device axes (x right, y toward the top edge, z
    out of the screen).
  - Android reports m/s² with the **opposite sign**: face-up gives z ≈ +9.81, where
    CoreMotion gives z ≈ −1.
  - The conversion is therefore `g_ios = −g_android / 9.80665`.

**Tasks:**
1. Port `PhoneOrientationCalculator` (`measurement`, `displayAngles`, and the private
   `sensorAngles` and `standardDeviation`) into commonMain with the exact thresholds. Port
   `PhoneOrientationTests.swift`; it has 265 lines of cases, so port all of them.
2. `expect class GravitySensor { val isAvailable: Boolean; fun samples(hz: Int = 60):
   Flow<GravitySample> }`:
   - **Android:** `SensorManager` with `TYPE_GRAVITY`. Fall back to low-pass-filtered
     `TYPE_ACCELEROMETER` when gravity is missing. Apply the conversion above inside the
     actual, sample at a 16 667 µs period, and unregister in `awaitClose`.
   - **iOS:** `CMMotionManager` with `deviceMotionUpdateInterval = 1/60`,
     `startDeviceMotionUpdatesUsingReferenceFrame(CMAttitudeReferenceFrameXArbitraryZVertical, toQueue = NSOperationQueue.mainQueue)`,
     emitting `motion.gravity` as is.
3. `OrientationSampler` (commonMain) keeps the window of the 120 most recent **raw** samples,
   filtering inside the window as described in §0.3, and emits a
   `(measurement?, displayAngles?, sampleCount, progress)` state. This is the reference
   `PhoneOrientationMonitor` minus the platform code.
4. `deviceModel` is `expect fun deviceModel(): String`: `Build.MODEL` on Android and
   `UIDevice.currentDevice.model` on iOS.
5. **Axis-convention test.** Put the conversion in a pure function
   `androidGravityToCoreMotion(x, y, z)` and unit-test it:
   - phone upright in portrait: Android `(0, +9.81, 0)` → `(0, −1, 0)`, giving tilt 0 and
     roll 0
   - phone face-up: Android `(0, 0, +9.81)` → `(0, 0, −1)`, giving tilt +90
   The step isn't done until the device check below confirms this on real hardware.

**Verification:**
- `./gradlew :core:sensors:allTests`.
- **Device check (required):** on a physical Android phone and a physical iPhone, log the
  converted gravity while the phone is held upright in portrait and while it lies face-up.
  Both platforms must produce the same sign and magnitude, to within about 0.02 g.

**Exit criteria:**
- The ported tests pass.
- The device check is recorded in the PR with the logged values. If there's no physical
  device, mark the step **BLOCKED-ON-HARDWARE**, keep merging behind it, and carry the device
  check into S10's checklist.

**Rollback:** revert.

---

### Step 8c: `feature:calibration`

- **Depends on:** S3, S6, S7 (for the nav host), S8b. **Model:** default. **Parallel with:** S9.

**Context brief:** recreate `ios/OpenFlight/RadarCalibrationView.swift`. Read it in full, and
reuse its instructional copy from §0.3 and `docs/ios-ble.md` ("Calibrate TI radar tilt").

**Tasks:**
1. `CalibrationUiState` is a **product type**, because sampling continues during and after
   submit:
   - `sensor`: a sealed type, `Unavailable("Motion unavailable")` or
     `Sampling(displayAngles?, progress, measurement?)`
   - `submit`: a sealed type, `Idle`, `Submitting`, `Applied(CalibrationResult)` or
     `Failed(message)`
   - `transport`, and `host` (editable in Wi-Fi mode)
   - `bluetoothReady`
2. The Apply button is enabled only when all of these hold:
   - `measurement.isReadyToSend`
   - `(transport == WIFI || supportsControls)`
   - `submit !is Submitting`

   Tapping it calls `ShotRepository.submitCalibration`, so it works over BLE or Wi-Fi.
3. Port every piece of UI from `RadarCalibrationView.swift`: the Bluetooth readiness card,
   the editable Wi-Fi host field, the readiness and stability copy, "Saved TI tilt", the
   enclosure-pitch summary, and "Motion unavailable". The iOS simulator has no device motion,
   so it shows "Motion unavailable".
3. Angle cards read "Live sensor reading" while the phone moves and "Stable 2-second average"
   once stable. They turn green when stable.
4. Keep the screen on during sampling: `FLAG_KEEP_SCREEN_ON` on Android and
   `idleTimerDisabled` on iOS, through `expect`/`actual`.
5. Add the route to `AppNavHost` and the dashboard's entry point.
6. Tests: VM tests driven by a fake `GravitySensor` flow (stable → Ready, noisy → stays in
   Sampling, roll > 3° → not ready, submit errors → Failed), plus UI tests for button enablement.

**Verification:**
- Run the tests. The happy path, which applies a result and renders the tilt, `persistent` and
  the enclosure pitch, is covered with a fake repository and a Ktor MockEngine.
- Manual run against `openflight-server --mock` from the Android emulator. The emulator's
  virtual sensors can hold it steady. The server returns **409 "TI IWR6843 radar is not
  enabled"** (§0.3a). The app must display that message in the `Failed` state.
- The real successful round trip needs TI hardware and is **human-gated in S10**.

**Exit criteria:** the tests pass, and the emulator shows the server's 409 message verbatim.

**Rollback:** revert. Remove the route and dashboard entry.

---

### Step 9: `feature:range`: driving range view

- **Depends on:** S3, S6, S7, S8a. **Model:** default. **Parallel with:** S8c.

**Context brief:**
- The reference renders a RealityKit 3D range (`RangeSceneController.swift` and
  `DrivingRangeView.swift`) with a tracer and a metrics overlay (`RangeMetricsOverlay.swift`).
- v1 uses a Compose `Canvas` with a perspective projection driven by the camera plan from
  S8a. Read the reference view model and tests: `DrivingRangeViewModel.swift` and its tests,
  and `DrivingRangeUITests.swift`.

**Tasks:**
1. `DrivingRangeViewModel` ports the reference phase machine exactly (§0.3, and read
   `DrivingRangeViewModel.swift`):
   - The phases are `Waiting → Preparing → Flying → Landed` and `Unavailable(msg)`.
   - Resolve and simulate each shot with `core:flight` on `Dispatchers.Default`.
   - **Newest pending shot wins**: only the newest queued shot plays after the current flight.
   - Landing dwells for 1.25 s.
   - Under reduced motion, playback lasts 0.9 s. Read the platform setting through
     `expect`/`actual`: `ANIMATOR_DURATION_SCALE` / accessibility on Android, and
     `UIAccessibilityIsReduceMotionEnabled` on iOS.
   - `suspend()` runs when the app goes to the background or the screen disappears.
   - Replay the last shot.
   - Show an "estimated" badge when `provenance.usesEstimatedFlight`.
   - Draw **one** tracer at a time, the same as the reference.
   - Port `DrivingRangeViewModelTests` and use virtual time for the dwell and playback
     timing.
2. `RangeCanvas`:
   - Draw a ground plane with yardage markers every 50 yd and a target line.
   - Project `Vec3` points with a pinhole camera from the planner.
   - Animate the ball along `trajectory.point(at: t)` over `playbackDuration` with
     `withFrameNanos`.
   - Draw a high-contrast tracer; the reference made its tracer "easier to see" in commit
     `5b379d3`.
3. Port `RangeMetricsOverlay` using designsystem metrics. It includes replay and exit
   buttons, the club selector and club error inside the overlay, the "Driving Range Ready"
   card, and a landscape layout.
4. Add the route and the dashboard entry. Add the `--range-mode` and `--preview-flight`
   debug launch hooks, which extend the S7 hooks.
5. Port `DrivingRangeUITests.swift` (dashboard → range → exit) as a Compose UI test that uses
   the launch hooks.
5. Tests:
   - VM tests with Turbine.
   - A pure projection test: a known point maps to the expected screen coordinates.
   - A UI test that the overlay shows carry and the estimated badge.

**Verification:** run the tests. With the mock server, fire 3 shots in quick succession and
confirm that only the first shot and then the newest shot play. **Human-gated:** profile with
Android Studio or Instruments and confirm there are no sustained dropped frames on a mid-range
device. An agent records "pending human" and continues.

**Exit criteria:** tests pass, the animation is visually correct, and the carry shown matches
the server's `estimated_carry_yards` within rounding.

**Rollback:** revert. Remove the route.

---

### Step 10: End-to-end hardening, hardware matrix, release prep

- **Depends on:** all previous steps. **Model:** default.

**Context brief:** by now the app has all its features. This step proves it against real
hardware and fixes whatever breaks. **This step is human-gated.** An agent prepares the
matrix, scripts and builds; a person with a Pi, an Android phone and an iPhone runs the
hardware rows. That includes the S8b gravity check and the real calibration round trip with
`--iwr6843`.

**Tasks:**
1. Run the hardware matrix. Record the results in `docs/hardware-test-matrix.md`.

   | | Android (physical) | iOS (physical) |
   |---|---|---|
   | Wi-Fi: shots, replay, club, calibration, Pi restart, 9th client 503 | | |
   | BLE: scan/connect, shots, club from phone, club from browser UI (`club_changed`), calibration, Pi out-of-range → reconnect | | |
   | Permissions denied → correct `Unavailable` message and recovery via Settings | | |
   | Bluetooth off / airplane mode | | |
   | Background → foreground reconnect | | |
   | S8b gravity device check (if it was deferred) | | |

2. Fix the defects found. For each one, first write a failing regression test.
3. Accessibility:
   - content descriptions on metrics
   - dynamic type / font scale up to 200% without clipping
   - TalkBack and VoiceOver pass on the dashboard
4. App icons and splash screens for both platforms, using the openflight logo only if the
   upstream license and trademark allow it. **Ask the user** before using the logo.
5. Update the README with screenshots, the protocol pointer, and known limitations: no auth
   (per the upstream security note), foreground-only BLE, and the Pi kernel
   `6.18.34+rpt-rpi-2712` BLE regression.
6. Tag `v0.1.0`. Publishing to the stores is **out of scope** unless the user asks.

**Verification:** every row in the matrix is marked pass or has a linked issue, and all
invariants hold.

**Exit criteria:** the matrix is complete, with no open P0 or P1 defects.

**Rollback:** individual fix commits are reverted one by one. Never force-push `main`.

---

## 6. Plan mutation protocol

- **Split** a step whose PR goes over about 800 changed lines, not counting ported tests.
  Name the parts `Nx`/`Ny`, and update the §3 graph.
- **Insert** a new step only with an explicit edge in §3 and its own context brief.
- **Skip** a step only when the user says so. Record it in the Review Log with the reason.
- **Reorder** a step only when its dependencies still hold.
- **Abandon** a step by recording why and what state it left behind.
- If an architecture decision in §1 proves wrong (for example, Kable can't do write-with-
  response sequencing on iOS), **stop, write a short ADR in `docs/adr/`, and ask the user**
  before switching libraries.

## 7. Out of scope (v1)

- FlightWeb cloud sync and accounts.
- Background BLE.
- Authenticated pairing.
- A 3D engine for the range.
- GSPro and simulator integrations.
- Store publishing.
- A watchOS or Wear OS app.

---

## 8. Re-scope R: native UI per platform (2026-09-24, see `docs/adr/0001-native-ui-shared-viewmodels.md`)

**User decisions:**
- Android uses **Jetpack Compose**, iOS uses **SwiftUI**, and ViewModels stay **shared in
  KMP**.
- The OpenFlight logo may be used for the app icon.
- There's **no Raspberry Pi**, so all verification runs on simulators and emulators against
  `openflight-server --mock --web-port 8091`. The hardware matrix stays open.

The R steps supersede the UI parts of S3, S7, S8c, S9 and S10. The `core:*` modules and the
ViewModels are kept as they are.

```
R1 Android: CMP → Jetpack Compose, VMs isolated in commonMain ──> R2 iOS interop + SwiftUI shell + Dashboard
                                                                   ├─> R3a SwiftUI Calibration ─┐
                                                                   └─> R3b SwiftUI Range (RealityKit) ┴─> R5b parity UI ─> R4 icons, docs, v0.1.0-sim
(R5a shared parity logic runs in parallel with R1)
```

### R1: Android on Jetpack Compose; shared presentation isolated (model: strongest)

1. Remove the Compose Multiplatform plugin and dependencies everywhere.
2. **Feature modules** (`feature:dashboard|calibration|range`):
   - `commonMain` keeps only the ViewModel, `UiState`, events, formatters and projection
     math, with no Compose imports. The KMP `androidx.lifecycle:lifecycle-viewmodel` is fine.
   - The Compose screens move to `androidMain` as Jetpack Compose.
   - Device UI tests stay in `androidDeviceTest`.
   - The Canvas renderer for the range moves to androidMain. Its pure projection math stays
     common.
3. `core:designsystem` becomes an Android-only Jetpack Compose library, keeping the same
   `Of*` API. ForbiddenImport still bans `androidx.compose.material3.*` outside it.
4. `composeApp` becomes **`shared`**:
   - an iOS umbrella framework `Shared` that exports `core:model`, `core:data` and the feature
     VM packages
   - common Koin bootstrap and launch options
   - The Android nav host, permission request and launch-option handling move into
     `androidApp` (Jetpack `navigation-compose`).
   - Remove `MainViewController`, `DesignSystemGallery` and the CMP `App()`.
5. The iOS app is temporarily a minimal SwiftUI placeholder that links `Shared` and calls a
   trivial exported function, so the iOS build stays green.

**Exit criteria:**
- The verification from invariants 1–3 passes, with the framework task renamed.
- Every existing Android device UI test passes on `Pixel_10_Pro_XL`.
- A live mock run on the Android emulator shows shots, the range flight and the calibration
  409, the same as before.
- `grep -r "org.jetbrains.compose" --include=*.kts` is empty.

### R2: iOS interop foundation + SwiftUI app shell + Dashboard (model: strongest)

1. Choose the interop layer:
   - Check whether **SKIE** supports Kotlin 2.4.20. If not, use **KMP-NativeCoroutines**. If
     neither supports it, use hand-written `FlowCollector` wrappers exported from `shared`.
   - Record the choice in the ADR.
2. Swift ViewModel bridge:
   - A generic `@Observable`/`ObservableObject` wrapper owns a Kotlin VM, collects its
     `uiState` on the main actor, forwards events, and calls `onCleared` / clears a
     `ViewModelStore` when released.
   - Koin starts once in the `App` init.
3. SwiftUI `DashboardView` renders the shared `DashboardUiState` and ports the layout and copy
   of the reference `ContentView.swift`. It covers the transport picker, host field with
   submit, status and Retry, metrics, club menu, history, and entry points to Calibration and
   Range as `NavigationStack` destinations.
4. Launch arguments `--ui-testing`, `--preview-shot`, `--range-mode` and `--preview-flight`
   are handled through the shared `LaunchOptions`.
5. Add an **XCUITest** target with a dashboard smoke test run on the simulator: preview shot
   visible, club menu present.

**Exit criteria:**
- `xcodebuild test` passes on the simulator.
- A live mock run on the iOS simulator (`localhost:8091`) shows streamed shots.
- A club change **driven by XCUITest taps** reaches the server; confirm with `GET /api/club`.

### R3a: SwiftUI Calibration (parallel with R3b)

- Port `RadarCalibrationView.swift`, rendering the shared `CalibrationUiState`.
- Add XCUITests for "Motion unavailable" on the simulator.
- Live run: on the simulator no motion data arrives, so the screen shows "Motion unavailable".

### R3b: SwiftUI Driving Range with RealityKit (parallel with R3a)

- Port `DrivingRangeView`, `RangeSceneController`, `RangeSceneView` and
  `RangeMetricsOverlay` from the reference, rendering the shared `DrivingRangeUiState`. The
  trajectory points come from `core:flight` through the VM.
- Port `DrivingRangeUITests` (dashboard → range → exit) as an XCUITest.
- Live run: newest-wins, and carry matches the server.

### R5: "Best of all sources": web-UI parity features that need no server changes

These come from a survey of the Pi's React web UI (`ui/src/**`) on 2026-09-24. The SSE and
BLE schema is narrow (§0.1), so only features computable from it, or reachable over plain
HTTP, are in scope.

**R5a: shared logic (KMP; can run in parallel with R1)**
- Create a new module `core:insights`, depending only on `core:model`. It contains:
  - A **units** preference (IMPERIAL: mph and yds; METRIC: km/h and m) with conversions and
    formatting. Degrees render tight ("9.5°").
  - **Per-club stats**, matching the web `StatsView.tsx`: count, average and maximum ball
    speed, average carry, average club speed, average smash, and shot counts per club.
  - **CSV export**, with columns matching the web `ShotList.tsx` export, without the
    comparator.
- `core:data` additions:
  - A persisted `units` setting.
  - `ShotRepository.deleteShot(eventId)` and `clearHistory()`, which are local only; the web UI
    uses Socket.IO for these.
  - A `PiControlClient.shutdown()` calling `POST /api/shutdown`, available on Wi-Fi only.
- Dashboard VM: `UiState` gains the units, the per-club stats, the club chips with counts, and a
  `newShot` one-shot event (for haptics and the flash).
- Add a new `feature:session` VM: stats tabs by club, plus the delete, clear and export
  actions.

**R5b: native UI for R5a (after R2, on both platforms)**
- A units toggle in settings.
- A Session/Stats screen with club tabs.
- CSV export through the Android share sheet and the iOS `ShareLink`.
- Swipe to delete, and clear with a confirmation.
- A haptic and a gold shot-flash on each new shot.
- Pi shutdown behind a confirmation dialog.
- Fonts: DM Serif Display for headings and Outfit for body text (OFL), bundled on both
  platforms.

**~~Out of scope~~ → now covered by R6 (Socket.IO, Wi-Fi only), except none:**
- confidence badges (`launch_angle_confidence`, `spin_quality`, `spin_source`,
  `carry_range`)
- a session backfill on connect (`GET /api/session`)
- server-side delete and simulate
- swing-speed training mode
- camera
- player profiles
- simulator (GSPro) status
- the radar debug panel
- FlightWeb cloud sync

### R6: "Add all of them": full Wi-Fi mode over the Pi's Socket.IO API (no server changes)

User decision (2026-09-24): add **every** web-UI feature. The Pi already exposes all of them to
the web UI over **Socket.IO** (Flask-SocketIO, python-socketio 5, Engine.IO v4), so the phone
speaks that same protocol on Wi-Fi.
- BLE keeps the core features (§0.1).
- Features that need Socket.IO are Wi-Fi only, and the UI hides or disables them on BLE with
  an explanation.
- This supersedes the "out of scope" list in R5.

**R6a: `core:socketio` + `PiSessionRepository` (KMP; model: strongest)**
- A minimal **Engine.IO v4 / Socket.IO v5 client** on Ktor:
  - WebSocket transport, with long-polling fallback if the server's threading mode doesn't
    accept WebSocket. Verify this live.
  - The open handshake, ping/pong, `40` connect, `42` events, reconnect with backoff, and a
    typed event flow.
  - Tests use a fake transport, plus a live check against `openflight-server --mock`.
- A `ShotDetail` model covering the full `shot_to_dict` payload (server.py ~893): confidence
  fields (`launch_angle_confidence`, `spin_quality`, `spin_source`, `carry_range`,
  `carry_spin_adjusted`), `player_name`, the sim fields, and so on. It stays tolerant of
  unknown or missing keys.
- `PiSessionRepository`, Wi-Fi only:
  - The session backfill (`get_session` → `session_state`) and server `stats`.
  - Server-side `delete_shot` and `clear_session`.
  - `simulate_shot`.
  - `set_player` / `player_changed`.
  - `set_training_implement`, `swing_speed` and the trigger-status mode.
  - Camera: `toggle_camera`, `toggle_camera_stream`, `camera_status` and `ball_detection`.
  - `sim_status`, `sim_shot`, `sim_send_failed` and `sim_shot_dropped`.
  - Debug and radar: `get/set_radar_config`, `toggle_debug`, `trigger_status`,
    `trigger_diagnostic`, `debug_reading` and `debug_shot`.
  - Cloud: `upload_cloud` → `cloud_upload_status`.
  - `shutdown` → `shutdown_ack`, or `POST /api/shutdown`.
  - All of these follow the exact payloads in `server.py` and `ui/src/services/socketService.ts`.
- An **MJPEG** multipart parser (common) for `GET /camera/stream`. Each platform decodes the
  JPEG frames itself.
- Integration with `ShotRepository`: on Wi-Fi, shots from Socket.IO **enrich** the SSE shots
  by `event_id`/timestamp. Delete and clear go to the server when Socket.IO is connected, and
  are local otherwise.

**R6b: shared VMs (KMP)**
- Screens: Session (server-backed stats and history), Training (swing speed), Camera, Players,
  Simulators, Radar/Debug, and Cloud.
- Dashboard additions: confidence indicators, the carry range, and the spin source.

**R6c: native UI for R6b** on both platforms, after R2/R3, together with R5b.

### R4: App icons, docs, release tag (model: default)

1. Build the app icons from the official OpenFlight logo, which the user approved.
   - Source it from the upstream repo or openflight.dev. The upstream repo has
     `cad/logo/MainLogo.DXF`; prefer an existing raster or SVG from the web UI or site.
   - Android: an adaptive icon. iOS: an `AppIcon` asset catalog.
2. Update the README and CLAUDE.md for the new architecture.
3. Mark the hardware matrix as pending until a Pi is available.
4. Tag `v0.1.0-sim`, which is simulator- and emulator-verified. `v0.1.0` waits for the
   hardware matrix.


## Review Log
- 2026-09-24: Draft created from reference commit `b053194`.
- 2026-09-24: Adversarial review (Opus) returned APPROVE-WITH-FIXES with 1 critical, 7 high,
  9 medium and 6 low findings. The critical finding and the high findings were spot-checked
  against the reference source and confirmed. All 23 findings were applied:
  - `encodeDefaults=true` plus schema_version tests
  - the decoder-reset semantics corrected
  - current-club sync on connect (S6)
  - `ShotTransport` moved into S2
  - split stream and control timeouts
  - BluetoothManagerTests ported
  - the full range phase machine and UI hooks (S9)
  - calibration verified against a 409, a product-type UI state, and Apply gating (S8c)
  - `(transport, host)` switch keying
  - raw-window semantics
  - Android host and device test enablement, detekt KMP source sets, and a wizard-based S1
  - Kable verification items
  - the `openflight-server` entrypoint and a path-loaded golden script
  - `core:datastore` removed from the graph
  - exact club display names
  - human-gated criteria marked
  **Status: FINAL (ready for execution).**

## Execution Log
- S1 ✅ merged (4bbfa73). Versions: Kotlin 2.4.20, AGP 9.3.3, CMP 1.12.1, Kable 0.45.0, Ktor 3.6.0. Local git only (user decision); CI never run. Port 8080 on dev Mac is occupied → mock server on 8091.
- S3 ✅ merged. ForbiddenImport bans all `androidx.compose.material3.*` outside core/designsystem; orchestrator added OfText/OfTextRole, OfIcon, OfDivider, OfLinearProgress, OfSpinner (0e2bd18).
- S2 ✅ merged (c29d2a4). ShotHistory is immutable (`record()` returns new). ControlCodec decodes ByteArray only.
- S8b ✅ merged. Android→CoreMotion `-v/9.80665` doc-verified; device check BLOCKED-ON-HARDWARE (→ S10, incl. roll sign). **Follow-up for S6:** port the Wi-Fi request halves of PhoneOrientationTests (`CalibrationURLRejectsUnsupportedHost`, `WiFiClubRequestUsesClubEndpoint`, `WiFiCurrentClubRequestReadsClubEndpoint`, URL/method/header half of calibration request test) against core:network.
- S8a ✅ merged. FlightTrajectory.id = eventId; RangeQualityProfile.current (memory-based) not ported — S9 picks BALANCED unless expect/actual added; Double not Float.
- S5 ✅ merged. Kable Bluetooth.availability throws in 0.45 → own adapter watchers. Android: BleShotTransport(context, scope); iOS: BleShotTransport(scope); Koin single. S6/S7 must request `requiredBluetoothPermissions` then retry(). Malformed club_changed → Error (deviation from Swift, per §0.3).
- S4 ✅ merged. Raw-bytes SSE (not Ktor sse plugin). `expect fun openFlightHttpClient()`, `WifiShotTransport(host, httpClient, dispatcher)`, `OpenFlightHttpError`. Darwin streaming researched (Ktor ≥3.4) but not run on device → verify in S7 on iOS sim. Live E2E: 3 shots + auto-reconnect OK on mock :8091.
- S6 ✅ merged. ShotRepository/SettingsRepository interfaces (impls internal); control calls serialized by Mutex; Koin: Android startKoin{androidContext(app); modules(dataModule)}, iOS startKoin{modules(dataModule)}. Android Koin graph not runtime-verified → S7 must launch app on emulator.
- S7 ✅ feature:dashboard (sealed `DashboardUiState` Waiting/Live + `ConnectionPanelState`, `DashboardViewModel(ShotRepository, SettingsRepository)`, stateless `DashboardScreen`, `DashboardRoute`, Koin `dashboardModule`); composeApp `App()` (LifecycleStartEffect start/stop), `AppNavHost` typed routes `Dashboard`/`Calibration`/`Range` (S8c/S9: replace your one `composable<…> { PlaceholderScreen(…) }` line in `composeApp/.../AppNavHost.kt`), `appModules`, Android `OpenFlightApplication`, iOS guarded `startKoin`. `ShotMetricFormatter` added to core:model (en-US, reuse in S9). Permissions: `TransportPermissionRequest(transport)` expect/actual — **Android 17 (targetSdk 37) needs runtime `ACCESS_LOCAL_NETWORK` for Wi-Fi to the Pi** (connects silently time out otherwise); BLE uses `requiredBluetoothPermissions`. Debug hooks: iOS args `--ui-testing --preview-shot --transport wifi --host h:p`; Android extras `ui_testing`/`preview_shot` (bool), `transport`/`host` (string). Device UI tests: `./gradlew :feature:dashboard:connectedAndroidDeviceTest` (enabled in `openflight.kmp.compose`; Espresso pinned 3.7.0 for API 36+). Live E2E on emulator + iOS sim OK; Darwin SSE streams per event (verified); club POST round-trip verified on Android only (no iOS tap tool), server→app `club_changed` verified on both.
- S7 ✅ merged (02d4503), orchestrator verified screenshots. Android 17 (targetSdk 37) needs ACCESS_LOCAL_NETWORK for Wi-Fi → TransportPermissionRequest(transport,onGranted). Espresso pinned 3.7.0 for API 37 device tests. S10 polish: degree unit spacing ('9.5 °'). iOS club pick from menu untested (no tap tool).
- S8c ✅ feature:calibration (1d98aa3). `CalibrationUiState(sensor: SensorUiState, submit: SubmitUiState, transport, host, bluetoothReady)` product type; `SensorUiState.Unavailable(message)` / `.Sampling(displayAngles?, progress, sampleCount, measurement?)`; `SubmitUiState.Idle/Submitting/Applied(CalibrationResult)/Failed(message)`; `applyEnabled` = `measurement.isReadyToSend && (WIFI || bluetoothReady) && submit !is Submitting`. `CalibrationViewModel(ShotRepository, SettingsRepository, GravitySensor, deviceModelProvider: () -> String = ::deviceModel)` — the last param is injectable because calling the real `deviceModel()` (Android actual reads `Build.MODEL`) inside `testAndroidHostTest` NPEs via Kotlin's non-null intrinsic (no Robolectric); Koin wires it with `viewModel { CalibrationViewModel(get(), get(), get()) }` rather than `viewModelOf(::CalibrationViewModel)`, since the latter tries to `get()` every constructor param including the `() -> String` default. `platformCalibrationModule` expect/actual supplies `GravitySensor` (Android: `createGravitySensor(androidContext())`; iOS: `createGravitySensor()`). `KeepScreenOn` expect/actual composable (`View.keepScreenOn` / `UIApplication.idleTimerDisabled`), active while `sensor is Sampling`. UI split across `CalibrationScreen.kt` + `CalibrationMeasurementCard.kt` to stay under detekt's `TooManyFunctions` per-file limit; no icon library added (dashboard has none either) — cards use `OfText`/`OfCard` only. composeApp edits kept to the agreed three: the `Calibration` line in `AppNavHost.kt`, `calibrationModule` in `appModules` (`AppKoin.kt`), and `api(projects.feature.calibration)` in `composeApp/build.gradle.kts`, plus `include(":feature:calibration")` in `settings.gradle.kts`. Tests: 9 VM tests (Turbine, fake `GravitySensor`/`ShotRepository`) covering stable/noisy/roll>3° readiness, BLE-without-controls vs Wi-Fi Apply gating, submit success/error (server message verbatim) and sampling continuing through a submission — pass on both `testAndroidHostTest` and `iosSimulatorArm64Test`. 10 `androidDeviceTest` Compose UI tests (`given_when_then`) pass on `Pixel_10_Pro_XL`. Manual: Android emulator over Wi-Fi (`10.0.2.2:8091`, `adb emu sensor set acceleration 0:9.81:0`) reached "STABLE 2-SECOND AVERAGE" / "Stable measurement ready" and, on Apply, showed **"TI IWR6843 radar is not enabled"** verbatim from the mock server's 409. iOS simulator: confirmed via `iosSimulatorArm64Test`'s unavailable-sensor VM test and the dashboard screenshot that the iosApp/ComposeApp build links and runs; could **not** capture a live tap-through screenshot of the Calibration screen itself — no macOS Accessibility permission for `osascript`/System Events and no `idb`/`cliclick` in this environment (same "no tap tool" gap S7 hit for iOS club selection). **Deferred to S10:** an iOS screenshot of "Motion unavailable" taken with a human or a proper XCUITest/idb setup; the real successful calibration round trip needs TI hardware (`--iwr6843`).
- S8c ✅ merged (79ffbd9). 409 path verified by screenshot. iOS 'Motion unavailable' screenshot pending (no tap tool).
- S9 ✅ merged (d046f7b). Newest-wins verified live on both platforms. Flat arcs = faithful port (reference constrain() scales x/z only, not y) → flagged to user, not changed. Open: SSE stale-client churn seen during S9 (investigating), rotation mid-flight suspends flight.
- SSE churn investigated: NOT a leak. Per-scenario lsof shows 1 conn running / 0 stopped on Android+iOS. Server ShotStreamBroker only unsubscribes on failed heartbeat write → phantom clients linger 15–35 s; confounders (2 installs, shared sim) hit the 8-cap. Android rotation recreates Activity → one reconnect + range flight suspended (polish in S10-prep).
- S10-prep ✅ (agent-doable portion). Degree spacing: `unitSuffix()` in `core:designsystem/OfMetric.kt` now attaches "°" tight ("9.5°") vs a leading space for word units (mph/rpm/yds); covers dashboard detail metrics + range overlay via `OfMetricPrimary`/`OfMetricDetail`. Rotation: `MainActivity` gets `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density|uiMode"` so Compose survives rotation without recreation — verified on `Pixel_10_Pro_XL` against `--mock --web-port 8091`: the SSE connection's local port (netsimd 127.0.0.1:58655↔8091) was identical before/after `user_rotation` toggles, landscape layout screenshot confirmed correct on both dashboard and range. Accessibility: `OfMetricPrimary`/`OfMetricDetail`/status chip/shot-history row now use `Modifier.semantics(mergeDescendants = true)` + a spelled-out `contentDescription` (e.g. "Ball speed, 139.1 miles per hour"; `OfMetricTest`, new `DashboardScreenTest` case); existing UI tests that inspected leaf Text nodes inside these now-merged tiles were updated to `useUnmergedTree = true`. Font-scale pass at 2.0 found two real overlap bugs (screenshots in scratchpad, not committed): (1) a word-shaped unit like "MPH" word-split across two lines inside the value/unit `Row` — fixed by switching to `FlowRow(itemVerticalAlignment = Bottom)`; (2) on the range screen, `Controls` (Exit/status/Replay) and `RangeMetricsOverlay` were independently top-aligned Boxes, and the overlay's fixed top padding (64/72dp, sized for one status-pill line) was overlapped once the pill wrapped to 3 lines — fixed by stacking both in one `Column` so the metrics row is pushed down by `Controls`' real height, dropping the fixed padding to a small gap. `docs/hardware-test-matrix.md` added (all Step 10 + prior human-gated items, incl. S8b gravity/roll-sign, real BLE on-device incl. API 31+/≤30 permission flows, adapter off/on, `--iwr6843` calibration, iOS Motion-unavailable + club-menu checks, frame-rate profiling, background-BLE limits, Pi kernel `6.18.34+rpt-rpi-2712`). README rewritten (features, mock-server run instructions incl. `--web-port`/`fire-mock-shot.py`/emulator-vs-simulator host, protocol pointer, test commands, known limitations). App icons/splash and the `v0.1.0` tag are **not done** — human-gated (logo/branding needs the user's decision) and left for a person, per this step's instructions.
