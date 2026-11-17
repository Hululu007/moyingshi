# B20 海阔管理屏（B8 全量迁移）

## §0 执行声明

- **目标**：把手机端海阔管理屏（`HkMiniappActivity`，321 行 Kotlin）迁移到 Flutter，路由 `/hk-manager`（C1 引擎），开关 `flutter_b20_enabled` 默认关。海阔列表 / 详情 / 搜索留原生（规划 §6 P3 决策，Wei 2026-11-17 12:50 定“只迁管理屏”）；导入 / 导出 / 云口令 100% host 侧。
- **状态**：本地实现完成，未推送。基线 B19 提交 `60cec7b1`；分支 `b8-flutter-rewrite`；工作树待提交（见 `git status`）。
- **诚实声明**：本机无 Java / Android / Flutter SDK，构建与运行均未验证；Pigeon codegen、Gradle 编译、`flutter analyze` 全部留给联合 CI 构建；行为留给 Wei 真机验收。

## §1 原生实现（只读发现）

### 1.1 管理页

`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkMiniappActivity.kt`（321 行，mobile 源集，手机专属）：

- **列表数据来源**：`LocalSource.getSites()` 过滤 `HkStore.isMiniapp(it)`（api 或 key 以 `hk://` 开头）；行显示 `Site.name`（`标题⁽ʰᵏ⁾┃[类型]`，`HkStore.siteOf` 的格式）。`LocalSourceAdapter` 设为可勾选（`setCheckable(true)`），多选框只为导出服务，无选中计数 UI。`onResume` 时 `refreshList()` 重取。
- **五个按钮**（纵向全宽描边按钮，顺序见 `activity_hk_miniapp.xml`）：导入文件/目录、粘贴 JSON、同步已装规则、导出、云口令导入。
- **导入文件/目录**：`showPickChooser()` 弹选择框（文件 / 目录）→ `HkImportPicker.pickFiles()`（SAF 多选 `.json/.zip/.hkzip`，复制到缓存再导入）/ `pickDirectory()`（SAF 选目录，递归收规则文件）→ `HkStore.importFiles`（后台线程）→ 回调 `onImported(added)`。
- **粘贴 JSON**：多行等宽输入框（hint `hk_miniapp_paste_hint`，6–10 行）→ 空白时 toast `hk_miniapp_import_error % "规则内容为空"` 且框不关 → `HkStore.importJsonText`（后台线程，先写缓存文件再走 `importFiles`）→ `onImported`。
- **云口令导入**：多行等宽输入框（5–12 行，12sp）+ 对话框 message 是公开短链提醒（`hk_miniapp_export_share_note`）；打开时读剪贴板预提取口令填进去（`HkCloudImport.extractCloudCodes`，提取不到只留空框，不 toast、不关框）；确认时再提取一次，无口令 toast `hk_miniapp_cloud_code_none` 且框不关 → `HkCloudImport.importCloudCodes`（后台线程，逐条取回 + `importJsonText`）→ 部分成功报成功数 + 最后一条失败原因并重载配置 + 刷新 + 预热；全失败报 `hk_miniapp_import_error` 或 `hk_miniapp_no_rule`。
- **同步已装规则**：`HkStore.syncSites()`（后台线程，枚举 `plugins/hk/*.json`，缺失的站源条目重新登记）→ toast `hk_miniapp_synced % 条数`，有新增才重载配置 → 刷新列表。不调预热。
- **导出**：取勾选的条目（只认小程序站源，标题去重保序）→ 逐条 `HkCloudExport.uploadTextDb`（云 2 textdb 上传，返回 `云2oooole/apidb/<key>`）→ 全失败 toast `hk_miniapp_export_failed`，部分失败 toast `hk_miniapp_export_partial`，全成功不 toast → 有口令就弹系统分享面板（`HkCloudExport.shareText`，首行是公开短链提醒；标题 1 条 / 多条两种）。导出中 `btnExport` 禁用，结束恢复。
- **导入回调 `onImported(added)`**（主线程）：`added > 0` → toast `hk_miniapp_imported % 条数` + 重载配置（`VodConfig.load(Config.vod())` + `ConfigEvent.vod()`）+ `HkWarmup.warmUpAfterImport()`；`added == 0` → `lastError` 为空报 `hk_miniapp_no_rule`，否则报 `hk_miniapp_import_error % 原因`；最后刷新列表。
- **点一条规则**：`HkListActivity.open(this, HkStore.titleOf(item))`，进它自己的列表页（留原生，不切站源）。
- **行末删除**：立即删除，无确认框：`LocalSource.removeByKeys([key])` + `HkStore.delete(title)` + `HkCache.invalidate(title)` → toast `lab_local_source_deleted % 1` + 重载配置 + 刷新列表。
- **HkImportPicker**（mobile 源集，只被 `HkMiniappActivity` 使用）：SAF 接线，`pickFiles` / `pickDirectory`，复制 + 收集在后台线程；取消选择器时不回调（本批加了 `onCancelled` 回调，见 §2.2）。

