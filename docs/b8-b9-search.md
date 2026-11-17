# B8 B9：搜索输入页迁移到 Flutter

## §0 执行声明

- 任务：B8 全量迁移第 9 批，把手机端搜索输入页（SearchActivity + SearchFragment）迁移到 Flutter；搜索结果暂走原生直出模式（host 打开原生结果页），聚合搜索是 B10 的事。
- 分支：`b8-flutter-rewrite`，本地提交，未推送（按 Wei 2026-11-17 10:44 指示：各批做完先不推，等全部做完一起推一起编）。
- 本机无 Java / Android / Flutter / Dart SDK，构建与运行均未验证；Pigeon 生成、flutter analyze、Gradle 编译、四包构建、设备对照全部留给联合 CI 构建 + Wei 真机验收。
- 原生实现一行不删；开关默认关；电视端不动；不碰 Room/schema；不碰播放器；不改 `android-release.yml`；不引入新 Dart 依赖。

## §1 原生实现（只读发现）

### 1.1 两个原生文件（mobile 源集）

- `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/activity/SearchActivity.java`（132 行）：`start()` 各重载汇入 5 参 `start(activity, keyword, siteKey, pic, wallPic)`；`direct()` 各重载在同一 5 参上加 `direct=true`；`directFromHistory()` 另加历史续看参数。`initView`：direct → `CollectFragment.newInstance(keyword, siteKey, "", pic, wallPic, hrCid, hrKey, hrTargetCid)`；否则 → `SearchFragment.newInstance(keyword, siteKey, pic, wallPic)`。`onBackInvoked`：有 fragment 回退栈则弹栈，否则走系统返回。
- `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SearchFragment.java`（479 行）：输入框（TextInputEditText，hint `search_keyword`，IME 搜索动作，maxLength 255，单行）；搜索记录（Flexbox 列表，`Setting.getKeyword()` JSON，最新在前、去重、上限 10，点按填入并搜索，长按直接删除无确认框，提交时 250ms 延迟落盘）；热词（三组 TV / 电影 / 综艺，每组上限 10，先显示 `Setting.getHotTv/Movie/Variety` 缓存，再并行请求 360kan rank 接口 cat 3/2/4，Referer `https://www.360kan.com/rank/general`，成功后存回 Setting）；联想（`SearchSuggest`：爱奇艺 + 腾讯两路并行，各自到达即显示，合并去重上限 20，清除 `</hl>` 标签、压缩空白、繁转简，`mSuggestSeq` 丢弃过期结果）；范围弹窗（全部 / 当前站源 / 分组，当前站源要求首页站源可搜索，否则 toast `detail_site_not_searchable`）；站源按钮（`SiteDialog.create().search().show(this)`）；提交（`collect()`：CollectFragment 盖在 SearchFragment 之上并入回退栈）；带关键词打开时 `initView` 自动提交；空关键词时弹键盘；CollectFragment 的 "result" edit=true 回调 → 弹键盘。

### 1.2 入口（只改分流处）

`SearchActivity.start` 5 参漏斗是唯一分流点。全部 `start()` 调用方（首页搜索栏 `VodFragment:625/631`、全局搜索意图 `HomeActivity:181`、服务端搜索事件 `HomeActivity:448`、播放页 `VideoActivity:4293`、分类页长按 `TypeFragment:224/237`、收藏无配置回退 `KeepActivity:180`、小组件浏览 `WidgetBrowseActivity:141`、`HomeWebBridge:434`、`WebHomeThemeBridge:443`，以及 B2/B8 的 host 调用）都汇入该漏斗。`direct()` / `directFromHistory()` 不分流（它们直接开结果页，是 B10 的范围）。TV 的 SearchActivity 各有实现，本批不动。

### 1.3 数据来源（全部 main 源集可见，见 §3）

