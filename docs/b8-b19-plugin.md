# B19 插件管理（B8 全量迁移）

## §0 执行声明

- **目标**：把手机端插件管理页（`PluginManagerActivity`，1040 行）迁移到 Flutter，路由 `/plugins`（C1 引擎），开关 `flutter_b19_enabled` 默认关。安装 / 重载 / 文件选择 100% host 侧（规划 §6 P2 决策，Wei 2026-11-17 12:50 定“迁”）。
- **状态**：本地实现完成，未推送。基线 B18 提交 `77a55786`；分支 `b8-flutter-rewrite`；工作树待提交（见 `git status`）。
- **诚实声明**：本机无 Java / Android / Flutter SDK，构建与运行均未验证；Pigeon codegen、Gradle 编译、`flutter analyze` 全部留给联合 CI 构建；行为留给 Wei 真机验收。

## §1 原生实现（只读发现）

### 1.1 管理页

`app/src/main/java/com/github/moyingshi/osc/tv/ui/activity/PluginManagerActivity.java`（1040 行，main 源集，手机 + TV 共用）：

- **列表数据来源**：`PluginSourceConfig.get().entries()`（SharedPreferences 键 `plugin_source_config`，`Entry` 记录：`id / name / version / runtimeApi / directory / enabled / importedAt`，插入顺序）；每次操作后都以后台重读出来的快照为准（开关与删除都先写配置再重读，界面状态与持久化状态一致）。
- **后台模型**：单线程执行器 `mWorker` + `mBusy` + `mReloadQueued`。类注释说明原因：`PluginBootstrap.installAndReloadQuietly()` 是阻塞调用（QuickJS 初始化最长 5 秒），`PluginController.reload` 不可重入；单线程队列把“装载”天然串行化。连点开关时 `mReloadQueued` 把多次重装合并成一次。
- **启用/禁用**：`config.setEnabled(id, enabled)` + `save()`，写失败时 toast `plugin_manager_save_failed` 并重读把开关拨回持久化状态；成功后 `scheduleReload()`。
- **重载**：`scheduleReload()` → worker 上 `installAndReloadQuietly()` + 重读快照。
- **本地导入**：`chooseDirectory()` → `PermissionUtil.requestFile` → `FileChooser.from(mDirectoryLauncher).showDirectory()`（FileActivity 目录选择）→ `onDirectoryPicked`（取 `uri.getPath()`，不做 URL 解码，否则路径里的 `+` 会变空格）→ `importPlugin(File)`：`PluginPackageImporter.importFrom(source)`，成功后 `installAndReloadQuietly()`，默认启用，重读快照，toast 成功/失败。
- **远程导入（五步）**：① `importFromUrl()` 弹 URL 输入框（粘贴按钮读剪贴板；点确认时 `PluginRemoteSource.resolve` 校验，不通过就地报错不关框；地址不落盘）；② `startRemoteImport()`：删旧临时包 → 再次 resolve → `download(urls, zip, 20s)` → `PluginBundleIndex.inspect(zip)`（空候选也算失败）；③ `showCandidates()` 候选勾选框（默认全选、按 `entryPath` 记勾选、全选切换、导入按钮随勾选数启用）；④ `confirmInstall()` 确认框（名字 + 合并权限 + 无条件出现的“未沙箱”警告 + 仅当申请时出现的联网/面板大白话高风险行，错误色加粗）；⑤ `runBundleImport()`：只传勾选的候选 → `importBundle(zip, selected)` → 成功则 reload → 重读快照 → `showRemoteResult()`（全成功 toast、部分成功弹逐行结果框、失败 toast）。
- **参数编辑**：`editParams()` 多行 JSON 输入框（`PluginEnvironmentStore.get(id)` 预填）→ 确认时先判 JSON 对象（非法就地报错）→ `put()`（false = 超限，就地报错）→ 成功后 `scheduleReload()`（参数在插件 init 阶段读走，不重装等于没改）。
- **删除**：`confirmDelete()` → `deletePlugin()`：`config.remove(id)` + `save()` + `PluginEnvironmentStore.remove(id)`（删参数防同名重装静默继承旧 token）→ `removePackageDir(directory)`（只删 `PluginPackageImporter.isOwnedDirectory()` 认定的根目录下条目，防脏配置指向任意路径）→ reload → 重读快照。配置删成功才删目录，反过来会留下指向空目录的条目。
- **权限令牌 → 人话**：`permissionLabel()`（7 个已知令牌本地化，未知令牌原样显示）；`isHighRisk()`：仅 `net.request` 与 `ui.panel`。
- **临时包**：`getCacheDir()/plugin-bundle.zip`，流程任何出口都删；`onDestroy` 在无后台任务时补删。

