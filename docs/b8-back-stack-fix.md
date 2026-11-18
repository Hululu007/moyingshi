# B8 back stack: cross-screen back + root-back hierarchy

任务：`b8-back-stack`（task_guard，mode standard）。分支 `b8-flutter-rewrite`，
本地提交，不 push。触发：#159（`cebf2c9`）真机反馈第 1 条——“不管什么界面
返回都是直接到桌面”。根因与设计见 `docs/b8-acceptance-159-diagnosis.md`
§1；本任务实现该设计，并裁决其中 H1 机制选择与“in-family back 是否
完好”未决项。

## 完成句

任意 Flutter 屏上按系统返回，先回上一屏（先 in-family pop，再 host
路由历史）；`/home` 上按返回改为后台运行而非 finish；每个屏都有可用的
返回入口。

## 根因（诊断 §1，一句话）

Flutter 侧 root Navigator 每次 host 导航都被重置到深度 1，host 不保存
路由历史，两个 `closeScreen` 实现与未处理 pop 的 fallback 都 finish 掉
singleTop 唯一实例，任务栈随即清空。

## 实现：三层结构

1. **Dart-internal back（不动，只补 B3 直接进入 case）**：inner-Navigator
   pop（B3/B21/B8）、目录上翻（B15）、root-stack pop（B9→B10）、dialog
   dismiss。未决项核查结论见下文“in-family 核查”——五条路径代码上均
   完好，本任务未改其中任何一条（B21/B8/B15/B9/B10 均不在 scope 内）。
2. **Host 路由历史（新增，source of truth 在 host）**：
   `C1ScreenActivity.sRouteHistory`（静态 `ArrayList<String>`，只存路由
   字符串，H2）。记录点：
   - `onCreate`：历史为空时以 `[mRoute]` 播种（fresh engine / 首次进入）。
     engine 被复用时不动历史——活着的 C1 实例已拥有它，叠加的新实例
     只断言路由、不记历史，返回时 finish（H4）。
   - `onNewIntent`：带 `EXTRA_HISTORY_SWITCH` 标记 → `recordSwitch`（栈
     重置为单条）；否则 `recordPush`（与栈顶去重后追加）。onNewIntent
     只在该实例位于栈顶时触发，故到达者必来自本 C1 屏（或绑定到它的
     host 调用）。
   - `handleAction` 的 `nav_position` 分支：`recordSwitch`（与
     `openHomeAt` 同一目的地集合）。
   - launcher 重入（无 `EXTRA_ROUTE` 的 MAIN intent）：`recordSwitch(/home)`。
   - `onDestroy` teardown（`isFinishing() && sLiveActivities.isEmpty()`）：
     随 engine 一起 `clear()`，下次进入重新播种。
3. **一个 host root-back handler**：`C1ScreenActivity.handleRootBack()`。
   顺序（与原生 `HomeActivity.onBackInvoked` 层级一致）：
   - 非 task root → `finish()`（H4：露出底下的原生界面/屏）。
   - 历史多于 1 条 → pop，`mRoute` = 新栈顶，`assertRouteForced()`。
   - 单条且非 `/home` → 回 `/home`（`recordSwitch(/home)` + 强制断言；
     原生“设置可见 → 点播首页”；覆盖底栏 switch 后的返回，见 C2）。
   - `/home` → `Util.moveToBackground(this)`，**无条件**（R1 决定，见下）。
   两个 `closeScreen` 实现（C1、B3）都 delegate 到它；`onBackPressed()`
   的 fragment-null fallback 也改走它（不再 `super.onBackPressed()`）。
   无新 Pigeon 方法：`closeScreen` 本来就是 Dart→host 的 root-back
   信号。

## H1 机制决定：pop 路径强制断言（forced assertion）

选择：**bypass host 侧 equality guard**（`assertRouteForced()`：无条件
发送，但与前向断言完全相同地更新 `sAssertedRoute` 与 ownership token，
H3 token 纪律不变）。**不**采用“distinct reset signal”，**不**改
`main.dart` 的 `_showRoute` no-op。

理由——pop 目标恒不等于当前已断言路由字符串，故 Dart 侧 no-op 永远
吞不掉一次 pop：

- push 与栈顶去重，栈内永无连续重复；pop 目标（新栈顶）恒不等于被
  pop 的路由字符串。
