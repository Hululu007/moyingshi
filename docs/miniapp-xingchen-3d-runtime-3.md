# 移植阶段 3d：HkJsRuntime 解析回调与求值入口（第 3 部分）

> Recovery anchor（任务：miniapp-xingchen-3d-runtime-3；lane：standard；分支 main；HEAD edda5cac）
> 目标：往现有 `HkJsRuntime.java` 追加第 3 部分（解析回调 + 求值入口 + 图片/懒求值 + 定时器 +
> JavaAdapter + 文件/种子簇），恢复 refreshPage/goTo 注册，本地提交 + recovery tag，不推不编。
> 范围（仅 2 路径）：`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkJsRuntime.java`（追加 +
> 两处原位插入）、`docs/miniapp-xingchen-3d-runtime-3.md`（本文件）。
> 状态：已完成，待 guard finish 提交。验收：抽取脚本自校验 + 独立 verify 均通过（见 §8）。
> 未解决遗留（§9）：中间态不可编译（类级前向引用，3e/阶段 4–7 补）；`sel`/`newSelector` 留给阶段 4；
> DoH 的 `HkHttp.applyDoh` 调用行仍未接（留给 3e）；`post0` 与 `fetchWithHeadersInterceptor` 留给 3e。

## 1. 来源与方法

- 星辰原文：`~/workspace/xingchen-ref/app/src/main/java/com/fongmi/android/tv/api/hk/HkJsRuntime.java`，
  14836 行（HEAD 52450c0）。输出：`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkJsRuntime.java`，
  11589 行 → 13777 行（本步净增 2188 行，纯插入、零删除）。
- 移植方式：机械抽取脚本 `/tmp/xc3d/port3d.py`（span 表 + 大括号配对定段尾 + 附着注释块上卷 +
  文本适配；不随仓库走，见 `/tmp/xc3d/`）+ 独立验证脚本 `/tmp/xc3d/verify.py`。逐段对照原则：
  输出 = 原文 kept 段原样拼接 + §6 所列适配，除此之外零差异（自校验逐段重算比对，见 §8）。
- 本步三处改动位置：① 追加块插在 3c 内容之后、外层类右大括号之前（我方文件 11619–13776 行）；
  ② refreshPage/goTo 的 putFunction 注册插回 `registerApi` 原位（sleep 注册之前，我方文件 4854 行起）；
  ③ `@Parameter refreshPage` 插回 @Parameter 块原位（refreshReadData 之前，我方文件 1709 行起）。
  ① 之外两处是任务书点名的“注册”恢复，仍只改 `HkJsRuntime.java` 一个文件；3b/3c 内容一行未动
  （verify.py 重建比对通过，见 §8）。
- 3b/3c 的文档未改；与 3b 文档 §5.2 / 3c 文档 §3 的出入记在本文件 §3，以本文件为准。

## 2. 本步搬入的原文段（按星辰行号，81 段）

段首行含直接附着的注释块（Javadoc/段头/分簇注释，如 `// ---- D1-A: eval core ----`）；
方法按星辰原文顺序排列。`星辰行`为抽取段实际起止（含附着注释头）。

### 2.1 定时器五法

| 段 | 星辰行 | 内容 |
|---|---|---|
| jsSetTimer | 2394–2461 | setTimeout/setInterval 实现 |
| jsClearTimer | 2463–2485 | clearTimeout/clearInterval 实现 |
| fireTimerCallback | 2487–2517 | 定时器回调触发 |
| clearAllTimers | 2519–2546 | 清全部定时器（destroy/onDestroy 调） |
| clearTimersForRule | 2548–2588 | 按规则清定时器（onRuleDeleted 调） |

### 2.2 种子簇 + 文件簇

| 段 | 星辰行 | 内容 |
|---|---|---|
| HK_SEED_FILES 字段 | 9129–9131 | 种子表（1 行：Apollo 磁力君.简.js） |
| hkSeedAssetFor | 9133–9146 | 种子表命中才回 asset 路径 |
| hkSeedRestore | 9148–9152 | fetch 读路种子回补入口 |
| hkSeedRestorePath | 9154–9164 | 按绝对路径回补 |
| hkSeedWriteIfInvalid | 9166–9187 | 种子写盘（无效才写） |
| fileToString | 14462–14492 | fileToString(File, String, boolean) |
| getContentByCharsetDetect | 14494–14517 | 字符集探测读文件 |
| detectCharset | 14519–14541 | 探测字符集 |

### 2.3 解析回调全套

