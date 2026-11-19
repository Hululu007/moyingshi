# 阶段 6a：云剪贴板客户端 + 云口令分发表重做（HkRuleManager 云区）

> Recovery anchor — 目标：阶段 6 第一笔。新增 `HkCloudClip`（星辰原文）；`HkRuleManager`
> 补齐云口令区（`importByCloudCode`/`fetchPasteRawText`/`parseByCloudCode`/`unwrapShareText`/
> `unwrapHomeRuleCommand`/`buildShareCommand`/`exportAsCloudCode`/`exportAsCmdImCode` 等，
> 星辰 `HkRuleManager.java:1166-1888`），云口令分发表按星辰重做（旧 `HkCloudImport` 的
> “云1/7/9/10 全走 pasteme”是错的，本笔只建新路、不删旧类，删除在 6b）；`parseJsonList`
> 补星辰的云口令/分享包装分发；`HkJsRuntime.parsePaste` 的两处调用就此闭合。
> 验收：10 个云号逐个对照星辰原文（§3 表）；`git diff --check` 通过；全树无
> `com.fongmi.android.tv`（文件头注释的星辰路径除外）、无 LitePal、无 dex 动态加载；
> 0 leanback。只做阶段 6a，做完即停，不开 6b/6c；不推、不编、不触发 workflow。
> 状态：文档先行（2026-11-19 17:04）；实现未开始。下一步唯一动作：开 guard 会话 6a 后实现。

- 完成句：云剪贴板客户端与云口令分发表按星辰落地，parsePaste 调用闭合，6a 提交后可回滚。
- 允许路径：`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkCloudClip.java`（新）、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkRuleManager.java`、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkStore.java`（仅 `importJsonText`
  处一条过时注释）、`docs/miniapp-xingchen-6a-cloud-clip.md`（本文档）。
- 保护路径：无初始脏文件（`git status` 干净，HEAD `99c33321`，领先 origin/main 26 笔）。
- 本地时间：2026-11-19 17:04 CST（Asia/Shanghai）。
- 预计：实现约 1.5 小时；验收约 20 分钟；预计 19:00 前提交。
- 最便宜的决定性验证：`grep -rn "HkCloudImport" app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkRuleManager.java`
  为零（新路不引用旧类）+ `git diff --check` 通过 + 云号逐行对照表（§3）。

## 1. 来源与范围

- 任务书：阶段 6 任务（6a 云剪贴板+分发表）；计划文档 `docs/miniapp-xingchen-port-plan.md`
  §2 阶段 6、§1.4；仓库规约 `~/workspace/projects/Mooyingshi/AGENTS.md`（guard 会话先行、
  一个 guard 会话等于一次原子逻辑提交、提交即打 recovery tag、任务文档先行、只改声明的路径）。
- 星辰源码：`~/workspace/xingchen-ref`（HEAD `52450c0`）；交叉验证
  `~/workspace/hk-ref/src/sources/`（oktv 反编译）；语义参考 `~/workspace/hikerview-ref`。
- 本笔不做：删除 `HkCloudImport`/`HkCloudExport`/`HkImportPicker` 旧实现（6b）、
  `HkShareHelper`/`HkPublishHelper`/`HkQrUtil`/`HkAccountPwd`（6b）、
  `HkAuthDialog`/`HkAuthManager` 与 `openAppIntent`/`publishRule` 接线（6c）、
  `HkHikerRuleUtil` 异步版（6c 收尾）、阶段 7（`HkPageActivity`、剩余 event、
  首页订阅定去留、ConfirmHandler 接管）——只在 §6 记名。
- 起点已核实：本地 main `99c33321`，工作区干净；阶段 5 已完成（`HkRouter` 接门面，
  新引擎已接进执行链路）；`HkRuleManager.java:1021-1044` 的 3e 标记写明
  `parseByCloudCode`/`unwrapShareText` 是阶段 6 的、`HkRuleManager.java` 内另有
  「阶段 6：云口令取件与导出（HkRuleManager.java:1166-1888）在此补」标记；
  `HkJsRuntime.java:4480/4487` 的 `parsePaste` 已调 `importByCloudCode`/
  `fetchPasteRawText`（方法尚不存在，本笔补齐即闭合）。

## 2. 设计决定（每项都有 grep/代码证据，不是推测）

### 2.1 星辰云口令分发表（本笔核心正确性点）

