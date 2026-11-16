# 移植阶段 3e：门面 + 小类补齐 + HkExecutor 接线（阶段 3 收尾）

> Recovery anchor（任务：miniapp-xingchen-3e；lane：standard；分支 main；HEAD 0f6c41a3）
> 目标：3e-1 先搬 HkJsRuntime 仍引用的小类/小模型（事件 POJO、HkCryptoFileUtil、
> HkItem/SearchResult、RequireUtils、HkHikerRuleUtil、HkRuleManager.isCloudCode）；
> 3e-2 再把星辰 HkEngine 门面改写为我们的新 HkEngine、HkExecutor 接线、DoH 接线行、
> post0/fetchWithHeadersInterceptor；本地提交 + recovery tag，不推不编。做完即停，不开阶段 4。
> 范围：只改 hk 相关文件 + 本文件。3e-1 与 3e-2 各一个 guard 会话 + 一笔原子提交。
> 状态：3e-1 已提交 fcbe80c6（tag recovery/miniapp-xingchen-3e-classes/20261116140747-fcbe80c6e5c4）；
> 3e-2 已完成（门面 + 适配段 + DoH + post0/fetchWithHeadersInterceptor），待 guard finish 提交。
> 做完即停，不开阶段 4。

## 1. 来源与方法

- 星辰原文：`~/workspace/xingchen-ref`（HEAD 52450c0），包名
  `com.fongmi.android.tv` → `com.github.moyingshi.osc.tv`（RequireUtils 及其
  model 类保持原包名 `com.example.hikerview.*`，见 §2.3）。
- 移植方式：机械抽取脚本 `/tmp/xc3e/port3e1.py`（包名改写 + 文件头插入 +
  §3 所列适配；不随仓库走）+ 独立验证脚本 `/tmp/xc3e/verify3e1.py`。
  逐段对照原则：输出 = 原文原样 + §3 所列适配，除此之外零差异（自校验逐段
  重算比对，见 §5）。每个新文件在 package 行后加一行文件头移植说明（3a/3b 同款）。

## 2. 3e-1 搬入清单（按星辰行号/行数）

### 2.1 事件 POJO：20 个（只搬 HkJsRuntime 代码引用的那些）

核对方法：对 3b §8.2 的 17 个 + 3d 的 OnRefreshPageEvent 逐个 grep
我方 `HkJsRuntime.java` 的代码行（注释提及不算）。结果：3b 少算了 2 个——
`RuleModifiedEvent`（代码引用在 `postRuleModifiedEvent`，:7262）与
`OnReversePageEvent`（代码引用在 `setPageReverseDo`，:5832），两者都在 3b
已搬的段内。20 个全搬，星辰行数 = 我方行数 − 1（文件头）。

| 类 | 星辰行 | 我方落点 |
|---|---|---|
| event/AuthBridgeEvent | 107 | api/hk/event/AuthBridgeEvent.java |
| event/AuthStorageItem | 43 | api/hk/event/AuthStorageItem.java |
| event/OnBackEvent | 24 | api/hk/event/OnBackEvent.java |
| event/home/OnRefreshPageEvent | 32 | api/hk/event/home/OnRefreshPageEvent.java |
| event/home/OnReversePageEvent | 22 | api/hk/event/home/OnReversePageEvent.java |
| event/home/SetPageTitleEvent | 22 | api/hk/event/home/SetPageTitleEvent.java |
| event/home/SetPageParamsEvent | 22 | api/hk/event/home/SetPageParamsEvent.java |
| event/home/SetPagePicEvent | 22 | api/hk/event/home/SetPagePicEvent.java |
| event/home/SetPageLastChapterRuleEvent | 22 | api/hk/event/home/SetPageLastChapterRuleEvent.java |
| event/rule/CacheCode | 24 | api/hk/event/rule/CacheCode.java |
| event/rule/ClsItemsFindEvent | 27 | api/hk/event/rule/ClsItemsFindEvent.java |
| event/rule/ItemFindEvent | 25 | api/hk/event/rule/ItemFindEvent.java |
| event/rule/ItemModifyEvent | 101 | api/hk/event/rule/ItemModifyEvent.java |
| event/rule/RefreshReadDataEvent | 50 | api/hk/event/rule/RefreshReadDataEvent.java |
| event/rule/RefreshVideoUrlEvent | 28 | api/hk/event/rule/RefreshVideoUrlEvent.java |
| event/rule/RefreshWebViewEvent | 19 | api/hk/event/rule/RefreshWebViewEvent.java |
| event/rule/OnRefreshX5HeightEvent | 25 | api/hk/event/rule/OnRefreshX5HeightEvent.java |
| event/rule/RuleModifiedEvent | 28 | api/hk/event/rule/RuleModifiedEvent.java |
| event/video/BackMainEvent | 11 | api/hk/event/video/BackMainEvent.java |
| event/video/PlayDataUpdateEvent | 35 | api/hk/event/video/PlayDataUpdateEvent.java |

