# B8 B7：设置主屏迁移到 Flutter

## §0 执行声明

- 任务护栏：`b8-b7-settings-home`（standard），分支 `b8-flutter-rewrite`，基线 `c3a2353a`（B5 提交）。
- 只在该分支上工作，main 不动（仍为 `f39a6e0`），不推送（Wei 2026-11-17 10:44 指示：做完先不编，等别的弄好了一起推）。
- C0/C1/C2/B2/B3/B4/B5 合约与实现冻结；共享文件只做加法；B6 播放器设置页因 P1 未定而跳过，本批内播放器设置行恒走原生。
- 本机无 Java/Android/Flutter/Dart SDK：构建、Pigeon 代码生成、运行均未验证，全部留给 CI + Wei 设备验收（见 §6）。

## §1 原生实现（只读发现）

### 1.1 设置主屏（`app/src/mobile/.../ui/fragment/SettingFragment.java`，1012 行）

7 个分组，25 行 + 下载列表，均在 `buildGroups()` 里按顺序建；行值在 `setText()` 里重读；`onResume`/`onHiddenChanged` 时重建隐藏分组、重读值、刷新下载列表、消费 pending row。

- 站点（3 个配置行）：点水、直播、壁纸。配置行 = 标题 + 值 + home 按钮 + history 按钮，无 chevron。
  - 点水行：点行 → `HistoryDialog.vod().manage()`；home → `SiteDialog.search().change()`；history → `HistoryDialog.vod()`。
  - 直播行：点行 → `HistoryDialog.live().manage()`；home → `LiveDialog.show(this)`；history → `HistoryDialog.live()`。
  - 壁纸行：点行 → `HistoryDialog.wall().manage()`；home → `setWallDefault()`（下一个默认壁纸 + `putWallType(0)` + `ConfigEvent.wall()`）；history → `setWallRefresh()`（`putWall(0)` + `WallConfig.get().load(cb)`）；history 长按 → `HistoryDialog.wall()`（`onWallHistory`）。
- 通用（7 行）：外观（值 = 浅色/深色/跟随系统）、增强（ROW_WIDGET）、播放设置（直接 `change(2)`）、弹幕、字幕（C1 分流）、AI、去广告。
- 账号与服务（2 行）：TMDB（值 = 已配置/未配置）、个人设置。
- 特色功能（6 行）：本地源、实验室、Git 云（值 = 账号数）、一键同步、Drive 检查（值 = 开/关）、站点健康（值 = 开/关 + 可选的 `样本数` 汇总；长按清空）。
- 数据与存储（5 行）：缓存（值 = 异步算出的缓存大小；点行一键清理，长按打开缓存管理面板）、备份、恢复、DoH（值 = 当前 DoH 名）、无痕模式（值 = 开/关）。
- 关于（1 行）：关于（值 = `AppVersion.fullName()`）。
- 隐藏设置（仅 `mHiddenEnabled` 时存在）：下载开关行（值 = 开/关；关掉会重新锁定隐藏设置并弹 `hidden_settings_disabled`）+ 下载列表容器。

行值来源（`setText()`）：点播/直播/壁纸描述分别来自 `VodConfig.getDesc()` / `LiveConfig.getDesc()` / `Setting.getWallDesc(WallConfig.getDesc())`；外观来自 `Setting.getThemeMode()`；TMDB 来自 `Setting.isTmdbReady()`；Git 云来自 `GitCloudAccountStore.list().size()`；Drive/无痕/下载是开关文案；站点健康来自 `SiteHealthStore.summary()`；DoH 来自 `VodConfig.get().getDoh()` + `Doh.objectFrom(Setting.getDoh())`；缓存见 §2.7。

### 1.2 下载列表（`refreshDownloadList` / `addDramaGroup` / `addEpisodeRow`）

