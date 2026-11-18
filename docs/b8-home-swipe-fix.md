# b8-home-swipe-fix: home tab swipe (Listener observer, per-tab scroll restore)

## Objective

Completion sentence: slow deliberate drags and fast flings over the
home grid switch category tabs in both directions, with per-tab
scroll positions preserved, while vertical scrolling, load-more,
the pinned tab bar, the FABs, and the glass bar drag are unaffected.

This is the home half of diagnosis §2
(`docs/b8-acceptance-159-diagnosis.md`): build #159's home had no
swipe path at all — tabs were tap-only by construction (no pager,
no horizontal-drag handler). The fix ports the native ViewPager +
Lab `GroupSwipeListener` semantics into `b11_home_page.dart`,
reusing the proven Lab port (`docs/b8-lab-swipe-fix.md`) without
re-deriving thresholds, and adds the per-tab scroll positions
native kept (one `TypeFragment` per pager page).

Lane: `standard`, one task-guard session (`b8-home-swipe`), one
atomic commit. Scope: `flutter_module/lib/b11_home_page.dart` and
this doc only. Protected pre-existing dirty path:
`docs/b8-acceptance-159-diagnosis.md` (uncommitted assessment work,
untouched). Local time at start: 2026-11-18 20:58 CST
(Asia/Shanghai). No Java/Android/Flutter SDK on this machine; CI
(`b8-c0.yml`) is the compile check and device acceptance S1–S4 is
the behavior check.

## Native semantics (not re-derived)

The shared gesture semantics are taken verbatim from the Lab port,
which verified each item against `LabActivity.java` (see
`docs/b8-lab-swipe-fix.md` "Native semantics"): direction lock
(`|dx| > slop` and `|dx| > |dy|`), follow-the-finger translation
with 0.35 end resistance clamped to ±screen width, fling recorded
and settled on release (velocity > 500 px/s, horizontal-dominant,
≥50dp travel, direction from displacement sign), displacement
fallback `max(120dp, 1/4 screen width)`, ~220ms decelerate
slide-in, ~180ms bounce-back, swipe left → next (+1), swipe right
→ previous (−1), new-gesture-interrupts-settle continuing from
the current offset, content-swap zeroes the offset. The home port
copies the Lab handlers structurally (same field/handler names
where the surface allows: `_hasTab` for `_hasGroup`, `_swipeTab`
for `_swipeGroup`); only the gating and the transform placement
are home-specific (see D1 and the implementation below).

## Diagnosis-doc claims check

Verified against the live tree at HEAD `0a1a9841` before
implementing. All defect claims held, with two staleness
corrections:

- **Held.** No swipe path: no `onHorizontalDrag`/`onPan` handler
  touches the grid; tabs are `ChoiceChip`s in the pinned
  `SliverPersistentHeader`; `_selectedTab` is written only by the
  initializer, the `_load` bounds clamp, the type-filter dialog
  apply, and the chip tap (now consolidated into `_switchTab`).
  Load-more trigger is `pixels >= maxScrollExtent - 200` with the
  `_hasMore && !_busy` guard (`_TabVideoGridState._onScroll`).
  Sticky-tabs D3 (shared controller keeps its offset across tab
  switches) confirmed in `docs/b8-home-sticky-tabs.md:92-95`.
  Grid D1 serial guard (pending reload while a load is in flight)
  confirmed in `_loadPage`.
- **C1 — stale detector census.** The diagnosis says the file's
  three `GestureDetector`s are tap/long-press only. The §3 glass
  fix (commit `fbb9a21d`, after the diagnosis) added
  `onHorizontalDrag` handlers to the glass bar's `GestureDetector`
  (bar drag). The logo/title/search-pill detectors remain
  tap/long-press only. The bar drag is a separate, self-contained
  recognizer and is untouched by this fix; Stack hit-test order
  keeps bar drags out of the grid swipe observer (see below).
- **C2 — stale line numbers.** The diagnosis cites the bar
  detector at `:1544`; it moved with the §3 additions. The
  `_selectedTab` writer lines (`:171`/`:306`/`:671`) still match
  exactly. No claim about the defect itself was overturned.

## Implementation

`flutter_module/lib/b11_home_page.dart` only (+497/−119, most of
the churn reindentation from the `Listener` wrap and the
`_SwipeTranslate` wraps). No Pigeon/Java/manifest change; the v2
filter stack, the glass bar widget, `_onFabScroll`, `_onScroll`,
and `_loadPage` are byte-identical in the diff.

- **Imports.** Explicit `show` imports (module convention, cf.
  the Lab port): `dart:math as math`; `dart:ui` pointer-event
  types + `PointerDeviceKind` + `Offset` (added to the existing
  `ImageFilter` show list); `package:flutter/animation.dart`
  (`AnimationController`, `CurvedAnimation`, `Tween` —
  `Curves` already resolves via `material_ui`);
  `package:flutter/foundation.dart` (`ValueListenable`,
  `ValueNotifier`); `package:flutter/gestures.dart` (`Velocity`,
  `VelocityTracker`, `kTouchSlop`);
  `package:flutter/rendering.dart` (`RenderBox`, `RenderObject`,
  for the tab-bar rect measurement).