| 段 | 星辰行 | 内容 |
|---|---|---|
| parseList ×5 + parseListUnmarked + parseListInternal | 9511–9699 | 列表解析（9511/9518/9526/9537/9548/9682/9687 起） |
| parseSearch ×3 | 9701–9787 | 搜索解析（9701/9708/9781 起） |
| parseDetailRaw ×2 | 11422–11468 | 详情原始条目收集（11422/11462 起） |
| lastParseDetailScopeRule 字段 + getter + clear | 11407–11420 | 最近一发详情 scope 规则（字段 11407–11412、getter 11414–11416、clear 11418–11420） |
| parseHome | 13336–13366 | 首页解析入口 |
| parseLastChapterRule | 13368–13396 | 最新章节规则解析入口 |
| parsePreEngineRule | 13398–13419 | parsePreRule(SearchEngine)（更名版） |
| parsePreRule ×2 | 13421–13454 | parsePreRule(ArticleListRule) 两入口 |
| parseSearchRes | 13456–13466 | 搜索结果解析入口 |
| parseStr | 13468–13491 | 字符串解析入口 |
| parseSearchResInternal | 13493–13527 | 搜索结果解析本体 |

### 2.4 求值入口 + 图片/懒求值簇

| 段 | 星辰行 | 内容 |
|---|---|---|
| evalJS ×3 | 12396–12588 | （InputStream 版 12396–12486、String 2 参版 12488–12491、String 3 参版 12493–12588） |
| logEvalError | 12590–12606 | 求值失败落盘日志 |
| evalJSForObj | 12608–12671 | 求值取对象 |
| evalLazyRule | 12673–12682 | 懒规则求值入口 |
| imageEngine 静态字段 | 9845–9852 | 图片求值进程单例字段（W3 注释 + 字段） |
| imageEngine | 9850–9871 | 取图片求值单例（DCL） |
| initImageEngine | 9873–9902 | 图片单例轻量初始化 |
| evalImageJs ×2 | 9904–9946 | 图片 JS 求值（9904/9920 起） |
| evalImageJsInternal | 9948–10133 | 图片 JS 求值本体 |
| imageAssetRequirePatch | 10135–10170 | 图片 asset require 补丁串 |
| ensureDollarShim | 10172–10210 | 图片 $ 兜底 shim |
| wrapImageJs | 10212–10215 | 图片 JS 包裹 |
| getMyTitleJs | 10250–10274 | 图片求值补裸全局 MY_TITLE |
| getRealImageUrl | 10301–10318 | 图片 URL 归一化 |
| coerceImageStream | 10340–10399 | 求值结果 → 流 |
| evalLazy | 10401–10410 | 懒求值入口 |
| evalActionProbe | 10412–10423 | 动作探针 |
| evalRawInFreshScope | 10425–10471 | 鲜 scope 懒求值 |
| evalLazyInternal | 10473–10490 | 懒求值本体 |
| DryEvalResult 嵌套类 | 10492–10505 | 干跑求值结果类型 |
| evalLazyDry | 10507–10552 | 干跑求值 |
| evalLazy（HkRule 版） | 10554–10560 | 懒求值规则版入口 |

### 2.5 导航标记消费 + 其他

| 段 | 星辰行 | 内容 |
|---|---|---|
| jsErrorMsg | 10605–10623 | JS 异常消息取法 |
| consumeRefreshRequest | 10625–10633 | 取走 refreshPage 标记 |
| actionResultCount | 10635–10642 | 当前 results 数量快照 |
| snapshotActionResults | 10644–10650 | 当前 results 拷贝 |
| consumeGoToRequest | 10652–10661 | 取走 goTo 导航请求 |
| isRefreshRequested | 10663–10666 | refreshPage 标记当前值 |
| setNextInputOverride | 10668–10674 | 下一次 evalLazy 的 input 覆盖 |
| isRefreshToTop | 10676–10678 | 是否回顶 |
| loadConfig | 10908–10920 | 注入规则配置对象 config |
| loadConfigInto | 10922–10935 | config 注入本体 |
| armCallbackKey | 12267–12278 | 登记 fresh CALLBACK_KEY |
| callbackKeySink | 12280–12311 | 回调 key 汇点（含 ToastUtil 适配，见 §6） |
| hkFileToInputStream | 12349–12351 | 文件 → 流 |
| hkFileToBytes | 12353–12361 | 文件 → 字节 |
| hkGetAssetsFile | 12363–12376 | hiker://assets/ 取文件 |
| hkGetRulesByHiker | 12378–12393 | hiker:// 取规则 |

