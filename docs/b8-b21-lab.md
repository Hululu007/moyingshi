# B21 实验室列表/详情（B8 全量迁移）

## §0 执行声明

- 分支 `b8-flutter-rewrite`，基底 HEAD `abfbe386`（B20 提交），main 不动（`f39a6e0`），不推送。
- 任务护栏：`b8-b21-lab`（standard），一个 guard 会话 = 一次提交 + 一个恢复标签。
- 完成句：把手机端实验室列表/详情（`LabActivity` 约 940 行 + `LabDetailActivity` 约 435 行，主源码集，手机与 TV 共用）迁移到 Flutter，终端/输出留原生。
- 本机无 Java / Android / Flutter SDK：构建、Pigeon 生成、`flutter analyze`、运行均未验证，全部留给联合 CI 构建 + Wei 真机验收。

## §1 原生实现（只读发现）

### 1.1 列表页 `LabActivity`（940 行，主源码集）

- 数据来源：`LabConfig.getLabRoot()`（缓存根；`reload(LoadCallback)` 在独立 executor 上 `loadSync()`，回调 `App.post` 回主线程；源为本地文件或网络 URL）。`reload()` = `LabEnv.syncVodPlusAssets` + `LabConfig.reload` + `autoInstall`（`auto_install && available && 未安装` 的包静默 `LabActions.install`，每 Activity 一次 attempted 集）。
- 分组（`render()` 顺序）：`all`（“全部”，硬编码）→ `__local_source__`（仅手机，`!Util.isLeanback()`，名 `setting_local_source`）→ `__miniapp__`（仅手机 + `Setting.isHkMiniapp()`，名“海阔小程序”硬编码）→ lab.json 分组（文件顺序）。包按 `show` 过滤，分组过滤（`all` 全显）。
- 卡片（`LabPackageAdapter`）：标题 = `name + displayVersion`（arch 匹配的 download 版本，否则 `item.version`）；图标 Glide；大小 = arch 匹配的 download 的 `size`（`LabEnv.findDownload`，包私有）；状态：未上线 / 运行中 / 已安装 / 未安装（运行只看内置命令）；`hasNewVersion` 时显示新版本角标。点/长按都进详情。
- 工具栏（`menu_lab`）：帮助（about 对话框）、`+`（按当前分组：本地源分组开本地源弹窗、海阔分组开小程序管理页、其余开导入包对话框）、设置（配置源对话框）、扫描目录（仅本地源分组可见）。
- 导入包：粘贴对话框（`VodPlusLabConfig://` 前缀 base64 解码，否则原文；无效 toast“无效的导入数据”并不关框）+ 文件选择（`PermissionUtil.requestFile` + `ACTION_OPEN_DOCUMENT` → 拷到 cache + `LabEnv.importPackage` 后台线程）。
- 配置源对话框：本地/URL 下拉 + URL 或本地路径（只读，文件夹按钮 `ACTION_OPEN_DOCUMENT`，`REQUEST_LOCAL_CONFIG`，`takePersistableUriPermission` 失败则拷到 `lab_local_config.json`，随后 `setSource(SOURCE_LOCAL)`、关框、reload）+ 根目录覆盖 + 前台/电池/全局代理开关（代理端口默认 7890）+ 保存后 `LabProcManager.updateService()` + reload。前台开时要通知权限，电池开时跳系统电池优化设置。
- 本地源分组：`LocalSource.getSites()` 去掉小程序；点一条设为 home 站源 + `finish()`；行末删除只删一条 + `reloadLocalConfig()`（`VodConfig.load` + `ConfigEvent.vod()`）；扫描目录用 `FileChooser` 目录选择器 + 后台 `LocalSource.scan`（跳过大文件夹只提示一句，pruned 数第二句提示）。
- 小程序分组：`LocalSource.getSites()` 过滤 `LocalSource.isMiniapp`；点一条开 `.hk_list` 隐式 intent（带 `LocalSource.miniappTitle`）；删除同本地源。
- 手机独占手势：内容区左右滑动切分组（fling 优先，位移阈值 120dp 与 1/4 屏宽取大者）、悬浮 tune 按钮开分组面板；TV 上不挂手势、不显示按钮。
- `onResume`：Hk 开关变了重渲染；小程序分组时刷新内嵌列表。
- `EXTRA_SHOW_RUNNING`：通知 intent 直接 `new Intent(this, LabActivity.class).putExtra(...)`（`LabRuntimeService:86-88`），不走 `start()`。

