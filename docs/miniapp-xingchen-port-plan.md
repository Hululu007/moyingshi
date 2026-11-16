# 海阔小程序引擎移植计划：按星辰 + oktv 实现重写

## 0. 背景与目标

- 用户 2026-11-16 09:16–09:18 定：推倒重来，完全按星辰（+ oktv）的实现方法重写 hk 引擎，不再打补丁。原话：“别努力了推倒重来吧，你完全按星辰的代码弄吧”“你的路不通”“参考这两个的实现方法”。
- “两个软件”：oktv APK（`~/workspace/hk-ref/`，反编译源码 `src/sources/`，报告 `ANALYSIS.md`；用户 09:16 发的 Drive 文件经核对与该 APK 字节相同，SHA-256 `75106a82…07d5a8`，不必再下）与星辰 GitHub 源码（`~/workspace/xingchen-ref`，HEAD `52450c0`，包名 `com.fongmi.android.tv`）。
- 现状：我们的 hk 引擎是按公开规则文档手写的（`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/` 19 文件 + `ui/hk/` 9 文件，共 9764 行），屡次与官方行为对不上（replace 正则重载、UniqueTag 漏出、x5 卡、gitcode 418 回退等都是补丁式修法，0836 版刚又因此返工一次）。
- 目标：把 hk 引擎换成星辰实现（以星辰真源码为准，oktv 反编译源码作交叉验证），只重写 hk 引擎本身。

## 0.1 边界（不动的东西）

只搬 hk 引擎，不动：品牌与包名 `com.github.moyingshi.osc.tv`、液态玻璃导航、短剧专区、Archive.org 爬虫、本地源（LocalSource/扫目录/PHP/Python/Node 运行环境）、实验室、TV/leanback 端、设置页、播放器（Exo/ijk/mpv）、下载功能、检查更新通道。星辰独有的 Clash 代理配置界面、它自己的主题/播放器改动、WebDAV、DLAN 投屏、EPUB、首页订阅、dex 动态加载，一律不搬（见 §6）。

## 0.2 证据与版本基线（2026-11-16 核对）

| 项 | 星辰 | 我们 | 结论 |
|---|---|---|---|
| 仓库 | `XingChenApp/xingchen`，HEAD `52450c0` | `xinyonghuu/moyingshi`，HEAD `c835477e`（v5.6.0-beta-202611160836 已发布） | 以星辰 HEAD 为移植基线 |
| hk 包 | `app/src/main/java/com/fongmi/android/tv/api/hk/` 75 文件（含 browser/event/util/view 子包） | `api/hk/` 19 文件 + `ui/hk/` 9 文件 | 见 §1 映射表 |
| 引擎核心 | `HkJsRuntime.java` 14836 行；门面 `HkEngine.java` 251 行 | `HkEngine.java` 2016 行 | 核心是逐段搬运 HkJsRuntime |
| 规则模型 | `HkRule.java` 656 行；`HkRuleManager.java` 2058 行 | `HkRule.java` 220 行；`HkStore.java` 834 行 | 模型字段与存储方式都要对齐 |
| Rhino | 1.7.15 | 1.7.14 | 阶段 3 随引擎核心升版（星辰注释称 1.7.15 实测） |
| OkHttp | 5.4.0；另有 `okhttp-brotli`、`okhttp-dnsoverhttps`、`glide-okhttp3-integration` | 5.4.0 同款；无 brotli/dnsoverhttps 依赖 | 阶段 1 不加；按需评估 |
| oktv 对照 | `~/workspace/hk-ref/src/sources/com/fongmi/android/tv/api/hk/HkJsRuntime.java` 24458 行（反编译） | — | 交叉验证用，不作移植源 |

## 1. 类映射表

### 1.1 引擎核心（阶段 3）

| 星辰 | 行数 | 我们的 | 结论 |
|---|---|---|---|
| `HkJsRuntime.java` | 14836 | `HkEngine.java`（2016） | **替换核心**：JS 求值、require/requireCache、fetchSync/fetch0、isJsCode、config/initConfig/rc、refreshPage、batchFetch、storage0、setTimeout、JavaAdapter 桥、dry-run 分类等全部按星辰搬 |
| `HkEngine.java` | 251 | （无直接对应；是门面） | **新增门面**：把 HkRule + HkJsRuntime + HkSelector + HkHttp 串起来，对外保持我们现有接缝（LocalSourceDialog 点站、实验室分组、管理页、播放器） |
| `HkRule.java` | 656 | `HkRule.java`（220） | **替换/扩字段**：g22 全字段 + `resolvedUa()` 等方法 |
| `HkRuleManager.java` | 2058 | `HkStore.java`（834） | **替换存取管**：规则存取、导入/同步、缓存、版本描述；但 LitePal 调用须改写成我们的文件存储（`plugins/hk/*.json` + `sites.json`），见 §6 |
| `HkSelector.java` | 323 | `HkParser.java`（548）选择器部分 | **替换** |
| `HkCommonParser.java` | 487 | `HkParser.java` | **替换**（选择器执行，直接移植自 8.83） |
| `HkPageParser.java` | 371 | `HkParser.java` / `HkSubpage.java`（299） | **替换** |
| `HkHttpParser.java` | 858 | `HkParser.java` | **替换**（HTTP 解析） |
| `HkLazyRuleParser.java` | 326 | `HkExecutor.java` 懒加载部分 | **替换** |
| `HkParserKit.java` | — | `HkParser.java` 工具部分 | **替换/合并** |
| `HkHostClasses.java` | 83 | （无） | **新增**：宿主类在规则作用域里的可达性补齐 |
| `HkJsAdapterBridges.java` | — | （无） | **新增**：JavaAdapter 抽象类桥 |
| `HkSettingConfig.java` | 17 | （无） | **新增**：配置模型（小） |

