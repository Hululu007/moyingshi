# B15 本地文件 + 应用图标（B8 全量迁移）

> §0 执行声明：本批把手机端本地文件页（FileActivity，约 161 行）与应用图标页（AppBrandingActivity，69 行）迁移到 Flutter，路由 `/local?selectDir=&readMode=` 与 `/branding`（C1 引擎）。
> 任务护栏 `b8-b15-local-files-branding`（standard），分支 `b8-flutter-rewrite`，基线 B14 分流修复提交 `bae53e51`。
> 开关 `flutter_b15_enabled` 默认关，独立于其他开关；开关 UI 只在实验屏。关 = 两页回原生，原生实现一行不删。
> **本机无 Java / Android / Flutter SDK，构建与运行均未验证**（CI 联编 + 真机验收时按 §6 逐项证明）。不推送。

## §1 原生实现（只读发现）

### 1.1 本地文件页

原生实现：`app/src/mobile/java/.../ui/activity/FileActivity.java`（161 行，mobile 源集；leanback 另有自己的 FileActivity，TV 不动）+ `app/src/mobile/java/.../ui/adapter/FileAdapter.java`（mobile 源集，窗口化渲染）。

- **形态**：结果回传型 picker，不是独立浏览屏。`onItemClick`：目录 → 进目录；read_mode + 可读扩展名 → `WebReaderActivity`（EXTRA_LOCAL_PATH）；否则 `setResult(RESULT_OK, Uri.fromFile(file))` + finish。select_dir 模式下列表只含目录，首行是“选择当前目录”（`lut_select_current_dir`）→ `onCurrentDirClick` 回传当前目录。返回键：根目录退出页，否则回父目录。
- **数据**：`Path.root()` 起点；非 select_dir 时 `Path.list(dir)`，select_dir 时 `dir.listFiles(File::isDirectory)` + `Path.sort`。标题栏显示当前目录绝对路径（start 方向省略）。空态是 ProgressLayout 的“标题 + 解释 + 一个主动作”，主动作是“重新扫描”（`file_empty_action` → `rescan()`，即重调唯一的列表填充入口 `update(dir)`）。
- **权限**：`initView` 里 `PermissionUtil.requestFile(this, allGranted -> update(Path.root()))`——注意 allGranted 被忽略，**无论授权与否都列根目录**（无权限时根目录列出来是空的，落空态）。
- **行渲染**：`adapter_file.xml`，名 + `ic_folder` / `ic_file` 图标，无缩略图（规划：无图）。FileAdapter 用窗口化渲染（WINDOW_SIZE 128，首屏前后各 64 行，滚动时前后扩展 32 行），大目录不一次建全部行。
- **可读扩展名**（`isReadable`）：.txt/.html/.htm/.epub/.zip/.pdf/.jpg/.jpeg/.png/.webp/.gif/.bmp。

### 1.2 入口（本地文件）

- **漏斗 `FileChooser`**（main 源集）：`show()` 先试系统文件管理器（ACTION_OPEN_DOCUMENT），无可用解析器 / leanback / frameworkpackagestubs 时才回落到内置 FileActivity；`showDirectory()` 恒走内置 FileActivity。调用方：PluginManagerActivity（选插件目录，B19 范围）、CatWebActivity（上传）、LabActivity（本地源目录）、VideoActivity（LUT 文件/目录、卡拉 OK 音轨）、ConfigDialog（配置导入）。全部走 `ActivityResultLauncher<Intent>`，等 `RESULT_OK` + Uri 回传（`FileChooser.getPathFromUri` / `getPathsFromIntent` 解析）。
- **本地阅读器行**：B4 增强设置 `B4HostApiImpl.openLocalReader()` 与 mobile `SettingEnhanceFragment.setLocalReader()`，都是 `new Intent(FileActivity.class)` + `read_mode=true`。前者是 Flutter B4 页的入口，后者是原生增强设置页的入口。
- **TV**：leanback 有自己的 FileActivity + FileChooser 调用方（HomeActivity/VideoActivity/ConfigDialog），全部不动。

### 1.3 应用图标页

原生实现：`app/src/main/java/.../ui/activity/AppBrandingActivity.java`（69 行，main 源集，与 TV 共用）+ `AppBranding`（main 源集）+ 布局 `app/src/main/res/layout/activity_app_branding.xml`。

