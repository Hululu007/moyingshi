# 移植阶段 7b：收藏/历史对接 + 剩余 event（ToastEvent）

> Recovery anchor：阶段 7b——收藏/历史对接现有存储 + `ToastEvent`（修当前 HEAD 疑似编不过）
> + 剩余 event 逐个定夺 + `hiker://collection` 恢复。计划：`docs/miniapp-xingchen-port-plan.md` §2
> 阶段 7（第 2/3 项）、§1.4/§1.5。7a 已提交（`06eb201e`，tag
> `recovery/miniapp-xingchen-7a/20261119173313-06eb201e5dcc`），本地 main 干净。
> 完成句：`HkFavorite`/`HkFavoriteStore`/`HkHistoryRecorder`/`ToastEvent` 四类在位并接线，
> collection/history 两支代码级走通，剩余 event 逐个有不搬理由，全树 grep 零悬空。
> 允许路径：`api/hk/HkFavorite.java`、`api/hk/HkFavoriteStore.java`、`api/hk/HkHistoryRecorder.java`、
> `api/hk/event/home/ToastEvent.java`（以上四类新建）、`api/hk/util/HkHikerRuleUtil.java`、
> `ui/hk/HkMiniappActivity.kt`、`ui/hk/HkDetailFragment.java`、`ui/hk/HkListFragment.java`、
> `ui/hk/HkSearchFragment.java`、本文档。一次只开一个 guard 会话（`miniapp-xingchen-7b`）。
> 下一步：按 §6 顺序实施 → §7 验收逐项填证据 → `task_guard.sh finish` 提交并打 recovery tag。

## 1. 来源与范围

- 星辰原文（`~/workspace/xingchen-ref`，HEAD `52450c0`）：
  `app/src/main/java/com/fongmi/android/tv/api/hk/HkFavorite.java`（107 行）、
  `HkFavoriteStore.java`（293 行）、`HkHistoryRecorder.java`（132 行）、
  `api/hk/event/home/ToastEvent.java`（星辰 `app/src/main/java/.../api/hk/event/home/ToastEvent.java`）。
- 机械搬运优先：星辰原文原样 + 只做必要的适配（包名 `com.fongmi.android.tv` →
  `com.github.moyingshi.osc.tv`；`History.getHk()` 我方无，见 §3.2；其余不自造行为）。
- 只改 §0 允许路径。品牌/包名、液态玻璃、短剧、本地源、实验室、TV/leanback、设置页、播放器、
  下载、检查更新一律不动；BaseLoader 零改动；底栏仍 6 项；不引入 LitePal、dex 动态加载、Clash、XPopup。
  绝对不推、不编、不触发任何 workflow。

## 2. 收藏：`HkFavorite` / `HkFavoriteStore`（星辰原文，存储无需改写）

### 2.1 `HkFavorite`（107 行，逐字搬，只改包名）

- 纯模型，`@SerializedName` 钉死字段名（title/ruleTitle/ruleUrl/episodeUrl/picUrl/group/direct/flag/time），
  查找键 = ruleTitle + episodeUrl。类头注释里 R8 保名提醒（2026-10-29 同型扫）照原文保留。

### 2.2 `HkFavoriteStore`（293 行，逐字搬，只改包名）——“若用 LitePal，改写成文件存储”的前提不成立

- 星辰本类**已是 SharedPreferences + Gson 持久化**（PREFS `hk_favorite`，KEY_RECORDS `records`），
  不是 LitePal（LitePal 只活在其类头注释里对齐原版 8.83 的表述）。故存储零改写，不另建存储，
  直接对接现有：收藏只进海阔侧 SP，不混入主 App 的 Keep（首页收藏）。
- 方法全套照原文：`list()`（时间倒序）/`groups()`/`find()`/`isFavorite()`/`fromHistory()`/
  `toggle()`/`addOrUpdate()`/`remove()`/`migrateIfNeeded()`/`toJson()`；`tryReadAll()` 的
  损坏备份（`records_corrupt_backup`）与“解析失败写路径中止、不拿空表覆盖”一并搬。
