# B12 首页块A（B8 全量迁移）

> §0 执行声明：本批把手机端首页块A（轮播自动播放 + 继续观看）迁移到 Flutter，作为 `/home` 族内的 widget（不是独立页）。
> 任务护栏 `b8-b12-home-block-a`（standard），分支 `b8-flutter-rewrite`，基线 B11 提交 `7f40d1bf`。
> 开关 `flutter_b12_enabled` 默认关，独立于其他开关；开关 UI 只在实验屏。关 = 首页块A回 B11 占位区，原生实现一行不删；B11 开关关时首页整体回原生，B12 开关不起作用。
> **本机无 Java / Android / Flutter SDK，构建与运行均未验证**（CI 联编 + 真机验收时按 §6 逐项证明）。不推送。

## §1 原生实现（只读发现）

原生实现：`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/VodFragment.java`（1210 行）+ 两个 mobile-only 适配器 + 三个布局。

### 1.1 轮播横幅（B1，VodFragment 359–421 行）

- **数据**：当前站首页推荐，即 `SiteViewModel.homeContent()` 的 `Result.getList()`（`homeVideoContent`）；`setAdapter()` 里 `setBanner(result.getList())`。无数据时整个横幅隐藏。
- **结构**：`ViewPager2` 横向，188dp 高；页内边距 16dp 两侧 / 4dp 上下；图 `centerCrop`、圆角 `Vod.Grid.Large`（16dp）；名 16sp 粗体白字带阴影（`#80000000`，dy 1，radius 3），距图底 28dp；备注 12sp 白字同阴影，距图底 8dp，空时 GONE（名的位置不动）；指示点右上角（距上/右 12dp）：本页 18dp 白色 pill，其余 6dp 半透白圆（`0x66FFFFFF`），间距 6dp，≤1 页时隐藏。
- **自动播放**：4 秒一页（`App.post(runnable, 4000)`）；拖动时暂停（`SCROLL_STATE_DRAGGING` 置位，`IDLE` 清除并重起）；仅 `itemCount > 1` 时启动；新数据 `setCurrentItem(0, false)` 回到第 0 页；`onResume` 时若横幅可见重起。
- **可见判据**：有数据，且非 hk 内嵌、非原生 chrome 隐藏、Web 首页不可见。
- **点按**（`onBannerClick`）：action 卡 → `ActionCardHelper.handleAction(activity, homeKey, action)`；index 站 → `SearchActivity.start(name)`；其余 → `VideoActivity.start(activity, homeKey, id, name, pic, null)`（传原始 vodPic，详情页自己解析 `@Headers=` 后缀）。

### 1.2 继续观看（B2，VodFragment 423–462 行）

- **数据**：`History.getForDisplay()` 过滤 `duration > 0 && position < duration`，取前 10 条；没有就隐藏整行。刷新点：`initView`（`setContinueView`）、`setAdapter`（随 homeContent 回包）、`onResume`、HISTORY 类 `RefreshEvent`。
- **结构**：区上下 padding 各 8dp；标题 `@string/home_continue_watching`（main res），16sp 粗体，marginStart 16dp；横向 `RecyclerView`，两侧 padding 16dp、上 8dp；item 宽 104dp、四边 margin 4dp；图 104×140dp、圆角 `Vod.Grid.Large`（16dp）、`centerCrop`；进度条 4dp 贴图底（max 100，`progressTint = colorPrimary`，底 `#40FFFFFF`）；名 12sp 单行、备注 12sp（`@color/setting_card_subtitle`）单行。
- **点按**：`HistoryResumeCoordinator.open(activity, item)`（与历史页点播一致）。
- **标题清洗**（`ContinueAdapter.displayName`）：`file://` 之类技术字串不直接当标题——取最后一段；`.m3u8` 播放列表取父文件夹名；去扩展名并解码 `%XX`；普通剧名原样返回。
- **无图**：pic 为空或仍是旧 `push_image` 占位 data URI 时，显示 `R.drawable.continue_placeholder`（main res：`surfaceContainerHigh → surfaceContainer` 对角渐变），不走 `ImgUtil`。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- `flutter_b12_enabled` 默认 false，开关 UI 只在实验屏；开关只控制 `/home` 内的两块 widget。B11 关时首页整体回原生，B12 开关不起作用（结构保证：B12 widget 只在 Flutter 首页内挂载）。
- **无新路由、无原生入口改动**：两块是 `/home` 族内的 widget（规划 §4.4），B11 页的 Tab 内容区占位（点开 B8 分类页）不动；B11 页只把两处 B12 占位替换为 widget（本批指定接管点）。

