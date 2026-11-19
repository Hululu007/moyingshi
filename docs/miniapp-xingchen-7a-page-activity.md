# 阶段 7a：HkPageActivity 评估 + 子页面（getNextPage）/ handleDealUrl / 合集浏览面 + HkNonVideo 承接删除

> Recovery anchor — 目标：阶段 7 第一笔。星辰 `HkPageActivity`（8476 行）评估后**不全搬**，
> 只搬子页面（`getNextPage` 开页）+ `handleDealUrl` 全套 + 合集浏览面；`HkNonVideo` 的
> 非影视分流由 `HkRouter`（类型谓词）+ 新 `HkPageActivity`（选集路由）承接后删除；
> `HkListFragment` 新增“规则 JSON 直供”模式承载子规则页。
> 验收：`hiker://page/` 卡经 `getNextPage` 组子规则开新页（阅动漫 Main.view 链路写清）、
> `handleDealUrl` 五种全套在列表/详情两面闭合、`openCollectionBrowser` 在位且合集包
> added 计 0 的 6b 小瑕随之修、`HkNonVideo` 删除后全树零引用、`git diff --check` 通过、
> 0 leanback。只做 7a，做完即停，不开 7b；不推、不编、不触发 workflow。
> 状态：文档先行（2026-11-19 17:25）；实现待 guard 会话。下一步唯一动作：开 guard 7a，按 §6 实施。

- 完成句：HkPageActivity 瘦身版落地、子页面走 getNextPage、handleDealUrl 与合集浏览面闭合、HkNonVideo 删除，提交后 7a 完工。
- 允许路径：`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/activity/HkPageActivity.java`（新）、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkListFragment.java`（规则 JSON 模式）、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkDetailFragment.java`（handleDealKind 委托 + 非影视调用点改名）、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkMiniappActivity.kt`（合集包导入后开浏览面）、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkClickDispatcher.java`（hiker://page 改走 openPage + dealKind 短路）、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkRouter.java`（接管 HkNonVideo 类型谓词）、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkShareHelper.java`（合集待浏览目录记账 + 注释更新）、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkNonVideo.java`（删除）、
  `app/src/mobile/AndroidManifest.xml`（仅新增 HkPageActivity 一条注册，与 HkListActivity 同形）、
  `docs/miniapp-xingchen-7a-page-activity.md`（本文档）。
- 保护路径：无初始脏文件（`git status` 干净，HEAD `705d473a`）。
- 本地时间：2026-11-19 17:25 CST（Asia/Shanghai）。
- 预计：实现约 2–2.5 小时；验收约 30 分钟；预计 20:30 前提交。
- 最便宜的决定性验证：`grep -rn "HkNonVideo" app/src/` 代码命中为零
  + `grep -rn "getNextPage" HkPageActivity.java` 在位 + `git diff --check` 通过。

## 1. 来源与范围

- 任务书：阶段 7 第 1、9 项（HkPageActivity、合集浏览面）；计划文档
  `docs/miniapp-xingchen-port-plan.md` §2 阶段 7、§1.5、§1.7（HkNonVideo 由
  HkRouter + HkPageActivity 承接：删除）；5e 文档 §2.1/§2.7/§2.11、§8
  （`pics://` 直接进阅读器、子页面 `getNextPage`、HkNonVideo 删除均记名阶段 7）；
  6b 文档 §2.6/§5（合集包只解压落盘、added 计 0、浏览面在阶段 7，文案未区分“已解压待点选”）。
- 星辰源码：`~/workspace/xingchen-ref`（HEAD `52450c0`）；交叉验证
  `~/workspace/hk-ref/src/sources/`（oktv 反编译）；语义参考 `~/workspace/hikerview-ref`。
- 本笔不做：7b（收藏/历史/剩余 event）、7c（订阅定去留、m3u8/歌词收尾、ConfirmHandler、
  刷新桥、HkLazyRuleParser 桥、HkParserKit:317 注释）——只在 §7 记名。

## 2. HkPageActivity 评估：不全搬（先评估、后实施的结论）

### 2.1 规模

- 星辰 `app/src/mobile/java/com/fongmi/android/tv/ui/activity/HkPageActivity.java`：
  **8476 行、315 个方法**（`grep -cE "^\s+(public|private|protected).+\("` 计）。
  它不是“子页面宿主”，而是星辰的 hk 总壳：规则列表（含分组/拖拽排序/多选删除/导入导出/
  云口令/扫码）、内容列表（分类/筛选/分页/滚动记忆）、详情 V4、内嵌 WebView（x5/网页桥）、
  阅读器/音频/漫画会话入口、合集文件浏览、外部 intent/文件后缀分发、剪贴板云口令嗅探、
  全局 JS（.hkshell）执行面。

