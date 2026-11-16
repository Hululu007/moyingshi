# 移植阶段 3b：HkJsRuntime 骨架 + 内置 API（第 1 部分）+ CookieJar 接线

> Recovery anchor（任务：miniapp-xingchen-3b-runtime-1；lane：standard；分支 main；HEAD 8c07e058）
> 目标：新建 `HkJsRuntime.java`（星辰 14836 行的第 1 部分：骨架 + 内置 API 注册 + 自包含实现助手）
> + `HkHttpClient` 接入 CookieJar/ContentType 拦截器/10s 超时 + DoH 重配入口；本地提交 + recovery tag。
> 范围（仅 4 路径）：`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkJsRuntime.java`（新建）、
> `.../api/hk/HkHttpClient.java`、`docs/miniapp-xingchen-3b-runtime-1.md`（本文件）、
> `docs/miniapp-xingchen-2b-manager.md`（第 202 行一句话）。
> 状态：已完成，待 guard finish 提交。验收：脚本抽取 + 自校验 + 独立 verify 均通过（见 §7）。
> 未解决遗留（§8）：中间态不可编译（类级前向引用 9 类，3c–3e/阶段 4–7 补）；DoH 的 `HkHttp.applyDoh`
> 调用行未接（HkHttp.java 不在本步 scope 内）；`HkHttp.java` 内 client() 注释已过时（同上）。
> 下一步：父代理组织 3c（require/fetch 主干）与 3d（解析回调）。

## 1. 来源与方法

- 星辰原文：`~/workspace/xingchen-ref/app/src/main/java/com/fongmi/android/tv/api/hk/HkJsRuntime.java`，
  14836 行（HEAD 52450c0）。输出：`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkJsRuntime.java`，
  9739 行。
- 移植方式：机械抽取脚本 `/tmp/xc3b/port3b.py`（span 表 + registerApi 手术 + 文本适配；
  不随仓库走，见 `/tmp/xc3b/`）+ 独立验证脚本 `/tmp/xc3b/verify.py`。逐段对照原则：
  输出 = 原文 kept 段原样拼接 + §6 所列适配，除此之外零差异（自校验逐段重算比对，见 §7）。
- 通读结论：原文 registerApi 内“先 stub、后真实现覆盖”的写法有 15 处（stub 在前、真实现在后，
  后者胜出）；本步按真实现归属决定 stub 去留（§4）。另有 3 处“先 stub、后无真实现”
  （getEpub*、buildWebDav、addWebProxyRule*、initChaquopy、isVideoOrMusic、png2Ts 的 stub 是唯一注册，
  予以保留）。

## 2. 本步搬入的原文段（按星辰行号）

| 段 | 星辰行 | 内容 | 备注 |
|---|---|---|---|
| 骨架 | 1–1219 | 包名/import/字段/构造器外全部基础结构：HkContextFactory、ListEvalSupersededException/ListEvalMark、QrScanHost、putFunction/putFunctionEx、dispatchApi、str/json/int/boolApi、addListenerImpl、看门狗、定时器字段 | 删 DEX_CLASSLOADERS 103–112、4 参 loadJavaInstance 695–698；HkContextFactory 两处适配（§6） |
| @Parameter | 1220–2003 | 约 160 个 @JSAnnotation 委托法（均调 dispatchApi），删 15 个（§3） | 注解行随方法一并保留/删除 |
| 求值辅助 | 2004–3437 | eval 日志/求值 helpers、parseJson、arg helpers、juStore、copyFileQuiet 等 | 删定时器五法、dex 三法；swap/restore 改桩（§6） |
| 构造器/init | 3438–3605 | 构造器、bindRule、submit、init、registerApiInto、resCodeByCallbackKey | init 调 getAllFunctions/getUaObject/assembleFreshScope/loadKv（均本步） |
| registerApi | 3607–6770 | 全部 putFunction/stub 注册与内联代码 | 手术 43 处（§4） |
| 实现助手 | 6778–9513 | 见 §2.1 清单 | 剔 3c 项、死代码、种子簇 |
| 上下文/交付 | 9790–11401 | 见 §2.2 清单（选段） | 解析/图片/懒求值簇留 3d |
| 杂项助手 | 11470–12139 | stringifyArg、jsonGetByKey、injectMobileUa、mergeMethod、xpathArrayImpl、applyGeneratePostOps、buildParamStr、optFlag、closeFetchStream、kv 簇（kvFile/syncKvToBoundRule/loadKv/isKvPoison/sanitizeKvPoisonOnce/sanitizeKvUndefinedOnce/saveKv） | fetchByHikerWithFlags/optFlagFromJson/wrapFetch/extractArrayBody/fetchSync 留 3c |
| D1 类型/字段 | 12140–12233 | ReturnType、JSAnnotation、Parameter、JSEngineCallback、HkPageRule、D1 字段（globalMap、rc4、myBase64、callbackMap、resCodeMap、methodList、cachingModuleScriptProvider、allFunctions、clazz、ruleTicketMap/ticketMap）+ imageGlobalMap 静态字段（9842–9843） | 跳过 HkNoSecurityController 12174–12185；MyRequire 12188–12198 留 3c（initRequire 用，见 §5 偏差） |
| 小助手 | 12249–12347 | hkRootDir、getFilesDir、genUuid、generateTicket、log、4 参 setError | 跳过 enterRhino（死代码）；armCallbackKey/callbackKeySink、hkFileToInputStream/hkFileToBytes、hkGetAssetsFile/hkGetRulesByHiker 留 3d |
| 尾部 | 选段 | syncExecute 12691–12717、getFunctionStr 12726–12876、appendLineVar 13795–13827、getAllFunctions 13869–13880、getUaObject 13890–13895、3 参 setError 13904–13908、realLineNumber 14353–14361、setErrorImpl 14369–14460、HkRc4 14582–14699、HkMyBase64 14708–14764、ecdsaSignDevice 14769–14787、ecdsaSignFallback 14797–14813、hkBigInt32Hex 14816–14822、hkBytesToHexString 14825–14833 | log/setError/getUaObject/syncExecute 四名用既有真方法挂 @JSAnnotation（不在 @Parameter 块重复） |

