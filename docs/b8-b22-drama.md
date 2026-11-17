# B22 短剧剧场/历史（B8 全量迁移，方案 B）

## §0 执行声明

- 本批按 Wei 2026-11-17 16:32 定下的方案 B 实施（“一点就播，剧场/历史再进 Flutter”）：短剧入口永远先开原生 `ShortDramaActivity` 播竖流，只有剧场/历史 Tab 的点按才分流进 Flutter；Flutter 页内绝不播视频。
- 分界线与方案设计全文见 `docs/b8-short-drama-scoping.md` §1（方法级分界）与 §2.2（方案 B）；本批的唯一前置是 G2 relaunch 机制（`docs/b8-c1-relaunch-mechanism.md`），分流依赖它把复用的 C1 引擎断言到正确路由。
- 本机无 Java/Android/Flutter/Dart SDK：**构建与运行均未验证**，全部留给联合 CI 构建与 Wei 的真机验收（见 §6）。
- 开关 `flutter_b22_enabled` 默认关，独立于其他开关；开关 UI 只在实验屏；关 = 剧场/历史 Tab 回原生，原生实现一行不删。播放器代码一行不碰。

## §1 原生实现（只读发现）

### 1.1 `ShortDramaActivity`（793 行，mobile 源集）

浏览部分（本批范围）：`setMode` 的 Tab 切 View（`Mode` 枚举 FEED/THEATER/HISTORY）、`sites`/`current`/`DramaSites.*`（`detect`/`current`/`isDramaSite`）、`loadSource()`（`homeContent` → `fetchPic` → 空 + types → `categoryContent` 首分类）、`showSourceDialog()`、`GridAdapter`（3 列网格）、`HistoryAdapter` + `reloadHistory()`（`History.get()` 按短剧源 key 前缀过滤）、`showState()`、`openDetail()`（开原生 `VideoActivity`）。

播放部分（**一行不碰**）：`player`/`buildPlayer`/`PageAdapter`/`PageHolder`/`onPageChosen`/`attachAndResolve`/`resolve`/`playUrl`/`playEpisode`/`onVideoEnded`/`recordHistory`/`toggleKeep`/`showEpisodes`/`stopPlayer` 及全部生命周期内的播放逻辑。`onPause` 的 `player.pause()` 是既有行为，本批只利用它，不改它。

`setMode` 的全部调用点（grep 验证）：`initView`（FEED）、两个 Tab 之外的 `showSourceDialog` 回调（FEED）、`GridAdapter` 卡片点按（FEED）、三个 Tab 点按（FEED/THEATER/HISTORY）。因此“非 FEED 目标”只可能来自剧场/历史 Tab 点按，分流守卫不可能误触发。

返回键：任何模式都是直接 `finish()`（mobile `BaseActivity.onBackInvoked`，无逐级返回）。

### 1.2 入口与调用方（全部，grep 验证）

- 短剧页的唯一入口是 `ShortDramaActivity.start(Activity)`：调用方为 mobile `HomeActivity` 的底栏 drama 项（经 `B11HostApiImpl` 的 `case "drama"`，反射打开且**不 finish Flutter 首页**——这是 relaunch 机制要覆盖的栈底活屏情形）、`VodFragment` 的 drama 入口、`HomeWebBridge`。本批不改任何入口：入口永远开原生页。
- 剧场/历史 Tab 的切换点是唯一的：`initView` 里 `tabTheater`/`tabHistory` 的 `setOnClickListener` → `setMode`。分流落点就是 `setMode` 顶部的守卫。
- 卡片点按去向（原生）：`GridAdapter` 卡片 → `setMode(FEED)` + `pager.setCurrentItem(position, false)`，经 `onPageChosen` 播该条。历史行点按去向（原生）：`HistoryAdapter` 行 → `openDetail()` → 原生 `VideoActivity`。Flutter 页复刻这两个去向（见 §2.3）。

### 1.3 数据源