### 1.2 入口

`PluginManagerActivity.start()` 的全部调用点（grep）：

| 调用点 | 位置 | 处理 |
| --- | --- | --- |
| 手机增强设置页插件行 | `app/src/mobile/.../SettingEnhanceFragment.java:134` | **B19 分流**：开关开 → `C1ScreenActivity.start(ROUTE_PLUGINS)`，关 → 原生（本批改动，分流处 only） |
| B4 Flutter 增强设置页插件行 | `flutter/B4HostApiImpl.java:323 openPluginManager()` | **B19 分流**：开关开 → `C1ScreenActivity.start(ROUTE_PLUGINS)` + finish，关 → 原生（本批改动，分流处 only；B15 `openLocalReader` 同款 start-then-finish） |
| TV 增强设置页插件行 | `app/src/leanback/.../SettingEnhanceActivity.java:111` | 不动（TV 原生） |

B7 设置主屏无插件行；无其他调用点。

### 1.3 字符串与资源归属（按目录分别 grep 验证，无 head 截断）

- 全部 `plugin_manager_*`（55 条，`strings.xml:3087–3141`）都在 **main** res；leanback / mobile res 里一条都没有。
- 共用字符串 `setting_enable / setting_disable / setting_delete / dialog_cancel / dialog_confirm / dialog_negative / dialog_positive / error_retry / error_play_timeout / setting_plugin_manager` 同样全部只在 main res；leanback / mobile res 里一条都没有。
- 结论：`B19HostApiImpl`（main 源集）直接引用全部 `R.string` 是 leanback 安全的，无需 `mobileString()` 回落。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- `Setting.flutter_b19_enabled`（`Prefers`，默认 false），开关 UI 只在 C0 实验屏（`main.dart` 新增一行业）。
- 关 = 两处入口都回原生，`PluginManagerActivity` 一行不删；开 = 两处入口走 `/plugins`。
- TV 入口（leanback `SettingEnhanceActivity`）不改，永远原生。

### 2.2 快照与完成规则（本批核心设计）

- **单快照类型**：全部 `@async` 动作方法都返回 `B19PluginScreen`（列表 + busy + 一次性 `noticeText` / `dialogErrorText` / `remoteResultLines`）；Dart 只渲染，不计算、不硬编码文案。
- **单线程 worker**：`B19HostApiImpl` 持一个 `newSingleThreadExecutor`（`executorService()` 懒建，`disposePlugins()` 关停），与原生 `mWorker` 同构；`busy` 守卫 + 立即完成（busy 时到来的动作直接回当前快照 + busy notice），替代原生的 `mReloadQueued` 合并——见 §5 V5，原生按钮在 busy 时本就是禁用的。
- **Completion 带 `ownsBusy`**：`getPluginScreen` 从不置 busy，它的 Completion 完成时**不**清 busy；动作方法的 Completion 才清。这是 B18 形态的必要修正——B18 的每次 Completion 都清 guard，因为每次调用都先置 guard；B19 的轮询快照若不清分 guard，会把正在跑的导入提前解锁。
- **35s 守卫**：每个 `@async` 调用都有 `Completion` + 35s watchdog，超时回错误快照；迟到的后台完成是 no-op。远程下载 20s 超时在 35s 守卫内。
- **目录选择不走 `@async`**：选目录要等用户在 FileActivity 里浏览，无法塞进 35s 守卫。Dart 调 fire-and-forget 的 `requestPluginDirectory()`，host 走原生 `PermissionUtil.requestFile` + `FileChooser.from(launcher).showDirectory()`（launcher 在 `C1ScreenActivity`，结果经 `onDirectoryPicked` 回到 impl），Dart 以 500ms 间隔轮询 `getPluginScreen()` 看 `pickState / pickEpoch`（B10 进度轮询先例），epoch 变化且回到 idle 时出结果 toast，10 分钟封顶。取消选择时 host 静默（与原生一致），`pickMessage` 为空，Dart 什么都不显示。
- **远程导入五步拆在 Pigeon 两侧**：`resolveRemoteUrl`（同步，URL 框校验不通过不关框）→ `downloadRemoteBundle`（@async，下载 20s + 索引，zip 暂存 host 侧 cache dir）→ Dart 候选勾选框（默认全选）→ `getRemoteConfirm`（同步，确认文案 host 全量拼好，含未沙箱警告与高风险大白话行）→ `installRemoteCandidates`（@async，只传勾选的 `entryPath`，结果折进刷新后的快照：全成功/失败走 `noticeText` toast，部分成功走 `remoteResultLines` 结果框）。
- **参数编辑**：`getPluginParams`（同步，预填 + host 拼标题）→ `savePluginParams`（@async；JSON 非法 / 超限时快照带 `dialogErrorText`，Dart 对话框保持打开并就地显示错误；保存成功才 reload，与原生一致）。
- **resumed 重取**：`didChangeAppLifecycleState(resumed)` 时重取（规划 §4.6）；前台 EventBus 推送缺口与全批一致（接受缺口）。
- **无 EventChannel**：全部状态变化都走快照或轮询。

