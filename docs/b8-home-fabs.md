# B8: Flutter 首页悬浮按钮（browse / link / top）

用户反馈（2026-11-18 07:48，截图对照）："悬浮按钮没了"——原生首页右下角
的悬浮按钮在 Flutter 首页一个都没有。本任务补齐 3 个悬浮按钮，位置/玻璃底/
可见规则/点击行为均与原生一致。

## 1. 原生对照（均已读码核实）

| 行为 | 原生位置 | 规则 |
|---|---|---|
| 3 个 FAB 的布局 | `app/src/mobile/res/layout/fragment_vod.xml:241-279` | 均 anchor `@id/pager` 右下（`end\|bottom`），16dp margin，`CustomFabBehavior`，`backgroundTint ?attr/colorSecondaryContainer`，`tint ?attr/colorOnSecondaryContainer`；browse 用 `ic_fab_tune`，link 用 `ic_fab_link`，top 用 `ic_fab_top` 且 `tag=top`、初始 `invisible` |
| 玻璃底 | `VodFragment.java:294-303 setFabGlass` | 3 个 FAB 一律刷 `R.color.setting_card_bg` 半透明底（`values/setting_card_colors.xml`：`#BFFFFFFF`；`values-night`：`#BF1E1E1E`）。注释写明：首页内容是滚动的海报/列表，没有静态壁纸可模糊，所以只画半透明色、不做真模糊 |
| 可见规则 | `VodFragment.java:568 setFabVisible` | hk 内嵌或原生 chrome 隐藏时 3 个全 GONE；否则 top 初始 INVISIBLE；有分类时 browse 显示、link GONE；无分类时 browse GONE、link 显示 |
| 滚动行为 | `CustomFabBehavior.java` | top：下滑内容（dyConsumed > 0）且 INVISIBLE 时 show，上滑（dyConsumed < 0）且 VISIBLE 时 hide 到 INVISIBLE；browse/link：下滑且 VISIBLE 时 hide，上滑且 INVISIBLE 时 show |
| browse 动作 | `VodFragment.java:609 onBrowse` | `TypeFilterDialog.create().items(mAdapter.getItems()).filter(getFiltersForPosition(pager.getCurrentItem())).filterProvider(this::getFiltersForPosition).show(this)` |
| link 动作 | `VodFragment.java:603 onLink` | `LinkDialog.show(this)` |
| top 动作 | `VodFragment.java:596 onTop` | 当前页 `scrollToTop()` 后 top 隐掉、browse/link 重新显示 |
| 筛选数据 | `VodFragment.java:613 getFiltersForPosition` | `mAdapter.get(position).getFilters()`，即首页 Result 的 types 及其 `getFilters()`；`Filter.setSelected(v)` 在共享对象上标记选中，`ValueAdapter` 点选时同样先标记共享对象再回调 `FilterListener.setFilter(key, value)` |
| 对话框回调 | `TypeFilterDialog.java` | `show(Fragment)` 把宿主 fragment 强转为 `TypeAdapter.OnClickListener` + `FilterListener`，用宿主的 childFragmentManager 显示；点分类不关闭面板（切页后就地刷新高亮与筛选区），点筛选项原样应用（可连选多组）；`TypeDialogAdapter` 高亮读 `Class.isSelected()`（原生由 `TypeAdapter.setSelected` 维护） |
| 筛选应用 | `VodFragment.java:974 setFilter` | 转发给当前页 fragment 的 `setFilter(key, value)`，即改该页的 extend map 并重载列表 |

## 2. 契约（只加法）

`flutter_module/pigeons/b11_home_messages.dart`，C0/C1/C2 与已有 B11 方法
一律不动，新增：

| 方法 | 形状 | 用途 |
|---|---|---|
| `openTypeFilterDialog(int tabIndex)` | `@async B11TypeFilterState` | browse FAB：打开原生 TypeFilterDialog；dismiss 时完成，带最终选择状态；失败时以未变状态完成 |
| `getTypeFilterState()` | `@async B11TypeFilterState` | 面板打开期间 Dart 每 300ms 轮询 live 选择（B19/B20 轮询形态） |
| `openLinkDialog()` | `@async void` | link FAB：打开原生 LinkDialog；dismiss 时完成 |

新增类 `B11TypeFilterState`：`dialogOpen`（bool，面板是否在屏上）、
`selectedTabIndex`（int，tabs 顺序的位置）、`version`（int，打开时归零，
每次点分类/筛选项 +1）。Pigeon 保留 Dart 方法名 verbatim——Java 侧方法名
已逐字核对（见 §6）。

## 3. 宿主实现

`app/src/main/java/com/github/moyingshi/osc/tv/flutter/B11HostApiImpl.java`（只加法）：

