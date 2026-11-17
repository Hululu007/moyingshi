# B14 追更页（B8 全量迁移）

> §0 执行声明：本批把手机端追更页（FollowingActivity，约 562 行）迁移到 Flutter，作为独立路由 `/following`（C1 引擎）。
> 任务护栏 `b8-b14-following`（standard），分支 `b8-flutter-rewrite`，基线 B13 提交 `c80e798f`。
> 开关 `flutter_b14_enabled` 默认关，独立于其他开关；开关 UI 只在实验屏。关 = 追更页回原生，原生实现一行不删。
> **本机无 Java / Android / Flutter SDK，构建与运行均未验证**（CI 联编 + 真机验收时按 §6 逐项证明）。不推送。

## §1 原生实现（只读发现）

原生实现：`app/src/main/java/com/github/moyingshi/osc/tv/ui/activity/FollowingActivity.java`（562 行，main 源集，与 TV 共用）+ `app/src/main/java/com/github/moyingshi/osc/tv/ui/adapter/FollowingAdapter.java`（187 行，main 源集）。

### 1.1 入口

- 手机端：`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/activity/HomeActivity.java:520`，`handleNavItem(R.id.following)` → `FollowingActivity.start(this, null)`。`start(Activity, String identityKey)`：identityKey 为空时不聚焦，否则渲染后滚动到该行。
- TV 端：leanback `HomeActivity` 两处调用（菜单 + 按键 10）——**TV 不动**。
- 另一入口：`B11HostApiImpl.java:631` 的 `openBottomBarItem("following")` 直接调 `FollowingActivity.start(activity, null)`——B11 已冻结，本批不改（见 V9）。

### 1.2 门禁与调度

- `onCreate`：`!FollowingSettings.isEnabled()` → toast `following_enabled_hint` + finish；随后 `FollowingNotifier.createChannel()`、`FollowingScheduler.ensurePeriodic(this)`、`FollowingScheduler.enqueueDueNow(this)`（ensure/enqueue 的调用形态与 `B5HostApiImpl.toggleFollowing` 同款；`enqueueDueNow` 用 `ExistingWorkPolicy.KEEP`，重复调是 no-op）。

### 1.3 数据来源与刷新

- `load()`：后台线程 `FollowingStore.list()`（Room `findActive()`），按当前筛选过滤，按 `LIST_ORDER` 排序（hasUpdate 优先 → unwatchedCount 降序 → updatedAt 降序 → metadataUpdatedAt 降序 → createdAt 降序），每行取 `FollowingStore.preferredSource(identityKey)`，另取 `FollowingStore.unreadCount()`；主线程 `render()`。
- 筛选 5 档循环：全部 / 更新（hasUpdate || unwatchedCount>0）/ 未看（unwatchedCount>0）/ 已完结（officialStatus 为 ENDED/CANCELED）/ 检查失败（`FollowingUpdateCoordinator.sourceCheckFailed(lastError)`，与源行失败判据同口径）。
- 摘要：`following_summary` = “追更 %1$d 部 · 未读 %2$d 部”（行数，未读数）；空态文案按筛选区分（`following_empty` / `following_filter_empty`）。
- `onResume` 重取。

### 1.4 行渲染（FollowingAdapter）

- 86×120dp 图（`ImgUtil.load(vodName, vodPic, image)`）+ 标题 16sp 粗体。
- 官方行：`FollowingUpdatePolicy.releasedEpisode(item)` > 0 → `following_official`（已出 N 集），否则 `following_official_unknown`。
- 元数据行（`bindMetadata`）：“季总集数 · 剧总集数 · 周X”，无项时隐藏；周 X 取 `R.array.following_weekdays[nextAirWeekday - 1]`。
- 源行：`source.playableEpisode > 0` → `following_source`（可播第 N 集），否则按 `sourceCheckFailed(lastError)` 区分 `following_source_failed` / `following_source_unknown`。
- 进度行：已看集数（`following_watched`，0 集时 `following_watched_unknown`）+ 换行 + 未看数（`following_unwatched`，0 且已出集时 `following_unwatched_none`）。
- 徽章：hasUpdate && unwatchedCount > 0 时显示 `following_new_count`（“新 N 集”）。
- 错误行：lastError 非空时显示，否则隐藏。
- 通知按钮文案：`following_notify_on` / `following_notify_off`；下一季按钮仅当 `latestReleasedSeason > trackedSeason` 时显示，文案 `following_next_season`（参数 `latestReleasedSeason + 1`）；已读按钮仅当 hasUpdate 时显示。
- 行点击 → `onOpenDetail` 详情弹窗：正文 = 官方行 + 源行 + 已看行 + 未看行 + 错误行；5 个动作 [继续， 检查， 已读， 换源， 取消追更] + 关闭按钮。**注意：第 5 个动作是取消追更（调 `onDelete`），不是关闭弹窗。**

