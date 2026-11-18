# B8 #155 闪退修复：singleTop start-then-finish + 冷启动空首页

任务：`b8-crash-singletop`（task_guard，mode standard）。分支 `b8-flutter-rewrite`，
本地提交，不 push。触发：#155（`0ce4a5c7`）真机视频——点底栏设置后约 0.5 秒回
桌面，无崩溃弹窗；冷启动首页一直显示“暂没有内容”。

## 根因 A：底栏导航闪退（singleTop + start-then-finish）

`C1ScreenActivity` 在 mobile manifest 里是 `singleTop`（delete-native phase 3
改的）。点底栏设置/实验室/追更时，导航走 `FlutterNav.openHomeAt/goHome` 或
`B11HostApiImpl` 里 `C1ScreenActivity.start + activity.finish()` 的
start-then-finish 老做法：singleTop 下 `startActivity` 不建新实例，只给当前
实例发 `onNewIntent`，随后 `activity.finish()` 把唯一实例关掉，任务栈空了就回
桌面。`onNewIntent` 本身已正确更新 `mRoute` 并 `assertRouteIfNeeded`，但
Activity 随即被 finish，断言作废。

### assertRouteIfNeeded 复核（修前已确认，无需改动）

`assertRouteIfNeeded()` 在 `!mRoute.equals(sAssertedRoute) ||
sRouteOwnerToken != mRouteToken` 时下发。同一实例经 `onNewIntent` 换路由时，
`mRoute` 已先更新、与 `sAssertedRoute` 不同，断言一定下发；ownership token
只拦“同一实例 + 同一路由”的重复断言（plain resume 场景），不拦换路由。
`onPostResume` 还会再断言一次兜底。Dart 侧只忽略同一路由的重复断言（见
`C1ScreenActivity` 类注释与 `docs/b8-c1-relaunch-mechanism.md`）。结论：只
要不再 finish 自身，切屏就能下发到 Dart。

### 改动点 A（10 个导航点）

规则：调用者已经是 `C1ScreenActivity` 时，只 `startActivity`、不 finish（由
`onNewIntent` + 路由断言完成切屏）；调用者不是 `C1ScreenActivity` 时保持现有
行为一字不改。manifest（singleTop 保留）、Dart、Pigeon 均未改动。

`FlutterNav.java`（7 处）：

| 方法 | 改法 | 行号（改后） |
|---|---|---|
| `openHomeAt` | 无条件 finish → 仅非 C1 调用者 finish | 164 |
| `goHome` | 同上 | 187 |
| `direct` | 删除 `if (activity instanceof C1ScreenActivity) activity.finish();`（非 C1 调用者本来就不 finish） | 原 269 行已删 |
| `directFromHistory` | 同上 | 原 313 行已删 |
| `openHistory` | 同上 | 原 395 行已删 |
| `openKeep` | 同上 | 原 416 行已删 |
| `openLocalReader` | 无条件 finish → 仅非 C1 调用者 finish | 436 |

类级 "Finish rules" 注释与各方法注释同步改写，不再称 start-then-finish。

`openHomeAt/goHome/openLocalReader` 用条件式而非删除，是因为存在非 C1
调用者：`LiveActivity.setNavigation`（`app/src/mobile/.../LiveActivity.java:432`）
调 `openHomeAt(this, …)`，`this` 是原生 LiveActivity，finish 语义必须保留
（该调用者自己还另调一次 `finish()`，本次未动）。

`B11HostApiImpl.java`（3 处，`activity` 字段恒为所绑定的 C1 实例，无非 C1
调用路径，直接删除 finish）：

| 位置 | 目标路由 | 行号（改后） |
|---|---|---|
| `openBottomBarItem` `"following"` 分支 | `ROUTE_FOLLOWING` | 1292（start 句） |
| `openBottomBarItem` `"local_source"` 分支 | `ROUTE_LAB` | 1306（start 句） |
| `openTabCategory` | `ROUTE_CATEGORY` | 1380（start 句） |

`"setting"` 分支走 `openHomeAt(1)` → `FlutterNav.openHomeAt`，由上表覆盖；
`openHistory`/`openKeep` 走 `FlutterNav`，同样覆盖。

