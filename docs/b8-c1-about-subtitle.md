# B8 C1：关于页 + 字幕设置迁移到 Flutter（实施记录）

> 状态：**已实施（本地提交，未推送，未经 CI/真机验证）**。评估与阶段定义见
> `docs/b8-flutter-rewrite-assessment.md`（§5 方案 C、§6 后续阶段 go/no-go、
> §7 回滚路径）；C0 壳工程见 `docs/b8-c0-shell.md`。分支：
> `b8-flutter-rewrite`；基线：`main` 合并后 `b2221ca`（含 P12 MPV 修复）；
> `main` 未动。
>
> **诚实声明**：本机无 Java、Android SDK、Flutter/Dart，一切构建与运行结论
> 均未在本地验证。所有“待 CI 证明 / 待真机证明”项见 §6，不得视为已验证。

## 0. §1 执行声明（仓库 AGENTS.md）

- 完成句：C1 在 `b8-flutter-rewrite` 上把手机端“关于”与“字幕设置”两个
  低风险屏迁移到 Flutter——新 Pigeon C1 合约 + `C1ScreenActivity` 承载
  `/about` 与 `/subtitle` 两个路由 + 功能开关控制原生/Flutter 切换（默认
  原生）+ C1 任务文档，本地提交，不推送，不动 `main`。
- 允许路径：`flutter_module/pigeons/c1_messages.dart`、
  `flutter_module/lib/main.dart`、`flutter_module/lib/c1_about_page.dart`、
  `flutter_module/lib/c1_subtitle_page.dart`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1HostApiImpl.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/ui/dialog/AboutDialog.java`、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SettingFragment.java`、
  `app/src/main/res/layout/activity_b8_c1.xml`、
  `app/src/mobile/AndroidManifest.xml`、`.gitignore`、
  `.github/workflows/b8-c0.yml`、`docs/b8-c1-about-subtitle.md`。
- 预存脏文件：无（开始时工作树干净）。
- 时间/时区：2026-11-16 22:05 CST（Asia/Shanghai）开始。
- 工期估计：文档 ~15 分钟 + Dart ~40 分钟 + Java ~30 分钟 + 验证提交
  ~15 分钟，约 23:40 CST 完成；构建与真机验证在 CI/手机上，不在本会话内。
- 最便宜的决定性验证：`git diff --check` + 作用域 diff 复核 + `main` 未动
  检查 + 提交后工作树干净。构建验证只能走 CI（门 G6/G7）。

## 1. 选屏与理由

C1 定义（评估文档 §5 方案 C）：无播放、无复杂状态的屏。候选屏实测如下：

| 候选 | 原生实现 | 判定 |
| --- | --- | --- |
| **关于**（选） | `AboutDialog.java`（弹窗，非全屏 Activity）+ `dialog_about.xml`：标题、免费徽章、版本行、免责声明、检查更新 / GitHub 代理 / 我知道了 / 更新设置 4 个入口；版本号连点 5 次开关隐藏设置 | 选。内容基本静态；唯一状态是隐藏设置开关（1 个布尔值）；3 个复杂子流程（检查更新、更新设置、GitHub 代理）全部留在原生，以 host 调用复用，不在 Dart 里重做 |
| **字幕设置**（选） | `SettingSubtitleFragment.java`（90 行）+ `fragment_setting_subtitle.xml`：自动匹配开关、偏好语言（原生单选弹窗）、字幕源（原生汇总弹窗）共 3 行 | 选。最扁平的子设置页：无条件显隐链（对比弹幕页的 load→api→auto→spider 四级联动）；全部状态是 3 个 `Setting` 读写；两个弹窗留在原生 |
| 弹幕设置 | `SettingDanmakuFragment.java`（105 行） | 不选。行数虽小，但有四级条件显隐联动，状态机比字幕页复杂；留待 C1 之后或 C2 |
| 设置主屏 | `SettingFragment.java`（996 行，7 个分组 + 下载列表 + 搜索入口） | 不选。体量与入口太多，不属于“低风险屏” |
| 播放器相关设置 | `SettingPlayerFragment.java`（588 行） | 不选。贴近播放器，C3 的事 |

**呈现形态说明**：原生“关于”是个弹窗，C1 把它做成一个 Flutter 全屏
（`C1ScreenActivity`）。这是 C1 有意接受的形态变化：弹窗的窗口尺寸、
圆角、遮罩等窗口级行为在 Flutter 侧不复刻；逐项对照（§4）只对照内容、
文案、行为，不对照弹窗窗口形态。Wei 真机验收时以 Flutter 屏为准。