### 1.2 HTTP 层（阶段 1 先行，见 §2）

| 星辰 | 行数 | 我们的 | 结论 |
|---|---|---|---|
| `HkHttp.java` | 1510 | （无；取数散在 HkEngine/HkExecutor） | **新增 HkHttpClient**（阶段 1：专用 client + 代理选择器链 + charset 头）；后续阶段补全 URL 模板展开等 |
| `HkHttpHelper.java` | 915 | （无） | **新增**：Req 请求构建（阶段 3，随引擎核心） |
| `HkCookieJar.java` | 142 | （无；共享 client 无 CookieJar） | **新增**（阶段 3：持久化 Cookie） |
| `HkSsl.java` | 50 | catvod `OkHttp` 内 trust-all（私有） | **新增**（阶段 3；或复用 catvod 现有实现，需把私有方法开放——以不改 catvod 为优先，hk 包内自备等价实现） |
| `HkUaEnum.java` | — | `HkEngine.resolveUa()` | **新增**：UA 枚举 |
| `HkContentTypePreInterceptor.java` / `HkContentTypeAfterInterceptor.java` | — | （无；`HkEngine.httpFetch` 自做 charset 猜测） | **新增**（阶段 3：内容类型判定） |
| `HkOkHttpStreamFetcher.java` / `HkGlideUrlLoader.java` | — | （无） | 图片链 Glide 集成，阶段 5 按需 |

### 1.3 路由 / 详情 / 搜索 / 播放（阶段 5）

| 星辰 | 行数 | 我们的 | 结论 |
|---|---|---|---|
| `HkRouter.java` | 1420 | `HkActionHandler.java`（480）+ `HkSubpage.java` | **新增路由层**：`dealKind` 分流（x5/web/pics/magnet 等）、动作 URL、子页面；替代 HkActionHandler/HkSubpage |
| `HkDetail.java` | 213 | `HkDetailHelper.java`（176） | **替换/扩**：详情数据模型 |
| `HkDetailItem.java` | — | `HkDetailHelper.java` | **替换**：详情解析出的原始条目 |
| `HkDetailBridge.java`（api/hk）/ `ui/hk/HkDetailBridge.java` | 1299（ui） | `HkPlayer.java`（84）+ 详情→播放接线 | **替换**：详情→播放器桥 |
| `HkPlay.java` | 258 | `HkExecutor.play` + `HkPlayer.java` | **替换**：播放解析结果 |
| `HkPlayData.java` | — | `HkExecutor.PlayOutcome` | **替换** |
| `HkSearchEngine.java` | 299 | `HkExecutor.search` | **替换** |
| `HkChapters.java` / `HkEpisodeDryClassifier.java` / `HkEpisodeMerger.java` / `HkEpisodeQualifier.java` / `HkEpisodeSorter.java` | — | `HkDetailHelper.java` / `HkExecutor.java` 选集逻辑 | **替换**：选集收编（干跑分类、同片多址归并、头型判定、分拣） |
| `HkFindDelivery.java` | — | （无） | **新增**：列表/搜索交付记账 |
| `HkLoading.java` | — | （无） | **新增**：preRule/showLoading UI 桥 |
| `HkErrorUtil.java` | — | （无） | **新增**：规则错误弹窗 |
| `HkM3u8ClearAdRule.java` / `HkM3u8ClearUtil.java` | — | （无） | m3u8 去广告，阶段 5 按需评估 |
| `HkLyricLoader.java` | — | `HkExecutor` 内 lyric 接线 | 按需：歌词加载（我们已有接线，对照后取舍） |
| `HkWebkitFetcher.java` | — | `HkWebRule.java`（239） | **替换**：WebView 后台抓取 |
| `HkItem.java` | — | `HkCard.java`（111） | **替换**：列表/搜索结果卡片模型 |

### 1.4 存储 / 导入 / 云（阶段 6）

