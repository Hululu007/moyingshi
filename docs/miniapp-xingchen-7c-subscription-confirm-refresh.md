# 移植阶段 7c：订阅定去留 + m3u8/歌词收尾 + ConfirmHandler + 刷新桥 + 清理

> Recovery anchor：阶段 7c——移植收尾。`HkHomeSubService` 定去留（不搬）、m3u8/歌词复核、
> ConfirmHandler 接管、OnRefreshPageEvent 桥、HkLazyRuleParser.parse 桥、过时注释清理。
> 计划：`docs/miniapp-xingchen-port-plan.md` §2 阶段 7（第 4/5/6/7/8/10 项）、§1.4/§1.5。
> 7a 已提交（`06eb201e`）、7b 已提交（`105934e1`），本地 main 干净。
> 完成句：7c 六件事全部有结论写进文档并落地（不搬的有依据、接管的有接线），8 条规则全回归
> 代码级核对，全树 grep 零悬空，阶段 7 结束后整棵移植的状态写清。
> 允许路径：`api/hk/HkClickDispatcher.java`、`api/hk/HkEngineManager.java`、`api/hk/HkJsRuntime.java`
> （只改 :9735 注释）、`api/hk/HkRouter.java`（只改 :351 注释）、`api/hk/HkParserKit.java`
> （只改 :317 注释）、`api/hk/HkRuleFetch.java`（只改 :26 注释）、`api/hk/HkRefreshPageEvent.java`
> （删除）、`ui/hk/HkListFragment.java`、`ui/hk/HkDetailFragment.java`、`ui/hk/HkSearchFragment.java`、
> 本文档。一次只开一个 guard 会话（`miniapp-xingchen-7c`）。
> 下一步：按 §6 顺序实施 → §7 验收逐项填证据 → `task_guard.sh finish` 提交并打 recovery tag →
> 打总 tag `recovery/miniapp-xingchen-port-complete/<时间戳>-<短SHA>`。

## 1. 来源与范围

- 星辰原文（`~/workspace/xingchen-ref`，HEAD `52450c0`）：`HkHomeSubService.java`（627 行）、
  `HkM3u8ClearAdRule.java`（67 行）/`HkM3u8ClearUtil.java`（601 行）、`HkLyricLoader.java`（76 行）、
  `HkArticleFragment.java`（`showRuleConfirm`/:2557、`routeConfirmResult`/:2586、`setConfirmHandler` 登记处/:2441）、
  `ui/hk/HkEngineBridge.java`（`HkLazyRuleParser.setJsRuntime` 接线处，约 :116）。
- 只改 §0 允许路径。品牌/包名、液态玻璃、短剧、本地源、实验室、TV/leanback、设置页、播放器、
  下载、检查更新一律不动；BaseLoader 零改动；底栏仍 6 项；不引入 LitePal、dex 动态加载、Clash、XPopup。
  绝对不推、不编、不触发任何 workflow。

## 2. 首页订阅 `HkHomeSubService`：不搬（计划 §1.4“延后/不搬”+ 用户原话）

- 决策依据（三条，逐项已验）：
  1. 计划 §1.4 写“延后/不搬”；用户原话不搬首页订阅——默认不搬。
  2. 星辰 `HkJsRuntime:6258-6270` 暴露 JS 函数 `getSubRecords`/`hasHomeSub`（即 `getHomeSub`/
     `hasHomeSub` API）调它；**我方 `HkJsRuntime` 无 `getHomeSub`/`hasHomeSub` JS 函数**
     （grep 零命中），全树无 `HkHomeSubService`/`HkSubscribe` 引用——无链路必需部分，
     子页面/收藏链路（7a/7b）不依赖它。
  3. 内容是 SP 存订阅记录（PREFS `hk_home_sub`）+ `HkSubscribeRecord`/`HkSubscribeHistory` +
     checkUpdate 全链 + 冷启触发（宿主 init 后延迟 7s 一轮）——纯首页增值功能，与 hk 引擎
     执行链路无关。
