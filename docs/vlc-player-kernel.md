# VLC 播放内核评审（vlc-player-kernel.md）

> 状态：**评审中（assessment only）**，2026-11-25。只做设计评审，未写任何业务代码、未改构建文件、未提交。
> 审批前置：按 `AGENTS.md §7/§8` 与 `.codex/skills/upstream-integration-governor/SKILL.md`，本任务是新增原生播放器依赖（material new player/dependency requirement），必须先有决策就绪的评审并经用户批准，才能动工。
> 文档命名说明：父任务指定本文档为 `docs/vlc-player-kernel.md`；按 Skill 惯例应为 `docs/<TASK-ID>-<slug>.md` 并挂入总评估索引。本任务不是上游合并（无上游 commit 可枚举），暂不占用 `E*/P*/C*` 编号；建议批准实施时在 `docs/upstream-player-dependency-merge-assessment-2026-11-08.md`（最新总评估）中分配一个 `C*`（common）编号并链接本文档。

## Recovery anchor（恢复锚点）

- **目标**：把 VLC（libvlc）作为第五个播放内核接进墨影视，与 EXO/IJK/SYSTEM/MPV 并列，用户可在设置里选、可在播放器里切、按线路记住（History.kernelByFlag），默认内核不变。
- **当前状态**：Stage 1 已实现并提交（见“Stage 1 实施记录”），等待 GitHub 构建 + 发布验证。
- **验收标准**：见“验收标准”一节（手机可测条目）。
- **当前文件/符号**：见“Stage 1 实施记录”。
- **未验证项**：见“未验证 / 需实施时验证”清单（HTTP 任意头、双字幕、PiP 下 Surface 存活、VLC 与 mpv 的 .so 符号共存、TV 端 surface 链路、实施后包体实测值）+ Stage 1 实施记录里的“待真机验证”。
- **唯一下一步**：GitHub 构建跑完 → 验发布（4 个 APK + 包体实测）→ 补文档里的 run/发布号。

## 要实现的实际能力

给播放器加第五个内核 **VLC**：

1. 设置页“播放器内核”多一个 `VLC` 选项；播放器内可切到 VLC；按线路记住的选择（History.kernelByFlag）能存 VLC。
2. VLC 能播现有解析链吐出的地址：直链、HLS、DASH、RTSP（以 mpv/Exo 已能播的为基线），带 Referer/User-Agent。
3. 基础播放控制全通：播放/暂停、seek、倍速、音轨/字幕轨切换、外挂字幕、画中画、后台音频、切集/切线路/切站源的自动容错链路（现有 onError 换线/换站流程复用）。
4. 不支持的能力明确置灰（与现有 `pic_unavailable_kernel` / `pic_unavailable_output_mode` 同款做法）：B6 画面调节 + Anime4K、LUT、DRM 内容。
5. 默认内核不变（EXO），VLC 只做可选项；包体增长约 +46MB/ABI（见包体一节）。

## 当前项目已有实现（精确到文件）

### 播放器抽象层

| 构件 | 文件 | 说明 |
|---|---|---|
| 引擎接口 | `app/src/main/java/com/github/moyingshi/osc/tv/player/engine/PlayerEngine.java`（388 行） | `start(PlaySpec)`、`release()`、`setTrack/resetTrack`、`setMetadata`、`isLive/isVod`、`supportsVideoEffects/setVideoEffects`、`supportsNativeLut/setNativeLutShader`、`supportsLut`、`supportsPictureAdjust/setPictureAdjust`、`supportsAnime4k/setAnime4kShader`、`supportsSubtitleStyle/setSubtitleStyle`、`supportsSecondarySubtitle/setSecondarySubtitleTrack`、`getErrorMessage(PlaybackException)`、`getPlayer()`（返回 Media3 `Player`）、`getDecode/setDecode`、`getDroppedFrames`、诊断方法 |
| 编排器 | `app/src/main/java/com/github/moyingshi/osc/tv/player/PlayerManager.java`（9914 行） | 持有 `engine: PlayerEngine` + `player: Media3 Player`；构造 `:421` `buildEngine(playerType, HARD)`；`buildEngine` switch 在 `:6382`（IJK/SYSTEM/MPV/default EXO）；切内核重建在 `:2359/:2384/:2457/:2485/:2512/:2591/:4521`，重建后回调 `callback.onPlayerRebuild(player, resetVideoSurface)`；`player.` 用了 225 次 vs `engine.` 161 次——**Media3 Player 接口是承重面**，新引擎必须交出一个 Media3 `Player` |
| 播放规格 | `app/src/main/java/com/github/moyingshi/osc/tv/player/engine/PlaySpec.java`（212 行） | `url`、`headers: Map<String,String>`、`subs`、`drm`、`format`、`key`、`metadata`、`parseResult`、`playbackRoute`、`playbackTraceId`；`from(Result, key, metadata)` / `fromParse(...)` |
| 四个现引擎 | `player/engine/ExoPlayerEngine.java`、`MpvPlayerEngine.java`（1206 行）、`IjkPlayerEngine.java`、`SystemPlayerEngine.java` | 各自 `getPlayer()` 返回内部 Media3 `Player`（Exo 返回 ExoPlayer；mpv/ijk/system 返回各自 SimpleBasePlayer 包装） |
| mpv 的 Media3 包装（VLC 的直接先例） | `app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java:97` | `public final class MpvPlayer extends SimpleBasePlayer implements MPVLib.EventObserver, MPVLib.LogObserver`——把非 Media3 的播放器（mpv JNI）包成 Media3 `Player`。VLC 要做同款：`VlcPlayer extends SimpleBasePlayer` 包 `org.videolan.libvlc.MediaPlayer` |
| mpv 启动链路 | `MpvPlayerEngine.start(PlaySpec, position, playWhenReady)`（`MpvPlayerEngine.java:182-194`） | `ExoUtil.getMediaItem(spec, decode)` → `player.setMediaItem(item[, position])` → `prepare()` → `play()/pause()`。VLC 版要把 PlaySpec 翻译成 `Media(libvlc, uri)` + media options |