| 星辰 | 行数 | 我们的 | 结论 |
|---|---|---|---|
| `HkShareHelper.java` / `HkPublishHelper.java` / `HkQrUtil.java` | — | `HkImportPicker.kt`（115）/ `HkMiniappActivity.kt`（321）/ `HkCloudImport.java`（346）/ `HkCloudExport.java`（113） | **替换**：导入导出助手（文件/目录、粘贴 JSON、同步、扫码、导出） |
| `HkCloudClip.java` | — | `HkCloudImport.java` 云口令部分 | **新增**：云剪贴板客户端；**云口令分发表按星辰重做**（云1=pastebin、云2=textdb、云5=cmd.im、云7=note.ms、云9=txtpad、云10=hastebin、云6≈pasteme、云3/4/8 不支持） |
| `HkFavorite.java` / `HkFavoriteStore.java` | — | 现有收藏（History/Favorite 体系） | **对接现有，不另建存储** |
| `HkHistoryRecorder.java` | — | 现有 History | **对接现有，不另建** |
| `HkSubscribeHistory.java` / `HkSubscribeRecord.java` / `HkHomeSubService.java` | — | （无） | 首页订阅：**延后/不搬** |
| `HkCachePurge.java` / `HkMediaCacheCleaner.java` / `HkTraceCleanup.java` | — | （无） | 清理类：按需 |
| `HkAccountPwd.java` | — | （无） | 规则账号密码模型：按需 |
| `HkAuthDialog.java` / `HkAuthManager.java` + `event/Auth*.java` | — | （无） | 敏感 API 授权：评估后搬（规则调敏感 API 时弹窗授权） |

### 1.5 UI（阶段 5 + 7）

| 星辰 | 行数 | 我们的 | 结论 |
|---|---|---|---|
| `ui/hk/HkListActivity.java` | 3268 | `HkListActivity.java`（99）+ `HkListFragment.java`（461） | **替换**：列表页，含 x5 内嵌 WebView（carrierPage / openX5PlayPage / childHasWebView） |
| `ui/hk/HkSearchActivity.java` | 748 | `HkSearchActivity.java`（112）+ `HkSearchFragment.java`（356） | **替换** |
| `ui/hk/HkDetailBridge.java` | 1299 | `HkDetailActivity.java`（89）+ `HkDetailFragment.java`（299）+ `HkDetailAdapter.java`（156） | **替换**（详情页桥接，含选集/线路） |
| `ui/hk/HkPageRecordStore.java` | — | （无） | **新增**：页面记录存储 |
| `ui/activity/HkPageActivity.java` | 8476 | （无；我们的 HkDetailActivity 极简） | **阶段 7**：巨型页 Activity（子页面/阅读器/漫画等全量页；最大单体，单独评估是否全搬） |
| `view/HkAdaptiveTextView.java` | — | （无） | 按需 |
| `event/*` 约 20 文件 | — | `HkRefreshPageEvent.java`（29） | 按需搬（EventBus 事件；refreshPage 等） |
| `util/HkCodeUtil.java` / `util/HkHikerRuleUtil.java` | — | （无） | 按需搬 |

### 1.6 明确不搬（星辰有，我们不要）

| 星辰 | 原因 |
|---|---|
| `HkProxyServer.java`（NanoHTTPD 本地代理服务器）、`HkProxyPrewarm.java`、`HkDlanController.java` / `HkDlanState.java`（投屏/局域网服务）、`browser/HkBrowserProxy*.java`（浏览器代理规则） | 星辰独有能力，与 hk 引擎无关或超出本次范围 |
| `HkWebDav.java` | WebDAV 同步，不搬 |
| `HkEpubBridge.java` | EPUB 阅读器，不搬 |
| `HkAndroidClassLoader.java`、`RetroLoadLibrary.java`、`RetroShareReflectUtil.java` | dex 动态加载：**不搬（安全风险）**；规则侧动态加载在我们这侧保持禁用 |
| `HkUpdateLogPopup.java` | 星辰原生 UI，不搬 |
| `HkCryptoFileUtil.java` | 流工具；我们已有等价，按需 |
| `HkImage.java` | 图片 @js= 求值上下文持有者；阶段 5 评估 |

### 1.7 我们多余（自研加法，移植时定去留）

| 我们的 | 结论 |
|---|---|
| `HkWarmup.java`（133） | 官方没有 warmUp：**删除**（用户已接受其存在，但移植后以星辰行为为准；如用户坚持可再加回） |
| `HkCache.java`（127，页级 LRU） | 星辰无同名类，缓存由 HkRuleManager/libs + HkFindDelivery 承接：阶段 2 确认去留 |
| `HkNonVideo.java`（302） | 由 HkRouter + HkPageActivity 承接：**删除** |
| `HkActionHandler.java`（480） | 由 HkRouter.dealKind 承接：**删除** |
| `HkDialogs.java`（207） | 由 HkLoading/HkErrorUtil/原生 Dialog 承接：**删除** |
| `HkSubpage.java`（299） | 由 HkPageParser/HkRouter 承接：**删除** |
| `HkCloudExport.java` / `HkCloudImport.java` | 由 HkCloudClip/HkShareHelper/HkPublishHelper 承接：**删除**（云口令分发表按星辰重做） |
| `HkListHelper.java`（336） | 由 HkRouter + HkItem 承接：**删除**（col_type 表按星辰 `COL_TYPES` 重做，`HkJsRuntime.java:9085-9103`） |
| `HkRefreshPageEvent.java`（29） | 对应星辰 `event/home/OnRefreshPageEvent.java`：**替换为星辰事件** |

