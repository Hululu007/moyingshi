# B8 B8：分类列表页迁移到 Flutter

## §0 执行声明

- 任务护栏：`b8-b8-category`（standard），分支 `b8-flutter-rewrite`，基线 `e5f906ff`（未用导入修复提交）。
- 只在该分支上工作，main 不动（仍为 `f39a6e0`），不推送（Wei 2026-11-17 10:44 指示：做完先不编，等别的弄好了一起推）。
- C0/C1/C2/B2/B3/B4/B5/B6/B7 合约与实现冻结；共享组件（VodCard/PicImage/SettingsRow）只加法，不改；共享文件只做加法行 + `FolderActivity` 一处分流点。
- 本批是第一个联网屏；不碰播放器、不碰 Room/schema、不碰电视端、不改 `android-release.yml`、不引入新 Dart 依赖。
- 本机无 Java/Android/Flutter/Dart SDK：构建、Pigeon 代码生成、运行均未验证，全部留给 CI + Wei 设备验收（见 §6）。

## §1 原生实现（只读发现）

### 1.1 三个原生文件（mobile 源集）

- `TypeFragment`（240 行）：列表结构、筛选、分页、点按/长按。
- `FolderFragment`（125 行）：文件夹下钻（child back stack）、委托给子 TypeFragment。
- `FolderActivity`（93 行）：独立入口，只取 `Result.types[0]`，标题 = 该分类名。
- leanback 另有自己的 `TypeFragment`（440 行）+ `FolderFragment`（231 行），电视端不动。

### 1.2 列表结构

- 样式：文件夹模式 → `Style.list()`；否则 `site.getStyle(class style)`（站点自有样式优先，其次分类样式，最后 `rect`）；每页结果的首项样式可覆盖（`TypeFragment.addVideo` 里 `result.getVod().getStyle(getStyle())`）。
- 三种卡片（`VodAdapter` 按样式分发）：rect 网格（`adapter_vod_rect.xml`）、list 行（`adapter_vod_list.xml`，40dp 缩略图 + 名 14sp + 备注 12sp）、oval 格（`adapter_vod_oval.xml`，圆形图 + 名 14sp）。
- 网格几何：`Product.getColumn/getSpec`（列数、cutout、oval 额外间距）；`Product` 与 `MobileWindow` 都是 mobile-only，main 源集的 impl 只能复制算式（规划 §4.8）。
- 分页（`CustomScroller`）：page 从 1 起；`loadMore` 请求 page+1、回调 true 才进位；空结果 page--；hasMore = page < pageCount || pageCount == 0（pageCount 0 = 未知，继续加载）。
- 数据源：`SiteViewModel.categoryContent(key, tid, page, true, mExtends)` → `Result` LiveData；`TIMEOUT_VOD` 30 秒；失败 → `Result.error(msg)`，否则 `Result.empty()`；`Result.getMsg()` 非空即错误。

### 1.3 筛选

- `Class.filters`：每组单选，点已选项取消（`ValueAdapter`：全组 `setSelected(value)`，已选则反选）；初始值 `FolderFragment.getExtend()` 从 `filter.init` 取；`TypeFragment.setFilter`：选中 put、取消 remove，然后刷新。
- 独立入口（`FolderActivity`）原生**没有筛选 UI**；筛选 UI 只在首页 `TypeFilterDialog`（底部面板：上半分类 chips、下半筛选组，各 40% 屏高 cap）。

### 1.4 点按/长按（`TypeFragment.onItemClick`）

- action 卡 → `ActionCardHelper.handleAction(activity, key, action)`。
- 文件夹卡 → `getParent().openFolder(item.getId(), mExtends)`（同屏下钻，复用父级 style + extend）。
- index 站（`site.isIndex()`）→ 搜索（历史恢复时 `HistoryResumeCoordinator.openSearch`，否则 `SearchActivity.start(name)`）。
- 历史恢复 → `item.setSite(getSite())` + `HistoryResumeCoordinator.openSelected(...)`；`isHistoryResume()` = cid ≥ 0 且 targetCid ≥ 0 且 key 非空。
- 其余 → `VideoActivity.start(activity, key, id, name, pic, isFolder ? name : null)`。
- 长按：action/folder → 无反应；否则 `SearchActivity.start(name)`。