- 依赖：`ItemFindEvent`/`ClsItemsFindEvent` import `HkItem`（本步同批搬），
  `RuleModifiedEvent` import `HkRule`（我方已有）；其余零依赖（纯 POJO）。
- **不搬**：`event/home/ToastEvent`（20 行）——HkJsRuntime 内只有 3 处注释提及
  （:3224/:3431/:9504），无代码引用，按任务书“多一个不要”不搬，留给阶段 7
  event/* 按需。其余 event/* 文件（OnPageChangeEvent、OnRefreshEvent、
  ShowFileChooserEvent、OnArticleListRuleChangedEvent、OnSubRefreshEvent、
  ShowSelectOptionsEvent、PlayChapterEvent、web/* 8 个）我方 HkJsRuntime/HkEngine
  零引用（代码与注释皆无），一律不搬，同样留给阶段 7。

### 2.2 小类：HkCryptoFileUtil、HkItem、SearchResult（原样搬）

| 类 | 星辰行 | 我方落点 | 备注 |
|---|---|---|---|
| HkCryptoFileUtil | 32 | api/hk/HkCryptoFileUtil.java | toBytes/toInputStream；`__cryptoFileUtil` 注入点在 3b 已搬 |
| HkItem | 237 | api/hk/HkItem.java | 字段与 HkCard 1:1 对应（title/url/pic/desc/sel/colType/extra/content/line/findRule/group/pages/preRule/rule），`fromSearchResult` 随同搬入 |
| SearchResult | 147 | api/hk/SearchResult.java | 12 字段 + 2 构造；BaseResultItem 的 extra 已内联（星辰原文即如此） |

### 2.3 RequireUtils（274 行）+ 两个 model 类（直接依赖，随同搬入）

| 类 | 星辰行 | 我方落点 |
|---|---|---|
| RequireUtils | 274 → 我方 352（含转写 helpers 77 行 + 文件头 1 行） | app/src/main/java/com/example/hikerview/ui/rules/service/require/RequireUtils.java（包名保持原样） |
| RequireDescription | 37 | app/src/main/java/com/example/hikerview/ui/rules/model/RequireDescription.java（包名保持原样） |
| RequireItem | 57 | app/src/main/java/com/example/hikerview/ui/rules/model/RequireItem.java（包名保持原样） |

- 3c 的三个委托（`getRequireVersion`/`updateDescription`/`generateRequireMap`）
  在我方 HkJsRuntime 内是全限定调用
  `com.example.hikerview.ui.rules.service.require.RequireUtils.*`，原包名落点即闭合。
- 适配（仅 3 处，见 §3）：App import 改我方包名；FileUtil 三法转写为本类私有方法；
  其余逐行与原文一致（verify 逐行核对，见 §5）。
- 两个 model 类不在任务书点名之列，但 RequireUtils 直接依赖它们（`getRequireItems`
  返回 `List<RequireItem>`，`getRequireVersion`/`updateDescription` 读写
  `RequireDescription`）；不搬则 RequireUtils 不可编译，故随同搬入，原文原样。

### 2.4 HkHikerRuleUtil（331 行 → 我方 172 行）：选项二变体

任务书给二选一：①只搬运行时调用的两个方法及其依赖、其余留给阶段 4；
②整文件搬但把碰 HkParserKit 的方法留空位。**选①为主、②为辅的混合**，理由：

- 运行时只调同步两法：`getAssetsFileByHiker`（hkGetAssetsFile 调）与
  `getRulesByHiker(String)`（hkGetRulesByHiker 调）。`getAssetsFileByHiker`
  只依赖 `loadAsset`（App assets），可全搬；`getRulesByHiker` 同步版的 home 支
  只依赖 `HkRuleManager.loadRuleJson/getRules`（我方 2b 已有）+ `shareSerializeRule`
  （纯 Gson）+ `allRulesJson`，可全搬。
- `getRulesByHiker` 的 collection/history 两支依赖阶段 4/5/7 的类，整方法删支
  会破坏分发结构、以后恢复要重拼，故保留方法与分支结构、两支先回空形（见下），
  其余（异步版、historyJson 一族）整段不搬：
  - collection 支：原文 `HkFavoriteStore.toJson()`；我方暂无 HkFavoriteStore
    （阶段 7），按本文件 bookmark/download 两支“无库恒回空”的同款口径先回 `"[]"`，
    阶段 7 搬 HkFavoriteStore 后恢复原文行。
  - history 支：原文 `historyJson()`；它依赖 `History.isHk` →
    `HkDetailBridge.isHkKey`（阶段 5）与 `HkParserKit.getHomeUrl`（阶段 4），
    且站 key 命名空间待定（星辰 `hk_` 前缀，我方 `hk://` scheme），本步不硬编
    谓词，先回 `"[]"`，阶段 5/7 把 historyJson 一族搬回后恢复原文行。
  - 未搬：异步版 `getRulesByHiker(String, HkCodeUtil.OnCodeGetListener)` +
    `HEAVY_TASK`（需 `util/HkCodeUtil.java`，另个 util 文件，不在 3e scope 内，
    留给阶段 6/7 随 HkCodeUtil 一并搬）；`HkViewHistoryRow` +
    `historyJson`/`toViewHistoryRow`/`extractPageUrl`（随 history 支，阶段 5/7）。
- 搬入的方法：`getAssetsFileByHiker`、`getRulesByHiker(String)`、
  `shareSerializeRule`、`allRulesJson`、`loadAsset`、`SHARE_EXCLUDED`；
  其余代码行与原文逐行一致（verify 核对，见 §5）。

### 2.5 HkRuleManager.isCloudCode（只搬判定，不搬分发）

- 搬入：`CLOUD_CODE_FIND` 模式（星辰 :75-76，
  `Pattern.compile("云([1-9]|10)oooole/?\\S+")`）+ `isCloudCode`
  （星辰 :1166-1169，null 守卫 + `find()`），落点在 `parseJsonList` 之前；
  加 `import java.util.regex.Pattern;`；`parseJsonList` 内的阶段 6 注释同步更新
  （isCloudCode 已搬入，parseByCloudCode/unwrapShareText 仍是阶段 6）。
- **不搬**：`CLOUD_CODE_PATTERN`（星辰 :69-70，`^云([1-9]|10)oooole(/.*)$`，
  只被 `parseByCloudCode` 用）与 `parseByCloudCode`（星辰 :1178 起，阶段 6）。
  3b §8.2 记的“云口令三法”现只剩 `importByCloudCode`/`fetchPasteRawText`
  待阶段 6（`isCloudCode` 已闭合）。

## 3. 3e-1 适配点（差异只剩这些）

1. **包名**：`com.fongmi.android.tv` → `com.github.moyingshi.osc.tv`
   （事件/HkCryptoFileUtil/HkItem/SearchResult/HkHikerRuleUtil 的 package 行与
   全限定引用；`com.fongmi.quickjs` 未出现，无须处理）。RequireUtils 及其
   model 类保持原包名 `com.example.hikerview.*`。
2. **RequireUtils 的 FileUtil 依赖**（星辰 `com.example.hikerview.utils.FileUtil`，
   大工具文件，不整文件搬）：它只用三法——`fileToString(String)`（3 处）、
   `stringToFile(String, String)`（2 处）、`copy(File, File)`（2 处）——按下行
   原文（星辰 FileUtil.java:105-130/131-145/258-270 含 `write` 助手 :60-65）
   逐字转写为 RequireUtils 的私有静态方法，调用点去掉 `FileUtil.` 前缀直调；
   App import 改我方包名。其余逐行与原文一致。
3. **HkHikerRuleUtil**：见 §2.4（两支空形 + 异步版/historyJson 一族未搬）。
4. **其余零差异**：事件 POJO、HkCryptoFileUtil、HkItem、SearchResult、
   RequireDescription、RequireItem 与星辰原文逐字节一致（除包名与文件头行）。
5. **未动**：HkJsRuntime、HkEngine、HkExecutor、HkRule、HkStore、TV/leanback、
   BaseLoader、底栏（仍 6 项）。未引入新依赖，未引入 LitePal、dex 动态加载、
   Clash、XPopup 相关代码（星辰原文 Javadoc 里提及 LitePal 的两处是注释文字，
   非代码引用：AuthBridgeEvent 类头注与 HkHikerRuleUtil 的 getRulesByHiker 说明，
   均原样保留）。

## 4. 前向引用清单（3e-1 之后；★ 为统一构建前必须解决的类级引用）

### 4.1 本步已补（HkJsRuntime 内的引用数，verify 脚本 grep 计数）

| 类 | HkJsRuntime 内提及数 | 说明 |
|---|---|---|
| HkItem | 54 | 含 parseList 全套返回类型 |
| SearchResult | 12 | 含 parseSearch 全套返回类型 |
| HkCryptoFileUtil | 3 | 含 `new HkCryptoFileUtil()`（evalImageJsInternal） |
| HkHikerRuleUtil | 6 | 含 hkGetAssetsFile/hkGetRulesByHiker 全限定调用 |
| RequireUtils | 11 | 含 getRequireVersion/updateDescription/generateRequireMap 委托 |
| 20 个事件 POJO | 1–20 不等（见 §5） | 全部代码引用已闭合 |
| HkRuleManager.isCloudCode | 1（parsePaste 调） | 方法已补；同处调的 importByCloudCode/fetchPasteRawText 仍待阶段 6 |

### 4.2 仍未解（★ 统一构建前必须先补齐；阶段 4/5/6/7 补）

| 类/方法 | 归属 | 说明 |
|---|---|---|
| ★ `HkSelector` | 阶段 4 | 3b §8.2 ★；`sel`/`newSelector` 方法级引用 10 处同属阶段 4；门面 home/search 的选择器分支与 `isJsRule` 本地小方法同阶段 4 恢复 |
| ★ `HkHttpParser` | 阶段 4 | 3b §8.2 ★（getParam lambda + handlePageRequest 二参版） |
| ★ `HkFindDelivery` | 阶段 5 | 3b §8.2 ★；全文件现 10 处（含 3d 新增 parseListInternal/parseSearch 各 1 处） |
| ★ `HkSearchEngine` | 阶段 5 | 3c §7.2 ★；getMyRule 内 6 处（instanceof + 强转 + 三元） |
| `HkRuleManager.importByCloudCode` / `fetchPasteRawText` | 阶段 6 | parsePaste 调；isCloudCode 已补 |
| `HkMediaCacheCleaner` | 按需（已中和） | 3c §6.4：downloadToFile 内调用已改为空操作，无代码引用；要恢复清理语义时再搬 |
| `event/home/ToastEvent` | 阶段 7 event/* 按需 | HkJsRuntime 内仅注释提及（:3224/:3431/:9504），无代码引用 |
| 其余 event/*（home 3 个 + rule 3 个 + video 1 个 + web 8 个） | 阶段 7 event/* 按需 | HkJsRuntime/HkEngine 零引用（代码与注释皆无），见 §2.1 |
| HkHikerRuleUtil 异步版 + historyJson 一族 | 阶段 5/6/7 | 见 §2.4（HkCodeUtil、HkDetailBridge.isHkKey、HkParserKit.getHomeUrl） |

### 4.3 3e-2 已补（阶段 3 结束时）

| 项 | 说明 |
|---|---|
| HkEngine 门面（home/search/getError/getRule/getJsRuntime/destroy + safe/classNameOf/prefetchBody/stripEmptyPrefix/getMarked/isJsRule） | 星辰 HkEngine.java 改写，适配只剩 §6.1 所列 6 点；暂无调用方，阶段 5 HkRouter 接 |
| 旧 HkEngine API 适配段（19 方法 + 无参构造，均 @Deprecated；resolveUa/HkException/DialogHost 原样保留未标） | 调用点零改动（HkExecutor/HkParser/HkSubpage/VideoActivity/HkDetailFragment）；逐项映射见 §6.2 |
| `HkJsRuntime.post0` / `fetchWithHeadersInterceptor` | 星辰 :13640–13681 逐字节搬入；星辰里零调用方 |
| `HkJsRuntime.putVarValue` | 本步新增 4 行（非星辰原文）：prepare 的 initialVars 的 Java 侧写口 |
| DoH：`HkHttp.applyDoh` 内 `HkHttpClient.setDns(HK_AUTO_DNS)` | 3e 接线；`HkHttp.java:847–850` 的 client() 过时注释同步更新 |

## 5. 3e-1 验收证据

- 脚本：`/tmp/xc3e/port3e1.py`（抽取 + 自校验）、`/tmp/xc3e/verify3e1.py`
  （独立验证）。脚本不随仓库走。
- 独立验证（verify3e1.py，**322 PASS，0 FAIL**）：25 个文件逐字节重算比对通过
  （20 事件 + HkCryptoFileUtil + HkItem + SearchResult + 2 model 类，与星辰
  原文逐字节一致，仅包名与文件头行不同）；RequireUtils 星辰原文每行（除
  App/FileUtil 两 import 行外）均在输出中出现，7 处 FileUtil 调用已改直调，
  4 个转写 helper 齐全；HkHikerRuleUtil 保留方法齐全、两支空形在位、异步版与
  historyJson 一族已删、保留代码行与星辰原文逐行一致（0 行差异）；
  HkRuleManager 模式 + 方法在位、`parseByCloudCode` 方法未搬；
  禁引核查通过（代码行零 `com.fongmi.android.tv` / `SourceGate` /
  `GlobalProxy` / `ToastUtil` / `LitePal` / `DexClassLoader` / `XPopup` /
  `Clash`；LitePal 仅存于两处星辰原文 Javadoc 注释文字，见 §3.5）；
  全部新文件大括号平衡；HkRuleManager 大括号 naive 深度与 HEAD 一致（2，
  原文件字符串字面量里的花括号所致，本步改动中性）。
- `git diff --check` 通过；改动：新文件 29 个（20 事件 + 3 小类 + RequireUtils
  + 2 model + HkHikerRuleUtil + 本文件），HkRuleManager.java +17/−2。
- 逐行重读：已人工重读 HkHikerRuleUtil 输出全文（172 行）、RequireUtils 转写
  helpers 段（:284-351）、HkRuleManager diff 全文；确认差异只剩 §3 所列适配点。
- 未编译：本地无 JDK/Android SDK，编译靠最后统一构建验（任务书已明示）。
- HkJsRuntime 引用计数（verify 脚本 grep，代码 + 注释合计）：HkItem 54、
  SearchResult 12、HkCryptoFileUtil 3、HkHikerRuleUtil 6、RequireUtils 11；
  事件：ItemModifyEvent 20、AuthStorageItem 15、AuthBridgeEvent 10、
  ItemFindEvent 5、OnReversePageEvent 4、SetPagePicEvent 4、RuleModifiedEvent 4、
  PlayDataUpdateEvent 3、ClsItemsFindEvent 3、其余 1–2。

## 6. 3e-2：门面 + HkExecutor 接线（已完成）

### 6.1 门面改写（星辰 HkEngine.java 251 行 → 我方 HkEngine.java 门面部分）

| 星辰原文 | 我方处理 | 理由 |
|---|---|---|
| 构造器 :48-65（新建 + init + HkImage 登记 + newSelector） | 保留新建 + init + HkImage 登记三行；`selector = jsRuntime.newSelector()` 删掉 | HkSelector 是阶段 4 的类；我方运行时无 newSelector（3d 已跳过） |
| 私有常驻构造器 + `wrapResident`（修法 A） | 整段删掉；`adopted` 字段删掉；`destroy()` 简化为 `if (jsRuntime != null) jsRuntime.destroy()` | HkEngineManager 在星辰仓库里根本没有这个类；我方 HkExecutor 每次执行新建引擎，不必强行常驻 |
| `getMarked`（SourceGate.mark 前后恢复） | 方法名保留，体内改为直接 `HkHttp.get(url, rule.resolvedUa())` | 代理选择器链已在 HkHttpClient 里生效 |
| `HkSelector.isJsRule`（:87/:146） | 按星辰 HkSelector.java:42 原文写本地小方法 `isJsRule`（`rule != null && rule.trim().startsWith("js:")`），注释“阶段 4 用 HkSelector.isJsRule 替换” | 只是个小谓词，不为此提前搬整个 HkSelector |
| `rule.validate()`（星辰版 void） | `String verr = rule.validate(); if (verr != null) throw new IllegalStateException(verr);` | 我方 validate() 返回 String（null=通过）；效果等价（失败都抛，门面 catch 后回空表） |
| home/search 的非 js 选择器分支（`selector.parseList/parseSearch`） | 按原文结构留空位：回空列表 + `SpiderDebug.log` 注明阶段 4，不自造解析；`selector` 字段与 `getSelector()` 同阶段 4 恢复 | HkSelector 未搬 |
| `home`/`search` 其余行（setListContext、预抓 + noteFetchFailure、parseList 4 参、parseSearch 4 参、ListEvalSupersededException 收口、Logger + SpiderDebug 双通道） | 照原文搬 | 依赖全已在位（HkJsRuntime/HkHttp/HkRule/HkImage 的方法均已存在，见 §5） |
| `getError`/`getRule`/`getJsRuntime`/`safe`/`classNameOf`/`prefetchBody`/`stripEmptyPrefix` | 照原文搬（`getError` 加 jsRuntime null 守卫：适配段无参构造实例未 prepare 时回 ""） | — |

- 门面暂无调用方：全树 grep，`new HkEngine(rule)` 与 `engine.home()/search()` 零调用；
  阶段 5 的 HkRouter 接门面后才有调用方。3e-2 只建门面，不替阶段 5 接线。
- `SpiderDebug`（catvod，已有）、`Logger`（orhanobut 2.2.0，hk 包内已有使用）照原文用。

### 6.2 接线：旧 HkEngine API 调用点盘点（shim，未重接线到门面）

实测 grep：hk 包内除 HkJsRuntime/HkEngine 外约 83 处 + hk 包外 2 处（VideoActivity:3097、
HkDetailFragment:175，均为 DialogHost 匿名实现/取用），与协调方计数一致。
**逐个决定：全部走 shim，零重接线到门面 home()/search()**，理由（三条，任一即否决重接）：
① 门面不跑 preRule（青苹果♨️ 的 preRule 跑 initConfig+rc 会断）；② 选择器规则会落入
门面空位分支（奇优影院♔ 是 selector 规则）；③ URL 拼法不等价（HkExecutor.buildHomeRequest
的 classUrl ID 式/fyAll/POST 守卫/诊断、buildDetailRequest 的详情/播放链）与门面 expandUrl
不等价，且 HkParser 的 `.js:` 段仍需引擎。

| 旧 API（调用点） | 3e-2 实现 | 语义变化 |
|---|---|---|
| `prepare(5 参/6 参)`（HkExecutor 7 处 + HkSubpage 2 处） | 新建 `HkJsRuntime(rule)` + `init()` + `setEvalResCodeBody(resCode)` + `setListContext(pageType, "", "", rule.getParams(), "", "", "")` + HkImage 登记；initialVars 经新增的 `HkJsRuntime.putVarValue`（本步加的 4 行 Java 侧写口，直调 private putVar2Impl；公开 putVar(Object, Object) 桥方法走 dispatchApi，不适合 Java 侧直调）写进程级 varMap | 旧“每引擎清 vars”→星辰“进程级 varMap 一表”语义；prepare 前调 setDialogHost/setRefreshEventEnabled 的顺序不变 |
| `evalPreRule()` | `jsRuntime.parsePreRule(rule, true)`，失败原样 throw 包成 HkException | 与旧“失败抛异常”一致 |
| `evalFindRule(findRule)`（homeDetailed/search/evalDetailRule/expand） | `jsRuntime.parseList(findRule, pageUrl, page)`（3 参版）+ HkItem→HkCard 逐字段转换（toCards，字段 1:1；HkItem.getType 无对应字段，丢弃） | 新运行时只认 setResult 一族交付，直接 return 数组不再回落转换（旧 cardsFromJs 回落已删）；wasResultSet 以返回表非空计 |
| `evalTextRule(rule)`（lastChapter） | `evalJS(jsBody, resCode, false)`；优先级：strResult 变化 → 新交付条目首标题 → JS 直接返回值；"error:" 转 HkException | 见已知缺口③ |
| `evalExpression(js, input)`（HkParser.extract :221/:250、playResolved、evalLazy） | `setNextInputOverride(input)` + `evalLazy(js, pageUrl, -1)` | 鲜 scope 而非 prepared scope；vars 经 varMap 延续 |
| `eval(js, sourceName)`（HkSubpage.requireOne :270） | `evalJS(js, resCode, false)` 回 String；"error:" 转 HkException；snapshot 差值记 lastResultCards/resultSetFlag | 旧回 live JS 值 → 新回字符串；sourceName 入参现仅占位 |
| `wasResultSet()`（homeDetailed 诊断、requireOne） | `resultSetFlag`：evalFindRule 置返回表非空；eval 置 snapshot 差值非空 | 订正：3e 事前核对的“actionResultCount 前后差值”法不成立——deliverResult 有 findSink 时走 findDelivery.deliver，不写共享 results 表；parseList 路径的正确信号就是返回表本身（setResult 空数组不计） |
| `getResultCards()`（requireOne :274，包内可见） | 回 shim 暂存的上次转换结果；未交过为 null | 与旧签名一致（含包内可见性） |
| `getExports()`（requireOne :271） | 恒回 `Undefined.instance` | 新运行时每发鲜 scope，$.exports 不跨发留存；exports 优先分支在 3e 恒走不到，阶段 5/7 恢复 |
| `setImportParam(s)` / `setImportParams(query)`（expand :213、requireOne :265/:267） | 只存值 | 运行时无 Java 侧 importParam 设置口；子页面规则读 $.importParam 取不到值（阶段 5/7 恢复） |
| `jsValueFromCards(cards)`（requireOne :275） | 回 JSON 字符串（字段与旧版一致：title/url/img/pic/desc/col_type/extra） | 旧回 live JS 数组；父规则对 require 结果做 .filter/.map 会失效 |
| `wasRefreshRequested()` | `jsRuntime.isRefreshRequested()` | — |
| `setRefreshEventEnabled()` | 只存值 | 新运行时 refreshPage 无门控，恒投 OnRefreshPageEvent；当前我方 UI 无该事件订阅方（全树 grep 为零），无 live 行为变化；阶段 5 恢复门控时删除 |
| `setDialogHost/getDialogHost`（HkExecutor 7 处 + HkSubpage :261 + VideoActivity/HkDetailFragment） | 只存取 | 新运行时 confirm/select 走 authGate/AuthBridgeEvent 授权链，不 consult DialogHost；阶段 5/6 接管弹框时移除 |
| `getVarsSnapshot()` | 回空表 | vars 在进程级 varMap 共享，跨发无需快照携带；阶段 5 删除 |
| `resolveUa`（HkExecutor :184/:876/:984 三处） | 旧体原样保留（Pixel 8/Chrome 126 串） | 不得委托给 HkUaEnum（其 MOBILE 是 Android 15/Chrome 128 串，改了会悄悄换全部 hk 请求 UA） |
| `HkException` / `DialogHost` | 旧版原样保留（未标 @Deprecated：仍是跨类签名类型） | — |
| 旧 `require(url, params)`（包内私有） | 外部零调用方，适配段不含它 | HkSubpage.require（静态）走新引擎 requireOne，见上 |
| BOOTSTRAP/Bridge/HkContextFactory/CxHandle/vars·myVars·items 旧内务 | 整段删除 | HkJsRuntime 已接管 |

- 适配段全部 19 个实例方法 + 无参构造已标 `@Deprecated`；门面方法不标。
- 旧 HkEngine 2016 行 → 新 HkEngine 618 行（门面约 250 行 + 适配段约 360 行）。

### 6.3 DoH 接线 + post0/fetchWithHeadersInterceptor

- DoH：`HkHttp.applyDoh` 成功路径 `initAutoDns(context);` 之后、`return true;` 之前加
  `HkHttpClient.setDns(HK_AUTO_DNS);`（HK_AUTO_DNS 是 HkHttp.java:173 的 private static final Dns，
  同类内直接用；`HkHttpClient.setDns(Dns)` :126 已存在，其 Javadoc 早已写明“接线方（HkHttp.applyDoh / 3e）
  在 HkHttp.java 内传 HkHttp 的 HK_AUTO_DNS”）。HK_AUTO_DNS 自带回落：命中注册表的域名走注册的
  DoH / 静态 IP，其余走 Dns.SYSTEM，故无条件安装不改变未注册域名的行为。
- `HkHttp.java:847–850` 的 client() 过时注释已更新：CookieJar/ContentType 拦截器（3b 已接进
  HkHttpClient.client()）、HK_AUTO_DNS（3e 接线）、代理选择器链（HkHttpClient 内生效）均已注明；
  仍未搬的只剩信任所有证书 SSL、10s 超时、dnsEnd 诊断。
- `post0`（星辰 :13646–13662，含段头注释 :13640–13645）与 `fetchWithHeadersInterceptor`
  （星辰 :13670–13681，含段头 :13664–13669）：**原样搬入**（星辰 :13640–13681 逐字节一致），
  插在 HkJsRuntime 外层类右大括号之前、3d 追加块之后。依赖全已在位（`argsNativeObjectAdjust`、
  `isUndefined` :2456、`generateRequestOptions`/`generateHeadersOptions`/`fetch0`（3c）、
  `HkHttpHelper.generatePostOps`/`HeadersInterceptor`（3a））。星辰里零调用方，搬入只为
  补齐 3c 遗留的两个方法级引用。
- `HkJsRuntime.putVarValue`（本步新增 4 行，见 §6.2 prepare 行）：不是星辰原文，是 3e-2
  为 prepare 的 initialVars 加的 Java 侧写口。

### 6.4 3e-2 验收证据

- 脚本：`/tmp/xc3e/port3e2.py`（抽取 + 插入）、`/tmp/xc3e/verify3e2.py`（独立验证）。脚本不随仓库走。
- 独立验证（verify3e2.py，**125 PASS，0 FAIL**）：门面签名与星辰结构行齐全；
  代码行（注释与字符串字面量已剔除）零 `com.fongmi.android.tv` / `SourceGate` /
  `GlobalProxy` / `HkEngineManager` / `adopted` / `wrapResident` / `HkSelector` /
  `ToastUtil` / `LitePal` / `DexClassLoader` / `XPopup` / `Clash`；
  旧 API 签名 30 项齐全（含包内可见 getResultCards）；全部调用点（hk 包内 + hk 包外，
  `engine.*`/`caller.*`/`HkEngine.*` 全树 grep）均在新 HkEngine 内有定义；
  post0/fetchWithHeadersInterceptor 与星辰 :13640–13681 逐字节一致、依赖齐全；
  DoH 接线行在 initAutoDns 之后、return true 之前，过时注释已更新；
  三文件大括号平衡；`git diff --check` 通过。
- 逐行重读：新 HkEngine.java 全文 618 行已人工重读一遍；HkJsRuntime 追加段与
  HkHttp diff 已核对。重读中发现类注释称适配段“@Deprecated”而方法未标注，已补 19 处
  @Deprecated（复验仍 125 PASS）。
- 改动：HkEngine.java 2016 行 → 618 行（-1852/+486 行净差，含 HkHttp +13/-2、HkJsRuntime +48）；
  HkExecutor/HkParser/HkSubpage/VideoActivity/HkDetailFragment 零改动。
- 未编译：本地无 JDK/Android SDK，编译靠最后统一构建验（任务书已明示）。
  注意：本步之前 HkJsRuntime 已含 HkFindDelivery 等阶段 5 引用（中间态不可编译），
  3e-2 未改变这一状态，见 §4.2。

## 7. 遗留（未解决，阶段 3 结束时）

1. 中间态不可编译：§4.2 的 ★ 清单（HkSelector、HkHttpParser、HkFindDelivery、
   HkSearchEngine）+ `sel`/`newSelector` + 云口令两法，统一构建前必须先补齐
   （阶段 4/5/6）。3e-2 未改变这一状态。
2. 门面暂无调用方：`new HkEngine(rule)` 与 `engine.home()/search()` 全树零调用，
   阶段 5 的 HkRouter 接门面后才有调用方；当前首页/搜索/详情/播放仍走 HkExecutor +
   适配段（调用点零改动）。
3. 适配段已知缺口（见 §6.2 逐项表）：① evalTextRule 里纯字符串 `setResult("第…集")`
   会被新运行时静默丢掉（parseLastChapterRule 的 JSEngineCallback 是 HkJsRuntime
   私有接口，外部无法驱动；最坏只是 lastChapter 装饰文案缺失）；② `getExports()`
   恒回 Undefined（exports 优先分支恒走不到）；③ `setImportParam(s)` 只存值
   （子页面规则读不到 $.importParam）；④ `jsValueFromCards` 回 JSON 字符串而非
   live JS 数组（父规则 .filter/.map 会失效）；⑤ evalFindRule 不再回落转换直接
   return 的数组（只认 setResult 一族交付）；⑥ setResult 空数组不计入 wasResultSet。
4. `SecurityController` import 在 HkJsRuntime 输出中暂无代码引用（仅 import），
   javac 只告警不报错；3d 的 JavaAdapter 落定后仍未用它，留待阶段 4 再看
   是否保留（3b 遗留未动）。
5. 两处注释仍提已删的 `HkEngine.Bridge`（HkCard.java:13、HkStore.java:227），仅注释、
   不影响编译；不在 3e-2 scope 内未动，留待阶段 4/5 顺手清理。
