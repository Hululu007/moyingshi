# B8 短剧批拆分设计：浏览/播放器分界线（调研文档，非实现）

> 状态：**调研完成，待决策**。本文档只做短剧屏的浏览/播放拆分设计，不包含
> 任何产品代码改动。评估与阶段定义见 `docs/b8-flutter-rewrite-assessment.md`
> （§5 方案 C、§6 go/no-go、§7 回滚）；全量规划见
> `docs/b8-full-migration-plan.md`（§2.3 短剧排除理由、§6 P5、§4 共性决策）；
> C0/C1/C2 已验证形态见 `docs/b8-c0-shell.md`、`docs/b8-c1-about-subtitle.md`、
> `docs/b8-c2-scoping.md`、`docs/b8-c2-history.md`（D1–D8 既定决策）；
> B11 首页壳与 G2 竞态见 `docs/b8-b11-home.md`（V10）。
> 分支：`b8-flutter-rewrite`；基线：HEAD `01fa554d`（B21）；`main` 未动。
>
> **诚实声明**：本机无 Java、Android SDK、Flutter/Dart，一切构建与运行
> 结论均未验证。行数与结构均实测于本地源码（2026-11-17）；凡需 CI/真机
> 证明的项见 §5，不得视为已验证。

## 0. §1 执行声明（仓库 AGENTS.md）

- 完成句：短剧批拆分设计——读透原生 `ShortDramaActivity`，画出浏览与
  播放器的精确分界线，比较至少两个拆分方案并给出明确推荐，写成调研文档
  并本地提交，不改产品代码，不推送，不动 `main`。
- 允许路径：仅 `docs/b8-short-drama-scoping.md`（task_guard assessment 模式）。
- 预存脏文件：无（开始时工作树干净）。
- 时间/时区：2026-11-17 16:15 CST（Asia/Shanghai）开始。
- 工期估计：源码调研约 40 分钟 + 文档约 25 分钟，约 17:20 CST 完成。
- 最便宜的决定性验证：`git diff --check` + diff 仅含本文档 + 提交后
  工作树干净。

## 1. 原生实现（实测）

### 1.1 一句话定性

`ShortDramaActivity`（mobile，793 行）**不是“一个浏览页 + 一个播放器”，
而是一个播放器 Activity 顺带长了浏览功能**：默认模式就是纵滑播放流
（FEED），剧场网格（THEATER）与历史（HISTORY）只是同一个 Activity 里
的另外两个 View，共用同一份 `feed` 列表与同一个源状态。所谓“只迁浏览、
不动播放器”的拆分点，不在 Activity 边界上，而在**模式边界**上——FEED
模式整体留原生，THEATER + HISTORY + 壳（Tab/源切换/空态）迁 Flutter。

### 1.2 入口（全仓 grep，唯一 funnel）

| 调用点 | 位置 | 处理 |
|---|---|---|
| 底栏/玻璃条“短剧”项 | 手机 `HomeActivity.java:527` `handleNavItem(R.id.drama)` | 无参 `ShortDramaActivity.start(this)`；`return false`（不切 fragment） |
| Flutter 首页底栏 drama 项 | `B11HostApiImpl.java:623` `case "drama"` | 反射调 `ShortDramaActivity.start`，**不 finish Flutter 首页**（C1 引擎带着 `/home` 活在下面，见 §2.3） |
| Dart 玻璃底栏 | `b11_home_page.dart:473` `case 'drama'` | 同上，经 B11 host 方法 |

- 底栏可见性规则（`HomeActivity.java:151,291,437`）：`DramaSites.hasDramaSites()
  && Setting.isShortDramaCollected()`（`short_drama_collected`，默认 false）。
  B11 Flutter 首页复刻了同一规则（B11 文档 §1）。
- 电视端：**无短剧屏**。`ShortDramaActivity` 只在 mobile 清单注册
  （`app/src/mobile/AndroidManifest.xml:228`）；leanback 只有设置里的
  短剧源配置行（`SettingEnhanceActivity` + `ShortDramaSourceDialog`，
  与本屏无关，见 §1.7）。TV 零影响。
- `start()` 只有无参一个重载：进屏永远从第 0 部、FEED 模式开始。

### 1.3 三种模式与壳（`activity_short_drama.xml`，113 行）

一个 `FrameLayout` 叠了 4 层 + 底栏（`subBar`，58dp，`#F2000000`）：

| 层 | View | 模式 | 内容 |
|---|---|---|---|
| 播放流 | `pager`（`ViewPager2`，纵向，`offscreenPageLimit=1`） | FEED（默认） | 每页一个播放页（见 §1.5） |
| 网格 | `grid`（`RecyclerView`，3 列，`paddingBottom=66dp`） | THEATER | 同一份 `feed` 的卡片墙（见 §1.4） |
| 历史 | `historyList`（`RecyclerView`，纵向，`paddingBottom=58dp`） | HISTORY | 短剧源的历史记录（见 §1.4） |
| 状态层 | `progress`（居中菊花）+ `state`（居中文字，可点重试） | 全部 | 加载中 / 无源（`drama_empty`）/ 无内容（`drama_no_content`） |