- 每个 `mRoute` 变更点都记历史（onNewIntent / handleAction /
  onCreate 播种），`mRoute` 与栈顶永不漂移（叠加实例例外：非 task
  root，从不走 pop 路径）。
- Dart 的 `_currentTopRoute` 跟踪最近一次断言，即被 pop 的路由字符串，
  故 pop 目标在 Dart 侧同样不是重复断言，`pushNamedAndRemoveUntil`
  必然重置。

**共享决定（供未来同路由导航需求复用）**：`assertRouteForced()` 是
host 侧同路由断言的唯一原语；但若未来出现“向 Dart 发送与当前完全相同
的路由字符串并要求重置”的需求（如诊断 §4 缺陷 3a 的 host round-trip
形态），仅 host 侧强制是不够的——还须另行处理 Dart `_showRoute` 的
no-op（当时 §4 选择了 Dart-side landing，绕开了此问题；不要在没有
Dart 侧改动的情况下复用本原语去做真·同字符串重置）。

## 20 处 switch-vs-push 分类表

逐处读码分类。默认规则：来自内容屏的 `start()` 一律 push；只有底栏
等价导航是 switch。分类的落点只有两处：`FlutterNav.openHomeAt/goHome`
经 `startSwitch` 带标记，其余一律走默认 push（onNewIntent 无标记即
push），因此 20 处中只有标记侧需要改码，其余“分类”即“确认默认行为
正确”。

### FlutterNav 7 处（`flutter/FlutterNav.java`）

| # | 位置 | 目标 | 分类 | 依据 |
|---|---|---|---|---|
| 1 | `openHomeAt` (:171) | `homeRouteForPosition(position)` | SWITCH | 底栏导航入口（B11 "setting"、B5/B7 行、LiveActivity 底栏）；`startSwitch` 带标记。finish 规则一字未改。 |
| 2 | `goHome` (:194) | `ROUTE_HOME` | SWITCH | 同上；`startSwitch` 带标记。 |
| 3 | `direct` (:279) | `ROUTE_COLLECT` + query | PUSH | 聚合搜索页叠在发起屏之上；原生 `SearchActivity.direct` 是新 Activity 入栈。默认 push。 |
| 4 | `directFromHistory` (:322) | `ROUTE_COLLECT` + hr* | PUSH | 同上（历史续播直达聚合搜索）。默认 push。 |
| 5 | `openHistory` (:402) | `ROUTE_HISTORY` | PUSH | 原生 `HistoryActivity` 是独立 Activity；返回应回发起屏。默认 push。 |
| 6 | `openKeep` (:422) | `ROUTE_KEEP` | PUSH | 同上（`KeepActivity`）。默认 push。 |
| 7 | `openLocalReader` (:442) | `ROUTE_LOCAL?readMode=true` | PUSH | 本地阅读器叠在设置屏之上；原生 FileActivity 入栈。默认 push；非 C1 调用者的 finish 保留。 |

### B11 3 处（`flutter/B11HostApiImpl.java`，本任务 scope 外，只分类）

| # | 位置 | 目标 | 分类 | 依据 |
|---|---|---|---|---|
| 8 | `openBottomBarItem` "following" (:1292) | `ROUTE_FOLLOWING` | SWITCH（意图）→ 默认 push 实现 | 见偏差 D1：底栏只在首页，栈下恒为 `[/home]`，push 与 switch 的返回行为相同。 |
| 9 | `openBottomBarItem` "local_source" (:1306) | `ROUTE_LAB` | SWITCH（意图）→ 默认 push 实现 | 同 D1。 |
| 10 | `openTabCategory` (:1380) | `ROUTE_CATEGORY` + query | PUSH | 首页分类 tab 的内容下钻；返回应回首页。默认 push。 |

### 第二批 10 处（`docs/b8-crash-singletop-fix.md` 第二批表）

