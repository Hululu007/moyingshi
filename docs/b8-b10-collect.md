# B10 聚合搜索（B8 全量迁移）

> §0 执行声明：本批把手机端聚合搜索页（多站源并行搜索 + 进度显示 + 结果列表）迁移到 Flutter。
> 任务护栏 `b8-b10-collect-search`（standard），分支 `b8-flutter-rewrite`，基线 B9 提交 `46d76ce6`。
> 开关 `flutter_b10_enabled` 默认关，独立于其他开关；开关 UI 只在实验屏。关 = 聚合搜索页回原生，原生实现一行不删。
> **本机无 Java / Android / Flutter SDK，构建与运行均未验证**（CI 联编 + 真机验收时按 §6 逐项证明）。不推送。

## §1 原生页

原生实现：`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/CollectFragment.java`（780 行），
宿主 `SearchActivity`（mobile，154 行）+ 输入页 `SearchFragment`（479 行）。

### §1.1 入口（全部被本批接管）

| 入口 | 原生行为 | 本批 |
|---|---|---|
| `SearchActivity.start(activity, keyword, siteKey, pic, wallPic)` | B9 已分流：B9 开关开 → Flutter 输入页 | 不动（B9 的） |
| `SearchActivity.direct(activity, keyword, siteKey, pic, wallPic)` | 直接开结果页（`CollectFragment.newInstance(keyword, siteKey, "", pic, wallPic, -1, "", -1)`） | B10 开关开且调用方不是 C1ScreenActivity → `/collect` 路由；否则原生 |
| `SearchActivity.directFromHistory(activity, history, keyword, targetCid)` | 直接开结果页（keyword 为空时取 `history.getVodName()`，pic/wallPic 取 history，hr 参数进 intent） | 同上分流，hr 参数进路由 query |
| `SearchFragment.collect(keyword)`（原生输入页提交） | 加 `CollectFragment`、隐藏输入页、进回退栈；工具栏"编辑"发 fragment result `{"edit": true}`（key `"result"`），输入页监听后 `Util.showKeyboard(keyword)` | B10 开关开 → `C1ScreenActivity` `/collect` 路由；否则原生 |
| B9 输入页 `_submit()` | B9 遗留：结果走原生 direct 模式（V5 分组范围暂不提供，V8 edit 回调丢失） | B10 开关开 → 族内 `Navigator.push(B10CollectPage)`；关 → 原有 `submitSearch` 不变 |

`direct()` 的原生调用方：`TmdbPersonActivity:248/258`、`TmdbDetailActivity:12608/12798/12823`、
`TmdbNavigation:112`、`HomeWebBridge:433`、`B9HostApiImpl:388`；`directFromHistory()` 的调用方：
`HistoryResumeCoordinator:88/150`。`SearchActivity.onBackInvoked` 先弹 fragment 回退栈（原生）。

### §1.2 数据来源（host 直接复用，不重写）

- `SiteViewModel.searchContent(List<Site>, keyword, false)`：多站并行搜索；新 epoch（`stopSearchLocked()` 取消旧 future，
  旧 executor `shutdownNow`）；先发 `SearchProgress.start(total)`，每站 `SearchTask` future，超时 `Constant.TIMEOUT_SEARCH`（30s），
  epoch 守卫后 post `search` LiveData 与进度；出错只发进度不发 search 值。
- `SiteViewModel.getSearch()`：每站 `Result` 到达 → `setCollect`（追加进主列表、按相似度过滤可见项、应用筛选、
  相似度过滤导致可见页为空时自动加载下一页 `maybeLoadNextPage` / `shouldContinue`）。
- `SiteViewModel.getSearchProgress()`：`SearchProgress(current, total, finished)`；`current >= total > 0` 时 `mSearchCompleted`。
- `SiteViewModel.getResult()`：单站分页结果到达 → `setSearch`（`SearchPageState.complete`，page token 去重——重复 token 标记该站 exhausted）。
- `SearchPageState`（main，179 行）：`begin`（仅当有站、无 pending、`canLoadMore`、请求的页 == 当前页 + 1）、
  `complete`（page token 去重，`accepted` 仅当有内容 + 同站 + 未重复；无 pending 时返回 NONE）、
  `canLoadMore`（pageCount == 0 或 page < pageCount，且未 exhausted）、`shouldContinue`（精确筛选且原始页有内容且可见页无内容且无 pending 且可加载更多）。