- 剧场网格：`loadSource()` 链——当前源的 `SiteApi.homeContent`（`homeContent` 里还会 `fetchPic` 补图），空结果则取 `types` 首分类走 `SiteApi.categoryContent`。当前源来自 `DramaSites.current(sites)`（`PREF_SITE = "drama_tab_site"` 偏好 + 首个可用源兜底）。
- 历史列表：`History.get()` 全部记录，按 `key` 以“短剧源 key + `AppDatabase.SYMBOL`”开头过滤；行 meta = 站点显示名 + ` · ` + remarks，时间 = `SimpleDateFormat("MM-dd HH:mm")`。
- 唯一耦合是共享 feed `List<Vod>` + Room 里的 History；播放侧数据结构本批不改。

### 1.4 布局事实（读源码核对）

- 整个短剧屏是黑底白字的沉浸式深色屏（`activity_short_drama.xml`）。
- 网格卡片（`item_drama_grid.xml`）：6dp padding、160dp 海报、名字 13sp 白色 1 行、remarks 11sp #99FFFFFF；网格固定 3 列、8dp padding。
- 历史行（`item_drama_history.xml`）：10dp padding、56×74dp 海报、名字 14sp、meta 12sp #99FFFFFF、时间 11sp #80FFFFFF。
- 海报占位底色 #1A1A1A。

### 1.5 字符串与资源归属（按目录分别 grep 验证，无 head 截断）

- 全部 13 个 `drama_*` 字符串**只在 `app/src/mobile/res`**（values + zh-rCN + zh-rTW），main 与 leanback 均无。本批 impl 用到的 4 个（`drama_theater`/`drama_history`/`drama_empty`/`drama_no_content`）一律走 `mobileString(name, fallbackResId)`（B11 同款），main-res 兜底分别为 `app_name`/`setting_home_history`/`widget_empty`/`widget_empty`；兜底实际不可达（无 leanback 入口）。
- `error_retry`/`error_play_timeout` 只在 `app/src/main/res`，直接引用安全。
- 其余字符串：本批 impl 不再引用任何其他 `R.string`。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- `Setting.isFlutterB22Enabled()` / `putFlutterB22Enabled()`，key `flutter_b22_enabled`，默认 `false`，独立于 C1/C2/B2–B21 开关。
- 开关 UI 只在实验屏（C0 引擎上注册 `B22HostApi`，只为开关用；B21 同款）。
- 分流点：`ShortDramaActivity.setMode()` 顶部守卫——目标非 FEED 且开关开 → `C1ScreenActivity.start(this, ROUTE_DRAMA_THEATER / ROUTE_DRAMA_HISTORY)`，start-only（不 finish，原生页留在返回栈里保持 FEED 模式），直接 return；开关关 → 原有 `setMode` 逻辑一字不改。
- 路由：两个独立字面路由 `/drama-theater` 与 `/drama-history`，无参数，不是 family 路由（两页互不导航，各自只有返回）。

### 2.2 分流时序 + 与 relaunch 机制的配合

时序（以 Flutter 首页底栏 drama 项进入为例，这是最复杂的栈）：

1. Flutter 首页（C1 引擎，路由 `/home`）→ 底栏 drama 项 → host 反射开原生 `ShortDramaActivity`，**不 finish 首页**（B11 既定行为，C1 引擎带着 `/home` 活在栈底）。
2. 原生页播竖流（一点就播不变）；点“剧场”Tab → `setMode` 守卫 → `C1ScreenActivity.start(ROUTE_DRAMA_THEATER)`。
3. 新的 `C1ScreenActivity` 复用缓存的 C1 引擎：relaunch 机制的路由断言经 MethodChannel 把 Dart 侧断言到 `/drama-theater`（Dart 经 `_currentTopRoute` 去重，同一路由不重置）；销毁守卫保证旧 `C1ScreenActivity` 的 `onDestroy` 不销毁仍有活 Activity 的引擎。
4. Flutter 剧场页内点卡片 → host 反射调 `jumpToFeedItem(vodId, position)` 记下静态待跳转 → host finish 当前 `C1ScreenActivity` → 返回原生页 → `onResume` 消费跳转（见 §2.3）。
5. Flutter 页内点返回（AppBar 或系统返回键）→ `C1HostApi.closeScreen()` → finish → 返回原生页，`onResume` 无待跳转，竖流保持暂停（与从详情页返回一致，用户点“推荐”恢复——原生既有语义，本批不加自动恢复）。
6. 原生页点返回 → 直接 `finish()` → 回到 Flutter 首页（`/home` 仍在，relaunch 去重不重置 family 深度）。

