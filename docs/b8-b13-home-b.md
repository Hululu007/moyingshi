# B13 首页块B（B8 全量迁移）

> §0 执行声明：本批把手机端首页块B（JS 小组件区：番剧 / 豆瓣 / 蜜柑 / TMDB 四行）迁移到 Flutter，作为 `/home` 族内的 widget（不是独立页）。
> 任务护栏 `b8-b13-home-block-b`（standard），分支 `b8-flutter-rewrite`，基线 B12 提交 `e7f03986`。
> 开关 `flutter_b13_enabled` 默认关，独立于其他开关；开关 UI 只在实验屏。关 = JS 小组件区回 B11 占位区，原生实现一行不删；B11 开关关时首页整体回原生，B13 开关不起作用。
> **本机无 Java / Android / Flutter SDK，构建与运行均未验证**（CI 联编 + 真机验收时按 §6 逐项证明）。不推送。

## §1 原生实现（只读发现）

原生实现：`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/VodFragment.java`（1210 行）里的 JS 小组件区 + main 源集的 `widget/` 包 + 两个 mobile-only 布局。

### 1.1 入口与刷新时机（VodFragment 461–541 行）

- **开关**：`Setting.isHomeWidget()` 关 → `refreshWidgets()` 直接返回，区容器 GONE，整区隐藏。
- **刷新点**：`setAdapter()`（homeContent 回包时，560 行）与 `onResume()`（1011 行）调 `refreshWidgets()`；另有 `addWidgetSection`（486–507）、`removeWidgetSection`（509–515）、`widgetInsertIndex`（517–527）、`updateWidgetVisibility`（529–535）。
- **可见判据**：`updateWidgetVisibility`：≥1 个 section，且非 hk 内嵌、非原生 chrome 隐藏、Web 首页不可见。Flutter `/home` 内 hk/WebHome 已回落原生（B11），所以 Dart 侧只需“≥1 section + 开关开”这一条。
- **行顺序**：按 `WidgetManager.builtinOrder` 固定（Bangumi → Douban → Mikan → TMDB），不是按加载完成顺序（`widgetInsertIndex`）。

### 1.2 数据来源与 JS 执行（全部 host 侧）

- **加载器**：`app/src/main/java/com/github/moyingshi/osc/tv/widget/WidgetManager.java`（main 源集，可直接引用）。`loadHome(Listener)` 在 4 线程池上并行加载 4 个内置组件：`builtin.bangumi`（`widgets/bangumi.js`）、`builtin.douban`（`widgets/douban.js`）、`builtin.mikan`（`widgets/mikan.js`）、`builtin.tmdb`（`widgets/tmdb.js`）。
- **JS 引擎**：`WidgetHost`（main 源集）：每个脚本一个 `QuickJSContext`，加载 `js/lib/widget_host.js` 垫片 + `WidgetBridge`，执行组件脚本，读全局 `WidgetMetadata`；`invoke()` 按名调用模块函数（白名单 `SAFE_PATH`），Promise 由 `Async.run` 处理。超时：load 10s、invoke 20s。**Dart 不引入任何 JS 引擎、不执行脚本，只渲染 host 算好的快照。**
- **投递语义**（以源码为准；发现阶段曾误记 Listener 回调带 `fromCache` 参数，实际是 `Section` 的公开字段）：`Listener.onSection(Section)` / `onSectionFailed(String widgetId)`，回调都在主线程（`App.post`）。每个组件：先贴缓存（`WidgetCache.read`，除非模块 `noCache()`），再调 JS 函数取新鲜数据（15 分钟会话节流 `SESSION_THROTTLE_MS`：成功刷新过就不再发请求，只重贴缓存）；新鲜结果为空且无缓存 → `onSectionFailed`；抛错且已贴缓存 → 无失败回调，该行停留在缓存。单个组件失败/超时只隐藏该行，不影响其他行。
- **Section**：公开静态类，字段 `metadata` / `module` / `items` / `fromCache`；`title()` = `metadata.getTitle() + " · " + module.getTitle()`；组件 id 取 `metadata.getId()`。
- **条目**：`WidgetItem.getTitle()` / `getDisplayRemark()`（显式 remark，否则 `"评分 · 年份"`：rating `%.1f` + releaseDate 前 4 位）；海报 `WidgetManager.posterUrlWithHeaders(item)`（posterUrl 优先，TMDB 相对路径拼 `TmdbConfig` image base + `TmdbProxy.imageUrl`；`customHeaders` 如豆瓣 Referer 以 `@Headers=<json>@` 后缀透传，由 `ImgUtil.getUrl` 解析）。
- **点按**：条目 → `onWidgetItemClick`（538–541）：在当前站搜该标题，`SearchActivity.start(activity, item.getTitle())`（B9 开关开时走 Flutter 搜索输入页，与原生一致）；区头“更多” → `WidgetBrowseActivity.start(activity, widgetId)`（mobile-only 类，main 源集无任何引用；B18 迁移该页）。

