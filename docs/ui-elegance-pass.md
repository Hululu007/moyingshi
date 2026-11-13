# 优雅精修 — 四件套（设计 + 实施计划）

任务来源：用户 2026-11-13 11:24 问“界面不够优雅，怎么才能更优雅”，助手给了四条，他点了“按这个清单开工”。
基线：本地 main `cdacad89`（删除按钮修复已推，构建 run 43355974493 一次通过，版 v5.6.0-beta-202611131059 已发）。
约束：**只改手机端，TV/leanback 不动；不改任何功能、不改包名品牌、不改开关默认；主色 #0B57D0 不变；BieCuiLe 美术资产不直接拿；图标不重画，只改圆底色值。**

## Recovery anchor

- 目标：四件套做完并编进测试版——①圆角/间距/字号全界面统一 ②设置卡片上真模糊 ③首页收尾（搜索栏、海报圆角、轮播指示器、FAB，B5 筛选 chips 统一成首页 chip 样式）④图标色收敛到同一色系降饱和度。
- 验收标准：每件按下表定值改完；功能一项不丢（底栏 6 项、B1–B6、吸顶分类栏、下载列表点播/删除、液态玻璃开关）；只改手机端；两笔提交、两轮构建，每笔一提交 + recovery tag；无 SDK，编译靠推后 GitHub 构建验证。
- 计划状态：本计划已完成（只读审计结束，见“审计实测”）；**提交 A（①③④ + ②的 helper 本体）已推上（81e14b9e，线上 main 已是该提交），但构建 run 43360170458 在“Run release-critical tests”失败：`View.setBackgroundBlurRadius` 编译找不到——该方法在公开 API 里根本不存在（见下“②机制已改”）。修法在 guard 会话 elegance-pass-b-fix（quick-fix）里做，改完再推再编。**
- 已做动作：只读审计——量了各屏圆角/间距/字号现值、设置 Fragment 清单、图标 6 色、玻璃模糊开关、首页结构、B5 chips 样式（见下）。提交 A 已改：`styles.xml`（Vod.Grid 全圆角 12dp、Vod.Grid.Large 16dp）、详情页标题 18sp、演员/选集间距统一 8dp、设置行卡副标题/数值 14sp、横幅内缩 16dp + 右上角 dots 指示器（BannerAdapter.bindDots）、首页搜索 pill（fragment_vod.xml + VodFragment 接线，menu_vod 搜索项隐藏）、B5 chips 统一成首页 chip 样式、6 个图标圆底色降饱和（HLS ×0.60）、ThemeController 新增 applyViewBlur/applyCardBlur（API 31+ 守卫 + 液态玻璃开关守卫）。静态核对：git diff --check 通过、14 个 XML 解析通过、无旧值残留（TmdbEpisodeAdapter:359 内边距 12dp 是内容内边距、非网格间距，保留）、leanback 零改动、19 文件 + 计划文档在 guard 作用域内。**构建失败后已定性（见“②机制已改”）：其余 19 个文件的改动都编译通过，失败只在 ThemeController.java:423/427 两处 `View.setBackgroundBlurRadius` 调用。**
- ②机制已改（2026-11-13 构建失败后核实）：`View.setBackgroundBlurRadius` 在公开 Android API 里不存在（官方 View 参考页无此方法，compileSdk 37 下 javac 找不到；只有 `Window` 有，且只模糊窗口外的屏幕；真原语 `RenderNode.setBackdropRenderEffect` 要 SDK 37.2 才公开，用户机器上还没有；`SurfaceControl` 的同名方法是 `@hide`，反射不走）。正确做法是**模糊整张壁纸**：`CustomWallView.setBlurRadius()`（RenderEffect，API 31+，GPU 加速，静态图一层成本低；视频壁纸是 SurfaceView 子画面，不受父视图 RenderEffect 影响，保持原样），`ThemeController.applyWallpaperBlur/clearWallpaperBlur`（读「模糊效果」设置 0–40dp，默认 5dp；液态玻璃关/半径 0/低版本时清除），调用点只有一处——`HomeActivity.changeFragment`（设置页位置 1–10 开、首页 0 关；全部设置屏都覆盖，主设置页 + 8 子页 + 搜索页）。首页搜索 pill 与 FAB 不再单加模糊（首页内容是滚动的海报/列表，没有静态壁纸可模糊），只留半透明底。
- 未验证项：本机无 SDK、无真机；第一次编译检查靠推后 GitHub 构建；模糊在真机上的观感（5dp 默认是否够显）只能等用户截图复核。
- 风险：① 原计划“每行卡片都模糊”的性能成本已随机制改变消失（改成一整张静态壁纸模糊一次，滚动不重模糊）；② 壁纸模糊后，首页壁纸在切回首页时已清除、只在设置页可见时模糊；③ 海报圆角改动 `Vod.Grid` 会波及搜索/收藏等所有海报网格（预期内、要的就是统一）；④ 搜索栏是首页新增一行，需守吸顶红线。
- 下一步动作：**guard finish 提交修法（elegance-pass-b-fix）→ 推送 → 确认无构建在跑后触发测试版构建 → 核对 release 4 APK → 汇报 tag + 4 条链接。修法构建若再失败，先看日志；修不好就汇报，不硬推。**