记录与热词缓存：`Setting`（keyword / hot_tv / hot_movie / hot_variety JSON）；范围：`VodConfig.get().getSites()` / `getHome()`、`Site.getGroups()`、`SiteGroupOrderStore.sort()`；联想：`SearchSuggest`（iqiyiUrl / tencentUrl / parseIqiyi / parseTencent / merge）；热词 JSON 解析：`Word.objectFrom()`；网络：`com.github.catvod.net.OkHttp` + `impl.Callback`；记录 JSON 编解码：`App.gson()`。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- 开关 `flutter_b9_enabled`（`Setting.java`，默认 false，独立于其他开关；开关 UI 只在实验屏）。关 = 搜索输入页回原生。
- 分流：`SearchActivity.start` 5 参漏斗内，开关开时把参数 URL 编码进路由 `/search?keyword=&siteKey=&pic=&wallPic=`，经 `C1ScreenActivity.start()` 打开；编码或启动失败时落回原生（B8 FolderActivity 同款 try/catch）。`direct()` / `directFromHistory()` 不动。
- 路由：`/search` 走 `onGenerateRoute`（B8 V1/V2 同款：参数无界，用查询串；`/settings?member=search` 是另一条字面量路由，不受影响）。单 C1 引擎 + `C1ScreenActivity` 承载（规划 §4.4）；返回走 `C1HostApi.closeScreen()`（C2/B2 同款）。

### 2.2 Pigeon 合约（`flutter_module/pigeons/b9_search_messages.dart`，176 行）

`B9HostApi`：`isB9Enabled` / `setB9Enabled`；`getSearchScreen(keyword, siteKey, pic, wallPic)`（同步快照：全部文案 + 缓存热词 + 记录 + 初始范围状态）；`refreshHotWords()`（@async → `B9HotWords`）；`getSuggest(keyword)`（@async → `B9SuggestResult`）；`getRecords()`（同步）；`deleteRecord(record)`（同步）；`openSiteDialog()`（@async → bool，关闭后返回当前站源是否可用）；`submitSearch(keyword, currentSite, siteKey, pic, wallPic)`（同步）。数据类全部扁平：`B9SearchScreen` / `B9HotWords` / `B9SuggestResult`。Java 侧 bool 一律装箱；方法名与 Dart 定义字符级一致。

### 2.3 Dart 页（`flutter_module/lib/b9_search_page.dart`，466 行）

- TextField 持有输入态（规划 §4.5；B5 `SettingSearchView` 形态模板）：AppBar 内嵌输入框，autofocus 仅当入口关键词为空（原生 `checkKeyword` 的行为），IME 搜索动作，maxLength 255，计数器隐藏；`onChanged` 300ms 防抖；空查询显示热词区（并刷新热词），非空查询调联想并用自增序号丢弃过期结果（原生 `mSuggestSeq` 同款）。
- 初始快照先显示缓存热词与记录，首帧后刷新一次热词；带关键词打开时首帧后自动提交一次（原生 `initView → search()` 同款），同时在后台拉一次联想以备返回时显示。
- 记录区（有记录才显示）：14sp 圆角 chips，点按填入并提交，长按直接删除（无确认框，原生同款），提交后本地先按去重/最新在前/上限 10 更新，返回前台时再向 host 重读（提交的落盘是 250ms 延迟的）。
- 热词区：标题 + TV / 电影 / 综艺三组，chips 13sp；联想区：标题 + 14sp chips；点按均填入并提交。
- 范围：AppBar 上的当前范围文案按钮（原生范围菜单项标题同款），弹窗只有全部 / 当前站源两项（见 V5）；当前站源不可用时点按显示 host 返回的提示文案（SnackBar，见 V6）。站源按钮：先收键盘，再调 host 开原生站源弹窗。
- 返回：AppBar 返回键与系统返回都走 `closeScreen()`（`PopScope` 拦截，C2/B2 同款）。

### 2.4 Host 实现（`B9HostApiImpl.java`，444 行）

- 快照：全部文案取自 main 资源（逐目录核查，见 §2.5）；初始范围状态照抄原生 `initView` 算式（有 siteKey 入口，或“默认当前站源”偏好开且首页站源可搜索）；记录与缓存热词照抄 `RecordAdapter` / `SearchFragment.hotItems()` 的解析与上限。
- 热词刷新：三路 360kan 并行（同 URL、同 Referer、同分组存回），每组失败时保留缓存值；三路到齐或 8s 超时后在主线程恰好完成一次（B2 Handler + completed 护栏同款）。
- 联想：爱奇艺 + 腾讯并行，`SearchSuggest.parse*` 解析后 `merge` 合并去重（上限 20）；两路到齐或 8s 超时后在主线程恰好完成一次。
- 记录删除：直接从 JSON 删（无确认框）；提交：250ms 延迟落盘（`App.post`，原生同款）+ 按 `getSearchSiteKey()` 解析站源键 + 调 `SearchActivity.direct()` 开原生结果页（结果页仍是原生，B10 再做 Flutter 列表）。
- 站源弹窗：反射开 mobile-only 的 `SiteDialog`（B7 无头 fragment 模式），关闭监听用 B7 `watchDialogDismiss` 同款的 FragmentLifecycleCallbacks；关闭后返回当前站源是否可用。无头 fragment 刻意不实现任何 listener 接口：`SiteDialog.show()` 只在父 fragment 是 `SiteListener` 时才设 listener，而原生搜索页（SearchFragment）不是——站源点按只有关闭作用，与原生一致。
- 全部 @async 方法在所有路径上完成（含超时与反射失败）。