## 根因 B：冷启动首页一直“暂没有内容”

根因 A 的退出会跑 phase 3 的 `onDestroy` teardown：`VodConfig.clear` +
`sConfigLoadStarted` 复位。重进后配置异步重拉，B11 `getHomeShell` 的快照读
`VodConfig.getHome()`，未加载时返回空 Site，`SiteApi.homeContent` 立刻返回空
Result → `empty=true` → Dart 空态；且配置加载完成时 `getCallback` 只处理
intent/toast，phase 3 删了 StateEvent 下发，无任何信号通知 Dart 重取，Dart
只在 init/resume/手动时 `_load`。于是每次启动都空，除非手动点重新加载。

### 改动点 B（只改 host，不动 Dart/Pigeon）

`C1ScreenActivity.java` 新增最小静态 hook（`sConfigLoadDone` +
`sConfigLoadWaiters`，字段 239/249 行）：

- `isConfigLoadPending()`（404 行）：`sConfigLoadStarted && !sConfigLoadDone`。
- `runAfterConfigLoad(Runnable)`（418 行）：已完成则立即跑、进行中则排队。
  注册与完成共用 waiter monitor，无 lost-wakeup；回调可能不在主线程触发
  （`VodConfig.finishSuccess/finishFailure` 直接调 callback），调用方需自行
  回主线程，见 B11 侧的 `handler.post`。
- `markConfigLoadDone()`（433 行）：幂等（`compareAndSet`），只第一次 drain。

完成标记点（共 5 处）：`getCallback` 的 success（493 行）/noConfig（499 行）/
error（505 行）三个分支（均先标记、再原有逻辑）；`loadConfig` 的
`CrashRestartMode.consume()` 分支（462 行，该分支不加载，B11 不能空等 35 秒）；
D3 已加载分支（480 行，见偏差 D1）。

复位点（1 处）：`onDestroy` teardown 内，与 `sConfigLoadStarted.set(false)`
并列（829–832 行）：复位 `sConfigLoadDone` 并清空 waiter 队列（属于垂死
engine 的 waiter 永不触发）。

`B11HostApiImpl.getHomeShell`（206 行）：`homeContent` 发起前，若
`VodConfig.get().getSites().isEmpty()`（D3 同款谓词，`isLoaded()` 是
protected）且 `isConfigLoadPending()`，则经 hook 等待该次加载完成（成功/
noConfig/error 都算完成）；否则立即进行。等待仍受原有 35 秒
`LOAD_TIMEOUT_MS` 看门狗约束（看门狗在等待前已 `postDelayed`）；迟到的
runnable 经 `startLoad` 内的 `completed[0]` 检查 no-op。已加载、无加载、
noConfig 合法空态时一律立即进行，不拖慢。`viewModel.init()` 仍在等待前执行
（只重置 LiveData，不读配置）。`getTabVideos` 未动（shell 等完后配置已就绪）。

## 偏差

- D1（D3 分支补标记）：`loadConfig` 原先把“guard 已被抢占”和“D3 已加载”并
  在一个 `if` 里 return。拆成两个 `if` 后，D3 分支（compareAndSet 成功但两
  配置已有数据、无加载运行）也调 `markConfigLoadDone()`。否则该路径下
  started=true、done=false 且永无回调，后续一次 `VodConfig.clear` 后的
  `getHomeShell` 会空等满 35 秒。加载决策本身的控制流与原先逐字等价。
- D2（B11 waiter 回主线程）：`runAfterConfigLoad(() -> handler.post(startLoad))`。
  VodConfig 回调不保证主线程，而 observer 注册与 `homeContent()` 须在主线程
  （与原有立即路径一致）。立即路径仍同步跑 `startLoad`，无额外延迟。
- D3（计数差异）：任务简报称 FlutterNav 有 5 处 `instanceof` finish 点；实测
  只有 4 处（`direct`/`directFromHistory`/`openHistory`/`openKeep`），另 3 处
  是无条件 finish（`openHomeAt`/`goHome`/`openLocalReader`）。7 处已全改。
  第 5 处 `instanceof` 在 `AppBrandingActivity`（见下表，已列为未改点）。