### 1.2 入口

`HkMiniappActivity` 没有 `start()` 方法，唯一入口是实验室的隐式 intent（grep 全仓）：

| 调用点 | 位置 | 处理 |
| --- | --- | --- |
| 实验室“海阔小程序”分组 + 菜单 | `app/src/main/.../lab/LabActivity.java:140` → `openMiniappManager()`（:337） | **B20 分流**：手机且开关开 → `C1ScreenActivity.start(ROUTE_HK_MANAGER)`（start-only，不 finish，LabActivity 仍在返回栈里，与原生一致），关 → 原来的隐式 intent（本批改动，分流处 only） |
| mobile AndroidManifest intent-filter | `${applicationId}.hk_miniapp` + `DEFAULT` | 不动（原生入口声明保留） |

`HkWarmup.java:55` 的注释提到管理页但不打开它；`SettingSearchIndex` / B4 的 `hkMiniapp` 行是“显示海阔小程序”开关，不是入口。TV：`LabActivity` 与 TV 共用，`Util.isMobile()` 为 false 时永远走隐式 intent；leanback 没有 `HkMiniappActivity`（mobile manifest 专属），`resolveActivity` 为 null，什么都不发生——与今天一致。

### 1.3 字符串与资源归属（按目录分别 grep 验证，无 head 截断）

- 全部 `hk_miniapp_*`（26 条，`lab_strings.xml:68–88,104–108`）都在 **main** res；leanback / mobile res 里一条都没有。
- 共用字符串 `lab_import / lab_local_source_deleted / dialog_cancel / dialog_confirm / error_retry / error_play_timeout / setting_delete` 同样全部只在 main res。
- 结论：`B20HostApiImpl`（mobile 源集，mobile flavor 合并 main res）直接引用全部 `R.string` 都能解析；leanback 构建根本不编译这个文件，无需 `mobileString()` 回落。主源集改动（`C1ScreenActivity` / `C0ShellActivity` / `LabActivity` / `Setting`）不新增任何 `R.string` 引用。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- `Setting.flutter_b20_enabled`（`Prefers`，默认 false），开关 UI 只在 C0 实验屏（`main.dart` 新增一行业）。
- 关 = 实验室入口回原生 `HkMiniappActivity`，原生实现一行不删；开 = 走 `/hk-manager`。
- TV 入口（leanback `LabActivity`）不改，`Util.isMobile()` 门控保证 TV 永远走隐式 intent。

### 2.2 §4.8 第 5 招：impl 落点 + 注册器间接层（本批核心设计）

规划 §4.8 只给了方向（“impl 放 mobile 源码集 + 注册器间接层”），细节由本批定，落地如下：

**落点清单**

| 文件 | 源集 | 作用 |
| --- | --- | --- |
| `app/src/mobile/.../flutter/B20HostApiImpl.java` | mobile | B20 合约的 impl（新建，约 600 行）；直接引用全部 Hk API（`HkStore` / `HkCloudImport` / `HkCloudExport` / `HkImportPicker` / `HkCache` / `HkWarmup` / `HkListActivity` / `LocalSource`，全是 mobile 或 main 可见） |
| `app/src/mobile/.../flutter/B20Registrar.java` | mobile | 注册器（新建，约 60 行）：静态 `register(messenger, activity)` / `unregister(messenger)`，per-messenger（每引擎一份 impl） |
| `app/src/mobile/.../api/hk/HkImportPicker.kt` | mobile | 只加一个可选 `onCancelled` 回调（默认空实现）+ 两处取消分支调它；原生调用点源码不动 |
| `app/src/main/.../flutter/C1ScreenActivity.java` | main | 反射调 `B20Registrar`（注册 + 反注册 + onCreate 重绑定），只加法 |
| `app/src/main/.../flutter/C0ShellActivity.java` | main | 同上（C0 引擎只为实验屏开关用） |
| `flutter_module/pigeons/b20_hk_messages.dart` | — | 合约；生成的 `B20Messages.java` 落 main 源集（与其他批一致，leanback 也编译它，但无人注册） |