- 只在隐藏分组已建且下载开关开时重建；否则容器为空。任务按剧名分组（`LinkedHashMap`，入队顺序），剧行 = 文件夹图标 + 剧名 + `download_drama_summary`（总数/已完成数）+ 删除按钮（长按出现，状态持久在 `mDeleteShownDownloadDramas` 里）；点剧行切换展开（状态在 `mExpandedDownloadDramas` 里），点剧行同时藏起删除按钮。
- 集行：集名 + 状态文案 + 失败原因单独一行（最多 3 行，截掉下载根路径前缀）+ 进度条（已暂停：有进度则定量显示否则隐藏；下载中：有进度定量否则转圈；解析中：转圈；其他：隐藏）。
- 点集行：已完成 → 播本地文件（文件缺失弹 `download_file_missing`）；失败 → `enqueue(...)` 重试（成功弹 `download_retry_started`）；已暂停 → `resumeTask`（弹 `download_resumed`）；排队/解析/下载中 → `pauseTask`（弹 `download_paused`）。
- 删除：确认框（`download_delete_title` / `download_delete_message`），确认后 `deleteDrama(name, ok -> ...)`，弹已删除/删除失败，刷新列表。
- 原生经 `VideoDownloadManager.Listener`（onResume 注册）刷新，约 500ms 一次。

### 1.3 入口（只改分流处）

`HomeActivity.change(1)` 是唯一 choke 点：底部导航设置项、子设置页返回、子页面 `openSetting()`、带 `nav_position` 的 intent（B5 深链）都走它。`restorePosition` 直接调 `changeFragment(position)`，绕过 `change(1)`，进程死亡恢复后仍是原生设置主屏（族内深度本来也不恢复，规划 §4.4 已接受）。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- `Setting.flutter_b7_enabled`，默认 false，独立于其他开关；开关 UI 只在实验屏（C0 屏）。
- 入口：mobile `HomeActivity.change(1)` 开头分流——B7 开 → `C1ScreenActivity.start(ROUTE_SETTINGS)`（裸 `/settings`，Dart 侧默认成员改 home），关 → 原生 `SettingFragment`，一行不删。
- 族内导航：`/settings` 族首页路由 + B7 开关；已有成员的路由与合约不动。

### 2.2 Pigeon 合约（`flutter_module/pigeons/b7_settings_home_messages.dart`）

`B7HostApi` + 5 个扁平数据类：`B7SettingsHome` / `B7SettingsGroup` / `B7SettingsRow` / `B7DownloadDrama` / `B7DownloadEpisode`。

- `getSettingsHome()`：整屏快照（7 组行 + 下载列表 + pendingRowId）。行上带 `key`（与 `SettingFragment.ROW_*` 同值字面量）、`member`（族成员 id，空表示非成员行）、`memberFlutter`（该成员当前是否 Flutter 屏）、`nativePage`（原生回退的 HomeActivity 页号，-1 表示无）。
- 行动作：18 个 `@async` 弹窗方法 + 同步开关/导航方法（见合约注释）。`@async` 一律照 C2/B2/B3/B4/B5 的写法：Java 侧 bool/int/double 装箱、方法名与 Dart 定义字符级一致、数据类扁平。

### 2.3 Dart 页（`flutter_module/lib/b7_settings_home_page.dart`）

- `SettingsHomeView`：族首页；`_B7MemberState` mixin 是 `_B3MemberState` 的按批复刻（含 resumed 重取、`afterDialog`、act）。
- 行去向：成员行且对应开关开 → 族内 `pushNamed('/$member')`；成员行开关关 → host `openNativePage(nativePage)`；播放器行（B6 跳过）恒走 `openNativePage(2)`；字幕/关于行走 host 的 C1 分流（`openSubtitle` / `openAbout`）；其余行按 key 走 host。
- 普通行用本地 `_HomeRow`（共享 `SettingsRow` 没有副标题槽，且本批作用域不碰共享组件；卡片形状与共享行一致）；配置行是本地 `_ConfigRow`（标题 + 值 + home/history 方按钮，壁纸 history 支持长按）。
- AppBar：标题 + 搜索按钮（B5 开 → 族内 `/search`，关 → host `openNativePage(10)`）；无 leading 返回按钮（原生首页也没有；系统返回经族壳 `_handleBack` 关屏）。
- 轮询：快照 `downloadsActive || cacheScanning` 时每 500ms 重取（B3 V2 模式）。
- pending row：首次加载成功后执行一次（`_pendingRowHandled` 守卫）：按 key 在快照里找行，复用 `_onRowTap`（即点行逻辑）。
- 下载列表：剧卡片 + 集行；展开/删除按钮显隐是 Dart 本地状态（对应原生的两个集合）；删除确认后从本地集合里清掉该剧名。
- 图标/圆圈色按 key 的 material 近似（B3 V8 模式）；配置行是本地 `_ConfigRow`（标题 + 值 + home/history 方按钮，壁纸 history 支持长按）。

