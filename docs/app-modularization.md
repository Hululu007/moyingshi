# App 模块化设计评审（app-modularization.md）

> 状态：**评审中（assessment only）**，2026-11-25。只做设计评审，未写任何业务代码、未改构建文件、未提交。
> 审批前置：按 `AGENTS.md §7/§8` 与 `.codex/skills/upstream-integration-governor/SKILL.md`，本任务是新增的模块分发与原生依赖加载机制（material new requirement），必须先有决策就绪的评审并经用户批准，才能动工。
> 数字口径：全部体积数字以 `~/workspace/apk-size-analysis/REPORT.md`（2026-11-25 实测，mobile-arm64 v5.6.0-beta-202611251756，213MB）为准，未重测 APK。单位 MB = MiB（1024²），与 release 页一致。

## Recovery anchor（恢复锚点）

- **目标**：把 App 里的大件拆成可选模块，基础包瘦下来，功能不砍、需要时再下载/安装；用户首次用某功能时下载对应模块，失败则回落到基础功能。
- **当前状态**：评审完成，用户已于 2026-11-25 19:26 批准并补充设计（见“用户决定栏”与“决定补充记录”）：一步到位全量做、模块两种获取方式（在线下载直连本仓库 release + 本地导入已下载的压缩包）、设置里单独一项“模块”。实施中：Stage 0 已通过（见“Stage 0 核查结论”），单元 ① 模块管理器核心、单元 ② mpv/VLC 接线、单元 ③ Sherpa/DV/ijk/LUT 接线、单元 ④ 设置“模块”页 + 提示、单元 ⑤ Gradle 打包已做完（commit 见“实施记录”与 finish 回执）。5 个单元的代码全部在 main 上（HEAD `e9a17079`，已推远端 main；单元⑤ `72a22a33` + `.gitattributes` 修复 `e9a17079`），未触发构建。
- **验收标准**：见“验收标准”一节（手机真机可测条目）。
- **当前文件/符号**：本文档；实施代码在 `app/src/main/java/com/github/moyingshi/osc/tv/module/`（ModuleDefinition/ModuleManifest/ModuleVerifier/ModuleInstaller/ModuleDownloader/ModuleManager/ModuleStatus/ModuleProgress/ModulePrompt/ModuleActivity）与 `app/src/test/java/com/github/moyingshi/osc/tv/module/`（5 个 JVM 单元测试类）；打包在 `app/build.gradle`（`packaging.jniLibs.excludes` + `packageModules*` 任务 + `moduleAar` 配置）；模块文件源在 `app/src/<abi>/mpv-libs/`、`app/src/main/jniLibs/`、`app/src/main/lut-presets/` 与两个 AAR；其余全部是只读调研，见“现状映射”。
- **未验证项**：Stage 0 已解决第 1、2、6、8 条（见“Stage 0 核查结论”）；剩余第 3、4、5、7 条见“未验证 / 需实施时验证”清单（已更新状态）。新增：Gradle 打包的编译正确性（`packaging` 排除 + 打包任务 + assemble 接线）与基础包实测体积，只能由 GitHub 构建验证。
- **唯一下一步**：workflow diff 报给父代理（浏览器改）→ 触发构建 → 核对 release（4 个 APK + 14 个模块 zip）→ 发版 + 版本记录。

## 决定变更记录（VLC 拆不拆）

- **2026-11-25 17:39**：用户批 VLC 内核方案时定“随包走”（8 问第 8 题原话“随包走”，见 `docs/vlc-player-kernel.md` 用户决定栏）。
- **2026-11-25 18:34**：用户在体积分析后表态“模块化，模块化”：不砍功能，把大件拆成可选模块。
- **2026-11-25 18:35**：用户改口“VLC 也拆”。**17:39 的“VLC 随包走”决定作废**。本评审把 VLC 列为正式的模块候选，与 mpv 同等对待，不再写成“不动/未来可选项”。

## 要实现的实际能力

1. **基础包变小**：mobile-arm64 下载体积从 213MB 降到约 79MB（全部 5 个大模块拆完；含 LUT 懒加载约 75MB）。分阶段目标见“实施阶段划分”。
2. **功能一个不少**：mpv / VLC / 实时字幕（Sherpa）/ 杜比视界 / ijk 五个件变可选模块；不用某功能的用户永远不用下载它，用的人首次使用时下载一次，之后常驻。
3. **用户侧体验**：首次选用某模块功能时弹下载提示（大小、预计流量、取消），下载中有进度、失败可重试；无网/弱网时明确提示、功能回落（内核切回 EXO、字幕功能置灰），不闪退、不静默失败。
4. **模块可管理**：设置里能看到已装模块、占空间、删模块（删了下次用时重新下载）。
5. **不走 Play**：分发沿用现有 GitHub release 直发 APK 侧载；模块是 release 附件里的 per-ABI zip，不用 Play Feature Delivery / App Bundle（见“方案对比”档 E，侧载场景下不可用）。

## 现状映射（读代码，精确到文件/类/方法）

### 大件归属与加载链路

| 大件 | 体积（arm64 下载） | 打包来源 | 加载链路（现状） | 关键文件 |
|---|---|---|---|---|
| mpv 内核 | 44.5MB（assets/mpv-libs） | 源码自编译，`app/src/arm64_v8a/assets/mpv-libs/` 与 `armeabi_v7a/...`（按 ABI 分目录）；JNI 桥 `libplayer.so` 由 `app/src/main/cpp/CMakeLists.txt` 编出，也在 bundle 内 | `MPVLib.ensureLoaded(context)`（`app/src/main/java/is/xyz/mpv/MPVLib.java:59`）：选 ABI → assets 拷到应用私有目录 `app_mpv-libs/<abi>/` → 按 `LOAD_ORDER`（c++_shared、mvutil、mwresample、mwscale、mvcodec、mvformat、mvfilter、mvdevice、mpv、player）逐个 `System.load(绝对路径)`；`.bundle-last-update` marker 记 bundle 版本（`VERSION:abi` 思路同 NodeLib）；加载失败记 `loadError`，`NativeLibraryDiagnostics.failure` 上报；`MpvPlayer.java:1821` 加载失败时抛 `IOException`，走现有播放错误链 | `is/xyz/mpv/MPVLib.java`、`androidx/media3/mpvplayer/MpvPlayer.java`、`player/engine/MpvPlayerEngine.java` |
| VLC 内核 | 46.0MB（libvlc.so 45.9 + libvlcjni.so 0.1） | Maven Central `org.videolan.android:libvlc-all:3.7.8`（`gradle/libs.versions.toml:49,125`、`app/build.gradle:180`）；标准 AAR，`.so` 走 jniLibs 打包 | 应用层**从不显式加载**：`VlcPlayer` 构造时 `new LibVLC(context, ...)`（`androidx/media3/vlcplayer/VlcPlayer.java:118`），原生库由 `org.videolan.libvlc` 绑定在静态初始化里 `System.loadLibrary`。这是 VLC 与 mpv 的最大区别：mpv 的加载在应用手里，VLC 的加载在第三方绑定手里 | `androidx/media3/vlcplayer/VlcPlayer.java`、`player/engine/VlcPlayerEngine.java`、`docs/vlc-player-kernel.md` |
| Sherpa-ONNX（实时字幕+AI 翻译） | 23.6MB（libsherpa-onnx-jni.so） | **⚠️ 来源未定位**：`app/libs/sherpa-onnx-v1.13.4.jar` 只有 323KB 的 Java 绑定（241 个 class，无 `.so`）；`gradle/libs.versions.toml` 与 `app/build.gradle` 里没有任何 sherpa 声明；`third_party/maven` 里也没有。APK 内确有该 `.so`。实施前必须先定位（见 Stage 0） | Java API `com.k2fsa.sherpa.onnx.*`（`subtitle/RealtimeSubtitleRecognizer.java`、`subtitle/RealtimeSubtitleTranslator.java`）；模型文件**已是运行时下载**：`subtitle/RealtimeSubtitleModelCatalog.java` 按语言列模型（GitHub releases `csukuangfj/sherpa-onnx-*`，每个文件带 url + 大小 + sha256），`subtitle/RealtimeSubtitleModelVerifier.java` 校验；控制器 `subtitle/RealtimeSubtitleController.java` | `subtitle/RealtimeSubtitle*.java`、`app/libs/sherpa-onnx-v1.13.4.jar` |
| Exo 杜比视界渲染器 | 10.0MB（libexo_dovi_renderer.so 9.6 + libdovi_android.so 0.45） | 预编译，`third_party/exo-ass-native/prebuilt`，`app/build.gradle:94` `sourceSets.main.jniLibs.srcDir(...)` | `player/exo/ExoDv5Native.java`：静态块 `loadLibrary("exo_dovi_renderer")`（`:66-71`），`probe()`（`:33`）按能力位（ImageReader/Vulkan/AHB/YCbCr/foreign-queue/libplacebo/libdovi）判定可用，不可用返回 `Probe(false)`。**已有“可用性探针”设计**，模块缺失可自然映射为 probe()=false | `player/exo/ExoDv5Native.java`（另有 `ExoDv5GpuMappingPolicy`） |
| ijk 内核 | 10.3MB（libijkffmpeg.so 9.3 + libijkplayer.so 0.6 + libijksdl.so 0.4） | ijkplayer（AAR/源码，二进制分发） | `tv/danmaku/ijk/media/player/IjkMediaPlayer.java:128` 静态 `IjkLibLoader`（ijkffmpeg、ijksdl、ijkplayer），`loadLibrary()` → `System.loadLibrary`；`player/engine/IjkPlayerEngine.java` + `IjkSimplePlayer.java` | `tv/danmaku/ijk/media/player/IjkMediaPlayer.java`、`IjkLibLoader.java`、`player/engine/IjkPlayerEngine.java` |
| Chaquopy Python（.py 规则引擎） | 约 20.9MB（lib 8.9：libpython3.10 3.5 + libcrypto_python 3.7 + ssl/sqlite；assets stdlib+pip 12.0） | 独立 Gradle 模块 `:chaquo`（`chaquo/build.gradle`：`com.chaquo.python` 插件，python 3.10，`pip install -r requirements.txt`：lxml、ujson、pyquery、requests、certifi、cachetools、pycryptodome、beautifulsoup4）；`app/build.gradle:166` 依赖 | `api/loader/PyLoader.java`（经 `BaseLoader.java` 按 `file://` api 分发）；`utils/LocalSource.java` 也调 Python。Chaquopy 插件把解释器打进应用构建，**不支持运行时下载解释器** | `chaquo/build.gradle`、`chaquo/requirements.txt`、`api/loader/PyLoader.java` |
| Exo FFmpeg 扩展 | 8.2MB（libavcodec 6.1 + swscale 1.2 + avutil 0.7 + swresample 0.1） | `io.github.anilbeesetti:nextlib-media3ext`（`gradle/libs.versions.toml:58,148`，本地 maven `third_party/maven`），Media3 FFmpeg 扩展标准路径 | 由 nextlib AAR 内部加载（Media3 扩展）；应用层在 `player/engine/ExoPlayerEngine.java`、`player/exo/ExoAudioCodecSelector.java` 使用其能力。**EXO 是默认内核，不可拆** | `player/engine/ExoPlayerEngine.java` |
| Thunder 磁力提取 | 6.4MB（libxl_thunder_sdk.so 5.6 + libxl_stat.so 0.8） | 本地 `app/libs/thunder-release.aar`（`fileTree` 引入，`app/build.gradle:164`） | Java API `com.xunlei.downloadlib.XLTaskHelper`（`player/extractor/Thunder.java:15`）；`.so` 由 AAR 内部加载 | `player/extractor/Thunder.java` |
| 剪片提取器 | 5.3MB（libjpa.so） | 本地 `app/libs/jianpian-release.aar` | `player/extractor/JianPian.java`；`.so` 由 AAR 内部加载 | `player/extractor/JianPian.java` |
| 卡拉 OK 音高检测 | 3.5MB（libtensorflowlite_jni.so） | `libs.tensorflow.lite`（`app/build.gradle:212`） | `player/karaoke/BasicPitchTfliteGenerator.java`；TFLite Java API 内部加载 | `player/karaoke/BasicPitchTfliteGenerator.java` |
| LUT 预设（B6） | 3.7MB 下载 / 11MB 安装（13 个 `.cube` 文本） | `app/src/main/assets/lut_presets/`（`乌托邦.cube`、`低对比.cube` 等） | 纯 assets，无原生代码；`player/lut/` 解析加载 | `app/src/main/assets/lut_presets/` |
| ASS 原生字幕渲染 | 2.8MB（libexo_ass.so，仅 arm64 有） | 同 DV，`third_party/exo-ass-native/prebuilt` | `player/exo/ass/AssNative.java:14`：`ensureLoaded()` 懒加载（`System.loadLibrary("exo_ass")`），只在有符合条件的 ASS 字幕到达时调用。**已有懒加载先例** | `player/exo/ass/AssNative.java` |
| Node.js 规则引擎 | 约 0.3MB（只有 JNI 桥在包内） | **`libnode.so` 已是运行时下载，不在包内**——见下节 | 见下节“现有先例” | `api/node/NodeLib.java` 等 |