### 2.3 Pigeon 合约（`flutter_module/pigeons/b19_plugin_messages.dart`）

`B19HostApi`：`isB19Enabled / setB19Enabled`；`getPluginShell`（同步，22 个 chrome 字符串）；`getPluginScreen / setPluginEnabled / reloadPlugins / installRemoteCandidates / savePluginParams / deletePlugin`（@async，均返 `B19PluginScreen`）；`requestPluginDirectory / disposePlugins`（fire-and-forget）；`resolveRemoteUrl / getRemoteConfirm / getPluginParams / getDeleteConfirm`（同步，纯计算或 SharedPreferences 读）。数据类全部扁平：`B19PluginShell / B19PluginItem / B19PluginScreen / B19RemoteResolve / B19RemoteCandidate / B19PermissionLabel / B19RemoteIndex / B19RemoteConfirm / B19PluginParams / B19DeleteConfirm`；`pickEpoch` 为 `int`（Java `Long` 装箱），`enabled / highRisk / ok / valid` 为 `bool`（Java `Boolean` 装箱），方法名与 Dart 定义字符级一致。

### 2.4 Dart 页（`flutter_module/lib/b19_plugin_page.dart`）

- `B19PluginPage`（字面路由 `/plugins`，无参数）：AppBar（标题 + 刷新按钮）+ 三按钮行（导入 / 从 URL 导入 / 重载）+ busy 进度条 + 列表 / 空态 / 错误态（带重试）。
- 每行：名字 + host 拼好的 meta 行（`v版本 · runtime · 启用/停用`）+ 状态小字 + Switch + 参数按钮（tune 图标）+ 删除按钮；busy 时全部禁用。
- 对话框全部 Dart 侧：URL 输入框（粘贴按钮走 `Clipboard.getData`）、候选勾选框（全选切换、空选时导入按钮禁用）、安装确认框（`riskText` 错误色加粗单独渲染）、部分结果框、参数多行输入框（就地错误时保持打开）、删除确认框。
- 轮询：`Timer.periodic(500ms)`，epoch 变化且 idle 时停表并出 toast；10 分钟封顶。`dispose()` 停表并调 `disposePlugins()`。

### 2.5 Host 实现（`B19HostApiImpl.java`）

- 逐字复制的原生逻辑：`readEntries / removePackageDir / permissionLabel / isHighRisk / permissionText / hostOf / isJsonObject / delete`、删除的写配置→删参数→删目录→reload 顺序、导入的 import→reload→重读顺序、远程五步的 resolve→下载→索引→确认→安装顺序、`installAndReloadQuietly()` 的调用点（启用、删除、保存参数、本地导入、远程安装、重载）。
- `selectedCandidates()` 按列表顺序过滤（原生 `checkedCandidates` 同款）。
- `busyScreen*()` 三个变体：busy notice / busy + dialogError / busy + notice，全部基于实时重读快照。
- 失败语义与原生一致：保存失败 toast + 快照回落到持久化状态；删除失败分“保存失败 / 目录删不掉”两条 notice；远程失败删临时 zip。

### 2.6 文件清单

