# B8 B4：增强设置页迁移到 Flutter（实施记录）

> 状态：**已实施（本地提交，未推送，未经 CI/真机验证）**。评估与阶段定义见
> `docs/b8-flutter-rewrite-assessment.md`（§5 方案 C、§6 后续阶段 go/no-go、
> §7 回滚路径）；全量批次规划见 `docs/b8-full-migration-plan.md`（§3 的 B4
> 范围句 + §4 共性决策全部适用；本文档按它实施，全部偏差见 §2.10）；
> C0/C1/C2/B2/B3 已验证形态见 `docs/b8-c0-shell.md`、
> `docs/b8-c1-about-subtitle.md`、`docs/b8-c2-history.md`（§2.9 的 D1–D8 是
> 既定决策，必须沿用）、`docs/b8-b2-keep.md`（§2.10 的 V1–V8 与
> `mobileString()` 做法、leanback 资源核查教训：按目录分别 grep，不用
> `head` 截断）、`docs/b8-b3-settings-a.md`（§2.10 的 V1–V10 是既定决策，
> 必须沿用：弹窗 @async 契约、内联弹窗、反射、Proxy、深链分流、图标硬编码、
> 字面路由、下标钳制）。分支：`b8-flutter-rewrite`；基线：HEAD `172f5fc3`
> （B3 提交）；`main` 未动。
>
> **诚实声明**：本机无 Java、Android SDK、Flutter/Dart，一切构建与运行结论
> 均未在本地验证。所有“待 CI 证明 / 待真机证明”项见 §6，不得视为已验证。

## 0. §1 执行声明（仓库 AGENTS.md）

- 完成句：B4 在 `b8-flutter-rewrite` 上把手机端增强设置页迁移到 Flutter——
  新 Pigeon B4 合约 + `/settings` 家族新增成员页 + 功能开关控制原生/Flutter
  切换（默认原生）+ B4 任务文档，本地提交，不推送，不动 `main`。
- 允许路径：`flutter_module/pigeons/b4_enhance_messages.dart`、
  `flutter_module/lib/b4_enhance_page.dart`、
  `flutter_module/lib/main.dart`、
  `flutter_module/lib/b3_settings_a_page.dart`、
  `flutter_module/lib/widgets/settings_row.dart`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B4HostApiImpl.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java`、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SettingFragment.java`、
  `.gitignore`、`.github/workflows/b8-c0.yml`、`docs/b8-b4-enhance.md`。
- 预存脏文件：无（开始时工作树干净）。
- 时间/时区：2026-11-17 12:11 CST（Asia/Shanghai）开始。
- 工期估计：只读发现 ~40 分钟 + Dart/Java ~90 分钟 + 文档验证提交
  ~30 分钟，约 14:50 CST 完成；构建与真机验证在 CI/手机上，不在本会话内。
- 最便宜的决定性验证：`git diff --check` + 作用域 diff 复核（共享文件只含
  加法行）+ `main` 未动检查 + 提交后工作树干净。构建验证只能走 CI（门 G6/G7）。

## 1. 选屏与理由

规划文档 §3 的 B4 范围句：**设置 B：增强设置页（族内新增一页）**。首次
证明的新东西：无（纯族内加法；规划 §3 B4 栏即如此）。原生实现
`SettingEnhanceFragment` 429 行（mobile），行多但扁平（规划 §2.1 记为
1 处显隐——即常年 `GONE` 的 `shellProxyConfig` 行，见 §2.6）。

28 行（显示顺序 = `SettingEnhanceFragment.reorderItems()` 的顺序，布局
XML 的顺序不是显示顺序；图标圆圈色与 chevron 有无见 §2.3）：

