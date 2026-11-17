# b8-home-sticky-tabs: sticky category tab bar on the Flutter home

## Objective

Make the Flutter home scroll like the native home: one unified scroll
where the toolbar, search pill, banner, continue-watching row and JS
sections scroll away, the category tab bar （电影/电视剧/动漫/综艺/短剧）
rises to the top and pins there, and the grid keeps scrolling under it.
Scrolling back to the top expands the header back into place. Wei
reported on 2026-11-18 (screenshot): "这个界面分类不能上滑到顶部" — the
chips never reach the top.

## Root cause

`_buildHome` in `flutter_module/lib/b11_home_page.dart` was a fixed
`Column`: top bar, search pill, B12 banner, B12 continue-watching, B13
widgets, and the tab bar `_buildTabBar` all sat still, and only the
`Expanded` tab content (`_TabVideoGrid`, with its own `ScrollController`)
scrolled. The chips were therefore fixed mid-screen forever, and a
vertical swipe starting anywhere above the grid scrolled nothing at all.

## Native reference

`app/src/mobile/res/layout/fragment_vod.xml`:

- `AppBarLayout` children `toolbar`, `searchBar`, banner/continue
  blocks and `widgetSections` all carry
  `app:layout_scrollFlags="scroll"` — they scroll away.
- `typeBar` （分类栏） deliberately has NO scroll flags (comment at
  line 163): "它是 AppBar 最后一个子项，其相对 Y 正好等于全部 scroll
  子项高度之和（总收起范围），收到底时正好停在屏幕顶部钉住不动；下滑回
  顶部时随 AppBar 展开回到原位。背景始终透明，露出壁纸。"
- The pager (grid) uses `appbar_scrolling_view_behavior`, so the
  scrolling view is offset below the AppBar and its content never
  paints under the tab bar.

## Design choice

Full sliver-ization with one `CustomScrollView`, chosen over
`NestedScrollView`:

- One scroll position for the whole page, so the load-more trigger
  (`pixels >= maxScrollExtent - 200`) keeps its exact current math —
  the header height is included in both `pixels` and `maxScrollExtent`,
  so "near the bottom" means the same thing as before.
- `NestedScrollView` would have required the grid to give up its own
  `ScrollController` for the coordinator-injected one (an explicit
  controller breaks header collapsing) plus the usual inner/outer
  coordination edge cases. The single-scrollable design has no
  coordination at all.
- B12/B13 widgets needed no changes: all three are fixed-height with
  only horizontal inner scrollables (banner `PageView`, horizontal
  `ListView`s), so they drop into `SliverToBoxAdapter` untouched.

## What changed (2 files, one commit)

| File | Change |
|---|---|
| `flutter_module/lib/b11_home_page.dart` | `_buildHome` is now a `CustomScrollView` (own `ScrollController`, owned by the page state): header blocks as `SliverToBoxAdapter`s, the tab bar as a pinned `SliverPersistentHeader` (`_StickyTabBarDelegate`, fixed 44dp), tab content as slivers. `_TabVideoGrid` takes the parent controller (listens, never disposes), renders `SliverFillRemaining` for loading/error/empty states and `SliverPadding` + `SliverList`/`SliverGrid` for the three styles, with the load-more indicator as a trailing sliver in a `SliverMainAxisGroup`. Item builders, keys, paddings, grid delegates, load-more serialization, folder drill-down and detail-open are byte-for-byte the old behavior. |
| `docs/b8-home-sticky-tabs.md` | This file. |

No Pigeon contract touched. No Java/Kotlin touched. No other Dart file
touched. No new dependencies. Mobile only. The `_buildHomeStack`
`Stack` (wallpaper layer + `SafeArea` content + floating capsule) is
untouched; the 88dp glass-slot bottom padding is preserved in all
three grid styles via the same `EdgeInsets` in `SliverPadding`.

## Pinned header background (deviation D1)

Native keeps the tab bar background transparent because the scrolling
view is offset below the AppBar — nothing ever paints under it. In a
`CustomScrollView`, grid content DOES scroll under a pinned header, so
a transparent bar would let cards show through the chips. The delegate
therefore paints opaque only while content is underneath
(`overlapsContent`): the host wallpaper color
(`Color(screen.wallpaperColor)`). At rest (scroll offset 0) the bar
stays transparent and the wallpaper shows through, exactly like native.

