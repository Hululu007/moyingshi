# 阶段 5e：点击分流改走新引擎 + 删除旧链路 + col_type 重做 + 3e 缺口收尾

> Recovery anchor — 目标：阶段 5 最后一笔。点击分流（列表/搜索/详情三处）改走常驻引擎
> `HkRouter`/`HkJsRuntime.evalLazy`；删除旧链路 `HkExecutor`/`HkListHelper`/`HkActionHandler`/
> `HkDialogs`；`col_type` 样式表按星辰 `HkColType` 重做；3e 适配段缺口逐项复核、可恢复的恢复；
> `proxyClearM3u8` JS 函数接线；子页面 `hiker://page/` 点开展新列表页。
> 验收：老链路调用点清零（grep 对照）、`git diff --check` 通过、0 leanback、禁入代码零命中、
> 8 条规则全链路代码级核对。只做阶段 5，做完即停，不开阶段 6；不推、不编、不触发 workflow。
> 状态：实现已做完（2026-11-19），逐行重读通过（重读时修掉 12 处孤儿 `@Deprecated` 注解与 1 处过时注释，见 §7 末段）；
> 待 `task_guard.sh finish` 提交；HEAD `f2d5aa3d`（5d）。下一步唯一动作：finish 提交并打 recovery tag。