## 审计中发现但本次没改的点

全仓库 grep `C1ScreenActivity.start` + `finish()` 审计结果。以下同类
start-then-finish 点在 scope 外，本次未改，需后续任务处理（症状与根因 A
相同：从 Flutter 屏点这些入口会回桌面）：

| 文件：行号 | 方法 / 入口 | 原因 |
|---|---|---|
| `flutter/C2HostApiImpl.java:171-173` | `openViewingReport` → `ROUTE_REPORT` + finish | scope 外 |
| `flutter/B4HostApiImpl.java:297-298` | `openLab` → `ROUTE_LAB` + finish | scope 外 |
| `flutter/B4HostApiImpl.java:317-318` | `openPluginManager` → `ROUTE_PLUGINS` + finish | scope 外 |
| `flutter/B5HostApiImpl.java:462-466` | `openSearchEntry` page 6 → `ROUTE_SUBTITLE`，随后无条件 `activity.finish()` | scope 外 |
| `flutter/B5HostApiImpl.java:479` | `openSearchEntry` 收尾 `activity.finish()`（经 `FlutterNav.openHomeAt` 导航后仍会 finish 自身；本次 FlutterNav 修复后，此处仍是残留） | scope 外，重点 |
| `flutter/B7HostApiImpl.java:697-698` | `openLab` → `ROUTE_LAB` + finish | scope 外 |
| `flutter/B7HostApiImpl.java:888-889` | `openAbout` → `ROUTE_ABOUT` + finish | scope 外 |
| `flutter/B13HostApiImpl.java:267-268` | `openWidgetMore` → `ROUTE_WIDGETS` + finish | scope 外 |
| `flutter/B21HostApiImpl.java:1059-1060` | `openLabMiniappManager` → `ROUTE_HK_MANAGER` + finish | scope 外 |
| `ui/activity/AppBrandingActivity.java:31-32` | `start` → `ROUTE_BRANDING` + `instanceof` finish（调用者是 B5 Flutter 屏） | scope 外 |

以下点已审计，确认无问题，本次不改：

- `C1HostApiImpl.closeScreen`（160 行）、`B3HostApiImpl.closeScreen`（662 行）：
  纯 finish（root-back 语义），无 start。
- `B21HostApiImpl.setLabLocalSourceHome`（995 行）：setHome + finish，无 start。
- `B15HostApiImpl`（207/214/271 行）：setResult + finish（文件回传）与
  CLEAR_TASK 重启，均无 C1 start。
- `B21HostApiImpl:939`：`instanceof` 只 gate launcher 调用，无 finish。
- 非 C1 调用者 start（无 finish 或 finish 自身原生界面，符合规则）：
  `LabActivity:340`、`TmdbPersonActivity:121`、`ShortDramaActivity:613`、
  `VodFragment:318/671/680`（均只 start）；`WebHomeShellActivity:264-265`
  （shell 自身 finish，调用者不是 C1）；`LiveActivity:432`（见改动点 A 说明）。
- `homeIntent`/`followingIntent`/`localBrowserIntent` 的调用点（通知、
  crash 重启、Video/Live/TmdbDetail 的 task-root 导航）：均无 start 后 finish。
- `openSearch`/`openFolder`/`openWidgetBrowse`：从不 finish 调用者，未动。

## 验收项

1. 底栏点设置/实验室/追更：切屏成功，不回桌面；再点回首页正常。
2. 首页分类 tab 点“更多”进分类页：不回桌面；返回后首页正常。
3. 历史/收藏入口、聚合搜索 direct 入口：不回桌面。
4. 直播页底栏点点播/设置：回 Flutter 首页/设置，直播页正常 finish。
5. 冷启动：首页直接出内容（分类 +  grid），不再“暂没有内容”；弱网/加载慢时
   35 秒内出内容或超时 toast，不再永久空态。
6. 无配置（noConfig）时：立即出合法空态，不拖 35 秒。
7. 退出重进、旋转：首页正常加载，无重复加载导致的空屏。
8. TV（leanback）构建与行为不受影响（TV 分支一行未动）。

