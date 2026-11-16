# P12：重编 libplayer.so，修复包名改名后 MPV 全灭

- 任务 ID：`P12`（MPV/native/App；索引中唯一的 `P12` 字样是 `E-SP12`，属 Exo 性能域，与此任务无关，ID 无冲突）
- 用户决定：2026-11-16 15:58，Wei 选择“重编原生库，彻底修”；15:59 指示修复同步给墨影视（main）
- 本任务性质：不是上游合并（无上游提交要移植），是本地 JNI 源码重编 + 窄 Java 加固；不做完整上游 commit ledger，但遵守 task-guard、单一任务文档、声明路径、原子提交 + 恢复 tag、二进制与源码提交分离
- 分支：`fix/mpv-jni-rebuild`，基线 `origin/main` @ `38097b630a560d2a5b28d833068071b92ae117a0`
- 回滚锚点：基线提交 `38097b630a560d2a5b28d833068071b92ae117a0`；本单元不提交二进制，只 revert 本提交即可完整回滚

## 完成句

重编两 ABI 的 `libplayer.so`，使其 `FindClass` 命中改名后的 `IsoSessionManager`，MPV 恢复播放；同时加固 `MpvPlayer` 初始化失败路径，使初始化中段失败不再永久卡死创建门闩；新二进制经 CI 校验后再单独成套提交，不与源码混在同一提交。

## 1. 根因证据链

### 1.1 现象

2026-11-16 真机（RMX3800，SDK 36）：所有视频 MPV 内核均报“MPV 加载媒体失败”，Exo 内核同一片源可播。对照测试：非 C0 的上一 beta `v5.6.0-beta-202611160956` 同样失败，排除 C0 回归。杀进程后首次即用 MPV 也失败，卡死发生在首个 MPV 初始化内。

### 1.2 决定性日志

干净日志（清日志 → 杀进程 → MPV 首播）第一条失败，首播后约 34ms：

```text
MPV_LOAD_FAILED: com.fongmi.android.tv.player.iso.IsoSessionManager
causeTypes=j14 <- java.io.IOException <- java.lang.ClassNotFoundException
```

同一 trace 内 11ms 后的重试即报 `MPV native context creation is already in progress`，此后全部失败均为该消息。

### 1.3 二进制与源码不一致（本分支实测，2026-11-16）

| 检查项 | 结果 |
| --- | --- |
| `app/src/arm64_v8a/assets/mpv-libs/arm64-v8a/libplayer.so` 含旧名字符串 `com/fongmi/android/tv/player/iso/IsoSessionManager` | 1 处 |
| 同文件含新名字符串 `com/github/moyingshi/osc/tv/player/iso/IsoSessionManager` | 0 处 |
| `app/src/armeabi_v7a/assets/mpv-libs/armeabi-v7a/libplayer.so` 含旧名 / 新名 | 1 处 / 0 处 |
| JNI 源码 `third_party/mpv-player-jni/src/iso_dvd.cpp:468` | `FindClass("com/github/moyingshi/osc/tv/player/iso/IsoSessionManager")`（新名） |
| `app/proguard-rules.pro:10` | `-keep class com.github.moyingshi.osc.tv.player.iso.IsoSessionManager`（新名） |
| 两份二进制最后一次改动 | 导入提交 `da729e1b7522c93c18b612ea4d06ba1a79f6b25f`（改名快照），之后未重编 |
| 其它 mpv-libs `.so` 含 `fongmi` 残留 | 无；残留仅此一处 |

当前（陈旧）二进制 SHA-256：arm64 `89cea66bf8b77b1081d602cdea3cedcec9ee9c34b4c03827d88ab000fd739689`，armv7 `72c3e113f9bff7e92a68793a3ea3e0982d8caa31dab40065a1ecb7480ebc3549`。

### 1.4 触发链

1. 每次 MPV 初始化，`third_party/mpv-player-jni/src/main.cpp:122` 的 `create()` 调用 `register_iso_protocol()`。
2. 旧二进制 `FindClass` 旧包名失败，挂起的 JNI 异常未被清除（`register_iso_protocol` 返回 `false` 后 `create()` 仅记日志继续），返回 Java 层时抛为 `ClassNotFoundException`。
3. `MPVLib.tryCreate()`（`app/src/main/java/is/xyz/mpv/MPVLib.java`）只在 `catch (RuntimeException | Error)` 中复位 `contextCreationAttempted`；`ClassNotFoundException` 是受检异常，不被该 catch 覆盖，异常逃出后 `contextCreationAttempted` 永久为真。
4. 此后每次 `tryCreate` 都因 `contextCreationAttempted` 返回 `false`，即日志中的 "already in progress"。同一进程内 MPV 永不可用。
5. 结论：改名后 MPV 很可能从未好过（与“之前的版本好像也不能”一致）；Exo 不走 JNI `FindClass`，不受影响。