### 1.5 入口（只改分流处）

- `FolderActivity.start` 两个调用点：`VideoActivity.java:2966`（详情页类型 span）、`CollectFragment.java:474-475`（搜索结果文件夹项，历史恢复用 6 参重载）。
- 首页 ViewPager 里的 `FolderFragment` 嵌入不属于 B8（归 B11 首页壳）。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- `flutter_b8_enabled` 默认 false，开关 UI 只在实验屏；`FolderActivity.start`（6 参 funnel，唯一分流点）开时走 `C1ScreenActivity` 的 `/category` 路由，关时走原生；任一编码异常 → 回落原生。
- 路由带参：`/category?key=&typeId=&type=&hrCid=&hrKey=&hrTargetCid=`；`type` = URL 编码的 Class JSON（`Result.types[0]`），因为 `Site` 没有类型查找，(key, typeId) 拿不到 Class；hr 参数恒带（默认 -1 / "" / -1）。

### 2.2 Pigeon 合约（`flutter_module/pigeons/b8_category_messages.dart`，248 行）

- `B8HostApi`：`isB8Enabled` / `setB8Enabled`；`@async B8CategoryScreen getCategoryPage(key, typeJson, typeId, extendJson, page)`；`openCategoryItem`（10 个扁平参数）；`searchCategoryItem`。
- `B8CategoryScreen`（18 字段）：标题 / 样式 / 几何 / 筛选组 / 当页 items / hasMore / pageCount / nextPage / extendJson 回显 / 空态 / 错误 / 重试 / 筛选标题；`errorMessage` 为空即无错误，错误时 items 为空、hasMore 为 false，但筛选组与 extendJson 仍在（加载更多失败可保留列表重试）。
- `nextPage` 语义：空页 → 同页（`CustomScroller.endLoading` 的 page--），否则 +1；`hasMore` = page < pageCount || pageCount == 0。
- `B8CategoryItem`：PicSpec 图片字段形 + `rawPic`（原始 vodPic，见 §2.3）+ 四个可见性 flag + isFolder/isAction/action。

### 2.3 Dart 页（`flutter_module/lib/b8_category_page.dart`，799 行）

- `B8CategoryPage` 壳：自有 Navigator（族内下钻）+ PopScope（先退钻取栈，再 `closeScreen`）。
- `main.dart` 新增 `onGenerateRoute` 处理 `/category?...`（首次使用——B3 的字面路由表无法表达无界参数；非 `/category` 返回 null，现有字面路由零改动）。
- `_B8CategoryListView`（一页一级）：下拉刷新（`RefreshIndicator`）；滚动到底部 200dp 内加载更多（hasMore 且未在加载中；内容不足一屏时不自动补页，与原生一致，见 §6.4）；resumed 时仅当前可见级重取第 1 页；加载串行（host 单订阅，第二个调用直接丢弃）。
- 筛选 chips：固定在列表上方，每组一名 + Wrap chips，单选可取消（点已选项清除该组），40% 屏高 cap + 自身滚动；加载中禁用；筛选状态只在 extendJson 里，选中态由 host 快照回标。
- 三种形态：rect → 共享 `VodCard`（信息区恒两行，备注行按 remarkVisible 显示/透明）；list → 页内行（40dp 圆角缩略图 + 名 14sp + 备注 12sp，8dp 边距）；oval → 页内格（`ClipOval` + 名 14sp，extent = 图高 + 24）。
- 点卡：folder → 族内 push 下钻（复用父 typeJson，typeId = 文件夹 id，标题 = 文件夹名）；其余走 host `openCategoryItem`；长按（非 folder、非 action）走 host `searchCategoryItem`；点详情传 `rawPic`（详情页自己用 ImgUtil 解析 @Headers 后缀，传干净 URL 会丢鉴权头）。
- 错误态：页 1 失败 → 全屏错误视图（host 标题/消息/重试）；加载更多失败 → SnackBar + 保留列表与分页状态，可滚重试；channel 断裂 → B2 同款诊断行。

