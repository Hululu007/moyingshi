# B8 B6：播放器设置页迁移到 Flutter

## §0 执行声明

- 任务护栏：`b8-b6-player-settings`（standard），分支 `b8-flutter-rewrite`，基线 `0b1277f2`（B7 提交）。
- 只在该分支上工作，main 不动（仍为 `f39a6e0`），不推送（Wei 2026-11-17 10:44 指示：做完先不编，等别的弄好了一起推）。
- C0/C1/C2/B2/B3/B4/B5/B7 合约与实现冻结；例外只有两处（本批简报授权）：`B7HostApiImpl.java` 的播放器行处理方法（补 B7 预留的分流点）和 `SettingFragment.java` 的 ROW_PLAYER 接线；共享文件只做加法；家族路由只加成员，不动现有成员。
- 本批只迁设置 UI，不碰播放器本身（播放内核、VideoActivity、PlaybackActivity、播放控制逻辑一律不动）。
- 本机无 Java/Android/Flutter/Dart SDK：构建、Pigeon 代码生成、运行均未验证，全部留给 CI + Wei 设备验收（见 §6）。

## §1 原生实现（只读发现）

### 1.1 播放器设置页（`app/src/mobile/.../ui/fragment/SettingPlayerFragment.java`，588 行）

布局 `fragment_setting_player.xml` 共 39 行；`initView()` 里 `hidePerformanceRows()` 永久隐藏 16 行（render、buffer、bufferBytes、backBuffer、playCache、preload + 5 个 preload 子行、tunnel、audioDecode、audioPassThrough、videoDecode、aac），它们由性能配置弹窗（exo4kCompat 行）统一管理；**可见 23 行**。`format = new DecimalFormat("0.#")`；`initView()` 里 `PlaybackPerformanceSetting.ensureInitialized()`。

行值来源：全部是 `PlayerSetting` / `LiveSetting` / `Setting` 的读方法 + `ResUtil.getStringArray()` 的数组查表 + 三个汇总方法（`LutSetting.getSummary()`、`MpvConfigStore.summary()`、`PlaybackPerformanceSetting.getSummary()`）；开关文案 = `setting_on` / `setting_off`。

### 1.2 可见 23 行（布局顺序）

1. **播放器按钮配置**（`player_button_config`）：值 = `player_button_config_summary`（可见数/总数）；点 → `PlayerButtonConfigDialog.show(activity, Runnable)`。
2. **Pad 直播模式**（`player_pad_live`，仅 `ResUtil.isPad()` 时可见）：值 = `select_pad_live_mode[getPadLiveMode()]`；点 → 循环 +1 取模。
3. **性能配置**（`player_exo_4k_compat`）：值 = `PlaybackPerformanceSetting.getSummary()`；点 → `PlaybackPerformanceDialog.show(activity, Runnable)`。
4. **播放器内核**（`player_kernel`）：值 = `select_player_kernel[getPlayer()]`；点 → `PlayerKernelDialog.show(activity, getPlayer(), Listener)`（弹窗内部把显示序号映回内核常量，只在换了内核时才回调）。
5. **mpv 配置**（`player_mpv_config`，仅 MPV 内核可见）：值 = `MpvConfigStore.summary()`；点 → `MpvConfigDialog.show(activity, Runnable)`。
6. **mpv 渲染器**（`player_mpv_render`，仅 MPV 可见）：值 = `select_mpv_render[getMpvRender()]`，Vulkan 且设备不支持时追加 ` (mpv_render_native_unavailable)`；点 → `ChoiceDialog.showSingle(activity, player_mpv_render, ...)`。
7. **蓝光菜单**（`player_bluray_menu`，仅 MPV 可见）：值 = 开/关；点 → 取反。
8. **画面比例**（`player_scale`）：值 = `select_scale[getScale()]`；点 → `VideoAspectModeDialog.show(activity, getScale(), IntConsumer)`（自定义比例时弹窗内部先开 `VideoAspectRatioDialog`，再报 mode）。
9. **LUT**（`player_lut`）：值 = `LutSetting.getSummary()`；点 → `LutDialog.show(fragment, Runnable)`。
10. **字幕**（`player_caption`，仅 `PlayerSetting.hasCaption()` 时可见）：值 = `select_caption[isCaption() ? 1 : 0]`（Default/System）；点 → `putCaption(!isCaption())`；长按 → 仅 caption 开时打开系统字幕设置（`Settings.ACTION_CAPTIONING_SETTINGS`）。
11. **屏幕显示**（`player_osd`）：值 = 勾选的 OSD 项以 ` / ` 连接，无勾选时 `setting_off`；点 → `PlayerOsdDialog.show(fragment, osd数组, getDisplayChecked(), Callback)`（每次切换都调 `onApply`）。
12. **媒体角标位置**（`osd_media_badge_position`）：值 = `select_media_badge_position[choice]`，越界时 `setting_off`；点 → `ChoiceDialog.showSingle(...)`。
13. **自动播放**（`player_auto_play`）：开/关，点 → 取反。
14. **自动换源**（`player_auto_change`）：开/关，点 → 取反。
15. **延后切换标志**（`player_defer_flag_switch`）：开/关，点 → 取反。
16. **记住播放亮度**（`player_remember_brightness`）：开/关，点 → 取反。
17. **直播源回退**（`player_live_source_fallback`）：开/关（`LiveSetting`），点 → 取反（行内 lambda）。
18. **播放失败回退**（`player_failure_fallback`）：值 = `select_player_failure_fallback[getFailureFallback()]`；点 → `ChoiceDialog.showSingle(...)`。
19. **倍速**（`player_speed`）：值 = `format.format(getSpeed())` + 独立的 `times`（“倍”）TextView，两者相邻无空格；点 → `SpeedDialog.show(fragment)`（确定和取消都会调 `setSpeed`，取消传回原值）。
20. **音乐通知**（`player_music_notification`）：开/关，点 → 取反。
21. **有声书通知**（`player_audio_book_notification`）：开/关，点 → 取反。
22. **后台播放**（`player_background`）：值 = `select_background[getBackground()]`；点 → `ChoiceDialog.showSingle(...)`。
23. **UA**（`player_ua`）：值 = `Setting.getUa()` 原文；点 → `UaDialog.show(fragment)`（确定才调 `setUa`）。