### 2.4 Host 实现（`B7HostApiImpl.java`，约 1100 行）

- 快照：按 §1.1 的顺序建 7 组；值计算包在 `safeRow` 里（B3 模式：失败降级为 `error_config_get` + 标红）；缓存文本见 §2.7；下载列表按 §1.2 逐行复刻（含 `downloadSummary` / 状态文案 / 错误截断 / 进度条三态）。
- 配置应用：`applyConfig` / `loadConfig` / `loadCallback` 逐行复刻 `SettingFragment.setConfig`（含 file: URL 的 `PermissionUtil.requestFile` 分支、vod 切 live 的 `ConfigSyncPolicy.shouldSyncLive` 同步、`Notify.progress/dismiss`）。
- 跨屏导航恒走 host（规划 §4.4）：`openNativePage` 先 `finish()` 再 `openHomeAt(page)`（B5 模式：`CLEAR_TOP | SINGLE_TOP` + 字面量 `"nav_position"`）；`openAbout` / `openSubtitle` 是 C1 分流（开 → 先 start 再 finish，B5 模式；关 → 原生）。

### 2.5 弹窗契约（沿用 B3 V1）

`@async` 只在状态可能变时完成回调，Dart 不 await 只挂 `.then`：

- 有自身回调的：HistoryDialog/SiteDialog/LiveDialog（点选 → `setConfig`/`setSite`/`setLive`）、GitCloudDialog（Runnable 在 onDestroyView 跑，任何关闭都完成）、SiteHealthDialog（Runnable 只在 onPositive 跑）、ChoiceDialog（选了才完成）、WallConfig.load（成功才完成）、备份（成功才完成）、恢复（成功才完成）、删除剧集（真删了才完成）。取消一律不完成、无需刷新。
- 无回调的：见 §2.6。

### 2.6 弹窗销毁监听（本批新机制）

AppearanceDialog、LocalSourceDialog、CacheManagementDialog 没有任何变化回调，本批用 `FragmentManager.FragmentLifecycleCallbacks` 按类名监听销毁完成 `@async`：在 show 之前先注册（快关不会漏），触发后自注销。只用于这三处，其余弹窗仍用自身回调。

### 2.7 缓存文本（`setCacheText` 的复刻 + 有界轮询）

- 计算与原生一致：管理关 → `FileUtil.getCacheSize`（纯大小）；管理开 → `CacheCenter.requestSnapshot(false, ...)`（`cache_management_inline`，总量 + 配额），计算中显示 `cache_management_scanning`。`cacheStale` + 1s 节流避免 500ms 轮询时重复计算。
- 原生靠 `RefreshEvent.CACHE` 更新，Flutter 听不到：缓存操作（点行清理 / 长按管理）后，Dart 除正常重取外再每 1s 轮询快照最多 15 次，缓存文本一变就停。

### 2.8 mobile-only 类与资源（规划 §4.8）

- 全部 90+ 个 `R.string` 逐个按目录 grep 核查（main / mobile / leanback 分开查，不用 head 截断）：**全部在 main res**，不需要 `mobileString()`。两个曾疑似缺失的其实是笔误：`setting_one_key_sync` 与 `setting_drive_check` 都在 main res。
- mobile-only 弹窗（History/Site/Live/Appearance/LocalSource/Restore）走反射 + headless fragment（`B7HomeHostFragment`，实现 `ConfigListener` / `SiteListener` / `LiveListener`，B3 `DanmakuApiHostFragment` 模式；旋转后 FragmentManager 恢复它时每次重新挂 host 引用）。`SettingFragment.sPendingRow` 与 `requestRow` 的读取也是反射；leanback 下类不存在 → 优雅降级为空。
- 其余类（`VideoActivity`、`LabActivity`、全部下载/缓存/存储类、全部 main 弹窗）都在 main 源集或两味共有，直接引用。
- 电视端不动；不碰 Room/schema；不碰播放器；不改 `android-release.yml`；不引入新 Dart 依赖。

### 2.9 文件清单

