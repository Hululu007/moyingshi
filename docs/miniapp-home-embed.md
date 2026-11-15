# 海阔小程序第 7 步之五：首页内嵌 j1

- 任务：miniapp-home-embed（standard lane；10 步串行工程的第 5 步）
- HEAD：d3f4b9f7（main，本地超前 origin 四笔，未推）；工作区干净后开工
- 完成句：当前站是小程序（api/key 以 `hk://` 开头）时首页优先用 `HkExecutor.home()` 的卡片渲染内嵌展示（复用 `HkListFragment` 的卡片样式与分页），内嵌失败时自动回落开 `HkListActivity`；点卡片进 `HkDetailActivity`、点搜索进 `HkSearchActivity`；普通站源首页行为一点不动；只改手机端，本地提交，不推、不编、不触发构建、不动版本记录空间。
- 时间：2026-11-15 18:36 CST 开工；估计 1.5 小时，预期 20:10 CST 前完成并提交。
- 最便宜的决定性验证：逐行重读改动 + `git diff --check`（本地无 JDK/Android SDK，编译检查等推上去之后的 GitHub 构建；真机验收等用户）。

## Recovery anchor

- 目标与验收：见“验收标准”一节；真机验不了的项已在验收表里标明。
- 计划状态：任务文档已写；实现已完成——改 `VodFragment`（hk 渲染分支 + 回落 + 搜索路由 + 可见性/事件守卫）、`HkListFragment`（内嵌模式 + `EmbedHost` 回调）、`fragment_vod.xml`（加 `hkContainer`）。
- 已完成证据：逐行重读改动（`git diff` 全量读过；复核修 1 处：回落时清掉上一个站的分类/横幅残影，`new ArrayList<Vod>()` 显式泛型）；`git diff --check` 通过；改动只在 guard scope 的 4 个文件内；TV/leanback、`BaseLoader`、`menu_nav.xml` 未动；无 `Json.safeListElement` 式返回类型误用、无泛型原始类型、无新增 dialog。
- 未验证的改动：全部（本地无 JDK/Android SDK，编译检查等推上去之后的 GitHub 构建；真机验收等用户）。
- 未解决风险：见“已知缺口与风险”。
- 下一步：`bash .codex/scripts/task_guard.sh finish --verified <证据> --commit-message <消息>`，随后 `git tag -a` 建 recovery tag。

## 1. 证据表

| 证据 | 位置 | 结论 |
|---|---|---|
| 内嵌语义 `HkSiteAdapter.open()` → `HomeActivity.j1(site)` 成功则首页显示，失败则 `HkListActivity.y1(...)` | 逆向报告 `~/workspace/user/files/__________8_3azg.md` §2.5 / §7.1（L1016：`activity instanceof HomeActivity && ((HomeActivity) activity).j1(site)`） | 魔影是“先试内嵌、失败再开列表页”；本步照此语义，不照搬 `j1` 本体（无源码） |
| 首页内嵌范围 | `~/workspace/miniapp/ASSESSMENT.md`（“首页内嵌 `j1(site)`”行） | 魔影先试内嵌失败再开列表页；墨影视首页是 `VodFragment` 渲染当前站源，内嵌等于给 `VodFragment` 加一条 hk 渲染分支；MVP 明确不做，放完整版 |
| 列表渲染复用对象 | `docs/miniapp-list.md` + `HkListFragment.java` | `HkListFragment`（kv1 对应物）已有分类 chips + 下拉刷新 + 滑到底翻页 + 加载/空/错三态，卡片走 `HkExecutor.homeDetailed` → `HkListHelper.pickCards/toVods` → `VodAdapter`；点卡片已进 `HkDetailActivity`（第 4 步）；内嵌复用它，不另起一套 |
| 首页取数链路 | `docs/miniapp-subpage.md` + `HkExecutor.java` | 内嵌首页同样走 `HkExecutor.home` 链路（含第 3 步的子页面/require 展开、第 4 步云口令分流后的规则读取）；不另写取数 |
| 首页结构 | `VodFragment.java`（mobile 源集） | 首页唯一入口是 `loadHome()`（`RefreshEvent.HOME` → `loadHome()`；`HomeActivity` 初次加载也发 `RefreshEvent.home()`）；`homeContent()` → `SiteViewModel.homeContent()` → `setAdapter(Result)` 填分类 tabs + `FolderFragment` 分页；`getHome()` = `VodConfig.get().getHome()` |
| 判定 | `HkStore.isMiniapp(site)` / `titleOf(site)` | api 或 key 以 `hk://` 开头即小程序；标题 = 去掉 `hk://` 前缀；与 `i94.w(site)` 判定一致 |
| 搜索入口 | `HkSearchActivity.open(context, ruleTitle)`（第 1 步） | 只传规则标题；无搜索规则时进页显示空态、不崩；内嵌首页搜索条直接复用 |
| 规则缺失既有行为 | `HkListFragment.initView` + `HkListActivity` | 规则文件被删但站源条目还在：列表页读不到规则 → 弹 `hk_list_rule_missing`（“规则不存在或已删除”）+ `finish()`；内嵌时不能 `finish()` 掉 `HomeActivity`，须改走回落 |
| 普通站首页 | `VodFragment` + `menu_nav.xml`（6 项） | hk 分支只在当前站是 hk 时触发；切站、换分类、首页组件（B5）等走原链路；底栏不加项 |

