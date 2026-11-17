# B8 B3：设置 A 组迁移到 Flutter（实施记录）

> 状态：**已实施（本地提交，未推送，未经 CI/真机验证）**。评估与阶段定义见
> `docs/b8-flutter-rewrite-assessment.md`（§5 方案 C、§6 后续阶段 go/no-go、
> §7 回滚路径）；全量批次规划见 `docs/b8-full-migration-plan.md`（§3 的 B3
> 范围句 + §4 共性决策全部适用；本文档按它实施，全部偏差见 §2.10）；
> C0/C1/C2/B2 已验证形态见 `docs/b8-c0-shell.md`、
> `docs/b8-c1-about-subtitle.md`、`docs/b8-c2-history.md`（§2.9 的 D1–D8 是
> 既定决策，必须沿用）、`docs/b8-b2-keep.md`（§2.10 的 V1–V8 与
> `mobileString()` 做法、leanback 资源核查教训：按目录分别 grep，不用
> `head` 截断）。分支：`b8-flutter-rewrite`；基线：HEAD `3737d050`
> （leanback 修复提交）；`main` 未动。
>
> **诚实声明**：本机无 Java、Android SDK、Flutter/Dart，一切构建与运行结论
> 均未在本地验证。所有“待 CI 证明 / 待真机证明”项见 §6，不得视为已验证。

## 0. §1 执行声明（仓库 AGENTS.md）

- 完成句：B3 在 `b8-flutter-rewrite` 上把手机端设置 A 组（TMDB / AI / 去广告 /
  弹幕 4 个子设置页）迁移到 Flutter——新 Pigeon B3 合约 + `/settings` 家族
  路由与族内 Navigator + 新共享组件 `SettingsRow`（只加法）+ 功能开关控制
  原生/Flutter 切换（默认原生）+ B3 任务文档，本地提交，不推送，不动 `main`。
- 允许路径：`flutter_module/pigeons/b3_settings_a_messages.dart`、
  `flutter_module/lib/b3_settings_a_page.dart`、
  `flutter_module/lib/widgets/settings_row.dart`、
  `flutter_module/lib/main.dart`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B3HostApiImpl.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java`、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SettingFragment.java`、
  `.gitignore`、`.github/workflows/b8-c0.yml`、`docs/b8-b3-settings-a.md`。
- 预存脏文件：无（开始时工作树干净）。
- 时间/时区：2026-11-17 12:02 CST（Asia/Shanghai）开始。
- 工期估计：只读发现 ~35 分钟 + Dart/Java ~75 分钟 + 文档验证提交
  ~30 分钟，约 14:10 CST 完成；构建与真机验证在 CI/手机上，不在本会话内。
- 最便宜的决定性验证：`git diff --check` + 作用域 diff 复核（共享文件只含
  加法行）+ `main` 未动检查 + 提交后工作树干净。构建验证只能走 CI（门 G6/G7）。

## 1. 选屏与理由

规划文档 §3 的 B3 范围句：**设置 A：TMDB + AI + 广告 + 弹幕 4 个子设置页；
建 `/settings` 族路由与族内 Navigator（§4.4），首批只含 4 个子页（无 sub
参数时默认首个子页，族主页待 B7）**。首次证明的新东西：**族路由 + 条件显隐链
（弹幕）**。

4 个原生页实测（行数均为 `wc -l`，入口均为 `SettingFragment` 的设置主屏行
与 `performRow` 深链，见 §2.1）：