### 1.2 详情页 `LabDetailActivity`（435 行，主源码集）

- `reload()` = `LabConfig.reload` → 按名 `findItem` → `applyCommandOverrides` → `bind()`；找不到 toast“找不到模块”并 finish；加载失败 toast“配置加载失败”并 finish。
- 头部：标题 = 包名；版本行 = `name + displayVersion`；info；Glide 图标。
- 按钮（`updateButtons`，2 秒轮询 handler）：下载按钮文案“下载安装”/“更新”（已安装且有新版本时），已安装且无更新时隐藏；卸载仅已安装时显示；状态：运行中 / 已安装 / 未安装。命令列表只走 `LabCommandAdapter.refreshStates()` 差异刷新（整表刷新会把遥控焦点踢回工具栏）。
- 下载：不可用 toast“该包暂未上线”；已安装且有新版本弹“确认更新”对话框；否则 `LabActions.installWithDialog`。卸载：弹“确认卸载”对话框 → `LabActions.uninstall`（先停掉全部运行中的命令）。
- 终端按钮 → `LabTerminalActivity.start(this, name, name)`。添加命令 → `LabCommandEditDialog.show(this, item, null, reload)`。刷新命令 → 确认对话框 → `LabActions.stopItem` + `clearCommandCache` + `clearCommandOverrides` + `reload`。
- 命令行（`LabCommandAdapter`）：内置命令文件顺序 + 自定义命令（`LabCustomCommands.list`，id `custom_…`，转成 `LabModels.Command` 的字段与 adapter 一致）；运行态 = `LabRunner.isRunning(item.name + "/" + command.id)`；行按钮文案 `lab_stop` / `lab_run`；点行 → 自定义直接 `runCustom`，不支持（`min_version`）toast，未支持外开 `LabCommandSheet`；行按钮 → 运行中则停止（`lab_command_stopped`），否则开 sheet 并 run；长按行/按钮 → 编辑对话框。
- 菜单 `menu_lab_detail` 只在 `item.hasSettings()` 时 inflate：导出（`lab_export_file`）+ 设置（`nav_setting`）。导出 = `LabEnv.exportPackage` 后台线程 + 进度对话框（`LabActions.hideCloseButton` / `showCloseButton`）；未安装 toast“该包尚未安装”。包设置对话框 = 每 `LabModels.Setting` 一行（text / number / select / switch / directory 五种；值 = `loadUserSettings` 存量否则默认值；保存 `saveUserSettings` + toast“设置已保存”）。
- `onActivityResult` 只转发给 `LabFilePicker.onActivityResult`（目录型设置行的 SAF 兜底，请求码自 3000 起）。

### 1.3 终端/输出交界（留原生，P4）

- `LabTerminalActivity`（321 行）：交互式 `/system/bin/sh`，流式 I/O；入口 `start(Context, title)` / `start(Context, title, packageName)`。详情页终端按钮、命令 sheet 的终端入口都调它。
- `LabOutputActivity`（188 行）：`LabRunner.getLog` 流式日志 + 静态 `appendGlobal` / `onExitGlobal`；命令 run 时由 `LabActions` / `LabCommandSheet` 在 host 侧打开。
- Flutter 页的“打开终端”只调 host 打开原生 `LabTerminalActivity`；命令执行与输出查看全部 host 侧原生对话框/Activity；Dart 内不做流式 I/O，无 EventChannel。

### 1.4 入口与调用方（全部，grep 验证）

| # | 调用方 | 位置 | 分流 |
|---|---|---|---|
| 1 | TV `SettingEnhanceActivity`（leanback） | `:83` | 不动，永远原生 |
| 2 | Flutter `B4HostApiImpl.openLab()` | `:303` | Flutter 分流，start-then-finish |
| 3 | Flutter `B7HostApiImpl.openLab()` | `:710` | Flutter 分流，start-then-finish |
| 4 | Flutter `B11HostApiImpl` 底栏 `local_source` | `:644` | Flutter 分流，start-then-finish（仅 `isLabNav()` 时） |
| 5 | 手机 `HomeActivity.handleNavItem(R.id.lab)` | `:495` | 手机分流，no-finish（叠在上面） |
| 6 | 手机 `HomeActivity.handleNavItem(R.id.local_source)` | `:499` | 手机分流，no-finish（仅 `isLabNav()` 时） |
| 7 | 手机 `SettingEnhanceFragment` lab 行 | `:100` | 手机分流，no-finish |
| 8 | 手机 `SettingFragment.onLab()`（ROW_LAB 点按 + `performRow` 深链共用） | `:571` | 手机分流，no-finish |
| — | `LabRuntimeService:86-88` 通知 intent | 直接 `new Intent`，不走 `start()` | 不动，永远原生 |