## 审计实测（2026-11-13，只读）

### 圆角：现值散布 2–28dp

- `cardCornerRadius`：16dp×20、8dp×10、18dp×7、24dp×5、10dp×6、6dp×2；`android:radius`：8dp×82、6dp×33、7dp×23、4dp×21、10dp×21、22dp×11、18dp×10、16dp×8、12dp×6、24dp×6。
- 已有 token：`webhtv_card_radius_default` 8dp、`webhtv_card_radius_large` 12dp（`webhtv_dimens.xml`），仅 8 处引用。
- 定值：主设置页分组卡 `SettingFragment.addGroup` 代码设 `card.setRadius(dp(24))`；子页行卡 `shape_setting_row_card.xml` 16dp；详情页 panels 24dp（`activity_tmdb_detail.xml:300/838/1089`）、内卡 16dp（`:322`）；海报 `Vod.Grid` 仅上圆角 8dp、横幅 `Vod.Grid.Large` 全圆角 8dp（`styles.xml`）；分类 chip 选中态 28dp（`shape_type_chip_selected.xml`，40dp 高药丸的一半）、未选中态 `shape_item_round.xml` 28dp；集数背景 `TmdbEpisodeAdapter:514` 圆角 12dp。
- 胶囊类圆角（28/22/21/18dp）全是“高的一半”派生值——**不是乱值，保留派生规则**，只统一卡片类圆角。

### 间距：现值散布 4/8/12/16dp

- 设置页：容器左右 16dp、行卡上下间距 8dp（`layout_marginTop="8dp"`）、行内边距 16dp/8dp、行高 72dp、图标 48dp、标题与图标间距 16dp——子页已统一，**不动**。
- 详情页：演员卡片尾间距 12dp（`TmdbCastAdapter:138`，cinema 模式 18dp）；选集网格间距 12dp（`TmdbEpisodeAdapter:333/341`，标准格 8dp、原生增强 12dp）。
- 首页：横幅内缩左右 12dp/上下 4dp（`adapter_banner.xml`）；继续观看行左右 padding 12dp、上下 4dp；海报项 margin 8dp（`adapter_vod_rect.xml`）；分类栏 paddingStart 4dp、paddingBottom 8dp，chip 项左右 margin 8dp（`adapter_type.xml`）。
- 主设置页分组标题：首组上边距 8dp、其余 24dp（`addGroup` 代码）——保留。

### 字号：现值散布 12–30sp