- **形态**：标题 `setting_app_branding`；说明 `app_branding_icon_select`；两个选项横排（64dp 预览图 + 文案）：当前图标（`@mipmap/ic_launcher`，文案 `app_branding_icon_current`）与历史图标（`@drawable/ic_launcher_history`，文案 `app_branding_icon_history`）；选中项容器 selected、文案变色（`display_option_bg_selected` vs 白色）；底部取消（`dialog_negative`）/ 保存（`dialog_positive`）。
- **保存**：`AppBranding.putIconMode(mode)` + `applyLauncherIcon(this)`（切换 HomeActivity 的 Current/History 两个别名组件的启用态）+ `setResult(RESULT_OK)` + 以 `CLEAR_TASK | NEW_TASK` 启动 `launcherIntent`（即重启进桌面别名对应的 HomeActivity）+ finish。旧自定义值由 `normalizeIconMode` 归一到当前图标。
- **入口**：`AppBrandingActivity.start(Activity)`，唯一入口。调用方三处：mobile `SettingPersonalFragment:251`（个人设置行）、`B5HostApiImpl.openAppBranding()`（Flutter B5 页的同一行）、leanback `SettingPersonalActivity:264`（TV 个人设置，TV 不动）。

### 1.4 字符串与资源归属（按目录分别 grep 验证，无 head 截断）

- `file_empty_title` / `file_empty_description` / `file_empty_action`、`lut_select_current_dir`、`setting_app_branding`、`app_branding_icon_select`、`app_branding_icon_current` / `app_branding_icon_history`、`dialog_negative` / `dialog_positive`、`error_play_timeout` / `error_retry`——全部只在 `app/src/main/res/values*`，mobile/leanback res 里一个都没有。所以 impl 里直接 `R.string` 引用是 leanback 安全的，无需 `mobileString()`。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- `Setting.flutter_b15_enabled`，默认 false，独立开关；开关 UI 只在实验屏（C0 屏新加一行）。
- **本地文件**：分流在 `FileChooser.localBrowserIntent(selectDir)`——`show()` 的回落分支与 `showDirectory()` 都走它；开关开且非 leanback 时启动 C1ScreenActivity 的 `/local` 路由，否则原样启动 FileActivity。系统文件管理器路径不动（原生也只有回落分支才用 FileActivity）。
- **本地阅读器行**：`B4HostApiImpl.openLocalReader()` 分流（B6→B7 同款预授权分流，见 §5 V3）：开关开时 `C1ScreenActivity.start(ROUTE_LOCAL + "?selectDir=false&readMode=true")` + `activity.finish()`；关时原样。原生 `SettingEnhanceFragment.setLocalReader()` 不动（B4 开关关时仍走它）。
- **应用图标**：分流在 `AppBrandingActivity.start()` 本身（唯一入口，三处调用方全覆盖）：开关开且非 leanback 时启动 C1ScreenActivity 的 `/branding` 路由；调用方是 Flutter 屏（B5 个人设置页）时再 `finish()`（B7 openAbout 同款），调用方是原生 Activity 时只 start 不 finish。TV 调用方被 `Util.isLeanback()`（BuildConfig 风味判断）挡回原生。
- 快照方法本身不判断开关（与 B8/B14 同款）：快照只在 Flutter 页内被调用，入口即防线。

### 2.2 快照与完成规则（本批核心设计）

- `getLocalFiles(dirPath, selectDir, readMode)`：@async 一次性快照（`Task.execute` 后台列目录 + 主线程完成），35s 看门狗；超时按 B8 惯例**成功返回错误快照**（`errorText = error_play_timeout`），Dart 显示错误态 + 重试。`dirPath` 为空即根目录（`Path.root()`）。目录导航是纯 Dart：点目录行时 Dart 再调一次本方法。
- **权限**：首次调用时先 `PermissionUtil.requestFile`（B4 `openCustomCsp` 同款，`FragmentActivity` 直调），回调后无论授权与否都列目录（原生 `checkPermission` 同款：allGranted 被忽略）；同一引擎生命周期内只申请一次（`permissionRequested` 标志，原生 `initView` 只调一次的对应物）。看门狗 35s 同样覆盖权限等待（见 §5 V1）。
- **结果回传**：`openLocalItem` / `returnCurrentDir` 是同步 fire-and-forget（B14 `continuePlay` 同款）：非阅读模式的文件点按与 select-dir 首行点按时，host `setResult(RESULT_OK, Uri.fromFile)` + `finish()`，调用方的 `ActivityResultLauncher` 收到与原生完全一致的结果；read_mode + 可读文件时 host 启动 `WebReaderActivity`，Flutter 页留在下面（返回后 resumed 重取，与原生一致）。
- `getBranding()`：@async 快照（本机同步读 `AppBranding.getIconMode` + 文案，主线程直接完成），35s 看门狗兜底。
- `saveBrandingIcon(mode)`：同步 fire-and-forget，原生 `AppBrandingActivity.save` 逐行复刻（putIconMode + applyLauncherIcon + launcherIntent 重启 + finish）；Dart 不 await 结果。
- 无 EventChannel；刷新 = 首次加载 + resumed 重取 + 下拉刷新 + 空态“重新扫描”按钮（原生 rescan 的对应物）。

