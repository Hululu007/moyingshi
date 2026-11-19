# 移植阶段 5d：详情页 + 播放接线（HkRouter.detail / HkDetailBridge.toVod / Router.play）

> 状态：已改写、grep 与 diff 已验（见 §8），待 guard finish 提交。
> 上游：星辰 `~/workspace/xingchen-ref`（HEAD `52450c0`）；本阶段不新搬星辰类，
> 只把 5a/5b 已搬的类接进详情页与播放器，并收 3e 遗留的 history 支。
> 约束：只改 hk 引擎相关文件（`api/hk/*`、`ui/hk/*`、本文档与 5c 文档一处数字订正、
> `VideoActivity` 仅 hk 播放分支）；不推、不编、不触发 workflow；BaseLoader 零改动。

## 1. 改动清单

| 文件 | 改动 |
|---|---|
| `api/hk/HkDetailBridge.java` | `KEY_PREFIX` 由 `"hk_"` 改 `"hk://"`（命名空间适配，见 §2） |
| `api/hk/HkDetail.java` | 加 `lastChapter` 字段 + getter/setter（新增，非星辰原文） |
| `api/hk/HkRouter.java` | `detail()` 内算 `lastChapter`（复刻 `HkExecutor.lastChapter`） |
| `api/hk/HkCard.java` | 加 `fromDetailItems(List<HkDetailItem>)`（`HkExecutor.detailItemsToCards` 逐字移入） |
| `ui/hk/HkDetailFragment.java` | 取数改走 `HkRouter.detail` + `HkDetailBridge.toVod`；标记分流；点选集经 `VideoActivity.startHk(mVod)` / `HkNonVideo`（新签名，带 Vod） |
| `api/hk/HkNonVideo.java` | 懒播放改走 `Router.play`；`routeEpisode` 加 Vod 入参；`HkPlayer.buildVod/play` 调用点改走传入的 Vod / `startHk`；`HkExecutor.isHttpUrl` 改本地小函数；`sdetail` 仍调 `HkExecutor.sdetail`（5e 再搬，见 §6） |
| `api/hk/HkPlayer.java` | 删除（`play`/`buildVod` 已由 `toVod` + `startHk` 承接） |
| `ui/activity/VideoActivity.java` | `resolveHkPlayerContent` 改走 `Router.play` + `HkPlay→Result` 映射；删 `DialogHost` 构造（新引擎不消费它，见 §5）；失败时显示解析方给出的明确错误文案 |
| `api/hk/util/HkHikerRuleUtil.java` | history 支恢复（`historyJson` 一族按星辰原文搬入，见 §4） |
| `docs/miniapp-xingchen-5c-list-search-ui.md` | §6 合计行数订正：`+143/−15` → `+128/−15`（143 是改动行总数，插入是 128） |

## 2. 站 key 命名空间：`hk://`（取舍已定）

- 星辰 `HkDetailBridge.KEY_PREFIX = "hk_"`；我方全树站 key 是 `hk://<标题>`
 （`HkStore.siteOf`、`VideoActivity.isHkSite()` 以 `getKey().startsWith("hk://")`
  判定、`HkStore.read(key.substring("hk://".length()))` 反查规则）。
- 若 `toVod` 照搬 `hk_`：详情页拼出的 Vod 进播放器后 `isHkSite()` 为假，hk 解析分支
  根本不触发；历史键、Flag 偏好缓存、阅读器/音频页的 siteKey 也会与 `hk://` 分叉。
- 决定：**只改 `KEY_PREFIX` 为 `"hk://"`**（`siteKey`/`isHkKey` 同改），其余照搬。
  全树 grep `"hk_"` 只有无关项（`hk_miniapp` 开关、`hk_import` 目录），无其他
  `hk_` 键消费者。`HkDetailBridge` 头注相应改写。

## 3. 详情页接线（HkDetailFragment）

- 取数：后台线程 `routerFor(rule)`（5c 同款懒建）→ `new HkItem(fbName, url, fbPic, fbDesc)`
  → `router.detail(url, item, false)`。
  - `fromSearch=false`：与旧 `HkExecutor.detailOutcome` 同口径（旧链路从不折叠搜索引擎；
    折叠语义——搜索来路 sdetail 段——留待 5e 后视规则表现再定，不在本笔改行为）。
  - `HkItem` 四参构造器在位（`:62`）；`extra` 为空，`setDetailParams` 无影响。