- 设置行（`item_setting_card_row.xml`）：标题 16sp、副标题 13sp、数值 **15sp**；分组标题 13sp（`item_setting_group_title.xml`）；页头“设置”30sp（`fragment_setting.xml:27`）。
- 详情页（`activity_tmdb_detail.xml`）：标题 28sp bold、副标题 14sp、hero 顶部标题 **17sp** bold、区块标题 18sp；另有 12/13/16sp 若干。
- 首页：chip 文字 14sp（`adapter_type.xml`）、B5 chip 文字 **13sp**（`adapter_widget_chip.xml`）、海报角标 12sp。
- 播放器音频态标题 22sp bold、副标题 19sp（`activity_video.xml:195/484`）——深色全屏功能性展示文字，**本次不动**（见“不做”）。

### 设置 Fragment 清单（模糊的落点）

共 10 个，均继承 `BaseFragment`：主设置 `SettingFragment` + 8 个子页（增强/播放器/弹幕/字幕/TMDB/个人/AI/广告）+ `SettingSearchFragment`（搜索页）。
- 主设置页：6 分组卡是代码建的 `MaterialCardView`（`addGroup`，圆角 24dp、 elevation 0、背景 `@color/setting_card_bg`），组内行是 `item_setting_card_row.xml`（透明底、1dp 分隔线）。
- 8 个子页：每行一个独立行卡（`shape_setting_row_card` 背景），共 115 处引用：增强 27、个人 24、播放器 39、广告 6、AI 6、TMDB 6、弹幕 4、字幕 3；搜索页 0 处（它用 `MaterialCardView` + `cardBackgroundColor="@color/setting_card_bg"`，见 `fragment_setting_search.xml`）。
- 卡片底色：`setting_card_bg` 浅色 `#BFFFFFFF` / 深色 `#BF1E1E1E`（75% 不透明）。

### 玻璃模糊现状

- `Setting.getGlassBlur()`：pref `liquid_glass_blur`，默认 **5dp**，范围 0–40；`Setting.isLiquidGlass()` 为总开关。
- `ThemeController.applyGlassWindowBlur`：弹窗窗口背景模糊，API 31+ 守卫 + 液态玻璃开关守卫，已有先例。
- `View.setBackgroundBlurRadius` 在公开 API 里**不存在**（全仓零使用是对的——它根本调不出来；见“②机制已改”）。minSdk 24，RenderEffect 需 API 31+ 守卫。
- 75% 不透明 + 5dp 默认：模糊会是柔和的一层，不会很抢；用户可用已有“模糊效果”设置（0–40）调强，开关可关。

### 首页现状（§3.5 剩余项）

- 顶部：`MaterialToolbar`（logo 36dp + 标题，logo 点进 `onLogo`、标题点进 `onSite` 切站；菜单 `menu_vod`：搜索/历史/更多）——**没有搜索条**。
- 轮播：ViewPager2 188dp，根 match_parent（B1 闪退教训已修）；**没有指示器**（`VodFragment.onPageSelected` 只处理页切换，无 dots 更新）。
- 继续观看：标题 `textAppearanceTitleSmall`（约 14sp），列表左右 padding 12dp。
- 组件区（B5）标题：`adapter_widget_section.xml`（实施时核对字号，目标 16sp 粗体）。
- 海报：`Vod.Grid` 仅上圆角 8dp；`Vod.Grid` 被手机端 `adapter_vod.xml` / `adapter_vod_list.xml` / `adapter_search.xml` / `adapter_quick.xml` 共用（另有 leanback 各一份，**不动 leanback**）。
- FAB：3 个（筛选/链接/回顶），`backgroundTint ?attr/colorSecondaryContainer`（实心），`CustomFabBehavior` 滚动显隐，margin 16dp。
- B5 chips：`WidgetBrowseActivity.ChipAdapter`（`adapter_widget_chip.xml`）：背景 `shape_item_round`、文字 `@color/selector_text`、13sp、左右 margin 4dp；选中态只靠 `text.setSelected()` 改文字色（旧式淡色选中态）。首页 chips：`adapter_type.xml` 背景 `shape_type_chip`（selector：选中态主色实心 + 白字）、文字白、14sp、左右 margin 8dp。`shape_item_round` / `selector_text` 是搜索片、B5 筛选片、收藏页共用的，**不能改这两个文件本身**，只改 B5 项布局的引用。

