# Hardware test matrix

**Status: PENDING — blocked on a Raspberry Pi.** As of R8g (2026-09-25) the user does not
yet have a Raspberry Pi running OpenFlight, so **every row below is BLOCKED-ON-HARDWARE** and
none has been run. Everything so far has been verified on the Android emulator and iOS
Simulator against `openflight-server --mock --web-port <port>` instead (see the plan's
Execution Log for those simulator/emulator runs), and the data layer by `MockServerIT` against
upstream `main` and the `feat/phone-connectivity` fork branch (README "Integration test against
a real mock server"). Re-run this whole matrix, row by row, once a Pi is available, and flip
each **Result** from BLOCKED-ON-HARDWARE to pass/fail as you go.

Which backend a row needs matters (README "Backend compatibility"): BLE, SSE, `/api/club` and
phone calibration exist only on the fork branch
[`btripp/openflight@feat/phone-connectivity`](https://github.com/btripp/openflight/tree/feat/phone-connectivity)
(or, for BLE v1 only, jake-fishtech's `feat/iOS-ble`). Section 9 lists the rows added for R8.

Everything below needs real hardware (a Raspberry Pi running the OpenFlight server, a
physical Android phone, a physical iPhone, and — for two rows — a TI IWR6843 radar). None of
it can be verified by an agent in CI or on emulators/simulators, so it stays a **human-gated**
checklist. It consolidates every hardware/human-gated item deferred across the plan's
Execution Log (`plans/openflight-kmp-app.md`) and Step 10's matrix.

Run each row, fill in **Result** (pass / fail) and **Notes**, and link a GitHub issue for any
failure instead of leaving the row blank. A row that can't be run (no TI hardware, no second
phone, etc.) should say **BLOCKED-ON-HARDWARE** and why, not be left empty.

## Verify the app (run this first, on real hardware, before the detailed rows below)

The reference iOS app's own "verify the app" checklist (`ios/README.md`), which applies to
both platforms here:

1. Reach **Connected** over the selected transport.
2. Fire or hit a shot and confirm it appears on the dashboard.
3. Open **Driving Range** and confirm the trajectory renders.
4. Change **Club for next shot** and confirm the Pi accepts it.
5. If an IWR6843 radar is attached and enabled (`--iwr6843`), run the phone calibration once.

The Pi replays its most recent completed shot when the app connects, so a fresh install
doesn't need to wait for a new shot if one was already recorded during the current server run.

Setup once, before starting:
- Pi: `uv sync && uv run openflight-server` (real radar) or `--mock` for the rows that don't
  need radar hardware. Note the Pi's kernel: `uname -r`.
- Phones: install the latest debug build (`./gradlew :androidApp:installDebug` /
  `xcodebuild ... -destination 'platform=iOS,id=<device>'`), on the same Wi-Fi network as the
  Pi for the Wi-Fi rows.

---

## 1. Wi-Fi transport

| # | Steps | Expected result | Android result | iOS result | Notes |
|---|---|---|---|---|---|
| 1.1 | Select Wi-Fi, enter the Pi's `host:port`, submit. Fire a shot (`tools/fire-mock-shot.py` against `--mock`, or a real swing against real radar). | Status goes idle → connecting → connected. The shot renders within ~1s: primary metrics (ball speed, carry), detail metrics, correct club name. | | | |
| 1.2 | Fire 2 more shots. | Previous-shots list grows, newest first, capped at 100, no duplicates. | | | |
| 1.3 | Kill the app (don't force-stop), background it, reopen it — the SSE client keeps `lastEventId` and auto-reconnects. | The most recent shot is **not** re-added to history (replay suppression). Tapping **Retry** explicitly, by contrast, **does** re-show the replayed shot (§0.3). | | | |
| 1.4 | Pick a different club from the "Club for next shot" menu. | `POST /api/club` round-trips; the Pi's log/console shows the new club; the app's own selection updates without waiting for `club_changed`. | | | |
| 1.5 | Change the club from the Pi's own web UI (`club_changed` event). | The app's club selector updates to match, without the user touching the phone. | | | |
| 1.6 | Restart the Pi server process while the app is connected. | App shows an error/idle state, then auto-reconnects with capped exponential backoff (1s → 15s) once the Pi is back; no crash. | | | |
| 1.7 | Connect 8 other SSE clients (`for i in {1..8}; do curl -N http://<pi>/api/shots/stream & done`), then let the app be the 9th. | App shows the "too many devices" error (HTTP 503) verbatim, not a generic network error. | | | |

## 2. BLE transport

| # | Steps | Expected result | Android result | iOS result | Notes |
|---|---|---|---|---|---|
| 2.1 | Fresh install on a device that has **never** granted Bluetooth permission. Select Bluetooth as transport. | **API 31+ (Android 12+):** the OS permission dialog for `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` appears; before granting, the app shows `Unavailable("Bluetooth permission is required")`, not a crash or a stuck spinner. | | n/a | |
| 2.2 | Same, on a device running **API ≤ 30** (Android 11 or lower, if available). | The legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION` prompt appears instead; same `Unavailable` messaging before grant. | | n/a | |
| 2.3 | Grant the permission(s) from 2.1/2.2. | App proceeds: scanning → connecting → discovering → connected, filtered on the OpenFlight BLE service UUID (`B6F633F2-…`). | | (iOS: CoreBluetooth's own permission prompt; confirm `NSBluetoothAlwaysUsageDescription` copy shows) | |
| 2.4 | Fire a shot on the radar. | Shot renders identically to the Wi-Fi path (same UI, different transport). Frame reassembly across multiple BLE notifications works for shots > 15 bytes. | | | |
| 2.5 | Change club from the phone. | Control-channel `set_club` round-trips within the 10s timeout; menu shows a busy state meanwhile and is disabled for a second overlapping request. | | | |
| 2.6 | Change club from the Pi's own browser UI while connected over BLE. | `club_changed` arrives on the control characteristic and updates the phone's selection (same as Wi-Fi's SSE event, different channel). | | | |
| 2.7 | Walk the phone out of BLE range, then back in. | Disconnect is detected, any in-flight control request fails with `disconnected`, the app returns to scanning after ~1s, and reconnects automatically once back in range — same TI calibration/club state resyncs on reconnect (`currentClub()` sync-on-connect). | | | |
| 2.8 | Turn the phone's Bluetooth adapter **off** while connected, then **on** again. | Adapter off → app reports the adapter-off state (not stuck "scanning" forever). Adapter on → app resumes scanning on its own and reconnects without the user reopening the screen. | | | |
| 2.9 | **Background BLE limits.** Background the app while connected over BLE (Home button / app switcher), wait ~60s, foreground it again. | v1 is **foreground-only BLE by design** (§7, out of scope). Expected: the connection is dropped/suspended on backgrounding and re-established from a scan on foreground — no attempt at a background BLE session, no crash, no silent data loss beyond the shots missed while backgrounded. Record how long it takes to reconnect on foreground. | | | |

## 3. Permissions, radios, and lifecycle

| # | Steps | Expected result | Android result | iOS result | Notes |
|---|---|---|---|---|---|
| 3.1 | Deny the Bluetooth (or, on Android, local-network) permission outright. | App shows the correct `Unavailable(message)` copy, not a crash, not a blank screen. | | | |
| 3.2 | From that denied state, open system Settings, grant the permission, return to the app. | App recovers and proceeds to scan/connect without needing a full app restart. | | | |
| 3.3 | Turn the phone's Bluetooth off entirely (not just deny permission), or enable Airplane Mode. | Correct "Bluetooth is off" / unavailable messaging; Wi-Fi transport (if selected) is unaffected by Bluetooth being off, and vice versa for Airplane Mode disabling Wi-Fi. | | | |
| 3.4 | Background the app (Home button), wait 10s, foreground it again, while connected over Wi-Fi. | One clean reconnect on foreground, **not** a reconnect storm; verifies the app is foreground-only by design and the transport start/stop only fires on real background/foreground transitions (see the Android rotation fix in this same step — confirms `LifecycleStartEffect` isn't also firing spuriously on this physical device). | | | |
| 3.5 | Rotate the physical phone during an active connection and during a driving-range flight animation. | Connection does not drop/reconnect; flight animation is not interrupted (mirrors the emulator evidence gathered for this step, but on real hardware / real sensors). | | | |

## 4. S8b gravity / roll-sign device check

Deferred from Step 8b (core:sensors) as BLOCKED-ON-HARDWARE; this is the mandatory check
before trusting calibration numbers off either phone. The Pi recomputes tilt/roll from the
raw gravity vector the phone sends, in **iOS CoreMotion's convention**: units of g, vector
points toward Earth, phone lying face-up reads `z ≈ −1`. Android's `TYPE_GRAVITY` sensor
reports the opposite sign in m/s², so the Android `actual` converts with
`g_ios = −g_android / 9.80665` before building the payload.

| # | Steps | Expected value | Android result | iOS result | Notes |
|---|---|---|---|---|---|
| 4.1 | Open **Calibrate TI Radar**. Hold the phone **upright in portrait**, screen facing you, resting flat against a vertical surface (as the on-screen instructions describe). Let the 2-second sample stabilize. Read the logged/displayed gravity vector and the computed tilt/roll. | Gravity vector ≈ `(0, −1, 0)` g (iOS convention) on **both** platforms, to within **~0.02 g**. Computed **tilt ≈ 0°**, **roll ≈ 0°**. | | | |
| 4.2 | Lay the phone **face-up** on a flat, level table. Let the sample stabilize. | Gravity vector ≈ `(0, 0, −1)` g on both platforms, within ~0.02 g. Computed **tilt ≈ +90°**. | | | |
| 4.3 | With the phone upright in portrait as in 4.1, roll it **clockwise** (right edge down) by a small, known angle (e.g. ~10°, checked with a level app or protractor) while keeping tilt near 0. | `roll = atan2(x, −y)` should report a **consistent sign** on both platforms for the same physical rotation direction — i.e. tilting right edge down gives the same sign of roll on Android as on iOS. This is the sign check the plan calls out as the thing that can't be verified without two real devices side by side. | | | |
| 4.4 | Repeat 4.1–4.3 holding a second unit of the *other* platform (Android checked against iPhone, or vice versa) at the same time, same orientation. | Both devices' displayed tilt/roll agree with each other to within ~0.5° (the app's own stability threshold), confirming cross-platform parity, not just each platform being internally consistent. | | | |

## 5. Calibration round trip with real TI hardware

Requires the Pi running **with** `--iwr6843` (real TI IWR6843 radar attached and enabled).
The mock server always returns `409 "TI IWR6843 radar is not enabled"` and cannot verify
this row — that 409 path is already covered by the emulator screenshot from Steps 8c/10.

| # | Steps | Expected result | Result | Notes |
|---|---|---|---|---|
| 5.1 | Start the Pi with `uv run openflight-server --iwr6843` (or the project's documented real-hardware launch command). Mount the phone per the on-screen instructions, get a stable 2-second average, tap **Apply**. | Request succeeds (`ok: true`); the app shows the returned `status`, `persistent`, `measured_mount_tilt_deg`, `configured_iwr_tilt_deg` (and `enclosure_pitch_deg` if present) — not the 409 fallback message. | | |
| 5.2 | Repeat over **both** transports (Wi-Fi and BLE), since `submitCalibration` is transport-agnostic by design. | Both transports produce the same kind of successful result; the Apply button's gating (`(WIFI || supportsControls)`) is confirmed correct on real BLE hardware, not just the emulator/mock server. | | |
| 5.3 | Confirm the calibration actually changes radar behavior: take a real shot before and after applying a deliberately-off calibration, compare the reported launch angle. | The applied tilt visibly affects the radar's angle readings, i.e. this isn't a no-op on the hardware side. | | |

## 6. iOS-specific manual checks (no tap-automation tool was available to the agent)

| # | Steps | Expected result | Result | Notes |
|---|---|---|---|---|
| 6.1 | On a device/simulator with Motion access unavailable or denied, open **Calibrate TI Radar**. Take a screenshot. | Screen shows **"Motion unavailable"** (the `SensorUiState.Unavailable` copy), not a blank or crashed sensor card. Attach the screenshot to this row. | | |
| 6.2 | From the dashboard, tap the **"Club for next shot"** menu and pick a club other than the current one. | Menu opens, selection changes, `setClub` round-trips (same as the already-Android-verified flow in S7's execution log, which explicitly could not be checked on iOS for lack of a tap tool). | | |

## 7. Frame-rate profiling (driving range)

| # | Steps | Expected result | Result | Notes |
|---|---|---|---|---|
| 7.1 | On a **mid-range** physical Android device (not a high-end flagship), open the driving range, fire 3+ shots in quick succession, and profile with Android Studio's Profiler (or `adb shell dumpsys gfxinfo dev.openflight.companion framestats`) during the flight animation. | No sustained dropped frames during `RangeCanvas`'s `withFrameNanos` animation loop; jank stays occasional, not systemic. | | |
| 7.2 | Same, on a physical iPhone, using Instruments' Core Animation / Hitches instrument. | Same bar: no sustained hitching during the ball-flight animation. | | |

## 8. Pi kernel BLE regression

| # | Steps | Expected result | Result | Notes |
|---|---|---|---|---|
| 8.1 | Check the Pi's kernel: `uname -r`. | If it reports **`6.18.34+rpt-rpi-2712`**, BLE advertising/GATT is known to regress on that build (see the plan's S5 exit criteria, which explicitly calls this out and asks for a kernel other than this one). Record the actual kernel string here regardless of outcome. | | |
| 8.2 | If running the flagged kernel, run the full §2 BLE matrix anyway and note which rows fail because of it, versus app-side bugs. If a different kernel is available, prefer it and note the version used. | A clear attribution: which BLE failures (if any) are the kernel regression, not an app defect. | | |

## 9. Backend, BLE schema, permissions and setups (added in R8g)

| # | Setup | Steps | Expected result | Android result | iOS result | Notes |
|---|---|---|---|---|---|---|
| 9.1 | **BLE v1 against jake-fishtech's Pi** (`jake-fishtech/openflight@feat/iOS-ble`, `b053194`, started with `--ble`) | Select Bluetooth, connect, fire a shot, change the club from the phone and from the Pi's web UI. | The v2 `hello` isn't offered (no v2 characteristics), so the app runs as v1: shots, `set_club`/`get_club` and `club_changed` work; profile, processing and power stay empty; Delete and Clear show "Wi-Fi only". | BLOCKED-ON-HARDWARE | BLOCKED-ON-HARDWARE | |
| 9.2 | **BLE v2 against the fork backend** (`btripp/openflight@feat/phone-connectivity`, `07d5313`, `--ble`) | Connect over Bluetooth; switch the active profile on the phone and on the kiosk; fire shots; delete a shot and clear the session on the kiosk; unplug the battery HAT's power if fitted. | `hello` negotiates schema 2 and only the v2 pair is subscribed. Profiles select both ways; each shot is filed under the active profile; the processing indicator shows; `shot_deleted` and `session_cleared` remove the rows on the phone; power updates. Delete and Clear on the phone stay disabled ("Wi-Fi only"). | BLOCKED-ON-HARDWARE | BLOCKED-ON-HARDWARE | |
| 9.3 | **BLE v2 phone against a v1 Pi and a v1 phone against a v2 Pi** | Connect this app to the jake-fishtech Pi, and jake-fishtech's own iOS app to the fork Pi. | Both fall back to v1 and work; neither side sees v2 traffic. | BLOCKED-ON-HARDWARE | BLOCKED-ON-HARDWARE | |
| 9.4 | **iOS Local Network denied** | Fresh install on a real iPhone; deny the Local Network prompt; connect over Wi-Fi. | The connection card shows the local-network-denied state with a button to Settings (not a generic timeout). After allowing it in **Settings > Privacy & Security > Local Network** and returning, the app connects. | n/a | BLOCKED-ON-HARDWARE | |
| 9.5 | **Android `ACCESS_LOCAL_NETWORK` denied** (Android 17+) | Fresh install; deny the local-network permission when connecting over Wi-Fi. | The card shows the open-settings state instead of timing out silently. Granting it in Settings and returning connects without a restart. | BLOCKED-ON-HARDWARE | n/a | |
| 9.6 | **Phone as the only interface to a headless Pi** | Pi with no display (and, separately, as its own access point on `192.168.4.1:8080`). Run a session from the phone only: connect, profiles, shots, delete, clear, device cards, shut down. | Everything is reachable from the phone; nothing needs the kiosk. | BLOCKED-ON-HARDWARE | BLOCKED-ON-HARDWARE | |
| 9.7 | **Phone plus the kiosk** | The Pi's touchscreen runs the web UI while the phone is connected over Wi-Fi. Change the club, the active profile and debug mode on each side; delete a shot and clear on each side. | Every change shows on the other side within a second (all server events are broadcasts). Units are the exception: each side keeps its own. | BLOCKED-ON-HARDWARE | BLOCKED-ON-HARDWARE | |
| 9.8 | **Phone plus `/display`** | A TV or laptop browser shows `http://<pi>:8080/display` while the phone controls the session. | The display follows the phone's shots, club and profile; the phone is unaffected by the display. | BLOCKED-ON-HARDWARE | BLOCKED-ON-HARDWARE | |
| 9.9 | **Provisional → final on real enrichment hardware** (IWR6843 or the high-speed camera configured, fork backend) | Hit shots over Wi-Fi and, separately, over BLE v2. | A provisional (OPS-only) shot appears first and is replaced in place by the final one within 20 s: one row, not two, the same `#n`, and the stored history keeps only the final version. A shot whose enrichment is skipped still gets its final version. | BLOCKED-ON-HARDWARE | BLOCKED-ON-HARDWARE | |
| 9.10 | **Shutdown on a real Pi** | Device card > Shut down the Pi > confirm, over Wi-Fi. Repeat with the Pi unplugged from the network before confirming. | The phase goes pending → done after the Pi's `200`; the link drop that follows is not shown as an error, and the OpenFlight service stops (the Pi itself stays up). Unreachable: it fails after 10 s and Retry targets the same host. | BLOCKED-ON-HARDWARE | BLOCKED-ON-HARDWARE | |
| 9.11 | **HTTPS with a private CA** (backend `feat/lan-https`, README "HTTPS with a private CA") | Install the CA on the phone, start the Pi with `--tls-cert/--tls-key`, enter `https://<host>:<port>`. | SSE (if served), HTTP control and Socket.IO (`wss://`) all connect. Without the CA installed the connection fails with a certificate error rather than falling back to HTTP. | BLOCKED-ON-HARDWARE | BLOCKED-ON-HARDWARE | |

---

**Sign-off:** every row above is pass, fail-with-linked-issue, or BLOCKED-ON-HARDWARE before
Step 10 is considered done per the plan's exit criteria ("no open P0 or P1 defects").