### 2.5 mobile-only 类与资源（规划 §4.8）

- `SiteDialog`：mobile-only → 反射 + 无头 fragment（B7 同款），无 listener（见 §2.4）。
- `SearchActivity.direct(activity, String, String, String, String)`：mobile 与 leanback 两源集均有完全相同的 5 参重载（已核对），可从 main 源集直接引用（B2 同款论证）；3 参 `direct` 重载 leanback 没有，本批不用。
- 资源核查（按目录分别 grep，未用 head 截断）：`search_keyword` / `search_record` / `search_hot` / `search_suggest` / `search_scope_all` / `search_scope_current` / `search_scope_group_hint` / `search_hot_tv` / `search_hot_movie` / `search_hot_variety` / `detail_site_not_searchable` 全部只在 main res，`R.string` 直引对 leanback 安全。`dialog_delete_record` 只在 mobile + leanback，不在 main——本批不需要（记录删除原生就没有确认框）。

### 2.6 文件清单

| 文件 | 变化 |
|---|---|
| `flutter_module/pigeons/b9_search_messages.dart` | 新，176 行 |
| `flutter_module/lib/b9_search_page.dart` | 新，466 行 |
| `flutter_module/lib/main.dart` | 改：`b9_search_page.dart` 与 `b9_search_messages.g.dart` 导入；`onGenerateRoute` 加 `/search` 分支；实验屏加 B9 开关（`_b9Api` / `_b9Enabled` / `_loadB9Flag` / `_setB9Flag` / SwitchListTile）。已有路由不动 |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B9HostApiImpl.java` | 新，444 行 |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java` | 改：加 `ROUTE_SEARCH` 常量；`ensureFlutterEngine` 注册 `B9HostApi`；类注释补一行 |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java` | 改：C0 引擎注册 `B9HostApi`（只为实验屏开关用） |
| `app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java` | 改：加 `isFlutterB9Enabled` / `putFlutterB9Enabled`（`flutter_b9_enabled`，默认 false） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/activity/SearchActivity.java` | 改：只改 `start` 5 参漏斗（分流 + 3 个导入）；`direct()` / `directFromHistory()` / `initView` / 其余不动 |
| `.gitignore` | 改：加 `B9Messages.java` |
| `.github/workflows/b8-c0.yml` | 改：Pigeon 步骤加 `b9_search_messages.dart` 生成 + Dart/Java 存在性检查（B8 同款） |
| `docs/b8-b9-search.md` | 新，本文档 |

## §3 状态来源清单（全部 host 快照，Dart 不计算）

| 展示/行为 | 来源 |
|---|---|
| 输入框 hint | `R.string.search_keyword` |
| 记录区标题 | `R.string.search_record` |
| 热词区标题 | `R.string.search_hot` |
| 联想区标题 | `R.string.search_suggest` |
| 范围：全部 / 当前站源 | `R.string.search_scope_all` / `R.string.search_scope_current` |
| 当前站源不可用提示 | `R.string.detail_site_not_searchable` |
| 热词组标题 | `R.string.search_hot_tv` / `search_hot_movie` / `search_hot_variety` |
| 初始范围（当前站源与否） | host：入口 siteKey 非空，或偏好开且首页站源可搜索 |
| 当前站源是否可用 | host：`VodConfig.get().getHome()` 非空且 `isEmpty()==false` 且 `isSearchable()` |
| 记录列表 | host：`Setting.getKeyword()` JSON 解析（最新在前，上限 10） |
| 缓存热词（三组） | host：`Setting.getHotTv/Movie/Variety` 经 `Word.objectFrom` 解析（上限 10） |
| 新鲜热词（三组） | host：360kan rank 接口 cat 3/2/4，失败留缓存，成功存回 Setting |
| 联想词 | host：`SearchSuggest` 爱奇艺 + 腾讯并行，`merge` 合并（上限 20） |
| 提交后打开的结果页 | host：`SearchActivity.direct()`（原生直出模式） |
| 提交后的记录落盘 | host：250ms 延迟，`RecordAdapter.checkToAdd` 规则 |

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 输入

