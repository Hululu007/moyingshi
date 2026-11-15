# 海阔小程序第 3 步：列表页

> Recovery anchor（任务 `miniapp-list`，按仓库 AGENTS.md §5）
>
> - 目标：点一条已导入的海阔规则，进它自己的列表页（卡片 + 分类切换 + 翻页），
>   三个入口都接通；本地做完、本地提交，不推、不编、不触发构建。
> - 分支/HEAD：`main` / `9dbd1031`（2026-11-15 09:48 上游同步 beta 3e892186 后）。
> - 计划状态：第 1 步地基（f9f6b234）、第 2 步导入（a2e06441）已在 main 上；
>   本步 = 设计评审（本文档）+ 实现，见下文“实现清单”。
> - 当前文件/符号：
>   `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/` 下 `HkRule` / `HkCard` /
>   `HkStore` / `HkEngine` / `HkParser` / `HkExecutor`（`home(rule, page, classId)` 已有，
>   覆盖页码 + 分类）；`ui/hk/HkMiniappActivity.kt`（管理页）；
>   占位在三处：`HkMiniappActivity.onItemClick`、`LabActivity.onItemClick`（main 源集）、
>   mobile `LocalSourceDialog.onItemClick` / `SiteDialog.onTextClick`，都是弹
>   `hk_miniapp_list_pending`（“小程序列表页还在做”）。
> - 已验证：本地逐行重读 + `git diff --check`；GitHub 构建（编译检查）未做，
>   本次不推不编，按任务要求留待用户说“推”之后。
> - 未验证（需要一次构建 + 真机 + 三条验收规则）：Rhino/列表页在真机上的表现、
>   三条规则的 find_rule 命中率、`fyAll` 替换语义（见“已知缺口”）。
> - 未解决的风险：见下文“已知缺口与风险”。
> - 下一个动作：用户说“推”后推上去走 GitHub 构建，再拿三条验收规则真机验；
>   第 4 步（详情）等列表页验完再做。

## 目标与范围

- 做：新建 `HkListActivity` + 列表 Fragment（kv1 对应物）+ 列表适配器复用
  `VodAdapter` + 两个布局；点规则进它自己的列表页：卡片（标题/海报）、
  分类切换（`class_name`/`class_url`）、翻页（`fypage`）。
- 接通三个入口：实验室“海阔小程序”分组内嵌列表、HkMiniappActivity 管理页、
  本地源弹窗与 SiteDialog 的 hk:// 分支。
- 不做：点卡片进详情/懒加载播放（第 4–5 步；本步点卡片只弹“详情页还在做”，
  不进空白蜘蛛页）；搜索页（第 6 步）；云口令/导出（第 6–7 步）；首页内嵌
  `j1`（第 7 步）；全部 48 种 `col_type`（第 7 步长尾）。
- 不变的行为：a7e97b48（2026-11-15 09:20 落地）——点海阔小程序分组只显示
  内嵌列表、不跳管理页，管理页只走工具栏 +；本步只改“点一条规则”的去向，
  不动分组本身。TV/leanback 不动，BaseLoader 不动，底栏不加项
  （`menu_nav.xml` 仍是 6 项上限）。

## 最佳实践研究（AGENTS.md §7 设计门）

### 证据表