### 2.1 实现助手 6778–9513 搬入清单

aesUtilCrypt、setPageReverseDo、mustBeString、hkMd5、ruleDirForTitle、downloadM3u8File、
hkTryGetString、appInstalled、paramStringValue、hkToast、copyDirectoryCount、zipDirectoryContents、
zipInto、isNotM3u8Url、convertM3u8Paths、fetchM3u8Body、resolveUrl、withHeadersOpt、stub（方法本身）、
myRuleObjFromScope、ambientRuleIsNull、ruleFromExplicitKey、myRuleFromScope、ruleFromJsJson、
coerceStringFields、fastJson 系列、pageFieldString、handlePageRequest×2、findPageCode×2、toStrMap、
itemMapsFromJson、itemObjectFromJson、itemValueFromJson、currentItems、findItemIndexByExtraId、
extraMapOf、extraIdOf、rawExtraId、hasCls、convertItem、applyUpdateFields、postItemModifyEvent×3、
postItemModifyListEvent、postItemModifyAddEvent、addItemAt、itemToFullMap、cacheHoursValue、toDouble、
mergeDesktopUa、resolveHikerPath、hkRuleTitle、currentRuleTitle、resolveRuleTitle、hkIntValue、
hkFilesDirPrefix、hkInJsDir、hkRawFileToString、hkRawStringToFile、hkDetectCharset、hkCharsetDetectRead、
hkFileBlacklisted、hkFileToString、hkStringToFile、getItemNow、checkSetItemSize、postRuleModifiedEvent、
setItemNow、auth 簇（authRuleExists/parseAuthItems/getAuth/updateAuth/authGate）、bridgeKvGet/Set/Keys/Remove、
kvMapForTitle、readKvFile、writeKvFile、readFileContent×2、aesDecode（2 参）、writeFileContent、fixM3u8Content、
hexToB64、rsaWithOptions、rsaCrypt、getLocalIp、publicKvFile、legacyPublicKvFile、loadPublicKv、savePublicKv、
COL_TYPES、colTypeCodes、hkColTypeCodes、loadAsset、loadAssetText、injectAndroidPackage。

### 2.2 上下文/交付簇搬入清单（9790–11401 选段）

getStrResult、getLastChapterRule、getPagePicUrl、eval（9810–9840，JsEvaluator 接口方法，@Override；
HkSelector 阶段 4 经此调）、escapeJsString、injectAndroidPackage、replaceOnce、getError、
noteFetchFailure、getLastUrl、setEvalResCodeBody、destroy、setContext、putDisplayNameInto、
ensureLazyPluginInstalled、ensurePluginIn、setListContext、setDetailParams、homeOf、stripJsPrefix、
resultTypeKey、deliverResult、deliveryCallbackKey、deliverNoData、classifyPayload、showFindError、
buildSearchItems、searchDescFallback、resultPayloadJson、collectResult、extractResultItems、
appendResultItems、buildResultItems、str、stringifyValue、drainResults、toSearchResults、nz。
（refreshScrollTop 2824–2827 已随求值辅助段搬入，供 3d 的 refreshPage 用。）

## 3. @Parameter 跳过的方法（15 个，与注册同进退）

executeWebRule、fetchCodeByWebView、findJavaClass、getHomeSub、hasHomeSub、hideLoading、
initChaquopy、loadJavaClass、openAppIntent、proxyClearM3u8、publishRule、refreshPage、
showLoading、showSelectOptions、startProxyServer（各连同其 @JSAnnotation 行删除）。
无 @Parameter 方法故无须处理的名字：__hkShowUpdateLog、fcbw、ewr、refresh、goTo
（仅 putFunction 注册，见 §4）。

## 4. registerApi 手术（43 处，按星辰原文行号）

规则：@Parameter 方法与注册同进退；真实现被整段跳过时对应 @Parameter 方法也跳过（§3）；
只跳真实现、stub 保留时 @Parameter 方法保留（dispatchApi 回 stub 值）。

