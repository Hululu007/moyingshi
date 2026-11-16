# B8 评估：Flutter 重写可行性与方案比较（评估文档，非实现）

> 状态：**评估完成，等待 Wei 决策**。本文档只做调研与方案比较，不包含任何重写代码。
> 实施前必须经 Wei 明确批准（仓库 `AGENTS.md` §7 设计研究强制门）。
> 分支：`b8-flutter-rewrite`（本地，未推送）；基线：`main` HEAD
> `b57287bd9aadafe2df6b11e8583d5d930e4639fd`；`main` 未动，无任何产品代码改动。

## 0. 结论先行

- **推荐：方案 C（Flutter UI + 原生核心混合，add-to-app），但先做 C0 壳工程验证**，
  用实测数据（构建、包体积、启动、Pigeon 往返、原生 Surface 嵌入）决定是否继续。
  若 C0 验证失败或 Wei 不愿承担双栈成本，退回方案 A。
- **不推荐方案 B（全量 Flutter 重写）作为直接目标**：它要求重写或重桥接约 44 万行
  生产代码，并会丢失已验证的三内核能力与 mpv 双渲染路径，而播放收益为零
  （B7 结论已指出 media_kit 之于播放并无增益，见 `docs/b-ui-b1-b7-port.md`）。
- **方案 A（不做，继续原生 + BieCuiLe 风格仿写）是可接受的默认选项**：B1–B6 已证明
  仿写路线可行，零风险，保留全部现有能力与上游同步路径。
- 推荐置信度：**中**。基于已读证据；§8 所列关键门（G2/G3/G4/G5）未验证，
  任何一门失败都可能改变结论。

## 1. 研究问题（决策形）

1. 现有 App 的规模与不可替代的原生资产是什么？哪些部分 Flutter/Dart 没有等价物？
2. media_kit 能否等价替代现有三内核（Exo/ijk/mpv）与 mpv 双渲染路径？
3. Flutter 与现有原生核心之间是否存在一条官方支持、低损耗的缝合线
   （add-to-app / platform channels / Pigeon）？
4. TV 端（Leanback + 遥控器焦点）在 Flutter 下的代价是什么？
5. 三个方案（不做 / 全量重写 / 混合）对功能、兼容、性能、包体积、上游同步、
   构建验证链的影响分别是什么？最小分阶段实施与回滚路径是什么？

## 2. 冻结基线与调研方法

- 基线：本地分支 `b8-flutter-rewrite`，源自 `main` HEAD `b57287bd`
  （2026-11-15 11:58:55 UTC，`fix(hk): 修第 6+7 步构建失败的 3 处编译错`），
  评估开始时工作树干净；仓库共 282 个提交，首提交 2026-11-04。
- 工具链现状（2026-11-15 实测）：本机无 Java、Android SDK、Flutter/Dart，
  本地无法编译或真机验证；构建走 GitHub Actions，Wei 在手机上测 APK。
  因此本评估的全部结论来自源码阅读与已打开的官方/项目文档，**无任何实测**；
  需要设备或构建验证的项一律记为门（§8），不做推测性断言。
- 证据等级：A = 官方文档/确切源码；B = 项目方一手文档；C = 第三方包文档；
  D = 搜索摘要/二手报道（不作决策依据，仅作弱佐证）；E = 无支撑断言（禁用）。
  全部证据见 §9 证据表；凡标注“推断”的内容均为分析，不是已验证事实。
- 上游集成 governor 技能适用性：其 commit-ledger / E*/P*/C* 任务编号机制针对
  上游依赖合并，B8 无上游提交范围，故不适用；其证据标准与播放器门
  （Exo 与 MPV 分别验证、原生供应链溯源、go/no-go 规则）适用，本评估遵守。

## 3. 现有 App 盘点（基线实测，2026-11-15）

### 3.1 规模

| 维度 | 实测值 | 来源 |
| --- | --- | --- |
| 跟踪文件总数 | 5833（`app/` 4296、`third_party/` 712、`docs/` 516） | `git ls-files` 计数 |
| Java/Kotlin | 2761 个文件 / 595,224 行；生产（非测试）约 44.3 万行；2752 个 `.java` + 9 个 `.kt` | Python 逐文件计数 |
| 源码集 | `main` 1374 文件 / 342,753 行；`mobile` 179 / 50,600；`leanback` 146 / 40,573；测试 981 / 152,176 | 同上 |
| Activity / Fragment | 59 个 `*Activity`（main 18、mobile 16、leanback 23、debug 2）、24 个 `*Fragment` | 文件名计数 |
| Manifest 声明 | main：10 Activity / 9 Service / 1 Receiver / 3 Provider；mobile +25 Activity；leanback +31 Activity | 三个 manifest |
| 布局 XML | 427 个（main 134、mobile 164、leanback 129）；XML 共 1231 个 | 文件计数 |
| UI 技术 | 仅 2 个文件用 `@Composable`；UI 绝大多数是 Views/XML，不是 Compose | `git grep` |
| Room 实体 | 11 个 `@Entity` 文件；`Setting.java` 1755 行 | `git grep` / 行数 |