- **State.** `_B11HomePageState` gains
  `SingleTickerProviderStateMixin` (settle controller) and the
  Lab-mirror swipe fields (`_swipeOffset` notifier, settle
  controller + tween + decelerate animation, tracked-pointer id,
  down position, `VelocityTracker`, base offset, direction-lock
  flags, pending fling delta, `_swipeTab` sync index) plus
  `_tabBarKey` (gating) and `_tabScrollOffsets` (per-tab
  positions). Controller and notifier are disposed in `dispose()`.
- **Observer.** `_buildHome` wraps the `CustomScrollView` in a
  `Listener` (`HitTestBehavior.translucent`, so short/empty
  content swipes anywhere) with the four pointer handlers. The
  Listener never enters the gesture arena, so vertical scrolling,
  the banner `PageView` swipe, the continue/B13 horizontal rows,
  the chips-row scroll, and card taps are unaffected by
  construction. The glass bar and FABs are later Stack siblings,
  so Stack hit-test order keeps their gestures out of this
  observer entirely (a bar drag never reaches it; a grid drag
  passing over the bar keeps routing to the observer).
- **Gating** (`_swipeStartsInGrid`). A `GlobalKey` on the pinned
  tab bar's `SizedBox` + `RenderBox.localToGlobal` gives the bar's
  screen rect at pointer-down time; drags starting above the
  bar's bottom edge are ignored. The banner, continue row, B13
  sections, and chips row all sit above that edge in the sliver
  order, at every scroll offset. A missing or unlaid-out bar
  fails safe (no swipe). When there are no tabs the bar renders
  a shrink box, the key has no context, and no swipe is tracked.