| 证据 | 来源 | 等级 | 支撑的结论 | 适用性/注意 |
|---|---|---|---|---|
| 逆向报告 §2.5 打开与运行 | `~/workspace/user/files/__________8_3azg.md` | 高（反编译源码引用） | `HkSiteAdapter.open()` → 内嵌 `j1` 失败则 `HkListActivity.y1(activity, g22, 标题, false)`；Intent 带 `data`=g22 JSON、`title` | 内嵌 `j1` 是第 7 步；本步只做列表页。`data` 传整条规则 JSON——咱们改传标题、页内再读规则（见“与上游的偏差”） |
| 逆向报告 §2.5 渲染 | 同上 | 高 | `HkListActivity.onCreate` 解析 `data` → `g22`，`setContentView(activity_hk_list)`，底部叠 `CustomWallView`，挂 kv1 到 `R.id.hk_list_container`，`kv1.x1(g22)`、`v1(标题, url)` | 咱们的 `BaseActivity` 已自动在 `setContentView` 时加 `CustomWallView`（`customWall()` 默认 true），布局里不用再加 |
| 逆向报告 §7.13 `activity_hk_list.xml` | 同上 | 高 | 顶栏是 `hk_miniapp_tabs_row`（44dp，默认 GONE）：横向 `hk_rv_miniapp_tabs` + “全部 ▾” pill；下面是 `hk_list_container` | “全部 ▾”是筛选组入口（第 7 步）；本步顶栏只放分类 chips，筛选不做 |
| 逆向报告 §7.10 `f22.k(page, tid)` | 同上 | 高（有源码） | 校验 → `fx1.b` 拼 URL → `ky1.i` 拼页码/分类 → 非 JS：抓页 + `b32.j` 选择器解析；JS：`M1("home", tid, 类名, …)` + 预取正文进 `R` + `c1(find_rule)` | 咱们第 1 步的 `HkExecutor.home(rule, page, classId)` 已按此顺序实现，复用，不重写 |
| 逆向报告 §7.1 `pickCards` / `toVods` | 同上 | 高（有源码） | `pickCards`：先取 `colType == 规则 col_type` 的可用卡片，空则取可用且非动作 URL 的，再空则取全部可用；`toVods`：卡片 → Vod（id=url，name=title，pic，remarks=desc，content=desc，typeName=规则 type），并进 `CACHE` | 直译成本步的 `HkListHelper.pickCards/toVods`；`CACHE` 本步不需要（详情页才按 URL 取卡片），不做 |
| 逆向报告 §2.5 `uy1` / §7.12 | 同上 | 高 | 卡片字段 title/url/pic/desc/sel/colType/extra/…；`usable()` = title+url 非空；动作 URL：`toast://`、`copy://`、`input://`、`confirm://`、`select://`、`download://`、`hiker://empty` | 咱们的 `HkCard` 已按此建模；`isActionUrl` 直译进 `HkListHelper` |
| 逆向报告 §3 `g22.f()` | 同上 | 高 | 分类数组，元素是 `[名字, id]` 字符串对；`home()` 转成 `Class` 列表 | 咱们用现成的 `TypeAdapter` + `Class` bean 渲染分类 tabs，与首页分类同一套 UI |
| 官方规则文档“首页频道链接”说明 3 | https://docs.189.tyrantg.com/docs/hikerview/help_rules.html（2026-11-15 访问） | 高（官方） | “用 `fyAll`，那么分类、年代和地区替换词都会去替换 `fyAll`，有 `fyAll` 就不能有别的替换词” | 第 1 步的 `fyAll` 未替换是已知缺口；本步在 `HkExecutor.buildHomeRequest` 里补上（见“`fyAll` 修复”），否则童话、臻品fix 两条验收规则首页请求会失败 |
| 官方规则文档“首页频道解析规则” | 同上 | 高（官方） | 格式 `列表;标题;图片;描述;链接[;显示样式]`；`&&`/`--`/`,n`/Text/Html/原生 jsoup；`.js:` 后处理；`js:` 整段 JS | 第 1 步 `HkParser` 已实现；本步不改解析器 |
| 社区 skill `hikerview-rule-generator` 的 48+ 种 `col_type` 表 | `Djjywj/openhands-skills`（评估时引用；本次未重新取到——两次 web 搜索只返回无关结果，按 §3 停止追查） | 中（二手） | 常用影视类型约 6–8 种：`movie_1/movie_3/text_1/text_2/text_3` 等 | 本步只认这 6 种 + 默认 fallback；其余 40+ 种是第 7 步长尾。表未重新核对，映射写进“已知缺口” |
| 仓库现有实现：`TypeFragment` + `VodAdapter` + `CustomScroller` + `ProgressLayout` | `app/src/mobile/java/…/ui/fragment/TypeFragment.java` 等 | 高（本地代码） | 分类列表页的现成模板：SwipeRefreshLayout → ProgressLayout → CustomRecyclerView；`CustomScroller` 回调 `onLoadMore(String page)`，`endLoading(Result)`（空页回退一页，`pageCount==0` 时一直允许加载）；`VodAdapter` 按 `Style` 切 rect 网格 / list 行 | 本步 Fragment 照此结构写；`Result` 只用来喂 `endLoading`（list + 默认 pageCount 0） |
| 仓库现有实现：`HkExecutor.home(rule, page, classId)` | `app/src/mobile/java/…/api/hk/HkExecutor.java` | 高（本地代码） | 已支持页码 + 分类（名或下标）；ID 式 / 模板式两种 `class_url`；`[firstPage=N]`、`fypage@…@` 运算、`fyclass/fyarea/fyyear/fysort` | 复用；只加 `fyAll`（见下） |
| 仓库现有实现：第 2 步导入与兜底 | `docs/miniapp-import.md`、`HkMiniappActivity.kt`、`HkStore` | 高（本地代码） | 三个占位点的准确位置与文案；管理页列表来自本地源登记的 hk:// 站源（删条目行即消失）；`HkStore.read(title)` / `isMiniapp(site)` / `titleOf(site)` 已有 | 入口接线只改这三处的 `onItemClick`/`onTextClick` 分支 |