### 4.1 整段删除（18 个 putFunction，无 stub 保留）

| 名 | 星辰行 | 原因 |
|---|---|---|
| __hkShowUpdateLog | 4289–4304 | HkUpdateLogPopup，不搬 |
| fetchCodeByWebView / fcbw | 4764–4781 / 4782–4791 | HkWebkitFetcher，阶段 5 |
| executeWebRule / ewr | 4792–4802 / 4803–4813 | 同上 |
| proxyClearM3u8 | 4984–5048 | HkM3u8ClearUtil，阶段 5 按需 |
| showSelectOptions | 5301–5356 | HkDetailBridge + ShowSelectOptionsEvent，阶段 5 |
| refreshPage | 5483–5497 | 留 3d（导航标记簇，§5） |
| goTo | 5499–5506 | 留 3d（同上） |
| openAppIntent | 5541–5609 | HkAuthManager，阶段 6 |
| publishRule | 5610–5647 | HkAuthManager + HkPublishHelper + AiActivityTracker，阶段 6 |
| findJavaClass / loadJavaClass | 5710–5730 / 5731–5749 | dex 动态加载，禁搬（§6） |
| showLoading / hideLoading | 5868–5880 / 5881–5889 | HkLoading，阶段 5 |
| startProxyServer | 5966–5988 | HkProxyServer，不搬 |
| getHomeSub / hasHomeSub | 6255–6266 / 6267–6274 | HkHomeSubService，阶段 7/不搬 |

### 4.2 只删真实现、保留早期 stub（10 个）

| 名 | 真实现行 | 保留的 stub | 原因 |
|---|---|---|---|
| isVideoOrMusic | 6319–6328 | `stub("isVideoOrMusic", false)` | HkRouter，阶段 5 |
| getEpubChapters / getEpubContent0 / getEpubMetadata | 6571–6577 / 6578–6583 / 6584–6588 | `"[]" / "" / "{}"` | HkEpubBridge，不搬 |
| initChaquopy | 6589–6609 | `stub("initChaquopy", null)` | 本仓库无 `com.fongmi.chaquo.Loader` |
| addWebProxyRule / removeWebProxyRule / refreshWebProxyRule | 6610–6631 / 6632–6639 / 6640–6645 | `null` | HkBrowserProxy，阶段 5 |
| buildWebDav | 6501–6507 | `stub("buildWebDav", null)` | HkWebDav，不搬 |
| png2Ts | 6735–6761 | `stub("png2Ts", null)` | 真实现留 3c（随 downloadToFile/png2TsFile） |

### 4.3 只删早期 stub、保留真实现（15 个；真实现覆盖 stub）

back 5527、backToHome 5528、refreshReadData 5890、refreshVideoSource 5891、
refreshVideoUrl 5892、refreshX5WebView 5893、createQRCode 5918、createQRCodeToFile 5919、
copyFiles 5945、shareDirectory 5964、writeHexFile 5965、checkPrivacyPassword 6033、
getPrivacyPasswordLen 6034、isShorthand 6078、getCurrentActivity 6181（各 1 行）。
其中值得单列的核实结果：

- **back / backToHome 真实现已搬入**（5752/5772 行段，覆盖 5527/5528 的 stub）：发
  `OnBackEvent` / `BackMainEvent`（事件 POJO 前向引用，§8）。
- **refreshReadData / refreshVideoSource / refreshVideoUrl / refreshX5Desc / refreshX5WebView
  真实现已搬入**（6646–6726 行段）：纯事件投递（RefreshReadDataEvent、RefreshVideoUrlEvent、
  PlayDataUpdateEvent、OnRefreshX5HeightEvent、RefreshWebViewEvent），不引用 HkPlay/HkDetail，
  故不随“涉 HkPlay 则跳”处理；消费方在阶段 5。
- **checkPrivacyPassword / getPrivacyPasswordLen 真实现已搬入**：只读 `share_data` 的 `ppwd`，
  无新依赖；`privacyModeUnlocked` 字段已在骨架内。
- **copyFiles 真实现已搬入**：代码用 `resolveHikerPath`（非任务书初估的 `getFilePath`），
  + `copyDirectoryCount`（本步）；与注释口径不符处以代码为准。
- **createQRCode / createQRCodeToFile 真实现已搬入**：用我方 `utils/QRCode.getBitmap`
  （已存在）+ `FileUtil.getShareUri`（已存在）；zxing-core 已在 `app/build.gradle:217`。