文件名匹配（生产 Java 文件 1690 个）：Subtitle 67、Setting 55、danmaku 44、
Live 42、Player 38、Download 8、Dlna/Cast 15、History 23、Vod 26、Search 26。
以上为量级信号，不是精确功能清单。

### 3.2 播放器（最关键、最复杂的部分）

- `tv/player` 包：326 个文件 / 93,928 行；`PlayerManager.java` 9877 行。
- 三个引擎：`ExoPlayerEngine.java` 1393 行、`MpvPlayerEngine.java` 1206 行、
  `IjkPlayerEngine.java` 352 行，另有 `SystemPlayerEngine` 与 ijk 策略类。
- `PlayerEngine.java`（388 行，已全文读）：约 50 个方法（含默认方法），
  深度耦合 Media3 类型（`Player`、`Tracks`、`Format`、`Effect`、`MediaMetadata`、
  `PlaybackException`），含记录类 `PlaybackFactsSnapshot`、`VideoPlaybackDetails`、
  `RuntimeMetrics`，以及 B6 画面调节/Anime4K 方法、LUT 方法、播放列表队列方法、
  字幕/版本方法、诊断方法与 `ErrorAction` 枚举。
  **缝合线结论（推断，基于 A 级证据）**：该接口无法原样跨 Dart 平台通道，
  必须设计一条新的、窄的、扁平的契约；Pigeon 明确不支持复杂继承、要求扁平化
  数据模型（证据 E2）。
- mpv 双渲染路径（`git grep` 实测，A 级）：`gpu-next`（`MPV_GPU`，libplacebo）
  与 `mediacodec_embed`（`MPV_SURFACE_DIRECT`）两条路径并存，
  `PlayerManager` 有按需重建逻辑（`rebuildAndRestartMpv`），含 DV7/DV8 处理、
  Vulkan 渲染器、hwdec 覆盖；Anime4K 走 GPU 路径的第二 shader 槽位
  （`PlayerManager.java:1058` 注释），Exo 侧 Anime4K 不支持。
  这是与 media_kit 对比的决定性差异（§4.1）。

### 3.3 原生依赖与 JNI

- 35 个跟踪 `.so`，合计 146 MB（双 ABI 合计）：每 ABI 一套 mpv 库
  （`libmpv.so`、`libmvcodec/device/filter/format/util.so`、`libmwresample/scale.so`、
  `libplayer.so`、`libc++_shared.so`）、ijk 库（`libijkffmpeg.so`、`libijkplayer.so`、
  `libijksdl.so`）、`libsherpa-onnx-jni.so`，另有 lab/proot 的
  `libandroid-shmem.so` 与仅 arm64 的 `libexo_ass.so`。
- mpv 原生基线（`README.md`，A 级）：mpv `0.41.0-940-gcca559b41`、
  FFmpeg `177f090e0503`（9.0.1-fongmi）、libplacebo 7.375.0、`curl` 8.21.0 +
  nghttp2 1.69.0；锁定提交已记录；NDK r29 / API 24。
  FFmpeg 库经 SONAME 重命名（`libav*`/`libsw*` → `libmv*`/`libmw*`），
   purpose 是避免与 nextlib 的 FFmpeg 发生 Android 链接器冲突——
  这说明“多套 FFmpeg 共存”是本仓库已知的真实风险，不是理论风险。
- C/C++ 跟踪文件 85 个：`third_party/mpv-player-jni/src/*.cpp`（event/render/
  property/request/thumbnail/iso_dvd/jni_utils/log/main）、
  `app/src/main/cpp/exo_dovi_renderer.cpp`、`nodejs/.../node_bridge.cpp`。
- 62 个补丁（`third_party/patches/`）、6 个锁定 JSON（dvd/exo-ass/
  fongmi-repositories/ijk/media/mpv-native）。
- `app/libs/`：5 个 AAR（forcetech、hook、jianpian、thunder、tvbus）+
  `sherpa-onnx-v1.13.4.jar`；Media3 `1.11.0-alpha01-fongmi` 本地 Maven +
  `nextlib-media3ext`（FFmpeg 渲染器、AV3A）。
- 关键依赖（`app/build.gradle` 已读）：模块 `:catvod`、`:chaquo`、`:nodejs`、
  `:quickjs`；QuickJS、jsoup 1.22.2、Rhino 1.7.14、DocumentFile、Media3、
  Room、WorkManager、WebKit、Glide、JUPnP、NanoHTTPD、NewPipeExtractor、
  Compose UI/foundation、Kyant、Lottie、Material、TFLite、ZXing、leanback、biometric。

### 3.4 运行时与子系统（Flutter/Dart 无等价物的部分）

- **蜘蛛/本地源**：`catvod/` Spider 接口与加载器（`BaseLoader`、`CatSpider`、
  `JarLoader`、`PyLoader`、`SpiderInit`、`SpiderCleanup`）；QuickJS 模块；
  Chaquopy Python 3.10（含 pip `requirements.txt` 与 Python 源码）；
  Node.js 模块 + JNI 桥；`WvSpider.jar` 资产；`LocalSource`。