新：`flutter_module/pigeons/b7_settings_home_messages.dart`、`flutter_module/lib/b7_settings_home_page.dart`、`app/src/main/java/.../flutter/B7HostApiImpl.java`、`docs/b8-b7-settings-home.md`。
改：`flutter_module/lib/main.dart`（`/settings` 默认成员改 home + `/settings?member=home` 路由 + B7 开关）、`flutter_module/lib/b3_settings_a_page.dart`（只加 `'home'` 成员与 `/home` case）、`flutter/C1ScreenActivity.java`（注册 B7HostApi）、`flutter/C0ShellActivity.java`（C0 引擎上注册，只为实验屏开关）、`setting/Setting.java`（`flutter_b7_enabled`，默认 false）、`app/src/mobile/.../ui/activity/HomeActivity.java`（只改 `change(1)` 分流处）、`.gitignore`（加 `B7Messages.java`）、`.github/workflows/b8-c0.yml`（Pigeon 步骤加 b7 生成 + 存在性检查，B5 同款）。`flutter_module/lib/widgets/settings_row.dart` 未改动（普通行用本页本地 `_HomeRow`）。

## §3 状态来源清单（全部 host 快照，Dart 不计算）

| 值 | 来源 |
|---|---|
| 各行标题/副标题 | `R.string.*`（main res） |
| 点播/直播/壁纸描述 | `VodConfig.getDesc()` / `LiveConfig.getDesc()` / `Setting.getWallDesc(WallConfig.getDesc())` |
| 外观值 | `Setting.getThemeMode()` → 浅色/深色/跟随系统 |
| TMDB 值 | `Setting.isTmdbReady()` → 已配置/未配置 |
| Git 云值 | `GitCloudAccountStore.list().size()` |
| Drive/无痕/下载值 | 开关文案 `setting_on/off` |
| 站点健康值 | `SiteHealthStore.summary()` + `Setting.isSiteHealthSort()` |
| 缓存值 | §2.7（异步，`cacheScanning` 驱动轮询） |
| DoH 值 | `VodConfig.get().getDoh()` + `Doh.objectFrom(Setting.getDoh())` |
| 关于值 | `AppVersion.fullName()` |
| 隐藏组显隐 | `Setting.isHiddenSettingsEnabled()` |
| 下载列表 | `VideoDownloadManager.get().getTasks()`（快照拷贝） |
| 下载进行中 | 任一任务 `isActive()` |
| pending row | 反射读 + 清 `SettingFragment.sPendingRow`（每引擎一次） |
| 搜索按钮去向 | `Setting.isFlutterB5Enabled()` |
| 各成员行去向 | 对应批的开关（B3/B4/B5/C1） |

## §4 逐项对照清单（Wei 真机验收用）

验收方法：实验屏开 B7 开关（其他批开关按需开），逐项与原生设置主屏对照；关 B7 开关 = 全回原生。

### 4.1 分组与行（25 行）

| 分组 | 行 | 点行去向 | 备注 |
|---|---|---|---|
| 站点 | 点播 | `HistoryDialog.vod().manage()` | home → 选站弹窗；history → 历史弹窗 |
| 站点 | 直播 | `HistoryDialog.live().manage()` | home → 选直播弹窗；history → 历史弹窗 |
| 站点 | 壁纸 | `HistoryDialog.wall().manage()` | home → 设为默认壁纸；history → 刷新壁纸；history 长按 → 壁纸历史弹窗 |
| 通用 | 外观 | 外观弹窗 | 值：浅色/深色/跟随系统 |
| 通用 | 增强设置 | B4 开 → `/enhance`；关 → 原生页 3 | |
| 通用 | 播放设置 | 原生页 2（B6 跳过，恒走原生） | |
| 通用 | 弹幕 | B3 开 → `/danmaku`；关 → 原生页 4 | |
| 通用 | 字幕设置 | C1 开 → `/subtitle`；关 → 原生页 6 | |
| 通用 | AI 设置 | B3 开 → `/ai`；关 → 原生页 8 | |
| 通用 | 去广告 | B3 开 → `/ad`；关 → 原生页 9 | |
| 账号与服务 | TMDB 设置 | B3 开 → `/tmdb`；关 → 原生页 7 | 值：已配置/未配置 |
| 账号与服务 | 个人设置 | B5 开 → `/personal`；关 → 原生页 5 | |
| 特色功能 | 本地源 | 本地源弹窗 | |
| 特色功能 | 实验室 | 原生 Lab 页 | |
| 特色功能 | Git 云同步 | Git 云弹窗 | 值：账号数 |
| 特色功能 | 一键同步 | 一键同步弹窗 | |
| 特色功能 | Drive 链接检查 | 开关切换 | 值：开/关 |
| 特色功能 | 站点健康 | 站点健康弹窗；长按清空（弹 `site_health_clear_done`） | 值：开/关 + 样本汇总 |
| 数据与存储 | 缓存 | 点行一键清理；长按打开缓存管理面板 | 值：异步缓存大小；管理关时不显示扫描文案 |
| 数据与存储 | 备份应用数据 | 先要文件权限，再跑备份进度弹窗 | 拒绝权限/备份失败不刷新 |
| 数据与存储 | 恢复应用数据 | 先要文件权限，再开恢复弹窗 | 拒绝权限/恢复失败/取消不刷新 |
| 数据与存储 | DoH | 单选弹窗 | 值：当前 DoH 名 |
| 数据与存储 | 无痕模式 | 开关切换 | 值：开/关 |
| 关于 | 关于 | C1 开 → `/about`；关 → 原生关于弹窗 | 值：版本号全名 |
| 隐藏设置 | 下载开关 | 开关切换；关掉重新锁定隐藏设置并弹提示 | 仅隐藏设置开启时出现；值：开/关 |