### 2.2 依赖（我方树内不存在的，逐项核对）

| 星辰依赖 | 我方状态 | 结论 |
|---|---|---|
| `read/ReadingRouter`、`ReadingSession`、`ReadingStore`（小说/漫画会话） | 不存在；我方阅读器是 `ui/novel/NovelRouter` + `WebReaderActivity` | 不搬；阅读器承接走我方现有 NovelRouter（见 §3.5） |
| `ui/hk/HkArticleFragment`（文章/阅读器面，ConfirmHandler 唯一登记处） | 不存在 | 不搬；ConfirmHandler 接管是 7c |
| `ui/hk/HkAdUrlBlocker`、`HkWebViewHolder`、`HkWebkitHolder`、`HkX5Extra`、`HkDialogs`、`HkDynamicBgView`、`HkGlassDrawable`、`HkImageUrlMap`、`HkPressFeedback` | 不存在（HkDialogs 5e 已删） | 不搬；x5/网页走 5c 已定的 `CatWebActivity` 顶替 |
| `ui/HkRadioRouter`（电台问询，handleDealUrl 的 web 支前置） | 不存在 | 不搬；web 支直开 CatWebActivity（见 §3.3） |
| `ui/hk/HkFavoriteActivity`、`HkHistoryActivity`、`AudioBookActivity`、`ComicReaderActivity` | 不存在 | 不搬；收藏/历史对接是 7b |
| `ActivityHkBinding`（星辰 hk 总壳布局） | 不存在；我方列表/详情各有布局 | 不搬 |
| `HkFavoriteStore` | 不存在（7b 搬） | 本笔不依赖它 |

### 2.3 与我方现有面的重叠

| 星辰 HkPageActivity 的面 | 我方已有对应物 | 重叠结论 |
|---|---|---|
| 规则列表 + 分组/导入/云口令 | `HkMiniappActivity`（管理页）+ 实验室“海阔小程序”分组 | 已有，不重复建 |
| 内容列表（分类/筛选/分页） | `HkListActivity` + `HkListFragment`（5c） | 已有 |
| 详情 V4 | `HkDetailActivity` + `HkDetailFragment`（5d） | 已有 |
| 导入导出/扫码/云口令 | `HkShareHelper`/`HkPublishHelper`/`HkQrUtil`/`HkCloudClip`（6a/6b） | 已有 |
| 子页面 `openPage`（getNextPage 组子规则） | **无**：5e 只有 `HkSubpage.expand` 单页渲染 | **本笔搬**（§3.1） |
| `handleDealUrl`（pics/x5/web/image/magnet） | 只有详情面 `HkDetailFragment.handleDealKind`（5d）；列表面无 | **本笔搬**（§3.3） |
| 合集浏览面 `openCollectionBrowser` | **无**（6b 记名） | **本笔搬**（§3.4） |
| 非影视选集路由（小说/漫画/音乐/图片） | `HkNonVideo.routeEpisode`（自研，见 §3.5） | **搬家后删除原文件** |

### 2.4 TV 影响

- 星辰文件在 `app/src/mobile/` 源集；我方新文件同放 mobile 源集，leanback 零文件、零影响。
- 新 Activity 只在 mobile manifest 注册；leanback manifest 不动。

### 2.5 评估结论

**不全搬。** 全搬等于把星辰 hk 总壳（含规则管理、阅读器会话、WebView holder、电台、
收藏/历史页）连同 10 余个不存在的依赖一起复制进来，与我方已有的管理页/列表页/详情页
大面积重复，且违反“只改 hk 引擎相关文件、品牌/包名/玻璃/短剧/本地源/实验室/TV 不动”的
硬约束。只搬三件缺口（§3.1 子页面开页、§3.3 handleDealUrl、§3.4 合集浏览面）+
一件搬家（§3.5 HkNonVideo 承接删除），其余面（规则管理、阅读器会话、WebView holder、
电台、收藏/历史页、订阅冷启）明确不搬。

## 3. 设计决定（每项都有 grep/代码证据）

### 3.1 子页面开页：新 `HkPageActivity.openPage`，经 `HkPageParser.getNextPage` 组子规则