### 现有的“可选/开关”与“首次使用再下载”先例

| 先例 | 位置 | 对模块化的启示 |
|---|---|---|
| **Node.js 运行时下载（最直接的先例）** | `api/node/NodeLib.java:75` `ensure(context, progress)`：从 `https://github.com/nodejs-mobile/nodejs-mobile/releases/download/<VERSION>/nodejs-mobile-<VERSION>-android.zip` 下载 → `NodeZip.extract` 解出 `libnode.so` → 落盘应用私有目录 → marker 文件记 `VERSION:abi`；失败时 range-fetch 回退 full-zip、代理失败回退直连；`NodeService.java:122`、`OriginNodeService.java:132` 以 Service 消息推下载进度。**注意：无 sha256 校验**，只有版本 marker——模块化设计必须补上完整性校验，不能照抄这一点 | 下载通道、版本 marker、失败回退、进度推送四件套都已跑通；模块下载照这个结构做，只需把“zip 里一个 .so”换成“zip 里一组 .so + manifest”，并加 sha256 |
| **Sherpa 模型下载（带 sha256 的先例）** | `subtitle/RealtimeSubtitleModelCatalog.java`：每个模型文件带 `url + size + sha256`；`RealtimeSubtitleModelVerifier.java` 校验 | 模块清单（manifest）+ sha256 校验的现成模板 |
| **mpv bundle 加载（本地 bundle 的先例）** | `MPVLib.ensureLoaded`：assets → 私有目录 → `System.load(绝对路径)`，按依赖顺序加载，marker 记版本，失败记 `loadError` 且 `MpvPlayer` 抛 `IOException` 走播放错误链 | 模块版只需把“assets”换成“已下载的 zip”，加载侧一行逻辑都不用重写思路；失败回落链路已存在 |
| **ASS 懒加载 + DV 探针** | `AssNative.ensureLoaded()`（懒加载）；`ExoDv5Native.probe()`（能力探针，不可用即 false） | “缺失 → 不可用 → 回落”的两种现成模式：懒加载适用于按需件，探针适用于渲染器类 |
| **内核可用性门（半成品）** | `setting/PlayerSetting.java:197-203`：`isPlayerAvailable(player)` 目前只对 IJK 有分支，且 `isIjkAvailable()` 恒返回 `true`；`sanitizePlayer()` 非法/不可用内核回落 EXO（`:189-191`） | 门已经留好了：模块化后把 `isPlayerAvailable` 改成查模块安装状态（MPV/VLC/IJK 各自），`sanitizePlayer` 的回落逻辑原样复用 |
| **功能开关模式** | 增强设置里的 `lab_nav`（默认开）、喜好开关（默认开）、各功能开关（默认关居多）；`Prefers` 存取 | 模块的“装/卸”状态与“首次使用提示”可走同一套开关行样式；模块管理页可复用设置页行组件 |
| **“缺失组件提示”先例** | 无通用先例。各功能缺失时是各自处理（mpv 加载失败抛错、Node 下载失败返回错误串）。**没有统一的“组件未安装”UI** | 模块化要新做一个统一的下载提示/进度/失败 UI（Stage 1 的一部分），不能指望现有分散处理 |

### Gradle 结构

- `settings.gradle`：`include ':app', ':catvod', ':chaquo', ':nodejs', ':quickjs'`。**Gradle 分 module 已有先例**，但只在蜘蛛引擎一侧：`:catvod`（`com.github.catvod.crawler`，爬虫核心）、`:chaquo`（Python 引擎）、`:nodejs`（Node JNI 桥，`cpp/`+`java/`）、`:quickjs`（JS 引擎 + jsoup）。`:app` 仍是单体，装着全部 UI、播放器（9914 行的 `PlayerManager`）、字幕、下载、实验室。
- `app/build.gradle`：flavors `mode(leanback/mobile) × abi(arm64-v8a/armeabi-v7a)`，`minSdk 24 / targetSdk 28 / compileSdk 37`，`versionCode 560 / versionName "5.6.0"`；`minifyEnabled`、`shrinkResources` 已开（release）；`sourceSets.main.jniLibs.srcDir(third_party/exo-ass-native/prebuilt)`；`implementation fileTree(dir: "libs", include: ["*.aar", "*.jar"])`（Thunder/JianPian/ForceTV/sherpa jar 由此进包）。
- 结论：**Gradle 层面再拆 module 不难，但它本身不减下载体积**（所有 module 仍编译进同一个 APK）。真正的瘦身只能来自“构建时把 .so 移出 APK + 运行时按需下载”。两者不要混为一谈。

## 模块划分建议

### 模块总表（arm64 下载体积口径）

| 模块 | 内容 | 省多少 | 留在基础包里的最小集 | 依赖关系 | 备注 |
|---|---|---|---|---|---|
| **mod-mpv** | mpv bundle 全部 10 个 `.so`（44.5MB） | 44.5MB | `MPVLib`、`MpvPlayer`、`MpvPlayerEngine` 的 Java 代码（小）；`MpvPlayer` 的加载失败路径原样保留 | 无（自带 FFmpeg 与 libc++_shared） | 拆法最顺：加载器已抽象，换数据源即可 |
| **mod-vlc** | `libvlc.so` 45.9 + `libvlcjni.so` 0.1 + AAR 自带的 `libc++_shared.so` 9.3 = **约 55.3MB（zip 未压缩）**；APK 口径省 47.4MB（另含基础包的 `libc++_shared.so` 1.37MB，见下） | 47.4MB | `org.videolan.libvlc` Java 绑定（AAR 的 classes.jar，约 1–2MB，待实测）+ `VlcPlayer`/`VlcPlayerEngine` 的 Java 代码 | 无（静态链接 FFmpeg 自带；libc++_shared 用 AAR 里那份，不用基础包的） | 拆法与 mpv 不同：应用层从不显式加载，须在首次 `new LibVLC()` 前 `System.load` 预载三个 `.so`（先 AAR 的 libc++_shared、后 libvlc、最后 libvlcjni），绑定的 `loadLibrary` 随后发现已加载即跳过。**纠正**：评审初版写的 46.0MB 是 APK 内 VLC 的 footprint（复用了基础包 1.37MB 的 libc++_shared）；zip 必须自带 VLC 自己的 9.3MB 那份（与 libvlc.so 同一套 NDK STL 构建才配对），故 zip 约 55MB。 |
| **mod-sherpa** | `libsherpa-onnx-jni.so` 23.6MB（实测：arm64 23,603,240 字节，armeabi 16,110,348 字节） | 23.6MB | `com.k2fsa.sherpa.onnx` Java 绑定（`app/libs/sherpa-onnx-v1.13.4.jar`，实测 175,635 字节≈172KB；评审写的 323KB 是错的，以实测为准）+ `subtitle/RealtimeSubtitle*` 的 Java 代码；模型文件本来就在线下载，不动 | 无（ONNX Runtime 自带） | 试点首选（见下）。前置条件：先定位 `.so` 的打包来源（Stage 0） |
| **mod-dovi** | `libexo_dovi_renderer.so` 9.6 + `libdovi_android.so` 0.45 = 10.0MB | 10.0MB | `ExoDv5Native` 的 Java 代码 + `probe()`；Exo 本体不动 | 依赖 Exo（默认内核，随包） | 探针设计已就绪；缺失即 probe()=false |
| **mod-ijk** | `libijkffmpeg.so` 9.3 + `libijkplayer.so` 0.6 + `libijksdl.so` 0.4 = 10.3MB | 10.3MB | `IjkPlayerEngine`、`IjkSimplePlayer`、ijk 的 Java 壳 | 无 | `isPlayerAvailable` 的门改成真检查即可；回落 EXO 已有 |
| **mod-lut**（资产包，非原生模块） | 13 个 `.cube` 3.7MB 下载（zip 压缩后；实测：12 个文件未压缩 9,862,336 字节，`.cube` 是文本、压缩比约 3:1；“磁盘 11MB”是 13 个文件合计 10,832,768 字节，含留包内的乌托邦.cube） | 3.7MB | `player/lut/` 解析代码；首屏默认 LUT 可留 1 个在包内，其余懒加载 | 依赖 B6 画面调节（mpv 下可用） | 无原生加载，风险最低；但省得少，只能当添头 |
| **不拆** | Exo FFmpeg 8.2MB、Python 20.9MB、Thunder 6.4MB、JianPian 5.3MB、TFLite 3.5MB、ASS 2.8MB | — | — | — | 见下节 |

