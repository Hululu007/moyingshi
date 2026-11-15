# 海阔小程序第 6 步之一：搜索功能

> Recovery anchor（任务 `miniapp-search`，按仓库 AGENTS.md §5）
>
> - 目标：小程序搜索页（单规则搜索 + 结果列表，点结果进详情）+ 列表页
>   搜索入口；本地做完、本地提交，不推、不编、不触发构建、不动版本记录空间。
> - 分支/HEAD：`main` / `025212ce`（2026-11-15 17:15 hk 修复版，已推，remote 一致）。
> - 计划状态：第 1–5 步 + hk 修复（025212ce）已在 main 上；本步 = 设计评审
>   （本文档）+ 实现，见下文“实现清单”。第 6 步的另一半（导出）不在本步。
> - 当前文件/符号：
>   `api/hk/` 下 `HkRule`（`getSearchUrl/getSearchFind` 已有，无 `E()` 方法）/
>   `HkExecutor`（`home/homeDetailed/detail/play` 已有，`fetch` 只做 GET，
>   `buildHomeRequest` 遇 POST 抛错）/ `HkParser`（`parseHomeList` 首页字段序，
>   无搜索解析）/ `HkEngine`（`prepare(rule, url, page, pageType, resCode)`、
>   `setSearchResult` 已在 BOOTSTRAP 里）；`ui/hk/HkListActivity` /
>   `HkListFragment`（第 3 步遗留：列表页无搜索入口）。
> - 已验证：本地逐行重读 + `git diff --check`；GitHub 构建（编译检查）未做，
>   本次不推不编，按任务要求留待用户说“推”之后。
> - 未验证（需要一次构建 + 真机 + 三条验收规则）：搜索页在真机上的表现、
>   三条规则的 search_url/searchFind 语义（尤其奇优的 POST 搜索）、
>   `**` 关键词编码、`fypage` 搜索翻页。
> - 未解决的风险：见下文“已知缺口与风险”。
> - 下一个动作：guard finish（本地提交，不推不编）→ 向父代理汇报；
>   用户说“推”后推上去走 GitHub 构建，再拿三条验收规则真机验；
>   第 6 步另一半（导出）与第 7 步长尾等搜索验完再做。

## 目标与范围

- 做：新建 `HkSearchActivity` + `HkSearchFragment` + 两个布局；搜索执行
  走 `HkExecutor.search(rule, keyword, page)`（复用首页同款链路：校验 →
  拼 URL → OkHttp 抓页 → preRule → `searchFind` 是 `js:` 走 Rhino，否则走
  选择器解析）；结果同样是 `HkCard` 列表，经 `HkListHelper.pickCards` /
  `toVods` 后复用首页同款 `VodAdapter` 渲染，点卡片进详情页（第 4 步的
  `HkDetailActivity`，直接复用）；列表页工具栏加搜索入口（点开进搜索页，
  带当前规则标题）；搜索页内可换关键词再搜；搜索支持翻页（`fypage`）。
- 不做：多规则聚合搜索（报告 `a1` 的 `engineTitles` 多引擎 + 底部引擎
  Tab + 分组/模式切换）、搜索联想（`hk_search_suggest`）、导出（第 6 步
  另一半）、云口令（第 7 步）、首页内嵌（第 7 步）、`webRule://`（第 7 步）。
- 不变的行为：列表页分类/翻页/三处入口照旧；详情/懒加载播放（第 4–5 步）
  一行不动。只改手机端，TV/leanback 不动，`BaseLoader` 不动，底栏不加项
  （`menu_nav.xml` 仍是 6 项上限）。

## 最佳实践研究（AGENTS.md §7 设计门）

### 证据表