## 2. 方案比较

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 不做 | 小程序设为当前站后首页仍走普通 `SiteViewModel`/`Spider` 链路 | 零风险 | hk 站源不走 `Spider`（`BaseLoader` 不动），首页取数失败/空白；与“先试内嵌、失败再开列表页”的魔影语义背离；本步任务就是做内嵌 | 否决 |
| B. 照搬上游（魔影 `j1`） | 按报告复刻 `HomeActivity.j1(site)` 内嵌逻辑 | 与魔影行为最接近 | `j1` 本体无源码（报告只给了 `open()` 里的调用点）；魔影首页结构与 `VodFragment`（ViewPager + FolderFragment + B1/B2/B5）不同，硬搬要重构首页核心 | 否决（无源码可搬，只能按语义窄化适配） |
| C. 窄化适配（推荐） | `VodFragment.loadHome()` 开头加 hk 分支：规则存在 → 把 `HkListFragment`（内嵌模式）挂进新增的 `hkContainer`，隐藏 pager/分类栏/横幅/继续观看/组件区/FAB；规则缺失或首页取数失败 → 回落开 `HkListActivity`（一次防重入）；搜索条/菜单搜索 → `HkSearchActivity`；其余链路一行不动 | 改动集中在 mobile 3 个文件 + 1 个布局；卡片渲染、分页、分类、详情、搜索全部复用第 1/3/4 步现成链路，不另起一套；回落语义与魔影 `open()` 一致；普通站源零触碰 | `HkListFragment` 需加内嵌模式（失败不 `finish()`、改为通知宿主）；内嵌时首页横幅/继续观看/组件区不展示（hk 规则没有首页推荐数据，见缺口） | **采用** |
| D. 把 hk 规则塞进 `VodConfig` 当普通站源 | 首页直接用现有 `FolderFragment` 渲染 | 不写新分支 | `FolderFragment` 走 `Spider` 取数，hk 不走 `Spider`；要给首页核心加蜘蛛之外的取数分支，爆炸面比新分支大；且与“小程序走独立列表页链路”的做法背离 | 否决（第 3 步文档已否决过同款思路） |

子问题“内嵌失败如何回落”的比较：

| 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 内嵌页自己显示错误态 + 重试（不回落） | 简单，不跳页面 | 首页停在报错页，违反任务“不要让首页停在空白或报错页” | 否决 |
| 失败即回落开 `HkListActivity`，无防重入 | 语义最贴魔影 | 列表页同样失败 → 返回首页 → 再次自动打开 → 死循环 | 否决 |
| 失败回落 + 每标题一次防重入（推荐） | 符合任务要求；回首页后第二次失败留在内嵌错误态，可点重试，不循环 | 回落只生效一次，之后用户需手动点重试 | 采用 |

## 3. 设计

### 文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `docs/miniapp-home-embed.md` | 新建（本文档） | 设计评审 + 实现记录 + 回滚 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/VodFragment.java` | 改 | hk 渲染分支 + 回落 + 搜索路由 + 可见性守卫 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkListFragment.java` | 改 | 内嵌模式（`newInstance(title, embedded)` + `EmbedHost` 回调） |
| `app/src/mobile/res/layout/fragment_vod.xml` | 改 | 加 `FrameLayout`（`@+id/hkContainer`，默认 GONE，与 pager 同款 `appbar_scrolling_view_behavior`） |

### 关键契约