### 内核选择链路

| 构件 | 文件 | 说明 |
|---|---|---|
| 内核常量 | `app/src/main/java/com/github/moyingshi/osc/tv/setting/PlayerSetting.java:15-19,25` | `EXO=0, IJK=1, SYSTEM=2, MPV=3, NONE=-1`；`KERNEL_ORDER = {EXO, IJK, MPV, SYSTEM}`。第 22 行注释：**常量数值是持久化值（也是数组下标），不能重排** → VLC 只能追加为 `4`，并进 `KERNEL_ORDER` |
| 全局默认 vs 运行期 | `PlayerSetting.java:90-117` | `getPlayer()` 读 Prefers `"player"`（默认 EXO）= 设置页全局默认；`getActivePlayer()` 读内存 `volatile activePlayer`（PlayerManager 建引擎/切引擎时写，退出播放清空）= 当前实际内核。运行期一律读后者 |
| 合法性 | `PlayerSetting.java:185-199` | `isPlayer()` / `sanitizePlayer()`（非法 → EXO）/ `resolvePlayer()` / `isPlayerAvailable()`（`isIjkAvailable()` 目前恒 `true`，无真实可用性门——VLC 不需要可用性门，AAR 双 ABI 都有） |
| 内核名数组 | `app/src/main/res/values/strings.xml:1998`、`values-zh-rCN/strings.xml:1946` | `select_player_kernel` = `[EXO, IJK, System, MPV]`（中文：EXO/IJK/系统/MPV），下标 = 内核常量值。加 VLC 要在两处数组第 5 位（下标 4）同步加 `VLC` |
| 设置页入口 | `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SettingPlayerFragment.java:107,186,222` | `:107` 按 `getPlayer()` 显示内核名；`:186` `PlayerKernelDialog.show(...)` 选内核后 `putPlayer(which)`；`:222` 有 MPV 专属行可见性先例——VLC 专属行（如硬解开关）可照此做 |
| 按线路记内核 | `app/src/main/java/com/github/moyingshi/osc/tv/bean/History.java:135,137,841-884`；`db/Migrations.java:257` | `kernelByFlag` TEXT 列，JSON `Map<flag, kernel:int>`。存的是 int，VLC=4 无需改结构；`kernelByFlagMap()` 解析失败即空 map，旧数据安全 |
| 切内核与容错 | `PlayerManager.java` 构建/重建点（上表）+ `VideoActivity.onError`（自动换线 → 自动换站，`PlayerSetting.isAutoChange()` 控制） | 容错在 PlayerManager/VideoActivity 层，与引擎无关——VLC 只要把错误按 `getErrorMessage()` 映射成现有 `PlaybackException` 语义，即可复用 |

### mpv 接入方式（最近似的新增内核先例）

| 环节 | 现状 | 对 VLC 的启示 |
|---|---|---|
| .so 进包 | **assets 而非 jniLibs**：`app/src/arm64_v8a/assets/mpv-libs/`（45MB：`libmpv.so` 17.8MB + `libmvcodec.so` 16.5MB + `libmvformat/libmvfilter/libmvutil/libmw*` + `libc++_shared.so` + `libplayer.so` 96KB）；`app/src/armeabi_v7a/assets/mpv-libs/`（39MB）。运行时 `is/xyz/mpv/MPVLib.java:28,71` 从 assets 解到应用私有目录 `app_mpv-libs/` 再加载 | VLC 不必学这套。libvlc-all 是标准 AAR，`.so` 走 jniLibs、Gradle 按 abiFilters 打包，更简单。但要注意：mpv 这样做是因为源码自编译 + 命名空间隔离（`libmv*`/`libmw*` 与 Exo 的 `libav*`/`libsw*` 分离，见 player-gates §4）。VLC 的 `libvlc.so` 是单体文件，内部静态链接了自己的 FFmpeg，符号默认隐藏——与 mpv/Exo 的 FFmpeg 直接冲突风险低，实施时须以 `readelf` 验证（`scripts/verify_mpv_native_assets.sh --require-elf` 同款检查） |
| 源码与锁 | `third_party/mpv-native-lock.json`（builder：`github.com/FongMi/mpv-android` commit `99a60ad2141d5ace94453590903c2c6b9a0a2443`；ndk r29/api 24；meson 1.11.2/ninja 1.1.1.1；各源码 sha256）；`scripts/build_mpv_native.sh`、`scripts/build_mpv_player_jni.sh`；`third_party/mpv-native-build.md`、`mpv-libmpv-integration-notes.md`；JNI 桥 `third_party/mpv-player-jni/` + `app/src/main/cpp/CMakeLists.txt`（`libplayer.so`） | mpv 是源码编译链（因为 FongMi 分支要打补丁）。VLC 无补丁需求时不建源码链，AAR 即可；若日后要改 VLC 行为，再评估源码链 |
| 构建 | `app/build.gradle`：flavors `mode(leanback/mobile) × abi(arm64-v8a/armeabi-v7a)`，`ndkVersion "29.0.14206865"`，`minSdk 24 / targetSdk 28 / compileSdk 37`（`gradle/libs.versions.toml:7-9`），Media3 `1.11.0-alpha01-fongmi`，`nextlib-media3ext`；`externalNativeBuild cmake src/main/cpp/CMakeLists.txt`；`sourceSets.main.jniLibs.srcDir(third_party/exo-ass-native/prebuilt)`；产物名 `${mode}-${abi}.apk`；`implementation fileTree(dir: "libs", include: ["*.aar", "*.jar"])` | 加 VLC = `gradle/libs.versions.toml` 加 `libvlc-all = "3.7.8"` + `app/build.gradle` 加 `implementation libs.libvlc.all`。**没有“按需下载内核 .so”的先例**（build.gradle 与 assets 布局已核实：mpv 随包走）——VLC 也随包走，除非用户另定按需下载 |
| 测试 | `app/src/test/.../player/` 55 个文件（含 `PlayerManagerTest.java`、`PlayerManagerLifecycleSourceTest.java`、`PlayerManagerSwitchCauseSourceTest.java`、`LiveSourceFallbackPolicyTest.java`、`IntroSkip*Test.java`）；`app/src/test/.../androidx/media3/mpvplayer/` 51 个文件（全是 Mpv* policy 单元测试） | 加内核后受影响：PlayerManager 切内核测试（加 VLC case）、kernel 常量/数组下标测试（若有）、`select_player_kernel` 长度断言（若有）。新增：`VlcPlayerEngineTest`（SimpleBasePlayer 状态机单元测试）、`VlcPlayerStateTest`（surface attach/detach、错误映射）。注意：现有引擎测试全是 JVM 单元测试，**没有真机播放测试**——VLC 的行为验证只能靠真机（见验收标准） |

