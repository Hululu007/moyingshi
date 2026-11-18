# b8-glass-geometry: native bar geometry + bar-drag tab switch

## Objective

Wei reported on build #159 (third report): the glass bottom bar
"还是不能滑动" — no sliding effect. Diagnosis
(`docs/b8-acceptance-159-diagnosis.md` §3) found two code-certain
root causes, both fixed here in one file, no Pigeon/Java change:

- **G1 — starved backdrop (visual):** the Dart grid reserved the
  full 88dp glass slot as bottom padding, so content stopped 8dp
  clear of the bar and the v2 `BackdropFilter` lens sampled only
  static wallpaper. Native content slides under the bar's top 24dp.
- **G2 — missing bar drag (interaction):** the native bar is
  horizontally draggable (`DampedDragAnimation`); the Dart bar was
  tap/long-press only, with a jumping selection pill.

Completion sentence: home grid content visibly slides under the
glass bottom bar's top edge while scrolling (native geometry), and
dragging horizontally on the bar slides the selection pill and
switches tabs.

## Geometry derivation (verified against the live tree)

All coordinates are dp above the window/safe-area bottom edge; the
Dart bar `Positioned` and the grid `SafeArea` share the same bottom
safe inset, so relative geometry matches native on every device.

| Fact | Source | Value |
|---|---|---|
| Fragment container `layout_above` the nav view | `app/src/mobile/res/layout/activity_home.xml:11` | pager ends above nav |
| `BottomNavigationView` height | `activity_home.xml:16` | 56dp → pager ends 56dp up |
| `glassNav` ComposeView slot height | `activity_home.xml:27` | 88dp |
| Bar height / top edge 8dp below slot top | `GlassNavBar.kt` history (`BarTopInSlot`), cited in diagnosis §3 | bar top edge 80dp up |
| Bar/pager overlap | 80 − 56 | bar's top 24dp overlaps pager |
| Grid bottom padding + `clipToPadding=false` | `app/src/mobile/res/layout/fragment_type.xml:18,21` | 8dp |
| FAB anchor: `layout_anchor=@id/pager`, `anchorGravity=end\|bottom`, `layout_margin=16dp` | `app/src/mobile/res/layout/fragment_vod.xml` (`browse`/`link`/`top`) | FAB bottom 72dp up |

Consequences:

- Grid clearance = 56 + 8 = **64dp** (was 88dp; the error was
  exactly 32dp). At maximum scroll the last row's bottom 16dp sits
  under the bar's top edge, exactly as native.
- FAB bottom = 56 + 16 = **72dp** + safe inset (was 88 + 16).
- The v2 filter stack (matrix lens, dispersion fringe, saturation
  lift) is untouched: once moving content passes under the bar, the
  existing layers produce the misalignment cue the v2 doc designed
  for. The lens scale (`distortionDp / 600`) is unchanged in this
  commit — device-tuned follow-up only if acceptance finds the
  effect too weak.

## What changed (1 code file + this doc)

| File | Change |
|---|---|
| `flutter_module/lib/b11_home_page.dart` | G1 constants + call site + FAB anchor; G2 `_GlassBottomBar` → `StatefulWidget` with one sliding pill overlay + bar-drag recognizer. V2 filter stack untouched. |
| `docs/b8-glass-geometry-fix.md` | This file. |

### G1 — geometry

- New constants next to the bar geometry block: `_pagerBottom = 56`,
  `_nativeGridPaddingBottom = 8`,
  `_gridBottomClearance = _pagerBottom + _nativeGridPaddingBottom`
  (64), with a comment citing `activity_home.xml` /
  `fragment_type.xml` / `GlassNavBar.kt`. `_glassSlotHeight` was
  removed: both of its uses are gone, and an unused private
  constant fails `dart analyze` (CI step 13). The 88dp slot remains
  in comments as bar geometry.
- `_TabVideoGrid` call site: `bottomPadding: _gridBottomClearance`.
  The three scrollable sites (list/oval/rect) all read the shared
  `widget.bottomPadding` parameter, so this one call-site change
  covers all three — no per-site edits were needed (see D1).