- **触发点**：`loadHome()`（首页唯一加载入口）。开头 `setTitle()` 后：`if (tryEmbedHkHome(home)) return;`，其余走原路（网页首页 / `showNativeContent() + homeContent()`）。`homeContent()` 开头加 hk 守卫（菜单刷新、`openVodHome()`、空态重试都调它）：hk 站时转回 `loadHome()`，不进 `SiteViewModel`。
- **判定**：`isHkHome()` = `HkStore.isMiniapp(getHome())`；标题 = `HkStore.titleOf(getHome())`。
- **内嵌**：规则存在（`HkStore.read(title) != null`）→ 隐藏进度条/网页首页/横幅/继续观看/组件区/分类栏（`typeBar`）/pager/FAB，显示 `hkContainer`，`getChildFragmentManager()` 挂 `HkListFragment.newInstance(title, true)`（tag `hk_embed`；同标题已挂则不重复挂）。`mHkEmbedded=true`，`mHkFallbackOpened` 只在标题变化时重置。
- **回落**：三路都走 `VodFragment.fallbackToList(title)`（返回 boolean，宿主已接管为 true）：
  1. 规则缺失（`HkStore.read(title) == null`）：直接回落，不挂内嵌 Fragment（否则它的 `finish()` 会关掉 `HomeActivity`）。
  2. 内嵌 Fragment 读不到规则（竞态：校验与挂载之间文件被删）：`HkListFragment` 内嵌模式不 `finish()`，调 `EmbedHost.onHkEmbedUnavailable(title)`。
  3. 首页取数失败（首屏 `vods == null`，即请求/解析异常）：内嵌模式不显示错误态，调 `EmbedHost.onHkEmbedFailed(title, error)`。
  - `fallbackToList`：`mHkFallbackOpened` 已 true → 返回 false（Fragment 自己显示错误态，可点重试，不循环）；否则置 true、拆掉内嵌 Fragment、收起进度条、回到原生内容态、`HkListActivity.open(requireActivity(), title)`、返回 true。规则缺失时列表页自己弹 `hk_list_rule_missing` 并 `finish()` 回首页，首页此时是原生内容态（空分类），不空白、不崩。
- **切回普通站**：`loadHome()` 非 hk 路径开头调 `hideHkEmbed()`（拆掉子 Fragment、隐藏容器、`mHkEmbedded=false`）；`setAdapter(Result)` 开头、`showNativeContent()` 的调用方、`onStateEvent(ERROR)` 都加 `mHkEmbedded` 守卫，防止迟到的普通站结果或状态事件把内嵌盖掉/把 pager 重新顶出来。
- **搜索**：搜索条点击/长按、工具栏搜索项（`menu_vod` 的 `R.id.search` 与 `setSearchLongClick` 的长按）统一走 `onSearchClick()`：hk 站 → `HkSearchActivity.open(requireActivity(), title)`（无搜索规则时搜索页自己显示空态、不崩），否则原 `SearchActivity` 行为。搜索条右侧历史 + ⋮ 不动。
- **点卡片**：`HkListFragment.onItemClick` 已是 `HkDetailActivity.open(...)`（第 4 步），内嵌模式不改，一并复用。卡片点播/懒加载播放走第 5 步链路，不动。
- **不展示**：内嵌时首页横幅（B1，数据来自普通站首页推荐）、继续观看（B2，读历史，与规则无关但属于普通站首页区）、首页组件区（B5）、分类栏（`typeBar`，内嵌 Fragment 自带分类 chips）、右下角 FAB（browse/link/top）全部隐藏；`updateBannerVisibility` / `updateContinueVisibility` / `updateWidgetVisibility` / `setFabVisible` 加 `!mHkEmbedded` 守卫（`onResume` 的 `refreshContinue()/refreshWidgets()` 也会经过这些守卫，不会把隐藏区顶出来）。
- **事件守卫**：`onRefreshEvent` 的 `SIZE` / `CATEGORY` 分支：hk 站时转 `loadHome()`（原 `homeContent()` / `getFragment().onRefresh()` 在内嵌态下无 pager 页可刷）；`HOME` 分支已是 `loadHome()`，不动。`onWebError()`：hk 站时转 `loadHome()`。`canBack()`：内嵌态下 pager 为空，原逻辑返回 true（允许返回），不动。
- **实验室/管理页不动**：实验室“海阔小程序”分组与管理页的点开行为不变（仍进列表页/管理页）；内嵌只影响“把小程序设为当前站后首页长什么样”。`SiteDialog` 仍过滤 hk 条目（025212ce），切站回普通站源后 `loadHome()` 走原路、首页恢复正常。

### `HkListFragment` 内嵌模式

- `newInstance(String title, boolean embedded)`（旧单参版保留，`embedded=false`，三处既有入口行为不变）。
- `EmbedHost` 接口（父 Fragment 实现）：`boolean onHkEmbedUnavailable(String title)` / `boolean onHkEmbedFailed(String title, String error)`；返回 true = 宿主已接管，false = Fragment 自己显示错误态。
- `initView`：规则读不到时，非内嵌走原路（弹 `hk_list_rule_missing` + `finish()`）；内嵌时先问宿主，宿主未接管则在自己的 `ProgressLayout` 显示错误态（`hk_list_error` / `hk_list_rule_missing` 文案 + `hk_list_retry` 重试，重试重读规则）。
- `onPageLoaded`：首屏失败（`vods == null && !append`）且内嵌时先问宿主 `onHkEmbedFailed`，宿主未接管才显示原错误态；翻页失败（`append`）仍只弹 toast + 回退页码，与列表页一致，不回落（用户已在浏览中途，整页跳走更糟）。