### 2.6 JavaAdapter

| 段 | 星辰行 | 内容 |
|---|---|---|
| HkJavaAdapterFn 嵌套类 | 13153–13225 | JavaAdapter 垫片（BaseFunction 子类；抽象类形走 HkJsAdapterBridges 桥） |
| JsProxyHandler 嵌套类 | 13227–13297 | JS 对象 → Java 接口代理 |
| installJavaAdapterShim | 13299–13305 | 给 scope 装 JavaAdapter 垫片 |

### 2.7 原位插入的两段（不在追加块内）

| 段 | 星辰行 | 插入位置 |
|---|---|---|
| refreshPage/goTo putFunction 注册 | 5483–5506 | `registerApi` 内 sleep 注册之前 |
| @Parameter refreshPage | 1764–1767 | @Parameter 块内 refreshReadData 之前（`@JSAnnotation(alias = "refresh")`） |

注：goTo 在星辰原文里没有 @Parameter 方法，只有 putFunction 注册，故只恢复注册。

## 3. 与 3b/3c 切分的出入（以本步实际为准）

1. **evalJS 是 3 个重载，不是 2 个**（3b §5.2 的注有误）：星辰原文有
   `evalJS(String, InputStream)`（12405）、`evalJS(String, String)`（12489，2 参委托 3 参）、
   `evalJS(String, String, boolean)`（12500）共 3 个，本步 3 个全搬。
2. **DryEvalResult 嵌套类随 evalLazyDry 一并搬入**（3b §5.2 未单列）：`public static final class
   DryEvalResult`（星辰 10493–10505），evalLazyDry 的返回类型，不搬则 evalLazyDry 不完整。
3. **imageEngine 静态字段随 imageEngine() 搬入**（3b 只列了 imageGlobalMap 字段）：
   `private static volatile HkJsRuntime imageEngine;`（星辰约 9849，W3 注释同段）。
4. **lastParseDetailScopeRule 字段随 getter/clear 搬入**（3b §5.2 只列方法名）：
   `private volatile HkRule lastParseDetailScopeRule;`（星辰 11412，Javadoc 同段）。
5. **HK_SEED_FILES 字段随种子簇搬入**：`private static final String[][] HK_SEED_FILES`
   （星辰 9129–9131，1 行种子）。
6. **newSelector/sel 跳过，留给阶段 4**（3b §5.2 列在 3d 名下，但其本体依赖阶段 4 类）：
   `newSelector`（星辰 10565–10569）返回 `HkSelector`，`sel`（10572–10574）调
   `HkSelector.decodeConflict`；按任务书“原文某方法依赖这些类，先跳过该方法”处理。
   3b 代码里已有的 10 处 `sel(` 调用仍是方法级前向引用，阶段 4 补 `sel` 后闭合；
   本步新增的 3d 块内无 `sel` 调用（verify 核查）。
7. **parseListInternal/parseSearch 的 HkFindDelivery.Sink 不跳方法**：
   两方法内各 1 处 `HkFindDelivery.Sink findSink = null;` 及后续 register/unregister/items/key
   调用。HkFindDelivery 是 3b §8.2 已登记的 ★ 类级前向引用（3b 代码已有 8 处），与 HkItem/
   SearchResult 同类；按“parseList/parseSearch 全套”的任务要求原样搬入，不按跳过处理。
   任务书的跳过清单（HkSelector/HkRouter/HkDetail/HkPlay/HkFindDelivery/HkSearchEngine/
   HkCloudClip）针对的是“方法主干依赖这些类”的情形，此处 Sink 只是交付汇点类型。
8. **HkJsAdapterBridges.tryCreate 引用可解**：HkJavaAdapterFn 内 1 处代码调用
   `HkJsAdapterBridges.tryCreate(...)`（星辰 :13210），我方 3a 已有同名类
   （`api/hk/HkJsAdapterBridges.java`，`tryCreate(Class, Scriptable, Object[], ThreadLocal)`
   静态方法在 :258），同包直引，无须适配。
9. **首版抽取脚本的字段重复追加 bug（已修）**：第一版 port3d.py 里两个 RANGES 字段段
   （HK_SEED_FILES、lastParseDetailScopeRule）在循环中既在锚点处插入、又作为尾段追加了一次，
   导致两字段各出现 2 次（编译会报重复字段）。发现后从 before-image 恢复、修脚本（锚点字段段
   不再参与尾段拼接）、重跑；verify.py 另加“每段在输出中恰好出现 1 次”的检查（count==1），
   重跑后通过。见 §8。