- **Handlers** (after the bottom-bar handlers): `_hasTab`,
  `_onSwipePointerDown` (first pointer only, gating, stop the
  settle controller, capture the base offset, fresh
  `VelocityTracker`), `_onSwipePointerMove` (direction lock,
  then follow-the-finger with 0.35 end resistance and ±width
  clamp, writing only `_swipeOffset` — no `setState`, no grid
  rebuild), `_onSwipePointerUp` (fling gates, then
  `_settleSwipe`), `_onSwipePointerCancel` (bounce back),
  `_settleSwipe` / `_bounceSwipeBack` / `_animateSwipeOffset`
  (220ms slide-in from ±width after the switch, 180ms
  bounce-back, decelerate curve, tween retargeted per settle).
  A switch during a grid load queues exactly as a chip tap does
  (the grid's serial guard), so no special handling.
- **Transform placement (D1).** The grid is slivers in the shared
  scroll view, so the translation cannot wrap the scroll view
  (that would slide the pinned tab bar and the header with it).
  A `_SwipeTranslate` helper (`ValueListenableBuilder` +
  `Transform.translate`, cached child) wraps the tab-content
  boxes only: the two page-level empty states, the grid's three
  loading/error/empty states, the load-more indicator, and the
  three style item builders (list/oval/rect). The pinned tab bar
  and the header slivers stay fixed, as native (ViewPager content
  slides, AppBarLayout does not). The offset notifier is passed
  to `_TabVideoGrid` as a `ValueListenable<double>` constructor
  parameter; the grid only reads it.
- **Build-time tab sync (Lab D4).** If `_selectedTab` changed
  without the swipe settle (chip tap, dialog, snapshot clamp),
  `build()` drops a stale offset and stops the settle
  controller. The settle path syncs `_swipeTab` before the
  rebuild, so the slide-in is unaffected. No `setState` from
  `build()`.
- **Single switch path.** `_switchTab(int)` is now the only
  writer of `_selectedTab` besides the initializer and the
  `_load` clamp: chip taps (`onSelected: (_) =>
  _switchTab(index)`) and the type-filter dialog apply (which
  adds its filter-epoch bump in a second `setState`, same frame)
  route through it, as does the swipe settle. FAB flags reset
  exactly as a chip tap does today; a switch to the
  already-selected tab still resets the FAB flags but stores and
  restores nothing.

## Per-tab scroll restore + the load-more guard

`_switchTab` stores the outgoing tab's controller offset before
switching and restores the incoming tab's stored offset after
switching, keyed by tab identity (D2). The restore runs in a
post-frame callback after the switch rebuild, clamped to the
current `maxScrollExtent`. The load-more guard needs no new flag
or parameter — it is the existing serial-guard invariant:

1. The switch rebuild runs `_TabVideoGrid.didUpdateWidget`,
   which calls `_loadPage(1)` synchronously; `_loadPage` sets
   `_busy = true` inside a synchronously-executed `setState`
   callback (or `_busy` is already true with a pending reload
   queued). Either way `_busy` is true before the frame ends.
2. The restore `jumpTo` runs in a post-frame callback, i.e.
   strictly after step 1, so the grid's `_onScroll` load-more
   check (`_hasMore && !_busy`) cannot fire from the jump.
3. When the page-1 load lands, `_busy` clears with the offset
   already at the restored position; no further scroll event
   fires unless the user scrolls. If the new tab's content is
   shorter than the restored offset, the viewport auto-clamps
   and a load-more at the true bottom may fire — legitimate
   bottom behavior, not a spurious trigger.
4. The jump fires the page's `_onFabScroll` listener, which
   would flip the FAB flags to the scrolled state; the switch's
   FAB reset is therefore re-applied in the same post-frame
   callback, before the next frame renders, so no flicker is
   visible and `_lastScrollPixels` stays synced to the restored
   offset.

## Deviations

- **D1 — per-content transforms, not a scroll-view transform.**
  See "Transform placement" above. Translating the whole
  `CustomScrollView` would slide the pinned tab bar sideways
  during the gesture (native keeps it fixed) and would need a
  counter-transform with viewport edge-clipping artifacts in
  the bar band. The `_SwipeTranslate` wrappers move only the
  tab content; nine wrap sites, all mechanical.
- **D2 — offsets keyed by tab identity, not index.** The
  diagnosis says "per tab index"; identity keys (typeId +
  typeJson, the grid's own reload identity) survive resume and
  tab reorder and can never restore a stale offset across a
  site switch. A tab with no stored offset (first visit) leaves
  the shared controller alone — exactly today's chip-tap
  behavior, unchanged.
- **D3 — restore via post-frame jumpTo.** The diagnosis allows
  "restore before the tab's first frame, or suppress one
  load-more evaluation"; the post-frame jump uses the existing
  `_busy` invariant instead of a new suppression flag (see the
  guard argument above), and re-applies the FAB reset in the
  same callback.
- **D4 — build-time tab sync instead of touching the chip tap
  or dialog paths.** Mirrors Lab D4; the sync lives in `build()`
  with no `setState`.
- **D5 — first-pointer-only tracking.** Mirrors Lab D5.
- **D6 — slop is `kTouchSlop` (18dp), not Android's
  `scaledTouchSlop` (~8dp).** Mirrors Lab D2.
- **D7 — fling velocity gate kept at ±500 px/s** plus native's
  dominance and 50dp-displacement gates. Mirrors Lab D3.
- **D8 — displacement fallback requires the horizontal lock.**
  Mirrors Lab D1: a tap interrupting a settle animation cannot
  re-switch from the stale base offset.
- **D9 — slide-in content swap is async.** The new tab's page-1
  load lands after the switch; until then the grid shows the
  outgoing tab's items (existing chip-tap behavior — the grid
  never clears `_items` on switch). The 220ms slide-in animates
  whatever content is showing, so on a slow host load the
  outgoing items begin the slide and the incoming items complete
  it. The animation itself is unaffected.

## Verification evidence

- Code inspection against the Lab port (handler-for-handler
  structural parity; thresholds copied, not re-derived) and
  against the live tree (claims check above, each cited to file
  and line at HEAD `0a1a9841`).
- `git diff`: only `flutter_module/lib/b11_home_page.dart`
  changed (+497/−119); the v2 filter stack, the `_GlassBottomBar`
  widget, `_onFabScroll`, `_onScroll`, and `_loadPage` are
  absent from the diff except one comment mention; no
  `GestureDetector`/`PageView` added; no Dart/Pigeon/Java/
  manifest file other than this one changed; the protected
  `docs/b8-acceptance-159-diagnosis.md` is untouched;
  `git diff --check` clean.
- Import audit: every newly imported name is used (math,
  Offset, all five pointer-event types, AnimationController,
  CurvedAnimation, Tween, ValueListenable, ValueNotifier,
  Velocity, VelocityTracker, kTouchSlop, RenderBox,
  RenderObject); no unused-import risk. `Curves` resolves via
  `material_ui` as before.
- `_selectedTab` writers after the change: initializer, `_load`
  clamp, `_switchTab` only — chip, swipe, and dialog paths all
  funnel through the one helper.
- No local compile check is possible (no SDK on this machine);
  CI decides. Device acceptance is diagnosis checks S1–S4:
  slow drag and fling switch tabs both ways with chips + FAB
  reset; vertical scroll, banner/row/chips horizontal scrolls,
  and load-more unaffected; per-tab scroll restore; end
  resistance + bounce-back with no spurious load-more.

## Recovery anchor

- Objective: home tab swipe per the completion sentence above.
- Status: implemented, one task-guard session, one atomic commit
  (hash/tag recorded in the finish report). Not pushed.
- Files: `flutter_module/lib/b11_home_page.dart`,
  `docs/b8-home-swipe-fix.md`.
- Unverified: compile (CI), device behavior (S1–S4).
- Next action: push with the sibling #159-acceptance fixes and
  build; Wei runs S1–S4 on device.
