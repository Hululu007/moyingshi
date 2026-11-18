# b8-lab-swipe-fix: Lab group swipe, observe-don't-consume port

## Objective

Completion sentence: slow deliberate drags and fast flings over the
Lab package list both switch groups in both directions, while
vertical scrolling, pull-to-refresh, and card taps are unaffected.

This is the Lab half of diagnosis §2
(`docs/b8-acceptance-159-diagnosis.md`): build #159's Lab swipe was
effectively dead (velocity-only `onHorizontalDragEnd` at ±500 px/s,
no displacement fallback, gesture-arena loss to the vertical list).
The fix ports the native `LabActivity` swipe semantics into
`b21_lab_page.dart`, replacing the arena-competing `GestureDetector`
with a `Listener`-based observer, mirroring native's
observe-don't-consume `OnTouchListener`.

Lane: `quick-fix`, one task-guard session (`b8-lab-swipe`), one
atomic commit. Scope: `flutter_module/lib/b21_lab_page.dart` and
this doc only. Protected pre-existing dirty path:
`docs/b8-acceptance-159-diagnosis.md` (uncommitted assessment work,
untouched). Local time at start: 2026-11-18 20:54 CST
(Asia/Shanghai). No Java/Android/Flutter SDK on this machine; CI
(`b8-c0.yml`) is the compile check and device acceptance S5 is the
behavior check.

## Native semantics, verified against the current tree

Read from `app/src/main/java/com/github/moyingshi/osc/tv/lab/LabActivity.java`
(kept native Activity, still present). Every claim below was
confirmed in code; corrections to the diagnosis doc are marked.

1. **Attachment — observe, don't consume** (`:166-171`).
   `OnTouchListener` on the package `RecyclerView` and on the empty
   view; `onSwipeTouch` (`:365-371`) feeds the `GestureDetector`
   and always returns `false`. Vertical scrolling, pull-to-refresh
   and card taps keep their own paths. Confirmed as diagnosed.
2. **Direction lock** (`GroupSwipeListener.onScroll`, `:890-902`).
   Horizontal locks only if `|dx| > slop` and `|dx| > |dy|`;
   vertical locks if `|dy| > slop` and `|dy| > |dx|`; slop is
   `ViewConfiguration.getScaledTouchSlop()`. Confirmed as diagnosed
   (see D2 for the slop value used in the port).
3. **Follow-the-finger** (`onScroll`, `:903-909`). `translationX =
   swipeDragBaseX + dx`, clamped to ±screen width; at the ends (no
   group in that direction) the drag beyond the base is scaled by
   **0.35**. Confirmed as diagnosed. The clamp was not mentioned in
   the diagnosis; it is ported.
4. **Fling recorded, settled on release** (`onFling`, `:911-924`,
   plus `settleSwipe` `:384-400`). The diagnosis omits two gates
   that `onFling` applies: `|velocityX| >= |velocityY|` and a
   minimum displacement of **50dp** (direction from the total
   displacement sign, not the velocity sign). The framework's
   minimum fling velocity additionally gates whether `onFling`
   fires at all. All three gates are ported (see D3 for the
   velocity number).
5. **Settle** (`settleSwipe`, `:384-400`). Fling first; else the
   current `translationX` against `max(120dp, 1/4 screen width)`.
   Over threshold with a group in that direction: switch, then the
   new content slides in once from the gesture direction in
   **220ms** (`DecelerateInterpolator`) — the old content is NOT
   slid out first. Otherwise bounce back in **180ms** (the
   diagnosis gave no bounce-back duration; native is 180ms).
   Direction: swipe left (negative) → next group (+1), swipe right
   → previous (−1). Confirmed as diagnosed.
6. **Gesture start interrupts the settle** (`onDown`, `:877-887`).
   `swipeSettleToken++`, the running settle animation is cancelled,
   and `swipeDragBaseX` is the current `translationX`, so a new
   drag continues from the current offset without a jump. Not
   mentioned in the diagnosis; ported.
7. **Content swap zeroes the offset** (`render()`, `:249-252`).
   `swipeSettleToken++`, animation cancelled, `translationX = 0`.
   Ported as the build-time group sync (see D4).
8. **Gesture area**: recycler + empty view only; the group chip
   `RecyclerView` scrolls horizontally by design and has no swipe
   handling. Confirmed as diagnosed; the Dart chips row is
   untouched and outside the `Listener`.

## Diagnosis-doc claims that did not hold (or needed correction)

- **C1 — wrong citation.** The brief pointed at
  `docs/b8-b21-lab.md` §5 V7 for the swipe contract; V7 (`:208`)
  is about file/dir-pick epoch bumps on cancel, unrelated to the
  swipe. The swipe contract lives at `docs/b8-b21-lab.md:22`
  (native semantics summary) and the ±500 px/s simplification
  claim at `:111`. The old Dart comment citing "§5 V7" repeated
  the same wrong citation; it is gone with the old handler.
- **C2 — fling gates omitted.** See native item 4 above: the
  diagnosis says "fling recorded but settled on release" without
  the dominance and 50dp-displacement gates. Both are ported.
- **C3 — bounce-back duration omitted.** Native is 180ms; the port
  uses 180ms.
- No diagnosis claim about the defect itself was overturned: the
  velocity-only handler, the missing displacement fallback, and
  the arena competition are all as described.

## Implementation

`flutter_module/lib/b21_lab_page.dart` only (240 insertions, 15
deletions). `_switchGroup` is byte-identical; the chips row, the
refresh logic, and every Pigeon/Java/manifest file are untouched.