从原生首页进入时栈里没有 Flutter 首页，第 3 步走新引擎路径（`initialRoute`），其余相同。relaunch 机制代码本批一行不改（`C1ScreenActivity` 的路由断言 + 销毁守卫、`main.dart` 的 channel/handler）。

### 2.3 Feed 交接语义（卡片点按 → 回原生播放流）

- `ShortDramaActivity` 新增静态待跳转（`pendingJumpVodId` + `pendingJumpPosition`）与静态方法 `jumpToFeedItem(String vodId, int position)`（B22HostApiImpl 经反射调用——该 Activity 在 mobile 源集，B11 先例；ProGuard `-keep com.github.moyingshi.osc.tv.**` 已有）。
- 静态而不用实例字段：旋转导致 Activity 重建时，Flutter 屏还开着，待跳转不丢失；新实例的 `onResume` 照常消费。
- `onResume` 新增消费逻辑（本批唯一新增的生命周期方法）：先取后清（清掉后永不重复触发）；按 vodId 在 `feed` 里找下标，找不到则用记录的 position（越界则放弃）；命中后精确复刻原生网格卡片点按：`setMode(Mode.FEED)` + `pager.setCurrentItem(index, false)`，经 `onPageChosen` 播该条。vodId 优先匹配可容忍两次独立加载之间的列表漂移；无 `DramaFeedStore`（不在本批作用域，impl 独立加载）。
- 历史行点按：host 直接调 `VideoActivity.start(...)`（双 flavor 签名一致，B2 先例），Flutter 屏不 finish，留在详情页之下；从详情页返回时 resumed 重取刷新列表。
- 源切换不进 Flutter 页：留在原生页的 `btnSource` 对话框里；Flutter 页内无底栏、无源切换（AppBar + 返回）。

### 2.4 Pigeon 合约（`flutter_module/pigeons/b22_drama_messages.dart`）

`B22HostApi`：`isB22Enabled()`/`setB22Enabled(bool)`（开关）；`@async B22DramaTheaterScreen getTheaterScreen()`；`@async B22DramaHistoryScreen getHistoryScreen()`；`openFeedItem(String vodId, int position)`（fire-and-forget）；`openHistoryItem(String siteKey, String vodId, String vodName, String vodPic)`（fire-and-forget）。

数据类全部扁平：`B22DramaTheaterScreen`（title、host 决定的 stateText——无源时 `drama_empty`、空/失败时 `drama_no_content`、retryLabel、仅看门狗超时才非空的 loadErrorText、columnCount、`List<B22DramaItem>`）、`B22DramaItem`（id/name/remarks + D3 PicSpec 图片字段组）、`B22DramaHistoryScreen`（title + `List<B22DramaHistoryItem>`，无空态文案——忠于原生空白列表）、`B22DramaHistoryItem`（siteKey/vodId/vodName/vodPic + host 算好的 meta/timeText + PicSpec 字段组）。

Pigeon 教训照旧：Java 侧 bool/int/double 一律装箱（Dart `int` → Java `Long` 参数、Builder 的 int setter 取 `long` 需 `(long)` 强转），方法名与 Dart 定义字符级一致。

### 2.5 Dart 页（`flutter_module/lib/b22_drama_page.dart`）

两个页：`B22TheaterPage`（`/drama-theater`）与 `B22HistoryPage`（`/drama-history`），同文件。深色主题（黑底白字，与原生沉浸式屏一致；不套共享浅色主题）。图片走共享 `PicImage`（只加法使用，不改）；网格卡片用页内局部 widget，不用 `VodCard`（VodCard 的叠加信息区 + 站点徽标与短剧卡片不符）。

