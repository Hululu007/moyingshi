# B8 CI #165 — missing `Util` import in C1ScreenActivity

## Objective

Completion sentence: the mobile Java compile passes — the single
`cannot find symbol` error from CI run #165 is gone.

## Diagnostic (run #165, head 4438b2a2, verbatim from the rendered job log)

- Step 13 "Analyze Dart code (fail fast)" PASSED in this run.
- Step 15 "Build four release APKs" FAILED; the only failure in the job.
  Later steps (collect/upload/publish) skipped; no prerelease.
- Exactly one compile error, printed twice (javac output + Gradle summary):

```text
app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java:891: error: cannot find symbol
    Util.moveToBackground(this);
    ^
  symbol:   variable Util
  location: class C1ScreenActivity

> Task :app:compileMobileArm64_v8aReleaseJavaWithJavac FAILED
```

## Root cause

The §1 back-stack fix (commit `ba8ef568`, `handleRootBack()`) added the
call `Util.moveToBackground(this)` — the unconditional home-back path —
without its import. `Util` at `:891` was the only `Util` reference in the
file, and the file already imported the sibling utils `PermissionUtil`
and `UrlUtil` from the same package, which is why the omission was not
obvious. Dart analyze (run #163's gate) cannot see a Java import, so the
defect only surfaced at the Java compile step in run #165.

The method lives in the app's own class
`app/src/main/java/com/github/moyingshi/osc/tv/utils/Util.java:57`:

```java
public static void moveToBackground(Activity activity) {
    try {
        activity.moveTaskToBack(true);
    } catch (NullPointerException ignored) {
        activity.startActivity(new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
```

The other `Util` (`com.github.catvod.utils.Util`, used by
MpvHlsProxy/Decoder/SiteApi) has no `moveToBackground` and was NOT
imported. The canonical import for the app's class is
`com.github.moyingshi.osc.tv.utils.Util` (same as Device/Episode/Flag).

## Fix

One line in
`app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java`,
added alphabetically after the `UrlUtil` import:

```java
import com.github.moyingshi.osc.tv.utils.Util;
```

No change to `handleRootBack()` or any other code. `this` is a
`C1ScreenActivity` (an `AppCompatActivity`, hence an `Activity`), so the
call signature matches.

## Verification (no Java/Android/Flutter SDK on this VM — CI decides)

- `git diff`: exactly 1 insertion, 0 deletions, in the one file.
- `grep -n Util C1ScreenActivity.java`: the import (:40) and the single
  call site (:892, shifted by the added line) are the only `Util`
  references; `TextUtils` (:7) is a different class and unaffected.
- `grep moveToBackground utils/Util.java`: the method exists at :57 with
  the matching `Activity` parameter.
- No second missing import: the run #165 log contains exactly one
  `cannot find symbol` error (javac reports all such errors in one pass),
  and no other step in the job failed.

## Recovery anchor

- Task: `b8-ci165-util-import` (quick-fix), branch `b8-flutter-rewrite`.
- Objective: mobile Java compile passes (run #165's single error gone).
- Files: `C1ScreenActivity.java` (1-line import) + this doc.
- Status: fix applied and committed locally; NOT pushed.
- Next action: parent pushes this commit (docs + code) via the browser
  upload route, verifies remote tree == local, aligns the local pointer,
  and creates a one-time CI check cron (~25–30 min later).