| 页 | 原生实现 | 行结构与状态来源 | 弹窗 / 特殊点 |
| --- | --- | --- | --- |
| TMDB 设置 | `SettingTmdbFragment` 147 行（mobile） | 6 行：TMDB 源（已配置/未配置）、详情打开方式（5 选 1 单选弹窗）、详情主题（3 选 1，`isTmdbMode` 时可见）、TMDB 匹配模式（点按循环）、剧集文件大小开关、历史聚合开关（`isTmdbReady` 时可见，写后发 `RefreshEvent.history()`） | 源弹窗 `TmdbSourceDialog`（main）；打开方式/主题弹窗是 fragment 内联的单选弹窗，无可复用入口 |
| AI 设置 | `SettingAiFragment` 188 行（mobile） | 6 行：AI 推荐（关/已配置/未配置，`AiConfig`）、个性化推荐（开有关闭确认弹窗）、推荐反馈（条数）、实时字幕模型（状态文案 + 下载/删除流）、AI 字幕最大并发（数字弹窗 1–8）、AI 字幕分块数（数字弹窗 1–32）；字幕 AI 组 `isAiConfigReady` 时可见 | `AiConfigDialog`（main）；反馈弹窗 `RecommendationFeedbackDialog`（mobile-only）；模型弹窗 `SubtitleSettingsDialog.showRealtimeModel`（main）+ 数字弹窗 `showNumber`（main） |
| 广告设置 | `SettingAdFragment` 169 行（mobile） | 6 行：去广告总开关、AI 广告检测（`isAiConfigReady` 时可见）、广告规则管理（规则数 + 待处理数）、广告拦截统计（无值文案）、自动跳过片头片尾（循环）、跳过类型（多选弹窗摘要）；每行摘要计算包在 `safeRun` 里，失败显示错误态 | 规则管理 `AdRuleManageDialog`（mobile-only）、统计 `AdBlockStatsDialog`（mobile-only）、跳过类型 `IntroSkipKinds.show`（main） |
| 弹幕设置 | `SettingDanmakuFragment` 105 行（mobile） | 4 行：弹幕加载开关、弹幕源（有/无，`load` 时可见）、自动加载开关（`load && (有有效 API \|\| 包内源)` 时可见）、蜘蛛优先开关（`load && 有有效 API && 自动` 时可见）；四级条件显隐链 | 弹幕源弹窗 `DanmakuApiDialog`（mobile-only，`show(Fragment)` + `DanmakuListener` 回调） |

- **移动端限定**：Flutter 入口只接在手机端 `SettingFragment` 的 4 行与
  `performRow` 深链上。电视端 `SettingActivity`（leanback）继续走原生，
  TV 不在 B3 范围内。
- **B3 不做设置主屏**：族主页待 B7；B3 只建族路由壳与注册表，首批 4 个子页。

## 2. 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- **新开关**，不复用 C1/C2/B2 开关：`Setting` 里加 `isFlutterB3Enabled()` /
  `putFlutterB3Enabled(boolean)`，键 `flutter_b3_enabled`，默认 `false`。
  每批一个开关（规划 §4.1）；B3 必须能独立回滚而不动已验收/已实施屏的状态。
- 开关 UI 只在 C0 实验屏：B2 开关旁再加一个 SwitchListTile（“用 Flutter 打开
  TMDB / AI / 去广告 / 弹幕设置”），读/写走 B3 合约的 `isB3Enabled()` /
  `setB3Enabled(bool)`。开关只在实验屏出现，不进正式设置页。
- 入口改动（`SettingFragment.java`，唯一入口文件）：4 行设置主屏行与 4 处
  `performRow` 深链共用 4 个新方法 `openTmdbSettings()` /
  `openAiSettings()` / `openAdSettings()` / `openDanmakuSettings()`——开 →
  `C1ScreenActivity.start(activity, ROUTE_SETTINGS + "?member=tmdb|ai|ad|danmaku")`；
  关 → 原样 `getRoot().change(7|8|9|4)`。深链一并分流（偏差 V6）：否则设置内
  搜索结果会绕过开关进原生页。原生实现一行未删。
- 回滚 = 关开关（实验屏上点一下），或 revert 本批提交；原生实现一行未删。

### 2.2 宿主 Activity 与引擎（复用 C1 引擎，不变量）

- 复用 `C1ScreenActivity` + `b8_c1_engine`，加 `ROUTE_SETTINGS = "/settings"`；
  `ensureFlutterEngine` 里同时注册 `B3Messages.B3HostApi`。不建第三个
  engine。C0 引擎上同样注册 B3 合约（只为实验屏的开关用，与 C1/C2/B2 做法一致）。
- **不变量**：C1 引擎的路由在引擎创建时固定，同一时刻只能有一个 C1 引擎屏
  存活，开新路由前前一屏必须已 finish。设置族屏符合该不变量（全屏屏，返回
  即 finish；点行打开的原生弹窗盖在上面时引擎不销毁、路由不变）。
- B3 不需要自己的 `getInitialRoute` / `closeScreen`，复用 C1 的：路由分发
  仍走 `main()` 启动时的 `C1HostApi.getInitialRoute()`，返回键关闭走
  `C1HostApi.closeScreen()`（族内见 §2.3）。