- 适配核对（逐项已验，全树 grep 在位）：
  - `fromHistory` 依赖 `HkDetailBridge.KEY_PREFIX`（我方 `hk://`，星辰 `hk_`，符号引用同款，
    逻辑不变）+ `isDirectForm` + `stripDirectKeyMark`（`hkdirect:` 标记，我方 `HkDetailBridge:108-121`
    在位）+ `HkRuleManager.get().getInstalledRule`（我方实例方法，`HkRuleManager.java:120` 在位）。
  - `extractVodId` 按首尾剥 `@@@`（与 `HkHistoryActivity.extractVodId` 同口径）；`History.getSiteKey()`/
    `getVodId()` 我方 bean 在位（`History.java:1001/1005`，均由 key 切 `@@@` 得出）。
  - `toJson()` 字段与原版 `home.js` 对齐（MTitle/title/picUrl/CUrl/url/ruleTitle/group/time），
    供规则 JS `fetch('hiker://collection')`。
- `migrateIfNeeded()`（一次性，幂等，`migrated_keep_v1` 标记；任一条失败不打完成标记、下次重试）：
  - 口径：Keep 键以 `HkDetailBridge.KEY_PREFIX`（我方 `hk://`）开头才候选；`rest.split("@@@")` 取
    parts[0]=规则名、parts[1]=集地址，与我方键形 `hk://<规则名>@@@<集地址>@@@cid` 同款成立；
    `k.getType() != 0` 跳过沿用星辰原文（我方 Keep type 0 = 站点键记录，`Keep.java:95` 同口径）。
  - 我方树内现状：全树 grep——hk 代码从未往 Keep 写过收藏（`Keep.` 在 `api/hk/` 与 `ui/hk/` 零命中），
    故本迁移在我方是**空扫**（首启即打完成标记，无行可迁）。照原文搬、不删：守的是“此前误写”的
    历史口径，万一哪版旧数据带 `hk://` Keep 键仍能接住；行为上零风险。
  - 调用点：对位星辰 `HkPageActivity:301`（星辰宿主 init 后调；`HkFavoriteActivity:90` 是另一调用点，
    我方不建收藏页）。我方放在 `HkMiniappActivity.initView` 首行（海阔小程序管理页是 hk 入口，
    每次进页调一次，内部有已迁标记门，不重复跑）。

### 2.3 收藏页不建

- 星辰 `HkFavoriteActivity`（收藏页）与 `HkHistoryActivity`（历史页）不在 7b 范围：任务只要求
  “对接现有收藏/历史体系，不另建存储”；收藏的读口是规则 JS `fetch('hiker://collection')`（§4）
  与 `HkFavoriteStore` API，历史的读口是现有历史页（`History` 表同表，见 §3）。建新页超出范围，
  记名不做。

## 3. 历史：`HkHistoryRecorder`（132 行，星辰原文，一处适配）

### 3.1 原文照搬的部分

- `keyOf(resTitle, vodId)` = `siteKey + "@@@" + vodId + "@@@" + VodConfig.getCid()`，与我方
  `VideoActivity.getHistoryKey()`（`VideoActivity.java:1373`：`getKey() + "@@@" + getId() + "@@@" + cid`）
  同形——已逐行核对 `toVod`（`HkDetailBridge.java:158-167`）：`vod.setId(itemUrl)`（itemUrl 即详情地址）、
  `site.setKey(siteKey(rule.getTitle()))`（`hk://<规则名>`），故 hk 播放时 `getHistoryKey()` =
  `hk://<规则名>@@@<详情地址>@@@cid` = `keyOf(ruleTitle, detailUrl)`，口径对齐，无需修调用方。
- `recordOpened`（不存在才建，position=0；已有只刷新展示字段与时间，绝不覆盖进度字段；
  `Setting.isIncognito()` 跳过；单线程 WRITER 串行）与 `updateProgress` 照原文搬。
- 类头注释（2026-11-14 23:03/23:05 星辰方亲定：海阔打开过的一切都必须记在海阔自家历史里）照原文保留。

### 3.2 唯一适配：`findBySiteAndName` 的 `History.getHk()` 我方没有

- 星辰：`for (History h : History.getHk())`（带当前 cid＋60 天窗的海阔历史读取口径）。
- 我方：`History.getHk()` 全树零命中；`History.getAll()`（`History.java:210`）走
  `HistoryDisplayPolicy.project` 投影（聚合换行），当定位口径用有换行风险。
- 改写：`AppDatabase.get().getHistoryDao().findAll()`（`HistoryDao.java:14` 在位）+ `HkDetailBridge.isHkKey`
  过滤（与 5d `historyJson` 同款取数口径），再按 `siteKey` + `vodName` 匹配。只改这一处，其余不动。

