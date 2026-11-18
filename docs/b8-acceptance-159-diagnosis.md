# b8-acceptance-159-diagnosis: four device reports on build #159

## Context

Build #159 (prerelease `b8-c0-159-cebf2c9`, head `cebf2c99`) is the
first build in which bottom-bar navigation no longer exits the app
(see `docs/b8-crash-singletop-fix.md`), so Wei could finally exercise
the Flutter screens. On 2026-11-18 20:26 he reported four problems:

1. Back from ANY screen goes straight to the desktop
   ("不管什么界面返回都是直接到桌面").
2. Category-tab swipe no longer switches tabs, on home AND in Lab
   ("分类标签左右滑动不能切换了，实验室里也不能").
3. The glass bottom bar still has no sliding effect
   ("玻璃底栏还是不能滑动") — the third report, after 2026-11-17 23:45
   and 2026-11-18 10:32.
4. Settings search is broken ("设置搜索失效了").

This document is assessment-only: every claim cites file + line /
symbol, verified against branch `b8-flutter-rewrite` at HEAD
`cebf2c99`. Deleted native code was read from git history
(`git show <parent>:<path>`), never from memory. No production code
was changed. Where the code cannot decide between two causes, the
section says so and names the single device observation that
disambiguates. There is no Java/Android/Flutter SDK on this machine;
CI is the compile check.

Summary of root causes (one line each):

1. **Back:** there is no cross-screen back stack anywhere — the Dart
   root navigator is reset to depth 1 on every host navigation, the
   host keeps no route history, and every root-back signal finishes
   the singleTop activity.
2. **Tab swipe:** home never had a swipe path (tap-only chips, no
   `PageView`, no drag handler); Lab has a swipe handler that is
   effectively dead (velocity-only threshold + gesture-arena loss to
   the vertical list, no displacement fallback).
3. **Glass bar:** the grid reserves the full 88dp slot, so content
   never paints under the bar and the `BackdropFilter` samples only
   the static wallpaper (native content passes under the bar's top
   24dp); the native drag-on-the-bar tab switch was also never ported.
4. **Settings search:** `B5HostApiImpl.getSettingSearch()` reads the
   index fields from the wrong class by reflection
   (`SettingSearchIndex` instead of its nested `Entry`), so the
   exception handler silently returns an empty index — search never
   returned a result in any Flutter build; the settings-home-row
   landing is additionally dead (same-route assertion no-op + a
   one-shot consumed flag that is always spent empty).

---

## 1. Back from any screen goes to the desktop

### Symptom

System back (and the AppBar back arrow where present) from any
Flutter screen lands on the launcher instead of the previous screen.

### Evidence

**The Dart root stack is always depth 1.** The host drives every
cross-screen navigation through the relaunch channel, and
`_showRoute` in `flutter_module/lib/main.dart` resets the stack:

```dart
navigator.pushNamedAndRemoveUntil(route, (_) => false);
```

with a no-op when the route equals `_currentTopRoute`. No host
navigation ever pushes onto the root navigator, so there is no Dart
history to pop back through. The only root-stack push in the module
is B9 → B10 (`flutter_module/lib/b9_search_page.dart:222`,
`Navigator.of(context).push(...)` — "pure Dart, no route", per the
`main.dart` comment).

**Every root-back signal ends in `finish()`.** Two Pigeon
`closeScreen` implementations exist, and both finish:

- `C1HostApiImpl.closeScreen()`
  (`app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1HostApiImpl.java:145-161`):
  task root + `PlaybackService` running → `Util.moveToBackground`,
  otherwise `activity.finish()`. Its own comment calls this "the
  root-back signal".
- `B3HostApiImpl.closeScreen()`
  (`app/src/main/java/com/github/moyingshi/osc/tv/flutter/B3HostApiImpl.java:661-663`):
  unconditional `activity.finish()` — no background gate at all, so
  settings-family back always finishes, even during playback.

Ten pages funnel system back into `closeScreen` through
`PopScope(canPop: false)` + `onPopInvokedWithResult`:
`c2_history_page.dart:128`, `b2_keep_page.dart:177`,
`b3_settings_a_page.dart:85`, `b8_category_page.dart:75`,
`b9_search_page.dart:317`, `b14_following_page.dart:383`,
`b15_local_page.dart:139`, `b16_report_page.dart:160`,
`b21_lab_page.dart:65`, `b22_drama_page.dart:138` and `:368`.

**Seven screens have no `PopScope` at all**, so system back falls
through to the host default. Grep over `flutter_module/lib/` finds
no `PopScope`/`closeScreen` in `b11_home_page.dart` (home),
`b10_collect_page.dart`, `b17_person_page.dart`,
`b18_widget_page.dart`, `b19_plugin_page.dart`,
`b20_hk_page.dart`; `c1_about_page.dart:173` and
`c1_subtitle_page.dart:142-143` call `closeScreen()` only from their
AppBar button. `C1ScreenActivity.onBackPressed()`
(`app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java:908-913`)
delegates to `flutterFragment.onBackPressed()` and otherwise calls
`super.onBackPressed()` — there is no hierarchy logic on the host
side either; an unhandled pop finishes the activity. On top of that,
`b17_person_page.dart:578`, `b18_widget_page.dart:314`,
`b19_plugin_page.dart:631`, `b20_hk_page.dart:427` use a bare
`AppBar(...)` with no explicit `leading:` — with a depth-1 root
stack `canPop` is false, so no back arrow is even shown; system back
is the only way out, and it exits.

**In-family back is designed to work** (and must be confirmed on
device — see "Undecided" below):

- Settings family: `b3_settings_a_page.dart:70-90` (`_handleBack`
  pops the inner `Navigator` at line 91 when `canPop()`, else
  `closeScreen`); member navigation pushes onto that inner
  Navigator (`b7_settings_home_page.dart:139` `pushNamed('/search')`,
  `:144` `pushNamed('/${row.member}')`).
- Lab: `b21_lab_page.dart:49-73`, same pattern (list → detail).
- Category drill-down: `b8_category_page.dart:59-93` (inner
  `Navigator`, `_onBack` pops or closes); drill pushes at line 318.
- Local files: `b15_local_page.dart:102-112` (`_onBack` goes to the
  parent directory, else `closeScreen`).
- B9 → B10: `b10_collect_page.dart:287-296` (`_onBack` pops the root
  navigator when `canPop()`, else `closeScreen`).

**What native did** (read from history, not memory).
`HomeActivity.onBackInvoked()` (deleted in phase 4a;
`git show 25c8df20^:app/src/mobile/java/com/github/moyingshi/osc/tv/ui/activity/HomeActivity.java:731-746`)
was a hierarchy, not an exit:

1. `mChrome.consumeBack()` (chrome restore);
2. nav vod item hidden → `setNavigation()`;
3. enhance → vod (`change(0)`);
4. settings sub-page visible (positions 2–10,
   `isSettingSubPageVisible`, same file ~:599) → settings home
   (`change(1)`);