- 随之不搬：`HkSubscribeRecord`、`HkSubscribeHistory`、`OnSubRefreshEvent`、
  `OnArticleListRuleChangedEvent`（7b §6 已定夺：只被 `HkHomeSubService` 等引用）。
- 若以后用户改主意要订阅：只搬 `HkHomeSubService` + 两记录类 + JS 函数两枚（`getHomeSub`/
  `hasHomeSub`）+ 冷启触发点，`HkHomeSubService` 本身不依赖 `HkArticleFragment` 之外的新面。

## 3. m3u8 去广告与歌词：复核记名，不搬不改

- `HkM3u8ClearAdRule`/`HkM3u8ClearUtil`：**5b 已搬**，与星辰原文 diff 仅包名 + 文件头注释 +
  一处 `App.get()` 包名改写；`proxyClearM3u8` 接线 5e 已做。7c 复核记名，无动作。
- `HkLyricLoader`（星辰 76 行）：**不搬**。星辰版依赖 `HkPlayCompanion.classifyLyric`
  （星辰播放器引擎类，我方全树零命中）+ `VideoActivity` 的 `LyricsController`；我方歌词已接通
  （`HkPlay.lyric` / `HkPlayData.lyric` → `HkPageActivity.routeEpisode` 传 `AudioActivity.start`；
  `HkHttpParser:524-538`、`HkRouter:1222-1228` 承载 lyric 字段）——搬它等于为一个已接通的
  功能再引一个不存在的播放器引擎类，不搬。

## 4. ConfirmHandler 接管：JS `confirm()` 不再恒走取消分支

### 4.1 定位过程与结论（任务第 6 项要求的定位）

- 星辰仓库里**没有独立的 `ConfirmHandler.java` 文件**（全树 grep 零命中）。真实形态有两件：
  1. 接口 + 分发在 `HkJsRuntime`：`ConfirmHandler` 接口（`onConfirm(title, content, confirmJs, cancelJs)`）
     + `setConfirmHandler` + `evalConfirmJs`（`evalJS(js, "", true)`）；`confirm` putFunction
     （无 handler 时记 debug 日志、回 false）。
  2. 唯一设置方是 `HkArticleFragment.java:2441` 的 `wireWebViewInterception` 内
     `rt.setConfirmHandler((title, content, confirmJs, cancelJs) -> showRuleConfirm(...))`；
     `HkPageActivity` 不设。`showRuleConfirm`（:2557，深色底 AlertDialog，确定/取消分别求值回调串，
     `isOnPause` 门）+ `routeConfirmResult`（:2586，后台线程 `rt.evalConfirmJs(js)`，结果非空且
     非 undefined/null/false/`hiker://empty` 才走 `bridge.dealUrlSimply`）。
- 我方现状（已验）：`HkJsRuntime` 已有接口 + `setConfirmHandler` + `evalConfirmJs`（:768）+
  `confirm` putFunction（:3696-3717，无 handler 时记 debug 日志、回 false）；**全树零设置方**
  （5e 确认）——故 5e 内 `confirm()` 恒走“取消”分支（回 false）。

### 4.2 接管方案（星辰同款逻辑，我方适配三处）

- 新方法 `HkClickDispatcher.installConfirmHandler(Host host)`（public static）：
  `host.router().getEngine().getJsRuntime()` 取共享桥（`HkRouter.getEngine` + `HkEngine.getJsRuntime:263`
  在位）→ `rt.setConfirmHandler(...)`。调用点：三 Fragment 各自 `loadPage`/`loadDetail` 后台段
  `routerFor(rule)` 之后各加一行（`HkListFragment:445`、`HkDetailFragment:244`、`HkSearchFragment:258`
  之后；每次加载都重装——共享桥上的 handler 永远是当前页的，翻页/切规则不串）。