### 不拆的件（说明原因）

- **Exo FFmpeg（8.2MB）**：EXO 是默认内核、基础播放的底线；Media3 扩展与 Exo 渲染器紧耦合，拆了等于基础包不能播。留。
- **Chaquopy Python（20.9MB）**：Chaquopy 插件不支持运行时下载解释器；`.py` 规则是本地源/站源的核心能力，拆了等于砍功能。只能做 requirements/stdlib 裁剪（REPORT 方案 8，约 2–5MB），不属于模块化。
- **Thunder（6.4MB）/ 剪片（5.3MB）/ 卡拉 OK（3.5MB）**：三个都是独立功能，但 `.so` 包在各自 AAR 内部，拆要先解 AAR、改 AAR 的 Java 加载，各省 5MB 上下，投入产出比差。先不动；若基础包目标卡得紧，再评估。卡拉 OK 的 TFLite 另有用途可能（BasicPitch 之外是否还有调用点，实施时核实）。
- **ASS 原生渲染（2.8MB）**：已有懒加载，且只有 2.8MB；可当作“微模块”顺手做，不单独成阶段。

### 基础包目标体积（arm64 下载口径）

| 阶段 | 拆完 | 基础包约 |
|---|---|---|
| 现状 | — | 213MB |
| Stage 1 试点（Sherpa） | −23.6MB | 约 189MB |
| Stage 2（DV） | −10.0MB | 约 179MB |
| Stage 3（mpv） | −44.5MB | 约 135MB |
| Stage 4（VLC） | −47.4MB | 约 88MB |
| Stage 5（ijk + LUT） | −14.0MB | 约 74MB |

> 注：模块 zip 本身有极小 overhead（zip 容器 + manifest，不到 0.1MB/个），上表已忽略。全量后基础包约 74MB（估算，构建实测待定；VLC 那行已按 47.4MB 修正，原 46.0MB 漏了基础包的 `libc++_shared.so` 1.37MB）；Thunder/剪片/TFLite 若日后也拆，可再到约 64MB，但不推荐为此立项。

## 方案对比

### 档 0：不做

- **收益**：零成本、零风险。
- **缺点**：213MB 不动；用户已明确表态“模块化，模块化”，等于需求落空。
- **结论**：仅作对照档。用户已否决（2026-11-25 18:34）。

### 档 A：仅 Gradle 代码分 module（不减包体）

- **做法**：把 `:app` 里的播放器、字幕、下载等代码拆成若干 Gradle library module，APK 仍一次打全。
- **收益**：结构清晰、构建可增量、权责分明。
- **缺点/风险**：**下载体积省 0MB**——所有 module 仍编译进同一个 APK。`PlayerManager`（9914 行）与各引擎、UI 深度耦合，拆分本身就是大手术，收益却不在用户要的体积上。
- **结论**：不作为体积方案。可在模块化实施中顺手做（新代码放独立 module），但不要把它当成减体积的手段向用户汇报。

### 档 B（推荐）：自建按需下载 .so（GitHub release 做分发）

- **做法**：构建时把各模块的 `.so` 从 APK 里移出，按 ABI 打成 zip，随 release 一起发（release 附件）；App 内建模块管理器：首次使用某功能时下载对应 zip → sha256 校验 → 解到应用私有目录 → `System.load(绝对路径)` 预载 → 功能可用。详见“分发与安全设计”。
- **收益**：基础包 213MB → 约 75MB（全量）；功能一个不少；无新基建（GitHub release 已在用）；NodeLib 与 Sherpa 模型下载已验证过同一条路（OkHttp 下载 + 私有目录落盘 + marker）。
- **缺点/风险**：(1) 首次用某功能要等下载（mpv 44.5MB、VLC 46MB，弱网下几十秒到几分钟），需做好进度与取消；(2) 自建通道的安全责任全在自己（完整性校验、版本绑定、回落），见“分发与安全设计”；(3) 每个模块多一套加载分支与测试面（尤其 VLC 的绑定预载与 mpv 的 LOAD_ORDER）；(4) 模块与 App 版本的对应关系要长期维护；(5) 卸载重装/清数据后模块要重新下载。
- **包体**：见上表。**维护**：每发一版 App 同步发一套模块 zip（CI 里做，不手工）；模块本身跟随上游版本（libvlc、mpv 源码链、sherpa、ijk）走，不另起版本线。
- **结论**：推荐。唯一同时满足“基础包瘦下来 + 功能不砍 + 不走 Play”的路线。

### 档 C：第三方插件化框架（动态加载 APK/dex）

- **做法**：用 VirtualApp、Atlas、DynamicAPK 之类框架把功能做成插件 APK，运行时加载。
- **缺点/风险**：主流框架或停更、或为加固/多开场景设计、或与 Android 14/15/16 的类加载与存储限制冲突；插件 APK 的签名、权限、资源隔离都是新问题；对“只拆几个 .so”的需求是重武器打蚊子。
- **结论**：不推荐。没有可信的、在侧载视频 App 里跑通的先例（外部调研未找到；见证据索引“未覆盖”说明）。

### 档 D：官方 Play Feature Delivery / App Bundle

- **做法**：Dynamic Feature Module + `SplitInstallManager` 按需下载。
- **缺点**：**侧载场景下不可用**。Play Feature Delivery 的下载、安装、校验全由 Play 商店完成；官方文档与教程均要求应用上架 Play（“To test your on-demand modules, you need to have your app on Google Play Store”）；APK Expansion Files 同样是 Play 托管。墨影视走 GitHub release 直发 APK，没有 Play 分发，`SplitInstallManager` 无下载源。
- **结论**：不可行。不是“不好”，是“没有”。档 B 是它在侧载场景下的等价实现，只是下载器、校验器、安装器都要自己写。

### 对照表

| 维度 | 不做 | A（Gradle 分 module） | B（自建按需下载，推荐） | C（第三方插件框架） | D（Play Feature Delivery） |
|---|---|---|---|---|---|
| 基础包体积 | 213MB | 213MB（省 0） | 约 75MB（全量） | 约 75MB（理论） | 约 75MB（理论） |
| 功能保留 | 全 | 全 | 全 | 全（理论） | 全 |
| 不走 Play 可用 | 是 | 是 | 是 | 是 | **否** |
| 构建 | — | 大改 :app 结构 | CI 加模块打包步骤 | 引入重型框架 | 改 AAB + 上架 Play |
| 维护 | 0 | 持续重构 | 每版同步发模块 zip | 框架跟随 | Play 政策跟随 |
| 最大风险 | 用户需求落空 | 白忙一场（体积不动） | 自建通道的安全与回落 | 框架停更/兼容 | 根本不可用 |

## 分发与安全设计

### 模块放哪

- **GitHub release 附件（推荐）**：与 4 个 APK 同一个 release 里，多发 per-ABI 的模块 zip（如 `module-mpv-arm64_v8a-560.zip`、`module-vlc-arm64_v8a-560.zip`，560 = versionCode）。无新基建、无新账号、无 CDN 费用；release 页已有 4 个 APK 的发布流程，模块 zip 由 CI 在同一 workflow 里产出并上传，**不手工打包**。
- 备选（不推荐首选）：同一仓库的另一个 release、或 jsDelivr 代理 GitHub release——多一跳、多一个故障点；只有 GitHub 直连在用户网络下不可用时才评估。

### 版本对应关系

- 模块 zip 内含 `manifest.json`：`{module, moduleVersion, appVersionCodeMin, appVersionCodeMax, abi, upstreamVersion, files: [{name, size, sha256}]}`。App 安装模块前校验：ABI 必须等于设备 ABI；`appVersionCode` 必须落在 `[Min, Max]` 内；否则拒绝安装并提示更新 App。
- 模块版本与 App 版本**同发同版**：每版 App 发版时 CI 从同一 commit 产出模块 zip；模块不单独设版本线（上游版本如 libvlc 3.7.8、mpv 源码 commit 写进 manifest 的 `upstreamVersion`，供 LGPL 合规与问题定位）。
- App 升级时：已装模块的 manifest 记 `appVersionCode`；升级后首次使用某模块时校验，过期则自动重新下载新版模块（旧模块文件删除）。不做静默兼容推断。

### 下载、校验与加载

- 下载：复用现有 OkHttp 客户端（NodeLib、Sherpa 模型下载同款）；支持断点续传（Range）与进度回调；大模块（mpv/VLC 约 45MB）默认仅 Wi-Fi 下自动下载，移动网络下先弹确认（可设“移动网络也下”）。
- 校验：下载完成先对每个文件算 sha256，与 manifest 比对；不一致 → 删文件、报错、可重试。**NodeLib 现状无 sha256（只有版本 marker），模块化必须补上，不照抄这一点**。manifest 本身随 zip 一起下发；进阶加固（Stage 2）：manifest 用 Ed25519 签名，公钥嵌进 App，防 release 被篡改后 manifest 与 zip 一起被换——Stage 1 先做 HTTPS + sha256，签名列为加固项（威胁模型见下）。
- 加载：解到应用私有目录（`Context.getDir("modules/<name>", MODE_PRIVATE)`，与 mpv `app_mpv-libs` 同款）；`System.load(绝对路径)` 预载，顺序按依赖（mpv 用现有 `LOAD_ORDER`；VLC 先 libvlc 后 libvlcjni；ijk 按 ijkffmpeg→ijksdl→ijkplayer）。**禁止外部存储**：共享存储可被其他应用写，下载的执行代码绝不能放那里（OWASP MASWE-0049 / CWE-494）。
- 加载失败：记 `loadError`（mpv 现有模式），功能入口显示“模块损坏/加载失败”，提供“重新下载”与“回落基础功能”两个操作；绝不反复重试加载同一份坏文件。

### 威胁模型（简述）