旧版 `HkCloudImport.fetch()`（`HkCloudImport.java:113-122`）只分三路：云2→textdb、
云5→cmd.im、其余（云1/3/4/6~10）全走 pasteme——其中云1/7/9/10 走 pasteme 是错的。
星辰 `HkRuleManager.fetchCloudContent`（`HkRuleManager.java:1397-1565`）与
`HkCloudClip`（`HkCloudClip.java:1-323`）的取法逐路不同，见 §3 对照表。
本笔按星辰原文重做分发表；旧类本笔不动（6b 删）。

### 2.2 `HkCloudClip`：星辰原文 + 两处适配

- 包名 `com.fongmi.android.tv` → `com.github.moyingshi.osc.tv`（含 import）。
- 星辰 `HkCloudClip.pastebinUpload` 用 `ThirdPartyKeys.PASTEBIN_DEV_KEY`；星辰公开源码里
  该文件是占位版（`api/ThirdPartyKeys.java:5-9`）——`PASTEBIN_DEV_KEY = ""`（真源由 CI
  从保密区注入，公开构建里就是空）。我方无 `ThirdPartyKeys` 类，且任务 scope 只到
  `api/hk/*`，不在 `api/` 新建密钥文件：本笔在 `HkCloudClip` 内放
  `private static final String PASTEBIN_DEV_KEY = ""` + 注释写明来源与后果（云1上传恒报
  `Bad API`、不可用，与星辰公开构建同态）。oktv 旁证：反编译 `fs.java:528` 的 pastebin
  上传同样传空 `api_dev_key`。**这不是我方密钥，是空占位；不复制任何真实密钥。**
- 其余一字不改：自建 `OkHttpClient`（15s/30s）、UA、Referer/Origin 头、pasteme
  `data.path` 成功判据、hastebin 双 base 回退，均照星辰原文。
- 星辰 `HkCloudClip.upload()` 云2/云5 两支抛 `IllegalArgumentException` 指路
  `HkRuleManager.exportAsCloudCode`/`exportAsCmdImCode`（本笔同补），照搬不改。
- 星辰内部不一致，记名不改：`HkCloudClip.download(code, 2)` 走 netcut
  （`netcut.cn/api/note2/info/`），而 `HkRuleManager.fetchCloudContent` 的云2
  `/apidb/` 走 textdb、`/p/` 判 netcut 已死。导入主路是 `parseByCloudCode`
  （textdb），`HkCloudClip.download` 只在 `HkShareHelper.downloadFromCloudClip`
  直调时才走得到（6b 才搬，无当前调用方）。不替星辰“修”这处分歧。

### 2.3 `HkRuleManager` 云区：星辰 `HkRuleManager.java:1166-1888` 全段

- 插入点：文件内「阶段 6」标记处（`importJsRule` 之后、`getRules` 之前），标记注释随本笔删除。
- 搬入清单：`CLOUD_CODE_PATTERN`（`:69-70`，附分发表注释 `:57-68`）、`normalizeCloudCode`（`:82-88`）、
  五个端点常量 `TEXTDB_API_UPDATE`/`TEXTDB_GET`/`PASTEME_API`/`PASTEME_REFERER`/
  `CMDIM_BASE`（`:89-93`）、`parseByCloudCode` 两形（`:1178-1211`，含口令后空格段当密码
  的习惯）、`PasteRawStash` + `pasteRawStash` ThreadLocal（`:1213-1236`）、
  `fetchPasteRawText`（`:1238-1350`）、`importByCloudCode`（`:1353-1356`）、
  `fetchPastemeData`（`:1359-1370`）、`fetchCloudJson`（`:1373-1383`）、`CloudContent`
  （`:1385-1395`）、`fetchCloudContent`（`:1397-1565`）、`fetchNoteMsRaw`（`:1567-1578`）、
  `extractTextarea`（`:1580-1595`）、`extractRuleJsonFromPasteme`（`:1597-1600`）、
  `extractPastemeData`（`:1602-1613`）、`extractRuleJsonFromCmdIm`（`:1615-1618`）、
  `extractCmdImTextBox`（`:1620-1633`）、`unwrapShareText`（`:1646-1668`）、
  `unwrapHomeRuleCommand`（`:1670-1693`）、`isShareCommandText`（`:1695-1697`）、
  `buildShareCommand`（`:1702-1705`）、`safeBase64Decode`（`:1707-1711`）、
  `decodeBase64Loose`（`:1713-1732`）、`exportAsCloudCode`（`:1745-1782`）、
  `exportAsCmdImCode`（`:1784-1812`）、`exportAsFile`（`:1814-1826`）、`exportAsHkZip`/
  `exportAsZip`/`exportRulePackage`/`writeZipEntry`（`:1828-1882`）。
