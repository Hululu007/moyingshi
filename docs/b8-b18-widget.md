# B18 小组件浏览（B8 全量迁移）

> §0 执行声明：本批把手机端小组件浏览页（WidgetBrowseActivity，417 行）迁移到 Flutter，路由 `/widgets?widgetId=`（C1 引擎）；同时接管 B13 首页块 B 的“更多”入口（B13 V5 指定的接管点）。
> 任务护栏 `b8-b18-widget-browse`（standard），分支 `b8-flutter-rewrite`，基线 B17 提交 `1da24aca`。
> 开关 `flutter_b18_enabled` 默认关，独立于其他开关；开关 UI 只在实验屏。关 = 浏览页回原生，原生实现一行不删。
> **本机无 Java / Android / Flutter SDK，构建与运行均未验证**（CI 联编 + 真机验收时按 §6 逐项证明）。不推送。

## §1 原生实现（只读发现）

### 1.1 浏览页

原生实现：`app/src/mobile/java/.../ui/activity/WidgetBrowseActivity.java`（417 行，mobile 源集）+ 布局 `app/src/mobile/res/layout/activity_widget_browse.xml`（Toolbar + 模块 chips 横排 + 参数 chips 横排 + 网格）与 `adapter_widget_grid_item.xml`（图片 150dp + 名字 12sp + 备注 12sp，备注空时 GONE，margin 4dp）与 `adapter_widget_chip.xml`（`shape_type_chip`：选中 = 主色实底 + 白字）与菜单 `menu_widget_browse.xml`（只有刷新项）。

- **加载流水线**（`loadWidget()`）：`WidgetManager.findBuiltin(widgetId)`，未知 → `widget_load_failed % "未知组件: "+id`；`Asset.read(script)` 读脚本；`new WidgetHost(widgetId, script)`，`load()`（10s 超时）→ metadata，Toolbar 标题 = metadata 标题；失败 → `showEmpty(widget_load_failed % msg)`；`selectModule(homeModule)`，homeModule 为空 → `showEmpty(widget_no_module)`。
- **分页**（`loadPage(page)`）：page 1 重置 `hasMore=true`，仅当列表为空才显示加载；参数拷贝 + `hasPageParam` 时补 page 参数；`useCache = page==1 && !forceRefresh && !noCache()`；缓存命中（`WidgetCache.read`，按 `paramsKey` + `cacheTtlMs`）→ `parseItems` 非空 → `applyItems(cached, 1, true)`；随后 `host.invoke(fn, params)`（20s + 5s 超时）→ parse 为空 → 抛 "组件返回数据异常"；page 1 且 !noCache → `WidgetCache.write`；`applyItems(items, page, false)`；异常 → loading=false、隐藏加载，page==1 且列表为空 → `showEmpty(widget_load_failed % msg)`；finally `forceRefresh=false`。
- **applyItems**：page 1 清空 + 添加、`page=1`，空且非缓存 → `showEmpty(widget_empty)` 否则隐藏空态；否则追加、`page=page`；`hasMore = !items.isEmpty() && hasPageParam(module)`。
- **模块 chips**：只列非 category 模块，当前高亮；点 → `selectModule`（参数重置为 `WidgetManager.defaultParams`，loadPage(1)）。
- **参数 chips**：只列 `WidgetManager.visibleParams`（跳过 page/constant + belongTo 隐藏），chip 文案 "标题：当前值"（枚举 → 选项标题；字符串 → 值截 8 字 + "…"，空 → 字面 "全部"）；点 → 枚举单选对话框（选 → put 参数、刷新 chips、loadPage(1)）或字符串对话框（EditText 单行、hint=描述，确认 → put 去空格值、刷新 chips、loadPage(1)）。
- **网格**：`GridLayoutManager`，列数 = 宽屏 ? `Product.getColumn` : 3；滚动监听：dy>0、未加载、有更多、module 非空、`hasPageParam` → lastVisible >= count-6 → loadPage(page+1)。
- **点卡**（`onItemClick`）：标题为空忽略，否则 `SearchActivity.start(activity, title)`（即当前站源搜标题；B9 开关开时被 B9 分流，与原生一致）。
- **销毁**（`onDestroy`）：executor `shutdownNow`，`host.destroy()`。

### 1.2 入口

- **分流点（两处）**：`WidgetBrowseActivity.start(Activity, String)` 是唯一 funnel，全文 grep 只有两个调用方：
  - 原生首页 `VodFragment.java:493`（小组件区块头 "更多"）：`WidgetBrowseActivity.start(getActivity(), widgetId)`。
  - `B13HostApiImpl.openWidgetMore`（约 265 行）：经反射调 `start`（mobile-only 类，规划 §4.8 招 4）。