| # | 位置 | 目标 | 分类 | 依据 |
|---|---|---|---|---|
| 11 | `B7HostApiImpl.openLab` (:701) | `ROUTE_LAB` | PUSH | 设置屏点实验室：独立屏叠上，返回应回设置。默认 push。 |
| 12 | `B7HostApiImpl.openAbout` (:893) | `ROUTE_ABOUT` | PUSH | 同上（关于页）。默认 push。 |
| 13 | `B4HostApiImpl.openLab` (:297) | `ROUTE_LAB` | PUSH | 增强设置点实验室，返回应回增强设置。默认 push。 |
| 14 | `B4HostApiImpl.openPluginManager` (:317) | `ROUTE_PLUGINS` | PUSH | 增强设置点插件管理，返回应回增强设置。默认 push。 |
| 15 | `B13HostApiImpl.openWidgetMore` (:268) | `ROUTE_WIDGETS?widgetId=` | PUSH | 首页小组件区点“更多”，返回应回首页。默认 push。 |
| 16 | `B21HostApiImpl.openLabMiniappManager` (:1061) | `ROUTE_HK_MANAGER` | PUSH | 实验室点海阔管理，返回应回实验室。默认 push。 |
| 17 | `C2HostApiImpl.openViewingReport` (:174) | `ROUTE_REPORT?range=` | PUSH | 历史页选范围进报表，返回应回历史。默认 push。 |
| 18 | `B5HostApiImpl.openSearchEntry` page 6 (:476) | `ROUTE_SUBTITLE` | PUSH | 字幕页是顶级 C1 路由，返回应回设置搜索。直接 `start`，默认 push。 |
| 19 | `B5HostApiImpl.openSearchEntry` 其余分支 (:483-490) | 经 `openHomeAt` 的各设置页 | SWITCH | 走 `FlutterNav.openHomeAt`，自动带 switch 标记。注：§4 之后设置首页行落地已走 Dart-side，host 分支只作 fallback 保留。 |
| 20 | `AppBrandingActivity.start` (:32) | `ROUTE_BRANDING` | PUSH | 唯一调用者是 B5（绑定的 C1 实例）；品牌页叠在个人设置之上，返回应回设置。默认 push。 |

### 默认规则覆盖的其他 `start()` 点（非 20 处内，行为确认）

- `FlutterNav.openSearch` / `openFolder` / `openWidgetBrowse`：默认
  push——搜索/分类/小组件浏览均为内容下钻，返回回发起屏正确。
- 原生调用者（`LabActivity:340`、`TmdbPersonActivity:121`、
  `WebHomeShellActivity:264`、`ShortDramaActivity:613`、
  `VodFragment:318/671/680`）：C1 不在栈顶时新建实例（onCreate 路径，
  历史不动或播种）；C1 在栈顶时 onNewIntent 默认 push。两种路径的
  返回语义均与原生入栈一致。
- Intent 直建入口（`homeIntent` / `followingIntent` /
  `localBrowserIntent`、通知/崩溃重启）：无 switch 标记；新实例走
  onCreate 播种，复用实例走默认 push。通知落地到追更页时返回回之
  前的屏，符合预期。

## Dart 侧改动

- **B3 member fallback**（`b3_settings_a_page.dart`）：新增
  `_MemberObserver`（`NavigatorObserver`，只跟踪 push/pop/replace；
  回调只做普通字段赋值，绝不 `setState`，故 build 期触发也安全）与
  `_currentMember` 字段（`initState` 以 entry member 初始化，与
  initialRoute 的默认规则一致）。`_handleBack`：inner 可 pop 则 pop；
  不可 pop 且当前 member 不是 `home` 时 `pushNamedAndRemoveUntil(
  '/home', (_) => false)`（replace，不是 push）；只有 `home` 才落到
  `closeScreen`。H2：member 层级只归 B3 管，host 永不跟踪 member。
- **补 `PopScope(canPop: false)` + handler**（B2/C2 模式，handler 均为
  `closeScreen`，即 host root-back 信号）：
  - `b17_person_page.dart`、`b18_widget_page.dart`、
    `b19_plugin_page.dart`、`b20_hk_page.dart`：新增 `C1HostApi _c1Api`
   （此前四页只有各自的 B17–B20 Api，没有 `closeScreen` 入口；复用
    B14/B16 的 `_c1Api` 形态）+ `_onBack` + PopScope；另补显式 AppBar
    `leading:` 返回箭头（此前 bare AppBar，深度 1 时无箭头）。
    b17 的照片查看器是 dialog 路由，返回先关 dialog，不受 PopScope
    影响。
  - `c1_about_page.dart`、`c1_subtitle_page.dart`：只补 PopScope，接
    入已有的 `_api.closeScreen()`。subtitle 已有 AppBar 返回箭头；
    about 的关闭入口是底部确认按钮（见偏差 D3），未加箭头。