### 横切能力：新内核必须接什么

| 能力 | 现状与位置 | VLC 接法 / 缺口 |
|---|---|---|
| 字幕（内嵌/外挂/ASSRT/双字幕） | `subtitle/` 包（`SubtitlePlaybackSession`、`SubtitleInjector`、`SubtitleAutoController`、ASSRT 源 `AssrtSubtitleProvider` + `SubtitleProviderRegistry`）；引擎层 `PlayerEngine.supportsSubtitleStyle/setSubtitleStyle`、`supportsSecondarySubtitle/setSecondarySubtitleTrack`；Exo 专属 ASS 会话（`PlayerManager:545-549` 只取 `ExoPlayerEngine`） | VLC 内嵌字幕：`getSpuTracks()/setSpu()` 选轨；外挂字幕：`MediaPlayer.addSlave(Media.Slave.Type.SUBTITLE, uri)`（2016 年起替代 `setSubtitleFile`，见证据）；字幕样式：libvlc 3.x 的 freetype 样式在实例创建时固定，用 media options `:freetype-rel-fontsize/:freetype-color/:subsdec-encoding` 调；**双字幕（第二字幕轨）VLC 3.x 原生不支持**（单 SPU 选择）→ 缺口：Stage 3 再定（自定义 overlay 渲染，或延期）。ASS 字幕：libvlc 内置 libass，可播 |
| 弹幕 | `player/danmaku/`（`LiveDanmaku*`、`DanmakuUrlPolicy` 等）+ `media3-ui-danmaku`；弹幕画在视频 Surface 之上的 overlay，与引擎解耦 | 大概率零引擎工作（overlay 不依赖解码器），实施时验证即可 |
| 画中画 | `utils/PiP.java`（`setAutoEnterEnabled` 自动进画中画）+ `PlaybackActivity`；已知有 | VLC 渲染目标是 Activity 的 SurfaceView，PiP 是 Activity 级行为，理论上直接可用；须真机验证（旋转/PiP 进出时 `detachViews/attachViews` 不黑屏）。VLC 4.0 NEWS 只提了 macOS/iOS/tvOS 的 PiP drawable，**Android PiP 无官方说明**，列为验证项 |
| 跳过片头片尾 | `player/IntroSkipPlayback.java` + `player/IntroSkipService.java`（分段计划、确认跳过），位置驱动 seek | 引擎无关（走 PlayerManager seek），零引擎工作，验证即可 |
| B6 画面调节 + Anime4K | `PlayerEngine.supportsPictureAdjust/setPictureAdjust`、`supportsAnime4k/setAnime4kShader`、`supportsNativeLut/setNativeLutShader`；LUT 在 `player/lut/` | **三项 VLC 全不支持**：画面调节可用 VLC `adjust` 视频滤镜近似（亮度/对比度/饱和度/色相/伽马，`--video-filter=adjust` + 选项），但不是同一套参数，需单独评估是否值得接；Anime4K 是 GLSL shader，VLC 无等价物；LUT 无等价物。默认方案：VLC 下三项按现有置灰模式处理（`PlayerManager:1039-1051` 的 `pic_unavailable_kernel` / `pic_unavailable_output_mode` 同款文案），Stage 4 再议 adjust 滤镜 |
| 倍速/手势 | 走 Media3 `Player.setPlaybackSpeed()` + `PlayerGesture`；`PlayerManager` 层 | 适配器必须实现速度控制（libvlc `MediaPlayer.setRate()`）。手势层不动 |
| 下载 | `download/`（`VideoDownloadManager`、`DownloadTask` 等）**零处引用 `PlayerManager`/`PlayerEngine`**（grep 核实） | 下载走解析链，与内核无关。零工作 |
| TV 端 | `app/src/leanback/.../VideoActivity.java`、`LiveActivity.java`、`CastActivity.java`、`DLNARendererService.java` 均用 `PlayerManager`——与手机共用同一套抽象 | 引擎层一次接入，TV 理论上直接可用；但 leanback 的 surface/焦点/遥控链路不同，TV 列为 Stage 3 验证项（是否进 Stage 1 由用户定） |
| DRM | `PlaySpec.drm`；`PlayerManager.rejectUnsupportedDrm()` | libvlc 无 Widevine CDM 集成 → DRM 内容对 VLC 不可用，沿用现有拒绝路径（切回 Exo），不新增逻辑 |
| 直播 | `LiveSourceFallbackPolicy`（直播换线/换站）+ `LiveActivity` | 引擎无关，复用。VLC 播直播（HLS/RTSP）能力随 Stage 1 验证 |
| 后台音频 / MediaSession | `PlayerManager` 内 `mediaSession`（构造即 `begin(0L)`）+ `service/PlaybackService.java`（`onPlayerRebuild` 转发） | 适配器须正确上报播放状态（Media3 `Player` 状态机），否则通知栏/耳机线控会错乱。这是 SimpleBasePlayer 适配器最容易出错的部分，单列验收项 |

