# b8-home-grid: inline video grid in the Flutter home tab

## Objective

Replace the B11 home tab placeholder (selected tab name + "这里什么都没有",
tap opens the B8 `/category` screen) with a real inline video grid: the
same list the B8 category screen shows for the selected tab, rendered
inside the home tab content area like the native VodFragment does. Wei
reported on build #51 that the tab area "加载不出来" — the grid was never
implemented; B12 (banner + continue watching) and B13 (JS widgets) own
only the AppBar blocks above the tabs.

## What changed (4 files, one commit)

| File | Change |
|---|---|
| `flutter_module/pigeons/b11_home_messages.dart` | One additive `@async` method `getTabVideos` + two new classes `B11TabVideos` / `B11TabVideoItem`. No existing method or field changed (two doc comments updated: `getHomeShell` no longer calls the tab content a placeholder, `openTabCategory` is now the folder drill-down action). |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B11HostApiImpl.java` | `getTabVideos` implementation reusing the B8 list path; shared serial guard with `getHomeShell`; B8-copied item flattening, image resolution, and grid geometry. |
| `flutter_module/lib/b11_home_page.dart` | `_buildTabContent` renders the new `_TabVideoGrid` (rect/list/oval, load-more, error/empty states); folder drill-down and detail-open reuse the B8 host chain; the dead `_openTabCategory` page method is removed. Bottom bar, top bar, B12/B13 widgets untouched. |
| `docs/b8-home-grid.md` | This file. |

No other batch's contract touched. No new Dart dependencies. No schema
changes. Mobile only. Generated Pigeon bindings are produced in CI
(`b8-c0.yml` already runs codegen for `b11_home_messages.dart`); no
generated file is committed.

## New Pigeon method

```dart
@async
B11TabVideos getTabVideos(String typeId, String typeJson, int page);
```

`B11TabVideos` mirrors `B8CategoryScreen` minus title and filters (the
home grid has neither): `siteKey`, `styleType`, `styleRatio`,
`columnCount`, `cardImageWidthPx`, `cardImageHeightPx`, `items`,
`hasMore`, `pageCount`, `nextPage`, `extendJson`, `errorTitle`,
`errorMessage`, `retryLabel`. `B11TabVideoItem` is a field-for-field
mirror of `B8CategoryItem` (id/name/year/remarks/site + visibility flags
+ folder/action + full image parts), so the shared `VodCard` renders
grid cards exactly like B8 category cards.

## Host reuse (B8 list path, verbatim)

`B11HostApiImpl.getTabVideos` copies `B8HostApiImpl.getCategoryPage`:

- `Class.objectFrom(typeJson)` parse with the same null guard; the
  effective filter map is always the Class filters' init values (the
  home grid sends no filter map, so every page uses the same map —
  exactly B8's first-load behavior and `FolderFragment.getExtend()`).
- Key is the home site key (`VodConfig.get().getHome().getKey()`),
  echoed in the response as `siteKey` so Dart can pass it to the B8
  open/search calls.
- One-shot `SiteViewModel` subscription: `init()`, `observeForever`,
  35s watchdog, observer removed on every path,
  `categoryContent(key, typeId, page, true, extend)`.
- Style resolution (`loaded.getVod().getStyle(baseStyle)`), `hasMore` /
  `nextPage` computation, and the error snapshot all mirror B8.
- `toVideoItem` / `resolvePic` / `splitHeaders` and the grid geometry
  (`categoryColumn` / `categorySpec` / `isWide` / `windowBounds` /
  `getCutout`) are deliberate copies of the B8 implementations,
  including the `toStringUrl()` Glide 5 call: batches share no host
  helper so each stays independently revertible. If either copy
  changes, the other must change with it.

One deliberate change to existing behavior: the serial guard is now
shared. `getHomeShell` and `getTabVideos` drive the same
activity-scoped ViewModel, so each refuses to start while the other is
in flight (`loading || loadingTab`); previously `getHomeShell` checked
only its own flag. Without this, a chip switch during a shell load
could clear the pending shell result via `init()` and wedge the shell
on its watchdog. Dart chains the loads (grid only after the shell
settles), so the guard is a safety net, not the normal path.

## Dart behavior

`_TabVideoGrid` (page-local, in `b11_home_page.dart`) mirrors the B8
list view state machine:

- Reload triggers: new tab (chip switch) or new shell load epoch
  (resume / refresh / site switch) → page 1. Loads are serialized with
  a pending-reload flag, and results for a stale tab/epoch are
  discarded, so a chip switch during a load can never leave another
  tab's list on screen.
- Styles: `rect` → shared `VodCard` grid (same paddings, spacing, and
  `VodCard.totalHeight` extent as B8); `list` → 40dp-thumb rows;
  `oval` → circular-image cells. The list/oval widgets are deliberate
  duplicates of the B8 page-local widgets with the B11 item type (the
  B8 page is another batch's frozen file; batches keep their own
  copies).
- Load-more: scroll within 200px of the end loads `nextPage`; a
  load-more failure keeps the list and shows the host error message in
  a SnackBar (B8 pattern); a thin progress bar shows at the bottom
  while loading more.
- States: initial load → spinner; page-1 host error → host
  title/message + retry button (reloads page 1); channel break with no
  snapshot → host empty text + refresh icon button (no label is
  hard-coded anywhere, per the B11 rule); empty result → host empty
  text (`widget_empty`, from the shell snapshot).
- Card tap: folder → `B11HostApi.openTabCategory(item.id, tab.typeJson)`
  (B8 drill-down semantics: parent Class JSON, only the typeId
  changes). Anything else → `B8HostApi.openCategoryItem(siteKey, id,
  name, rawPic, isFolder, isAction, action, -1, '', -1)` — the exact
  call the B8 page makes, so the host follows the native
  `TypeFragment.onItemClick` chain (action card, index-site search, or
  the detail page). Long-press → `B8HostApi.searchCategoryItem(name,
  -1, '', -1)` for non-folder, non-action items. This is the first Dart
  page to call another batch's HostApi; it is still host-mediated
  navigation (plan §4.4), and it avoids duplicating the open chain or
  adding a second B11 method.

## The /category screen is not stranded

The placeholder tap was one of two live entries to `/category`; both
remaining entries are intact:

- `FolderActivity.start` (native folder entries) still diverts to
  `/category` when the B8 flag is on.
- Folder cards in the new grid route to `/category` via the existing
  `openTabCategory` host method.

No extra "browse all" affordance was added: the grid itself is the
category list (same data, paginated), and filters remain available on
the `/category` screen via folder drill-down. If Wei wants a filters
entry from the home grid, that is a follow-up.

## Deviations

- D1 — Shared serial guard (see above): `getHomeShell` now also
  refuses while a grid load is in flight. Safe: Dart never overlaps
  the two loads in the normal flow, and a refused shell load leaves
  the previous shell on screen.
- D2 — List/oval row widgets duplicated from the B8 page instead of
  shared (batch revertibility; same precedent as the C2 history page's
  inline card copy).
- D3 — A load-more channel break is silent (list stays, retry by
  scrolling again). B8 shows a hard-coded message on this path; B11
  hard-codes no locale strings, and no host string exists for it.
- D4 — Grid geometry (px→dp) is computed at load time, like B8, so a
  rotation keeps the old column widths until the next reload (resume
  refetches the shell, which bumps the epoch and reloads the grid).
- D5 — The shell-level empty state (`emptyTitle`, shown when the home
  load produced no tabs and no list) is unchanged and still takes
  precedence over the grid.

## Acceptance checklist (device)

- [ ] Home tab area shows video posters + titles + remarks for the
      selected tab (no more "这里什么都没有" when the site returns a list).
- [ ] Switching category chips reloads the grid with the new tab's
      list (including a fast double-switch: no stale tab's list).
- [ ] Scrolling to the bottom loads the next page and appends items.
- [ ] Tapping a card opens the detail page (same as the native home /
      B8 category screen); tapping a folder card opens the /category
      screen for the folder; long-press opens search for the item name.
- [ ] A tab with no videos shows the host empty text; a failing site
      shows the host error + retry, and retry reloads.
- [ ] Rotation keeps the grid usable; returning from a detail page
      refreshes the grid; back from the home still lands on the native
      home (unchanged V1 behavior).
- [ ] List-style and oval-style sites render rows / circular cells
      (if a configured site uses those styles).

## Verification (no SDKs on this VM)

- Pigeon name parity checked by grep: `getTabVideos`,
  `B11TabVideos`, `B11TabVideoItem`, and every field name match
  character-by-character across the contract, the Java impl
  (Builder setters), and the Dart page. Pigeon preserves the Dart
  method name verbatim in Java (past lesson from C1).
- No other file references the removed placeholder: the only
  `_openTabCategory` page-method caller was the placeholder; the host
  `openTabCategory` method is still used (folder drill-down).
- Imports audited: every new import is used; no dead code left in the
  page (the removed method would otherwise trip `flutter analyze`).
- CI build + device acceptance are the real gates (Pigeon codegen
  runs in CI; no generated file is committed).

## Recovery anchor

- Objective: inline video grid in the Flutter home tab (this doc).
- Status: implemented, local commit only, NOT pushed. Parent pushes
  via the browser route after verification.
- Files: `flutter_module/pigeons/b11_home_messages.dart`,
  `flutter_module/lib/b11_home_page.dart`,
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B11HostApiImpl.java`,
  `docs/b8-home-grid.md`.
- Next action: parent verifies the commit, pushes, and waits for the
  CI build; then Wei runs the acceptance checklist above on device.