- 星辰等价路有两条，同源不同宿主：`HkPageActivity.openPage(item, url)`（`:6152-6230`，
  本页内压栈切子规则）与 `HkListActivity.openPageUrl(rule, pageUrl, position)`（`:2614-2645`，
  `getNextPage` 组子规则后经 `doOpenSubList` 开新页）。我方无 `HkArticleFragment.SubListRequest`/
  `doOpenSubList`（文章面未搬），走第二条的我方对位：**新开一个 Activity 承载子规则列表页**。
- `openPage(Context context, HkRule parentRule, String pageUrl, String extraJson, String title)`：
  `HkPageParser.getNextPage(parentRule, pageUrl, extraJson)`（4b 已搬，`:131`）组子规则；
  组不出（返 null / 抛 `ParseException`）→ `Notify.show("找不到对应的页面")`、回 false、
  不开新页（星辰 `openPageUrl` 同款：失败弹错留本页，绝不拿原规则回落开页——回落即
  “点二级又重开一个首页”）；组出后 `sub.setPages(itemPages)`（星辰 `openPage` 同款：
  条目 `pages` 位覆写组出规则的 `pages`，`HkItem:48`；本笔条目无 pages 位时跳过——
  `HkCard` 无 `pages` 字段，`getNextPage` 产出的子规则自带 `pages`，不覆写）、
  `sub.clearNav()`（星辰同款），经 Intent（extra `EXTRA_RULE_JSON = sub.toJson()` +
  `EXTRA_TITLE`）启动 `HkPageActivity`。
- `HkPageActivity` 本体（瘦壳）：`BaseActivity` + `ActivityHkListBinding` 复用（与
  `HkListActivity` 同一布局）；`initView` 按 extra 规则 JSON（`HkRule.parse`，失败回
  toast 关页）+ 标题（子页面名，调用方已按星辰 `openPage` 的“规则 pages 按 path 找 name”
  回退链算好：页名 → 条目标题 → 父标题）挂 `HkListFragment.newInstanceWithRuleJson(json, title)`；
  工具栏搜索项隐藏（子规则未登记进站源，`HkSearchActivity` 按标题读规则会串回父规则，
  与 5e 子页面模式“搜索项隐藏”同款理由）。
- `HkListFragment` 新增“规则 JSON 直供”模式（`newInstanceWithRuleJson`，args `rule_json`）：
  `initView`/`retryBindRule` 里 `rule_json` 非空时 `mRule = HkRule.parse(ruleJson)`（失败走
  现有“规则缺失”错误态），不走 `HkStore.read(title)`；`bindRuleLoaded` 走正常分支
  （`setClasses()` + `setStyle(mRule.getColType())`，子规则自带分类与样式）；`loadPage`
  里规则 JSON 模式跳过 `HkCache` 读写（与 5e 子页面模式同款理由：子规则标题常与父规则同名，
  键是规则标题+分类+页码，读写会与父规则首页互串）；`onLoadMore` 不拦截（子规则是真规则，
  有分页定义，与 5e 单页模式不同）。
- `HkClickDispatcher.handle` 的 `hiker://page/` 支（`:134-137`）改走新路：
  `HkPageActivity.openPage(activity, host.rule(), url, card.getExtra(), cleanTitle(card.getTitle()))`
  ——extra 即条目 `extra` JSON（`HkCard.getExtra` 在位，`getNextPage` 的 `paramsJson`，
  供 `MY_PARAMS` 注入）；列表点击目前在 `HkListFragment.onItemClick:479` 按 Vod 字段
  重建 `HkCard`，extra 在 Vod 转换处丢失，故列表路传进来的 extra 现为空串（与 5e 的
  `expand` 路同口径——它同样不读条目 extra），详情动作卡（`HkCard.fromDetailItems`
  保留 extra）则能传真值；extra 恒缺时 `getNextPage` 以 pageUrl 本身为准组页，不崩。
  组页失败时回落 `host.openSubpage(url, title)`（5e 的 `HkSubpage.expand` 单页路，
  兜底不断链；`getNextPage` 找不到页时 `expand` 同样按 pages 查，失败则空页——
  两路失败形态一致，不静默变首页）。
  `dispatchLazyResult` 的 `hiker://page/` 支（`:332`）不动（懒结果无条目 extra 语境，
  沿用 5e 的 `openSubpage`）。