| # | 行（key） | 原生行为 | 状态来源（host 快照） |
| --- | --- | --- | --- |
| 1 | lab | 进实验室 `LabActivity`（main，与 TV 共用，仅打开原生页） | 固定文案 `setting_lab_summary` |
| 2 | labNav | 底栏实验室开关 | `Setting.isLabNav()` 开/关 |
| 3 | hkMiniapp | 海阔小程序开关 | `Setting.isHkMiniapp()` 开/关 |
| 4 | customCsp | 先申请文件权限，授权后开自定义 CSP 弹窗 | `CustomCspSetting.status()`（可用/开关/条数），不可用时错误态 |
| 5 | webHomeExtension | WebHome 扩展弹窗；长按清空 | `Setting.isWebHomeExtension()` + `WebHomeExtensionRegistry` 快照（就绪/已装数） |
| 6 | gitCloud | Git 云弹窗 | `GitCloudAccountStore.list().size()` |
| 7 | remoteTrust | 远程信任弹窗 | `RemoteStore.summary()` |
| 8 | oneKeySync | 一键同步弹窗（fire-and-forget） | 固定文案 `device_select` |
| 9 | loginState | 登录态学习弹窗 | `LoginStateSync`（已选/待确认数，学习中变体） |
| 10 | shellProxy | Shell 代理弹窗 | `Setting.isShellProxy()` + `ProxySetting.count()` |
| — | shellProxyConfig | **永不显示**（布局 `gone`，`initEvent` 再设 `GONE`） | 不进快照（见 §2.6） |
| 11 | managePage | 管理页弹窗（fire-and-forget） | 固定文案 `manage_page_web` |
| 12 | webHomeTheme | WebHome 主题弹窗 | `WebHomeThemeDialog.summary()` |
| 13 | webHomeFullscreen | WebHome 全屏开关 | `Setting.isWebHomeFullscreen()` 开/关 |
| 14 | cspWarmup | CSP 预热弹窗 | `Setting.getCspWarmupMode()`（默认/自定义条数/关） |
| 15 | playbackArtworkWall | 播放海报墙开关 | `Setting.isPlaybackArtworkWall()` 开/关 |
| 16 | driveCheck | 网盘检查开关 | `Setting.isDriveCheck()` 开/关 |
| 17 | siteName | 站点名弹窗 | `SiteNameStore.count()` |
| 18 | localReader | 进本地阅读（`FileActivity`，`read_mode=true`） | 固定文案 `setting_local_reader_summary` |
| 19 | novelSource | 小说源弹窗 | `NovelSourceConfig` 展示规则为空与否 |
| 20 | comicSource | 漫画源弹窗 | `ComicSourceConfig` 同上 |
| 21 | audioSource | 音频源弹窗 | `AudioConfig` 同上 |
| 22 | shortDramaSource | 短剧源弹窗 | `ShortDramaConfig` 同上 |
| 23 | siteHealthSort | 站点健康弹窗；长按清空 | `Setting.isSiteHealthSort()` + `SiteHealthStore.summary()`（站点/样本数） |
| 24 | debugLog | 调试日志开关；开后弹日志弹窗 | `Setting.isDebugLog()` 开/关 |
| 25 | searchCurrentSite | 当前站搜索开关 | `Setting.isSearchCurrentSiteDefault()` 开/关 |
| 26 | homeWidget | 首页组件开关 | `Setting.isHomeWidget()` 开/关 |
| 27 | playbackWebhook | 观看记录同步弹窗 | `ViewingRecordSyncStore.summary()` |
| 28 | pluginManager | 进插件管理 `PluginManagerActivity`（仅打开原生页，B19 待定不影响本行） | `PluginSourceConfig.get().entries().size()` |

- **移动端限定**：Flutter 入口只接在手机端 `SettingFragment` 的增强行与
  `performRow` 深链上。电视端 `SettingActivity`（leanback）继续走原生，
  TV 不在 B4 范围内。
- **B4 不做设置主屏**：族主页待 B7；B4 只在族内新增一页。

## 2. 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- **新开关**，不复用 B3 开关：`Setting` 里加 `isFlutterB4Enabled()` /
  `putFlutterB4Enabled(boolean)`，键 `flutter_b4_enabled`，默认 `false`。
  每批一个开关（规划 §4.1）；B4 必须能独立回滚而不动已验收/已实施屏的状态。
- 开关 UI 只在 C0 实验屏：B3 开关旁再加一个 SwitchListTile（“用 Flutter
  打开增强设置”），读/写走 B4 合约的 `isB4Enabled()` /
  `setB4Enabled(bool)`。开关只在实验屏出现，不进正式设置页。
- 入口改动（`SettingFragment.java`，唯一入口文件）：设置主屏增强行与
  `performRow` 深链共用一个新方法 `openEnhanceSettings()`（B3 V6 同款）——
  开 → `C1ScreenActivity.start(activity, ROUTE_SETTINGS + "?member=enhance")`；
  关 → 原样 `getRoot().change(3)`。原生实现一行未删。
- 回滚 = 关开关（实验屏上点一下），或 revert 本批提交；原生实现一行未删。

### 2.2 宿主 Activity 与引擎（复用 C1 引擎，不变量）

- 复用 `C1ScreenActivity` + `b8_c1_engine`；`ensureFlutterEngine` 里同时注册
  `B4Messages.B4HostApi`。不建第三个 engine。C0 引擎上同样注册 B4 合约
  （只为实验屏的开关用，与 C1/C2/B2/B3 做法一致）。