### 4.2 下载列表行

- 隐藏设置开 + 下载开关开时出现；下载开关关时列表为空（不显示空文本）；开关开但无任务时显示空文本。
- 剧行：点切换展开（同时藏起删除按钮），长按出现删除按钮；删除要确认，确认后该剧消失。
- 集行：已完成点播本地文件、失败点重试、已暂停点恢复、下载中/解析中/排队中点暂停；失败原因单独一行（截掉下载根路径前缀）；进度条三态（暂停定量/下载中定量或转圈/解析中转圈）。
- 下载进行中时进度/状态每 500ms 更新。

### 4.3 深链表（`performRow` 25 行 → pending row）

pending row 与点行同一处理：`vod/live/wall` → 对应管理弹窗；`appearance` → 外观弹窗；`widget`/`danmaku`/`ai`/`ad`/`account`/`personal` → 对应成员（开关开走族内，关走原生页）；`player` → 原生页 2；`subtitle` → 原生页 6（按 `performRow` 原样，不走 C1 分流，见 §7 V8）；`local_source`/`lab`/`gitcloud`/`onekey`/`drive`/`site_health`/`cache`/`backup`/`restore`/`doh`/`incognito`/`about`/`download` → 与点行一致（`about` 走 C1 分流，即 `onVersion`）。未知 rowId 忽略。

### 4.4 族路由与开关

- 裸 `/settings` 与 `/settings?member=home` → 族首页；`/settings?member=tmdb|ai|ad|danmaku|enhance|personal|search` 不动。
- 族内成员间跳转走 Dart Navigator（首页 ↔ 各成员），返回逐级回首页，再返回关屏；关屏后回到首页 Tab（原生从设置首页返回也是回首页 Tab）。
- B7 开关只在实验屏；关 = 设置主屏回原生，原生实现一行不删。

## §5 回滚

- 按开关：关 `flutter_b7_enabled` → 设置主屏回原生，零行为变化。
- 按批：revert 本批提交（共享文件只含加法行，`main.dart` / `b3_settings_a_page.dart` / `settings_row.dart` / `C1ScreenActivity` / `C0ShellActivity` / `Setting.java` / `HomeActivity` / `.gitignore` / `b8-c0.yml` 均可独立回退）。
- 按分支：丢弃 `b8-flutter-rewrite` 分支。
- 无数据层改动，无需数据回滚。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