- **shareDirectory 真实现已搬入**：用 `zipDirectoryContents`（本步）+ 我方 `FileUtil.getShareUri`。
- **writeHexFile 真实现已搬入**：用 `resolveHikerPath` + `hkFilesDirPrefix` + `hkInJsDir`（均本步）。
- **isShorthand 真实现已搬入**：恒回 `Boolean.FALSE`，无依赖。
- **getCurrentActivity 真实现已搬入**：`return App.activity();`（我方 App 有此方法）。
- **getLastRules / getRuleCount 已搬入**：用 `HkRuleManager.get().getDir()`（我方有此方法）。
- **getPrivateJS / getJsPlugin / getJsLazyPlugin 已搬入**：用 `aesUtilCrypt` / `loadAsset`（本步）。
  注意：我方 assets 内暂无 `Hikerurl.js` / `plugin.js`，`loadAsset` 取不到时两函数回 `""`
  （与星辰缺资产时同形）；资产补齐是后续步骤的事。
- **registerTask / unRegisterTask 真实现已搬入**（见 §5 偏差）：全部依赖在本步闭合内。
- **setLastChapterRule 已搬入**：发 `SetPageLastChapterRuleEvent`（事件 POJO 前向引用）。
- **cacheCode0 已搬入**：发 `event.rule.CacheCode`（事件 POJO 前向引用）。
- **setPageParams / setPagePicUrl / setPageTitle 已搬入**：发 `SetPageParamsEvent` /
  `SetPagePicEvent` / `SetPageTitleEvent`（事件 POJO 前向引用；`getPageTitle` 用
  `App.activity()`，我方有此方法）。
- **registerDNS 已搬入**：用 `authGate` + `resolveRuleTitle`（本步）+
  `HkHttp.registerAutoDomain`（3a 已有，`HkHttp.java:137`）。
- **getSearchMode / setSearchMode 已搬入**：只读写 `share_data` 的 `smode`。
- **parsePaste 已搬入**：调 `HkRuleManager.isCloudCode / importByCloudCode / fetchPasteRawText`——
  这三个方法我方 `HkRuleManager` 暂无（阶段 6 云口令分发表时补），记为前向引用（§8）。
- **`refresh` 别名**：`putFunction("refresh", …)` 调 `setPageReverseDo`（倒序语义，已搬入）；
  与 `refreshPage`（重刷标记，留 3d）是两个东西，不要混淆。原文 `// （W1：…）` 标记注释已保留。

## 5. 留给 3c / 3d 的部分（以本步通读后的实际切分为准）

### 5.1 留给 3c（require/fetch 主干 + MY_* 注入 + 文件下载）

require0 12977–13102、initRequire 13314–13333、fetchSync 11758–11991、
fetchByHiker 13577–13637、fetchByHikerWithFlags×2 11675–11690、fcImpl 6815–6846、
fcRuleTitle 6852–6856、batchFetchImpl 6864–6938、requireCacheEval 6962–6985、
batchCacheM3u8Impl 7289–7352、batchExecuteImpl 14191–14347、requestForCode 14015–14045、
isJsCode 14052–14070、proxyRequire 13141–13151、proxyUrl 13991–14006、giteePoisoned 13109–13114、
finishRequire 13120–13133、tryGetString ~13914–13923、generateRequestOptions 14093–14117、
generateHeadersOptions 14126–14137、getDirectory 14078–14083、getRequireVersion 14553–14556、
updateDescription 14559–14562、generateRequireMap 14574–14577、loadLibLocal 9196–9222、
downloadToFile 9268–9364 + rangeStartOf 9367–9383 + contentRangeTotalOf 9386–9395 +
optWithoutRange 9398–9409 + sweepStalePartFiles 9495–9509、png2TsFile 9453–9489 +
extensionOf 9435–9445 + clearHkCacheDir 9412–9421 + deleteRecursively 9423–9432、
getFilePath 14168–14180、MY_* 注入助手 getMyRule/getMyType/getMyCallbackKey/getMyInput/getMyJs/getMyUrl
13695–13793、getReplaceJS×2 13829–13856、initRhino 13689–13693、
generateMyParams/generateInjectMap ~13925–13969、decodeConflictStr ~13971–13987、
runScript×2 12882–12952、MyRequire 12188–12198、getHomeUrl 10239–10248、getImageDom 10326–10338、
optFlagFromJson 11694–11704、wrapFetch 11719–11727、extractArrayBody 11735–11750。

### 5.2 留给 3d（解析回调 + 求值入口 + 图片/懒求值 + 定时器 + JavaAdapter + 导航标记）

- 解析回调：parseList 全套 9514–9699、parseSearch 全套 9704–9787、parseDetailRaw 11422–11468、
  parseHome 13346–13366、parseLastChapterRule 13376–13396、parsePreEngineRule 13405–13419、
  parsePreRule×2 13423–13454、parseSearchRes 13463–13466、parseStr 13475–13491、
  parseSearchResInternal 13503–13527、loadConfig/loadConfigInto 10918–10935、
  lastParseDetailScopeRule/clearLastParseDetailScopeRule 11414–11420。
- 求值入口：evalJS×2 12405–12588、logEvalError 12591–12606、evalJSForObj 12612–12671、
  evalLazyRule 12676–12682（注：原文是 2 个 evalJS 重载，不是 3 个）。