- 剧场页：`@async` 快照 + 35s 看门狗（host 侧）；resumed 重取；下拉刷新（仅剧场页，历史是本地读不加）；加载态白圈；看门狗超时态（host 文案 + 重试点按）；空态居中文字可点按重试（复刻原生 `showState`）；网格 3 列（列数 host 给）、8dp 外边距、卡片 6dp padding + 160dp 海报 + 13sp 名 + 11sp remarks；卡片点按 → `openFeedItem(id, index)`。
- 历史页：`@async` 快照 + resumed 重取；空列表渲染空白（忠于原生，无空态文案）；行布局 10dp padding + 56×74 海报 + 14sp 名 + 12sp meta + 11sp 时间；行点按 → `openHistoryItem(...)`。
- 两页均为 `PopScope(canPop: false)` → `C1HostApi.closeScreen()`（AppBar 返回按钮同路）；系统返回键语义与原生一致：Flutter 页内返回即关屏回竖流，不逐级。
- 全部展示文案由 host 快照给，Dart 不硬编码文案（通道失败的诊断文案 `e.toString()` 除外，B2 先例）。

### 2.6 Host 实现（`B22HostApiImpl.java`，main 源集）

B21 注册表形态：静态 per-messenger 注册表 + `register`/`unregister`/`rebind`/`dispose`、单 worker 线程、带 35s 看门狗的 `Completion<T>`（主线程完成回调）。`C1ScreenActivity` 与 `C0ShellActivity` 均按 B21 同款注册/重绑/注销。

- `buildTheaterScreen()` 逐步复刻 `loadSource`：`DramaSites` 逻辑按规划 §4.8 招3 在 impl 内复刻（`isDramaSite`/`detect`/`current` + `PREF_SITE` + `KEYWORDS`，带漂移警告；B11 已用同款复刻 `isDramaSite`），异常一律吞掉 → 空列表 + 无内容文案（仅看门狗超时才产错误快照）。
- `buildHistoryScreen()` 复刻 `reloadHistory` + `HistoryAdapter` 的 meta/time 计算（`SimpleDateFormat("MM-dd HH:mm")`、站点显示名查表、`siteName + " · " + remarks`）。
- `resolvePic`/`splitHeaders` 照 B2 的 D3 委托（`ImgUtil.getUrl` + `TmdbProxy.nextAutoImageUrl` 兜底 + `ColorGenerator.get400` 占位）。
- `openFeedItem` 反射 `jumpToFeedItem` 后 finish（重绑后的）Activity；`openHistoryItem` 直接调 `VideoActivity.start`。
- `Prefers` 导入为 `com.github.catvod.utils.Prefers`（main 可见，`Setting.java` 同款）。

### 2.7 文件清单

新增：`flutter_module/pigeons/b22_drama_messages.dart`、`flutter_module/lib/b22_drama_page.dart`、`app/src/main/java/com/github/moyingshi/osc/tv/flutter/B22HostApiImpl.java`、`docs/b8-b22-drama.md`（本文件）。

修改（只加法 + 分流处）：`flutter_module/lib/main.dart`（2 条字面路由 + B22 开关；relaunch channel/handler 一行不改）、`app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java`（2 个路由常量 + B22 注册/重绑/注销；relaunch 代码一行不改）、`app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java`（C0 引擎注册 B22，只为开关）、`app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java`（`flutter_b22_enabled`，默认关）、`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/activity/ShortDramaActivity.java`（2 个 import + 静态待跳转与 `jumpToFeedItem` + `setMode` 守卫 + `onResume` 消费；播放代码一行不改）、`.gitignore`（加 `B22Messages.java`）、`.github/workflows/b8-c0.yml`（Pigeon 步骤加 b22 生成 + 存在性检查，B18 同款）。

未碰：C0/C1/C2/B2–B21 全部合约与实现、relaunch 机制代码、共享组件、TV（leanback）任何文件、Room/schema、`android-release.yml`、播放器全部代码。

## §3 状态来源清单（全部 host 快照，Dart 不计算）

| 展示项 | 来源 |
|---|---|
| 剧场标题 / 空态文案 / 重试文案 / 超时文案 | `getTheaterScreen()` 快照（host 决 `drama_empty` vs `drama_no_content`） |
| 网格列数 | 快照 `columnCount`（复刻 `GridLayoutManager(this, 3)`） |
| 卡片名 / remarks / 图片 | 快照 `B22DramaItem`（图片经 D3 委托） |
| 历史标题 / 行 meta / 时间 | `getHistoryScreen()` 快照（host 算好） |
| 开关态 | `isB22Enabled()`（实验屏） |

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 一点就播（开关开/关都必须成立）

