#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Claude Code PreToolUse hook (Bash). Blocks `git commit` unless the CLAUDE.md invariant chain
# passes, judged on Gradle's exit code, never on grepped output ("Gate commits on exit code").
#
# - Only acts on commands that run `git commit`; everything else passes straight through.
# - Commits that touch only Markdown skip the build (docs/plan logs don't need a Gradle run).
# - macOS also links the iOS framework; Linux can't, so it runs the JVM half (like CI's jvm job).
# - Exit 2 blocks the tool call and feeds stderr back to Claude; exit 0 lets it through.
#
# Escape hatch for humans: OPENFLIGHT_SKIP_COMMIT_GATE=1 in the environment Claude Code runs in.

set -uo pipefail

input="$(cat)"

if command -v jq >/dev/null 2>&1; then
  cmd="$(printf '%s' "$input" | jq -r '.tool_input.command // empty')"
  cwd="$(printf '%s' "$input" | jq -r '.cwd // empty')"
else
  # No jq: fall back to matching the raw JSON; good enough to spot `git commit`.
  cmd="$input"
  cwd=""
fi

# `git commit`, `git -C dir commit`, also after `&&`/`;`. Not `git commit-tree` etc.
if ! printf '%s' "$cmd" | grep -Eq '(^|[;&|[:space:]])git([[:space:]]+-C[[:space:]]+[^[:space:]]+)?[[:space:]]+commit([[:space:]]|$|")'; then
  exit 0
fi

if [ "${OPENFLIGHT_SKIP_COMMIT_GATE:-0}" = "1" ]; then
  echo "commit-gate: skipped (OPENFLIGHT_SKIP_COMMIT_GATE=1)" >&2
  exit 0
fi

root="${cwd:-${CLAUDE_PROJECT_DIR:-$PWD}}"
root="$(git -C "$root" rev-parse --show-toplevel 2>/dev/null || echo "$root")"
cd "$root" || exit 0

# Staged, unstaged and untracked files: the hook runs before the command, so `git commit -a` and
# `git add x.kt && git commit` haven't staged anything yet.
changed="$(
  {
    git diff --cached --name-only
    git diff --name-only
    git ls-files --others --exclude-standard
  } | sort -u
)"
if [ -n "$changed" ] && ! printf '%s\n' "$changed" | grep -Evq '\.md$'; then
  echo "commit-gate: only Markdown changed, skipping the build" >&2
  exit 0
fi

studio_jbr="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$studio_jbr" ]; then
  export JAVA_HOME="$studio_jbr"
fi

tasks=(spotlessCheck detekt allTests :androidApp:assembleDebug)
if [ "$(uname -s)" = "Darwin" ]; then
  tasks+=(:shared:linkDebugFrameworkIosSimulatorArm64)
else
  tasks+=(-x iosSimulatorArm64Test)
fi

log="$(mktemp -t openflight-commit-gate.XXXXXX)"
echo "commit-gate: ./gradlew ${tasks[*]}" >&2
./gradlew "${tasks[@]}" >"$log" 2>&1
rc=$?

if [ "$rc" -ne 0 ]; then
  {
    echo "commit-gate: BLOCKED. ./gradlew ${tasks[*]} exited $rc. Full log: $log"
    echo "Fix the failure (run spotlessApply for formatting), then commit again. Last lines:"
    tail -n 40 "$log"
  } >&2
  exit 2
fi

echo "commit-gate: invariant chain passed (rc=0)" >&2
rm -f "$log"
exit 0