### 1.5 第二道卡死口（本次一并加固）

即使 `tryCreate` 成功，若 `initialized = true` 之前的中段步骤（`applyPreInitOptions` / `mpvInit` / `applyPostInitOptions` 等）抛错，`MpvPlayer.ensureInitialized()` 不销毁已创建的 native context，而 `releaseNativeContextLocked()` 首行 `if (!initialized) return;` 又拒绝销毁未初始化实例的 context（含 takeover 路径），同样永久卡死创建门闩。`mpvInit()` 自身失败除外：`MPVLib.initializeCreatedContext()` 会自清 flags。

## 2. 方案比较与决定

| 方案 | 做法 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- | --- |
| no change | 不动 | 零风险 | MPV 永久全灭 | 否决 |
| shim 兼容类 | 新增旧包名 `IsoSessionManager`，4 个 JNI 静态方法（`length`/`readAt`/`close`/`prepareTrackMetadata`，签名已与新类逐一核对）委托给新类 + ProGuard keep | 最快，无需重编 | 重新引入已废弃的旧包名；二进制与源码继续不一致，属绕过而非修复 | 备选，未选 |
| 重编原生库 | 按锁定输入重编 `libplayer.so`（两 ABI），二进制与源码重新一致 | 彻底修复根因；符合 `third_party/mpv-native-build.md` 的供应链规则 | 构建重（须先建 MPV/FFmpeg 前缀，两 ABI 全链，CI 上数小时）；需单独成套提交二进制 | **Wei 2026-11-16 15:58 选定** |

另：Java 加固（§4）无论选哪条路线都应做，它堵的是中段失败卡死口，与触发异常的具体类型无关。

## 3. 范围

### 3.1 本单元改动（task-guard scope，恰好 4 路径）

- `docs/P12-mpv-jni-rebuild-rename.md`（本文件，唯一任务文档）
- `docs/upstream-player-dependency-merge-assessment-2026-08-20.md`（仅索引加一行 `P12`）
- `app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java`（仅 §4 两处加固）
- `.github/workflows/mpv-native-rebuild.yml`（新 CI workflow，§5）

### 3.2 明确不改

- `MPVLib.java`：不改。理由：`tryCreate` 的等待/超时语义是“旧 context 存活时不得建第二个 context”的安全阀；放宽其 catch 或复位语义有在旧 context 仍存活时建第二个 context 的风险。代价见 §6 残留风险。
- JNI 源码（`third_party/mpv-player-jni/**`）：不改。当前源码已是新名，重编即修复；`register_iso_protocol` 未清挂起异常的问题属后续加固候选（§6），不混入本次重编。
- 锁文件、补丁、override、构建脚本：不改。重编使用当前锁定输入（NDK `29.0.14206865` r29、API 24、lock 文件原样）。
- 二进制 assets：本单元不提交。CI 产物校验通过后，由后续单独提交成套更新（源码与二进制提交分离，见 `integration-workflow.md` §6）。
- `main` 分支与 `b8-flutter-rewrite` 分支：不动。本单元只在 `fix/mpv-jni-rebuild` 本地提交，不 push、不建 PR；同步给 main 由 Wei 另行决定。

### 3.3 合同保留

- MPV FFmpeg `libmv*`/`libmw*` 命名空间、SONAME/DT_NEEDED 隔离不变（JNI 脚本自带门禁 + `verify_mpv_native_assets.sh --require-elf`）。
- `MpvPlayer` 初始化成功路径语义不变：加固只在抛错路径上增加销毁，成功路径多一层 try/catch 帧，无行为变化。
- `releaseNativeContextLocked` 的 surface detach、observer 清理、finally 字段复位语义不变，只放宽首行守卫。

## 4. Java 加固（MpvPlayer.java）

行号以基线 `38097b63` 为准，按符号定位。

### 4.1 ensureInitialized：中段失败销毁已建 context

`nativeContextOwner = this` 之后、`initialized = true` 之前及之后的中段步骤，任何 `Throwable` 都必须销毁刚创建的 native context（仅当 `nativeContextOwner == this` 时调 `mpvDestroyCreatedContext()` 并清 owner），再重新抛出原异常。