### 2.3 Dart 路由与族内 Navigator（规划 §4.4 的首次落地）

- `main.dart` 的 `routes` 表加 5 条字面路由：`/settings`（无 member 时默认
  首个子页 TMDB）与 `/settings?member=tmdb|ai|ad|danmaku`。字面路由是因为
  `routes` 表只做精确字符串匹配；B4/B5/B6/B7 加成员时同样只加一条字面路由，
  不动已有条目与路由机制（偏差 V9）。
- `B3SettingsPage(member)` 是族壳：内含一个 `Navigator`（`GlobalKey`），初始
  路由为 `/$member`，`onGenerateRoute` 按成员名返回对应的成员页；未知成员
  回落到 `/tmdb`。族主页（B7）落地时成为该 Navigator 的初始页，成员页改为
  族内 push——新增成员只加一页与一条注册表条目，不改现有成员（规划 §4.4）。
- 返回键：族壳包 `PopScope(canPop: false, onPopInvokedWithResult: ...)`——内
  Navigator 能 pop 时先 pop，否则调 `closeScreen()` finish。成员页 AppBar
  的返回按钮共用同一处理（`onBack` 回调进族壳）。B3 只有一页在栈内时，返回
  即 finish；B7 加族主页后，返回先回到族主页（与原生 `change()` 回设置主屏
  一致）。
- 进程死亡后族内深度不恢复（只恢复到路由初始页），与规划 §4.4 的已知缺口
  一致，见 §6。

### 2.4 Pigeon B3 合约（最小扩展，只够 4 个子页用）

新文件 `flutter_module/pigeons/b3_settings_a_messages.dart`，CI 内一次生成
Dart + Java 两边。数据类只做扁平字段；**全部展示文案与计算值由 host 返回**
（`getString` 解析后的当前语言文案 + bean getter 算好的显示值 + host 算好
的可见性布尔值），Dart 侧不硬编码文案、不算条件显隐、不拼状态文案。
Java 侧 `bool`/`int` 一律用装箱类型（`Boolean`/`Long`，C1 教训）；方法名与
Dart 定义字符级一致，绝不推断大小写归一（C1 的 `setSubtitleAutoMatch` 一案）。

```dart
@HostApi()
abstract class B3HostApi {
  bool isB3Enabled();
  void setB3Enabled(bool enabled);
  B3TmdbSettings getTmdbSettings();
  @async void openTmdbSource();
  @async void openDetailOpenMode();
  @async void openDetailThemeMode();
  void cycleTmdbMatchMode();
  void toggleTmdbEpisodeFileSize();
  void toggleHistoryAggregation();
  B3AiSettings getAiSettings();
  @async void openAiConfig();
  @async void togglePersonalRecommendation();
  @async void openRecommendationFeedback();
  @async void openRealtimeModel();
  @async void setSubtitleAiMaxConcurrency();
  @async void setSubtitleAiChunkCount();
  B3AdSettings getAdSettings();
  void toggleAdblock();
  void toggleAiAdDetection();
  @async void openAdRuleManage();
  void openAdBlockStats();
  void cycleAutoSkipIntroOutro();
  @async void openIntroSkipKinds();
  B3DanmakuSettings getDanmakuSettings();
  void toggleDanmakuLoad();
  @async void openDanmakuApi();
  void toggleDanmakuAuto();
  void toggleDanmakuSpider();
  void closeScreen();
}
```

- 快照类（各页一个）：`B3TmdbSettings`（屏标题 + 6 行的标题/值文案 + 2 个
  可见性布尔值）、`B3AiSettings`（屏标题 + 2 个组标题 + 6 行的标题/值文案 +
  字幕 AI 组可见性 + 实时模型下载中/删除中布尔值 + 并发数/分块数 int）、
  `B3AdSettings`（屏标题 + 3 个组标题 + 6 行的标题/值文案 + 1 个可见性布尔值
  + 5 个错误布尔值，见 §2.8）、`B3DanmakuSettings`（屏标题 + 4 行的标题/值
  文案 + 3 个可见性布尔值，见 §2.6）。
- 开关/循环写回是同步方法（host 直接写 Setting，Dart 调后重取快照）；
  弹窗类方法是 `@async`（见 §2.5 的弹窗契约）。
- `List` 返回值本批未用；`Long`/`Boolean` 装箱与 `VoidResult` 形态沿用
  C1/C2/B2 已验证形态，仍待 CI 证明（§6 第 2 项）。