- **不变量**：C1 引擎的路由在引擎创建时固定，同一时刻只能有一个 C1 引擎屏
  存活，开新路由前前一屏必须已 finish。增强设置页符合该不变量（全屏屏，
  返回即 finish；点行打开的原生弹窗/ Activity 盖在上面时引擎不销毁、
  路由不变）。
- B4 不需要自己的 `getInitialRoute` / `closeScreen`，复用 C1/B3 的：路由分发
  仍走 `main()` 启动时的 `C1HostApi.getInitialRoute()`，返回键关闭走族壳
  的 `_handleBack()` → `B3HostApi.closeScreen()`（见 §2.3）。

### 2.3 Dart 路由与族内成员（规划 §4.4 的加法扩展）

- `main.dart` 的 `routes` 表加 1 条字面路由：`/settings?member=enhance`
  （B3 V9 同款字面路由；不动已有条目与路由机制）。
- `B3SettingsPage`（族壳）加法扩展：`_members` 表加 `'enhance'`，
  `onGenerateRoute` 加 `'/enhance'` 分支，返回 `EnhanceSettingsView`。
  其余成员与族壳逻辑一行未动；B5/B6/B7 加成员时同样只加一条字面路由
  与一个分支。
- 返回键：复用族壳的 `PopScope` + `_handleBack()`——族内 Navigator 能 pop
  时先 pop（B7 加族主页后才分得出），否则调 `closeScreen()` finish。
  增强页的 AppBar 返回按钮共用同一处理。
- 进程死亡后族内深度不恢复（只恢复到路由初始页），与规划 §4.4 的已知缺口
  一致，见 §6。
- 工具栏：原生 `MaterialToolbar`（标题 + GitHub/CNB 两个仓库菜单项）对应
  Dart 的 `AppBar` 标题 + 两个 `IconButton`（`actions`）；菜单标题文案由
  host 给（`repo_github` / `repo_cnb`），图标是 Dart 硬编码的 material 近似
  （见 §2.10 V8）。
- 行图标与圆圈色在 Dart 内硬编码（B3 V8 同款）：按
  `fragment_setting_enhance.xml` 逐行核实，圆圈色 violet→deepPurple、
  purple→purple、green→green、blue→blue、orange→orange；chevron 有无按布局
  逐行核实——无 chevron 的行：labNav、hkMiniapp、webHomeFullscreen、
  playbackArtworkWall、driveCheck、searchCurrentSite、homeWidget；其余
  21 行有 chevron（含 debugLog：虽是开关行，布局里仍有 chevron，见 §2.10 V5）。

### 2.4 Pigeon B4 合约（最小扩展，只够增强页用）

新文件 `flutter_module/pigeons/b4_enhance_messages.dart`，CI 内一次生成
Dart + Java 两边。数据类只做扁平字段；**全部展示文案与计算值由 host 返回**
（`getString` 解析后的当前语言文案 + bean getter 算好的显示值 + host 算好
的错误标志），Dart 侧不硬编码文案、不算条件显隐、不拼状态文案。
Java 侧 `bool`/`int` 一律用装箱类型（`Boolean`/`Long`，C1 教训）；方法名与
Dart 定义字符级一致，绝不推断大小写归一（C1 的 `setSubtitleAutoMatch` 一案）。

```dart
@HostApi()
abstract class B4HostApi {
  bool isB4Enabled();
  void setB4Enabled(bool enabled);
  B4EnhanceSettings getEnhanceSettings();
  void toggleLabNav(); void toggleHkMiniapp(); void toggleDriveCheck();
  void toggleDebugLog(); void toggleSearchCurrentSite(); void toggleHomeWidget();
  void togglePlaybackArtworkWall(); void toggleWebHomeFullscreen();
  void openLab(); void openLocalReader(); void openPluginManager();
  @async void openSiteName();
  @async void openNovelSource(); @async void openComicSource();
  @async void openAudioSource(); @async void openShortDramaSource();
  @async void openSiteHealthSort(); @async void openWebHomeExtension();
  @async void openWebHomeTheme(); @async void openCspWarmup();
  @async void openPlaybackWebhook();
  void openManagePage();
  @async void openRemoteTrust(); @async void openGitCloud();
  @async void openShellProxy(); @async void openLoginState();
  @async void openCustomCsp();
  void openOneKeySync();
  void clearSiteHealth(); void clearWebHomeExtension();
  void openGithubRepo(); void openCnbRepo();
}

class B4EnhanceRow {
  String key; String title; String text; bool hasError;
}

class B4EnhanceSettings {
  String screenTitle; String repoGithubTitle; String repoCnbTitle;
  List<B4EnhanceRow> rows;
}
```