- **分流方式**：`start()` 内 `Setting.isFlutterB18Enabled()` 时拼路由 `/widgets?widgetId=`（URL 编码）调 `C1ScreenActivity.start(activity, route)` 并返回，**不 finish**（原生调用方留在下面，B8 FolderActivity 前例）；关时走原 intent，一行不改。B13 的 `openWidgetMore` 则单独分流（见 §2.1），不能走 `start()`，否则旧 Flutter 首页 Activity 会留在 C1 引擎上，违反单引擎不变式。

### 1.3 字符串与资源归属（按目录分别 grep 验证，无 head 截断）

- 本批用到的 8 个字符串只在 `app/src/main/res/values*`（values + zh-rCN + zh-rTW 各 3 个文件），mobile/leanback res 里一个都没有：`widget_load_failed`、`widget_no_module`、`widget_empty`、`widget_more`、`widget_refresh`、`dialog_negative`、`dialog_positive`、`error_retry`。所以 impl 里直接 `R.string` 引用是 leanback 安全的，无需 `mobileString()`。
- `SearchActivity.start(Activity, String)` 在 mobile 与 leanback 两源集同签名（B2 前例），main 源集 impl 直接引用安全。
- `WidgetManager` / `WidgetHost` / `WidgetItem` / `WidgetMetadata` / `WidgetModule` / `WidgetCache` 全部在 main 源集，直接引用安全；`ResUtil` / `PlayerSetting` 同样 main 源集。mobile-only 的是 `Product.getColumn` 与 `MobileWindow.isWide`（列数规则），按规划 §4.8 招 3 逐字复制到 impl 内（带漂移警告，见 §2.5）。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- `Setting.flutter_b18_enabled`，默认 false，独立开关；开关 UI 只在实验屏（C0 屏新加一行）。
- **入口分流 1**：见 §1.2。路由参数只有 widgetId（URL 编码，B17 同款 `encode` 辅助）。
- **入口分流 2（B13 "更多" 接管，B13 V5）**：`B13HostApiImpl.openWidgetMore` 内，`Setting.isFlutterB18Enabled() && Util.isMobile()` 时拼 `/widgets?widgetId=` 调 `C1ScreenActivity.start(activity, route)` **并 `activity.finish()`**（start-then-finish，B11 V10 同款 G2 引擎复用竞态，见 §6）；关时走原有反射原生路径。B13 其余代码一行不动。

### 2.2 快照与完成规则（本批核心设计）

- `getBrowseShell()` 同步取页面静态文案（重试、刷新、取消、确认），Dart 先渲染页面骨架。空态文案（`widget_empty` / `widget_no_module`）是状态相关的，放在 `B18BrowseScreen.emptyText` 里，不进 shell（shell 只留真正静态的 4 个）。
- `getBrowse(widgetId)` 是 `@async` 一次性快照（B8 形态）：后台单线程跑原生 `loadWidget()` + `loadPage(1)`（§1.1 顺序）；`Completion` 看门狗 35s，超时返回错误快照（`error_play_timeout`）；每个路径恰好完成一次；迟到的后台完成是 no-op。同一 widgetId 且已加载时复用会话（`WidgetHost` + metadata + module + params 不重建）。
- **缓存优先的完成规则（与原生唯一的形态差）**：Pigeon `@async` 只能完成一次，原生 `loadPage(1)` 是一次调用内先缓存后新鲜。规则：page-1 缓存命中时立即完成（`fromCache=true`），Dart 随后调一次 `reloadBrowsePage(false)` 取新鲜；reload 永远等新鲜结果，新鲜失败且有缓存条目时静默完成缓存（与原生一致），无条目时完成错误快照（`widget_load_failed % msg`）。`selectBrowseModule` 同样走缓存优先 + Dart 一次 reload。
- **分页**：`loadMoreBrowsePage(page)` 只走新鲜 invoke（page>1 永不用缓存，与原生一致）；`hasMore = !items.isEmpty() && hasPageParam(module)`。
- **resumed 重取**（规划 §4.6）：Dart 在 `resumed` 时重调 `reloadBrowsePage(false)`（原生无 onResume 重取，见 §5 V3）。无 EventChannel。
- **跨屏导航恒走 host**：点卡是 host 方法（fire-and-forget 同步 void，`SearchActivity.start`）；参数对话框的确认只发 `setBrowseParam` + Dart reload。Flutter→Flutter 的导航只发生在 B13 "更多" 的 start-then-finish（G2，见 §6）。