- handler 内（星辰 `showRuleConfirm` + `routeConfirmResult` 同款逻辑）：
  - 门：`WeakReference<Activity>` + `activity` alive 检查（finishing/destroyed 丢弃；
    共享桥持 handler，强引 Activity 会跨页泄漏）+ `Host.isActive()` 门（星辰 A9 `isOnPause`
    同款：宿主被盖住时不弹，免得确认框串到别页之上；见 §4.3）。
  - 弹框：复用本类已有私有 `confirmAsync(activity, title, message, Consumer<Boolean>)`
    （`HkClickDispatcher:641`：`usable` + `onMain` + `Once`，确认回 true、取消/返回键/点框外回 false；
    标题空时用 `R.string.hk_action_confirm_title`，与星辰深色底 AlertDialog 的“确定/取消”同义，
    不另造弹框——星辰 `HkDialogs` 已删，我方 `WebHtvAlertDialogBuilder` 是既定对位）。
  - 回调：`confirmed` 选 `confirmJs`/`cancelJs`，空串不求值；**后台线程** `rt.evalConfirmJs(js)`
    （JS 求值不许占主线程）；结果过滤与星辰 `routeConfirmResult` 同款（非空且非 undefined/null/
    false/`hiker://empty`，忽略大小写）；过关的结果**回主线程**走 `HkClickDispatcher.handle(host, 卡片)`
    路由（合成 `HkCard`：`setUrl(result)` + `setTitle("")`；`handle` 是 public static，
    覆盖 hiker://page/http(s)/toast:// 等全部分发，与星辰 `dealUrlSimply` 同位）。
  - 失败形态不变：无 handler/空标题内容/坏参/异常时 `confirm` putFunction 仍回 false
    （`HkJsRuntime:3696-3717` 不动）——接管只补“有 handler 时弹框”，不改返回值语义。
- `confirm://` 裸 scheme（`HkClickDispatcher.handleConfirmBare`）不动：那是卡片 URL 形的确认动作，
  与 JS `confirm()` 函数是两条通道。

### 4.3 `Host.isActive()`（适配接缝，星辰 `isOnPause` 的我方对位）

- `HkClickDispatcher.Host` 加 `default boolean isActive() { return true; }`（default 方法，
  仅有的三个实现方是三 Fragment，全树 grep 已验无其他实现——加 default 不强制改任何一方）。
- 三 Fragment 各 override：`return isResumed() && getUserVisibleHint();`（与 `onHkRefreshPage`/
  `onToastEvent` 的门同款，星辰注释自述“暂停或 hint 假，W4 F15 同款”）。
- 写清：这是适配接缝，不是星辰原文——星辰的门直接读宿主 `HkListActivity.isOnPause()`；
  我方 Fragment 无 `isOnPause()`，用 resumed+可见门等价替代。

## 5. OnRefreshPageEvent → 列表页的桥（5e 遗留，本阶段接上）

- 现状（已验）：`HkJsRuntime:4977` 真实 post 星辰 `OnRefreshPageEvent`（`refreshToTop`）；
  三个 Fragment（`HkListFragment:307`、`HkDetailFragment:166`、`HkSearchFragment:153`）订阅的是
  我方自研 `HkRefreshPageEvent`（`post` 方法零调用方），故刷新事件投出后无人接。
- 改动：
  - 三个 Fragment 的订阅方法改成 `OnRefreshPageEvent`：方法名 `onHkRefreshPage` 不动，
    参数类型换成 `com.github.moyingshi.osc.tv.api.hk.event.home.OnRefreshPageEvent`，
    `event.isToTop()` → `event.isScrollTop()`（`OnRefreshPageEvent` 的 getter 名；
    `forceRefresh` 字段本桥不用——`reloadPage()` 本就重跑当前页，与 `forceRefresh` 同效，
    写清不取该字段的理由）。
  - 删 `api/hk/HkRefreshPageEvent.java`（删前 grep 确认订阅方三处已改、`post` 零调用方）。
  - 过时注释：`HkRouter:351`（“全树无 OnRefreshPageEvent 订阅方”）改成已接线；
    7b 文档曾记 `HkDetail:114` 亦过时——复核：该行是 `surfaceOpened` 注释，与订阅无关，
    不动（写清复核结果，不硬改）。
