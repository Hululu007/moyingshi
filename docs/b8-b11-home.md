# B11 首页壳（B8 全量迁移）

> §0 执行声明：本批把手机端首页壳（顶部栏 + 分类 Tab + ViewPager 容器 + Dart 玻璃底栏）迁移到 Flutter，路由 `/home`；hk:// 站点与 Web 主题回落原生首页。
> 任务护栏 `b8-b11-home-shell`（standard），分支 `b8-flutter-rewrite`，基线 B10 提交 `01f2faa1`。
> 开关 `flutter_b11_enabled` 默认关，独立于其他开关；开关 UI 只在实验屏。关 = 首页回原生，原生实现一行不删。
> **本机无 Java / Android / Flutter SDK，构建与运行均未验证**（CI 联编 + 真机验收时按 §6 逐项证明）。不推送。

## §1 原生页

原生实现：`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/activity/HomeActivity.java`（703 行）+
`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/VodFragment.java`（1210 行）。

- **HomeActivity（宿主壳 + 底栏）**：`change(position)` 是分流点（1=设置，0=首页）；`handleNavItem` 处理底栏与玻璃条点按（玻璃条 `onSelect` 直接调 `handleNavItem`）；`selectNavigation` 同步选中项；`changeFragment` 切 fragment；`setNavigation()` 定 6 项可见规则（vod/setting 常显；live 需 `LiveConfig.hasUrl()`；drama 需 `DramaSites.hasDramaSites() && Setting.isShortDramaCollected()`；following 需 `FollowingSettings.isEnabled()`；第 5 项 lab/local_source 由 `Setting.isLabNav()` 定）；`syncGlassState()` 组玻璃条（可见项过滤 + 徽章 + blur/distortion/translucency/dispersion + 暗色 + colorPrimary）；`restorePosition()` 进程恢复直接调 `changeFragment`；`onDestroy()` 停服务、清 VodConfig、退 Source；`launchMode=singleTop`。
- **VodFragment（首页复合壳）**：AppBar 含 toolbar（logo 36dp + 标题跑马灯）、搜索条（40dp 圆角 pill）、banner ViewPager2（188dp）、继续观看行、JS 小部件区、横向分类 Tab（`TypeAdapter`，14sp 白字 chip）；下方 `CustomViewPager`，页为 `FolderFragment`（分类列表）、`hkContainer`（hk:// 内嵌）、`homeWeb`（WebView）；3 个 FAB（浏览/链接/回顶）；工具栏菜单 `menu_vod.xml`、⋮ 菜单 `menu_vod_more.xml`。
- **顶栏行为**：logo 点 → `HistoryDialog.create().vod().readOnly()`；logo/标题长按 → `reloadConfig()`（`VodConfig.clear().config().load()`）；标题点 → `SiteDialog.create().search().change()`；搜索条点 → `SearchActivity.start`，长按 → 当前站搜索；历史图标 → `HistoryActivity`；⋮ 菜单 7 项（刷新/收藏/搜索/一键同步/推送 APK/推送播放/增强）；底栏直播长按 → 钉快捷方式（`ShortcutManagerCompat.requestPinShortcut` + `ShortcutReceiver` 回调）。
- **回落分支**：`tryEmbedHkHome`（`HkStore.isMiniapp`：api/key 以 `hk://` 开头）→ hkContainer；`mWeb.load`（`WebHomeTarget.canLoad`：站源 homePage 或全局主题）→ WebView；其余走原生内容。全局 Web 主题默认关（`Setting.isWebHomeThemeEnabled()` 默认 false），所以 `canLoad()` 只在站源自带 homePage 或用户开了全局主题时为真。

## §2 设计