- 图片求值簇：evalImageJs×2 9914–9946、evalImageJsInternal 9954–10133、
  imageAssetRequirePatch 10142–10170、ensureDollarShim 10183–10210、wrapImageJs 10213–10215、
  imageEngine 9853–9871、initImageEngine 9877–9902、getMyTitleJs 10262–10274、
  getRealImageUrl 10302–10318、coerceImageStream 10345–10399。
- 懒求值簇：evalLazy 10408–10410、evalActionProbe 10421–10423、evalRawInFreshScope 10430–10471、
  evalLazyInternal 10473–10490、evalLazyDry 10515–10552、evalLazy 10555–10560。
- 选择器桥：newSelector 10565–10569、sel 10572–10574（`HkSelector.decodeConflict`，阶段 4）。
- 导航标记簇：refreshPage/goTo 注册（已在 §4.1 删除）+ consumeRefreshRequest 10629–10633、
  actionResultCount 10640–10642、snapshotActionResults 10648–10650、consumeGoToRequest 10657–10661、
  isRefreshRequested 10664–10666、setNextInputOverride 10672–10674、isRefreshToTop 10676–10678
  （字段 refreshRequested/refreshToTop/lastRefreshPageAt/goToUrl 与 refreshScrollTop 已在 3b 骨架内）。
- 定时器：jsSetTimer 2399–2461、jsClearTimer 2464–2485、fireTimerCallback 2492–2517、
  clearAllTimers 2520–2546、clearTimersForRule 2557–2588（字段 timerScheduler/jsTasks/jsTaskRules
  与 timerRuleKey 已在 3b 骨架内；destroy/onDestroy 调 clearAllTimers，属前向引用）。
- JavaAdapter：HkJavaAdapterFn 13160–13225、JsProxyHandler 13228–13297、
  installJavaAdapterShim 13299–13305（assembleFreshScope 调它，属前向引用）。
- 文件/种子/其他：hkSeed* 簇 9134–9187（+ HK_SEED_FILES 字段）、fileToString 簇 14471–14541
  （fileToString/getContentByCharsetDetect/detectCharset）、hkFileToInputStream/hkFileToBytes
  12349–12361、hkGetAssetsFile/hkGetRulesByHiker 12370–12393、armCallbackKey/callbackKeySink
  12273–12311、jsErrorMsg 10609–10623。
- 死代码不搬：fileCached 8143–8165、enterRhino 12238–12246（全仓库零调用方）。

### 5.3 与初步计划的偏差（通读原文后裁定）

1. **registerTask / unRegisterTask 纳入 3b**（初步计划倾向 3d）：真实现全部依赖
   （timerScheduler/jsTasks/jsTaskRules/timerRuleKey/submit/evalJsRooted/nullRuleScope/
   syncKvToBoundRule/hkToast）都在 3b 闭合内；只有 setTimeout/setInterval 的引擎
   （jsSetTimer 等五法）留 3d。
2. **MyRequire 改归 3c**（初步计划列 3d）：唯一调用方是 `initRequire`（13330 行，3c）。
3. **`eval(String, String)` 纳入 3b**（初步计划未列）：`HkSelector.JsEvaluator` 接口方法
   （@Override），HkSelector（阶段 4）经此调；唯一非 3b 依赖是 `getMyCallbackKey`（3c）。
4. **getRealImageUrl 改归 3d**（初步计划未定）：调用方是 getImageDom（3d）与 getHomeUrl（3c）；
   按“图片 URL 归一化属图片簇”归 3d，getHomeUrl（3c）反向引用它。
5. **getHomeUrl / getImageDom 归 3c**：getHomeUrl 被 getMyUrl（3c）调用；getImageDom 唯一调用方
   是 getHomeUrl。
6. **getError / noteFetchFailure / getLastUrl / setEvalResCodeBody 纳入 3b**（初步计划未列）：
   四个无依赖的公开存取器（error/lastUrl/evalResCodeBody 字段），供 UI/错误展示用；
   jsErrorMsg 归 3d（仅 parse 簇调用）。
7. **replaceOnce 纳入 3b**：`handlePageRequest`（3b）调用它。
8. **findJavaClass / loadJavaClass 整段跳过**（初步计划只列了方法名未定性）：其实现
   `resolveJavaClass` / `newInstanceOrClass` / 5 参 `loadJavaInstance`（3261–3423）
   含 `new DexClassLoader(...)`（3383 行），属禁搬的 dex 动态加载；1 参 `loadJavaInstance(String)`
   （686–693，纯 Class.forName）已搬入（无调用方，公开 API 保留）。
9. **createQRCode* / copyFiles / shareDirectory / writeHexFile 等真实现可搬**（初步计划待读后定）：
   见 §4.3——所用工具（QRCode/FileUtil/resolveHikerPath/zipDirectoryContents）均已在我方或本步内。

## 6. 适配点（差异只剩这些）

1. **包名**：`com.fongmi.android.tv` → `com.github.moyingshi.osc.tv`（全文含注释与全限定引用，
   59 处 `api.hk.` 全限定引用 + package/import 行）；`com.fongmi.quickjs` 包名保持原样
   （3a 有意保留）。每个新文件在 package 行后加一行文件头移植说明（3a 同款）。