`LabDetailActivity.start` 只在 `LabActivity.onOpen/onLongPress` 内被调，无外部深链。

### 1.5 数据来源清单

- `LabConfig`：`getLabRoot` / `reload` / `getLoadError` / `getInstalledVersion` / `applyCommandOverrides` / `clearCommandCache` / `clearCommandOverrides` / `saveImported` / `loadUserSettings` / `saveUserSettings` / 源与开关的 getter/setter / `serverPort()` / `dataPath()`。
- `LabEnv`：`installed` / `displayVersion`（arch 匹配 download 版本否则 `item.version`）/ `compareVersions` / `arch()` / `syncVodPlusAssets` / `importPackage` / `exportPackage` / `uninstall`；`findDownload` 与 `downloadError` 是**包私有 static**（impl 在 `flutter` 包内调不到，大小文案按 §2.6 复现）。
- `LabActions`：`install` / `installWithDialog` / `uninstall` / `stopItem` / `runCustom` / `runCommand`；`hideCloseButton` / `showCloseButton` 是**包私有 static**（导出对话框按 §2.6 复现两行）。
- `LocalSource`：`getSites` / `isMiniapp` / `miniappTitle` / `removeByKeys` / `setScanDir` / `getScanDir` / `scan` / `invalidate` / `takeSkippedLargeDir` / `takePrunedCount`。
- `LabCustomCommands`：`list` / `save` / `add` / `remove`（edit 对话框内部用）。
- `LabRunner`：`isRunning(item.name + "/" + command.id)` / `stop(key)`。
- `LabFilePicker.pick(Activity, directory, Consumer)` / `onActivityResult`（外部存储可读时走原生文件浏览对话框，否则 SAF）。
- `LabCommandSheet`：`new LabCommandSheet(activity, item, command, callback).show()`（构造器，非静态 show；`run()` 公开）。
- `LabCommandEditDialog.show(Activity, item, command, Runnable)`（command 为 null = 新增；内部按 `custom_` 前缀区分自定义命令）。

### 1.6 字符串与资源归属（按目录分别 grep 验证，无 head 截断）

- 全部 `lab_*` 都在 main res（`lab_strings.xml` 62 个 + `strings.xml` 9 个 `lab_local_source_*`）；leanback / mobile 目录 0 个（`lab_nav` / `labNav` 除外，本批不用）。
- 本批用到的键（已逐个核对在 main res）：`lab_title`、`lab_about_title`、`lab_about_message`、`lab_import_title`、`lab_import_hint`、`lab_import_placeholder`、`lab_import_select_file`、`lab_import`、`lab_import_success`、`lab_import_error_short`、`lab_source_title`、`lab_source_local`、`lab_source_url`、`lab_source_hint`、`lab_source_switch`、`lab_source_placeholder`、`lab_switch_group`、`lab_refresh`、`lab_add_custom`、`lab_terminal`、`lab_keepalive_foreground_title/hint`、`lab_keepalive_battery_title/hint`、`lab_global_proxy_title/hint`、`lab_global_proxy_port`、`lab_global_proxy_no_proxy`、`lab_empty_no_source`、`lab_local_source_empty`、`lab_local_source_imported`、`lab_local_source_pruned`、`lab_local_source_too_many_files`、`lab_local_source_deleted`、`lab_local_source_scan`、`lab_running`、`lab_installed`、`lab_not_installed`、`lab_run`、`lab_stop`、`lab_refresh_success`、`lab_command_stopped`、`lab_export_file`、`menu_help`、`hk_miniapp_empty`、`hk_miniapp_list_pending`，以及共用的 `setting_local_source`、`setting_lab`、`setting_delete`、`setting_custom_csp_permission_required`、`dialog_cancel`、`dialog_confirm`、`error_retry`、`nav_setting`、`error_play_timeout`。
- 详情布局硬编码文案（`activity_lab_detail.xml`）：“下载安装”、“卸载”、“命令列表”、“暂无命令”——host 原样返回，Dart 不硬编码。`menu_lab_detail` 两项：导出（`lab_export_file`）、设置（`nav_setting`）。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- 开关 `flutter_b21_enabled`（`Setting`，默认 false，独立于其他开关；开关 UI 只在实验屏）。关 = 实验室列表/详情回原生；原生实现一行不删。
- 路由 `/lab`（`C1ScreenActivity.ROUTE_LAB`），family 路由 + Dart 内 `Navigator`（B3 同款）：`/list` 初始页，包点按推 `/detail`（name 作参数）；系统返回先弹详情，再经 `C1HostApi.closeScreen()` 关屏。family 内导航纯 Dart；跨屏导航永远走 host。
- 7 处分流（§1.4 表）：手机原生 4 处 no-finish（Flutter 页叠在上面，返回回原生）；Flutter 内 3 处 start-then-finish（一次只活一个 C1 引擎屏，B15/B19 同款，G2 风险见 §5 V11）。