- **状态字段**：`homeTypes`（最近一次成功 shell 加载的 types，即对话框的
  items 与筛选来源，与原生 mAdapter items 同源；超时/失败时保留旧值）、
  `filterOverrides`（每 tab 的 `typeId -> filter key -> Value.v`）、
  `typeFilterDialogOpen` / `typeFilterSelectedTab` / `typeFilterVersion`。
- **buildScreen**：每次拿到新 Result 就替换 `homeTypes` 并清空
  `filterOverrides`（原生重建页面即重置筛选状态）。
- **getTabVideos**：init 值之后叠加该 tab 的 overrides（`extend.putAll`），
  其余逻辑不动；回显的 extendJson 保持 load-more 一致。
- **openTypeFilterDialog**：反射打开 mobile 对话框（B7/B9 形态）：`create()`
  → `items(homeTypes)` → `filter(fabFiltersForPosition(startTab))` →
  `filterProvider(fabHost)` → `show(fabHost)`；打开前按
  `TypeAdapter.setSelected` 语义在共享对象上置选中位；dismiss 观察器挂在
  fabHost 的 childFragmentManager 上（B7 `watchDialogDismiss` 同款），
  exactly-once 完成（`App.post` + completed 守卫，同 `completeSiteDialog`）。
  打开失败（leanback 无 mobile 源集时 `Class.forName` 抛错）走 catch，以
  未变状态完成，不抛错。
- **openLinkDialog**：`openLogoDialog` 同款——现有 headless fragment +
  `watchDialogDismiss` + 反射调静态 `LinkDialog.show(Fragment)`；对话框
  自带播放行为，无回调。
- **回调**（mobile fragment 转发，包内可见）：`onFabTypeSelected`（置选中位 +
  记 tab + version+1）、`onFabFilterSet`（写 overrides + version+1）、
  `fabFiltersForPosition`（原生 `getFiltersForPosition` 的等价实现）。

`app/src/mobile/java/com/github/moyingshi/osc/tv/flutter/B11FabHostFragment.java`
（新建，mobile 源集）：无 UI 的 headless fragment，实现
`TypeAdapter.OnClickListener` + `FilterListener` +
`TypeFilterDialog.FilterProvider`，全部回调转发给 `B11HostApiImpl`。必须放
mobile 源集：对话框 `show()` 强转的 `TypeAdapter.OnClickListener` 是
mobile-only 类型，main 源集无法具名（leanback 变体没有 mobile 源集）；主源集
侧只用反射取它（B20 注册器形态），类名/方法名过 R8 靠整包 keep
（`proguard-rules.pro:38`）。旋转后 FragmentManager 恢复它时宿主引用丢失，
`fabHostFragment()` 每次重绑（同 `homeHostFragment()`）。

## 4. Dart 实现

`flutter_module/lib/b11_home_page.dart`（只加法 + 一处 tab 点选扩展）：

- **浮层位置**：`_buildHomeStack` 的 Stack 内，胶囊之后（画在最上），
  `right: 16`，`bottom: 88（玻璃槽位）+ 16 + 底部安全区`。与原生几何一致：
  原生 FAB 底边在槽位顶上 16dp，胶囊顶边在槽位顶下 8dp，间距 24dp；
  Flutter 侧 FAB 底边（104 + 安全区）与胶囊顶边（80 + 安全区）间距同样 24dp。
- **可见规则**：`_buildFabOverlay` 镜像 `setFabVisible`：hk/WebHome 全隐藏
  （反正要关屏，防御性镜像）；有 tabs 显示 browse、无 tabs 显示 link；
  滚动状态由 `_onFabScroll` 镜像 `CustomFabBehavior`（pixels 增 = 下滑内容，
  显示 top + 隐藏 browse/link；pixels 减则反之）；切 tab 与点 top 后重置
  （原生 `setFabVisible` / `onTop` 的显式重置）。同一时刻最多一个 FAB 可见，
  切换用 150ms scale `AnimatedSwitcher`（`heroTag: null`，过渡期两个 FAB
  共存时不触发 hero 冲突）。
- **玻璃底**：`setting_card_bg` 的原值（亮 `#BFFFFFFF` / 暗 `#BF1E1E1E`），
  不做 BackdropFilter——原生同样只画半透明色。
- **top 动作**：`_onTopFab` 用统一的 `_scrollController.animateTo(0)` 回顶，
  再显式复位 FAB 状态（原生 `onTop` 同款）。
- **browse 动作**：`_openTypeFilterDialog` 在 await 对话框期间开 300ms
  定时轮询 `getTypeFilterState`；version 变化即 `_applyTypeFilterState`
  （切 tab + `_filterEpoch++` 触发网格重载 page 1）；dismiss 完成时再应用
  一次最终状态（轮询漏掉也丢不了）。`_filterEpoch` 是新传给 `_TabVideoGrid`
  的独立世代：筛选重载不碰 `_homeLoadEpoch`，B12/B13 不重置（原生换筛选
  也不重置轮播）。