- 其余不搬的 event（7b §6 已逐个定夺，本笔不再重复列，只记“7b 已定夺”）。

## 6. `HkLazyRuleParser.parse` 桥：接线（星辰 `HkEngineBridge:116` 同款）

- 星辰接线（`ui/hk/HkEngineBridge.java:116`）：匿名 `JsRuntime`——`evalLazyRule` 经
  `HkEngineManager.runtimeFor` + `ensureInit` 后调 `rt.evalLazyRule`，初始化失败抛
  `RuntimeException`（不许在未初始化的运行时上继续求值装出空结果）；`evalJSForObj` 经
  `HkEngineManager.currentRule()`（空规则回 null）；`getMyRule` 直通静态方法；
  不覆写 `consumeRefreshRequest`（默认 false）。
- 我方 `HkJsRuntime` 具备全部三法：`evalLazyRule(Object,String,String)`（:13613）、
  `evalJSForObj`（:13549）、静态 `getMyRule`（:11321）、`evalLazy(String,String,int)`（:12921）；
  `consumeRefreshRequest`（:13099）在位。`HkLazyRuleParser.JsRuntime` 接口（:48-56）与星辰同形。
- 接线点：我方无 `HkEngineBridge` 类，装入 `HkEngineManager.runtimeFor` 内 BRIDGE 创建后
  （双检锁内 `BRIDGE = rt;` 之后一次，与桥同寿命；`setJsRuntime` 是静态 volatile 写，
  重复调无害）。逐字照星辰匿名类搬，包名改写 + `Log` import。
- 诚实记名：接线后 `HkLazyRuleParser.parse()`/`parseFroAny()` 在我方仍**零调用方**
  （只有 `HkEngineManager:148` 注释提及；星辰同样零直接调用方，除 `HkDanmuSearchDialog:576`
  与 `HkEngineBridge:405`）——桥已就位、待后续调用方（弹幕搜索等）接入；不留空、不冒充已用。

## 7. 清理：三处过时注释（只改注释，不动代码）

- `HkParserKit.java:317`：调用方列表仍列已删的 `HkExecutor`/`HkActionHandler`——按实际调用方重写
  （`HkNonVideo.sdetail` 已随 7a 搬家成 `HkPageActivity.sdetail`，一并订正）。
- `HkRuleFetch.java:26`：仍列 `HkNonVideo.sdetail`——改成 `HkPageActivity.sdetail`。
- `HkJsRuntime.java:9735`：承接点仍写 `HkArticleFragment.onToastEvent`——改成三 Fragment 各一个
  `onToastEvent`（7b 已接）。

## 8. 验收（代码级，不要等构建；逐项填证据）

- [x] `HkHomeSubService` 不搬：决策依据（三条，§2）写进文档；全树 grep `HkHomeSubService`/
  `HkSubscribe` 零命中，无新增引用。
- [x] m3u8/歌词：5b 已搬复核记名——与星辰原文 diff：`HkM3u8ClearAdRule` 仅多 1 行文件头注释，
  `HkM3u8ClearUtil` 仅多 1 行文件头注释（包名改写已归一化），与 §3 所述一致；
  `HkLyricLoader` 不搬结论写进文档（§3）；`HkPlayCompanion` 全树零命中。
- [x] ConfirmHandler：`installConfirmHandler` 在位 + 三 Fragment 调用点在位（`routerFor` 之后各一行）；
  JS `confirm()` 走弹框链路：`confirm` putFunction → handler → `confirmAsync` →
  后台 `evalConfirmJs` → 结果过滤 → 主线程 `handle(host, 卡片)`；`HkJsRuntime` 的 confirm
  putFunction 一字不动（无 handler 仍回 false）。