- 快照形态（偏差 V1，见 §2.10）：不用 28 行 × 标题/值/错误标志的命名
  字段（约 70 个字段，笔误面大），改用一个键控行列表
  `List<B4EnhanceRow>`，顺序即原生 `reorderItems()` 的显示顺序；`key` 是
  固定 token（`lab`、`labNav`、…），Dart 按 key 取图标/圆圈色/chevron 与
  点按处理。`List<数据类>` 字段是 C2 已证明的形态（`List<C2HistoryItem>`）。
- 开关写回是同步方法（host 直接写 Setting，Dart 调后重取快照）；
  弹窗类方法是 `@async`（见 §2.5 的弹窗契约）；火后即忘的导航/弹窗是
  同步 `void` 方法。
- `List<B4EnhanceRow>` 返回值与 `VoidResult` 形态沿用 C1/C2/B2/B3 已验证
  形态，仍待 CI 证明（§6 第 1 项）。

### 2.5 弹窗契约（沿用 B3 V1）

原生增强页的交互几乎全是弹窗；弹窗不触发 Activity 的 pause/resume
（C2 §2.8 的 D2 教训），所以“弹窗关闭后 resumed 重取”对弹窗不成立。
B4 的弹窗方法一律是 `@async`，完成语义按弹窗能否观测到关闭分两档
（B3 §2.5 同款，V1）：

- **有关闭钩子的弹窗**，host 在关闭时完成回调，Dart `await` 后重取：
  小说/漫画/音频/短剧源弹窗（`onDismiss`，任意关闭路径都触发）、
  观看记录同步弹窗（回调在 `onDismiss` 里跑）。
- **只有变化回调、没有关闭钩子的弹窗**，host 只在变化回调里完成回调，
  取消关闭时不完成——取消本来就没有改动，无需刷新。Dart 侧**从不 await
  这些调用**，只挂一个 `.then` 重取延续（`afterDialog`）：变化时重取，
  取消时延续永不触发、页面无任何阻塞。涉及：站点名（`onChanged`）、
  站点健康 / WebHome 扩展 / WebHome 主题 / CSP 预热 / 远程信任 / Git 云 /
  Shell 代理 / 登录态（`show(activity, callback)` 的回调）。
- **自定义 CSP 行**：先经 `PermissionUtil.requestFile(activity, …)`
  申请文件权限（PermissionX，走 host Activity；原生用的是 Fragment
  重载，同一 PermissionX 调用，见 §2.10 V9），授权后开弹窗；拒绝时 host
  弹 Toast，不完成回调（Dart 不 await，无事可做）。
- **不改状态的弹窗/导航**是同步 fire-and-forget：调试日志弹窗（开后弹出，
  不改本页状态）、管理页弹窗、一键同步弹窗、实验室/本地阅读/插件管理
  三个原生 Activity 入口、两个仓库外链。
- 每个成员页仍保留 `resumed` 重取（C1/C2 同款），覆盖切后台再回、播完返回
  等路径；弹窗关闭后的刷新只靠上述 `@async` 完成，不靠 resumed。

### 2.6 显隐：只有一行，且永不显示

规划 §2.1 记增强页有“1 处显隐”，即 `shellProxyConfig` 行：布局里
`android:visibility="gone"`，`initEvent` 里又显式设 `GONE`，`setText` 里
虽会给它写值但永远不可见。Dart 快照里**没有这一行**——与原生一致
（偏差 V4）。本页没有其他运行时显隐条件：`setText()` 只写文案，
`reorderItems()` 只调顺序。

### 2.7 两处原生 HomeActivity 调用：本批有意不做

原生 `setLabNav()` / `setWebHomeFullscreen()` 里各有一处对手机端
`HomeActivity` 的调用，impl 在 main 源码集无法直接引用该类
（mobile-only），且 Flutter 屏是盖在 `HomeActivity` 之上的独立 Activity：

- **labNav**：原生调 `activity.refreshNavigation()` 立即刷新底栏。
  不做的原因：`HomeActivity.onResume()` 里已有 `lastLabNav !=
  Setting.isLabNav()` 时调 `setNavigation()` 的检查——从 Flutter 屏返回后
  底栏必然刷新；Flutter 屏打开时底栏被盖住，立即刷新不可见。行为在
  返回时与原生一致（偏差 V6）。
