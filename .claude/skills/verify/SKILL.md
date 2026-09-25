---
name: verify
description: Run the OpenFlight invariant chain (spotless, detekt, allTests, Android assemble, iOS framework link, and the Xcode build when iOS code changed) and report pass/fail from real exit codes.
disable-model-invocation: true
argument-hint: "[--no-ios]"
---

Run the CLAUDE.md invariant chain for the current working tree and report the result from the
process exit codes, never from how the log looks.

1. If `JAVA_HOME` is unset, use Android Studio's JBR when it exists
   (`/Applications/Android Studio.app/Contents/jbr/Contents/Home` on macOS).
2. Run `./gradlew spotlessApply` first, and say which files it reformatted (`git status --short`).
3. Run the chain with the exit code captured, writing the log to your scratchpad (or `mktemp`):
   ```bash
   ./gradlew spotlessCheck detekt allTests :androidApp:assembleDebug \
     :shared:linkDebugFrameworkIosSimulatorArm64 > "$log" 2>&1; rc=$?
   ```
   On Linux, or when the arguments include `--no-ios`, drop the link task and add
   `-x iosSimulatorArm64Test`.
4. On macOS, if `git diff --name-only HEAD` plus untracked files include anything under
   `iosApp/` or `shared/src/iosMain/`, also run invariant 9 (`xcodebuild … build`) and capture
   its exit code the same way. Skip it with `--no-ios`.
5. Report a table of each step with its exit code. For any non-zero step, show the first real
   error from the log (search for `FAILED`, `e: `, `error:`, detekt/spotless violations), with
   file:line, and propose the fix. Don't edit code unless asked.
6. Remind the user that device UI tests (`connectedDebugAndroidTest`, the Xcode test targets)
   aren't part of this chain.