### 2.3 Pigeon 合约（`flutter_module/pigeons/b18_widget_messages.dart`）

- `B18HostApi`：`isB18Enabled` / `setB18Enabled`；`getBrowseShell()`；`@async getBrowse(String widgetId)`；`@async reloadBrowsePage(bool forceRefresh)`；`@async loadMoreBrowsePage(int page)`；`@async selectBrowseModule(String functionName)`；`void setBrowseParam(String name, String value)`；`void openBrowseItem(String title)`；`void disposeBrowse()`。
- `B18BrowseScreen`：`widgetId` / `title` / `loaded` / `errorText` / `emptyText` + `modules`（`B18ModuleItem`：functionName/title/selected）+ `params`（`B18ParamItem`：name/type/title/**value（原始当前值）**/displayValue/hint/options）+ `items`（`B18BrowseItem`：title/remarks/remarksVisible + PicSpec 字段组）+ `hasMore` / `page` / `fromCache` / `columnCount`。
- **`value` 字段的由来**：`displayValue` 是截断/翻译后的展示文案（字符串截 8 字、枚举是选项标题），Dart 无法用它预选枚举对话框或回填字符串输入框，所以合约另给原始当前值 `value`（host 的 mParams 条目）。
- **扁平数据类**：PicSpec 字段组（url/referer/cookie/user-agent/extraHeadersJson/fallbackUrl/placeholderText/placeholderColor）按 B2/B8 前例在条目类里平铺，不共享类型。Java 侧 int 一律装箱（`Long`，见 C1 教训；`setPage/setColumnCount/setPlaceholderColor` 全部 `(long)` 强转）。

### 2.4 Dart 页（`flutter_module/lib/b18_widget_page.dart`）

- `B18BrowsePage`（`/widgets` 路由，`onGenerateRoute` 分支，B17 同款）：init 时取 shell + `getBrowse`；`fromCache` 时调一次 `reloadBrowsePage(false)` 取新鲜（不再触发第二次，见 §2.2）。
- **形态**：AppBar 标题 + 刷新按钮（tooltip = shell 的 refreshLabel）；模块 chips 横排（`ChoiceChip`，选中 = primary）；参数 chips 横排（`ChoiceChip`，永不选中，文案 "标题：当前值"）；网格 `GridView.builder`（列数 = host 的 `columnCount`，间距 8dp，`mainAxisExtent` 188dp 估算，见 §5 V6）；卡片 = `ClipRRect(16)` 包共享 `PicImage`（150dp 高）+ 名字 12sp + 备注 12sp（`onSurfaceVariant`，各 1 行省略，备注空时隐藏）。
- **参数对话框**：枚举 → `AlertDialog` + `RadioListTile`（当前值 = `param.value` 预选），选 → `setBrowseParam` + reload page 1；字符串 → `AlertDialog` + `TextField`（autofocus、hint=描述、回填 `param.value`），确认 → 去空格值 + reload page 1。字符串对话框是文本输入面，B5 键盘依赖适用（见 §6）。
- **加载/错误/空态**：初始加载转圈；错误且无条目 → 错误文案 + 重试按钮（`getBrowse` 再调一次）；空条目 → `screen.emptyText`；上拉近底（距底 <600px，见 §5 V5）→ `loadMoreBrowsePage(page+1)`，失败 SnackBar、保留列表；下拉刷新 → `reloadBrowsePage(true)`（原生只有菜单刷新，见 §5 V4）。
- **图片**：走共享 `PicImage`（host 的 `ImgUtil.getUrl` 委托，D3）；`data:` 显示占位（D6）。
- **销毁**：`dispose` 时 fire-and-forget 调 `disposeBrowse()`（host 销毁 `WidgetHost` + executor，正在进行的加载完成错误快照）。

### 2.5 Host 实现（`B18HostApiImpl.java`）