- **webHomeFullscreen**：原生在关闭时调 `setWebHomeChrome(normal)` +
  `refreshWebHomeChromeState()`。不做的原因：impl 够不到后台的
  `HomeActivity` 实例；`Setting` 已持久化，`WebHomeChromeController`
  与 `HomeWebController` 在下次 WebHome 加载/页面事件时按此开关重新取
  值（`getMode()` 与各 `applyDefaultChrome` 调用点均读
  `Setting.isWebHomeFullscreen()`）。行为是“下次 WebHome 加载时生效”
  而非“立即生效”（偏差 V7，验收 §4.7 写明）。

### 2.8 行错误态：host 包 try/catch，Dart 染红

原生 `SettingEnhanceFragment.setText()` 用 `safeRun` 包住每行摘要计算：
异常时该行显示 `error_config_get` 文案 + 错误色 + 深底（设计文档 §9.9
的错误色要求）。B4 在 host 侧镜像（B3 §2.8 同款）：`getEnhanceSettings()`
里每行摘要计算包在 `safeRow()` 里，失败时返回错误文案 + `hasError`
标志；Dart 侧 `SettingsRow(isError: true)` 把值文案染红（近似原生错误色；
深底未复刻，B3 V7 同款）。涉及全部带计算值的行；3 行固定文案
（lab、localReader、oneKeySync）不参与。`customCsp` 与 `shellProxy`
两行各有自己的 `set*Text()` 安全包装，host 内同样镜像（含
`CustomCspSetting.status().available() == false` 时的错误态）。

### 2.9 mobile-only 类与资源：全部在 main 源码集，无反射

impl 在 main 源码集（leanback 构建也要编译它），本批的 18 个弹窗、全部
Store/Config 与两个 Activity 入口**全部在 main 源码集**，可直接引用，
本批**零反射**（B3 的 4 处反射是 mobile-only 弹窗，本批没有）。

| 引用 | 源码集 | 打开/调用方式 |
| --- | --- | --- |
| 18 个弹窗（站点名/小说/漫画/音频/短剧源、调试日志、CSP 预热、自定义 CSP、Git 云、登录态、管理页、一键同步、远程信任、Shell 代理、站点健康、观看记录同步、WebHome 扩展/主题） | 全部 main | 直接调用；`show(FragmentActivity, …)` / `create(activity).onChanged/onDismiss().show()` 均有 Activity 重载 |
| `LabActivity`、`PluginManagerActivity` | main | `start(activity)` 直接调用 |
| `FileActivity` | mobile + leanback 各一份 | 直接引用（B2 V5 同款先例：两端源码集均存在且同包同名；本屏无 leanback 入口，调用不可达） |
| 全部 Store/Config（`SiteNameStore`、`SiteHealthStore`、`CustomCspSetting`、`ProxySetting`、`WebHomeExtensionRegistry`、`RemoteStore`、`GitCloudAccountStore`、`LoginStateSync`、`ViewingRecordSyncStore`、`NovelSourceConfig`、`ComicSourceConfig`、`AudioConfig`、`ShortDramaConfig`、`PluginSourceConfig`） | 全部 main | 直接调用 |
| `PermissionUtil.requestFile` | main | `requestFile(FragmentActivity, Consumer<Boolean>)`（PermissionX） |

**资源核查（本批无 mobileString）**：B4 用到的 52 个 `R.string` 已按目录
分别 grep 核实（main / leanback / mobile，不用 `head` 截断——B2 的
`dialog_delete_record` 教训）：**全部在 main res**，impl 可直接引用，
无需运行时解析。核查中曾把登录态的两个字符串误写作
`setting_login_state_count` / `setting_login_state_learning_count` 而报
“不存在”；实际名是 `login_state_count` /
`login_state_learning_count`（无 `setting_` 前缀），在 main res
`values/strings.xml:1216-1217`，已逐字核对 Java 引用与资源定义一致。

### 2.10 相对规划的全部偏差