- **本地服务**：`tv/server/` 50 个文件（NanoHTTPD）；WebHome 25 个 Java 文件；
  README 记录的能力：本地 HTTP 服务、管理页 `/m`、远程托管、一键同步、
  站点注入、WebHome 扩展、播放记录同步、登录态学习、APP 代理、调试日志。
- **海阔小程序**：28 个 `Hk*` 文件，仅 mobile，Rhino 1.7.14 解释器模式
  （`setOptimizationLevel(-1)`），含引擎/解析/存储/播放/云导入导出/预热。
- **实验室**：约 25 个文件（`tv/lab/`），运行时环境管理器（安装 PHP/Python/
  Node、执行命令、终端），手机与 TV 共用。
- **短剧**：`ShortDramaActivity`（mobile）、`DramaSites`、`ShortDramaConfig`、
  `ShortDramaQueueCoordinator`；**弹幕**：`player/danmaku`、
  `master/flame/danmaku`、`tv/danmaku` 三个包；**直播**：mobile 与 leanback
  各一个 `LiveActivity`；**国际化**：`values`、`values-zh-rCN`、`values-zh-rTW`
  及 night/v27/sw600dp 变体，遍布 main/mobile/leanback。
- **推断**：上述运行时（Chaquopy Python、QuickJS、Node JNI、Rhino、JNI 播放器、
  sherpa-onnx、5 个 AAR、NanoHTTPD 服务、Room、DLNA/Cast、弹幕、下载）中，
  绝大多数没有 Dart 等价实现；任何方案都必须保留它们为原生代码并桥接，
  或付出重实现代价。桥接即意味着“混合”，全量重写无法绕开这一点。

### 3.5 构建、签名与发布

- `app/build.gradle`：命名空间/applicationId `com.github.moyingshi.osc.tv`，
  versionName `5.6.0`、versionCode 560；`gradle/libs.versions.toml`：
  compileSdk 37、minSdk 24、targetSdk 28、AGP 9.2.1、Kotlin 2.3.21；
  Gradle 9.5.1、JDK 21、Python 3.10（Chaquopy）、NDK r29（MPV/JNI）与 r28c
  （IJK/DVD）。
- 风味：mode（leanback/mobile）× abi（arm64_v8a/armeabi_v7a）= 4 个 APK。
- 唯一工作流 `.github/workflows/android-release.yml`（仅手动触发）：构建 4 个
  release APK、创建并上传 GitHub Release 与更新通道清单。签名走 GitHub
  Secrets（未见、未记录任何密钥值）。
- 最近一次构建（父代理 2026-11-15 20:29 核实）：run 43711533935 成功，
  22 分 55 秒，产物 `webhtv-v5.6.0-beta-202611151958` 353 MB（含 4 个 APK）。
  单个 APK 体积未核实，本文不引用。

### 3.6 仓库内 Flutter 现状与 BieCuiLe 参照物

- 仓库内**不存在任何 Flutter**：`git grep flutter` 仅命中 10 个文件
  （文档提及、`CatWebActivity.java:36` 一处关于 CatPawOpen `flutter_inappwebview`
  的注释、2 个二进制 `.so` 误报）；`media_kit` 仅出现在 4 篇文档中；
  无 Dart 代码、无 `pubspec`、无 Flutter module。
- BieCuiLe 参照材料**不在本机**：`~/workspace/ui-ref/` 与 `~/ui-ref/` 均不存在。
  `docs/ui-restyle-biecuile.md` §0 记录：BieCuiLe 整个 UI 是 Flutter
  （Dart AOT 于 `libapp.so`）；反编译无法恢复精确颜色/列数/边距；其 39 个
  美术资产（“派大星图标包”、金蜗牛 logo）**不得复用**，须同风格重绘。
  重新获取参照 APK/分析/截图是任何 B8 实施的证据门（G1）。
- B7 结论（`docs/b-ui-b1-b7-port.md`，2026-11-11，不再重议）：media_kit 是
  Flutter 专属包（Dart 包装 libmpv），原生 Android App 无法使用；墨影视已有
  三内核，等价且更多；BieCuiLe 的 media_kit 底层同样是 libmpv，与
  `MpvPlayerEngine` 同源；采用 media_kit 即等于嵌入 Flutter（= B8），
  播放收益为零。

## 4. Flutter 可行性（已读一手来源）

### 4.1 media_kit：可用，但与现有 mpv 能力不等价

证据 E4（media_kit README，main 分支，2026-11-15 读取，B 级）：

- media_kit 是纯 Dart 包，经 `dart:ffi` 调用 libmpv 的 C API；分包为
  `media_kit`、`media_kit_video`、`media_kit_libs_video`/`_audio`；
  README 标注版本 `media_kit ^1.2.6`、`media_kit_video ^2.0.1`、
  `media_kit_libs_video ^1.0.7`（以访问日 README 为准，pub.dev 当前版未核）。