### 3.3 `recordOpened` 调用点：`HkDetailFragment.onEpisodeClick`（两支，各记一次）

- 非影视支：`HkRouter.isNonVideoRule` 真 → 记一次 → `HkPageActivity.routeEpisode`。
- 影视支：`mVod == null` 仍 return（不记——没播的东西不建行）；`VideoActivity.startHk` 前记一次。
- 参数：`keyOf(mRule.getTitle(), arg("url"))`（vodId = 详情地址，与 `toVod` 的 id 口径同款）、
  vodName=`mDetail.info.title`、vodPic=`mDetail.info.pic`、flag=`line.title`（线路名）、
  episodeName=`episode.getTitle()`、episodeUrl=`episode.getUrl()`。两支共用一处私有 helper
  `recordHistory(line, episode)`，不写两遍同样的 6 个参数。
- 不重复记：`onEpisodeClick` 是详情页点选集的唯一入口（动作行走 `onActionClick`，不经过此）；每次
  点选集记一次，已有行只刷新时间与展示字段，进度字段不动——与星辰“分流/交接处建记录”同款。
- 与播放器自家历史写不冲突：`VideoActivity.startHk` 后播放器按同一键（`getHistoryKey` 与 `keyOf`
  同形，见 §3.1）找行更新进度；`recordOpened` 建的行就是播放器要找的那行，同键同行，不会双行。
- `updateProgress` 暂不接线：星辰的接线方是借面存进度时（阅读器/音乐会话的 `MusicStore`/
  `ReadingStore` 两侧的门），我方借面（`NovelRouter`/`AudioActivity`）不在 hk scope 内，
  动它们超出“只改 hk 引擎相关文件”的硬约束。`updateProgress` 方法照原文搬、留待以后接线；
  现状后果写清：小说/音乐/听书的集内进度仍由借面自家存档管，海阔历史行只记“打开过、到哪集”，
  不记集内万分比/毫秒——已知缺口，不冒充已接。

## 4. `hiker://collection` 恢复

- `HkHikerRuleUtil.getRulesByHiker` 的 collection 支（`HkHikerRuleUtil.java:117-121`）：
  `"[]"` → `HkFavoriteStore.toJson()`，注释改成“7b：已恢复原文行”，其余分支不动
  （bookmark/download 两支恒回 `"[]"` 的记名结论不动）。
- 链路：规则 JS `fetch('hiker://collection')` → `getRulesByHiker` → `HkFavoriteStore.toJson()`。
  history 支 5d 已恢复，不动。

## 5. `ToastEvent`：修当前 HEAD 疑似编不过 + 三 Fragment 订阅

### 5.1 类本身（星辰原文 12 行级小类，逐字搬，只改包名）

- `api/hk/event/home/ToastEvent.java`：`public final String message` + 构造。类头注释照原文
  （含 136b A7 曾认混两条通道、2026-11-15 已订正的提醒；承接点一句改成我方三 Fragment，
  见 §5.2——原文写的是 `HkArticleFragment`，我方无此面）。
- **修编不过**：我方 `HkJsRuntime.java:9737` 在 `setError` 无回调支真实投递
  `new ...event.home.ToastEvent(msg)`，但类在全树不存在——本笔新建类即闭合该引用。
  其余被 `HkJsRuntime` 引用的 event 类 7a 前已全部核对在位（仅 `ToastEvent` 缺失）。

### 5.2 订阅：三个 Fragment 各加 `onToastEvent`（星辰 `HkArticleFragment:406-412` 同款）

- 星辰原文：`@Subscribe(threadMode = ThreadMode.MAIN)`；空消息/无 Activity/finishing 丢弃；
  `isOnPause()` 门（宿主被盖住时不弹）；呈现走 `HkErrorUtil.showErrorMsg(getActivity(),
  event.message, "500", event.message, new Exception(event.message))`
  （title=msg、ruleTitle 槽位传 `"500"`、msg=msg、现造异常；官 `FilmListActivity.onToast`
  同款，`DebugUtil.showErrorMsg` 错误式呈现）。