- **分流（native first）**：只改两处——`HomeActivity.change(0)` 与 `handleNavItem(R.id.vod)`。先显原生首页（`changeFragment(0)` + 选中项同步），再起 `/home`；**不 finish HomeActivity**（`onDestroy()` 会停服务、清 VodConfig）。`change(0)` 的选中同步不用 `selectNavigation`（它经监听会重进 `handleNavItem` 导致两次启动），而用玻璃条同款“摘监听—设选中项—挂回监听”写法；`handleNavItem` 分支里选中项已是 vod，直接 `changeFragment(0)`。`restorePosition` 不动（进程恢复仍原生）；启动器重 tap 因 `singleTop` + 无 `nav_position` 不叠加。
- **回落判定在 host**：`isFlutterHomeFallback()` = `HkStore.isMiniapp(home) || WebHomeTarget.canLoad(home)`，只在分流处判断；快照另带 `isHkHome` / `isWebHome` 二次把关——Flutter 首页内切站到 hk:// 或 Web 主题后，Dart 自动关屏，底下原生首页接管。Dart 绝不判断站源类型。
- **快照（B8 形态）**：`getHomeShell()` 为 `@async` 一次性订阅——`SiteViewModel.init()` 清旧值 → `observeForever(getResult())` → 首个非空完成 → 35s 看门狗 → 各路径 `removeObserver`；串行 guard（进行中再调直接 error）。快照含：标题（`AppBranding.getDisplayName`）、搜索提示/历史/更多 tooltip、Tab 列表（`Result.getTypes()` 顺序，含 Class JSON）、`errorToast`（站源报错时 Dart 只弹一次 SnackBar，内容区照常显示，与原生“显示内容 + toast”等价）、空态文案（`home_empty_*`）、底栏项（`setNavigation()` 规则已应用）、选中 token（恒 `"vod"`）、玻璃参数（blur/distortion/translucency/dispersion/暗色/colorPrimary）、⋮ 菜单 5 项、占位空文案（`widget_empty`）。
- **顶栏动作映射**：logo 点 → `openLogoDialog`（反射 HistoryDialog，只读）；标题点 → `openSiteDialog`（反射 SiteDialog + 无头 fragment，返回 true 表示已切站，Dart 重取快照）；logo/标题长按 → `reloadConfig`（与原生 `reloadConfig()` 逐行一致：`clear("b8-b11-reload").config(getConfig()).load()`，失败时 `Notify.dismiss()` + `Notify.show(msg)`）；搜索条点/长按 → `openSearch` / `openSearchCurrentSite`（hk:// 站走反射 `HkSearchActivity.open`，否则 `SearchActivity.start`）；历史图标 → `openHistory`（C2 开走 `/history`，否则原生）；⋮ → `openMenuItem`（keep/search/sync/enhance；refresh 由 Dart 重调快照）。
- **底栏映射**：token `vod/live/drama/following/local_source/setting`；vod 为 no-op（占位区无内容可滚）；live → `LiveActivity`，长按 → `longPressBottomBarItem`（与 `HomeActivity.addShortcut` 逐行一致，`ShortcutReceiver` 类与 `ACTION` 反射取）；drama → 反射 `ShortDramaActivity.start`；following → `FollowingActivity.start(activity, null)`；local_source → labNav 开走 `LabActivity`，否则反射 `LocalSourceDialog`；setting → B7 开走 `/settings`，否则 `openHomeAt(1)`（B5/B7 形态：`CLEAR_TOP|SINGLE_TOP` + 字面 `"nav_position"`）。
- **对话框宿主**：无头 fragment `B11HomeHostFragment`（B7 形态，tag `b8_b11_home_host`），实现 `SiteListener` + `ConfigListener`——后者不可省，`HistoryDialog.onAttach` 无条件把父 fragment 转 `ConfigListener`（只读模式永不回调，实现为空）。SiteDialog 的切站结果用实例字段 `siteDialogChanged` 记录，dismiss watcher 完成时读它（初版只返回 false 的 bug 已在本批内修正）。
- **mobile-only 处理（§4.8）**：直引 0 个。反射：SiteDialog / HistoryDialog / LocalSourceDialog / ShortDramaActivity / HkSearchActivity / ShortcutReceiver。复制（招3，附 drift 警告）：`HkStore.isMiniapp` / `titleOf`（`hk://` 前缀判定）、`DramaSites.isDramaSite`（`KEYWORDS = {"短剧","漫短","漫剧","[短]"}` + `Setting.isShortDramaSiteEnabled`）。`HomeActivity` / `LiveActivity` / `HistoryActivity` / `KeepActivity` 双 flavor 同签名，直引安全（B2 先例）。`WebHomeTarget.canLoad`、`LiveConfig.hasUrl()`、`FollowingSettings.isEnabled()`、`FollowingPlaybackBridge.cachedUnreadCount()` 皆主源码集。
- **mobile-only 资源（按目录分别 grep，不截断）**：`nav_vod` / `nav_live` / `nav_drama` / `local_source` / `menu_search` / `app_history` / `control_other` / `app_keep`（主 0、mobile 1）走 `mobileString(name, fallback)`，回落串皆主 res（`app_name` / `live_refresh` / `dialog_short_drama_enabled_label` / `error_play_stage_app_local` / `danmaku_search_title` / `setting_home_history` / `home_filter_title` / `following`）；回落实际不可达（本屏无 leanback 入口，`C1ScreenActivity` 仅 mobile 清单），仅为 leanback 编译。主 res 直引：`nav_following` / `app_lab` / `nav_setting` / `live_refresh` / `setting_one_key_sync` / `setting_enhance` / `home_empty_*` / `widget_empty` / `error_play_timeout`。
- **Dart 页**（`b11_home_page.dart`）：Column = 顶栏（56dp：logo 按钮 + 标题手势区）/ 搜索条（40dp 圆角 pill：左区点按搜索、长按当前站搜索；历史按钮；`PopupMenuButton`）/ B12 占位（banner 188dp、继续观看 96dp，仅图标无文字）/ B13 占位（JS 小部件 96dp）/ 横向 Tab（`ChoiceChip`，14sp，选中态 accent 填充）/ 占位内容区（Expanded）+ 玻璃底栏（`bottomNavigationBar`）。resumed 重取（C1/C2 形态）；`errorToast` 每快照只弹一次；回落快照用 `addPostFrameCallback` 关屏（每快照一次）；`PopScope` 拦截返回走 `C1HostApi.closeScreen()`；加载失败显示异常原文 + 仅图标重试（诊断用，B2 形态）。
- **玻璃底栏近似**：玻璃开时 `BackdropFilter` blur（sigma = `glassBlurDp`）+ 半透明 surface（亮 `0xFFF4F4F6` / 暗 `0xFF1E1E20`，透明度由 translucency 换算，见 V4）+ 顶部圆角 20；关闭时不透明 56dp 条。选中项为 accent 淡底 pill + accent 图文；未选中亮黑 0.62 / 暗白 0.72（与 `GlassNavBar.kt` 同值）；追更徽章 >0 显示（>99 记 `99+`，为 Dart 侧写死格式，非文案）。图标为 Material 近似（见 V5）。
- **Tab 内容区占位**：显示选中 Tab 名 + `tabPlaceholderEmptyText`；整个区域可点，点按走 host `openTabCategory(typeId, typeJson)` 打开 B8 `/category` 屏（host 拼路由，start-then-finish），使 Tab→分类映射在验收时可验证；不设按钮（无合适主 res 文案，不在 Dart 硬编码）。