| 文件 | 动作 |
| --- | --- |
| `flutter_module/pigeons/b19_plugin_messages.dart` | 新：B19 合约 |
| `flutter_module/lib/b19_plugin_page.dart` | 新：插件管理页 |
| `flutter_module/lib/main.dart` | 改：`/plugins` 路由 + B19 开关（只加法） |
| `app/src/main/java/.../flutter/B19HostApiImpl.java` | 新 |
| `app/src/main/java/.../flutter/C1ScreenActivity.java` | 改：`ROUTE_PLUGINS` + 注册 B19 + 目录选择 launcher（只加法） |
| `app/src/main/java/.../flutter/C0ShellActivity.java` | 改：注册 B19（只加法，flag 方法 only） |
| `app/src/main/java/.../flutter/B4HostApiImpl.java` | 改：`openPluginManager()` 分流处 only |
| `app/src/mobile/.../SettingEnhanceFragment.java` | 改：插件行点击分流处 only + 1 个 import |
| `app/src/main/java/.../setting/Setting.java` | 改：`flutter_b19_enabled`（只加法） |
| `.gitignore` | 改：+ `B19Messages.java` |
| `.github/workflows/b8-c0.yml` | 改：Pigeon 步骤 + b19 生成 + 存在性检查 |
| `docs/b8-b19-plugin.md` | 新：本文档 |

## §3 状态来源清单（全部 host 快照，Dart 不计算）

| Dart 展示 | Host 来源 |
| --- | --- |
| 标题 / 三按钮 / 空态文案 / 对话框按钮 | `getPluginShell()`（`setting_plugin_manager` 等，main res） |
| 插件行：名 / 版本 / runtime / 开关态 / meta 行 | `B19PluginScreen.entries`（`PluginSourceConfig.entries()` + `plugin_manager_entry_meta` 格式化） |
| busy / 进度条 / 按钮禁用 | `B19PluginScreen.busy`（host `busy` 守卫）+ Dart 本地 `_busy` |
| 加载失败 / 重试 | `B19PluginScreen.loadErrorText`（`plugin_manager_load_failed` 或超时） |
| 操作 toast | `B19PluginScreen.noticeText`（导入成功/失败、保存失败、全部安装成功、批量失败、busy） |
| 参数框就地错误 | `B19PluginScreen.dialogErrorText`（JSON 非法 / 超限 / busy） |
| 目录选择进行中 / 结果 | `B19PluginScreen.pickState / pickEpoch / pickMessage`（host 轮询状态机） |
| 远程候选行 | `B19RemoteIndex.candidates`（`PluginBundleIndex.inspect` + `plugin_manager_remote_candidate_meta` + 权限 label 列表） |
| 安装确认文案 | `B19RemoteConfirm.messageText / riskText`（host 全量拼接） |
| 参数框预填 / 标题 | `B19PluginParams`（`PluginEnvironmentStore.get` + `plugin_manager_params_title`） |
| 删除确认文案 | `B19DeleteConfirm`（`plugin_manager_delete_message`） |
| 部分安装结果行 | `B19PluginScreen.remoteResultLines`（逐行 `…: installed / …: 错误`） |

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 插件列表

- [ ] 开关开：增强设置页插件行进 Flutter 页，列表与原生一致（名字、版本、runtime、启用态、顺序）；开关关：回原生页。
- [ ] 空插件时显示空态文案 + 导入按钮；加载失败时显示错误态 + 重试。
- [ ] 切后台再回来，列表重取（resumed 重取）。

### 4.2 启用 / 禁用 / 重载

- [ ] 拨开关：配置落盘、插件重装生效、开关态与原生一致；重载按钮后列表刷新。
- [ ] 连续快拨开关：不出现两次重装打架（busy 守卫），最终状态与配置一致。

### 4.3 本地导入

- [ ] 点“导入”：出原生文件选择器（FileActivity 目录浏览），选含 `manifest.json` 的目录后导入成功 toast、列表多一条且默认启用；选无清单目录 / 取消选择，分别与原生一致（失败 toast / 静默）。
- [ ] 无存储权限时：提示权限文案，不进选择器。

### 4.4 远程导入

- [ ] URL 框：非法地址（空、http、file、含 `@`）就地报错不关框；粘贴按钮能填剪贴板地址。
- [ ] 有效 GitHub 仓库地址：下载后出候选框（数量 + 来源主机、默认全选、全选切换、导入按钮随勾选禁用/启用）。
- [ ] 确认框：名字 + 合并权限 + “未沙箱”警告必现；申请了联网/面板权限时大白话高风险行出现（错误色加粗），没申请时不出现。
- [ ] 安装：全成功 toast、部分成功弹逐行结果框、失败 toast；装完列表刷新，临时包不残留。