- 主要风险：攻击者替换模块 zip（中间人、或 GitHub release 被篡改）→ App 加载恶意 `.so`，等于在 App 进程内执行任意代码。缓解：HTTPS（GitHub release）+ sha256（防损坏与传输篡改）+ 私有目录（防本地其他应用替换）+ 版本绑定（防旧版模块混用）。残余风险：release 本身被攻破（攻击者同时换 zip 与 manifest）——Stage 2 用签名公钥把信任锚从“GitHub 账号”移到“嵌进 App 的公钥”；实施时写进任务文档，不在 Stage 1 扩大范围。

### 失败与回滚

- **下载失败**（无网、弱网、GitHub 不可达）：提示 + 重试；功能回落（内核切回 EXO，`sanitizePlayer` 已有；字幕功能置灰并说明；DV 降级普通 HDR——需实施时验证 Exo 回退行为）。不阻塞其他功能。
- **模块损坏/不兼容**：删模块文件，功能回到“未安装”态，用户可重新下载；App 本体不受影响。
- **模块有 bug**（如某版 mpv 模块播不了）：设置里可删该模块回落基础功能；下一版 App 发新模块 zip 修复；**代码回滚**：revert 对应阶段的 guard commit（模块管理器 + 某模块的加载分支 + 构建打包步骤同属一个原子 commit），删掉的 `.so` 回到 APK 内。
- **发布回滚**：测试版照常发下一版；若模块机制导致启动崩溃，走现有“删功能连测试文件一起清”的做法；模块 zip 可从 release 附件里单独删除（不影响 APK）。
- **用户侧回滚**：设置 → 模块管理 → 删模块；删完即回基础包行为，不用重装 App。

## 实施阶段划分（每阶段独立可验证、可回滚）

- **Stage 0 — 前置核查（不写功能代码，只定位与验证）**：定位 Sherpa `.so` 的打包来源（见“未验证”第 1 条）；`readelf` 核查 libvlc.so / libvlcjni.so / libmpv.so 的 DT_NEEDED（libc++_shared 归属）；核查 DV 缺失时的 Exo 回退行为、TV 侧各模块入口可达性。产出写进本文档“未验证”清单的结论。**Stage 0 不通过（Sherpa 来源找不到），试点改 DV**，不硬上。
- **Stage 1 — 试点：Sherpa 模块（推荐）**：模块管理器骨架（下载 + sha256 + 私有目录 + manifest + 版本绑定）+ Sherpa 一个模块 + 设置里的模块管理入口 + 统一的下载提示/进度/失败 UI。为什么是 Sherpa：(1) 单个 `.so`，无依赖链，加载最简单；(2) 与播放完全解耦——模块坏了只影响实时字幕，不影响播片；(3) 模型下载已带 sha256，校验模板现成；(4) 省 23.6MB，是非内核件里最大的。验证：基础包 213MB → 约 189MB；不开实时字幕的用户全程无感知；开实时字幕首次弹下载，装完识别正常；删模块后功能置灰、重装恢复。
- **Stage 2 — DV 模块 + 安全加固**：`ExoDv5Native` 静态加载改懒加载（照 `AssNative.ensureLoaded()` 模式），缺失即 `probe()`=false；manifest Ed25519 签名（公钥嵌 App）；断点续传与仅 Wi-Fi 默认。验证：基础包约 179MB；DV 片源首次弹下载，装完正常渲染；模块缺失时 DV 降级不崩（实施时验证回退行为）。
- **Stage 3 — mpv 模块**：`MPVLib.ensureLoaded` 的数据源从 assets 改成已下载 zip（marker 与 LOAD_ORDER 不动）；构建时 mpv-libs 移出 APK、CI 打 per-ABI zip。验证：基础包约 135MB；切 mpv 首次下载、装完起播正常；弱网取消后回落 EXO；删除模块重装恢复。
- **Stage 4 — VLC 模块**：构建时从 libvlc-all AAR 抽 `libvlc.so` + `libvlcjni.so` 打 zip（Java 绑定留基础包）；`VlcPlayer` 构造前预载（先 libvlc 后 libvlcjni）；`isPlayerAvailable(VLC)` 查模块安装态；LGPL 文本与源码链接随模块 manifest 走。**为什么 VLC 不做试点**：VLC 内核 Stage 1（v5.6.0-beta-202611251756）刚上、真机播放行为尚未验证；试点同时调两个未知量（新内核行为 + 新模块机器）是下策。等 VLC 真机验证通过、且 Stage 1–3 的机器跑通，再拆 VLC。验证：基础包约 89MB；切 VLC 首次下载、装完播片正常；TV 端同批。
- **Stage 5 — ijk + LUT + 收尾**：ijk 模块（`isPlayerAvailable` 真检查 + 回落 EXO）；LUT 资产包懒加载；ASS 微模块（可选）；模块管理页完善（已装列表、占空间、删模块）；文档与版本记录。验证：基础包约 75MB；全部模块装/卸/重装一轮通过。
- **每阶段一个 guard 会话 + 一个原子 commit + recovery tag**；任一阶段失败可单独回滚，不影响其他模块与基础包。

## 验收标准（手机真机可测）

1. 基础包：每阶段结束后 mobile-arm64 APK 体积符合上表（误差 ±2MB 内，实测值写入发布说明）。
2. 试点（Sherpa）：全新安装 App，实时字幕首次开启弹下载提示（显示 23.6MB）；Wi-Fi 下下载成功，识别出字幕；移动网络下先确认再下载；下载中可取消，取消后功能置灰；删模块后功能回到未安装态，再开重新下载。
3. 下载失败：断网时下载，明确报错 + 重试按钮；重试成功后功能可用；失败期间播放不受影响。
4. 校验：故意损坏模块文件（或改 manifest 里的 sha256 后重下），App 拒绝加载并提示重新下载，不闪退。
5. 回落：模块未安装时切 mpv/VLC/ijk，提示下载或自动回落 EXO，不闪退；DV 模块未安装时播 DV 片源不崩（降级行为以实施时验证为准）。
6. 升级：装好模块后升新版 App，旧模块被识别为过期并重新下载（或明确提示），不加载旧版 `.so`。
7. 模块管理：设置里能看到已装模块与占用空间，删模块后空间释放、下次使用重新下载。
8. TV 端：leanback 上 VLC/mpv 切内核的下载提示与回落与手机一致（TV 无移动网络，Wi-Fi 逻辑相应简化）。
9. 回归：全部模块都不装时，App 启动、播片（EXO）、搜片、短剧、实验室、本地源与改动前一致。
10. 连续播 3 集不闪退；模块装/卸各 5 次不闪退；模块下载中途杀 App 再进，状态一致（不断点续传或干净重下，不留半截文件当已装）。

## 回滚路径

- 代码回滚：revert 对应阶段的 guard commit（模块管理器 + 某模块的加载分支 + CI 打包步骤同属一个原子 commit）；`PlayerSetting.sanitizePlayer()` 已保证未知/不可用内核回落 EXO，旧数据安全。
- 发布回滚：测试版照常发下一版（模块机制缺省关闭时不影响任何现有用户）；若模块机制导致启动崩溃，走现有“删功能连测试文件一起清”的做法；模块 zip 可从 release 附件里单独删除。
- 模块回滚：用户在设置里删模块即回基础包行为；CI 可随时停发某模块的新 zip（旧版 App 校验 appVersionCode 后拒绝安装旧模块，提示更新 App）。

## Stage 0 核查结论（2026-11-25 晚，实施前核查，全部通过，无 blocked 模块）

1. **Sherpa `.so` 来源已定位**（解决未验证第 1 条）：`app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so` 与 `armeabi-v7a` 同名文件，均已提交进 git（`git ls-files` 确认）；`app/libs/sherpa-onnx-v1.13.4.jar` 只有 323KB Java 绑定。mod-sherpa 不 blocked。
2. **DT_NEEDED（从 `~/workspace/apk-size-analysis/arm64/` 解包 APK 的 readelf）**：
   - libvlc.so 需 `libc++_shared.so`；libvlcjni.so 需 `libvlc.so` → VLC 预载顺序 libc++_shared → libvlc → libvlcjni。
   - libmpv.so 需 libmvcodec/libmvfilter/libmvformat/libmvutil/libmwresample/libmwscale/libmvdevice/libc++_shared；libplayer.so 需 libmpv.so → 现有 `MPVLib.LOAD_ORDER` 顺序正确，换数据源时保持。
   - libsherpa-onnx-jni.so：仅系统库，无 libc++ 依赖。libexo_dovi_renderer.so：仅系统库（含 libvulkan、libmediandk）；libdovi_android.so：仅 libdl/libc；两者无互依赖。libijkffmpeg.so：仅系统库；libijkplayer.so 需 libijkffmpeg + libijksdl → ijk 预载顺序 ijkffmpeg → ijksdl → ijkplayer。
   - libc++_shared.so：`lib/` 里的那份与 `assets/mpv-libs/` 里的那份 md5 相同（`b02c898086ba3f55f3748f6cf6bae128`），lab 里的是另一构建（`74c074af816a40cff31b1803d638c880`）。基础包 `lib/*.so` 里只有 libvlc.so 需要 libc++_shared → VLC 模块化后基础包可删 libc++_shared.so（省 1.4MB），VLC 模块 zip 必须自带并先预载；mpv 模块自带自己那份。