2. **ToastUtil → Notify**（2 处）：toast lambda（星辰 :3881）与 hkToast（:7141）的
   `ToastUtil.show(App.get(), msg, android.widget.Toast.LENGTH_SHORT)` 改为 `Notify.show(msg)`；
   `Notify`（`com.github.moyingshi.osc.tv.utils.Notify`）无时长参数。第三处 :12306 在
   callbackKeySink 内，归 3d（LONG 时长丢失，3d 处理时注明）。两 import 行已删 ToastUtil、
   已加 Notify import。
3. **按源定向标记改空操作**：`swapHkMark` 改 `private Object swapHkMark() { return null; }`，
   `restoreMark` 改 `private void restoreMark(Object prev) {}`；三处调用点
   （evalJsInScope 2101、evalJsRooted 2201、evalIntoScope 2264）的 `SourceGate.Mark prevMark`
   改 `Object prevMark`；删 `com.github.catvod.net.SourceGate` import。代理选择器链已在
   HkHttpClient 里生效。输出文件零 `SourceGate` 字串（含注释——本步自写注释亦避开该词）。
4. **HkAndroidClassLoader（dex，不搬）**：`HkContextFactory.createClassLoader` 改
   `return super.createClassLoader(parent);`；`installSecurityControllerOnce` 改空实现；
   跳过 `HkNoSecurityController` 类（12174–12185）与 DEX_CLASSLOADERS 登记表字段（103–112）
   及其注释；跳过 dex 三法（§5.3 第 8 条）。`HkAndroidClassLoader` 在输出中仅存于 5 行注释
   （文件头 1 行 + HkContextFactory 历史注释 2 行 + 本步适配注释 2 行），无代码引用。
   `SecurityController` import 保留（当前无代码引用，仅 import；3d 的 JavaAdapter 可能用）。
5. **其余**：`com.fongmi.android.tv.App` 全限定引用 0 处（均走 import）；`FileUtil` /
   `QRCode` 全限定引用改我方包名（各 2 处）；`HkCryptoFileUtil` 挂载
   （`__cryptoFileUtil`，4419–4421 行段）与 `HkHostClasses.installFileUtilChain` 调用按原文保留
   （HkCryptoFileUtil 是前向引用，§8）；`_base64` 挂 `new HkMyBase64()`（5145 行段）已搬入。

## 7. CookieJar 接线（HkHttpClient）

改动只影响 hk 取数。调用方核查（grep `HkHttpClient.client()`）：仅三处，均属 hk——
`HkExecutor.java:999`（hk 执行器取数）、`HkHttp.java:816`（图片 client 基底）、
`HkHttp.java:854`（`HkHttp.client()` 委托）。无非 hk 调用方。

接线内容（对照星辰 `HkHttp.client()` 原文 875–940 行）：

- `HkCookieJar`：`.cookieJar(HkCookieJar.get(App.get()))`（SharedPreferences 持久化 + 内存表）。
  装不上直接抛、client 不缓存、下次调用可重试（星辰“不吞”口径）。
- ContentType 前后拦截器：`.addInterceptor(HkContentTypePreInterceptor.INSTANCE)`（应用拦截器）+
  `.addNetworkInterceptor(HkContentTypeAfterInterceptor.INSTANCE)`（网络拦截器，星辰同款位置）。
  前者备份 Cookie/Content-Type 头、给 gitee 补 Referer；后者按 `#noCookie#` 哨兵恢复/删除 Cookie 头。
  `fetchCookie` 的 `fetchByHiker` 全链依赖这对拦截器。
- 默认超时改 10s（connect/read/write，星辰口径）；`client(timeoutMs)` 变体保留覆盖能力。
- 显式 `.followRedirects(true) / .followSslRedirects(true)`（星辰同款，与共享 client 默认值一致，
  仅作口径锁定）。charset 头拦截器、OkProxySelector、trust-all SSL（继承共享 client）、Brotli 反射加载
  均保持原样。未搬：dnsEnd 诊断监听器（仅日志，无行为影响）。

DoH 生效路径（本步只做到 hook 就绪）：

- 取数 client 应走 `HkHttp.HK_AUTO_DNS`（命中 registerDNS 注册表的域名走注册的 DoH / 静态 IP，
  其余系统解析 + 阿里 DoH 兜底；按 lookup 时读 live `AUTO_DOMAIN` 表，一次接入后登记即时生效，
  无需反复重建）。
- 本步在 `HkHttpClient` 上加了重配入口（仅 hk 用）：`setDns(Dns)`（设覆盖 + 内含 `reset()`）与
  `reset()`（失效已缓存 client，下次 `client()` 时重建；构建时 `dnsOverride` 非空即应用）。