### 2.5 弹窗契约（本批的核心设计决策）

原生子设置页的交互几乎全是弹窗；弹窗不触发 Activity 的 pause/resume
（C2 §2.8 的 D2 教训），所以“弹窗关闭后 resumed 重取”对弹窗不成立。
B3 的弹窗方法一律是 `@async`，完成语义按弹窗能否观测到关闭分两档：

- **有关闭钩子的弹窗**，host 在关闭时完成回调，Dart `await` 后重取：
  `TmdbSourceDialog` / `AiConfigDialog` 的 `onDismiss`（任意关闭路径都触发）、
  详情打开方式/主题单选弹窗与个性化推荐确认弹窗（host 内联创建，
  `setOnDismissListener`，见 §2.10 V3）。
- **只有变化回调、没有关闭钩子的弹窗**，host 只在变化回调里完成回调，
  取消关闭时不完成——取消本来就没有改动，无需刷新。Dart 侧**从不 await
  这些调用**，只挂一个 `.then` 重取延续（`afterDialog`）：变化时重取，
  取消时延续永不触发、页面无任何阻塞。这是相对规划 §4.6“每个 @async 在
  一切路径完成回调”的有界偏差（V1）：§4.6 的本意是 Dart 不得无限等待，
  而不 await 的 `.then` 延续本来就不等待；取消路径的状态本来就没有变。
  涉及：`openRecommendationFeedback`（`onChanged`）、`openAdRuleManage`
  （`onRuleChanged`）、`openIntroSkipKinds`（`onChanged`）、
  `setSubtitleAiMaxConcurrency` / `setSubtitleAiChunkCount`（数字弹窗的
  `IntCallback`）、`openRealtimeModel`（模型选择回调与删除完成回调；删除
  确认弹窗里点取消同样不完成）、`openDanmakuApi`（`setDanmakuApi`）。
- **不改状态的弹窗**是同步 fire-and-forget：`openAdBlockStats`（纯统计展示）。
- 每个成员页仍保留 `resumed` 重取（C1/C2 同款），覆盖切后台再回、播完返回等
  路径；弹窗关闭后的刷新只靠上述 `@async` 完成，不靠 resumed。

### 2.6 弹幕四级条件显隐链：host 算，Dart 只渲染

原生 `SettingDanmakuFragment` 的链（`updateApiVisibility` →
`updateAutoVisibility` → `updateSpiderVisibility`）：

```
load = DanmakuSetting.isLoad()
api 可见     = load
auto 可见    = load && (hasValidApiUrl() || isBundleSource())
spider 可见  = load && hasValidApiUrl() && isAuto()
```

`B3HostApiImpl.getDanmakuSettings()` 按同一算式算出 3 个可见性布尔值放进
快照；Dart 只按布尔值渲染/隐藏，不在 Dart 内复刻条件逻辑（任务简报的硬性
要求）。行值文案同样由 host 算（加载/自动/蜘蛛优先 → 开/关；弹幕源 →
有/无）。切换任一开关后 Dart 重取快照，链的下一级联动由 host 的下一次
快照体现。

### 2.7 AI 实时字幕模型行：下载进度靠轮询

原生 `SettingAiFragment` 在选模型后启动 `RealtimeSubtitleController`
下载，并在下载进度回调里**实时**更新行文案（“下载中 N%”）。Dart 没有
EventChannel，收不到进度推送。B3 的做法（规划 §4.6 的轮询机制，聚合搜索
同款，首次在本批之外使用，偏差 V2）：

- 选模型后 host 按原生逻辑写 Setting、启动下载（进度回调在本批是空实现），
  并完成 `@async` 调用；Dart 重取快照，看到 `realtimeModelDownloading`
  为真。
- AI 页在快照显示下载中或删除中时，每 500ms 轮询一次 `getAiSettings()`；
  状态离开下载/删除时停轮询。下载成功/失败的 Toast 由 host 在下载完成
  回调里发（与原生 `onRealtimeModelChanged` 一致）。
- 下载中/删除中点该行时 host 直接完成调用、不弹选择器（与原生 `return`
  一致）。
- 模型状态文案（缺失/就绪/下载中/删除中/下载进度）全部由 host 按原生
  `setRealtimeModelStatus` / `modelStatusText` / `modelProgressStatusText`
  的算式拼出，Dart 只显示。