- 不搬：星辰 `isCloudCode`（`:1166-1169`，我方 3e 已搬入，`HkRuleManager.java:1027`）；
  星辰 `get()` 里的 `HkMediaCacheCleaner.scheduleClean`（不在云区，我方 `get()` 不动）。
- 存储适配：零 LitePal。星辰云区本就走文件（`getDir()` + `safeFileName(title) + ".json"`），
  与我方 `saveRule`/`loadRuleJson` 同形——`exportAsCloudCode`/`exportAsCmdImCode`/
  `exportAsFile`/`exportRulePackage` 的直接读文件照搬；`importByCloudCode` 走我方
  `saveRule`（同标题单文件 + `deleteSameTitleFiles` 去重，见 §4 去重核对）。
- `exportRulePackage` 用的 `com.example.hikerview.ui.rules.service.require.RequireUtils.
  getRequireItems` 与 `RequireItem.getFile()` 在我方树内已存在
  （`app/src/main/java/com/example/hikerview/ui/rules/service/require/RequireUtils.java:238`、
  `.../model/RequireItem.java:43`），照搬可用；`getRequireFilesDir` 我方已有
  （`HkRuleManager.java:728`）。
- 新增 import：`android.util.Base64`、`okhttp3.*`（`FormBody`/`OkHttpClient`/`Request`/
  `Response`）、`java.io.IOException`、`java.util.concurrent.TimeUnit`、
  `java.util.zip.ZipOutputStream`。我方已有 `Files`/`StandardCharsets`/`JsonParser`/
  `Pattern`/`ZipEntry`/`ZipFile` 等，不重复引。
- `HkHttp.client()` 我方已有（`HkHttp.java:856`，阶段 1 的 hk 专用 client）；
  取件派生 `.newBuilder().callTimeout(30, TimeUnit.SECONDS)` 照星辰原文，本体不动。
- `parseJsonList` 分发：我方 `parseJsonList`（`HkRuleManager.java:1039`）的阶段 6 注释处，
  按星辰 `:1040-1062` 补 dispatch 头（非 `{`/`[` 开头才分发：先 `isCloudCode` →
  `parseByCloudCode`，再 `unwrapShareText` 剥壳后重判；规则原文/数组不受影响）。
  此后 `HkStore.importJsonText`（粘贴导入）自动吃云口令与 ￥ 包装文本。

### 2.4 `HkStore.java`：只改一条过时注释

- `importJsonText` 的注释（`HkStore.java:552-556`）写“云口令文本不走这里（`HkCloudImport`
  先取回 JSON 再调本方法）；2b 起 `importJsonList` 不再识别云口令/分享包装（那是阶段 6）”——
  6a 后 `parseJsonList` 已识别，注释改写为 6a 口径。零代码改动。

### 2.5 云口令安全属性（写清，不弱化）

- 云口令是公开短链、无加密：导出即公开，任何人拿到口令都能取回同一份规则；不要用云口令
  传带账号密码的规则。管理页对话框与分享文本首行已有同款提醒（`hk_miniapp_export_share_note`），
  本笔不改文案，只在本文档与代码注释里保留该属性。

## 3. 云口令分发表核对（星辰原文落点）