- `SearchResultFilter.filter(items, keyword, similarityPercent)`（main，212 行）：相似度过滤，host 直接调用。
- `Collect.all()` = `Site.get("all", ResUtil.getString(R.string.all))`，selected=true；`Collect.getPage()` 最小 1。
- `Site.getGroups(List<Site>)` + `SiteGroupOrderStore.sort(...)`：分组选项；站搜索或分组搜索时分组为空。
- `SiteHealthStore.sortSites(mSites)` / `flush()`：站排序与落盘（onDestroyView 时 flush）。
- `SearchGridLayout.resolveSpanCount / resolveItemWidth`（main）：网格列数与卡宽。
- `Product.getColumn(context)` / `getSpec(context, column)`（mobile）：`count = isWide ? 7 : 5; +pad ? 1 : 0; return abs(PlayerSetting.getSize() - count)`；
  注意：聚合搜索用的是无 Style 的 `getColumn(context)`，**没有** B8 `categoryColumn` 的 land-style −1。
- `Setting.getSearchUi()`（0 横排源列表 / 1 左列）、`getSearchColumn()`（0=1 列 / 1=2 列，clamp，默认 0）、
  `getSearchResultSort()`、`getSearchSimilarity()` / `putSearchSimilarity()`、`getKeyword()` / `putKeyword()`。

### §1.3 原生页结构（Dart 侧逐项对照的基准）

- 布局：顶部工具栏；源列表 56dp 行——横排（`searchUi == 0`，含 "全部" 行，显示 `current/total` 进度）或左列
  （宽按最长站名算出，最小 120dp，最大半屏 − 40dp）；结果区为 `GridLayoutManager` 的 RecyclerView（paddingStart 0 / paddingEnd 8dp）
  或列表；空态 TextView（默认文案 `search_filter_empty`，gone）。
- 结果卡：网格用 `AdapterVodRectBinding`（矩形卡）；列表行用 mobile `adapter_search.xml`：128dp 行、96dp 缩略图、
  名称 14sp 最多 3 行、备注 12sp、站名角标 12sp 最大 90dp 左上。
- 菜单：分组筛选（仅非站搜索/非分组搜索且分组非空时可见；弹窗最多 8 项 × 44dp；"全部" + 各分组）、
  相似度筛选（`SliderNumberDialog` 0–100%，`Setting.putSearchSimilarity` 持久化，重建可见项）、
  列数切换（1 ↔ 2 列，`Setting.putSearchColumn`）。
- 点击：文件夹 → `FolderActivity.start`（续看时 6 参重载，否则 2 参）；否则历史续看 →
  `HistoryResumeCoordinator.openSelected(...)`，否则 `VideoActivity.collect(activity, siteKey, id, name, pic, wallPic, keyword)`
  （pic 为空时回退到入口 pic）。
- 空态：仅当 similarity > 0、搜索完成、未加载中、激活列表为空、且原始结果存在时显示
  （`search_filter_empty` / `search_filter_empty_exact`）；搜索完成且零可见项时原生留空白区。
- 加载更多：`CustomScroller` 触底 → `mPaging.begin(siteKey, requestedPage)`（仅 page+1、仅 `canLoadMore` 时）→
  `searchContent(site, keyword, false, page)`（`TIMEOUT_VOD` 30s，`SiteHealthStore.recordSearch`）。

## §2 设计

### §2.1 合约（`flutter_module/pigeons/b10_collect_messages.dart`）

`B10HostApi`（一个 impl，一份合约；C0/C1/C2/B2–B9 合约冻结不动）：