### 图标色现状

6 色圆底（`shape_setting_circle_*.xml`，全仓仅设置行 + 下载剧组行 `item_download_drama.xml` 用）：蓝 `#0A84FF`、绿 `#34C759`、橙 `#FF9500`、紫 `#7C5CFF`、红 `#FF3B30`、紫罗兰 `#AF52DE`——全是高饱和 iOS 式纯色，白线形 glyph 24dp 置于 48dp 圆中。

## 定值（Token）

### 圆角（卡片类三档；胶囊类保持“高的一半”派生）

| 档 | 值 | 用处（改后） |
|---|---|---|
| 面板 Panel | **24dp** | 详情页大面板（已是 24）、主设置页分组卡（已是 24）、控制弹窗面板（已是 16→24？见实施注） |
| 卡片 Card | **16dp** | 子页行卡（已是 16）、详情页内卡（已是 16）、横幅（8→16）、选集项背景（12→16） |
| 海报 Poster | **12dp** | `Vod.Grid`：8dp 仅上圆角 → **12dp 四角**；`Vod.Grid.Large`（横幅）：8dp → 16dp（按卡片档） |
| 小 Small | **8dp** | 小卡片/徽章（已有 token `webhtv_card_radius_default` 8dp 保持） |
| 胶囊 Pill | 高/2 | chips 28dp、按钮 22/21/18dp——**不动**，派生规则写进注释即可 |

实施注：控制弹窗面板 `shape_control_sheet_panel.xml` 现值实施时核对，若是 16dp 则随卡片档保持 16dp（不强行升 24dp——弹窗是小面板）；上表“面板 24dp”只覆盖详情页面板与设置分组卡。

### 间距（两档 + 页面边距）

| 档 | 值 | 用处 |
|---|---|---|
| 紧凑 | **8dp** | 行卡之间、网格内间距、chip 内边距、演员/选集卡片间距（12→8） |
| 页面 | **16dp** | 页面左右边距（设置容器已是 16；首页横幅内缩 12→16、继续观看列表左右 padding 12→16） |

保留：设置分组标题上边距（首组 8dp、其余 24dp）、行高 72dp、图标 48dp、标题与图标间距 16dp。

### 字号（六档）

| 档 | 值 | 用处 |
|---|---|---|
| 页头 Page | 30sp bold | 设置页“设置”（已是 30） |
| 展示 Display | 28sp bold | 详情页标题（已是 28） |
| 区块 Section | **18sp** | 详情页区块标题（已是 18）；hero 顶部标题 17sp bold → **18sp bold**；首页继续观看/组件区标题 → **16sp bold**（见下） |
| 标题 Title | 16sp | 设置行标题、详情页正文、首页区块标题（继续观看 `TitleSmall`→16sp bold、组件区标题→16sp bold） |
| 副标题 Sub | **14sp** | 设置行副标题 13sp → **14sp**；设置行数值 15sp → **14sp**（并入副标题档）；详情页副标题已是 14sp |
| 说明 Caption | 12sp | 海报角标、分组标题 13sp **保留**（分组标题是小字标签角色，不并入 12sp）、B5 chip 文字 13sp → **14sp**（并入副标题档，随 chip 统一样式） |

首页 chip 文字 14sp 不变；B5 chip 文字 13sp → 14sp。

### 图标色板（同色相、降饱和度；白 glyph 不变）

饱和度统一乘 0.60，色相/亮度不动（python colorsys HLS 实算）：

| 色 | 现值 | 改后 |
|---|---|---|
| 蓝 blue | `#0A84FF` | `#3B84CE` |
| 绿 green | `#34C759` | `#51A967` |
| 橙 orange | `#FF9500` | `#CC8C32` |
| 紫 purple | `#7C5CFF` | `#8F7CDE` |
| 红 red | `#FF3B30` | `#D56059` |
| 紫罗兰 violet | `#AF52DE` | `#A56EC2` |