- [x] 刷新桥：三 Fragment 订阅改成 `OnRefreshPageEvent`（`isScrollTop()`）；`HkRefreshPageEvent.java`
  已删（29 行，全树零引用）；`HkRouter:351` 注释已更新。
- [x] parse 桥：`HkEngineManager.runtimeFor` 内 `setJsRuntime` 适配器在位（星辰原文同款）；
  `parse()` 仍零调用方已记名（§6）。
- [x] 清理：三处过时注释已更新（`HkParserKit:317`、`HkRuleFetch:26`、`HkJsRuntime:9735`）。
- [x] 其余不搬的 event（7b §6）不再重复列；`ToastEvent` 已在 7b 搬。
- [x] `git diff --check` 通过；全树 grep 确认无悬空引用（`HkJsRuntime`/`HkEngine`/`HkRouter` 内对
  阶段 7 搬入类的引用全部闭合；`HkNonVideo` 删除后零代码引用（只剩 7a 溯源注释）；
  无 `com.fongmi.android.tv`（7c 文件零命中）、无 LitePal、无 dex 动态加载）；改动文件列表与
  行数统计见 §8 末；0 leanback 文件。
- [x] 8 条规则全回归代码级核对（§9）；阶段 7 结束后整棵移植的状态写清（§10）。
- 改动统计（`git diff --numstat` + 已暂存的删除 + 本文档）：`HkClickDispatcher.java` +59、
  `HkEngineManager.java` +46、`HkListFragment.java` +11/−4、`HkDetailFragment.java` +11/−4、
  `HkSearchFragment.java` +11/−4、`HkJsRuntime.java` ±1 行（注释）、`HkRouter.java` ±2 行（注释）、
  `HkParserKit.java` ±3 行（注释）、`HkRuleFetch.java` ±1 行（注释）；
  删 `HkRefreshPageEvent.java` 29 行；本文档 236 行。括号平衡：状态机分词器复核 9 个改动文件全部归零。

## 9. 8 条规则全回归代码级核对（7a/7b/7c 增量合计）

| 规则 | 核对 |
|---|---|
| 奇优影院♔ | 列表/详情/搜索不变（引擎层，7c 不动）；confirm 接管只影响调 `confirm()` 的规则，奇优不调 |
| 童话 | 同上 |
| 臻品fix | `hiker://empty@lazyRule=` tab 卡仍走 `runLazy`，不进 openPage；confirm 弹框不影响 tab 求值 |
| 韩剧TV.Q | 列表/详情不变（引擎层，7c 不动） |
| 麻花影院 | `x5_webview_single` 卡仍走 Fragment `isX5Url` 支；裸 `x5://` 卡走分发器 x5 支；`handleDealUrl` 委托不变 |
| 西瓜影院 | `hiker://page/home` 卡 → `openPage` → `getNextPage` → 新 `HkPageActivity`（规则 JSON 直供）；组页失败回落 5e `openSubpage` |
| 阅动漫 | `hiker://page/Main.view` → 同上链路；`$.importParam` 经页地址 query 参数挂/清；ClassTab 解构链路通 |
| 星辰影视123.Dr / 青苹果♨️ | 只验报错清晰与缓存回退（gitcode 418 不变），不声称能出数据 |

复核证据（7c）：`confirm()`（JS 函数）在 4 份验收规则档（奇优/童话/臻品fix 三 JSON +
阅动漫 rule.json）里**零调用**——唯一含 "confirm" 字样的是阅动漫的 `confirm://` URL scheme，
那是另一通道（`handleConfirmBare`，本笔不动）；`refreshPage` 在臻品fix 与阅动漫 rule.json 里
均有调用（阅动漫约 20 处，`refreshPage(false/true/无参)`），故刷新桥接通后这两条规则的
“改设置/切分类后重刷”才真正生效——7c 前事件投出后无人接，等于没调。

## 10. 阶段 7 结束后整棵移植的状态（收尾结论）

### 10.1 阶段 7 三笔总账

