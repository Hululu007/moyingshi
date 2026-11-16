# 移植阶段 3c：HkJsRuntime require/fetch 主干（第 2 部分）

> Recovery anchor（任务：miniapp-xingchen-3c-runtime-2；lane：standard；分支 main；HEAD e52aaf4e）
> 目标：往现有 `HkJsRuntime.java` 追加第 2 部分（require/fetch 主干 + MY_* 注入 + 文件下载），
> 另拷运行时真正读的 3 个 JS 资产进 `app/src/main/assets/`；本地提交 + recovery tag，不推不编。
> 范围（仅 5 路径）：`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkJsRuntime.java`（追加）、
> `docs/miniapp-xingchen-3c-runtime-2.md`（本文件）、`app/src/main/assets/Hikerurl.js`、
> `app/src/main/assets/plugin.js`、`app/src/main/assets/aes.js`。
> 状态：已完成，待 guard finish 提交。验收：抽取脚本自校验 + 独立 verify 均通过（见 §8）。
> 未解决遗留（§9）：中间态不可编译（3d 方法级前向引用 9 个 + 类级前向引用 2 个，3d/3e 补）；
> DoH 的 `HkHttp.applyDoh` 调用行仍未接（留给 3e）；`post0` 与 `fetchWithHeadersInterceptor`
> 在星辰里零调用方，本步未搬（见 §3）。

## 1. 来源与方法

- 星辰原文：`~/workspace/xingchen-ref/app/src/main/java/com/fongmi/android/tv/api/hk/HkJsRuntime.java`，
  14836 行（HEAD 52450c0）。输出：`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkJsRuntime.java`，
  9739 行 → 11590 行（本步净增 1850 行，含段首注释与 3c 分区头）。
- 移植方式：机械抽取脚本 `/tmp/xc3c/port3c.py`（span 表 + 大括号配对定段尾 + 文本适配；
  不随仓库走，见 `/tmp/xc3c/`）+ 独立验证脚本 `/tmp/xc3c/verify.py`。逐段对照原则：
  输出 = 原文 kept 段原样拼接 + §6 所列适配，除此之外零差异（自校验逐段重算比对，见 §8）。
- 本步只追加：新方法全部插在 3b 内容（含尾部嵌套小类）之后、外层类右大括号之前
  （我方文件 9740–11588 行），3b 已有内容一行未动。
- 3b 文档的切分表本身未改；与 3b 文档 §5.1 的出入（两处补入、两处跳过）记在本文件 §3，
  以本文件为准。

## 2. 本步搬入的原文段（按星辰行号，58 段）

段首行含直接附着的注释块（Javadoc/段头）；方法按星辰原文顺序排列。