- 标记分流（`HkDetail` 上的星辰标记，调用方必须处理，否则静默错页）：
  - `lazyParseFailed` → 错误态（文案“详情解析失败”，走原 `showError` 重试分支）。
  - `tabSwitch` → 不渲染，直接结束本页（懒规则只改了分类变量；详情页重跑同一懒规则会循环触发，不可原地 reload）。
    注：按设计列表页应靠 refreshPage 事件重刷，但核对发现既有缺口——JS 的 `refreshPage`
    发的是 `OnRefreshPageEvent`，三个 Fragment 订阅的是 `HkRefreshPageEvent`，全树无转译订阅，
    `HkRefreshPageEvent.post` 零调用，事件实际到不了列表页。这是阶段 7 的 event 范畴，
    本笔不扩大范围修事件桥，只保证本页不循环、不空页。
  - `directPlayUrl` / `dealKind=video` → 跳过详情页直接播放：拼最小 Vod
    （`HkStore.siteOf(rule)`，id=详情地址，name/pic=回退值）+ `VideoActivity.startHk`
    + 结束本页。
  - `dealKind=pics` → `HkRouter.parsePics(dealUrl)` 拼 `pics://` payload，
    最小 Flag（单集“图集”）+ 最小 Vod → `NovelRouter.openPics` + 结束本页。
  - `dealKind=x5/web` → `CatWebActivity.intent(ctx, dealUrl)`（5c 已定的顶替方案）+ 结束本页。
  - `dealKind=image` → `PhotoViewerDialog.show(activity, [dealUrl], 0, null)` + 结束本页。
  - `dealKind=magnet` → 与旧链路同款直通：最小 Vod + `startHk`（集址=磁力链，播放器侧原样接）。
  - `textResult` → `AlertDialog` 显示文本，关闭时结束本页。
  - `actionUrl` → 主线程 `HkActionHandler.handle(this, HkCard(actionUrl))`（5e 前仍由它执行），
    然后结束本页。
- 正常路：`HkCard.fromDetailItems(hd.getRawItems())` → `HkDetailHelper.split(...)`
  （切分逻辑一点不动，信息头/线路头/text_5/动作行与旧版一致）→ `lastChapter`
  追加到简介末尾（旧行为）→ `HkDetailBridge.toVod(rule, url, hd)` 得 `mVod`
  （供点选集与阅读器复用）→ `columnsFor(rule.getDetailColType())` 不变。
- **线路名回 stamp**：`toVod` 按 `HkDetailItem.line` 分组，而选择器解析的条目 `line`
  恒为空（只在 JS 详情对象带 `line` 键时才有，`HkJsRuntime:6754/6784`），不 stamp
  则 toVod 的 Vod 恒为单线路“默认”，与详情页的多线路不一致，`startHk` 的 playFlag
  也对不上。做法：split 之后，按卡片→条目同一性（`fromDetailItems` 是 1:1 保序转换，
  用 `IdentityHashMap<HkCard, HkDetailItem>` 建映射）把每集所属线路名写回
  `HkDetailItem.setLine(...)`，再调 `toVod`。`toVod` 内的 admit/sorter/merger
  过滤仍按星辰语义走（与旧 `buildVod` 全量收编不同，见 §7）。
- 点选集：先 `HkDetailBridge.setClickPosition(行内下标, 规则名, 集址)`（MY_POSITION
  槽：旧链路从未有人放值，恒为 -1；本笔在详情页点击与 `VideoActivity` 解析前放值，
  `Router.playRoute:502` 消费），再分流：
  - 非影视类 → `HkNonVideo.routeEpisode(..., mVod)`（新签名，见下）；
  - 影视类 → `VideoActivity.startHk(activity, mVod, line.title, episode.title, episode.url)`。
- 生命周期：`initView` 规则读到后 `HkEngineManager.surfaceOpened(mRule)`；
  `onDestroy` 里 `mRouter.destroy()` + `surfaceClosed(mRule)`（5c 同款）。