### 2.2 TV 入口隔离核查清单（本批必查项）

- [x] **不在 `LabActivity.start()` 内设门**：B4/B7/B11 调它时不 `finish()`，在里面分流会再起一个 C1 屏叠在缓存引擎上（G2 违规）。改为 7 个调用点各自按 `Util.isMobile() && Setting.isFlutterB21Enabled()` 分流。
- [x] TV `SettingEnhanceActivity:83` 一行未动，永远走原生 `LabActivity.start`。
- [x] `LabRuntimeService` 通知 intent 直接构造 `LabActivity`，不走 `start()`，不动，永远原生。
- [x] 7 处分流全部带 `Util.isMobile()` 门控；leanback 构建的 TV 入口（`SettingEnhanceActivity`）不在分流表里。
- [x] leanback 构建复核：`B21HostApiImpl` 在 main 源集，只引用 main 可见 API（`Lab*`、`LocalSource`、`FileChooser`、`PermissionUtil` 皆 main 源集；不引用 `HkStore` 等 mobile-only 类，小程序走 `LocalSource.isMiniapp` + 隐式 intent）；唯一改动的 mobile 源集文件是 3 个入口文件（`HomeActivity`、`SettingEnhanceFragment`、`SettingFragment`），本身就是 mobile-only；`LabActivity` 的 B20 分流行一行未动。
- [x] 资源：本批 impl 直接引用的 `R.string` / `R.style` 全部 main res（§1.6 已按目录分别核对）；`R.style.Theme_App_Lab_Dialog` 与 `Theme_App_Lab_DayNight_Dialog` 皆 main res。
- [x] `LabActivity.render()` 里的手机独占分组（本地源/小程序）只在 `!Util.isLeanback()` 时出现——impl 的 `buildGroups` 原样复现该条件。

### 2.3 终端/输出交界

- 详情页“终端”按钮 → `openLabTerminal` → host `LabTerminalActivity.start(activity, name, name)`。
- 命令 sheet 的运行/输出按钮、命令行的 run 路径全部 host 侧原生（`LabCommandSheet` / `LabActions.runCustom` / `LabRunner`），输出页仍由原生打开。
- Dart 内无流式 I/O，无 EventChannel；详情页只用 2 秒 `pollLabDetail()` 轻量快照合并运行态。

### 2.4 Pigeon 合约（`flutter_module/pigeons/b21_lab_messages.dart`）

- `B21HostApi` 34 个方法 + 16 个扁平数据类（`B21LabShell` / `B21LabGroup` / `B21LabPackage` / `B21LabSite` / `B21LabList` / `B21LabDetail` / `B21LabCommand` / `B21LabDetailState` / `B21LabCommandState` / `B21LabSettings` / `B21LabSettingsInput` / `B21LabSettingsForm` / `B21LabSettingRow` / `B21LabSettingOption` / `B21LabSettingValue` / `B21LabImportDecode`）。
- `@async` 一次性快照，回调每条路径恰好完成一次：加载配置的三类调用（`getLabList` / `reloadLabList` / `getLabDetail`，以及同样触发 reload 的 `saveLabSettings` / `importLabPackage` / `refreshLabCommands`）用 **60s watchdog**（源可能是网络 URL）；只读缓存快照（`deleteLabLocalSource` / `deleteLabMiniapp` / `saveLabSettingsForm`）用 **35s watchdog**（规划 §4.6 约定）。
- 文件/目录选择不进 watchdog：`requestLabImportFile` / `requestLabLocalConfigFile` / `pickLabLocalSourceDir` / `requestLabSettingDirectory` 皆 fire-and-forget，Dart 按 B19 同款 500ms 轮询 `pickState` / `pickEpoch`（10 分钟上限）；取消时 host 仍 bump epoch、消息留空（原生同样静默，轮询能及时停表，见 §5 V7）。
- 列表页与详情页 mounted 期间都 2 秒轮询（原生详情页 `updateButtons` 的节奏；列表页多这一轮是因为 host 无 EventChannel，自动安装对话框与后台安装会让“未安装”行过期，见 §5 V1）。