- FAB `Positioned.bottom`: `_pagerBottom + _fabMargin + safe inset`
  (72dp + inset). The stale FAB comment ("pager ending at the top
  of the 88dp glass slot") is corrected. FAB visibility logic is
  untouched — only the anchor moved.
- The `_TabVideoGrid.bottomPadding` doc comment is corrected to
  the native geometry.

### G2 — bar drag + sliding pill

- `_GlassBottomBar` is now a `StatefulWidget`. The per-item pill
  backgrounds are gone; one pill overlay (same color
  `accent.withAlpha(38)`, same radius 16) is drawn behind the row,
  positioned in fractional tab-index units.
- Pill position is a `TweenAnimationBuilder<double>` target
  (`_pillTarget`): programmatic selection changes retarget it in
  `didUpdateWidget` and the pill animates over ~220ms, so
  bottom-bar taps and bar drags share one sliding pill. If the host
  snapshot does not confirm a drag-settle, the next
  `didUpdateWidget` retargets the pill back to the reported
  selection (self-healing).
- A horizontal-drag recognizer wraps the bar overlay:
  `onHorizontalDragStart` snapshots the finger x and the current
  pill position; updates move the pill with the finger
  (delta / tab stride), damped ×0.3 past either end; release
  settles on the nearest tab and fires the existing `onTap` for
  that tab when it changed; cancel settles back to the selection.
  Absolute finger positions (not accumulated deltas) drive the
  math, so a mid-flight animation or a re-layout cannot skew it.
- Tap/long-press composition (verified by framework arena
  semantics, not by test): the item `GestureDetector`s
  (`onTap`/`onLongPress`, `HitTestBehavior.opaque`) are unchanged,
  and the drag recognizer is their ancestor in the same hit-test
  path, so all three recognizers share one gesture arena — a press
  without movement past slop still taps, a hold still long-presses,
  movement past slop hands the gesture to the drag. This is the
  native `inspectDragGestures` disambiguation.
- The pill is wrapped in `IgnorePointer` (it never steals taps)
  and clipped to the capsule (`ClipRRect`), so end-overshoot never
  pokes outside the bar in the opaque path. The drag recognizer is
  self-contained inside the bar overlay: bar drags never reach the
  grid and grid drags never reach the bar recognizer, so the later
  §2 tab-swipe work needs no coordination with this change.
- The pill hides when no tab is selected and no drag is in flight
  (same as the old per-item rendering, which drew no pill then).

## Deliberate acceptance-criteria change (recorded)

The sticky-tabs/glass acceptance item "last row scrolls fully clear
of the capsule" (v1 doc `docs/b8-home-glass.md` item 4, and the old
`_TabVideoGrid.bottomPadding` comment) is **superseded**: native
parity means the last row's bottom 16dp is UNDER the bar at
maximum scroll, visible through the refraction. Taps in the bar
area go to the bar, not the grid (same as native; the `Positioned`
capsule only hit-tests on itself). The 16dp underlap strip is
visual-only and untappable where covered — native-identical, but a
behavior change from builds #59–#159. The parent should tell Wei
this is the native look, not a regression. The v1/v2 docs are left
as historical records; this doc owns the supersession.

## Deviations