- `mVars` 与 `HkActionHandler.Host` 实现、`onActionClick` 走 `HkActionHandler.handle`
  全部保留（5e 再换）；`loadDetail` 不再需要 `DialogHost`（新引擎不消费它，见 §5），
  `HkExecutor`/`HkPlayer` import 随之去掉。

## 4. history 支恢复（HkHikerRuleUtil）

- 按星辰 `HkHikerRuleUtil.java:188-232` 原文搬入 `historyJson()` /
  `toViewHistoryRow()` / `extractPageUrl()` + `HkViewHistoryRow`（含 `compareTo`）。
- 适配点（两处）：
  - `History.isHk(h)`：我方 `History` bean 无此方法，改内联
    `HkDetailBridge.isHkKey(h.getSiteKey())`（与星辰 `History.isHk` 的定义同款，
    命名空间已按 §2 走 `hk://`）。
  - 其余逐字段映射照搬：`title←vodName`、`picUrl←vodPic`、`url←extractPageUrl`
    （历史键 `站段@@@vodId@@@cid` 按首尾剥，`getVodId()` 回退）、
    `ruleBaseUrl←HkParserKit.getHomeUrl(url)`（阶段 4 已在位）、
    `videoUrl←episodeUrl`、`type="二级列表"`、`time←createTime`（0 视为无时刻）。
  - `AppDatabase.get().getHistoryDao().findAll()`、`History.getCreateTime()` 均在位。
- collection/bookmark/download 三支不动（仍回 `"[]"`，阶段 7）。

## 5. 播放接线（VideoActivity.resolveHkPlayerContent）

- `HkExecutor.play(rule, episodeUrl, activity, dialogHost)` →
  `new HkRouter(rule).play(episodeUrl)`（`HkPlay`）。
  `HkPlay→Result` 映射（与旧 `PlayOutcome→Result` 逐项对照）：
  - 多线路：`getUrls()/getNames()/getLineHeaders()` 非空时逐线 `Url.add(name, url)` +
    `mHkLineHeaders` 记表（旧 `PlayLine` 同款）；单线时 `getUrl()/getHeaders()`。
  - 字幕：`getSubtitle()` 单值 → `Sub.from(...)` 一个（旧为列表；新模型只有单值字段）。
  - 弹幕：`getDanmu()` 单值 → `Danmaku.from(...)` 一个（同上）。
  - 歌词：`getLyric()` → `LyricUtil.loadProvided(...)` 不变。
  - `pics` 非空（漫画结果误入视频路）：与旧行为一致，不进播放器、报
    `R.string.hk_play_no_url`（旧 `PlayOutcome.isEmpty()` 同款收口）。
  - `refreshOnly`（懒结果只切换变量）：同款收口，不进播放器。
  - `playType`（magnet/ed2k/thunder/ftp）：地址原样透传（旧同款）。
  - `audioUrls`：旧 `PlayOutcome` 无此字段，`Result` 也无音频地址位，本笔不接（音乐走
    `HkNonVideo`→`AudioActivity` 路；见 §7 遗留）。
- `Router.play` 遇动作地址/不可播 scheme 抛 `IllegalStateException`（`checkPlayExit`
  的精选文案，如“海阔懒解析未返回播放地址，请换一条试试”）；`post` 回调里
  `noticeText==null && err!=null` 时显示 `err` 而不是通用 `hk_play_no_url`
  （旧代码把解析方文案吞掉，本笔只改本分支的回调，属 hk 分支内改动）。
- **DialogHost 删除**：`HkEngine.DialogHost` 在新引擎内无任何消费方——全树 grep
  `getDialogHost()` 只有 `HkSubpage:262` 的透传，`HkJsRuntime` 内零引用；JS 的
  `confirm` 走 `ConfirmHandler`/`dispatchApi`。`VideoActivity` 里的匿名
  `DialogHost` 实现（含 3 处 `HkDialogs.*` 调用）整段删除；`HkExecutor`/`HkDialogs`
  import 随之去掉（`HkEngine` import 同去，本文件内无其他 `HkEngine` 引用）。
  `HkDialogs` 本体仍在（`HkActionHandler` 还在用），5e 再删。
- 起播前放点击位：`HkDetailBridge.setClickPosition(当前 Flag 内下标, 规则名, 集址)`
  （下标取不到时不放；`VideoActivity.getFlag()` 在位）。

## 6. HkNonVideo 改走新引擎（不删，见 §7）