- **b11_home_page.dart 未改**：已核实 `_close()`（:221）即
  `_c1Api.closeScreen()`，且 PopScope（:366）已接 `_close()`。b10
  同样已核实（PopScope :305 + `_onBack` :287），不在 scope 内，未动。
- b4/b5/b6/b7 是 B3 family 内成员，由 B3 shell 的 PopScope 拥有，
  未加 PopScope（与 parent 更正的 census 一致）。

## in-family 核查（诊断 §1 未决项，代码核查结论）

逐条读码，五条路径的 handler 均先尝试内部 pop、仅在无法内部 pop
时才 `closeScreen`，**代码上全部完好，无一需修**。若真机仍见
in-family 返回直达桌面，则非 handler 逻辑问题，须另查手势/embedding
分发（诊断 R2）。

| 路径 | 文件：行 | 结论 |
|---|---|---|
| B3 settings family | `b3_settings_a_page.dart:70-78`（修前） | `_handleBack` 先 `canPop()` pop，否则 `closeScreen`；member 间 `pushNamed`（b7 :139/:144）。完好；本任务只补直接进入 case。 |
| B21 Lab | `b21_lab_page.dart:49-61` | `_handleBack` 先 inner pop，否则 `closeScreen`；详情 `pushNamed('/detail')`（:680）。完好，未改（scope 外）。 |
| B8 category | `b8_category_page.dart:59-70,318` | `_onBack` 先 inner pop，否则 `closeScreen`；下钻 `Navigator.of(context).push(MaterialPageRoute)`（context 在 inner Navigator 子树内，故 push 的是 inner 栈）。完好，未改（scope 外）。 |
| B15 local | `b15_local_page.dart:102-112` | `_onBack`：root 则 `closeScreen`，否则 `_loadDir(parentPath)` 上翻。完好，未改（scope 外）。 |
| B9→B10 | `b9_search_page.dart:288-290` + `b10_collect_page.dart:287-296` | B9 `_onBack` 即 `closeScreen`（深度 1 root 页，正确）；B10 `_onBack` 先 root `canPop()` pop 回 B9，否则 `closeScreen`。完好，未改（scope 外）。 |

## 危害与风险处理（H1–H4 / R1–R4）

- H1：见上文“机制决定”。pop 路径 `assertRouteForced()`。
- H2：host 只存路由字符串；B3 fallback 拥有 member 层级。
- H3：`assertRouteForced` 与前向断言相同地更新 `sAssertedRoute` /
  `sRouteOwnerToken`；G2 relaunch token 纪律不变；`onPostResume` 的
  兜底断言不动。
- H4：`handleRootBack` 先判 `isTaskRoot()`，非 root 直接 `finish()`；
  叠在 C1 之上的原生 Activity（播放器、WebHome shell）的返回完全
  不动。
- R1：**home-back 无条件后台运行**，无 in-app 退出路径；M2 teardown
  在 home-back 时不跑（这正是想要的：避免 teardown → 冷启动空首页
  循环）。若 Wei 想要真退出，需另加 affordance（scope 外，已记录）。
  附带：C1 `closeScreen` 原先“仅播放中才后台”的分支随 delegate 消失，
  行为统一为“home 恒后台”，与诊断 §1 的 R1 决定一致。
- R2：embedding 回调 live path 仍无法从代码验证；全部页面已有
  PopScope 消费 pop，`onBackPressed` fallback 只是安全网。真机
  检查 C2/C5 覆盖。
- R3：`PopScope(canPop: false)` 下 predictive-back 无预览（与已有
  十页一致），接受。
- R4：分类表在上；底栏等价导航全部经 `startSwitch` 或 D1 论证，
  返回永不走底栏历史。

## 偏差

