# B8 CI #33 analyze 修复：member mixin 缺少的 dart:ui / services 类型

## 失败

CI run #33（44065376464，head 2fa7382）2026-11-17 18:33 在第 13 步
"Analyze Dart code (fail fast)" 失败，30 条 error（0 warning、0 info），
全部是第二轮（116c23b9）补的 WidgetsBindingObserver 空实现里 3 个未定义
的名字。#31 的 20 条 mixin 诊断已全部消失。

每文件 6 条，共 30 条（b3/b4/b5/b6/b7 各 6 条）：

- `PredictiveBackEvent`：每文件 2 条 `undefined_class`
  （`handleStartBackGesture`、`handleUpdateBackGestureProgress` 的参数类型）
- `ViewFocusEvent`：每文件 1 条 `undefined_class`
  （`didChangeViewFocus` 的参数类型）
- `AppExitResponse`：每文件 3 条（`didRequestAppExit` 的
  `Future<AppExitResponse>` 返回类型和 `.value(...)` 里的类型参数各 1 条
  `non_type_as_type_argument`，`AppExitResponse.exit` 1 条
  `undefined_identifier`）

## 根因

3 个类型都不在这些文件已 import 的
`package:material_ui/material_ui.dart` 的导出范围里，文件也没有别的
import 提供它们；方法签名本身是对的（#33 没有报任何签名错误），只缺
类型可见性。

## 简报勘误：PredictiveBackEvent 不在 dart:ui

任务简报要求先核对 SDK 源码、确认 3 个类型都在 dart:ui 再写修复。
核对结果：只有 2 个在 dart:ui，第 3 个不在——

- `AppExitResponse`、`ViewFocusEvent`：在 dart:ui。证据：Flutter 3.50.0
  `packages/flutter/lib/src/widgets/binding.dart` 第 40–58 行
  `import 'dart:ui' show ... AppExitResponse ... ViewFocusEvent;`。
- `PredictiveBackEvent`：**不在 dart:ui**，在 services 库
  （`package:flutter/services.dart`）。证据：api.flutter.dev 上的
  "PredictiveBackEvent class - services library - Dart API"
  （https://api.flutter.dev/flutter/services/PredictiveBackEvent-class.html）；
  旁证：binding.dart 自身 import 了 `package:flutter/services.dart`
  （第 69 行），而它的 dart:ui show 列表里没有 PredictiveBackEvent。

按简报原方案（`import 'dart:ui' as ui;` + 3 个类型全加 `ui.` 前缀）
会修不掉 PredictiveBackEvent 的 10 条诊断（`ui.PredictiveBackEvent`
同样未定义），所以改用下面的 show import 方案。

## 本轮修复

5 个文件各加 2 行 import，mixin 空实现的方法体和签名一行未动：

```dart
import 'dart:ui' show AppExitResponse, ViewFocusEvent;

import 'package:flutter/services.dart' show PredictiveBackEvent;
```

- 只 import 需要的 3 个名字（show 列表），不引入整库，避免与
  material_ui 已导出名字（如 Locale、Brightness）的 ambiguous import。
- 空实现与 SDK 默认行为一致（`didPopRoute`/`didPushRoute`/
  `didPushRouteInformation` 返回 `false`，`handleStartBackGesture`
  返回 `false`，`didRequestAppExit` 返回 `AppExitResponse.exit`，
  其余为空方法体），行为无变化。

改动文件（各 +3/−0 行 import，stub 0 行改动）：

- `flutter_module/lib/b3_settings_a_page.dart`
- `flutter_module/lib/b4_enhance_page.dart`
- `flutter_module/lib/b5_personal_page.dart`
- `flutter_module/lib/b6_player_page.dart`
- `flutter_module/lib/b7_settings_home_page.dart`

## 本地验证

- `git diff --check` 干净；`git status` 只有上述 5 个修改文件 + 本 doc。
- 逐文件 grep：`PredictiveBackEvent`、`ViewFocusEvent`、
  `AppExitResponse` 每文件恰好 6 处引用，签名未变、其它类型名未动；
  新增的 2 行 import 每文件各 1 次；无新增变量。
- 3 个类型的库归属已按上文逐条核对 SDK 源码/API 文档。

## 未验证项

- 本机无 Flutter/Dart SDK，无法本地跑 `flutter analyze`；analyze
  结果以 CI 下一轮为准。
- 真机行为无变化：只补了 import，空实现语义与上一轮相同。