5. settings visible → vod home (`change(0)`);
6. `mManager.canBack(0)` → background if playing, else
   `super.onBackInvoked()` (finish).

`FragmentStateManager.change()` (deleted in phase 4b;
`git show c245dde6^:app/src/mobile/java/com/github/moyingshi/osc/tv/ui/custom/FragmentStateManager.java`)
used hide/show with **no** `addToBackStack` — bottom-nav switches
were switches, never history. `VodFragment.canBack()`
(`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/VodFragment.java:976-986`)
consumed WebView back, chrome restore, and folder drill-down
(`getFragment().goBack()`) before allowing exit; the default
`BaseFragment.canBack()`
(`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/base/BaseFragment.java:50`)
returns true. Screens that were separate Activities natively (Lab,
Search, History, Keep, Folder, WidgetBrowse) finished back to
`HomeActivity` underneath — i.e. back always revealed the launching
screen, and only the home itself exited (or backgrounded while
playing).

**The pending `/settings` root-back question is answered by this
complaint**: return to the previous screen; home-back backgrounds.
Native parity for the home itself is "background if playing, else
finish" — the design below backgrounds unconditionally (see R1).

### Root cause (single, falsifiable)

There is no cross-screen back stack anywhere in the Flutter
replacement: every host navigation resets the Dart root stack to
depth 1, the host keeps no route history (`mRoute` /
`sAssertedRoute` in `C1ScreenActivity.java:213-257` track only the
current route), and every root-back path — both `closeScreen`
implementations and the unhandled-pop fallback — finishes the
singleTop activity, emptying the task. Falsifiable prediction: back
from a B8 drill-down level 2 still lands on level 1 (in-family back
intact), while back from any depth-1 screen lands on the desktop.

### Fix design

Back handling becomes three ordered layers; each layer only runs
when the one above it declines:

1. **Dart-internal back (unchanged, keep working):** inner-Navigator
   pop (B3/B21/B8), folder-up (B15), root-stack pop (B9→B10), dialog
   dismiss. No change except §1.4 below.
2. **Host route history (new):** `C1ScreenActivity` keeps a route
   history stack (source of truth on the host, because the host owns
   `mRoute` and the assertion channel). Classification of host
   navigations:
   - *Switch* (replaces the stack with a single entry): bottom-bar
     navigation and `goHome`/`openHomeAt` — mirrors native
     `change()` (no back stack). `FlutterNav.openHomeAt`
     (`app/src/main/java/com/github/moyingshi/osc/tv/flutter/FlutterNav.java:160-164`)
     and `goHome` are the switch entry points.
   - *Push* (appends, de-duplicated against the top): cross-screen
     `C1ScreenActivity.start(...)` calls from a C1 caller — the 20
     sites fixed in `docs/b8-crash-singletop-fix.md` (B11/B7/B4/B13/
     B21/C2/B5/AppBrandingActivity). These mirror native separate
     Activities stacked above home. The fix task must classify each
     of the 20 sites switch-vs-push; the default for a `start()` from
     a content screen is *push*.
   - Root-back pops the stack and asserts the previous route through
     the existing `assertRouteIfNeeded()` /
     `sendRouteToDart` channel (`C1ScreenActivity.java:757-763`).
     `pushNamedAndRemoveUntil` in Dart stays correct: the host stack
     is the history, the Dart stack stays depth 1 (except the
     B9→B10 Dart-internal push, which never enters host history and
     pops in Dart before the host is consulted — no desync).
   - Empty stack at `/home` → background (`Util.moveToBackground`,
     the existing closeScreen playing-branch behavior), never
     `finish()` — see R1.
3. **One host root-back handler (new):** e.g.
   `C1ScreenActivity.handleRootBack()`. Both `closeScreen`
   implementations (`C1HostApiImpl.java:145`, `B3HostApiImpl.java:661`)
   delegate to it — this also fixes the B3 unconditional-finish
   defect. `C1ScreenActivity.onBackPressed()` (`:908`) routes its
   fallback (fragment null, or any embedding path that bypasses
   Dart) to the same handler instead of `super.onBackPressed()`.
   No new Pigeon method is needed: `closeScreen` already is the
   Dart-to-host root-back signal (per its own comment).

**Dart-side changes (exact files):**

- Add `PopScope(canPop: false)` + a back handler to every
  PopScope-less page, funneling into `closeScreen` (the B2/C2
  pattern): `b11_home_page.dart` (home — handler is just
  `closeScreen`, which now backgrounds), `b17_person_page.dart`,
  `b18_widget_page.dart`, `b19_plugin_page.dart`,
  `b20_hk_page.dart`, `c1_about_page.dart`, `c1_subtitle_page.dart`.
  With `PopScope(canPop: false)` on every page, Dart always consumes
  the pop, so the embedding's unhandled-pop path is never the live
  path — this sidesteps the framework-version question in R2.
- `b10_collect_page.dart`: add `PopScope(canPop: false)` wired to
  the existing `_onBack` (`:287`, which already pops when the root
  navigator `canPop()`). Without it, system back on B10 relies on
  the default root pop; with it, the B9→B10 pop is explicit and the
  initial-route case closes through the host handler.
- `b17_person_page.dart`, `b18_widget_page.dart`,
  `b19_plugin_page.dart`, `b20_hk_page.dart`: add an explicit
  `leading:` back button calling the same handler (today these pages
  show no back arrow at all — see Evidence).
- `b3_settings_a_page.dart` `_handleBack` (`:70-78`): add the
  direct-entry case. When the host asserts `/settings?member=X`
  directly, the inner Navigator starts at depth 1 on member X, so
  back currently goes to `closeScreen` and skips the settings home
  (native: sub-page → settings home → home). Fix: track the current
  member (e.g. a `NavigatorObserver` or a state field set from
  `onGenerateRoute`); if the inner stack cannot pop and the current
  member is not `home`, replace the inner stack with `/home`
  (`pushNamedAndRemoveUntil('/home', (_) => false)` on the inner
  navigator — replace, not push, so the next back does not return to
  the member); only the `home` member falls through to `closeScreen`.

**Host/Dart sync hazards the fix must handle:**

- H1 — same-route assertions are no-ops on both sides
  (`assertRouteIfNeeded` no-op at `C1ScreenActivity.java:757-763`;
  `_showRoute` no-op in `main.dart`). A history pop to a route
  string equal to the current `mRoute` must still reset Dart state
  (e.g. popping from a settings member back to `/settings` when
  `mRoute` is already `/settings` because the member was reached by
  Dart-internal push). The pop path must force the assertion (bypass
  the equality guard) or send a distinct "reset to route" signal;
  the fix task picks one and the device check C4 below covers it.
  Note this is the same no-op that breaks the settings-search row
  landing (§4, link 2) — the two fixes share this mechanism and must
  agree on it.