- `loadWidget` = 原生逐字抄：`findBuiltin`（未知 → "未知组件: "+id）→ `Asset.read` → `new WidgetHost` → `load()` → metadata → `getHomeModule` → `defaultParams`；`module==null` → `buildScreen` 的空模块态（`emptyText=widget_no_module`）。
- `loadPageInternal(page, forceRefresh, allowCacheComplete, completion)` = 原生 `loadPage` 逐字抄 + §2.2 的完成规则：参数快照（主线程拷贝 `LinkedHashMap`，再提交后台）→ `useCache` 判断 → 缓存命中且 allowCache → 立即完成缓存快照；否则 invoke → parse 为空 → "组件返回数据异常"；page 1 且 !noCache → `WidgetCache.write`；失败且有缓存条目 → 静默完成缓存，否则错误快照。`mLoading` 标志只在主线程置位、`Completion.complete()` 里清除（串行守卫，B11 V9 同款）；`pendingCompletion` 供 `disposeBrowse()` 完成。
- `paramDisplayValue` 逐字抄原生：枚举 → 选中选项标题；字符串 → 截 8 字 + "…"；空 → 字面 "全部"。`visibleParams` 直接调 `WidgetManager.visibleParams(module, params)`。
- `resolvePic` / `splitHeaders` 是 C2/B2/B13 同款复制（`ImgUtil.getUrl` 委托 + `TmdbProxy.nextAutoImageUrl` 回退，D3；`data:` 占位，D6）。`browseColumn()` 复制 mobile `Product.getColumn` + `MobileWindow.isWide`（宽屏 ? `abs(PlayerSetting.getSize() - (7 + isPad?1:0))` : 3；`ResUtil` / `PlayerSetting` 是 main 源集，直接引用）——**漂移警告**：mobile 改列数规则时本复制必须同步改（C2/B2 的同款警告，见 `docs/b8-b2-keep.md` §2.6）。
- 全部 8 个字符串直接 `R.string` 引用（§1.3 已验证 main-res）；`SearchActivity.start` 双 flavor 同签名，直接引用；`Util.isMobile()` 只在 B13 分流处用（main 源集）。

### 2.6 文件清单

| 文件 | 动作 |
|---|---|
| `flutter_module/pigeons/b18_widget_messages.dart` | 新（B18HostApi + B18BrowseShell/Screen/ModuleItem/ParamItem/ParamOption/BrowseItem） |
| `flutter_module/lib/b18_widget_page.dart` | 新（浏览页：chips + 参数对话框 + 分页网格） |
| `flutter_module/lib/main.dart` | 改（`/widgets` 路由分支 + B18 开关；不动已有路由） |
| `app/src/main/java/.../flutter/B18HostApiImpl.java` | 新 |
| `app/src/main/java/.../flutter/C1ScreenActivity.java` | 改（注册 B18HostApi、加 `ROUTE_WIDGETS`） |
| `app/src/main/java/.../flutter/C0ShellActivity.java` | 改（C0 引擎上注册 B18HostApi，只为实验屏开关用） |
| `app/src/main/java/.../setting/Setting.java` | 改（`flutter_b18_enabled`，默认 false） |
| `app/src/main/java/.../flutter/B13HostApiImpl.java` | 改（**只改 `openWidgetMore` 一处**：B18 开关开时走 `/widgets` + finish，关时走原有反射原生路径；其余不动） |
| `app/src/mobile/java/.../ui/activity/WidgetBrowseActivity.java` | 改（`start()` 内唯一分流点 + `encode` 辅助；原生逻辑一行不删） |
| `.gitignore` | 改（加 `B18Messages.java`） |
| `.github/workflows/b8-c0.yml` | 改（Pigeon 步骤加 b18 生成 + 存在性检查，B17 同款） |
| `docs/b8-b18-widget.md` | 新（本文档） |

## §3 状态来源清单（全部 host 快照，Dart 不计算）

| 显示项 | 来源（host） | 备注 |
|---|---|---|
| 页面标题 | `WidgetMetadata.getTitle()` | 加载后才有；加载前为空 |
| 模块 chips | `metadata.getModules()`（跳过 category）+ 当前 module | 顺序 = 脚本定义顺序 |
| 参数 chips | `WidgetManager.visibleParams(module, params)` + `paramDisplayValue` | "标题：当前值"，host 算好 |
| 参数对话框 | `B18ParamItem.value`（原始值）+ `options` | 枚举预选 / 字符串回填 |
| 浏览条目 | `WidgetHost.invoke` → `parseItems` → `resolvePic` | PicSpec 字段组 |
| 列数 | `browseColumn()`（`Product.getColumn` 复制） | 宽屏 ? 规则 : 3 |
| 有更多 | `hasMore = !items.isEmpty() && hasPageParam(module)` | 与原生一致 |
| 空态文案 | `screen.emptyText`（`widget_empty` / `widget_no_module`） | 状态相关，不进 shell |
| 全部静态文案 | `getBrowseShell()` | 重试、刷新、取消、确认 |

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 浏览列表