### 2.5 Dart 页（`flutter_module/lib/b21_lab_page.dart`）

- `B21LabPage`：family 壳（`PopScope canPop:false` + 内 `Navigator`，B3 同款）；返回先弹内页，再 `closeScreen()`。
- 列表页：shell 加载 → `getLabList` + 2 秒轮询 + resumed 重取 + 下拉刷新（`reloadLabList`）；分组 chips + 滑动切分组（速度 ±500 px/s 近似，原生 fling/位移阈值的简化）+ FAB 分组面板；工具栏按分组走（帮助 → 关于对话框、+ → 导入/本地源弹窗/小程序管理、扫描图标仅本地源分组、齿轮 → 配置源对话框）；导入对话框（粘贴 + 解码 + 选文件链接）；配置源对话框（源下拉、URL/本地路径字段 + 文件夹按钮 + 500ms 轮询、根目录、3 个开关、代理开时端口与 no-proxy 字段）；包卡片（`Image.network` + 占位、标题/信息/版本/大小/状态 chip + 更新角标，点/长按 → 详情）；本地源行（点 → 设 home，删除按钮）；小程序行（点 → 原生列表，删除按钮）；错误态重试；忙时线性进度。
- 详情页：头（图标/版本/信息/状态 chip）、下载/卸载按钮、命令区（终端/添加/刷新图标按钮，命令行运行圆点 + 运行/停止按钮，点 → sheet，长按 → 编辑）、无命令文案；包设置表单对话框（text / number / select / switch / directory 五种行，目录行文件夹按钮 + 500ms 轮询）；2 秒 `pollLabDetail` 按 id 合并；`found=false` → 弹回列表。

### 2.6 Host 实现（`B21HostApiImpl.java`，main 源集）

- B19 形态：单 worker 线程 + 主线程 `Completion`（handler + 超时 + done 守卫）；无 busy 字段（B21 快照不带 busy，`Completion` 去掉了 `ownsBusy`）。
- B20 形态：静态 per-messenger 注册表 + `rebind`（旋转后新 Activity 接管进行中的选择/导入/扫描，关掉 B19 的旋转丢结果缺口）；`C1ScreenActivity` 与 `C0ShellActivity` 都在 `ensureFlutterEngine` 注册、`onCreate` 重绑、`onDestroy`（finishing 时）反注册。impl 在 main 源集，直接注册，不用 B20 的反射间接层。
- `C1ScreenActivity` 新增：`/lab` 路由常量；`labLocalSourceLauncher`（`FileChooser` 目录选择器，B19 launcher 同款）+ `launchLabLocalSourceDir()`；`onActivityResult` 转发给 `LabFilePicker.onActivityResult`（SAF 码自 3000 起）与 `B21HostApiImpl.onActivityResult`（导入 21001 / 本地配置 21002）。
- 关键复现（带漂移注释，规划 §4.8 第 3 招）：包大小文案复现 `LabEnv.findDownload` 的 arch 匹配循环（包私有）；导出对话框的关/开“关闭”按钮复现 `LabActions.hideCloseButton` / `showCloseButton` 的两三行（包私有）。
- 对话框与 Activity 全部 host 侧原生：关于、导入确认、更新确认、卸载确认、重置命令确认、包设置表单宿主、导出进度、命令 sheet、添加/编辑命令对话框、终端 Activity。
- `getLabShell` 与各快照的全部展示文案、计算值皆 host 返回；Dart 不硬编码文案（布局硬编码的“全部”、“海阔小程序”、“下载安装”、“卸载”、“命令列表”、“暂无命令”、“重置命令列表”、“根目录（留空跟随配置）”、“保存”等皆由 host 以 Java 字面量原样返回）。

### 2.7 文件清单