## 复核证据

- grep：`FlutterNav` 内 `activity.finish()` 只剩 3 处，且均为
  `if (!(activity instanceof C1ScreenActivity))`；`B11HostApiImpl` 内
  `activity.finish()` 为 0；`instanceof C1ScreenActivity) activity.finish()`
  正向形式在两个文件内为 0。
- `git diff --stat` 只含 scope 内 3 个 Java 文件 + 本 doc；0 个 Dart/Pigeon/
  manifest 改动；`git status` 除新 commit 外 clean。
- 本地无 Java/Android/Flutter SDK，无法本地编译；以 CI（b8-c0.yml）构建为准。

---

# 第二批：其余 HostApiImpl 的同类 start-then-finish 点（任务 `b8-crash-singletop2`）

任务：`b8-crash-singletop2`（task_guard，mode standard）。分支
`b8-flutter-rewrite`，本地提交，不 push。上一批审计表中列为 scope 外的
10 处同类点（症状与根因 A 相同：从 Flutter 屏点这些入口会回桌面），
本次全部修完；审计表中的对应行自此作废，以本节为准。

## 改法（与上一批同一规则）

六个 impl 的 `activity` 字段恒为所绑定的 C1 实例：B7/B4/B13/C2/B5
均在 `C1ScreenActivity.registerHostApis` 里以 `this` 构造（`C1ScreenActivity.java`
626/635/638/641/664 行）；B21 经 `B21HostApiImpl.register(messenger, this)`
（同文件 705 行）构造，`rebind` 只在 `register` 内部调用，`act` 同样恒为
C1 实例（并有 null 检查）。因此与上一批的 `B11HostApiImpl` 相同：直接删
finish，不用条件式——不存在非 C1 调用路径。`B13HostApiImpl:119` 的
`instanceof C1ScreenActivity` 检查是 C0 引擎守卫，不是非 C1 调用者。

另：`C1ScreenActivity` 只在 mobile manifest 里声明，leanback/main
manifest 里没有——TV 上这些 impl 从不被绑定，TV 分支实际不可达；
删 finish 不改变任何 TV 行为。

## 改动点（10 处）

| 文件 | 方法 / 入口 | 目标路由 | 改法 |
|---|---|---|---|
| `flutter/B7HostApiImpl.java` | `openLab` | `ROUTE_LAB` | 删 `activity.finish()`（原 698 行） |
| `flutter/B7HostApiImpl.java` | `openAbout` | `ROUTE_ABOUT` | 删 `activity.finish()`（原 889 行） |
| `flutter/B4HostApiImpl.java` | `openLab` | `ROUTE_LAB` | 删 `activity.finish()`（原 298 行） |
| `flutter/B4HostApiImpl.java` | `openPluginManager` | `ROUTE_PLUGINS` | 删 `activity.finish()`（原 318 行） |
| `flutter/B13HostApiImpl.java` | `openWidgetMore` | `ROUTE_WIDGETS + ?widgetId=` | 删 `activity.finish()`（原 268 行） |
| `flutter/B21HostApiImpl.java` | `openLabMiniappManager` | `ROUTE_HK_MANAGER` | 删 `act.finish()`（原 1060 行） |
| `flutter/C2HostApiImpl.java` | `openViewingReport`（range 回调内） | `ROUTE_REPORT + ?range=` | 删 `activity.finish()`（原 173 行） |
| `flutter/B5HostApiImpl.java` | `openSearchEntry` page 6 | `ROUTE_SUBTITLE` | 删分支末尾无条件 `activity.finish()`（原 466 行） |
| `flutter/B5HostApiImpl.java` | `openSearchEntry` 收尾 | 经 `FlutterNav.openHomeAt` 的各设置页 | 删收尾 `activity.finish()`（原 479 行） |
| `ui/activity/AppBrandingActivity.java` | `start` | `ROUTE_BRANDING` | 删 `if (activity instanceof C1ScreenActivity) activity.finish();`（原 32 行） |

各处“start-then-finish”旧注释同步改写，不再称 start-then-finish。

### B5 两处重点核对