| 证据 | 来源 | 等级 | 支撑的结论 | 适用性/注意 |
|---|---|---|---|---|
| 逆向报告 §7.4 `HkSearchActivity`（578 行，全文可读） | `~/workspace/user/files/__________8_3azg.md` L2481–3060 | 高（反编译源码） | 启动器 `Z0(activity, g22, 关键词)`（单规则，extra `oneRule=true` + `engineTitles=[标题]` + `wd`）/`a1(activity, 列表, 关键词)`（多规则）；`b1(关键词)`：空关键词弹“搜索关键词不能为空”并收键盘，否则重置 ViewPager2、建新的 `z22` 搜索任务；onCreate 只收 `g22.E()` 为真的规则进候选（`E()` = search_url 与 searchFind 都非空）；布局 `activity_hk_search`：返回 + 搜索框 + 清空 + 分组 + 模式 + 底部 RecyclerView + ViewPager2 + 联想 RecyclerView | 本步只做单规则（`Z0` 对应物）；多规则聚合、分组/模式切换、联想不在本步。`E()` 语义直译成 `HkRule.isSearchable()` |
| 逆向报告 §7.1 `search(site, 关键词, 页码)`（全文可读） | 同上 L1071–1160 | 高（有源码） | `a0()` 校验 → `E()` 为假直接返回空列表；`fx1.b(g22, g22.u())` 拼搜索模板 + `ky1.h(页码, 模板, 关键词, 布尔)` 替换占位与页码；searchFind 是 JS（`b32.f`）时先抓页进 `w02.R`（`getResCode()` 的源）、`M1("search", …)` 再 `c1(searchFind)`，否则 `b32.k(正文, searchFind, url)`；结果只留标题 + 链接双非空的卡片，转 Vod（id=url、name=标题、pic、remarks=描述），失败吞异常返回空列表 | 本步 `HkExecutor.search` 按此顺序实现；失败同样返回空列表还是抛异常见“设计”（本步抛异常、由 Fragment 显示错误态，与列表页三态一致；`E()` 为假才返回空列表） |
| 官方规则文档“搜索引擎链接” | https://docs.189.tyrantg.com/docs/hikerview/help_rules.html（2026-11-15 访问，L2–10） | 高（官方） | 关键词占位符是 `**`，冲突时用 `%%` | **纠正任务描述**：任务说的“fykey 占位”在真实规则与官方文档里都不存在；三条验收规则全用 `**`。本步实现 `**`/`%%`，另把 `fykey` 当无害别名一起换（见“设计”） |
| 官方规则文档“搜索解析规则” | 同上（L70–88） | 高（官方） | 格式 `列表;标题;链接;描述;详情;图片`，前三个必选，后三个可省 | **与首页字段序不同**：首页是 `列表;标题;图片;描述;链接`。搜索必须另写 `parseSearchList`，不能复用 `parseHomeList`（否则链接/图片位置全错） |
| 官方规则文档“POST传参” | 同上（L90–104） | 高（官方） | POST 与 GET 传参一样，真正请求时参数自动转到 body；链接里有 `?` 时用 `？？` 代替，请求时转回 `?` | 奇优的 search_url 带 `;post`，本步 `fetch` 必须补 POST（query 进 body），否则三条验收规则之一搜不出结果 |
| 官方规则文档“首页频道链接增强” | 同上（L24–30） | 高（官方） | `;get`/`;POST`/`;GBK`/`;{headers JSON}` 修饰符，第一个分号后依次出现 | `buildSearchRequest` 的修饰符解析与 `buildHomeRequest` 同款；本步把这段抽成共享方法（见“设计”） |
| 官方规则文档“JS预处理” | 同上（L208–210） | 高（官方） | preRule 在首页解析规则和搜索解析规则前都会执行一遍 | 搜索同样先 `evalPreRule()` 再跑 searchFind |
| 三条验收规则的 search 字段（逐条读过原文） | `~/workspace/miniapp/test-rules/` 三条 JSON | 高（真实规则） | 奇优影院♔：`search_url=http://www.qiyou03.com/search.php?searchword=**;post;utf-8`（**POST**），searchFind 选择器 6 字段（`.stui-vodlist__media&&li;.title&&Text;a&&href;.pic-text&&Text;.detail&&Text;.lazyload&&data-original`）；童话：`search_url=https://m.tonghuacun.com/search.php?searchword=**`（GET），searchFind 选择器 6 字段；臻品fix：`search_url=https://www.zpcxb.com/sosou/page/fypage.html?wd=**`（GET + `fypage`），searchFind 是 `js:`（`getResCode()` + `parseDomForArray` + `res.data = d; setHomeResult(res)`） | 三条覆盖 GET/POST、选择器/JS、`fypage` 翻页；奇优的 POST 是本步必须补的关键点（见“设计”） |
| 仓库现有实现：`HkExecutor.homeDetailed/detail/play` | `api/hk/HkExecutor.java` | 高（本地代码） | 首页/详情/播放三条链同款顺序（校验 → 拼请求 → `fetch` → `prepare` → `evalPreRule` → JS 走 Rhino/否则选择器）；`isHttpUrl` guard（非 http/https 不抓页，JS 在空页上跑）；`RequestSpec{url, headers, charset}`；`replacePagePlaceholders`（`fypage@…@` 运算）；`buildHomeRequest` 的 `;` 修饰符解析 + `[firstPage=N]` + `fyAll/fyclass/…` | 搜索复用同一条链：`search()` 内新建 `buildSearchRequest`；`fetch` 加 POST（`RequestSpec` 加 `method`/`body`，默认 GET，首页/详情/播放行为不变）；修饰符解析抽成 `splitUrlModifiers` 供两处共用 |
| 仓库现有实现：`HkParser.parseHomeListDetailed` | `api/hk/HkParser.java` | 高（本地代码） | `splitOutsideQuotes`、`*` 继承、`extract`（`&&`/`--`/`,n`/Text/Html/`.js:`/`||`/`+`）全部可复用 | `parseSearchList` 只换字段下标（列表;标题;链接;描述;详情;图片），其余逻辑不动；`HkCard` 的 desc 取第 4 字段（描述），第 5 字段（详情）为空时才作 desc 回落 |
| 仓库现有实现：`HkEngine.prepare/evalFindRule` | `api/hk/HkEngine.java` | 高（本地代码） | `prepare(rule, url, page, pageType, resCode)`，`pageType` 只暴露给 JS 当 `MY_TYPE`；BOOTSTRAP 已有 `setSearchResult(d)`（即 `setResult`）；`evalFindRule` 优先取 `setResult` 的结果 | 搜索传 `pageType="search"`；JS 搜索规则用 `setHomeResult` 或 `setSearchResult` 都能接住（臻品fix 用 `setHomeResult(res)`，`.data` 拆包第 4 步已补） |
| 仓库现有实现：`HkListFragment` + `HkDetailActivity.open` | `ui/hk/HkListFragment.java`、`HkDetailActivity.java` | 高（本地代码） | 分类/下拉刷新/滑到底翻页（`CustomScroller`）+ 加载/空/错三态（`ProgressLayout`）+ 序号防串位 + 后台线程取数；点卡片 `HkDetailActivity.open(context, 规则标题, url, 标题, 海报, 简介)` | 搜索 Fragment 照此结构写（无分类 chips；翻页走 `fypage`）；点卡片同一行调用 |
| 仓库现有实现：本 App 自己的搜索页 | `ui/fragment/SearchFragment.java` + `fragment_search.xml` | 高（本地代码） | EditText 放进 MaterialToolbar（白字、白 hint、`shape_cursor` 光标、`actionSearch`）；`@drawable/ic_search` 已有 | 搜索页工具栏照此样式：返回 + EditText + 搜索按钮（`ic_search`），不用新画图标 |
| 仓库现有实现：`HkStore.read(title)` | `api/hk/HkStore.java` L116 | 高（本地代码） | 按标题读规则文件，读不到返回 null | 列表页与搜索页都用它取规则；读不到时搜索页弹 `hk_list_rule_missing` + `finish()`（与列表页同款） |