底栏 5 个格：推荐（FEED）/ 剧场（THEATER）/ 源切换按钮（`btnSource`，42dp
换源图标）/ 历史（HISTORY）/ 首页（`finish()`）。`setMode()` 只切三个
View 的可见性 + Tab 颜色（当前 `#FFFF7A29`，其余白），外加播放器
暂停/恢复（见 §1.5）：切出 FEED 暂停，切回 FEED 若已有媒体项则续播；
切到 HISTORY 时 `reloadHistory()`。

**返回键**：`ShortDramaActivity` 不重写返回行为，mobile `BaseActivity`
的 `onBackInvoked()` 就是 `finish()`。即原生里从**任何模式按返回都直接
退出整个短剧区**，不存在“先回 FEED 再退出”的逐级返回。这是拆分方案
里回退语义的基准（见 §2.4）。

### 1.4 浏览部分（迁）

**数据源——源列表**：`DramaSites.detect()`（`drama/DramaSites.java`，
64 行，mobile）：遍历 `VodConfig.get().getSites()`，名字或 key 含
`短剧 / 漫短 / 漫剧 / [短]` 或 `Setting.isShortDramaSiteEnabled(key, name)`
即算短剧源。`current(sites)` 取记住的源（`Prefers "drama_tab_site"`），
否则第一个；`saveCurrent(key)` 在切换源时写回。源切换弹窗是 Activity
内联的 `AlertDialog` 单选（`showSourceDialog()`，标题 `drama_source_title
· drama_source_count %n`），选后 `detailCache.clear()` + `setMode(FEED)`
+ `loadSource(target)`。

**数据源——列表**：`loadSource(Site)`（`Task` 线程池上跑，`App.post` 回主线程）：

1. `SiteApi.homeContent(site)`（main 源集，`SiteApi.java:67`，同步蜘蛛调用，
   抛异常）→ `SiteApi.fetchPic(site, result)`（`:494`，补图）；
2. 列表为空且 `result.getTypes()` 非空 → 取第一个分类
   `SiteApi.categoryContent(site.getKey(), typeId, "1", false, ...)`（`:115`）
   再取一遍；
3. 回主线程：`feed.addAll(loaded)` + 两个 adapter 一起 `notifyDataSetChanged()`；
   空 → `showState(drama_no_content)`；非空 → `currentPage = -1`，
   `pager.setCurrentItem(0, false)`，`onPageChosen(0)`。

无分页、无筛选、无下拉刷新；`state` 文字可点重试（`loadSource(current)`）。

**剧场网格**：`GridAdapter`（3 列，`item_drama_grid.xml`，36 行）：卡片 =
海报（160dp 高，`centerCrop`，`ImgUtil.load`）+ 名字（1 行）+ 集数
（`vod.getRemarks()`，1 行，`#99FFFFFF`）。点卡片 → `setMode(FEED)` +
`pager.setCurrentItem(position, false)`。纯浏览，无播放逻辑。

**历史**：`HistoryAdapter`（`item_drama_history.xml`，51 行）+ `reloadHistory()`：
`History.get()` 全量取回后按 key 前缀过滤（key 以 `<短剧源key> + AppDatabase.SYMBOL`
开头）。行 = 海报（56×74dp）+ 名字 + meta（站点显示名 [`·` 集数备注]）+
时间（`MM-dd HH:mm`）。点行 → `VideoActivity.start(this, parts[0], parts[1],
vodName, vodPic)`（key 按 `SYMBOL` 切出 siteKey 与 vodId）。历史是只读
列表，不写、不删。

**点名字进详情**：播放页的名字点按 → `openDetail(position)` →
`VideoActivity.start(this, key, id, name, pic)`（5 参，mobile 与 leanback
双 flavor 同签名——`VideoActivity.java:848` / leanback `:827`，B2/B11
先例：main 源集 impl 可直引）。详情本体（含短剧模式的倍速记忆与自动
续播，`ShortDramaQueueCoordinator`，440 行，main）**全部留原生**，
Flutter 屏只调 host 打开它。

### 1.5 播放器部分（不迁，全部留在 `ShortDramaActivity` 内）

**播放器本体**：`buildPlayer()`（`initView` 里第一个调用）：单个
`ExoPlayer`，`DefaultHttpDataSource.Factory`（允许跨协议重定向，连接/
读取超时各 15s）+ `DefaultMediaSourceFactory`。监听器只做三件事：
READY → 藏 loading/海报；ENDED → `onVideoEnded()`；ERROR → 自动重试
一次（800ms 后重播同集，`loadRetry` 守卫），再失败显示
`drama_load_failed`（点提示文字可再试）。