### 2.4 Host 实现（`B8HostApiImpl.java`，531 行）

- `getCategoryPage`：`Class.objectFrom`（裸 Gson 解析，try/catch 包住）→ extend（空串用 init，否则解析 Dart 的 JSON；`"{}"` 明确表示空，不重算 init）→ 筛选快照（选中态由 extend 标）→ base style（folder → list；否则 `site.getStyle(class style)`）→ 一次性 `SiteViewModel` 订阅（`init()` 清旧值 → `observeForever` → `categoryContent` → 首个非空值完成 + 全路径 `removeObserver`；35s 超时守卫，`TIMEOUT_VOD` 30s + 5s）→ `buildScreen`（msg 非空 → 错误快照；样式取首项覆盖；几何按 Product 复制；hasMore/nextPage 按 CustomScroller）。
- `openCategoryItem`：`TypeFragment.onItemClick` 逐条复刻（action / index 站 / 历史恢复 / 详情；folder 只作防御性回落详情，因为 Dart 必拦截）。
- `searchCategoryItem`：长按搜索（历史恢复时 `openSearch`）。
- 图片：`resolvePic` / `splitHeaders` 从 B2 逐字复制（`ImgUtil.getUrl` 委托 + TmdbProxy 回退 + 首字占位；data: 占位）；几何：`categoryColumn` / `categorySpec` 按 `Product.getColumn/getSpec` 全量复制（B2 的 `keepColumn` 是收藏专用的“3 列 unless wide”，不适用）；`isWide` / `windowBounds` / `getCutout` 同 C2/B2 的复制。
- 全部文案 main-res：`widget_empty`、`error_config_load`、`error_retry`、`error_play_timeout`、`home_filter_title`（均有 zh-rCN 翻译）；无 `mobileString` 需求。

### 2.5 mobile-only 类与资源（规划 §4.8）

- `Product` / `MobileWindow`（mobile-only）：纯计算复制（§2.4）。
- `VideoActivity` / `SearchActivity`：双 flavor 同签名直接引用（B2 已验证的例外）。
- `SyncDialog`：本批不用。
- 资源：逐目录分别 grep（main / leanback / mobile），无 head 截断；B8 用的 5 个字符串全部 main-res。

### 2.6 文件清单

| 文件 | 变化 |
|---|---|
| `flutter_module/pigeons/b8_category_messages.dart`（新） | 248 行：B8HostApi + 4 个扁平数据类 |
| `flutter_module/lib/b8_category_page.dart`（新） | 799 行：族壳 + 列表页 + 筛选区 + 空态/错误态 + list 行 + oval 格 |
| `app/src/main/java/.../flutter/B8HostApiImpl.java`（新） | 531 行 |
| `flutter_module/lib/main.dart` | +57：`/category` 的 `onGenerateRoute` + B8 开关（只加法） |
| `app/src/main/java/.../flutter/C1ScreenActivity.java` | +9/−1：`ROUTE_CATEGORY` + 注册 B8HostApi（−1 行是 javadoc 换行） |
| `app/src/main/java/.../flutter/C0ShellActivity.java` | +4：C0 引擎注册 B8HostApi（只为开关） |
| `app/src/main/java/.../setting/Setting.java` | +9：`flutter_b8_enabled`，默认 false |
| `app/src/mobile/java/.../ui/activity/FolderActivity.java` | +26：`start` 唯一分流点（只改分流处） |
| `.gitignore` | +1：`B8Messages.java` |
| `.github/workflows/b8-c0.yml` | +3：Pigeon 步骤加 b8 生成 + 存在性检查（B6 同款） |
| `docs/b8-b8-category.md`（新） | 本文档 |

## §3 状态来源清单（全部 host 快照，Dart 不计算）