- 功能面：硬件/GPU 加速、播放列表、音量/速率/音调、音视频/字幕轨选择、
  外挂音轨/字幕、HTTP headers、视频控制、字幕样式、截图。
- **Android 渲染路径（决定性）**：经 Flutter Texture Registry API
  （基于 `SurfaceTexture`），libmpv 以 `--wid` 直接渲染到 `android.view.Surface`，
  要求 `--hwdec=mediacodec` + `--vo=mediacodec_embed`，并依赖一个 JNI 辅助库
  （`media-kit-android-helper`）获取 Surface 全局引用与访问 APK 资产。

与 §3.2 对比的结论（基于 A 级源码 + B 级 README 的分析）：

| 能力 | 现有原生 mpv | media_kit（文档路径） |
| --- | --- | --- |
| `mediacodec_embed` 表面直通 | 有 | 有（默认/文档路径） |
| `gpu-next` + libplacebo | 有 | **未在 README 中确立**（门 G2） |
| Anime4K（shader 槽位） | 有（GPU 路径） | **取决于 G2**；`mediacodec_embed` 绕过 GPU 渲染管线，shader 能力存疑（推断，待验证） |
| DV7/DV8 处理、Vulkan、hwdec 覆盖与重建 | 有 | 未确立（门 G2） |
| Exo / ijk 双内核 | 有 | 无（media_kit 只有 libmpv 这一内核） |
| 诊断/事实快照/自动上下文 | 有（`PlaybackFactsSnapshot` 等） | 需重建（推断） |

- **原生库共存（门 G3，未验证）**：`media_kit_libs_video` 自带一套 libmpv/
  FFmpeg `.so`；本仓库的 FFmpeg 已 SONAME 重命名以避让 nextlib 的 FFmpeg。
  两套 FFmpeg/libmpv 在同一 APK 内是否冲突、是否重复计体积，未验证，
  不可断言。只可说：这是已知风险模式，不是凭空担忧。

### 4.2 缝合线：add-to-app 与 Pigeon（官方支持，A 级）

证据 E1（`docs.flutter.dev/add-to-app`，2026-11-15 读取，Flutter 3.50）：

- add-to-app 在 Android 上受支持；移动端仅 multi-engine 模式（multi-view
  仅 Web）；同一应用内打包多个 Flutter 库不受支持。
- 集成方式：Gradle hook 自动构建导入 Flutter module，或预构建 AAR；
  `FlutterEngine` API 可独立于 `FlutterActivity`/`FlutterFragment` 启动与保持；
  Java 与 Kotlin 宿主均支持；Android 端仅支持 AndroidX 应用；
  可使用 Flutter 插件与平台交互；支持 `flutter attach` 调试与热重载。
- 官方列出的典型用例包括**混合导航栈**（部分屏 Flutter、部分屏原生）与
  **局部 Flutter 视图**——这正是方案 C 的形态。
- 限制：不假设 Flutter Activity 恒存在的插件可能行为异常；multi-engine 下
  各实例是隔离的 Dart 程序、状态独立。

证据 E2（`docs.flutter.dev/interop/pigeon`，2026-11-15 读取）：

- Pigeon 生成 Dart 与宿主平台之间的类型安全绑定，替代手写平台通道；
  低层原生内存/C/C++ 集成改用 FFIgen/JNIgen。
- 两种通信选项：平台通道（稳定，支持全平台、零外部工具链）与原生互操作
  （直接 FFI/JNI，**预览版，不稳定，不可用于生产**）。
- 约束：不支持复杂继承，数据模型应扁平化；多引擎 + 原生互操作时绑定与
  “最近注册的宿主实例”相关（Flutter issue #195877），需谨慎。

对本项目的含义（分析）：官方推荐的缝合线是 **Pigeon + 平台通道（稳定模式）**；
`PlayerEngine` 级别的宽接口必须重设计为窄契约；高频数据流（进度、诊断）
应走事件流设计，不应逐帧走方法调用；原生互操作预览版不可作为生产依赖。
视频画面本身不走通道——画面嵌入方式（原生 SurfaceView 保留原生、
Texture、PlatformView）是独立的门（G4），须实测。

### 4.3 包体积与启动（A 级官方口径 + D 级弱佐证）

证据 E3（Flutter 官方 FAQ，2026-11-15 读取）：引擎只增加“几 MB 压缩后”
的基线体积（因平台/架构而异）；应用体积主要由资产、字体与第三方包决定；
Dart 代码 AOT 编译为本机代码；Android 默认渲染器为 Impeller（Vulkan）。

- 对本项目：引擎基线相对 353 MB 的四 APK 产物（§3.5）不是主要矛盾；
  真正的体积问题是原生库是否重复（门 G3），须实测，本文不给数字。
- 第三方 add-to-app 报道（D 级，仅搜索摘要，未打开原文，不作决策依据）：
  普遍提到通用 APK 增加数十 MB、Android 冷启动增加。仅作“需在 C0 实测
  体积与启动时间”的提醒，不引用具体数值。