### 方案比较

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 不做 | 列表页保持无搜索入口 | 零风险 | 第 3 步遗留的搜索入口一直空着；三条验收规则的搜索都测不了；第 6 步是用户 2026-11-15 18:07 亲口说“后边的工程一次做完”定的下一步 | 否决 |
| B. 照搬上游 | `HkSearchActivity` 全量：ViewPager2 多引擎 Tab + 底部引擎列表（`m22`）+ 分组/模式切换 + 联想 RecyclerView + `z22` 异步搜索任务（`a01`/`xe0` 适配器） | 与魔影行为最接近 | 多引擎聚合要跨规则搜（第 7 步长尾）；联想要额外接口；`z22`/`m22`/`a01`/`xe0` 全是混淆名、无源码，行为只能猜；工作量数倍于本步；验收三条规则都是单规则搜索，用不上 | 否决（多引擎与联想是第 7 步） |
| C. 窄化适配（推荐） | 单规则搜索页（Activity + Fragment，复用 `HkExecutor`/`HkParser`/`HkEngine`/`VodAdapter`/`CustomScroller`/`ProgressLayout`）+ 列表页工具栏搜索图标入口；`E()` 为假时进搜索页显示空态、不崩 | 改动集中在 mobile 新文件 + `HkExecutor`/`HkParser`/`HkRule` 三处小补丁 + 列表页一行接线；三条验收规则的 GET/POST、选择器/JS、`fypage` 一次覆盖；不碰 `BaseLoader`、不碰首页、不碰 TV、不碰播放链路 | 多引擎聚合、联想、分组/模式切换不做（第 7 步）；搜索结果的“详情”字段暂不展示（见“已知缺口”） | **采用** |
| D. 复用现有 `SearchActivity`/`SearchFragment` | 把 hk 规则当一条站源塞进现有搜索 | 不写新页面 | 现有搜索绑定 `Site` + `Spider` 取数；hk 不走 `Spider`（`BaseLoader` 明确不动），要给核心搜索加 hk 分支，爆炸面比新页面大；且与魔影“小程序走独立搜索页链路”的做法背离 | 否决 |