- **link 动作**：`_openLinkDialog`，await dismiss 即可。

## 5. 偏差

- **D1**：FAB 图标用 Material 近似（`Icons.tune` / `Icons.link` /
  `Icons.arrow_upward`），对照原生 `ic_fab_tune` / `ic_fab_link` /
  `ic_fab_top`，与底部栏图标近似策略一致（docs/b8-b11-home.md V5）。
- **D2**：图标色近似 `colorOnSecondaryContainer`（亮 `#FF1D1B20` / 暗白色）；
  底色是 `setting_card_bg` 原值，无偏差。
- **D3**：显示/隐藏动画用 150ms scale `AnimatedSwitcher`，原生
  `FloatingActionButton.show()/hide()` 也是 scale 动画，时长为 Dart 侧自定。
- **D4**：回顶用 300ms easeOut `animateTo`，原生是页 fragment 的 smooth
  scroll，时长为近似。
- **D5**：`isNativeChromeHidden()` 的 GONE 条件在 Flutter 首页无对应物
  （没有原生 chrome 隐藏状态），只镜像 hk/WebHome 隐藏。
- **D6**：面板打开时的 live 选择经 300ms 轮询到达，点选后最长 ~300ms 才
  在背后网格上反映（原生经直连 listener 即时反映）；dismiss 完成是后备，
  最终状态不丢。
- **D7**：面板打开时旋转：与 B7/B9 对话框调用同一敞口（impl 随 Activity
  重建，`@async` 完成走 B9 观察器形态），列入真机观察项。
- **D8**：筛选 overrides 在每次 shell 成功加载后清空（恢复/刷新/切站）；
  原生恢复时不重载故筛选保留，Flutter 首页恢复时重载 shell 是既有设计，
  本任务沿用该语义。

## 6. 验证（静态，无 SDK）

- Pigeon 方法名三处逐字核对：契约 `openTypeFilterDialog` /
  `getTypeFilterState` / `openLinkDialog` 与 Java 侧
  `openTypeFilterDialog(Long, Result<B11TypeFilterState>)` /
  `getTypeFilterState(Result<B11TypeFilterState>)` /
  `openLinkDialog(VoidResult)`、Dart 侧
  `openTypeFilterDialog(int)` / `getTypeFilterState()` / `openLinkDialog()`
  一致；`B11TypeFilterState` 字段 `dialogOpen` / `selectedTabIndex` /
  `version` 与 Java Builder `setDialogOpen` / `setSelectedTabIndex` /
  `setVersion` 一致。
- 反射方法名与原生签名逐字核对：`TypeFilterDialog.create/items/filter/
  filterProvider/show`、`TypeFilterDialog$FilterProvider`、
  `LinkDialog.show(Fragment)`、`B11FabHostFragment.setHost`。
- 重读改动文件：FAB 定位与胶囊不重叠（24dp 间距）、滚动监听只读不
  dispose 控制器、轮询定时器在 dispose 与 dismiss 时取消、grid 的
  `filterEpoch` 已进 `didUpdateWidget` 与 stale 判断；`git diff` 复查仅
  5 个 scope 文件，无无关改动。
- CI 的 Dart analyze + Gradle 编译是第一次真实编译检查；Pigeon 生成代码
  由 CI 从契约生成（生成文件均 gitignore，未入版本库）。

## 7. 设备验收清单（需真机验收）

1. 首页右下角出现 tune 悬浮按钮，在胶囊上方、不遮挡网格最后一行。
2. 上滑内容：tune 按钮隐藏、回顶按钮出现；滑回顶部附近：回顶按钮隐藏、tune 按钮恢复。
3. 点回顶按钮：平滑滚到顶部，按钮换回 tune。
4. 点 tune：底部弹出"全部分类 + 筛选"面板，当前分类高亮、当前 tab 的筛选组显示。
5. 面板内点另一分类：面板不关闭，高亮与筛选区就地刷新，背后网格切到该分类。
6. 面板内点筛选项：面板不关闭，背后网格按筛选重载；连选多组均生效。
7. 关闭面板：首页停在所选分类与筛选项上；再打开面板，高亮与筛选项保持。
8. 无分类的站：右下角显示 link 按钮而非 tune；点 link 弹出链接输入框，粘贴链接可播放。
9. hk 站 / WebHome 站：不显示悬浮按钮（关屏回原生）。
10. 暗色模式：按钮底为深色半透明、图标白色；切横屏后按钮位置仍在胶囊上方右侧。
11. 面板打开时旋转：面板与选择状态无崩溃（D7 观察项）。

## 8. 需真机验收

本机无 Java/Android/Flutter SDK，无法本地编译或真机测试；以上静态验证
通过后，CI 构建与真机验收是本任务的最终验证。
