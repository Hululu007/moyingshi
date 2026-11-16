# B8 C0 壳工程笔记（实施记录，非评估）

> 状态：**已实施（本地提交，未推送，未经 CI/真机验证）**。本文档记录 C0 壳工程
> 写了什么、为什么这样写、如何验证、如何回滚。评估与方案比较见
> `docs/b8-flutter-rewrite-assessment.md`（§5 方案 C、§6 C0 验收标准、§8 门）。
> 分支：`b8-flutter-rewrite`（本地）；基线：`main` HEAD
> `b57287bd9aadafe2df6b11e8583d5d930e4639fd`；`main` 未动。
>
> **诚实声明**：本机无 Java、Android SDK、Flutter/Dart，本文档所述的一切构建
> 与运行结论均未在本地验证。所有“待 CI 证明 / 待真机证明”项见 §7，不得视为
> 已验证。

## 0. §1 执行声明（仓库 AGENTS.md）

- 完成句：C0 壳工程在本地分支 `b8-flutter-rewrite` 上完成——手写 Flutter
  module + Gradle hook 接入 + FlutterFragment 占位屏 + 一条 Pigeon 往返 +
  专用 CI workflow + 本笔记，本地提交，不推送，不动 `main`。
- 允许路径：`flutter_module/`、`settings.gradle`、`app/build.gradle`、
  `app/src/mobile/AndroidManifest.xml`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/`、
  `app/src/main/res/layout/activity_b8_c0.xml`、
  `app/src/main/res/values/b8_c0_strings.xml`、`.gitignore`、
  `.github/workflows/b8-c0.yml`、`docs/b8-c0-shell.md`。
- 预存脏文件：无（开始时工作树干净）。
- 时间/时区：2026-11-15 22:13 CST（Asia/Shanghai）开始。
- 工期估计：约 25 分钟写文件 + 提交，约 22:40 CST 完成；构建与真机验证
  在 CI/手机上，不在本会话内。
- 最便宜的决定性验证：`git diff --check` + 作用域 diff 复核 + `main` 未动
  检查 + 提交后工作树干净。构建验证只能走 CI（门 G6）。

## 1. C0 范围（以评估文档为准，未扩大）

评估文档 §5 方案 C 对 C0 的定义：新建 Flutter module、Gradle hook 或预构建
AAR 二选一、一个 `FlutterFragment` 承载的空白/占位屏 + 一条 Pigeon 往返调用、
CI 增加 Flutter 构建步骤。验收标准见评估文档 §6（5 条），真机验收步骤见
本文档 §5（逐条对照）。C0 不做数据层变更（Room/设置/历史 schema 一律不动），
不改现有播放器、蜘蛛、实验室、小程序的任何行为。

## 2. 设计选择与理由

| 决策 | 选择 | 理由与证据 |
| --- | --- | --- |
| 接入路由 | Gradle hook（module source code），不用预构建 AAR | 官方文档两种路由都支持；hook 是一步构建、便于同时迭代双侧，CI 可安装 Flutter SDK（评估文档 E1）。AAR 路由多一步构建且产物须分发，本仓库不需要离线构建。 |
| Module 位置 | 仓库内 `flutter_module/`（与宿主同仓库） | 官方示例是 sibling 目录；同仓库则 `settings.gradle` 路径改为相对于 `settingsDir`（已在注释中说明差异）。单仓库便于分支级回滚（删分支即零残留）。 |
| Flutter 版本 | **3.50.0**（stable，Dart 3.14.0） | 2026-11-15 时的最新 stable（官方 releases JSON，见 §3）。文档站亦以 3.50 为准。 |
| Pigeon 版本 | **30.0.2**（精确锁定，无 caret） | 2026-11-15 时的最新版（pub.dev API，见 §3）；Pigeon 要求通信两边用同一版本生成，精确锁定保证可复现。要求 Dart `^3.11.0`，Dart 3.14.0 满足。 |
| Pigeon 生成策略 | CI 内 `dart run pigeon` 生成，两边一次生成 | 生成物不提交，避免生成代码漂移；一次生成保证 Dart/Java 两边同一版本。生成步骤在 Gradle 编译前（workflow 顺序保证）。备选“提交手写生成代码”被否：生成代码数百行，手工复现不可靠。 |
| Pigeon 语言 | Java（不用 Kotlin） | 仓库 99% 是 Java；Java 生成器只产平台通道代码（无原生互操作分支），其 `setUp` 注册形态已从当前 Pigeon 源码（`java_generator.dart`，main 分支）核实，见 §3。 |
| 引擎策略 | Activity 持有 cached engine，`withCachedEngine` | 官方 FlutterFragment 文档模式。引擎在 `super.onCreate()` 之前建好并入缓存，覆盖进程死亡后 FragmentManager 恢复的场景；Pigeon 在 Dart 启动前注册；`isFinishing()` 时才销毁（旋转复用）。 |
| 入口 | 手机端独立 LAUNCHER 入口（`C0ShellActivity`） | 只做加法：零现有 Java/Kotlin 文件改动，不动 Lab/设置屏与流程。图标与主应用相同、标签明确写“实验”，默认不打扰正常使用。TV（leanback）构建包含该类但无入口（manifest 条目只在 mobile manifest 中）。 |
| 渲染/路由 | 默认：新引擎默认值（opaque SurfaceView、路由 `/`、入口 `main()`） | C0 只需证明渲染 + 往返；不定制 renderMode/transparencyMode/initialRoute。 |
| 返回键 | `onBackPressed()` 转发给 fragment（文档示例） | 不用 `shouldAutomaticallyHandleOnBackPressed` builder 选项：该选项在 cached-engine builder 上的可用性未核实，文档示例的转发写法是已确立的形态。 |
| configChanges | 仅 `keyboard\|keyboardHidden\|uiMode` | 旋转/尺寸变化有意不声明，让 Activity 重建，从而验证缓存引擎恢复路径（验收标准 3 的一部分）。 |
| CI | 专用 workflow `b8-c0.yml`，现有 workflow 不动 | 推送到 `b8-flutter-rewrite` + 手动触发；只构建不发布；复用现有 workflow 的 JDK/签名/Android 包约定。 |

未采纳的备选（已记录，不重议）：预构建 AAR 路由（多一步构建）；Lab/设置内
加一行入口（要改现有屏，C0 坚持零现有代码改动）；Kotlin 生成（仓库 Java 为主，
且 Java 生成器形态已核实）；提交手写 Pigeon 生成代码（不可靠）。

## 3. 版本锁定与证据

访问日均为 2026-11-15。以下为实施时实际读取的一手来源：

| 锁定项 | 值 | 证据 |
| --- | --- | --- |
| Flutter SDK | 3.50.0（stable，2026-11-11 发布，commit `ab2912035e160d9cad6306df307576da6128ebdb`） | 官方 releases JSON `https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json`：`current_release.stable` 即 3.50.0，`dart_sdk_version: 3.14.0`，archive sha256 `6ea2d806…ff9f5` |
| Dart SDK | 3.14.0（随 Flutter 3.50.0）；pubspec 约束 `sdk: ^3.14.0`，`flutter: ">=3.50.0"` | 同上 |
| Pigeon | 30.0.2（精确锁定，2026-11-11 发布，要求 Dart `^3.11.0`） | pub.dev API `https://pub.dev/api/packages/pigeon`：`latest.version: 30.0.2` |
| Gradle hook 写法 | Groovy `setBinding` + `apply from: <module>/.android/include_flutter.groovy`；`app/build.gradle` 加 `implementation project(':flutter')`；`download.flutter.io` Maven 仓；Java 17+ 要求（本仓库 Java 21，已满足）；`flutter.androidPackage` 不得与宿主包名相同 | 官方文档 `https://docs.flutter.dev/add-to-app/android/project-setup`（Flutter 3.50，页面更新 2026-05-05） |
| FlutterFragment 写法 | `withCachedEngine(id).build()`、cached engine 创建/缓存/销毁、`FlutterEngineCache.put/get/remove`、OS 信号转发清单 | 官方文档 `https://docs.flutter.dev/add-to-app/android/add-flutter-fragment` 与 `https://docs.flutter.dev/add-to-app/android/add-flutter-screen`（Flutter 3.50） |
| Pigeon 写法 | `@ConfigurePigeon(PigeonOptions(dartOut/javaOut/javaOptions))`、`@HostApi`、`dart run pigeon --input`、Dart 侧 `ExampleHostApi()` + `await` 调用 | 官方文档 `https://docs.flutter.dev/interop/pigeon`（页面更新 2026-11-07）与 Pigeon 示例 README（flutter/packages `main`） |
| Pigeon Java 生成形态 | 外层类 `public class C0Messages`；嵌套 `public interface C0HostApi`；同步方法 `@NonNull String getHostInfo();`；注册 `static void setUp(@NonNull BinaryMessenger, @Nullable C0HostApi)`；`JavaOptions(package/className)` | Pigeon 源码（`main` 分支，2026-11-15 读取）：`packages/pigeon/lib/src/java/java_generator.dart`（`writeHostApi`、`_writeInterfaceMethod`）、`packages/pigeon/lib/src/pigeon_lib.dart`（`JavaOptions` 字段） |
| 宿主现状 | AGP 9.2.1、Gradle 9.5.1、JDK 21、compileSdk 37、minSdk 24、targetSdk 28、4 风味、签名走 Secrets、APK 汇总到 `Release/apk` | 仓库源码：`app/build.gradle`、`gradle/libs.versions.toml`、根 `build.gradle`、`.github/workflows/android-release.yml` |

