# 优雅精修第二轮（设计 + 实施计划）

任务来源：用户 2026-11-13 13:33 装上 1146 精修版后只回一句“还是不够优雅，再优雅一点”，没指哪块；父任务按他截图里最扎眼的地方定了五件。
基线：本地 main = 线上 main = `5a6fef0e`（下载三件套三笔提交都已推上：重试/暂停 fd3555a0 + 历史海报 6b715fd2 + 修变量名冲突 5a6fef0e；构建 run 43373487369 一次通过，v5.6.0-beta-202611131339 已发布，见“协调”）。
约束：**只改手机端，TV/leanback 不动；不改任何功能、不改包名品牌、不改开关默认（模糊半径默认值是数值默认，父任务明确要求试 8–12dp，不属于开关默认）；主色 #0B57D0 不变；BieCuiLe 美术资产不直接拿；不新画图标（占位图只用渐变，不画新 glyph）。**

## 完成句（AGENTS.md §1）

精修第二轮五件做完并编进测试版：①继续观看无海报条目换柔和渐变占位、标题不再显示 file:// ②字体层级收一级（标题加粗、副标题淡一级、数值向标题靠一级、灰种收敛）③壁纸更柔（模糊默认 5dp→10dp、首页也轻微模糊 3dp）④首页区块节奏统一 8dp ⑤播放器 ControlDialog 补“画面”按钮。

- Lane：standard。允许路径（guard scope，见“实施清单”）：ContinueAdapter.java、adapter_continue.xml、新 drawable continue_placeholder.xml、item_setting_card_row.xml、item_download_drama.xml、values/setting_card_colors.xml、values-night/setting_card_colors.xml、Setting.java（仅 getGlassBlur 默认值）、ThemeController.java（壁纸模糊）、HomeActivity.java（changeFragment）、fragment_vod.xml、ControlDialog.java、dialog_control.xml、docs/ui-elegance-pass2.md（本计划）。
- 保护的脏路径：无（开始时工作树干净；下载三件套两笔提交已在 main 上）。
- 当前时间：2026-11-13 约 13:45 CST。估计：只读审计已做约 20 分钟；等下载三件套重试构建跑完约 15 分钟；提交 A 实施约 25 分钟 + 构建约 25 分钟；提交 B 实施约 15 分钟 + 构建约 25 分钟；合计约 1 小时 45 分钟，预计 15:30 CST 前汇报。
- 最便宜的决定性验证：推后 GitHub 构建一次通过（本机无 SDK、无真机，编译检查只靠构建）+ 用户真机截图复核观感。

## Recovery anchor

- 目标：五件做完并编进测试版（见完成句）。
- 验收标准：每件按下表定值改完；功能一项不丢（底栏 6 项、B1–B6、吸顶分类栏、下载重试/暂停/点播/历史海报、液态玻璃开关）；只改手机端；两笔提交、两轮构建，每笔一提交 + recovery tag；构建一次通过（允许一处小错修一轮）。
- 计划状态：只读审计已完成（见“审计实测”）；guard 会话 elegance-pass2-a 已开（base 5a6fef0e），提交 A（①②④⑤）已实施完毕，待静态核对 → finish → 推 → 构建；提交 B（③壁纸更柔）随后另开 guard 会话。
- 已做动作：只读审计——量了占位图来源、字体/灰色现值、模糊机制、首页节奏、ControlDialog 按钮清单（见下）；确认与下载三件套的边界（见“协调”）；提交 A 实施：continue_placeholder.xml 新建、ContinueAdapter 占位分支 + 标题兜底、adapter_continue 备注色、设置行/下载行标题加粗、下载行字号 13/15→14/14、色板两主题三色新值、fragment_vod 4 处间隙统 8dp、dialog_control 加 pictureAdjust 行、ControlDialog 接线 + onPictureAdjust()。核对发现：item_download_episode.xml 的 error 行已用 subtitle 色、status 行用 value 色，无需改动。
- 未验证项：本机无 SDK、无真机；第一次编译检查靠推后 GitHub 构建；真机观感（渐变占位、加粗标题、10dp 模糊、3dp 首页模糊）只能等用户截图复核。
- 风险：① 占位图只改 ContinueAdapter，不碰 ImgUtil 全局占位（改 ImgUtil 会波及搜索/收藏等所有海报）；② 模糊默认值 5→10 会同时影响弹窗窗口模糊（applyGlassWindowBlur 也读同一设置，用户滑杆可调回）；③ 首页 3dp 轻微模糊是壁纸背景，不影响海报/文字前景；④ ControlDialog 新增行不碰 Listener 接口（见实施注），不改 VideoActivity/TmdbDetailActivity。
- 下一步动作：**静态核对提交 A → guard finish（提交 + recovery tag）→ 推 → 触发测试版构建 → 核对 4 APK → 汇报父任务；随后开 guard 会话 elegance-pass2-b 做提交 B。**