## §3 文件清单

新：`flutter_module/pigeons/b11_home_messages.dart`（`B11HostApi` + `B11HomeScreen` / `B11HomeTab` / `B11NavItem` / `B11MenuItem`）、`flutter_module/lib/b11_home_page.dart`、`app/src/main/java/com/github/moyingshi/osc/tv/flutter/B11HostApiImpl.java`、`docs/b8-b11-home.md`。
改（只加法）：`flutter_module/lib/main.dart`（`/home` 路由 + B11 开关）、`flutter/C1ScreenActivity.java`（`ROUTE_HOME` + 注册 B11HostApi）、`flutter/C0ShellActivity.java`（C0 引擎注册，仅开关用）、`setting/Setting.java`（`flutter_b11_enabled`，默认 false）、`app/src/mobile/.../ui/activity/HomeActivity.java`（两处分流 + `isFlutterHomeFallback()` + 2 个 import）、`.gitignore`（+`B11Messages.java`）、`.github/workflows/b8-c0.yml`（Pigeon 步骤 +b11 生成与存在性检查）。
未动：C0/C1/C2/B2–B10 合约与实现、原生首页实现、TV、Room/schema、播放器、`android-release.yml`、无新 Dart 依赖。

## §4 逐项对照清单

- [ ] 开关开：启动器进应用 → Flutter 首页（顶栏 + 搜索条 + Tab + 玻璃底栏）；开关关 → 全原生。
- [ ] 顶栏：标题与原生一致（含多站源时的显示名）；logo 点 → 只读历史弹窗；标题点 → 切站弹窗，切站后 Tab 与标题刷新；logo/标题长按 → 配置重载后刷新。
- [ ] 搜索条：提示文案与原生一致；点 → 搜索页；长按 → 当前站搜索；历史图标 → 历史页（C2 开走 Flutter，否则原生）；⋮ 菜单 5 项文案与原生一致，逐项行为正确（刷新重取、收藏、搜索、同步弹窗、增强）。
- [ ] Tab：列表与顺序与原生一致；切换选中态正确；空站源显示空态（标题/描述/重载按钮）；站源报错弹一次 SnackBar 且内容区照常显示；35s 超时显示超时提示。
- [ ] 底栏：6 项可见性与原生一致（无直播源时无 live；无短剧源或未收藏时无 drama；追更关时无 following；labNav 定第 5 项文案）；选中恒为首页；追更徽章数与原生一致；玻璃开/关两态外观；直播长按钉快捷方式。
- [ ] 回落：hk:// 站开 → 原生首页（无 Flutter 首页闪现）；Web 主题站开 → 原生首页；Flutter 首页内切站到 hk:// → 自动关闭回原生。
- [ ] 返回：Flutter 首页按返回 → 落原生首页，再按返回退出。
- [ ] 进程恢复：杀进程重进 → 原生首页（`restorePosition` 未动）。
- [ ] resumed：从播放/详情/搜索返回首页，Tab 与标题刷新。

## §5 相对规划的偏差（V1–V11）

