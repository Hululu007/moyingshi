# 移植阶段 5c：列表 / 搜索 UI 替换（适配改写，非逐字搬）

> Recovery anchor（任务：miniapp-xingchen-5c；lane：standard；分支 main；HEAD c16c438b）
> 目标：阶段 5 第 3 笔——列表页与搜索页改走新引擎：HkListFragment /
> HkSearchFragment 的取数源由 HkExecutor（旧链路 + 适配段）换成 HkRouter
> （新引擎门面，经 HkEngineManager 常驻运行时）；HkCard 加两个静态转换
> 工厂（fromItems/fromSearchResults，自 HkExecutor 的私有转换逐字移入）；
> x5 卡二选一落定。本地提交 + recovery tag，不推不编。做完即停，不开 5d。
> 范围：改 ui/hk/HkListFragment.java、ui/hk/HkSearchFragment.java、
> api/hk/HkCard.java + 本文件。HkListActivity/HkSearchActivity 壳子不动。
> 状态：已改写、grep 与 diff 已验（见 §6），待 guard finish 提交。

## 1. 为什么不是逐字搬星辰 HkListActivity（3268 行）/ HkSearchActivity（748 行）

任务书要求“替换我方现有的列表/搜索页”，计划 §1.5 同款。逐字搬运经核对
**不可行**，三类硬性阻断（逐项 grep 核实，非推测）：

### 1.1 HkListActivity（3268 行）：XPopup 禁令 + 阅读器/文章生态

- `import com.example.hikerview.ui.view.popup.MyXpopup`（+ `PopImageLoader` /
  `PopImageLoaderNoView` / `XPopupImageLoader`）：XPopup 是任务书硬约束明文
  禁止引入的（“不引入 LitePal、dex 动态加载、Clash、XPopup”）。逐字搬即
  违反硬约束。
- `import com.fongmi.android.tv.read.ReadingGate/ReadingRouter/ReadingSession/
  ReadingStore`（小说阅读器）+ `HkArticleFragment`（5825 行，文章/漫画页）
  + `HkRouteBlocker`（694 行）+ `FilmListActivity` + `utils/download/
  DownloadManager` + `ui/activity/VideoActivity` 播放面直连：整套是星辰
  的 HkPageActivity 网页/文章/阅读生态，单页无法剥离；我方无对应类，
  逐字搬要连带搬入数千行非 hk 引擎代码，超出“只改 hk 引擎相关文件”的范围。
