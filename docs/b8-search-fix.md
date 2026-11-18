# b8-search-fix: settings search empty index + row landing

## Objective

Settings search returns results and tapping any result (sub-page,
settings-home row, player, subtitle) lands correctly, verified by
code inspection against the index and the B7 snapshot. Fixes
diagnosis `docs/b8-acceptance-159-diagnosis.md` §4 (device report
"设置搜索失效了" on build #159). One `quick-fix` task-guard session,
one commit, no Pigeon change, no push (parent batches the push).

## Root causes (both code-proven, all diagnosis claims held)

### 1. Primary: wrong-class reflection → empty index, every build

`B5HostApiImpl.getSettingSearch()` reflected over the mobile-only
index with `indexClass.getField("titleRes")` etc. on
`SettingSearchIndex` — but the five fields (`titleRes`,
`subtitleRes`, `destRes`, `page`, `rowId`) are declared on its
nested class `SettingSearchIndex.Entry`
(`app/src/mobile/java/.../ui/fragment/SettingSearchIndex.java:17-29`);
`SettingSearchIndex` itself declares no fields. `Class.getField()`
searches only the class and its superclasses, so it threw
`NoSuchFieldException` on the first field, the `catch (Exception e)`
cleared the entries, and the search screen got an empty index. The
catch swallowed the exception silently, which is why this shipped
unnoticed. R8 is excluded: `app/proguard-rules.pro:38` keeps the
whole package. Search therefore never returned a result in any
Flutter build; it was simply unreachable before #159 fixed
navigation.

### 2. Secondary: settings-home-row landing dead two ways

For a main-page row (`page == -1`, `rowId` set), `openSearchEntry`
set the M15 host static and navigated to `/settings`:

- (3a) Same-route assertion no-op: the host `mRoute` is already
  `/settings` throughout a family visit (member navigation is
  Dart-internal), so `onNewIntent` + `assertRouteIfNeeded` no-op
  and Dart stays on the search member.
- (3b) One-shot gates always spent empty:
  `B7HostApiImpl.consumePendingRow()` ran once per impl instance,
  and Dart `_maybeHandlePendingRow` set `_pendingRowHandled = true`
  on the first load even when `pendingRowId` was empty. The
  settings home always loads before search is reachable, so both
  gates were consumed empty in every reachable sequence. Latent
  landmine: the old `consumePendingRow` early-returned without
  draining the static, so a stale row could fire on a much later
  settings-home load after a rebind constructed a fresh impl.

## Fix

### Reflection fix — `B5HostApiImpl.java`, `getSettingSearch` only

Resolve the nested class and read the five fields from it:

```java
Class<?> entryClass = Class.forName(
    "com.github.moyingshi.osc.tv.ui.fragment.SettingSearchIndex$Entry");
Field titleResField = entryClass.getField("titleRes");
// ... subtitleRes, destRes, page, rowId likewise
```

Reflection is kept (the index is mobile-only; the impl lives in the
main source set and cannot reference it at compile time — plan §4.8
fourth option). The catch-and-empty degradation is kept, but the
exception is now logged (`e.printStackTrace()`, the established
pattern in the other host impls) instead of swallowed silently.

### Row landing, Dart-side — `b5_personal_page.dart` + `b7_settings_home_page.dart`

`SettingSearchView._onEntryTap` now handles a non-empty `rowId`
without the host: it pops the family Navigator back to the first
route and then requests the row through `SettingsSearchRowHandoff`
(a static `ValueNotifier`-based holder in the b7 file). The
settings-home state listens from `initState` and performs the row
through the existing `_onRowTap`, exactly as if the row had been
tapped. Ordering is load-bearing: pop FIRST, request SECOND —
requesting before the pop would push the member above search and
the pop would discard it.

The holder is cleared on consume (`take()` reads-and-clears before
performing, so a row can never perform twice), and system-back out
of search requests nothing, so back performs nothing. `load()` also
drains the holder after the snapshot lands, covering a value that
arrived before the snapshot (or before this state mounted). Only
one landing path exists: the host `openSearchEntry` rowId branch
stays (Pigeon-public) but is unreachable from Dart; it is otherwise
untouched.

### M15 drain fix — `B7HostApiImpl.java`, `consumePendingRow` only

Drain-on-read: every snapshot build reads-and-clears the static
`sPendingRowId`, and the instance `pendingRowId` is set whenever the
drained value is non-empty. The once-per-instance gate
(`pendingRowConsumed`) is dropped, removing the stale-static
landmine. Defense-in-depth only — the Dart path no longer depends
on it. The deleted `SettingFragment.sPendingRow` reflective fallback
is NOT reintroduced. Dart `_maybeHandlePendingRow` is untouched
(legacy guard for the snapshot field).

### No change

The Dart filter, the `flutterAvailable` in-family routing, the
player (page 2) / subtitle (page 6) host paths, any Pigeon contract,
or any other file.

## rowId → B7 snapshot mapping (all 24 verified)

Every `rowId` literal emitted by `SettingSearchIndex.all()` was
checked against the `row("key", …)` literals in
`B7HostApiImpl` (site/general/account/feature/storage/about groups)
and the `_onRowTap` handling in `b7_settings_home_page.dart`
(member push when `row.member` is non-empty, else the `switch` on
`row.key`). All 24 match exactly; every key is handled.

| rowId (index) | B7 key | member | `_onRowTap` path |
|---|---|---|---|
| vod | vod | — | `openVodManage()` |
| live | live | — | `openLiveManage()` |
| wall | wall | — | `openWallManage()` |
| appearance | appearance | — | `openAppearance()` |
| widget | widget | enhance | push `/enhance` |
| player | player | player | push `/player` |
| danmaku | danmaku | danmaku | push `/danmaku` |
| subtitle | subtitle | — | `openSubtitle()` |
| ai | ai | ai | push `/ai` |
| ad | ad | ad | push `/ad` |
| account | account | tmdb | push `/tmdb` |
| personal | personal | personal | push `/personal` |
| local_source | local_source | — | `openLocalSource()` |
| lab | lab | — | `openLab()` |
| gitcloud | gitcloud | — | `openGitCloud()` |
| onekey | onekey | — | `openOneKeySync()` |
| drive | drive | — | `toggleDriveCheck()` |
| site_health | site_health | — | `openSiteHealth()` |
| cache | cache | — | `openCacheClear()` (+ cache poll) |
| backup | backup | — | `openBackup()` |
| restore | restore | — | `openRestore()` |
| doh | doh | — | `openDoh()` |
| incognito | incognito | — | `toggleIncognito()` |
| about | about | — | `openAbout()` |

Notes:

- `account` → the TMDB row (key `account`, title
  `R.string.setting_tmdb`, member `tmdb`); `widget` → the enhance
  row (key `widget`, title `R.string.setting_enhance`, member
  `enhance`). Both keys differ from their titles, as the diagnosis
  flagged; both verified against the snapshot builder.
- The B7 snapshot has one extra row, `download` (hidden group), with
  no index entry — it is simply unsearchable, matching native. Not
  a mismatch.
- `_findRow` returns null for an unknown key and `_drainHandoff`
  returns silently; with the table above verified, no emitted rowId
  can hit that path.

## Verification evidence (code inspection; no SDK on this machine)

- Reflection: `Entry` declares exactly the five public final fields
  the impl reads; `all()` returns `List<Entry>`; `getInt(entry)` /
  `get(entry)` now operate on fields belonging to the instance's
  class. The class name uses the JVM nested-class form
  `SettingSearchIndex$Entry`.
- Entry path intact: settings-home `_openSearch` does
  `pushNamed('/search')`; the family `Navigator` in
  `b3_settings_a_page.dart` maps `/search` to `SettingSearchView`.
  No host caller uses `/settings?member=search`, so the settings
  home always loads before search is reachable (diagnosis link 0).
- Sub-page coverage: `isFlutterDestination` returns true for pages
  3/4/5/7/8/9; `_memberFor` maps them to enhance/danmaku/personal/
  tmdb/ai/ad; all six exist as family routes in the shell's
  `onGenerateRoute`. Pages 2 and 6 go through the host:
  page 2 → `openHomeAt(2)` → `/settings?member=player`
  (`FlutterNav.homeRouteForPosition`); page 6 → `ROUTE_SUBTITLE`
  via the dedicated branch in `openSearchEntry`. The index emits
  no other page values (main rows are -1 with rowId; sub rows are
  2–9 with empty rowId), so the `rowId`-first branch in
  `_onEntryTap` cannot shadow a sub-page tap.
- No double-perform: the holder is cleared before performing; the
  listener and the `load()` drain both go through `take()`; the
  host rowId branch is unreachable from Dart. Repeat search → tap
  works because no one-shot gate remains on the Dart path.
- No Pigeon change: no `.g.dart` file, no pigeon input file, and no
  host method signature was touched. `openSearchEntry` keeps its
  signature and its rowId branch.
- Import cycle check: `b5_personal_page.dart` now imports
  `b7_settings_home_page.dart`; the b7 file imports only pigeon
  messages, widgets, and Flutter packages — no cycle. The
  `ValueNotifier`/`ValueListenable` import is an explicit `show`
  import from `package:flutter/foundation.dart` (same-declaration
  re-export through material_ui cannot collide, unlike the
  CI-#149 `Colors` case, which was two different declarations).
- Java: no remaining reference to `pendingRowConsumed`; the B5
  `getField` calls all target `entryClass`.
- Compile verification: none possible locally (no Java/Android/
  Flutter SDK); CI (`b8-c0.yml`) decides.

## Deviations from the diagnosis design

- D1 — Holder placement: the diagnosis suggested a holder "owned by
  the family shell `B3SettingsPage` state, checked via
  `didPopNext`/RouteObserver". The family Navigator has no
  `RouteObserver` (no `observers:` argument), so `didPopNext` is
  unavailable without editing the shell file, which is out of this
  task's scope. The holder is therefore a static in the b7 file
  (the consumer's file), and delivery is event-driven via
  `ValueNotifier` instead of visibility-driven. Semantics are the
  same: set on row tap, cleared on consume, back performs nothing.
- D2 — `load()` also drains the holder (in addition to the
  listener). This covers a value requested before the snapshot
  landed or before this state mounted; it is a no-op in the normal
  flow because the listener already cleared the value.

## Keyboard/IME

No code change. Acceptance item T5 (keyboard appears, typing
normal, IME search action dismisses the keyboard) is a device check
for the parent's delivery list; no code evidence of an IME defect
was found (diagnosis §4 link 4).

## Device acceptance (diagnosis §4 T1–T5)

- T1: settings home → search icon → type "播放" → matching rows
  appear; clear the query → hint text returns; gibberish → empty.
- T2: tap a sub-page result (e.g. danmaku) → member opens; back →
  search, query preserved.
- T3: tap a settings-home row result (e.g. 点播/壁纸/缓存) → lands on
  settings home and the row's dialog/action runs exactly once;
  repeat the search → tap flow a second time → still works.
- T4: tap a player-page result → player settings opens; tap a
  subtitle result → subtitle screen opens.
- T5: keyboard appears on entering search, typing is normal, IME
  search action dismisses the keyboard.

## Recovery anchor

- Objective: settings search returns results; every tap lands
  correctly. Branch `b8-flutter-rewrite`, base HEAD `cebf2c99`.
- Status: implemented, code-inspection verified, committed via
  task_guard (see commit below). No push. CI decides compilation.
- Files: `B5HostApiImpl.java`, `B7HostApiImpl.java`,
  `b5_personal_page.dart`, `b7_settings_home_page.dart`,
  `docs/b8-search-fix.md` (this file).
- Protected dirty path preserved untouched:
  `docs/b8-acceptance-159-diagnosis.md`.
- Next action: parent batches the push with the other §5 sessions
  and runs T1–T5 against the joint build.