## 2. 分阶段搬运顺序

总粗估：约 2–3 周（含编译修与真机回归；与完整版小程序最初评估的 4–6 周同一量级，这次是重写已有功能且有验收规则对照，取下限）。每阶段一个或多个 guard 会话 + 原子提交 + recovery tag；每阶段结束是可回滚点（tag 在，代码在 git 历史里）。

### 阶段 1：HTTP 层（本次委托内做，单独提交）

- 目标：hk 取数统一走一个 hk 专用 OkHttpClient，带代理选择器链；与星辰 `HkHttp.client()` 对齐到“无代理时直连等价、有代理时按代理走”。
- 改动：新增 `api/hk/HkHttpClient.java`（派生自共享 `OkHttp.client()`，继承 trust-all SSL / OkDns / 拦截器 / 30s 超时；加 charset=UTF-8 头拦截器；显式 `.proxySelector(OkHttp.selector())`）；`HkEngine.httpFetch`（约 1882 行）与 `HkExecutor` 页面抓取（约 1000 行）改走它。`HkCloudImport/HkCloudExport` 自建的 client 不动（云口令上传/下载，不是规则取数，阶段 6 再定）。
- 接缝：无 UI 接缝；`OkHttp.selector()` 的规则由现有 `ProxySetting` / `ShellProxyDialog` / `BaseConfig` 填充，不动。
- 验收：3 条验收规则取数行为 grep 零回归；无代理时与原来直连等价（同一条 client 派生）；有代理规则时走代理（代码路径核对）。
- 回滚点：本阶段 recovery tag；删 `HkHttpClient.java`、恢复两处调用即回滚。
- 不做的事：CookieJar、ContentType 拦截器、DoH、10s 超时、SourceGate 按源定向——留给阶段 3/5（见 §3a）。

### 阶段 2：规则模型与存储

- 目标：`HkRule`（656 行）与 `HkRuleManager`（2058 行）搬过来；`HkStore`（834 行）改写为文件存储版。
- 改动：新增/替换 `HkRule.java`；新增 `HkRuleManager.java`（LitePal 调用全部改写成 `plugins/hk/*.json` + `sites.json` 文件存储，见 §6）；`HkStore` 瘦身为门面或删除；`HkCache` 去留定案；`HkUaEnum`、`HkSettingConfig` 随本阶段搬。
- 接缝：`LocalSource.mergeSites()`、`LocalSourceDialog`、`LabActivity` 海阔小程序分组、`HkMiniappActivity`（管理页列表源改为 HkRuleManager）。
- 验收：3 条验收规则导入/同步/删除行为不变；4255 合集抽样规则 `validate()` 通过数不下降；`sites.json` 格式不变（不迁移用户已装规则）。
- 回滚点：本阶段 tag；`HkStore` 旧版在 git 历史里。

### 阶段 3：引擎核心（最大头）

- 目标：`HkJsRuntime`（14836 行）逐段搬运 + `HkEngine` 门面（251 行）；Rhino 1.7.14 → 1.7.15。
- 改动：新 `HkEngine.java`（门面）+ 新 `HkJsRuntime.java`（核心，分 4–6 笔搬：BOOTSTRAP/内置 API → require/requireCache/fetchSync → config/initConfig/rc/storage → 解析器回调 → 定时器/JavaAdapter/杂项）；`HkCookieJar`、`HkSsl`、`HkContentType*Interceptor`、`HkHttpHelper`、`HkHostClasses`、`HkJsAdapterBridges` 随本阶段搬；`HkSettingConfig` 若未在阶段 2 搬则补。
- 接缝：`HkExecutor` 的执行入口改为调新引擎；`VideoActivity` 的 hk 分支、`VodFragment` 首页内嵌分支不动接口。
- 验收：3 条验收规则全链路（列表/分类/搜索/详情/懒加载播放）真机回归；韩剧TV.Q replace 正则、麻花 UniqueTag、臻品fix 懒链不再需要我们的补丁（由星辰原生写法承接）；`HkJsRuntime` 内 `retStr + ''` 包装（约 12832 行）、`scriptableString`（约 744 行）原样在位。
- 回滚点：本阶段 tag；旧 `HkEngine.java` 全套在 git 历史里（不删除文件，只替换引用，出问题可整阶段回滚到 c835477e）。
- 风险：单文件 14836 行，编译错只能靠 GitHub 构建发现；每笔搬完即做一次构建，不攒到最后。

### 阶段 4：解析器