3. **MPVLib.ensureLoaded**（`is/xyz/mpv/MPVLib.java:59`）：assets → 私有目录 `app_mpv-libs/<abi>/` → 按 LOAD_ORDER `System.load`，marker `.bundle-last-update`，失败记 `loadError`，`MpvPlayer.java:1821` 抛 IOException 走现有播放错误链。模块版只需把数据源换成模块目录。
4. **ExoDv5Native**：`private static final LoadResult LOAD_RESULT = loadLibrary();` 类加载时一次定死 → 模块化须改懒加载（失败后可重试），否则模块装完当进程仍判 false。`probe()` 不可用即 false，回落链路已存在。
5. **VlcPlayerEngine.buildPlayer**：`new VlcPlayer(App.get(), decode)`，VlcPlayer 构造里 `new LibVLC(...)`（约 :118）→ 预载钩子必须在该构造前；预载失败时 LibVLC 构造抛 UnsatisfiedLinkError。`PlayerManager.buildEngine`（约 :6383）构造引擎处无 try/catch，须处理。
6. **PlayerManager 回落已存在**：`ensurePlayerAvailableForPlayback()`（约 :9814）→ `nextFallbackPlayer()` → 切下一个可用内核或报错 `error_play_ijk_unavailable`；`resolveManualPlayer(type)`（约 :9875）：手动切到不可用内核 → `Notify.show(error_play_ijk_unavailable)` 并保持当前内核。这是新提示“请到模块里面下载”的接线点（需 Activity 上下文弹对话框 + 跳转按钮）。
7. **PlayerSetting**：VLC=4，`KERNEL_ORDER = {EXO, IJK, MPV, SYSTEM, VLC}`；`isPlayerAvailable` 目前只对 IJK 有分支且 `isIjkAvailable()` 恒 true；`sanitizePlayer` 非法/不可用回落 EXO。
8. **IjkLibLoader**（`tv/danmaku/ijk/media/player/IjkLibLoader.java`）：抽象类，`loadLibrary(String)` 抽象；`IjkMediaPlayer.java:128` 的匿名实现调 `System.loadLibrary` → 模块化须改写成 `System.load` 模块目录绝对路径。
9. **Sherpa 原生加载**：jar 内 `LibraryUtils.class` 含 "sherpa-onnx-jni" 字串、`LibraryLoader.class` 含 "loadLibrary" → jar 自带加载器；拦截点是首次用 Sherpa 类之前预载（`RealtimeSubtitleRecognizer.create()` → `new OnlineRecognizer(...)`，约 :83）。
10. **NodeLib**（`node/NodeLib.java`）：`ensure(context, progress)` 阻塞式；`installed()` 靠 marker；OkHttp + Range（`NodeRangeZip`）下载，`GithubProxy.apply(url)`（GitHub 直连在部分网络下不可达——模块下载必须复用它，见“加速补充记录”）；失败链 range → 整包 → 直连；文件在 `getFilesDir()/node/<abi>/`。
11. **LutStore**（`player/lut/LutStore.java`）：`AssetManager.list` 扫 assets `lut_presets` + 用户 SAF 树；`LutPreset` 有 id/name/assetPath/format/asset 标志；13 个 `.cube` 在 `app/src/main/assets/lut_presets/`。模块化加 `scanModule()` 从模块安装目录列 `.cube`；assets 留 1 个默认（乌托邦.cube，资产列表首项）。
12. **实验室本地导入模式**：`LabActivity` 用 `Intent.ACTION_OPEN_DOCUMENT`（:528、:751）、`LabEnv.importPackage(context, cache)`（:645）、`LabFilePicker`（ACTION_OPEN_DOCUMENT_TREE / ACTION_OPEN_DOCUMENT）。模块本地导入照抄：SAF 选 zip → 拷缓存 → 同一套校验/安装。
13. **GithubProxy.apply(url)** 用 `Setting.getGithubProxy()` + `Setting.isGithubProxyEnabled()`，只对 GitHub 下载生效；`App.get()` 存在（`App.java:87`）。
14. **Workflow**（`.github/workflows/android-release.yml`）：build job 每 variant assemble → "Collect APK" 要求 `Release/apk` 里恰好 1 个 APK → upload-artifact `apk-<variant>`；publish job 下载 `apk-*`，要求恰好 4 个 APK，逐个生成 `.json` 清单，`gh release create/upload dist/*.apk dist/*.json`。模块 zip 进 release 附件**需要改 workflow**：build job 须同时收集模块 zip 为 artifact；publish job 须下载它们并在 `gh release upload/create` 里加 `dist/*.zip`。另有 "Verify bundled MPV native assets" 步骤跑 `scripts/verify_mpv_native_assets.sh --require-elf`（核对已提交的 mpv assets 与 `third_party/mpv-native-lock.json`——mpv assets 若移出 `app/src/.../assets`，脚本须同步改路径；脚本改动可走 git，workflow 文件改动不可走 git）。**实施员不推 workflow 改动，只报精确 diff 给父代理走浏览器改。**
15. **单元测试**：模块管理器 JVM 测试（manifest 校验、sha256 不一致拒绝、版本绑定过期拒绝、ABI 不匹配拒绝）。注意 workflow 测试 job 只过滤 danmaku.*、GithubProxyTest、AboutDialogLayoutTest、VodAdapterLayoutTest（mobile）/ danmaku.*、GithubProxyTest（leanback）——新模块测试须进 workflow diff 的过滤列表，否则 release 关键测试跑不到它们。

## 实施记录

### 单元 ① 模块管理器核心（guard 会话 module-manager-core，2026-11-25 晚）

- 新包 `app/src/main/java/com/github/moyingshi/osc/tv/module/`：
  - `ModuleDefinition`：6 个模块静态定义（id/显示名/约体积/zip 内文件/预载顺序）；VLC 含自带 libc++_shared.so 且先预载它（Stage 0 结论 2）；LUT 列 12 个 .cube（乌托邦.cube 留基础包）。
  - `ModuleManifest`：manifest.json 的 Gson 模型 + validate（模块名、ABI、appVersionCode 范围、文件项合法性、sha256 格式）。
  - `ModuleVerifier`：sha256（文件/字节数组）、逐文件校验（存在+大小+sha256）、运行时快检（存在+大小，不算 sha256）。
  - `ModuleInstaller`：在线下载与本地导入共用的唯一安装路径。分包：解到暂存目录 → 逐文件校验 → 通过后整体搬进 `modules/<id>/`，失败删干净且旧模块原样保留；总包：先按 total-manifest.json 验各内层分包 sha256，再逐个走分包安装，某分包失败只影响它自己；防 zip-slip（canonical 路径检查 + 清单文件名校验）。
  - `ModuleDownloader`：OkHttp 下载，断点续传 Range（206 续传、200 从头、416 删旧重下、只重试一次），进度回调每 1MB；**走 GitHub 代理**：`GithubProxy.apply(originUrl)` 优先，代理失败回退直连（照 NodeLib.fetch），直连也失败才报错；取消令牌保留半截文件供下次续传。
  - `ModuleManager`：单例门面。私有目录 `getDir("modules")/<id>/`；状态 NOT_INSTALLED/INSTALLED/OUTDATED（版本绑定过期）/CORRUPT；`ensureLoaded` 按 loadOrder `System.load` 绝对路径预载（同模块只载一次，失败记 loadError 返回 false 不抛）；`downloadAndInstall` / `downloadAndInstallAll`（下载地址用 `BuildConfig.BUILD_TAG` 对应的 release tag，空时查 GitHub API 最新 release 兜底）；`isWifi()` 供移动网络确认；`deleteModule`。
- 测试 `app/src/test/java/com/github/moyingshi/osc/tv/module/`（5 类，JVM 单元测试；本地无 JDK，GitHub 构建的测试 job 是唯一执行检查——workflow diff 须把它们加进过滤列表）：
  - `ModuleDefinitionTest`：6 模块、id 唯一、loadOrder 是 files 子集、VLC 先载 libc++_shared、zip 文件名。
  - `ModuleManifestTest`：解析/往返、ABI 不符、版本低于 Min/高于 Max、未知模块、sha256 格式错、文件列表空。
  - `ModuleVerifierTest`：sha256 标准向量（"abc"）、文件校验通过/大小不符/sha 不符/缺失。
  - `ModuleInstallerTest`：有效分包安装落盘、sha 不符拒绝且旧模块保留、ABI 不符、版本绑定过期、缺 manifest、zip-slip、总包一好一坏（好装上坏跳过）、总清单 sha 错只影响该分包。
  - `ModuleDownloaderTest`（MockWebServer，`mockwebserver3` 包，OkHttp 5.4.0）：整包下载、断点续传、服务端忽略 Range 从头下、416 删旧重下、404 报错、代理 URL 失败回退直连 URL、取消、进度回调。
- 体积影响：本单元只加 Java 代码（约 30KB 量级），基础包体积不变；瘦身在单元 ⑤（Gradle 打包移出 .so/assets 后生效）。

### 单元 ② mpv/VLC 接线（guard 会话 module-wire-mpv-vlc，2026-11-25 晚）

- `is/xyz/mpv/MPVLib.java`：`ensureLoaded` 不再从 assets 拷 bundle，委托给 `ModuleManager.ensureLoaded(ModuleDefinition.MPV)`（数据源改成模块目录 `modules/mpv/`，LOAD_ORDER 与 marker 逻辑随之删除）；失败不缓存——模块装好或重装后下一次调用重新尝试加载（旧代码 `loadError != null` 直接返回的粘滞分支已删，否则用户装完模块仍播不了）；`isBundledVulkanEnabled` 改读模块目录的 libmpv.so 扫 "List of enabled features:"，缓存按 libmpv.so 的 lastModified 失效。
- `player/engine/VlcPlayerEngine.java`：`buildPlayer` 在 `new VlcPlayer` 之前预载 VLC 模块（先 libvlc 后 libvlcjni，由 ModuleManager 按 loadOrder 做）；模块未安装抛 `IllegalStateException("VLC 模块未安装，请到模块里面下载 VLC")`，已装但加载失败抛“请到模块管理里重新下载 VLC”（带 loadError 作 cause）。
- `setting/PlayerSetting.java`：`isPlayerAvailable` 改成真检查——IJK/MPV/VLC 查对应模块安装态，EXO/系统恒可用；`isIjkAvailable()` 不再恒 true，新增 `isMpvAvailable()`/`isVlcAvailable()`；`sanitizePlayer` 不动（只校验常量合法性，设置页对话框要如实显示用户已存的选择，模块缺失的回落由播放侧负责）；`App.get()` 为 null 时判不可用（防 Application 未 attach 时调用）。
- `player/PlayerManager.java`：`buildEngine` 加 try/catch——任一引擎构造失败时（VLC 预载失败是主因：正常切换前 resolveManualPlayer/resolveAvailablePlayer 已用 isPlayerAvailable 拦过，但构造时仍可能遇到竞态或同尺寸损坏文件；且构造前旧引擎已释放，直接抛会裸奔）统一回落 EXO，并把 `playerType` 与会话内核改回 EXO，与刚建起来的引擎保持一致；EXO 本身建不起来则原样抛出。写法照 4522/4624 行已有的 buildEngine try/catch 先例。
- 本单元未改任何 UI：设置页选 MPV/VLC 且模块未安装时，本单元的行为是 `sanitizePlayer` 不拦、播放侧回落 EXO（旧有静默回落）；“请到模块里面下载”提示在单元 ④ 的 call site 接。
- 体积影响：本单元只改 Java 代码，基础包体积不变。

### 单元 ③ Sherpa/DV/ijk/LUT 接线（guard 会话 module-wire-rest，2026-11-25 晚）