## 审计实测（2026-11-13，只读）

### ① 继续观看占位图：来源已定性

- 入口：`VodFragment.setContinueView/refreshContinue`（B2）：读 `History.getForDisplay()`，只留未看完（duration > 0 且 position < duration），最近 10 条；点进 `HistoryResumeCoordinator.open`。
- 渲染：`ContinueAdapter.bind`：标题 `item.getVodName()`、备注 `item.getVodRemarks()`、进度条、`ImgUtil.load(name, pic, image)`。
- 布局 `adapter_continue.xml`：104dp 宽 × 140dp 高图，背景 `?attr/colorSurfaceContainer`，`Vod.Grid.Large`（16dp 圆角）；标题 12sp（无颜色=默认 onSurface）、备注 12sp `?attr/colorOnSurfaceVariant`。
- 大白卡 + 灰纸飞机来源：`SiteApi.pushDetail:403` 把本地文件播放入口的海报写成 `R.string.push_image`——**一整段 base64 data-URI JPEG（纸飞机白底图）**，存在 strings.xml。下载三件套第二笔 `6b715fd2` 已改：下单时存真海报（DownloadTask 新增 poster 字段），pushDetail 里 `current.poster` 非空就用真海报；**老记录（已存 file:// 标题 + push_image 图片）不受影响，仍显示纸飞机**——这就是本轮①要兜底的对象。
- 边界：不碰 `SiteApi.java / VideoActivity.java / DownloadTask.java / SettingFragment.java / TmdbDetailActivity.java / DownloadEpisodeDialog.java`（下载三件套 scope）；不改 `push_image` 字符串；不改 `ImgUtil`（全局占位）。

### ② 字体/灰色：现值已量

- 设置行（`item_setting_card_row.xml`）：标题 16sp 无 style、`setting_card_title`；副标题 14sp、`setting_card_subtitle`；数值 14sp、`setting_card_value`。
- 下载剧组行（`item_download_drama.xml`）：标题 16sp 无 style、`setting_card_title`；副标题 **13sp**、`setting_card_subtitle`；数值 **15sp**、`setting_card_value`——第一轮只统一了设置行，这行漏了（13/15 与 14/14 不一致）。
- 设置卡片色板（`values/setting_card_colors.xml` / `values-night/`）：浅色标题 #000000、副标题 #8A8A8E、数值 #8A8A8E、分组标题 #8A8A8E、chevron #C7C7CC、分隔线 #E5E5E5；深色标题 #FFFFFF、副标题 #98989D、数值 #98989D、分组标题 #98989D、chevron #48484A、分隔线 #38383A。**副标题=数值=分组标题共用同一个灰**——层级全靠字号撑，颜色上没有分层。
- 详情页标题已是 18sp/28sp bold（`activity_tmdb_detail.xml` 多处 `textStyle="bold"`），首页区块标题（继续观看/组件区）已是 16sp bold、marginStart 16——这三处不动。
- 移动端 layout 里 `android:textColor` 分布（前几位）：`setting_card_title` 123、`setting_card_value` 116、`dialog_outlined_button_text` 54、`webhtvColorOnWallpaper` 50、`white` 37、`colorOnSurfaceVariant` 34、`selector_control_sheet_text` 29。继续观看备注用 `colorOnSurfaceVariant`，与设置卡片灰是两个体系。

### ③ 壁纸模糊：机制已核实（沿用第一轮结论）

- `Setting.getGlassBlur()`：pref `liquid_glass_blur`，默认 **5dp**，范围 0–40（`Setting.java:439-444`）。
- `ThemeController.setWallpaperBlur`：`HomeActivity.changeFragment` 一处调用——位置 ≥1（设置页 1 主设置 + 2–10 子页/搜索）开全半径，位置 0（首页）清除；`CustomWallView.setBlurRadius()`（RenderEffect，API 31+，GPU 加速）；视频壁纸是 SurfaceView 子画面，不受影响。
- `applyGlassWindowBlur`（弹窗窗口模糊）也读同一 `getGlassBlur()`——默认值改动会同时影响弹窗背景模糊（用户滑杆可调回，见风险②）。
- 用户反馈“5dp 不够显”：第一轮计划“不做”里写过“模糊半径默认值不动（5dp）”，本轮父任务明确要求试 8–12dp。