### 1.3 入口（只改分流处）

- `HomeActivity` 原生页索引 2（`change(2)`）。
- `SettingFragment` ROW_PLAYER（`"player"`，`:98`）：行在 `:213`，深链 `performRow` 在 `:439`；两处都走新增的 `openPlayerSettings()`。
- B7 设置主屏的播放器行（`B7HostApiImpl.generalGroup()`）：B7 建行时预留了分流点（member 为空、memberFlutter false、nativePage 2）；本批只改这一行调用。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- 开关 `flutter_b6_enabled`（`Setting`，默认 false，独立于其他开关）；开关 UI 只在实验屏（C0 页）。
- 关 = 播放器设置页回原生：B7 主屏行走原生页 2，原生 `SettingFragment` 行与深链走原生页 2；原生实现一行不删。
- 开时三个入口都进族内 `player` 成员：B7 主屏行（`member="player"` + `memberFlutter` + `pushNamed('/player')`，族内导航，见 B7 `_onRowTap`）、原生设置页行（`openPlayerSettings()` → `C1ScreenActivity.start(...ROUTE_SETTINGS + "?member=player")`）、`performRow` 深链（同上）。

### 2.2 Pigeon 合约（`flutter_module/pigeons/b6_player_messages.dart`）

`B6HostApi` 共 27 个方法：开关 2 个（`isB6Enabled` / `setB6Enabled`）、快照 1 个（`getPlayerSettings()`）、同步开关 9 个（blurayMenu / caption / autoPlay / autoChange / deferFlagSwitch / rememberBrightness / liveSourceFallback / musicNotification / audioBookNotification）、循环 1 个（`cyclePadLiveMode`）、`@async` 弹窗 13 个（playerButtons / exo4kCompat / kernel / mpvConfig / mpvRender / scale / lut / osd / osdMediaBadgePosition / background / failureFallback / ua / speed）、长按 1 个（`openSystemCaptioning`，fire-and-forget）。

`B6PlayerSettings` 扁平快照 50 个字段：屏标题 + 每行标题/值 + 3 个可见标志（`padLiveVisible`、`mpvVisible`、`captionVisible`）；倍速值由 host 把 `DecimalFormat("0.#")` 结果与 `times` 拼好（无空格，与原生相邻 TextView 一致）。

### 2.3 Dart 页（`flutter_module/lib/b6_player_page.dart`）