## 4. 3c 的 9 个方法级前向引用补齐情况（9/9，全部补齐）

| 方法 | 3c 调用方 | 本步定义位置（我方文件行） |
|---|---|---|
| getRealImageUrl | getHomeUrl、getImageDom | 3d 追加块（星辰 10302–10318） |
| fileToString | fetchByHiker file 分支 | 3d 追加块（星辰 14471–14492） |
| hkFileToInputStream | fetchByHiker file/inputStream 分支 | 3d 追加块（星辰 12349–12351） |
| hkFileToBytes | fetchByHiker file/toHex 分支 | 3d 追加块（星辰 12353–12361） |
| hkGetAssetsFile | fetchByHiker hiker://assets/ 分支 | 3d 追加块（星辰 12370–12376） |
| hkGetRulesByHiker | fetchByHiker hiker:// 分支 | 3d 追加块（星辰 12386–12393） |
| hkSeedRestore | fetchByHiker file 分支（种子回补） | 3d 追加块（星辰 9149–9152） |
| installJavaAdapterShim | runScript（3 参）、assembleFreshScope | 3d 追加块（星辰 13299–13305） |
| logEvalError | runScript（3 参） | 3d 追加块（星辰 12591–12606） |

grep 核对：9 个方法在我方文件内各有 1 处定义（verify.py 逐个断言通过）。

另：3b §8.1 留给 3d 的方法级前向引用，除 `sel` 外已全部闭合——jsSetTimer、jsClearTimer、
clearAllTimers、clearTimersForRule、evalJS（evalConfirmJs 调）、loadConfigInto、
installJavaAdapterShim 均已定义；fireTimerCallback、evalJSForObj、evalLazyRule、logEvalError
也已随本步提供。`sel` 仍待阶段 4。

## 5. 本步新增的 3d 块内字段核对

3d 方法用到的字段在我方文件内均已存在（3b 骨架或 3c 已有）：refreshRequested/refreshToTop/
lastRefreshPageAt/goToUrl、timerScheduler/jsTasks/jsTaskRules、findDelivery/activeFindSink/
findRegisteredKeys、boundResBody、activeListMark、LIST_MARK_KEY、results、nextInputOverride、
currentMyType、myParams、evalResCodeBody、resCodeMap/callbackMap、jsThread、rhinoCx、
imageGlobalMap、ambientEvalScope。`imageScope` 是 evalImageJsInternal 内的局部变量，不是字段。
本步新补 3 个字段：HK_SEED_FILES、imageEngine、lastParseDetailScopeRule（§2.2/§2.4/§2.3）。

## 6. 适配点（差异只剩这些）

1. **包名**：`com.fongmi.android.tv` → `com.github.moyingshi.osc.tv`，共 3 处——
   refreshPage 注册内的 `event.home.OnRefreshPageEvent`（星辰 :5493）、hkGetAssetsFile 内的
   `api.hk.util.HkHikerRuleUtil.getAssetsFileByHiker`（:12372）、hkGetRulesByHiker 内的
   `api.hk.util.HkHikerRuleUtil.getRulesByHiker`（:12388）。`com.fongmi.quickjs` 未出现，
   无须处理。
2. **ToastUtil → Notify**（1 处，3b 说的第三处 :12306）：callbackKeySink 内
   `ToastUtil.show(App.get(), err == null ? "" : err, android.widget.Toast.LENGTH_LONG)`
   改为 `Notify.show(err == null ? "" : err)`（`Notify` import 3b 已加；LONG 时长丢失，
   与 3b 的 SHORT 两处同口径）。
3. **SourceGate / GlobalProxy**：本步 81 段里出现 0 次，无须改。
4. **其余零差异**：`HkRouter` 在本步新增部分只出现在注释里（consumeRefreshRequest、
   lastParseDetailScopeRule 字段、refreshPage 注册的三处 Javadoc/行注释），非代码引用，未改；
   `HkEngineBridge`/`HkEngineManager`/`HkLazyRuleParser` 同样只在注释里出现；
   `HkJsProxy` 是 JsProxyHandler 内字符串字面量 `"HkJsProxy"`，不是类引用。
5. **未动**：HkHttpClient、HkHttp.applyDoh 的 DoH 行（留给 3e，见 §9）、HkEngine/HkExecutor/
   HkRule/HkRuleManager/HkStore、TV/leanback、BaseLoader、底栏（仍 6 项）。未引入新依赖，
   未引入 LitePal、dex 动态加载、Clash、XPopup 相关代码。