| 文件 | 动作 |
|---|---|
| `flutter_module/pigeons/b21_lab_messages.dart` | 新：B21HostApi + 16 个扁平数据类 |
| `flutter_module/lib/b21_lab_page.dart` | 新：`/lab` family 页（列表 + 详情） |
| `flutter_module/lib/main.dart` | 改：`/lab` 路由 + B21 开关（import 有引用，无 unused import） |
| `app/src/main/java/.../flutter/B21HostApiImpl.java` | 新：34 方法实现 + 注册表/rebind |
| `app/src/main/java/.../flutter/C1ScreenActivity.java` | 改：`ROUTE_LAB`、注册/重绑/反注册、目录选择 launcher、`onActivityResult` 转发 |
| `app/src/main/java/.../flutter/C0ShellActivity.java` | 改：注册/重绑/反注册（只为实验屏开关用） |
| `app/src/main/java/.../setting/Setting.java` | 改：`flutter_b21_enabled`，默认 false |
| `app/src/main/java/.../flutter/B4HostApiImpl.java` | 改：`openLab` 分流 + `Util` import |
| `app/src/main/java/.../flutter/B7HostApiImpl.java` | 改：`openLab` 分流 + `Util` import |
| `app/src/main/java/.../flutter/B11HostApiImpl.java` | 改：底栏 `local_source` 分流 + `Util` import |
| `app/src/mobile/.../ui/activity/HomeActivity.java` | 改：`R.id.lab` 与 `R.id.local_source` 分流（no-finish） |
| `app/src/mobile/.../ui/fragment/SettingEnhanceFragment.java` | 改：lab 行分流（no-finish）+ `Util` import |
| `app/src/mobile/.../ui/fragment/SettingFragment.java` | 改：`onLab()` 分流（no-finish）+ `Util` import |
| `.gitignore` | 改：+ `B21Messages.java` |
| `.github/workflows/b8-c0.yml` | 改：Pigeon 步骤 + b21 生成 + 存在性检查 |
| `docs/b8-b21-lab.md` | 新：本任务文档 |

冻结批零改动：C0/C1/C2/B2–B20 的合约与实现只在上表三处动了分流点（B4/B7/B11 的 `openLab` / 底栏分支，B6→B7 / B15 / B19 / B14-fix 同款预授权分流先例）；共享组件（`VodCard` / `PicImage` / `SettingsRow`）零改动；`LabActivity` 的 B20 分流行一行未动；原生实验室实现一行未删。

## §3 状态来源清单（全部 host 快照，Dart 不计算）

| Dart 字段 | 来源 |
|---|---|
| shell 全部文案 | `activity.getString(R.string.lab_*)` / `dialog_*` / `error_retry` / `setting_*` / `nav_setting`；布局硬编码文案作 Java 字面量 |
| groups | `LabActivity.render` 顺序：all + 本地源（手机）+ 小程序（手机 + Hk 开关）+ lab.json 分组 |
| packages | `LabConfig.getLabRoot().lists` 过滤 `show`；标题/版本 `LabEnv.displayVersion`；大小 = arch 匹配 download 的 `size`（§2.6 复现）；状态 = `LabPackageAdapter` 逻辑（运行只看内置命令） |
| localSources / miniappSites | `LocalSource.getSites()`，按 `LocalSource.isMiniapp` 分流；名 = `Site.getName()`（与 `LocalSourceAdapter` 同款） |
| loadErrorText | `LabConfig.getLoadError()`；watchdog 超时填超时文案 |
| noticeText | 产生该快照的动作的一次性提示（导入成功/删除/扫描结果/设置已保存/刷新成功） |
| pickState / pickEpoch / pickMessage | impl 内三组独立状态（列表导入+扫描 / 配置源选文件 / 包设置选目录） |
| isHkMiniapp | `Setting.isHkMiniapp()` |
| detail 全部字段 | `LabDetailActivity.bind` + `updateButtons`（安装/运行/hasUpdate/下载文案与可见性/卸载可见性/状态文案与 kind/`hasSettings`）；命令 = adapter 的内置 + 自定义合并（含 `custom_` 前缀判定） |
| pollLabDetail | 缓存根上的 `updateButtons` + `refreshStates`（不 reload），按 id 合并 |
| settings form | `item.settings` + `LabConfig.loadUserSettings`（存量否则默认值；select 选项原样） |

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 列表页