`PlayerSettingsView` + 本文件私有的 `_B6MemberState` mixin（从 B5 的 `_B5MemberState` 复制：resumed 重取、`afterDialog`（不 await，只挂 `.then`）、`act`（同步动作 + 重取）、loading/error/rows 三个脚手架；不共享，按批独立可回滚）。

行顺序 = §1.2 布局顺序；图标/圆圈色按 B3 V8 模式取 material 近似；chevron 只在弹窗行上，开关/循环行无 chevron；字幕行点按切换、长按走 `openSystemCaptioning()`（`SettingsRow.onLongPress`，B4 已加）。

### 2.4 Host 实现（`B6HostApiImpl.java`）

- 快照：全部文案与计算值 host 算出；数组查表用 `clampIndex` 防越界（原生是直接下标；存储值本身由各 getter 自清洗，clamp 只是防御，行为不变）；三个汇总方法（LUT / mpv 配置 / 性能）用 `safeText` 包一层，失败时该行值退化为 `error_config_get`，不拖垮整页（B3 safeRow 思路，无逐行 error 标志）。
- 开关/循环：与原生点按处理逐行对应（取反或 +1 取模），无额外副作用（原生这些处理里也没有 `ConfigEvent` 之外的调用）。
- 弹窗分两类：main 源码集的弹窗（PlayerKernel / VideoAspectMode / MpvConfig / PlayerButtonConfig / PlaybackPerformance / ChoiceDialog）用 `FragmentActivity` 重载直接调；mobile-only 的 4 个弹窗走反射 + 无头 fragment（见 §2.6）。
- `getMpvRenderText` / `getOsdText` / `getBadgePositionText` 逐行复刻原生计算（含 Vulkan 不可用后缀、` / ` 连接、空选 `setting_off`、角标越界回退）；OSD 连接循环取 `min(checked.length, items.length)`（原生直接按 checked 长度取下标，防御性收紧）。

### 2.5 弹窗契约（沿用 B3 V1）

`@async` 方法只在弹窗报告变化时完成回调，Dart 不 await、只挂 `.then` 重取；无变化关闭时什么都不完成、不重取。两个例外（原生行为如此）：

- `openSpeed`：SpeedDialog 的确定和取消都会调 `setSpeed`，所以关闭时总会完成（新值或原值）；Dart 重取一次，无害。
- `openOsd`：PlayerOsdDialog 每次切换都调 `onApply`，所以第一次切换即完成回调，后续切换仍立即写盘；最终状态靠 resumed 重取兜底。
- `openKernel`：弹窗内部只在内核真换了时才回调；选当前内核或返回都不完成（无变化，无需重取）。

### 2.6 mobile-only 类与资源（规划 §4.8）

- 3 个 mobile-only 弹窗（`UaDialog` / `SpeedDialog` / `PlayerOsdDialog`）经反射 + 无头 fragment 打开；`LutDialog` 是 main 源码集的类，但其 Fragment 重载经同一无头 fragment 打开（Activity 重载要 `PlayerManager`，impl 不持有）。`UaListener` / `SpeedListener` 在 main 源码集（`impl` 包），fragment 可直接实现；`PlayerOsdDialog.Callback` 是 mobile-only 嵌套接口，用 `Proxy` 实现（B3 V5 同款）。ProGuard 已有 `com.github.moyingshi.osc.tv.**` keep（C2 D5）。
- 2 个 mobile-only 数组（`select_background` = Off/On、`select_pad_live_mode` = Fullscreen/Standard）按名运行时解析（`mobileStringArray`，C2 `mobileString()` 同款），缺失时返回空数组（leanback 侧无入口，不会走到）；其余全部文案/数组均为 main-res，无需 `mobileString()`。
- 资源核查方法：按目录分别 grep（main / leanback / mobile 三个 values 目录），不用 head 截断（B2 教训，见 B2 文档 §2.11）。

### 2.7 文件清单