- D1（B11 两处 switch 意图走默认 push）：`openBottomBarItem`
  "following"/"local_source" 直接调 `C1ScreenActivity.start()`，
  `B11HostApiImpl.java` 不在本任务 scope 内，无法加 switch 标记。
  无害的理由：底栏只存在于首页（`_GlassBottomBar` 只在
  `b11_home_page.dart`），两次导航的发起屏恒为 `/home`，故 push
  后的栈恒为 `[/home, 目标]`，返回必回首页，与 switch 的返回行为
  完全相同；R4 的“走底栏历史”链条需要从非首页屏再点底栏，而那在
  代码上不存在。若未来底栏出现在非首页屏，须给这两处补标记
  （follow-up：B11 一行改动）。
- D2（`onCreate` 播种条件）：不用 `!engineReused`，而用
  `sRouteHistory.isEmpty()`。等价于 fresh-engine 播种，并防御历史
  与 engine 状态漂移的任何路径；叠加实例（历史非空）永不重置。
- D3（parent census 更正）：`c1_about_page.dart` 并没有 AppBar
  `closeScreen` 按钮——它的 AppBar 是 bare 的，`closeScreen` 在底部
  确认按钮（:173）。本任务按 brief 只补了 PopScope，未加箭头；
  about 的返回入口 = 系统返回 + 底部确认按钮。`c1_subtitle_page.dart`
  确有 AppBar 返回箭头（:141）。如需 about 也显示箭头，是 3 行
  follow-up。
- D4（`C1HostApiImpl`  imports）：`PlaybackService` 与 `Util` 两个
  import 随旧 `closeScreen` 逻辑一并删除（grep 证实两符号在该文件
  内别无他用；`ResUtil` 仍在用，已保留——初版误删后已恢复并复核）。
- D5（`handleRootBack` 单条非 home 规则）：诊断设计原文只写了
  “empty stack at /home → background”；单条非 home（如底栏切到设置
  后栈为 `[/settings]`）时直接 background 会违背 C2（设置 → 返回 →
  首页），故加“单条非 home 先回 `/home`”一层，与原生层级第 5 步
  一致。

## 验收项（诊断 §1 C1–C7）

- C1：设置首页 → 成员行 → 返回 → 设置首页（in-family）；再返回 →
  首页（非桌面）。
- C2：首页 → 底栏 → 设置 → 返回 → 首页；首页返回 → 后台（最近任务
  可见，重进即时恢复，无空首页闪烁）。
- C3：首页 → 实验室 → 返回 → 首页；首页 → 搜索 → 结果 → 分类 →
  返回 → 搜索输入；返回 → 首页。
- C4：直接 member 进入（搜索结果 → 播放器设置）→ 返回 → 设置首页
  → 返回 → 首页（覆盖 H1 + B3 fallback）。
- C5：插件 / 海阔管理 / 人物 / 小组件页显示返回箭头；箭头与系统
  返回均回上一屏。
- C6：播放中首页返回照常后台；播放器（原生）返回仍露出发起的
  Flutter 屏。
- C7：B9 → B10 → 返回 → B9 输入（root pop 保留）；B9 返回 → 首页。

## 复核证据

- grep：`closeScreen` 的 Java 实现只剩 2 处（C1/B3），均 delegate
  到 `handleRootBack`；`super.onBackPressed()` 在
  `C1ScreenActivity` 内为 0 处；`startSwitch` 调用点只剩
  `FlutterNav.openHomeAt/goHome` 2 处。
- `git diff --stat` 只含 scope 内文件；`b11_home_page.dart` 无 diff；
  0 个 Pigeon/manifest 改动；保护路径
  `docs/b8-acceptance-159-diagnosis.md` 未动。
- PopScope census：`flutter_module/lib/` 内全部路由页均有 PopScope
  或归属 family shell（b4/b5/b6/b7 归 B3 shell；b12/b13 无路由，
  住在 `/home` 内）。
- 本地无 Java/Android/Flutter SDK，无法本地编译；以 CI（b8-c0.yml）
  构建为准。

## Recovery anchor

- Objective：见完成句。设计：诊断 §1 + 本 doc。
- Branch/HEAD：`b8-flutter-rewrite`，基线 `4888823a`。
- Status：实现完成，待 CI 编译验证与真机验收 C1–C7。
- Files：见 `git show --stat`（4 个 Java + 7 个 Dart + 本 doc；
  b11 在 scope 内但无改动）。
- Next action：parent 推送并编包，真机跑 C1–C7；D1/D3 的 follow-up
  仅在底栏扩展或 Wei 要求时才做。