### 1.3 布局形态

- **区头**（`adapter_widget_section.xml`）：区上下 padding 各 4dp；标题 16sp 粗体、marginStart 16dp；“更多” `textAppearanceBodySmall`（12sp）+ `colorPrimary`、padding 4dp、marginEnd 16dp；列表 paddingTop 8dp、两侧 16dp。
- **卡片**（`adapter_widget_item.xml`，`WidgetSectionAdapter` 80 行，mobile-only）：宽 104dp、四边 margin 4dp；图 104×140dp、圆角 `Vod.Grid.Large`（16dp）；名 12sp 单行（marginTop 4dp）、备注 12sp（`onSurfaceVariant`）单行；备注空时 GONE。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- `Setting.flutter_b13_enabled`（key `flutter_b13_enabled`，默认 false）；开关 UI 只在实验屏（`main.dart` 的 B13 SwitchListTile，B12 同款写法）；`C0ShellActivity` 的 C0 引擎上注册 `B13HostApi` 只为开关用；`C1ScreenActivity` 的 C1 引擎注册同一 impl。
- 不建独立路由：块走 `/home` 族内 widget，`B11HomePage._buildHome` 在 B12 两块下方挂 `B13WidgetsWidget(epoch: _homeLoadEpoch)`，替换 B11 的 B13 占位行（指定接管点）。接管后 B11 的 `_buildBlockPlaceholder` 无人引用，按 B12 前例删掉（−19 行）。

### 2.2 快照完成规则（本批核心设计）

`WidgetManager.loadHome` 是增量投递（缓存先到、新鲜后到），没有“全部完成”回调。`getWidgets()` 的完成规则：

- 4 个内置组件各有首个结果（section 或失败）后：若全部已是终态（新鲜 section 或失败）→ 立即完成；否则起 2s grace 计时，期间陆续到的新鲜结果覆盖缓存，grace 到时按现有最好结果完成；grace 内全部转终态则提前完成。
- 35s 看门狗：任何路径（某组件挂起、JS 超时）都按现有最好结果完成；回调恰好完成一次。
- 串行守卫：已有加载在途时再调 → 立即 error（B11 V9 同款），Dart 保留旧区，下一 epoch 重试。
- C0 引擎守卫与开关守卫：非 `C1ScreenActivity` 或 `isHomeWidget()` 关 → 立即返回空 section 列表（Dart 隐藏整区），不启动加载。

### 2.3 Pigeon 合约（`flutter_module/pigeons/b13_home_b_messages.dart`）

- `B13HostApi`：`isB13Enabled` / `setB13Enabled`（同步 bool）；`@async B13WidgetScreen getWidgets()`；`openWidgetItem(String title)`；`openWidgetMore(String widgetId)`；`disposeWidgets()`（同步 void）。
- 扁平数据类：`B13WidgetScreen{sections}` → `B13WidgetSection{widgetId, title, moreLabel, items}` → `B13WidgetItem{title, remarks, remarksVisible, PicSpec 字段组}`。PicSpec 字段组按规划 §4.2 逐合约重声明，不共享类型；Java 侧 bool/int/double 一律装箱，方法名与 Dart 定义字符级一致。

### 2.4 Dart widget（`flutter_module/lib/b13_home_block_b.dart`）

- `B13WidgetsWidget(epoch)`：`initState` 读开关 + 加载；`didUpdateWidget` 在 epoch 变化时重载（复用 B11/B12 的壳快照节律：resume / 站点切换时重载，Tab 切换不重载）；`dispose` 时 fire-and-forget 调 `disposeWidgets()`，让 host 销毁 WidgetManager 的线程池。
- 渲染：开关关或快照未到 → B11 同款图标占位（`Icons.widgets_outlined`，96，`_B13BlockPlaceholder`，B12 同款拷贝）；sections 为空 → `SizedBox.shrink()`（原生 GONE）；否则逐 section 渲染：16sp 粗体标题 + 右侧 colorPrimary 12sp “更多”（点按 → `openWidgetMore(widgetId)`），横向 ListView（高 184，见 V4），卡片 104 宽、图 104×140 圆角 16（共享 `PicImage`）、名 12sp、备注 12sp `onSurfaceVariant`（`remarksVisible` 时才渲染），点按 → `openWidgetItem(title)`。出错时保留旧区，不出错误视图。
- `main.dart` 只加开关（`_b13Api` 字段、`_b13Enabled`、`_loadB13Flag` / `_setB13Flag`、SwitchListTile），**不 import `b13_home_block_b.dart`**（未用 import 规则；该页只在 B11 内引用）。