- 我方适配（两处，均有依据）：
  - 门：三个 Fragment 无 `isOnPause()`，用既有 `onHkRefreshPage` 同款门
    `!isResumed() || !getUserVisibleHint()`（星辰注释自述“暂停或 hint 假，W4 F15 同款”，
    与 `isOnPause` 同义；`HkListFragment:311`/`HkDetailFragment:170`/`HkSearchFragment:157` 在位）。
  - 呈现：走我方 `HkErrorUtil.showErrorMsg(Activity, String, Throwable)` 的五参重载
    `showErrorMsg(activity, errorTitle, ruleTitle, errorMsg, throwable)`（`HkErrorUtil.java:59`
    在位，参数顺序与星辰调用一致），不走 `Notify.show`——星辰本事件是**错误式呈现**，
    不是轻提示（JS `toast()` 的轻提示是另一通道，`HkJsRuntime` 的 `toast` 直弹，与本事件无关）。
- 落点：`HkListFragment` / `HkDetailFragment` / `HkSearchFragment` 各加一个方法，
  放在各自 `onHkRefreshPage` 之下；EventBus 注册已在三个 Fragment 的 `onStart`/`onStop` 在位，
  不另加。三个面都订阅是星辰同款吗？星辰只在 `HkArticleFragment`（详情/文章面）订阅；
  我方无此面，`setError` 可能发生在任一页的规则求值中（列表/详情/搜索三面各有常驻引擎），
  故三面各订阅、各加“当前页才弹”门——多面订阅是**我方适配**，不是星辰原文，写清理由：
  事件是全局 post，不订阅的面收不到；门保证同一时刻只有一个可见面弹，不会三弹齐出。

## 6. 剩余 event 逐个定夺（3e 清单复核，不全盘照搬）

- 核对方法：星辰全树 grep 谁引用 + 我方 `HkJsRuntime`/`HkEngine`/`HkRouter` 是否引用。
  结论：除 `ToastEvent`（§5）外，**全部不搬**，理由逐条如下（7c 文档不再重复列，只记“7b 已定夺”）：
  - `event/home/OnPageChangeEvent`、`OnRefreshEvent`：星辰只被 `HkArticleFragment`/`HkListActivity`
    （UI 生命周期）引用，引擎（`HkJsRuntime`/`HkEngine`/`HkRouter`）零引用 → 不搬。
  - `event/home/ShowFileChooserEvent`：星辰只被 `HkWebkitHolder`/`HkArticleFragment` 引用
    （WebView holder 面，我方无 `HkWebkitHolder`，只有 `HkWebkitFetcher` + `CatWebActivity`）→ 不搬。
  - `event/rule/OnArticleListRuleChangedEvent`：星辰只被 `HkDlanController`、`HkHomeSubService`、
    `AutoImportHelper` 引用 → 不搬；规则变更感知走我方 `HkRuleManager.saveStamp`。
  - `event/rule/OnSubRefreshEvent`：星辰只被 `HkHomeSubService` 引用 → 随订阅不搬（7c 定夺）。
  - `event/rule/ShowSelectOptionsEvent`：星辰 `HkJsRuntime:5304-5331` 在 `showSelectOptions`
    putFunction 内真实 post；但**我方 `HkJsRuntime` 根本没有 `showSelectOptions` putFunction**
    （grep `showSelect` 零命中），事件无生产方 → 不搬；记名：JS `showSelectOptions` API 缺失是
    另一缺口，`select://` 裸 scheme 由 `HkClickDispatcher` 处理。
  - `event/video/PlayChapterEvent`：星辰只被 `HkDlanController`（不搬）、`VideoActivity`、
    `HkArticleFragment` 引用 → 不搬。
  - `event/web/*` 8 个（`BlobDownloadEvent`、`BlobDownloadProgressEvent`、`FindMagnetsEvent`、
    `OnSaveAdBlockRuleEvent`、`OnSetAdBlockEvent`、`OnSetWebTitleEvent`、`ShowSearchEvent`、
    `ShowTranslateEvent`）：星辰只被 `HkWebkitHolder`/`HkArticleFragment` 引用（WebView holder 面，
    我方无 `HkWebkitHolder`）→ 不搬。

## 7. 验收（代码级，不要等构建；逐项填证据）

- [x] `HkFavorite.java`（新，星辰原文，包名改写）在位。
- [x] `HkFavoriteStore.java`（新，星辰 SP+Gson 原文，包名改写）在位；`migrateIfNeeded()` 在
  `HkMiniappActivity.initView` 调一次；`fromHistory` 的 `KEY_PREFIX`/`isDirectForm`/
  `stripDirectKeyMark`/`getInstalledRule` 引用全部闭合。