### 推荐

采用方案 C。理由：验收的三条规则全是单规则搜索，窄化适配一次覆盖
GET/POST、选择器/JS、`fypage`；取数与解析全部复用第 1 步的引擎与第 3 步
的列表 UI（只补 POST 与搜索字段序两个缺口）；UI 是新的独立页面，不碰
现有搜索页与播放器；回滚 = 删新文件 + 恢复列表页一行接线，引擎补丁
（POST、`parseSearchList`）可独立回滚。

## 设计

### 文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `docs/miniapp-search.md` | 新建（本文档） | 设计评审 + 实现记录 + 回滚 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkRule.java` | 改 | 加 `isSearchable()`（`E()` 对应物：search_url 与 searchFind 都非空） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkExecutor.java` | 改 | 加 `search(rule, keyword, page)` + `buildSearchRequest`；`RequestSpec` 加 `method`/`body`（默认 GET）；`fetch` 支持 POST（body 进 `application/x-www-form-urlencoded`）；`;` 修饰符解析抽成 `splitUrlModifiers`（`buildHomeRequest` 与 `buildSearchRequest` 共用，行为不变） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkParser.java` | 改 | 加 `parseSearchList(html, searchFind, baseUrl, engine)`（字段序 列表;标题;链接;描述;详情;图片，前三个必选） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkSearchActivity.java` | 新建 | 搜索页壳：Toolbar（返回 + EditText + 搜索按钮）+ `FrameLayout`（`@+id/hk_search_container`，挂 Fragment）；`open(Context, ruleTitle)` / `open(Context, ruleTitle, keyword)` 启动入口；壁纸由 `BaseActivity.setContentView` 自动加 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkSearchFragment.java` | 新建 | 搜索 Fragment：后台线程调 `HkExecutor.search` → `pickCards`/`toVods` → `VodAdapter`；下拉刷新（重搜第 1 页）+ 滑到底翻页（`fypage`）+ 加载/空/错三态 + 换关键词重搜；点卡片进详情页 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkListActivity.java` | 改 | 工具栏加搜索菜单项（`menu_hk_list.xml`，`ic_search`）：点开进搜索页（带规则标题）；规则不可搜索时该项隐藏 |
| `app/src/mobile/res/layout/activity_hk_search.xml` | 新建 | 照 `activity_hk_list.xml`：MaterialToolbar（内嵌 EditText + 搜索按钮，照本 App `fragment_search.xml` 的样式）+ `FrameLayout`（container） |
| `app/src/mobile/res/layout/fragment_hk_search.xml` | 新建 | 照 `fragment_hk_list.xml`：`diagText` + SwipeRefreshLayout → ProgressLayout → CustomRecyclerView（无分类 chips） |
| `app/src/mobile/res/menu/menu_hk_list.xml` | 新建 | 一个搜索项（`@+id/action_search`，`@drawable/ic_search`，`showAsAction="always"`） |
| `app/src/mobile/AndroidManifest.xml` | 改 | 注册 `HkSearchActivity`（无 intent-filter，只有 mobile 代码直接启动；`parentActivityName=".ui.hk.HkListActivity"`；照 `HkDetailActivity` 的写法） |
| `app/src/main/res/values/lab_strings.xml` | 改 | 加 5 条：`hk_search_hint`（搜影片名）、`hk_search_submit`（搜索）、`hk_search_no_rule`（该规则不支持搜索）、`hk_search_empty`（没有搜到相关结果）、`hk_search_empty_keyword`（搜索关键词不能为空） |

