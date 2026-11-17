# B8 CI #31 analyze 修复：member mixin 的 WidgetsBindingObserver 空实现

## 任务

修复 CI run #31（`b8-c0.yml`，HEAD `509c41d`）在 "Analyze Dart code" 步骤
失败的剩余 20 条诊断。

## #31 的 20 条诊断

全部集中在 5 个文件，每文件恰好 4 条、模式完全相同：

| 文件 | 位置 | 诊断 |
| --- | --- | --- |
| `lib/b3_settings_a_page.dart` | 144:5、150:41、155:44、160:8 | 下同 |
| `lib/b4_enhance_page.dart` | 300:5、306:41、311:44、316:8 | 下同 |
| `lib/b5_personal_page.dart` | 518:5、524:41、529:44、534:8 | 下同 |
| `lib/b6_player_page.dart` | 269:5、275:41、280:44、285:8 | 下同 |
| `lib/b7_settings_home_page.dart` | 374:5、380:41、385:44、390:8 | 下同 |

每文件 4 条：1 条 `mixin_with_clause` error（"A mixin can't have a
with clause"）+ 2 条 `argument_type_not_assignable` error（mixin 类型
不能赋给 `WidgetsBindingObserver` 参数，即 `addObserver(this)` /
`removeObserver(this)` 处）+ 1 条 `override_on_non_overriding_member`
warning（`didChangeAppLifecycleState` 的 `@override`）。

## 第一轮错在哪里

第一轮修复（`fa73c43`）把 5 处 mixin 声明从
`implements WidgetsBindingObserver` 改成了 `with WidgetsBindingObserver`。
但 Dart 语言规定 **mixin 声明里不能用 `with` 子句**（只有 class 声明可以），
所以改完反而产生了这 20 条新诊断。第一轮的其它修复（B22 Pigeon
构造函数、B10 去 const、B21 await、Radio→RadioGroup）已生效，#31 日志里
0 条相关诊断，本轮未动那些文件。

## 本轮修复

只改 5 个文件（+ 本 doc）：

1. 5 处 mixin 声明改回 `implements WidgetsBindingObserver`：
   `_B3MemberState`、`_B4MemberState`、`_B5MemberState`、`_B6MemberState`、
   `_B7MemberState`（均为 `mixin _BXMemberState<T extends StatefulWidget>
   on State<T>` 形式）。
2. 在每个 mixin 内补上 `WidgetsBindingObserver` 尚未实现的全部方法空实现，
   只保留已有的 `didChangeAppLifecycleState` 真实逻辑（resumed 时 `load()`）。
   空实现返回与 SDK 默认行为一致的值（`didPopRoute` /
   `didPushRoute` / `didPushRouteInformation` 返回 `false`；
   `handleStartBackGesture` 返回 `false`；`didRequestAppExit` 返回
   `AppExitResponse.exit`；其余为空方法体）。

### 签名来源

逐条核对 Flutter **3.50.0** SDK 源码：

https://raw.githubusercontent.com/flutter/flutter/3.50.0/packages/flutter/lib/src/widgets/binding.dart

（`WidgetsBindingObserver`，第 167–799 行，`abstract mixin class`）。
**计数更正**：SDK 源码里该接口共 **17** 个方法，不是 #28 日志所说的
16 个——第 17 个是已废弃的 `didPushRoute(String route)`（`@Deprecated`，
用 `didPushRouteInformation` 代替）。`implements` 要求实现全部成员，
所以每 mixin 补 16 个空实现（已有 `didChangeAppLifecycleState` 是第 17 个）。
17 个方法：`didPopRoute`、`handleStartBackGesture`、
`handleUpdateBackGestureProgress`、`handleCommitBackGesture`、
`handleCancelBackGesture`、`handleStatusBarTap`、`didPushRoute`、
`didPushRouteInformation`、`didChangeMetrics`、`didChangeTextScaleFactor`、
`didChangePlatformBrightness`、`didChangeLocales(List<Locale>?)`、
`didChangeAppLifecycleState(AppLifecycleState)`、`didChangeViewFocus`、
`didRequestAppExit`、`didHaveMemoryPressure`、
`didChangeAccessibilityFeatures`。

类型可见性：5 个文件都 import `package:material_ui/material_ui.dart`
（转 export Flutter material），`PredictiveBackEvent` /
`RouteInformation` / `ViewFocusEvent` / `AppExitResponse` / `Locale` /
`AppLifecycleState` 均在该 import 链内，无需新增 import。`Future` 来自
`dart:core`，B6 文件未 import `dart:async` 也不受影响。

### 未动之处

其它文件的 `with WidgetsBindingObserver` 全部是 **class 声明**
（`class _XState extends State<...> with WidgetsBindingObserver`），合法，
本轮未动。`addObserver(this)` / `removeObserver(this)` 调用处未改——
mixin 实现接口后类型检查即通过；`didChangeAppLifecycleState` 的
`@override` 在接口实现后不再警告。

## 本地验证

- `git diff --check` 干净。
- `git status` 只有上述 5 个修改文件 + 本 doc。
- 逐个复读 5 处 mixin：声明为 `implements WidgetsBindingObserver`，
  17 个方法签名与 SDK 源码逐条一致，无新增未使用 import/变量。
- 全仓 grep：mixin 声明里不再有 `with WidgetsBindingObserver`
  （剩余命中全部是合法的 class 声明）。

## 未验证项

- 本机无 Flutter/Dart SDK，无法本地跑 `flutter analyze`；
  analyze 结果以 CI run #32（或下一轮）为准。
- 真机行为无变化：空实现不改变任何现有回调语义，
  `didChangeAppLifecycleState` 的 resumed-refetch 逻辑原样保留。

## Recovery anchor

- 目标：清掉 CI #31 剩余 20 条 analyze 诊断（5 个 member mixin 的
  `WidgetsBindingObserver` 实现问题）。
- 验收：下一轮 CI 的 "Analyze Dart code" 步骤通过（0 条与这 5 文件相关
  的诊断）；`addObserver(this)` / `removeObserver(this)` 类型检查通过。
- 状态：已完成。5 文件 + 本 doc 已提交，见提交记录。
- 风险：签名以 Flutter 3.50.0 SDK 源码为准逐条核对；若 CI 仍报
  相关诊断，按文件路径归属到具体 mixin 再修。
- 下一步：push 后看下一轮 CI 的 analyze 步骤结果。