- `routeEpisode` 签名加 `Vod vod` 入参（`HkDetailFragment` 传 `mVod`；`postOpen` 里
  `HkPlayer.buildVod(...)` 改用传入的 Vod）。
- 懒播放：`HkExecutor.play(r, ep.getUrl(), act, null)` → `new HkRouter(r).play(ep.getUrl())`；
  字段对照：`pics`（`HkPlay.getPics()` 列表 → `"pics://" + join("&&", pics)`，
  与 `contentFromCards` 同口径）、`url←getUrl()`、`headers←getHeaders()`、
  `lyric←getLyric()`；`notice` 字段新模型没有——`Router.play` 遇 toast:// 等动作地址
  直接抛错，外层 catch 已弹错文案，行为等价（旧：notice 弹文案；新：异常文案）。
  `HkExecutor.isHttpUrl` 两处改本地 `isHttpUrl` 小函数（`http://`/`https://` 前缀）。
- 音乐/图片/小说回落里的 `HkPlayer.play(...)` → `VideoActivity.startHk(act, vod, l.title, ep.getTitle(), ep.getUrl())`。
- `sdetailContent` 仍调 `HkExecutor.sdetail`：`HkRouter` 无 sdetail 入口，
  `HkExecutor.sdetail` 依赖的 `buildDetailRequest/fetch` 是其私有方法，整段搬入
  `HkNonVideo` 的工作量与 5e 的 `HkExecutor` 删除同批做更划算；本笔只把 `play` 路
  换掉，`sdetail` 留 5e（文档记名，不丢）。

## 7. 语义差与遗留（本笔接受并记录）

- **toVod 与 split 的选集过滤不同**：`toVod` 过 `HkChapters.admitEpisodeRuns` +
  `HkEpisodeSorter` + `HkEpisodeMerger`（星辰语义：剔导航/互链/重复、合并同集多址）；
  旧 `HkPlayer.buildVod` 全量收编。播放器内选集表可能比详情页少（被剔的导航项），
  但这是星辰原生行为，接受。点详情页里被 toVod 剔掉的集仍可播（集址直传，
  `resolveHkPlayerContent` 不依赖 Flag 包含该集）。
- **lastChapter 的 JS 详情分支**：选择器详情分支用已抓的页正文直接算；JS 详情分支
  （`fromJsDetail`）无现成页正文，本笔在该分支多抓一次详情页（仅小说/漫画类且
  `last_chapter_rule` 非空时）再算，与旧行为一致；抓页失败回 `""`，不炸详情页。
- **详情 `*` 列表段复核**（阶段 4 文档 §4/§5.1 的遗留，本笔复核结论）：
  `Router.detail` 的详情路由不走 `HkSelector.parseDetail` 的 `*` 整页当一条分支，
  `classifyDetailItems` 只打 dry 标；`*;*;body&&div@option;Text;value` 这类
  chapter_find 样本在新版下仍回空表——**星辰原生行为，接受并记录，不补**。
  影响面只限 chapter_find/sdetail_find 以 ` *` 开头的规则，8 条验收规则无一用此写法。
- **奇优搜索 desc 多拼一段 `.detail&&Text`**：星辰行为，只影响展示，确认即可（5b 已记）。
- **HkPlay.audioUrls 未接**：`Result` 无音频地址位；音乐类走 `AudioActivity` 路，
  不经 `resolveHkPlayerContent`。留待后续如需再接。
- **HkPlayer 删除后**：`buildVod` 的 `$$$`/`#`/`$` 字符串编码彻底退场，
  Vod 的 flags 一律由 `toVod` 的 `Flag.create`/`Episode.create` 直建。
- **5e 待办**：`HkExecutor` 删除（含 `HkWarmup`/`HkParserKit` 注释外的 `home` 调用、
  `HkActionHandler.evalLazy`、`HkNonVideo.sdetail` 的搬入）；`HkActionHandler`/
  `HkListHelper`/`HkSubpage`/`HkWebRule`/`HkDialogs` 删除；点击分流改走
  `HkRouter.dealKind`；col_type 表按 `HkColType` 重做 `styleFor/columnsFor`；
  3e 适配段缺口复核（getExports、setImportParam、jsValueFromCards、evalTextRule、
  evalFindRule、setResult 空数组）；`proxyClearM3u8` 接线定夺；
  `HkLazyRuleParser.setJsRuntime` 桥注入定案。