## 7. 前向引用清单（本步引用、别处才补；★ 为统一构建前必须解决的类级引用）

### 7.1 方法级（本步 3d 块内调用、星辰有、我方无的方法名：0 个）

verify.py 核查：3d 追加块内调用的、星辰定义而我方未定义的方法名共 0 个。
（其余调用名如 `__hkImageLog` / `__imageAssetRequire` / `__imageRequireAsset` /
`function` / `neval` 是 JS 字符串内的标识符，不是 Java 方法调用；`HkCryptoFileUtil` /
`HkItem` 是类名，不是方法。）

### 7.2 类级（★ 统一构建前必须先补齐；本步新增或新增引用的部分）

| 类 | 本步代码引用 | 归属 |
|---|---|---|
| ★ `event/home/OnRefreshPageEvent` | 1 处（refreshPage 注册内 `new ...OnRefreshPageEvent(refreshToTop)`） | 阶段 7 event/* 按需（3b §8.2 已预告“归 3d 的 refreshPage”） |
| ★ `HkFindDelivery` | +2 处（parseListInternal、parseSearch 各 1 处 `HkFindDelivery.Sink`；全文件现 10 处） | 阶段 5（3b §8.2 ★ 已登记） |
| ★ `HkItem` | parseList 全套返回类型 + snapshotActionResults（全文件引用数增加） | 阶段 5（3b §8.2 ★ 已登记） |
| ★ `SearchResult` | parseSearch 全套返回类型 | 阶段 5（3b §8.2 ★ 已登记） |
| ★ `HkHikerRuleUtil` | +2 处（hkGetAssetsFile、hkGetRulesByHiker 全限定调用；全文件现 3 处） | 待协调方裁定——3b 遗留未登记：3b 已在 `HkJsRuntime.java:7829` 调过一次，但 3b §8.2 未列出该类；本仓库无此文件（`api/hk/util/HkHikerRuleUtil.java` 待搬，或改走已有实现） |
| ★ `HkCryptoFileUtil` | +1 处（evalImageJsInternal 内 `new HkCryptoFileUtil()`；全文件现 2 处） | 3b §8.2 ★ 已登记（32 行小类） |
| `sel` / `newSelector`（方法，非类） | 3b 代码 10 处 `sel(` 调用仍无定义 | 阶段 4（本步跳过的 newSelector/sel，见 §3.6） |

- 3b §8.2 的 ★ 清单（HkItem、HkFindDelivery、SearchResult、17 个事件 POJO、
  HkCryptoFileUtil、HkSelector、HkHttpParser、HkRuleManager 云口令三法）仍未补，
  加 3c §7.2 的 RequireUtils、HkSearchEngine 与本节新增的 OnRefreshPageEvent、
  HkHikerRuleUtil，统一构建前必须先补齐；建议仍由 3e 一并搬入小 POJO/小类
  （协调方裁定；HkSelector/HkHttpParser 属阶段 4，HkItem/SearchResult/HkFindDelivery
  属阶段 5，事件 POJO 属阶段 7——若 3e 只搬门面，需另排一笔补这些类）。
- `HkJsAdapterBridges` 不在上表：我方 3a 已有该类，本步引用可解（见 §3.8）。

## 8. 验收证据

- 脚本：`/tmp/xc3d/port3d.py`（抽取 + 自校验）、`/tmp/xc3d/verify.py`（独立验证）、
  `/tmp/xc3d/port3d-manifest.json`（81 段段表 + 适配计数）、`/tmp/xc3d/methods.txt`（星辰方法清点）、
  `/tmp/xc3d/HkJsRuntime.before-3d.java`（3d 前原文件，供重建比对）。脚本不随仓库走。
- 自校验（port3d.py）：81 段抽取成功；适配计数：包名替换 3、ToastUtil→Notify 1、
  SourceGate/GlobalProxy 0；段内禁引断言通过（零 `com.fongmi.android.tv` /
  `ToastUtil` / `SourceGate` / `GlobalProxy` / `HkSelector` / `HkDetail` / `HkPlay` /
  `HkSearchEngine` / `HkCloudClip` / `DexClassLoader` / `LitePal` / `XPopup`；
  `HkFindDelivery` 仅 parseListInternal/parseSearch 两处，见 §3.7；
  `HkJsAdapterBridges` 仅 HkJavaAdapterFn 一处代码调用，见 §3.8）。
- 独立验证（verify.py，全 PASS，FAILURES: none）：81 段逐段重算比对全部通过
  （与星辰原文逐段一致，仅 §6 两处适配；且每段在输出中恰好出现 1 次）；
  全文件大括号平衡（终深度 0、最低 0）；追加块禁引核查通过；
  refreshPage/goTo 注册插在 sleep 之前、@Parameter refreshPage 插在 refreshReadData 之前；
  3c 的 9 个前向引用各有 1 处定义；重建比对通过（before-image + 3 处插入 == 新文件，
  3b/3c 内容未动）；3d 块内方法级前向引用 0 个。
- `git diff --check` 通过；改动：`HkJsRuntime.java` +2188 行（纯插入、零删除），
  3 个 diff hunk（@Parameter refreshPage +5、nav 注册 +25、追加块 +2158），本文件新建。
- 逐行重读：已人工重读三处插入点上下文（@Parameter refreshPage、registerApi 内
  refreshPage/goTo、追加块头尾）、callbackKeySink 的 Notify 行、三个新字段的落点；
  确认差异只剩 §6 所列适配点。字段重复追加 bug（§3.9）已修并重跑、重验。
- 未编译：本地无 JDK/Android SDK，编译靠最后统一构建验（任务书已明示）。
- 3d 链路（代码级）：`$.require` → require0（3c）→ hiker:///file:// 分支 fetchByHiker（3c）
  → 本步 6 个文件助手 + hkSeedRestore 回补；图片 `evalImageJs`（3d）→
  `imageEngine()` 单例 → `evalImageJsInternal`；列表/搜索/详情解析入口 parseList/
  parseSearch/parseDetailRaw（3d）→ assembleFreshScope（3b）→ runScript（3c）；
  定时器 setTimeout/setInterval 经 registerApi → jsSetTimer（3d）→ timerScheduler（3b 字段）；
  `new JavaAdapter` 经 installJavaAdapterShim（3d）→ HkJavaAdapterFn/JsProxyHandler（3d）。

## 9. 遗留（未解决）

1. 中间态不可编译：§7.2 的类级前向引用（OnRefreshPageEvent、HkFindDelivery、HkItem、
   SearchResult、HkHikerRuleUtil、HkCryptoFileUtil）+ 3b §8.2 / 3c §7.2 的 ★ 清单
   （17 个事件 POJO、HkSelector、HkHttpParser、RequireUtils、HkSearchEngine、
   HkRuleManager 云口令三法）+ 阶段 4 的 `sel`/`newSelector`，统一构建前必须先补齐；
   需协调方裁定由哪一步补（建议 3e 一并搬入小 POJO/小类，见 §7.2）。
2. DoH 调用行仍未接：`HkHttp.applyDoh` 内传 `HK_AUTO_DNS` 的一行（3b §7），
   HkHttp.java 不在本步 scope 内，留待 3e 门面接线。
3. `HkHttp.java:847–850` 的 client() 注释已过时（称 CookieJar/ContentType
   “尚未接进任何 client”），随 HkHttp.java 下一次改动时更新（3b 遗留未动）。
4. `post0` 与 `fetchWithHeadersInterceptor` 未搬（星辰里零调用方，3c §3.2 已定，
   留给 3e；本步核查：`post0` 在我方文件内 6 处全是 3b/3c 注释提及，
   `fetchWithHeadersInterceptor` 0 处）。
5. `SecurityController` import 在输出中暂无代码引用（仅 import），javac 只告警不报错；
   3d 的 JavaAdapter 仍未用它（JsProxyHandler 用 ContextFactory），留待 3e/阶段 4 再看
   是否保留（3b 遗留未动）。
6. `HkHikerRuleUtil` 缺类问题是 3b 引入、3b 文档未登记的（见 §7.2）：3b 起我方文件即调用它，
   本步又加 2 处；需协调方裁定是搬星辰 `api/hk/util/HkHikerRuleUtil.java` 还是改走已有实现。

## 10. 其他改动

- 未动：`HkEngine.java` / `HkExecutor.java` / `HkRule` / `HkRuleManager` / `HkStore` /
  TV/leanback / BaseLoader / 底栏（仍 6 项）。未引入新依赖（Rhino 已是 1.7.15）。
  未引入 LitePal、dex 动态加载、Clash、XPopup 相关代码。
- 3b/3c 的文档未改；切分出入记在本文件 §3。
