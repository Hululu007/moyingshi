# 首页分类+筛选合成悬浮按钮（第四轮）

任务来源：用户 2026-11-13 17:57 发首页截图（红圈：分类栏末尾猫图标、右下角悬浮漏斗），问“能不能想想办法让这两个按钮合并？然后美化一下”。父任务先按“点猫图标开合成面板”问了用户，用户点了“合成一个按钮”；随后用户改主意，设计变了：**猫图标开面板那套不要做了**，改成下面这版。
基线：本地 main = 线上 main = `7633aaa5`（顶部栏精修 v5.6.0-beta-202611131543）。
约束：**只改手机端，TV/leanback 不动；不改任何功能（分类切页、筛选应用原样保留）、不改包名品牌、不改开关默认；主色 #0B57D0 不变；不碰 .github/workflows/*。**

## 完成句（AGENTS.md §1）

首页“全部分类+筛选”合成一个右下角悬浮按钮（Material 风格 tune 图标，半透明底，与 link/top 悬浮按钮同一质感），点开一个面板（上半全部分类、下半筛选）；分类栏末尾的猫图标（typeMore）整个去掉，分类栏只剩分类 chip；旧漏斗悬浮按钮被新悬浮按钮取代；做完编进测试版。

- Lane：standard。允许路径（guard scope）：`app/src/mobile/res/layout/fragment_vod.xml`、`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/VodFragment.java`、`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/dialog/TypeFilterDialog.java`（新建）、`app/src/mobile/res/layout/dialog_type_filter.xml`（新建）、`app/src/mobile/res/drawable/ic_fab_tune.xml`（新建）、`app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh-rCN/strings.xml`、`app/src/main/res/values-zh-rTW/strings.xml`（三处各加 `home_filter_title` 一行）、`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/dialog/TypeDialog.java`（删）、`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/dialog/FilterDialog.java`（删）、`app/src/mobile/res/layout/dialog_type.xml`（删）、`app/src/mobile/res/layout/dialog_filter.xml`（删）、本计划 `docs/ui-elegance-pass4.md`。
- 保护的脏路径：无（开始时工作树干净）。
- 当前时间：2026-11-13 约 18:06 CST。估计：计划约 10 分钟 + 实施约 30 分钟 + 静态核对约 5 分钟 + 构建约 25 分钟；合计约 70 分钟，预计 19:15 CST 前汇报。
- 最便宜的决定性验证：推后 GitHub 构建一次通过（本机无 SDK、无真机，编译检查只靠构建）+ 用户真机截图复核观感。

## 审计实测（2026-11-13，只读，结论先于动手）

- 猫图标 = `typeMore`（`fragment_vod.xml:192`，40dp，`ic_action_menu.xml` 实际是猫矢量）：点按 `onTypeMore` → `TypeDialog.create().items(全部类型).show()`；另有 `onTypeMoreTouch` 按压缩放动画；`updateTypeMoreVisible` 按横向滚动范围决定显隐。**本次整段去掉**：布局 ImageView、`onTypeMore`、`onTypeMoreTouch`、`updateTypeMoreVisible`、全部 `setVisibility` 调用、initEvent 里的两行监听；`ic_action_menu.xml` 猫矢量文件留着不动（与之前 `ic_setting_home` 的处理一致，不顺手删美术资产）。
- 漏斗悬浮按钮 = filter FAB（`fragment_vod.xml:249`，`ic_fab_filter`）：点按 `onFilter` → `FilterDialog.create().filter(当前分类 getFilters()).show()`；长按 `onLink` → LinkDialog（与 link FAB 同功能）。`setFabVisible` 里 filter 与 link 共用一个槽位（当前分类有筛选条件时 filter 显示、link 隐藏；否则反过来；无内容时 link 显示）；`onTop` 恢复时按 INVISIBLE 状态二选一恢复；`hideNativeContent` / `applyWebHomeChrome` 里三 FAB 一起 GONE。
- `FilterAdapter`：纵向组列表，每组横向 `ValueAdapter` chips；点筛选项只 `setFilter` 不 dismiss（可连选多组）——合成面板沿用此行为。
- `TypeAdapter.setSelected(position)` 改 `Class` 选中旗标并 `notifyItemRangeChanged`；`TypeDialogAdapter` 共用同一批 `Class` 对象，面板内点分类后 `notifyDataSetChanged()` 即可刷新高亮。
- `TypeDialog` / `FilterDialog` 全仓唯一调用方是 `VodFragment`（`onTypeMore` :494、`onFilter` :550）；`DialogTypeBinding` / `DialogFilterBinding` 也只被它们自己用；`DialogGlassChipStyleTest.mobileCategoryAndFilterDialogsShareTheControlSheetStyle` 只读 `adapter_type_dialog.xml` / `adapter_value.xml` 文本，不引用这两个类，且不在 CI `--tests` 里——**删两个类 + 两个布局安全**。
- `R.id.filter` 在 `activity_following.xml`（main/mobile/leanback）里也有定义，FAB 改名后 `R.id.filter` 仍存在，`FollowingActivityDeviceTest` 编译不受影响。
- tune 图标：全仓无现成 tune drawable；官方 SVG 一次拉取超时，不再重试。**新建 `ic_fab_tune.xml`，按 Material tune 的几何自画**：3 条横向滑轨（2dp 粗，y=5/11/17）+ 3 个错开的竖向滑块（2dp 宽 × 6dp 高，x≈14/8/17），24dp viewport、单色白，与 `ic_fab_filter` 同格式（FAB 的 tint 会盖成 `colorOnSecondaryContainer`）。
- 面板样式沿用现有两套：根背景 `shape_dialog_control_glass_panel`（两旧对话框同款）、窗口配置照抄 `TypeDialog.configureWindow`（dim 0、无 FLAG_DIM_BEHIND、sheet WRAP_CONTENT）、`BaseBottomSheetDialog.bindDialogTheme` 玻璃绑定不变。