- [ ] 开关开后，从首页底栏实验室、设置页实验室行、增强设置实验室行、设置内实验室（B4/B7/B11）进的都是 Flutter 列表页；开关关后全回原生。
- [ ] 分组：全部 / 本地源 / 海阔小程序（Hk 开关开时才有）/ lab.json 分组顺序与原生一致；各分组包数一致。
- [ ] 包卡片：标题、版本、大小、状态文案、更新角标与原生一致；点/长按进详情。
- [ ] 下拉刷新重载；切分组、滑动切分组、FAB 面板切分组与原生一致。
- [ ] 本地源分组：列表、点行设 home（回首页）、删除、扫描目录（选目录后扫出新源并提示条数）。
- [ ] 小程序分组：列表、点行进原生小程序列表页、删除。
- [ ] 工具栏：帮助对话框、`+`（各分组行为）、设置对话框、扫描图标只在本地源分组出现。
- [ ] 配置源对话框：本地/URL 切换、选本地文件后路径填入并切到本地、保存后生效（前台/电池/代理开关同原生）。
- [ ] 导入包：粘贴 `VodPlusLabConfig://` 数据导入成功；无效数据留在框内并提示；选 `.lab.7z` 文件导入成功。
- [ ] 自动安装：`auto_install` 的包首次打开列表时静默安装（行状态随后变已安装）。

### 4.2 详情页

- [ ] 头部：包名、版本行、信息、图标、状态 chip 与原生一致。
- [ ] 下载/更新按钮：未安装显示“下载安装”；有新版本显示“更新”并弹确认框；已安装无更新时隐藏；卸载仅已安装时显示。
- [ ] 命令列表：内置 + 自定义顺序、运行圆点、运行/停止按钮文案与原生一致；2 秒轮询后运行态跟上。
- [ ] 点行开原生 sheet（变量/下载/点击/运行/输出）；运行中点行按钮停止并提示；长按进编辑对话框；添加命令对话框可用。
- [ ] 终端按钮开原生终端页；刷新命令弹确认框，确认后重置并提示“刷新成功”。
- [ ] 有 settings 的包右上角出现导出与设置图标；无 settings 的包不出现。
- [ ] 包设置表单：五种行类型、默认值、保存后提示“设置已保存”；目录行文件夹按钮能选目录并填入。
- [ ] 导出：已安装包导出成功并提示路径；未安装包提示“该包尚未安装”。
- [ ] 包被删/配置失效后详情页弹回列表并提示。

### 4.3 终端/输出交界

- [ ] 终端按钮打开的是原生终端页（可输入交互），不是 Flutter 内嵌终端。
- [ ] 命令运行后输出仍在原生输出页查看；停止命令仍走原生。

### 4.4 开关与回滚

- [ ] 实验屏 B21 开关只在实验屏出现；开/关立即生效（下次进实验室页）。
- [ ] 开关关 = 全部实验室入口回原生；全部开关关 = 整 app 原生，零行为变化。
- [ ] TV 实验室入口（增强设置实验室行）永远原生；TV 构建通过（leanback flavor）。

## §5 相对规划的全部偏差