- [x] `HkHistoryRecorder.java`（新，星辰原文，一处适配：`findBySiteAndName` 用 DAO `findAll()` +
  `isHkKey` 过滤）在位；`keyOf` 与 `VideoActivity.getHistoryKey` 同形已核对 `toVod` 口径。
- [x] `ToastEvent.java`（新，星辰原文，包名改写）在位；`HkJsRuntime:9737` 的投递引用闭合
  （此前疑似编不过的点已修）。
- [x] 三个 Fragment 的 `onToastEvent` 在位（MAIN + 空消息/Activity 门 + resumed/可见门 +
  `HkErrorUtil.showErrorMsg(activity, msg, "500", msg, new Exception(msg))`）。
- [x] `HkDetailFragment.onEpisodeClick` 两支各记一次历史（helper 单定义，`mVod == null` 不记）。
- [x] `HkHikerRuleUtil` collection 支回 `HkFavoriteStore.toJson()`，注释已更新。
- [x] `git diff --check` 通过；全树 grep 无 `com.fongmi.android.tv`（文件头注释里的星辰路径除外）、
  无 LitePal 依赖（本笔新增文件里只有 `HkFavoriteStore.java:20` 星辰原文注释提及原版 LitePal 表，
  无引用、无依赖）、无 dex 动态加载；改动文件列表与行数统计见 §7 末；0 leanback 文件。
- [x] collection/history 两支代码级走通（调用链见 §7 末）。

## 8. 遗留（不归 7b）

- 7c：`HkHomeSubService` 定去留（计划 §1.4“延后/不搬”，用户原话不搬首页订阅）；
  `HkM3u8ClearAdRule/Util`（5b 已搬，7c 复核记名）与 `HkLyricLoader`（星辰版依赖
  `HkPlayCompanion`，我方无，7c 定夺）；ConfirmHandler 接管（星辰唯一登记处是
  `HkArticleFragment:2441`，我方无此面，7c 在三个 Fragment 上接）；OnRefreshPageEvent →
  列表页的桥；`HkLazyRuleParser.parse` 桥；`HkParserKit:317` 与 `HkRuleFetch:26` 过时注释清理；
  `HkJsRuntime:9735` 的过时注释（承接点仍写 `HkArticleFragment.onToastEvent`，7b 起是三 Fragment
  各一个 `onToastEvent`，`HkJsRuntime.java` 不在 7b scope，归 7c 一并改）。
- `HkHistoryRecorder.updateProgress` 已搬、暂不接线（借面不在 hk scope，见 §3.3）：
  小说/音乐/听书的集内进度仍由借面自家存档管，海阔历史行只记“打开过、到哪集”。
- `HkFavoriteActivity`/`HkHistoryActivity`（星辰收藏/历史页）不建（见 §2.3）。
- `HkFavoriteStore.migrateIfNeeded()` 在我方是空扫（§2.2）：首启即打完成标记，无行可迁。
- 调用链（代码级走通）：
  - collection：规则 JS `fetch('hiker://collection')` → `HkHikerRuleUtil.getRulesByHiker`
    （collection 支）→ `HkFavoriteStore.toJson()`（SP `hk_favorite` 读表 → Gson 序列化）。
  - history（点选集记）：`HkDetailFragment.onEpisodeClick` → `recordHistory` →
    `HkHistoryRecorder.recordOpened(keyOf(规则名, 详情地址), …)` → WRITER 单线程 → `History` 表
    （键 `hk://<规则名>@@@<详情地址>@@@cid`，与播放器 `getHistoryKey` 同键同行）。
  - ToastEvent：`HkJsRuntime.setError` 无回调支（:9737）post → 三 Fragment 各 `onToastEvent`
    （MAIN，resumed+可见门）→ `HkErrorUtil.showErrorMsg(activity, msg, "500", msg, new Exception(msg))`。
- 改动统计（`git diff --stat` + 5 个新文件）：`HkHikerRuleUtil.java` +10/−5、`HkDetailFragment.java`
  +28、`HkListFragment.java` +15、`HkMiniappActivity.kt` +3、`HkSearchFragment.java` +15；
  新文件 `HkFavorite.java` 107 行、`HkFavoriteStore.java` 293 行、`HkHistoryRecorder.java` 137 行
  （星辰原文 132 行 + `findBySiteAndName` 适配 5 行）、`ToastEvent.java` 22 行、本文档。括号平衡：状态机分词器复核 9 个改动文件全部归零。