## 4. 文件清单

新增（`*`）与修改（`M`）。全部 UTF-8 文本，无二进制文件。

```
* flutter_module/pubspec.yaml            module 定义（版本锁定见 §3）
* flutter_module/lib/main.dart           占位屏 + 一个演示按钮 + Pigeon 调用
* flutter_module/pigeons/c0_messages.dart  Pigeon 定义（@ConfigurePigeon + @HostApi）
* flutter_module/analysis_options.yaml   最小分析选项（无 flutter_lints 依赖）
* flutter_module/.metadata               project_type: module，version 3.50.0
* flutter_module/.gitignore              忽略 .android/.dart_tool/生成代码
* flutter_module/README.md               指向本文档的短说明
* app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java
* app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0HostApiImpl.java
* app/src/main/res/layout/activity_b8_c0.xml
* app/src/main/res/values/b8_c0_strings.xml
M settings.gradle                        加 download.flutter.io 仓 + hook 求值
M app/build.gradle                       加 implementation project(':flutter')
M app/src/mobile/AndroidManifest.xml     加 C0ShellActivity 的 LAUNCHER 条目
M .gitignore                             加 Flutter 生成物忽略规则
* .github/workflows/b8-c0.yml            专用 CI workflow（只构建不发布）
* docs/b8-c0-shell.md                    本文档
```