- **V1 列表页 2 秒轮询**：规划 §4.6 只有 resumed 重取 + 下拉刷新；本批列表页 mounted 期间也 2 秒轮询 `getLabList()`。原因：host 无 EventChannel，自动安装对话框与后台安装会让“未安装”行过期；详情页原生就有 2 秒轮询，列表页只是同款节奏的延伸。
- **V2 watchdog 分档**：60s（触发 `LabConfig.reload` 的 6 个 `@async` 调用，源可能是网络 URL）vs 35s（只读缓存快照的 3 个 `@async` 调用）。规划只说了 35s 守卫惯例；URL 源加载可能超 35s，故分档。
- **V3 冻结批分流点**：B4/B7/B11 的 `openLab` / 底栏分支是分流点专用的最小编辑（B6→B7、B15、B19、B14-fix 同款预授权先例）；三处之外零改动，B20 的 `LabActivity` 分流行未动。
- **V4 `onActivityResult` 转发**：`C1ScreenActivity.onActivityResult` 新增转发给 `LabFilePicker`（SAF 码自 3000 起）与 B21 impl（21001/21002）；只加法，`flutterFragment` 转发原样保留。
- **V5 大小文案复现**：`LabEnv.findDownload` 包私有，impl 内按原文复现 arch 匹配循环（带漂移注释）；`LabEnv.displayVersion` 是公开的，直接调用。
- **V6 导出对话框按钮复现**：`LabActions.hideCloseButton` / `showCloseButton` 包私有，impl 内复现其两三行（带注释）；行为与原生一致（进度时藏“关闭”，失败时放出）。
- **V7 取消也 bump epoch**：文件/目录选择取消时 host 把 `pickState` 置 idle 并 bump `pickEpoch`、消息留空。原生无 pick 状态故无此动作；Flutter 侧需要 epoch 变化来停掉 500ms 轮询（否则跑到 10 分钟上限），且空消息保证静默，与原生一致。
- **V8 扫描提示合并**：原生扫描后可能连弹两句 toast（导入数 + pruned 数）；快照只带一个 `noticeText`，host 用 `\n` 拼成一句。内容相同，提示次数少一次。
- **V9 不在 `LabActivity.start()` 内设门**：见 §2.2。规划没定门的位置；实测 B4/B7/B11 调 `start()` 时不 `finish()`，在里面分流会违反单引擎屏不变量（G2），故 7 个调用点各自设门。
- **V10 配置源选文件后对话框不关**：原生选完本地配置文件后关掉设置对话框并 reload；Flutter 侧对话框留在 Dart，host 只把新路径经 `pickMessage` 填回字段并切源为本地，等用户点保存才生效。用户多一步确认，少一次误触 reload。
- **V11 G2 跨屏竞态**：3 处 Flutter 分流（B4/B7/B11）与 `openLabMiniappManager` 的 B20-on 路径都是 start-then-finish；新 Activity 可能复用旧引擎/路由，旧 Activity 的 onDestroy 再销毁引擎。B15/B19 同款已知风险；若真机复现，`C1ScreenActivity` 需加 relaunch 机制（跨批修复，本批不做）。
- **V12 通知权限结果吞掉**：`saveLabSettings` 前台开时调 `requestPermissions`；`C1ScreenActivity.onRequestPermissionsResult` 只转发给 flutterFragment（B7 文档 G6 的已知缺口），不回调 `LabProcManager.updateService()`。无行为损失：保存流程本身已无条件调了一次 `updateService()`。
- **V13 `LabCommandEditDialog` 的 onSaved 是空实现**：原生保存后 `reload()` 详情；Flutter 侧靠 2 秒 `pollLabDetail` 在 2 秒内拾到新增/修改的命令。
- **V14 列表与详情的运行态口径不同**：列表状态只看内置命令（`LabPackageAdapter.running` 原样），详情状态还看自定义命令（`LabDetailActivity.anyRunning` 原样）——原生本身就是两套口径，本批原样复现，不是 bug。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon 代码生成是否与 34 方法合约逐字匹配**（最担心）：`List<B21LabSettingRow>` 内嵌 `List<B21LabSettingOption>`、带返回值的 `@async`、`int` 字段的 Java 侧 `Long` / Builder 侧 `long`、数据类的 getter、方法名大小写。CI 构建是唯一证明；若报错按文件路径归属到本批、向前修。
2. **旋转重绑 + 文件选择转发**（最担心）：静态注册表 + `rebind`、目录选择 launcher、21001/21002 转发、`LabFilePicker` 3000+ 码转发——旋转中途选文件/导入/扫描是否正常完成。CI 编译 + 真机旋转验收。
3. G2 跨屏竞态（§5 V11）：3 处 Flutter 分流与小程序管理 start-then-finish 在真机是否复现旧引擎复用。
4. 键盘/IME：导入粘贴框与包设置表单是本批的两个文本输入面（B5 依赖）；若实验屏开关页之外的 FlutterFragment 内键盘异常，按规划 §4.5 停下等决策。
5. 图片一致性：`Image.network` 无磁盘缓存，上下滑回看时与 Glide 的表现差异；Douban 图、`@` 后缀 header、首字占位。
6. `PermissionX` 文件权限流程从 `C1ScreenActivity` 发起是否正常（导入包选文件的前提）。
7. `LabFilePicker` 的 SAF 兜底分支（外部存储不可读时）是否走到 `onActivityResult` 转发。
8. 导出对话框“关闭”按钮的复现行在真机是否与原生一致。
9. leanback 构建零影响 + TV 实验室入口永远原生（CI leanback flavor；Wei 无 TV 设备，真机只验手机）。

## Recovery anchor

- 目标：手机端实验室列表/详情迁到 Flutter（`/lab` family 路由），终端/输出留原生；开关默认关；TV 永远原生。
- 状态：本地做完，未推。契约 + Dart 列表/详情页 + `B21HostApiImpl` + choke-point 编辑 + 7 处入口分流 + 本任务文档均已写好；`git diff --check` 待跑；联合 CI 构建与真机验收待做（本机无 SDK）。
- 风险：Pigeon 34 方法合约的代码生成匹配（§6-1）；旋转重绑 + 文件选择转发（§6-2）。
- 下一步（恰好一个）：**短剧批**——先定浏览/播放器拆分设计（浏览 UI 迁移、Activity 内 ExoPlayer 部分留原生），再实施。