安全性：`mpvInit()` 自身失败时 `initializeCreatedContext()` 已自清 flags，此时 `contextCreated == false`，`destroyCreatedContext()` 首行直接返回，是空操作安全的，不会二次破坏状态。销毁调用本身的异常被吞掉（与 `releaseNativeContextLocked` 现有风格一致），以保原异常上报；owner 清理在 `finally` 中保证执行。

### 4.2 releaseNativeContextLocked：按所有权而非仅按 initialized 守卫

`if (!initialized) return;` 改为仅当“未初始化且不拥有 native context”时跳过；拥有 context 但未初始化时也要走销毁路径（清 owner、调 `mpvDestroyCreatedContext()`）。其余逻辑原样。这同时修复了 takeover 路径：前任 owner 若处于“已建 context 但未初始化”的泄漏态，takeover 现在能真正销毁它，而不是提前返回。

## 5. CI workflow（mpv-native-rebuild.yml）

- 触发：`workflow_dispatch` + push 到 `fix/mpv-jni-rebuild` 且 paths 仅限该 workflow、`third_party/mpv-player-jni/**`、`scripts/build_mpv_*.sh`、`third_party/mpv-native-lock.json`、`third_party/patches/**`、`third_party/mpv-native-overrides/**`。文档/Java 加固提交不触发重编。
- job1 `native-rebuild`（ubuntu）：按 `third_party/mpv-native-build.md` 主机准备安装工具（build-essential、cmake、gperf、pkg-config、python3-venv、perl 等）；JDK 21；Android SDK + NDK `29.0.14206865`（lock 指定版本）；跑 `scripts/build_mpv_native.sh --abi all`（不加 `--install`）再跑 `scripts/build_mpv_player_jni.sh --abi all`（不加 `--install`）；决定性校验：`strings` 检查两个 `libplayer.so` 不含旧名且含新名，否则失败；上传两个 `libplayer.so` + 构建日志 + 清单为 artifact。
- job2 `apk`（needs job1）：全新 checkout，把 job1 的 `libplayer.so` 拷入两处 assets 覆盖，跑 `bash scripts/verify_mpv_native_assets.sh --require-elf`，按 `android-release.yml` 的签名步骤（相同 secret 名）与 4 个 assemble 任务编 4 个 release APK，后缀 `-mpvfix`，上传 artifact。不创建 Release、不碰 update-channel。
- 偏差记录：任务下达时假设参考 `b8-c0.yml`，但该文件只存在于 `b8-flutter-rewrite` 分支，main 上不存在；job2 实际以 main 上的 `android-release.yml` 为参照（签名步骤与 4 个 assemble 任务与之相同，`WEBHTV_APK_SUFFIX` 经 `app/build.gradle:19,158` 确认生效）。

## 6. 残留风险（诚实记录）

- **tryCreate 逃逸路径未堵**：本次观测到的 `ClassNotFoundException` 从 `tryCreate` 内部逃出（§1.4 第 3 步），`MpvPlayer.java` 一侧无法复位 `MPVLib` 私有的 `contextCreationAttempted`（`destroyCreatedContext()` 在 `!contextCreated` 时是空操作）。重编消除了唯一已知的触发源，但若未来有其它受检异常从 JNI `create()` 逃出，门闩仍会卡死。彻底堵死需后续工作（二选一或并行）：(a) `MPVLib.tryCreate` 的 catch  widen 至 `Throwable` 并复位 flags（需复评“旧 context 存活”风险）；(b) JNI `register_iso_protocol` 在 `FindClass` 失败后 `ExceptionClear`，使 `create()` 正常返回、协议不可用时仅记日志。后者同时会让旧二进制在非 ISO 内容上可用。两者均不在本单元范围，需另行决策。
- **CI 原生构建时长与磁盘**：两 ABI 全链构建在 GitHub  hosted runner 上预计数小时，磁盘占用大；workflow 已含清盘步骤与 330 分钟超时，但首次运行仍可能超时或磁盘不足，需看实际运行结果。
- **设备验收未做**：本单元无本地 SDK/NDK，Java 加固未经编译，APK 未编、未真机验证；验证矩阵见 §7，全部待 CI + 真机。

## 7. 验证矩阵

