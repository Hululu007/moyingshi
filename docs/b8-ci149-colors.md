# Run #149 Dart analyze 失败修复：vector_math 整包导入撞上 material_ui 的 Colors

- 运行：b8-c0 run #149（id 44239497332），head `ffe61e04`（#147 修复提交），2026-11-18 12:48 CST 触发，3m 34s，conclusion failure。
- 失败步骤：第 13 步 "Analyze Dart code (fail fast)"；后续签名/打包/发布全部 skipped，无 prerelease、无 APK。
- 诊断来源：job 日志（job 134583633987，经浏览器读取）+ 本机文件逐字核对。

## 16 个问题（全部在 flutter_module/lib/b11_home_page.dart）

- 15× `ambiguous_import`：`Colors` 在 `package:material_ui/src/colors.dart` 与
  `package:vector_math/vector_math_64.dart` 中重复定义。位置：375:50、444:27、666:23、
  1481:11、1482:11、1508:30、1517:32、1588:20、1636:53、1643:34、1678:18、1687:19、
  1688:19、1692:19、1693:19。均为既有用法（`Colors.white` / `Colors.black` /
  `Colors.transparent`，来自 material_ui）。
- 1× `invalid_constant` 在 1517:32：同一位置的 const Colors 用法因歧义而失效，
  随歧义消除而消失。

## 根因：#147 修复的二次失败

#147 修复（提交 `2dbaf6ad`）把 glass v2 的 `Matrix4` 改从 vector_math 导入时写了整包
导入 `import 'package:vector_math/vector_math_64.dart';`。vector_math_64 也导出 `Colors`，
与 material_ui 的 `Colors` 撞名，导致文件中 15 处既有 Colors 引用全部歧义。
run #148（docs 提交 bc247c9）被后续推送取消；run #149 是 #147 修复后第一个完整跑到
analyze 的运行，故现在才暴露。

## 修法（唯一改动点）

把整包导入收窄为只引入文件实际用到的两个符号：

```dart
import 'package:vector_math/vector_math_64.dart' show Matrix4, Vector3;
```

- 文件只用到 `Matrix4`（透镜）与 `Vector3`（透镜参数），其余一律不引入，Colors 歧义即消失。
- 用 `show` 而不用 `hide`：更彻底，防止 vector_math 其他符号日后再撞。
- 不改任何 Colors 用法，不改透镜逻辑，不碰 pubspec、Java/Pigeon/manifest。

## 静态核查（本机无 Dart/Flutter SDK，CI 为第一次真实编译检查）

- vector_math import 只有 `show Matrix4, Vector3` 一行；文件内 Matrix4/Vector3 引用齐全；
  Colors 用法未动；括号平衡。
- `git diff` 仅 1 个 Dart 文件 + 本文档；`pubspec.yaml`、Java/Pigeon/manifest 未动。

## Recovery anchor

- 目标：run #149 的 16 个 analyze 问题清零，CI 重新变绿。
- 完成：import 收窄一行；文档本文；本地提交 + recovery tag（见提交信息）。
- 未验证：dart analyze 本机无法跑，CI 为第一次真实检查；下一轮 check cron 看结果。
- 下一步：parent 推送 + 建 CI check cron。