| 文件 | 变化 |
|---|---|
| `flutter_module/pigeons/b6_player_messages.dart` | 新：B6HostApi（27 方法）+ B6PlayerSettings（50 字段） |
| `flutter_module/lib/b6_player_page.dart` | 新：PlayerSettingsView + _B6MemberState |
| `flutter_module/lib/main.dart` | 加：pigeon 导入 1 行、`/settings?member=player` 路由、B6 开关（_b6Api/_b6Enabled/_loadB6Flag/_setB6Flag + SwitchListTile）。注意：不导入 `b6_player_page.dart`——main.dart 不引用 `PlayerSettingsView`，多余导入会触发 `unused_import`（见 §6 第 9 项） |
| `flutter_module/lib/b3_settings_a_page.dart` | 加：`'player'` 成员 + `/player` 路由 + 导入 |
| `app/src/main/java/.../flutter/B6HostApiImpl.java` | 新 |
| `app/src/main/java/.../flutter/B7HostApiImpl.java` | 只改播放器行调用：member `"player"`、memberFlutter `b6`、nativePage 2（B7 预留的分流点） |
| `app/src/main/java/.../flutter/C1ScreenActivity.java` | 加：注册 B6HostApi；ROUTE_SETTINGS 注释加 `player` |
| `app/src/main/java/.../flutter/C0ShellActivity.java` | 加：C0 引擎上注册 B6HostApi（只为实验屏开关） |
| `app/src/main/java/.../setting/Setting.java` | 加：`flutter_b6_enabled`（默认 false） |
| `app/src/mobile/java/.../ui/fragment/SettingFragment.java` | 加：`openPlayerSettings()`；ROW_PLAYER 行与 performRow 深链改走它 |
| `.gitignore` | 加：B6Messages.java |
| `.github/workflows/b8-c0.yml` | Pigeon 步骤加 b6 生成 + 存在性检查 |
| `docs/b8-b6-player.md` | 本文档 |

## §3 状态来源清单（全部 host 快照，Dart 不计算）

| 行 | 标题来源 | 值计算（host） | 可见条件 | 动作 → host 方法 |
|---|---|---|---|---|
| 播放器按钮配置 | `player_button_config` | `player_button_config_summary`（可见数/总数） | 总是 | 弹窗 → `openPlayerButtons` |
| Pad 直播模式 | `player_pad_live` | `select_pad_live_mode[getPadLiveMode()]`（mobile-only 数组） | `ResUtil.isPad()` | 循环 → `cyclePadLiveMode` |
| 性能配置 | `player_exo_4k_compat` | `PlaybackPerformanceSetting.getSummary()` | 总是 | 弹窗 → `openExo4kCompat` |
| 播放器内核 | `player_kernel` | `select_player_kernel[getPlayer()]` | 总是 | 弹窗 → `openKernel` |
| mpv 配置 | `player_mpv_config` | `MpvConfigStore.summary()` | 内核 = MPV | 弹窗 → `openMpvConfig` |
| mpv 渲染器 | `player_mpv_render` | `select_mpv_render[getMpvRender()]` + Vulkan 不可用后缀 | 内核 = MPV | 弹窗 → `openMpvRender` |
| 蓝光菜单 | `player_bluray_menu` | 开/关 | 内核 = MPV | 取反 → `toggleBlurayMenu` |
| 画面比例 | `player_scale` | `select_scale[getScale()]` | 总是 | 弹窗 → `openScale` |
| LUT | `player_lut` | `LutSetting.getSummary()` | 总是 | 弹窗 → `openLut` |
| 字幕 | `player_caption` | `select_caption[isCaption() ? 1 : 0]` | `hasCaption()` | 点取反 → `toggleCaption`；长按 → `openSystemCaptioning` |
| 屏幕显示 | `player_osd` | 勾选项 ` / ` 连接；无勾选 `setting_off` | 总是 | 弹窗 → `openOsd` |
| 媒体角标位置 | `osd_media_badge_position` | `select_media_badge_position[choice]`，越界 `setting_off` | 总是 | 弹窗 → `openOsdMediaBadgePosition` |
| 自动播放 | `player_auto_play` | 开/关 | 总是 | 取反 → `toggleAutoPlay` |
| 自动换源 | `player_auto_change` | 开/关 | 总是 | 取反 → `toggleAutoChange` |
| 延后切换标志 | `player_defer_flag_switch` | 开/关 | 总是 | 取反 → `toggleDeferFlagSwitch` |
| 记住播放亮度 | `player_remember_brightness` | 开/关 | 总是 | 取反 → `toggleRememberBrightness` |
| 直播源回退 | `player_live_source_fallback` | 开/关（LiveSetting） | 总是 | 取反 → `toggleLiveSourceFallback` |
| 播放失败回退 | `player_failure_fallback` | `select_player_failure_fallback[getFailureFallback()]` | 总是 | 弹窗 → `openFailureFallback` |
| 倍速 | `player_speed` | `DecimalFormat("0.#")` + `times`（无空格） | 总是 | 弹窗 → `openSpeed` |
| 音乐通知 | `player_music_notification` | 开/关 | 总是 | 取反 → `toggleMusicNotification` |
| 有声书通知 | `player_audio_book_notification` | 开/关 | 总是 | 取反 → `toggleAudioBookNotification` |
| 后台播放 | `player_background` | `select_background[getBackground()]`（mobile-only 数组） | 总是 | 弹窗 → `openBackground` |
| UA | `player_ua` | `Setting.getUa()` 原文 | 总是 | 弹窗 → `openUa` |

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 23 行