- **G1（最担心）Pigeon 代码生成 + 手写 impl 签名**：33 个方法 + 5 个数据类 + `List<>` 返回 + `Long` 字段 + `@async VoidResult`，只有 CI 的 Pigeon 生成 + Gradle 编译能证明。尤其 6 处反射链（History/Site/Live/Appearance/LocalSource/Restore）的方法名、静态/实例、参数类型须逐字对 CI 日志（C1 `setSubtitleAutoMatch` 教训：Pigeon 方法名原样保留，绝不推断大小写）。
- **G2（最担心）Flutter→Flutter 跨屏导航的引擎复用竞态**：`openAbout` / `openSubtitle` 在 C1 开时先 start 后 finish（B5 同款写法）；新 Activity 的 `ensureFlutterEngine` 可能复用旧引擎旧路由，随后旧 Activity `onDestroy` 销毁引擎。B5 的 subtitle 深链路径有同一风险，两处都未经设备验证；若复现，需在 `C1ScreenActivity` 加 relaunch 机制（跨批修复，本批不做）。
- **G3 四味 release 构建**：impl 在 main 源集，全部 `R.string` 已核 main res；反射只在 mobile 跑；leanback 构建仍需 CI 证明（C0/C1 的 4 味成功不覆盖本批新增代码）。
- **G4 反射与 R8/minify**：6 处反射 + 1 处 Proxy 在 release 构建下是否存活（ProGuard 有 `-keep com.github.moyingshi.osc.tv.**`，但仍需 CI + 设备验证；失效时对应弹窗打不开，可关开关回退）。
- **G5 缓存文本时序**：有界轮询（15×1s）能否覆盖后台清理完成的时刻；`CacheCenter.requestSnapshot` 回调线程与 `FileUtil.getCacheSize` 回调线程是否都在主线程。
- **G6 权限分支**：`requestFile` 用 Activity overload（B4 V9 模式）；legacy 存储路径走 `onRequestPermissionsResult`，而 `C1ScreenActivity` 在 flutterFragment 非空时不调 super——旧设备/无文件管理器时可能挂起。常见路径（已授权直接短路）与 MANAGE 路径（经 `onActivityResult`→super）不受影响。
- **G7 下载列表轮询等价性**：500ms 轮询 vs 原生 `VideoDownloadManager.Listener` 事件驱动；暂停/恢复/重试后状态是否及时刷新；`getTasks()` 快照拷贝在轮询下的开销。
- **G8 pending row 时序**：首帧加载后执行一次；若用户在族内切走又回来不重放（与原生 `consumePendingRow` 语义一致）；`sPendingRow` 字段名若上游改动，反射失败只丢深链、不影响首页。

## §7 相对规划的全部偏差

- **V1** 行渲染：每行独立卡片（普通行用本页本地 `_HomeRow`，共享 `SettingsRow` 没有副标题槽且本批不碰共享组件；卡片形状与共享行一致），而非原生每组一张圆角卡片 + 组内 80dp 左边距分隔线；分组标题沿用 `SettingsGroupHeader`。
- **V2** 标题：原生大标题 30sp 加粗 + 40dp 搜索按钮；Flutter 用标准 AppBar（标题 + 搜索按钮），与族内成员页一致。
- **V3** 图标/圆圈色为 Dart 侧 material 近似（B3 V8 模式）；配置行值用结尾省略（Flutter 无中间省略）；集行错误文案用主题 error 色（原生颜色未逐像素核对）。
- **V4** 壁纸行按钮分工以源码为准：home = 设默认壁纸（同步），history = 刷新壁纸（`@async`），history 长按 = 壁纸历史弹窗。发现时纠正了规划笔记的误记（曾误写作 history 长按设默认）。
- **V5** 销毁监听机制（新，见 §2.6）：无回调的弹窗用 FragmentManager 生命周期监听完成 `@async`；此前各批只用弹窗自身回调。
- **V6** 缓存文本：原生经 `RefreshEvent.CACHE` 更新；Flutter 用快照 + 轮询（§2.7）；管理开/关两条计算路径与 `setCacheText` 一致。
- **V7** 搜索按钮：B5 开时族内 `pushNamed('/search')`，关时 host `openNativePage(10)`；原生是 `change(10)` 原地切换，Flutter 须离屏再进。
- **V8** pending row 的 subtitle 深链：按 `performRow` 原样走原生 `change(6)`，不走 C1 分流（C1 冻结行为）；而 B5 的搜索结果入口对 page 6 做 C1 分流——两条路径行为不同，以原生为准。
- **V9** 进程死亡恢复：`restorePosition` 绕过 `change(1)` 分流，恢复后仍是原生设置主屏（规划 §4.4 已接受族内深度不恢复）。
- **V10** `openAbout` / `openSubtitle` 的 C1 开路径沿用 B5 的先 start 后 finish，带 G2 竞态（见 §6）；本批不另做 relaunch 机制。

## Recovery anchor

下一步恰好一个：**B8**（分类列表：`TypeFragment` + `FolderFragment` + `FolderActivity`，首个联网屏，见 `docs/b8-full-migration-plan.md` §3）。