**页与播放器绑定**：`PageAdapter` + `PageHolder`（`item_drama_page.xml`，
154 行）：每页一个全屏 `PlayerView`（`use_controller=false`，`resize_mode=fit`，
`surface_type=texture_view`）+ 海报（全屏 `centerCrop`）+ loading +
hint + 底部信息区（名字 17sp 粗体 / meta 12sp / 简介 2 行可展开）+
右侧 rail（收藏 / 详情 / 选集，11sp 白字 + 图标）。绑定流程
`onPageChosen → attachAndResolve → resolve → playUrl`：

- `attachAndResolve`：旧 holder 的 `player.setPlayer(null)` + 海报复位；
  新 holder `setPlayer(player)`；holder 找不到时 50ms 重试、最多 10 次。
- `resolve`：`playToken` 顶掉旧请求（`++playToken`，全部异步回调先验 token）；
  `detailOf`（`SiteApi.detailContent` + `detailCache`，key = `siteKey + SYMBOL + vodId`）
  → `firstFlag`（第一个有集数的线路）→ 第 1 集 → `SiteApi.playerContent`
  取播放地址；`parse==1 || jx==1` → 显示 `drama_need_parse`（需解析，
  不播）；空地址 → `drama_load_failed`；成功 → `recordHistory` 写
  History 行 → `playUrl`（按 `.m3u8`/`.mpd` 后缀设 MIME，`setMediaItem +
  prepare + play`）。
- `playEpisode(position, episodeIndex)`：同一套取地址逻辑，在视频流内换集
  （token 同样顶旧请求），播完自动下一集（`onVideoEnded`：同剧有下一集
  就换集，否则滑到下一部剧；已是最后一部则停住）。
- `stopPlayer()`（切源时）：`playToken++` + `stop + clearMediaItems` +
  holder 解绑。`onPause()` 暂停；`onDestroy()` 释放播放器；
  `FLAG_KEEP_SCREEN_ON`。

**播放页上的三个 rail 动作**（都在播放页内，留原生）：

- 收藏 `toggleKeep`：`Keep.find(key)` 有无切换（`Keep` 写删在 `Task` 线程），
  rail 图标 `ic_control_keep_on/off` 切换。key = `siteKey + SYMBOL + vodId
  + SYMBOL + VodConfig.getCid()`。
- 详情 `toggleContent`：简介 2 行 ↔ 全文展开（`railDetail` 与 `content`
  本身都触发；`bindDetail` 里 `Html.fromHtml(...).trim()` 后填简介，
  meta 写成 `drama_episode_meta % (1, 总集数)`）。
- 选集 `showEpisodes`：`detailOf` 取线路 → `EpisodeGridDialog`
  （mobile-only，`ui/dialog/EpisodeGridDialog.java`，原生弹窗，留原生）
  弹选集（当前集高亮自动定位）→ `onItemClick`（`EpisodeAdapter.OnClickListener`）
  按 URL 找 index → `playEpisode`。

### 1.6 浏览/播放分界线（方法与字段级）

| 成员 | 归属 | 理由 |
|---|---|---|
| `Mode` / `mode` / `setMode()`（Tab 切 View + 颜色） | 浏览（壳） | Tab 导航本身；但其中 `player.pause()/play()` 两行属播放 |
| `sites` / `current` / `DramaSites.*` | 浏览 | 源状态；播放器只消费 `current` |
| `feed: List<Vod>` | **共用** | 网格与播放流的同一份列表；拆分后需交接（见 §2.2） |
| `detailCache: Map<String, Vod>` | 播放 | 只服务 `resolve`/`playEpisode`/`showEpisodes` 的详情取用 |
| `loadSource()` / `showSourceDialog()` | 浏览 | 列表加载 + 源切换；播放器只消费结果 |
| `GridAdapter` / `HistoryAdapter` / `reloadHistory()` | 浏览 | 纯列表渲染；`reloadHistory` 读 `History.get()` 过滤 |
| `showState()` / `progress` / `state` | 浏览 | 空态与加载态 |
| `openDetail()` / 历史行点按 | 浏览（调 host） | 打开原生 `VideoActivity`，本体不动 |
| `player` / `httpFactory` / `buildPlayer()` | 播放 | ExoPlayer 本体 |
| `pageAdapter` / `PageHolder` / `activeHolder` | 播放 | PlayerView 绑定面 |
| `onPageChosen()` / `holderAt()` / `attachAndResolve()` | 播放 | 翻页 → 绑定 → 解析 |
| `resolve()` / `playUrl()` / `playEpisode()` / `onVideoEnded()` | 播放 | 取地址 + 播 + 换集 + 自动续播 |
| `playToken` / `loadRetry` / `currentPage` / `playingEpisodeIndex` / `episodePosition` / `episodeList` | 播放 | 播放会话状态 |
| `detailOf()` / `firstFlag()` / `bindDetail()` / `failPage()` | 播放 | 详情解析与页渲染 |
| `recordHistory()` | 播放（写） | 播成功才写 History 行；HISTORY  Tab 只读它（以 Room 为界，无直接调用） |
| `toggleKeep()` | 播放（页动作） | rail 上的收藏，只存在于播放页；网格无收藏按钮 |
| `showEpisodes()` / `onItemClick()` | 播放 | 流内换集 |
| `stopPlayer()` / `onPause()` / `onDestroy()` | 播放 | 生命周期 |
| `EpisodeGridDialog` / `ShortDramaQueueCoordinator` / `VideoActivity` 短剧模式 | 播放（本体） | 全部留原生，不碰 |