### Surface 链路（最关键的设计点）

- 手机/leanback 的 `PlaybackActivity.onPlayerRebuild(Player, resetVideoSurface)`（`PlaybackActivity.java:1145`）与 `PlaybackService.onPlayerRebuild`（`:766,890`）负责在引擎重建后把新 `Player` 交给 UI；UI 层（PlayerView）调 Media3 `Player.setVideoSurface(Surface)`。
- libvlc 侧的对应物是 `IVLCVout`：`setVideoView(SurfaceView)` / `setVideoSurface(Surface, SurfaceHolder)` / `setSubtitlesView/setSubtitlesSurface` + `attachViews()/detachViews()`（主线程调用，有 `SURFACE_STATE_INIT/ATTACHED/READY` 状态机；2015 年 `AWindow: fix surface states` 与 2019 年 `VideoHelper: Don't attach vout if layout is not ready` 都是修这块的竞态）。
- 因此 `VlcPlayer extends SimpleBasePlayer` 必须实现：`Player.setVideoSurface(surface)` → `IVLCVout.setVideoSurface(surface, null)` + `attachViews()`；surface 销毁/重建（旋转、PiP、切内核）→ `detachViews()` 再 `attachViews()`；`onNewVideoLayout` 回调 → 上报视频尺寸（供 UI 的 aspect 处理）。官方示例在 `code.videolan.org/videolan/libvlc-android-samples`（`VLCVideoLayout` 为推荐封装）。
- 经典坑（有案可查）：“只有声音没有画面”（surface 未 attach 就 play，StackOverflow/TV 应用案例）与“画面只占 Surface 一小块”（未调 `setWindowSize` / 未处理 `onNewVideoLayout`）。见证据索引。

## 方案对比

### 档 0：不做

- **收益**：零包体增长、零维护成本、零 LGPL 合规工作。
- **缺点/风险**：用户已明确要 VLC 内核；个别 mpv/Exo 播不了的格式（VLC 解码面最广，含部分老旧/小众容器与网络协议）仍无备胎。
- **兼容性/性能/包体**：无变化。
- **维护**：无。
- **结论**：仅作对照档。用户已否决（2026-11-25 明确“加进来，做个新内核”）。

### 档 A（推荐）：AAR `org.videolan.android:libvlc-all:3.7.8`（Maven Central 稳定版）

- **版本**：`3.7.8`（2026-11-24 发布，Maven Central；`3.7.7` 为 2026-10-01）。**不用 `4.0.0-eap29`**（EAP 预览，API 大改：构造器强制传 instance、删 deprecated API、minSdk 23、AImage/ASurfaceControl 新输出路径——等 VLC 4 正式版再评估）。
- **产物**：AAR 88.3MB（含 armeabi-v7a / arm64-v8a / x86 / x86_64 四 ABI 的 `.so` + Java 绑定 + JNI）。Gradle 的 `abiFilters` 会使每个 APK 只含一个 ABI；arm64-v8a 的 `libvlc.so` 实测约 45.9MB（LeeCat 包内同名文件实测值，可作参照）。
- **最低 SDK**：libvlc 3.x AAR 声明 `minSdk 17`；本项目 `minSdk 24`，无冲突。
- **API**（3.7.x，Java 绑定 `org.videolan.libvlc`）：`LibVLC(Context, options)`（应用内建议单例）、`Media(libvlc, Uri)` + `addOption(":http-user-agent=…")` / `":http-referrer=…"`、`MediaPlayer(libvlc)` + `setMedia/play/pause/stop/setRate/getTime/setTime`、`getVLCVout()`（`IVLCVout`）、`getSpuTracks/setSpu`、`getAudioTracks/setAudioTrack`、`addSlave(SUBTITLE, uri)`、`Media.setHWDecoderEnabled(enabled, force)`（带设备黑名单的 `HWDecoderUtil`）、`MediaPlayer.EventListener` 事件。
- **License**：LGPL 2.1（Maven Central 标注）。本项目自身是 GPLv3（`LICENSE.md`）——LGPL 库与 GPL 应用兼容，无冲突。合规义务（VideoLAN wiki `LibVLC` 页，Grade A）：(1) 动态链接（AAR 的 `.so` 由 `System.loadLibrary` 动态加载，满足）；(2) 不修改 VLC 核心/插件，或修改后重新共享修改；(3) 只用 LGPL 插件（AAR 默认即如此）；(4) 分发时附 LGPL 文本并提供获取对应源码的途径（链接 VideoLAN 源码 + 记录所用版本即可；2026-10 的 libvlcjni issue #21 显示连 VideoLAN 自己都被追问过对应源码——实施时把版本 + 源码链接写进 About/NOTICE，不要只贴个版本号）。
- **收益**：无原生编译（本项目无本地 SDK/NDK，GitHub Actions 是唯一构建——源码编译路线与之根本冲突）；版本可钉死；与 Exo/nextlib 的 Maven 接入方式一致；升级 = 改一行版本号。
- **缺点/风险**：(1) AAR 是黑盒二进制：出问题只能等上游修或自己绕；（2）88.3MB 的 AAR 下载进 CI 缓存，构建时间 +几分钟；（3）VLC 4 迟早要升，届时 API 迁移；（4）HTTP 任意头受限（见未验证项）；（5）双字幕、Anime4K、LUT 无原生支持。
- **包体**：每 ABI 约 +46MB（arm64-v8a 实测参照 45.9MB；armeabi-v7a 待实测，预计 ~40MB）。APK 内 `.so` 以 stored（不压缩）存放，下载体积基本等额增加。
- **维护**：跟随上游发版（约 1-2 月一个小版本）；只做版本钉死 + 回归，不碰源码。