| # | 偏差 | 理由 |
| --- | --- | --- |
| V1 | 快照用键控行列表 `List<B4EnhanceRow>`，不用 28 行的命名逐字段 | 28 行 × 标题/值/错误标志 ≈ 70 个命名字段，笔误面大；行列表顺序即原生 `reorderItems()` 顺序，key 是固定 token；`List<数据类>` 字段是 C2 已证明的形态 |
| V2 | 成员页辅助 mixin（resumed 重取 / `afterDialog` / `act` / 三种脚手架）从 B3 的 `_B3MemberState` 复刻为私有的 `_B4MemberState` | B3 的 mixin 是文件私有的；每批独立 revert 的纪律（规划 §4.9）要求 B4 不动 B3 代码；与 B2 的 host 复刻同款纪律 |
| V3 | `SettingsRow` 加可选参数 `onLongPress` | 只加法：站点健康 / WebHome 扩展两行的长按清空需要；已有调用方不受影响（参数默认 null） |
| V4 | `shellProxyConfig` 行不进快照、不渲染 | 原生里它永为 `GONE`；这是规划记的“1 处显隐”的全部内容 |
| V5 | debugLog 行保留 chevron（虽是开关行） | 布局 `fragment_setting_enhance.xml` 里该行确有 chevron；照布局镜像，不按语义推断 |
| V6 | labNav 切换不调 `HomeActivity.refreshNavigation()` | 见 §2.7：`HomeActivity.onResume` 的 `lastLabNav` 检查覆盖返回时刷新；Flutter 屏盖住底栏时立即刷新不可见 |
| V7 | webHomeFullscreen 切换不调 `setWebHomeChrome` / `refreshWebHomeChromeState()` | 见 §2.7：impl 够不到后台的 mobile `HomeActivity`；开关已持久化，WebHome 下次加载时按开关取值；立即生效改为下次加载生效 |
| V8 | 行图标与圆圈色在 Dart 内硬编码（material 图标近似原生 drawable；工具栏两个仓库图标用 `Icons.code_outlined` / `Icons.cloud_outlined` 近似 `ic_repo_github` / `ic_repo_cnb`） | 图标是纯展示选择，非本地化文案；B3 V8 同款做法；chevron 与圆圈色按布局逐行核实 |
| V9 | 自定义 CSP 的权限申请走 `PermissionUtil.requestFile(activity, …)`（Activity 重载） | 原生用的是 Fragment 重载；同一 PermissionX 调用，Activity 重载存在；拒绝时 Toast、不完成回调（Dart 不 await） |
| V10 | 开关值文案用 `setting_enable` / `setting_disable`（原生增强页的 `getSwitch`），不用 B3 各页的 `setting_on` / `setting_off` | 照原生增强页逐字镜像；两组字符串都在 main res |

### 2.11 哪些东西明确不做

- 不碰 Room/设置 schema（评估文档 §7 硬性前提）；只读写已有 `Setting`
  键 + 1 个新开关布尔键；`labNav` / `webHomeFullscreen` 的原生
  HomeActivity 副作用见 §2.7 / V6/V7。
- 不碰播放器、蜘蛛、实验室、小程序；不改 `C0HostApi` / `C1HostApi` /
  `C2HostApi` / `B2HostApi` / `B3HostApi` 合约与实现（C0/C1/C2/B2/B3 冻结）；
  不改 `SettingEnhanceFragment`、`SettingFragment` 的其他行、
  `HomeActivity`、任何弹窗类、任何 Store/Config。
- 不回头重构 B3 的 4 个成员页（冻结）——共享 `SettingsRow` 只加了一个
  可选参数；不改 C2 历史页与 B2 收藏页（冻结）。
- 不新增 Dart 依赖；不改 Flutter/Pigeon 版本锁定。
- 不改 `android-release.yml`；B4 构建仍走 `b8-c0.yml`（只加 Pigeon 生成命令）。
- 不改任何电视端代码与布局；不改 `ImgUtil.java`。
- 不做设置主屏（B7）、个人设置与设置内搜索（B5）、播放器设置（B6，
  待 P1）。

## 3. 文件清单

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `flutter_module/pigeons/b4_enhance_messages.dart` | 新：B4HostApi + `B4EnhanceSettings` / `B4EnhanceRow`（扁平数据类） |
| 2 | `flutter_module/lib/b4_enhance_page.dart` | 新：`EnhanceSettingsView`（族成员页）+ `_B4MemberState`（B3 mixin 的复刻） |
| 3 | `flutter_module/lib/main.dart` | 改：`/settings?member=enhance` 路由 + 实验屏 B4 开关 |
| 4 | `flutter_module/lib/b3_settings_a_page.dart` | 改：只加 `'enhance'` 成员与 `'/enhance'` 分支（加法） |
| 5 | `flutter_module/lib/widgets/settings_row.dart` | 改：只加可选参数 `onLongPress`（加法） |
| 6 | `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B4HostApiImpl.java` | 新：host 实现（快照/开关/弹窗/长按/外链） |
| 7 | `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java` | 改：注册 `B4HostApi` + 类注释提及增强页 |
| 8 | `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java` | 改：C0 引擎注册 `B4HostApi`（只为开关） |
| 9 | `app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java` | 改：加 `flutter_b4_enabled` 开关方法 |
| 10 | `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SettingFragment.java` | 改：增强行 + `performRow` 深链分流（共用新方法 `openEnhanceSettings()`） |
| 11 | `.gitignore` | 改：加 `B4Messages.java` |
| 12 | `.github/workflows/b8-c0.yml` | 改：Pigeon 步骤加 b4 生成 + 存在性检查 |
| 13 | `docs/b8-b4-enhance.md` | 新：本文档 |

