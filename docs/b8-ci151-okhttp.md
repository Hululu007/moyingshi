# b8-ci151-okhttp: run #151 Java compile failure — wrong OkHttp import package

## Cause

`app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java:18`
imported `com.catvod.net.OkHttp`, which does not exist. The real class is
`com.github.catvod.net.OkHttp` (module `:catvod`), used by every other file in
the repo (e.g. `Startup.java`, `DanmakuApi.java`, and `HomeActivity` on main,
plus ~20 more files).

The typo was introduced by the phase-3 commit `7f01f3de` (lifecycle migration):
its only use is line 755, `OkHttp.get().clear();` in `onDestroy` cleanup, copied
from `HomeActivity.onDestroy`.

## Why it surfaced only now

This is the first run to reach Java compilation since phase 3:

- Runs #143–#146 were cancelled by the next push (concurrency cancel-in-progress)
  before reaching the compile step.
- Runs #147 and #149 failed earlier, at the Dart analyze step.

So the bad import sat uncompiled in the tree for several pushes.

## Fix

One line: `import com.catvod.net.OkHttp;` → `import com.github.catvod.net.OkHttp;`
in `C1ScreenActivity.java`. No behavior change — the call site (`OkHttp.get().clear()`
at line 755) is untouched.

Repo-wide grep for any other `import com.catvod.` (without `github.`) across
`*.java` / `*.kt` / `*.gradle` / `*.gradle.kts`: **zero other occurrences**. No
further fix needed.

## Verification

- `git diff`: 1 file, 1 line changed (the import).
- Repo-wide grep (imports + fully-qualified references): no remaining
  `com.catvod.` without `github.` anywhere.
- Local build is not possible on this VM (no Java/Android SDK); verification is
  by CI run on the pushed head.

## Recovery anchor

- Objective: fix run #151 Java compile failure (wrong OkHttp import package).
- Branch `b8-flutter-rewrite`, base HEAD `b4d4f6b3`.
- Files: `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java`
  (1-line import fix), `docs/b8-ci151-okhttp.md` (this doc).
- Status: fixed locally, committed; **not pushed** (parent pushes via browser).
- Next action: parent pushes the commit via the browser upload route and creates
  a CI check cron for the new head.