| 方法 | 形态 | 说明 |
|---|---|---|
| `isB10Enabled() → bool` | 同步 | 实验屏开关读 |
| `setB10Enabled(bool)` | 同步 void | 实验屏开关写 |
| `startSearch(keyword, currentSite, siteKey, group, pic, wallPic, hrCid, hrKey, hrTargetCid) → B10CollectScreen` | `@async` | 建会话、发起多站搜索；进度完成或 35s 看门狗时完成（见 §2.2） |
| `getProgress() → B10Progress` | 同步 | Dart 300ms 轮询用 |
| `getSnapshot() → B10CollectScreen` | 同步 | revision 变化时 Dart 重读 |
| `loadMore() → B10CollectScreen` | `@async` | 加载更多（链式自动续页，链结束或看门狗时完成） |
| `setActiveSource(siteKey) → B10CollectScreen` | 同步 | 切源（返回新快照） |
| `setGroupFilter(group) → B10CollectScreen` | 同步 | 切分组（返回新快照） |
| `setColumn(int) → B10CollectScreen` | 同步 | 列数切换（返回新快照） |
| `openSimilarityDialog() → B10CollectScreen` | `@async` | 相似度滑杆对话框（任意路径完成） |
| `openCollectItem(itemId, itemName, itemPic, itemIsFolder, itemSiteKey)` | 同步 void | 点结果卡片进详情（走 host，跨屏导航恒走 host） |
| `recordSearch(keyword)` | 同步 void | 250ms 延迟记一条搜索记录（B9 同款） |
| `cancelSearch()` | 同步 void | 取消搜索、清观察者、清状态 |

数据类（扁平；图片字段复用 B8 PicSpec 形）：

- `B10CollectScreen`：title、groups、currentGroup、canFilterGroup、allScopeLabel、sources、items、activeSiteKey、
  columnCount、isGrid、gridSpanCount、cardImageWidthPx、cardImageHeightPx、sourceColumnWidthPx、horizontalSources、
  similarity、similarityTitle、emptyVisible、emptyText、errorTitle、errorMessage、retryLabel、
  progressCurrent、progressTotal、searchFinished、hasPendingPage、canLoadMore、revision。
- `B10SourceItem`：siteKey、displayName、selected、progressText。
- `B10CollectItem`：id、name、year、remarks、siteName、siteKey、siteVisible、nameVisible、remarkVisible、yearVisible、
  isFolder + PicSpec 图片字段（picUrl、rawPic、picReferer、picCookie、picUserAgent、picExtraHeadersJson、
  fallbackPicUrl、placeholderText、placeholderColor）。**无 isAction / action**——CollectFragment 没有 action 分支。
- `B10Progress`：current、total、finished、revision、hasPendingPage。

### §2.2 会话模型（host 持有全部 CollectFragment 等价状态）

`B10HostApiImpl` 持有：`SiteViewModel` + 3 个 `observeForever` 观察者（`onSearchResult` / `onSearchProgress` / `onPageResult`，
分别对应原生 `setCollect` / `setSearchProgress` / `setSearch`，adapter 调用换成状态更新 + `updateSelection()` +
`maybeLoadNextPage` / `maybeLoadSelectedPage`）、主列表、可见列表、`SearchPageState`、过滤分组、相似度、进度、revision、激活源。
`startSearch` 解析站键（规则见 §2.5），`setSites()` / `setWidth()` 建站与分组，种子 `Collect.all()`（sort==1 时再加每站行），
零可搜站时立即完成（原生永不完成，页面只显示 "全部" 行；见 V11）。
`@async` 完成保证：`completed` 守卫 + `App.post` 回主线程完成；搜索与分页各一个 35s 看门狗（`TIMEOUT_SEARCH` / `TIMEOUT_VOD` 均为 30s，
B8 惯例 +5s）；`completeSearch` / `completeLoadMore` / `maybeCompleteLoadMore` 恰好一次。
`cancelSearch`：完成待处理回调、`stopSearch`、移除观察者、清空状态、`SiteHealthStore.flush()`。
重新搜索时旧 epoch 被 `SiteViewModel` 的 `stopSearchLocked()` 替代（与原生同机制）。

### §2.3 进度（300ms 轮询快照，无 EventChannel）

Dart 用 `Timer.periodic(300ms)` 轮询 `getProgress()`，仅在 `_needsPolling` 时运行
（搜索待处理 || 加载更多待处理 || 快照 `hasPendingPage`）；revision 变化时重读 `getSnapshot()`；
`dispose` 时停表、dispose 滚动控制器、fire-and-forget `cancelSearch()`。流式以 300ms 粒度近似（见 V2）。
下拉刷新 = 重新发起搜索；resumed 时重取第 1 页（B8 V7 同款；原生不重取，见 V7）。

### §2.4 导航（族内 push + G2 护栏，不加 relaunch 机制）

