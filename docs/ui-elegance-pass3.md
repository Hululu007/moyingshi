# 首页顶部栏精修（第三轮）

任务来源：用户 2026-11-13 15:39 装上 1437 后说“很好，都修好了”，接着要求：“能不能把右上角那两个图标弄到搜索框里面把那个师兄秒播去掉，再弄的好看点”。
基线：本地 main = 线上 main = `8c2f6206`（精修第二轮 2a + 2b 已发布 v5.6.0-beta-202611131437）。
约束：**只改手机端，TV/leanback 不动；不改任何功能、不改包名品牌、不改开关默认；主色 #0B57D0 不变；不新画图标（复用现有历史/⋮ 图标）；不碰 .github/workflows/*。**

## 完成句

首页顶部栏精修做完并编进测试版：历史 + ⋮ 两个图标从工具栏右上角移到搜索条右侧（行为不变），搜索条右侧的站名文字彻底去掉，切站走点工具栏标题（标题仍是当前站名、唯一指示），工具栏只剩 logo + 标题。

- Lane：standard。允许路径（guard scope）：`app/src/mobile/res/layout/fragment_vod.xml`、`app/src/mobile/res/menu/menu_vod.xml`、`app/src/mobile/res/menu/menu_vod_more.xml`（新建）、`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/VodFragment.java`、本计划 `docs/ui-elegance-pass3.md`。
- 保护的脏路径：无（开始时工作树干净）。
- 当前时间：2026-11-13 约 15:40 CST。估计：实施约 20 分钟 + 静态核对约 5 分钟 + 构建约 25 分钟；合计约 50 分钟，预计 16:35 CST 前汇报。
- 最便宜的决定性验证：推后 GitHub 构建一次通过（本机无 SDK、无真机，编译检查只靠构建）+ 用户真机截图复核观感。

## 审计实测（2026-11-13，只读，结论先于动手）

- 工具栏标题就是站名：`VodFragment.setTitle()`（:474）把当前站名同时写进 `title` 和 `searchSite`；截图里标题和搜索条右侧都显示“💥师兄｜秒播💥”，是同一站名重复出现两次。
- 点标题本来就能切站：`title.setOnClickListener(this::onSite)`（:192）→ `SiteDialog.create().change()`；长按标题是重载配置。**所以切站不需要另找去处，标题就是入口**——不往 ⋮ 菜单里加“切换站点”。
- 历史图标：`menu_vod.xml` 的 `R.id.history` → `onMenuItemClick` → `HistoryActivity.start`；⋮ 是 `more_actions` 子菜单（刷新/追更/一键同步/推送 APK/推送播放/增强设置），同走 `onMenuItemClick`（`toolbar.setOnMenuItemClickListener`）。
- `searchSite` 全仓只有 3 处引用：`fragment_vod.xml:78`（布局）、`VodFragment.java:199`（点按切站）、`:477`（setText）——删干净无残留。
- `menu_vod.xml` 只由布局 `app:menu` 引用，无 Java 代码 inflate；`updateToolbarMenu()` 只动 `web_home_fullscreen` 的显隐，`setSearchLongClick()` 只找 `R.id.search`——两处都不受本次改动影响。
- 搜索条背景 `shape_setting_row_card` 自带 16dp 内边距，所以两侧图标不用再加外边距；分隔线色 `setting_card_divider` 两主题都有（浅 #E5E5E5 / 深 #38383A），与设置卡片同一体系。
- 没有下箭头/下拉类 drawable（只有 `ic_chevron_right`），标题旁不加“可点”小箭头——不新画图标，标题点按切站是既有行为。

## 定值

- 搜索条：删 `searchSite`；右侧加 1dp × 20dp 分隔线（`@color/setting_card_divider`）+ 两个 36dp 触摸区图标（内 8dp padding，实际图标 20dp，与放大镜同尺寸；tint `?attr/colorOnSurfaceVariant`，与放大镜同色——原来工具栏上的白色 tint 在浅色搜索条上看不见，必须换色）；图标 `clickable=true` + `selectableItemBackgroundBorderless`，点按只触发自己，不会误触搜索条的点进搜索。
- `menu_vod.xml`：删 `history` 和 `more_actions`（含子菜单），只留 `search` 与 `web_home_fullscreen` 两个隐藏项。
- 新建 `menu_vod_more.xml`：原 `more_actions` 子菜单 6 项原样搬过来（id/图标/文案不变）。
- `VodFragment.java`：删 `searchSite` 的点按与 `setText` 两行；接 `searchHistory` → `HistoryActivity.start`、`searchMore` → 新增 `onSearchMore()`（`PopupMenu` 锚在 ⋮ 图标上，inflate `menu_vod_more`，`setOnMenuItemClickListener(this::onMenuItemClick)`——复用既有处理，6 项行为与原来一字不差）；删 `onMenuItemClick` 里已无菜单可达的 `R.id.history` 分支（无测试引用）。
- 工具栏：只剩 logo + 标题（站名），标题点按切站、长按重载配置均不动。

## 不做

- 标题文案、logo、搜索条点进搜索/长按搜本站均不动；底栏 6 项、B1–B6、吸顶分类栏、下载重试/暂停/点播/历史海报、液态玻璃开关均不动。
- Leanback（TV）不动；不新画图标；不改开关默认。

## 回滚

一笔提交 + recovery tag；构建失败先看日志，一处小错可修一轮再推再编。
