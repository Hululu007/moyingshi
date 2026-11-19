# 阶段 6b：导入导出助手 + 管理页改走新类 + 删旧云导入导出

> Recovery anchor — 目标：阶段 6 第二笔。新增 `HkShareHelper`/`HkPublishHelper`/
> `HkQrUtil`/`HkAccountPwd`（星辰原文 + §2 适配）；`HkRuleManager` 加
> `extractCloudCodes`（口令提取）；`HkStore` 加 `importCloudCodes`/
> `registerImportedRules`（云口令批量导入 + 已导入规则登记）；`HkImportPicker`
> 重写后端走 `HkShareHelper.importFromFile`；`HkMiniappActivity` 五路入口改走
> `HkRuleManager` + 新助手（行为保持）；删除 `HkCloudImport`/`HkCloudExport`。
> 验收：五路入口代码级走通（§4）；`git diff --check` 通过；删除文件全树零引用复核；
> 0 leanback。只做阶段 6b，做完即停，不开 6c；不推、不编、不触发 workflow。
> 状态：文档先行（2026-11-19 17:10）；实现未开始。下一步唯一动作：开 guard 会话 6b 后实现。

- 完成句：管理页五路入口改走星辰导入导出助手、旧云导入导出删除，6b 提交后可回滚。
- 允许路径：`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkShareHelper.java`（新）、
  `HkPublishHelper.java`（新）、`HkQrUtil.java`（新）、`HkAccountPwd.java`（新）、
  `HkRuleManager.java`、`HkStore.java`、`HkImportPicker.kt`、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkMiniappActivity.kt`、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkCloudImport.java`（删）、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkCloudExport.java`（删）、
  `docs/miniapp-xingchen-6b-share-publish.md`（本文档）。
- 保护路径：无初始脏文件（`git status` 干净，HEAD `4e71fb73`，6a 已提交）。
- 本地时间：2026-11-19 17:10 CST（Asia/Shanghai）。
- 预计：实现约 1.5 小时；验收约 20 分钟；预计 19:00 前提交。
- 最便宜的决定性验证：`grep -rn "HkCloudImport\|HkCloudExport"` 代码零命中
  + `HkMiniappActivity` 五个按钮的调用点逐个 grep 在位 + `git diff --check` 通过。

## 1. 来源与范围

- 任务书：阶段 6 任务（6b 导入导出助手+管理页改走新类）；计划文档
  `docs/miniapp-xingchen-port-plan.md` §2 阶段 6、§1.4；6a 文档
  `docs/miniapp-xingchen-6a-cloud-clip.md`（云区已落，`importByCloudCode`/
  `exportAsCloudCode`/`exportAsCmdImCode` 就绪）；仓库规约
  `~/workspace/projects/Mooyingshi/AGENTS.md`。
- 星辰源码：`~/workspace/xingchen-ref`（HEAD `52450c0`）。
- 本笔不做：`HkAuthDialog`/`HkAuthManager` + `openAppIntent`/`publishRule` 接线（6c；
  `HkPublishHelper.publishRule` 的调用方在 6c 落）、`HkHikerRuleUtil` 异步版（6c 收尾）、
  阶段 7（`HkPageActivity`、合集浏览面、剩余 event、首页订阅定去留、ConfirmHandler 接管）。
- 起点已核实：6a 提交 `4e71fb73`（`HkCloudClip` + `HkRuleManager` 云区 + `parseJsonList`
  分发）；旧类调用方只有 `HkMiniappActivity.kt` 与 `HkStore.java` 一处注释
  （`grep -ln HkCloudImport|HkCloudExport|HkImportPicker` 全树仅此二文件）。

## 2. 设计决定（每项都有 grep/代码证据，不是推测）

### 2.1 `HkShareHelper`：星辰原文 + 一处适配

- 星辰 `HkShareHelper.java`（131 行）原文搬运，包名改写。方法：`detectFromClipboard`/
  `shareAsCommand`/`importFromText`/`importFromFile`/`shareToCloudClip`/
  `downloadFromCloudClip`/`cloudClipName`。
- 唯一适配：`ToastUtil.show(context, "复制失败", …)`（星辰无我方 `ToastUtil` 工具类，
  5a `HkErrorUtil` 已有“`ToastUtil`→`Toast.makeText().show()`”先例）→
  `Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()`。
- 依赖闭合：`unwrapHomeRuleCommand`/`buildShareCommand`/`importJson`/`importJsonList`/
  `importHkZip`/`isCollectionPackage`/`extractCollectionPackage`/`get()`——6a 后
  `HkRuleManager` 全有（前两项 6a 补，余项 2b 已有）。
- 合集支注释引用的 `HkPageActivity.openCollectionBrowser` 是阶段 7 的合集浏览面，
  本笔不建；`importFromFile` 的合集支只解压落盘、回空列表（与星辰同形，见
  `HkRuleManager.extractCollectionPackage` 的 2b 文档）。行为变化见 §5。

### 2.2 `HkPublishHelper` + `HkAccountPwd`：星辰原文 + 两处适配

- 星辰 `HkPublishHelper.java`（193 行）与 `HkAccountPwd.java`（模型，account/password
  两字段 + 无参/两参构造 + getter/setter 全套）原文搬运，包名改写。
- 适配 1（XPopup→原生 Dialog；我方无 XPopup 依赖，硬约束“不引入 XPopup”）：
  `showPublishGuide` 的 `XPopup.Builder.asConfirm` → `AlertDialog.Builder`
  （同标题「提交云仓库说明」、同正文、同「取消」/「设置规则」钮；确认支仍是空回调 +
  注释：`PublishCodeEditActivity` 编辑页不存在，落树后再改指；取消支照官为空）。
  `borderRadius` 无原生等价物，丢弃（记名）。
- 适配 2：`ToastUtil.show` → `Toast.makeText().show()`（同 §2.1）；`App.get()` 我方已有
  （`App.java:87`）；`HkJsRuntime.getInstance()`/`getMyRule`/`generateMY`/
  `evalJS(script, "", false)` 我方全有（`HkJsRuntime.java:556/11214/602/13330`）。
- 存储载体：星辰类头注已写明官走 LitePal `BigTextDO` 两键、星辰侧以 SharedPreferences
  「hk_publish」同名两键承接——照搬该口径（`publish_code` 存提交云仓库的 JS 规则原文、
  `publish_account_code` 存 `{account,password}` JSON 串）。**本笔不建任何 LitePal。**
- 无调用方，记名：`HkPublishHelper.publishRule` 的唯一调用方是星辰
  `HkJsRuntime.putFunction("publishRule")`（星辰 `HkJsRuntime.java:5637-5639`），
  我方引擎尚未注册该 JS 函数——接线在 6c。本笔只落类本身。

### 2.3 `HkQrUtil`：星辰原文 + 两处适配

- 星辰 `HkQrUtil.java`（145 行）原文搬运，包名改写。语义：`dealQRContent`——`http`
  开头走系统浏览器 `ACTION_VIEW`，否则弹原生 Dialog（标题「扫描结果」，「取消」/「复制」钮）。
- 适配 1：`ToastUtil.show` → `Toast.makeText().show()`（同 §2.1）。
- 适配 2：星辰 `R.drawable.dialog_glass_dark` 我方不存在 → 现有
  `R.drawable.shape_dialog_glass_panel`（`app/src/main/res/drawable/`，圆角 22dp
  深色渐变 + 白色描边，同属玻璃面板系）。其余程序化建视图一字不改。
- 无调用方，记名：星辰全树 grep `HkQrUtil.` 零调用方；我方管理页亦无扫码入口。
  本笔只落类本身（任务搬运清单点名），扫码入口是未来的 UI 项，不属阶段 6。

### 2.4 `HkRuleManager.extractCloudCodes`（新增，保旧 UI 行为）

- 管理页云口令对话框要“从一段文本提取多条口令、去重保序”（旧 `HkCloudImport.
  extractCloudCodes`）。星辰 `HkRuleManager` 无此批量提取法（星辰是单条
  `parseByCloudCode`）；为保管理页行为不丢，新增 `public static List<String>
  extractCloudCodes(String text)`：用 6a 已有的 `CLOUD_CODE_FIND` + 星辰
  `normalizeCloudCode`（口令 `oooole` 后缺 `/` 补 `/`），逻辑与旧法逐行同构
  （`CODE_EXTRACT` 图案与 `CLOUD_CODE_FIND` 同形；`normalizeCode` 与星辰
  `normalizeCloudCode` 同形）。这是唯一一处“旧法搬进新类”的方法，理由写清：
  星辰无等价批量提取，UI 需要它。

### 2.5 `HkStore` 新增两法（门面职责：登记是门面的职责）

- `public static int importCloudCodes(List<String> codes)`：逐条
  `HkRuleManager.get().importByCloudCode(code)` → `registerSites(singletonList(rule))`；
  单条失败记 `lastError`、记日志、继续下一条；返回成功登记条数。签名/语义与旧
  `HkCloudImport.importCloudCodes` 同构（旧法是 fetch→`HkStore.importJsonText`；
  新法是 `importByCloudCode` 直落盘，少一次经 cache 文件的转手）。管理页的
  部分成功/全失败分支（`lastError` 读法）不改。
- `public static int registerImportedRules(List<HkRule> rules)`：`registerSites(rules)`
  后回 `rules.size()`（空/空表回 0）。供重写后的 `HkImportPicker` 登记
  `HkShareHelper.importFromFile` 已落盘的规则（`HkRuleManager` 本身不登记站源）。
- `HkStore.importJsonText` 的注释在 6a 已改口径，本笔不动。

### 2.6 `HkImportPicker.kt` 重写（SAF 不动，后端换新助手）

- 保留：类名/构造（`activity, onImported`）、`pickFiles()`/`pickDirectory()`、
  两个 SAF launcher、`copyUriToCache`、`collectRuleFiles` 的目录递归、`onImported`
  回调语义（主线程、added 条数）。
- 改：`importUris`/`importDirectory` 不再调 `HkStore.importFiles`，改为逐文件调
  `HkShareHelper.importFromFile(file)`，成功后 `HkStore.registerImportedRules(rules)`
  累计 added；单文件失败记日志继续下一个（与旧 `importFiles` 的逐文件容错同口径）。
  失败文件不抛、不阻断其余文件；`onImported` 仍在主线程回调一次总数。
- `isRuleFile` 扩展 `.txt`/`.hiker`（星辰 `importFromFile` 支持的单规则文本格式；
  `.json`/`.zip`/`.hkzip` 保持）。SAF mime 数组已含 `*/*`，不用改。
- 差别（记名）：旧路 `HkStore.importFiles` 有 `mirrorToCache` 失败重试一次；
  新路无重试（星辰 `importFromFile` 无重试；失败即记日志继续下一文件）。
  差别 2（合集包，见 §5）：旧路合集 `.zip` 会经 `importZip`→`parseZip` 自动导入
  包内条目；新路 `isCollectionPackage` 命中后只解压落盘、added 计 0（星辰官形，
  2b `extractCollectionPackage` 文档已定谳：官形＝只解压＋浏览面逐个点选，
  旧自动导入是自家分叉）。合集浏览面在阶段 7，之前合集包导入后列表无新增是
  已知形态，见 §5。

### 2.7 `HkMiniappActivity.kt` 改走新类（五路入口，行为保持）

| 入口 | 旧路 | 新路 | 行为变化 |
|---|---|---|---|
| 文件/目录导入 | `HkImportPicker`（旧后端 `HkStore.importFiles`） | `HkImportPicker`（新后端 §2.6） | 仅 §2.6 两处差别（无重试；合集包只解压） |
| 粘贴 JSON | `HkStore.importJsonText(text)` | 不变 | 6a 后 `parseJsonList` 分发已吃云口令/￥ 包装文本（行为扩展，非改动） |
| 云口令导入 | `HkCloudImport.extractCloudCodes` + `importCloudCodes` + `lastError()` | `HkRuleManager.extractCloudCodes` + `HkStore.importCloudCodes` + `HkStore.lastError()` | 无；对话框剪贴板预提取、部分成功分支、按钮禁用/恢复均不动 |
| 同步已装规则 | `HkStore.syncSites()` | 不变 | 无 |
| 多选导出 | `HkCloudExport.uploadTextDb(title)` + `shareText(codes, note)` | `HkRuleManager.get().exportAsCloudCode(title)`（同款 textdb 上传：key=`xc<秒>_<hash>`、value=base64、`status==1`、口令 `云2oooole/apidb/{key}`）+ 分享文本改成本页私有 `shareText`（同格式：首行提醒 + 每行 `标题：云口令`） | 无；导出仍是云 2 textdb 单通道（云5/云6 等导出不在管理页，见 §5） |

- 删除 `HkCloudExport`/`HkCloudImport` 两行 import；其余（列表、删除、点规则进列表页、
  `reloadLocalConfig`、`HkWarmup` 预热）不动。
- 云口令安全属性：对话框 message 与分享文本首行仍用 `hk_miniapp_export_share_note`
  （“云口令是公开短链，不是加密，拿到口令的人都能取回这条规则。”），不弱化。

### 2.8 删除清单（删前全树 grep 零引用复核）

- `HkCloudImport.java`：调用方仅 `HkMiniappActivity`（§2.7 已改）与 `HkStore.java:553`
  注释（6a 已改口径，无代码引用）。删文件。
- `HkCloudExport.java`：调用方仅 `HkMiniappActivity`（§2.7 已改）。删文件。
- `HkImportPicker.kt`：不删文件，重写后端（§2.6）；调用方仅 `HkMiniappActivity`。

## 3. 云口令分发表（6a 已落，本笔复用，不重复核对）

- 取回主路：`HkRuleManager.importByCloudCode`（6a §3 表：云1=pastebin、云2=textdb
  `/apidb/`、云5=cmd.im、云6=pasteme `/xxxxxx/`、云7=note.ms、云9=txtpad、云10=hastebin、
  云3/4/8 不支持、云2 `/p/` netcut 已死）。管理页云口令导入走此表；云2/云5 重点核对
  （导出 `云2oooole/apidb/{key}` 与取回 `GET textdb.online/{key}` 对称；
  云5取回 `test_box` 与导出 `Location` 形对称——导出云5不在管理页，只核对取回）。
- 导出：管理页只做云 2（`exportAsCloudCode`）；`HkShareHelper.shareToCloudClip` 的
  云1/6/7/9/10 上传（`HkCloudClip.upload`）与云2/云5 指路（抛错指
  `exportAsCloudCode`/`exportAsCmdImCode`）是 6a 已落的助手能力，本笔无 UI 调用方。

## 4. 验收证据（代码级，不等构建）

- [x] 五路入口代码级走通：文件/目录导入（picker→`HkShareHelper.importFromFile`→
      `registerImportedRules`→`onImported`）、粘贴 JSON（`importJsonText`→6a 分发）、
      同步（`syncSites` 不变）、云口令导入（提取→`importCloudCodes`→`lastError` 部分成功支）、
      多选导出（`exportAsCloudCode`→分享面板）。调用点 grep 全数在位（见 finish 证据）。
- [x] 导出→导入往返（代码级）：导出得 `云2oooole/apidb/{key}` → 贴进云口令框 →
      `extractCloudCodes` 提取 → `importByCloudCode` → textdb 取回 → base64 解 →
      `unwrapShareText` → `parseJson` → `saveRule`（同标题单文件，去重见 6a §4）→
      `registerSites` → `mergeSites` 同 key 原位更新。同一规则重复导入不产生重复站点条目。
- [x] 删除复核：`git grep "HkCloudImport\|HkCloudExport" -- app/src/` 代码零命中
      （仅 5 处注释 provenance 提及）；`HkImportPicker` 的 `HkStore.importFiles` 调用清零。
- [x] `git diff --check` 通过；全树 grep：6b 文件内无 `com.fongmi.android.tv`（仅新文件
      类头注释里的星辰源路径，属允许例外）、无 LitePal、无 dex 动态加载、无 XPopup；
      0 leanback 文件。
- [x] 改动文件：跟踪文件 +121/−484（`HkCloudImport.java` −346、`HkCloudExport.java` −113
      两删；`HkImportPicker.kt` +55/−?、`HkRuleManager.java` +17、`HkStore.java` +39、
      `HkMiniappActivity.kt` +35/−?）；新建 `HkShareHelper.java` 134 行、
      `HkPublishHelper.java` 197 行、`HkQrUtil.java` 150 行、`HkAccountPwd.java` 40 行。
      新文件与星辰原文 diff 只剩 §2.1–2.3 的适配点（Toast/XPopup/drawable/类头注）。
- [x] 逐行重读：新文件与星辰原文逐项 diff 复核；管理页 diff 全量重读；javac/Kotlin 坑：
      `HkShareHelper` 的 `importFromFile` 返回 `List<HkRule>`、Kotlin 侧平台类型直传
      `registerImportedRules`；`HkStore.lastError()` 可空处沿用 `.orEmpty()`；无原始类型
      泛型、无 lambda 非 effectively final 捕获。

## 5. 实现步骤

1. 开 guard 会话 6b（scope 见首部允许路径；新文件先建再 `--adopt-dirty`）。
2. 新建 `HkAccountPwd.java`（星辰原文 + 包名）、`HkShareHelper.java`（§2.1）、
   `HkPublishHelper.java`（§2.2）、`HkQrUtil.java`（§2.3），逐行重读。
3. `HkRuleManager.java` 加 `extractCloudCodes`；`HkStore.java` 加 `importCloudCodes`/
   `registerImportedRules`；`HkImportPicker.kt` 重写后端；`HkMiniappActivity.kt` 改五路。
4. 删 `HkCloudImport.java`、`HkCloudExport.java`（先 grep 零引用复核）。
5. 验收（§4）：五路调用点 grep + 删除复核 + `git diff --check` + 行数统计；逐行重读改动
   （注意 javac 坑：见 6a 文档 §5 末段同款清单；Kotlin 侧注意 `HkShareHelper` 的 Java
   静态方法调用写法与空安全）。
6. `task_guard.sh finish` 提交并打 recovery tag。

## 6. 遗留（不归 6b）

- 6c：`HkAuthDialog`/`HkAuthManager` 评估后搬 + `openAppIntent`/`publishRule` 接线
  （届时 `HkPublishHelper.publishRule` 得调用方）；`HkHikerRuleUtil` 异步版恢复。
- 合集包导入后：只解压到 `_cache/<净名>.hk合集`、列表无新增（§2.6 差别 2）；合集浏览面
  （`HkPageActivity.openCollectionBrowser`）在阶段 7，之前合集包的条目点选入口不存在；
  且 picker 的 `onImported(0)` 会使管理页报 `hk_miniapp_no_rule`（“没有找到可导入的规则”），
  文案未区分“已解压待点选”——已知小 wart，随阶段 7 合集浏览面一起修，不在本笔加新串。
- 管理页导出仍是云 2 单通道；云5导出（`exportAsCmdImCode`）与云6/7/9/10/1 导出
  （`HkShareHelper.shareToCloudClip`）已有助手能力、无管理页入口——是否加出口令选择器，
  由用户定，不在本笔自造 UI。
- `HkQrUtil` 无 UI 调用方（扫码入口未建）；`HkShareHelper.detectFromClipboard`/
  `shareAsCommand` 无管理页调用方（剪贴板分享口令检测/复制是列表页退回支等未来的接线点）。
- 云1上传：无真实 pastebin dev key，公开构建恒不可用（6a 已记名）；云9取回侧站点已停服。