### 4.4 TV 端：无官方框架，焦点层须自建或依赖社区包

- 两次搜索（2026-11-15）**未找到** Flutter 官方 Android TV 指南或第一方
  TV 框架（否定性陈述，仅说明“未找到”，不是“不存在”）。
- 证据 E5（`dpad` 包文档，pub.dev，2026-11-15 读取，C 级）：Flutter 内置方向
  焦点按几何最近选择 widget，在真实 TV 布局中会跳 lane、跳行、逃出轮播、
  丢失焦点记忆；`dpad`（`^3.3.0`）以 region/beam 模型替代，复刻 Android
  `FocusFinder`/Leanback 行为，含焦点记忆、边缘行为、懒加载列表感知、
  文本框方向键语义。TV 上架还要求 `android:banner`、`LEANBACK_LAUNCHER`、
  overscan 安全布局。
- 对本项目：leanback 源码集 146 文件 / 40,573 行 + 31 个 Activity，且 B 系
  restyle 明确“TV 未动”。TV 的 Flutter 化是一个独立工作流，不是手机端
  重写的免费副产品；文本输入（搜索框）在遥控器下有已知坑（门 G5）。

### 4.5 构建与验证链

- 任何含 Flutter 的方案都要求 CI 增加 Flutter 工具链（module 的 Gradle hook
  或 `flutter build aar`），并保留现有签名/发布流程；本机无 Flutter SDK，
  Dart 代码在本机无法编译验证（门 G6）。
- 验证仍依赖 GitHub Actions 构建 + Wei 手机真机测试；播放器门要求 Exo 与
  MPV **分别**验证，一方的成功不证明另一方。

## 5. 方案比较

### 方案 A：不做——保持原生，继续 BieCuiLe 风格仿写

- 收益：零风险；保留三内核、mpv 双渲染路径、全部运行时、上游同步路径、
  Leanback TV 原生体验；B1–B6 已证明仿写可达 BieCuiLe 观感（手机端）。
- 缺点：UI 迭代仍是 Views/XML，改动成本高于 Flutter 热重载；与 BieCuiLe
  的像素级一致存在天花板（精确数值本就无法从反编译恢复，G1 同样约束 A）。
- 对现有功能/兼容/性能/体积：无影响。
- 最佳实践状态：原生 Android 开发是平台一等公民；无额外依赖。
- 实施与验证：无；继续现有节奏（CI 构建 + Wei 真机）。
- 回滚：不适用（无变更）。

### 方案 B：全量 Flutter 重写（新 Dart 应用，原生仅作插件/FFI 支撑）

- 收益：单一 Dart UI 代码库；与 BieCuiLe 同构；热重载迭代快；理论上可跨端。
- 缺点与风险（按严重度）：
  1. **规模**：约 44.3 万行生产代码、427 个布局、59 个 Activity、94k 行播放器
     需重写或重桥接；回归面覆盖播放、蜘蛛、下载、直播、短剧、实验室、
     小程序、服务器、DLNA/Cast、弹幕、字幕、设置（1755 行）等全部子系统。
  2. **播放能力降级**：media_kit 只有 libmpv 单内核，Exo/ijk 丢失；
     文档路径仅确立 `mediacodec_embed`，`gpu-next`/Anime4K/DV/Vulkan 未确立
     （G2）；诊断与自动上下文体系需重建。
  3. **运行时桥接无法绕开**：Chaquopy/QuickJS/Node/Rhino/JNI/sherpa-onnx/
     AAR/NanoHTTPD/Room 等无 Dart 等价物（§3.4 推断），重写仍需大量原生
     桥接——B 在实践中会退化为“换壳的混合”，却付出了全量重写的代价。
  4. **TV 端**：焦点层 DIY + 文本输入坑 + 独立工作流（G5）。
  5. **上游同步断裂**：上游 webhtv 是原生 Java/Kotlin；全量重写后无法再
     以现有方式同步上游提交（当前已有 4 个上游提交待同步）。（分析）
  6. **原生库共存与体积**：G3 未验证。
  7. **验证链**：本机无 Flutter SDK，一切验证依赖 CI + Wei 单设备真机，
     周期长、覆盖窄（G6）。
- 最佳实践状态：Flutter 官方文档把 add-to-app 定位为“已有应用、从零开始
  不现实”时的集成方式；全量重写不是官方推荐的已有大型应用迁移路径。（分析，
  基于 E1 的文档定位。）
- 最小分阶段实施：B 没有“小”的切法——即使分屏迁移，Dart 应用壳、导航、
  状态管理、桥接层也必须先行，且中途无法发布“半个应用”而不保留原生壳，
  那就等于方案 C。因此 B 的最小实施即先走 C0/C1 再决定是否继续深入；
  直接立项 B 是不推荐的。（分析）