## 4. 验收标准

代码层面可确认（提交前逐行重读 + `git diff --check`）：

1. hk 分支只在 `HkStore.isMiniapp(getHome())` 为真时触发；其余站源 `loadHome()` / `homeContent()` / `setAdapter` / 搜索 / 切站 / 换分类 / B5 组件区一行行为未变。
2. `menu_nav.xml` 仍是 6 项；`BaseLoader` 未动；TV/leanback 源集零改动；实验室分组与管理页点开行为未动。
3. 无 `Json.safeListElement` 式返回类型误用、无泛型原始类型、无新增 dialog（无窗口背景问题）。
4. 回落无死循环：`mHkFallbackOpened` 每标题只回落一次；第二次失败留在内嵌错误态，可点重试。

只能真机验（需一次 GitHub 构建 + 用户装新版 + 三条验收规则已导入并设为当前站）：

5. 奇优影院♔：设为当前站后首页内嵌出卡片（海报+标题）；分类可切（电影/电视剧）；滑到底翻页正常；点卡片进详情页；点搜索条进搜索页。
6. 童话：同上（电影/连续剧任一切两个）。
7. 臻品fix：同上（JS 路径；电影/电视剧任一切两个）。
8. 规则文件被删但站源条目还在：首页不空白、不崩、回落开列表页（列表页再报“规则不存在或已删除”）。
9. 普通站源首页零回归；切站回普通站源后首页恢复正常（横幅/继续观看/分类/pager 回来）。
10. 阅动漫（`.hkzip` 框架型规则，第 3 步已能展开子页面）：首页内嵌出卡片为加分项，不承诺全通（它还要远程拉规则列表，在线与否看真机）。

## 5. 回滚路径

- 整步回滚：`git revert <本提交>`（一个原子提交，recovery tag 见提交信息）。新文件只有任务文档；`VodFragment` / `HkListFragment` / `fragment_vod.xml` 的改动随 revert 一起回去，首页回到“hk 站走普通蜘蛛链路”的旧行为。
- 局部回滚：只删 `loadHome()` 开头的 `tryEmbedHkHome` 调用（恢复成原 `loadHome`），内嵌分支即不可达，`HkListFragment` 的内嵌模式变成死代码，下次构建不受影响；`HkSearchActivity` 路由恢复成只走 `SearchActivity` 需同步改回搜索条点击。

## 6. 已知缺口与风险

- **内嵌首页无横幅/继续观看/组件区**：hk 规则没有普通站的首页推荐数据，B1 横幅无数据可填；B2 继续观看是历史区，与规则无关，内嵌态下隐藏是设计取舍（不是 bug）；B5 组件区与站源无关，理论上可留，但与小程序列表页风格不搭，本步一并隐藏，后续用户要再放出来。
- **内嵌失败回落只生效一次**：回首页后第二次失败留在内嵌错误态（可点重试），不自动再跳列表页——防死循环的取舍，见 §2 子问题表。
- **翻页失败不回落**：用户已在浏览中途时翻页失败只弹 toast，与列表页一致；只有首屏失败才回落。
- **规则 `col_type` 映射沿用第 3 步**：内嵌卡片样式与列表页完全一致（`HkListHelper.styleFor`），`movie_3_marquee` 等长尾类型仍落默认卡片。
- **首页取数失败与“真没内容”仍靠列表页既有诊断**：首屏 0 条时内嵌 Fragment 显示第 4 步的命中诊断（`diagText`），不回落——0 条不是失败。
- **本地无编译检查**：无 JDK/Android SDK，`FragmentVodBinding.hkContainer` 字段名、`getChildFragmentManager()` 挂载、`EmbedHost` 回调等只能等 GitHub 构建驗；提交前逐行重读是唯一本地防线。

## 7. 实现清单

- [x] 设计评审（本文档）
- [x] `fragment_vod.xml` 加 `hkContainer`
- [x] `HkListFragment` 内嵌模式（`newInstance(title, embedded)` + `EmbedHost` + 规则缺失/首屏失败回通知宿主）
- [x] `VodFragment` hk 分支（`loadHome` / `homeContent` / `setAdapter` / 搜索路由 / 可见性与事件守卫 / `fallbackToList`）
- [x] 逐行重读 + `git diff --check` + guard finish（本地提交，不推不编）