### 2.3 Pigeon 合约（`flutter_module/pigeons/b15_local_messages.dart`）

- `B15HostApi`：`isB15Enabled` / `setB15Enabled`；@async `getLocalFiles(String dirPath, bool selectDir, bool readMode)` → `B15LocalScreen`；同步 void `openLocalItem(String path, bool readMode)` / `returnCurrentDir(String path)`；@async `getBranding()` → `B15BrandingScreen`；同步 void `saveBrandingIcon(int mode)`。
- `B15LocalScreen`：currentPath（标题栏）、parentPath、isRoot、selectDir、readMode、selectCurrentDirLabel、emptyTitle/emptyDescription/emptyActionLabel、retryLabel、errorText、items。
- `B15LocalItem`：name、path、isDirectory（无图，不复用 PicSpec——规划已定无图）。
- `B15BrandingScreen`：title、selectLabel、currentLabel、historyLabel、selectedMode（0 当前 / 1 历史）、cancelLabel、saveLabel、errorText、retryLabel。

### 2.4 Dart 页（`flutter_module/lib/b15_local_page.dart`，两页共一文件）

- `B15LocalPage(selectDir, readMode)`：AppBar（返回 + 当前目录绝对路径，start 方向省略）→ `RefreshIndicator` + 列表/空态/错误态。select-dir 模式首行是“选择当前目录”行（文件夹图标）。行：文件夹/文件 Material 图标 + 名。返回键经 `PopScope` 拦截：非根目录回父目录，根目录 `closeScreen()`（无结果退出 = RESULT_CANCELED，与原生一致）。
- `B15BrandingPage`：AppBar（返回 + title）→ 说明文案 → 两选项横排（64dp 图标 + 文案，选中项边框加粗 + 文案变主色）→ 底部取消（TextButton）/ 保存（FilledButton）。保存后 host 重启应用，Dart 不 await。
- 两页都用 `WidgetsBindingObserver` 的 resumed 重取（C2/B2 同款）。

### 2.5 Host 实现（`B15HostApiImpl.java`）

- `buildScreen`：`Path.root()` / `Path.list(dir)` / select-dir 的 `listFiles(File::isDirectory)` + `Path.sort` 全部照抄原生 `FileActivity.list`；`isReadable` 照抄原生扩展名表；`startReader` 照抄原生（WebReaderActivity + EXTRA_LOCAL_PATH，不 finish）。
- `Completion` 看门狗：B14 同款逐字复制（B15 结果类型）。
- `saveBrandingIcon`：原生 `AppBrandingActivity.save` 逐行复刻。
- B15 字符串全部 main-res（§1.4），直接 `R.string` 引用，leanback 安全。

### 2.6 文件清单

- 新 4 个：`flutter_module/pigeons/b15_local_messages.dart`、`flutter_module/lib/b15_local_page.dart`、`app/src/main/java/com/github/moyingshi/osc/tv/flutter/B15HostApiImpl.java`、本文档。
- 改 9 个：`flutter_module/lib/main.dart`（`/branding` 字面路由 + `/local` 的 onGenerateRoute 分支 + B15 开关）、`C1ScreenActivity.java`（`ROUTE_LOCAL` / `ROUTE_BRANDING` + 注册 B15HostApi）、`C0ShellActivity.java`（注册 B15HostApi，仅开关用）、`Setting.java`（`flutter_b15_enabled`）、`app/src/main/java/.../utils/FileChooser.java`（`localBrowserIntent` 分流）、`B4HostApiImpl.java`（`openLocalReader` 分流，§5 V3）、`app/src/main/java/.../ui/activity/AppBrandingActivity.java`（`start` 分流，§5 V4）、`.gitignore`（`B15Messages.java`）、`.github/workflows/b8-c0.yml`（Pigeon 步骤加 b15 生成 + 存在性检查）。
- 未动：`FileActivity`（两端）/ `FileAdapter` / `AppBranding` / `WebReaderActivity` / TV 入口 / C0–C2/B2–B14 合约与实现（除 B4 一处 §5 V3 分流）/ 共享组件（本批不用 VodCard/PicImage，无图）。