**移动端限定**：两个 Flutter 入口只接在手机端 `SettingFragment` 上。
电视端 `SettingActivity`（leanback）继续走原生 `AboutDialog`，字幕设置
电视端有独立的 `activity_setting_subtitle.xml`，同样不动。TV 不在 C1
范围内（评估文档 G5/C4）。

## 2. 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- 开关存在 `Setting` 里，与现有布尔偏好同一写法：
  `isFlutterC1Enabled()` / `putFlutterC1Enabled(boolean)`，键
  `flutter_c1_enabled`，默认 `false`。
- 开关 UI 在 C0 实验屏（`main.dart` 的 `C0HomePage`）里：一个
  `SwitchListTile`“用 Flutter 打开关于 / 字幕设置”，读/写走 C1 合约的
  `isC1Enabled()` / `setC1Enabled(bool)`。开关只在实验屏出现，不进正式
  设置页——C1 是实验，不是默认行为。
- 手机端 `SettingFragment` 两个入口查开关：
  - `onVersion()`：开关开 → `C1ScreenActivity.start(activity, "/about")`；
    开关关 → 原样 `AboutDialog.show(...)`。
  - 字幕行：开关开 → `C1ScreenActivity.start(activity, "/subtitle")`；
    开关关 → 原样 `getRoot().change(6)`。
- 回滚 = 关开关（实验屏上点一下），或删分支；原生实现一行未删。

### 2.2 宿主 Activity 与引擎（沿用 C0 已验证形态）

- `C1ScreenActivity` 复刻 `C0ShellActivity` 的 cached-engine 模式：独立
  engine id `b8_c1_engine`，`super.onCreate()` 之前建引擎并注册 Pigeon，
  只在 `isFinishing()` 时销毁；OS 信号同样转发给 fragment。
- 与 C0 的区别只有两点：初始路由来自 Intent（`EXTRA_ROUTE`，
  `/about` 或 `/subtitle`）；注册的是 `C1Messages.C1HostApi` 而非 C0。
- 布局复刻 `activity_b8_c0.xml` 为 `activity_b8_c1.xml`（同为一个
  `fragment_container` 的 `FrameLayout`），manifest 只在 mobile 侧声明，
  无 launcher 入口（不新增桌面图标）。
- C0 与 C1 是两个独立 engine，可同时存活（从实验屏开开关后退回、再进
  设置页开 Flutter 屏时，C0 engine 仍在缓存里）。实验阶段接受该内存
  代价；不做 engine 复用优化。

### 2.3 路由：Dart 侧问宿主（不用 Fragment initialRoute）

- `FlutterFragment.withCachedEngine` 的 `initialRoute` 形态在 Flutter
  3.50 是否可用未在本机核实，不赌它。改用 Dart `main()` 启动时先调
  一次 host（`C1HostApi.getInitialRoute()`），再按返回的路由建
  `MaterialApp`：`'/'` → C0 占位屏，`'/about'` → 关于页，
  `'/subtitle'` → 字幕设置页；host 调用失败则回落 `'/'`。
- 两个 engine 的 impl 各自返回自己的路由：C0 engine 的 impl 返回 `'/'`，
  C1 engine 的 impl 返回 Intent 里的路由。启动时有一次往返，屏会晚几
  毫秒出现；实验阶段可接受。

### 2.4 Pigeon C1 合约（最小扩展，只够这两个屏用）

新文件 `flutter_module/pigeons/c1_messages.dart`，CI 内一次生成 Dart +
Java 两边（workflow 见 §3）。数据类只做扁平字段；**全部展示文案都由
host 返回**（`getString` 解析后的当前语言文案），Dart 侧不硬编码任何
中文/英文文案，避免与 `values-zh-rCN` 漂移。