## 4. 逐项对照清单（Wei 真机验收用）

先在实验屏打开 B4 开关，再进设置页对照；每项与原生（开关关闭后）逐条比。

### 4.1 列表与行

- [ ] 开关开：设置页“增强设置”行进 Flutter 页；开关关：进原生页。
- [ ] 28 行顺序与原生一致（lab / 底栏实验室 / 海阔小程序 / 自定义 CSP /
      WebHome 扩展 / Git 云 / 远程信任 / 一键同步 / 登录态 / Shell 代理 /
      管理页 / WebHome 主题 / WebHome 全屏 / CSP 预热 / 播放海报墙 / 网盘检查 /
      站点名 / 本地阅读 / 小说源 / 漫画源 / 音频源 / 短剧源 / 站点健康 /
      调试日志 / 当前站搜索 / 首页组件 / 观看记录同步 / 插件管理）；
      `shellProxyConfig` 行不出现（原生同样不出现）。
- [ ] 每行标题与值文案与原生一致；开关行显示“启用/停用”（不是“开/关”）。
- [ ] 工具栏标题“增强设置”与两个仓库图标都在；点 GitHub 图标进浏览器打开
      Mooyingshi 仓库，点 CNB 图标进浏览器打开 CNB 页。
- [ ] 返回键退出页面回到设置主屏。

### 4.2 开关行（8 个）

- [ ] 底栏实验室 / 海阔小程序 / 网盘检查 / 调试日志 / 当前站搜索 / 首页组件 /
      播放海报墙 / WebHome 全屏：点后开/关翻转，退出重进后状态保持。
- [ ] 调试日志开后出现原生日志弹窗；关后不出现。
- [ ] 底栏实验室开关后，回到首页时底栏第 5 项在实验室/本地源之间切换
      （与原生一致；Flutter 屏内不立即变，返回后变，见 §2.7 V6）。
- [ ] WebHome 全屏开关后，WebHome 的全屏/普通 chrome 在下次 WebHome 加载时
      变化（与原生“立即变”不同，见 §2.7 V7）。

### 4.3 弹窗行（13 个 @async + 3 个 fire-and-forget）

- [ ] 站点名行点后出现原生站点名弹窗，改完关闭后行内条数已更新。
- [ ] 小说/漫画/音频/短剧源行点后出现原生源弹窗，改完关闭后行内开/关已更新。
- [ ] 站点健康行点后出现原生弹窗，改完关闭后行内文案已更新；**长按**该行
      出现清空效果（Toast + 行内文案变回仅开关值）。
- [ ] WebHome 扩展行点后出现原生弹窗，改完关闭后行内“开 · 就绪/已装”已更新；
      **长按**该行清空扩展（Toast + 行内数字归零）。
- [ ] WebHome 主题行点后出现原生主题弹窗，选完关闭后行内主题名已更新。
- [ ] CSP 预热行点后出现原生弹窗，改完关闭后行内模式文案已更新。
- [ ] 观看记录同步行点后出现原生弹窗，改完关闭后行内文案已更新。
- [ ] 远程信任行点后出现原生弹窗，改完关闭后行内文案已更新。
- [ ] Git 云行点后出现原生弹窗，改完关闭后行内账号数已更新。
- [ ] Shell 代理行点后出现原生弹窗，改完关闭后行内“开 · 规则数”已更新。
- [ ] 登录态行点后出现原生学习弹窗，改完关闭后行内“已选/待确认”已更新。
- [ ] 自定义 CSP 行：有文件权限时点后出现原生弹窗，改完关闭后行内文案已更新；
      无权限时先出现系统权限申请，拒绝后出现 Toast、无弹窗。
- [ ] 管理页行点后出现原生管理页弹窗；一键同步行点后出现原生同步弹窗。
- [ ] 各弹窗取消关闭后，行内文案不变（与原生一致）。