**分界线之外的耦合点（实施批必须处理）**：

1. `feed` 是唯一的双向耦合：网格与播放流读同一份列表；切源时
   `loadSource` 同时清两边。拆分后列表必须在 Flutter 壳与原生播放器
   之间交接一次（§2.2 的 `DramaFeedStore`）。
2. `recordHistory`（播放写）→ `reloadHistory`（浏览读）以 Room 为界，
   无直接调用；Flutter 历史页照抄 `reloadHistory` 的过滤逻辑即可，
   不依赖播放器进程状态。
3. `setMode(FEED)` 的续播行（`player.play()`）与 `setMode(非FEED)` 的
   暂停行是壳与播放器唯一的同一方法内耦合；拆分后它们随 FEED 模式
   一起留在原生 Activity 内，Flutter 壳不复刻。
4. 播放页的简介/名字/meta（`bindDetail`）是“浏览信息长在播放页上”，
   但它们由 `resolve` 的详情结果驱动，与播放会话同生命周期——
   **不能只把这三行文字抽给 Flutter**：抽文字不抽 PlayerView 在技术
   上等于用 PlatformView 嵌原生播放面，本规划不引入 PlatformView
   （§4.3 无新依赖、§4.5 不用 PlatformView 的同一精神）。
   Flutter 壳里没有“当前播放剧的信息区”；信息区只在原生播放页内出现。

### 1.7 易混淆的邻居（都不在本批内）

- `ShortDramaConfig`（140 行，main）+ `ShortDramaSourceDialog`（main）：
  短剧**源规则配置**（启用哪些源/关键词），入口在设置（增强设置/TV 增强
  设置），B4 已迁手机端。不是本屏的源切换弹窗（§1.4 的内联
  `AlertDialog`），别混淆。
- `VideoActivity` 的短剧模式（`ShortDramaQueueCoordinator` 440 行）：
  从短剧页点名字进的详情播放器，自动续播下一部；留原生，本批只调
  `VideoActivity.start` 打开它。
- `LiveActivity`（直播）与 `AudioActivity`：排除类，与本批无关。

### 1.8 资源归属（按目录分别 grep，无 head 截断）

全部 13 个 `drama_*` 字符串**只在 mobile res**（`values` + `zh-rCN` +
`zh-rTW`），main/leanback res 为 0：`drama_recommend` / `drama_theater` /
`drama_history` / `drama_home` / `drama_empty` / `drama_no_content` /
`drama_load_failed` / `drama_need_parse` / `drama_episode_meta` /
`drama_source_title` / `drama_source_count` / `drama_detail` /
`drama_episodes`。结论：B22 impl 在 main 源集，**必须**走 `mobileString()`
运行时解析（C2/B2 先例）+ main-res 回落，否则 leanback 构建失败。
`R.drawable.ic_control_keep_on/off`、`ic_control_info`、`ic_control_episodes`、
`ic_drama_swap`、`bg_drama_source` 同为 mobile-only，但它们只在原生
播放页/壳内使用，Flutter 壳用 material_ui 图标近似，不直引。

## 2. 拆分方案

### 2.1 方案 A（推荐）：Flutter 浏览壳 + 原生播放流 Activity

**形态**：新路由 `/drama`（C1 引擎，族内 Dart 导航），Flutter 壳 =
剧场网格（默认页）+ 历史 + 底栏（推荐/剧场/源切换/历史/首页，视觉与
原生 subBar 一致）。FEED 模式**不迁**：点网格卡片或点“推荐”Tab →
host 起原生 `ShortDramaActivity`（见 §2.2 的两个原生加法），播完按
返回即退出短剧区（与原生一致，§1.3）。

**原生侧加法（只加，不改既有行为）**：

1. `ShortDramaActivity.start(Activity, int position)` 新重载 + intent
   extra `EXTRA_START_POSITION`；`initView` 记 `targetPosition` 字段；
   `loadSource` 完成回调里用它代替写死的 `setCurrentItem(0)` +
   `onPageChosen(0)`（extra 缺失或越界时行为与今天完全一致）。
2. `DramaFeedStore`（新，main 源集，约 40 行）：进程内交接盒，只存
   `{siteKey, List<Vod>, savedAt}` 三个字段 + `put/get/matches/clear`。
   B22 impl 在快照加载成功后 `put`；`ShortDramaActivity.loadSource`
   先看 store（siteKey 一致就直接用，跳过一次 `homeContent` 网络调用），
   不一致就走今天的网络路径。store 是纯交接缓存：切源、清缓存、进程
   死亡后全部自然失效，无持久化、无 schema。