- `subtitle/RealtimeSubtitleController.java`：`prepareModel` 的 try 顶部先验 Sherpa 模块（`ModuleManager.ensureLoaded(SHERPA)`），未安装直接抛 `IllegalStateException("实时字幕模块未安装，请到模块里面下载实时字幕")`，走现有 catch → failRecognizer → 状态 ERROR + 提示；放在 `ensureModel` 之前，避免无谓下载模型文件。模块装好后下一次开启重新尝试。模型文件本身仍是在线下载，不动。
- `player/exo/ExoDv5Native.java`：`static final LOAD_RESULT` 改懒加载（`ensureLoaded()`，成功后快路径、失败下次重试；`probe()`/`create()` 都走它）。`loadLibrary()` 先预载 DV 模块（libdovi_android + libexo_dovi_renderer，按 loadOrder），再 `System.loadLibrary("exo_dovi_renderer")`（已预载的库会被跳过）；模块未安装时抛 UnsatisfiedLinkError → probe()=false，播 DV 片源降级不崩。`App.get()` 为 null 时跳过预载直接走老路。
- `tv/danmaku/ijk/media/player/IjkLibLoader.java`：加载失败不再缓存（catch 里 `loadAttempted = false`），模块后装上时下一次 `isAvailable()` 重新尝试（旧代码一旦失败整进程判不可用）。
- `tv/danmaku/ijk/media/player/IjkMediaPlayer.java`：匿名 `IjkLibLoader` 的 `loadLibrary` 优先从模块私有目录 `System.load` 绝对路径，找不到（模块未安装）时回退 `System.loadLibrary`（过渡期包内仍有 .so 时走老路；模块化完成后抛 UnsatisfiedLinkError，由 isAvailable() 接住判不可用）。加载顺序仍是 ijkffmpeg → ijksdl → ijkplayer。
- `player/lut/LutStore.java`：`scanPresets` 增加 `scanModule()`（在 scanAssets 之后、scanTree 之前）：模块已安装时从 `modules/lut/` 列 `.cube` 补齐，预设 id 用 `module:` 前缀、asset=false、path 为绝对路径，`LutEffectFactory.open` 直接走 FileInputStream；未安装时只有 assets 里的 1 个默认（乌托邦.cube，按名排序首项）可用。manifest.json 不是 .cube，`formatOf` 为 null 自动跳过。
- 注意（留给单元 ④）：LUT 模块装/删后须调 `LutStore.clearCache()`（或 refreshPresets），否则缓存的预设列表不更新。
- 体积影响：本单元只改 Java 代码，基础包体积不变。

### 单元 ④ 设置“模块”页 + 提示（guard 会话 module-settings-page，2026-11-25 晚）

- 新文案（`values/strings.xml` 与 `values-zh-rCN/strings.xml`，各 22 条）：`setting_module`/`setting_module_sub`（设置行）、`module_missing_prompt`（用户原话“请到模块里面下载 %1$s”）、`module_go_download`（去下载）、`module_download_all`（下载全部（总包））、`module_import`（本地导入）、`module_download`（在线下载）、`module_delete`、`module_redownload`、4 个状态文案、`module_downloading`（下载中 %1$d%%）、`module_mobile_confirm`、`module_install_success`/`module_install_failed`/`module_download_failed`、`module_pick_zip`、`module_page_hint`、`module_importing`、`module_busy`。标准按钮沿用 `android.R.string.ok`/`cancel`。
- `module/ModulePrompt.java`：未安装提示的唯一构造点。`showMissingModule(Activity/Fragment, ModuleDefinition[, Runnable onCancel])`：文案 = 原话 + 模块显示名；“去下载”按钮直跳 `ModuleActivity.start(activity, definition.id)`（尽量直达该模块行）；取消/返回键走 onCancel（按钮 dismiss 不触发 OnCancelListener，不会重复跑）；空/finishing/destroyed 的 Activity 或 detached 的 Fragment 时直接跑 onCancel。只在用户主动选了未安装模块时由 call site 弹，已安装不弹。
- `module/ModuleActivity.java`（main 源码集，main Manifest 已声明 `.module.ModuleActivity`，与 LabActivity 同格式）：Toolbar + 提示行 + “下载全部（总包）”按钮 + RecyclerView（6 行，名/大小/版本/状态）。行点开弹操作单（`WebHtvAlertDialogBuilder.setItems`）：未安装是在线下载/本地导入；已安装是重新下载/本地导入/删除；过期/损坏是重新下载/本地导入/删除；下载中是取消下载。删除先弹确认。在线下载走 `ModuleManager.downloadAndInstall`/`downloadAndInstallAll`（已走 GitHub 代理，见单元 ①）；移动网络下先弹确认（`isWifi()` 假时）；进度写进行副标题（总包进度落到全部 6 行，取消令牌记在 "all" 键上）；同时只跑一个下载（按钮置灰 + `module_busy` 提示）。本地导入照实验室抄：`ActivityResultLauncher` + `ACTION_OPEN_DOCUMENT` 选 zip → 拷缓存 → 工作线程 `installZip`（分包/总包都能导）→ `Notify` 结果 + 刷新。LUT 装/删后调 `LutStore.clearCache()`（单元 ③ 的遗留点已闭合）。布局 `activity_module.xml` + `item_module.xml` 在 main res（自写，不依赖 mobile 才有的 `ItemSettingCardRowBinding`，否则 leanback 编不过）；滚动到指定行用 `list.post(scrollToPosition)`（布局前不滚动，实验室的教训）。
- `setting/PlayerSetting.java`：新增 `moduleForPlayer(int)`（IJK→IJK、MPV→MPV、VLC→VLC，EXO/系统返回 null；放在 PlayerSetting 是为避免 module↔setting  import 环）。
- 手机设置入口：`SettingFragment` 特色功能组末尾（追更提醒行之后）加“模块”行（`ROW_MODULE`，图标 `ic_setting_download`、紫圈），点开 `ModuleActivity.start(requireContext())`。
- 提示 call sites（未安装时弹提示、取消则按原有回落走，不改内核、不崩、不反复弹；已安装时原样通过）：
  - 设置选内核：手机 `SettingPlayerFragment.onKernel`、TV `SettingPlayerActivity.setKernel`（`PlayerKernelDialog` 回调里先查 `isPlayerAvailable(which)`，未安装弹提示并 return、不 `putPlayer`）。
  - 播放器内切内核：leanback `VideoActivity`/`CastActivity`/`LiveActivity` 与手机 `LiveActivity` 的 `switchPlayerKernel(int)` 顶部、`VideoActivity.onChoose()`（`kernelAt(index)` 之后）。
  - 实时字幕：`TrackDialog.onRealtimeAi`（停止分支之后、选模型之前查 Sherpa；取消则不启用，功能保持关闭）。
  - LUT：手机 `SettingPlayerFragment.onLut`、TV `SettingPlayerActivity.onLut`（未安装先弹提示；取消仍打开对话框——包内自带 1 个默认 LUT 可用）。
  - **DV 无提示点**：grep 确认 DV 没有任何用户侧入口（设置页、播放器设置、字符串里都没有 DV/杜比行；探针只在 `ExoDv5GpuRendererFactory.shouldCreate` 内部调），未安装时按单元 ③ 的 probe()=false 降级，不额外弹提示；如实记下，不编一个入口。
- TV 决定（best-effort）：TV 设置页是 `SettingActivity` 固定三列瓷砖网格（`activity_setting.xml`，焦点链写死），加一格要重排网格 + 焦点链，风险高——**TV 设置页不加“模块”格**。TV 侧回落行为完整（`isPlayerAvailable` 门控 + `buildEngine` 回落 EXO）+ 提示的“去下载”按钮能跳到模块页（ModuleActivity 在 main Manifest，主源码集，TV 构建也有）。TV 用户从提示进来可下载模块；TV 设置里没有独立入口，见报告。
- 体积影响：本单元只加 Java 代码与两份小布局，基础包体积不变；瘦身在单元 ⑤。

### 单元 ⑤ Gradle 打包（guard 会话 module-gradle-packaging，2026-11-25 晚）