## 定值

- 新建 `TypeFilterDialog`（`BaseBottomSheetDialog`，`transparent()` + `stableOverlay()` 均为 true）+ 布局 `dialog_type_filter.xml`（根 `shape_dialog_control_glass_panel`，padding 同 `dialog_type`：16/16/9/20）：
  - 上：typeRecycler（Flexbox ROW/WRAP，`TypeDialogAdapter`，maxHeight 屏幕 40% 竖屏 / 32% 横屏——旧 TypeDialog 用 58/46，合成面板下半还有筛选区，类型区收一档；内部滚动）。
  - 中：1dp 分隔线（`@color/setting_card_divider`）+ “筛选”标题（16sp bold，与首页区块标题一致）；无筛选条件时整段隐藏（分隔线+标题+列表全 GONE，不留空白）。
  - 下：filterRecycler（LinearLayoutManager 纵向，`FilterAdapter`，复用 `FilterListener`），maxHeight 屏幕 40% / 32%，内部滚动。
  - 点分类：**不 dismiss**；调 `typeListener.onItemClick`（切页 + setSelected）→ `typeAdapter.notifyDataSetChanged()` 刷新高亮 → provider 取新分类 filters 刷新筛选区（无则隐藏）。点筛选项：`setFilter` 原样应用，不 dismiss（可连选多组）。
  - 接口：`items(List<Class>)`、`filter(List<Filter> 初始)`、`filterProvider`（函数式接口 `getFilters(int position)`），`show(Fragment)` 防重复（childFragmentManager 里已有 TypeFilterDialog 则 return，与旧 TypeDialog 同款守卫）。
- `VodFragment.java`：
  - 删 typeMore 相关全部代码（见审计）；删 `TypeDialog` / `FilterDialog` import。
  - FAB 改名 `filter` → `browse`：布局 id、`srcCompat` → `@drawable/ic_fab_tune`，其余属性（anchor、behavior、tint、margin）原样；`mBinding.filter` 全改 `mBinding.browse`；点按 → 新增 `onBrowse`（开 `TypeFilterDialog`，传当前分类 filters + provider `this::getFiltersForPosition`）；**删长按**（新按钮只要点按，link 功能走原有的 link FAB）；`setFabGlass(mBinding.browse)` 不变。
  - `setFabVisible`：chrome 隐藏 → 三 FAB 全 GONE；有内容 → top INVISIBLE、browse VISIBLE、link GONE；无内容（itemCount==0）→ top INVISIBLE、browse GONE、link VISIBLE。`onTop`：只恢复 browse/link（browse INVISIBLE 则 show，否则 link INVISIBLE 则 show）。
  - 新增 `getFiltersForPosition(int)` 供 dialog 刷新筛选区。
- 删：`TypeDialog.java`、`FilterDialog.java`、`dialog_type.xml`、`dialog_filter.xml`（均被 `TypeFilterDialog` + `dialog_type_filter.xml` 取代）。
- 美化：面板圆角玻璃与现有对话框同一套；类型 chip 与筛选 chip 全部复用现有适配器/selector，一字不改；悬浮按钮半透明底与 link/top 同一质感（`setFabGlass` 的 `setting_card_bg`）。

## 不做

- link/top FAB 不动（link 功能不变，仍是 LinkDialog）；猫矢量文件留着；底栏 6 项、B1–B6、吸顶分类栏、下载重试/暂停/点播/历史海报、液态玻璃开关均不动。
- Leanback（TV）不动；不改开关默认；不新画除 tune 外的图标。

## 回滚

一笔提交 + recovery tag；构建失败先看日志，一处小错可修一轮再推再编。