- [ ] 首页底栏 drama 项 / VodFragment drama 入口点按 → 立即开原生短剧页播竖流，无中间页、无 Flutter 闪屏。
- [ ] 竖流滑动、选集、换源对话框、详情页打开均与原生一致（播放代码未动）。
- [ ] 开关关时：剧场/历史 Tab 行为与改动前逐项一致（原生网格/历史、原生空态、原生卡片点按回流）。

### 4.2 剧场/历史 Tab 分流（开关开）

- [ ] 点“剧场”Tab → Flutter 深色网格页（3 列、卡片 160dp 海报 + 名 + remarks），标题与原生一致；竖流暂停（听不到声音）。
- [ ] 点“历史”Tab → Flutter 深色历史列表（行布局与原生一致）；空历史时列表空白（无空态文案，与原生一致）。
- [ ] 无短剧源时剧场页显示 `drama_empty` 文案；有源但空/失败时显示 `drama_no_content` 文案；点按空态文案可重试。
- [ ] 网络极慢时 35s 后显示超时文案 + 重试按钮；重试可恢复。
- [ ] 从 Flutter 首页底栏进入（栈底有 `/home`）与从原生首页进入，分流后显示的都是剧场/历史页（不是 `/home` 闪回或错路由）；返回后首页状态不重置（relaunch 去重）。
- [ ] 旋转：Flutter 剧场/历史页内旋转不丢列表、不错路由；旋转后卡片点按仍能回流。

### 4.3 卡片点按去向

- [ ] 剧场卡片点按 → 回到原生页并播所点那条（vodId 匹配；列表漂移时不播错条）；点正在播的那条 → 回到该条继续播。
- [ ] 历史行点按 → 开原生详情页（`VideoActivity`）；从详情页返回 → Flutter 历史页（resumed 重取，刚看过的在列）；再返回 → 原生竖流（暂停态，点“推荐”恢复）。
- [ ] Flutter 页内点 AppBar 返回 / 系统返回键 → 关屏回原生竖流（不逐级、不 finish 原生页）。

### 4.4 返回键与开关回滚

- [ ] 原生页内任何模式点返回 → 直接 finish 回首页（与改动前一致）。
- [ ] 实验屏关掉 B22 开关 → 剧场/历史 Tab 立即回原生，无需重装；其余批次开关不受影响。
- [ ] 全部开关关 = 全原生，零行为变化。

## §5 相对规划与评估文档的全部偏差