- `app/build.gradle`：`android.packaging.jniLibs.excludes`（用 `addAll`，AGP 的 excludes 只有 getter、Groovy 下 `+=` 不可靠）排除 9 个 `.so` ×2 ABI（Sherpa、ijk×3、libc++_shared、libvlc、libvlcjni、libexo_dovi_renderer、libdovi_android）。排除前对 APK 内全部 `.so` 做 `readelf DT_NEEDED` 核查：只有 libvlc.so 需要 libc++_shared.so（故基础包删它安全，VLC zip 自带 AAR 那份）；libexo_ass.so 只依赖系统库；其余基础包 `.so` 不依赖任何一个被排除的文件（libmedia3ext 依赖的 FFmpeg 库留在基础包）。
- `app/build.gradle` 末尾：`packageModulesArm64_v8a` / `packageModulesArmeabi_v7a` 两个任务（`moduleAbis`/`moduleOrder`/`moduleFiles`/`moduleUpstreamVersions` 四张表；文件清单与 `ModuleDefinition.files` 必须保持一致）。每 ABI 产 6 个分包 + 1 个总包到 `app/build/outputs/modules/<abi>/`：分包根目录是 manifest.json（module/moduleVersion/appVersionCodeMin=Max=当前 versionCode/abi/upstreamVersion/files[{name,size,sha256}]，与 `ModuleManifest`/`ModuleInstaller` 的格式逐项对过：总清单是 `{modules:[{file,module,size,sha256}]}`，内层分包文件名不含 `/`）+ 模块文件平铺；总包是 6 个分包 + total-manifest.json，不重复放 `.so`。确定性：`ZipOutputStream` 固定时间戳 1980-01-01 + 固定条目顺序（manifest 在首位），manifest 不含时间戳。
- 各模块 `.so` 来源（逐项实测，非推测）：mpv 是移出 assets 后的 `app/src/<flavor>/mpv-libs/<abi>/`（10 个文件，arm64 46,673,648 字节）；VLC 从 `moduleAar` 配置解析的 libvlc-all 3.7.8 AAR 取 `jni/<abi>/` 三件（arm64：libc++_shared.so 9,290,184 + libvlc.so 45,875,392 + libvlcjni.so 101,104；zip 约 55MB）；Sherpa/ijk 取 `app/src/main/jniLibs/<abi>/`（Sherpa arm64 23,603,240、armeabi 16,110,348；ijk arm64 10,303,584、armeabi 7,582,160）；DV 的 libdovi_android.so 取 exoplayerhdrutils 0.4.0 AAR 的 `jni/<abi>/`（arm64 589,400），libexo_dovi_renderer.so 取 CMake 产物 `build/intermediates/cmake/**/obj/<abi>/`（任务依赖 mobile variant 的 CMake 构建，`tasks.matching` 按名匹配；找不到时抛错明说，不静默出坏包；mobile/leanback 产物可能因 DWARF 内嵌路径不同而字节不同，按路径排序取首个，CI 只从 mobile 任务收集，故发布物唯一；注意 zip 里这份是未 strip 的，比 APK 里 9.6MB 那份略大）；LUT 取移出 assets 后的 `app/src/main/lut-presets/`（12 个文件 9,862,336 字节，zip 压缩后约 3.7MB）。
- 资产移出 assets（AGP 9.2.1 的 `packaging` DSL 只有 dex/jniLibs/resources 三块，官方 API 参考已核实**没有 assets 块**，assets 没法用排除语法）：`app/src/<abi>/assets/mpv-libs/` → `app/src/<abi>/mpv-libs/`（`git mv`，20 个文件；`assets/lab/` 不动）；12 个 `.cube` 从 `app/src/main/assets/lut_presets/` → `app/src/main/lut-presets/`（乌托邦.cube 留 assets，`lut_presets/` 里留了 `.gitkeep`）。配套改了五处路径：`scripts/verify_mpv_native_assets.sh`（verify_abi 的目录）、`scripts/build_mpv_native.sh`（--install 目标 + 帮助文案）、`scripts/build_mpv_player_jni.sh`（--install 目标，libplayer.so 同一位置）、`third_party/mpv-native-build.md`（安装目录说明 + “不再作为 assets 打进 APK”）、`.codex/skills/upstream-integration-governor/scripts/verify_upstream_checkpoint.sh`（受保护路径 glob）。另有一处没法随代码推：`.github/workflows/mpv-native-rebuild.yml:185-189` 也往旧路径拷 libplayer.so，见 workflow diff（浏览器改）。历史任务文档（docs/C10、P1/P2-*、android7-support-audit 等）里提到的旧路径是当时状态的记录，不动。
- `configurations.moduleAar`（不可消费、可解析）：只解析 libvlc-all 与 exoplayerhdrutils 两个 AAR 供打包任务取 `.so`；Java 绑定仍由 `implementation` 引入、留基础包。
- 接线：4 个 release assemble 任务（`assemble(Mobile|Leanback)(Arm64_v8a|Armeabi_v7a)Release`，与 workflow 矩阵同名）各 `dependsOn` 对应 ABI 的 package 任务；debug 不打。任务无 inputs/outputs 声明，每次 release assemble 都重新打。
- 体积影响（估算，构建实测待定）：mobile-arm64 基础包 213MB → 约 74MB（移出 mpv 44.5 + VLC 相关 47.4 + Sherpa 23.6 + DV 10.0 + ijk 10.3 + LUT 3.7 ≈ 139.5MB；`.so` 几乎不压缩、`.cube` 按压缩后 3.7MB 计）。模块 zip（未压缩口径）：mpv 46.7MB、VLC 55.3MB、Sherpa 23.6MB、DV 约 11–13MB（含未 strip 的渲染器）、ijk 10.3MB、LUT 约 3.7MB（zip 压缩后）；总包约 150MB。Chaquopy Python（20.9MB）不在 6 个模块里，没动。
- 本单元未改 workflow（gh token 缺 workflow 权限，diff 见父代理收到的报告）；本地无 JDK/Gradle，`packaging` 排除与打包任务的编译正确性只能由 GitHub 构建验证（构建触发前须先上 workflow diff）。
- 提交过程（2026-11-25 20:11–20:12）：commit `72a22a33`（40 文件，293+/24-，全部 rename 100% 相似度，字节未动），recovery tag `recovery/module-gradle-packaging/20261125201159-72a22a33f908`。guard `start` 曾三次失败：`--adopt-dirty` 必须与 initial-dirty 清单逐项精确匹配，目录级 adopt 不支持前缀匹配，且 git 对非 ASCII 文件名（12 个中文 `.cube`）输出带引号的八进制转义、目录前缀匹配不上；解法是 `git config core.quotePath false`（本地仓库配置，不进提交）后用文件级 adopt（7 个改动文件）+ 新路径目录级 adopt（3 个目录）。`finish` 又失败一次：3 个 CRLF 行尾的 `.cube` 文件（低对比/复古深棕/通透鲜艳）被 `git diff --cached --check` 当行尾空白拦下；解法是新增 `.gitattributes` 规则 `*.cube whitespace=cr-at-eol`（数据文件字节不动，约 7.5 万行 LUT 不改写，已实测 check 通过）。但提交时 `printf >` 覆盖了已存在的 `.gitattributes`，丢了 `*.patch` 与两个 drpy min.js 的空白豁免，随后 quick-fix 会话 `module-gitattributes-fix` 以 commit `e9a17079`（tag `recovery/module-gitattributes-fix/20261125201234-e9a17079f331`）把原有 4 行逐字节恢复、`.cube` 规则保留。教训：覆盖已存在文件前先读原内容。两次 commit 均已推上远端 main（只推 main + 当次 recovery tag，不用 `--tags`）。

## 未决问题（需要用户拍板）

1. **试点选哪个**：推荐 Sherpa（最独立、与播放解耦、省 23.6MB）。是否同意？还是改 DV（更小、探针现成）或 mpv（最大、最能验证机器）？
2. **VLC 进模块的时间点**：推荐 Stage 4（等 VLC Stage 1 真机验证通过、且 Stage 1–3 的机器跑通）。是否同意？还是提前到 Stage 2/3？
3. **模块放哪**：推荐 GitHub release 附件（与 APK 同一个 release，无新基建）。是否同意？还是另找地方（如 jsDelivr 代理）？
4. **基础包目标体积**：全量后约 75MB（Sherpa+DV+mpv+VLC+ijk+LUT）。这个目标是否接受？还是只做前几阶段（如只到 135MB 的 mpv 为止）？
5. **下载体验**：首次使用某功能时弹下载提示（推荐）；还是设置里集中预下载、或 Wi-Fi 下自动下？移动网络下默认弹确认是否接受？
6. **老版本用户升级**：升新版后模块缺失，首次使用时下载（推荐）。是否接受？还是升级后首次启动就提示补模块？
7. **下载失败的回落**：功能不可用 + 明确提示 + 回落 EXO/置灰（推荐）。是否接受？还是失败时静默重试？
8. **模块管理入口**：设置里单独一页“模块管理”（推荐），还是只放在各功能开关旁边？TV 端是否也要模块管理入口？

## 未验证 / 需实施时验证

1. **Sherpa `.so` 打包来源**：~~来源未定位~~ → **已定位（Stage 0 结论 1）**：`app/src/main/jniLibs/<abi>/libsherpa-onnx-jni.so`，已提交进 git；`app/libs/sherpa-onnx-v1.13.4.jar` 只有 Java 绑定。mod-sherpa 不 blocked。
2. libvlc 绑定静态加载细节：**已核查（Stage 0 结论 2）**：libvlc.so 需 libc++_shared.so，libvlcjni.so 需 libvlc.so；预载顺序 libc++_shared → libvlc → libvlcjni；VLC 模块 zip 自带 libc++_shared.so。剩余：`org.videolan.libvlc.LibVLC` 静态块具体 `loadLibrary` 哪个名字——实施时以预载后构造成功为准（预载成功则绑定跳过已加载库）。
3. mpv 的 `libplayer.so`（JNI 桥，96KB）是否 DT_NEEDED `libmpv.so`；若是，模块加载顺序必须保证 libmpv 先于 libplayer（现有 `LOAD_ORDER` 已是此顺序，换数据源时保持）。
4. DV 模块缺失时 Exo 的回退行为：播 DV 片源是降级 HDR10/HEVC 还是报错；`ExoDv5Native.probe()` 的调用方与回退链路需实施时读全。
5. TV（leanback）侧各模块入口可达性：VLC/mpv 切内核在 TV 上可达（Stage 1 已进）；实时字幕、DV 在 TV 端的入口与回落需核查。
6. `libc++_shared.so` 的归属：**已核查（Stage 0 结论 2）**：`lib/` 里的那份与 `assets/mpv-libs/` 里的那份 md5 相同（`b02c898086ba3f55f3748f6cf6bae128`）；基础包 `lib/*.so` 里只有 libvlc.so 需要它。VLC 模块化后基础包可删 libc++_shared.so（省 1.4MB），VLC 模块 zip 自带并先预载；mpv 模块自带自己那份。
7. GitHub release 附件大小限制与下载速度：模块 zip（单个最大约 55MB，VLC）随 release 发是否有限制、用户侧下载速度是否可接受；若 GitHub 直连在用户网络下慢，是否需要镜像。
8. Sherpa Java 绑定是否在类加载时就 `loadLibrary`（静态块在哪个类）：**已核查（Stage 0 结论 9）**：jar 内 `LibraryUtils.class` 含 "sherpa-onnx-jni" 字串、`LibraryLoader.class` 含 "loadLibrary" → jar 自带加载器；拦截点是首次用 Sherpa 类之前预载（`RealtimeSubtitleRecognizer.create()` → `new OnlineRecognizer(...)`，约 :83）。

## 证据索引