### 2.8 广告行错误态：host 包 try/catch，Dart 染红

原生 `SettingAdFragment.setText()` 用 `safeRun` 包住每行摘要计算：异常时该
行显示 `error_config_get` 文案 + 错误色 + 深底（设计文档 §9.9 的错误色要求）。
B3 在 host 侧镜像：`getAdSettings()` 里每行摘要计算包在 `safeRow()` 里，
失败时返回错误文案 + 对应 `*Error` 布尔值；Dart 侧 `SettingsRow(isError:
true)` 把值文案染红（近似原生错误色；深底未复刻，见 §2.10 V7）。
涉及 5 行：去广告、AI 广告检测、规则管理、自动跳过、跳过类型。拦截统计行
无值文案，不参与。

### 2.9 mobile-only 类与资源：反射四招 + 全量核查

impl 在 main 源码集（leanback 构建也要编译它），4 个弹窗只在 mobile 源码集，
按规划 §4.8 招 4（反射 + ProGuard keep，与 C2 的 `SyncDialog` 同款）打开：

| 弹窗 | mobile-only 原因 | 打开方式 |
| --- | --- | --- |
| `DanmakuApiDialog` | mobile + leanback 各一份；唯一入口 `show(Fragment)`，正按钮回调父 Fragment 的 `DanmakuListener` | 反射调 `show(Fragment)`；父 Fragment 由 impl 内一个无界面 `DanmakuApiHostFragment`（实现 `DanmakuListener`，public static，隐式无参构造，可被 FragmentManager 恢复）担任，随用随取、常驻到 Activity 结束（偏差 V4） |
| `RecommendationFeedbackDialog` | mobile + leanback 各一份 | 反射 `create(FragmentActivity)` → `onChanged(Runnable)` → `show()` |
| `AdRuleManageDialog` | mobile + leanback 各一份；回调是嵌套接口 `Callback.onRuleChanged()` | 反射 `create()` → `show(FragmentActivity, Callback)`；Callback 用 `java.lang.reflect.Proxy` 实现（偏差 V5） |
| `AdBlockStatsDialog` | mobile + leanback 各一份 | 反射 `create(Activity)` → `show()`，fire-and-forget |

其余弹窗与 API 全在 main 源码集，可直接引用：`TmdbSourceDialog`、
`AiConfigDialog`、`SubtitleSettingsDialog`（含嵌套 `IntCallback`）、
`IntroSkipKinds`、`WebHtvAlertDialogBuilder`、`LightDialog`、
`DanmakuSetting`、`AiConfig`、各规则/反馈 Store、
`RealtimeSubtitleController`（含嵌套 `ModelDownloadListener`）。

**资源核查（本批无 mobileString）**：B3 用到的全部字符串与数组已按目录
分别 grep 核实（main / leanback / mobile，不用 `head` 截断——B2 的
`dialog_delete_record` 教训）：60 个 `R.string` 与 4 个 `R.array`
（`select_tmdb_match_mode`、`select_realtime_subtitle_model`（+`_value`）、
`select_auto_skip_intro_outro`）**全部在 main res**，impl 可直接引用，
无需运行时解析。`R.layout` / `R.id` 本批未新增引用。

### 2.10 相对规划的全部偏差