- 目标：`HkSelector`、`HkCommonParser`、`HkPageParser`、`HkHttpParser`、`HkLazyRuleParser`、`HkParserKit` 搬过来，替换 `HkParser.java`（548 行）。
- 接缝：`HkExecutor` 的 home/detail/search 解析调用点。
- 验收：3 条验收规则的选择器与 JS 两路解析结果与现版一致（卡片数/字段）；`col_type` 仍走旧表直至阶段 5 重做。
- 回滚点：本阶段 tag。

### 阶段 5：列表 / 详情 / 搜索 / 播放

- 目标：`HkRouter`（1420 行）、`HkDetail`/`HkDetailItem`/`HkDetailBridge`、`HkPlay`/`HkPlayData`、`HkSearchEngine`、`HkChapters` 与选集四件套、`HkFindDelivery`、`HkLoading`、`HkErrorUtil`、`HkWebkitFetcher` 搬过来；`ui/hk/HkListActivity`（3268 行）、`HkSearchActivity`（748 行）替换我们的列表/搜索页。
- 改动：删除 `HkListHelper`、`HkActionHandler`、`HkNonVideo`、`HkDialogs`、`HkSubpage`、`HkWebRule`（由 HkRouter/HkWebkitFetcher 承接）；`HkExecutor` 瘦身为路由调用层或删除；`HkPlayer` 由 HkPlay/HkPlayData 承接。
- 接缝：`VideoActivity` 播放入口、`VodFragment` 首页内嵌、`HkMiniappActivity` 点规则进列表。
- 验收：8 条规则（§5）全链路真机回归；x5 卡在列表页内嵌 WebView 渲染（carrierPage/openX5PlayPage）或明确降级为点开站内 WebView（0836 已有 CatWebActivity 方案，二选一写进阶段文档）。
- 回滚点：本阶段 tag。

### 阶段 6：管理 / 导入 / 云口令 / 导出

- 目标：`HkShareHelper`、`HkPublishHelper`、`HkQrUtil`、`HkCloudClip` 搬过来；云口令分发表按星辰重做；`HkAuthDialog`/`HkAuthManager` 评估后搬。
- 改动：删除 `HkCloudImport`/`HkCloudExport`/`HkImportPicker` 旧实现（由新类承接）；`HkMiniappActivity` 管理页改走 `HkRuleManager` + 新导入导出助手。
- 验收：文件/目录导入、粘贴 JSON、同步已装规则、云口令导入（云2/云5 实测）、导出拿口令再导回不重复。
- 回滚点：本阶段 tag。

### 阶段 7：收尾

- 目标：`HkPageActivity`（8476 行）评估是否全搬（子页面/阅读器/漫画全量页；如太大则只搬 hiker://page 子页面与 x5 相关部分）；`HkFavorite`/`HkHistoryRecorder` 对接现有收藏/历史；`event/*` 剩余事件搬完；`HkHomeSubService`（首页订阅）定去留；`HkM3u8ClearAdRule/Util`、`HkLyricLoader` 按需收尾。
- 验收：阅动漫 `Main.view` 子页面、`hiker://search`/`hiker://collection`/`hiker://history` 导航卡、8 条规则全回归。
- 回滚点：本阶段 tag；全量完成后打一个总 recovery tag。

## 3. 核对点 a：require / fetch 路径与代理选择器链

### 3.1 星辰的 require → fetch 链（源码位置）

- 入口 `require`（`HkJsRuntime.java:4237` 附近，`putFunctionEx("require", …)`）：本地 `libs/` 优先（`loadLibLocal`），http/file/hiker URL 走 `require0(options, libUrl, -1, options, evalCallback, true, ruleTitle, reqVersion)`，失败软回 null（入口 catch 收口）。
- `require0`（`HkJsRuntime.java:12965-13108`）：file/hiker 协议走 `fetchByHiker`；http(s) 走本地缓存 `libs/<md5(url)>.js` + 版本文件 `<md5(url)>.json`，有效性按 `expireHours`（<0 只比版本，==0 永远重下，>0 按 `3600000*hours` 过期）；缓存无效时调 `requestForCode(url, fetchOpts)`。
- `requestForCode`（`HkJsRuntime.java:14015-14046`）：`fetch0(url, generateRequestOptions(headersOptions, false), null, null)` 取回后按 JSON `{error, statusCode, body}` 判定：error 非空或 statusCode 不是 2xx 即回 null。
- `fetchSync`（`HkJsRuntime.java:11758` 起）：经 `HkHttp.to(url, req)` / `HkHttp.toRaw(url, req, rawBody)` 取数；`HkHttp.to()` 的注释（`HkHttp.java:945`）写明“客户端换成 8.83 配置的 `HkHttp.client()`”。
- `HkHttp.client()`（`HkHttp.java:875-940`）：DNS `HK_AUTO_DNS`、trust-all SSL（`HkSsl`）、charset=UTF-8 公共头拦截器、ContentType 前后拦截器、超时 10/10/10 秒、跟随跳转、反射加载 Brotli 拦截器、`HkCookieJar`，以及代理选择器链（`:935`）：
  `b.proxySelector(SourceGate.wrapSelector(GlobalProxy.wrapSelector(java.net.ProxySelector.getDefault())));`
  图片 client 用同款链的确定归属版（`:839`，`wrapSelectorNoGuess`）。