### 档 B：源码编译 VLC-Android（vlc-android 仓库）

- **做法**：Linux 机器，`git clone https://code.videolan.org/videolan/vlc-android.git`，`buildsystem/compile.sh -l -a arm64 -r` 出 AAR（`libvlc/build/outputs/aar/`）；contrib 预编译包 ~166MB/ABI；冷编译 1-2 小时；需 autotools/cmake/NDK r29 等全套工具链。
- **收益**：完全控制（可裁剪模块、可打补丁、可出 LGPL 对应源码的完整证据链）；与 mpv 的源码编译链同款治理（lock 文件 + 构建脚本 + 哈希）。
- **缺点/风险**：(1) **与本项目构建现实冲突**：本地无 Android SDK/NDK，GitHub Actions 是唯一编译检查，源码编译要把整条 VLC 工具链搬进 CI（耗时、缓存、失败面都大）；(2) 维护成本与 mpv 链相当（mpv 已有一条源码链在跑，再养一条 VLC 链）；(3) 除非要改 VLC 行为，否则控制权无实际收益。
- **包体**：与 AAR 档相同量级（可裁剪模块省几 MB，不值得）。
- **License**：合规证据链最完整，但 AAR 档 + 版本记录已能满足 LGPL 要求。
- **结论**：不推荐。仅当未来出现“AAR 无法满足的定制需求”（如必须改解码行为、必须裁剪特定模块）时再评估。

### 档 C：第三方再发行版（mrmaffen/vlc-android-sdk 等）

- **现状**：`de.mrmaffen:libvlc-android:2.1.12` / `vlc-android-sdk:1.9.8`，上游已多年不更新（基于 VLC 2.x/3.0 早期），ABI 停留在 armeabi-v7a/x86 时代，无 arm64-v8a 保障，无安全更新。
- **结论**：不考虑。版本陈旧是硬伤。

### 对照表

| 维度 | 不做 | A（AAR 3.7.8，推荐） | B（源码编译） | C（第三方 SDK） |
|---|---|---|---|---|
| 能播 VLC | 否 | 是 | 是 | 是（旧版） |
| 构建 | — | 改两行 Gradle | Linux + NDK + 1-2h，CI 大改 | 改一行 Gradle |
| 包体/ABI | 0 | +~46MB | +~46MB（可裁几 MB） | +~40MB（旧版） |
| License | — | LGPL 2.1，动态链接 + 附文本 + 源码链接 | 同左，证据链更全 | 同左，版本旧 |
| minSdk | — | 17（项目 24，无冲突） | 同左 | 17 |
| 维护 | 0 | 跟上游版本走 | 养第二条源码链 | 无人维护 |
| 最大风险 | 用户需求落空 | 黑盒 AAR、双字幕/LUT 缺口 | 构建链成本 | 陈旧、无安全更新 |

## 推荐方案

**档 A：`org.videolan.android:libvlc-all:3.7.8`（Maven Central），`VlcPlayer extends SimpleBasePlayer` 适配器 + `VlcPlayerEngine implements PlayerEngine`，`VLC=4` 追加进 `PlayerSetting`，默认内核保持 EXO。**

理由：(1) 唯一与“GitHub Actions 是唯一构建”相容的路线；(2) mpv 的 SimpleBasePlayer 包装是现成先例，风险结构已知；(3) 包体代价与 mpv 相当（mpv arm64 45MB / armeabi-v7a 39MB，VLC arm64 ~46MB）；(4) LGPL 合规成本低（本项目已是 GPLv3）。

## 实施阶段划分（每阶段独立可验证、可回滚）

- **Stage 1 — 最小可用（骨架）**：`libs.versions.toml` 加 `libvlc-all = "3.7.8"`；`app/build.gradle` 加依赖；新建 `player/engine/VlcPlayerEngine.java` + `androidx/media3/vlcplayer/VlcPlayer.java`（SimpleBasePlayer 包装：play/pause/stop/seek/setRate、状态机、错误映射、surface attach/detach）；`PlayerSetting` 加 `VLC=4`、`KERNEL_ORDER` 追加、`isPlayer()` 扩展；两处 `select_player_kernel` 数组加 `VLC`；`SettingPlayerFragment`/`PlayerKernelDialog` 透传；`PlayerManager.buildEngine` 加 `case VLC`；`History.kernelByFlag` 不动（已通用）。验证：mobile-arm64 构建全绿；VLC 播 MP4 + HLS + RTSP 各一；设置里切 VLC 生效；播失败自动换线/换站仍走通；切回 Exo 正常。
- **Stage 2 — 轨道与字幕**：音轨/字幕轨枚举与选择（`getAudioTracks/setAudioTrack`、`getSpuTracks/setSpu`）；外挂字幕 `addSlave`；字幕样式（freetype media options）；ASSRT 字幕源接入验证；HTTP 头验证（UA/Referer 走 media options；任意头若不支持，定降级方案：走现有本地代理或仅 UA/Referer）；DASH 验证。
- **Stage 3 — 横切能力**：PiP 进出、IntroSkip、弹幕 overlay、倍速/手势、后台音频 + MediaSession/通知栏、切集/切线路；TV（leanback）验证（surface + 遥控）；直播（HLS/RTSP）验证。
- **Stage 4 — 降级与收尾**：B6/LUT/Anime4K 在 VLC 下置灰（沿用 `pic_unavailable_kernel` 同款文案）；DRM 内容排除 VLC（沿用 `rejectUnsupportedDrm`）；硬解黑名单 + 软解回退；LGPL NOTICE 文本 + 对应源码链接进 About；版本记录 + 发布测试版。
- **每阶段一个 guard 会话 + 一个原子 commit + recovery tag**；任一阶段失败可单独回滚，不影响其他内核。