### 关键契约

- **启动参数**：`HkSearchActivity.open(context, ruleTitle)` / `open(context, ruleTitle, keyword)`——
  规则标题 + 可选初始关键词（列表页入口不带关键词；搜索页内换词重搜不
  经过 Activity）。不传整条规则 JSON（与列表页/详情页同款理由）；页内
  `HkStore.read(title)` 读规则，读不到 → 弹 `hk_list_rule_missing` +
  `finish()`（与列表页同款）。
- **搜索取数**（`HkExecutor.search(rule, keyword, page)`，后台线程）：
  1. 规则校验（与 `home()` 同款 `validate()`）；关键词空（null/空白）→
     抛“搜索关键词不能为空”（Fragment 收到后显示错误态；Activity 的提交
     入口先判空、空则只弹 toast、不发请求，与报告 `b1()` 同款）。
  2. `!isSearchable()` → 返回空列表（报告 `E()` 为假的语义：不要假数据、
     不抛错；Fragment 显示空态 + `hk_search_no_rule` 提示）。
  3. `buildSearchRequest(rule, keyword, page)` 拼请求（见下）。
  4. 与 `homeDetailed` 同款 `isHttpUrl` guard：搜索地址不是 http/https
     时不抓页（JS 在空页上跑，选择器得 0 条），不抛 OkHttp scheme 错。
  5. `engine.prepare(rule, spec.url, page, "search", pageText)` →
     `evalPreRule()`（官方文档：preRule 在搜索解析前同样执行一遍）→
     searchFind 是 `js:` 走 `evalFindRule`，否则 `parseSearchList`。
  6. 返回原始卡片列表；筛选/转 Vod 由 Fragment 走 `pickCards`/`toVods`
     （与列表页同款：先取 colType == 规则 col_type 的可用卡片，空则取
     可用且非动作 URL 的；搜索卡片一般无 colType，走第二遍）。