### 1.5 行动作

- 继续（`onContinue`）：后台 `reconcile` + `FollowingStore.historyFor(item, source)` → 有历史 → `VideoActivity.startFromFollowingHistory`（mobile 与 leanback 双端同签名）；否则 `preferred(item, source)` 兜底 → `TmdbDetailActivity.start` 8 参（含 `Setting.getDetailOpenMode()`）；无可用目标 → toast `following_check_failed`。
- 追下一季（`onFollowNextSeason`）：整行 + 源复制（字段重置见 `B14HostApiImpl.copyForNextSeason`，与原生逐行一致）→ `FollowingStore.saveNew` → `enqueueDueNow` + toast `following_added` → 以新 identityKey 重新打开聚焦；已存在时直接聚焦。
- 检查（`onCheck`）：`FollowingUpdateCoordinator.checkNow(identityKey, true)` → toast `following_check_done` / `following_check_failed` → 重取。
- 已读（`onRead` / `markAllRead`）：内存 `FollowingUpdatePolicy.markRead(item, 0)` + `FollowingPlaybackBridge.markReadAllAsync` → 刷新未读摘要；全部已读只取当前可见（已筛选）行中有更新的 key。
- 通知开关（`onToggleNotify`）：开 + SDK ≥ 33 + 未授权 → `ActivityResultLauncher` 申请 `POST_NOTIFICATIONS`（允许 → `setNotificationsEnabled(true)` + `setNotifyEnabledAsync(true)`；拒绝 → 仅重取）；关 → 直接 `setNotifyEnabledAsync(false)`。
- 换源（`onChangeSource`）：反射 `SearchActivity.start(Activity, String)`（SearchActivity 只在 mobile/leanback 源集）；异常 toast。
- 取消追更（`onDelete`）：确认弹窗（标题 `following_delete_title`，正文 `following_delete_message`，取消 `dialog_negative`，确认按钮文案也是 `following_cancel`）→ `FollowingScheduler.cancelNext` + `FollowingPlaybackBridge.deleteAsync` → 重取。

### 1.6 顶栏

- 全部检查：toast `following_checking` → 逐项 `checkNow`（全部行，不过滤）→ toast `following_check_done` / `following_check_failed` → 重取；检查期间按钮禁用。
- 全部已读、筛选循环按钮、服务端导入按钮。
- 服务端导入：URL + token 表单弹窗（token 为密码输入）；“预览”中性按钮与“导入”正按钮；地址非 http(s) → `following_server_invalid`；先 `setServerImportEnabled(true)` 再 `AlistSubscriptionImporter.fetch`（未启用时 fetch 抛错）；预览弹窗列前 20 条（`标题 · S<n+1>[ · E<m>]`，超出显示 `… +N`），确认后 `importCandidates` → toast `following_server_result`（“已导入 X，更新 Y”）；空列表 → `following_server_empty`。

### 1.7 布局

- `app/src/main/res/layout/activity_following.xml`：顶栏 4 按钮 + 摘要 + RecyclerView + 空态（含 `ic_home_following` 图标）。
- `app/src/main/res/layout/item_following.xml`：MaterialCardView，16dp padding，14dp 底 margin；按钮行在 HorizontalScrollView 里，38dp tonal 按钮。
- 页面背景透明 + 全局壁纸（`CustomWallView` 垫在内容下）。

### 1.8 字符串与资源归属（按目录分别 grep 验证，无 head 截断）