## Stage 1 实施记录（2026-11-25，guard 会话 vlc-kernel-stage1）

- **改动文件**（一个原子 commit，见 commit 记录）：
  - `gradle/libs.versions.toml`：`libvlc = "3.7.8"` + `libvlc-all = { group = "org.videolan.android", name = "libvlc-all", version.ref = "libvlc" }`。
  - `app/build.gradle`：`implementation libs.libvlc.all`。
  - 新建 `app/src/main/java/androidx/media3/vlcplayer/VlcPlayer.java`：`extends SimpleBasePlayer`，包 `org.videolan.libvlc.MediaPlayer`；play/pause/stop/seek/setRate/音量、状态机（Opening/Buffering/Playing/Paused/Stopped/EndReached/EncounteredError/TimeChanged/LengthChanged/Vout/ES*）、错误映射为 `VLC_LOAD_FAILED` + `ERROR_CODE_IO_UNSPECIFIED`、Surface 经 `IVLCVout.setVideoSurface/setVideoView + attachViews(this)`（OnNewVideoLayoutListener 回报尺寸）、轨道快照（`getVideoTracks/getAudioTracks/getSpuTracks` → `Tracks`，Format.id 为 `type:vlcId`）、HTTP 头只传 UA/Referer（media options）、硬解按 decode 开关（`setHWDecoderEnabled`）、单循环（repeatOne 时 EndReached 回 0 重播）、`--no-video-title-show` 关片名 OSD。
  - 新建 `app/src/main/java/com/github/moyingshi/osc/tv/player/engine/VlcPlayerEngine.java`：`implements PlayerEngine`，照 IjkPlayerEngine 的薄壳（`ExoUtil.getMediaItem` + setMediaItem/prepare/play；`setTrack` 解析 `type:vlcId` 调 `setAudioTrackIndex/setSpuTrackIndex`，文本轨 disabled → `setSpuTrack(-1)`；`isLive/isVod` 走 `TimelineStreamKindPolicy`；`handleError` 恒 FATAL；`supportsPictureAdjust/supportsAnime4k/supportsNativeLut/supportsSecondarySubtitle/supportsSubtitleStyle` 全部缺省 false，置灰自然生效）。
  - `PlayerSetting.java`：`VLC = 4` 追加（数值是持久化值，未重排）；`KERNEL_ORDER` 追加 VLC（末位，回退顺序 EXO → IJK → MPV → 系统 → VLC）；`isPlayer()`、`useNativeVideoOutput()` 纳入 VLC。
  - `app/src/main/res/values/strings.xml` 与 `values-zh-rCN/strings.xml`：`select_player_kernel` 第 5 位加 `VLC`；新增 `error_play_vlc`（“VLC failed to play this media…” / “VLC 播放该媒体失败，请手动切换内核或更换线路”）。
  - `PlayerManager.java`：`buildEngine` 加 `case VLC -> new VlcPlayerEngine(decode, listener)`（+ import）。
  - `app/src/test/java/com/github/moyingshi/osc/tv/setting/PlayerSettingTest.java`：KERNEL_ORDER / nextPlayer 循环 / orderKernels / nativeVideoOutput 四组断言补 VLC。
  - `app/proguard-rules-media.pro`：`-keep class org.videolan.libvlc.**`（与 ijk 同款，防止 R8 裁掉 JNI 回调方法）。
- **核查后确认不用改的**：`SettingPlayerFragment`（`onKernel` 走 `PlayerKernelDialog` + `select_player_kernel` 数组下标）、`PlayerKernelDialog`（全由 `KERNEL_ORDER`/`kernelAt`/`kernelRank` 驱动）、leanback `SettingPlayerActivity`（同款数组 + 对话框）、`History.kernelByFlag`（存 int，VLC=4 无需改结构）。TV 端因此 Stage 1 即随手机一起进（共用 PlayerManager + 同款入口），待真机验证。
- **libvlc 签名核查**：上游源码站 403 读不到 3.7.8 源码，签名以 3.4.3 官方 Javadoc 核实（`setSpuTrack(int): boolean`、`getVideoTracksCount()`、`addSlave(int, Uri, boolean)`、`IMedia.Slave.Type.Subtitle`、`Media(ILibVLC, Uri)`、`LibVLC(Context, List<String>)`、`IVLCVout` 在 `org.videolan.libvlc.interfaces` 包）；3.7.8 下无逐版 Javadoc，残留风险由 GitHub 构建（唯一编译检查）兜底。
- **验证结果**（构建前）：本地无 JDK/Android SDK，未编译；逐行复核已做（类型、泛型、lambda、XML 属性、跨包可见性——VlcPlayer 供引擎用的方法均为 public）。
- **构建 / 发布**（待填）：run 号 ____；发布版本 ____；mobile-arm64 实测包体 ____（上一版约 170MB，预期 +46MB 级）；构建耗时 ____。
- **待真机验证**（验收标准 1–10 的手机项，本地无法验）：设置里切 VLC 生效；MP4/HLS/RTSP 各一播通无黑屏；暂停/seek/倍速/切集/切线/切站；内嵌字幕与音轨切换；旋转与 PiP 不黑屏；跳过片头片尾、弹幕、后台音频 + 通知栏；B6/Anime4K/LUT 置灰；切回其他内核回归；连续播 3 集、切内核 10 次不闪退。

## 验收标准（手机真机可测）