**为什么不用接口**：注册器只有两个静态方法（`register` / `unregister`），主源集侧只用反射调静态方法，不需要一个 main 源集的接口类型；静态方法 + `Class.forName` 是仓库里已有的形态（`C2HostApiImpl` 的 `SyncDialog` 反射、`Startup` 的事件索引反射）。类名与方法名能过 R8：`app/proguard-rules.pro:38` 有 `-keep class com.github.moyingshi.osc.tv.** { *; }`，整个包都不混淆、不裁剪。

**为什么每引擎一份 impl（per-messenger map）**：C0 与 C1 是两个引擎、两个 Activity；impl 持有 Activity（SAF picker + 分享面板 + 剪贴板）与单线程 worker，必须按引擎隔离。map 的 key 是 `BinaryMessenger`（即 `engine.getDartExecutor()`），与引擎同生命周期。

**旋转重绑定（本批相对 B19 的改进）**：缓存引擎活得比 Activity 久（旋转），但 SAF picker 的 launcher 必须注册在活着的 Activity 上。`C1ScreenActivity.onCreate`（`super.onCreate` 之后）与 `C0ShellActivity.onCreate` 都再调一次 `register`：首次调创建 impl + `setUp`，之后的调用只调 `impl.rebind(activity)`（换 Activity + 新建 picker，worker / busy / pick 状态不断）。于是旋转中进行中的导入不断（不断 watchdog），旋转后文件/目录导入、导出分享、行点进列表仍可用。B19 没有这一步（它的目录选择 launcher 在旋转后丢结果，本批不沿用那个缺口）。`unregister` 只在 `onDestroy + isFinishing` 时调（引擎销毁），关掉 worker、释放已销毁的 Activity 引用；旋转时 `isFinishing()` 为 false，不调。

**C0 引擎的 impl**：与之前各批一致，C0 引擎也注册一份完整 impl（只调 flag 方法）。构造时会创建一个 `HkImportPicker`（在 C0ShellActivity 上注册 launchers），无害——C0 引擎上永远不会有人点导入。

**HkImportPicker 的 onCancelled**：原生不需要取消信号（对话框直接关掉），Flutter 的轮询需要停表信号。改动是加法：构造函数第三参数 `onCancelled: () -> Unit = {}`（Kotlin 默认参数，原生调用点 `HkImportPicker(this) { added -> ... }` 源码不动）；空列表 / `uri == null` 时调它。`B20HostApiImpl`（Java）调三参构造，lambda 显式 `return Unit.INSTANCE`（Java lambda 不能把 void 调用当作 Kotlin `Unit` 返回值）。

### 2.3 快照与完成规则（本批核心设计）

- **单快照类型**：全部 `@async` 动作方法都返回 `B20HkScreen`（列表 + busy + 一次性 `noticeText` / `dialogErrorText`）；Dart 只渲染，不计算、不硬编码文案。
- **单线程 worker**：`B20HostApiImpl` 持一个 `newSingleThreadExecutor`（懒建，`disposeHk()` 关停），与 B19 同构；`busy` 守卫 + 立即完成（busy 时到来的动作直接回当前快照，notice 为空——Hk 串里没有对应的 busy 文案，Dart 已显示进度条与禁用按钮，见 §5 V11）。
- **Completion 带 `ownsBusy`**：`getHkScreen` 从不置 busy，它的 Completion 完成时**不**清 busy；动作方法的 Completion 才清（B19 §2.2 的修正，本批沿用）。
- **35s 守卫**：每个 `@async` 调用都有 `Completion` + 35s watchdog，超时回错误快照；迟到的后台完成是 no-op。**例外：导出用 180s 守卫**（见 §5 V5，多标题逐个上传）。
- **文件/目录选择不走 `@async`**：SAF 选择器等用户浏览，无法塞进 35s 守卫。Dart 调 fire-and-forget 的 `requestHkImportPick(kind)`，host 走原生 `HkImportPicker`（launcher 在 impl 构造时注册），Dart 以 500ms 间隔轮询 `getHkScreen()` 看 `pickState / pickEpoch`（B19 V1 形态），epoch 变化且回到 idle 时出结果 toast，10 分钟封顶。取消选择时 `onCancelled` 把状态拨回 idle、epoch +1、message 为空（原生静默，Dart 什么都不显示）。
- **粘贴 / 云口令的对话框错误**：空白粘贴与无云口令都走 `dialogErrorText`，Dart 保持对话框打开并内联显示（B19 `savePluginParams` 同款；原生是 toast + 框不关，框不关一致，呈现由 toast 改内联，见 §5 V6）。
- **导出**：`exportMiniapps(keys)` 在 worker 上逐条上传（原生顺序：去重保序、记首错、失败继续）；完成后在主线程 handler 里开分享面板（Dart 打不开分享面板）+ 完成快照。全成功无 toast、只有面板；部分失败 toast + 面板；全失败只 toast、不开面板——与原生一致。
- **删除**：无确认框（原生如此，照搬），worker 上按原生顺序执行（删站源条目 → 删规则文件 → 清缓存），完成时 notice + 重载配置。
- **resumed 重取**：`didChangeAppLifecycleState(resumed)` 时重取（规划 §4.6；原生 `onResume` 本来就刷新）；前台 EventBus 推送缺口与全批一致（接受缺口）。
- **无 EventChannel**：全部状态变化都走快照或轮询。

