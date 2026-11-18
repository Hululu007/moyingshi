# B8 CI #163 — Dart analyze failure (import-only)

## Recovery anchor

- Objective: make `flutter analyze --fatal-infos` report zero issues so CI run #163's
  failure is cleared.
- Branch `b8-flutter-rewrite`, base HEAD `71fd2844` (pushed #159 fixes).
- Files: `flutter_module/lib/b11_home_page.dart`, `flutter_module/lib/b21_lab_page.dart`
  (import blocks only), this doc. No other files touched.
- Status: fix applied and verified by grep (below). Compile verification is CI's job
  (no Flutter SDK on this VM). Next action: parent pushes this commit and creates a
  new CI check cron.

## The failure

- CI run #163 (head `71fd2844`, workflow `b8-c0.yml`), job "Build C0 shell APKs",
  step 13 "Analyze Dart code (fail fast)": `cd flutter_module && flutter analyze
  --fatal-infos`. Exit code 1. Steps 1–12 passed; steps 14–18 skipped; no prerelease.
- Failing job's rendered log read verbatim by a browser task (page identity verified:
  run #163, head `71fd284`). 12 diagnostics, all import-only, no error-level
  diagnostic, none in any other file. Summary line: `12 issues found. (ran in 13.5s)`.

| # | File | Line | Code | Diagnostic |
|---|------|------|------|------------|
| 1 | `lib/b11_home_page.dart` | 7:5 | `undefined_shown_name` (warning) | 'PointerCancelEvent' shown, but isn't used |
| 2 | `lib/b11_home_page.dart` | 9:5 | `undefined_shown_name` (warning) | 'PointerDownEvent' shown, but isn't used |
| 3 | `lib/b11_home_page.dart` | 10:5 | `undefined_shown_name` (warning) | 'PointerMoveEvent' shown, but isn't used |
| 4 | `lib/b11_home_page.dart` | 11:5 | `undefined_shown_name` (warning) | 'PointerUpEvent' shown, but isn't used |
| 5 | `lib/b11_home_page.dart` | 13:8 | `unnecessary_import` (info) | 'package:flutter/animation.dart' unnecessary |
| 6 | `lib/b11_home_page.dart` | 18:8 | `unnecessary_import` (info) | 'package:flutter/rendering.dart' unnecessary |
| 7 | `lib/b21_lab_page.dart` | 5:5 | `undefined_shown_name` (warning) | 'PointerCancelEvent' shown, but isn't used |
| 8 | `lib/b21_lab_page.dart` | 7:5 | `undefined_shown_name` (warning) | 'PointerDownEvent' shown, but isn't used |
| 9 | `lib/b21_lab_page.dart` | 8:5 | `undefined_shown_name` (warning) | 'PointerMoveEvent' shown, but isn't used |
| 10 | `lib/b21_lab_page.dart` | 9:5 | `undefined_shown_name` (warning) | 'PointerUpEvent' shown, but isn't used |
| 11 | `lib/b21_lab_page.dart` | 11:8 | `unnecessary_import` (info) | 'package:flutter/animation.dart' unnecessary |
| 12 | `lib/b21_lab_page.dart` | 13:8 | `unnecessary_import` (info) | 'package:flutter/foundation.dart' unnecessary |

Note: `--fatal-infos` makes the 4 info-level `unnecessary_import` diagnostics fatal
too; that is why a warnings/infos-only run exits 1 with no error-level diagnostic.

## Root cause

The four `Pointer*Event` names ARE referenced in both files as handler parameter
types (`_onSwipePointerDown(PointerDownEvent event)` etc. — b11 :511/:530/:564/:587,
b21 :346/:364/:398/:421). They are flagged unused because those references resolve
through the `package:material_ui/material_ui.dart` full import, which re-exports
dart:ui's pointer-event types via `material.dart` — the analyzer attributes each
reference to the material import, leaving the `dart:ui` `show` entries unused.

The four unnecessary imports are likewise fully re-exported through the material
import: `animation.dart` (`AnimationController`/`CurvedAnimation`/`Tween`/`Curves`),
`rendering.dart` (`RenderBox`/`RenderObject`, used at b11 :504), `foundation.dart`
(`ValueNotifier`, used at b21 :163). The analyzer states all used elements are
also provided by the material import.

This is the same class of failure as CI #147/#149 (glass v2 `Matrix4`/`Colors` import
issues): the swipe work in b11/b21 added `dart:ui` show lists and explicit
`flutter/*` imports that duplicate what `material_ui` already re-exports.

## Fix (import-only, no logic touched)

`b11_home_page.dart`:
- `dart:ui` show list reduced to `ImageFilter, Offset, PointerDeviceKind`
  (removed `PointerCancelEvent`, `PointerDownEvent`, `PointerMoveEvent`,
  `PointerUpEvent`).
- Deleted `import 'package:flutter/animation.dart' show AnimationController,
  CurvedAnimation, Tween;`.
- Deleted `import 'package:flutter/rendering.dart' show RenderBox, RenderObject;`.

`b21_lab_page.dart`:
- `dart:ui` show list reduced to `Offset, PointerDeviceKind` (same four names
  removed).
- Deleted `import 'package:flutter/animation.dart' show AnimationController,
  CurvedAnimation, Curves, Tween;`.
- Deleted `import 'package:flutter/foundation.dart' show ValueNotifier;`.

Kept deliberately (not flagged, still needed as written):
- b11's `package:flutter/foundation.dart` import (`ValueListenable`,
  `ValueNotifier`) — the analyzer did NOT flag it, unlike b21's.
- `package:flutter/gestures.dart` (`Velocity`, `VelocityTracker`, `kTouchSlop`)
  in both files.
- Remaining `dart:ui` shown names (`ImageFilter`, `Offset`, `PointerDeviceKind`).

## Verification (no SDK locally; CI decides)

- After the edits, grep confirms: no `flutter/animation.dart` or
  `flutter/rendering.dart` import remains in b11; no `flutter/animation.dart` or
  `flutter/foundation.dart` import remains in b21; both `dart:ui` show lists
  contain only the kept names.
- Names from the removed imports are still referenced in code — 13 occurrences
  in b11 (`AnimationController`/`CurvedAnimation`/`Tween`/`RenderBox`/
  `RenderObject`) and 10 in b21 (`AnimationController`/`CurvedAnimation`/`Curves`/
  `Tween`/`ValueNotifier`) — and resolve via the `material_ui` full import,
  exactly as the analyzer asserted. No handler, widget, or logic line was edited.
- `git diff --stat` for the commit covers only the two Dart files plus this doc.