- **拼搜索请求**（`buildSearchRequest`）：
  1. 模板 = `rule.getSearchUrl()`；`？？` 先转回 `?`（官方 POST 一节）。
  2. `;` 修饰符解析与 `buildHomeRequest` 同款（`splitUrlModifiers` 共享）：
     `get`/`post`（大小写不敏感）、编码（`UTF-8`/`GBK`/`GB2312`/`GB18030`）、
     `{headers JSON}`；修饰符未开始前的 `;` 仍是 URL 的一部分（原逻辑原样保留）。
  3. `[firstPage=N]` 与 `fypage@…@` 运算与首页同款（`replacePagePlaceholders`）。
  4. 关键词替换（URL 编码后替换，编码用规则 charset，默认 UTF-8）：
     `**` → 编码后关键词；`%%` → 同样（官方文档的冲突备用占位符）；
     `fykey` → 同样（任务描述里的写法，真实规则里没有，当无害别名）。
     替换顺序 `**` → `%%` → `fykey`。`ky1.h` 的布尔参数含义报告里无
     源码（推测是编不编码），本步统一编码后替换，OkHttp 发请求时不再
     二次编码（见“已知缺口”）。
  5. POST：URL 问号后的部分进 `body`（`application/x-www-form-urlencoded`，
     官方“POST传参和get一样，真正请求时会自动转到body里面”），URL 只留
     问号前；无问号时 body 为空串。`JsonBody` 的 JSON POST 本步不支持
     （见“已知缺口”）。
  6. 请求头 = 规则 `firstHeader` + `ua`（与首页同款）+ URL 内 `{headers}`。
- **`fetch` 的 POST**：`RequestSpec` 加 `method`（默认 `"GET"`）与 `body`
  （默认 `""`）；`method == "POST"` 时 `RequestBody.create(body,
  MediaType.parse("application/x-www-form-urlencoded"))` 发 POST（body
  字符串已是编码后的 query，不再二次编码）；其余走原 GET。首页/详情/
  播放的 `RequestSpec` 不填这两个字段，行为与原来一致。注意：
  `buildHomeRequest` 遇 POST 仍抛“POST 请求暂不支持”（只动搜索路径，
  首页 POST 是另一回事，不在本步扩范围）。
- **搜索选择器解析**（`HkParser.parseSearchList`）：字段序
  `列表;标题;链接;描述;详情;图片`（官方文档，前三个必选，后三个可省，
  字段不足 3 个返回空列表）。`title=extract(f1, false)`、
  `url=extract(f2, true)`、`desc=extract(f3, false)`、`detail=extract(f4, false)`（有才取）、
  `pic=extract(f5, true)`（有才取）；desc 为空时用 detail 回落；只留
  `usable()`（标题 + 链接双全）的卡片——与 `parseHomeList` 同款过滤，
  与报告 `search()` 只留标题 + 链接双非空一致。第 5 字段（详情）除回落
  外不另展示（见“已知缺口”）。
- **搜索页 UI**（Activity + Fragment，照列表页分工）：
  - Activity：Toolbar（返回 + EditText + 搜索按钮 `ic_search`）+
    container；EditText 的 IME 搜索动作与搜索按钮都调
    `fragment.search(keyword)`；提交时先判空（空则 toast
    `hk_search_empty_keyword`“搜索关键词不能为空”——报告 `b1()` 同款文案）；
    初始关键词（extra `wd`）非空时进页即搜一次。
  - Fragment：`search(keyword)` 换词时回到第 1 页（`mScroller.reset()` +
    adapter 清空 + `loadSeq` 自增防串位）；下拉刷新重搜当前词第 1 页；
    滑到底翻页（`CustomScroller.onLoadMore` → `search(page+1, append)`，
    `fypage` 换页）；三态与列表页同款（`showProgress` / `showContent` /
    `showError(hk_list_error, 原因, hk_list_retry, 点重试重搜)`）；翻页失败
    只 toast、不盖列表（同款）；首屏 0 条时 `diagText` 显示原因：
    不可搜索 → `hk_search_no_rule`，搜到 0 条 → `hk_search_empty`。
  - 样式：`HkListHelper.styleFor(rule.getColType())`（三条验收规则全是
    `movie_3` → rect 海报网格，与列表页同款）；点卡片
    `HkDetailActivity.open(context, 规则标题, url, 标题, 海报, 简介)`，
    与列表页同一行调用；长按什么都不做。
  - EditText 在 Toolbar 内：白字、白 hint（`#B3FFFFFF`）、`shape_cursor`
    光标、`imeOptions="actionSearch"`、`inputType="text"`、`maxLength=255`、
    单行（照本 App `fragment_search.xml` 的样式）；根布局不套透明背景
    （壁纸由 `BaseActivity.setContentView` 自动加，与列表页同款；不学
    报告 `activity_hk_search.xml` 的 `@android:color/transparent` 根背景）。