| 段 | 星辰行（含段头） | 内容 |
|---|---|---|
| fcImpl | 6799–6846 | fc（require0 useCallback=false 包装） |
| fcRuleTitle | 6848–6856 | fc 第五参隐参通道读规则标题 |
| batchFetchImpl | 6858–6938 | 批量 fetch |
| requireCacheEval | 6951–6985 | require 缓存求值 |
| batchCacheM3u8Impl | 7282–7352 | 批量缓存 m3u8 |
| loadLibLocal | 9189–9222 | 本地库加载（md5 直算全局 libs） |
| downloadToFile | 9264–9364 | 下载落盘（含断点续传/416 处理；1 处适配见 §6） |
| rangeStartOf | 9366–9383 | 续传起点解析 |
| contentRangeTotalOf | 9385–9395 | Content-Range 总长解析 |
| optWithoutRange | 9397–9409 | 去 Range 选项 |
| clearHkCacheDir | 9411–9421 | 清 hk 缓存目录 |
| deleteRecursively | 9423–9432 | 递归删除 |
| extensionOf | 9434–9445 | 取 URL 扩展名（FileUtil.getExtension 口径） |
| png2TsFile | 9447–9489 | png2Ts 真实现（FileUtil.png2Ts 逐条转写） |
| sweepStalePartFiles | 9491–9509 | 清过期 .part 分片 |
| getHomeUrl | 10238–10248 | 取首页 URL（调 getRealImageUrl，3d，见 §7） |
| getImageDom | 10325–10338 | 取图片域名（调 getRealImageUrl，3d，见 §7） |
| fetchByHikerWithFlags（2 参） | 11667–11677 | fetchByHiker flags 包装 |
| fetchByHikerWithFlags（3 参） | 11679–11690 | 同上（ruleKey 透传版） |
| optFlagFromJson | 11692–11704 | options JSON 取布尔旗 |
| wrapFetch | 11718–11727 | 五字段 fetch 包装串 |
| extractArrayBody | 11729–11750 | fetch options 字节数组 body 还原 |
| fetchSync | 11752–11991 | 同步 fetch（JS 单线程阻塞） |
| MyRequire | 12187–12198 | 嵌套类（Rhino Require 子类，getFunctionName 回 "requirejs"） |
| runScript（2 参） | 12878–12884 | runScript 入口（调 3 参版） |
| runScript（3 参） | 12886–12952 | runScript 本体（新 scope + 全量注入 + 求值） |
| wrapTryScript | 12954–12960 | try 包裹脚本（§3 补入，batchExecuteImpl 调） |
| require0 | 12963–13102 | $.require 核心加载器 |
| giteePoisoned | 13104–13114 | gitee 坏内容校验 |
| finishRequire | 13116–13133 | require0 公共收尾（登记 + 回调） |
| proxyRequire | 13135–13151 | options.proxy 拼代理地址 |
| initRequire | 13307–13333 | requirejs 装配（单例 provider + MyRequire） |
| fetch0 | 13538–13568 | fetch 入口（调 HkHttpHelper.fetch0，§3 补入） |
| fetchByHiker | 13570–13637 | hiker:///file:// 伪协议分发 |
| initRhino | 13684–13693 | Rhino 初始化 |
| getMyRule | 13695–13735 | MY_RULE 注入串（HkRule/HkSearchEngine 双模型分发） |
| getMyType | 13737–13745 | MY_TYPE 注入串 |
| getMyCallbackKey | 13747–13755 | MY_CALLBACKKEY 注入串 |
| getMyInput | 13757–13765 | MY_INPUT 注入串 |
| getMyJs | 13767–13777 | MY_JS 注入串 |
| getMyUrl | 13779–13793 | MY_URL 注入串 |
| getReplaceJS（1 参） | 13831–13837 | getReplaceJS 入口 |
| getReplaceJS（2 参） | 13839–13856 | 替换 JS 本体 |
| tryGetString | 13912–13923 | 取字符串（非串抛错） |
| generateMyParams | 13925–13937 | MY_PARAMS 注入串 |
| generateInjectMap | 13939–13969 | 注入表生成 |
| decodeConflictStr | 13971–13987 | 冲突字符串解码 |
| proxyUrl | 13990–14006 | 代理 URL 拼接 |
| requestForCode | 14008–14045 | 取码请求（仅 2xx + 无 error 才回 body） |
| isJsCode | 14047–14070 | JS 代码判定 |
| getDirectory | 14072–14083 | require 基址（全局 libs 单基址 URI） |
| generateRequestOptions | 14093–14117 | 请求选项生成 |
| generateHeadersOptions | 14126–14137 | 请求头选项生成 |
| getFilePath | 14161–14180 | hiker:// 路径映射 |
| batchExecuteImpl | 14191–14347 | 批量执行（含 runScript/wrapTryScript 调用） |
| getRequireVersion | 14552–14556 | 读 <md5>.json 版本（委托 RequireUtils，见 §7） |
| updateDescription | 14558–14562 | 写 <md5>.json 描述（委托 RequireUtils，见 §7） |
| generateRequireMap | 14564–14577 | 登记 require 映射（委托 RequireUtils，见 §7） |

## 3. 与 3b 文档 §5.1 切分的出入（以本步实际为准）

### 3.1 补入的两段（3b 漏列，本步方法完整性要求必须搬）

1. **fetch0（星辰 13544–13568，段头起 13538）**：`requestForCode`（3c，星辰 :14018）
   在代码里直接调它；它再调 3a 的 `HkHttpHelper.fetch0`（delegate 回调
   `fetchByHiker`，同为 3c）。不搬则 3c 的 require 取码链路断在 `requestForCode`。
   其内部匿名 `RuleFetchDelegate.fetch` 一并随段搬入。