### 4.4 导航行（3 个）

- [ ] 实验室行点后进原生实验室页；返回后仍在增强设置页。
- [ ] 本地阅读行点后进原生文件页（阅读模式）；返回后仍在增强设置页。
- [ ] 插件管理行点后进原生插件管理页；返回后仍在增强设置页。

### 4.5 族路由与开关

- [ ] 从设置主屏进增强页、从设置内搜索结果进增强页，开关开时都走 Flutter，
      关时都走原生（深链已分流，见 §2.1）。
- [ ] 族内返回：增强页返回键退出到设置主屏；旋转、切后台再回不崩溃，状态还在。
- [ ] 实验屏 B4 开关开/关立即生效（下次进增强页）；开关状态重启 App 后保留。
- [ ] 电视端设置页不受影响（无 Flutter 入口）。

## 5. 回滚步骤

- **实验屏上关 B4 开关**，增强设置页立即回到原生。
- **完整回滚**：revert 本批提交；无需改 main。
- **失败时**：`b8-c0` 产物不发布；用实验屏开关/回滚恢复；C1/C2/B2/B3
  验收状态不受 B4 回滚影响（独立开关、独立合约、独立 impl）。

## 6. 未能验证的项

| # | 项 | 风险/备注 |
| --- | --- | --- |
| G6-1 | Pigeon codegen：`List<B4EnhanceRow>` 字段 + 15 个 `@async void` 方法（`VoidResult`） | C2 已证明 `List<数据类>` 字段与 `@async void`；B4 是同一形态的新文件，CI 生成时校验 |
| G6-2 | `flutter analyze`（一个警告即失败，C1 #8 教训） | 本地无 Dart，全部 Dart 文件未静态检查；CI 失败先看分析日志再修；`switch` 无 default 分支 + 私有 mixin 复刻是本批的新点 |
| G6-3 | Gradle 编译：手写 `B4HostApiImpl` vs 生成接口 | 装箱类型、方法名与 Dart 定义字符级一致（C1 的教训）；`FileActivity` 直接引用（B2 V5 同款先例）与 `PermissionUtil.requestFile` Activity 重载以 CI 日志为准 |
| G6-4 | `b8-c0.yml` 4 风味 release 构建（mobile/arm64、mobile/arm、TV、leanback） | 本批无直接 mobile-only 引用（全部 52 个字符串已核实在 main res；18 个弹窗与全部 Store 均在 main 源码集；零反射）；待 CI 证明 leanback 风味构建通过 |
| G6-5 | R8/minify | 本批零反射、零 Proxy；Pigeon 生成代码的混淆表现待 release 构建 + 真机证明 |
| G7-1 | 族内新增成员：`/settings?member=enhance` 经 `getInitialRoute()` 的精确匹配 | 字面路由须与 host 拼出的字符串字符级一致；拼错则回落到 `/`（C0 占位屏），验收时可见 |
| G7-2 | 弹窗 `@async` 契约：变化时重取、取消时无事可做（B3 V1） | 需逐个弹窗验收（§4.3 各弹窗项）；取消后页面文案必须与原生一致（无改动） |
| G7-3 | `SettingsRow` 长按：`onLongPress` 在 `InkWell` 上的行为 | 长按清空是本批的新交互；验收 §4.3 的两项长按 |
| G7-4 | 工具栏两个仓库外链 | 点后进浏览器；无浏览器时 Toast；验收 §4.1 |
| G7-5 | WebHome 全屏开关的延迟生效（V7） | 原生立即变、Flutter 版下次 WebHome 加载时变；验收 §4.2 的对应项 |
| G7-6 | 底栏实验室开关的返回时刷新（V6） | 返回首页后底栏切换；验收 §4.2 的对应项 |
| G7-7 | 自定义 CSP 的权限流（V9） | 授权/拒绝两条路；拒绝后 Toast、无弹窗；验收 §4.3 的对应项 |
| G7-8 | §4 对照清单逐项通过；开关关闭后回到原生 | Wei 真机验收 |

## 7. Recovery anchor（恰好一个下一步：B5）

**下一步：B5**——按 `docs/b8-full-migration-plan.md` §3 的批次顺序，继续下一批
（B5：个人设置页 + 设置内搜索，首个文本输入切面）。不推送、不编包；各批
完成后统一联合推送（Wei 2026-11-17 10:44 指示）。

---

*任务护栏：`b8-b4-enhance-settings`（见 §0）。提交与恢复标签见提交记录。*