- **Imports.** Explicit `show` imports (module convention, cf.
  `b11_home_page.dart`): `dart:math as math`;
  `dart:ui` pointer-event types + `PointerDeviceKind` + `Offset`;
  `package:flutter/animation.dart`
  (`AnimationController`, `CurvedAnimation`, `Curves`, `Tween`);
  `package:flutter/foundation.dart` (`ValueNotifier`, cf. B7);
  `package:flutter/gestures.dart`
  (`Velocity`, `VelocityTracker`, `kTouchSlop`).
- **State.** `_B21LabListViewState` gains
  `SingleTickerProviderStateMixin` (for the settle controller).
  New fields: `_swipeOffset` (`ValueNotifier<double>`), the settle
  `AnimationController` + `Tween<double>` + `CurvedAnimation`
  (decelerate), tracked-pointer id, down position, `VelocityTracker`,
  base offset, direction-lock flags, pending fling delta, and
  `_swipeGroup`. Controller and notifier are disposed in
  `dispose()`.
- **Handlers** (new, after `_switchGroup`): `_hasGroup` (native
  `hasGroup`); `_onSwipePointerDown` (first pointer only, stop the
  settle controller, capture the base offset, fresh
  `VelocityTracker`); `_onSwipePointerMove` (direction lock, then
  follow-the-finger with 0.35 end resistance and ±width clamp,
  writing only `_swipeOffset` — no `setState`, no list rebuild);
  `_onSwipePointerUp` (fling gates, then `_settleSwipe`);
  `_onSwipePointerCancel` (bounce back, native `ACTION_CANCEL`);
  `_settleSwipe` / `_bounceSwipeBack` / `_animateSwipeOffset`
  (220ms slide-in from ±width after `_switchGroup`, 180ms
  bounce-back, decelerate curve, tween retargeted per settle).
- **Tree.** The `GestureDetector(onHorizontalDragEnd:)` wrapping
  `body` is replaced by a `Listener`
  (`behavior: HitTestBehavior.translucent`, so the empty/loading
  states swipe anywhere — `Center` does not hit-test its empty
  area, while native's empty view fills its container) whose child
  is a `ValueListenableBuilder<double>` driving a single
  `Transform.translate` around `body` (passed as the builder's
  `child`, so the list subtree is never rebuilt by pointer moves).
  The `Listener` sits inside the existing `RefreshIndicator`, below
  the chips row; loading/error/local-source/miniapp/package bodies
  are all inside the same subtree, matching native (gesture on
  recycler + empty view).
- **Group sync in `build()`.** If `_currentGroup` changed without
  the swipe settle (chip tap, snapshot group reset), the stale
  offset is dropped and the settle controller stopped — the
  `render()` reset (native item 7). The settle path syncs
  `_swipeGroup` before the rebuild, so the slide-in is unaffected.
  No `setState` is called from `build()`.

## Deviations

- **D1 — displacement fallback requires the horizontal lock.** A
  tap that interrupts a settle animation must not re-switch from
  the stale base offset: native `settleSwipe` reads the raw
  `translationX`, so a tap landing mid-slide-in could switch groups
  again. The port settles by displacement only for gestures that
  locked horizontal; taps and vertical scrolls always bounce back
  (a no-op at offset 0). No behavior change for real gestures.
- **D2 — slop is `kTouchSlop` (18dp), not Android's
  `scaledTouchSlop` (~8dp).** The Flutter-idiomatic value,
  consistent with the list's own recognizers; the lock rule
  (|dx| > slop and |dx| > |dy|) is unchanged.
- **D3 — fling velocity gate kept at ±500 px/s.** Native relies on
  the framework's minimum fling velocity inside `GestureDetector`;
  the port keeps the ±500 px/s value the old Dart handler shipped,
  and adds native's dominance and 50dp-displacement gates. A quick
  flick exceeds 500 px/s easily; slow drags are covered by the
  displacement fallback either way.
- **D4 — build-time group sync instead of touching the chip tap or
  the snapshot apply path.** Both were out of the no-touch list;
  the sync mirrors the native `render()` reset with no `setState`.
- **D5 — first-pointer-only tracking.** A second finger never
  disturbs the drag in progress; native's `GestureDetector` is
  effectively single-gesture too.

## Verification evidence

- Code inspection against the native implementation (items 1–8
  above, each cited to `LabActivity.java` line numbers at HEAD
  `fbb9a21d`).
- `git diff`: only `flutter_module/lib/b21_lab_page.dart` changed
  (+240/−15); `_switchGroup` definition byte-identical (its two old
  call sites in the deleted handler are replaced by the single
  `_switchGroup(delta)` call in `_settleSwipe`); the chips row is
  context-only in the diff; no `GestureDetector` /
  `onHorizontalDragEnd` remains in the file; no Dart/Pigeon/Java/
  manifest file other than this one changed; the protected
  `docs/b8-acceptance-159-diagnosis.md` is untouched.
- No local compile check is possible (no SDK on this machine); CI
  decides. Device acceptance is diagnosis check S5: slow drag and
  fling both switch groups in both directions; vertical scroll,
  pull-to-refresh, and card taps unaffected; end-of-list resistance
  + bounce-back.

## Recovery anchor

- Objective: Lab group swipe per the completion sentence above.
- Status: implemented, one task-guard session, one atomic commit
  (hash/tag recorded in the finish report). Not pushed.
- Files: `flutter_module/lib/b21_lab_page.dart`,
  `docs/b8-lab-swipe-fix.md`.
- Unverified: compile (CI), device behavior (S5).
- Next action: push with the sibling #159-acceptance fixes and
  build; Wei runs S5 on device.