- 全部 `following_*` 字符串只在 `app/src/main/res/values/strings.xml`，mobile/leanback res 里一个都没有；`R.array.following_weekdays` 在 main res；`dialog_close` / `dialog_negative` / `error_play_timeout` / `error_retry` 均 main res。所以 impl 里直接 `R.string` 引用是 leanback 安全的，无需 `mobileString()`。
- `following_notification_permission_hint` 有 zh-rCN 翻译（“未获得通知权限；站内角标和手动检查仍可使用。”），但原生代码里没有任何调用处（闲置字符串），见 V3。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- `Setting.flutter_b14_enabled`，默认 false，独立开关；开关 UI 只在实验屏（C0 屏新加一行）。
- 入口分流只在手机端 `HomeActivity.handleNavItem(R.id.following)`：开关开 **且** `FollowingSettings.isEnabled()` → `C1ScreenActivity.start(this, ROUTE_FOLLOWING)`；否则原样 `FollowingActivity.start(this, null)`。TV 入口、B11 底栏入口不动。
- `getFollowing` 内另有防线：特性关时返回空列表 + 空态文案 `following_enabled_hint`。

### 2.2 快照与动作完成规则（本批核心设计）

- `getFollowing(filterIndex)`：@async 一次性快照（Task.execute 后台取数 + 排序 + 逐行算文案，主线程完成），35s 看门狗；超时按 B8 惯例**成功返回错误快照**（`errorText = error_play_timeout`），Dart 显示错误态 + 重试。`lastRows` 保存本次筛选后的行，供 `markAllRead` 取 key（与原生 adapter 可见行同口径）。
- `checkAll`：180s 看门狗（每行一次联网检查，原生无超时）；`checkItem` / 详情弹窗内检查：60s；`markRead` / `markAllRead` / `followNextSeason`：10s；`openDetail` / `deleteItem`：30s（等用户点弹窗）；服务端导入 fetch 60s / import 30s；`toggleNotify` 权限分支 120s（见 §2.5）。
- 每个 @async exactly-once：`Completion` 守卫 + 看门狗互斥；看门狗触发后，后台迟到完成一律 no-op（**含 toast 也不补发**），Dart 重取后看到旧状态，下次 resumed/下拉刷新时看到新状态。
- 无 EventChannel；刷新 = 首次加载 + resumed 重取 + 下拉刷新 + 每个 @async 动作完成后的 Dart 重取。

### 2.3 Pigeon 合约（`flutter_module/pigeons/b14_following_messages.dart`）

- `B14HostApi`：`isB14Enabled` / `setB14Enabled`；@async `getFollowing(int)` → `B14FollowingScreen`；@async `checkAll` / `checkItem` / `markRead` / `markAllRead` / `toggleNotify` / `followNextSeason` / `openDetail` / `deleteItem` → `B14ActionResult`（ok + message + newIdentityKey）；同步 void `continuePlay` / `changeSource`；@async `fetchServerCandidates(url, token)` → `B14ImportPreview`；@async `importServerCandidates()` → `B14ActionResult`。
- `B14FollowingScreen`：items、summary、unreadCount、filterIndex、filterLabel、emptyLabel、顶栏 3 文案、行动作 5 文案、导入弹窗 6 文案、title（`nav_following`）、retryLabel（`error_retry`）、errorText。
- `B14FollowingItem`：PicSpec 图像字段组（按 §4.2 每合约重声明）+ 官方/元数据/源/进度行、徽章（visible + text）、错误（visible + text）、通知文案 + 状态、下一季（visible + label）、已读可见。
- `B14ImportPreview`：ok、message、title、lines（前 20 条）、moreCount、totalCount、importLabel。

### 2.4 Dart 页（`flutter_module/lib/b14_following_page.dart`）

- AppBar（返回 + title）→ 摘要行 → 工具栏（筛选 / 全部检查 / 全部已读 / 服务端导入，横向可滚）→ `RefreshIndicator` + 列表/空态。
- 行卡：86×120 `PicImage` + 文本列（标题 + 徽章、官方、元数据、源、进度、错误）+ 横向滚动的 tonal 按钮行；行点击走 host 详情弹窗。
- 每行动作按钮在途禁用（`_busyActions`，防双击）；动作完成后 Dart 重取（继续/换源是 fire-and-forget，靠 resumed 重取）。
- 服务端导入：Dart 表单弹窗（URL + token 密码框）→ 预览取数 → 预览弹窗（前 20 条 + `… +N`）→ 确认导入 → SnackBar 显示 host 的结果文案 → 重取；直接导入则跳过预览弹窗。
- 追下一季：host 返回新 identityKey，Dart 重取后用行 GlobalKey + `ensureVisible` 滚动定位。
- 错误态：host 错误快照（errorText + 重试）与通道级错误（B2 同款 bare 文本 + 重试）分开处理。

