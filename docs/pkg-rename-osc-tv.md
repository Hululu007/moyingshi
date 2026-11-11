# 包名迁移 com.github.moyingshi.osc.tv

任务来源：用户 2026-11-11 18:36 说“把整个仓库换名成这个包名也改成这个，迁移所有代码，断开与之前版本的连接”。新包名 `com.github.moyingshi.osc.tv`。只在本地改、不推、不编，等做完汇报再定。

## Recovery anchor

- 目标：全仓库包名改成 `com.github.moyingshi.osc.tv`；applicationId 两变体（mobile/leanback 共用 defaultConfig，无 per-flavor 后缀）、源码包 `com.fongmi.android.tv` 全部迁移、authority 与硬编码包名全改、全仓库 grep 旧包名零残留（历史文档例外见下）；不做旧版数据迁移。
- 基线：本地 main `e435e251`（设置页+详情页+播放器+首页 chips 改版已推，构建 run 42998181049 在跑那份代码，不受这次改动影响）；工作树干净。
- 进度：调查完成（见“现状清单”）；Phase 1 已完成（applicationId + namespace + Manifest 全限定名 + 工作流测试过滤器 + proguard）；Phase 2 源码迁移、Phase 3 authority/硬编码扫尾+验证、Phase 4 活文档更新均未开始。
- 风险：预编译 `third_party/exo-ass-native/prebuilt/arm64-v8a/libexo_ass.so` 含旧 JNI 符号（`Java_com_fongmi_android_tv_player_exo_ass_AssNative_*`），本机无 NDK 重编不了；下次发版前必须用 `scripts/build_exo_ass_native.py`（NDK 29）重编，否则 Exo ASS 字幕渲染报 UnsatisfiedLinkError。
- 下一步：做完 Phase 1 提交。

## 新旧对照（历史对照，本节例外保留旧包名写法）

| 旧 | 新 |
|---|---|
| applicationId `com.github.mooyingshi.osc` | `com.github.moyingshi.osc.tv` |
| 源码包 `com.fongmi.android.tv` | `com.github.moyingshi.osc.tv` |
| 源码包 `com.fongmi.android.tv.nodejs` | `com.github.moyingshi.osc.tv.nodejs` |
| 源码包 `com.fongmi.quickjs` | `com.github.moyingshi.osc.tv.quickjs` |
| 源码包 `com.fongmi.chaquo` | `com.github.moyingshi.osc.tv.chaquo` |
| namespace `com.fongmi.android.tv` | `com.github.moyingshi.osc.tv` |
| namespace `com.fongmi.android.tv.nodejs` / `.quickjs` | `com.github.moyingshi.osc.tv.nodejs` / `.quickjs` |
| namespace `com.fongmi.chaquo` | `com.github.moyingshi.osc.tv.chaquo` |
| FileProvider authority `${applicationId}.provider` | 自动跟随 applicationId → `com.github.moyingshi.osc.tv.provider` |
| androidx-startup authority `${applicationId}.androidx-startup` | 自动跟随 |

不动：`com.github.catvod.crawler`（catvod 模块 namespace 与源码包）、`com.whl.quickjs.*`（外部 quickjs AAR 的包）、`androidx.media3.*`（vendored 代码的包）、JS 桥名 `window.fongmi` / `fongmiBridge` / `fongmiRemote`（网页桥契约，改了会断）、上游 webhtv 本身的包名（上游代码仍是 `com.fongmi.android.tv`，只在本文档与历史文档里出现）。

## 现状清单（2026-11-11 18:37 调查）