### ④ 首页节奏：现值已量（fragment_vod.xml）

- 搜索 pill：高 40dp，margin 16/8/16/4（左右 16、上 8、下 4）。
- 轮播：188dp，无外边距（内缩在 adapter_banner 里 16dp）。
- 继续观看区：paddingTop 4、paddingBottom 4；标题 16sp bold、marginStart 16；列表 paddingTop 8、左右 16。
- 组件区：无 padding；行内 paddingTop/Bottom 各 4；标题 16sp bold、marginStart 16（与继续观看标题已统一）。
- 分类栏（吸顶）：paddingBottom 8，无 paddingTop；**吸顶红线：typeBar 不加 scroll flag、保持 AppBar 最后一个子项，改它内部 padding 不影响吸顶 math（吸顶位置 = 全部 scroll 子项高度之和，typeBar 自身 padding 只改变它自己的高度）。**
- 区块间隙现状：搜索→轮播 4dp、轮播→继续观看标题 4dp、继续观看→组件区 4dp、组件区→分类栏 0、分类栏→海报网格 8dp——**不匀（4/4/0/8）**。
- FAB：第一轮已在 `VodFragment:182` 代码里把 tint 改成 `setting_card_bg`（半透明），layout XML 里仍是旧 attr（代码覆盖为准），本轮不动。

### ⑤ ControlDialog：按钮清单已核实

- 弹窗（`app/src/mobile/java/.../dialog/ControlDialog.java`，BottomSheet）：`Controls` 镜像宿主控制栏——player、decode、playParams、speed、scale、lut、reset、repeat、text、audio、videoTrack、opening、ending、danmaku、title、episodes；另有弹窗自有项（timer、immersiveAudio、codecCapability、panDiagnostic），**不镜像宿主**。
- 第一行（`dialog_control.xml:56-80`）：lut、reset、repeat、timer、immersiveAudio，均为 `@style/ControlSheetButton`。
- 控制栏有“画面”按钮：`VideoActivity:1904/2200`（`mBinding.control.action.pictureAdjust`，`addActionButton(PICTURE_ADJUST, …)`），`onPictureAdjust()` → `PictureAdjustDialog.show(this, player())`（:5370）；TmdbDetailActivity 内联链同款（`playerPictureAdjust`，`onInlinePictureAdjust()` → `PictureAdjustDialog.show(this, player())`）。**弹窗里没有**——之前改版漏的。
- `PictureAdjustDialog.show(FragmentActivity, PlayerManager)`（:36）；ControlDialog 自带 `player` 字段 + `requireActivity()`，可直接调（同款先例：`onPanDiagnostic()` 先 dismiss 再 `App.post` 弹框，带 isFinishing/isDestroyed/isStateSaved/isReleased 守卫）。
- 文案：`@string/play_picture_adjust`，中文“画面”（values-zh-rCN:87），控制栏同款。

## 定值（Token）

### ① 占位图（只改 ContinueAdapter + adapter_continue.xml + 新 drawable）

- 新 drawable `app/src/main/res/drawable/continue_placeholder.xml`：layer-list，一张柔和对角渐变（`?attr/colorSurfaceContainerHigh` → `?attr/colorSurfaceContainer`，两端都是主题色、深浅两档都自然；**不画新 glyph**——渐变本身就是占位，进度条 + 下方标题已说明这是视频条目；纸飞机是旧 push 占位图的画风，不复刻）。
- `ContinueAdapter.bind`：海报为空 **或等于 push 占位图**（`TextUtils.equals(pic, ResUtil.getString(R.string.push_image))`——字符串还在，老记录能命中；新记录已是真海报，不命中）时，不走 `ImgUtil.load`，直接 `binding.image.setImageDrawable(continue_placeholder)`；有真海报时原样走 `ImgUtil.load`。`ImgUtil` 本身不动。
- 标题兜底（同文件内一个小 helper，不新增工具类）：标题以 `file://`（或含 `://`）开头时不直接显示——去 scheme，取最后一段；若最后一段是 `index.m3u8` 之类播放列表文件，取父文件夹名；去扩展名。例如 `…/玫瑰战争/玫瑰战争 1/index.m3u8` → `玫瑰战争 1`；`…/玫瑰战争 第01集.mp4` → `玫瑰战争 第01集`。新记录已有真剧名，不走兜底；老记录靠兜底不再挂技术字串。
- 不做：历史页适配器不动；`push_image` 字符串不动；`ImgUtil` 不动。