### 2.5 Host 实现（`B14HostApiImpl.java`）

- 快照构建与原生逐行对照：`filteredRows`（筛选 + `LIST_ORDER` 原样复制）、`toItem`（按 `FollowingAdapter.onBindViewHolder` 逐行算文案；`nextAirWeekday` 越界时防御性跳过该片段——原生会越界崩溃）；图片解析 `resolvePic` / `splitHeaders` 照抄 C2（`ImgUtil.getUrl` 委托 + `TmdbProxy` fallback + 首字占位，D3/D6）。
- 弹窗全部 host 侧（B4 同款）：详情弹窗与删除确认弹窗都用 `WebHtvAlertDialogBuilder`，与原生同文案同按钮。
- 详情弹窗完成时序（C2 D2 的延伸）：@async 结果**按所选动作结算**——继续/换源立即完成（已跳原生页）；检查/已读在桥接回调后完成；取消追更在内层确认弹窗结算后完成；无动作关闭（关闭按钮/返回键）时在 dismiss 完成。`actionChosen` 标志防止 dismiss 抢跑；删除确认弹窗另有 `confirmed` 标志，同一道理。
- 通知开关：SDK < 33 或已授权 → 直接开；否则 `PermissionX` + 仓库 `PermissionCallback`（B4 `PermissionUtil.requestFile` 同款，`allGranted` 直通）；拒绝 → host toast `following_notification_permission_hint`，状态保持关。120s 看门狗只防实现挂起，不防用户久等——若用户 120s 内未作答，看门狗先完成，迟到的授权仍会执行写操作（只跳过已发出的完成），见 §6.3。
- 换源：与原生完全相同的反射调用（`SearchActivity` 只在 mobile/leanback 源集，§4.8 招 4）。
- `getFollowing` 内复刻原生 `onCreate` 的调度调用（`createChannel` + `ensurePeriodic` + `enqueueDueNow`，`KEEP` 策略下重复调用无害）。

### 2.6 文件清单

- 新 4 个：`flutter_module/pigeons/b14_following_messages.dart`、`flutter_module/lib/b14_following_page.dart`、`app/src/main/java/com/github/moyingshi/osc/tv/flutter/B14HostApiImpl.java`、本文档。
- 改 7 个：`flutter_module/lib/main.dart`（`/following` 路由 + B14 开关）、`C1ScreenActivity.java`（`ROUTE_FOLLOWING` + 注册 B14HostApi）、`C0ShellActivity.java`（注册 B14HostApi，仅开关用）、`Setting.java`（`flutter_b14_enabled`）、`app/src/mobile/.../HomeActivity.java`（底栏追更项分流）、`.gitignore`（`B14Messages.java`）、`.github/workflows/b8-c0.yml`（Pigeon 步骤加 b14 生成 + 存在性检查）。
- 未动：`FollowingActivity` / `FollowingAdapter` / 全部 following 包 / TV 入口 / B11 / C0–C2/B2–B13 合约与实现 / 共享组件（只读复用 `PicImage`，无改动）。

## §3 状态来源清单（全部 host 快照，Dart 不计算）

| 显示项 | 来源（host） |
|---|---|
| 列表行、顺序 | `FollowingStore.list()` + 筛选 + `LIST_ORDER` |
| 未读数、摘要 | `FollowingStore.unreadCount()` + `following_summary` |
| 筛选标签、空态文案 | `following_filter_*` / `following_empty` / `following_filter_empty` |
| 官方/元数据/源/进度行 | `FollowingUpdatePolicy.releasedEpisode` + adapter 规则逐行算 |
| 徽章“新 N 集” | hasUpdate && unwatchedCount > 0 |
| 错误行 | lastError |
| 通知按钮文案 | `following_notify_on` / `following_notify_off` |
| 下一季按钮 | `latestReleasedSeason > trackedSeason`，文案 +1 季 |
| 已读按钮可见 | hasUpdate |
| 顶栏/行动作/导入弹窗文案 | 全部 `following_*` + `dialog_negative` + `error_retry` |
| 标题 | `nav_following` |
| 图片 | `ImgUtil.getUrl` 委托（D3）+ `TmdbProxy` fallback |

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 列表与排序