- [ ] 打开搜索页（开关开）：输入框有 hint、无预填关键词时自动弹键盘；有关键词进入时不弹键盘并自动提交一次打开结果页。
- [ ] 输入时 300ms 后联想更新；清空输入后回到热词区并刷新热词。
- [ ] 键盘搜索键提交；空关键词提交无反应（见 V7）。
- [ ] 输入框最长 255 字，无计数器显示。

### 4.2 热词

- [ ] 空查询时显示三组热词（TV / 电影 / 综艺），先出缓存，稍后刷新为最新；一组失败时该组保留缓存。
- [ ] 点热词：填入输入框并提交，打开结果页。

### 4.3 记录

- [ ] 有搜索记录时显示记录区（最新在前，最多 10 条）；点记录填入并提交。
- [ ] 长按记录直接删除（无确认框）；返回结果页后记录区含本次提交的关键词。
- [ ] 无记录时不显示记录区。

### 4.4 联想

- [ ] 输入关键词后出现联想词（爱奇艺 + 腾讯合并去重，最多 20 条）；点联想填入并提交。
- [ ] 快速连续输入时只显示最后一次输入的联想结果。

### 4.5 提交

- [ ] 提交后打开原生结果页（直出模式）；结果页返回后回到 Flutter 输入页，输入框内容与范围选择保持。
- [ ] 范围选“当前站源”时，结果只搜当前站源；选“全部”时全站搜索。
- [ ] 带关键词进入（分类页长按、收藏回退等）时自动打开结果页。

### 4.6 范围与站源

- [ ] 范围按钮显示当前范围；弹窗有全部 / 当前站源两项（分组暂不提供，见 V5）。
- [ ] 首页站源不可搜索时，点“当前站源”显示提示，不切换范围。
- [ ] 站源按钮打开原生站源弹窗；弹窗内改某站源的可搜索开关后关闭，范围状态随之更新；点站源本身只关闭弹窗（与原生一致）。

### 4.7 返回

- [ ] 输入页返回键与系统返回都关闭本屏，回到上一屏；结果页返回回到输入页（不是直接退出）。

### 4.8 开关与回滚

- [ ] 开关关时所有搜索入口回到原生输入页；开关开时全部入口走 Flutter 输入页；结果页始终是原生（本批）。

## §5 回滚

- 开关级：实验屏关掉 B9 开关 → 搜索输入页回到原生；全部开关关 = 全原生，零行为变化。
- 批次级：联合推送前 revert 本批提交（共享文件均为加法行，可独立 revert）。
- 分支级：丢弃 `b8-flutter-rewrite` 分支；main 不受影响。
- 无数据层改动，无需数据回滚。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon 生成**：新形态——`List<String>` 字段（`B9SearchScreen.records/hotTv/...`、`B9HotWords`、`B9SuggestResult.words`）、`@async` 带返回值（`refreshHotWords` / `getSuggest` / `openSiteDialog` 返回 bool）、`@async` 返回 `List<String>` 以外的类——能否一次生成且 Java 侧 Builder / Result 签名与手写实现一致。CI 的 Pigeon 步骤与 Gradle 编译是唯一证明。
2. **flutter analyze**：新增的 Dart 文件与 main.dart 改动有无警告（C1 run #8 证明一个警告即失败 CI）。尤其注意：`b9_search_page.dart` 的全部导入是否都被引用；`_showHot` / `_suggestSeq` 等状态是否被判定未使用。
3. **键盘 / 输入法行为**：TextField 在嵌入式 FlutterFragment 中是否正常弹键盘、切换输入法、收键盘（规划 R4）。本批沿用 B5 的形态（AppBar 内 TextField + autofocus + search 动作），但 B5 的键盘行为本身也待联合验收。**显式标注：若 B5 联合验收时键盘行为异常，B9 停下来等决策（规划 §4.5）——本批实施不停，只记录。**
4. **联想时序**：两路并行一次合并返回（非原生逐路显示），在弱网下是否感觉更慢；8s 超时是否合理；序号护栏是否正确丢弃过期结果。
5. **热词刷新**：三路 360kan 并行 + 超时 + 缓存回退是否如设计工作；`Setting::putHotTv` 等方法引用在 CI 编译是否通过。
6. **站源弹窗**：反射打开 `SiteDialog` 是否成功；关闭监听是否恰好完成一次 @async 调用；无头 fragment 在旋转 / 进程死亡后恢复时是否仍能打开弹窗。
7. **自动提交**：带关键词进入时首帧后自动提交是否稳定触发；从结果页返回后不重复提交。
8. **G2 引擎复用竞态**：与 B7 V10 同一已知风险——若 C1 引擎已由另一路由的屏持有，再开搜索页可能显示旧路由。搜索入口与设置屏正常情况下不会叠加，本批不新增重载机制；联合验收时若复现，按 B7 的方案在 `C1ScreenActivity` 补重载机制。
9. **chips 与标题的视觉对照**：14sp/13sp、圆角、间距、18sp/16sp 标题均为按原生布局文件的近似还原（未逐像素量取），以 Wei 的截图对照为准。