- **D1** One call-site change, not three per-site edits: the list,
  oval, and rect scrollables share `widget.bottomPadding`, so the
  three sites at :1179/:1197/:1226 take the new value through the
  single constructor argument. Per-style resting arithmetic (the
  styles' own outer margins stack on top of the 64dp): list rests
  with 16dp under the bar, oval with 8dp (8 + 64 = 72 vs the 80dp
  bar top), rect flush with the bar top edge (16 + 64 = 80). The
  sliding effect — rows passing under the bar's 24dp strip — is
  present in all three styles during scrolling; only the maximum-
  scroll resting position differs, by the pre-existing style
  margins. No other scrollable changes, per the brief.
- **D2** Pill size is derived, not measured: width =
  `(cellWidth − 10)` clamped to 40–60dp, height 48dp (old per-item
  pill was ~50×49dp for 2-character labels). Color and radius are
  unchanged. With 6 items on a 390dp phone the pill is ~47dp wide,
  matching the old look; longer host labels would have widened the
  old per-item pill but leave this one fixed — cosmetic only, the
  pill is a background behind the label.
- **D3** "Spring" is `Curves.easeOutCubic` over 220ms, not a
  physics spring: Compose's default `spring()` is critically
  damped (damping ratio 1.0, no overshoot), and ease-out cubic is
  its closest cheap equivalent. No overshoot by design.
- **D4** `TweenAnimationBuilder` instead of an `AnimationController`:
  no ticker, no dispose, retargeting mid-flight is automatic, and
  the drag-follow case is a zero-duration retarget. The pill's
  current value is cached in a plain field by the builder (no
  `setState`) so a drag starting mid-slide does not jump.
- **D5** `_glassSlotHeight` removed (see G1 above). No other
  constant removed; `_glassBarFloat` / `_glassBarInsetX` /
  `_fabMargin` keep their uses.
- **D6** Drag-start position uses the cached animated value
  (`_pillCurrent`), not the animation target: if the user grabs
  the bar mid-slide, the pill continues from where it visibly is.

## Risks

- The 16dp underlap strip is untappable where the bar covers it —
  native-identical, but new since #59; see the acceptance-criteria
  change above.
- `BackdropFilter` cost: v2 doc D5 (two backdrop blurs per frame)
  stands; moving content under the bar adds no layers, but scroll
  smoothness remains an acceptance item.
- The drag/tap/long-press arena behavior is framework-guaranteed
  but untested on device until L3; if the drag recognizer ever
  starves taps, the fix is to scope it (e.g. only past a larger
  slop), not to remove the pill animation.
- Flat-color wallpaper: the under-bar strip now shows moving cards,
  so the effect no longer depends on wallpaper detail — this
  resolves the v1 "flat wallpaper looks static" complaint as a
  side effect.

## Device acceptance check (diagnosis §3, L1–L4)

- L1: scroll the home grid — cards visibly slide under the bar's
  top edge, blurred/magnified/misaligned against the content
  outside the bar; the bar no longer looks like a static pill.
  Flat-color wallpaper: same effect (cards, not wallpaper, carry
  it).
- L2: maximum scroll — last row's bottom ~16dp under the bar
  (list style), still readable through the glass; FABs sit 16dp
  above the pager bottom (visibly lower than in #159).
- L3: drag horizontally ON the bar — the pill follows the finger,
  release settles on the nearest tab and switches; tap and live
  long-press still work; drag past the ends resists and bounces
  back.
- L4: glass off → opaque capsule, unchanged; scrolling stays
  smooth.

## Verification (no SDKs on this VM)

- Re-read the full modified widget after editing: opaque path and
  glass path return the same trees as before except for the shared
  `barContent`; the v2 filter stack (lens matrix, dispersion
  fringe, saturation lift, washes, highlight) is byte-identical in
  the diff; the only `screen`/`onTap`/`onLongPress` reference
  changes are the `widget.` qualifications required by the
  StatefulWidget conversion.
- Grep: zero remaining `_glassSlotHeight` references; zero bare
  `onTap(`/`onLongPress(` calls in the bar; all new symbols
  (`_pillTarget`, `_pillCurrent`, `_dragging`, `_dragStartX`,
  `_dragStartIndex`, `_visibleItems`, `_selectedIndex`,
  `_pagerBottom`, `_gridBottomClearance`,
  `_nativeGridPaddingBottom`, `TweenAnimationBuilder`) referenced;
  `_GlassBottomBar` has exactly one construction site.
- Bracket balance over the modified file: `{}`/`()`/`[]` all
  balanced. `git diff --stat` limited to the 2 scope files; the
  protected pre-existing dirty path
  (`docs/b8-acceptance-159-diagnosis.md`) untouched.
- Diagnosis-doc claim check: every §3 claim used here held
  (geometry numbers, FAB anchoring, drag absence, jumping pill,
  cited line numbers). One refinement is recorded as D1 above.
- Full verification is the CI build + device acceptance (parent
  pushes; this commit is local-only).

## Recovery anchor

- Objective: native bar geometry (content slides under the bar)
  + bar-drag tab switching with one sliding pill; see Acceptance
  check L1–L4.
- Status: implemented, local commit only (no push).
- Files: `flutter_module/lib/b11_home_page.dart`,
  `docs/b8-glass-geometry-fix.md`.
- Next action: parent pushes, CI builds, then device acceptance
  against L1–L4; tell Wei the 16dp underlap is the native look.
