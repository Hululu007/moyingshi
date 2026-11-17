# B8 CI #28 analyze 修复

CI run #28（联合推送 B1–B22，分支 b8-flutter-rewrite，HEAD 83ddb8d7）在
"Analyze Dart code (fail fast)" 失败：`flutter analyze` 报 57 条诊断
（48 error + 2 warning + 7 info）。本仓库 CI 里 info 级诊断也会失败
（C0 run #1 的先例），所以 57 条全部清掉。本次修复只改 Dart/Pigeon，
不动 Java、workflow、其它合约文件。

## 诊断归类与修复

| 文件 | 数量 | 诊断 | 根因 | 修复 |
|---|---|---|---|---|
| pigeons/b22_drama_messages.dart | 33 error | not_initialized_non_nullable_instance_field | 4 个数据类（B22DramaTheaterScreen、B22DramaItem、B22DramaHistoryScreen、B22DramaHistoryItem）只声明字段、没写构造函数；其它合约（如 c2_messages.dart）都有 required 命名参数构造函数 | 按 c2 同风格给 4 个类补构造函数，字段名/类型/顺序不变；B22HostApi 不动 |
| b3_settings_a_page.dart（4）、b5_personal_page.dart（2）、b4_enhance_page.dart（1）、b6_player_page.dart（1）、b7_settings_home_page.dart（1） | 9 error | non_abstract_class_inherits_abstract_member | 5 个 mixin 用了 `implements WidgetsBindingObserver`，缺 16 个方法的实现；仓库其它页面都用 `with`（mixin 自带空默认实现） | 5 处 `implements WidgetsBindingObserver` 改 `with WidgetsBindingObserver`；各 mixin 只重写了 didChangeAppLifecycleState，addObserver/removeObserver 调用不变 |
| b10_collect_page.dart（行 579/585/615/619/629） | 5 error | const_with_non_const | `BorderRadius.circular` 不是 const 构造函数，却被 `const` 调用 | 5 处去掉 `const` |
| b21_lab_page.dart | 1 error + 1 warning + 1 info | invalid_assignment（行 1027）/ unused_field（行 128）/ deprecated_member_use（行 1209） | pollLabDetail 是同步合约方法，但 Pigeon 生成的 Dart 侧返回 Future，同步赋值非法；_isHkMiniapp 只写不读；DropdownButtonFormField 的 `value` 已废弃 | _poll 改 async + await（Timer 回调里无 await 调用，analysis_options 未启用 discarded_futures）；删 _isHkMiniapp 字段及其唯一赋值；`value:` 改 `initialValue:`（onChanged 本就写回 controllers，行为不变） |
| b16_report_page.dart | 2 info + 1 warning | deprecated_member_use（行 123/124）/ unused_element_parameter（行 402） | RadioListTile 的 groupValue/onChanged 已废弃；_CenterMessage 的 description 参数没有任何调用处传值 | 范围选择对话框的 Radio 包进 RadioGroup<String>，groupValue/onChanged 挪到 RadioGroup 上；删 description 参数声明、字段及 build 里的条件分支 |
| b18_widget_page.dart（行 232/233） | 2 info | deprecated_member_use | 同上，枚举参数对话框的 RadioListTile 用了废弃的 groupValue/onChanged | 包进 RadioGroup<String>；onChanged 按值反查 option 后 pop（原地逐个 pop 改为按值查找，行为不变） |
| b22_drama_page.dart（行 150/380） | 2 info | prefer_const_constructors | 两处 AppBar leading 的 IconButton 可为 const（_closeScreen 是顶层函数，tear-off 是常量表达式） | 两处加上 `const` |

总计：33 + 9 + 5 + 3 + 3 + 2 + 2 = 57 条。

## 本地验证

- `git diff --check` 干净。
- `grep -rn "implements WidgetsBindingObserver" flutter_module/lib/`：无残留。
- B22 的 4 个类都有构造函数，`required this.` 共 33 个，等于字段总数（6+11+2+14）。
- `grep _isHkMiniapp`（b21）、`grep description`（b16）：无残留；两处 RadioListTile 已无 groupValue/onChanged。
- 11 个改动文件括号平衡检查通过；改动范围只有上述 11 个文件，无新增未使用 import/变量。
- 没有本地 Flutter/Dart SDK，做不了 `flutter analyze`；Pigeon 生成与 analyze 结果以 CI 为准。

## 未验证项

- 无本地 Flutter SDK：Pigeon 代码生成（B22 新构造函数与生成代码的匹配）、`flutter analyze` 全量结果、Gradle 编译，均以 CI run #29 为准。
- RadioGroup 迁移写法（B16/B18）以 CI analyze 为准；行为等价性（选中态、弹窗返回值）待真机验收。
- B21 DropdownButtonFormField `initialValue`：对话框重建时选中态靠 FormField 内部状态保持，待真机验收确认。
- B21 `_poll` 改 async 后，Timer 回调里仍是无 await 调用；analysis_options 未启用 discarded_futures，不会新增诊断。

## Recovery anchor

- 目标：清掉 CI #28 的 57 条 flutter analyze 诊断，一个 commit 提交。
- 分支 b8-flutter-rewrite，main f39a6e0 未动；不 push、不触发 CI。
- 下一步：推分支触发 CI run #29，看 analyze 是否通过；若仍有诊断，按文件路径归属到批次修。