只改 6 个 `shape_setting_circle_*.xml` 的 solid 色值；下载剧组行（`item_download_drama.xml`）同色系跟随，属预期内。

## 实施清单

### 提交 A（①③④；standard；先做）

**① 圆角/间距/字号统一：**
- `app/src/main/res/values/styles.xml`：`Vod.Grid` → 四角 12dp；`Vod.Grid.Large` → 16dp。
- `app/src/main/java/com/github/moyingshi/osc/tv/ui/adapter/TmdbCastAdapter.java:138`：尾间距 12dp → 8dp（cinema 模式 18dp 不动）。
- `app/src/main/java/com/github/moyingshi/osc/tv/ui/adapter/TmdbEpisodeAdapter.java:333/341`：网格间距 12dp → 8dp（`:514` 是徽章背景圆角，不是卡片，**不动**；选集/演员项卡片已是 16dp，见 `adapter_tmdb_episode.xml:12` / `adapter_tmdb_cast.xml:15`）。
- `app/src/main/res/layout/activity_tmdb_detail.xml`：hero 顶部标题 17sp bold → 18sp bold（`:64`）。
- `app/src/mobile/res/layout/item_setting_card_row.xml`：副标题 13sp → 14sp；数值 15sp → 14sp。
- `app/src/mobile/res/layout/adapter_banner.xml`：内缩左右 12dp → 16dp（上下 4dp 不动）。
- `app/src/mobile/res/layout/fragment_vod.xml`：继续观看标题 `TitleSmall` → 16sp bold；列表左右 padding 12dp → 16dp；其余不动（吸顶红线：typeBar 不加 scroll flag、AppBar 最后一个子项；toolbar/轮播/继续观看/组件区保持 scroll flag）。

**③ 首页收尾：**
- 搜索条：在 `fragment_vod.xml` 的 AppBarLayout 内、toolbar 下方加一行搜索 pill（`layout_scrollFlags="scroll"`，随 AppBar 收起）：高 40dp、左右边距 16dp、圆角 16dp、背景 `@color/setting_card_bg`（半透明）+ 真模糊（复用②的 helper——若②未合入则先只做半透明，模糊随提交 B 补；见“提交说明”）；左侧搜索图标 + hint“搜索影片、剧集…”、右侧当前站名（点站名走现有 `onSite` 切站逻辑，点 pill 其余部分走 `SearchActivity.start()`）；同时 `menu_vod.xml` 搜索项改为 `visible="false"`（避免与 pill 重复）。
- 轮播指示器：`adapter_banner.xml` 内底部加 dots 行（实施时核对 `BannerAdapter` 绑定点）：点 6dp、间距 6dp；激活点白色 18dp 宽 pill、未激活白色 40% 6dp 圆；随 `onPageSelected` 更新。
- 海报圆角：随①的 `Vod.Grid` 改动（12dp 四角）。
- FAB 玻璃化：`VodFragment` 绑定后，3 个 FAB 的 `backgroundTint` 改为 `@color/setting_card_bg`（半透明）+ 真模糊（同搜索条，复用②的 helper）；图标 tint 与 `CustomFabBehavior` 不动。
- B5 chips 统一：`app/src/mobile/res/layout/adapter_widget_chip.xml`：背景 `@drawable/shape_item_round` → `@drawable/shape_type_chip`；文字色 `@color/selector_text` → `@color/white`；字号 13sp → 14sp；左右 margin 4dp → 8dp。`WidgetBrowseActivity.ChipAdapter` 代码不动（`setSelected` 继续驱动 selector）。`shape_item_round` / `selector_text` 两文件本身不动。
- 组件区标题：`app/src/mobile/res/layout/adapter_widget_section.xml` 标题字号统一为 16sp bold（实施时核对现值）。

**④ 图标色收敛：**6 个 `shape_setting_circle_*.xml` 按上表改 solid 色值。