## §3 状态来源清单（全部 host 快照，Dart 不计算）

| 显示项 | 来源（host） |
|---|---|
| 当前目录、父目录、是否根目录 | `Path.root()` + `File.getAbsolutePath()` / `getParentFile()` |
| 目录条目、顺序 | `Path.list(dir)`；select-dir 时 `listFiles(isDirectory)` + `Path.sort` |
| 空态三文案 + 重扫动作 | `file_empty_title` / `file_empty_description` / `file_empty_action` |
| “选择当前目录”行文案 | `lut_select_current_dir`（仅 select-dir 模式） |
| 可读扩展名判定、阅读器启动 | `isReadable`（原生表）+ `WebReaderActivity.EXTRA_LOCAL_PATH` |
| 图标选项文案与当前选择 | `AppBranding.getIconMode()` + `app_branding_icon_current` / `app_branding_icon_history` |
| 图标页标题/说明/取消/保存 | `setting_app_branding` / `app_branding_icon_select` / `dialog_negative` / `dialog_positive` |
| 错误/重试文案 | `error_play_timeout` / `error_retry` |
| 权限 | `PermissionUtil.requestFile`（host 侧，首次调用一次） |

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 本地文件浏览

- [ ] 开关开后，插件管理“选目录”、Lab 本地源目录等入口进 Flutter 文件页；开关关回原生 FileActivity。
- [ ] 列表内容、顺序与原生一致（同一目录、同一排序）；目录行点按进子目录，标题栏显示绝对路径。
- [ ] 空目录显示原生空态三文案 + “重新扫描”按钮，点后重列当前目录。
- [ ] 返回键：子目录回父目录，根目录退出页面。
- [ ] 下拉刷新重列当前目录；从阅读器返回后列表刷新。

### 4.2 选择器回传（重点）

- [ ] 选文件后调用方拿到文件（插件目录选择、配置导入、LUT 选择等与原生同表现）；选目录模式下首行“选择当前目录”回传当前目录。
- [ ] 取消（根目录返回）时调用方收到取消，与原生一致。
- [ ] 有系统文件管理器的手机上，`show()` 仍走系统选择器（与原生一致，Flutter 只替换回落分支）。

### 4.3 本地阅读器模式

- [ ] 增强设置“本地阅读器”行（B4 开关开 + B15 开关开）进 Flutter 文件页；点 .txt/.pdf/.jpg 等可读文件进原生阅读器，返回后回到文件列表。
- [ ] 点不可读文件时回传该文件（与原生一致）；B15 开关关时该行回原生 FileActivity。

### 4.4 应用图标

- [ ] 个人设置“应用图标”行（B5 开关开 + B15 开关开）进 Flutter 图标页；两个选项与当前选择和原生一致。
- [ ] 点保存后图标切换并重启进首页（与原生一致）；点取消直接返回，不改图标。
- [ ] B15 开关关时该行回原生 AppBrandingActivity。

### 4.5 开关与回滚

- [ ] 实验屏 B15 开关开/关立即生效（下次进两页）。
- [ ] 开关关 = 两页回原生，功能全量正常。
- [ ] TV 上两页始终原生（leanback 守卫）。

## §5 相对规划的全部偏差