### 2.2 轮播数据：搭 B11 的便车，绝不另发一次 homeContent（本批核心设计）

`SiteViewModel.execute()` 会取消同一 `TaskType.RESULT` 的在途请求（旧 future `cancel(true)` + taskId 递增，只有最新请求的回包被投递）。B11 的 `getHomeShell()` 与 B12 若各自调 `homeContent()`，两者会在同一个 activity 作用域的 `SiteViewModel` 上互相取消——B11 的壳加载会被 B12 取消（B11 看门狗报错），或反之。因此：

- `getBanner()` **永不调 `init()` / `homeContent()`**，只观察共享的 `getResult()` LiveData：值已在（B11 壳刚加载完）则立即完成；值为 null（B11 正在重取）则 `observeForever` 等下一个非空值；35s 看门狗；全路径 `removeObserver`；串行 guard（进行中再调直接 error，B11 同款）。
- 时序保证：B12 widget 只在 B11 快照到达后才挂载（B11 页 `_screen != null` 才渲染 `_buildHome`），且每次 B11 快照加载成功 epoch +1 才触发 B12 重取——Tab 切换只 rebuild 不改 epoch，轮播不会被重置回第 0 页。
- C0 引擎防御：`getBanner()` 在非 `C1ScreenActivity` 上直接返回空列表（该引擎的 ViewModel 永不加载，等 35s 毫无意义；按既定模式，C0 引擎上只调开关方法）。

### 2.3 Pigeon 合约（`flutter_module/pigeons/b12_home_a_messages.dart`）

- `B12HostApi`：`isB12Enabled` / `setB12Enabled`；`@async B12BannerScreen getBanner()`；`B12ContinueScreen getContinueWatching()`（同步，见 §2.4）；`openBannerItem(int index)`；`openContinueItem(int cid, String key)`。
- `B12BannerScreen`（1 字段：`List<B12BannerItem> items`，空 = 隐藏横幅，错误结果同样空列表）；`B12BannerItem`（15 字段：index/id/name/remarks/remarksVisible/isAction/action/rawPic + PicSpec 图片字段组）。
- `B12ContinueScreen`（title + `List<B12ContinueItem> items`）；`B12ContinueItem`（15 字段：key/cid/displayName/remarks/remarksVisible/progressPercent/hasRealPic + PicSpec 图片字段组）。
- 图片字段组按规划 §4.2 在各合约内各自声明，不共享类型；`rawPic` 是未动的原始 vodPic，点详情时原样传（B8 同款理由）。

### 2.4 继续观看：同步调用，绝不等网络

原生 `refreshContinue()` 在 `initView` 里立即跑（本地数据），不依赖 homeContent 回包。因此 `getContinueWatching()` 是同步方法：每次调用直接读 `History.getForDisplay()`、过滤、取前 10、算进度与标题清洗——继续行在首页打开时立刻出现，哪怕轮播还在等网络。这也是 B11 壳加载失败时继续行仍能显示的原因。

### 2.5 Dart 页（`flutter_module/lib/b12_home_block_a.dart`）

- `B12BannerWidget(epoch)`：`StatefulWidget` + `WidgetsBindingObserver`。init 时读开关（关 → B11 同款图标占位 188dp）；开则调 `getBanner()`。`didUpdateWidget` 仅 epoch 变化时重取。数据为空 → `SizedBox.shrink`（隐藏，与原生 GONE 一致）；加载中 → 图标占位（无转圈，原生加载前横幅也是隐藏的，见偏差 V7）。
  - `PageView.builder` + `PageController`；新数据 `jumpToPage(0)`（无动画，与原生 `setCurrentItem(0, false)` 一致）；页内 Padding 16/4、`ClipRRect` 16dp、`PicImage` 高 180dp；名/备注用 `TextStyle.shadows` 复刻原生阴影；指示点 Row（本页 18×6 白 pill、其余 6×6 白 40% 透明、间距 6dp，`>1` 页才显示）；点卡走 `openBannerItem(index)`。
  - 自动播放：`Timer.periodic(4s)`，`items.length > 1` 且未拖动且应用在前台时运行；`NotificationListener<ScrollNotification>`：`ScrollStartNotification` 暂停、`ScrollEndNotification` 重起；`paused/resumed` 同理；`dispose` 取消。`onPageChanged` 用 `setState` 更新 `_page`（指示点跟随；实施中自查修掉的 bug——初版只赋值不 setState，点不会动）。