- B9 输入页 → 结果页：**同引擎 Dart `Navigator.push`**（规划 §4.4：`/search` 族内跳转纯 Dart，不经过 host）。
  这恢复了 B9 V8：返回 / 编辑回到输入页，keyword 完好（键盘弹出不恢复，见 V8）。
  备选方案（分流 `direct()` + start-then-finish 第二个 C1ScreenActivity）被否决：它确定性复用旧引擎旧路由，
  且旧 Activity 的 `onDestroy` 销毁引擎——正是 B7 文档 §6 G2 / V10 悬而未决的竞态
  （"若复现，需在 C1ScreenActivity 加 relaunch 机制（跨批修复，本批不做）"）。**本批不加 relaunch 机制**。
- `direct()` / `directFromHistory()` / `SearchFragment.collect()` 分流：B10 开关开时走 `/collect` 路由；
  调用方是 `C1ScreenActivity` 时跳过分流、落回原生（G2 护栏，见 V9）——B9 页的提交走族内 push，永不经过 `direct()`。
- 结果卡片点击走 host（`openCollectItem`）：文件夹经反射调 mobile-only 的 `FolderActivity.start`（2 参 / 6 参，
  使 B8 的 `FolderActivity.start` 分流继续生效；反射失败回退 `VideoActivity.collect`），
  否则历史续看 `HistoryResumeCoordinator.openSelected`，否则 `VideoActivity.collect` 7 参（入口 pic 回退）。
- 工具栏标题点击与编辑图标 = 返回：族内模式 pop 回输入页；路由模式关闭 host 屏。
- 族内返回时 B10 页 dispose → `cancelSearch()`（原生 fragment 销毁时同样停止搜索）。

### §2.5 站键约定（与 B9 / SearchFragment 字符级一致）

host 规则：`currentSite && siteKey 为空 ? homeKey : siteKey`。B9 Dart 预先应用（仅 currentSite 时传 siteKey）；
SearchFragment 传预解析的 siteKey + currentSite 标志；`direct()` 传显式 siteKey + `currentSite=false`。
与 `B9HostApiImpl.submitSearch` 及 `SearchFragment.getSearchSiteKey()` / `getSearchGroup()` 完全一致。

### §2.6 对话框与图片

- 相似度对话框内联构建（`LightDialog.create` + `setOnDismissListener`，内容复制 `SliderNumberDialog`），
  因为 `SliderNumberDialog.show()` 返回 void 且无 dismiss 钩子；B3/B4 前例（`openDetailOpenMode`、`NovelSourceDialog.create(...).onDismiss(...)`）。
  任意路径完成 `@async`（B3 `openDetailOpenMode` 前例）。
- 图片：host 委托 `ImgUtil.getUrl` 解析（D3，B8 同款 `resolvePic` / `splitHeaders`，含漂移警告注释）；
  `data:` 显示占位（D6）；Dart 侧复用共享 `PicImage`，网格卡复用共享 `VodCard`，列表行在 B10 页内写（128dp 行、96dp 缩略图，
  与 mobile `adapter_search.xml` 一致；**不是** B8 的 40dp `adapter_vod_list.xml` 行）。
- 网格几何：`Product.getColumn(context)`（无 Style，故无 land-style −1，见 §1.2）+ `SearchGridLayout.resolveSpanCount / resolveItemWidth`；
  列表行 128dp/96dp；网格项间距 8dp（原生 4dp 边距的近似，见 V13）。
- 全部展示文案与计算值由 host 返回，Dart 不硬编码文案；`VodCard` / `PicImage` / `SettingsRow` 只加法，本批未改。

## §3 文件清单

