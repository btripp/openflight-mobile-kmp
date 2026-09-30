# Changelog

All notable changes to OpenFlight Companion are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Pre-releases are published as
[GitHub pre-releases](https://github.com/btripp/openflight-mobile-kmp/releases). Each section
lists the iOS build (`CURRENT_PROJECT_VERSION`) and the Android `versionCode` built from that
tag. Plain `v0.1.0` waits on the hardware test matrix on a real Raspberry Pi.

## [Unreleased]

iOS 0.1.0 (build 4), Android 0.1.0 (versionCode 4). Build 4 was uploaded to TestFlight without a
tag, to test Bluetooth against a schema-2-only Pi; the next tagged release is `v0.1.0-beta.4`.

### Changed

- **Bluetooth LE is schema 2 only** (#59). The app connects to a Pi whose Bluetooth serves only
  the schema 2 shot and control characteristics, which it previously rejected. Bluetooth now
  needs the Pi's schema 2 build; a Pi without it shows "The Pi needs its Bluetooth update" and
  can still use Network. A refused schema 2 handshake is an error with Retry, not a fallback.
- Build numbers bumped to iOS build 4 and Android versionCode 4 (#60).

### Fixed

- Settings › Audio call-outs: unchecking every field now keeps them off, instead of switching
  Carry and Ball speed back on (#75).
- Practice no longer drops a new shot or brings back a deleted one when both happen at the same
  moment (#73).
- A shot deleted or a profile's session cleared on the Pi's own screen now also leaves Practice
  and the phone's saved history, on a Pi without the phone-connectivity update too (#68).
- Clearing a session now removes all of that profile's saved shots from it, not only its newest
  200 (#74).
- **Over Bluetooth, History stores each shot's final values** (#66). A shot that arrived
  provisional and then final could be stored with the provisional's ball speed, carry and carry
  range, and nothing corrected it, so History, Bag stats and gapping were wrong.
- **Over Bluetooth, stored shots keep their profile and shot number** (#67), so a profile filter
  no longer drops them. The stored row now takes its values from the shot event itself.
- Android: a status pill whose text wraps (Settings' long "Live session" reconnect reason, most
  visibly at 200% text) is now a rounded rectangle, so its lines no longer crowd the pill's
  curved edges (#65). One-line pills keep their fully rounded ends.
- iOS: in Demo mode the Demo banner no longer covers the navigation bar's back buttons, Bag's
  Edit, the iPad sidebar toggle or the Range's top controls; screens now start below it, and its
  text stops growing at xxLarge so it stays one compact line at accessibility sizes (#79).
- iOS: at the accessibility text sizes, header rows (Practice's "Launch Monitor" with Range, the latest shot's "View on range", the profile's "Change", Bag's "Conditions" with its pill) put their buttons under the title, so no word breaks mid-word and the pill no longer cuts to "M…" (#81).
- **The Range works at the largest text sizes** (#80). On iOS at accessibility sizes the top bar
  collapses to Exit, a "More" menu (camera, History, Replay) and settings, so nothing runs off
  the screen; the metrics use two columns with scaling labels, and the angles read as degrees to
  VoiceOver. On Android at 150% text and above the dock uses two columns instead of cutting
  values short.
- iOS: gold buttons ("Hit a shot", "Looks right", Retry, Simulate and others) show dark text on
  gold, as on Android, instead of hard-to-read white, and a disabled one stays readable (#82).
- A very strong headwind no longer shows a negative carry and total: the carry stops at 0 and
  no roll is added to it (#71).
- Bag: the metric carry spread (± N m) no longer rounds twice, so 2.4 m reads "± 2 m" instead
  of "± 3 m" (#72).
- Network: a Pi address with a network interface (`[fe80::1%en0]`) is refused in Settings with
  a plain explanation instead of connecting forever. An address the phone can't use at all now
  says so and stops, rather than showing "unreachable" and retrying without end (#70).
- Network: IPv6 addresses with a `+` or `-` in them (`[fe80::-1]`) are refused as not valid,
  instead of being accepted and then failing to connect (#76).
- Network: after a live shot stream drops, the app reconnects within a second again, instead of
  waiting longer after each drop until every reconnect took 15 seconds (#69).

### Removed

- The Bluetooth version-one characteristics and the v1 fallback (#59). Network (Socket.IO and
  SSE) schema handling is unchanged.

## [0.1.0-beta.3] - 2026-09-28

iOS 0.1.0 (build 3) for TestFlight, and the signed Android APK 0.1.0 (versionCode 3) (#57).

### Added

- **Pi low-battery warnings** (#48, #49): an amber or red notice on Practice, plus a one-time
  spoken alert when audio call-outs are on.

### Changed

- **"Wi-Fi" is now "Network"** (#17, #51), and the 192.168.4.1 access-point hint is gone.
- **Club pickers follow your bag** (#15, #53): your active bag's clubs come first, then
  "All clubs…".

### Fixed

- **Club changes work on a stock upstream Pi** (#12, #16): the app falls back to Socket.IO
  `set_club` instead of failing with "HTTP 405".
- **Calibration on a stock Pi** (#13, #16) explains that the Pi needs the phone-connectivity
  update, instead of showing "HTTP 405".
- **Bluetooth "nothing found" hint** (#14, #16): after 15 s of scanning, the app explains why
  and suggests the network connection.
- **Android reconnects after a late local-network permission** (#6, #50): tapping Allow now
  reconnects both links at once, and connection timeouts retry.
- **Android Settings "Live session"** (#7, #52): a long status no longer squeezes the label into
  one letter per line.
- iOS "Overlay all sessions" no longer draws near-vertical streaks (#8); closed with this
  release, fixed by the wave 4 tee camera (#10).

### Known issues

- Android Settings at 200% text: the "Shot stream" host breaks mid-word (#54).
- Android bottom bar at 200% text: the tab labels run together (#55).
- iOS: the Range club picker lists clubs bottom-up (#56).

## [0.1.0-beta.2] - 2026-09-27

iOS 0.1.0 (build 2) for TestFlight, and the first signed Android APK, 0.1.0 (versionCode 2).

### Added

- **Range themes and atmosphere** (#10): sky, haze, tree line, stripes, tracer glow, and polish.
- **Shot trails** on the range (#10).
- **Range quick settings** and a viewing profile saved per device (#10).
- **Demo mode**: try every feature without a Pi (#10).
- **Signed Android release APK** (#11): signed with a keystore named by user-level Gradle
  properties, and not debuggable, so the preview hooks are off.

### Changed

- Build numbers bumped to iOS build 2 and Android versionCode 2 (#11).

### Known issues

- Android: the Socket.IO session doesn't recover if the local-network permission is granted
  after the first connection attempt (#6).
- Android Settings: a long "Live session" status squeezes its label (#7).
- iOS: "Overlay all sessions" on the range draws near-vertical streaks (#8).

## [0.1.0-beta.1] - 2026-09-27

The first TestFlight beta: iOS 0.1.0 (build 1), built with the release signing setup from #9.
Android stays at versionCode 1 (no APK attached).

### Added

- **Navigation and layout** (#2): four tabs on both platforms (Practice · Sessions · Bag ·
  Settings). Tablets get an Android navigation rail or an iPad sidebar, with list-detail
  layouts.
- **Practice**: profiles (select, add, rename, remove), a processing indicator, per-problem
  connection messages including denied local-network access, and a once-per-launch club
  confirmation.
- **Live shots and club changes on a stock upstream Pi** over Socket.IO alone (#4).
- **Driving range** (#2, #3): replay, an overlay of up to 200 shots, gestures, and "view any
  shot on the range".
- **Sessions**: a dispersion map, server-confirmed delete and clear, and a stored session
  history (Room) with CSV export and replay on the range.
- **Bag** (#2): My Bag, club analysis and gapping, and conditions-adjusted carry, wind and
  estimated roll, always labelled "est.".
- **Settings**: device cards (power, trigger, radar, debug), a step-by-step Pi shutdown, the
  rewritten camera screen, and audio shot call-outs (#2).
- **Transports**: Bluetooth LE schema v2 negotiation and SSE `?schema=2`.
- **iOS release setup** (#9): a privacy manifest and the export-compliance flag.
- A README with screenshots and a status checklist (#5).

### Changed

- **Range rendering on iOS** uses shared geometry drawn with SwiftUI `Canvas` (ADR 0002).
- **Accurate ball flight** (#3): the backend's drag and lift model, with a drag-fit carry.
- CI GitHub Actions updated (#1).

### Security

- An endpoint policy gates every Pi transport and keeps plain `http://` on the LAN.
- iOS signing values moved out of git into `Local.xcconfig` (#9).

### Known issues

- Android: the Socket.IO session doesn't recover if the local-network permission is granted
  after the first connection attempt (#6).
- Android Settings: a long "Live session" status squeezes its label (#7).
- iOS: "Overlay all sessions" on the range draws near-vertical streaks (#8).

## [0.1.0-sim] - 2026-09-24

The first tagged build, verified on simulators and emulators only: iOS 0.1.0 (build 1),
Android 0.1.0 (versionCode 1). No GitHub release or store build.

### Added

- Native UI per platform over shared Kotlin Multiplatform ViewModels: Jetpack Compose on
  Android and SwiftUI on iOS (ADR 0001).
- Live shots over Bluetooth LE or Wi-Fi (Server-Sent Events), with automatic reconnect.
- Dashboard with the latest shot's metrics, confidence indicators and shot history, and club
  selection synced both ways with the Pi.
- Phone-assisted TI radar tilt calibration.
- Driving range with a fixed or follow-ball camera.
- Session stats, delete and clear, and CSV export.
- Wi-Fi features over the Pi's Socket.IO API: server-backed sessions, camera preview, speed
  training, player selection, a radar/debug panel and a Pi shutdown.
- Imperial or Metric units, accessibility labels, and app icons from the OpenFlight logo.

[Unreleased]: https://github.com/btripp/openflight-mobile-kmp/compare/v0.1.0-beta.3...HEAD
[0.1.0-beta.3]: https://github.com/btripp/openflight-mobile-kmp/compare/v0.1.0-beta.2...v0.1.0-beta.3
[0.1.0-beta.2]: https://github.com/btripp/openflight-mobile-kmp/compare/v0.1.0-beta.1...v0.1.0-beta.2
[0.1.0-beta.1]: https://github.com/btripp/openflight-mobile-kmp/compare/v0.1.0-sim...v0.1.0-beta.1
[0.1.0-sim]: https://github.com/btripp/openflight-mobile-kmp/releases/tag/v0.1.0-sim