2. **wrapTryScript（星辰 12958–12960，段头起 12954）**：`batchExecuteImpl`（3c，
   星辰 :14156）在代码里调它。不搬则 3c 的批量执行链路不完整。

### 3.2 跳过的两段（均在 3c 相邻区域，但星辰里零调用方）

1. **post0（星辰 13646–13662）**：private，全仓库（星辰树）零代码调用方——星辰
   registerApi 的 post/postRequest 已改走 `fetchSync` 直调（星辰 :4761 注释），
   post0 只剩注释提及。按 3b“死代码不搬”先例（fileCached、enterRhino）跳过；
   如 3e 要补，它只依赖 3c 已有的 `generateRequestOptions` + `fetch0` +
   3a 的 `HkHttpHelper.generatePostOps`，可原样搬入。
2. **fetchWithHeadersInterceptor（星辰约 13665–13688）**：public，全仓库零调用方。
   只依赖 3c 已有的 `generateHeadersOptions` + `fetch0`，留给 3e 原样搬入。

### 3.3 仍留给 3d 的相邻段（本步未碰）

- `getMyTitleJs` 10262、`getRealImageUrl` 10302（图片簇；`getHomeUrl`/`getImageDom`
  反向引用它，见 §7）、`coerceImageStream` 10345、`post0`/`fetchWithHeadersInterceptor`
  见上、`parseHome` 等解析回调簇 13346–13527、`fetch0` 之后 `post0` 之前无他法。
  `escapeJsString`/`replaceOnce` 已在 3b。

## 4. require0 缓存配对结论（2b/2c 遗留验收项）

结论：**配对成立，路径同根；但 require0 本身不调 `HkRuleManager` 的两个 getter，
而是沿星辰原文用 `hkRootDir()` 内联拼路径**（照原文搬，未自造改法）。

- require0 的 http 分支（星辰 :13025–13028，原文照搬）：
  `libsDir = hkRootDir() + "/libs"`，`jsPath = libsDir/<md5(url)>.js`，
  `jsonPath = libsDir/<md5(url)>.json`。`hkRootDir()` = 外部
  `getExternalFilesDir(DOCUMENTS)`（3b 已搬，星辰 :12249–12253）。
- 我方 `HkRuleManager.getGlobalLibsDir()`（2b）= 同一外部 Documents 根下 `libs/`，
  其注释已写明“require0 的 md5 直算目录同根”——两边逐字同根，.hkzip 包内 libs
  落到 `getGlobalLibsDir()` 即被 require0 的 md5 直算命中，无需回退层。
- 登记簿：`finishRequire` → `generateRequireMap(ruleTitle, desc, url, jsPath)` →
  星辰原文委托 `com.example.hikerview…RequireUtils.generateRequireMap`
  （见 §7），按 8.83 语义写 `Documents/rules/files/<规则标题>/require.json`——
  与我方 `HkRuleManager.getRequireFilesDir(title)`（2b）逐字同根；.hkzip 导入时
  包内 require.json 也是落到该目录（2b `saveHkZipAssets`），require0 的
  `hiker://page/` 分支经 `HkRuleManager.get().getInstalledRule(ruleTitle)` 查库，
  与该登记簿同一标题键。
- 版本文件：`<md5>.json` 的读写经 `getRequireVersion`/`updateDescription`，
  同样委托 RequireUtils（见 §7），落点即上述 `libs/<md5>.json`。

## 5. JS 资产文件处理结论（3b 遗留）

结论：**星辰 assets 里有，且运行时确实读——3 个全拷，字节一致**。

