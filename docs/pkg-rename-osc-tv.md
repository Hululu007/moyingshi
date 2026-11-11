# 包名迁移 com.github.moyingshi.osc.tv

任务来源：用户 2026-11-11 18:36 说“把整个仓库换名成这个包名也改成这个，迁移所有代码，断开与之前版本的连接”。新包名 `com.github.moyingshi.osc.tv`。只在本地改、不推、不编，等做完汇报再定。

## Recovery anchor

- 目标：全仓库包名改成 `com.github.moyingshi.osc.tv`；applicationId 两变体（mobile/leanback 共用 defaultConfig，无 per-flavor 后缀）、源码包 `com.fongmi.android.tv` 全部迁移、authority 与硬编码包名全改、全仓库 grep 旧包名零残留（历史文档例外见下）；不做旧版数据迁移。
- 基线：本地 main `e435e251`（设置页+详情页+播放器+首页 chips 改版已推，构建 run 42998181049 在跑那份代码，不受这次改动影响）；工作树干净。
- 进度：调查完成（见“现状清单”）；Phase 1 已完成（commit `38ab810b`）；Phase 2 已完成（commit `71283605`）；Phase 3 已完成（commit `42c06784`）；Phase 4 已完成（活文档更新，见“结果”）。全部做完，本地 main `e435e251` → 4 笔新提交，未推、未编，等用户定推不推。
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

1. Phase 1：applicationId + namespace + Manifest 全限定名 + 工作流测试过滤器 + proguard + 本文档。commit `38ab810b`。
2. Phase 2：源码目录迁移 + 全仓库内容替换（code/config；历史 md 与 webhome-devkit 镜像除外；预编译 `.so` 二进制除外）+ 仓库地址改小写。commit `71283605`。
3. Phase 3：authority 与硬编码扫尾 + 验证（grep 零残留、XML 解析、底栏 6 项、R/BuildConfig 引用抽查）+ config 仓库地址。
4. Phase 4：活文档更新 + 本文档收尾（结果、验证证据、`.so` 重编提醒）。

## 结果（Phase 4，2026-11-11 18:56）

- 提交（本地 main `e435e251` → 4 笔，未推、未编）：
  - `38ab810b` chore(pkg)：applicationId `com.github.moyingshi.osc.tv`、namespace（app/nodejs/quickjs/chaquo）、eventBusIndex、Manifest 全限定名、工作流测试过滤器、proguard。
  - `71283605` refactor(pkg)：源码 2495 文件目录迁移 + 2606 文件内容替换 + 仓库地址 `xinyonghuu/Mooyingshi` → `xinyonghuu/moyingshi`（Github.java 等 12 个 app 文件 + widget_host.js）。
  - `42c06784` chore(pkg)：authority 扫尾 + 全仓库验证 + config 仓库地址。
  - Phase 4（本文档提交）：活文档更新（README、b-ui/b5/b6/ui-restyle）+ 本文档收尾。
- 每笔都有 recovery tag：`recovery/pkg-rename-osc-tv-p1|p2|p3|p4/<时间戳>-<短 hash>`。
- 新旧包名对照：applicationId `com.github.moyingshi.osc.tv`；源码包 `com.github.moyingshi.osc.tv`（+ `.nodejs` / `.quickjs` / `.chaquo` 子包）；namespace 同上；authority `${applicationId}.provider` 自动跟随。
- 新仓库 `moyingshi`（全小写）：代码里写死的仓库地址已全改小写；GitHub 上须先删旧仓库 `Mooyingshi` 才能建新仓库（顺序由主对话办）；本地 git remote 仍指向旧仓库地址，推新仓库前须改 remote（主对话办）。
- 未做完/风险：① 预编译 `libexo_ass.so` 须用 `scripts/build_exo_ass_native.py`（NDK 29）重编，否则 Exo ASS 字幕渲染 UnsatisfiedLinkError；② 没编译、没真机测（本机无 SDK），第一次编译检查靠推上去后的 GitHub 构建；③ 历史文档（251 个 md）保留旧包名原样（见“现状清单”），那是写下当时的真实记录，有些还是上游自身的包名。
- 建议先测（推上去编完之后）：安装新包 APK 与老版并存、互不干扰；设置/历史/收藏全新空白（旧数据不迁移）；更新检查走新仓库地址（beta 渠道能看到新版、能下载）；老版 App 检查更新被 validatePackage 挡住（下新包、校验失败、删包、老 App 原样保留）；进短剧、播 ASS 字幕（.so 重编前会崩，须先重编）；底栏仍 6 项。

## 验证（Phase 3，2026-11-11 18:52）

- 包声明与目录一致：2641 个 .java/.kt 文件逐个核对 `package` 声明与所在目录推导出的包名，0 不匹配。
- `com.fongmi.android.tv`：全仓库 252 个命中，全部是 .md 历史文档（+ 本文档的新旧对照表）；代码/配置/脚本零命中。
- `com.fongmi.chaquo` / `com.fongmi.quickjs`：代码零命中；只剩历史文档与 `.codex/task-state`（git 忽略的 guard 审计文件）。
- 旧 applicationId `com.github.mooyingshi.osc`（不带 `.tv` 后缀）：只剩活文档 4 处（b-ui/b5/b6/ui-restyle 的约束行，Phase 4 改）+ 本文档对照表 + `.codex/task-state` 审计文件。
- `xinyonghuu/Mooyingshi`：代码/配置零命中；只剩 `.codex/task-state` 审计文件。
- `Mooyingshi`（大写 M）：只剩 README 标题 1 处 + b6 文档 2 处（Phase 4 改）+ `.codex/task-state` 审计文件。
- authority：Manifest 用 `${applicationId}.provider` / `${applicationId}.androidx-startup`，自动跟随；代码里 `FileProvider.getUriForFile` 用 `getPackageName() + ".provider"`，自动跟随；无硬编码旧 authority。
- XML：87 个改动布局解析全部通过；底栏 `menu_nav.xml` 6 项。
- 必须保留的旧串（外部二进制/上游署名，改了会断）：`com.fongmi.hook.Hook`（`hook-release.aar` 预编译库的类，App.java/Core.java 引用 + baseline-prof.txt 条目）、`https://github.com/fongmi`（LrcLibClient 的 User-Agent，上游作者署名）、JS 桥名 `window.fongmi` 等（网页桥契约）。
- 未解决：预编译 `third_party/exo-ass-native/prebuilt/arm64-v8a/libexo_ass.so` 仍含旧 JNI 符号，须重编（见风险）；其余同上。

## 仓库改名（新仓库 `moyingshi`，全小写）

- 旧仓库 `Mooyingshi`（大写 M）→ 新仓库 `moyingshi`（全小写）。GitHub 仓库名不区分大小写，须先删旧仓库才能建新仓库（顺序由主对话办）。
- 代码里写死的仓库地址已全改小写：`Github.java`（更新检查 API、release 下载、update-channel）、`GithubProxy.java`、`SettingEnhanceFragment` / `SettingEnhanceActivity` 的 `URL_GITHUB`、两个 Github 测试、`config/mooyingshi-test-config.json`（3 个 raw.githubusercontent 地址）、`widget_host.js` 注释、6 个 glass 文件的“adapted for moyingshi”注释。
- 本节为旧仓库名对照说明（例外保留旧写法）。