- H2 — Dart-internal member navigation is invisible to the host:
  `mRoute` stays `/settings` while Dart moves between family
  members. The host history must therefore store route strings, and
  the B3 member fallback above (not the host) owns member-level
  back. The host must never try to track members.
- H3 — the G2 relaunch mechanism (`docs/b8-c1-relaunch-mechanism.md`,
  ownership token `mRouteToken` / `sRouteOwnerToken`,
  `C1ScreenActivity.java:256-257,567-570`): history pops go through
  the same assertion channel, so the token discipline is unchanged;
  a pop assertion must update `sAssertedRoute` exactly as a forward
  assertion does.
- H4 — native Activities stacked above C1 (player, dialogs-as-
  activities, WebHome shell): back from those is native and
  untouched; the history handler only runs when C1 is the foreground
  activity. The existing closeScreen comment's "stacked above
  another activity" case (finish to reveal what is underneath) is
  preserved by checking `isTaskRoot()` first: non-root → `finish()`
  as today; root → history/background.

### Risks

- R1 — **home-back backgrounds unconditionally** (per the brief),
  deviating from native (finish when idle). Consequence: the process
  stays resident and the M2 teardown in `C1ScreenActivity.onDestroy`
  (Server stop, config clear) does not run on home-back; that is
  desirable here (it avoids the teardown → cold-start empty-home
  cycle from `docs/b8-crash-singletop-fix.md` §B), but there is no
  longer an in-app "exit" path. If Wei wants a real exit, it needs a
  separate affordance (out of scope for this fix; record the
  decision).
- R2 — **which embedding callback is the live back path** on Flutter
  3.50 cannot be verified from this repo (no Flutter SDK): whether
  `C1ScreenActivity.onBackPressed()` (`:908`) still fires, or the
  `FlutterFragment` dispatches through `OnBackPressedDispatcher`.
  The design makes this a safety net only (every page consumes the
  pop in Dart), but the fix task must verify the fallback hook on
  device: if back on a PopScope-less page still exits after the fix,
  the fallback hook missed the live path.
- R3 — predictive-back gesture preview: `PopScope(canPop: false)`
  vetoes the route pop, so the system predictive-back animation will
  not preview on Flutter screens (same as today for the ten pages
  that already use it). Cosmetic; accepted.
- R4 — the 20 `start()` sites need individual switch-vs-push
  classification; misclassifying a bottom-bar-equivalent switch as a
  push would make back walk through bottom-nav history (native never
  did). The fix task must list the classification in its doc.

### Device acceptance check

- C1: settings home → member row → back → settings home (in-family
  intact); back again → home screen (not desktop).
- C2: home → bottom bar → settings → back → home; back on home →
  app backgrounds (recents shows it, relaunch restores instantly,
  no empty-home flash).
- C3: home → Lab → back → home; home → search → result → category →
  back → search input; back → home.
- C4: direct member entry (search result → player settings) → back
  → settings home → back → home (covers H1 + the B3 fallback).
- C5: plugins / Hk manager / person / widgets pages show a back
  arrow; arrow and system back both return home.
- C6: during playback, back on home backgrounds (existing behavior
  preserved); back from the player (native) still reveals the
  launching Flutter screen.
- C7: B9 → B10 → back → B9 input (root pop preserved); back on B9 →
  home.

### Undecided from code