| 资产 | 星辰大小 | 运行时读方 | 我方此前状态 | 本步处理 |
|---|---|---|---|---|
| `Hikerurl.js` | 18370 B | `assembleFreshScope`（3b，缺失即抛 `IllegalStateException`）、`getJsPlugin` | 缺失 | 已拷进 `app/src/main/assets/`，SHA-256 `a64ce842…39737b` 与星辰一致 |
| `plugin.js` | 31348 B | `getJsLazyPlugin`、装配期尾评 | 缺失 | 已拷进 `app/src/main/assets/`，SHA-256 `86273e38…2d` 与星辰一致 |
| `aes.js` | 184910 B | `getCryptoJS`（3b 已搬，CryptoJS 库） | 缺失（3b 文档只点了前两个的名） | 已拷进 `app/src/main/assets/`，SHA-256 `7c326d96…e64` 与星辰一致 |

- 只拷这 3 个，未搬整目录。`loadAsset` 经 `App.get().getAssets().open()` 读
  `main/assets`，3 个文件放该目录即被读到；`getJsPlugin`/`getJsLazyPlugin`/
  `getCryptoJS` 自此不再回 `""`，`assembleFreshScope` 不再因缺 `Hikerurl.js` 抛错。
- 资产来源与用途已按上表写清；三文件均为星辰 assets 原样复制，未改一字节。

## 6. 适配点（差异只剩这些）

1. **包名**：`com.fongmi.android.tv` → `com.github.moyingshi.osc.tv`。本步 58 段里
   该字串出现 0 次（无全限定引用），替换计数 0；`com.fongmi.quickjs` 出现 1 次，
   按 3a 口径保持原样。
2. **ToastUtil**：本步 58 段里出现 0 次，无须改（3b 说的第三处 :12306 在
   callbackKeySink 内，仍归 3d，本步未碰）。
3. **SourceGate / GlobalProxy**：本步 58 段里出现 0 次，无须改（3b 已把
   `swapHkMark`/`restoreMark` 改成空操作；代理选择器链已在 HkHttpClient 里生效）。
4. **HkMediaCacheCleaner（1 处，downloadToFile 内星辰 :9333 行）**：
   原文 `HkMediaCacheCleaner.scheduleClean(new File(getFilePath("hiker://files/")));`
   （落盘后自动清理：6 小时/8 个文件/400MB 删最旧，后台线程跑）。该类未随本步移植，
   且本步 scope 只允许改 HkJsRuntime.java——该行改为注释说明的空操作，
   其余落盘逻辑原样保留。清理语义缺失，不影响下载本身；如后续要补，搬星辰
   `HkMediaCacheCleaner` 类后把该行恢复原文即可。
5. **其余零差异**：`HkRouter` 在本步新增部分只出现 1 次，且是 `fetchSync` 内一条
   注释的提及（星辰 :11803 原文注释），非代码引用，未改；`FileUtil` 出现 6 次、
   `JSONObject` 出现 4 次，也全是注释提及，未改。
6. **未动**：HkHttpClient（3b 已接好线）、HkHttp.applyDoh 的 DoH 行（留给 3e，
   见 §9）、HkEngine/HkExecutor/HkRule/HkRuleManager/HkStore、TV/leanback、
   BaseLoader、底栏（仍 6 项）。未引入新依赖，未引入 LitePal、dex 动态加载、
   Clash、XPopup 相关代码。

## 7. 前向引用清单（本步引用、别处才补；★ 为统一构建前必须解决的类级引用）

### 7.1 方法级（代码调用；3d 补）

| 方法 | 调用方（本步） | 归属 |
|---|---|---|
| `getRealImageUrl` | getHomeUrl、getImageDom（各 1 处） | 3d 图片簇（3b §5.2 已定） |
| `fileToString(f, null, true)` | fetchByHiker file 分支 | 3d 文件簇 14471–14492 |
| `hkFileToInputStream` | fetchByHiker file/inputStream 分支 | 3d 文件簇 12349–12351 |
| `hkFileToBytes` | fetchByHiker file/toHex 分支 | 3d 文件簇 12353–12361 |
| `hkGetAssetsFile` | fetchByHiker hiker://assets/ 分支 | 3d 文件簇 12370–12376 |
| `hkGetRulesByHiker` | fetchByHiker hiker:// 分支 | 3d 文件簇 12386–12393 |
| `hkSeedRestore` | fetchByHiker file 分支（种子回补） | 3d 种子簇 9134–9187 |
| `installJavaAdapterShim` | runScript（3 参） | 3d JavaAdapter 13299–13305（assembleFreshScope 也调，3b 已记） |
| `logEvalError` | runScript（3 参） | 3d 求值入口簇 12591–12606 |