### ② 字体层级（收一级；灰种收到每主题 3 个正文字灰）

| 角色 | 浅色 | 深色 | 说明 |
|---|---|---|---|
| 标题 Title | #000000（不变） | #FFFFFF（不变） | 设置行标题、下载剧组行标题加 `textStyle="bold"`；详情/区块标题已是 bold，不动 |
| 数值/状态 Value | #55555A | #C6C6CB | 原与副标题同灰，现向标题靠一级（设置行数值、下载行数值、下载集行状态文字） |
| 副标题/说明 Sub | #96969C | #A3A3A8 | 原 #8A8A8E/#98989D，淡一级（设置行副标题、下载行副标题、下载集行失败原因、继续观看备注） |
| 分组标题 | 与副标题同灰 | 与副标题同灰 | 不再单独占一个灰（原与副标题同值，改值后自动跟随） |

- 字号统一：下载剧组行副标题 13sp→14sp、数值 15sp→14sp（与设置行 14/14 对齐）；其余字号不动。
- 继续观看备注：`?attr/colorOnSurfaceVariant` → `@color/setting_card_subtitle`（收进同一灰体系）；标题 12sp 默认色（onSurface）不动。
- 落点文件：`item_setting_card_row.xml`（标题 bold、数值色）、`item_download_drama.xml`（标题 bold、副标题 14sp、数值 14sp + 两色）、`values/setting_card_colors.xml` + `values-night/setting_card_colors.xml`（subtitle/value 两色新值；group_title 改为与 subtitle 同值——直接写同值，保持引用不动）、`adapter_continue.xml`（备注色）、下载集行失败原因文字色（实施时核对落点，`item_download_episode.xml` 的 error 行色改为 subtitle 色）。
- 对比度注：数值 #55555A 在白卡上约 7:1；副标题 #96969C 约 3.3:1（次要文字，沿用第一轮对次要文字的容忍口径，不再加深——再深就看不出“淡一级”）。

### ③ 壁纸更柔

- 默认半径：`Setting.getGlassBlur()` 默认 5→**10dp**（8–12dp 中值；范围 0–40、滑杆不变，用户可调回；弹窗窗口模糊同步变强一点，见风险②）。
- 首页也轻微模糊：`ThemeController.setWallpaperBlur` 加半径参数——设置页（position ≥ 1）用设置值（默认 10dp），首页（position 0）用 **3dp** 轻微模糊代替清除；`HomeActivity.changeFragment` 按位置传半径（0→3dp，≥1→设置值）。液态玻璃关 / 低版本时仍清除（0dp）。
- 不做柔光遮罩（“或”的备选）：只调半径已能达到“柔一点”，少一层视图、少一份风险；若用户仍嫌不够显，下一轮再加遮罩。

### ④ 首页节奏：区块间隙统一 8dp

- 搜索 pill：marginBottom 4→**8dp**。
- 继续观看区：paddingTop 4→**8dp**、paddingBottom 4→**8dp**（标题→列表 8dp 不变）。
- 组件区：加 `android:paddingTop="8dp"`（行内 4/4 不动）。
- 分类栏：加 `android:paddingTop="8dp"`（paddingBottom 8 不变；吸顶红线见审计④）。
- 区块标题样式已统一（继续观看/组件区均为 16sp bold、marginStart 16），不动。

### ⑤ ControlDialog 补“画面”按钮

- `dialog_control.xml`：第一行 lut 之后加 `pictureAdjust`（`@style/ControlSheetButton`，`android:text="@string/play_picture_adjust"`），与 lut 同行（都是画面相关）。
- `ControlDialog.java`：`initEvent` 里 `binding.pictureAdjust.setOnClickListener(v -> onPictureAdjust())`；新增 `onPictureAdjust()`——照 `onPanDiagnostic()` 的守卫写法：先 `dismissAllowingStateLoss()`，再 `App.post(() -> { activity 未 finish/destroy、未 saveState、player 未 release 时 PictureAdjustDialog.show(activity, player); }, 140)`。
- **不加 Listener 方法、不改 Controls、不改 VideoActivity/TmdbDetailActivity**（弹窗自带 player + activity，直接调与两宿主现有行为一致；不支持的引擎由 PictureAdjustDialog 自己置灰并说明原因，B6 已有该逻辑）。
- 不做：Leanback 的 ControlDialog 不动；弹窗其余行不动。

## 实施清单

### 提交 A（①②④⑤；standard；先做）