| # | 偏差 | 理由 |
| --- | --- | --- |
| V1 | 弹窗 `@async` 只在“状态可能变了”时完成；Dart 从不 await，只挂 `.then` 重取 | 见 §2.5：无关闭钩子的弹窗取消时无法观测关闭；取消本来无改动、无需刷新；不 await 故 Dart 永不等待，§4.6 的本意已满足 |
| V2 | AI 实时模型行下载/删除期间，Dart 每 500ms 轮询 `getAiSettings()` | 见 §2.7：无 EventChannel；规划 §4.6 的轮询机制（聚合搜索同款）首次在本批之外使用 |
| V3 | 详情打开方式/主题单选弹窗与个性化推荐确认弹窗由 host 内联创建（`WebHtvAlertDialogBuilder` + `LightDialog`，均为 main 可见） | 原生弹窗代码内联在 fragment 里，无可复用入口；impl 逐行镜像原生建弹窗逻辑，含 `setOnDismissListener` 完成语义 |
| V4 | 弹幕源弹窗经无界面 `DanmakuApiHostFragment` + 反射打开 | `DanmakuApiDialog.show(Fragment)` 要求一个实现 `DanmakuListener` 的父 Fragment；headless fragment 是唯一不改动原生类的办法；常驻到 Activity 结束，无 UI、无泄漏出屏 |
| V5 | `AdRuleManageDialog.Callback` 用 `java.lang.reflect.Proxy` 实现 | 回调接口是 mobile-only 嵌套接口，main 源码集无法直接实现；Proxy 是唯一办法；`equals`/`hashCode`/`toString` 已按代理惯例处理 |
| V6 | `performRow` 深链的 4 处入口同样查开关分流 | 否则设置内搜索结果会绕过 B3 开关进原生页；与 `buildGroups` 的 4 行共用 4 个新方法；C1 的 `performRow` 字幕行仍直接 `change(6)`（C1 冻结，不动） |
| V7 | 广告错误行只染红值文案，未复刻原生错误深底 | Dart 侧无对应 drawable；红字已满足错误可辨识；深底是装饰性差异 |
| V8 | 行图标与圆圈色在 Dart 内硬编码（material 图标近似原生 drawable；chevron 有无按布局逐行核实） | 图标是纯展示选择，非本地化文案；C1 字幕页同款做法；chevron 映射：TMDB 前 3 行有、后 3 行无；AI 全部 6 行有；广告规则管理/统计/跳过类型有、其余无；弹幕仅弹幕源行有 |
| V9 | 家族路由用 `routes` 表字面条目（`/settings?member=x`），不用 `onGenerateRoute` | 不动现有路由机制；B4–B7 加成员同样只加字面条目；member 值是固定 token，无需 URL 编码 |
| V10 | 数组下标钳制（TMDB 匹配模式、自动跳过模式取下标时 clamp） | 原生直接下标；钳制只防损坏的偏好值导致越界崩溃，有效状态下行为完全一致 |

### 2.11 哪些东西明确不做

- 不碰 Room/设置 schema（评估文档 §7 硬性前提）；只读写已有 `Setting` /
  `DanmakuSetting` 键 + 1 个新开关布尔键；历史聚合开关写后发的
  `RefreshEvent.history()` 与原生一致。
- 不碰播放器、蜘蛛、实验室、小程序；不改 `C0HostApi` / `C1HostApi` /
  `C2HostApi` / `B2HostApi` 合约与实现（C0/C1/C2/B2 冻结）；不改 4 个原生
  Fragment、`SettingFragment` 的其他行、`HomeActivity`、`IntroSkipKinds`、
  任何弹窗类。
- 不回头重构 C1 字幕页（冻结）——共享 `SettingsRow` 是新建件，C1 保留其
  私有 `_row`；不改 C2 历史页与 B2 收藏页（冻结）。
- 不新增 Dart 依赖；不改 Flutter/Pigeon 版本锁定。
- 不改 `android-release.yml`；B3 构建仍走 `b8-c0.yml`（只加 Pigeon 生成命令）。
- 不改任何电视端代码与布局；不改 `ImgUtil.java`。
- 不做设置主屏（B7）、增强/个人/播放器设置（B4/B5/B6）、设置内搜索（B5）。

## 3. 文件清单

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `flutter_module/pigeons/b3_settings_a_messages.dart` | 新：B3HostApi + `B3TmdbSettings` / `B3AiSettings` / `B3AdSettings` / `B3DanmakuSettings`（扁平数据类） |
| 2 | `flutter_module/lib/b3_settings_a_page.dart` | 新：`B3SettingsPage`（族壳 + 族内 Navigator）+ 4 个成员页（TMDB / AI / 广告 / 弹幕） |
| 3 | `flutter_module/lib/widgets/settings_row.dart` | 新：共享 `SettingsRow` + `SettingsGroupHeader`（见 §2.10 V8） |
| 4 | `flutter_module/lib/main.dart` | 改：`/settings` 家族路由 5 条 + 实验屏 B3 开关 |
| 5 | `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B3HostApiImpl.java` | 新：host 实现（快照/弹窗/反射/轮询快照字段） |
| 6 | `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java` | 改：`ROUTE_SETTINGS` + 注册 `B3HostApi` + 类注释提及设置族 |
| 7 | `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java` | 改：C0 引擎注册 `B3HostApi`（只为开关） |
| 8 | `app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java` | 改：加 `flutter_b3_enabled` 开关方法 |
| 9 | `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SettingFragment.java` | 改：4 行入口 + 4 处 `performRow` 深链分流（共用 4 个新方法） |
| 10 | `.gitignore` | 改：加 `B3Messages.java` |
| 11 | `.github/workflows/b8-c0.yml` | 改：Pigeon 步骤加 b3 生成 + 存在性检查 |
| 12 | `docs/b8-b3-settings-a.md` | 新：本文档 |