| 云号 | 取回（import，`HkRuleManager.fetchCloudContent`） | 上传（export） | oktv 交叉验证 |
|---|---|---|---|
| 云1 | `GET https://pastebin.com/raw/{id}`；口令形 `云1oooole/raw/{pasteId}` 或 `云1oooole/{id}`（星辰 RM:~1470） | `POST pastebin.com/api/api_post.php`，`api_dev_key=""` 占位 → 公开构建不可用（星辰 `HkCloudClip` pastebinUpload） | `fs.java:528` 上传同款空 key |
| 云2 | path `/apidb/{key}` → `GET https://textdb.online/{key}`，正文 base64 解开再 `unwrapShareText`（星辰 RM:~1410） | `POST https://api.textdb.online/update/`（key=`xc<秒>_<hash>`，value=base64）→ `云2oooole/apidb/{key}`（星辰 RM `exportAsCloudCode`） | `tz1.java:942` 上传、`tz1.java:950` 口令形；与旧 `HkCloudExport.uploadTextDb` 同形 |
| 云2 `/p/` | netcut：官方已下线接口，通道失效，给明确提示（星辰 RM:~1556） | —（`HkCloudClip.upload` 云2 抛错指路 `exportAsCloudCode`） | netcut 已死（任务简报已确认） |
| 云3/4/8 | 不支持：落到底部 `throw new IllegalArgumentException("暂不支持该云口令通道…")`（星辰 RM:~1565） | 不支持（`HkCloudClip.upload/download` default 抛错） | 8.83 无实现（计划 §1.4） |
| 云5 | `GET https://cmd.im/{id}`，取 `class="test_box"` div、去标签反转义（星辰 RM:~1440） | `POST https://cmd.im/`（`txt`=规则 JSON，`followRedirects=false`），取 `Location` 尾段 → `云5oooole/{id}`（星辰 RM `exportAsCmdImCode`） | `tz1.java:978` 上传、`tz1.java:988` 口令形；旧 `HkCloudImport.fetchCmdIm` 同款取法 |
| 云6 | path `/xxxxxx/{rest}` → `GET https://pasteme.tyrantg.com/api/getContent/{rest}`，取信封 `data` 字段；设密码时按 `(@密码)` 后缀再取一次（星辰 RM:~1430） | `POST https://pasteme.tyrantg.com/api/create`，成功判据只认 `data.path` → `云6oooole/xxxxxx/{path}`（星辰 `HkCloudClip` pastemeUpload） | `tz1.java:1226` 取回、`fs.java:545-546` 上传；旧的全 pasteme 路由只对云6是对的 |
| 云7 | `GET https://note.ms/{id}`：先浏览器 UA 取页抽 `textarea`，再换非浏览器 UA 取正文直剥（星辰 RM:~1490，`fetchNoteMsRaw`） | `POST https://note.ms/api/notes`（note=`hk<毫秒%1e6>`）→ `https://note.ms/{note}`（星辰 `HkCloudClip` notemsUpload） | `fs.java:563-569` 上传 |
| 云9 | `GET https://txtpad.cn/{id}`，正文直剥（星辰 RM:~1520） | `POST https://a6.qikekeji.com/txt/data/save/`，取 `id`/`key`/`txt_id` → `https://txtpad.cn/{id}`（星辰 `HkCloudClip` txtpadUpload） | `fs.java:586` 上传；取回侧星辰注“站点已停服（2026-11-12 实探回「服务已停止」页）”，`fetchPasteRawText` 云9 不给原文（见 §2.2 末） |
| 云10 | `GET https://hassdtebin.com/raw/{key}`，失败回退 `https://hastebin.com/raw/{key}`；口令形 `云10oooole/share/a/b/{key}` 取末段（星辰 RM:~1540） | `POST https://hastebin.com/documents`，取 `key` → `https://hastebin.com/{key}`（星辰 `HkCloudClip` hastebinUpload） | `fs.java:576-579` 上传 |

`fetchPasteRawText`（`parsePaste` 回退用）的原文分支：云5→test_box 原文；云6→pasteme
`data` 原文；云2 `/apidb/`→base64 解开原文；云7→note.ms textarea/正文原文；云10→`/raw/`
正文（双 base）；**云1 不给原文**（8.83 `PastebinImporter.canUseSync=false`）、**云9
不给原文**（官 parseSync 是 WebView 取 textarea 值，与 GET 不等价且站点已停服）——两路回
`null`，调用方回退口令原文。以上均照星辰 `fetchPasteRawText`（`:1238-1350`）原文。

## 4. 验收证据（代码级，不等构建）

- [x] `HkCloudClip` 在位（332 行，星辰原文 323 行 + 类头移植注 9 行；包名已改写；
      `PASTEBIN_DEV_KEY=""` 占位 + 注释）。与星辰原文 diff（仅包名/密钥引用归一化）只剩
      类头注、`PASTEBIN_DEV_KEY` 常量与一处引用行三处差。
- [x] `HkRuleManager` 云区在位：`importByCloudCode`/`fetchPasteRawText`/`parseByCloudCode`/
      `unwrapShareText`/`unwrapHomeRuleCommand`/`buildShareCommand`/`exportAsCloudCode`/
      `exportAsCmdImCode`/`exportAsFile`/`exportAsHkZip`/`exportAsZip`（24 个方法签名全数
      grep 在位，见 finish 证据）；`HkJsRuntime.parsePaste`（`:4480/4487`）的两处调用闭合
      （方法存在、签名一致：`importByCloudCode(String) throws Exception → HkRule`、
      `fetchPasteRawText(String) → String` 可空，调用方已判空）。