### 方案比较

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 不做 | 保持三处占位弹“列表页还在做” | 零风险 | 用户已导入的规则打不开；第 3 步是用户 2026-11-15 10:27 亲口说“开始做列表页”定的下一步 | 否决 |
| B. 照搬上游 | Activity + kv1 + 全部 48 种 `col_type` + 首页内嵌 `j1` + `CACHE` + “全部 ▾”筛选 pill，`data` 传整条规则 JSON | 与魔影行为最接近 | kv1 无源码、48 种布局无源码（只能按社区二手表猜）；内嵌 `j1` 要动首页 `VodFragment`（核心链路，风险大）；工作量数倍于本步；48 种里大部分是小说/漫画/音乐等非影视类型，与验收无关 | 否决（内嵌与 48 种是第 7 步） |
| C. 窄化适配（推荐） | Activity + Fragment + 复用 `VodAdapter`/`TypeAdapter`/`CustomScroller`/`ProgressLayout`；只认 6 种 `col_type` + 默认 fallback；只传规则标题、页内读规则；分类 chips + 下拉刷新 + 滑到底翻页；点卡片弹“详情页还在做” | 改动集中在 mobile 新文件 + 三处一行接线；复用首页同款列表 UI 与分页机制，风格一致；不碰 `BaseLoader`、不碰首页、不碰 TV；验收三条规则全是 `movie_3`，一次覆盖 | `movie_1` 等映射是窄化外推（见“已知缺口”）；`fyAll` 多筛选拼接语义是外推（单筛选规则可验） | **采用** |
| D. 复用现有首页 `VodFragment` | 把 hk 规则当一条站源塞进 `VodConfig`，首页直接渲染 | 不写新页面 | `VodFragment` 绑定 `Site` + `Spider` 取数；hk 不走 `Spider`（`BaseLoader` 明确不动），要给首页核心加 hk 分支，爆炸面比新页面大；且与魔影“小程序走独立列表页链路”的做法背离 | 否决 |

### 推荐

采用方案 C。理由：验收的三条规则全是 `movie_3` + 分类 + 翻页，窄化适配一次覆盖；
引擎（`HkExecutor.home`）与解析器第 1 步已验证过结构，只差 `fyAll` 一处补丁；
UI 全部复用首页同款组件，不引入新设计语言；回滚 = 删新文件 + 恢复三处占位，
一行接线改动可独立回滚。

## 设计