- 实验屏开 B6 后，从设置主屏点“播放器设置”进 Flutter 页：行顺序、标题、值与原生页逐行一致；16 个性能行不出现（与原生 `hidePerformanceRows()` 一致）。
- 每个开关行点按后值立即变（开/关翻转）；pad 行在 Wei 的手机上不出现（非 pad）。
- 每个弹窗行点按后打开对应的原生弹窗，确认后值更新；取消后值不变、无卡住。
- 内核切到 MPV 后，mpv 配置 / mpv 渲染器 / 蓝光菜单三行出现；切回 Exo/Ijk 后三行消失（快照重取）。
- 字幕行：点按在 Default/System 间切换；长按打开系统字幕设置（仅 caption 开时；关时常按无反应）。
- 倍速行显示如 `1倍`（`0.#` 格式，整数不带 `.0`）；改倍速后值更新。

### 4.2 深链

- 原生设置页（B7 关时）的播放器行 → B6 开走 Flutter，关走原生页 2。
- 设置内搜索的播放器结果行（page 2）→ 恒走原生页 2（见 §7 V1，B5 冻结）。
- B7 主屏的 pending row `player` → 与点行同一处理（B6 开走族内 `player`，关走原生页 2）；B7 文档 §4.3 的“`player` → 原生页 2”在 B6 开时被本批分流取代（B7 文档本身冻结，不改）。

### 4.3 族路由与开关

- `/settings?member=player` 直开播放器设置成员；族内返回回到设置主屏（B7 开时）或退出（B7 关时原生设置页不在族内——返回行为与其它成员一致，走 `_handleBack`）。
- 开关关掉后，播放器设置页立刻回原生；其它批的开关不受影响。

## §5 回滚

- 开关级：实验屏关掉 B6，播放器设置页回原生；全部开关关 = 全原生，零行为变化。
- 批次级：本批一个提交，整批 revert 即可（共享文件均为加法行 + 两处分流点）。
- 分支级：丢弃 `b8-flutter-rewrite` 分支；main 从未动过。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon 代码生成**：27 方法 + 50 字段的扁平合约，Java 侧 bool 一律装箱、方法名与 Dart 定义字符级一致、`@async` 的 `VoidResult` 形态——只能由 CI 的 Pigeon 生成 + Gradle 编译证明（C1 run #9/#10 的教训：先对 CI 日志逐字核对方法名）。
2. **反射弹窗**：`UaDialog` / `SpeedDialog` / `PlayerOsdDialog` 的类名、`show` 签名、嵌套 `Callback` 接口名，以及无头 fragment 的 `requireParentFragment()` 强转——CI 编译证明不了运行时反射，靠设备验收逐个弹窗点开；若任一弹窗打不开（类名/签名漂移或 ProGuard 混淆），该行点按后无反应且 Dart 不会卡住（`afterDialog` 不 await），但需报上来修。
3. **内核弹窗的序号映射**：impl 信任弹窗内部把显示序号映回内核常量（`kernelAt` / `kernelRank`）；设备验收需实测 EXO → MPV → IJK 切换，核对行文案与 MPV 三行的出现/消失。
4. **SpeedDialog 取消路径**：取消也完成回调（传回原值）；验收确认取消后值不变、无重复重取导致闪烁。
5. **OSD 多次切换**：连续勾选两项再关闭，两次写盘都生效（第一次切换完成回调、第二次仍写盘，最终靠 resumed 重取）。
6. **字幕长按**：caption 关时常按无反应；开时打开系统字幕设置页，返回后 Flutter 页重取（值不变）。
7. **Pad 行**：Wei 的手机不是 pad，该行应不出现——无法在真机上验证它在 pad 上的形态，记为已知未验证。
8. **后台/ Pad 数组英文原文**：`select_background`（Off/On）、`select_pad_live_mode`（Fullscreen/Standard）是 mobile 数组里的英文原文，原生也这样显示，不是翻译缺失。
9. **联合构建前核查（本批发现，既存风险，不属 B6）**：`main.dart` 里 B4/B5/B7 的页导入（`b4_enhance_page.dart` / `b5_personal_page.dart` / `b7_settings_home_page.dart`）在 main.dart 内无任何引用（各成员页只在族壳内构造）；按本模块 `analysis_options.yaml`（`unused_import: warning`，C1 run #8 已证明 warning 会使 CI 失败），联合构建时 `flutter analyze` 可能报这三处未使用导入。B6 的 `b6_player_page.dart` 导入已按此排查、未加进 main.dart。B4/B5/B7 冻结，本批不动，需在联合推之前核查处理。**已修复（小修复 `b8-main-unused-imports-fix`，不推）**：3 行导入已从 main.dart 删除；删除前逐个核实 `EnhanceSettingsView` / `PersonalSettingsView` / `SettingSearchView` / `SettingsHomeView` 在 main.dart 内 0 引用、只在 `b3_settings_a_page.dart` 族壳内构造；顺带核查 main.dart 全部其余导入（b2/b3/c1/c2 各页 + 9 个 pigeon 生成文件）均有引用，无其他未用导入。