- [ ] 开关开后底栏追更进 Flutter 页；列表内容、顺序与原生一致（有更新的在前，其次未看数多、最近更新）。
- [ ] 摘要“追更 N 部 · 未读 M 部”与原生一致；未读数与首页底栏徽章一致（B11 同源）。
- [ ] 每行：图、标题、官方行、元数据行（季总/剧总/周X）、源行、进度行（两行）、错误行，均与原生逐行比对。
- [ ] 无追更 / 筛选无结果时空态文案正确（全部时 `following_empty`，筛选时 `following_filter_empty`）。

### 4.2 未读标记

- [ ] 有更新且有未看集的行显示“新 N 集”徽章；点“已读”后徽章消失、摘要未读数下降。
- [ ] “全部已读”只清当前筛选可见行；切换筛选后再点，作用范围跟着变。

### 4.3 行按钮与点卡

- [ ] 继续：有历史时进播放器，无历史时进详情页（与原生同去向）。
- [ ] 检查：单行检查后 toast 对、行数据更新。
- [ ] 追下一季：新季行出现并自动滚动定位；已存在的新季直接定位。
- [ ] 通知开关：开/关切换文案对；SDK 33+ 首次开时弹系统权限窗（见 §6.3）。
- [ ] 换源：进搜索页（B9 开关开时进 Flutter 搜索页）。
- [ ] 取消追更：确认弹窗 → 行消失。

### 4.4 详情弹窗

- [ ] 行点击弹窗正文（官方/源/已看/错误行）与原生一致；5 个动作齐全；第 5 个是“取消追更”。
- [ ] 弹窗内点“检查”/“已读”后，弹窗关闭且列表已是新状态（不是旧状态）。
- [ ] 关闭按钮/返回键关闭弹窗，列表不变。

### 4.5 顶栏

- [ ] 全部检查：toast 对（检查中 → 完成/失败），检查期间按钮禁用。
- [ ] 筛选按钮 5 档循环，标签与列表同步变。
- [ ] 服务端导入：表单、预览（前 20 条 + `… +N`）、直接导入、无效地址提示、导入结果 toast 均对。

### 4.6 刷新与错误

- [ ] 下拉刷新、从播放器/搜索页返回后列表刷新。
- [ ] 加载超时（35s）时错误态 + 重试按钮出现（设备上难复现，知道即可）。

### 4.7 开关与回滚

- [ ] 开关关 → 底栏追更回原生页；实验屏开关即时生效。
- [ ] 特性总开关（追更功能）关时：底栏仍进原生页（原生 toast 提示）。

## §5 相对规划的全部偏差

- **V1（详情弹窗按动作结算）**：规划 §4.6 只规定 @async 一次性快照与看门狗，未规定原生弹窗内动作的完成时序。本批定为：结果按所选动作结算（检查/已读/删除等异步动作完成后再完成），而不是弹窗 dismiss 时就完成——否则 Dart 会在动作结算前重取到旧状态。这是 C2 D2（删除弹窗 dismiss 时完成）的延伸。
- **V2（已读无乐观本地更新）**：原生 `markReadLocally` 立即改内存行再异步落库；Flutter 侧无内存列表，桥接写完 + Dart 重取后才见效。最终态一致，中间无即时反馈。
- **V3（通知拒绝时 toast）**：原生拒绝后静默重取；本批在拒绝时 host toast `following_notification_permission_hint`（该字符串一直闲置，zh-rCN 有翻译）。
- **V4（无壁纸）**：原生页面透明 + 全局壁纸；Flutter 页是纯色背景（与 B2/C2 同款处理）。
- **V5（徽章位置近似）**：原生徽章叠加在卡片上；Dart 放在标题行右侧小 pill。
- **V6（按钮形态近似）**：原生 38dp tonal MaterialButton（HorizontalScrollView）；Dart 用 `FilledButton.tonal`（38dp、shrinkWrap）横向滚动。
- **V7（空态图标近似）**：原生 `ic_home_following`；Dart 用 `Icons.rss_feed`。
- **V8（检查看门狗）**：原生全部检查/单行检查无超时；本批 180s/60s 看门狗，超时后 Dart 先重取旧状态，后台检查继续跑，迟到完成静默（toast 也不补）。
- **V9（B11 底栏追更项仍走原生）**：B11 已冻结，未改 `B11HostApiImpl.openBottomBarItem("following")`。后果：Flutter 首页（B11 开）+ B14 开时，底栏点追更仍进原生页；只有原生首页的底栏分流走 Flutter。待父级决定：是否像 B6→B7 那样允许对 B11 做 3 行预授权分流，或等 B11 解冻再改。
  - **解决（2026-11-17 14:51，任务 `b8-b14-b11-diversion`，quick-fix）**：父级批准 B6→B7 同款预授权分流。`B11HostApiImpl.openBottomBarItem` 的 `case "following"` 改为：`Setting.isFlutterB14Enabled()` 开时 `C1ScreenActivity.start(activity, ROUTE_FOLLOWING)` + `activity.finish()`，关时原样 `FollowingActivity.start(activity, null)`。B11 其余代码、B14 合约与实现均未动。分流与 "setting" 行同为 start-then-finish，G2 跨屏竞态风险同款（B11 文档 §5 V10），若设备验收复现路由串位，需在 `C1ScreenActivity` 加重启机制（跨批修复，本批不做）。