- 完成句：阶段 5 收尾——点击分流改走新引擎、旧链路删除、col_type 按星辰重做、3e 缺口收尾，提交后阶段 5 完工。
- 允许路径：`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/*`、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/*`、`docs/miniapp-xingchen-5-*.md`。
  例外（只改 hk 分支调用点，不动播放器本身）：`ui/activity/VideoActivity.java` 不动——5e 不需要改它。
  `HkListActivity`/`HkListFragment`/`HkSearchFragment`/`HkDetailFragment` 在 `ui/hk/` 内，属允许路径。
- 保护路径：无初始脏文件（`git status` 干净，HEAD `f2d5aa3d`）。
- 本地时间：2026-11-19 16:33 CST（Asia/Shanghai；VM 时间 08:33 UTC）。
- 预计：文档已完；实现约 2.5–3 小时；验收约 30 分钟；预计 20:00 前提交。
- 最便宜的决定性验证：`grep -rn "HkExecutor\.\|HkListHelper\.\|HkActionHandler\.\|HkDialogs\."`
  代码命中为零 + `git diff --check` 通过 + 删除文件全树零引用复核。

## 1. 来源与范围

- 任务书：阶段 5 最后一笔（5a 路由+详情+播放核心、5b 搜索+选集+FindDelivery、5c 列表/搜索 UI、
  5d 详情+播放接线已提交；本笔 5e 收尾）。计划文档 `docs/miniapp-xingchen-port-plan.md` §2 阶段 5、
  §1.3、§5；3e 文档 `docs/miniapp-xingchen-3e-facade.md` §6.2/§7；仓库规约
  `~/workspace/projects/Mooyingshi/AGENTS.md`（guard 会话先行、一个 guard 会话等于一次原子逻辑提交、
  提交即打 recovery tag、任务文档先行、只改声明的路径）。
- 星辰源码：`~/workspace/xingchen-ref`（HEAD `52450c0`）；交叉验证 `~/workspace/hk-ref/src/sources/`（oktv
  反编译）；语义参考 `~/workspace/hikerview-ref` 的 JSEngine。
- 本笔不做：阶段 6（云口令两法、HkCloudClip 等）、阶段 7（HkPageActivity、剩余 event、
  `getNextPage` 子规则列表、refreshPage 事件桥、ConfirmHandler 接管）——只在 §8 记名。
- 5d 文档数字订正（本笔顺手）：`docs/miniapp-xingchen-5d-detail-play.md` §8
  “本文档新增 199 行”→ **214 行**（`wc -l` 在填 §8/§9 之前量的，之后又改了文档；与 5c 的
  +143→+128 订正同款处理）。

## 2. 设计决定（每项都有 grep/代码证据，不是推测）

### 2.1 点击分流：新类 `HkClickDispatcher`（api/hk，新文件，替代 `HkActionHandler`）

- 旧 `HkActionHandler.handle(Host, HkCard)` 的分发语义逐项保留，只换求值引擎与分发落点：
  `handle()` 入口不变（主线程调；返回 true=已接管、false=普通 URL 走原路）。
- 顺序（与旧版一致，新增两支）：
  1. `@lazyRule=`/`@input=`/`@rule=` 优先于裸 scheme（臻品fix 的 tab 卡是
     `hiker://empty@lazyRule=…`，`@` 后半段才是动作）→ `executeLazy`；
  2. 裸 scheme：`toast://`/`copy://`/`download://`/`input://`/`confirm://`/`select://`/
     `hiker://empty`（原样保留，见 §2.2 的弹框搬迁）；
  3. **新增：裸 `hiker://page/…` → `host.openSubpage(url, title)`，开新列表页，不进详情**
     （旧版返回 false → 详情页 → `Router.detail` 判 `lazyParseFailed` → 错误态；星辰
     `HkRouter.detail:113-116` 与我方 `HkRouter.detail:114-116` 同款注释：
     “子页面不进 V4（应由 openPage 打开新列表页）”；星辰等价路是
     `HkListActivity.openPageUrl`，见 §2.7）；
  4. **新增：裸 `x5://…` → 剥前缀后进 `CatWebActivity`**（实现时先读 5d 详情页支再落笔，
     发现 5d 支有潜 bug：它把 URL 原样传给 `CatWebActivity.intent`，而后者原样调
     `webView.loadUrl(url)`，`x5://` scheme 进 WebView 加载失败；星辰 `handleDealUrl` 的
     x5 支是剥前缀的（`url.replaceFirst("(?i)^x5://", "")`）。**本笔顺手修**：分发器新支与
     详情页 `handleDealKind` 的 x5 支都剥前缀（web 支原样）；麻花影院不受影响——它的卡是
     `x5_webview_single` colType + http URL，走 Fragment 的 `isX5Url` 支）；
  5. 其余返回 false：`x5_webview_single` 卡仍由 Fragment 的 `isX5Url` 支接
     （`CatWebActivity`，5c 已定）；普通 URL 进详情页（`Router.detail` 的
     `dealKind`/`directPlayUrl` 分流在 5d 已落到详情页）。
- `Host` 接口换血（`HkActionHandler.Host` 的 7 方法 → 7 方法）：
  `activity()`/`rule()`/`pageUrl()`/`reloadPage()`/`openDetail(url, title, pic, desc)` 保留，
  **新增 `router()` 与 `openSubpage(url, title)`**，**删除 `vars()`/`updateVars()`**。
  理由：新运行时 vars 在进程级 `varMap` 共享（3e `prepare` 语义），懒求值与详情重跑读同一张表，
  不再需要 Fragment 的 `mVars` 快照中转；`HkDetailFragment.mVars` 字段随之删除
  （`loadDetail` 已走 `router.detail`，不再传 `initialVars`；5d 前的“下次重跑详情预填 vars”
  旧契约由 `varMap` 承接）。列表/搜索页的 `emptyVars()` 随之死亡。
- `runLazy` 改走常驻引擎（替代 `HkExecutor.evalLazy`，逐项对齐）：
  - 入口：`Host.router()`（新增，返回 Fragment 已有的 `mRouter`，为空时 toast“引擎未就绪”并放弃；
    卡片可点时 `mRouter` 已在 `loadPage`/`loadDetail` 里建好，空值只是防御）；
  - `.js:`/`js:` 路：`jsRuntime.setNextInputOverride(input)` + `jsRuntime.evalLazy(js, pageUrl, -1)`
   （`HkEngine.evalExpression` 同款；`Router.evalTab:684` 同款；`position=-1`＝官 parse 无
    position 形的缺省实参）；
  - 选择器路：`HkRuleFetch.fetch(HkRuleFetch.buildDetailRequest(rule, pageUrl))` 抓页（非 http(s)
    不抓，空页跑选择器，与旧 `evalLazy` 同款 guard）+ `engine.getSelector().evalField(pageText, js, page)`；
  - 不重跑 `preRule`（常驻引擎在页面加载时已跑过；重跑会重放青苹果♨️ `initConfig`+`rc` 等副作用，
    `evalTab` 也不重跑）；
  - `refreshRequested`：求值后 `jsRuntime.consumeRefreshRequest()`（`evalTab` 同款；读后清零，
    不误判残留标记）；
  - `dialogHost` 删除：新运行时 JS 的 `confirm/select/input` 走 `authGate`/`AuthBridgeEvent`
    授权链，不 consult `DialogHost`（3e 表）；`HkDialogs` 的同步 latch 版随之死亡（§2.2）。
  - 仍在后台线程跑，回来主线程分发（`App.post`，与旧版一致）。
- `dispatchLazyResult` 改写（类型变了，分支语义不变）：
  - 入参由 `LazyOutcome` 改为 `(result String, refreshRequested boolean, error String)`；
  - `webRule://` 支：仍走 `HkWebRule.resolve`（见 §2.3 保留决定），递归重分发、只许一次
    （`webRuleDone` 旗不变）；`stripHashMarkers` 随分发移入新类作私有静态方法（不在 `HkRuleFetch` 里——
  该类只放请求工具）；
  - `toast://`/`hiker://empty` 支：`refreshRequested` 时 `reloadPage()`（位置不变）；
  - `copy://`/`download://` 支：原样（`handleCopy`/`handleDownload` 逐字移入）；
  - `pics://` 支：仍 toast `hk_action_pics_unsupported`（旧行为；直接进阅读器是阶段 7
    `handleDealUrl` 的活，不在本笔提前搬）；
  - `{` 多线路 JSON 支：`firstMultiLineUrl` 取首条 → `openDetail`（原样）；
  - **http(s) 与子页面支拆开**：`http(s)` → `openDetail`；`hiker://page/` →
    `host.openSubpage(result, title)`（旧版两者都进 `openDetail`，子页面会撞上
    `Router.detail` 的 `lazyParseFailed`；拆开是本笔子页面闭环的关键）；
  - 其余：记日志，不 toast、不跳转（原样）。
- `stripNowSuffix`/`unescapeLazy`/`stripHashMarkers`/`stripLeadingHashMarkers`/
  `firstMultiLineUrl`/`indexOfLazy`/`lazyMarker`/`cleanTitle`/`fileNameOf`/
  `showToastText`/`copyToClipboard`/`handleCopy`/`handleDownload`/`enqueueDownload`/
  `handleInputBare`/`handleConfirmBare`/`handleSelectBare`：逐字移入新类（`enqueueDownload`
  仍走 `VideoDownloadManager.enqueue` 直链透传，隐藏下载通道不动）。
- `dialogHostFor` 随 `DialogHost` 一并删除（§2.5）。

### 2.2 `HkDialogs` 删除，异步弹框移入新分发类

- 调用方全量 grep：`HkDialogs.` 代码命中只剩 `HkActionHandler`（`dialogHostFor` 的 3 处同步版 +
  `inputAsync:172/:408` + `confirmAsync:426` + `selectAsync:462`；VideoActivity 的 3 处已在 5d 删除）。
- 删除后零引用 → 删文件。异步三法（`inputAsync`/`confirmAsync`/`selectAsync`，含 `Once`/
  `onMain`/`usable`，仍用 `WebHtvAlertDialogBuilder` + `dialog_action_input` 布局与
  “空输入不关框”规则）逐字移入 `HkClickDispatcher` 作私有静态方法；弹框行为一字不变。
- 同步三法（`input`/`confirm`/`select`）与 `awaitResult` latch 整段删除：唯一用途是旧 Rhino
  桥的 `DialogHost`（后台线程等用户点框）；新运行时 JS 的 `confirm` 走
  `HkJsRuntime.ConfirmHandler`（`HkJsRuntime:721-727`），无人接管（见 §2.11）。

### 2.3 `HkWebRule` 保留（任务书删除清单的逐项复核结论：不删）

- 调用方只剩 `HkActionHandler.dispatchLazyResult` 的 `webRule://` 支（`HkWebRule.resolve`）。
- 不删的理由：它是全树唯一注入 `fy_bridge_app.finishParse` 的嗅探器（隐藏 WebView + 轮询脚本，
  239 行）；5a 搬入的 `HkWebkitFetcher`（`Router.resolveWebRule` 用）明确不注入该桥，
  依赖桥的嗅探式规则在它手里会回空（5a 文档已记降级）。新类无人承接桥注入行为，
  删它等于删一条解析路。`HkWebRule.resolve` 在新分发类里继续服务 `webRule://` 懒结果支。
- 附带：`Router.play` 的 `webRule://` 支已在 5a 走 `HkWebkitFetcher`（接受降级），与本支无关。

### 2.4 `HkSubpage` 保留并瘦身（任务书删除清单的逐项复核结论：不删文件，删死方法）

- 保留的理由（grep 实证）：`expand(rule, pageUrl)` 是全树唯一的子页面渲染器（新引擎
  `HkJsRuntime` 内部只处理 JS 侧的 `require('hiker://page/…')`/`fetch('hiker://page/…')`
  ——`handlePageRequest:3286`、`require` 页规则查找 `:6566`——不提供 Java 侧“展开一页成卡片列表”
  的能力）；5e 的子页面列表页（§2.7）与 `Router.play` 的子页面选集支（§2.9）都调它；
  它本身已跑在新引擎上（`new HkEngine + prepare + evalPreRule + evalFindRule`，3e/4 的门面与
  解析器），不是旧链路。
- 删除（死代码，零调用方；删后 `HkEngine` 的对应适配方法随之可删，见 §2.5）：
  `require()`/`requireOne()`（`$.exports` 优先分支的唯一消费方）/`pageJson()`（JS 侧
  `fetch('hiker://page/…')` 已由 `HkJsRuntime.handlePageRequest` 承接）/
  `REQUIRE_DEPTH`/`MAX_REQUIRE_DEPTH`；`setDialogHost` 抄送行与 `Undefined` import 随之清理
  （实现时逐行核对；`jsBody` import 并不存在，初稿误写）。
- 保留：`PREFIX`/`isSubpageUrl`/`Parsed`/`parse`/`subpageName`/`parseQuery`/`pagesOf`/
  `findPage`/`pageString`/`titleOf`/`expand`/`findPlayableUrl`，另加
  `pageColType(rule, pageUrl)`（子页面列表页定样式用，见 §2.7；读页面对象的 `col_type` 字段，
  空则回落规则 `col_type`）。
- 类注释更新：调用方由 `HkExecutor.detail` 改为 `HkListFragment` 子页面模式 +
  `HkRouter.playRoute` 子页面支；`require` 段的注释随方法删除。

### 2.5 `HkEngine` 适配段瘦身（3e 表“阶段 5 删除/移除”的兑现）

- 调用方全量 grep（排除 `HkEngine.java`/`HkExecutor.java`/`HkSubpage.java`/`HkActionHandler.java`
  四个死亡/瘦身文件后，剩余命中只剩注释与 `HkRouter.java:320/323` 两处代码调用）：
- 删除：`wasResultSet()`、`getResultCards()`、`getExports()`（恒回 `Undefined`）、
  `jsValueFromCards()`（回 JSON 字符串）、`setImportParam(Object)`（单数；复数版保留）、
  `getVarsSnapshot()`（恒回空表）、`setRefreshEventEnabled()` + `refreshEventEnabled` 字段、
  `wasRefreshRequested()`、`setDialogHost()`/`getDialogHost()` + 内嵌 `DialogHost` 接口 +
  `dialogHost` 字段、`evalExpression()`、`eval(Object, String)`、`importParam` 字段（单数）、
  `lastResultCards`/`resultSetFlag` 字段（只剩 `evalFindRule` 写、`wasResultSet`/
  `getResultCards` 读；两者既删，字段与 `evalFindRule` 内的写值行一并删）。
- 保留（仍有 live 调用方）：`prepare`（5/6 参）、`evalPreRule()`、`evalFindRule()`、
  `evalTextRule()`、`getSelector()`、`setImportParams(Map)`（复数；`HkSubpage.expand:213`
  在 `prepare` 之后调，运行时已存在，可直通转发，见 §3 ③）、`resolveUa`（未标
  `@Deprecated` 的静态工具，`HkRuleFetch.buildDetailRequest` 用）、`HkException`、
  门面方法（`home/search/detail/play` 等）、`getError()`、`getJsRuntime()`、
  `getDialogHost` 的删除不影响 `HkJsRuntime.ConfirmHandler`（另一条线）。
- `HkRouter.computeLastChapter` 里的 `engine.setRefreshEventEnabled(false)` 调用随方法删除而删除
  （该调用本就是空操作：新运行时 `refreshPage` 无门控、恒投 `OnRefreshPageEvent`，而全树
  无该事件订阅方——5d 已核实 `HkRefreshPageEvent.post` 零调用；装饰性求值不发事件的意图
  由“无人订阅”承接，不另设门控）。
- `eval()` 删除后 `HkEngine.java:543` 注释里“HkSubpage.requireOne 的 fallback 仍可用”一句随之失效，
  注释一并改掉。

### 2.6 新文件 `HkRuleFetch`（api/hk，`HkExecutor` 私有请求工具的逐字移入）

- 旧 `HkExecutor` 的请求三件套在新链路仍有两处调用方（`HkNonVideo.sdetail`、新分发类
  `runLazy` 的选择器路），不能随文件陪葬；`HkParserKit` 是逐字移植文件（头注“除包名外与原文一致”），
  不许往里加自有方法；`HkHttp` 只有 `get(url, ua)` 两形，无 headers 版。
  故新开自有工具类 `HkRuleFetch`（final，私有构造），逐字移入：
  `RequestSpec`（内嵌静态类）/`buildDetailRequest(rule, url)`/`fetch(spec)`（GET/POST、
  按 `spec.charset` 解码，失败抛 `HkException` 带 URL 与原因）/`parseFirstHeader`/
  `parseJsonHeaders`/`charsetFor`/`isHttpUrl`。`HkEngine.resolveUa` 引用保留。
- `buildHomeRequest`/`buildSearchRequest`/`splitUrlModifiers`/`UrlModifiers`/
  `replacePagePlaceholders`/`applyPageOp`/`classIndexFor`/`classNameFor`/`firstId`/
  `isCharset`/`bracesBalanced` 不移入（只服务已死的 `homeDetailed`/`search`，随文件删除）。

### 2.7 子页面列表页（`HkListFragment` 子页面模式 + `HkListActivity.openSubpage`）

- 星辰等价路：`HkListActivity.openPageUrl(rule, pageUrl, position)` 经
  `HkPageParser.getNextPage` 组子规则开新列表页（`xingchen-ref` :2607-2660）。
  我方 5e 不走这条路：`getNextPage` 产出的子规则无法经 Intent 传递（`HkRule` 未实现
  `Serializable`/`Parcelable`），且子规则的分页/分类需要整套新接缝；那是阶段 7
  `HkPageActivity` 的活。本笔走旧行为对等路：子页面模式仍由 `HkSubpage.expand` 渲染
  （单页，与旧 `HkExecutor.detail` 的子页面支行为一致），只把承载面从详情页换成列表页。
- `HkListActivity.openSubpage(Context, title, pageUrl)`：extra `EXTRA_TITLE`（规则标题，
  仍供 `HkStore.read`）+ `EXTRA_SUBPAGE_URL`；工具栏标题取
  `HkSubpage.subpageName(pageUrl)`；子页面模式下搜索项隐藏（`mSearchable=false`；
  子页面内搜索无定义，搜主规则会串内容）。
- `HkListFragment.newInstanceSubpage(String title, String pageUrl)`：args 增 `subpage_url`；
  `bindRuleLoaded` 里子页面模式跳过 `setClasses()`（显式隐藏分类栏，`mClassId=null`），
  样式取 `HkSubpage.pageColType(rule, pageUrl)`（空则回落规则 `col_type`）；
  `loadPage` 里子页面模式走 `HkSubpage.expand(rule, mSubpageUrl)` → `HkCard.fromItems` →
  同款 `pickCards`/`toVods`；**缓存跳过读写**（键是规则标题+分类+页码，子页面内容与首页
  同键会互串——`HkCache.get/put` 两处都加 `mSubpageUrl == null` 守卫）；
  `onLoadMore` 里子页面模式直接返回 false（单页，无第 2 页；`CustomScroller` 只有回 true 才
  翻页，`expand` 重跑只会回同一页，去重后也是空追加）。
- 嵌套子页面：子页面卡片本身又是 `hiker://page/…` 时，`onItemClick` 的分发同样走
  `openSubpage`（新开一页 Activity，返回栈自然嵌套）。
- `Router.detail` 的 `hiker://page/` 支保持 `lazyParseFailed` 不变（调用方漏拦截时的护栏）。

### 2.8 `HkListHelper` 删除，内容两处分流

- `pickCards`/`toVods`/`dedupeByUrl`/`isX5Url`（含 `X5_URLS` 记表与 `MAX_X5_URLS`）/
  `isRichText`/`stripHtml`：逐字移入 `HkCard`（api/hk；卡片列表操作与已有的
  `fromItems`/`fromSearchResults`/`fromDetailItems` 同家）。
  调用方改名：`HkListFragment`（`loadPage`/`onPageLoaded`/`onItemClick`/`setStyle`）、
  `HkSearchFragment`（同款四处）、`HkWarmup`（§2.10）。
- `isActionUrl`：不移入——`HkRouter.isActionUrl:1307` 已是同款正表（toast/copy/input/
  confirm/select/download/`hiker://empty` 七支）；`pickCards` 第二遍与
  `HkDetailHelper.isActionable/isLineHeader/isEpisodeCard` 改走 `HkRouter.isActionUrl`。
  语义差：Router 版先 `toLowerCase`（大小写不敏感），旧版大小写敏感；动作 scheme
  按构造恒为小写，放宽无行为变化，写清即可。
- `styleFor`/`columnsFor`（42 种实测类型全表 + 数字族通用解析）：**不逐字移入，按
  `HkColType` 重做**（见 §4）。`HkDetailHelper.columnsFor` 不动（详情网格自有规则：
  空→4、`text_2`→2、`text_3`→3、`movie_1`→1、其余 4；5d 已定空 `col_type` 默认 4 列保留）。

### 2.9 `HkRouter.playRoute` 子页面选集支（旧行为保留，星辰无此支）

- 旧 `HkExecutor.playResolved` 有此支（选集地址是子页面时先展开、在展开卡片里找第一条可播
  地址继续解析）；星辰 `HkRouter.play` 全树无 `hiker://page` 处理（只有 `detail` 的
  拒绝支）。新链路若不补，子页面选集地址会落到步骤 8 兜底、把 `hiker://page/…` 原样塞给
  播放器。补一支（适配，非星辰原文，写清）：
  位置在步骤 0 之后（`@lazyRule=` 已剥离，`u` 为页地址部分；`HkSubpage.parse` 自带 `#` 剥离，
  无需预处理）、步骤 0.1 之前；条件与旧版一致：
  `HkSubpage.isSubpageUrl(u)` 且 `lazyRuleText` 为空或不以 `.js:`/`js:` 开头；
  动作：`HkSubpage.expand(rule, u)` → `HkSubpage.findPlayableUrl(cards)`，
  找不到抛 `IllegalStateException("子页面未解析出播放地址：<页名>（展开得 N 张卡片）")`
  （旧版 `HkException` 同款文案，改抛 `IllegalStateException` 与 `playRoute` 各支一致）；
  找到后循环展开最多 3 次（子页面套子页面），直到非子页面地址，再调一次 `playRoute`；
  循环内仍找不到可播地址时同款抛错。

### 2.10 `HkWarmup` 改走新链路

- `HkExecutor.home(rule, 1, firstClass)` → `new HkRouter(rule).home(1, firstClass, "", "", "")`
  + `HkCard.fromItems` + `HkCard.toVods(HkCard.pickCards(cards, rule), rule.getType())`；
  缓存键不变（首分类口径与 `HkListFragment.setClasses` 一致）。
- `new HkRouter(rule)` 不建 surface（构造器只接常驻运行时，不调 `surfaceOpened`），
  故预热不需 `destroy()`（常驻引擎上是空操作）；每条规则的取数前后各加一次
  `surfaceOpened`/`surfaceClosed` 配对（预热是无界面的后台取数，配对持有可保常驻运行时
  在取数期间不被清定时器；净计数为零）。
- 类注释里的 `HkExecutor#home` 与 `HkListHelper` 引用改为新落点；“含第 3 步的子页面/
  require 展开”一句删除（新链路首页不展开子页面；子页面展开只发生在点击与播放支）。

### 2.11 `HkExecutor` 删除（1231 行）与 `HkNonVideo.sdetail` 搬入

- 删除前全树 grep 零引用复核（§7 验收项）：代码调用方只剩三处，全部有去向——
  `HkActionHandler`（新分发类承接，§2.1）、`HkWarmup`（§2.10）、`HkNonVideo.sdetail`
  （本节搬入）。
- `sdetail` + `evalDetailRule` 逐字移入 `HkNonVideo`（私有静态方法）：
  `sdetail(rule, url, initialVars)`（`DialogHost host` 形参删除；
  `evalDetailRule` 内的 `engine.setDialogHost(host)` 行删除）；
  `detailItemsToCards` 调用改为已有的 `HkCard.fromDetailItems`；
  请求部分改走 `HkRuleFetch`（`buildDetailRequest`/`fetch`/`isHttpUrl`）；
  `stripLazyMarkers` 随 `sdetail` 移入 `HkNonVideo`（私有静态；只它用）。
  `HkNonVideo.sdetailContent` 的调用点由 `HkExecutor.sdetail(rule, url, null, null)`
  改为 `sdetail(rule, url, null)`。
- 随文件死亡（无其他调用方）：`home`/`homeDetailed`/`HomeOutcome`/`search`/
  `detail`/`detailOutcome`/`DetailOutcome`/`lastChapter`/`play`/`playResolved`/
  `dispatchResolved`/`PlayOutcome`/`evalLazy`/`LazyOutcome`/`outcomeFromMultiLine`/
  `stringListOf`/`resolveMediaUrl`/`looksLikeInlineLyrics`/`mergeJsonHeaders`/
  `normalizeDirect`/`hasHeaderIgnoreCase`/`splitOutsideQuotes`/`buildHomeRequest`/
  `buildSearchRequest`/`splitUrlModifiers`/`UrlModifiers`/`replacePagePlaceholders`/
  `applyPageOp`/`classIndexFor`/`classNameFor`/`firstId`/`isCharset`/`bracesBalanced`/
  `toCards`/`searchResultsToCards`/`detailItemsToCards`（三转换已在 5c/5d 移入 `HkCard`）、
  `charsetFor`（随 `fetch` 移入 `HkRuleFetch` 的是同一份；文件内只剩一处定义，移入后
  `HkExecutor` 内无残留）。
- `HkNonVideo` 仍不删除（5d 已定；删除等阶段 7 的 `HkPageActivity`/阅读器承接）。

### 2.12 `HkPlayer` 已删（5d），本笔无动作；`HkNonVideo` 保留（见上节）

## 3. 3e 适配段缺口复核（§6.2/§7.3 六项，逐项定夺）

| # | 缺口（3e 原文） | 复核结论 | 动作 |
|---|---|---|---|
| ① | `evalTextRule` 里纯字符串 `setResult("第…集")` 被静默丢掉 | **属实，可恢复**。根因：`setResult` 处理器（`HkJsRuntime:3180`）只在 `typeKey=="lastChapter"` 时写 `strResult`；普通 `setResult("…")` 走 `deliverResult→collectResult→buildResultItems`，纯字符串解析不成条目被静默丢掉（`buildResultItems` 回 null，`appendResultItems` 无声返回）。`getStrResult` 的全树唯一调用方是 `HkEngine.evalTextRule:497/507`（且 `computeLastChapter` 用全新引擎实例，`strResult` 是运行时实例字段，无跨页串扰）。 | **恢复**：新增私有助手 `noteStringResult(args)`（`args[0] instanceof String` 时写 `strResult = argStr(args[0])`，空串不写），在 `setResult` 的 search 支与 else 支、`setHomeResult`、`setSearchResult` 处理器里各调一次（共 4 处；`setPreResult` 不补——PRE 按契约是列表型交付）。只影响 `evalTextRule` 的 `strAfter` 读取，不影响任何列表/搜索交付（`strResult` 无其他读者）。 |
| ② | `getExports()` 恒回 `Undefined` | **属实，恢复不了，不恢复**。新运行时每发鲜 scope，`$.exports` 不跨发留存；要恢复需在每次 `evalJS` 后捕获 scope 的 `$.exports` 并跨发持有，等于重做运行时 scope 模型。且唯一消费方是已死的 `HkSubpage.requireOne`（§2.4 随之删除），无 live 消费方。JS 侧 `require('hiker://page/…')` 的模块导出由 `HkJsRuntime` 内部 `require` 实现自行处理，不走 `HkEngine.getExports()`。 | 删除方法（§2.5），写进文档。 |
| ③ | `setImportParam(s)` 只存值，子页面规则读不到 `$.importParam` | **属实，可恢复（live 路径）**。消费方是 `HkSubpage.expand:213`（`prepare` 之后调 `setImportParams(query)`，运行时已存在，可直通）。`$` 由 `Hikerurl.js` 在每发鲜 scope 里定义；`HkEngine.setImportParams` 转发给 `jsRuntime`（新增 `setImportParams(Map)` 字段 + setter），`assembleFreshScope` 在 `Hikerurl.js` 评入后、从 scope 取 `$` 对象并以 `Context.javaToJS(map, fresh)` 挂 `importParam` 属性。`expand` 每次新建引擎，字段只活在该子页面引擎内，不会漏进主规则求值。注入包 try/catch：失败只记日志，绝不炸 scope 装配（失败即现状：`$.importParam` 为 undefined）。 | **恢复**：`HkJsRuntime` 新增 `volatile Map<String, String> importParams` 字段 + public setter；`HkEngine.setImportParams` 改为直通转发；`assembleFreshScope` 注入（约 20 行）。 |
| ④ | `jsValueFromCards` 回 JSON 字符串而非 live JS 数组，父规则 `.filter/.map` 失效 | **属实，恢复不了，不恢复**。要恢复需把 Rhino live 值跨 scope 交回调用方，`evalJS` 的字符串返回契约不支持；唯一消费方是已死的 `HkSubpage.requireOne`。JS 侧 `require` 的返回值由运行时内部处理，不走此方法。 | 删除方法（§2.5），写进文档。 |
| ⑤ | `evalFindRule` 不再回落转换直接 return 的数组（只认 `setResult` 一族交付） | **属实，可恢复（live 路径）**。根因：`parseListInternal` 把 `evalJs(…)` 的返回值直接丢掉，只回 `findSink.items()`。但私有 `evalJs:2079` 回的是 Rhino 原始值（`rhinoCx.evaluateString`），不是字符串——`argStr` 的 `""` 丢失只发生在公开 `evalJS` 的字符串化出口，不影响本修复。 | **恢复**：`parseListInternal` 内捕获 `evalJs` 返回的 `Object`（新增私有助手 `itemsFromDirectReturn(directReturn)`，包 try/catch，失败回 null）；`findSink` 为空且返回值是数组（`argsNativeObjectAdjust` 后为 `List`，或本身就是 `[` 开头的 JSON 串）时，经 `buildResultItems(GSON.toJson(…))` 转换，只留标题或链接任一非空的条目（与阶段 4 选择器“留标题或链接任一非空”同口径）。`findSink` 非空时忽略返回值（`setResult` 优先）。一处修复覆盖 `home`/`search`/`detail`/`expand` 全部 JS 列表求值入口（都走 `parseListInternal`）。 |
| ⑥ | `setResult` 空数组不计入 `wasResultSet` | **属实，不恢复**。消费方只剩 `HkExecutor:99` 的命中诊断（随文件删除）与已死的 `requireOne`；新链路用 `getError()` 区分真空与失败，不读 `wasResultSet`。 | 删除方法（§2.5），写进文档。 |

- 另两处 3e 表内“阶段 5 删除/移除”的兑现：`setRefreshEventEnabled`（§2.5，连同
  `computeLastChapter` 的调用一并删）、`setDialogHost/getDialogHost`（§2.5）、
  `getVarsSnapshot`（§2.5）。`resolveUa` 保留（未标 `@Deprecated`，`HkRuleFetch` 用）。
- `HkLazyRuleParser` 的异步 `parse()` 桥注入（`setJsRuntime`）：全树零调用处
  （`HkLazyRuleParser:63` 只有定义；`parse()`/`parseFroAny()` 同样零调用方——只有注释提及；
  星辰全树同样无调用处，阶段 4 已注）。**本阶段决定：留空，不接线。**理由：live 的懒求值
  已有三条路（`Router.evalTab`、`Router.playRoute` 的 `resolveLazyRule`、`HkClickDispatcher.runLazy`，
  全部直调常驻运行时的 `evalLazy`）；`HkLazyRuleParser.parse` 是 8.83 回调风格的另一套入口，
  接线等于多一套懒执行链。文件本身保留（逐字移植的 4c 成果，不删），桥的接管留给阶段 7 的
  `HkPageActivity`（若它需要回调风格入口再议）。
- JS `confirm()` 在新引擎内：`HkJsRuntime.ConfirmHandler`（`:721-727`）全树零设置方；
  无 handler 时 `confirm` 记一条 debug 日志、回 `false`（`HkJsRuntime:3666-3680`），不崩不挂。
  旧 `DialogHost` 桥随 `HkExecutor` 死亡；新桥的接管（在常驻运行时上设 `ConfirmHandler`，
  弹框后把 `confirmJs`/`cancelJs` 评回去）是阶段 6/7 的活——影响面：依赖 `confirm()` 自更新
  的规则（如福建兄妹更新域名）在 5e 内走“取消”分支，不自更新，不崩。写清，不补。

## 4. col_type 表按 `HkColType` 重做（替代 `HkListHelper` 旧表）

- 落点：`HkColType` 本身加两个公开静态方法（5e 新增，非星辰原文；文件头加一行说明，
  5b 的“除包名外与原文一致”自此只适用于 enum 本体 57 码与已有方法）。`HkDetailHelper.columnsFor`
  不动（§2.8）。
- `styleFor(String colType)`：按 code 家族派生形状（`byCode` 查不到回退 `MOVIE_3`）：
  - list：`text_*`（含 `text_center_1`）、`long_text`、`rich_text`、`input`、`flex_button`、
    `scroll_button`、`icon_1_search`、`blank_block`/`big_blank_block`/`big_big_blank_block`、
    `line`/`line_blank`、`header`/`footer`、`movie_1` 族（含 `movie_1_left_pic`、
    `movie_1_vertical_pic[_blur]`）、`icon_1_left_pic`、`text_icon` → `Style.list()`；
  - oval：code 含 `round` 或等于 `avatar` → `new Style("oval", 1.0f)`；
  - rect 1.33：`pic_1` 族（`pic_1`/`pic_1_full`/`pic_1_center`/`pic_1_card`）→
    `new Style("rect", 1.33f)`；
  - rect 1.0：其余 `pic_*`、`icon_*`（非 round）→ `new Style("rect", 1.0f)`；
  - 其余（`movie_2/3[_marquee]`、`card_pic_*`、`video`、未知/空）→ `Style.rect()`（0.75 默认）。
- `columnsFor(String colType)`：list 形态回 0（`setStyle` 里 0＝走 `Product.getColumn` 的 App
  默认列数，与旧 `LIST_FORM` 一致）；其余回 `60 / spanCount`（`landSpanCount` 不用——旧表
  也不分横竖屏，`setStyle` 无横竖分支）。
- 行为差（旧表 → 新表，逐项核对，接受并记录；星辰官表无 `movie_4/5/6` 等码，回退即官行为）：

| code | 旧表 | 新表 | 备注 |
|---|---|---|---|
| `movie_3`/`movie_2`/`movie_3_marquee`/`pic_1/2/3`/`pic_1_card`/`pic_2_card`/`icon_2/3/4/5`（含 round）/`card_pic_1/2/3[_center]/2_2[_left]`/`text_1~5`/`long_text`/`rich_text`/`input`/`flex_button`/`scroll_button`/`icon_1_search` | 见旧表 | 形状与列数一致 | 主流 77.6%（`movie_3` 2234/`movie_2` 563/`movie_3_marquee` 503）零变化 |
| `movie_4`/`movie_5`/`movie_6` | rect 4/5/6 列 | rect **3 列**（`byCode` 回退 `MOVIE_3`） | 星辰官表无此三码；合集里三码合计不足 1% |
| `movie_1`/`movie_1_left_pic`/`movie_1_vertical_pic` | list，列数 0 | list，列数 0 | 一致 |
| `movie_1_vertical_pic_blur` | 旧表无此码 → 族解析 list | list，列数 0 | 一致（官码 span 60） |
| `avatar` | oval 4 列 | oval **1 列**（span 60） | 官表口径；头像行变单列宽行 |
| `x5_webview_single` | rect 默认列数（0） | rect **1 列**（span 60） | 单页网页卡本就全宽，显式 1 列更贴合 |
| `pic_1_full`/`pic_1_center`/`pic_3_square`/`icon_5_no_crop`/`text_icon`/`icon_1_left_pic`/`video`/`header`/`footer`/`line`/`line_blank`/`blank_block`/`big_blank_block` | 旧表无此码 → 默认 rect/0（`video` 等）或族解析 | 按家族派生（见上；`video` rect 1 列，结构行 list） | 旧表漏登记的码首次有了确定形态 |
| `icon_6` 及未登记的编号变体 | 族解析 rect min(n,6) | rect **3 列**（回退 `MOVIE_3`） | 官表无此码；族通用解析随旧表删除 |
| 空/未知 col_type | 默认 rect/0 | rect 3 列（回退 `MOVIE_3`，span 20） | 兜底由“App 默认列数”改为“3 列海报网格”；详情网格不受影响（`HkDetailHelper.columnsFor` 另有规则） |

## 5. `proxyClearM3u8` JS 函数接线（5b 遗留定夺：接线）

- 星辰 `HkJsRuntime:4985-5048`（1-based；初稿写的 4989-5060 是行号错，抽取时以实际行号为准）
  的 `putFunction("proxyClearM3u8", …)` 逐字搬入我方
  `HkJsRuntime.registerApi`，插在 `putFunction("setPageTitle", …)`（我方 `:4482`，星辰同款邻位）
  之前；唯一改动是包名 `com.fongmi.android.tv.api.hk.HkM3u8ClearUtil` →
  `com.github.moyingshi.osc.tv.api.hk.HkM3u8ClearUtil`。
- 依赖核对（5b 已断言，本笔实现时再 grep 一遍）：`toStringMap`（我方 `:2593`）、
  `argStr`、`md5(String)`（我方 `:2601`）、`App.get()`、
  `HkM3u8ClearUtil.getRules()`/`.getProxyContent(url, headers, adRules)`/`.pruneCacheDir()`
  （5b 已搬，均在位）；缓存目录 `hk_m3u8_cache`、返回 `file://…##原URL` 与星辰一致。
- 未接线前去广告不生效（与 3b 至今的行为一致）；接线后 JS 规则调 `proxyClearM3u8(url, headers, rules)`
  即走 `HkM3u8ClearUtil` 清广告。失败回原 URL（星辰同款，不抛错）。

## 6. x5 决策（重申 5c，不改）

- 继续用 0836 版的 `CatWebActivity` 顶替方案（点卡进站内 WebView），不做列表页内嵌 WebView。
  理由（5c 已定，本笔复核无变化）：星辰 `HkListActivity.openX5PlayPage` 本身就是新开专用网页页
  （`carrierPage=true` → “官 WebViewActivity 等价物”），与 `CatWebActivity` 同构；星辰注释明言
  “内网页模式（他亲定 x5 走系统 WebView）”，星辰自己也没有 X5（腾讯 TBS）引擎；列表/搜索页只在
  mobile 源集，leanback 零影响。
- 本笔新增：裸 `x5://…` 卡片 URL 也进 `CatWebActivity`（§2.1 第 4 支；`x5_webview_single`
  colType 卡仍走 Fragment 的 `isX5Url` 支，两支不冲突）。

## 7. 验收（代码级，不要等构建；逐项填证据）

- [x] `HkExecutor.java` 已删（1231 行）；全树 `HkExecutor.` 代码命中为零，只剩 6 处注释提及：
  `HkListFragment:366`（子页面支旧行为注）、`HkDetailFragment:232`（fromSearch 口径注）、
  `HkRouter:282`（lastChapter 同款注）、`HkParserKit:317`（注释里的调用方列表已过时，仍列
  HkExecutor/HkActionHandler——该文件是逐字移植文件，不在本笔 scope 内，留待后续顺手改，见 §8）、
  `HkClickDispatcher:227`（evalExpression 同款注）、`HkEngine:291-294`（适配段瘦身节头注）。
- [x] `HkListHelper.java`（336 行）、`HkActionHandler.java`（480 行）、`HkDialogs.java`（207 行）
  已删；全树 `HkListHelper.`/`HkActionHandler`/`HkDialogs.` 代码命中为零（只剩 `HkEngine`
  节头注与 `HkColType` javadoc 各一处注释提及）。
- [x] `HkPlayer.` 全树零命中（5d 已删，本笔复核；只剩 `HkNonVideo` 类注释一句“已删除的
  `HkPlayer.buildVod`”历史说明）。
- [x] `HkFindDelivery`、`HkSearchEngine` 在位（5b 已闭合 13 处代码引用）；本笔复核：
  `HkJsRuntime` 内两名合计 17 处提及（代码+注释），无新增悬空引用——新类只调已在位 API
  （`HkRouter`/`HkEngine`/`HkJsRuntime`/`HkSelector`/`HkRuleFetch`/`HkSubpage`/`HkWebRule`/
  `HkCard`/`HkColType` 均在位）。
- [x] 新引擎接线证据：`HkRouter.home:69` 调 `engine.home(...)`、`HkRouter.search:74` 调
  `engine.search(...)`（门面 `HkEngine.home:103`/`search:161` 在位）；调用点：三个 Fragment
  （5c/5d）+ `VideoActivity.resolveHkPlayerContent`（5d）+ `HkNonVideo`（5d）+ 本笔
  `HkWarmup`（`new HkRouter(rule).home(1, firstClass, "", "", "")`）与
  `HkClickDispatcher.runLazy`（常驻 `evalLazy`）。老链路调用点清零；保留项及理由：
  `HkSubpage.expand`（全树唯一子页面渲染器，新引擎内无等价物，§2.4）、`HkWebRule.resolve`
  （全树唯一 `fy_bridge_app` 桥注入嗅探，§2.3）、`HkRuleFetch`（两处调用方：
  `HkNonVideo.sdetail`、分发器 `runLazy` 选择器路，§2.6）、`HkEngine` 适配段残留
  （`prepare`/`evalPreRule`/`evalFindRule`/`evalTextRule`，调用方只剩 `HkSubpage.expand`
  与 `HkRouter.computeLastChapter`）。
- [x] `git diff --check` 通过（多次复核，最后一次在删四文件与文档订正后）。
  全树 grep：`com.fongmi.android.tv` 只出现在文件头移植来源注释与 5a 已有的一处
  `SourceGate` 注释；LitePal 只出现在 3e/5a 的注释里（`HkJsRuntime`/`HkUrlDetector`/
  `HkHikerRuleUtil`/`HkPageParser`/`AuthBridgeEvent`），无依赖、无代码调用；
  DexClassLoader 命中只在 App 既有蜘蛛加载器（`BaseLoader`/`JarLoader`/
  `CspDexClassLoader`，与 hk 无关，本笔未引入新的 dex 动态加载）；Clash、XPopup 全树零命中。
  改动：`git diff HEAD --stat` 计 18 文件 +623/−2646（不含未跟踪的 2 个新文件与本
  文档；`HkClickDispatcher` 672 行、`HkRuleFetch` 137 行、本文档 477 行）：
  删 4 文件（HkExecutor 1231、HkListHelper 336、HkActionHandler 480、HkDialogs 207）；
  改 13 文件（HkCard 135、HkDetailHelper 6、HkEngine 232、HkJsRuntime 153、HkNonVideo 61、
  HkRouter 28、HkSubpage 109、HkWarmup 34、HkColType 48、HkDetailFragment 42、
  HkListActivity 29、HkListFragment 95、HkSearchFragment 41，均为改动行总数）；
  5d 文档 1 行订正（199→214）。0 leanback 文件（全部改动在 `app/src/mobile/.../api/hk`、
  `ui/hk` 与 docs 内；`VideoActivity` 未动）。
- [x] `HkEngine` 适配段删除清单逐项 grep 零引用（§2.5：`wasResultSet`/`getResultCards`/
  `getExports`/`jsValueFromCards`/`setImportParam`（单数）/`getVarsSnapshot`/
  `setRefreshEventEnabled`/`wasRefreshRequested`/`setDialogHost`/`getDialogHost`/`DialogHost`/
  `evalExpression`/`eval`，代码行零命中，只剩节头注释）；`HkSubpage.require/requireOne/pageJson`
  删除后，`HkSubpage.java:273` 原有一处 `engine.wasResultSet()` 代码引用随 `requireOne()` 一并消失
  （删前已确认在待删方法内，非遗漏）。单数 `setImportParam(` 全树零命中（只剩复数
  `setImportParams`）；两 Fragment 内 `Map<` 零命中（`java.util.Map` import 已正确删掉）。
- [x] 8 条规则全链路核对（§9）。
- [x] 5d 文档 §8 数字已订正为 214 行（本笔，`git diff` 计 5d 文档 1 行改动）。

逐行重读发现并修掉的两处（提交前复核，见 §10 末）：
- `HkEngine` 删除 `@Deprecated` 方法时，删除脚本的 javadoc 上溯未跳过 `@Deprecated` 注解行，
  留下 12 处孤儿 `@Deprecated`（堆叠在保留方法上，重复注解会 javac 报错）；已清除并逐一核对
  剩余 7 处都紧贴声明（无参构造、`prepare`×2、`evalPreRule`、`evalFindRule`、`evalTextRule`、
  `setImportParams`）。`HkSubpage` 的删除不受影响（被删方法均无 `@Deprecated`）。
- `HkClickDispatcher.enqueueDownload` 的 javadoc 仍提已删的 `PlayOutcome.notice`，已改成
  “调用方是 `handleDownload`（主线程）”。
- 括号平衡：逐字状态机扫描（跟踪 code/行注释/块注释/字符串/字符四态）重算，15 个改动/新文件
  全部 depth=0 且终态为 code；粗糙 regex 计数器的 ±1 偏差是计数器 artifact（字符串里的 `//`
  等），已弃用。
- 本地无 javac/java（`which javac java ecj` 为空，`/usr/lib/jvm/` 为空），无法本地编译；
  编译检查靠推上去后的 GitHub 构建——但本任务绝对不推不编，按任务书执行。

## 8. 遗留（阶段 5 结束后仍缺什么）

- 阶段 6：云口令两法、`HkCloudClip` 等（不要提前搬）。
- 阶段 7：`HkPageActivity`（列表/详情/播放的完整宿主，含 `handleDealUrl` 全套、
  `getNextPage` 子规则列表与分页、阅读器/音频页承接、`HkNonVideo` 删除）、剩余 event
  （`OnRefreshPageEvent`→列表页的桥：JS 的 `refreshPage` 发 `OnRefreshPageEvent`，
  三个 Fragment 订阅的是 `HkRefreshPageEvent`，全树无转译订阅——5d 已记名）、
  `ConfirmHandler` 接管（§3 末段）、`HkLazyRuleParser.parse` 桥（§3 末段）、
  无详情规则时 `pics://`/`x5://`/`web`/`image` 的直接分流（§2.1 第 5 支的反向说明：
  有详情规则时走详情页分流；无详情规则时 `video`/`magnet` 已由 `Router.detail` 的空规则支
  兜底，`pics/x5/web/image` 目前仍是空详情/错误态，与旧版一致，不崩）。
- 3e 缺口中恢复不了的两项（② `getExports`、④ `jsValueFromCards`）随方法删除而关闭：
  唯一消费方已死，无 live 影响；若阶段 7 的 `HkPageActivity` 需要 `$.exports` 优先分支，
  需重做运行时 scope 模型，届时再议。
- 详情 `*` 列表段：`Router.detail` 不走 `HkSelector.parseDetail` 的 `*` 整页当一条分支
  （5a/5d 已复核：星辰原生行为，接受并记录，不补；`*;*;body&&div@option;Text;value`
  这类 `chapter_find` 样本在新版下仍回空表）。
- 奇优搜索 desc 多拼一段 `.detail&&Text`：星辰行为，只影响展示（5d 已确认）。
- `toVod` 与 `split` 的选集过滤不同（5d 已接受：`toVod` 过 `admitEpisodeRuns`+
  `HkEpisodeSorter`+`HkEpisodeMerger`，旧 `buildVod` 全量收编；星辰原生行为）。
- gitcode 418 的两条规则（星辰影视123.Dr、青苹果♨️）：移植后首次仍出不了数据（星辰 require
  链无重试、无镜像、无备用域名、无特殊 UA；失败时本地有缓存回退旧版，无缓存抛
  “获取远程依赖失败”），只验报错清晰与缓存回退路径（§9）。

## 9. 8 条规则全链路核对（5e 增量：点击分流与子页面；5c/5d 的列表/搜索/详情/播放核对不变）

| 规则 | 列表/搜索（5c） | 详情/播放（5d） | 5e 点击分流 |
|---|---|---|---|
| 奇优影院♔ | 走通 | 走通 | 普通卡 → 详情页；懒链接经新 `runLazy`（常驻 `evalLazy`） |
| 童话 | 走通 | 走通 | 同上；`.js:` 懒段经 `setNextInputOverride`+`evalLazy` |
| 臻品fix | 走通 | 走通 | `hiker://empty@lazyRule=` tab 卡 → 新 `runLazy` → `refreshPage` 时 `reloadPage()`（`consumeRefreshRequest`）；`#noHistory#` 前缀剥离不变 |
| 韩剧TV.Q | 走通 | 走通 | JS find_rule 的 `.replace` 落在 Java 字符串上问题属引擎层，5e 不动 |
| 麻花影院 | 走通（x5 卡） | 走通 | `x5_webview_single` 卡 → `HkCard.isX5Url` → `CatWebActivity`（5c 决策不变） |
| 西瓜影院 | 走通 | 子页面待 5e | **`hiker://page/home` 卡 → `openSubpage` → 新列表页（`HkSubpage.expand` 单页渲染）；子页面内再点子页面 → 再开一页；子页面选集地址 → `playRoute` 子页面支展开后播放** |
| 星辰影视123.Dr | 报错清晰（gitcode 418） | 同左 | 只验报错清晰与缓存回退，不声称能出数据 |
| 青苹果♨️ | 报错清晰（gitcode 418） | 同左 | 只验报错清晰与缓存回退，不声称能出数据 |

## 10. 实现顺序（guard 会话内按此序，每步做完 grep 自查）

1. `HkRuleFetch` 新文件（`HkExecutor` 请求工具逐字移入）→ `HkCard` 增 `pickCards`/
   `toVods`/`dedupeByUrl`/`isX5Url`（逐字移入）→ `HkColType` 增 `styleFor`/`columnsFor`
   （§4 派生规则；行为差表逐项对过）。
2. `HkJsRuntime` 四处：`proxyClearM3u8` 接线（§5）、`setResult` 的 `strResult` 补救（§3 ①）、
   `parseListInternal` 直接 return 数组回落（§3 ⑤）、`assembleFreshScope` 的
   `$.importParam` 注入 + `setImportParams` 字段/setter（§3 ③）。
3. `HkEngine` 适配段删除（§2.5）+ `setImportParams` 转发；`HkRouter` 的
   `computeLastChapter` 删 `setRefreshEventEnabled` 调用 + `playRoute` 子页面支（§2.9）。
4. `HkClickDispatcher` 新文件（§2.1/§2.2 的逐字移入 + 改写）→ 三个 Fragment 的
   `Host` 实现与调用点改名（`HkActionHandler`→`HkClickDispatcher`，`HkListHelper`→
   `HkCard`/`HkColType`/`HkRouter`）→ `HkListActivity.openSubpage` +
   `HkListFragment` 子页面模式（§2.7）。
5. `HkNonVideo.sdetail` 搬入（§2.11）→ `HkWarmup` 改走新链路（§2.10）→
   `HkSubpage` 瘦身（§2.4）→ `HkDetailHelper` 的 `isActionUrl` 改走 `HkRouter`。
6. 删除 `HkExecutor`/`HkListHelper`/`HkActionHandler`/`HkDialogs`（每删一个全树 grep
   零引用复核）→ 5d 文档数字订正 → §7 验收逐项填证据 → finish。