- `$.importParam` 桥（本笔新增，子页面走通的关键）：`$.importParam` 只由
  `HkSubpage.expand:196` 经 `HkEngine.setImportParams(parsed.query)` 注入（5e 的
  3e 缺口③恢复）；`Router.home` 路从不调它，而子规则的 `find_rule`（如阅动漫的
  `ClassTab` 页规则 `var {...} = $.importParam`）在求值时要读它。桥法：
  `HkPageActivity.openPage` 用 `HkSubpage.parse(pageUrl).query` 取页地址 query，
  随 Intent（extra `EXTRA_IMPORT_PARAMS`，JSON 串）交给新页；
  `HkListFragment` 规则 JSON 模式在 `loadPage` 的后台段、`router.home(...)` 调用前后
  加 `engine.setImportParams(query)` / `finally` 内 `setImportParams(null)` 收尾
  （只包 `home()` 一次求值；`importParams` 是共享桥上的 volatile 字段，
  窗口内并发求值的他规则理论上可读到它——与共享桥 `varMap` 同类的已知局限，
  窗口仅一次 `home()` 求值，记名接受；不改引擎全局行为，不给所有规则都挂 `$.importParam`）。
  `MY_PARAMS` 不走此桥（它唯一来源是规则 `params` 字段，`getNextPage` 的
  `setParams(paramsJson)` 已落，引擎 `generateMyParams` 既有路承接）。
- 5e 的 `HkListActivity.openSubpage` / `HkListFragment` 子页面模式保留（懒结果支与旧子页面
  仍用它；`HkPageActivity` 与之并存，不互相改写）。

### 3.2 阅动漫 Main.view 走通链路（代码级）

- 规则档：`~/workspace/miniapp/test-rules/阅动漫.hkzip` 内 `rule.json`（`title=阅动漫`、
  `type=all`、`find_rule` 为 JS、`pages` 数组含 `ClassTab` 等子页面定义；`Main.view`
  是其 `pages` 中的一个 path——实现时以该档实测的 path 名为准核对）。
- 链路：首页 `find_rule` 求值出卡（卡 URL `hiker://page/Main.view?...`，extra 含
  `MY_PARAMS`）→ `HkListFragment.onItemClick` → `HkClickDispatcher.handle` →
  `hiker://page/` 支 → `HkPageActivity.openPage` → `HkPageParser.getNextPage`
  （按 path 在 `pages` 里找页，用其 `rule`/`col_type` 拼子规则，extra 的 `rule` 键换规则、
  `col_type "*" `回退、`params` 只在 extra 为 `{...}` 时写）→ `sub.clearNav()` →
  新 `HkPageActivity`（规则 JSON 直供 + `EXTRA_IMPORT_PARAMS` 带页地址 query）→
  `HkListFragment` 正常 `setClasses`/`loadPage` → `loadPage` 后台段在 `router.home`
  前后挂/清 `$.importParam`（§3.1 末段的桥）→ `HkRouter.home` 跑子规则 `find_rule`
  （`ClassTab` 的 `$.importParam` 解构有值）出卡；子页面内再点子页面同路嵌套
  （返回栈自然嵌套，与星辰 `openPage` 的 `subStack` 压栈等价——我方用 Activity 返回栈承接）。

### 3.3 `handleDealUrl` 全套：新 `HkPageActivity.handleDealUrl`，详情面委托、列表面短路

- 星辰本体：`HkPageActivity.handleDealUrl(kind, url, item)`（`:4918-5051`），五种
  pics/x5/web/image/magnet，default 回退 V4。我方对位：
  - `pics`：星辰进漫画阅读器（`ReadingSession` kind=COMIC + `ReadingStore` 进度续读）；
    我方无 Reading 会话栈，对位是 `HkDetailFragment.handleDealKind` 的 pics 支（5d 已定）：
    `HkRouter.parsePics` 拆图 → 无图 toast `hk_action_pics_unsupported`（星辰“入口无图不许拦”
    的反向：我方无阅读器回退解析，空图集进 `NovelRouter.openPics` 是空白页，故保留 5d 的
    拦截 toast）→ 有图 `NovelRouter.openPics(activity, siteKey, flag, vod, ep, "pics://…")`。
  - `x5`：剥前缀进 `CatWebActivity`（5c/5e 已定，星辰“无 X5 引擎走系统 WebView 顶替”同款）。
  - `web`：直开 `CatWebActivity`；星辰 web 支首段的电台问询（`HkRadioRouter.claimSignal`）
    不搬（`HkRadioRouter` 不存在，电台面不在移植范围），记名。
  - `image`：`PhotoViewerDialog.show(activity, singletonList(url), 0, null)`（5d 已定）。
  - `magnet`：星辰首段 `.torrent` 种子走 `HkDetailBridge.startParseTorrent`、P2P 链走
    自家播放器、其余走系统选应用；我方无 `startParseTorrent`（我方 `HkDetailBridge` 是
    281 行瘦版，无此方法），三段统一收敛为详情面同款 `playDirect`（`VideoActivity.startHk`
    最小 Vod，迅雷链由播放器侧 `SiteApi.hkPlayerContent` 承接——5d 已定“海阔磁力走星辰播放器，
    不维持原版、不走系统选应用”）；`.torrent` 同路（不走系统选应用，与 5d 的 magnet 支一致）。
  - `video`：星辰无此种（default 回 V4）；我方详情面 `handleDealKind` 的 video 支
    （`playDirect`）保留在详情面，不进 `handleDealUrl`。