1. 设置 → 播放器内核出现 `VLC`，选中后下次播放走 VLC；播放器内切内核到 VLC 即时生效；按线路记住 VLC（切线路后仍是 VLC）。
2. VLC 播：本地 MP4、HTTP 直链、HLS（m3u8）、RTSP 各一，声音画面同步，无黑屏。
3. 暂停/续播、seek、前进/后退、倍速（0.5/1.5/2.0）、切集、切线路、切站源全通；播失败自动换线/换站仍触发。
4. 字幕：内嵌字幕轨切换、外挂字幕挂载（srt/ass）、字幕样式大小可调；音轨切换。
5. 旋转不黑屏；进后台自动画中画不黑屏、不静音；回前台恢复。
6. 跳过片头片尾触发；弹幕正常叠加；后台音频 + 通知栏控制正常。
7. B6/Anime4K/LUT 在 VLC 下置灰并有说明文案；DRM 内容不走 VLC（自动回 Exo 或明确提示）。
8. 切回 EXO/MPV/IJK/系统内核，行为与改动前一致（回归）。
9. 包体：mobile-arm64 APK 增长 ≤ 50MB（实测值写入发布说明）；构建时间增长记录。
10. 连续播 3 集不闪退；切内核 10 次不闪退；VLC 释放后无残留声音/通知。

## 回滚路径

- 代码回滚：revert 对应阶段的 guard commit（依赖 + 引擎 + switch case + 常量/数组条目同属一个原子 commit）；`PlayerSetting.sanitizePlayer()` 已保证未知内核值回落 EXO，旧数据（含已存 `player=4`）安全。
- 发布回滚：测试版照常发下一版（VLC 缺省关闭时不影响任何现有用户）；若 VLC 导致启动崩溃，走现有“删功能连测试文件一起清”的做法（见 mooyingshi_upkeep skill）。
- 依赖回滚：删 `libs.versions.toml` / `app/build.gradle` 两行即回到无 VLC 状态；AAR 不留本地产物。

## 未决问题（需要用户拍板）

1. **默认内核**：保持 EXO 默认、VLC 仅作可选项——是否同意？
2. **包体上限**：mobile-arm64 预计 +46MB（170MB 级 → 220MB 级），armeabi-v7a 另 +~40MB——是否接受？是否只做 arm64？
3. **TV 端**：Stage 1 是否包含 leanback，还是手机先行、TV 放 Stage 3？
4. **版本线**：`3.7.8` 稳定版（推荐）还是等/用 `4.0.0-eap29` 预览版？
5. **B6/Anime4K/LUT**：VLC 下置灰是否接受？画面调节是否值得用 VLC `adjust` 滤镜另做一套（Stage 4 再议）？
6. **双字幕**：VLC 原生不支持，第二字幕轨是延期、还是做自定义 overlay？
7. **HTTP 任意头**：若 libvlc 只能传 UA/Referer、个别源要 Cookie/自定义头，是否接受“走本地代理”或“该源不走 VLC”的降级？
8. **按需下载**：是否接受随包走（+46MB），还是要做“首次选 VLC 时再下载 .so”（无先例，需另立项）？

## 未验证 / 需实施时验证

- libvlc 对 PlaySpec.headers 的完整表达：UA/Referer 已确认有 media options；Cookie/任意自定义头待 Stage 2 实测。
- 双字幕在 libvlc 3.7.x 下的可行方案（overlay 渲染 vs 延期）。
- PiP + 旋转 + 切内核时的 Surface 存活（`attachViews/detachViews` 时序）。
- `libvlc.so` 与 mpv/Exo 的 `.so` 共存：`readelf` 查 SONAME/DT_NEEDED/导出符号（实施时跑 `scripts/verify_mpv_native_assets.sh --require-elf` 同款检查）。
- TV 端 surface/焦点链路。
- 实施后包体与构建时间实测值。
- VLC 4.0 正式版发布后是否升级（届时重评，不在本次范围）。

## 证据索引