- Page 6 分支：finish 在 mobile/TV `if` 之外，无条件执行——mobile 上
  finish 的正是刚被 `start(ROUTE_SUBTITLE)` 复用的 C1 实例；TV 分支走
  `openHomeAt(6)`，但 TV 从不绑定该 impl（见上文 manifest 证据），删
  finish 对 TV 无影响。
- 收尾 finish：`openHomeAt` 已由上一批改为 caller-aware，导航本身不再
  finish C1 调用者；B5 自己收尾再 finish 一次是残留——mobile 上同样关
  掉唯一实例。删后 mobile 全程只 start、不 finish。

### AppBrandingActivity 一处重点核对

原代码只在调用者是 C1 时 finish——在 singleTop 下恰恰是最坏的写法。
唯一调用者是 `B5HostApiImpl.openAppBranding`（B5:384 行），传的是绑定
的 C1 实例。修法是整行删除，不是取反：注释早已写明原生调用者“只 start，
不 finish”（品牌页盖在上面，返回回设置页），C1 调用者同样不 finish，
两种调用者行为就此统一。

## 横扫：全部 `*HostApiImpl.java` 里的 `finish()` 分类

grep 覆盖 `app/src/main/java/com/github/moyingshi/osc/tv/flutter/*HostApiImpl.java`
共 18 处。除上表 9 处（第 10 处在 `AppBrandingActivity`，不在该目录）
外，其余 9 处逐个核对，均非 C1 路由导航，一律未动：

| 位置 | 分类 | 结论 |
|---|---|---|
| `B15HostApiImpl.java:207/214/271` | 打开原生 Activity/文件回传 | setResult + finish（文件回传）与 CLEAR_TASK 重启，无 C1 start；上一批已审计 |
| `B21HostApiImpl.java:995` | 其他（setLabLocalSourceHome） | setHome + finish，无 start |
| `B22HostApiImpl.java:394` | 其他（openFeedItem） | 只向原生 ShortDramaActivity 发 reflective 跳转请求，无 C1 start；finish 是为了落回原生播放流，语义正确 |
| `B3HostApiImpl.java:662` | 其他（closeScreen） | 纯 finish（root-back 语义），无 start |
| `B7HostApiImpl.java:801/808` | 其他 | `progress.finish()`，是 Progress 对象的方法，不是 Activity |
| `C1HostApiImpl.java:160` | 其他（closeScreen） | 纯 finish（root-back 语义），无 start |

上一批已修的 `FlutterNav`（3 处条件式 finish，均为
`if (!(activity instanceof C1ScreenActivity))`）与 `B11HostApiImpl`
（0 处）本次未动；原生调用者（`LabActivity`、`TmdbPersonActivity`、
`AppBrandingActivity`、`WebHomeShellActivity`）调
`C1ScreenActivity.start` 时 C1 不在栈顶，与本次 bug 无关，未动。

## 复核证据

- grep：7 个改动文件内，`C1ScreenActivity.start` 后的 `activity.finish()` /
  `act.finish()` 为 0；`instanceof C1ScreenActivity) activity.finish()`
  正向形式在 Java 代码中为 0 处（`AppBrandingActivity` 的已删）。
- `git diff --stat` 只含 scope 内 7 个 Java 文件 + 本 doc；0 个 Dart/Pigeon/
  manifest 改动；`git status` 除新 commit 外 clean。
- 本地无 Java/Android/Flutter SDK，无法本地编译；以 CI（b8-c0.yml）构建为准。

## 验收项（接上一批）

1. 设置页点实验室/关于：切屏成功，不回桌面；返回后设置页正常。
2. 设置页点插件管理：切屏成功，不回桌面。
3. 小组件页点“更多”：进小组件浏览页，不回桌面。
4. 实验室点海阔小程序管理：切屏成功，不回桌面。
5. 历史页选范围进查看报告：切屏成功，不回桌面。
6. 设置内搜索点字幕设置行 / 任意设置行：进目标页，不回桌面；返回后
   设置页正常。
7. 个人设置点应用品牌：进品牌页，不回桌面；返回后个人设置页正常。
8. 短剧 feed 卡点按、实验室设首页、设置页关屏：行为不变（横扫未动点）。