- 落点：`public static boolean handleDealUrl(Activity activity, HkRule rule, String kind,
  String url, String title, String pic, Vod vod)`（`vod` 为空时按详情面 `minimalVod` 同款
  现拼：站 key `hk://` + id=url + name=title + pic；不 finish——finish 归调用方）；
  未知 kind 回 false（调用方走原路，不擅自回退 V4）。
- 接线两面：
  - 详情面：`HkDetailFragment.handleDealKind` 的 pics/x5/web/image 四支改调
    `HkPageActivity.handleDealUrl(...)`（传 `mVod`），成功后仍 `requireActivity().finish()`
    （5d 行为不变）；video/magnet 两支仍 `playDirect` 不动。
  - 列表/搜索面：`HkClickDispatcher.handle` 在 `x5://` 支之后、落底之前加 dealKind 短路：
    `kind = HkRouter.dealKind(url)`（`:1387`，5a 已搬）；kind 为 pics/magnet/image 时
    `HkPageActivity.handleDealUrl(activity, host.rule(), kind, url, card title/pic, null)`，
    消费后回 true；kind 为 video/web/"" 时不拦截（video/web 走详情页原路，与今日一致）。
    注意：`dealKind` 的 magnet 门含 `isP2pPlayLink` 四 scheme + `isTorrentLink` 种子支，
    与详情面 `Router.detail` 的 dealKind 分流同一张表，不另造判据。

### 3.4 合集浏览面：`openCollectionBrowser` + `handleCollectionEntry`（星辰原文 + 我方适配）

- 星辰本体：`openCollectionBrowser(File dir)`（`:2209-2245`：目录内条目按“目录优先、
  名忽略大小写”排序，空目录 toast“合集目录是空的”，`HkDialogs.cards` 列名点选，
  目录递归进、文件进 `handleCollectionEntry`）+ `handleCollectionEntry(File f)`
  （`:2245-2300`：分发表——`.mht/.mhtml` 走网页、`.hkshell` 走脚本确认面、
  合集内 txt/json/hiker 走文本导入、合集内 hkzip/含 `.hk小程序` 的 zip 走包导入、
  合集内 hkpkg/含 `.hk合集` 的 zip 递归解压再开浏览面、其余 hiker/txt 文本导入、
  zip/hkzip/7z/rar 包导入、媒体走播放器、图片/未知走系统选择器）。