## §7 相对规划的全部偏差

- **V1 联想一次合并返回**：原生两路联想各自到达即显示；B9 的 @async 只能完成一次，host 等两路到齐（或 8s 超时）后一次返回合并结果。弱网下首词出现可能比原生慢；B10 可评估是否改为分两次调用。
- **V2 联想空查询不调用**：原生清空输入时走 `getHot()`；B9 同样在清空时刷新热词，但不调 `getSuggest("")`（host 对空查询直接返回空列表，Dart 侧也不发起）。
- **V3 空热词组隐藏**：原生空热词组仍显示组标题；B9 的空组整个隐藏（无信息的空标题不渲染）。
- **V4 范围弹窗形态**：原生用自定义 PopupWindow 锚在工具栏范围按钮上；B9 用系统 PopupMenuButton（菜单样式不同，功能与选项相同）。
- **V5 分组范围暂不提供**：原生范围弹窗有全部 / 当前站源 / 分组；B9 只提供全部 / 当前站源。原因：结果走原生 `direct()` 模式，`SearchActivity` 的 direct 分支把 group 写死为 `""`，分组信息传不进去；本批任务约束只改分流处，不动 `initView`。B10 落地 Flutter 结果页时恢复分组范围。
- **V6 不可用提示用 SnackBar**：原生当前站源不可用时用系统 toast（`Notify.show`）；Flutter 侧无 toast 通道，改为 SnackBar 显示同一文案。
- **V7 空关键词提交无反应**：原生空关键词提交仍会打开一个空结果页；B9 的提交在空查询时直接返回（输入页已弹键盘，空结果页无信息量）。
- **V8 CollectFragment 的 "result" edit 回调丢失**：原生结果页的“编辑”按钮会发 fragment result 让输入页弹键盘；B9 的结果页是独立原生 Activity，输入页不在同一 FragmentManager 内，该回调无接收方。B10 落地结果页时恢复。
- **V9 旋转 / 进程死亡**：路由参数由 Intent 保存，C1 引擎恢复机制沿用 C0/C1（`ensureFlutterEngine` 在 `super.onCreate()` 之前）；输入框文字与范围选择在进程死亡后不恢复（与 C2 的 family Navigator 深度丢失同类接受项，规划 §4.4）。

## Recovery anchor

- 目标：B8 全量迁移 B9——手机端搜索输入页迁移到 Flutter，结果暂走原生直出模式；开关默认关；只改 `SearchActivity.start` 分流处；不推送。
- 状态：已完成，本地未提交。分支 `b8-flutter-rewrite`，HEAD `1ba0675f`（B8 提交），main `f39a6e0` 不动。
- 文件：新 `flutter_module/pigeons/b9_search_messages.dart`、`flutter_module/lib/b9_search_page.dart`、`app/src/main/java/com/github/moyingshi/osc/tv/flutter/B9HostApiImpl.java`、本文档；改 `flutter_module/lib/main.dart`、两 flutter Activity、Setting、mobile `SearchActivity`（只改分流处）、`.gitignore`、`.github/workflows/b8-c0.yml`。
- 验证：`git diff --check` 干净；作用域 diff 已逐项复核，共享文件只含加法行；新文件括号嵌套校验通过；main 仍为 `f39a6e0`；构建与运行未验证（无 SDK），留给联合 CI + 设备验收；§6 第 3 项含 B5 键盘依赖标注。
- 未决风险：§6 第 1/2 项（Pigeon 生成与 analyze，联合 CI 证明）、第 3 项（键盘行为，B5 联合验收决定 B9 是否继续）。
- 下一步（恰好一个）：B10 聚合搜索页迁移。