- 7a（`06eb201e`）：`HkPageActivity` 不全搬评估 + 子页面开页（`openPage`/`getNextPage`/
  `$.importParam` 桥）+ `handleDealUrl` 全套 + 合集浏览面 + `HkNonVideo` 承接删除
  （类型谓词归 `HkRouter`、选集路由归 `HkPageActivity`）。
- 7b（`105934e1`）：`HkFavorite`/`HkFavoriteStore`（SP+Gson 原文）/`HkHistoryRecorder`/
  `ToastEvent` 四类在位；collection 支恢复 `toJson()`；点选集记历史（`keyOf` 与播放器
  `getHistoryKey` 同键同行）；三 Fragment `onToastEvent` 错误式呈现；剩余 event 逐个定夺
  （除 `ToastEvent` 外全部不搬，理由见 7b §6）。
- 7c（本笔）：订阅不搬、m3u8 复核、歌词不搬、ConfirmHandler 接管、刷新桥接通、parse 桥接线、
  三处过时注释清理、`HkRefreshPageEvent` 删除。

### 10.2 还缺什么（已知缺口，按影响排序）

- `HkLazyRuleParser.parse()` 桥已接、调用方待接入（弹幕搜索等；星辰同样只有两处调用方）。
- `HkHistoryRecorder.updateProgress` 已搬、借面回写待接（阅读器/音乐会话的进度回写；
  现状：海阔历史行只记“打开过、到哪集”，不记集内万分比/毫秒）。
- JS `showSelectOptions` API 缺失（7b §6 记名）：`HkJsRuntime` 无此 putFunction，事件亦不搬；
  `select://` 裸 scheme 由 `HkClickDispatcher` 处理。
- `$.importParam` 桥只包一次 `home()` 求值（7a 记名接受，与共享桥 varMap 同类局限）。

### 10.3 已知不搬的（决策已定，不再翻）

- 首页订阅（`HkHomeSubService` + 两记录类 + `OnSubRefreshEvent`/`OnArticleListRuleChangedEvent`）。
- `HkLyricLoader`（依赖不存在的 `HkPlayCompanion`；歌词已接通）。
- `HkFavoriteActivity`/`HkHistoryActivity`（星辰收藏/历史页；读口是规则 JS + 现有历史页）。
- `.hkshell` 脚本执行面、WebView holder 面（`HkWebkitHolder` + web/* 8 个 event）、
  阅读器会话栈（`ReadingRouter`/`ReadingSession`/`ReadingStore`）、电台问询（`HkRadioRouter`）、
  星辰 hk 总壳其余部分（规则列表/导入导出总壳已有我方对位）。
- 剩余 event（7b §6 清单）：`OnPageChangeEvent`、`OnRefreshEvent`、`ShowFileChooserEvent`、
  `ShowSelectOptionsEvent`、`PlayChapterEvent`、web/* 8 个——引擎零引用，不搬。

### 10.4 已知小瑕（接受，不修）

- `HkShareHelper` 合集包导入：单条/多条均直接导入，多条勾选面不另建（7a 记名）。
- 子规则页返回栈由 Activity 栈承接（星辰 `openPage` 的 `subStack` 压栈/恢复不另搬）。
- `handleDealUrl` pics 支保留 5d 的“无图拦截 toast”（星辰“入口无图不许拦”是因其阅读器自带
  回退解析，我方无该回退，拦截仍是正确行为）。
- `HkFavoriteStore.migrateIfNeeded()` 在我方是空扫（hk 代码从未往 Keep 写收藏，首启即打完成标记）。
- `HkJsRuntime.refreshPage` 的 200ms 节流（`lastRefreshPageAt`）照原文保留：200ms 内连续调只发一次事件。
- 列表点击的 Vod 转换处仍丢 `extra`（7a 记名）：`openPage` 传给 `getNextPage` 的 paramsJson
  目前是空串，组页以 pageUrl 本身为准；`MY_PARAMS` 的 extra 路待以后补。