CI 生成、永不提交的文件（已在 `.gitignore` 中）：

```
flutter_module/.android/                 （flutter pub get 生成的包装工程）
flutter_module/.dart_tool/
flutter_module/lib/pigeon/c0_messages.g.dart
app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0Messages.java
```

有意省略（`flutter create -t module` 模板有、但 C0 不需要）：`test/widget_test.dart`
（无 Dart 测试运行步骤）、`.ios/`（无 iOS 宿主）、`flutter_lints` 依赖。

## 5. Wei 真机验收步骤（对照评估文档 §6 的 5 条标准）

前置：CI 产物 `b8-c0-<run>-<sha>` 中的 `mobile-arm64_v8a-b8-c0.apk`（Wei 的手机
用的就是 mobile arm64 包）。安装后桌面会出现两个图标：原“墨影视”和
“墨影视·Flutter 实验”（图标相同，标签不同）。

标准 1——4 风味 APK 可构建/签名/安装/启动，`main` 零改动：
CI 侧看 `b8-c0.yml` 运行成功且 artifact 含 4 个 APK；手机侧安装 mobile arm64
包能正常打开主应用（原图标）。`main` 零改动由分支结构保证（本分支未合入）。

标准 2——包体积与冷启动增量实测并记录：
CI 侧看 artifact 内的 `c0-sizes.txt`（4 个 APK 的字节数与 sha256），与同基线
原生构建（run 43711533935 的 353 MB 四包产物）对照；增量是否可接受由 Wei 判定，
本文档不预设阈值。冷启动增量：手机侧分别冷启动原图标与实验图标，主观感受 +
秒表即可，记录在验收回复里（无自动化测量）。

标准 3——Pigeon 往返 + FlutterFragment 生命周期无崩溃：
1. 点桌面“墨影视·Flutter 实验”图标，应看到标题“Flutter 实验（C0）”的屏、
   文字“这是 Flutter 渲染的占位屏。”、“尚未调用原生接口”和一个按钮。
2. 点“调用原生接口”按钮，文字应变成一行原生信息，形如
   `Mooyingshi 5.6.0 (560) | <机型> | SDK <数字>`。看到这一行 = Pigeon 往返通过。
   若显示“调用失败：…” = 不通过。
3. 旋转手机：屏应跟随旋转且不崩溃，旋转后文字仍在（或可再次点按钮调用成功）。
4. 按 Home 切后台，再从最近任务切回：不崩溃，屏正常。
5. （如方便）从最近任务划掉应用再重新点实验图标打开：不崩溃，可再次调用成功。
   这一步覆盖“内存回收/进程死亡后恢复”路径。

标准 4——Exo 与 MPV 各自冒烟：
点原“墨影视”图标进主应用，各播一片（含字幕与倍速），一次用 Exo 内核、一次用
mpv 内核（在播放设置里切换内核）。任一失败即 C0 不通过——C0 未改播放器，
这里是回归冒烟，不是新功能验收。

标准 5——回滚验证：
按 §6 移除 module 与 CI 步骤后，构建恢复到基线状态（CI 上 `android-release.yml`
在回滚后的分支上仍能成功构建）。此条在需要回滚时才验证，平时不执行。

## 6. 回滚步骤

阶段级（保留分支，只去掉 C0）：

1. 删除 `flutter_module/` 整个目录。
2. `settings.gradle`：删掉 `download.flutter.io` 仓条目、`flutterStorageUrl`
   声明与文件末尾的 hook 三行。