- **未接的一行**：真正把 `HK_AUTO_DNS` 传进来要写在 `HkHttp.applyDoh` 内
  （如 `HkHttpClient.setDns(HkHttp.HK_AUTO_DNS)`，需 HkHttp 先把该字段以包内可见方式暴露；
  或 applyDoh 内直接传）。`HkHttp.java` 不在本步 scope 内，故未改——留待 3e 门面接线或下一次
  HkHttp 改动时接上。在此之前取数 client 仍用系统 DNS（与 3b 前一致），DoH 仅作用于图片
  client（现状不变）。`setDns` / `reset` 目前全仓库零调用方（预期内）。
- 附带影响：`HkHttp.imageClient()` 经 `client().newBuilder()` 派生，会继承本 client 的
  CookieJar 与 ContentType 拦截器（超时与 DNS 由它自己覆盖：15s、HK_IMAGE_DNS、callTimeout 45s）。
  ContentType 对图片请求基本透明（无 Temp 头时直通；仅 gitee 图床请求会多一个 Referer，
  与规则取数同口径）。`HkHttp.java` 内 `client()` 的注释（847–850 行）称“CookieJar 与
  ContentType 拦截器类已随本步新建，但尚未接进任何 client”——该句现已过时，
  随 HkHttp.java 下一次改动时更新（本步 scope 外，不动）。

## 8. 前向引用清单（本步引用、别处才补；★ 为统一构建前必须解决的类级引用）

### 8.1 方法级（3b 代码调、3c/3d 补；调用数由 verify.py 在输出代码行上统计）

3c：fetchSync 11、fetchByHikerWithFlags 6、downloadToFile 4、getFilePath 3（hkMd5 /
resolveHikerPath / publicKvFile）、fetchByHiker 2、fcImpl 2、requireCacheEval 2、
batchFetchImpl 2、batchExecuteImpl 2、batchCacheM3u8Impl 2、require0 1、loadLibLocal 1、
initRequire 1（assembleFreshScope）、getMyCallbackKey 1（eval）。
3c 提供、3b 内 0 调用（由 3d 的 runScript/evalJS/parse 调用）：getMyRule、getMyType、
getMyInput、getMyJs、getMyUrl、initRhino。
3d：sel 10（parseDom/_pd 系 lambda）、jsSetTimer 2、jsClearTimer 2、clearAllTimers 2
（destroy + onDestroy）、clearTimersForRule 1（onRuleDeleted）、evalJS 1（evalConfirmJs）、
loadConfigInto 1（assembleFreshScope）、installJavaAdapterShim 1（assembleFreshScope）。
3d 提供、3b 内 0 调用：fireTimerCallback、evalJSForObj、evalLazyRule、logEvalError 等。

### 8.2 类级（★ 统一构建前必须先补齐，否则 3b–3e 完成后的统一构建仍无法编译）