3. 两个入口分流（B14 同款预授权形态）：手机 `HomeActivity.handleNavItem
   (R.id.drama)` 开关开 → `C1ScreenActivity.start(ROUTE_DRAMA)`，关 →
   原样；B11 Flutter 首页 `openBottomBarItem case "drama"` 开关开 →
   `C1ScreenActivity.start(ROUTE_DRAMA)` + `activity.finish()`
   （start-then-finish，B14 追更页同款），关 → 现有反射路径。

**为什么是 A**：

- 完全沿用已验证的批次形态：一条路由、一个开关、入口分流在导航点、
  跨屏导航归 host（规划 §4.4）、`@async` 一次性快照（§4.6）、图片走
  `ImgUtil.getUrl` 委托（§4.7）、无 EventChannel。
- **不新增 G2 暴露面**：从首页（原生或 Flutter）进 `/drama` 只有
  start-then-finish 一条 Flutter→Flutter 跳法（B14 已证明的形态）；
  从 Flutter 壳进原生播放流是 Flutter→原生，不碰缓存引擎；原生
  播放流内不再起任何 Flutter 屏。
- 不动播放器一行：`buildPlayer` / `resolve` / `playToken` / 自动续播 /
  选集弹窗全部原样留在 `ShortDramaActivity` 内；回滚 = 关开关，
  两个入口点同时回原生，零行为变化。
- 代价只有一项，且是产品决策不是技术风险：**入口体验变化**——点短剧
  Tab 先看到的是网格，不再是自动开播的播放流（见 §2.4，需 Wei 定）。

### 2.2 方案 B（备选）：原生播放流留作入口，剧场/历史 Tab 分流进 Flutter

**形态**：`ShortDramaActivity` 保持入口与 FEED 宿主不变；`setMode(THEATER)`
/ `setMode(HISTORY)` 时开关开 → `C1ScreenActivity.start("/drama-theater"
或 "/drama-history")`（start-only，不 finish，原生活动留在返回栈，
按返回回播放流）；开关关 → 今天的原生网格/历史。Flutter 侧是两条独立
路由，不是族路由。

**为什么不选 B**：

1. **G2 竞态是活的**：B11 的 drama 项打开原生短剧页时**不 finish Flutter
   首页**（`B11HostApiImpl.java:623-630`，反射起 Activity 即 break），
   C1 引擎带着 `/home` 活在栈底。此时在原生短剧页里点“剧场”Tab 再起
   一个 `C1ScreenActivity`，`ensureFlutterEngine` 命中缓存引擎，Dart
   侧路由仍是 `/home`——用户看到的是首页不是剧场。这是 B7/B11 文档
   已挂账的 G2（“若复现需在 `C1ScreenActivity` 加重启机制，跨批修复，
   本批不做”），B 等于在没有重启机制的情况下又开一个 G2 暴露面。
   绕法（只在缓存引擎不存在时分流）会让行为依赖进入路径（从原生首页
   进是 Flutter 剧场，从 Flutter 首页进是原生剧场），验收无法写出
   确定性用例，不接受。
2. 偏离批次形态：Flutter 屏第一次从**原生 Activity 内部**（而不是导航
   入口）启动；`setMode` 成为第二个分流点，入口纪律变复杂。
3. 列表交接省不掉：Flutter 剧场/历史同样要 `DramaFeedStore`（方向
   反过来：原生写、impl 读），A 的组件一个不少；源切换弹窗在原生，
   Flutter 侧刷新要靠 resumed 重取 + 对话框回调补齐，缝比 A 多。
4. 收益只有一个：入口仍是自动开播的播放流（体验零变化）。但这个收益
   的代价是把一次已知的产品决策（入口改网格）换成一个已知的技术缺口
   （G2），不划算。

### 2.3 方案 C（否决）：先拆原生 Activity，再迁浏览

**形态**：先把 FEED 抽成 `ShortDramaFeedFragment`（或独立 Activity），
`ShortDramaActivity` 退化为薄壳，再按 A 或 B 迁移剧场/历史。

**为什么否决**：拆的收益是“原生结构更干净”，但 Flutter 侧仍然托管
不了播放流（没有 PlatformView，本规划不引入），拆完后的迁移形态与 A
（或 B）**一模一样**——等于先付一次高风险原生重构（ViewPager2 +
ExoPlayer 挂接/解绑 + token 守卫 + 生命周期，全部在播放器热路径上），
再走 A 的路。重构本身没有可验收的用户价值，且 `ShortDramaActivity`
今天工作正常。唯一合理的触发条件是将来 C3 真要迁播放流，那时再拆；
本次否决，记为远期选项。

### 2.4 必须由 Wei 定的一项产品决策（A 的唯一前置条件）

A 把短剧入口从“一点就播的竖流”改成“先看网格、点一张才进竖流”。
这不是实现细节，是用户第一眼看到的东西。两种接受形态：