- `event/home/OnRefreshEvent`、`event/rule/ShowSelectOptionsEvent`：我方
  event/* 只搬了 3e 的 20 个 POJO，这两个不在其中（阶段 7 event/* 按需）。
- 结论：**不逐字搬**。我方列表页是“Activity 壳子 + Fragment 取数渲染”
  结构（壳子 99 行 + Fragment 461 行），与星辰单体 Activity 不是同一
  结构；替换的等价落点是 Fragment 的取数源改走 HkRouter（§3），壳子、
  布局、分类 chips、下拉刷新、翻页、缓存键、三态 UI 全部保留。

### 1.2 HkSearchActivity（748 行）+ HkSearchFragment（1239 行）：Clash + 缺失依赖与布局

- 搜索子系统连带 `HkSearchModel`（165 行，`import com.fongmi.android.tv.
  clash.ReadingProxyGate`——Clash，硬约束禁入）、`HkSearchSession`（244）、
  `HkSearchParser`（221）、`HkSearchAdapter`（206）、`HkSearchFragment`
  （1239，`import com.scwang.smart.refresh.layout.SmartRefreshLayout`——
  我方 gradle 无 SmartRefreshLayout 依赖）。
- `ui/hk/view/HkCenterListPopup`（130 行）+ `HkCenterListAdapter`（125 行）
  依赖我方 res 内不存在的布局 `R.layout.pop_custom_center_recycler_view` /
  `R.layout.item_rect_radius[_dark]` / `R.layout.item_rect_radius2[_dark]`
  与 `HkTextViewUtils`；本阶段 scope 不含 res/，不新增布局文件。
- 结论：**不逐字搬**。等价落点同 1.1：我方搜索页壳子（112 行）+ Fragment
  （356 行）保留，取数源改走 `HkRouter.search`（§3）。星辰的多引擎聚合
  （ViewPager2 每发动机一页）我方本就是单规则搜索，不搬聚合形态。

### 1.3 ui/hk HkDetailBridge（1299 行）：缺失布局与 drawable，留给 5d 定案

- 依赖同款 `view/HkCenterListPopup` / `HkCenterListAdapter`（§1.2 的缺失
  布局）与 `R.drawable.bg_round_all`（我方 res 内无此 drawable）。
- 本笔不搬 ui 版 HkDetailBridge；详情页桥接走 5a 已搬的 **api 版
  HkDetailBridge.toVod**（5d 接线时落到 HkDetailFragment）。

## 2. 本笔改动清单

| 文件 | 改动 |
|---|---|
| ui/hk/HkListFragment.java | 取数源 HkExecutor.homeDetailed → HkRouter.home（§3）；router 懒建 + destroy；surfaceOpened/Closed 记账；失败语义见 §3.3 |
| ui/hk/HkSearchFragment.java | 取数源 HkExecutor.search → HkRouter.search（§3）；同款 router 生命周期 + surface 记账；失败语义见 §3.3 |
| api/hk/HkCard.java | 新增 `fromItems(List<HkItem>)`、`fromSearchResults(List<SearchResult>)` 两个静态工厂（§3.2） |
| （不动） | HkListActivity/HkSearchActivity 壳子、布局文件、HkListHelper（5d 删）、HkActionHandler（5d 删）、HkCache、HkDetailFragment（5d）、VideoActivity、VodFragment、BaseLoader、底栏、TV |

## 3. 接线（新引擎进列表/搜索链路）

### 3.1 Router 生命周期

- 每个 Fragment 持一个 `HkRouter mRouter`（懒建，首次取数时在后台线程建；
  构造器抛异常时按取数失败收口）。`HkRouter` 构造器经
  `HkEngineManager.runtimeFor(rule)` 取进程唯一桥 + `ensureInit`（每进程
  只 init 一次）+ `HkEngine.wrapResident` 包裹——分类点击的 putMyVar 与
  页面刷新求值落在同一台运行时上（5a §3.4 的修法 A），旧链路每次执行新建
  引擎的“写读丢弃”问题到此终结。
- `onDestroy()` 时 `mRouter.destroy()`（adopted 常驻引擎：空操作，不销毁
  桥）+ `HkEngineManager.surfaceClosed(mRule)`。`bindRuleLoaded()`（规则
  读到后）调 `HkEngineManager.surfaceOpened(mRule)` 记一面；归零时桥清该
  规则名下的 JS 定时器/任务（星辰 S2 落修，HkEngineManager 原文行为）。

### 3.2 HkItem/SearchResult → HkCard 转换

HkExecutor 的两个私有转换（4c 引入）逐字移入 HkCard 作公开静态工厂，
HkExecutor 内的私有副本留待 5d 随 HkExecutor 一并删除（本笔不删，避免
HkExecutor 在中间态少方法）：

- `HkCard.fromItems(List<HkItem>)`：title/url/pic/desc + `setColType`，
  null 条目跳过（HkExecutor.toCards :861-873 逐字）。
- `HkCard.fromSearchResults(List<SearchResult>)`：title/url/img→pic/desc
  （HkExecutor.searchResultsToCards :874-884 逐字；colType 不设，与旧路一致）。
- 转换之后仍走 `HkListHelper.pickCards/toVods/dedupeByUrl/styleFor/columnsFor`
 （本笔不删 HkListHelper；5d 按 5b §3.2 落地 HkColType 时替换）。

### 3.3 失败语义变化（星辰门面收口，写清）

- 旧路：`HkExecutor.homeDetailed/search` 失败抛 `HkEngine.HkException` →
  Fragment 走错误态（`vods == null` 分支）。
- 新路：星辰门面 `HkEngine.home()/search()` 捕获全部异常后**回空表**
  （3e 门面原文收口，`SpiderDebug.log("hk", "home failed …")` 留痕），
  不抛错。故 Fragment 改判：取数回空表时读 `mRouter.getEngine().getError()`
 （`HkEngine.getError()` → `jsRuntime.getError()`，门面已在位）；非空即
  视为失败，走原错误态分支（文案为 getError 内容）；为空即真空（0 条内容态
  + 命中诊断）。preRule/抓页失败由运行时经 `noteFetchFailure` 进错误通道。
- 命中诊断收窄（与 4c §4.1 同款）：旧 `diag` 含页面大小（"页面约 XKB，
  得到 N 张卡片"），新路门面不回页面大小，诊断只写“得到 N 张卡片”
  （选择器无页面可解析的分支说明保留：`hiker://empty` 等非 http(s) 首页
  地址仍得 0 条，诊断注明）。

### 3.4 点卡片行为：本笔不动（5d 接管）

`HkActionHandler.handle`（动作 URL/懒 JS/子页面）与 x5 卡分流（`HkListHelper.
isX5Url` → CatWebActivity）本笔原样保留；5d 由 `HkRouter.dealKind` 分流
承接后删除 HkActionHandler。x5 决策见 §4。

## 4. x5 卡二选一（任务书指定，写进阶段文档）

**选：继续用 0836 版的 CatWebActivity 顶替方案（点卡进站内 WebView），
不做星辰 HkListActivity 的列表页内嵌 WebView。**

理由（逐项核实）：

1. 星辰所谓的“内嵌”并不是在列表里嵌 WebView 渲染卡片：`HkListActivity.
   openX5PlayPage`（:2187-2188）是**新开一个专用网页页**（`carrierPage=true`
   → “官 WebViewActivity 等价物”，:432-435），x5 卡点开后进新页的 WebView，
   与我方 CatWebActivity（点卡新开站内 WebView 页）**同构**——不是两种方案，
   是同一种方案的两种落点。
2. 星辰该页的 WebView 承载依赖 `HkWebCarrier`（178 行）+ `HkWebkitHolder`
   （2528 行）+ `HkX5WebViewParser`（151 行）+ `HkX5Extra` 的全桥生态，
   且星辰注释（HkListActivity:526）明言“内网页模式（他亲定 x5 走系统
   WebView）”——星辰自己也没有 X5（腾讯 TBS）引擎，走的系统 WebView。
   我方同样无 X5 引擎，CatWebActivity 就是系统 WebView。
3. 麻花影院那张 x5 卡的渲染结果（网页内容本身）在两种落点下一致；差异只在
   页栈形态（新开 Activity vs 列表内嵌），而我方列表页是 Fragment 结构，
   内嵌 WebView 要改列表渲染管线（VodAdapter/GridLayoutManager + WebView
   行类型），收益为零、风险全在 UI 侧。
4. 影响面：leanback 零影响——列表/搜索页只在 mobile 源集（Fragment 类注释
   已写明 TV 不可见），CatWebActivity 本就是 mobile 页；TV 端无 x5 卡入口。

## 5. 8 条规则核对（列表/搜索链路，代码级）

核对方法：每条规则的 find_rule/searchFind 在新链路（HkRouter.home/search
→ HkEngine 门面 → HkJsRuntime.parseList/parseSearch 或 HkSelector）上的
求值路径，与 4c §5 的旧路逐段对照。详情/懒加载播放链路属 5d（详情页改走
Router.detail 后统一核对）。

| 规则 | 列表/搜索新链路 | 结论 |
|---|---|---|
| 奇优影院♔ | selector find_rule → 门面选择器支 `selector.parseList`（4c 已验等价）；搜索 `;post` → 门面 `parseSearch`，desc 多拼一段 `.detail&&Text`（4c §5.1 已记，星辰行为，只影响展示） | 走通 |
| 童话 | 同款 selector 两路；搜索第 5 段 `*` 的 detail 回 `""`（4c §4.3），desc 取 `.set&&Text` 非空时同值 | 走通 |
| 臻品fix | 全 js: → 门面 JS 支 `parseList/parseSearch`（`setListContext` + 预抓 + `setEvalResCodeBody`），与旧 evalFindRule 的求值口径一致；`getExports` 等 3e 适配段缺口只影响 HkSubpage.requireOne 路，不影响列表/搜索 | 走通 |
| 韩剧TV.Q | JS find_rule → 门面 JS 支；`.replace` 重载问题由星辰 `retStr + ''` 包装承接（3b 已搬，0836 的 `__jsStr` 补丁不再需要） | 走通 |
| 麻花影院 | selector 列表 → 门面选择器支；标题 UniqueTag 由星辰 `scriptableString`（NOT_FOUND → `""`）承接（3b 已搬）；x5 卡点开走 CatWebActivity（§4） | 走通 |
| 西瓜影院 | `hiker://empty` + `hiker://page/home` JS 首页 → 门面 JS 支；`hiker://page/` 子页面点开由 5d 的 Router.dealKind 分流（本笔列表页只出卡，不点子页面） | 列表走通；子页面点开属 5d |
| 星辰影视123.Dr | preRule require gitcode → 门面 JS 支跑 preRule（`parsePreRule`，3e 适配段 `evalPreRule` 同款入口）；gitcode 418 时 require0 回退本地缓存、无缓存抛错 → 门面回空表 + getError 报错，Fragment 走错误态（报错清晰，见 §3.3） | 报错清晰；首次仍无数据（418，见计划 §4） |
| 青苹果♨️ | preRule initConfig + rc(config.Btea) → 门面跑 preRule；gitcode 418 时同上回空表 + getError | 报错清晰；首次仍无数据（418） |

## 6. 验收证据（代码级，未构建）

- **调用链 grep**：HkListFragment/HkSearchFragment 内 `HkExecutor.homeDetailed/
  HkExecutor.search` 零命中；`new HkRouter(`（各 1 处，routerFor 内）与
  `router.home(/router.search(` 调用点在位；`HkEngineManager.surfaceOpened/
  surfaceClosed` 调用点在位（各 1 处开/1 处销）；`HkCard.fromItems/
  fromSearchResults` 定义与调用点在位。
- **HkExecutor 引用变化**：列表/搜索两页不再调 HkExecutor；剩余调用方
  （HkDetailFragment、VideoActivity、HkNonVideo、HkWarmup、HkSubpage、
  HkActionHandler）属 5d；另 HkRule/HkCard/HkEngine/HkParserKit 内只有注释提及。
- **包名/禁入残留**：本笔不新增类；`com.fongmi.android.tv|LitePal|
  DexClassLoader|SourceGate|GlobalProxy|XPopup|Clash` 在改动三文件内零命中。
- **git diff --check** 通过（finish 前复核）。
- **改动文件列表与行数**：HkListFragment +58、HkSearchFragment +54、
  HkCard +31，本文件；合计 +143/−15（`git diff --stat`，不含本文档）。
- **javac 坑复核**：本笔新增代码无 lambda 捕获重赋值变量（沿用旧 `finalVods`
  定值拷贝写法）；`HkCard.fromItems` 返回 `List` 不赋给 `JsonArray`（无 JSON
  涉入）；无匿名 LinkedHashMap、无 `JsonElement.isString()`。

## 7. 遗留（5c 结束后仍缺什么）

- 5d：详情页改走 `HkRouter.detail` + api `HkDetailBridge.toVod`（HkDetailFragment
  重写；`HkDetailHelper.Detail` → Vod flags 转换层；lastChapter 缺口——Router
  无 lastChapter 字段，5d 定恢复或接受丢失）；播放页 `VideoActivity` hk 分支
  改走 `HkRouter.play`（HkPlayer 由 HkPlay/HkPlayData 承接）；删除清单
  （HkListHelper、HkActionHandler、HkNonVideo、HkDialogs、HkSubpage、HkWebRule、
  HkExecutor 瘦身/删除、HkPlayer）；HkHikerRuleUtil history 支恢复；3e 适配段
  缺口复核（getExports、setImportParam、jsValueFromCards、evalTextRule 纯字符串
  setResult、evalFindRule 直接 return 数组回落、setResult 空数组 wasResultSet）；
  HkLazyRuleParser.parse 桥注入定案；`proxyClearM3u8` JS 函数接线定夺；8 条规则
  全链路（列表/搜索/详情/懒加载播放）统一核对。
- 阶段 6：云口令两法、HkCloudClip 等；阶段 7：HkPageActivity、剩余 event、
  HkWebkitHolder 全桥、ui HkDetailBridge（待缺失布局/drawable 补齐后评估）。
  均不提前搬。