- 回滚：重写分支可整体丢弃（`main` 不受影响）；但一旦发布，Dart 侧数据
  （Room/设置/历史）迁移若已发生，回滚需数据兼容预案，须在实施计划中
  明确，此处未展开。
- 粗略工期（推断，低置信度，量级估计）：6–12 个月量级，单人节奏、
  无本地构建、单设备验证。依据：§3 规模 + §4 门的数量；不是承诺。

### 方案 C：混合——Flutter UI + 原生核心（add-to-app，推荐路线）

- 收益：渐进迁移、逐屏验证；保留三内核、双渲染路径、全部运行时与上游
  同步路径；TV 可暂留原生；可先拿到 Flutter 的 UI 迭代收益而风险可控；
  官方支持的迁移形态（混合导航栈 / 局部视图，E1）。
- 缺点与风险：
  1. 双栈长期并存：Dart + Java/Kotlin 双构建、双调试、双依赖治理；
     团队（实为单人 + agent）心智负担上升。
  2. 引擎开销：冷启动与内存增加，量级须实测（§4.3）；multi-engine 状态隔离
     要求导航与状态设计显式处理。
  3. Pigeon 契约设计成本：`PlayerEngine` 级宽接口须重设计为窄契约（§4.2）；
     契约一旦铺开，改动需双侧同步。
  4. 画面嵌入方式未定（G4）：播放器 UI 层 Flutter 化时，原生 Surface 的
     嵌入与 HDR/隧道/DRM 表面行为须实测。
  5. TV 侧若纳入，代价同 B 的第 4 项（G5）；建议 TV 排除在首期之外（决策点 2）。
- 对现有功能/兼容/性能/体积：首期（C0–C2）零功能变化；性能与体积影响
  以 C0 实测为准；兼容性（minSdk 24、targetSdk 28、AndroidX）须在 C0
  验证 Flutter module 的 Gradle/AGP 兼容性（本仓库 AGP 9.2.1、Gradle 9.5.1、
  JDK 21，较新，兼容性为门 G6 的一部分）。
- 最佳实践状态：官方支持的迁移路径；Pigeon 平台通道模式稳定；
  原生互操作预览版不可用（E2）。
- 最小分阶段实施与验证（每阶段独立可发布、可回滚）：
  - **C0 壳工程验证（spike）**：新建 Flutter module（`flutter create -t module`
    形态或等价），Gradle hook 或预构建 AAR 二选一；一个 `FlutterFragment`
    承载的空白/占位屏 + 一条 Pigeon 往返调用；CI 增加 Flutter 构建步骤；
    验收：4 风味 APK 均可构建、安装、启动；包体积与冷启动增量实测并记录；
    原生屏零回归（Exo/MPV 各自冒烟）；回滚：删除 module 与 CI 步骤，零残留。
  - **C1 低风险屏**：迁移 1–2 个无播放、无复杂状态的屏（如关于/部分设置页）；
    验收：与原生屏逐项对照 + Wei 真机验收；回滚：功能开关切回原生屏。
  - **C2 内容屏**：首页/详情等读模型为主的屏；桥接 Room/设置/历史读接口；
    验收同上，另加列表滚动性能与图片加载对照。
  - **C3 播放器控制层（仅 UI）**：Flutter 控制层 + 原生 Surface/引擎；
    须先过 G4；验收：Exo 与 MPV 分别全量回归（含 B6 画面调节/Anime4K、
    字幕、倍速、投屏、弹幕、诊断），任一内核失败即该阶段不通过。
  - **C4 TV 侧（可选、最后）**：仅在手机端验证通过且 Wei 明确要求时启动；
    须先过 G5。
- 回滚路径：每阶段保留原生实现，功能开关控制；任一阶段失败即停在该阶段，
  已迁移屏可逐个切回；整个分支可丢弃，`main` 不受影响。
- 粗略工期（推断，低置信度）：C0 约 1–2 周；C1–C2 约 4–8 周；C3 约 4–6 周
  （不含内核替换）；C4（如做）约 4–8 周。依据：§3 规模、门的数量、
  无本地构建（每次迭代须走 CI，单次构建约 23 分钟）与单设备验证瓶颈。
  不是承诺；C0 实测后应重新估计。

### 比较总表

| 维度 | A 不做 | B 全量重写 | C 混合（推荐） |
| --- | --- | --- | --- |
| 播放能力 | 完整保留 | 降级风险高（单内核、G2） | 完整保留 |
| 运行时/蜘蛛/实验室/小程序 | 完整保留 | 全部重桥接/重实现 | 完整保留 |
| TV 端 | 原生 Leanback | DIY 焦点层 + 独立工作流 | 可暂留原生 |
| 上游同步 | 完好 | 断裂 | 核心完好 |
| 风险 | 无 | 最高 | 中（分阶段可控） |
| 工期量级 | 0 | 6–12 个月（推断） | C0 1–2 周起，分阶段（推断） |
| 包体积/启动 | 无变化 | 引擎小，原生库重复风险（G3） | 同 B，C0 实测 |
| 官方最佳实践 | — | 非推荐迁移路径 | 官方支持的迁移路径 |
| 回滚 | — | 分支可弃；已发布数据迁移需预案 | 每阶段可切回 |