- 标题、样式、列数、卡片尺寸、筛选组与选中态、hasMore/pageCount/nextPage、extendJson 回显、空态/错误/重试文案：`getCategoryPage` 快照。
- 卡片字段：id/name/year/remarks/siteName + 四个可见性 + isFolder/isAction/action + PicSpec + rawPic。
- Dart 本地状态只有：当前页 items 累积、extendJson 持有、加载标志、滚动位置、下钻栈。

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 列表与分页

- 实验屏开 B8 后，从详情页类型入口进 Flutter 分类页：标题 = 分类名；首屏内容与原生一致（卡片、备注、站点角标）。
- 下拉刷新重载第 1 页；滚到底加载更多，页码连续无重复/无漏；末页后不再请求。
- 空分类显示空态标题；加载失败显示 host 错误 + 重试按钮，点重试重载。

### 4.2 筛选

- 有筛选的分类页上方出现筛选组（组名 + chips）；点选项后列表刷新为筛选结果；再点已选项取消该组筛选；多组可连选。
- 无筛选的分类（如搜索结果文件夹）不出现筛选区。

### 4.3 下钻

- 点文件夹卡进下级分类（标题 = 文件夹名，样式/筛选沿用父级）；系统返回/左上返回逐级回退，最后一级返回退出 Flutter 页。
- 下钻后下拉刷新与加载更多仍正常；进程被杀后只剩根级（族内深度不恢复，与规划一致）。

### 4.4 点按与长按

- 点普通卡进详情页；点 action 卡走 action；index 站点卡点按进搜索；长按普通卡进搜索（名）；长按文件夹/action 卡无反应。
- （历史恢复入口需特定历史记录，验收时如无该场景可跳过。）

### 4.5 图片与卡片形态

- 矩形卡：图 + 站点角标 + 名 + 备注，与原生网格一致；年份角标不显示（见 §7 V5）。
- 列表样式（文件夹内）：40dp 圆角缩略图 + 名 + 备注行。
- 圆形样式：圆形图 + 名。
- 坏图/无图显示首字占位；data: 图占位。

### 4.6 开关与回滚

- 开关关掉后，详情页类型入口与搜索文件夹入口立刻回原生；其它批开关不受影响。

## §5 回滚

- 开关级：实验屏关掉 B8，分类页回原生；全部开关关 = 全原生，零行为变化。
- 批次级：本批一个提交，整批 revert 即可（共享文件均为加法行 + `FolderActivity` 一处分流点）。
- 分支级：丢弃 `b8-flutter-rewrite` 分支；main 从未动过。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon 代码生成**：嵌套 List（`B8FilterGroup` 内含 `List<B8FilterValue>`）+ `@async` 五参 + 18 字段快照——B2 只证明了单层 List 与 `@async` 自定义返回；嵌套列表与新形状只能由 CI 的 Pigeon 生成 + Gradle 编译证明（C1 run #9/#10 的教训：先对 CI 日志逐字核对方法名与装箱类型）。
2. **一次性 SiteViewModel 订阅**：`init()` 清旧值 + `observeForever` + `categoryContent` 的时序（旧值、taskId 守卫、cleared 守卫、35s 超时）——CI 编译证明不了运行时序；设备验收需实测：首屏加载、切换筛选、下拉刷新、连续翻页、弱网超时（35s 后出错误态且可重试、无卡死）。
3. **分页语义**：hasMore（page < pageCount || pageCount == 0）与 nextPage（空页同页）只在真机多页分类上可验证；特别注意 pageCount == 0 的站点（未知总页）是否会反复重试同一空页。
4. **短列表加载更多**：内容不足一屏时无法滚到底，原生同样无触发点；验收对照原生：短列表在原生是否也只能看到第一页（若原生有补页机制，Flutter 需补）。
5. **三种卡片形态**：rect/list/oval 需分别找对应样式的分类实测（oval 较少见——圆形头像类站点；若验收时找不到 oval 分类，该形态记为未验证）。
6. **图片一致性**：@ 后缀头、Douban 图、Tmdb 回退、首字占位色——与 C2/B2 同款逻辑，但首次用于分类列表的大批量图片与滚动回滚（无磁盘缓存，滚回顶部是否闪烁/重载）。
7. **文件夹下钻**：多级下钻（文件夹套文件夹）+ 返回栈；下钻页的筛选是否沿用父级（与 `FolderFragment.openFolder` 一致）。
8. **历史恢复入口**：`CollectFragment` 的历史恢复路径（`openSearch` / `openSelected`）需特定历史记录才能触发；无该场景时记为未验证。
9. **年份角标**：原生仅当 item 无 site 且年份 ≥ 4 字符时显示；分类结果项一般带 site，验收时对照原生确认该角标在原生是否同样不出现。