**提交说明（已改，2026-11-13）：**原计划“每张卡片调 `View.setBackgroundBlurRadius`”是错的——该方法不存在，提交 A 的构建因此失败。②改成模糊整张壁纸（见“②机制已改”）：helper 是 `ThemeController.applyWallpaperBlur/clearWallpaperBlur` + `CustomWallView.setBlurRadius()`，调用点只有 `HomeActivity.changeFragment` 一处；首页搜索条与 FAB 不再单加模糊，只留半透明底。

### 提交 B（②真模糊——已改机制；quick-fix 修复提交 A 的构建失败，会话 elegance-pass-b-fix）

- `app/src/main/java/com/github/moyingshi/osc/tv/ui/custom/CustomWallView.java`：新增 `public void setBlurRadius(float radiusPx)`（API 31+ 守卫；`RenderEffect.createBlurEffect(radius, radius, CLAMP)` 模糊整张壁纸，`<= 0` 清除；视频壁纸不受父视图 RenderEffect 影响）。
- `app/src/main/java/com/github/moyingshi/osc/tv/theme/ThemeController.java`：删掉编译不过的 `applyViewBlur/applyCardBlur`；新增 `applyWallpaperBlur/clearWallpaperBlur(Activity)`（找到 `android.R.id.content` 第 0 个子视图 `CustomWallView`，半径取 `Setting.getGlassBlur()` dp × density；液态玻璃关/半径 0/低版本时清除）。
- `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/activity/HomeActivity.java`：`changeFragment` 里一切换就开关（位置 1–10 设置页开、0 首页关）；全部设置屏（主设置页 + 8 子页 + 搜索页）都覆盖。
- `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/VodFragment.java`：去掉搜索条与 FAB 的 `applyViewBlur` 调用（方法已删）与无用 import；FAB 只留半透明底。
- 不透明度不动（`setting_card_bg` 保持 #BF 75%）；模糊半径不动（默认 5dp，用户设置 0–40 可调）。
- 性能注：整张静态壁纸模糊一次，滚动不重模糊，成本低于原计划的逐卡片模糊；开关可关、滑杆可调；低端机若卡，用户关液态玻璃即回原样。

## 拆分与验证

- 提交 A：①③④（XML 为主 + 少量 Java 间距/绑定），一轮构建（构建失败，见“②机制已改”）。
- 提交 B：②机制改成壁纸模糊（quick-fix 修 A 的构建失败），一轮构建。
- 每笔：guard `finish`（`--verified` 写静态核对证据）→ 推送 → `gh workflow run android-release.yml --repo xinyonghuu/moyingshi -f release_channel=beta -f prerelease=true -f publish_oci=false -f release_notes="…"` → 核对 release 4 APK 齐全 → 汇报 tag + 4 条链接。触发前 `gh run list` 确认无在跑构建。
- 静态核对（每笔）：`git diff --check`、XML 解析（`python3 -c xml.etree` 过改动的 layout/drawable）、grep 残留（旧色值/旧字号/旧间距无残留）、逐行复核 diff（类型、dp/sp 单位、selector 引用存在、leanback 未动）。
- 回滚：每笔独立提交 + recovery tag；任一构建失败先看日志，一处小错可修一轮再推再编。

## 不做（本次范围外）

- 播放器音频态标题 22sp/19sp（`activity_video.xml:195/484`）不动；播放器控制栏、弹幕/字幕面板不动。
- 详情页 19/22sp 其余出现处（`activity_tmdb_person.xml` 等）不动。
- 设置卡片不透明度不动（75%）；模糊半径默认值不动（5dp）。
- `shape_item_round` / `selector_text` 两文件不动；`shape_vod` 前景文件若与新圆角冲突实施时核对（只改圆角、不改结构）。
- 首页网格 ItemDecoration 间距：实施时核对海报网格的实际间距来源（若不在 `adapter_vod_rect.xml` 的 8dp margin 里，另记一笔，不硬改）。
- 搜索条不进 TV 端；`Vod.Grid` 的 leanback 副本不动。