### 2.4 Pigeon 合约（`flutter_module/pigeons/b20_hk_messages.dart`）

`B20HostApi`：`isB20Enabled / setB20Enabled`；`getHkShell`（同步，18 个 chrome 字符串）；`getHkScreen / importPasteJson / importCloudCodes / syncMiniapps / exportMiniapps / deleteMiniapp`（@async，均返 `B20HkScreen`）；`requestHkImportPick / openMiniappList / disposeHk`（fire-and-forget）；`getCloudCodePrefill`（同步，剪贴板读 + 口令提取）。数据类全部扁平：`B20HkShell / B20HkItem / B20HkScreen / B20CloudCodePrefill`；`pickEpoch` 为 `int`（Java `Long` 装箱），方法名与 Dart 定义字符级一致。

### 2.5 Dart 页（`flutter_module/lib/b20_hk_page.dart`）

- `B20HkPage`（字面路由 `/hk-manager`，无参数）：AppBar（标题）+ 五个全宽 `OutlinedButton` 纵向排布（顺序与原生布局一致：导入文件/目录、粘贴 JSON、同步已装规则、导出、云口令导入）+ busy 进度条 + 列表 / 空态 / 错误态（带重试）。
- 每行：前置 `Checkbox`（只勾选，服务导出）+ 名字（host 给的 `标题⁽ʰᵏ⁾┃[类型]`）+ 行末删除按钮；点行进原生列表页（`openMiniappList`），点复选框只切换勾选——用 `ListTile + 前置 Checkbox` 分开两个点击目标（`CheckboxListTile` 整行都会触发勾选，不符合原生，见 §5 V8）。
- 对话框全部 Dart 侧：文件/目录选择框（两个选项）、粘贴框（等宽字体，6–10 行）、云口令框（等宽 12sp，5–12 行，顶部是 host 给的公开短链提醒，打开时 `getCloudCodePrefill` 预填剪贴板口令）。
- 轮询：`Timer.periodic(500ms)`，epoch 变化且 idle 时停表并出 toast；10 分钟封顶。`dispose()` 停表并调 `disposeHk()`。

### 2.6 Host 实现（`B20HostApiImpl.java`，mobile 源集）

- 逐字复制的原生逻辑：`refreshList`（`LocalSource.getSites()` 过滤 `isMiniapp`）、`onImported`（notice + 重载配置 + 预热）、云口令确认分支（部分成功 / 全失败 / 无规则）、`runSync`（有新增才重载）、`runExport`（去重保序、逐条上传、首错、分享面板标题 1 条 / 多条）、`onDeleteClick`（删条目 → 删规则 → 清缓存 → notice → 重载）、`onItemClick`（`HkListActivity.open`）。
- `readMiniapps()` 与原生 `refreshList` 用同一个调用；导出与删除先在快照里找 `titleOf`，再动手。
- `canTouchView()`（B19 同款）：分享面板与列表页只在 Activity 活着时打开。

### 2.7 文件清单