- `B12ContinueWidget(epoch)`：init 时读开关（关 → B11 同款图标占位 96dp）；开则调 `getContinueWatching()`；epoch 变化时重取。空 → `SizedBox.shrink`；标题 16sp 粗体（host 文案）、`ListView` 横向（padding 16dp）、item 宽 104dp + margin 4dp、图 104×140dp 圆角 16dp、`hasRealPic` 为 false 时显示对角渐变容器（`surfaceContainerHigh → surfaceContainer`，复刻 `continue_placeholder.xml`）、进度条 `LinearProgressIndicator`（4dp，底白 25% 透明、值 `colorScheme.primary`）、名/备注 12sp 单行省略；点卡走 `openContinueItem(cid, key)`。
- `_B12BlockPlaceholder`：B11 `_buildBlockPlaceholder` 的复刻（开关关/加载中时用，视觉与 B11 一致；B11 自己的那份留给 B13 用，不动）。
- B11 页改动（只加法 + 指定接管点）：import 本文件；`_homeLoadEpoch` 字段，`_load()` 成功时 +1；`_buildHome` 里两处 B12 占位换成两个 widget；类注释里“占位属于 B12”的表述更新。

### 2.6 Host 实现（`B12HostApiImpl.java`）

- `getBanner`：见 §2.2（C0 引擎防御 + 串行 guard + 现值直取 + `observeForever` + 35s 看门狗）。`buildBanner`：`Result.getList()` 逐条 `toBannerItem`（`remarksVisible = !remarks.isEmpty()`，与 `BannerAdapter` 的 GONE 规则一致）。
- `openBannerItem`：点按时**重读**当前共享 Result，按 index 取卡（Dart 传来的 index 只在当前结果里有效，过期 index 越界即无操作——见偏差 V2）；复刻 `VodFragment.onBannerClick`：action → `ActionCardHelper`（main 源集直引）；index 站 → `SearchActivity.start(name)`；其余 → `VideoActivity.start(..., rawPic, null)`。站 key 与 isIndex 取点按时的当前 home 站（`VodConfig.get().getHome()`）。
- `getContinueWatching`：同步；`History.getForDisplay()` 过滤 + 前 10；`toContinueItem`：`displayName` 用复刻的 `ContinueAdapter.displayName`（招3，附 drift 警告）、`progressPercent = position*100/duration` 夹取 0–100、`hasRealPic` 按原生规则（空或等于 `push_image` 串即 false）。
- `openContinueItem`：C2 同款 `(cid, key)` 查找 → `HistoryResumeCoordinator.open`。
- 图片：`resolvePic` / `splitHeaders` 从 C2 逐字复刻（`ImgUtil.getUrl` 委托 + TmdbProxy 回退 + 首字占位；data: 占位）；每批独立 revert 的纪律，不跨 impl 共享 helper。
- 资源：本批唯一字符串 `home_continue_watching` 定义在 main res（逐目录 grep 核实：定义只在 main，mobile 只在布局里引用它、leanback 无），host 直接 `R.string` 引用可全 flavor 编译；`push_image` 与 `continue_placeholder` 同样 main res。无 `mobileString()` 需求。

### 2.7 文件清单