### 2.5 Host 实现（`B13HostApiImpl.java`）

- 懒建 `WidgetManager`（无参构造，`new WidgetManager()`，与 VodFragment 同款）；`pendingSections` 按 widgetId 累积（同组件缓存后新鲜覆盖），`pendingFinal` 记终态；完成时按 `builtinOrder` 稳定排序后成快照。
- `toWidgetItem`：标题、备注（`getDisplayRemark()` host 算）、图片走 `WidgetManager.posterUrlWithHeaders(item)` + `resolvePic`（C2/B2/B12 同款拷贝：委托 `ImgUtil.getUrl`，fallback `TmdbProxy.nextAutoImageUrl`，`data:` 显示占位）。
- `openWidgetItem`：空标题忽略，否则 `SearchActivity.start(activity, title)`（双源集同签名，直接引用，B2 前例）。
- `openWidgetMore`：反射开 `WidgetBrowseActivity.start(Activity, String)`（mobile-only，规划 §4.8 招 4；失败吞掉）。
- `disposeWidgets()`：销毁 manager（线程池 `shutdownNow`）并把在途的 `getWidgets()` 按现有最好结果完成，不留悬空 Result。

### 2.6 文件清单

| 文件 | 变化 |
|---|---|
| `flutter_module/pigeons/b13_home_b_messages.dart` | 新：B13HostApi + 3 个扁平数据类 |
| `flutter_module/lib/b13_home_block_b.dart` | 新：B13WidgetsWidget + 占位拷贝 |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B13HostApiImpl.java` | 新 |
| `flutter_module/lib/b11_home_page.dart` | 改：+import、接管行换 widget、删死方法 `_buildBlockPlaceholder`（−19 行） |
| `flutter_module/lib/main.dart` | 改：+pigeon import、+`_b13Api` 字段、+`_b13Enabled`、+initState 一行、+`_loadB13Flag` / `_setB13Flag`、+SwitchListTile |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java` | 改：注册 B13HostApi（+3 行） |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java` | 改：注册 B13HostApi（+3 行） |
| `app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java` | 改：+`isFlutterB13Enabled` / `putFlutterB13Enabled` |
| `.gitignore` | 改：+`B13Messages.java` |
| `.github/workflows/b8-c0.yml` | 改：Pigeon 步骤 +b13 生成与存在性检查（B12 同款） |
| `docs/b8-b13-home-b.md` | 新：本文档 |

## §3 状态来源清单（全部 host 快照，Dart 不计算）

| Dart 侧用的值 | Host 来源 |
|---|---|
| 区开关 | `Setting.isHomeWidget()`（关 → 空快照，整区隐藏） |
| 4 行顺序 | `WidgetManager.builtinOrder`（Bangumi → Douban → Mikan → TMDB） |
| 区标题 | `Section.title()`（`metadata 标题 · 模块标题`） |
| “更多”文案 | `R.string.widget_more`（main res，按目录核查过） |
| 卡片标题 / 备注 | `WidgetItem.getTitle()` / `getDisplayRemark()` |
| 卡片图片 | `WidgetManager.posterUrlWithHeaders(item)` → `ImgUtil.getUrl` 解析（D3） |
| 点按去向 | 条目 → `SearchActivity.start(title)`；更多 → 反射 `WidgetBrowseActivity.start(widgetId)` |

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 小组件渲染

- [ ] B11 + B13 开关都开，进首页：番剧 / 豆瓣 / 蜜柑 / TMDB 四行按该顺序出现（或按原生同条件下的行数出现），标题形如“番剧 · xxx”。
- [ ] 每行卡片：图 104×140 圆角、名 12sp、备注 12sp（无备注的行不留空行位——除 V4 的固定高度外），横向可滑。
- [ ] 图片与原生一致（豆瓣图带 Referer 正常出图；TMDB 相对路径拼 image base 正常出图；`data:` 图显示文字占位）。
- [ ] 无数据时整区隐藏（不是空白占位）；原生 `isHomeWidget()` 关时整区隐藏。

### 4.2 刷新

- [ ] 从播放页 / 其他屏返回首页（resume），小组件区刷新；切换站点后刷新；切换首页 Tab 不刷新（轮播不回第 0 页，小组件不闪）。
- [ ] 热启动（有缓存）：区先出缓存内容，2 秒内新鲜内容替换上来（V3 的 grace 行为可感知但不闪白）。
- [ ] 冷启动（无缓存）：区在 35s 内出现，或一直无数据时隐藏，不卡首页其他块。

### 4.3 点按

- [ ] 点卡片 → 在当前站搜该标题（B9 开关开时进 Flutter 搜索页，关时进原生搜索页，与原生一致）。
- [ ] 点“更多” → 进对应组件的浏览页（原生 `WidgetBrowseActivity`，B18 前仍是原生页）。

### 4.4 开关与回滚

- [ ] 只关 B13：区回 B11 图标占位，首页其他块不受影响。
- [ ] 只关 B11：首页整体回原生，B13 开关不起作用。
- [ ] 全部开关关：首页行为与迁移前一致。

## §5 相对规划的全部偏差

- **V1（完成规则，本批核心设计）**：规划 §4.6 只规定“@async 一次性快照、35s 看门狗、无 EventChannel”，未规定增量投递如何收口。本批定为：4 组件首结果齐 + 2s 新鲜 grace（全部终态提前完成）+ 35s 看门狗。见 §2.2。
- **V2（15 分钟节流按引擎重置）**：`WidgetManager` 的 `lastFreshAt` 是实例状态；本批 impl 每个引擎一个 manager（与 B12 的每 impl 一份同款），所以每次进 `/home`（新引擎）节流都重置，15 分钟内重进首页会重新调 JS。原生 fragment 级 manager 在一次首页会话内节流。后果只是多几次后台 JS 调用（缓存先贴，界面不卡）；不采用静态共享 manager（跨引擎生命周期风险）。验收时观察重进首页是否重复拉取。
- **V3（整区一次性出现，原生是逐行出现）**：原生每行到货即上屏；Flutter 等快照完成才整区出现，热启动最多晚 2s（grace）。轮播 / 继续观看是独立 widget，不受影响。
- **V4（列表固定高 184dp）**：与 B12 V8 同款估算。原生 `wrap_content`：备注全空的行会矮一些；Dart 固定 184，备注空的行底部留白。名 + 备注双行时高度与原生一致。
- **V5（“更多”仍走原生浏览页）**：`WidgetBrowseActivity` 是 mobile-only，host 反射打开；B18 迁移该页后此处改走 host 的 B18 入口。
- **V6（dispose 时销毁 manager）**：原生 fragment 从不销毁自己的 manager（随 fragment 活）；本批 widget dispose 时销毁线程池并完成在途快照。挂载期间行为一致，离开首页后更干净。
- **V7（发现纠正）**：发现阶段摘要称 `Listener.onSection` 带 `fromCache` 参数，实际以源码为准：回调是 `onSection(Section)`，`fromCache` 是 `Section` 的公开字段；组件 id 取 `section.metadata.getId()`（无 `widgetId()` 访问器）。实现与文档均按源码写。
- **V8（无其他偏差）**：开关默认关、只实验屏有 UI、TV 不动、无 schema 变化、无新 Dart 依赖、JS 100% host 侧、图片走共享 `PicImage` + `ImgUtil.getUrl` 委托，全部按规划。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon 代码生成（CI 唯一可证）**：`@async B13WidgetScreen getWidgets()` 是两层嵌套 List（`List<B13WidgetSection>` 内含 `List<B13WidgetItem>`）——本仓库首次在同一合约里两层嵌套；另有同步 void `disposeWidgets()`。CI 若报生成/编译错，按文件路径归属本批修。
2. **快照完成时序（设备验收）**：热启动 grace 行为（缓存先出、2s 内新鲜替换）、某组件挂起时的 35s 看门狗、15 分钟内重进首页的重复拉取（V2）。若 grace 导致首屏区迟迟不出现，缩短或去掉 grace（缓存即完成）。
3. **反射存活（R8/minify）**：`openWidgetMore` 的 `WidgetBrowseActivity` 反射调用在 release 包里是否存活（ProGuard keep 存在，但未验证）；若失效，点“更多”无反应，需改走 §4.8 其他招。
4. **G2 跨屏竞态**：`openWidgetItem` 在 B9 开关开时进 Flutter 搜索页，是又一处 Flutter→Flutter start-then-finish（B11 V10 同款风险）；若复现路由错乱，需 C1ScreenActivity 的重进机制（跨批修，不在本批）。
5. **图片一致性**：四行图片与原生逐行比对（豆瓣 Referer、TMDB 相对路径、失败 fallback、占位色）。

## Recovery anchor

- 目标：B13 首页块B（JS 小组件区）迁移到 Flutter，作为 /home 族内 widget；JS 100% host 侧，开关默认关，不推送。
- 状态：本地实现完成（护栏 `b8-b13-home-block-b`），待 `finish` 提交 + 恢复标签；main 仍为 `f39a6e0`；工作树待验证干净。
- 文件：新 4 个（合约、Dart widget、Host impl、本文档），改 7 个（b11 页接管、main.dart 开关、两 Activity 注册、Setting 开关、.gitignore、b8-c0.yml）。
- 未验证：构建与运行（本机无 SDK），按 §6 逐项留给 CI 联编 + Wei 真机验收。
- 下一步：B14 追更迁移实施。
