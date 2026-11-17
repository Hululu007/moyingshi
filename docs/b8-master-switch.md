# B8 总开关重构：一个 Flutter 总开关，撤掉分开关与实验屏

## 决定

Wei 在 2026-11-17 21:13 决定：只要一个总开关，分开关和实验屏都去掉
（"只要总开关，分开关和实验屏都去掉"）。设置里一个 Flutter 总开关：
开 = 除播放器外的所有迁移屏走 Flutter，关 = 全部回原生。

## 设计：别名法

`Setting.java` 新增总开关 `flutter_enabled`（默认 false）+
`isFlutterEnabled()` / `putFlutterEnabled(boolean)`。23 个分批 getter
（`isFlutterC1Enabled` / `isFlutterC2Enabled` / `isFlutterB2Enabled` …
`isFlutterB22Enabled`）全部改成 `return isFlutterEnabled();`，并注明
"master-switch alias"。23 个 `putFlutter*Enabled` setter 保持原样（继续写
各自旧键，值不再被读到），注明已死——留着是为了让 23 个 Pigeon impl 的
flag 方法继续编译。

理由：分流点与 Dart 侧检查读的都是这些 getter。别名法让 35 处分流点与
所有 Dart 侧 flag 检查（`isB12Enabled` / `isB13Enabled` 等）自动跟随总
开关，零遗漏、零分流点改动。反之，若 B12/B13 等屏内检查继续读各自旧键，
新装机默认 false 时总开关开了首页块也不会显示——这是别名法要堵住的陷阱。

## 改了什么

- `Setting.java`：新增总开关方法；23 个 getter 改别名；23 个 setter 标死。
- `flutter_module/lib/main.dart`：删掉整个 `C0HomePage`（23 个 flag 字段、
  `_load*Flag` / `_set*Flag` 方法、SwitchListTile 列表、Pigeon 测试按钮）
  与因此无用的 23 个 pigeon import（只留 `c1_messages.g.dart`，`main()` 的
  `getInitialRoute()` 还用它）。`'/'` 路由改到最小占位
  `_UnreachablePlaceholder`（实际不可达，所有入口都传显式路由），并更新
  了相关注释。`C0ShellApp`、routes 表、`onGenerateRoute`、`main()` 里的
  `getInitialRoute` 调用保留。
- `app/src/mobile/AndroidManifest.xml`：删 `C0ShellActivity` 的 `<activity>`
  块（含第二个 LAUNCHER intent-filter）；删
  `flutter/C0ShellActivity.java`（`git rm`）。删前全仓 grep 确认：除
  Manifest 外只剩 javadoc 注释引用，`b8_c0_engine` 零引用，
  `C1ScreenActivity` 用自己独立的 `b8_c1_engine` 并自行 rebind
  B20/B21/B22，不受影响。
- `SettingFragment.java`（原生设置）：通用分组第一行加总开关行
  （`ROW_FLUTTER = "flutter"`，与 B7 快照行同 key；图标复用
  `ic_setting_switch`，文案用 `b8_flutter_title` / `b8_flutter_sub`），
  状态读 `isFlutterEnabled()`，点按翻转后刷新行值（与无痕/下载行的既有
  模式一致）；`performRow` 加对应 case，走设置搜索深链也能触达。
- `b8_c0_strings.xml`：新增 `b8_flutter_title`（"Flutter 界面"）与
  `b8_flutter_sub`（"打开后除播放器外的界面走 Flutter，关掉回到原生"），
  原生行与 B7 行共用。
- B7 Flutter 设置主屏（必须有，否则总开关开着时用户无处可关）：
  - 契约 `b7_settings_home_messages.dart` 加一个方法
    `setFlutterEnabled(bool enabled)`（B7 没有通用行动作通道，各行都是
    专属方法，这是唯一的契约加法；CI 重新生成两侧代码）与快照字段
    `flutterEnabled`（`B7SettingsHome` 末尾加法）。
  - `B7HostApiImpl`：实现 `setFlutterEnabled`（写 `putFlutterEnabled`）；
    快照 builder 加 `.setFlutterEnabled(...)`；通用分组第一行加 key 为
    `"flutter"` 的行（与原生行顺序镜像，value 留空，Dart 画 Switch）。
  - `b7_settings_home_page.dart`：`_HomeRow` 加可选 `trailing`（有则替
    代 chevron）；flutter 行渲染 `Switch`，状态读快照
    `flutterEnabled`（不写死）；行点按与 Switch 切换都调
    `_setFlutterEnabled` 写 host，成功后 `C1HostApi().closeScreen()`
    关屏回原生，失败则留屏。`_rowIcon` 加 `'flutter'` 项。

## 刻意留着什么

- 23 个 Pigeon 契约（c0/c1/c2/b2…b22）除 B7 的一个加法外一字不改；
  各 HostApiImpl 的 flag 方法（`isB<N>Enabled` / `setB<N>Enabled`）不动，
  变成死方法，无害。C0 契约与 `C0HostApiImpl` 留着（Dart 侧不再调用）。
- 23 个分批 setter 与旧偏好键留着（见上）。
- `layout/activity_b8_c0.xml` 与 `b8_c0_strings.xml` 里的
  `b8_c0_launcher_label` 留着不动（无害，减小 diff；label 已无引用）。
- 各 impl javadoc 里对 `C0ShellActivity` 的 `{@link}` 注释引用留着
  （注释不参与编译）。

## 原生行与 B7 行的位置与行为

- 原生设置：通用分组第一行（外观行之前）。开/关文字与其它开关行一致
  （开/关）；点按立即翻转，下一次进入任一迁移屏即跟随新值。
- Flutter 设置主屏：通用分组第一行（外观行之前），Switch 形态。只在
  总开关开时可达，恒显示开；关掉后当前 Flutter 屏关闭，回到原生设置。

## 未验证项

- 本机无 Java/Android/Flutter SDK，无法本地编译：Java 编译、Dart
  analyze、Pigeon 重新生成（B7 新方法/字段的两侧命名）以 CI 为准。
- 行为以 Wei 真机为准：总开关开/关后各屏分流、Flutter 设置主屏关开关
  后关屏回原生、第二个启动图标消失。

## Recovery anchor

- 目标：一个总开关 + 撤掉分开关与实验屏，全部 23 批跟随总开关。
- 当前状态：本地提交待 CI + 真机验证。
- 下一步：推分支，等 CI 新 run，Wei 装包验收。