## 6. 推荐与验收标准

**推荐：方案 C，先做 C0 壳工程验证；C0 通过后再由 Wei 决定是否进入 C1。**
若 C0 失败（构建/体积/启动/桥接任一关键指标不达标），退回方案 A，
不做进一步投入。方案 B 不作为立项目标；只有当 C 走到深处且全部关键门
（G2/G3/G4/G5）均已实测通过时，才可重新讨论 B 的残值——那时 B 的增量
收益已很小。

### C0 验收标准（全部满足才算通过）

1. 4 个风味 APK 均可经 CI 构建、签名、安装、启动；`main` 分支零改动。
2. 包体积与冷启动增量实测并记录（与同基线原生构建对照）；增量是否可接受
   由 Wei 判定，本文不预设阈值。
3. 一条 Pigeon 往返调用在真机上工作；FlutterFragment 生命周期
   （旋转、后台/前台、内存回收）无崩溃。
4. Exo 与 MPV 各自冒烟通过（各播一片，含字幕与倍速）；任一失败即不通过。
5. 回滚验证：移除 module 与 CI 步骤后，构建恢复到基线状态。

### 后续阶段通用 go/no-go（沿用播放器门）

无未解决的正确性/崩溃/生命周期/ABI/安全/重大性能回归；回滚可恢复；
Exo 与 MPV 分别验证；原生供应链（新 `.so`/AAR）须有来源与锁定记录。

## 7. 回滚路径（总）

1. 分支级：`b8-flutter-rewrite` 始终不合入 `main`，直至 Wei 批准；
   任何时候可丢弃分支，零影响。
2. 阶段级：每阶段保留原生实现，功能开关切回；已迁移屏逐个可逆。
3. 发布级：若含 Flutter 的构建已发布，回退到上一原生 Release 即可；
   数据层（Room/设置/历史）如无 schema 变更则无需数据回滚——C0–C3
   不做数据层变更是硬性前提；如未来必须变更，须另立数据迁移与回滚预案。

## 8. 未解决的门（实施前必须逐个关闭或由 Wei 接受风险）

| 门 | 内容 | 关闭方式 |
| --- | --- | --- |
| G1 | BieCuiLe 参照 APK/分析/截图不在本机；精确视觉数值无法恢复 | 重新获取参照材料；或 Wei 接受“风格接近即可” |
| G2 | media_kit 在 Android 上能否配置 `vo=gpu-next` + libplacebo，Anime4K/DV/Vulkan 能否等价 | 读 media_kit 源码/选项文档 + 真机实测；仅影响 B，不阻塞 C0–C2 |
| G3 | media_kit 自带 libmpv/FFmpeg 与仓库重命名库 + nextlib FFmpeg 的共存与体积 | 检查 `media_kit_libs_video` 产物 + 构建实测；仅影响 B，不阻塞 C0 |
| G4 | Flutter 内嵌入原生播放 Surface 的方式与 HDR/隧道/DRM 行为 | C3 前做 spike + 真机实测 |
| G5 | TV 焦点/文本输入/上架要求在 Flutter 下的等价性 | C4 前做 TV spike + 真机（电视/盒子）实测 |
| G6 | 本机无 Flutter SDK；CI 工具链、AGP 9.2.1/Gradle 9.5.1/JDK 21 兼容性、构建时长 | C0 实测 |
| G7 | Pigeon 契约范围（`PlayerEngine` 等价面）与高频事件流设计 | C1/C3 设计时定稿，Wei 评审 |
| G8 | Wei 的决策（§10） | 本文档评审会 |

## 9. 证据表

访问日均为 2026-11-15（CST）。“已读”指实际打开并阅读了原文；
“摘要”指仅见搜索结果摘要，未打开原文。