## §7 相对规划的全部偏差

- **V1** 设置内搜索的播放器结果行（page 2，约 40 条）恒走原生页 2：B5 冻结，`flutterAvailable` 对 page 2 恒为 false，`openSearchEntry` 的 rowId 为空路径直接 `openHomeAt(2)`，B6 无法拦截。本批入口只有 B7 主屏行、原生设置页行、`performRow` 深链三处。
- **V2** 两个 mobile-only 数组运行时解析（`mobileStringArray`，空数组回退）：`select_background`、`select_pad_live_mode` 不在 main-res，直接引用 `R.array` 会断 leanback 构建；回退只在 leanback 触发，那里本屏无入口。
- **V3** 字幕行一点按一长按双行为：点按切换 Default/System，长按开系统字幕设置——原生就是这样，`SettingsRow.onLongPress` 自 B4 已有，无需改共享组件。
- **V4** SpeedDialog 关闭时总会完成回调（确定与取消都调 `setSpeed`），与 B3 V1“无变化不完成”不同；Dart 侧仍用 `afterDialog`，重取一次无害。
- **V5** PlayerOsdDialog 每次切换都调 `onApply`，首次切换即完成回调；后续切换仍立即写盘，最终状态靠 resumed 重取。
- **V6** LutDialog 用无头 fragment 的 Fragment 重载：Activity 重载要一个 `PlayerManager` 实例，impl 不持有；其余 main 弹窗用 Activity 重载直接调。
- **V7** B7 文档 §4.3 的 pending row `player` → 原生页 2，在 B6 开时被本批的 B7 行分流取代（member `player` + 开关）；B7 文档冻结不改，行为变化记在这里。
- **V8** 16 个性能行不进快照（与原生 `hidePerformanceRows()` 一致），由 exo4kCompat 性能弹窗统一管理；其值不在 Flutter 页展示。
- **V9** 内核弹窗只在真换了内核时回调；选当前内核或返回都不完成，Dart 不重取（无变化）。
- **V10** 倍速值 host 拼好（`"1" + "倍"` 无空格），与原生相邻的两个 TextView 一致；Dart 只渲染。

## Recovery anchor

- 目标：手机端播放器设置页（23 可见行）迁移到 Flutter，作为 `/settings` 家族的 `player` 成员，`flutter_b6_enabled` 默认关。
- 状态：本地实现完成（1 个提交，护栏 `b8-b6-player-settings`），未推未编；main 仍为 `f39a6e0`。
- 未验证：Pigeon 生成 + Gradle 编译 + 13 个弹窗的运行时行为（§6 全部），留给联合 CI 构建 + Wei 设备验收。
- **恰好一个下一步：B8 分类列表页（按 `docs/b8-full-migration-plan.md` §3 顺序）。**