- **列表页搜索入口**（第 3 步遗留）：`HkListActivity` 的 Toolbar 加
  `menu_hk_list.xml`（一个搜索项，`ic_search`，`showAsAction="always"`）；
  `onCreateOptionsMenu` 里按 `HkStore.read(title).isSearchable()` 决定显隐
  （读不到规则时隐藏——Fragment 那边会弹“规则不存在或已删除”并 finish，
  菜单显隐不影响）；点搜索项 → `HkSearchActivity.open(this, title)`，
  列表页留在返回栈里（返回键回列表，与详情页同款）。

### 不做的（明确边界）

- 多规则聚合搜索（报告 `a1` 的 `engineTitles` + 底部引擎 Tab + 分组/
  模式切换）、搜索联想、导出（第 6 步另一半）、云口令（第 7 步）、
  首页内嵌（第 7 步）、`webRule://`（第 7 步）、`JsonBody` 的 JSON POST、
  搜索结果的“详情”字段单独展示、搜索历史记录（本 App 自己的搜索有
  历史，hk 搜索本步不记）。

## 验收标准

代码层面可确认（提交前逐行重读 + `git diff --check`）：

1. `HkSearchActivity` 在 mobile 清单里注册；main 源集零改动；`BaseLoader`
   未动；TV/leanback 源集零改动；`menu_nav.xml` 仍 6 项。
2. `buildSearchRequest` 只影响搜索路径；`buildHomeRequest` 的修饰符解析
   行为不变（`splitUrlModifiers` 抽取后逐行对过）；首页/详情/播放的
   `RequestSpec` 不填 `method`/`body`，`fetch` 走原 GET。
3. `parseSearchList` 只在搜索路径用，`parseHomeList`/`parseDetailList`
   行为不变；`home()`/`detail()`/`play()` 签名与返回值不变。
4. 无 `Json.safeListElement` 式返回类型误用、无泛型原始类型；dialog 根布局
   不透明背景一条不适用（本步无 dialog）；布局根布局不透明（不套
   `@android:color/transparent`）。
5. 不可搜索的规则进搜索页不崩（`isSearchable()` 为假 → 空列表 +
   `hk_search_no_rule` 空态）；空关键词不发请求。

只能真机验（需一次 GitHub 构建 + 用户装新版 + 三条验收规则已导入）：

6. 奇优影院♔：列表页点搜索图标进搜索页，搜一个片名（如“狂飙”）有结果
   （POST 搜索走通）；点结果进详情（标题/海报/简介是真的），再点选集进
   播放（第 5 步链路，不动它）；翻页能翻（第 2 页不是第 1 页重复）。
7. 童话：同上（GET + 选择器搜索）。
8. 臻品fix：同上（GET + JS 搜索，`getResCode()` + `setHomeResult(res)`）。
9. 搜索页内换关键词再搜：改词后结果跟着换，不串位（快速连搜旧结果丢掉）。
10. 无 search_url/searchFind 的规则：列表页搜索图标隐藏；直接进搜索页
    （如有入口）显示“该规则不支持搜索”空态，不崩。
11. 列表/详情零回归：分类/翻页/点卡片进详情/点选集进播放照旧；没导入
    任何小程序时零回归。

## 回滚路径

- 整步回滚：`git revert <本提交>`（一个原子提交，recovery tag 见提交信息）。
  新文件（搜索页 Activity/Fragment/三个布局与菜单/任务文档）随 revert
  一起消失；列表页工具栏恢复成无搜索项；`HkExecutor`/`HkParser`/`HkRule`
  的补丁一起回去——注意：回滚后搜索功能整体消失，`fetch` 的 POST 与
  `parseSearchList` 也没了，但首页/详情/播放不受影响（它们不走这两条）。
- 局部回滚：只回滚列表页接线（删 `menu_hk_list.xml` 的引用与
  `HkListActivity` 的菜单代码）而保留搜索页代码：搜索页变成不可达代码，
  下次构建不受影响。`fetch` 的 POST 与 `parseSearchList` 建议保留——
  它们只在搜索路径用，不影响其它功能。