### 文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `docs/miniapp-list.md` | 新建（本文档） | 设计评审 + 实现记录 + 回滚 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkListHelper.java` | 新建 | `pickCards` / `toVods` / `isActionUrl` / `styleFor`（直译 + 窄化映射） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkListActivity.java` | 新建 | 列表页壳：Toolbar（规则标题 + 返回）+ `FrameLayout`（`@+id/hk_list_container`，挂 Fragment）；`open(Context, String title)` 启动入口；壁纸由 `BaseActivity.setContentView` 自动加 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkListFragment.java` | 新建 | kv1 对应物：分类 chips + 下拉刷新 + 滑到底翻页 + 加载/空/错三态；后台线程调 `HkExecutor.home` |
| `app/src/mobile/res/layout/activity_hk_list.xml` | 新建 | 纵向 `LinearLayoutCompat`：MaterialToolbar + `FrameLayout`（container） |
| `app/src/mobile/res/layout/fragment_hk_list.xml` | 新建 | 纵向根布局：分类 `RecyclerView`（横向，`@+id/classRecycler`，无分类时 GONE）+ `fragment_type.xml` 同款 SwipeRefreshLayout → ProgressLayout → CustomRecyclerView |
| `app/src/mobile/AndroidManifest.xml` | 改 | 注册 `HkListActivity`：intent-filter action `${applicationId}.hk_list` + DEFAULT（供 main 源集的实验室页隐式启动；照 `HkMiniappActivity` 的写法，targetSdk 28，不用写 `exported`）；`parentActivityName=".lab.LabActivity"` |
| `app/src/main/res/values/lab_strings.xml` | 改 | 加 3 条：`hk_list_detail_pending`（详情页还在做）、`hk_list_rule_missing`（规则不存在或已删除）、`hk_list_load_failed`（加载失败：%1$s） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkExecutor.java` | 改 | `buildHomeRequest` 加 `fyAll` 替换（见下） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkMiniappActivity.kt` | 改 | `onItemClick`：hk 站源 → `HkListActivity.open(this, title)`，不再弹占位 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/dialog/LocalSourceDialog.java` | 改 | `onItemClick` hk 分支：开列表页 + `dismiss()`，不再弹占位 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/dialog/SiteDialog.java` | 改 | `onTextClick` hk 分支：开列表页 + `dismiss()`，不再弹占位 |
| `app/src/main/java/com/github/moyingshi/osc/tv/lab/LabActivity.java` | 改 | `onItemClick` hk 分支：隐式 intent（action `${applicationId}.hk_list`，extra `title`）开列表页，不 `finish()`（返回键回实验室，与管理页一致）；分组本身不动 |
| `app/src/main/java/com/github/moyingshi/osc/tv/utils/LocalSource.java` | 改 | 加 `miniappTitle(Site)`：从 api/key 去掉 `hk://` 前缀（`HkStore.titleOf` 的 main 源集版，纯字符串操作） |

### 关键契约

- **启动参数**：只传规则标题（extra `"title"`），不传整条规则 JSON。
  原因：规则 JSON 可达 77KB（阅动漫），标题足够；页内 `HkStore.read(title)`
  读规则，读不到（文件被删）→ 弹 `hk_list_rule_missing` + `finish()`。
  与魔影 `y1(context, g22, title, autoPage)` 的偏差已记录：`autoPage`（自动翻页
  标记）本步不用；`listHost`（内嵌标记）是第 7 步。
- **取数**：Fragment 后台线程调 `HkExecutor.home(rule, page, classId)`，
  classId 传分类名（`classNames()` 的项；`HkExecutor` 按名匹配，不到再按下标）。
  默认分类 = 第一个。`HkExecutor` 返回 `List<HkCard>` → `HkListHelper.pickCards`
  → `toVods` → 主线程进 `VodAdapter`。
- **分页**：照 `TypeFragment` + `CustomScroller`：`onLoadMore(String page)` →
  `loadPage(page, append=true)`；`endLoading(Result)` 用 `Result.setList(vods)`、
  pageCount 默认 0（一直允许加载，空页自动回退一页）。下拉刷新与分类切换都回到
  第 1 页（`mScroller.reset()` + adapter 清空）。
- **防串位**：每次加载取 `loadSeq` 自增序号，后台线程捕获；回主线程时序号已变
  或 Fragment 已 `!isAdded()` 则丢弃结果。
- **`fyAll` 修复**（`HkExecutor.buildHomeRequest`，必须做，否则童话、臻品fix
  首页请求 404）：官方文档“分类、年代和地区替换词都会去替换 `fyAll`，有 `fyAll`
  就不能有别的替换词”。实现：拼 URL 时若含 `fyAll`，用“分类值 + 地区首值 +
  年份首值 + 排序首值”拼接替换（ID 式取分类 ID，模板式取分类名），并跳过
  `fyclass/fyarea/fyyear/fysort` 的单独替换。验收的三条规则都只有分类有值，
  所以 `fyAll` → 分类值可真机验证；多筛选拼接是外推，见“已知缺口”。
  例子：童话 `https://m.tonghuacun.com/list/fyAll-fypage.html` → 第 1 页电影
  `https://m.tonghuacun.com/list/1-1.html`；臻品fix
  `https://www.zpcxb.com/zptop/fyAll/page/fypage.html` → 电影
  `https://www.zpcxb.com/zptop/dianying/page/1.html`；奇优影院♔无 `fyAll`，
  走原路 `fyclass` → `http://www.qiyou03.com/list/2_1.html?order=`（无 sort 值，
  `fysort` 换空）。