```dart
@HostApi()
abstract class C1HostApi {
  String getInitialRoute();            // '/' | '/about' | '/subtitle'
  bool isC1Enabled();                  // 开关读
  void setC1Enabled(bool enabled);     // 开关写
  C1AboutInfo getAboutInfo();          // 关于页全部文案 + 隐藏设置状态
  bool toggleHiddenSettings();         // 连点 5 次后调用；host 写 Setting + Toast，返回新状态
  void checkUpdate();                  // Updater.create().force().start(activity)
  void openUpdateSettings();           // 原生 UpdateSettingsDialog
  void openGithubProxy();              // 原生 AboutDialog 里的 GitHub 代理弹窗
  C1SubtitleInfo getSubtitleInfo();    // 字幕页全部文案 + 状态
  void setSubtitleAutoMatch(bool enabled);
  void openSubtitleLanguage();         // 原生单选弹窗；选后写 Setting
  void openSubtitleSource();           // 原生字幕源汇总弹窗
  void closeScreen();                  // finish() 宿主 Activity
}

class C1AboutInfo {
  String title; String badge; String versionLine; String disclaimer;
  String checkUpdateLabel; String githubProxyLabel; String confirmLabel;
  String updateSettingsLabel; bool hiddenSettingsEnabled;
}

class C1SubtitleInfo {
  String screenTitle; String autoMatchTitle; bool autoMatchEnabled;
  String onText; String offText;
  String languageTitle; String languageText;
  List<String> languageLabels; List<String> languageValues;
  String currentLanguage;
  String sourceTitle; String sourceText;
}
```

- 方法全部同步、无回调：原生弹窗类 host 方法都是 fire-and-forget；
  弹窗关掉后 Flutter 侧在 `AppLifecycleState.resumed` 时重新
  `getSubtitleInfo()` 刷新（语言/字幕源可能在原生弹窗里改了）。关于页
  不需要 resumed 刷新（页内唯一状态变化就是连点开关，Dart 自己知道）。
- `checkUpdate()` 不先关 Flutter 屏：原生是先关弹窗再启动更新器；Flutter
  屏本身就是 Activity，先 finish 再拿它的 window token 启动更新器有
  风险。更新器界面直接盖在 Flutter 关于页上，返回后仍在关于页，点
  “我知道了”关闭。这是与原生有意的偏差，见 §4。
- `openGithubProxy()` 复用 `AboutDialog` 里的同名私有方法：把该方法由
  `private` 改为 `public`（一词改动，无行为变化），C1 不复制那 200 行
  弹窗逻辑（含延迟探测、dpad 导航）。

### 2.5 哪些东西明确不做

- 不碰 Room/设置/历史 schema（评估文档 §7 硬性前提）；只读写已有
  `Setting` 键 + 1 个新开关布尔键。
- 不碰播放器、蜘蛛、实验室、小程序；不改 `C0HostApi` 合约（C0 冻结）。
- 不新增 Dart 依赖；不改 Flutter/Pigeon 版本锁定。
- 不改 `android-release.yml`；C1 构建仍走 `b8-c0.yml`（只加一行 Pigeon
  生成命令，见 §3）。
- 不改任何电视端代码与布局。

## 3. 文件清单

新增（`*`）与修改（`M`）。全部 UTF-8 文本，无二进制文件。

```
* flutter_module/pigeons/c1_messages.dart   C1 合约（§2.4）
* flutter_module/lib/c1_about_page.dart     关于页（Flutter）
* flutter_module/lib/c1_subtitle_page.dart  字幕设置页（Flutter）
M flutter_module/lib/main.dart              路由分发 + C0 实验屏加 C1 开关
* app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java
* app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1HostApiImpl.java
M app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java
                                            C0 engine 上也注册 C1HostApi（只为实验屏的开关用）
M app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java
                                            + isFlutterC1Enabled / putFlutterC1Enabled
M app/src/main/java/com/github/moyingshi/osc/tv/ui/dialog/AboutDialog.java
                                            showGithubProxy 由 private 改 public（仅此一词）
M app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SettingFragment.java
                                            onVersion + 字幕行查开关分流
* app/src/main/res/layout/activity_b8_c1.xml
M app/src/mobile/AndroidManifest.xml        加 C1ScreenActivity（无 launcher 入口）
M .gitignore                                + C1Messages.java（CI 生成，不提交）
M .github/workflows/b8-c0.yml               Pigeon 步骤加 c1_messages.dart 生成 + 存在性检查
* docs/b8-c1-about-subtitle.md              本文档
```

CI 生成、永不提交的文件（已在 `.gitignore` 中）：

```
flutter_module/lib/pigeon/c1_messages.g.dart
app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1Messages.java
```

## 4. 逐项对照清单（Wei 真机验收用）

先在实验屏打开开关，再进设置页对照；每项与原生逐条比：

**关于页（Flutter `/about` vs 原生 AboutDialog）**
1. 标题、免费徽章、版本行（`版本 fullName · flavor/abi`）文案一致。
2. 免责声明全文一致、可滚动到底。
3. “检查更新”点后出现更新器界面（原生行为）；返回后仍在关于页。
4. “加速”（GitHub 代理）点后出现原生代理弹窗，可增删源、测延迟、开关。
5. 右下角小图标点后出现原生更新设置弹窗。
6. “我知道了”关闭页面，回到设置页。
7. 版本行连点 5 次（2 秒内）：Toast 提示隐藏设置开/关；回设置页后隐藏分
   组出现/消失（与原生一致：`SettingFragment.onResume` 重建分组）。