### 4.5 参数与删除

- [ ] 参数按钮：预填当前 JSON；填非法 JSON 保存时框内报错不关闭；填超大 JSON 时报超限；保存成功后重装生效。
- [ ] 删除按钮：确认框文案含插件名；确认后配置与目录都删，参数不残留（重装同名插件不继承旧参数）。

### 4.6 开关与回滚

- [ ] 实验屏关掉 `flutter_b19_enabled`：两处入口立即回原生，其他批不受影响；全部开关关 = 全部原生。
- [ ] TV：增强设置页插件行仍进原生 `PluginManagerActivity`，无变化。

## §5 相对规划的全部偏差

- **V1（目录选择轮询）**：规划 §4.6 只规定了“@async 一次性快照 + resumed 重取”，没覆盖“等用户选目录”这种无界等待。本批用 fire-and-forget `requestPluginDirectory()` + 500ms 轮询 `getPluginScreen()`（`pickState/pickEpoch/pickMessage`）实现，B10 进度轮询先例；每 500ms 一次快照调用只读配置，开销可忽略。替代方案（给这一个方法放 10 分钟 watchdog）被否决：watchdog 超时后 Dart 侧 Future 已完成，之后导入成功无处投递。
- **V2（远程确认多一次同步调用）**：确认文案依赖 Dart 侧的勾选集合，host 必须在用户勾选后才能拼。`getRemoteConfirm(entryPaths)` 是新增的同步调用，纯计算、无 I/O；替代方案（Dart 侧拼名字 + 权限）会把排序与本地化规则复制到 Dart，违反“文案 host 返回”。
- **V3（B4 分流 start-then-finish）**：`B4HostApiImpl.openPluginManager()` 的 B19 分流照 B15 `openLocalReader` 同款（start 新 `C1ScreenActivity` + finish 当前），G2 跨屏竞态风险同款——返回时落在原生增强设置页而不是 Flutter 增强设置页。见 §6 第二项。
- **V4（参数保存失败不单独返回值）**：`savePluginParams` 仍返回 `B19PluginScreen`，失败信号放在 `dialogErrorText` 一次性字段上；Dart 参数框据此决定保持打开。替代方案（单独的 `B19ParamsResult` 嵌套快照）会增加一层 Pigeon 嵌套 codegen 风险；一次性字段在下一次 `getPluginScreen()` 时归零，不会污染列表。
- **V5（`mReloadQueued` 合并取消）**：原生用排队标记把连点开关的多次重装合并成一次。本批用 `busy` 守卫 + “busy 时立即回快照 + busy notice”替代：原生按钮在 busy 时本就是禁用的，合并路径在 UI 上不可达；Dart 同样在 busy 时禁用按钮。用户可见行为一致。
- **V6（超时后的临时 zip 残留）**：`downloadRemoteBundle` 的 35s watchdog 先触发时，后台下载任务的成功分支直接返回，不再接管 zip；该 zip 留在 cache dir，下一次远程导入开头会先删旧包（原生同款），且系统可回收 cache dir。无用户数据影响。
- **V7（候选权限行样式降级）**：原生候选行的高风险权限是行内“错误色加粗 span”；Dart 侧整行权限文字统一按“任一高风险即错误色加粗”渲染（`permissionLabels[].highRisk` 由 host 标记）。信息等价，样式略粗。
- **V8（`busyScreen()` 在主线程读配置）**：busy 时立即完成的快照在 Pigeon 调用线程（主线程）上直接 `readEntries()`，而不是过 worker。读的是 SharedPreferences 快照解析，耗时可忽略；避免为一次拒绝再排一次 worker 任务。
- **V9（删除失败的目录路径）**：`deletePlugin` 的“目录删不掉”notice 复用原生文案（含 `directory` 绝对路径）；Dart 只展示，不解析。
- **V10（参数框无 TV 焦点接线）**：原生 `editParams` 有 `wireParameterDialogFocus` 的 leanback 遥控器焦点接线；B19 页只在手机跑，Dart 对话框无此接线。TV 不走此页，无影响。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon codegen**：新合约含 10 个数据类、3 级嵌套 List（`B19RemoteIndex.candidates[].permissionLabels`）、`List<String>` 入参（`getRemoteConfirm / installRemoteCandidates`）、同步 + @async + fire-and-forget 混用；`dart run pigeon` 与 Java 侧 `B19Messages` 生成、`B19HostApiImpl` 的方法签名逐字对应，CI 才能证明。重点核对：`pickEpoch` 的 `int/Long` 装箱、`resolveRemoteUrl / getRemoteConfirm / getPluginParams / getDeleteConfirm` 的同步形态、`@async` 方法的 `Result` 回调形态。
2. **G2 跨屏竞态（与 B7/B11/B14/B15 共担）**：B4 页的 `openPluginManager` 分流与 B15 `openLocalReader` 同款 start-then-finish；新 `C1ScreenActivity` 实例挂到已缓存的 C1 引擎上，`initialRoute` 取自首次创建时的 `C1HostApiImpl`，无重定向机制。真机复现时若插件页打不开或打开的是旧路由，需按 B7 文档的建议给 `C1ScreenActivity` 加 relaunch 机制；手机原生入口（`SettingEnhanceFragment`）不受此影响（直接 start，无 finish）。
3. **目录选择轮询时序**：FileActivity 在 C1ScreenActivity 之上时，C1 引擎与 Dart 页是否保持 resumed、500ms 轮询是否在后台被节流、用户在 FileActivity 停留超 10 分钟后回来的状态，均需真机验证。轮询封顶后若导入仍在跑，下一次 resumed 重取会补上列表。
4. **Busy 守卫与 `ownsBusy`**：`getPluginScreen` 轮询在导入进行中完成时必须不清 `busy`；反之动作完成必须清。逻辑在 `Completion(ownsBusy)` 一个布尔上，写错即死锁（按钮永久禁用）或提前解锁（两次重装并发）。真机验收 4.2 的连点用例覆盖。
5. **远程导入长链**：下载（20s）+ 索引在 35s 守卫内完成；弱网下的超时快照、候选框与确认框之间 host 侧 `remoteZip / remoteCandidates` 的持有（页面 dispose 后失效，`getRemoteConfirm.valid=false` 时 Dart 中止流程）；`installRemoteCandidates` 在 zip 已删时回批量失败 notice。
6. **参数保存的 reload 时序**：`savePluginParams` 成功后 host 立即 reload；Dart 对话框关闭与列表刷新的先后、以及参数实际生效（插件 init 阶段读走），需真机对照原生。
7. **`flutter analyze`**：新增 Dart 文件无未使用 import、无 warning（C1 run #8 教训：warning 即失败）。`b19_plugin_page.dart` 引用了全部 import（`services.dart` 供粘贴、`pigeon` 供全部调用）；`main.dart` 新增的两个 import 均被路由与开关引用。