- **接受**：按 A 实施；验收清单 §4 第 1 项即为该决策的验收点。
- **不接受**：短剧批停在 B 的技术缺口上——要么等跨批的 `C1ScreenActivity`
  relaunch 机制先落地再按 B 做，要么短剧整屏继续留原生（回到规划 P5
  的原建议）。不要在“入口不变”的要求下强行按 A 做（A 的壳默认页改
  历史/推荐都绕不开这一点：Flutter 壳里没有播放流可默认）。

附带说明（写进验收备注，不另决策）：A 里从 Flutter 壳进原生播放流用
start-then-finish——壳 finish 掉，按返回从播放流出来**直接回首页**，
与原生“任何模式按返回都退出短剧区”逐字一致。备选 start-only（壳留
在栈底，按返回回网格）更顺手但偏离原生回退语义，实施批不要擅自改，
如 Wei 想要再议。

## 3. 实施范围（按方案 A）

### 3.1 迁什么（B22，开关 `flutter_b22_enabled`，默认关）

| 内容 | 原生来源 | Flutter 形态 |
|---|---|---|
| 剧场网格 | `GridAdapter`（3 列，160dp 海报 + 名字 + 集数） | `/drama` 默认页；卡片走共享 `VodCard`/`PicImage`（只加法，不改 B2 的卡片）；点卡片 → host `openFeed(position)` |
| 历史 | `HistoryAdapter` + `reloadHistory()`（56×74 海报 + 名字 + meta + `MM-dd HH:mm`） | `/drama` 族内页（Dart 内 Tab 切换，不经过 host）；点行 → host `openHistoryItem`（`VideoActivity.start` 5 参） |
| 底栏 | subBar（推荐/剧场/源切换/历史/首页） | Dart 底栏，视觉与原生一致（当前项 `#FFFF7A29`）；“推荐”Tab 是动作（开播放流第 0 部），不是一个视图；“首页”Tab → host `closeScreen`（finish，与原生一致） |
| 源切换 | `showSourceDialog()`（内联 `AlertDialog` 单选） | 弹窗留原生：host 方法打开，选后 host 重载并完成 `@async` 回调（B3 V1 同款：弹窗类 `@async` 只在状态可能变化时完成）；Dart 随后重取快照 |
| 列表加载 | `loadSource()`（`homeContent` → `fetchPic` → 空则 `categoryContent` 首分类） | host `@async` 一次性快照（35s 守卫，B8 同款形态；`homeContent` 是同步蜘蛛调用，无 LiveData，`Task` 线程池 + latch 即可）；下拉刷新 + resumed 重取（规划 §4.6）；空态文案与重试（`state` 可点重试）照抄 |
| 空态 | `drama_empty`（无源）/ `drama_no_content`（空列表） | 快照字段，Dart 只渲染 |

### 3.2 不迁什么

- FEED 播放流整体：`ExoPlayer`、`PageAdapter`/`PageHolder`、PlayerView、
  `resolve`/`playUrl`/`playEpisode`/`onVideoEnded`、`playToken`/`loadRetry`、
  `detailCache`、选集弹窗、收藏 rail、简介展开、自动重试、自动续播、
  `recordHistory`、`stopPlayer`、`onPause`/`onDestroy` 生命周期。
- 详情本体：`VideoActivity`（含短剧模式 `ShortDramaQueueCoordinator`），
  只调 `start` 打开。
- 短剧源规则配置（`ShortDramaConfig`/`ShortDramaSourceDialog`，B4 已迁），
  与本屏无关。
- 电视端：无短剧屏，不动；leanback 设置里的短剧源配置行不动。

### 3.3 开关、路由与入口

- 开关 `flutter_b22_enabled`（`Setting`，默认 false，实验屏唯一开关 UI，
  与 B2–B21 同款）；关 = 两个入口点同时回原生（`HomeActivity` drama 项
  + B11 drama 项），原生实现一行不删。
- 路由 `/drama`（C1 引擎，`C1ScreenActivity.ROUTE_DRAMA`）；族内 Tab
  （剧场/历史）纯 Dart 切换，不经过 host（规划 §4.4 族路由）；跨屏
  （开播放流 / 进详情）一律调 host（§4.4 跨屏导航归 host）。
- 实施批新增的原生改动只限 §2.1 的三处：`start(Activity, int)` 重载 +
  `targetPosition`、`DramaFeedStore`、两处入口分流。原生播放流的
  既有方法一行不改。

### 3.4 Pigeon B22 合约草图（只够短剧壳用）

新文件 `flutter_module/pigeons/b22_drama_messages.dart`，CI 内生成
（`b8-c0.yml` 加两行，`.gitignore` 加 `B22Messages.java`，C1/C2 同款）。
**全部展示文案与计算值由 host 返回**，Dart 不硬编码文案、不算时间格式。
方法名大小写以 Dart 定义为准，Java 侧原样保留；Java 侧 `bool`/`int`/
`double` 一律装箱（C1 教训）。