- **V10（下一季聚焦）**：原生 `scrollToPosition`；Dart 用行 GlobalKey + `ensureVisible`，行尚未构建时静默跳过。
- **V11（导入共用暂存列表）**：`fetchServerCandidates` 后 `importServerCandidates` 直接用 host 暂存的那批候选，不重新拉取——与原生一致（原生导入的就是预览那批）。
- **V12（通道级失败文案）**：host 超时走错误快照；通道本身断开时 Dart 沿用 B2 的 `'加载失败：$e'` 前缀。
- **V13（无其他偏差）**：开关默认关、只实验屏有 UI、TV 不动、无 schema 变化、无新 Dart 依赖、图片走共享 `PicImage` + `ImgUtil.getUrl` 委托、`data:` 显示占位、全部文案 host 返回。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon 代码生成（CI 唯一可证）**：14 个方法 + 4 个数据类（`List<B14FollowingItem>` 嵌套、`Long`/`Boolean` 装箱、方法名与 Dart 定义逐字一致——C1 run #10 的教训）；CI 若报生成/编译错，按文件路径归属本批修。
2. **弹窗完成时序（设备验收）**：详情弹窗内点“检查”/“已读”/“取消追更”后，列表是否已是新状态；关闭/返回是否正常完成不挂起；删除确认弹窗点确认后行是否消失。
3. **通知权限流程（设备验收）**：SDK 33+ 首次开通知 → 系统弹窗 → 允许后开关变开；拒绝后 toast 提示且开关保持关。120s 看门狗只防实现挂起；若用户久不作答后才授权，写操作仍执行、只跳过已发出的完成。
4. **全部检查耗时（设备验收）**：追更数多时 180s 看门狗是否触发；触发后下拉刷新是否看到新状态。
5. **G2 跨屏竞态**：继续/换源是 Flutter→原生 start（无竞态）；但从追更页进详情/播放器再返回、以及下一季聚焦滚动时，观察路由是否错乱（B11 V10 同款风险，复现时需跨批修）。
6. **图片一致性**：各行图片与原生逐行比对（豆瓣 Referer、TMDB fallback、`data:` 占位、占位色）。
7. **键盘（B5 依赖）**：服务端导入表单两个 TextField 的键盘弹出/收起是否正常；若异常，B9 的决策点同样适用。
8. **下一季聚焦滚动**：新季行是否定位准确；新季排在列表下方很远时是否仍能定位。

## Recovery anchor

- 目标：B14 手机端追更页迁移到 Flutter（`/following` 路由 + B14HostApi），开关 `flutter_b14_enabled` 默认关，不推送。
- 状态：本地实现完成（护栏 `b8-b14-following`），待 `finish` 提交 + 恢复标签；main 仍为 `f39a6e0`；工作树待验证干净。
- 文件：新 4 个（合约、Dart 页、Host impl、本文档），改 7 个（main.dart 路由 + 开关、C1/C0 注册、Setting 开关、手机 HomeActivity 分流、.gitignore、b8-c0.yml）。
- 未验证：构建与运行（本机无 SDK），按 §6 逐项留给 CI 联编 + Wei 真机验收。
- 下一步：B15 本地文件 + 应用图标迁移实施。