## Recovery anchor

- **目标**：B19 把手机端插件管理页（`PluginManagerActivity`，1040 行）迁移到 Flutter，路由 `/plugins`（C1 引擎），开关 `flutter_b19_enabled` 默认关；安装 / 重载 / 文件选择 100% host 侧。
- **状态**：本地实现完成，未推送。基线 B18 提交 `77a55786`；分支 `b8-flutter-rewrite`；工作树待提交（见 `git status`）。
- **已完成**：只读发现（§1）；Pigeon 合约 + Dart 页（列表 + 三按钮 + URL/候选/确认/参数/删除对话框 + 目录选择轮询）+ `B19HostApiImpl`（单线程 worker + busy 守卫 + `ownsBusy` Completion + 远程五步 host 侧）+ main.dart 路由/开关 + C1/C0 注册 + C1 目录选择 launcher + Setting 开关 + 两处入口分流（`SettingEnhanceFragment`、`B4HostApiImpl.openPluginManager`）+ `.gitignore` / `b8-c0.yml` + 本文档。验证：`git diff --check`（finish 时复核）、作用域 diff 复核、main 仍为 `f39a6e0`。
- **未验证**：全部构建与运行（本机无 SDK）；§6 的 7 项留给 CI 联编 + 真机验收。
- **下一步（恰好一个）**：B20 海阔管理屏（`HkMiniappActivity`，321 行，Kotlin；列表/详情/搜索留原生 per P3）——按 `docs/b8-full-migration-plan.md` §3 顺序，起新任务护栏 `b8-b20-hk-manager`，新合约 `b20_hk_messages.dart`，开关 `flutter_b20_enabled` 默认关，不推送。