## 8. 验收证据（已验，finish 前复核）

- **grep**：`HkDetailFragment`/`HkNonVideo`/`VideoActivity` 内 `HkExecutor.play` /
  `HkExecutor.detailOutcome` / `HkPlayer.` 代码命中为零（只剩注释提及）；
  `HkPlayer.java` 已删；`HkDetailBridge.KEY_PREFIX` 为 `hk://`；
  `HkExecutor` 的剩余代码调用方只有三处，属 5e：`HkActionHandler`（`evalLazy`/
  `stripNowSuffix`/`unescapeLazy`/`stripHashMarkers`）、`HkWarmup`（`home`）、
  `HkNonVideo`（`sdetail`，本笔有意保留）。
- **调用链**：`router.detail(`（DetailFragment:240）、`HkDetailBridge.toVod(`
  （:264）、`router.play(`（NonVideo:115、VideoActivity:3115）、`HkPlay` 映射
  调用点在位；`setClickPosition` 在详情点击（DetailFragment:464）与
  `resolveHkPlayerContent`（VideoActivity:3111）内在位。
- **history**：`historyJson`/`toViewHistoryRow`/`extractPageUrl`/`isHkHistory`
  在位；`HkParserKit.getHomeUrl`（:162）、`HistoryDao.findAll`、`History.getVodId`
  均在位；`isHkKey` 按 `hk://` 判定。
- **禁入**：改动文件内 `DexClassLoader|SourceGate|GlobalProxy|XPopup|Clash` 代码
  命中为零；`com.fongmi.android.tv` 只出现在文件头移植来源注释（既定例外）与
  5a 已有的一处 `SourceGate` 注释；`LitePal` 只出现在 `HkHikerRuleUtil` 的 3e
  旧注释；`git diff --check` 通过。
- **改动文件列表与行数**：10 文件 +589/−212（`git diff --stat`，不含本文档；每文件
  数字为改动行总数 = 插入+删除）：HkDetailFragment 290、HkHikerRuleUtil 152、
  VideoActivity 90、HkNonVideo 88、HkPlayer 84（删除）、HkRouter 45、HkCard 20、
  HkDetail 19、HkDetailBridge 10、5c 文档 3；本文档新增 214 行。
- **javac 坑复核**：本笔无 lambda 捕获重赋值变量（`out` 只改字段不重赋，`final LoadResult
  result = out` 定值拷贝）；无 `Json.safeListElement`/`JsonArray` 赋值；无匿名
  LinkedHashMap；无 `JsonElement.isString()`；`switch (kind)` 为字符串 switch；
  `getFlag()` 在后台线程只读 getter，外包 try/catch。
- **8 条规则详情/播放链路核对**：见 §9（代码级）。

## 9. 8 条规则详情/播放链路核对（代码级，已核）

- 奇优影院♔：详情 `detail_find_rule` 选择器 → `Router.detail` ④ 分支 →
  `parseDetail` → split/toVod；选集 `@lazyRule=` → `Router.play`。走通。
- 童话：详情自包含 JS → `fromJsDetail`；选集懒加载同上。走通。
- 臻品fix：详情 JS → `fromJsDetail`；tab 卡片是动作行（`HkActionHandler`，5e），
  选集 `@lazyRule=.js:eval(...)` → `Router.play`。走通。
- 韩剧TV.Q：JS find_rule（`.replace` 落在 Java 字符串上，5a 前已修 `__jsStr`）；
  详情/播放同上。走通。
- 麻花影院：x5 卡不进详情（列表页 CatWebActivity，5c）；详情页本身选择器。走通。
- 西瓜影院：`hiker://empty` + `hiker://page/home` JS 首页；子页面点开属 5e
  （列表点击分流），详情页 `hiker://page/` 进 `Router.detail` 即
  `lazyParseFailed`（本笔走错误态，不展开）。列表走通，子页面待 5e。
- 星辰影视123.Dr / 青苹果♨️：preRule require gitcode 418——详情/播放链路本身走通，
  只验报错清晰（`Router.play`/`detail` 抛错 → 错误态/明确 toast）与缓存回退，
  不声称能出数据。