| 检查 | 方法 | 状态 |
| --- | --- | --- |
| `git diff --check` 干净 | 本地 | 待 finish 前执行 |
| Workflow YAML 可解析 | `python3 -c yaml.safe_load` | 待执行 |
| 新 `libplayer.so` 不含旧名、含新名（两 ABI） | CI job1 `strings` 校验（失败即红） | 待 CI |
| SONAME / 未改名 FFmpeg 依赖门禁 | JNI 脚本内置检查 | 待 CI |
| `verify_mpv_native_assets.sh --require-elf` | CI job2，覆盖后的 assets | 待 CI |
| 4 个 release APK 构建成功（后缀 `-mpvfix`） | CI job2 | 待 CI |
| 真机 MPV 播放（普通 HLS/MP4、硬解/软解、字幕、倍速） | Wei 真机，RMX3800 | 待设备 |
| 真机 MPV 生命周期（旋转、前后台、杀进程重进、连续换集） | Wei 真机 | 待设备 |
| ISO/DVD 内容回归（`register_iso_protocol` 实际命中新类） | 有 ISO 片源时 | 待设备（可选） |
| 回滚可行性 | revert 本提交；二进制提交后 revert 成套提交 | 锚点已记 |

## 8. Checkpoint 1：本地实施完成（2026-11-16）

- 完成：任务文档、索引行、MpvPlayer 两处加固、新 workflow；本地提交 + 恢复 tag（见 Recovery anchor）。
- 基线：`38097b630a560d2a5b28d833068071b92ae117a0`（`origin/main`，fetch 于 2026-11-16 16:01）。
- 未验证：Java 编译、CI 构建、真机播放（见 §6、§7）。
- 下一动作：把分支推到远端并触发 `mpv-native-rebuild` workflow（需 Wei 授权 push；本单元不 push）。

## 9. Checkpoint 2：CI 首跑失败与 NDK 修复（2026-11-16）

- Run #1（43829081150，16:11）1m40s 即失败：第 8 步 `Build MPV native stack` 0 秒退出，`error: NDK revision mismatch: expected 29.0.14206865 at /usr/local/lib/android/sdk/ndk/27.3.13750724`。
- 双重原因：① runner 预装 NDK 27 并导出 `ANDROID_NDK_HOME` 指向它，构建脚本优先用 `ANDROID_NDK_HOME`，故即使装好 r29 也会读错；② `sdkmanager "ndk;29.0.14206865"` 一步 1 秒静默无操作（无下载行），r29 实际未装上。
- 修复（本单元，workflow only）：改用官方 zip 直下 NDK r29（URL 已验 HTTP 200），校验 `source.properties` 的 `Pkg.Revision` 与 `llvm-strip` 可执行，并把 `ANDROID_NDK_HOME` 写入 `$GITHUB_ENV` 覆盖 runner 默认值。job2 不用 NDK，不动。
- 下一动作：推修复后的 workflow 触发 run #2，取 job1 的 `strings` 校验结果。

## 10. Checkpoint 3：CI 第二跑成功与本地验证（2026-11-16）

- Run #2（43835455083）completed/success，约 34 分钟；两 job 全绿。Artifacts：`mpvfix-2-beaa7c9`（369,953,074 B）+ `mpv-libplayer-rebuilt`（290,036 B），均未过期。
- 本地验证（Orion，逐项实测）：两份重编 `libplayer.so` 旧名 0 处、新名 1 处；SONAME 均为 `libplayer.so`；DT_NEEDED 仅 `libmwscale/libmvcodec/libmpv` + 系统库，无未改名 FFmpeg 依赖；SHA-256 与 manifest 一致（arm64 `1d63e694…12069`，armv7 `997ea007…7be77d0`）；mobile arm64 APK 内 `assets/mpv-libs/arm64-v8a/libplayer.so` 与重编文件 SHA-256 逐字节一致，旧名 0 处。
- 本单元把两份已验二进制装入 `app/src/*/assets/mpv-libs/*/` 成套提交（与源码分开提交），随后推分支；APK 发 Wei 真机验证 MPV。

## Recovery anchor

- 目标：重编 `libplayer.so` 修复改名后 MPV 全灭 + 加固初始化失败路径；验收见 §7。
- 计划状态：CI 首跑因 NDK 问题失败，已修复 workflow，待第二跑。
- 当前文件/符号：`MpvPlayer.ensureInitialized` / `releaseNativeContextLocked`；`.github/workflows/mpv-native-rebuild.yml`；本文件。
- 已完成动作与结果：见 Checkpoint 1。
- 未验证的工作区编辑：无（已提交）。
- 未解决风险：§6 三项。
- 下一动作：推修复后的 workflow 触发 run #2，取 job1 的 `strings` 校验结果。