| 文件 | 动作 |
| --- | --- |
| `flutter_module/pigeons/b20_hk_messages.dart` | 新：B20 合约 |
| `flutter_module/lib/b20_hk_page.dart` | 新：海阔管理页 |
| `flutter_module/lib/main.dart` | 改：`/hk-manager` 路由 + B20 开关（只加法） |
| `app/src/mobile/.../flutter/B20HostApiImpl.java` | 新（mobile 源集） |
| `app/src/mobile/.../flutter/B20Registrar.java` | 新（mobile 源集）：静态 register / unregister |
| `app/src/mobile/.../api/hk/HkImportPicker.kt` | 改：加可选 `onCancelled` 回调 + 两处取消分支（原生调用点源码不动） |
| `app/src/main/.../flutter/C1ScreenActivity.java` | 改：`ROUTE_HK_MANAGER` + `registerB20/unregisterB20` 反射方法 + ensure/onCreate/onDestroy 三处调用（只加法） |
| `app/src/main/.../flutter/C0ShellActivity.java` | 改：同上（C0 引擎） |
| `app/src/main/.../setting/Setting.java` | 改：`flutter_b20_enabled`（只加法） |
| `app/src/main/.../lab/LabActivity.java` | 改：`openMiniappManager()` 分流处 only + 1 个 import |
| `.gitignore` | 改：+ `B20Messages.java` |
| `.github/workflows/b8-c0.yml` | 改：Pigeon 步骤 + b20 生成 + 存在性检查 |
| `docs/b8-b20-hk.md` | 新：本文档 |

## §3 状态来源清单（全部 host 快照，Dart 不计算）

| Dart 展示 | Host 来源 |
| --- | --- |
| 标题 / 五按钮 / 空态文案 / 对话框按钮 | `getHkShell()`（`hk_miniapp_*` 等，main res） |
| 列表行：名 / key | `B20HkScreen.entries`（`LocalSource.getSites()` 过滤 `isMiniapp` + `site.getName()` / `site.getKey()`；`titleOf` 只在 host 内部用，Dart 不需要） |
| busy / 进度条 / 按钮禁用 | `B20HkScreen.busy`（host `busy` 守卫）+ Dart 本地 `_busy` |
| 加载失败 / 重试 | `B20HkScreen.loadErrorText`（watchdog 超时文案 `error_play_timeout`） |
| 操作 toast | `B20HkScreen.noticeText`（已导入 / 已同步 / 导出失败 / 部分导出 / 已删除 / busy 时为空） |
| 粘贴 / 云口令框就地错误 | `B20HkScreen.dialogErrorText`（空白粘贴 / 无云口令 / busy） |
| 文件/目录选择进行中 / 结果 | `B20HkScreen.pickState / pickEpoch / pickMessage`（host 轮询状态机） |
| 云口令框预填 | `B20CloudCodePrefill.prefillText`（剪贴板 + `extractCloudCodes`，每行一条） |
| 导出分享面板 | host 直接 `startActivity(createChooser)`（Dart 打不开） |

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 列表与开关

- [ ] 开关开：实验室“海阔小程序”分组 + 菜单进 Flutter 页，列表与原生一致（名字 `标题⁽ʰᵏ⁾┃[类型]`、顺序）；开关关：回原生页。
- [ ] 空列表时显示空态文案；加载失败时显示错误态 + 重试。
- [ ] 切后台再回来，列表重取（resumed 重取，与原生 `onResume` 一致）。

### 4.2 导入文件 / 目录

- [ ] 点“导入文件/目录”：出文件/目录二选一框；选文件（可多选 `.json/.zip/.hkzip`）后导入成功 toast、列表多一条；选目录后递归导入；取消选择器：无 toast、无 busy 残留、列表不变。
- [ ] 选无规则文件：报“没有找到可导入的规则”；导入后首页站点列表里出现新小程序站源（重载配置生效）。

### 4.3 粘贴 JSON 与云口令

- [ ] 粘贴框：空白点导入，框不关、框内显示导入失败原因；贴合法规则 JSON，导入成功 toast、列表刷新。
- [ ] 云口令框：剪贴板有口令时打开即预填（每行一条）；无口令时空框；输无效文本点导入，框不关、框内显示“没有识别到云口令”；输有效口令，导入成功 toast、列表刷新；部分成功时报成功数 + 失败原因。
- [ ] 云口令框顶部有公开短链提醒（云口令不是加密）。