- 我方适配（逐项）：
  - 弹框：星辰 `HkDialogs.cards`（5e 已删）→ `AlertDialog.Builder` + `setItems`
    （同“目录优先、名忽略大小写”排序、同空目录 toast、同“取消”钮；`HkDialogs` 不复活）。
  - `.mht/.mhtml`：星辰 `HkDetailBridge.goWeb`（我方瘦版无此方法）→
    `CatWebActivity.intent(activity, "file://" + path)`。
  - `.hkshell`：星辰 `showHkShellConfirm`（读全文进输入框、确认后 `runHkShellJs`/
    `executeHkShellText` 执行脚本）**不搬**：执行任意脚本文件超出 hk 引擎移植范围，
    且与“不引入 dex 动态加载”的安全边界同源；本支 toast“暂不支持执行脚本文件”，记名。
  - 文本导入（txt/json/hiker）：后台读全文 → `HkShareHelper.importFromText`（6b 已搬）
    → 成功 `Notify.show("已导入…")`（复用管理页 `hk_miniapp_imported` 串，见下），
    失败 toast 异常信息（星辰 `importTextFile`/`importRuleText` 同形）。
  - 包导入（hkzip/zip/7z/rar）：`HkShareHelper.importFromFile`（6b 已搬，返回
    `List<HkRule>`，实计 added）→ 单条/多条均直接导入（星辰 `collectFromFile` 后
    “单条直导/多条勾选”的勾选面对位是我方管理页已有的多选导出框，不另建；
    多条一次全导并报数，记名）→ 导入后 `reloadLocalConfig` 级别的刷新不归本面管，
    回管理页刷新列表（调用方在管理页时 `refreshList` 由 `onImported` 同款触发，
    见下）；包内含合集目录时（`collectFromFile` 的 `collectionDirs`）→
    toast“请选择要导入的合集内容”+ 递归 `openCollectionBrowser(dir)`（星辰同款）。
  - hkpkg/含 `.hk合集` 的 zip：后台 `HkRuleManager.extractCollectionPackage`（6b 已搬）
    → toast“请选择要导入的合集内容”+ `openCollectionBrowser(dir)`（星辰同款）。
  - 媒体（`isHkMediaName` 后缀表逐字搬，`:2430-2438`）：`VideoActivity.startHkPlay`
    我方 mobile 源集无此方法（只有 `startHk(Activity, Vod, …)`）→ 最小 Vod 现拼
    （站 key 取规则管理面占位？本面无规则语境——`HkStore.siteOf` 需规则；改用
    `VideoActivity.startHk(activity, minimalVod(file), "", name, "file://"+path)`，
    Vod 的 site 置空站（`new Site()`，key ""）——播放器内直传 file 地址不走站源解析，
    与 `HkNonVideo.minimalVod` 的直传口径一致；记名）。
  - 图片/未知：`openWithSystem`（`Intent.ACTION_VIEW` + `FileProvider` 分享 URI，
    星辰 `FileUtil.getShareUri` 对位我方 `FileUtil.getShareUri`——实现时 grep 核对，
    无则用 `Uri.fromFile` 回退并记名；`.epub` 与未知后缀同走系统选择器，星辰同款）。
- 6b 小瑕修复（本笔顺带，任务书第 9 项点名）：合集包导入后 added 计 0。
  根因：`HkShareHelper.importFromFile` 的合集支只解压落盘回空列表
  （`HkImportPicker.importFiles` 累计 added=0 → `HkMiniappActivity.onImported(0)`
  走“无规则”分支）。修法：`HkShareHelper` 记一笔待浏览合集目录
  （`pendingCollectionDir`，合集支解压成功后置值；`consumePendingCollectionDir()`
  取走并清零）→ `HkMiniappActivity.onImported(added==0)` 时取走它，非空则
  toast“已解压到合集目录，请点选要导入的规则”+ `HkPageActivity.openCollectionBrowser(this, dir)`；
  浏览面内逐条导入实计 added（§3.4 文本/包导入支），合集包的条目自此有真实导入数。
  `HkShareHelper` 类头“合集支注释引用的 openCollectionBrowser 是阶段 7 的合集浏览面，
  本笔不建”一句随之改成已建。

### 3.5 `HkNonVideo` 承接删除（计划 §1.7 的兑现）

- 结论先行：`HkNonVideo` 是**我方自研**类（第 7 步之九，`docs/miniapp-nonvideo.md`），
  不是星辰类；星辰的对位是非影视会话栈（`ReadingRouter`/`AudioBookActivity`/
  `ComicReaderActivity`，§2.2 已定不搬）。删除它不是“星辰承接”，而是**搬家**：
  行为一字不动，只换宿主——类型谓词归 `HkRouter`，选集路由归新 `HkPageActivity`。
- 拆分（逐字移动，不改语义）：
  - 类型谓词 `isNonVideoRule`/`isReadRule`/`isMusicRule`/`isPictureRule`/`typeOf`
    → `HkRouter` 公开静态方法（`computeLastChapter:306` 的调用点在本类内，
    不再跨 `ui/activity` 取谓词；`HkDetailFragment` 与 `HkPageActivity` 调
    `HkRouter.isNonVideoRule/isReadRule`）。
  - 其余全部（`routeEpisode`/`playVideo`/`minimalVod`/`isHttpUrl`/`sdetail`/
    `evalDetailRule`/`stripLazyMarkers`/`sdetailContent`/`contentFromCards`/
    `postOpen`/`buildFlag`/`novelPayload`/`isImageUrl`/`IMAGE_EXTS`/`Content`）
    → `HkPageActivity` 静态方法（类注释保留原说明 + “5e/7a 搬家”记名；
    `routeEpisode` 仍在后台线程跑、`postOpen` 仍走 `NovelRouter`/`AudioActivity`/
    `VideoActivity`，三者均为我方现有面，不动）。