- **V1（权限等待进 35s 看门狗）**：规划 §4.6 要求每个 @async 在一切路径完成回调；原生权限申请无超时。本批把权限等待包进 `getLocalFiles` 的 35s 看门狗——用户在系统“全部文件访问”设置页停留超过 35s 时，Dart 先显示错误态 + 重试；用户授权回来后点重试即可（`PermissionUtil` 此时已 short-circuit，直接列目录）。
- **V2（系统选择器路径不动）**：`FileChooser.show()` 有系统文件管理器时仍走系统选择器，Flutter 只替换无解析器时的回落分支——与原生一致（原生也只有回落分支才用 FileActivity）。因此 B15 开关开时，在有系统文件管理器的手机上 `show()` 的表现与原生完全相同。
- **V3（B4 本地阅读器行分流）**：`B4HostApiImpl.openLocalReader()` 加 3 行分流（B6→B7 同款预授权分流：只改分流处，开关关时行为逐字不变）。Flutter B4 页的该行由此进 Flutter 文件页。返回时落在原生增强设置页（start-then-finish 的 B7 同款结果），不是 Flutter 增强设置页；G2 跨屏竞态风险同款（B7 文档 §5 V10），若复现需跨批修。
- **V4（应用图标入口分流）**：`AppBrandingActivity.start()` 加分流（B6→B7 同款：唯一入口，三处调用方全覆盖，开关关 / TV 时行为逐字不变）。调用方是 Flutter 屏（B5 个人设置页）时 start-then-finish（B7 openAbout 同款），返回时落在原生个人设置页；调用方是原生 Activity 时只 start 不 finish。G2 风险同款。
- **V5（图标预览近似）**：原生两选项显示真实启动器图标（mipmap ic_launcher / drawable ic_launcher_history）；Dart 用 `Icons.apps` / `Icons.history` 的 64dp 图标近似（Dart 无法直接渲染 Android drawable）。
- **V6（选中态近似）**：原生选中项是容器 selected + 文案变色（`display_option_bg_selected`）；Dart 用加粗边框 + 文案变主色近似。
- **V7（无窗口化渲染）**：原生 FileAdapter 只渲染视口前后 128 行窗口；Dart 用 `ListView.builder` 惰性构建（等价的惰性机制，不是一次建全部行），大目录行为待真机观察。
- **V8（行图标近似）**：原生行图标是 `ic_folder` / `ic_file` drawable；Dart 用 `Icons.folder` / `Icons.insert_drive_file` 近似。
- **V9（权限只申请一次）**：原生 `checkPermission` 在 `initView` 只调一次；本批 `permissionRequested` 标志保证同一引擎生命周期内只申请一次（与原生一致）。引擎销毁重进后再次申请（原生重进 Activity 同样再次申请）。
- **V10（无其他偏差）**：开关默认关、只实验屏有 UI、TV 不动、无 schema 变化、无新 Dart 依赖、无图（不复用 PicImage/VodCard）、全部文案 host 返回、结果回传与原生同契约。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon 代码生成（CI 唯一可证）**：`@async` 返回含 `List<B15LocalItem>` 的快照 + 同步 void 方法（`openLocalItem` / `returnCurrentDir` / `saveBrandingIcon`）；手写 Java 的 Builder/Result 签名是否与 CI 生成的 `B15Messages.java` 一致。CI 若报错，按文件路径归属本批修。
2. **结果回传（设备验收，重点）**：经 FileChooser 选文件/选目录后，调用方（插件管理、配置导入、Lab 本地源、LUT 选择）是否拿到与原生一致的路径；取消时是否收到取消。`C1ScreenActivity.setResult` + `finish()` 经 `ActivityResultLauncher` 的路径是本仓库首次。
3. **权限流程（设备验收）**：首次进文件页弹系统权限窗（全部文件访问 / 读写权限，按系统版本）；拒绝后仍显示空态（与原生一致）；授权后重试/重进能列出文件；35s 看门狗触发后的重试表现（见 V1）。
4. **阅读器模式（设备验收）**：B4 行进 Flutter 文件页；可读文件进 WebReaderActivity，返回后回到文件列表；不可读文件回传。
5. **应用图标切换（设备验收）**：保存后图标切换 + 应用重启进首页；取消不改；从 Flutter 个人设置页进图标页并保存后，返回落点是原生个人设置页（V4 的已知变化）。
6. **G2 跨屏竞态**：B4 行与 B5 行的 start-then-finish 是否复现路由串位（B7/B11 V10 同款风险）；复现时需在 `C1ScreenActivity` 加重启机制（跨批修复，本批不做）。
7. **大目录滚动**：上千文件的目录滚动是否流畅（V7 的惰性构建对照原生窗口化）。
8. **图标近似目视**：两选项预览图与选中态、行图标与原生逐项比对（V5/V6/V8）。

## Recovery anchor

- 目标：B15 手机端本地文件页 + 应用图标页迁移到 Flutter（`/local?selectDir=&readMode=` 与 `/branding` 路由 + B15HostApi），开关 `flutter_b15_enabled` 默认关，不推送。
- 状态：本地实现完成（护栏 `b8-b15-local-files-branding`），待 `finish` 提交 + 恢复标签；main 仍为 `f39a6e0`；工作树待验证干净。
- 文件：新 4 个（合约、Dart 页、Host impl、本文档），改 9 个（main.dart 路由 + 开关、C1/C0 注册、Setting 开关、FileChooser 分流、B4 openLocalReader 分流、AppBrandingActivity.start 分流、.gitignore、b8-c0.yml）。
- 未验证：构建与运行（本机无 SDK），按 §6 逐项留给 CI 联编 + Wei 真机验收。
- 下一步：B16 观看报表迁移实施。