注释提及（非代码引用，不构成编译依赖）：`evalLazyDry`（fetchSync 内注释 1 处）、
`hkSeedAssetFor`（fetchByHiker 内注释 1 处）、`fileToString`（require0 内注释
`FileUtil.fileToString` 1 处）。其余 3c 方法的全部被调方法均已在 3a/3b/3c 内闭合：
`fetch0` → 3a `HkHttpHelper.fetch0` + 3c `fetchByHiker`；`initRequire` →
`UrlModuleSourceProvider`/`StrongCachingModuleScriptProvider`（3b 已 import，
字段 `cachingModuleScriptProvider` 在 3b）；`require0` → `HkRuleManager`（同包已存在）
+ `HkPageRule`（3b 已搬）；`getMyRule` → `HkRule`（同包已存在）+ §7.2 的
`HkSearchEngine`。

### 7.2 类级（★ 统一构建前必须先补齐）

| 类 | 本步代码引用 | 归属 | 星辰位置 |
|---|---|---|---|
| ★ `com.example.hikerview.ui.rules.service.require.RequireUtils` | 6 处（全限定调用：getRequireVersion / updateDescription / generateRequireMap 各 2 处，含段头注释） | 待协调方裁定（建议 3e 随门面一并搬入，或单独一笔） | 星辰 `app/src/main/java/com/example/hikerview/ui/rules/service/require/RequireUtils.java`，274 行；三个方法为其 public static 方法（:164、:253、:266） |
| ★ `HkSearchEngine` | 6 处（getMyRule 内 instanceof + 强转 + 三元，星辰 :13710–13735 段内） | 待协调方裁定（建议 3e 随门面一并搬入） | 星辰 `api/hk/HkSearchEngine.java`，299 行；`getTitle()` :153、`getDisplayName()` :47 |

- 3b §8.2 的 ★ 清单（HkItem、HkFindDelivery、SearchResult、17 个事件 POJO、
  HkCryptoFileUtil、HkSelector、HkHttpParser、HkRuleManager 云口令三法）仍未补，
  与本步新增的 2 类合计，统一构建前必须先补齐；建议仍由 3e 一并搬入小 POJO/小类。
- `HkMediaCacheCleaner` 不在上表：本步已把其唯一调用改为空操作，无代码引用；
  要恢复清理语义时再搬该类（星辰 `api/hk/` 下，行数待 3e 核查）。

## 8. 验收证据

- 脚本：`/tmp/xc3c/port3c.py`（抽取 + 自校验）、`/tmp/xc3c/verify.py`（独立验证）、
  `/tmp/xc3c/inventory.py`（星辰方法清点，609 段）、`/tmp/xc3c/port3c-manifest.json`
  （58 段段表 + 适配计数）。注：`/tmp` 在任务中途被环境清理过一次，脚本已重建
  并重跑通过；脚本不随仓库走。
- 自校验（port3c.py）：58 段抽取成功；适配计数：包名替换 0、ToastUtil 0、
  SourceGate 0、GlobalProxy 0、HkMediaCacheCleaner 中和 1；禁引通过
  （零 `com.fongmi.android.tv` / `SourceGate` / `GlobalProxy` / `ToastUtil` /
  `HkSelector` / `HkDetail` / `HkPlay` / `HkFindDelivery` / `HkCloudClip`；
  `HkRouter` 仅注释提及 1 处，见 §6.5）。
- 独立验证（verify.py）：58 段逐段重算比对全部通过（与星辰原文逐段一致，
  仅 §6.4 一处适配）；全文件大括号平衡（终深度 0、最低 0）；3c 块
  （我方文件 9740–11588 行）禁引核查通过；方法定义清点：58 段 + 内嵌
  `RuleFetchDelegate.fetch` + `MyRequire.getFunctionName`，与段表一致。