- 调用点（全树 grep，2 处代码调用 + 1 处类型引用）：
  `HkDetailFragment:465-466`（`isNonVideoRule` + `routeEpisode`）→
  `HkRouter.isNonVideoRule` + `HkPageActivity.routeEpisode`；
  `HkRouter:306`（`isReadRule`）→ 本类 `isReadRule`；
  删文件后全树 `HkNonVideo` 代码命中为零（只剩本文档与 5e 文档的历史记名）。

### 3.6 Manifest 注册

- `app/src/mobile/AndroidManifest.xml`，在 `HkSearchActivity` 注册之后加一条，
  与 `HkListActivity`（`:151-160`）同形：
  `android:name=".ui.activity.HkPageActivity"`、`configChanges="screenSize|
  smallestScreenSize|screenLayout"`、`parentActivityName=".ui.hk.HkListActivity"`、
  `screenOrientation="fullUser"`、intent-filter action `${applicationId}.hk_page` +
  `DEFAULT` category。leanback manifest 不动。

## 4. 验收（代码级，不要等构建；逐项填证据）

- [x] `HkPageActivity.java`（新，mobile `ui/activity/`，804 行）在位：`openPage`（`getNextPage` +
  `clearNav` + `EXTRA_RULE_JSON`）、`handleDealUrl`（pics/x5/web/image/magnet）、
  `openCollectionBrowser` + `handleCollectionEntry`、`routeEpisode` 一族（自 HkNonVideo 逐字移入）。
- [x] `HkListFragment` 规则 JSON 模式在位：`newInstanceWithRuleJson` + `rule_json` args +
  缓存跳过；5e 子页面模式与普通模式行为不变（`isSubpage()` 判定不动）。
- [x] `HkClickDispatcher`：`hiker://page/` 支改走 `HkPageActivity.openPage`（带
  `card.getExtra()`），失败回落 `host.openSubpage`；dealKind 短路（pics/magnet/image）
  在位；其余分支一字不动。
- [x] `HkDetailFragment.handleDealKind`：pics/x5/web/image 四支委托 `HkPageActivity.handleDealUrl`
  （仍 finish）；video/magnet 仍 `playDirect`；`onEpisodeClick` 改调
  `HkRouter.isNonVideoRule` + `HkPageActivity.routeEpisode`。
- [x] `HkRouter`：类型谓词四法在位；`computeLastChapter` 调本类谓词。
- [x] `HkNonVideo.java` 已删；全树 `HkNonVideo` 代码命中为零（只剩 3 处注释溯源：
  `HkPageActivity:385/490`、`HkDetailFragment:430`、`HkRouter:300`；另 `HkRuleFetch:26`
  的旧注释仍列 `HkNonVideo.sdetail`，已是过时，见 §7，归 7c 清理）。
- [x] 合集：`HkShareHelper.pendingCollectionDir` 记账 + `HkMiniappActivity.onImported(0)`
  开浏览面；`HkShareHelper` 类头旧注释已改。
- [x] Manifest：`HkPageActivity` 注册在位，action `${applicationId}.hk_page`；
  0 leanback 文件。
- [x] `git diff --check` 通过；全树 grep 无 `com.fongmi.android.tv`（7a 文件里只有类头注释的
  星辰路径 `app/src/mobile/java/com/fongmi/android/tv/...`；唯一一处包名形命中是
  `HkRule.java:18` 的既有注释，非本笔）、无 LitePal（命中只剩既有注释）、无 dex 动态加载
  （命中只剩 `indexOf`）；改动文件列表与行数统计见 §4 末。
- [x] 8 条规则代码级核对（§5）。
- 改动统计（`git diff --stat` + 2 个新文件）：`AndroidManifest.xml` +11、`HkClickDispatcher.java`
  +35/−9、`HkNonVideo.java` −389（删）、`HkRouter.java` +34/−1、`HkShareHelper.java` +17/−1、
  `HkDetailFragment.java` +44/−50（净 −6 行有效代码）、`HkListFragment.java` +91/−3、
  `HkMiniappActivity.kt` +16/−3；新文件 `HkPageActivity.java` 804 行、本文档 345 行。
  括号平衡：状态机分词器复核 7 个改动文件全部归零。