- `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/adapter/ContinueAdapter.java`：占位图分支（空或 push_image 时用新 drawable）+ 标题兜底 helper。
- `app/src/main/res/drawable/continue_placeholder.xml`（新建）：layer-list 对角渐变（两端主题色）。
- `app/src/mobile/res/layout/adapter_continue.xml`：备注色 → `@color/setting_card_subtitle`。
- `app/src/mobile/res/layout/item_setting_card_row.xml`：标题加 bold；数值色（XML 里色值引用不变，色板改值后自动生效——只改标题 style）。
- `app/src/mobile/res/layout/item_download_drama.xml`：标题加 bold；副标题 13sp→14sp；数值 15sp→14sp。
- `app/src/main/res/values/setting_card_colors.xml` + `values-night/setting_card_colors.xml`：subtitle/value/group_title 三色按②表改值。
- 下载集行失败原因文字：`item_download_episode.xml` 实施时核对——error 行已用 `@color/setting_card_subtitle`，status 行用 `@color/setting_card_value`，无需改动（色板改值后自动生效）。
- `app/src/mobile/res/layout/fragment_vod.xml`：④ 的 4 处间隙（搜索 marginBottom、继续观看区 padding、组件区 paddingTop、分类栏 paddingTop）。
- `app/src/mobile/res/layout/dialog_control.xml`：第一行 lut 后加 pictureAdjust。
- `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/dialog/ControlDialog.java`：initEvent 接线 + onPictureAdjust()（含守卫）。

### 提交 B（③壁纸更柔；standard；后做）

- `app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java`：`getGlassBlur()` 默认 5→10（只改默认值数字，范围 0–40 不动）。
- `app/src/main/java/com/github/moyingshi/osc/tv/theme/ThemeController.java`：`setWallpaperBlur` 加半径参数（设置页=设置值，首页=3dp；关/低版本=0）。
- `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/activity/HomeActivity.java`：`changeFragment` 按位置传半径。

## 拆分与验证

- 提交 A：①②④⑤（UI 为主），一轮构建；提交 B：③（行为相邻：默认值 + 首页模糊），一轮构建。
- 每笔：guard `finish`（`--verified` 写静态核对证据）→ 推送 → 触发前 `gh run list` 确认无在跑构建 → `gh workflow run android-release.yml --repo xinyonghuu/moyingshi -f release_channel=beta -f prerelease=true -f publish_oci=false -f release_notes="…"` → 核对 release 4 APK 齐全 → 汇报 tag + 4 条链接。
- 静态核对（每笔）：`git diff --check`、XML 解析（`python3 -c xml.etree` 过改动的 layout/drawable/colors）、grep 残留（旧色值/旧字号/旧间距无残留）、逐行复核 diff（dp/sp 单位、selector/引用存在、leanback 未动、未碰下载三件套 7 文件、未碰 VideoActivity/TmdbDetailActivity/SiteApi）。
- 回滚：每笔独立提交 + recovery tag；任一构建失败先看日志，一处小错可修一轮再推再编。

## 不做（本次范围外）

- `ImgUtil` 全局占位不动；`push_image` 字符串不动；历史页适配器不动。
- 柔光遮罩不加（③只调半径）。
- 不新画图标、不改字体文件、不改开关默认（模糊滑杆范围 0–40 不动）。
- Leanback（TV）不动：`app/src/leanback/.../ControlDialog.java`、leanback 版 Vod.Grid、leanback 布局一律不动。
- `shape_item_round` / `selector_text` 不动；`colorOnSurfaceVariant` 在未触及的布局里不动。

## 协调（与下载三件套）

- 下载三件套三笔提交已在 main 上（fd3555a0 重试/暂停、6b715fd2 历史海报、5a6fef0e 修变量名冲突）；构建 run 43373487369 一次通过（22 分 21 秒），v5.6.0-beta-202611131339 已发布。**本轮不动它的 7 个文件**（SiteApi、DownloadTask、VideoDownloadManager、TmdbDetailActivity、DownloadEpisodeDialog、VideoActivity、SettingFragment）；本轮 scope 与之零重叠（唯一沾边的是 Setting.java——对方没碰 getGlassBlur；HomeActivity/ThemeController 对方没碰）。
- 顺序：等 run 43373487369 跑完（无论成功失败，只要无 in_progress）→ `git fetch` + 更新本地 main → guard start → 拷入本计划 → 实施。实施中若 main 又动了（对方修构建失败推了新提交），以 `git fetch` 看到的最新 main 为准，先合入再开 guard 会话。