```dart
@HostApi()
abstract class B22HostApi {
  bool isB22Enabled();
  void setB22Enabled(bool enabled);

  /// 一次快照：源列表 + 当前源 + 网格列表 + 全部壳文案。
  /// host 在 Task 线程上跑 loadSource 同款逻辑（homeContent → fetchPic
  /// → 空则 categoryContent 首分类），成功后把列表写进 DramaFeedStore
  /// 再完成回调；35s 守卫。
  @async B22DramaScreen getDramaScreen();

  /// 历史是另一份快照（History.get() 按短剧源 key 前缀过滤，reloadHistory 同款）。
  @async B22DramaHistoryScreen getDramaHistory();

  /// 源切换弹窗（原生 AlertDialog，留原生）。选后 host 重载当前源并
  /// 完成回调（B3 V1 同款）；取消则完成一个“未变”快照，Dart 不重取。
  @async B22DramaScreen selectDramaSource();

  /// 下拉刷新：重跑 getDramaScreen 同款逻辑。
  @async B22DramaScreen reloadDramaScreen();

  /// 点网格卡片 / “推荐”Tab：host 起原生 ShortDramaActivity
  ///（start(Activity, position)，store 已有列表则免一次网络加载），
  ///随后 finish 当前 C1 屏（start-then-finish，与原生回退语义一致）。
  void openFeed(int position);

  /// 点历史行：host 调 VideoActivity.start(key, id, name, pic)（5 参，
  /// 双 flavor 同签名，直引安全）。
  void openHistoryItem(String siteKey, String vodId, String vodName, String vodPic);

  /// “首页”Tab：finish，与原生一致。
  void closeScreen();
}

class B22DramaScreen {
  String title;
  String recommendLabel; String theaterLabel; String historyLabel; String homeLabel;
  String emptyText; String noContentText; // drama_empty / drama_no_content
  String sourceTitle; // drama_source_title（弹窗标题 host 侧拼 count）
  List<B22DramaSite> sites; String currentSiteKey;
  List<B22DramaItem> items; // 网格：与 B2 卡片同字段形态
}

class B22DramaSite {
  String key; String displayName;
}

class B22DramaItem {
  String id; String name; String remarks;
  String picUrl; String picReferer; String picCookie; String picUserAgent;
  String fallbackPicUrl; String placeholderText; int placeholderColor;
}

class B22DramaHistoryScreen {
  List<B22DramaHistoryItem> items;
}

class B22DramaHistoryItem {
  String siteKey; String vodId; String vodName; String vodPic;
  String meta; String timeText; // host 算好的 "站点 · 集数" 与 "MM-dd HH:mm"
  String picUrl; String picReferer; String picCookie; String picUserAgent;
  String fallbackPicUrl; String placeholderText; int placeholderColor;
}
```

- 图片字段形态即 D3 的 `PicSpec`（`ImgUtil.getUrl` 委托，`data:` 占位，
  不更新 `RouteSelector` 健康信号——C2 已知缺口，全批一致）。
- `List<B22DramaItem>` + `List<B22DramaSite>` 同一快照内的双 `List<>`
  是本批的新 Pigeon 形态，待 CI 证明（B18 有三 `List<>` 同快照的先例，
  形态风险低于首创）。

### 3.5 实施批文件清单（草案）

新：`flutter_module/pigeons/b22_drama_messages.dart`、
`flutter_module/lib/b22_drama_page.dart`、
`app/src/main/java/.../flutter/B22HostApiImpl.java`、
`app/src/main/java/.../drama/DramaFeedStore.java`（main 源集，交接盒）、
`docs/b8-b22-drama.md`。
改（只加法 + 两处分流）：`flutter_module/lib/main.dart`（`/drama` 路由 +
B22 开关）、`C1ScreenActivity.java`（注册 + `ROUTE_DRAMA`）、
`C0ShellActivity.java`（注册，开关用）、`Setting.java`（`flutter_b22_enabled`）、
`ShortDramaActivity.java`（`start(Activity, int)` 重载 + `targetPosition` +
`loadSource` 里先读 store——三处加法，播放方法不动）、
`HomeActivity.java`（drama 项分流）、`B11HostApiImpl.java`（`case "drama"`
分流）、`.gitignore`、`b8-c0.yml`。

## 4. 逐项对照清单（Wei 真机验收用）

先在实验屏打开 B22 开关，再从首页底栏短剧项进 Flutter 壳，每项与原生
（开关关闭后）逐条比：

**壳与网格**
1. （产品决策验收）入口先看到剧场网格，不是自动开播的播放流；底栏
   5 格与原生一致（推荐/剧场/源切换/历史/首页），当前项橙色 `#FFFF7A29`。
2. 网格 3 列，卡片海报/名字/集数与原生一致；无源时 `drama_empty`，
   空列表时 `drama_no_content`，点状态文字重试；下拉刷新重取。