## 5. 8 条规则代码级核对（7a 增量：子页面开页 + dealKind 短路；其余沿用 5c/5d/5e）

| 规则 | 7a 增量核对 |
|---|---|
| 奇优影院♔ | 普通卡 → 详情页不变；dealKind 短路只命中 pics/magnet/image 卡，奇优卡为 http 详情卡，不命中 |
| 童话 | 同上 |
| 臻品fix | `hiker://empty@lazyRule=` tab 卡仍走 `runLazy`，不进 openPage |
| 韩剧TV.Q | 列表/详情不变（引擎层，7a 不动） |
| 麻花影院 | `x5_webview_single` 卡仍走 Fragment `isX5Url` 支（`CatWebActivity`）；裸 `x5://` 卡走分发器 x5 支（5e 已有） |
| 西瓜影院 | `hiker://page/home` 卡 → `openPage` → `getNextPage` 组子规则 → 新 `HkPageActivity`（规则 JSON 直供）→ 子规则 `find_rule` 出卡；组页失败回落 5e `openSubpage`（`HkSubpage.expand` 单页） |
| 阅动漫 | `hiker://page/Main.view`（path 以规则档实测为准）→ 同上链路；`$.importParam` 经页地址 query 参数（`EXTRA_IMPORT_PARAMS`）在 `home()` 求值前后挂/清；`card.getExtra()` 进 `getNextPage` 的 paramsJson（MY_PARAMS） |
| 星辰影视123.Dr / 青苹果♨️ | 只验报错清晰与缓存回退（gitcode 418 不变），不声称能出数据 |

## 6. 实现顺序（guard 会话内按此序，每步做完 grep 自查）

1. 新 `HkPageActivity.java`（§3.1/§3.3/§3.4/§3.5：openPage + handleDealUrl +
   openCollectionBrowser + routeEpisode 一族自 HkNonVideo 逐字移入）→ 逐行重读。
2. `HkRouter` 接类型谓词（§3.5）→ `HkListFragment` 规则 JSON 模式（§3.1）→
   `HkClickDispatcher` 两处改动（§3.1/§3.3）→ `HkDetailFragment` 委托 + 调用点改名（§3.3/§3.5）。
3. `HkShareHelper` 合集记账 + `HkMiniappActivity.onImported(0)` 开浏览面（§3.4）→
   Manifest 注册（§3.6）。
4. 删 `HkNonVideo.java`（删前全树 grep 确认仅 2 处调用点，均已改）→ §4 验收逐项填证据 →
   `task_guard.sh finish` 提交并打 recovery tag。

## 7. 遗留（不归 7a）

- 7b：`HkFavorite`/`HkFavoriteStore`/`HkHistoryRecorder` 对接现有收藏/历史；
  剩余 event（`ToastEvent` 为首——我方 `HkJsRuntime` 已在 `setError` 无回调支真实投递它，
  类尚不存在，见 7b 评估；其余按引用逐个定夺）。
- 7c：`HkHomeSubService` 定去留（计划 §1.4“延后/不搬”，用户原话不搬首页订阅）；
  `HkM3u8ClearAdRule/Util`（5b 已搬，与星辰原文仅包名差，7c 复核记名即可）与
  `HkLyricLoader`（星辰版依赖 `HkPlayCompanion`，我方无，7c 定夺）；
  ConfirmHandler 接管（星辰唯一登记处是 `HkArticleFragment:2441`，我方无此面，
  7c 在三个 Fragment 上接）；OnRefreshPageEvent → 列表页的桥；
  `HkLazyRuleParser.parse` 桥（星辰由 `HkEngineBridge:116` 接线，我方无此桥，7c 定夺）；
  `HkParserKit:317` 过时调用方列表注释；`HkRuleFetch:26` 的过时注释（仍列已删的
  `HkNonVideo.sdetail`，现为 `HkPageActivity.sdetail`，与 `HkParserKit:317` 同批清理）。
- 本笔不搬的星辰面（§2.5）：规则管理总壳、阅读器会话栈、WebView holder、电台问询、
  收藏/历史页、订阅冷启、`.hkshell` 脚本执行面。
- `handleDealUrl` 的 pics 支保留 5d 的“无图拦截 toast”（星辰“入口无图不许拦”是因其
  阅读器自带回退解析，我方无该回退，拦截仍是正确行为）。
- 子规则页的返回栈由 Activity 栈承接（星辰 `openPage` 的 `subStack` 压栈/恢复不另搬）。
