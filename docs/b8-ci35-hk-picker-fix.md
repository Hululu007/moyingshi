# B8 CI #35 Kotlin 编译修复：HkImportPicker 具名参数

## 失败

CI run #35（44070462135，head 6dd2f14）："Build four release APKs" 失败。
Dart analyze 首次通过（14s），Pigeon 通过；两个 mobile 编译任务失败：
`compileMobileArm64_v8aReleaseKotlin`、`compileMobileArmeabi_v7aReleaseKotlin`。
4 个 APK 一个也没产出。

错误原文（`HkMiniappActivity.kt:57`，每变体 3 条，共 6 条）：

```
e: HkMiniappActivity.kt:57:33 No value passed for parameter 'onImported'.
e: HkMiniappActivity.kt:57:39 Argument type mismatch: actual type is
   '(??? (Unknown type for value parameter added)) -> Unit',
   but '() -> Unit' was expected.
e: HkMiniappActivity.kt:57:41 Cannot infer type for value parameter 'added'.
   Specify it explicitly.
```

## 根因

- 调用处（旧代码，未被任何 B8 批次改过）：
  `picker = HkImportPicker(this) { added -> onImported(added) }`（trailing lambda）。
- 被调方签名被 B20 改了。联合推送提交 `b3d3eec8`
  "feat(b8): joint push — HkImportPicker onCancelled callback"（属 B20，
  见 `HkImportPicker.kt` 顶部的 "B8 B20" 注释及相邻提交
  "B20 Hk impl + registrar"）给构造函数加了一个带默认值的参数：

  ```kotlin
  class HkImportPicker(
      private val activity: AppCompatActivity,
      private val onImported: (added: Int) -> Unit,
      private val onCancelled: () -> Unit = {}
  )
  ```

- 在此之前构造函数只有一个 lambda 参数，trailing lambda 绑定到 `onImported`
  是对的；加了 `onCancelled` 后，Kotlin 按规则把 trailing lambda 绑定到
  最后一个参数 `onCancelled`（`() -> Unit`），于是 `added` 无法推断、
  类型不匹配，而 `onImported` 反而没传值。
- 另一个调用点 `B20HostApiImpl.java:131` 是 Java 代码，两个回调都是位置参数
  显式传入，不受 trailing lambda 改绑影响，无需改动。

## 修复

`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkMiniappActivity.kt:57`
改成具名参数，1 行：

```kotlin
picker = HkImportPicker(this, onImported = { added -> onImported(added) })
```

未改 `HkImportPicker` 签名，未去掉 `onCancelled`，未动其它逻辑。
`onImported(added: Int)` 是该 Activity 的私有方法（第 277 行），与
`(added: Int) -> Unit` 签名一致。

## 本地验证

- `git diff --check` 干净；`git status` 只有 1 个修改文件 + 本 doc。
- 复读改动处：具名参数与构造函数签名一致。
- 全仓 grep `HkImportPicker(`：3 处——KDoc 示例（注释，无影响）、
  `HkMiniappActivity.kt:57`（已修）、`B20HostApiImpl.java:131`（Java 位置参数，
  不受影响）。无其它 trailing-lambda 调用点残留。

## 未验证项

- 本机无 Android SDK，无法本地跑 Kotlin 编译；编译结果以 CI 下一轮为准。
- 真机行为无变化：只改了参数传递方式，回调语义与之前相同。

## Recovery anchor

- 目标：修 CI #35 的 Kotlin 编译错误（HkMiniappActivity.kt:57）。
- 分支/HEAD：`b8-flutter-rewrite` / `6dd2f14`；main 仍是 `f39a6e0`。
- 已完成：具名参数修复 + 本 doc，待 `task_guard finish` 提交。
- 未验证：Kotlin 编译（CI 为准）。
- 下一步：父代理推送并看新一轮 CI。
