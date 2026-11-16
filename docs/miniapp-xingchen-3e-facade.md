# 移植阶段 3e：门面 + 小类补齐 + HkExecutor 接线（阶段 3 收尾）

> Recovery anchor（任务：miniapp-xingchen-3e；lane：standard；分支 main；HEAD 0f6c41a3）
> 目标：3e-1 先搬 HkJsRuntime 仍引用的小类/小模型（事件 POJO、HkCryptoFileUtil、
> HkItem/SearchResult、RequireUtils、HkHikerRuleUtil、HkRuleManager.isCloudCode）；
> 3e-2 再把星辰 HkEngine 门面改写为我们的新 HkEngine、HkExecutor 接线、DoH 接线行、
> post0/fetchWithHeadersInterceptor；本地提交 + recovery tag，不推不编。做完即停，不开阶段 4。
> 范围：只改 hk 相关文件 + 本文件。3e-1 与 3e-2 各一个 guard 会话 + 一笔原子提交。
> 状态：3e-1 已完成，待 guard finish 提交；3e-2 待做（见 §6 stub）。

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

### 4.2 仍未解（★ 统一构建前必须先补齐；3e-2 或阶段 4/5/6/7 补）

| 类/方法 | 归属 | 说明 |
|---|---|---|
| ★ `HkSelector` | 阶段 4 | 3b §8.2 ★；`sel`/`newSelector` 方法级引用 10 处同属阶段 4 |
| ★ `HkHttpParser` | 阶段 4 | 3b §8.2 ★（getParam lambda + handlePageRequest 二参版） |
| ★ `HkFindDelivery` | 阶段 5 | 3b §8.2 ★；全文件现 10 处（含 3d 新增 parseListInternal/parseSearch 各 1 处） |
| ★ `HkSearchEngine` | 阶段 5 | 3c §7.2 ★；getMyRule 内 6 处（instanceof + 强转 + 三元） |
| `HkRuleManager.importByCloudCode` / `fetchPasteRawText` | 阶段 6 | parsePaste 调；isCloudCode 已补 |
| `HkMediaCacheCleaner` | 按需（已中和） | 3c §6.4：downloadToFile 内调用已改为空操作，无代码引用；要恢复清理语义时再搬 |
| `event/home/ToastEvent` | 阶段 7 event/* 按需 | HkJsRuntime 内仅注释提及（:3224/:3431/:9504），无代码引用 |
| 其余 event/*（home 3 个 + rule 3 个 + video 1 个 + web 8 个） | 阶段 7 event/* 按需 | HkJsRuntime/HkEngine 零引用（代码与注释皆无），见 §2.1 |
| HkHikerRuleUtil 异步版 + historyJson 一族 | 阶段 5/6/7 | 见 §2.4（HkCodeUtil、HkDetailBridge.isHkKey、HkParserKit.getHomeUrl） |

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

## 6. 3e-2：门面 + HkExecutor 接线（待补）

> 本节在 3e-2 提交时填写：星辰 HkEngine 门面改写（SourceGate/HkEngineManager/
> HkSelector.isJsRule/常驻路径取舍）、旧 HkEngine API 调用点盘点（shim/重接线
> 逐项表）、HkExecutor 接线、DoH 接线行、post0/fetchWithHeadersInterceptor、
> 阶段 3 结束时的完整前向引用清单（已补 / 阶段 4 / 阶段 5 / 阶段 6 / 阶段 7）。

## 7. 遗留（未解决，3e-1）

1. 中间态不可编译：§4.2 的 ★ 清单（HkSelector、HkHttpParser、HkFindDelivery、
   HkSearchEngine）+ `sel`/`newSelector` + 云口令两法，统一构建前必须先补齐；
   3e-2 门面接线时 `getSelector()` 与选择器分支先留空位（阶段 4）。
2. `HkHttp.java:847–850` 的 client() 注释仍过时（称 CookieJar/ContentType
   “尚未接进任何 client”），随 3e-2 的 HkHttp.java 改动时更新（3b 遗留未动）。
3. DoH 调用行仍未接：`HkHttp.applyDoh` 内传 `HK_AUTO_DNS` 的一行，留待 3e-2。
4. `post0` 与 `fetchWithHeadersInterceptor` 未搬（星辰里零调用方），留待 3e-2。
5. `SecurityController` import 在 HkJsRuntime 输出中暂无代码引用（仅 import），
   javac 只告警不报错；3d 的 JavaAdapter 落定后仍未用它，留待 3e-2/阶段 4 再看
   是否保留（3b 遗留未动）。