| # | 结论 | 来源 | 访问日期 | 等级 |
|---|---|---|---|---|
| E1 | libvlc-all 最新稳定版 3.7.8（2026-11-24，AAR 88.3MB，LGPL 2.1）；3.7.7（2026-10-01）；4.0.0-eap29（2026-07-08） | https://mvnrepository.com/artifact/org.videolan.android/libvlc-all/3.7.8；https://libraries.io/maven/org.videolan.android:libvlc-all | 2026-11-25 | A（官方制品库） |
| E2 | vlc-android 以 `libvlcVersion = '3.7.7' / '4.0.0-eap29'` 区分 VLC3/VLC4；VLC3 `minSdk=17`、VLC4 `minSdk=23` | vlc-android `build.gradle`（2026-11-09/11-19 commit 邮件）；lucas-vlc-android `CLAUDE.md`（“libVLC declares minSdkVersion=17，读自 libvlc-all:3.7.0 AAR manifest”） | 2026-11-25 | A（上游源码/构建配置） |
| E3 | LGPLv2 下闭源 App 可用 libVLC：动态链接、修改须共享、建议只用 LGPL 插件、分发分离 | https://wiki.videolan.org/LibVLC/（“libVLC, license and proprietary applications”一节） | 2026-11-25 | A（VideoLAN 官方 wiki） |
| E4 | libvlc-all 用户为履行再分发义务追问对应源码（issue #21） | https://code.videolan.org/videolan/libvlcjni/-/issues/21（2026-10-21/28；mailman 2026-10-30） | 2026-11-25 | B（上游 issue，说明合规义务真实存在） |
| E5 | 源码编译路径：`buildsystem/compile.sh -l -a <ABI> [-r]`；Linux 必需；contrib ~166MB/ABI；AAR 在 `libvlc/build/outputs/aar/` | https://wiki.videolan.org/AndroidCompile/；vlc-android `README.md`（raw.githubusercontent.com/ITAnt/vlc-android/master/README.md） | 2026-11-25 | A（官方 wiki/README） |
| E6 | Android 的 libvlc 是单体 `libvlc.so`（无插件树、无 libvlccore.so） | Godot VLC 扩展文档（store.godotengine.org VLC 资产页） | 2026-11-25 | B（第三方实现文档，转述上游构建事实） |
| E7 | 外挂字幕用 `MediaPlayer.addSlave(SUBTITLE, uri)`（替代旧 `setSubtitleFile`）；字幕样式用 `:freetype-rel-fontsize/:freetype-color/:subsdec-encoding`；倍速 `setRate` | VideoLAN mailman 2016-05-31（commit 03220852）；https://docs.videolan.me/libvlcsharp/docs/how_do_I_do_X.html | 2026-11-25 | A（上游提交记录 + 官方文档） |
| E8 | HW 解码：`Media.setHWDecoderEnabled(enabled, force)` + `HWDecoderUtil` 设备黑名单；默认开 | VideoLAN mailman 2015-06-08（commit 3339fcd0） | 2026-11-25 | A（上游提交记录） |
| E9 | Surface 状态机：`AWindow` 的 `SURFACE_STATE_INIT/ATTACHED/READY`；`attachViews` 前须有 surface；`VideoHelper` “layout 未就绪不 attach” | VideoLAN mailman 2015-06-26（`AWindow: fix surface states`）、2019-02-18（`VideoHelper` commit dc832cf4） | 2026-11-25 | A（上游提交记录） |
| E10 | 经典坑“只有声音没有画面”（surface 未 attach 就 play）、“画面只占 Surface 一小块”（未调 `setWindowSize`/未处理 `onNewVideoLayout`） | StackOverflow 问答镜像（windows-hexerror.linestarve.com，libvlc 3.0.0 TV 应用案例）；mrmaffen/vlc-android-sdk issue #104 | 2026-11-25 | C（复现的第三方报告，可作检查清单，不作定论） |
| E11 | `IVLCVout` 接口：`setVideoView/setVideoSurface/setSubtitlesView/setSubtitlesSurface`、`attachViews/detachViews`、`OnNewVideoLayoutListener` | mrmaffen/vlc-android-sdk `IVLCVout.java`（2015 年接口，与官方 3.x 一致）；VideoLAN mailman 2015-07-02 | 2026-11-25 | B（与官方同源的公开实现） |
| E12 | VLC 4.0 变化：Android 6+（API 23）、删 iomx、AImage/ASurfaceControl 输出、字幕性能改进、删 VLM/deprecated API、`libvlc_media_player_new_from_media` 强制传 instance | VLC `NEWS`（raw.githubusercontent.com/videolan/vlc/master/NEWS，3.0.x→4.0.0-dev 一节） | 2026-11-25 | A（上游 NEWS） |
| E13 | 官方示例：`libvlc-android-samples`（`VLCVideoLayout` 封装） | vlc-android `README.md`（“Have a look at our sample codes”） | 2026-11-25 | A（上游 README） |
| E14 | LeeCat 包内 `libvlc.so` 45.9MB（arm64-v8a 实测参照） | 本机 `~/workspace/drive-incoming/LeeCatVodSimplify-android-mobile-arm64-release.apk` 拆包实测（2026-11-25） | 2026-11-25 | A（本地实测） |
| E15 | 仓库事实：`PlayerEngine` 接口、`PlayerManager.buildEngine` switch、`MpvPlayer extends SimpleBasePlayer`、mpv assets 布局与大小、`PlayerSetting` 常量与数组、History.kernelByFlag、下载零引擎引用、TV 共用 PlayerManager | 仓库内 grep + 读码（文件路径见“当前项目已有实现”各表；HEAD `8ce295f0`） | 2026-11-25 | A（本地源码） |
| E16 | 本项目 GPLv3（`LICENSE.md` 首行 `GNU GENERAL PUBLIC LICENSE Version 3`） | 仓库 `LICENSE.md` | 2026-11-25 | A（本地文件） |

### 来源类别说明（按 Skill 证据标准）

- 已覆盖：上游源码/构建配置（E2/E5/E8/E9）、官方制品库（E1）、官方 wiki/文档（E3/E7/E13）、上游 NEWS（E12）、本地源码与实测（E14/E15/E16）。
- 未覆盖且已记录原因：学术论文（无相关算法/并发主张，不适用）；VLC 的 CVE/模糊测试史（本次不改 VLC 源码、不新增解析面，决策不受影响——若未来走源码编译路线须补）；第三方基准性能对比（VLC vs mpv 解码性能无可信同条件基准，且用户需求是“多一个备胎”而非“更快”，不适用）。
- 研究停止点：核心决策问题（AAR vs 源码、API 可行性、License、包体量级）已有 Grade A 证据且相互印证；剩余不确定性均为实施时实测项，已列入“未验证”清单，不再扩展搜索。

## 用户决定栏（已批，2026-11-25 17:39）

- 推荐方案（档 A，libvlc-all 3.7.8）：☑ 同意实施
- 未决问题 1–8 的答复（用户 2026-11-25 17:39 原话“1同意2接受3一起4稳定5接受6忽略7接受8随包走”）：
  1. 默认内核保持 EXO、VLC 仅作可选项——同意。
  2. 包体 +46MB 接受（手机 arm64 170MB→220MB 级，armeabi-v7a 另 +~40MB，全 ABI 随包走，不只做 arm64）。
  3. TV 端 Stage 1 就一起进（手机 + leanback 同批）。
  4. 版本线：3.7.8 稳定版。
  5. B6/Anime4K/LUT 在 VLC 下置灰——接受。
  6. 双字幕：忽略/延期（Stage 1 不做）。
  7. HTTP 任意头降级（走本地代理或该源不走 VLC）——接受。
  8. 随包走（+46MB），不做按需下载。
- 批准人 / 日期：用户 / 2026-11-25 17:39