Deviation: with a wallpaper *image* set, the pinned 44dp strip shows
the flat fallback color instead of the image region behind it. With a
flat-color wallpaper (or no custom wall) the strip matches the
background exactly. Accepted: the alternative (transparent bar) shows
moving grid cards behind the chips, which is worse.

## Other deviations

- D2: the load-more `LinearProgressIndicator` used to sit pinned below
  the grid area (outside the scrollable); it is now a trailing sliver
  after the grid's bottom padding, visible when scrolled to the very
  bottom. The user is at the bottom whenever load-more triggers, so
  this is not a visible behavior change in practice.
- D3: tab-switch scroll position is unchanged from before (the shared
  controller keeps its offset across tab switches, as the grid's own
  controller did). Switching tabs while pinned stays pinned with the
  new tab's page-1 list; native behavior on tab switch was not
  re-verified and is not claimed to match.

## Verification

No Java/Android/Flutter SDK exists on this VM, so no local compile was
possible. Static verification only:

- Full re-read of the changed file: all symbols referenced exist
  (`CustomScrollView`, `SliverToBoxAdapter`, `SliverPersistentHeader`,
  `SliverPersistentHeaderDelegate`, `SliverFillRemaining`,
  `SliverMainAxisGroup`, `SliverPadding`, `SliverList`, `SliverGrid`,
  `SliverChildBuilderDelegate` — all in `package:flutter/widgets.dart`,
  exported through `material_ui`; `SliverMainAxisGroup` exists since
  Flutter 3.7, the project uses Flutter 3.50).
- Brace/paren/bracket balance check over the whole file (strings and
  comments stripped): balanced.
- `git diff` review: only the two scoped files changed; item builders,
  paddings, grid delegates, load-more trigger, tap/long-press chains
  and B12/B13 mount points are unchanged apart from the sliver
  wrapping; no stale `_scrollController` reference remains in the grid
  state; the page disposes the one controller it owns.
- The CI `Analyze Dart code` step is the first real compile check;
  device acceptance below is still required.

## Device acceptance checklist (需真机验收)

1. 上滑吸顶：上滑首页，顶栏/搜索/轮播/继续观看滚出屏幕，分类栏升到
   顶部钉住不动，网格继续滚动；钉住时网格内容不透出到 chips 下面。
2. 下滑复位：滑回顶部，头部（顶栏/搜索/轮播/继续观看）完整回到原位。
3. 分类栏横滑：chips 横向滑动正常；点 chip 切 tab，网格换新列表。
4. 加载更多：滑到底部自动加载下一页，指示条出现，列表接续正确；
   加载更多失败后再次滚动可重试。
5. 网格行为不变：点卡片进详情、长按搜索、文件夹卡片进分类屏。
6. B12/B13：轮播自动播放、继续观看横滑、JS 组件正常；切 tab 不重置
   轮播到第 0 页；切站点/从后台回来后重载。
7. 旋转：旋转后布局正常，网格列宽按重载后几何显示（D4 from
   docs/b8-home-grid.md 仍适用：旋转后需一次重载才刷新列宽）。
8. 玻璃开关：玻璃开/关时，壁纸 + 悬浮胶囊正常，最后一行不被胶囊挡住
   （88dp 槽位预留仍在）。
9. 空态：无站点/无内容时空态居中显示，按钮可用。

## Recovery anchor

- Objective: sticky category tab bar on the Flutter home, native parity
  (see Objective above).
- Branch/HEAD: `b8-flutter-rewrite`, based on `3f87231e`. One commit,
  task-guard session `b8-home-sticky-tabs`, recovery tag
  `recovery/b8-home-sticky-tabs/<timestamp>`.
- Files: `flutter_module/lib/b11_home_page.dart`,
  `docs/b8-home-sticky-tabs.md`. No push made by this task.
- Verification: static only (see above). Next action: parent pushes
  the commit via the browser-upload route, waits for the b8-c0 CI run,
  and delivers the APK for the device acceptance checklist above.