## §7 相对规划的全部偏差

- **V1** 路由机制：`main.dart` 新增 `onGenerateRoute` 处理 `/category?...`——B3 的字面路由表无法表达无界参数（key/typeId/Class JSON/历史恢复参数）；非 `/category` 路由返回 null，现有字面路由零改动。
- **V2** Class 随路由走：`Site` 无类型查找，(key, typeId) 拿不到 Class；`FolderActivity.start` 把 `types[0]` 的 JSON 以 URL 编码放进路由 query（约 1–5KB，远低于 Intent 1MB 限）；进程死亡后路由仍在 Intent 里，可恢复根级（下钻深度不恢复，规划 §4.4 已接受）。
- **V3** 分页状态分工：简报要求“分页状态由 host 持有”，实际实现为——hasMore/pageCount/nextPage 的判定在 host（按 `CustomScroller`），页码计数器在 Dart（host impl 是单例、跨下钻页共享，无法按页持有状态；路由亦不带页码）。行为与原生一致。
- **V4** 筛选 UI 是新增：独立分类页原生没有筛选 UI（只有首页 `TypeFilterDialog` 有）；本批按规划默认加了 Dart chips（组名 + Wrap chips，40% 屏高 cap），选项全部来自 host 快照。
- **V5** 年份角标不渲染：`VodCard` 无年份槽位；原生仅当 item 无 site 且年份 ≥ 4 字符时显示角标，分类结果项一般带 site（角标本来就是 GONE）；year/yearVisible 仍在快照里，留待以后。
- **V6** 矩形卡信息区恒两行：`VodCard` 的 `mainAxisExtent` 要求全网统一；备注行按 remarkVisible 显示/透明（C2 历史卡同款做法），备注为空的卡会多一行空白——与原生矩形卡（备注盖在图底、名在图下）形态不同，但与 B2 收藏卡一致。
- **V7** resumed 重取：原生 `TypeFragment` 不在 onResume 重取；本批按规划 §4.6 全屏统一 resumed 重取第 1 页（从详情页返回后列表刷新，属行为变化，验收时注意）。
- **V8** 列表/oval 行是页内写：`VodCard` 只覆盖矩形卡；list 行与 oval 格按 `adapter_vod_list.xml` / `adapter_vod_oval.xml` 在 `b8_category_page.dart` 内写，未改 `VodCard`；oval 格高度 = 图高 + 24（名 14sp + 4dp 间隙）为估算值，待真机核对。
- **V9** 空页 hasMore：pageCount == 0（未知总页）且空页时，hasMore 仍为 true、nextPage 同页——与原生 `CustomScroller` 一致；用户反复滚到底会重试同一页（原生同款行为），见 §6.3。
- **V10** `openCategoryItem` 的 folder 防御分支：Dart 必拦截文件夹点按，host 收到 folder 项时回落为详情页（mark = 文件夹名）而非静默无反应；正常流程永不触发。

## Recovery anchor

- 目标：手机端分类列表页（独立入口）迁移到 Flutter，作为 `/category` 路由，`flutter_b8_enabled` 默认关；第一个联网屏。
- 状态：本地实现完成（1 个提交，护栏 `b8-b8-category`），未推未编；main 仍为 `f39a6e0`。
- 未验证：Pigeon 生成 + Gradle 编译 + 一次性订阅时序 + 分页/筛选/下钻/三种卡片（§6 全部），留给联合 CI 构建 + Wei 设备验收。
- **恰好一个下一步：B9 搜索输入页（按 `docs/b8-full-migration-plan.md` §3 顺序）。**