- [x] 分发表代码级核对：§3 表 10 个云号逐个对照星辰原文落点；旧的全 pasteme 路由本笔未动
      （旧类仍在，6b 删——新路零引用 `HkCloudImport`，grep 为零）。
- [x] 往返去重（代码级）：`importByCloudCode` → `saveRule`（`HkRuleManager.java:532`，
      同标题单文件 + `deleteSameTitleFiles` 清旧副本）；站源登记在 6b 的 `HkStore` 层走
      `LocalSource.mergeSites`（`LocalSource.java:211`，同 key 原位更新、`added` 只计新 key），
      故导出→导入同一规则不产生重复站点条目（6b 落管理页后复核一次）。
- [x] `git diff --check` 通过；全树 grep 无 `com.fongmi.android.tv`（跟踪 diff 内零命中；
      唯一一处是 `HkCloudClip.java:31` 类头注释里的星辰源路径，属允许例外）、无 LitePal、
      无 dex 动态加载；0 leanback 文件。
- [x] 改动文件：`HkRuleManager.java` +770/−6、`HkStore.java` +3/−3（仅注释）、
      新建 `HkCloudClip.java` 332 行、本文档 189 行；`HkRuleManager.java` 现 2093 行。
- [x] 逐行重读：云区系星辰原文机械抽取（脚本断言行号边界 + 无 `fongmi`/`LitePal`），
      另补漏的星辰 `:69-70` 图案、`:82-88` 归一、`:89-93` 端点常量（抽取时漏在块外，
      已补并复核）；javac 坑 grep：无 `isString()` 误用、无 `safeListElement` 误赋、
      无匿名 LinkedHashMap 裸 Entry；括号计数差为字符串/注释字面量噪声（`// 1)`/`// 2)`、
      `"{"` 字面量），方法结构逐签名复核无误。

## 5. 实现步骤

1. 开 guard 会话 6a（scope 见首部允许路径；新文件 `HkCloudClip.java` 先建再 `--adopt-dirty`
   或按 guard 提示处理）。
2. 新建 `HkCloudClip.java`（星辰原文 + §2.2 两处适配），逐行重读。
3. `HkRuleManager.java`：补 imports；标记处插入云区（§2.3 清单）；`parseJsonList` 补分发头。
4. `HkStore.java`：只改 `importJsonText` 处过时注释。
5. 验收（§4）：grep 对照 + `git diff --check` + 行数统计；逐行重读改动（注意 javac 坑：
   `Json.safeListElement` 返回 List 不能赋给 JsonArray；泛型适配器不用原始类型；lambda
   捕获须 effectively final；匿名 LinkedHashMap 子类里裸 Entry 写全限定名；
   `JsonElement` 无 `isString()`，用 `getAsJsonPrimitive().isString()`）。
6. `task_guard.sh finish` 提交并打 recovery tag。

## 6. 遗留（不归 6a）

- 6b：`HkShareHelper`/`HkPublishHelper`/`HkQrUtil`/`HkAccountPwd`；删 `HkCloudImport`/
  `HkCloudExport`/`HkImportPicker` 旧实现；管理页改走 `HkRuleManager` + 新助手（五路入口
  行为保持；云2/云5 重点核对；导出→导入往返复核）。
- 6c：`HkAuthDialog`/`HkAuthManager` 评估后搬 + `openAppIntent`/`publishRule` 接线闭合；
  `HkHikerRuleUtil` 异步版 `getRulesByHiker(String, OnCodeGetListener)` 恢复（星辰
  `util/HkHikerRuleUtil.java:267`；我方 `HkCodeUtil.java:311` 已记名只搬了同步版，
  调用点 `HkCodeUtil.dealFileByHiker` 待改）；ConfirmHandler 与 `HkAuthManager` 无关
  （星辰 `HkAuthManager` 全文件零引用 ConfirmHandler），仍留阶段 7。
- 阶段 7：`HkPageActivity`、剩余 event、首页订阅定去留；合集浏览面
  （`HkPageActivity.openCollectionBrowser`，`HkShareHelper.importFromFile` 的合集支引用）。
- 云1上传：无真实 pastebin dev key，公开构建恒不可用（与星辰公开构建同态）；云9取回侧
  站点已停服（星辰原文注），`fetchPasteRawText` 云9 不给原文。