8. 旋转、切后台再回、划掉重进：不崩溃，内容还在。

**字幕设置页（Flutter `/subtitle` vs 原生 SettingSubtitleFragment）**
1. 标题“字幕设置”，三行：自动匹配 / 偏好语言 / 字幕源，顺序与原生一致。
2. 自动匹配行点后开关翻转，文案“开/关”同步变；退出重进后状态保持。
3. 偏好语言行点后出现原生单选弹窗，选后行内文案变成所选语言（如“英语”）。
4. 字幕源行点后出现原生汇总弹窗；文案“已加载 N 个”与原生一致。
5. 旋转、切后台再回：不崩溃，三行状态与原生一致。

**开关与回滚**
1. 开关关掉后，设置页“关于”与“字幕设置”回到原生（弹窗 / 原生页）。
2. 电视端不受影响（无 Flutter 入口）。

## 5. 回滚步骤

- 开关级：实验屏关掉 C1 开关，两个入口立即回到原生；Flutter 代码留在
  分支里不生效。
- 分支级：直接丢弃 `b8-flutter-rewrite` 分支，`main` 不受任何影响（C1
  从未合入）。无数据层变更，无需数据回滚；开关布尔键残留无害。

## 6. 未能验证的项（全部标注“待 CI 证明 / 待真机证明”）

本机无 Flutter SDK，Dart/Java 代码均未编译。以下每一项都必须在 CI 或真机上
证明后才能视为成立：

| # | 待证明项 | 证明方式 | 关联门 |
| --- | --- | --- | --- |
| 1 | `dart run pigeon --input pigeons/c1_messages.dart` 能生成 Dart + Java 两边（含 `List<String>` 字段与两个数据类） | CI 步骤 `test -f` 两处生成物 | G6/G7 |
| 2 | 生成的 Java 接口形态与 `C1HostApiImpl` 手写实现一致（方法签名、`setUp` 形态） | Gradle 编译通过 | G6/G7 |
| 3 | 生成的 Dart 客户端形态与 `main.dart` / 两个页的手写调用一致 | `flutter analyze` + Gradle 编译通过 | G6/G7 |
| 4 | `main()` 启动时 `await getInitialRoute()` 再 `runApp` 的形态可用（无 engine 侧约束） | CI 构建 + 真机打开各路由 | G6 |
| 5 | 两个 engine（C0 + C1）同时缓存时内存与生命周期正常 | 真机：实验屏开开关 → 设置页进 Flutter 屏 → 返回 | — |
| 6 | `C1ScreenActivity` 的 manifest 条目只在 mobile 构建生效，leanback 构建不受影响 | 4 风味 release 构建通过 | — |
| 7 | R8/minify 下 C1 的 Pigeon 生成代码无混淆问题 | release 构建 + 真机运行 | G6 |
| 8 | §4 对照清单逐项通过 | Wei 真机验收 | 验收 |
| 9 | 开关关闭后两个入口回到原生 | Wei 真机验收 | 回滚 |

## 7. Recovery anchor（恢复锚点）

- 目标：C1 关于页 + 字幕设置页迁移（见 §0 完成句）；评估见
  `docs/b8-flutter-rewrite-assessment.md`，C0 见 `docs/b8-c0-shell.md`。
- 状态：已实施并本地提交；未推送；未 CI 构建；未真机验证。下一步唯一动作：
  父代理决定推送分支后，CI 自动跑 `b8-c0.yml`，Wei 按 §4 验收。
- 分支/HEAD：`b8-flutter-rewrite`，基线 `b2221ca`（main 合并后，含 P12
  修复）；C1 提交见提交记录（`git log --oneline -3`）。
- 变更文件：见 §3 文件清单；无产品行为改动（默认开关关闭时零行为变化），
  无数据层变更。
- 验证：`git diff --check` 通过；`main` 仍指向 `f39a6e0`；工作树干净；
  构建/真机验证全部待 CI（§6）。
- 未解决风险：§6 的 9 项待证明项；任一关键项失败即按 §5 回滚或停在 C1；
  C1 之后仍有评估文档 §8 的门 G4/G5（C3/C4 前关闭）。
- 下一步：推送分支 → CI 构建 → Wei 真机验收（§4）→ 决定是否继续 C1
  内其他屏或进入 C2。