## 4. 逐项对照清单（Wei 真机验收用）

先在实验屏打开 B3 开关，再进设置页对照；每项与原生（开关关闭后）逐条比。

### 4.1 TMDB 设置页

- [ ] 开关开：设置页“TMDB”行进 Flutter 页；开关关：进原生页。
- [ ] 6 行顺序与原生一致：TMDB 源 / 详情打开方式 / 详情主题 / TMDB 匹配模式 /
      剧集文件大小 / 历史聚合；标题与值文案与原生一致。
- [ ] 详情主题行只在详情打开方式为 TMDB 模式时出现；历史聚合行只在 TMDB
      已配置时出现；其余 4 行常显。
- [ ] TMDB 源行点后出现原生源弹窗，改完关闭后值文案已更新（已配置/未配置）。
- [ ] 详情打开方式行点后出现原生 5 选 1 弹窗，选后值文案变；切到 TMDB 模式后
      详情主题行出现，主题弹窗 3 选 1 同样可用。
- [ ] 匹配模式行点后值循环变化；剧集文件大小行点后开/关翻转；历史聚合行点后
      开/关翻转。
- [ ] 返回键退出页面回到设置主屏（Flutter 族内无上一页时直接 finish）。

### 4.2 AI 设置页

- [ ] 6 行顺序与原生一致：AI 推荐 / 个性化推荐 / 推荐反馈 / 实时字幕模型 /
      AI 字幕最大并发 / AI 字幕分块数；两组标题（推荐组 / 字幕组）与原生一致。
- [ ] AI 推荐行点后出现原生 AI 配置弹窗，改完关闭后值文案已更新（关/已配置/
      未配置）。
- [ ] 个性化推荐行：关 → 点后直接变开；开 → 点后出现原生确认弹窗，确认后变关，
      取消后不变。
- [ ] 推荐反馈行点后出现原生反馈管理弹窗，恢复某条/全部恢复后，行内条数已更新。
- [ ] 实时字幕模型行点后出现原生模型选择弹窗：选未下载的模型后，行文案变
      “下载中 N%”并随下载推进更新；下载完成变“就绪”；删除流程（模型行 →
      删除 → 确认）后变“缺失”。
- [ ] 最大并发/分块数行点后出现原生数字弹窗，输入新值后行内数字已更新；输入
      超界值时按原生钳制（1–8 / 1–32）。
- [ ] 字幕 AI 组只在 AI 已配置就绪时出现（3 行同隐同显）。

### 4.3 广告设置页

- [ ] 6 行顺序与原生一致：去广告总开关 / AI 广告检测 / 广告规则管理 / 广告
      拦截统计 / 自动跳过片头片尾 / 跳过类型；三组标题与原生一致。
- [ ] AI 广告检测行只在 AI 已配置就绪时出现。
- [ ] 去广告 / AI 检测行点后开/关翻转。
- [ ] 规则管理行点后出现原生规则管理弹窗，改完关闭后行内“规则数 + 待处理数”
      已更新。
- [ ] 拦截统计行点后出现原生统计弹窗（无值文案，只标题 + 箭头）。
- [ ] 自动跳过行点后值循环变化；跳过类型行点后出现原生多选弹窗，确认后行内
      摘要已更新，取消后不变。

### 4.4 弹幕设置页

- [ ] 4 行顺序与原生一致：弹幕加载 / 弹幕源 / 自动加载 / 蜘蛛优先。
- [ ] 显隐链：加载关 → 只剩加载行；加载开 → 弹幕源行出现；填了有效 API
      地址（或包内源）→ 自动加载行出现；自动加载开且有有效 API → 蜘蛛优先
      行出现；反向关闭时各级依次消失。
- [ ] 弹幕源行点后出现原生 API 输入弹窗，保存新地址后行文案变“有”，自动加载
      行出现；清空地址后变“无”。
- [ ] 加载/自动/蜘蛛优先行点后开/关翻转。