- **`col_type` 映射**（`HkListHelper.styleFor`，整页按规则 `col_type` 定一种 `Style`，
  空则默认 rect；卡片自带的 colType 只用于 `pickCards` 筛选，不参与布局）：
  - `movie_3` / `movie_2` → `Style.rect()` 海报网格（验收三条全是 `movie_3`）。
  - `movie_1` → `Style.list()` 单列行（“1 列”的窄化对应：每行一张小图 + 标题/描述）。
  - `text_1` / `text_2` / `text_3` → `Style.list()` 文字行（`VodListHolder`：
    标题 + 描述，无图时占位图）。
  - 其余（含空、未知、`movie_4/5/6`、`pic_*`、`icon_*` 等）→ `Style.rect()` 默认卡片。
  - 不做：`x5_webview_single`（WebView 单页，第 7 步）、`movie_3_marquee`（跑马灯，
    第 7 步）、`rich_text`/`text_5`（详情/选集语义，第 4 步）——这些出现时同样
    落到默认卡片，不崩。
- **卡片点击**：弹 `hk_list_detail_pending`（“详情页还在做”），不进详情、不进
  播放器、不进蜘蛛页。长按：什么都不做（返回 false）。动作 URL 的卡片已被
  `pickCards` 第二遍滤掉，到不了列表。
- **三态**：加载中 `ProgressLayout.showProgress()`；成功 `showContent(first, size)`
  （0 条即空态）；失败 `showError(标题, 原因, 重试, 点击重试)`，重试跑当前页；
  下拉刷新失败只弹 `hk_list_load_failed` toast、不盖掉已有列表。
- **入口接线**（三处都只改 hk 分支，非 hk 行为一行不动）：
  - 管理页 `HkMiniappActivity.onItemClick`：`HkListActivity.open(this, HkStore.titleOf(item))`。
  - 本地源弹窗 `LocalSourceDialog.onItemClick`：同上 + `dismiss()`。
  - 切站弹窗 `SiteDialog.onTextClick`：同上 + `dismiss()`。
  - 实验室 `LabActivity.onItemClick`（main 源集，不能引用 mobile 类）：隐式 intent，
    action = `getPackageName() + ".hk_list"`，extra `"title"` =
    `LocalSource.miniappTitle(item)`；`resolveActivity` 守卫（打不开则弹原占位句，
    不崩）；不 `finish()`。
  - 规则文件被删但站源条目还在：列表页读不到规则 → 弹 `hk_list_rule_missing` + `finish()`。

### 不做的（明确边界）

- 详情/选集/懒加载播放（第 4–5 步）、搜索（第 6 步）、云口令/导出（第 6–7 步）、
  首页内嵌（第 7 步）、48 种 `col_type` 全覆盖（第 7 步）、`hiker://page/` 子页面
  （第 7 步；本步这类卡片点了一样弹“详情页还在做”）、`webRule://`（第 7 步）、
  筛选组（area/year/sort UI；取数时只取各组第一个值，沿用第 1 步的做法）、
  `warmUp` 预热（导入时 ≤20 条本应预热前 5 条，第 2 步已留口；列表页出来后
  可顺手做，但不在本步验收里——先不做，避免扩大范围）。

## 验收标准

代码层面可确认（提交前逐行重读 + `git diff --check`）：

1. 三处占位（管理页、实验室、两个弹窗）都不再弹 `hk_miniapp_list_pending`；
   非 hk 分支行为一行未动。
2. `HkListActivity` 在 mobile 清单里注册（`${applicationId}.hk_list`），
   main 源集只用隐式 intent + 标题字符串，不引用 mobile 类。
3. `fyAll` 替换只影响含 `fyAll` 的 URL；无 `fyAll` 的规则走原路。
4. `menu_nav.xml` 仍是 6 项；`BaseLoader` 未动；TV/leanback 源集零改动；
   无 `Json.safeListElement` 式返回类型误用、无泛型原始类型。

只能真机验（需一次 GitHub 构建 + 用户装新版 + 三条验收规则已导入）：

5. 奇优影院♔：打开首页出卡片（海报+标题）；切“电影”/“电视剧”有内容；
   滑到底翻页能翻（第 2 页不是第 1 页重复）。只测电影/电视剧（它有伦理分类，不测）。
