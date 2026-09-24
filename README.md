# OpenFlight Companion (Kotlin Multiplatform)

An Android + iOS companion app for [OpenFlight](https://openflight.dev), the DIY golf launch
monitor. It shows live shots over Bluetooth LE or Wi-Fi (SSE), lets you pick your club,
calibrates the TI radar tilt with the phone's motion sensors, keeps shot history, and draws
a driving-range ball-flight view. The UI is shared with Compose Multiplatform.

> Status: early bootstrap. Only a placeholder screen exists so far. See
> [`plans/openflight-kmp-app.md`](plans/openflight-kmp-app.md) for the build plan.

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
| `androidApp/` | Android application (`com.android.application`), hosts `App()` in `MainActivity` |
| `composeApp/` | KMP shared app shell (Android library + iOS `ComposeApp.framework`) |
| `iosApp/` | Xcode project, embeds `ComposeApp.framework` through a Gradle build phase |
| `build-logic/` | Gradle convention plugins (`openflight.*`) |
| `gradle/libs.versions.toml` | Single source of truth for versions |
| `tools/` | Dev helpers (mock-shot firing) |

## Building

Requirements: JDK 17+ (Android Studio's bundled JBR works), the Android SDK (`sdk.dir` in
`local.properties`), and Xcode 26+ for iOS.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # if no system JDK

./gradlew spotlessCheck detekt          # formatting + static analysis
./gradlew allTests                      # Android host tests + iOS simulator tests
./gradlew :androidApp:assembleDebug     # Android APK
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Run `./gradlew spotlessApply` before committing. The Xcode build phase falls back to
Android Studio's JBR when `JAVA_HOME` is unset, because Xcode doesn't inherit your shell
environment.

## Testing against a mock server

The OpenFlight Pi server runs without radar hardware in mock mode. In an
[openflight](https://github.com/jake-fishtech/openflight/tree/feat/iOS-ble) checkout:

```bash
uv run openflight-server --mock --no-camera            # add --web-port 8091 if 8080 is taken
curl -N localhost:8080/api/shots/stream                # watch the SSE stream
```

Mock shots fire only through the Socket.IO event `simulate_shot`. There is no HTTP route
for it. Use the helper:

```bash
uv run --with "python-socketio[client]" tools/fire-mock-shot.py -n 5 --url http://localhost:8080
```

Each shot appears on the stream as an `event: shot` line. This was verified on
2026-09-24 against `feat/iOS-ble@b053194`.

## Network security

The Pi serves plain HTTP on the local network, at a host the user types in (a `.local` name
or a bare IP).

- **iOS** uses `NSAllowsLocalNetworking`. That allows cleartext only to local names and
  private address ranges.
- **Android** has no equivalent. `network_security_config` cannot allow-list arbitrary IPs
  entered by the user, so cleartext traffic is permitted app-wide. The tradeoff is
  acceptable because the app only talks to the user's own device on the LAN and never sends
  credentials. Revisit it if the app ever talks to internet hosts.