| 文件 | 变化 |
|---|---|
| `flutter_module/pigeons/b12_home_a_messages.dart`（新） | B12HostApi + 4 个扁平数据类 |
| `flutter_module/lib/b12_home_block_a.dart`（新） | 轮播 widget + 继续观看 widget + 占位复刻 |
| `flutter_module/lib/b11_home_page.dart` | +25/−7：import + epoch 字段与自增 + 两处占位换 widget + 类注释更新（指定接管点） |
| `flutter_module/lib/main.dart` | +30：B12 import + `_b12Api`/`_b12Enabled` + load/set + 实验屏开关 |
| `app/src/main/java/.../flutter/B12HostApiImpl.java`（新） | 搭便车订阅、继续快照、点按分流、图片解析、标题清洗复刻 |
| `app/src/main/java/.../flutter/C1ScreenActivity.java` | +4：注册 B12HostApi（无新路由） |
| `app/src/main/java/.../flutter/C0ShellActivity.java` | +4：C0 引擎注册（只为开关） |
| `app/src/main/java/.../setting/Setting.java` | +9：`flutter_b12_enabled`，默认 false |
| `.gitignore` | +1：`B12Messages.java` |
| `.github/workflows/b8-c0.yml` | +3：Pigeon 步骤加 b12 生成 + 存在性检查（B11 同款） |
| `docs/b8-b12-home-a.md`（新） | 本文档 |

## §3 状态来源清单（全部 host 快照，Dart 不计算）

- 轮播卡片字段：index/id/name/remarks/remarksVisible/isAction/action/rawPic + PicSpec（`getBanner` 快照）。
- 继续观看：标题 + 每项 displayName/remarks/remarksVisible/progressPercent/hasRealPic + PicSpec（`getContinueWatching` 快照）。
- Dart 本地状态只有：开关值、轮播当前页、自动播放定时器、拖动/前台标志、epoch。

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 轮播

- 实验屏开 B11 + B12 后进 Flutter 首页：轮播与原生一致（图、名、备注、指示点）；无推荐的站无轮播区（不留空白）。
- 自动播放：每 4 秒翻一页，到末页回第 0 页；手拖动时暂停，松手后恢复 4 秒节奏；切后台再回，前台时继续播。
- 手动滑动后指示点跟随；点卡进详情（action 卡走 action、index 站进搜索）；切站后轮播内容随新站刷新、回到第 0 页。
- Tab 切换不重置轮播页码（切 Tab 再切回，轮播停在原页）。

### 4.2 继续观看

- 有未看完内容时出现“继续观看”行（标题 + 横向卡片），内容与顺序与原生一致（最新在前、最多 10 条）；无未看完内容时整行隐藏。
- 卡片：图 104×140、进度条比例与原生一致、名/备注单行；无海报的卡显示柔和渐变占位（不是首字占位）；`file://` 之类标题已清洗（不是技术字串）。
- 点卡进续播（与原生一致）；播完返回首页后行内进度已更新（resumed 重取）；播完一条后该条从行里消失。
- 从详情/播放返回首页，轮播与继续行都刷新。

### 4.3 开关与回滚

- B12 关：首页两块回到 B11 图标占位（轮播 188dp、继续行 96dp），其余首页内容不受影响。
- B11 关：首页整体回原生（B12 开关不起作用）。
- 开关状态重启 App 后保留（`Prefers` 持久化）。

## §5 相对规划的全部偏差