Whether in-family back (B3/B21/B8 inner pop) actually works today:
the code pattern is sound, but Wei's "any screen" phrasing does not
exclude an in-family break. **Disambiguating observation:** settings
home → member row → system back. Lands on settings home → only
cross-screen back is broken (this section's root cause). Lands on
desktop → the in-family path is broken too, and the fix task must
diagnose the `PopScope` + inner-`Navigator` interaction first.

---

## 2. Category-tab swipe no longer switches tabs (home and Lab)

### Symptom

Horizontal swipe over the content area does not switch category tabs
on the home screen, nor groups in Lab. Tapping chips still works.

### Evidence — home

**No swipe path exists at all in the Flutter home.** The tab bar is
`_buildTabBar` (`flutter_module/lib/b11_home_page.dart:644-681`): a
horizontal `ListView` of `ChoiceChip`s; `onSelected` only sets
`_selectedTab` (state at `:80`, set at `:671`) and resets FAB flags.
The only `PageView`s in the whole module are the B12 banner
(`flutter_module/lib/b12_home_block_a.dart:153`) and the B17 photo
viewer (`flutter_module/lib/b17_person_page.dart:709`) — neither is
a tab pager. Grep finds no `onHorizontalDrag`/`onPan` handler in
`b11_home_page.dart`; its three `GestureDetector`s (`:568`, `:599`,
`:1544`) are tap/long-press only (logo, title, search pill, bar
items). The tab content is a single `CustomScrollView` whose grid is
slivers (`docs/b8-home-sticky-tabs.md`): a horizontal drag over the
grid is unclaimed by any recognizer and does nothing.

**Native had a real pager.** `app/src/mobile/res/layout/fragment_vod.xml`
hosts a `CustomViewPager` (`@id/pager`, `appbar_scrolling_view_behavior`)
under the `typeBar` chip `RecyclerView`. `VodFragment.java:331-337`
registers a page-change listener (`onPageSelected` syncs the chip
selection and FAB visibility); `VodFragment.java:966`
(`onItemClick`) calls `mBinding.pager.setCurrentItem(position)`.
`CustomViewPager`
(`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/custom/CustomViewPager.java`)
is a plain `androidx.viewpager.widget.ViewPager` with only an
exception-safe touch intercept — swipe is enabled by default. So
natively, swiping the grid area switched tabs with the ViewPager's
follow-the-finger paging, and each tab kept its own `TypeFragment`
scroll position.

### Evidence — Lab

**Native Lab has an observe-don't-consume swipe.**
`LabActivity.java:166-171` attaches an `OnTouchListener` to the
package `RecyclerView` and the empty view that only observes
("只观察、不消费事件") and feeds a `GestureDetector`
(`GroupSwipeListener`, `:871-920`): direction lock on first
significant move (horizontal wins only if `|dx| > slop` and
`|dx| > |dy|`), follow-the-finger `translationX` with 0.35 edge
resistance at the ends, fling recorded but settled on release.
`settleSwipe()` (`:383-400`): fling first, else displacement over
`max(120dp, 1/4 screen width)`; over threshold, the new group slides
in once (~220ms) from the gesture direction; otherwise it bounces
back. Documented in `docs/b8-b21-lab.md:22` ("手机独占手势：内容区左右
滑动切分组").

**Dart Lab has a handler, but a weak one.**
`flutter_module/lib/b21_lab_page.dart:897-911`: a `GestureDetector`
wrapping `body` (the `ListView.builder` at `:787-789`, inside a
`RefreshIndicator`) with **only** `onHorizontalDragEnd`, switching
on `velocity < -500` / `> 500` px/s. `_switchGroup` (`:273-279`) is
correct (bounds-checked, `setState`). `docs/b8-b21-lab.md:111`
claims "滑动切分组（速度 ±500 px/s 近似，原生 fling/位移阈值的简化）".
Two mechanisms make it effectively dead for real gestures:

1. **Velocity-only, no displacement fallback.** A slow deliberate
   drag (e.g. 300px in 1s ≈ 300 px/s) never reaches ±500 px/s, so it
   never switches — exactly the gesture native handles via the
   displacement threshold. Only a fast fling can switch.
2. **Gesture-arena competition with the vertical list.** The
   `HorizontalDragGestureRecognizer` competes with the `ListView`'s
   vertical drag recognizer; any initial vertical drift beyond touch
   slop lets the vertical recognizer win and the horizontal drag
   never starts. Native never competes — its listener observes all
   touches and settles on release regardless of vertical drift
   (direction lock decides, not the arena).

The chips row itself (`b21_lab_page.dart:875-893`, horizontal
`ListView` of `ChoiceChip`s) scrolls horizontally by design and
must keep doing so; the swipe area is the content below it, matching
native (gesture on recycler + empty view only).

### Root causes (single per surface, falsifiable)

- **Home:** swipe-to-switch was never implemented — there is no
  pager and no horizontal-drag handler; tabs are tap-only by
  construction. Falsifiable: no code path in `b11_home_page.dart`
  changes `_selectedTab` except chip taps (`:671`), the type-filter
  dialog application (`:306`), and the bounds clamp (`:171`).
- **Lab:** the handler exists but its contract is a strict subset of
  native (velocity-only, arena-competing); slow or slightly diagonal
  swipes — the normal way users swipe — cannot switch. Falsifiable
  prediction: a fast, perfectly flat fling across the package list
  still switches groups, while a slow 400px drag does not.

### Fix design

**Shared gesture semantics (both surfaces, ported from native
`LabActivity`):** fling-priority settlement + displacement fallback
(`max(120dp, 1/4 screen width)`), direction lock, follow-the-finger
translation while dragging, 0.35 resistance at the ends, ~220ms
slide-in on settle, bounce-back under threshold. Direction mapping
is the same on both surfaces (native ViewPager and native Lab
agree): swipe left → next tab/group (index +1), swipe right →
previous (index −1).

**Lab (`flutter_module/lib/b21_lab_page.dart` only):** replace the
`GestureDetector(onHorizontalDragEnd:)` at `:897-911` with a
`Listener`-based observer (pointer down/move/up + `VelocityTracker`)
that mirrors native's observe-don't-consume `OnTouchListener`:
`Listener` does not enter the gesture arena, so vertical scrolling,
pull-to-refresh, and card taps are unaffected by construction.
Track the drag from pointer-down, lock direction on first
significant move, apply `translationX` to the list content while
horizontal-locked, settle on pointer-up per the shared semantics.
Keep `_switchGroup` (`:273-279`) and the chips row untouched. The
empty/loading states (`body` at `:726-789`) are inside the same
`Listener` subtree, matching native (gesture on recycler + empty
view).

**Home (`flutter_module/lib/b11_home_page.dart` only):** do NOT
introduce a `PageView` for tab content — it would break the
sticky-tabs contract (`docs/b8-home-sticky-tabs.md`: one
`CustomScrollView`, pinned `SliverPersistentHeader`, one page-owned
`ScrollController` driving grid load-more, FAB scroll listener).
Instead, apply the same `Listener`-based swipe detection to the grid
area, with two home-specific constraints:

1. **Gating:** the detector must only claim drags that start in the
   grid area (below the pinned tab bar), never drags starting on the
   B12 banner `PageView`, the continue-watching row, the B13
   horizontal lists, or the chips row (which scrolls horizontally).
   Implementation: a `GlobalKey` on the pinned tab bar +
   `RenderBox.localToGlobal` gives the tab bar's screen rect; ignore
   gestures whose pointer-down is above the tab bar's bottom edge.
   (The tab bar is pinned, so its rect is stable once scrolled; at
   scroll offset 0 its natural position is measured the same way.)
2. **Per-tab scroll positions:** today the shared controller keeps
   its offset across tab switches
   (`docs/b8-home-sticky-tabs.md` D3 — "unchanged from before").
   Native kept one scroll position per tab (one `TypeFragment` per
   pager page). The fix stores the controller offset per tab index
   on switch and restores it (jump, no animation) when returning to
   a tab, clamped to the new tab's `maxScrollExtent`. The load-more
   trigger (`pixels >= maxScrollExtent - 200`) must not fire
   spuriously from a restore jump — restore before the tab's first
   frame, or suppress one load-more evaluation after restore. The
   fix task decides and the device check S4 covers it.
3. **Transition:** a slide transition on switch (new grid slides in
   from the swipe direction, ~220ms, native Lab settle style). The
   grid content is slivers in a shared scroll view, so the transition
   must animate the incoming tab's content, not the scroll view
   itself — e.g. an `AnimatedSwitcher`-style slide wrapper around
   the tab-content slivers, or a temporary `Transform.translate`
   driven by the drag. The follow-the-finger phase translates the
   outgoing content by the drag delta (clamped, 0.35 resistance at
   the ends, mirroring native Lab).
4. **FAB/scroll interplay:** a tab switch resets the FAB flags
   exactly as a chip tap does today (`_selectedTab` setter block at
   `:671-677`: `_fabTopVisible = false`, `_fabsScrolledAway =
   false`). The swipe must not disturb the `_onFabScroll` listener
   (`:122`) or the grid load-more listener.

### Risks

- The `Listener` approach observes pointer events that the vertical
  scrollable also uses; it must never call `setState` per pointer
  move in a way that rebuilds the list (translate via an
  `AnimationController`/`ValueNotifier` + `Transform`, not full
  rebuilds), or scrolling will jank.
- Gating by tab-bar rect needs the rect measured after layout; a
  stale rect (e.g. first frame, rotation) must fail safe (no swipe),
  never misroute a banner drag.
- Per-tab offset restore interacts with the grid-fix serial guard
  (`docs/b8-home-grid.md` D1: shell reload refused while a grid load
  is in flight) — a switch during a load must queue or refuse
  exactly as a chip tap does today.

### Device acceptance check

- S1 (home): slow 400px left drag on the grid → next tab; slow right
  drag → previous tab; fast fling → same; chips update; FAB flags
  reset (top FAB hides).
- S2 (home): vertical scrolling, banner swipe, continue-watching
  horizontal scroll, chips-row horizontal scroll all unaffected;
  load-more still triggers at the bottom of each tab.
- S3 (home): scroll tab A down, switch to B, switch back → A's
  scroll position is restored.
- S4 (home): switch tabs at the ends (first/last) → resistance, no
  switch, bounce-back; no spurious load-more.
- S5 (Lab): slow drag and fling both switch groups in both
  directions; vertical scroll, pull-to-refresh, and card taps
  unaffected; end-of-list resistance + bounce-back.

### Undecided from code

Whether Lab swipe is *totally* dead or merely *effectively* dead:
the code proves slow/diagonal swipes fail, but a fast flat fling
should still fire `onHorizontalDragEnd`. **Disambiguating
observation:** one fast, perfectly horizontal fling across the Lab
package list. Switches → the arena/threshold design is the whole
cause (this section). Does not switch → something else also blocks
the detector (placement/pointer routing), and the fix task must
investigate before implementing.

---

## 3. Glass bottom bar still has no sliding effect

### Symptom

The floating glass capsule looks static while scrolling: no visible
liquid refraction/sliding as content moves. Third report; the v1 bar
(`docs/b8-home-glass.md`) and the v2 layered-filter bar
(`docs/b8-home-glass-v2.md`) did not resolve it.

### Evidence

**Read first:** `docs/b8-home-glass.md` (v1: wallpaper + capsule +
88dp grid clearance) and `docs/b8-home-glass-v2.md` (v2: layered
filters — matrix lens, dispersion fringe, saturation lift — over the
same layout). The v2 doc's acceptance item 1 assumes "cards sliding
under the bottom bar". That assumption is false in the Dart layout,
and the v2 filters were therefore starved of moving content.

**The Dart layout guarantees nothing slides under the bar.**
`_buildHomeStack` (`flutter_module/lib/b11_home_page.dart:389-420`)
is a `Stack`: full-screen wallpaper, `SafeArea` content, the capsule
`Positioned` at 24dp sides / 16dp float above the bottom safe inset.
The grid reserves the full slot: `bottomPadding: _glassSlotHeight`
(`:743`, `_glassSlotHeight = 88`, `:1424`), applied inside all three
grid styles (`:1179`, `:1197`, `:1226`). At maximum scroll the last
row's bottom edge sits 88dp above the scroll content's bottom edge,
while the bar's top edge is 80dp (16 float + 64 height) above the
same edge — an 8dp gap. Content never paints under the bar, so the
`BackdropFilter` in `_GlassBottomBar` samples only the static
wallpaper (blurred 3dp, unmoving). A lens, however good, cannot show
a sliding effect over a static backdrop. The FAB comment at
`:408-410` states the underlying assumption explicitly: "the pager
ending at the top of the 88dp glass slot". That assumption is wrong
(see native geometry below).

**Native content DOES pass under the bar.** `activity_home.xml`
(still in the tree at `app/src/mobile/res/layout/activity_home.xml`):

- the fragment container is `match_parent` but
  `layout_above="@id/navigation"` — the 56dp `BottomNavigationView`
  — so the pager ends **56dp** above the window bottom, not 88dp;
- the `glassNav` `ComposeView` is an 88dp slot aligned to the parent
  bottom; the bar inside it is 64dp tall, 24dp side insets, 16dp
  float, top edge 8dp below the slot top
  (`git show c245dde6^:app/src/mobile/java/com/github/moyingshi/osc/tv/glass/GlassNavBar.kt`,
  `BarInsetX` / `BarTopInSlot` + geometry comment).

So the bar's top edge is 80dp above the window bottom while the
pager runs to 56dp — **the bar's top 24dp overlaps the pager**.
The grid's bottom padding is only 8dp
(`app/src/mobile/res/layout/fragment_type.xml`: `paddingBottom="8dp"`,
`clipToPadding="false"`), so at maximum scroll the last row's bottom
16dp sits under the bar, and during scrolling rows continuously pass
under the bar's top strip. The backdrop capture exists precisely for
this: `GlassBackdropCapture` (`GlassNavBar.kt`) captures the root
layout's bottom strip = slot height + `EXTRA_ABOVE_PX` (160px)
above it, with the comment "so the blur/lens effects have real
content to sample at the bar's top edge". When glass is on, the
plain navigation is `GONE` and the glass slot is `VISIBLE`
(`HomeActivity.applyBottomBar`, pre-4a `:405-422`); below the pager
the bar refracts the wallpaper, above it the live grid.

The geometry error is exactly 32dp (88 − 56): the Dart grid stops
32dp too high, and the Dart FABs sit 32dp too high for the same
reason (FAB `bottom: _glassSlotHeight + _fabMargin`, `:411-416`,
vs native FABs anchored 16dp above the *pager* bottom, i.e. 72dp
above the window bottom, `fragment_vod.xml` `browse`/`link`/`top`).

**The native bar is also draggable — never ported.**
`FloatingNavBar.kt` (deleted in phase 4b) wires
`.then(dampedDragAnimation.modifier)` at line 474;
`DampedDragAnimation.kt:56` exposes a `pointerInput` modifier
(`inspectDragGestures`) whose `onDrag` moves the selection value
with the finger and whose `onDragStopped` settles on the nearest
slot and fires `onTabSelected` when the tab changed
(`FloatingNavBar.kt` `onDrag`/`onDragStopped` blocks). Dragging
horizontally ON the bar slides the selection pill and switches tabs.
The Dart `_GlassBottomBar` items are tap/long-press only
(`b11_home_page.dart:1544`, `GestureDetector(onTap:, onLongPress:)`)
and the pill is a plain `Container` (`:1532`) with no drag-follow
and no slide animation.

**What the native "sliding effect" actually was** (answering the
brief's (a)–(d)): (a) content sliding under the bar — yes, the top
24dp strip, refracted live by the Kyant backdrop (blur + lens +
dispersion, `FloatingNavBar.kt` `drawBackdrop` effects block);
(b) bar hide/show on scroll — no such behavior exists in
`GlassNavBar.kt` / `FloatingNavBar.kt` (no scroll listener, no
show/hide animation; the bar is always visible when glass is on);
(c) distortion shifting with scroll offset — no, the lens is static
per frame over a moving backdrop; the *backdrop* moves, the filter
does not; (d) drag-on-bar tab switching — yes, as above, a separate
interaction from the visual effect.

### Root causes (two, both code-certain)

- **G1 — starved backdrop (the visual effect):** the 88dp grid
  clearance keeps all content 8dp clear of the bar, so the
  `BackdropFilter` sees only static wallpaper. The v2 lens/
  dispersion/saturation layers are fine; they have nothing moving to
  refract. The 32dp geometry error (pager ends at 56dp, not 88dp) is
  the single falsifiable claim: measure the last row's bottom edge
  at maximum scroll against the bar's top edge.
- **G2 — missing bar drag (the interaction):** no horizontal-drag
  handling on `_GlassBottomBar`; taps only. The native
  `DampedDragAnimation` port does not exist in Dart.

Which of G1/G2 Wei means by "不能滑动" cannot be decided from the
report text alone ("滑动特效" in the first two reports points at G1;
"不能滑动" in this one could be either). Both are certainly absent;
the fix implements both. If only one can be done first, do G1 — it
is the effect the first two reports named.

### Fix design

**G1 — geometry (in `flutter_module/lib/b11_home_page.dart` only,
no Pigeon/Java change):**

- Reduce the grid bottom clearance from the 88dp slot to the native
  pager geometry: content must extend to 56dp above the content
  bottom edge, plus the native 8dp grid padding — i.e.
  `bottomPadding` becomes 64dp in the same coordinate space as the
  current 88dp (the safe-inset handling stays exactly as the bar's
  `Positioned` does it, so the relative geometry matches native on
  every device). At maximum scroll the last row's bottom 16dp sits
  under the bar's top edge, exactly as native. The three application
  sites (`:1179`, `:1197`, `:1226`) take the new constant; no other
  scrollable changes.
- Re-anchor the FABs to the pager bottom: `bottom` becomes
  56dp + 16dp margin + safe inset (today 88dp + 16dp), matching
  native `fragment_vod.xml` anchoring. This is part of the same
  geometry fix, not a separate task.
- Keep the v2 filter stack untouched. Once moving content passes
  under the bar, the existing matrix lens + dispersion fringe +
  saturation lift produce the misalignment cue the v2 doc designed
  for. Strengthening the lens scale (`distortionDp / 600`, v2 doc
  D1) is a device-tuned follow-up only if acceptance finds the
  effect too weak — do not change it in the same commit.
- **Acceptance-criteria change (deliberate, record it):** the
  sticky-tabs/glass acceptance item "last row scrolls fully clear of
  the capsule" is superseded — native parity means the last row's
  bottom 16dp is *under* the bar at maximum scroll (visible through
  the refraction, matching native). Taps in the bar area go to the
  bar, not the grid — same as native (the bar overlay hit-tests on
  itself; the Dart `Positioned` capsule "only hit-tests on the bar
  itself", `:386-388`). The underlapped strip is visual only.

**G2 — bar drag (same file, same session — see §5):** port the
`DampedDragAnimation` semantics in simplified form onto
`_GlassBottomBar`:

- A horizontal-drag recognizer on the bar: drag updates move the
  selection pill with the finger (damped: pill offset follows drag
  delta, clamped to the bar's tab stride, with end resistance);
  release settles on the nearest tab and fires the existing
  `_onBottomBarTap` for that tab when it changed. Reuse the existing
  tap/long-press paths: a press without drag past slop still taps,
  and the live-item long-press still fires (drag and long-press
  disambiguate by movement, as native `inspectDragGestures` does).
- Pill slide animation on programmatic selection (today the pill
  jumps — plain `Container`, `:1532`): animate the pill to the
  selected tab (~220ms spring, native `LaunchedEffect` +
  `animateToValue` behavior), so bottom-bar taps and bar drags share
  one sliding pill.
- The drag must not leak into the grid swipe (§2): the bar is a
  separate `Positioned` overlay above the scroll view, so bar drags
  never reach the grid `Listener`, and grid drags starting above the
  bar never reach the bar recognizer. No coordination code needed.

### Risks

- The 16dp underlap means the last row's bottom strip is visible
  only through refraction and untappable where the bar covers it —
  native-identical, but a behavior change from builds #59–#159; Wei
  may read it as a regression if not told it is the native look.
- `BackdropFilter` cost: the v2 doc's D5 (two backdrop blurs per
  frame) stands; moving content under the bar does not add layers,
  but scroll smoothness remains an acceptance item.
- If the wallpaper is a flat color, the under-bar strip still shows
  moving cards (the effect no longer depends on wallpaper detail) —
  this resolves the v1 "flat wallpaper looks static" complaint as a
  side effect.

### Device acceptance check

- L1: scroll the home grid — cards visibly slide under the bar's top
  edge, blurred/magnified/misaligned against the content outside the
  bar; the bar no longer looks like a static pill. Flat-color
  wallpaper: same effect (cards, not wallpaper, carry it).
- L2: maximum scroll — last row's bottom ~16dp under the bar, still
  readable through the glass; FABs sit 16dp above the pager bottom
  (visibly lower than in #159).
- L3: drag horizontally ON the bar — the pill follows the finger,
  release settles on the nearest tab and switches; tap and live
  long-press still work; drag past the ends resists and bounces
  back.
- L4: glass off → opaque capsule, unchanged; scrolling stays smooth.

---

## 4. Settings search is broken

### Symptom

Settings search ("设置搜索") does not work. The code proves the
failure mode is "no results, ever" plus "tapping a settings-home row
result does nothing". Typing itself is not implicated by any code
evidence (see "Undecided" below).

### Evidence — the chain, link by link

**Link 0 — entry (intact).** The search screen is a settings-family
member: the settings-home search affordance calls `_openSearch`
(`flutter_module/lib/b7_settings_home_page.dart:257`), which does
`Navigator.of(context).pushNamed('/search')` (`:138-139`); the
family `Navigator` maps `/search` to `SettingSearchView`
(`flutter_module/lib/b3_settings_a_page.dart:124-126`). No host
caller uses the `/settings?member=search` route (grep over
`app/src/` finds none), so the Dart push is the only entry — and the
settings home therefore always loads before any search interaction
(matters for link 3).

**Link 1 — index snapshot (BROKEN, root cause).**
`B5HostApiImpl.getSettingSearch()`
(`app/src/main/java/com/github/moyingshi/osc/tv/flutter/B5HostApiImpl.java:392-430`)
reflects over the mobile-only index:

```java
Class<?> indexClass = Class.forName("...ui.fragment.SettingSearchIndex");
Method allMethod = indexClass.getMethod("all");
...
Field titleResField = indexClass.getField("titleRes");   // throws
```

The fields `titleRes`, `subtitleRes`, `destRes`, `page`, `rowId`
are declared on the **nested** class `SettingSearchIndex.Entry`
(`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SettingSearchIndex.java:17-29`),
not on `SettingSearchIndex` itself (which declares no fields).
`Class.getField()` searches only the class and its superclasses, so
it throws `NoSuchFieldException` on the first field. The `catch
(Exception e)` block then runs `entries.clear()` and returns a
screen with an empty index — the failure is silent by design ("the
search screen degrades to an empty index"). The nested-`Entry` shape
predates B5: the pre-phase-4a file
(`git show 25c8df20^:.../SettingSearchIndex.java`) has the identical
nested class (4a only inlined the `SettingFragment.ROW_*` literals
into `rowId` strings). **Search therefore never returned a result
in any Flutter build**; it was simply unreachable before #159 fixed
navigation. R8/obfuscation is excluded as a cause: `app/proguard-rules.pro:38`
has the blanket `-keep class com.github.moyingshi.osc.tv.** { *; }`,
so class, method, and field names survive — the bug is purely the
wrong class in `getField` (and `getInt(entry)` would additionally
fail with `IllegalArgumentException`, since the field, even if
found, would not belong to the `Entry` instance).

**Link 2 — Dart filtering and tap routing (intact, given a
non-empty index).** `SettingSearchView`
(`flutter_module/lib/b5_personal_page.dart:265+`): an autofocus
`TextField` in the `AppBar` title (conventional `TextEditingController`
+ `FocusNode`, `TextInputAction.search`), a 300ms debounce, and
`_applyFilter` mirroring `SettingSearchIndex.search()` (trim,
lowercase, substring on title + subtitle; empty query matches
nothing). `_onEntryTap` routes `flutterAvailable` entries (pages
3/4/5/7/8/9, per `isFlutterDestination` in `B5HostApiImpl`) to an
in-family `pushNamed('/$member')` via `_memberFor` — back returns to
search — and all other entries to the host `openSearchEntry(page,
rowId)`. With a fixed index, sub-page taps and the player/subtitle
host paths work; only settings-home-row taps are broken (link 3).

**Link 3 — settings-home-row landing (BROKEN, two independent
defects).** For a main-page row (`page == -1`, `rowId` set),
`openSearchEntry` (`B5HostApiImpl.java:454-483`) calls
`B7HostApiImpl.setPendingRowId(rowId)` and then
`openHomeAt(1)` → `FlutterNav.homeRouteForPosition(1)` returns
`ROUTE_SETTINGS` (`/settings`,
`FlutterNav.java:103-130`) → `C1ScreenActivity.start(activity,
"/settings")`.

- **Defect 3a — same-route assertion no-op.** The host `mRoute` is
  already `/settings` throughout the family visit (member navigation
  is Dart-internal and invisible to the host), so `onNewIntent` +
  `assertRouteIfNeeded` (`C1ScreenActivity.java:757-763,881-901`)
  no-op (same route + same ownership token), and Dart `_showRoute`
  (`main.dart`) would no-op too. The screen visibly does nothing:
  Dart stays on the search member, the pending row is never
  consumed. This is the same no-op mechanism flagged as hazard H1
  in §1 — the two fixes must agree on the forced-assertion
  mechanism. (When the current host route differs, e.g. the user
  entered via `/settings?member=tmdb`, the assertion fires — but
  defect 3b still drops the row.)
- **Defect 3b — the M15 one-shot gates are always spent empty.**
  `B7HostApiImpl.consumePendingRow()` (`:156-164`) runs once per
  impl *instance* (`pendingRowConsumed` flag): the first
  `getSettingsHome()` call reads-and-clears the static
  `sPendingRowId`. Dart `_maybeHandlePendingRow`
  (`flutter_module/lib/b7_settings_home_page.dart:91-101`) sets
  `_pendingRowHandled = true` on the first `load()` even when
  `pendingRowId` is empty. Because the settings home always loads
  before search is reachable (link 0), both gates are consumed with
  an empty row in every reachable sequence; a later
  `setPendingRowId` is ignored by the host instance forever, and a
  later non-empty snapshot would be ignored by the Dart state.
  The pending-row deep link is dead in all reachable sequences.
  Latent landmine: `consumePendingRow` early-returns *without*
  draining the static, so a stale `sPendingRowId` survives until a
  *fresh* impl instance consumes it — and `registerHostApis`
  (`C1ScreenActivity.java:619`) constructs new impl instances
  (`new B7HostApiImpl(this)`) on every rebind, so a stale row can
  fire unexpectedly on a much later settings-home load.

**Link 4 — keyboard/IME (no defect evidenced).** The `TextField`
setup is conventional (autofocus, focus node, no custom input
handling, no `windowSoftInputMode` override in the Flutter path
visible in this code). The known-unverified B5 keyboard/IME watch
item is not confirmed or denied by code; see "Undecided" below.

### Root causes

- **Primary (no results):** wrong-class reflection in
  `getSettingSearch` → silent empty index. Single falsifiable
  claim: with the index class fixed to `SettingSearchIndex$Entry`,
  typing a common word returns the matching rows.
- **Secondary (row taps dead):** the host round-trip for settings-
  home rows cannot reach Dart (3a) and cannot survive the one-shot
  gates (3b). The host round-trip itself is unnecessary: the row
  handlers already live in Dart (`_onRowTap`,
  `b7_settings_home_page.dart:143+`).

### Fix design

1. **Reflection fix** (`B5HostApiImpl.java`, `getSettingSearch`
   only): resolve the nested class —
   `Class.forName("...ui.fragment.SettingSearchIndex$Entry")` — and
   read the five fields from it. Keep reflection (the index is
   mobile-only; the impl lives in the main source set and cannot
   reference it at compile time — plan §4.8 fourth option). Keep the
   catch-and-empty degradation, but log the exception (today it is
   swallowed silently, which is why this shipped unnoticed). No
   Pigeon change, no Dart change for this link.
2. **Row landing, Dart-side (primary):** in `SettingSearchView.
   _onEntryTap` (`b5_personal_page.dart`), handle non-empty `rowId`
   without the host: pop the family Navigator back to the settings
   home (pop-until-first-route) and perform the row through a
   Dart-side pending-row handoff — e.g. a small static holder owned
   by the family shell (`B3SettingsPage` state) that
   `SettingsHomeView` checks when it becomes visible again
   (`didPopNext` via `RouteObserver`, or a post-pop callback from
   the search view). The row is performed by the existing `_onRowTap`
   (`b7_settings_home_page.dart:143+`), which already handles every
   `rowId` the index emits (`vod`, `live`, `wall`, `appearance`,
   `player`, `danmaku`, `subtitle`, `ai`, `ad`, `account`,
   `personal`, `local_source`, `lab`, `gitcloud`, `onekey`, `drive`,
   `site_health`, `cache`, `backup`, `restore`, `doh`, `incognito`,
   `about` — the fix task must verify each `rowId` literal in
   `SettingSearchIndex.all()` has a matching `row.key` in the B7
   snapshot, especially `account` → TMDB row and `widget` →
   enhance row, whose keys differ from their titles).
   The host `openSearchEntry` rowId branch then becomes unreachable
   from Dart; keep it (Pigeon-public) but fix defect 3b as
   defense-in-depth — see (3).
3. **M15 drain fix** (`B7HostApiImpl.java`, `consumePendingRow`
   only): drain the static on *every* call (always read-and-clear),
   and set the instance `pendingRowId` whenever the drained value is
   non-empty — drop the once-per-instance gate in favor of
   drain-on-read. This removes the stale-static landmine even though
   the Dart path no longer depends on it. Do not reintroduce the
   deleted `SettingFragment.sPendingRow` reflective fallback (gone
   with that class in phase 4a; the doc comment at `:153-155`
   already records this).
4. **No change** to the Dart filter, the `flutterAvailable`
   in-family routing, or the player (2) / subtitle (6) host paths —
   all intact once the index is non-empty. No Pigeon change anywhere
   in this section.

### Risks

- The `rowId` → `row.key` mapping must be verified row by row; a
  mismatch silently no-ops in `_findRow`
  (`b7_settings_home_page.dart:103-111`, returns null → return).
  The fix task must enumerate all 24 main-row literals against the
  B7 snapshot keys in its doc.
- If the Dart-side handoff uses a static holder, it must be cleared
  on consume (same landmine class as 3b) and must survive the
  search view being popped by system back (back → home without a
  row must not perform anything).
- The forced-assertion mechanism shared with §1/H1: if the fix task
  instead keeps the host round-trip for rows, it must solve 3a and
  3b together; the Dart-side design above avoids both. Do not do
  both (two landing paths for one row = double-perform risk).

### Device acceptance check

- T1: settings home → search icon → type "播放" → matching rows
  appear (titles + destinations); clear the query → hint text
  returns; gibberish query → empty text.
- T2: tap a sub-page result (e.g. a danmaku row) → the member page
  opens; back → search, query preserved.
- T3: tap a settings-home row result (e.g. 点播/壁纸/缓存) → lands on
  settings home and the row's dialog/action runs exactly once;
  repeat the search → tap flow a second time → still works (gates
  not spent).
- T4: tap a player-page result → player settings opens; tap a
  subtitle result → subtitle screen opens.
- T5: keyboard appears on entering search, typing is normal, IME
  search action dismisses the keyboard (covers the watch item).

### Undecided from code

Whether typing/IME works: no code evidence of an IME defect, but
keyboard behavior is device-only. **Disambiguating observation:**
open search and type. Keyboard appears and text enters, but results
stay empty → this section's root cause (link 1) is the whole story.
Keyboard never appears / cannot type → a separate IME investigation
(FlutterFragment input connection / `windowSoftInputMode`) is
needed, independent of the reflection fix.

---

## 5. Fix sequencing

Principles: one task-guard session = one atomic logical change
(repo `AGENTS.md` §6); a session's acceptance must be testable on
device without another session's work; shared files are sequenced,
not parallelized; the forced-assertion mechanism (§1 H1 / §4 3a) is
decided once, in the §1 session, and §4's Dart-side design avoids
depending on it.

Recommended order:

1. **§4 settings search — one `quick-fix` session, one commit.**
   Files: `B5HostApiImpl.java` (reflection), `B7HostApiImpl.java`
   (M15 drain), `b5_personal_page.dart` (Dart row landing),
   `b7_settings_home_page.dart` (handoff check) + the task doc.
   Why one session: the three links share one acceptance flow
   (search → results → tap → row performs); splitting would leave
   intermediate states that cannot be device-tested (results
   without landing, or landing without results). Smallest fix,
   unblocks a whole feature. No Pigeon change.
2. **§1 back hierarchy — one `standard` session, one commit.**
   Files: `C1ScreenActivity.java` (history stack + root-back
   handler + `onBackPressed` fallback), `C1HostApiImpl.java` +
   `B3HostApiImpl.java` (delegate `closeScreen`), Dart `PopScope` +
   back buttons on the 7 PopScope-less pages, `b3_settings_a_page.dart`
   (member fallback), `b10_collect_page.dart` (`PopScope`) + the
   task doc including the 20-site switch-vs-push classification.
   Why separate from §4: cross-cutting, largest blast radius, and
   its `closeScreen` semantic change must be verified on every
   screen. Why after §4: §4's Dart row landing uses family pops
   that §1 must not break; landing §4 first gives §1 a working
   search flow to regression-test back behavior against. §1 also
   decides the forced-assertion mechanism (H1) for any future
   same-route navigation need.
3. **§3 glass (G1 geometry + G2 bar drag) — one `standard` session,
   one commit.** Files: `flutter_module/lib/b11_home_page.dart` +
   the task doc. Why one session: both changes live in the one file,
   share the "bar" acceptance pass, and the geometry change alters
   the bar's surroundings that the drag port is tested against.
   Why after §1: no dependency, but §1 is more user-visible; §3's
   geometry change also re-anchors the FABs, which §2's home swipe
   must then respect — so §3 lands before the home swipe.
4. **§2 Lab swipe — one `quick-fix` session, one commit.** Files:
   `flutter_module/lib/b21_lab_page.dart` + the task doc. Fully
   independent of §1/§3/§4 (one file, one screen); placed here only
   so its device acceptance can ride the same build as §2-home if
   the parent batches pushes. Could be done any time after §4.
5. **§2 home swipe — one `standard` session, one commit.** Files:
   `flutter_module/lib/b11_home_page.dart` + the task doc. Why
   separate from §3 despite the shared file: different risk (gesture
   arena + per-tab offsets + load-more interplay vs layout), and
   different acceptance; sequential sessions on the same file avoid
   a combined diff that cannot be bisected. Why after §3: the swipe
   gating (tab-bar rect) and the per-tab offset math are written
   against the final §3 geometry (64dp clearance, FAB anchor).

Push/build strategy (parent's call, per standing authorization):
push the sessions' commits together and build once, as Wei prefers
("做完先不编，等别的弄好了一起推" pattern from the B8 program) — but
keep the commits separate so a bad session can be reverted without
losing the others. Each session's device acceptance list (§1 C1–C7,
§2 S1–S5, §3 L1–L4, §4 T1–T5) must be run against the joint build;
the §1 "undecided" observation (in-family back check) and the §2
Lab fling observation should be gathered first, before the fix
sessions start, because they can change §1/§2 scope.

Decisions this diagnosis closes (record in memory when the fixes
land):

- The pending `/settings` root-back question: return to the previous
  screen (home in the hierarchy); home-back backgrounds. (§1.)
- The glass acceptance item "last row scrolls fully clear of the
  capsule" is superseded by native parity: the last row's bottom
  16dp sits under the bar. (§3.)
- The settings-search row landing moves from the host M15 static to
  a Dart-side handoff; the host static remains only as a drained
  fallback. (§4.)

## Recovery anchor

- Objective: diagnose Wei's four #159 reports from code evidence;
  fix designs only, no production edits.
- Branch/HEAD: `b8-flutter-rewrite`, `cebf2c99`, worktree clean
  except this file.
- Status: diagnosis complete. All four root causes are code-proven
  except the two flagged device observations (§1 in-family back,
  §2 Lab flat fling) and the IME non-finding (§4), which need one
  device check each before/with the fix sessions.
- Files: `docs/b8-acceptance-159-diagnosis.md` (this file). No
  commit made (assessment-only).
- Next action: parent gathers the three device observations (or
  proceeds — the designs stand without them, with the flagged
  contingencies), then spawns the §5 fix sessions in order.