## 已知缺口与风险

- **`ky1.h` 的布尔参数含义未知**：报告里 `ky1.h(页码, 模板, 关键词, true/false)`，
  两个调用点分别用于选择器路径与 JS 预取路径，`ky1` 本身无源码。推测是
  “关键词编不编码”的开关；本步统一 URL 编码后替换。真机若出现搜不到，
  优先查编码（`%E7%8B%82%E9%A3%99` vs 原文）与 POST body 格式。
- **POST body 格式是外推**：官方文档只说“POST传参和get一样，真正请求时
  会自动转到body里面”；本步实现是 query 字符串原样进
  `application/x-www-form-urlencoded` body。奇优是第一条真机验证的 POST
  规则，若服务端要求其它 Content-Type 再调。
- **搜索结果“详情”字段暂不展示**：第 5 字段（详情）只在 desc 为空时回落；
  奇优的 `.detail&&Text`（剧情简介）因此可能不显示。以后可把 detail 放进
  `HkCard.content` 再由 `toVods` 展示，本步不动 `toVods`。
- **`JsonBody` 的 JSON POST 不支持**：带 `JsonBody={"key":"**"}` 的搜索
  URL 本步当普通 POST 处理（body 里会有 `JsonBody=…` 字面量），搜不出
  结果；验收三条规则用不到。
- **搜索翻页无规则可验页码语义**：`fypage` 换页与首页同款（臻品fix 的
  search_url 带 `fypage`）；奇优/童话的 search_url 无 `fypage`，翻页
  时发同一 URL（服务端可能返回同一页），`CustomScroller` 的空页回退
  会挡住无限翻，但“第 2 页重复”只能真机看。
- **快速连搜的请求不取消**：换词只靠 `loadSeq` 丢弃旧结果，旧请求本身
  还在后台跑（与列表页切分类同款做法）；搜索是用户主动触发，频率低，
  可接受。
- **`splitUrlModifiers` 抽取风险**：`buildHomeRequest` 的修饰符解析被
  原样搬进共享方法，行为必须不变；提交前逐行对过两处调用点（见“实现清单”）。

## 实现清单

- [x] 设计评审（本文档）
- [x] `HkRule.isSearchable()`
- [x] `HkExecutor.search` + `buildSearchRequest` + `RequestSpec.method/body` + `fetch` POST + `splitUrlModifiers`
- [x] `HkParser.parseSearchList`
- [x] `HkSearchActivity` + `activity_hk_search.xml` + 清单注册
- [x] `HkSearchFragment` + `fragment_hk_search.xml`（取数 + 三态 + 翻页 + 换词重搜）
- [x] `HkListActivity` 搜索菜单项 + `menu_hk_list.xml`
- [x] `lab_strings.xml` 加 5 条
- [x] 逐行重读 + `git diff --check` + guard finish（本地提交，不推不编）

实现中纠正的（2026-11-15 18:5x，提交前）：

- 文档内修正两处：`lab_strings.xml` 新增字符串由初版写的 3 条改成实际
  5 条（补了 `hk_search_submit` 与 `hk_search_empty_keyword`）；空关键词
  的 toast 文案改用新串 `hk_search_empty_keyword`，不留“复用硬编码串还是
  新串”的待定。
- `HkSearchFragment.loadPage` 里初版写了全限定名
  `com.github.moyingshi.osc.tv.api.hk.HkCard`，已改成 import（纯风格，
  无行为变化）。
- 其余实现与设计一致，无需纠正：逐行重读未发现返回类型误用、泛型原始
  类型、悬空 import、布局 id 与 binding 不一致；`RequestBody.create(String,
  MediaType)` 的参数顺序与仓库已有用法（`CatSpider.java:139`、
  `PlaybackWebhookSender.java:126`，OkHttp 5.4.0）一致；`splitUrlModifiers`
  抽取后与原 `buildHomeRequest` 修饰符解析逐行对过，行为不变。