### 4.5 族路由与开关

- [ ] 从设置主屏进 4 页、从设置内搜索结果进 4 页，开关开时都走 Flutter，
      关时都走原生（深链已分流，见 §2.1）。
- [ ] 族内返回：成员页返回键退出到设置主屏；旋转、切后台再回不崩溃，状态还在。
- [ ] 实验屏 B3 开关开/关立即生效（下次进子设置页）；开关状态重启 App 后保留。
- [ ] 电视端设置页不受影响（无 Flutter 入口）。

## 5. 回滚步骤

- **实验屏上关 B3 开关**，4 个子设置页立即回到原生。
- **完整回滚**：revert 本批提交；无需改 main。
- **失败时**：`b8-c0` 产物不发布；用实验屏开关/回滚恢复；C1/C2/B2
  验收状态不受 B3 回滚影响（独立开关、独立合约、独立 impl）。

## 6. 未能验证的项

| # | 项 | 风险/备注 |
| --- | --- | --- |
| G6-1 | Pigeon codegen：4 个数据类 + 11 个 `@async void` 方法（`VoidResult`） | C1/C2/B2 已证明同类形态；B3 是同一形态的新文件，CI 生成时校验 |
| G6-2 | `flutter analyze`（一个警告即失败，C1 #8 教训） | 本地无 Dart，全部 Dart 文件未静态检查；CI 失败先看分析日志再修；mixin + `PopScope` + `Timer` 形态是本批的新点 |
| G6-3 | Gradle 编译：手写 `B3HostApiImpl` vs 生成接口 | 装箱类型、方法名与 Dart 定义字符级一致（C1 的教训）；嵌套 `DanmakuApiHostFragment`、Proxy、内联单选弹窗、`SubtitleSettingsDialog.IntCallback` 以 CI 日志为准 |
| G6-4 | `b8-c0.yml` 4 风味 release 构建（mobile/arm64、mobile/arm、TV、leanback） | 本批无直接 mobile-only 引用（全部字符串/数组已核实在 main res；4 个 mobile-only 弹窗全走反射）；待 CI 证明 leanback 风味构建通过 |
| G6-5 | R8/minify：4 处反射 + Proxy + headless fragment | R8 保留全部 app 成员名（proguard 规则）；反射目标是 public 方法/类；Proxy 实现的嵌套接口在 release 下须存活——待 CI + 真机证明 |
| G7-1 | 族内 Navigator + `PopScope` 在缓存引擎 `FlutterFragment` 内的返回键行为 | 族壳是本批的新机制；若拦截不生效，返回键直接退出页面（族内无上一页时与正确行为相同，B7 加族主页后才分得出） |
| G7-2 | 路由 query 字符串：`/settings?member=x` 经 `getInitialRoute()` 的精确匹配 | 5 条字面路由须与 host 拼出的字符串字符级一致；拼错则回落到 `/`（C0 占位屏），验收时可见 |
| G7-3 | 弹窗 `@async` 契约：变化时重取、取消时无事可做（V1） | 需逐个弹窗验收（§4 各弹窗项）；取消后页面文案必须与原生一致（无改动） |
| G7-4 | 弹幕四级显隐链在真机上的联动 | 链的每一步都依赖 host 快照；验收 §4.4 的显隐序列 |
| G7-5 | 实时模型下载进度轮询（500ms）与下载完成/失败 Toast | 轮询期间页面是否闪烁/卡顿；下载失败时 Toast 与行文案是否与原生一致 |
| G7-6 | 广告错误行渲染（红字） | 触发条件是规则 Store 抛异常，真机上大概率测不到；只验代码路径存在 |
| G7-7 | 族内单引擎不变量：`/about`、`/subtitle`、`/history`、`/keep`、`/settings` 共用 C1 引擎 | 验收时只测单屏进出；族内多页叠加待 B7 |
| G7-8 | §4 对照清单逐项通过；开关关闭后回到原生 | Wei 真机验收 |

## 7. Recovery anchor（恰好一个下一步：B4）

**下一步：B4**——按 `docs/b8-full-migration-plan.md` §3 的批次顺序，继续下一批
（B4：增强设置页，族内新增一页）。不推送、不编包；各批完成后统一联合推送
（Wei 2026-11-17 10:44 指示）。

---

*任务护栏：`b8-b3-settings-a`（见 §0）。提交与恢复标签见提交记录。*