6. 童话：同上（电影/连续剧/综艺/动漫任一切两个）。
7. 臻品fix：同上（JS 路径；电影/电视剧任一切两个）。部分播放源走第三方解析站
   是规则本身写法，不修；本步只验列表，不点卡片进播放。
8. 没导入任何小程序时零回归：本地源、实验室、首页、底栏开关都不受影响；
   三个入口在无规则时不崩（管理页空列表、实验室分组空列表、弹窗无 hk 条目）。
9. 规则文件被删但条目还在：点规则弹“规则不存在或已删除”，不崩。

## 回滚路径

- 整步回滚：`git revert <本提交>`（一个原子提交，recovery tag 见提交信息）。
  新文件（Activity/Fragment/Helper/两个布局/任务文档）随 revert 一起消失；
  三处接线恢复成弹占位句；`fyAll` 补丁随 `HkExecutor` 一起回去——注意：
  回滚后童话、臻品fix 的首页请求会再次带 `fyAll` 原样发出（第 1 步的已知缺口，
  不影响其它功能）。
- 局部回滚：只回滚接线（恢复三处弹占位）而保留列表页代码：三处各改回一行
  `Notify.show(R.string.hk_miniapp_list_pending)` 即可，列表页变成不可达代码，
  下次构建不受影响。

## 已知缺口与风险

- **`fyAll` 多筛选拼接是外推**：官方文档只说“分类、年代和地区替换词都会去
  替换 `fyAll`”，没给拼接顺序/分隔符。本步实现是“分类+地区+年份+排序首值
  直接拼接”；验收规则都只有一个筛选（分类），真机可验证；多筛选规则以后
  拿实例再对。
- **`col_type` 映射是窄化外推**：48 种表未重新取到（两次 web 搜索无果，按 §3
  停止追查）；`movie_1 → list`、`text_* → list` 是按名字含义定的，未经真机
  对照；`movie_4/5/6` 等落到默认 rect 网格，样子对但列数不按规则走。
- **列表为空分不清原因**：规则返回 0 条卡片时，可能是站点真没内容，也可能是
  find_rule 没命中——本步只显示空态，不做“规则命中诊断”（第 4 步详情调试时再补）。
- **无搜索入口**：列表页工具栏本步不放搜索按钮（第 6 步加）；规则没搜到想要的
  只能翻页/切分类。
- **性能**：`HkExecutor.home` 是同步网络 + Rhino，每页都重建引擎；翻页时
  后台线程 + 序号防串位已做，但快速连翻仍会多发请求（`CustomScroller` 的
  `isLoading` 门已挡住重复触发）。
- **规则安全**：同第 1 步——Rhino 在 App 进程里跑规则 JS，只导可信规则。

## 实现清单

- [x] 设计评审（本文档）
- [x] `HkListHelper`（pickCards/toVods/isActionUrl/styleFor）
- [x] `HkListActivity` + `activity_hk_list.xml` + 清单注册
- [x] `HkListFragment` + `fragment_hk_list.xml`（分类 chips + 刷新 + 翻页 + 三态）
- [x] `HkExecutor` 加 `fyAll` 替换
- [x] 三处入口接线（管理页、实验室、两个弹窗）+ `LocalSource.miniappTitle`
- [x] 3 条新字符串（实际加了 5 条：detail_pending / rule_missing / load_failed / error / retry）
- [x] 逐行重读 + `git diff --check` + guard finish（本地提交，不推不编）

实现中纠正的两处（已记入设计）：

- `HkListFragment` 初版里“规则没写 col_type 时按首卡 colType 定样式”写成了
  恒返回 `""` 的桩函数——`Vod` 没有 `setStyle`，卡片 colType 带不过来。
  已删掉这段：样式只按规则 `col_type` 定（空则默认 rect），卡片 colType
  只用于 `pickCards` 筛选。文档“`col_type` 映射”一节已同步改掉。
- `HkListActivity` 初版漏了 `import com.github.moyingshi.osc.tv.R`
 （`R.id.hk_list_container`）；`SiteDialog` 改完后 `Notify`  import 悬空，
  已删。`HkExecutor` 的 `fyAll` 三元表达式按优先级本是对的，但改写成
  if/else 更直白。
- 附注：`docs/miniapp-foundation.md` 里“`fyAll` 未替换”的已知缺口一句现已
  过时（本步已补上），该文件不在本步 scope 内未改，下次动它时顺手删。