- applicationId 只在 `app/build.gradle:39`（defaultConfig），mobile/leanback 共用，无 per-flavor 后缀；改这一行即改两变体。
- namespace：app `com.fongmi.android.tv`、nodejs `com.fongmi.android.tv.nodejs`、quickjs `com.fongmi.android.tv.quickjs`、chaquo `com.fongmi.chaquo`。
- `com.fongmi.android.tv` 出现在 2836 个文件（java 2480、kt 7、xml 89、md 251、gradle 4、pro 2、yml 1，其余为 cpp/py/脚本）。
- Manifest：基本用相对名（`.App`、`.ui.activity.X`，随 namespace 自动走）；4 处全限定名（main 2 处：`Startup`、`following.FollowingWorkInitializer`；debug 2 处：`AssPrototypeActivity`、`TmdbScrimHostActivity`）；authority 用 `${applicationId}` 前缀，自动跟随。
- JNI：`app/src/main/cpp/exo_dovi_renderer.cpp`（9 个 `Java_com_fongmi_android_tv_player_exo_ExoDv5Native_*`，随 app 构建编译，改源码即可）；`nodejs/src/main/cpp/node_bridge.cpp`（3 个 NodeBridge native，随模块构建编译）；`third_party/exo-ass-native/exo_ass.cpp`（宏 `JNI_METHOD` 生成旧名）+ 预编译 `.so`（见风险）；`third_party/mpv-player-jni/src/iso_dvd.cpp:468`（`FindClass("com/fongmi/android/tv/player/iso/IsoSessionManager")`，mpv-player-jni 从源码构建，改源码即可）。
- 硬编码字符串：`Startup.java` 的 EventBus index、`ReaderTtsService` 的 `com.fongmi.android.tv.tts.*` action、`Class.forName("com.fongmi.android.tv...")`（FollowingActivity、TmdbDetailActivity、RemoteTrustDialog 等）、若干测试里的类名字符串；全部随全局替换走。
- `BuildConfig.APPLICATION_ID` / `getPackageName()` 引用（Updater.validatePackage、ActionEvent、NodeService 等）自动跟随新 applicationId，不用改。代码里零处出现 `mooyingshi` 字符串，即没有任何指向旧包的升级/迁移逻辑。
- 工作流 `.github/workflows/android-release.yml` 的 `--tests` 过滤器用旧包名（须改，否则 CI 里那些测试匹配不到）。
- Proguard：`app/proguard-rules.pro`（含 `com.fongmi.quickjs.method.**`、`com.fongmi.chaquo.**`、`com.fongmi.android.tv.**` 等）、`app/proguard-rules-media.pro`（AssNative）。
- 文档：251 个 md 文件、597 处提到旧包名；其中 dated 历史记录（上游评估、同步回顾、任务文档、修复报告）保留原样——那是写下当时的真实包名，有些还是上游 webhtv 自身的包名，改了等于篡改历史；只更新活文档（README、b-ui/b5/b6/ui-restyle 四个 tracker + 本文档）。
- 底栏：`menu_nav.xml` 6 项，迁移不碰。

## 断开说明

新包名在 Android 上就是一个全新 App：与老版本无法原地升级、旧版数据（设置、历史、收藏、本地源配置）不迁移；老用户需卸载旧版、重装新版。老版 App 检查更新时：beta 渠道会看到新版并下载 APK，但 `Updater.validatePackage` 校验包名（`BuildConfig.APPLICATION_ID` 须等于下载 APK 的 packageName），新旧包名不一致即拒绝安装、删掉下载的 APK、老 App 原样保留；要装新版只能手动从 GitHub 下 APK 安装，与老版并存。稳定渠道不受影响。

## 实施顺序

1. Phase 1：applicationId + namespace + Manifest 全限定名 + 工作流测试过滤器 + proguard + 本文档。
2. Phase 2：源码目录 `git mv` 式迁移 + 全仓库内容替换（code/config；历史 md 与 webhome-devkit 镜像除外；预编译 `.so` 二进制除外）。
3. Phase 3：authority 与硬编码扫尾 + 验证（grep 零残留、XML 解析、底栏 6 项、R/BuildConfig 引用抽查）。
4. Phase 4：活文档更新 + 本文档收尾（结果、验证证据、`.so` 重编提醒）。

## 验证（待填）

- 待填：grep 旧包名结果、XML 解析结果、底栏项数、commit 号。