| 类 | 3b 内代码引用数 | 归属 | 星辰位置/大小 |
|---|---|---|---|
| ★ `HkItem` | 37 | 阶段 5 | `api/hk/HkItem.java` |
| ★ `HkFindDelivery` | 8 | 阶段 5 | `api/hk/HkFindDelivery.java` |
| ★ `SearchResult` | 4 | 阶段 5 | `api/hk/SearchResult.java` |
| ★ `event/rule/ItemModifyEvent` | 16 | 阶段 7 event/* 按需 | `api/hk/event/rule/ItemModifyEvent.java`（101 行） |
| ★ `event/AuthStorageItem` | 13 | 阶段 7 event/* 按需 | `api/hk/event/AuthStorageItem.java`（43 行） |
| ★ `event/AuthBridgeEvent` | 8 | 阶段 7 event/* 按需 | `api/hk/event/AuthBridgeEvent.java`（107 行） |
| ★ `HkCryptoFileUtil` | 1 | 32 行小类（toBytes/toInputStream） | `api/hk/HkCryptoFileUtil.java` |
| ★ `HkSelector` | 1（implements 句） | 阶段 4 | `api/hk/HkSelector.java`（其 JsEvaluator 接口的 eval 方法已在 3b 实现） |
| ★ `HkHttpParser` | 2（getParam lambda + handlePageRequest 二参版） | 阶段 4 | `api/hk/HkHttpParser.java` |
| `event/rule/ItemFindEvent` | 2 | 阶段 7 event/* 按需 | 25 行 |
| `event/rule/ClsItemsFindEvent` | 2 | 阶段 7 event/* 按需 | 27 行 |
| `event/rule/CacheCode` | 1 | 阶段 7 event/* 按需 | 24 行 |
| `event/rule/RefreshReadDataEvent` | 2 | 阶段 7 event/* 按需 | 50 行 |
| `event/rule/RefreshVideoUrlEvent` | 1 | 阶段 7 event/* 按需 | 28 行 |
| `event/video/PlayDataUpdateEvent` | 2 | 阶段 7 event/* 按需 | 35 行 |
| `event/rule/OnRefreshX5HeightEvent` | 1 | 阶段 7 event/* 按需 | 25 行 |
| `event/rule/RefreshWebViewEvent` | 1 | 阶段 7 event/* 按需 | 19 行 |
| `event/home/SetPageTitleEvent` | 1 | 阶段 7 event/* 按需 | 22 行 |
| `event/home/SetPageParamsEvent` | 1 | 阶段 7 event/* 按需 | 22 行 |
| `event/home/SetPagePicEvent` | 1 | 阶段 7 event/* 按需 | 22 行 |
| `event/home/SetPageLastChapterRuleEvent` | 1 | 阶段 7 event/* 按需 | 22 行 |
| `event/OnBackEvent` | 1 | 阶段 7 event/* 按需 | 24 行 |
| `event/video/BackMainEvent` | 1 | 阶段 7 event/* 按需 | 11 行 |

事件 POJO 合计：3b 需 17 个、607 行（另有 `OnRefreshPageEvent` 32 行归 3d 的 refreshPage）。
另：`HkRuleManager.isCloudCode / importByCloudCode / fetchPasteRawText`（parsePaste 调用，
我方 HkRuleManager 暂无，阶段 6 云口令分发表时补）；`HkMediaCacheCleaner`
（downloadToFile 内 9333 行调用，downloadToFile 本身已留 3c，3c 接手时若该类仍不搬需把这行改空操作/删掉）。

**协调事项**：上表 ★ 的 9 类是统一构建的硬门。建议协调方裁定由哪一步补——最省事的是
3e 随门面一并搬入这批小 POJO/小类（事件 17 个 + HkItem/HkFindDelivery/SearchResult/
HkCryptoFileUtil + HkSelector/HkHttpParser 若阶段 4 未先行）。在 3e 之前，
本步的中间态不可编译是预期内的（任务书已明示）。

## 9. 验收证据

- 脚本：`/tmp/xc3b/port3b.py`（抽取 + 自校验）、`/tmp/xc3b/verify.py`（独立验证）、
  `/tmp/xc3b/inventory.txt`（方法清单）、`/tmp/xc3b/port3b-manifest.json`（段表 + 适配计数 + 手术记录）。
- 自校验（port3b.py）：输出 9739 行、191 段、registerApi 手术 43 处；适配计数与预期一致
  （package 1、App import 1、api.hk. 全限定 59、FileUtil 2、QRCode 2、ToastUtil→Notify 2、
  SourceGate 调用点 3、createClassLoader 1、installSecurityControllerOnce 1、
  import 删除 ToastUtil/SourceGate 各 1）；逐段重算比对通过；括号平衡；禁引通过。
- 独立验证（verify.py）：方法多重集比对一致（原文 inventory 549 → 输出 434，保留区 + 块内删除
  已扣除）；inventory 漏检名核查通过（20 个应有、80 个应无）；禁引核查通过
  （零 `com.fongmi.android.tv` / `SourceGate` / `GlobalProxy` / `ToastUtil`）。
- HkHttpClient：`git diff --check` 通过；调用方仅 hk 三处（§7）；`setDns`/`reset` 零调用方（预期内）。
- 逐行重读：已人工重读骨架头 130 行 diff、registerApi 关键段（toast/hkToast/refreshPage/goTo/
  back/refresh*/copyFiles/createQRCode*/registerTask）、尾部小类；确认差异只剩 §6 所列适配点。
- 未编译：本地无 JDK/Android SDK，编译靠最后统一构建验（任务书已明示）。

## 10. 其他改动

- `docs/miniapp-xingchen-2b-manager.md` 第 202 行：“在阶段 7 前走网络”→“在阶段 3 前走网络”
  （2c 已指出，3a 只改了 §4 那句，这句留给 3b）。
- 未动：`HkEngine.java` / `HkExecutor.java` / `HkRule` / `HkRuleManager` / `HkStore` /
  TV/leanback / BaseLoader / 底栏（仍 6 项）。未引入新依赖（Rhino 已是 1.7.15）。
  未引入 LitePal、dex 动态加载、Clash、XPopup 相关代码。

## 11. 遗留（未解决）

1. 中间态不可编译：§8.2 的 ★ 类级前向引用（9 类）+ §8.1 的方法级前向引用（3c/3d 补），
   统一构建前必须先补齐；需协调方裁定由哪一步补（建议 3e 一并搬入小 POJO/小类）。
2. DoH 调用行未接：`HkHttp.applyDoh` 内传 `HK_AUTO_DNS` 的一行（§7），HkHttp.java 不在本步
   scope 内，留待 3e 或下一次 HkHttp 改动。
3. `HkHttp.java:847–850` 的 client() 注释已过时（称 CookieJar/ContentType“尚未接进任何 client”），
   随 HkHttp.java 下一次改动时更新。
4. 我方 assets 暂无 `Hikerurl.js` / `plugin.js`：`getJsPlugin` / `getJsLazyPlugin` 现回 `""`；
   资产补齐是后续步骤的事（init 注入 $ 工具集时一并处理）。
5. 图片 client 继承了 ContentType 拦截器（§7 附带影响）：gitee 图床请求会多一个 Referer，
   真机观察。
6. `SecurityController` import 在输出中暂无代码引用（仅 import），javac 只告警不报错；
   3d 的 JavaAdapter 落定后再看是否保留。