3. 点卡片进原生播放流，播的正是点的那部第 1 集（不是第 0 部）；点
   “推荐”Tab 进播放流第 0 部；播放流内收藏/选集/简介/名字进详情
   与原生一致（这些都在原生播放页内，本批未动，回归确认即可）。

**源切换**
4. 点源切换出原生单选弹窗（标题含源数量），切源后网格换成新源列表，
   播放流随后进的也是新源；取消则列表不变。

**历史**
5. 历史 Tab 列表与原生一致（海报 56×74、名字、站点 [`·` 集数]、
   `MM-dd HH:mm`）；点行进原生详情页；看完一部短剧回历史，记录在
   （resumed 重取；播放写 History、历史只读，Room 为界）。

**播放交界（回归项，本批未动，逐项确认无回归）**
6. 播放流内滑到下一部自动播第 1 集；一集播完自动下一集，最后一集播
   完自动滑下一部；需解析的源显示 `drama_need_parse` 不播；弱网失败
   显示 `drama_load_failed`，点提示重试；切后台/切剧场再回暂停与续播
   与原生一致。
7. 旋转：Flutter 壳不崩溃（C1 引擎缓存）；原生播放流旋转后重进第 0 部
   （与原生今天一致，不视为回归）。

**开关与回滚**
8. 开关关掉后，短剧入口回到原生页（一点就播的竖流）；电视端不受影响
   （无短剧屏）；设置里的短剧源配置行不受影响。

## 5. 未能验证的项（全部标注“待 CI 证明 / 待真机证明”）

| # | 待证明项 | 证明方式 | 关联门 |
|---|---|---|---|
| 1 | **产品决策**：入口改网格先行，Wei 是否接受（§2.4） | Wei 一句话；不接受则本批按 B 或停 | 决策门 |
| 2 | `DramaFeedStore` 交接正确：siteKey 匹配才用、切源/进程死亡后失效、列表与原生 `loadSource` 结果一致；store 里 Vod 列表的内存占用 | 实施批代码 + 真机（§4 第 3 项：点的那部正是播的那部） | — |
| 3 | `start(Activity, int)` + `targetPosition`：异步加载完成后再定位，加载中旋转/切源/返回不串位、不崩溃 | 真机（§4 第 3、7 项） | — |
| 4 | Pigeon 新形态：同一快照内 `List<B22DramaSite>` + `List<B22DramaItem>`，`@async` + fire-and-forget 混用 | CI：`dart run pigeon` + `flutter analyze` + Gradle 编译 | G6/G7 |
| 5 | 13 个 mobile-only `drama_*` 字符串走 `mobileString()`（§1.8），leanback 构建通过 | CI（leanback 风味） | G6 |
| 6 | 首页 → `/drama` 的 start-then-finish 不触发 G2（B14 同款形态，真机复核） | 真机：原生首页 → 短剧壳 → 返回 → 再进；Flutter 首页 → 短剧壳 → 返回 → 再进 | G2 |
| 7 | 源切换后壳与播放流的列表一致性（壳重取 vs 播放流内切源，resumed 重取覆盖） | 真机（§4 第 4 项） | R3 |
| 8 | `flutter analyze`：新增 Dart 文件无未使用 import、无 warning（C1 run #8 教训：一个 warning 即失败） | CI | — |
| 9 | §4 对照清单逐项通过；开关关闭后回到原生 | Wei 真机验收 | 验收 |

## 6. Recovery anchor（恢复锚点）

- 目标：短剧批拆分设计（见 §0 完成句）；评估见
  `docs/b8-flutter-rewrite-assessment.md`，全量规划见
  `docs/b8-full-migration-plan.md`（§2.3、§6 P5），B11 与 G2 见
  `docs/b8-b11-home.md`。
- 状态：调研完成；推荐方案 A（Flutter 浏览壳 `/drama` + 原生播放流
  Activity，§2.1）；B 备选（G2 活缺口，不推荐）、C 否决；A 的唯一
  前置条件是 §2.4 的产品决策（入口改网格先行）待 Wei 定。无产品代码改动。
- 分支/HEAD：`b8-flutter-rewrite`，基线 `01fa554d`（B21）；本文档为本次
  唯一新增文件。
- 变更文件：仅 `docs/b8-short-drama-scoping.md`（新建）。
- 验证：`git diff --check` 通过；diff 仅含本文档；`main` 仍指向 `f39a6e0`；
  工作树干净；构建/真机验证全部待 CI（§5）。
- 未解决风险：§5 的 9 项待证明项；最大两项见汇报（入口改网格的产品
  决策、交接盒 + 定位重载的正确性）。
- 下一步（恰好一个）：Wei 对 §2.4 表态接受 A 后，另开一个 task_guard
  standard 会话实施短剧批（B22），新合约 `b22_drama_messages.dart`，
  开关 `flutter_b22_enabled` 默认关，不推送；若 Wei 不接受，则按
  §2.4 议 B 或停。