- [ ] 首页小组件区块点 "更多" → 开关开时进 Flutter 浏览页（标题 = 组件标题），关时进原生页；其他入口（若有）同样分流。
- [ ] 列表内容、顺序、图片、名字、备注与原生逐项比对；备注为空的行不占备注行。
- [ ] 下拉到底 → 自动加载下一页；页尾无更多时不再请求；加载中底部有转圈。
- [ ] 工具栏刷新按钮 → 强制刷新第 1 页（缓存不命中）；下拉刷新同样。
- [ ] 第 1 页加载失败且无条目 → 错误文案 + 重试按钮；重试后恢复。
- [ ] 组件无首页模块时 → 显示 `widget_no_module` 文案；未知组件 id 时 → `widget_load_failed` 文案。

### 4.2 模块与参数

- [ ] 模块 chips：只出现非 category 模块，当前模块高亮；点其他模块 → 参数重置、列表换成该模块第 1 页。
- [ ] 参数 chips：文案 "标题：当前值" 与原生一致（枚举是选项标题、字符串截 8 字 + "…"、空值是 "全部"）；只出现可见参数（page/constant/belongTo 隐藏的不出现）。
- [ ] 点枚举参数 → 单选对话框，当前选项已预选；选新值 → 对话框关闭、chip 文案更新、列表刷新。
- [ ] 点字符串参数 → 输入对话框，hint 是参数描述、已填当前值；确认 → chip 文案更新、列表刷新；取消 → 不变。
- [ ] 键盘：字符串对话框里输入、确认、收起键盘表现正常（B5 依赖项）。

### 4.3 点卡

- [ ] 点卡片 → 进当前站源的标题搜索（B9 开关开时进 Flutter 搜索输入页，否则原生搜索页）；标题为空的卡片点按无反应。
- [ ] 从搜索页返回浏览页，浏览页状态还在（resumed 重取）。

### 4.4 B13 "更多" 接管

- [ ] 首页小组件区块（B13 渲染的区块）点 "更多" → 开关开时进 Flutter 浏览页，**且返回后回到原生首页**（旧 Flutter 首页 Activity 已 finish，不出现双首页）；关时进原生浏览页。
- [ ] B13 区块本身（轮播/卡片/点卡）不受影响。

### 4.5 开关与回滚

- [ ] 实验屏关掉 B18 开关 → 浏览入口回到原生页；再打开 → 回到 Flutter 页。
- [ ] TV 端完全不受影响（WidgetBrowseActivity 是 mobile-only，leanback 无此页）。

## §5 相对规划的全部偏差