- V1（相对评估文档方案 B 的设计细化）：评估文档的方案 B 只定了“Tab 分流 + 卡片回原生流”的方向；本批把回流机制定为“静态待跳转 + `onResume` 消费”，并把 `jumpToFeedItem` 经反射调用（`ShortDramaActivity` 在 mobile 源集，main 源集的 impl 不可见，B11 先例）。待跳转静态化是为覆盖旋转重建。
- V2（相对评估文档）：评估文档未定 Flutter 页内有无源切换/底栏；本批定为无（源切换留原生 `btnSource` 对话框，底栏留原生页之下），Flutter 页只有 AppBar + 返回。
- V3（相对评估文档）：评估文档未定无自动恢复；本批定为从 Flutter 页返回后竖流不自动恢复（与从详情页返回一致，用户点“推荐”恢复），不利用 `setMode(FEED)` 的自动 `play()` 之外的任何新恢复逻辑——`onResume` 消费跳转时走的正是原生卡片点按的同一路径。
- V4（相对 B2–B21 的页面形态）：本批两页用深色主题（黑底白字），不套共享浅色主题——原生短剧屏本身就是深色沉浸式屏，浅色主题会错。
- V5（相对共享组件惯例）：网格卡片不用 `VodCard`（页内局部 widget），因 VodCard 的叠加信息区 + 站点徽标与短剧卡片（名 + remarks 在海报下方）不符；图片仍走共享 `PicImage`。
- V6（相对规划 §4.6 的 resumed 重取）：剧场页 resumed 重取时若用户刚在原生页换了源，新快照会换源——原生 `loadSource` 只在源切换时触发，Flutter 页的重取略勤，但与“返回即最新”一致，可接受。
- V7（相对原生 `showState`）：原生空态文案的 `retry` 参数实际未用（`showState` 只设文字与可见性）；本批空态文案可点按重试（与 B8/B10 的空态重试惯例一致），是行为小增强而非复刻缺失。
- V8（相对评估文档的 `DramaFeedStore` 设想）：未建共享 feed store（不在作用域）；impl 独立加载，vodId 优先匹配容忍漂移，漂移过大时 position 兜底、越界则放弃跳转（留在竖流当前页）。
- V9（相对 B21 的注册表形态）：`B22HostApiImpl` 无文件选择/后台长任务，`dispose` 只关 worker 与看门狗；旋转重绑的唯一作用是保证 `openFeedItem` 的 finish 作用于活 Activity。
- V10（相对规划 §4.4 的 family 路由惯例）：两页用两个独立字面路由而非 family 路由——两页互不导航，各自只有返回；family Navigator 的深度语义无用且会在 relaunch 去重里多一层状态。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon 代码生成匹配（CI 唯一可证）**：`B22HostApi` 含 `List<B22DramaItem>` / `List<B22DramaHistoryItem>` 嵌套字段 + `@async` 快照 + fire-and-forget 混用；Java 侧方法名、装箱类型、Builder setter 形态须与生成代码字符级一致（C1 run #8–#10 的教训）。
2. **Relaunch 配合（设备唯一可证）**：Flutter 首页 → 原生短剧页 → Flutter 剧场页 → 返回 → 首页，这条栈上路由断言是否无闪回/不错路由、family 深度是否不重置；以及旋转后重绑的 impl 是否仍 finish 正确的 Activity。
3. **待跳转语义（设备）**：卡片点按后 `onResume` 是否精确播所点那条；列表漂移、空 feed、position 越界时是否不播错条、不崩溃；旋转后待跳转是否仍消费一次且仅一次。
4. **反射存活（设备，R8/minify 下）**：`jumpToFeedItem` 的反射调用（类名 + 方法名 + `(String, int)` 签名）在 release 构建下是否存活；`DramaSites` 复刻逻辑与原生 `PREF_SITE`/`KEYWORDS` 是否长期一致（漂移警告已写在 impl 内）。
5. **图片一致性（设备）**：`ImgUtil.getUrl` 委托 + `TmdbProxy` 兜底在短剧源图片上的表现（`@` 后缀头、Douban 图、首字占位）；`Image.network` 无磁盘缓存，滑回顶部是否闪图（与 C2/B2 同款待证明项）。
6. **看门狗时序（设备）**：`homeContent`/`categoryContent` 慢请求下 35s 看门狗是否先给错误态、重试是否命中温缓存；`fetchPic` 的串行补图是否拖慢首屏（原生同款行为，对照即可）。
7. **Leanback 构建（CI）**：`mobileString()` 的 4 个 drama 字符串 + 兜底是否让 leanback flavor 编译通过（B2/C2 的 leanback 教训）；`ShortDramaActivity` 的改动只在 mobile 源集，leanback 不可见。
8. **键盘/IME 不适用**：本批无文本输入面，无此待证明项。

## Recovery anchor

- 目标：B22 短剧剧场/历史 Tab 按方案 B 迁到 Flutter，一点就播与播放器留原生；全部批次至此已齐（B1–B21 + B22 + relaunch 机制）。
- 状态：本地提交待护栏 finish；未推送（Wei 10:44 联合推送指令仍有效）。
- 风险：见 §6（Pigeon 代码生成、relaunch 配合、待跳转语义为前三）。
- 下一步（恰好一个）：联合推送 + 构建——父代理做全量 diff 复核（作用域、开关默认关、TV 零影响、无 schema 变更、无未用 import/analyze 警告）后，经浏览器路由把累积的本地提交推到 `b8-flutter-rewrite` 并跑 `b8-c0.yml`，失败按文件路径归因到批次、正向修复。