- **V1** 轮播不自己取数：规划 §3 只说“`/home` 内新增 widget，同一合约扩展”，未定数据来源。实际是搭 B11 的便车（§2.2）——`SiteViewModel.execute()` 的取消语义决定了两批不能各发一次 `homeContent()`。代价：轮播只能在壳快照到达后加载；壳加载超时时轮播要再等最多 35s 才隐藏（见 V9）。
- **V2** 轮播点按按 index 查当前结果：快照里不带站 key（`SiteViewModel` 是 activity 作用域单例，host 点按时重读即最新值）；Dart 传 index、host 按当前 `getResult()` 取卡，越界无操作。过期快照的 index 不可能打开错卡。
- **V3** C0 引擎防御：`getBanner()` 在非 `C1ScreenActivity` 上直接返回空列表（B11 没有同款守卫；此处是为避免 35s 空等加的 3 行防御）。
- **V4** 备注行：轮播名恒在距图底 28dp（备注空时也不上移），与原生 `adapter_banner.xml` 的锚定一致——不是偏差，是逐项对齐的记录。
- **V5** 继续观看备注色用 `onSurfaceVariant` 近似：原生是 `@color/setting_card_subtitle`，未逐值复刻，需真机目视对照。
- **V6** 自动播放的 `ScrollEndNotification`：编程式 `animateToPage` 也会触发滚动开始/结束通知，效果是每页播完后定时器从滚动结束起重计 4 秒——页间隔仍是 4 秒，无行为差异。
- **V7** 加载态是 B11 图标占位：轮播/继续行取数期间显示灰色图标占位；原生是取数前整区隐藏（GONE）。首次进首页瞬间会闪一下占位，随后出真实块。
- **V8** 继续行高固定 184dp（140 图 + 4 + 两行 12sp 文字的估算）；原生是 `wrap_content`，以真机为准。
- **V9** 壳加载失败后的轮播残影窗口：B11 壳加载超时/失败时，B12 的 `getBanner()` 观察不到新值，要等自己的 35s 看门狗才清空——期间轮播仍显示旧站内容；点按则用新站 key（§2.6），可能出现旧卡 + 新站的组合。B11 的刷新（⋮ 菜单刷新）会重新触发 homeContent，新值一到观察即完成。
- **V10** 两块无独立下拉刷新：刷新走 B11 的 ⋮ 菜单刷新与 resumed 重取（与原生一致：轮播/继续行随 homeContent/resume 刷新，没有自己的刷新手势）。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon 代码生成**：`@async B12BannerScreen getBanner()`（14 字段 item + `List<>`）与同步 `B12ContinueScreen getContinueWatching()`（15 字段 item）——生成的 Java Builder/Result 签名是否与手写 impl 字符级一致，**CI 才能证明**（B11 只证明了 3 个 `List<>` 嵌套类 + `@async bool/void` 的形态）。
2. **`flutter analyze`**：`main.dart` 的 b12 导入被实验屏开关引用；`b11_home_page.dart` 的新 import 被两个 widget 引用；`b12_home_block_a.dart` 无未使用 import/变量警告（C1 run #8 教训：一个 warning 即失败）。
3. **leanback 编译**：impl 只引 main 源集类 + 双 flavor 同签名的 `VideoActivity`/`SearchActivity`；`home_continue_watching` / `push_image` / `continue_placeholder` 均 main res；无 mobile-only 直引。
4. **搭便车时序（真机）**：轮播随壳快照一起出现，无二次 homeContent 请求（B11 看门狗不报错）；切站、刷新、resumed 后轮播与 Tab 一起刷新；壳加载超时时轮播按 V9 表现。
5. **自动播放（真机）**：4 秒节奏、拖动暂停/恢复、后台暂停、切 Tab 不重置页码、指示点跟随（§4.1 全项）。
6. **继续观看（真机）**：过滤（未看完、前 10）、进度比例、标题清洗、渐变占位、点卡续播、播完后消失（§4.2 全项）。
7. **开关矩阵（真机）**：B12 关 → 占位；B11 关 → 全原生；两开关重启后保留。
8. **几何（真机）**：轮播 188/180、指示点、名/备注位置；继续行 104×140、16dp 圆角、区 padding；小屏与横屏无溢出（B11 §6.8 同款关注点）。

## Recovery anchor

- 目标：手机端首页块A（轮播自动播放 + 继续观看）迁移到 Flutter，作为 `/home` 族内的 widget，`flutter_b12_enabled` 默认关；本地提交不推送。
- 验收：§4 清单在 CI 联编 + 真机逐项通过；开关关 = B11 占位区。
- 计划状态：B12 实施完成待验证；全量迁移按 `docs/b8-full-migration-plan.md` 顺序继续。
- 当前文件：见 §2.7；分支 `b8-flutter-rewrite`，基线 B11 提交 `7f40d1bf`，工作树待提交。
- 已完成：发现（原生轮播/继续观看 + B11 形态 + 全部 API 核实）→ 护栏 `b8-b12-home-block-a` → 合约 + impl + Dart widgets + 7 个共享文件 → 本文档。
- 未验证：构建、Pigeon 生成、analyze、Gradle 编译、真机（本机无 SDK）。
- 未决风险：Pigeon 新形态 codegen（§6.1）；搭便车时序与 V9 残影窗口（§6.4）；自动播放节律（§6.5）。
- **恰好一个下一步：B13 首页块B（JS 小组件区）。**