- `GlobalProxy` 与 `SourceGate` 是 `com.github.catvod.net` 包的类（`HkHttp.java:10-11` import），在星辰仓库里以编译依赖形式存在（仓库内无源码）；oktv 反编译源里也没有这两个类（只有调用点）。`GlobalProxyDialog`（星辰 `ui/dialog/GlobalProxyDialog.java`）是应用级全局代理配置界面，不属于 hk 引擎。

### 3.2 无代理时是否直通（等价性结论）

- **是，直通。** `GlobalProxy.wrapSelector(default)` 在未配置全局代理时返回直通下层选择器的包装；`SourceGate.wrapSelector` 在无源定向裁决时原样返回下层选择器；链路退化为 `ProxySelector.getDefault()`，即直连。旁证：`HkHttp.java:974-980` 注释“判直连/无裁决时原样返回 `client()`，选择器链行为不变”；以及星辰图片 client 的注释（`:826-838`）说明全量版只在“会话绑定与 15 秒近期回退”这类代理已配场景才起作用。
- 诚实限定：`GlobalProxy`/`SourceGate` 本体是编译产物（catvod 新版 AAR），未逐字节反编译；上述等价性是从两边代码的行为与注释推断的，不是本体源码逐行验证。移植阶段 3 如需按源定向，再以反编译或行为测试确认。

### 3.3 我们项目里的对应实现（已纠正任务简报的一处误判）

- **任务简报称“复用项目已有的 catvod GlobalProxy”是误判：我们仓库全量 grep `GlobalProxy` / `SourceGate` 零命中。** 我们的 catvod 快照早于这两个类，不存在可复用的 `GlobalProxy`。
- 我们的等价物是 `OkProxySelector`（`catvod/src/main/java/com/github/catvod/net/OkProxySelector.java`）：规则列表（`Proxy` bean，含 host 匹配规则）+ 空规则时 fallback 系统选择器（`select()`：`proxy.isEmpty()` → `fallback(uri)` → `system.select(uri)`，系统默认即 DIRECT）；已接入共享 `OkHttp.client()`（`OkHttp.java:247` `builder.proxySelector(selector())`），规则由 `ProxySetting.java:44`、`ShellProxyDialog.java:583`、`BaseConfig.java:99` 的 `OkHttp.selector().addAll(...)` 填充。另有实验室全局代理（`LabConfig` 的 `getGlobalProxy/getGlobalProxyPort/getGlobalProxyNoProxy`，只作用于运行环境，不进 OkHttp 选择器）。
- **结论：我们 hk 取数今天已经走代理选择器链。** 与星辰在 HTTP 层的唯一实质差别不是“有没有选择器”，而是三点：(1) 星辰是 hk 专用 client（CookieJar / charset 头 / 10s 超时 / ContentType 拦截器 / DoH），我们是共享 client（30s 超时、无 CookieJar、无 charset 头、无 ContentType 拦截器）；(2) 星辰多了按源定向的 SourceGate 层（会话绑定 / 15 秒近期回退——只在配了代理且多源时才起作用）；(3) 星辰的 GlobalProxy 开关是应用级“全局代理”（Clash），我们的是按规则的 ProxySetting + 实验室全局代理，入口不同但机制同源。
- 阶段 1 的做法（见 §2 阶段 1）：新建 `HkHttpClient`（hk 专用 client，派生自共享 `OkHttp.client()` 以继承 trust-all SSL / OkDns / 拦截器 / 超时策略），加 charset=UTF-8 头拦截器，显式 `.proxySelector(OkHttp.selector())`。SourceGate 不抄：其按源定向只在代理已配时生效，hk 取数在无代理时与直连等价；等阶段 3 搬引擎核心时再评估是否需要按源定向。

## 4. 核对点 b：gitcode 418——星辰 / oktv 有没有过墙手段；两库有无公开镜像

### 4.1 结论：没有过墙手段（源码确认）