- **V1** 返回多按一次：Flutter 首页之下原生首页已加载数据，返回先落原生首页，再按才退出（HomeActivity 不能 finish，见 §2）。
- **V2** Tab 内容区为占位：B12/B13 接管前无真实内容；点占位区打开 B8 分类页是过渡验证手段，不是正式内容。
- **V3** 无折叠 AppBar、Tab 不吸顶：内容区静态占位，无滚动可折叠；B12/B13 接管内容块时再定滚动形态。
- **V4** 玻璃条为近似：无透镜畸变/色散；translucency→透明度映射取 `alpha = 1 - translucency/100`（夹取 0.15–0.9），未对照原生 `GlassPanel` 内部实现验证；高度玻璃 76dp（原生玻璃条 88dp，含安全区后视觉接近，待验收对照）。
- **V5** 图标与标题形态近似：logo 为 `Icons.local_movies_outlined`（非 `ic_logo`）；底栏图标为 Material 近似（home/live_tv/movie/subscriptions/folder_open/settings）；标题单行省略，非跑马灯。
- **V6** 同步弹窗关闭后不刷新：`OneKeySyncDialog` 关闭无生命周期事件，Flutter 首页只在下次 resumed 时刷新（与 C2 同类缺口）；底下原生首页会经 EventBus 即时刷新。
- **V7** ⋮ 菜单缺两项：`push_apk` / `push_play`（设备推送弹窗，小众）与 `web_home_fullscreen`（Flutter 壳内永不显示 WebHome，无意义）未提供。
- **V8** 无配置时 Flutter 显示空态：原生显示原生内容 + toast；底下原生首页仍显示自身状态，两者并存时以 Flutter 空态为准。
- **V9** `getHomeShell` 串行 guard：进行中再调直接 error（B8 为单订阅；Dart 侧只串行调用，不触发）。
- **V10** G2 跨屏竞态（与 B5/B7 同风险）：`openHistory` / `openMenuItem`（keep/enhance）/ `openBottomBarItem`（setting）/ `openTabCategory` 皆 start-then-finish；若复现新屏路由串位，需在 `C1ScreenActivity` 加重启机制（跨批修复，本批不做）。
- **V11** 底栏 vod 点按无滚动到顶：占位区无内容可滚，仅为 no-op；B12/B13 接管内容后该行为需重定。

## §6 待证明项（CI 联编 + 真机验收时逐项证明，本机未验证）

1. Pigeon 生成：`B11HomeScreen` 含 3 个 `List<>` 嵌套类 + `@async bool` / `@async void` + `Long` 字段，CI 生成 Java 与 Dart 代码且编译通过。
2. `flutter analyze`：`main.dart` 的 b11 导入被 `/home` 路由引用；`b11_home_page.dart` 无未使用 import/变量警告（C1 run #8 教训：一个 warning 即失败）。
3. leanback 编译：`B11HostApiImpl` 无 mobile-only 直引（反射 + `mobileString` + 复制逻辑）；`HomeActivity` 改动仅 mobile 源集。
4. 真机 §4 全项：重点是底栏可见性矩阵（有/无直播源、短剧源、追更开关、labNav）与玻璃条亮/暗外观。
5. 回落三项：hk:// 站、Web 主题站、Flutter 内切站到 hk:// 自动关闭。
6. G2 竞态：从 Flutter 首页连续打开历史/收藏/设置/分类页，确认每次路由正确且不串屏。
7. 进程恢复与返回路径（§4 两项）。
8. B12/B13 占位区无崩溃、无布局溢出（小屏与横屏各看一次）。

## Recovery anchor

- 目标：B11 把手机端首页壳迁移到 Flutter（`/home` 路由，顶栏 + 分类 Tab + ViewPager 占位容器 + Dart 玻璃底栏，hk:// 与 Web 主题回落原生），本地提交不推送。
- 验收：§4 清单在 CI 联编 + 真机逐项通过；开关关 = 全原生。
- 计划状态：B11 实施完成待验证；全量迁移按 `docs/b8-full-migration-plan.md` 顺序继续。
- 当前文件：见 §3；分支 `b8-flutter-rewrite`，基线 B10 提交 `01f2faa1`，工作树待提交。
- 已完成：发现（原生首页 + B8/B9/B10 形态 + 全部 API 核实）→ 护栏 `b8-b11-home-shell` → 合约 + impl + Dart 页 + 6 个共享文件 → 本文档。
- 未验证：构建、Pigeon 生成、analyze、Gradle 编译、真机（本机无 SDK）。
- 未决风险：G2 跨屏竞态（V10）；玻璃条 translucency 映射（V4）；Tab 内容区占位（V2）。
- 下一步：B12 首页块A（轮播 + 继续观看）。