| # | 结论 | 来源 | 访问日期 | 等级 |
|---|---|---|---|---|
| E1 | Play Feature Delivery 按需模块由 Play 商店分发；`SplitInstallManager` 从 Play 下载；本地测试须应用上架 Play（“To test your on-demand modules, you need to have your app on Google Play Store”） | https://developer.android.com/guide/playcore/feature-delivery/on-demand ；https://www.kodeco.com/27947850-android-app-bundles-play-feature-delivery/page/3?page=1 | 2026-11-25 | A（官方文档 + 教程实证） |
| E2 | 按需模块的原生库推荐用 ReLinker 加载；`System.loadLibrary` 须手动按依赖顺序加载；可选模块内 `dlopen` 相对路径不可用，须用 `ClassLoader.findLibrary()` 取绝对路径 | https://developer.android.com/guide/playcore/feature-delivery/on-demand （“Load native code in an app using on-demand delivery”一节） | 2026-11-25 | A（官方文档） |
| E3 | ReLinker：Maven Central `com.getkeepsafe.relinker:relinker`；`ReLinker.recursively().loadLibrary()` 按 DT_NEEDED 递归加载；支持版本指定与强制重链 | https://raw.githubusercontent.com/KeepSafe/ReLinker/master/README.md ；https://medium.com/keepsafe-engineering/relinker-v1-2-is-here-b8f1799e22fe | 2026-11-25 | A（上游 README） |
| E4 | 运行时下载代码无完整性校验 = CWE-494（OWASP MASWE-0049）；缓解：可信来源 + 完整性校验 + 私有目录存放 | https://gulertech.co.uk/mobile/maswe-0049-unsafe-dynamic-code-loading/ | 2026-11-25 | B（OWASP 方法论文档，转述 MASWE 要求） |
| E5 | APK Expansion Files 由 Google Play 托管；新应用须用 App Bundle（2021 年 8 月起）；TV 更新自 2023 年 6 月 30 日起只接受 AAB | https://developer.android.com/google/play/expansion-files ；https://support.google.com/googleplay/android-developer/answer/2481797 | 2026-11-25 | A（官方文档） |
| E6 | 自托管可下载内容有先例（游戏引擎 LiveUpdate：HTTP(S) 自建服务器 + 客户端拉取），但那是资产不是原生代码；未找到侧载视频 App 自托管原生模块的可信先例 | https://forum.defold.com/t/apk-expansion-files/195?page=2 | 2026-11-25 | C（论坛讨论，仅作“自托管下载可行”的旁证） |
| E7 | 仓库事实：MPVLib 加载链、VlcPlayer 构造、Sherpa 模型目录与校验、ExoDv5Native 探针、AssNative 懒加载、NodeLib 运行时下载、PlayerSetting 可用性门、Gradle module 结构、fileTree 本地 AAR、版本 560/5.6.0 | 仓库内 grep + 读码（文件路径见“现状映射”各表；HEAD `5e692eb5`） | 2026-11-25 | A（本地源码） |
| E8 | 体积事实：213MB 构成、`.so` 174.3MB、各件大小、4 套 FFmpeg 约 60MB、R8/shrinkResources 已开、按需下载无先例 | `~/workspace/apk-size-analysis/REPORT.md`（2026-11-25 实测） | 2026-11-25 | A（本地实测） |
| E9 | 决定变更：17:39 VLC 随包走（`docs/vlc-player-kernel.md` 用户决定栏）；18:34“模块化，模块化”；18:35“VLC 也拆”（用户原话，MEMORY.md 已记） | 用户消息 + `docs/vlc-player-kernel.md` + `~/MEMORY.md` | 2026-11-25 | A（用户原话） |

### 来源类别说明（按 Skill 证据标准）

- 已覆盖：官方平台文档（E1/E2/E5）、上游项目 README（E3）、安全方法论（E4）、本地源码与实测（E7/E8）、用户原话（E9）。
- 未覆盖且已记录原因：学术论文（无相关算法/并发主张，不适用）；第三方插件框架的同类 App 落地案例——搜索后未找到可信的、在侧载视频 App 里跑通的原生 `.so` 插件先例（Kodi 插件是 Python/zip 资产级，不加载第三方 `.so`；VirtualApp/Atlas 类框架或停更、或为多开场景设计），故档 C 的“不推荐”依据是“无先例 + 框架风险”，而非某个反例；VLC 4.0 的模块化影响（本次不升级 VLC，不适用）。
- 研究停止点：核心决策问题（Play 路线是否可用、自建下载的技术可行性、安全底线、模块划分与试点）已有 Grade A/B 证据且相互印证；剩余不确定性均为实施时核查项，已列入“未验证”清单，不再扩展搜索。

## 决定补充记录（2026-11-25 19:26，用户四连发原话）

- “这个模块化能不能让我实验室那样可以选择本地导入，本地导入就是下好的压缩包导入” + “这样操作可以吗？” + “直接一步到位，然后就是设置里边有一项模块” + “在线下载或者本地导入两个选项，在线下载就是直接连接到那个仓库里边”。
- 含义：模块获取方式两种——**在线下载**（直连本仓库 GitHub release 的模块 zip）或**本地导入**（用户自己下好的压缩包，像实验室导入本地包一样导进来，SAF 文件选择器选 zip）；**一步到位**：不按 Stage 1–5 分试点做了，6 个模块一次全做、一次发版；设置里单独一项**“模块”**（模块管理页）。
- 本地导入的 zip 与在线下载的 zip 是同一格式（manifest.json + .so 文件），导入时同样校验 manifest（ABI、appVersionCode 范围）与每个文件的 sha256，不合格拒绝安装。
- 实施：模块管理器 = 下载 + 本地导入 + 校验 + 私有目录 + 加载，两条获取路径共用同一套校验/安装逻辑。

## 打包补充记录（2026-11-25 19:29，用户原话）

- “你拆好了之后，给他们每个都生成一个压缩包，然后就是在线导入，就连接仓库里面的模块，然后有一个总的压缩包，剩下的就是分叉压缩包吧”“就是每个功能一个压缩包，然后再弄一个总总的压缩包”。
- 含义：**每个功能一个压缩包**（6 个模块各一个 zip，按 ABI 分，各含自己的 manifest.json + 该模块文件）+ **一个总压缩包**（modules-all-<abi>-<versionCode>.zip，内含 6 个分包 zip + 一份总清单 total-manifest.json，总包本身不重复放 .so，只做分包的容器）。
- 在线导入连接仓库里的模块：release 附件里既有 6 个分包也有总包；在线下载时用户二选一——只下这一个模块的分包，或下总包一次装全。
- 本地导入：分包和总包都能导；导总包时管理器先解出内层分包，再按分包逐个走同一套校验/安装逻辑，某个分包校验不过只跳过/报错该分包，不影响其他分包。
- CI 打包产出：每 ABI 一套 = 6 个分包 + 1 个总包，随 release 一起发。

## 提示补充记录（2026-11-25 19:31，用户原话）

- “在那个设置里面好，比如说，选到MPV之后不是没有下载那个吗？就提示请到模块里面下载”。
- 含义：设置里选播放器内核（MPV / VLC / IJK）时，若该模块未安装，弹提示；文案用用户原话“请到模块里面下载”，带模块名，如“请到模块里面下载 MPV”；提示里带“去下载”/“打开模块”按钮，点它直接打开设置“模块”页（最好直达该模块那一行）；播放器内切内核与 Sherpa/DV/LUT 功能入口遇到模块未安装时，同样模式提示；用户取消提示后按原有回落走（内核回落 EXO、功能置灰），不闪退、不静默失败、不反复弹。

## 加速补充记录（2026-11-25 19:32，用户原话）

- “他这个软件里边不是有个加速设置吗？就是让这个加速设置下载模块的时候也能生效”。
- 含义：那个“加速设置”是现有的 GitHub 代理（`utils/GithubProxy.java`，pref `github_proxy` / `github_proxy_mode`；NodeLib 下载 Node 运行时就是走它，`NodeLib.java:98` 的 `GithubProxy.apply(origin())`）。模块管理器的在线下载（GitHub release 的模块 zip / 总包）必须同样走 `GithubProxy.apply(url)`，用用户配好的代理源/模式下载；代理失败照 NodeLib 的做法回退直连，直连也失败才报下载失败；本地导入是本地文件，不走代理。

## 用户决定栏（已批，2026-11-25 19:26）

- 推荐方案（档 B，自建按需下载 .so）：☑ 同意实施，并补充本地导入与一步到位（见“决定补充记录”）
- 试点模块：不适用——一步到位，6 个模块一次全做（mpv、VLC、Sherpa、DV、ijk、LUT）
- VLC 进模块时间点：一步到位，本次就拆
- 模块放哪：GitHub release 附件（在线下载直连本仓库 release）
- 基础包目标体积：全量约 75MB
- 下载体验：首次使用弹下载提示确认；获取方式二选一（在线下载 / 本地导入）
- 老版本用户升级：首次使用时下载/导入（缺失即提示，不静默）
- 下载失败的回落：功能不可用 + 明确提示 + 回落 EXO/置灰
- 模块管理入口：设置里单独一项“模块”
- 未决问题 1–8 的答复：按上面已批内容执行；TV 端模块管理页尽量与手机同款，若 TV 设置结构不同则只保证回落行为
- 批准人 / 日期：用户 / 2026-11-25 19:26

## 首编失败修复（run 45625164694，2026-11-25 21:58）

首编 4 个 build job + 测试 job 全挂，两处独立根因（workflow 本身合法，YAML 解析通过）：

1. **测试 job 编译失败**：`ModuleDownloaderTest.java:38/:47` 的 `request.getPath()` 与 `request.getHeader("Range")` 在 mockwebserver 5.4.0（`gradle/libs.versions.toml:59` 的 `okhttp = "5.4.0"`，`app/build.gradle:271` 的 `testImplementation libs.okhttp.mockwebserver`）上不存在（`cannot find symbol`）。修法：只改测试，不改生产代码——按官方 5.x 文档的 Dispatcher 示例改用 `request.getUrl().encodedPath()`（本测试 URL 无 query，与旧 getPath() 等价，另加 getUrl() 为空的兜底），`request.getHeaders().get("Range")`（语义同旧 getHeader：首个值或 null）。commit `95504b36`，tag `recovery/module-fix-test-api/20261125215826-95504b36f0ff`。
2. **4 个 build job 全挂在 packageModules**：`findCmakeDoviSo` 在 `build/intermediates/cmake/**/obj/<abi>/` 下找不到 `libexo_dovi_renderer.so`。CI 日志实测两点：① CMake 任务实际叫 `buildCMakeRelWithDebInfo[arm64-v8a]`（按 CMake 构建类型 + ABI 命名，不含 variant 名），旧 `dependsOn(tasks.matching { startsWith("buildCMake") && contains(variantCap) })` 匹配不到任何任务，依赖是空操作；② buildCMake 与 mergeNativeLibs 明明都在 packageModules 之前跑完，`intermediates/cmake` 下仍无该 .so。修法：dependsOn 改按真实任务名匹配（`startsWith("buildCMake") && endsWith("[${abi}]")`），另加 `dependsOn("mergeMobile${flavorCap}ReleaseNativeLibs")` 保证合并产物已落盘；`findCmakeDoviSo` 先找 CMake 原始产物，找不到再兜底找 `intermediates/merged_native_libs/**/out/lib/<abi>/` 的合并产物（同样未 strip，是同一份 .so 的逐字节拷贝，zip 内容不变；优先取 mobile variant，与 CI“只从 mobile 任务收集模块 zip”口径一致）；两处都没有时抛错改为列出两个搜索位置，便于下次定位。commit `b82d38bc`，tag `recovery/module-fix-cmake-dep/20261125220009-b82d38bc29f2`。