- 星辰 require 链（§3.1）中：**无重试**（`HkJsRuntime.java` 全文件 grep `retry` 零命中；`fetchSync` / `fetch0` 均无重试循环）、**无镜像**、**无备用域名**、**无特殊 UA**（UA 由 `generateRequestOptions` 按规则声明补，默认移动 UA；`fetchSync` 约 11810–11850 行只在规则声明了 ua 时注入，未声明的不碰）。
- 失败时的全部出路（`require0`，`HkJsRuntime.java:13070-13108`）：本地无缓存 → 抛“获取远程依赖失败：<url>”；本地有缓存 + 新内容 `!isJsCode(code)` → 回退本地旧版；`hiker.nokia.press` / `gitee.com` 的坏内容（空 / 过短 / 含“非法猎鹰”/“The Repository is invisible”）→ 回退本地；否则落盘新内容。`isJsCode`（`:14052`）：空、HTML/XML 前缀/后缀 → false；含 `var ` / `let ` / `function` / `eval(` 等关键字或方法名 → true。
- 唯一的“代理”是两条，且都不解决无配置下的 418：(1) client 级代理选择器链（用户配了全局代理才生效）；(2) require 的 `options.proxy` 字段经 `proxyRequire` → `proxyUrl`（`HkJsRuntime.java:13991-14008`）做的 **URL 字符串替换**（行格式 `key=>replacement`，URL 含 key 时整段替换）——规则必须自己传 proxy 选项；两条出事规则的 preRule 都是裸 `require(url)`，未传。
- oktv 同款（`~/workspace/hk-ref/ANALYSIS.md` 已证实，交叉验证）：require 是普通 OkHttp；418 时 `isJsCode` 失败 → 静默回退磁盘旧版库；全新安装无缓存一样拿不到。反编译源佐证：`HkJsRuntime.java:4174` isJsCode、`:2555` require0 调用点、`:9248` / `:24458` 回退点。

### 4.2 两库有无公开镜像：无（实测）

- `qq_32394351/dr` 的 `js/预处理.js`（星辰影视123.Dr 要的库）：`raw.githubusercontent.com/hjdhnx/dr`、 `github.com/hjdhnx/dr`、`gitee.com/hjdhnx/dr`、`cdn.jsdelivr.net/gh/hjdhnx/dr` 全部 404。`hjdhnx/dr_py` 在 GitHub 存在，但“dr”库不在其下——“dr”与“dr_py”是两个仓库，不要混淆。
- `m0_73099089/btea` 的 `webpre.js`（青苹果♨️ 要的库）：GitHub / Gitee 同款四处全部 404。
- 两次定向搜索（“webpre.js btea”“qq_32394351/dr 预处理.js 镜像”）无命中。生态报告（hikerskill `eco-corpus-distillation.md`）称预处理.js 是 400+ 规则共享的底座，托管地含 gitcode / coding / gitlab / gitea / codeberg / jsdelivr——但具体到这两个库，未找到除 gitcode 外的托管。
- git 协议同样被拦（父代理 2026-11-16 已测 `git ls-remote` 也是 418）。
- **结论：无公开镜像，无法预置进 require 缓存；不要编一个。** 这两条规则要出数据，唯一路径是用户网络下某次取到一次（或用户配代理），之后缓存回退才能顶着用。**移植后首次仍出不了数据**——这是唯一不能靠移植解决的验证点，必须向用户说清，不要把移植说成能修 418。

## 5. 核对点 c：8 条规则的移植后承接与验证点

| 规则 | 类型 / 关键写法 | 移植后承接的星辰类 | 验证点 |
|---|---|---|---|
| 奇优影院♔ | selector find_rule，movie_3，搜索 `;post` | `HkSelector` + `HkCommonParser`（列表）、`HkSearchEngine`（搜索）、`HkDetail` / `HkPlay`（详情懒加载） | 首页出卡、分类切换、搜索、详情、懒加载播 |
| 童话 | selector，movie_3，详情自包含 JS | 同上 | 首页、详情（不走网络的 JS 详情） |
| 臻品fix | JS find_rule（`getResCode()` + parse），懒加载 `@lazyRule=.js:eval(...)` | `HkJsRuntime`（JS 求值）+ `HkLazyRuleParser` + `HkPlay` | 首页、详情、懒链、多线路（如有） |
| 韩剧TV.Q | JS find_rule，`.replace(/type\/(.*?)\//, ...)` 落在 Java 字符串上 | `HkJsRuntime`（STRING 型方法包装 `retStr + ''`，约 12832 行；0836 用的 `__jsStr` 补丁移植后不再需要，由星辰原生包装承接） | 列表出卡（find_rule#19 不再报重载不明确） |
| 麻花影院 | x5 卡，无 title，desc `100%&&float` 是规则自写 | `HkJsRuntime.scriptableString`（约 744 行：`NOT_FOUND` → `""`；0836 的 7 处守卫移植后由原生写法承接）+ `HkListActivity` x5 内嵌 WebView（阶段 7；阶段 5 前先由 0836 的 CatWebActivity 顶替） | 标题显示“网页”不再漏 UniqueTag；点卡进网页 |
| 西瓜影院 | `hiker://empty` + `hiker://page/home`，JS 首页 | `HkRouter`（子页面）+ `HkPageParser` + `HkJsRuntime` | 首页出“网页”卡；点开进站内 WebView / 子页面 |
| 星辰影视123.Dr | preRule require gitcode `qq_32394351/dr` 预处理.js | `HkJsRuntime.require0` + require 缓存（`libs/<md5>.js`） | **gitcode 418 下移植后首次仍失败（无缓存）**——见 §4.2；只能验报错是否清晰、缓存回退是否工作 |
| 青苹果♨️ | preRule `initConfig` + `rc(config.Btea)`（gitcode `m0_73099089/btea` webpre.js） | `HkJsRuntime` initConfig / config accessor / rc + `require0` 缓存 | 同上：首次仍 418；只能验 initConfig 不再报未定义、报错是否清晰 |