### 4.4 同步与导出

- [ ] 点“同步已装规则”：toast 已同步条数；删掉站源条目、保留规则文件后同步，条目重新登记回来。
- [ ] 不勾选点“导出”：toast 请先勾选；勾选 1 条导出：上传后弹系统分享面板（标题“导出：<标题>”）；勾选多条：标题“导出 N 个小程序”；分享文本首行是公开短链提醒、随后每行一条“标题：云口令”。
- [ ] 导出失败（断网）：toast 导出失败，不弹分享面板。

### 4.5 行操作与删除

- [ ] 点一行：进原生小程序列表页（仍是原生页，不是 Flutter）。
- [ ] 点行末删除：立即删除（无确认框，与原生一致），toast 已删除，列表少一条，规则文件也删（同步不再把它登记回来）。

### 4.6 开关与回滚

- [ ] 实验屏关掉 `flutter_b20_enabled`：实验室入口立即回原生，其他批不受影响；全部开关关 = 全部原生。
- [ ] TV：实验室“海阔小程序”分组行为无变化（无管理页可开，与今天一致）。
- [ ] 旋转：旋转后文件/目录导入、导出分享、行点进列表仍可用；旋转中进行中的导入不断。

## §5 相对规划的全部偏差

- **V1（§4.8 第 5 招的落地形态）**：规划只定方向，细节由本批定——注册器是 mobile 源集的静态方法类（`B20Registrar.register/unregister`，per-messenger map），主源集侧只用 `Class.forName + getMethod + invoke` 调静态方法，不设 main 源集接口类型（两个方法不需要接口）。类名与方法名过 R8 靠整包 keep（`proguard-rules.pro:38`）。
- **V2（旋转重绑定）**：`onCreate` 后再调一次 `register`（`rebind`），保证 SAF picker 挂在活 Activity 上；进行中的任务不断。B19 没有这一步（旋转后目录选择丢结果），本批不沿用那个缺口。这是新增机制，不是规划原话。
- **V3（HkImportPicker 加 onCancelled）**：取消选择器时原生不需要信号，Flutter 轮询需要停表信号。改动是加法（Kotlin 默认参数，原生调用点源码不动）；取消时 `pickMessage` 为空、epoch +1，与原生静默一致。
- **V4（busy 守卫）**：原生按钮在操作进行中基本不禁用（只有云口令/导出按钮自禁用），本批统一 busy 守卫 + Dart 禁用全部按钮；快速连点时回当前快照（无 toast），而不是像原生那样再起一个线程。用户可见行为只在连点时不同。
- **V5（导出 watchdog 180s）**：35s 约定的例外——导出是多标题逐个上传（每条 15s/20s 超时），35s 守卫会在慢网多标题时先触发、分享面板永远打不开。180s 覆盖少量慢上传；Dart 侧只显示进度条，无 Dart 侧超时。
- **V6（对话框错误内联化）**：空白粘贴与无云口令时，原生是 toast + 框不关；Dart 是框内 inline 错误 + 框不关（B19 `dialogErrorText` 同款）。文案相同、框不关一致，呈现由 toast 改内联。
- **V7（删除无确认框是忠实复刻，不是偏差）**：原生 `onDeleteClick` 就是立即删除；Dart 同样立即删除。记在这里是怕验收时误以为缺了确认框。
- **V8（行点击目标拆分）**：原生行点进列表页、复选框点切换勾选；`CheckboxListTile` 整行都会触发勾选，不符合原生，Dart 改用 `ListTile + 前置 Checkbox` 分开两个点击目标。
- **V9（按钮纵向排布）**：原生五个按钮是纵向全宽描边按钮；Dart 用五个全宽 `OutlinedButton` 纵向排布，顺序与原生布局一致（导入文件/目录、粘贴 JSON、同步已装规则、导出、云口令导入）。
- **V10（导出分享面板 host 侧开）**：Dart 打不开 Android 分享面板，`exportMiniapps` 完成后 host 在主线程直接 `startActivity(createChooser)`；全成功无 toast、只有面板，与原生一致。
- **V11（busy 时无 toast）**：Hk 串里没有对应的 busy 文案（B19 有 `plugin_manager_remote_busy`），busy 时到达的动作回当前快照、notice 为空；Dart 已显示进度条与禁用按钮。只在连点竞态下可见。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon codegen**：新合约含 4 个数据类、`List<String>` 入参（`exportMiniapps`）、同步 + `@async` + fire-and-forget 混用；`dart run pigeon` 与 Java 侧 `B20Messages` 生成、`B20HostApiImpl` 的方法签名逐字对应，CI 才能证明。重点核对：`pickEpoch` 的 `int/Long` 装箱、`getCloudCodePrefill` 的同步形态、`@async` 方法的 `Result` 回调形态。
2. **注册器间接层（§4.8 第 5 招）**：leanback 构建——mobile 源码集缺失时 `C1ScreenActivity` / `C0ShellActivity` 的反射调用静默跳过，leanback 编译通过；mobile 构建——反射找到 `B20Registrar`，`setUp` 成功，`/hk-manager` 能打开；R8 下 `B20Registrar` 类名与 `register/unregister` 方法名保留（整包 keep）。三点都只能由联合 CI + 真机证明。
3. **旋转重绑定**：旋转后文件/目录导入、导出分享、行点进列表仍可用；旋转中进行中的导入不断（§4.6 最后一项覆盖）。`HkImportPicker` 在 `ensureFlutterEngine`（`super.onCreate` 之前）构造时调 `registerForActivityResult` 是否合法，是本批新增的风险点（B19 的 launcher 是 Activity 字段初始化，时序同款，但 picker 是 impl 构造时注册——真机旋转 + 导入用例覆盖）。
4. **SAF 取消回调**：取消文件/目录选择器后轮询停表、无 toast、无 busy 残留；`HkImportPicker` 的 `onCancelled` 在两种取消路径（空列表 / `uri == null`）都触发。
5. **导出长链**：多标题导出在 180s 守卫内完成、分享面板弹出、部分失败 toast；弱网下的超时快照与重试；`disposeHk` 后迟到的分享面板不弹出（`canTouchView` + `disposed` 双守卫）。
6. **云口令长链**：多口令逐条取回（每条 15s/20s 超时）与 35s 守卫的竞合——慢网多口令可能先触发 watchdog（快照错误 + 重试），原生无此上限；部分成功 notice + reload + warmup 的时序。
7. **`flutter analyze`**：新增 Dart 文件无未使用 import、无 warning（C1 run #8 教训：warning 即失败）。`b20_hk_page.dart` 引用了全部 import；`main.dart` 新增的两个 import 均被路由与开关引用。