| ID | 等级 | 来源 | 支持的主张 | 适用性与注意事项 | 决策影响 |
| --- | --- | --- | --- | --- | --- |
| E1 | A，已读 | 官方文档 `https://docs.flutter.dev/add-to-app`（Flutter 3.50，页面更新 2026-07-31） | add-to-app Android 支持、multi-engine、Gradle hook/AAR、`FlutterEngine`、Java/Kotlin 宿主、AndroidX-only、混合导航栈用例 | 直接适用；移动端无 multi-view | 方案 C 可行性的核心依据 |
| E2 | A，已读 | 官方文档 `https://docs.flutter.dev/interop/pigeon`（页面更新 2026-11-07） | Pigeon 类型安全 codegen；平台通道稳定；原生互操作预览、不可用于生产；不支持复杂继承、须扁平化；多引擎绑定注意事项 | 直接适用；`PlayerEngine` 须重设计契约 | 缝合线选型；排除预览版原生互操作 |
| E3 | A，已读 | 官方 FAQ `https://docs.flutter.dev/resources/faq`“How big is the Flutter engine?” | 引擎基线仅几 MB（压缩后）；体积主要由资产/字体/第三方包决定；Dart AOT；Android 用 Impeller/Vulkan | 直接适用；未给本项目可用的具体数字 | 体积结论：引擎不是主要矛盾，G3 才是 |
| E4 | B，已读 | media_kit README，`https://raw.githubusercontent.com/media-kit/media-kit/main/README.md` | 纯 Dart + `dart:ffi` 调 libmpv；分包与 README 标注版本；Android 路径 = SurfaceTexture + `--wid` + `--hwdec=mediacodec` + `--vo=mediacodec_embed` + JNI 辅助库；功能清单 | 项目方一手文档；版本以访问日 README 为准；未确立 gpu-next 路径 | B 方案播放降级风险的核心依据；G2/G3 来源 |
| E5 | C，已读 | `dpad` 包文档，`https://pub.dev/documentation/dpad/latest/`（`dpad ^3.3.0`） | Flutter 内置焦点按几何最近选择，TV 布局中会跳 lane/逃出轮播/丢记忆；region/beam 模型；TV 上架清单 | 第三方包文档，有自利倾向；但焦点机制描述与多份实地报道一致 | TV 代价（G5）的依据 |
| E6 | A，已读 | 仓库源码：`app/src/main/java/com/github/moyingshi/osc/tv/player/engine/PlayerEngine.java`（全文）、`MpvPlayerEngine.java`、`PlayerManager.java` 相关行、`PlaybackMediaFactsMapper.java:227-228`、`MpvPlayer.java` `vo` 配置 | `PlayerEngine` 约 50 方法、Media3 耦合；mpv 双路径（gpu-next/mediacodec_embed）+ DV/Vulkan/Anime4K shader 槽位 | 直接适用；基线 HEAD `b57287bd` | 与 E4 对比的决定性差异 |
| E7 | A，已读/计数 | 仓库盘点：`git ls-files` 计数、`app/build.gradle`、`gradle/libs.versions.toml`、`.github/workflows/android-release.yml`、`README.md`、manifests | §3 全部规模/构建/原生基线数字 | 直接适用；计数方法：Python 逐文件统计，`xargs wc` 的错误中间值已废弃 | 规模与工期估计的依据 |
| E8 | A，已读 | 仓库文档：`docs/b-ui-b1-b7-port.md`（B7 结论）、`docs/ui-restyle-biecuile.md` §0–§1.2 | B7 不做（media_kit 零播放收益）；BieCuiLe 全 Flutter UI、资产不得复用、精确数值不可恢复 | 直接适用 | B7 结论不再重议；G1 来源 |
| E9 | D，摘要 | 两次 TV 搜索与 add-to-app 第三方报道（procedure.tech、LeanCode 案例等，仅搜索摘要） | 未找到官方 TV 指南；第三方称通用 APK 增加数十 MB、冷启动增加 | 未打开原文，不作决策依据；否定性搜索结论仅称“未找到” | 仅提示 C0/G5 须实测 |
| E10 | B，转述 | 父代理 2026-11-15 20:29 核实：构建 run 43711533935 成功（22m55s），产物 353 MB（4 APK） | 构建时长与产物量级 | 二手核实；单 APK 体积未核实 | 验证链节奏估计的依据 |

## 10. 需要 Wei 拍板的决策点

1. **路线**：A（不做）/ B（全量重写）/ C（混合，先 C0 验证）？——推荐 C0。
2. **TV 范围**：TV 端是否纳入 B8？还是手机端先行、TV 永留原生？
3. **双栈接受度**：是否接受 Dart + Java/Kotlin 长期并存的构建与调试复杂度？
4. **内核策略**：保留三内核（C 的前提），还是接受单 media_kit 内核并放弃
   Exo/ijk + gpu-next 能力（B 的隐含代价）？
5. **上游同步**：B8 期间是否接受上游同步暂停（B）或仅限原生核心（C）？
6. **C0 验收**：包体积/冷启动增量的可接受阈值，以及 Wei 真机验收的屏与用例。

## 11. Recovery anchor（恢复锚点）

- 目标：产出一份决策就绪的 B8 评估文档；不写重写代码、不推送、不动 `main`。
- 状态：评估完成；文档已写，待 Wei 决策（§10）。无产品代码改动。
- 分支/HEAD：`b8-flutter-rewrite`，基线 `b57287bd9aadafe2df6b11e8583d5d930e4639fd`；
  本文档为该分支上唯一新增文件。
- 变更文件：仅 `docs/b8-flutter-rewrite-assessment.md`（新建）。
- 验证：`git diff --check` 通过；diff 仅含本文档；`main` 仍指向 `b57287bd`；
  无远程分支创建；未触发构建（无构建可触发——纯文档）。
- 风险：§8 的门未关闭；工期估计为低置信度推断；外部证据访问日 2026-11-15，
  Flutter/media_kit 版本演进可能改变细节，实施前须复核。
- 唯一下一步：Wei 阅读本文档并对 §10 决策点表态；在明确批准前不开始任何实施。