## 6. “完全按星辰的代码”的工程含义与边界

### 6.1 包名改写点

- `com.fongmi.android.tv` → `com.github.moyingshi.osc.tv`：源码级替换。`R` 资源、`BuildConfig`、Manifest action 前缀 `${applicationId}` 都走我们的 applicationId；星辰代码里硬编码的 `com.fongmi.android.tv` 字符串要扫一遍（重点是 `HkJsRuntime` 注释里的类名引用与 `HkHostClasses` 的宿主类表）。
- 资源：星辰 hk 相关的 layout / drawable / string 只搬 hk 引擎需要的；主题色、启动图标、导航样式一律用墨影视现有的。

### 6.2 App 类接缝

- `App.get()`（Application 单例）：星辰的 `App` 与我们的 `App` 同名不同包，方法签名逐一核对（`getExternalFilesDir`、`get()` 等）。
- `HkSettingConfig`（17 行）：星辰的 8.83 设置模型；只搬 hk 引擎读到的字段，其余走我们现有 `Setting`。
- 存储：`HkRuleManager` 用 LitePal（`LitePal.where("title = ?", …)`，见 `HkJsRuntime.java` require0 的 hiker://page 分支注释）——**我们不用 LitePal**，阶段 2 全部改写成 `plugins/hk/*.json` + `sites.json` 文件存储；`sites.json` 格式不变，不迁移用户已装规则。
- `EventBus`（greenrobot）：两边都有，直接用。
- `Glide`：星辰图片链用 Glide + `okhttp3-integration`；我们 hk 图片目前走 Vod.pic 直显，阶段 5 核对是否需要接 Glide。
- `NanoHTTPD`：只被 `HkProxyServer` 用，不搬则不需要。
- `OkGo`：星辰注释里提到的 8.83 公共头（charset=UTF-8）已在 `HkHttp.client()` 里用 OkHttp 拦截器实现，不引入 OkGo。

### 6.3 依赖核对（阶段 3 / 5 按需）

- Rhino 1.7.14 → 1.7.15（阶段 3 随引擎核心升版）。
- `okhttp-brotli`、`okhttp-dnsoverhttps`（星辰有，我们没有）：按需加；阶段 1 不加（Brotli 拦截器是反射加载，缺失则跳过）。
- `glide-okhttp3-integration`：阶段 5 核对。

### 6.4 不搬清单（再确认）

品牌/包名、玻璃导航、短剧、本地源、实验室、TV 端、设置页、播放器、下载、检查更新、Clash 代理配置界面、WebDAV、DLAN 投屏、EPUB、首页订阅、dex 动态加载、更新日志弹窗。**只有 hk 引擎换血，其余一切照旧。**

## 7. 风险与诚实限定

1. **移植不修 gitcode 418。** 两条出事规则（星辰影视123.Dr、青苹果♨️）在移植后首次仍出不了数据（§4.2）。不要向用户承诺移植能修这两条；能修的是引擎对不上官方的那一类问题。
2. **`GlobalProxy` / `SourceGate` 本体未逐行验证。** 两者是编译产物（catvod 新版 AAR），仓库内无源码；§3.2 的直通结论来自行为与注释推断。阶段 3 如需按源定向，再以反编译或行为测试确认。
3. **星辰代码本身带大量“体检批 / 复核订正”注释与星辰私有修法**（如 `HkJsRuntime` 里 2026-10/11 月的真机实证补丁）：搬运时要分清哪些是 8.83 官方行为、哪些是星辰针对自家真机问题的修法；后者只搬与 hk 引擎行为有关的，不搬星辰 UI/播放器相关的。
4. **HkJsRuntime 单文件 14836 行**：编译错只能靠 GitHub 构建发现；阶段 3 每笔搬完即做一次构建，不攒到最后。本地无 JDK/Android SDK，提交前逐行重读是唯一本地检查。
5. **HkPageActivity 8476 行**：阶段 7 再评估是否全搬；如太大，只搬 hiker://page 子页面与 x5 相关部分。
6. **LitePal 改文件存储**是阶段 2 的最大改写风险点：`HkRuleManager` 的查询语义（按标题取规则、版本比对）要逐一对上文件实现，验收以 3 条验收规则 + 4255 合集抽样为准。

## Recovery anchor

- 目标：按星辰 + oktv 实现重写墨影视 hk 引擎；本次委托 = 评估文档（本文档）+ 阶段 1 HTTP 层落地，做完即停。
- 基线：本地 main `c835477e`（已推，v5.6.0-beta-202611160836 已发布）；星辰 HEAD `52450c0`；oktv APK SHA-256 `75106a82…07d5a8`。
- 已完成：本文档；待做：阶段 1（`HkHttpClient` + 两处取数点改走它，单独 guard 会话 + 原子提交 + recovery tag）。
- 下一步：阶段 2（规则模型/存储），见 §2。
