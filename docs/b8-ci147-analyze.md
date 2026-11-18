# b8-ci147-analyze — run #147 Dart analyze 失败修复

## 失败

- Run #147（id 44234366455，head `2aeda631`，阶段 4b 删除提交触发），job
  "Build C0 shell APKs" 第 13 步 "Analyze Dart code (fail fast)" 失败；
  后续签名/打包/发布步骤全部跳过，无 prerelease、无 APK。
- `flutter analyze` 共 5 个问题，0 个 error：2 个 warning + 3 个 info。
  CI 步骤为裸 `flutter analyze`，任一问题即非零退出。

## 5 个问题与修法

| # | 位置 | 级别/规则 | 问题 | 修法 |
|---|------|-----------|------|------|
| 1 | `flutter_module/lib/b11_home_page.dart:3:36` | warning `undefined_shown_name` | `import 'dart:ui' show ImageFilter, Matrix4;`——`dart:ui` 不导出 `Matrix4` | `dart:ui` 只保留 `ImageFilter`；`Matrix4` 改从 `package:vector_math/vector_math_64.dart` 导入 |
| 2 | `b11_home_page.dart:1603` | info `deprecated_member_use` | `Matrix4.translate(cx, cy)` 已弃用 | `..translateByVector3(Vector3(cx, cy, 0.0))` |
| 3 | `b11_home_page.dart:1604` | info `deprecated_member_use` | `Matrix4.scale(lensScale)` 已弃用 | `..scaleByVector3(Vector3.all(lensScale))` |
| 4 | `b11_home_page.dart:1605` | info `deprecated_member_use` | `Matrix4.translate(-cx, -cy)` 已弃用 | `..translateByVector3(Vector3(-cx, -cy, 0.0))` |
| 5 | `flutter_module/lib/b7_settings_home_page.dart:542` | warning `unused_element_parameter` | `_HomeRow` 可选参数 `trailing` 从未被传入 | 删构造参数 + 字段 + 字段注释；build 里删死分支 `if (trailing != null) ...[trailing!]`，`if (trailing == null && showChevron)` 简化为 `if (showChevron)` |

## 语义等价性（透镜变换）

- `translate(x, y)` ≡ `translateByVector3(Vector3(x, y, 0.0))`；`scale(s)`（单参
  均匀缩放）≡ `scaleByVector3(Vector3.all(s))`。弃用只是改名，变换
  矩阵逐字一致：先平移至透镜中心、均匀缩放、再平移回去。玻璃效果不变。
- 新 API 依据：vector_math 最新版 `Matrix4` 文档（pub.dev，
  `translateByVector3(Vector3)` / `scaleByVector3(Vector3)` 均存在；
  `translateByDouble`/`scaleByDouble` 为 4 参（含 w），不是 2/3 参调用的
  直接替换，故选用 Vector3 形态）。

## 为什么现在才暴露

- 问题 1–4 全部来自 glass v2（提交 `99090103`）：透镜代码是那次引入
  的，`Matrix4` 的错误 import 从那时起就存在。
- 之后 4 次运行（`29e5aa34`、`7f01f3de`、`858839fc`、`5a6b9a23` 对应
  run #143–#146）均在到达 analyze 之前被后续推送取消（concurrency
  cancel-in-progress），所以 analyze 从未真正跑过；run #147 是 glass v2
  之后第一个完整跑到 analyze 的运行。
- 问题 5 来自阶段 2（提交 `5109ee08`）：总开关行被删除后，`_HomeRow`
  的 `trailing` 参数再无调用方；其字段注释本身就写着 "no row passes
  one since the master-switch row was removed"。同样是首次跑到 analyze
  才暴露。

## 未引入新问题

- `vector_math` 是 Flutter 的传递依赖，不新增 Dart 依赖，`pubspec.yaml`
  未动。模块 `analysis_options.yaml` 刻意不含 `package:flutter_lints`，
  只启用 3 条 lint（`prefer_const_constructors` / `prefer_final_fields` /
  `avoid_print`），故直接引用传递包不会触发
  `depend_on_referenced_packages`；新增的 `Vector3(...)` /
  `Vector3.all(...)` 均非常量可构造，不触 `prefer_const_constructors`。
- `trailing:` 在 `b19/b20/b21` 的命中是 Flutter `ListTile.trailing`，
  与 `_HomeRow` 无关，未动。
- `Matrix4` 全模块仅 b11 两处引用（import + 透镜），已全部处理。

## 静态核查（本机无 Dart/Flutter SDK，CI 为第一次真实编译检查）

- `dart:ui` 含 `Matrix4` 的 import：0 命中；旧 `..translate(` /
  `..scale(` 调用：0 命中；b7 文件内 `trailing`：0 命中。
- 改动文件括号计数与 HEAD 对比：b11 仅 `(`/`)` 各 +3（每行透镜调用
  多一层 `Vector3(`），成对平衡；b7 全括号类型 delta 均为 0。
- `git diff` 仅 2 个 scope Dart 文件 + 本文档；`pubspec.yaml`、
  Java/Pigeon/manifest 未动。

## 验收

- 下一次 CI 运行 analyze 步骤通过（0 issues），后续打包/发布步骤恢复；
  真机验收仍按 glass v2 清单：滑动时透镜效果可见、玻璃开关与
  distortion/dispersion 设置生效、设置首页行渲染与 chevron 正常。