3. `app/build.gradle`：删掉 `implementation project(':flutter')` 一行及注释。
4. `app/src/mobile/AndroidManifest.xml`：删掉 `C0ShellActivity` 的 `<activity>`
   条目及注释。
5. 删除 `app/src/main/java/com/github/moyingshi/osc/tv/flutter/`、
   `app/src/main/res/layout/activity_b8_c0.xml`、
   `app/src/main/res/values/b8_c0_strings.xml`、`.github/workflows/b8-c0.yml`、
   `docs/b8-c0-shell.md`。
6. `.gitignore`：删掉 B8 C0 段落。
7. 提交后跑一次 `android-release.yml`（或 `b8-c0.yml` 已删除故只需确认原
   workflow 成功），即恢复基线。无数据层变更，无需数据回滚。

分支级：直接丢弃 `b8-flutter-rewrite` 分支，`main` 不受任何影响（C0 从未合入）。

## 7. 未能验证的项（全部标注“待 CI 证明 / 待真机证明”）

本机无 Flutter SDK，Dart/Java 代码均未编译。以下每一项都必须在 CI 或真机上
证明后才能视为成立：

| # | 待证明项 | 证明方式 | 关联门 |
| --- | --- | --- | --- |
| 1 | `flutter pub get` 能生成 `.android/include_flutter.groovy` | CI 步骤 `test -f .android/include_flutter.groovy` | G6 |
| 2 | `dart run pigeon` 能按 `@ConfigurePigeon` 生成 Dart + Java 两边（含 `../app/...` 相对路径） | CI 步骤 `test -f` 两处生成物 | G6 |
| 3 | 生成的 Java 接口形态与 `C0HostApiImpl` 手写实现一致（方法签名、`setUp` 形态） | Gradle 编译通过 | G6 |
| 4 | 生成的 Dart 客户端形态与 `main.dart` 手写调用一致（`C0HostApi()` + `await getHostInfo()`） | `flutter analyze` + Gradle 编译通过 | G6 |
| 5 | Flutter 3.50.0 的 Gradle hook 与 AGP 9.2.1 / Gradle 9.5.1 / JDK 21 / compileSdk 37 兼容 | 4 风味 release 构建通过 | G6 |
| 6 | `FLUTTER_ROOT` / `flutter.sdk` 双保险能让 hook 找到 SDK | 同上（任一失败即配置期报错） | G6 |
| 7 | 4 个风味 APK 均可签名、安装、启动 | CI 成功 + Wei 手机安装 | 验收 1 |
| 8 | 包体积增量（`c0-sizes.txt`）与冷启动增量可接受 | CI sizes 文件 + Wei 判定 | 验收 2 |
| 9 | Pigeon 往返在真机上工作 | §5 标准 3 步骤 1–2 | 验收 3 |
| 10 | 旋转、后台/前台、进程死亡恢复无崩溃 | §5 标准 3 步骤 3–5 | 验收 3 |
| 11 | Exo/MPV 冒烟通过（零回归） | §5 标准 4 | 验收 4 |
| 12 | 桌面出现两个图标、实验入口标签正确、无其他可见变化 | Wei 手机目视 | — |
| 13 | R8/minify 下 Flutter 引擎与 Pigeon 生成代码无混淆问题（release 构建 `minifyEnabled=true`） | release 构建 + 真机运行 | G6 |
| 14 | `subosito/flutter-action@v2` + `cache: true` 在 runner 上可用 | CI 步骤成功 | G6 |

如官方文档与仓库现状冲突（如工具链兼容性），本文档不强行降级仓库工具链；
冲突项一律记为上表中的待证明项，由 CI 结论决定下一步。

## 8. Recovery anchor（恢复锚点）

- 目标：C0 壳工程实施（见 §0 完成句）；评估见 `docs/b8-flutter-rewrite-assessment.md`。
- 状态：已实施并本地提交；未推送；未 CI 构建；未真机验证。下一步唯一动作：
  父代理决定推送分支后，CI 自动跑 `b8-c0.yml`，Wei 按 §5 验收。
- 分支/HEAD：`b8-flutter-rewrite`，基线 `b57287bd`；评估提交 `18d647fe`；
  本次 C0 提交见提交记录（`git log --oneline -3`）。
- 变更文件：见 §4 文件清单；无产品行为改动，无数据层变更。
- 验证：`git diff --check` 通过；`main` 仍指向 `b57287bd`；工作树干净；
  构建/真机验证全部待 CI（§7）。
- 未解决风险：§7 的 14 项待证明项；任一关键项失败即按 §6 回滚或停在 C0。
- 下一步：推送分支 → CI 构建 → Wei 真机验收（§5）→ 决定是否进入 C1。
