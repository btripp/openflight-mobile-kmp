---
paths:
  - "feature/games/**"
  - "iosApp/**/*Game*.swift"
  - "shared/**/*Game*.kt"
  - "androidApp/**/*Game*.kt"
---

# Games (`feature:games`, plan F9)

- `feature:games` (KMP, depends on core:data, core:flight, core:insights, core:model) holds the
  pure game engine plus `GamesViewModel` and `ActivitiesViewModel`. Its Android UI
  (`feature:games:ui`) and SwiftUI screens come in F9b/F9c; both VMs are already in
  `gamesModule` (shared `appModules`), `KoinHelper` and the iOS bridge.
- Engine: `GameMode` (TargetCallout, ClosestToPin, Bullseye, GolfPong, IconicShots) scores a
  pure `GameShot`; `GameSessionReducer.reduce(state, event)` has no clock (timestamps come in the
  events). A swing belongs to whoever is up when its `event_id` is **first sighted** and is
  scored when it turns **final** (`FinalShotStream`, A14). Undo takes back the last attribution.
- `GamesViewModel` publishes the running game to `ActiveGameRepository` and clears it on End or
  `onCleared`; End files an `Activity` (type = `GameType.storageValue`, JSON from `GameRecords`).
  No connected Pi means `GamesUiState.ConnectToPlay`; "Simulate shot" shows only for a `--mock` Pi.
- Navigation takes a typed `GameLaunch(type, distanceYards)` (A6): Android passes it with
  `koinViewModel { parametersOf(launch) }`, iOS with `KoinHelper().gamesViewModel(launch:)`.
- The iconic-shot catalog is Kotlin constants (`IconicShotCatalog`, A15): generic, descriptive
  scenarios only, never real players' names or tournament trademarks.