- **V1 缓存优先要多一次往返**：原生 `loadPage(1)` 一次调用内先缓存后新鲜；Pigeon `@async` 只能完成一次，所以 Dart 收到缓存快照后另调一次 `reloadBrowsePage` 取新鲜。首屏多一次往返；新鲜失败且有缓存时静默保留列表（与原生一致）。
- **V2 刷新失败多一个 SnackBar**：原生 page-1 失败且有列表时静默保留；Dart 在同样情况弹 SnackBar 显示失败文案（B8 前例）。
- **V3 resumed 重取是新增行为**：原生 `WidgetBrowseActivity` 无 onResume 重取；Dart 按规划 §4.6 在 resumed 时重调 `reloadBrowsePage(false)`。返回浏览页时会多一次请求（缓存命中时便宜）。
- **V4 下拉刷新是新增入口**：原生只有菜单刷新按钮；Dart 另加 `RefreshIndicator`，走同一强制刷新路径。
- **V5 上拉阈值是近似**：原生 lastVisible >= count-6；Dart 用距底 <600px 触发，阈值不逐字相等。
- **V6 网格卡片高是估算**：原生条目高由内容自然决定（150dp 图 + 4dp 间距 + 名字 + 备注）；Dart 用固定 `mainAxisExtent` 188dp。名字/备注换行或字体缩放时可能与原生有 1 行高度差。
- **V7 chips 形态近似**：原生是 `shape_type_chip`（深色半透明底 + 白字，选中主色实底）；Dart 用 `ChoiceChip`，颜色与圆角不逐字相等；chips 行高固定 48dp（原生由 RecyclerView 内容决定）。
- **V8 枚举对话框形态近似**：原生是单选列表对话框；Dart 用 `AlertDialog` + `RadioListTile`，选项顺序与选中规则与原生一致，样式不逐字相等。
- **V9 字符串对话框是新文本输入面**：B5 之后第二个文本输入面；键盘/IME 行为留待设备验收（见 §6），若异常按规划 §4.5 在 B9 处决策。
- **V10 G2 竞态只在 B13 "更多"**：`openWidgetMore` 是 start-then-finish（B11 V10 同款）；`WidgetBrowseActivity.start()` 的分流不 finish，无此风险。
- **V11 图片无磁盘缓存**：与 C2 相同，`Image.network` 只有内存缓存，滑动返回会重新加载。
- **V12 无模块态无工具栏标题外的空态图**：原生 `showEmpty` 只显示文案；Dart 同样只显示文案（`screen.emptyText`），一致。
- **V13 失败态多一个重试按钮**：原生 page-1 失败且无条目时只显示 `widget_load_failed % msg` 文案，无重试按钮；Dart 在文案下方加重试按钮（B8 前例）。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon 代码生成（最担心）**：新形态——`@async` 返回含 3 个 `List<>`（模块 + 参数 + 条目）的同一快照 + `B18ParamItem` 含嵌套 `List<B18ParamOption>`；`B18HostApiImpl` 手写实现与生成接口的逐方法匹配（`Long` 参数、`Builder` 字段名、`Result<B18BrowseScreen>`）。CI 是唯一证明。
2. **会话生命周期与看门狗（最担心）**：`WidgetHost.load()` 10s 超时 + `invoke` 20s+5s 超时 vs 35s 看门狗的竞合；缓存优先时两次完成（缓存 + reload）的顺序；页面 dispose 时 `disposeBrowse()` 与在途加载的竞合（`pendingCompletion` 是否总能完成、不悬空 Result）。CI 编译证明不了，设备验收时看首屏与快速进出。
3. **B13 "更多" 的 G2 竞态**：start-then-finish 时新 Activity 是否复用旧引擎/路由、旧 Activity 的 onDestroy 是否销毁引擎（B7/B11 同款风险）；若复现，`C1ScreenActivity` 需要跨批的 relaunch 机制。
4. **字符串参数对话框的键盘/IME**：嵌入式 FlutterFragment 里的 `TextField`（autofocus、确认、收起）在真机上的表现；B5 的键盘待证明项若异常，本批同受影响。
5. **图片一致性**：`posterUrlWithHeaders` 的 `@Headers=` 解析 + `ImgUtil.getUrl` 委托 + `TmdbProxy` 回退在真机上的表现；`data:` 占位；Douban 图片 referer。
6. **网格几何**：188dp 估算高与 chips 行高在真机上的观感；列数规则（宽屏 `Product.getColumn` 复制）在横屏/平板上的表现。
7. **leanback 编译**：全部 8 个字符串 main-res；`SearchActivity.start` 双 flavor 同签名；`WidgetManager` / `WidgetHost` / `WidgetCache` 全部 main 源集；`Product.getColumn` / `MobileWindow.isWide` 只以复制形式出现、未直接引用。CI 联编时 leanback flavor 是证明。

## Recovery anchor

- **目标**：B18 把手机端小组件浏览页（WidgetBrowseActivity，417 行）迁移到 Flutter，路由 `/widgets`（C1 引擎），开关 `flutter_b18_enabled` 默认关；同时接管 B13 的“更多”入口。
- **状态**：本地实现完成，未推送。基线 B17 提交 `1da24aca`；分支 `b8-flutter-rewrite`；工作树待提交（见 `git status`）。
- **已完成**：只读发现（§1）；Pigeon 合约 + Dart 页（chips + 参数对话框 + 分页网格）+ `B18HostApiImpl`（`loadWidget` / `loadPage` / `paramDisplayValue` 逐字复制）+ main.dart 路由/开关 + C1/C0 注册 + Setting 开关 + `WidgetBrowseActivity.start()` 分流点 + `B13HostApiImpl.openWidgetMore` 接管 + `.gitignore` / `b8-c0.yml` + 本文档。验证：`git diff --check`（finish 时复核）、作用域 diff 复核、main 仍为 `f39a6e0`。
- **未验证**：全部构建与运行（本机无 SDK）；§6 的 7 项留给 CI 联编 + 真机验收。
- **下一步（恰好一个）**：B19 插件管理（`PluginManagerActivity`，1040 行）——按 `docs/b8-full-migration-plan.md` §3 顺序，起新任务护栏 `b8-b19-plugin-manager`，新合约 `b19_plugin_messages.dart`，开关 `flutter_b19_enabled` 默认关，不推送。