| 文件 | 变动 |
|---|---|
| `flutter_module/pigeons/b10_collect_messages.dart`（新，~12 KB） | `B10HostApi` + `B10CollectScreen` / `B10SourceItem` / `B10CollectItem` / `B10Progress` |
| `flutter_module/lib/b10_collect_page.dart`（新，~24 KB） | `B10CollectPage` + `_SourceRow` / `_B10ListRow` / `_LoadErrorView` |
| `flutter_module/lib/main.dart` | `/collect` 路由（onGenerateRoute）+ B10 开关；B9 开关副标题改一字（"结果页由'聚合搜索'开关控制"） |
| `flutter_module/lib/b9_search_page.dart` | 仅 `_submit` + imports + 一行类注释：B10 开关开时族内 push（V5/V8 恢复项，任务冻结条款的例外） |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B10HostApiImpl.java`（新，~1300 行） | 会话 + 3 观察者 + 快照构建 + 相似度对话框 + 点击分流 |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java` | 注册 `B10HostApi`、加 `ROUTE_COLLECT = "/collect"` |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java` | C0 引擎注册 `B10HostApi`（仅开关方法） |
| `app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java` | `isFlutterB10Enabled()` / `putFlutterB10Enabled(boolean)`（`flutter_b10_enabled`，默认 false） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/activity/SearchActivity.java` | `direct()` / `directFromHistory()` 分流 + G2 护栏；`start()` 注释更新 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SearchFragment.java` | `collect()` 分流（预解析 siteKey/group，pic/wallPic null 守卫） |
| `.gitignore` | 加 `B10Messages.java` |
| `.github/workflows/b8-c0.yml` | Pigeon 步骤加 b10 生成 + 2 个存在性检查（B9 同款） |
| `docs/b8-b10-collect.md`（本文件） | 任务文档 |

`B9HostApiImpl.java` 未动；B9 合约未动；共享组件未动；TV 不动；Room/schema 不动；播放器不动；
`android-release.yml` 不动；无新 Dart 依赖。

## §4 逐项对照清单

验收时开关开（实验屏），逐项与原生对比；开关关 = 原生。

- [ ] **多站源并行搜索**：输入关键词提交后多站同时出结果；"全部"行显示 `current/total` 进度；站行逐站出现，含 `Collect.all()` 伪站。
- [ ] **进度显示**：顶部/源行进度随搜索推进变化；搜索完成时进度消失；30s 超时站不卡住页面（35s 看门狗完成）。
- [ ] **结果列表**：网格（矩形卡，`VodCard`）与列表（128dp 行、96dp 缩略图、名称 14sp 最多 3 行、备注 12sp、站名角标 12sp 最大 90dp）两种形态；
  列数切换 1 ↔ 2 列生效且持久化；下拉刷新重新搜索。
- [ ] **分组范围**（B9 V5 恢复）：非站搜索/非分组搜索且分组非空时，分组菜单可见，"全部" + 各分组；切分组后结果按组过滤；站搜索时菜单隐藏。
- [ ] **相似度筛选**：滑杆对话框 0–100%，改值后可见项重建，持久化；取消也正常返回（对话框任意路径完成）。
- [ ] **加载更多**：滚动到底部 200px 内触发；仅 page+1、仅 `canLoadMore` 时加载；相似度过滤致可见页为空时自动续页；
  重复 page token 的站标记 exhausted，不再加载。
- [ ] **取消**：离开页面（返回/编辑/进详情后返回不计）停止搜索；再进页面重新搜索正常。
- [ ] **空态**：similarity > 0、搜索完成、激活列表空、原始结果存在时显示过滤空态文案；搜索完成零可见项时显示通用空态（原生留空白，见 V3）。
- [ ] **错误态**：通道断裂显示错误文案；列表加载失败显示错误视图 + 重试按钮，重试点后重新加载。
- [ ] **结果点击**：文件夹进文件夹页（B8 分流生效）；历史续看进续看；普通项进详情（`VideoActivity.collect`，pic 为空回退入口 pic）。
- [ ] **返回 / 编辑**：族内（B9 输入页进）返回回输入页、keyword 完好；路由模式（direct 进）返回回搜索前屏幕；标题点击与编辑图标同返回。
- [ ] **入口覆盖**：首页搜索栏、TMDB 人物/详情页、Web 桥、历史续看、原生输入页提交、B9 输入页提交——六路入口结果页一致。
- [ ] **回滚**：实验屏关掉 B10 开关 → 聚合搜索页回原生；全部开关关 = 全原生；本批提交可独立 revert（共享文件只含加法行，
  除 B9 `_submit` 的恢复项与 main.dart 一行副标题修正）。

## §5 相对规划的偏差（V1–V14）

- **V1 族内 push 改 `_submit`**（B9 文件）：B9 输入页 → 结果页用 Dart `Navigator.push`，而非纯 `direct()` 接管。
  这是 B9 V5/V8 的恢复项，任务冻结条款的例外；`B9HostApiImpl.java` 与 B9 合约未动。
- **V2 进度/结果流式以 300ms 轮询快照近似**：无 EventChannel；revision 变化时重读快照；进度粒度 300ms。
- **V3 通用空态 `widget_empty`**：搜索完成且零可见项时 Dart 显示通用空态文案；原生留空白区。
- **V4 分组菜单用 `PopupMenuButton`**：原生自定义 PopupWindow；样式与最大 8 项 × 44dp 的弹窗形态未逐像素复刻。
- **V5 分组范围在结果页恢复**：入口分组仅来自原生输入路径；B9 入口恒为 `""`（B9 输入页本身不提供分组选择）。
- **V6 相似度对话框内联构建**：复制 `SliderNumberDialog` 内容（`LightDialog.create` + `setOnDismissListener`），漂移警告已注；
  `SliderNumberDialog.show()` 本身无 dismiss 钩子，无法直接复用。
- **V7 resumed 重取**：B10 页 resumed 时重新发起搜索；原生不重取。
- **V8 编辑恢复返回 + keyword，不恢复键盘弹出**：族内返回回输入页、keyword 完好；原生的 `Util.showKeyboard(keyword)` 未接；
  路由模式返回去搜索前屏幕，不回输入页。
- **V9 G2 护栏**：`direct()` / `directFromHistory()` 的调用方是 C1ScreenActivity 时跳过分流、落回原生结果页。
- **V10 文件夹点击经反射**：mobile-only `FolderActivity` 反射调用（B9 SiteDialog 同款），失败回退 `VideoActivity.collect`。
- **V11 零可搜站立即完成**：`startSearch` 无站时立即完成并给空态；原生永不完成，页面只显示 "全部" 行。
- **V12 年份角标不渲染**：`VodCard` 无该槽位（B8 V5 前例）；`B10CollectItem.year` / `yearVisible` 已进合约备用。
- **V13 网格项间距 8dp**：近似原生 4dp 边距；未逐像素复刻。
- **V14 B9 实验屏副标题改一字**："结果仍走原生结果页" → "结果页由'聚合搜索'开关控制"（准确性修正，已在作用域内的文件）。

## §6 待证明项（CI 联编 + 真机验收时逐项证明，本机未验证）

1. **Pigeon 代码生成（最担心）**：新形态——一个类内嵌套 `List<B10SourceItem>` / `List<B10CollectItem>`、
   `@async` 方法返回 `B10CollectScreen`、同步方法返回 `B10CollectScreen` / `B10Progress`——
   生成的 Java Builder / Result 签名是否与手写 `B10HostApiImpl` 字符级一致。**CI 才能证明**（`b8-c0.yml` 的 Pigeon 步骤 + `flutter analyze` + Gradle 构建）。
2. **多观察者会话生命周期（最担心）**：`observeForever` 注册、重新搜索时的 epoch 替代、看门狗完成、
   加载更多自动续页链、dispose 时观察者移除——任一环节漏掉都会导致回调丢失、重复完成或泄漏。**真机才能证明**
   （多站搜索、切源、切分组、加载更多、离开再进、快速连续搜索）。
3. **族内 push + G2 护栏真机行为**：B9 输入页 push 结果页、返回 keyword 完好；`direct()` 在 C1 调用方下落回原生；
   从结果页进详情再返回时 resumed 重取是否可接受。
4. **键盘 / IME（B5 依赖）**：B5 的 TextField 键盘行为若在真机异常，B9 文档已定 B9 在此停下等决策；B10 的输入页继承同一依赖。
5. **图片一致性**：`ImgUtil.getUrl` 委托 + TmdbProxy 回退 + `data:` 占位，与原生逐项对比（网格卡、列表行、文件夹封面）。
6. **网格几何**：`Product.getColumn` / `SearchGridLayout` 复制值与原生逐屏对比（窄屏/宽屏/平板、横竖屏）。
7. **相似度对话框内联复制**：滑杆行为、取值、持久化与原生 `SliderNumberDialog` 对比；取消路径不卡住页面。
8. **resumed 重取（V7）**：从详情返回时重新搜索是否可接受（流量与滚动位置丢失）。
9. **年份角标（V12）**：确认不渲染年份是否可接受，或后续给 `VodCard` 加槽位。
10. **零可搜站（V11）**：无站时立即空态是否可接受（原生只显示 "全部" 行）。

## Recovery anchor

- 下一步（恰好一个）：**B11 首页壳**（顶栏 + 分类 Tab + ViewPager + Dart 玻璃底栏；路由 `/home`；hk:// 站与 Web 主题回退原生首页）。
- 本批提交独立 revert；联合推送前按规划做全量 diff 复核（作用域、开关默认关、TV 零影响、无 schema 变更）。