- 依赖核查：星辰方法清点（609）与我方方法清点（510 行定义行，含内嵌方法）
  对比，3c 块内引用的“星辰有、我方无”的方法名共 11 个，其中代码调用 9 个
  （§7.1），注释提及 3 个（含重叠）；类级扫描：全部类型经 import 或全限定
  引用解决，仅 §7.2 的 2 类待补；`GSON` 字段（我方 :106）与 `Util` import
  已在 3b；`JSONObject`/`FileUtil` 在 3c 块内仅注释出现。
- `git diff --check` 通过；改动：`HkJsRuntime.java` +1850 行（纯追加，3b 内容
  未动），新文件 3 个 JS 资产 + 本文档。
- 逐行重读：已人工重读 `require0` 全段、`fetchByHiker` 全段、`runScript`（3 参）
  全段、`requestForCode`/`fetch0` 全段、`getMyRule` 全段与 `downloadToFile` 的
  416/续传段；确认差异只剩 §6 所列适配点。
- 未编译：本地无 JDK/Android SDK，编译靠最后统一构建验（任务书已明示）。
- 3 条验收规则 require 链路（代码级）：`$.require` → `require0`（3c）→
  http 分支：`requestForCode`（3c）→ `fetch0`（3c）→ 3a `HkHttpHelper.fetch0`
  → OkHttp 直连（无代理配置时与星辰同形；代理选择器链已在 HkHttpClient 里）；
  hiker:///file:// 分支：`fetchByHiker`（3c）→ 3d 文件/种子助手（§7.1，
  待 3d）；本地库：`loadLibLocal`/`getDirectory`（3c，单基址全局 libs）+
  `initRequire`/`MyRequire`（3c）装配 `requirejs`。http(s) 链路在 3a+3b+3c 内
  已闭合；hiker:///file:// 分支待 3d 的 6 个文件助手。
- gitcode 418（预期，非回归）：星辰影视123.Dr / 青苹果♨️ 的 require 链路走到
  gitcode 预处理库（`qq_32394351/dr 预处理.js`、`m0_73099089/btea webpre.js`）
  时，`HkHttpHelper.fetch0` 经 OkHttp 直连拿到 gitcode 回的 HTTP 418，
  `requestForCode` 因 statusCode 非 2xx 回 null，`require0` 抛
  “获取远程依赖失败：<url>”——与星辰同形（星辰 require 同样直连、无代理配置
  时一样过不去；2026-11-16 已核对 git 协议同样被拦）。418 是服务端拒绝，
  不是本步引入的回归。

## 9. 遗留（未解决）

1. 中间态不可编译：§7.1 的 3d 方法级前向引用（9 个）+ §7.2 的类级前向引用
   （RequireUtils、HkSearchEngine）+ 3b §8.2 的 ★ 清单（9 类），统一构建前
   必须先补齐；需协调方裁定由哪一步补（建议 3e 一并搬入小 POJO/小类）。
2. DoH 调用行仍未接：`HkHttp.applyDoh` 内传 `HK_AUTO_DNS` 的一行（3b §7），
   HkHttp.java 不在本步 scope 内，留待 3e 门面接线。
3. `HkHttp.java:847–850` 的 client() 注释已过时（称 CookieJar/ContentType
   “尚未接进任何 client”），随 HkHttp.java 下一次改动时更新（3b 遗留未动）。
4. `HkMediaCacheCleaner` 调用已中和（§6.4）：落盘后自动清理暂不执行；要恢复
   该语义需另搬该类并恢复原文行。
5. `post0` 与 `fetchWithHeadersInterceptor` 未搬（星辰里零调用方，见 §3.2）；
   如 3e 要补，两者只依赖 3c/3a 已有方法，可原样搬入。
6. `SecurityController` import 在输出中暂无代码引用（仅 import），javac 只告警
   不报错；3d 的 JavaAdapter 落定后再看是否保留（3b 遗留未动）。

## 10. 其他改动

- 未动：`HkEngine.java` / `HkExecutor.java` / `HkRule` / `HkRuleManager` / `HkStore` /
  TV/leanback / BaseLoader / 底栏（仍 6 项）。未引入新依赖（Rhino 已是 1.7.15）。
  未引入 LitePal、dex 动态加载、Clash、XPopup 相关代码。
- 3b 的文档未改；切分出入记在本文件 §3。
