# B8 图标字体缺失修复（联合构建 #40 验收反馈）

## 现象

Wei 在 2026-11-17 21:08 发来 3 张真机截图（联合构建 #40，b8-c0-40-d9fe1c5，
mobile arm64）：实验室（B21）、设置主屏（B7）、首页（B11）三个 Flutter 屏上，
所有图标位置都显示为带 X 的方框（tofu / notdef 字形）。布局、文字、按钮均正常，
只有图标缺失。

## 根因

`flutter_module/pubspec.yaml` 的 `flutter:` 段只有 `module:`，缺少
`uses-material-design: true`，也没有任何 `fonts:` 声明。Dart 侧 74 处
`Icon(Icons.xxx)`（经 `package:material_ui/material_ui.dart`）引用的是
`MaterialIcons` 字体族；字体没有打进 Flutter 模块的资源包，运行时每个
`Icon` 只能渲染缺字字形。

已核实 material_ui 1.11.0 自身不带字体：其 pubspec（pub.dev API，
2026-11-17 读取）的 `flutter:` 段只有 `shaders`，没有 `fonts:`。所以必须由
应用模块声明 `uses-material-design: true`，Flutter 工具才会把 SDK 的
MaterialIcons 字体打进资源包。

该声明自 C0 壳手写 pubspec 起就缺失（见 pubspec 注释 "Hand-written
equivalent of `flutter create -t module` output"）；C0/C1 屏图标少，未被发现，
直到图标密集的 B7/B11/B21 在联合构建 #40 上真机验收才暴露。

## 修复

`flutter_module/pubspec.yaml`：`flutter:` 段下加 `uses-material-design: true`
（一行 + 注释）。不改任何 Dart 代码，不改依赖版本。

## 未验证项

- 无本地 Flutter SDK，无法本地验证字体是否打进包；以 CI 下一轮构建 + Wei
  真机截图为准：B7/B11/B21 的图标应正常显示。
- 截图中的空状态文案（实验室"暂无可用包"、首页"暂时没有内容"）是无源配置时的
  正常空状态，与图标问题无关；如 Wei 已配源仍为空，另行排查。

## Recovery anchor

- 目标：联合构建的 Flutter 屏图标正常显示。
- 当前状态：pubspec 已改，待 CI 构建 + 真机验收。
- 下一步：推送后等 CI 新 run，Wei 装包看图标。