## Recovery anchor

- **目标**：B20 把手机端海阔管理屏（`HkMiniappActivity`，321 行 Kotlin）迁移到 Flutter，路由 `/hk-manager`（C1 引擎），开关 `flutter_b20_enabled` 默认关；海阔列表/详情/搜索留原生；导入 / 导出 / 云口令 100% host 侧；impl 落 mobile 源码集 + 注册器间接层（规划 §4.8 第 5 招）。
- **状态**：本地实现完成，未推送。基线 B19 提交 `60cec7b1`；分支 `b8-flutter-rewrite`；工作树待提交（见 `git status`）。
- **已完成**：只读发现（§1）；Pigeon 合约 + Dart 页（列表 + 五按钮 + 文件/目录选择 + 粘贴/云口令对话框 + 导出勾选 + 轮询）+ `B20HostApiImpl`（mobile 源集：单线程 worker + busy 守卫 + `ownsBusy` Completion + 导入/同步/导出/删除 host 侧）+ `B20Registrar`（静态 register/unregister + per-messenger map + rebind）+ `HkImportPicker` 的 `onCancelled` 回调 + main.dart 路由/开关 + C1/C0 反射注册 + Setting 开关 + 实验室入口分流（`LabActivity.openMiniappManager`）+ `.gitignore` / `b8-c0.yml` + 本文档。验证：`git diff --check`（finish 时复核）、作用域 diff 复核、main 仍为 `f39a6e0`。
- **未验证**：全部构建与运行（本机无 SDK）；§6 的 7 项留给 CI 联编 + 真机验收。
- **下一步（恰好一个）**：B21 实验室列表/详情（`LabActivity` 940 + `LabDetailActivity` 435，手机与 TV 共用——TV 入口隔离是批内必查项；终端/输出留原生 per P4）——按 `docs/b8-full-migration-plan.md` §3 顺序，起新任务护栏 `b8-b21-lab`，新合约 `b21_lab_messages.dart`，开关 `flutter_b21_enabled` 默认关，不推送。
