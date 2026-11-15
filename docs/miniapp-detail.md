# 海阔小程序第 4 步：详情页

> Recovery anchor（任务 `miniapp-detail`，按仓库 AGENTS.md §5）
>
> - 目标：列表页点卡片进真详情页（标题/海报/简介/分线路选集），点选集弹
>   “播放还在做”（第 5 步才做懒加载播放）；顺手补第 3 步遗留的列表 0 条
>   命中诊断 + 删 foundation 里过时的 `fyAll` 缺口句；本地做完、本地提交，
>   不推、不编、不触发构建。
> - 分支/HEAD：`main` / `2219a72b`（第 3 步列表页，本地，未推）。
> - 计划状态：第 1 步地基（f9f6b234）、第 2 步导入（a2e06441）、第 3 步列表页
>   （2219a72b）已在 main 上；本步 = 设计评审（本文档）+ 实现，见下文“实现清单”。
> - 当前文件/符号：
>   `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/` 下 `HkRule` /
>   `HkCard` / `HkStore` / `HkEngine` / `HkParser` / `HkExecutor`（`home()` 已有，
>   `detail()` 未有）/ `HkListHelper`；`ui/hk/HkListActivity` /
>   `HkListFragment`（点卡片处弹 `hk_list_detail_pending`“详情页还在做”）。
> - 已验证：本地逐行重读 + `git diff --check`（2026-11-15 13:5x）；复核中修了
>   两处：`HkEngine.evalFindRule` 签名行与下一行拼行、详情布局包名
>   `ui.view`→`ui.custom`（列表页布局证实是 `ui.custom`，原写法一打开就
>   ClassNotFound 崩）；另清掉 `HkListFragment` 里已无用的 `HkCard` 导入。
>   三条规则的 detail JS 原文已读过：臻品fix 的“🔎线路”头无 url、tab 卡是
>   `hiker://empty` 动作 URL、“选集”头 url 以 `#` 开头，`split` 的分类与之
>   对得上；奇优的免责声明卡带 `MY_URL` 但有 `text_5` 保护不会混进选集；
>   童话的 `text_1` 头被第二遍谓词排除。GitHub 构建（编译检查）未做，
>   本次不推不编，按任务要求留待用户说“推”之后（第 3 步 + 第 4 步一起推）。
> - 未验证（需要一次构建 + 真机 + 三条验收规则）：详情页在真机上的表现、
>   三条规则的 detail JS 输出形状、`.data` 拆包与 `getVar(k, 默认值)` 两个
>   引擎补丁的效果（见“引擎补丁”）。
> - 未解决的风险：见下文“已知缺口与风险”。
> - 下一个动作：guard finish（新文件不在原 scope 里，需 abort 后带全部路径
>   重开再 finish）→ 本地提交 → 向父代理汇报；用户说“推”后把第 3 步
>   （2219a72b）+ 本步一起推上去走 GitHub 构建，再拿三条验收规则真机验；
>   第 5 步（懒加载播放）等详情验完再做。

## 目标与范围

- 做：新建 `HkDetailActivity` + `HkDetailFragment` + `HkDetailAdapter` +
  布局；列表页点卡片进详情页；详情解析走 `detail_find_rule`（JS 路径复用
  `HkEngine`，选择器路径复用 `HkParser`）；展示标题/海报/简介/信息；选集
  列表按线路分组（`chapter_find` / `movie_find` 作无选集时的备选，见下）；
  点某一集弹“播放还在做”，不进播放器、不进蜘蛛页。
- 顺手做（第 3 步遗留，任务明确要求）：①列表 0 条时补规则命中诊断；
  ②删 `docs/miniapp-foundation.md` 里过时的“`fyAll` 未替换”缺口句。
- 不做：懒加载播放地址解析（`f22.t`）+ 接入现有播放器（第 5 步）；搜索页
  （第 6 步）；云口令/导出（第 6–7 步）；首页内嵌（第 7 步）；48 种
  `col_type` 全覆盖（第 7 步）；`hiker://page/` 子页面（第 7 步）；
  `webRule://`（第 7 步）；多线路切换（规则内 `putVar` + `refreshPage`
  的线路切换语义，第 5–7 步）。
- 不变的行为：列表页分类/翻页/三处入口照旧；`a7e97b48` 点分组只显示内嵌
  列表不动。只改手机端，TV/leanback 不动，`BaseLoader` 不动，底栏不加项
  （`menu_nav.xml` 仍是 6 项上限）。新详情页不碰 `VideoActivity` /
  `TmdbDetailActivity`（skill 里的双活动核对规则：本步不改那两个活动，
  只复用一个与它们无关的通用 chip 布局，见“设计”）。

## 最佳实践研究（AGENTS.md §7 设计门）

### 证据表

| 证据 | 来源 | 等级 | 支撑的结论 | 适用性/注意 |
|---|---|---|---|---|
| 逆向报告 §6 `f22.detail` / `HkSiteAdapter.detail` | `~/workspace/user/files/__________8_3azg.md` L210/L314/L796 | 低（反编译失败，2673 条指令被跳过） | 详情方法无源码，只能从调用方 + `player()` 推断：解析详情页，处理 `hiker://page/` 子页面与动作 URL | 本步按文档 + 真实规则重写，不声称复刻魔影 |
| 逆向报告 §7.1 `pickEpisodes` | 同上 L582–606（全文可读） | 高（有源码） | 选集识别两遍：先取 `colType == text_5` 的可用卡片；空则取 colType 不含 button/input/header/tab/title 且 ≠ `text_1`、URL 非动作且以 `http` 开头或含 `@lazyRule=`/`@rule=` 或以 `js:` 开头的可用卡片 | 直译成本步 `HkDetailHelper` 的选集判定（逐卡版，见“设计”） |
| 逆向报告 §3 `g22` 字段表 | 同上 L256–261 | 高 | `detail_find_rule` / `sdetail_find_rule`（详情解析规则）、`detail_col_type` / `sdetail_col_type`（详情类型）、`last_chapter_rule`（最后一章，小说/漫画类）、`chapter_find` / `movie_find`（章节/影片查找规则） | 三条验收规则：`sdetail_find_rule` 全是 `*`，`chapter_find`/`movie_find`/`last_chapter_rule` 全空——本步只实现主详情 + 备选回落，见“设计” |
| 逆向报告 §2.5/§7.12 `uy1` | 同上 | 高 | 卡片字段 title/url/pic/desc/sel/colType/extra；`usable()` = title+url 非空；动作 URL 含 `hiker://empty` | `HkCard` 已按此建模；`isActionUrl` 覆盖 `hiker://empty` 前缀 |
| 官方规则文档“二级列表动态解析” | https://docs.189.tyrantg.com/docs/hikerview/help_rules.html（2026-11-15 访问，L172–190） | 高（官方） | 点二级列表结果时按规则再解析链接拿真正链接；`@lazyRule=` 只能用在最后一级、不能嵌套；`＆＆＆＆` 代替 `&&` | 选集 URL 里的 `@lazyRule=` 是第 5 步才求值的懒加载标记，本步只展示、不求值 |
| 官方规则文档“连续选集/章节标识” | 同上（L366–374） | 高（官方） | “软件默认会将连续的同一 col_type 识别为连续选集/章节”；自定义分组靠 `extra.cls` 加 `playlist`；多线路用 `playlist r1` / `playlist r2` 区分 | 支撑本步的分组设计：连续同 col_type 的选集卡片归为同一线路；三条验收规则都没写 `extra.cls`，走默认规则 |
| 官方规则文档“JS 预处理” | 同上（L208–210） | 高（官方） | preRule 在首页/搜索解析前各执行一遍 | 详情也跑 preRule（通常是取 cookie），与首页一致；文档没明说详情，本步按同款处理并记录 |
| 官方规则文档“视频多线路、外挂字幕、弹幕、歌词” | 同上（L266–304） | 高（官方） | 播放地址可用 `{urls, names, headers, subtitle, danmu, lyric}` JSON | 第 5 步才解析；本步选集 URL 原样展示、不解析 JSON |
| 三条验收规则的 detail JS（实测读出） | `~/workspace/miniapp/test-rules/` 三条 JSON | 高（真实规则） | 三条全是 `js:` 详情规则，输出都是卡片数组：首卡是信息卡（`pic_1` / `movie_1_vertical_pic`，含标题/简介/海报），随后是线路头卡片（`text_1` / `text_center_1`）+ 选集卡片（`text_5`，或无 col_type + `@lazyRule=` URL）；奇优/童话以 `res.data = d; setHomeResult(res)` 交结果，臻品fix 以 `setResult(d)` 交结果 | 本步设计的直接依据（见“验收规则的详情输出形状”）；`res.data` 形式暴露了第 1 步引擎的缺口，见“引擎补丁” |
| 社区 skill `hikerview-rule-generator` 的 `chapter_find` 等字段说明 | `Djjywj/openhands-skills`（本次 web 搜索只返回无关结果，按 §3 停止追查） | 低（未取到） | 无可用二手资料 | `chapter_find` / `movie_find` / `last_chapter_rule` 只按逆向报告字段表（小说/漫画类）处理；`last_chapter_rule` 本步不做（第 7 步小说/漫画专项） |
| 仓库现有实现：`HkExecutor.home` / `HkEngine` / `HkParser` | `api/hk/HkExecutor.java` 等 | 高（本地代码） | 首页流程：校验 → 拼 URL → OkHttp 抓页 → `prepare(rule, url, page, 类型, 正文)` → `evalPreRule` → `js:` 走 Rhino，否则 `parseHomeList`；`HkEngine` 的 `setHomeResult`/`setResult` 都进 `cardsFromJs`；`HkParser.parseHomeList` 按 `列表;标题;图片;描述;链接[;显示样式]` 解析，只留 `usable()` 卡片 | 详情复用同一条链：`HkExecutor.detail(rule, url)` + `HkParser.parseDetailList`（见“设计”） |
| 仓库现有实现：`HkListFragment` / `HkListActivity` | `ui/hk/HkListFragment.java` 等 | 高（本地代码） | 点卡片处弹 `hk_list_detail_pending`；`VodAdapter` 的 `Vod` id = 卡片 url、name = 标题、pic、remarks/content = 描述（`HkListHelper.toVods`） | 详情启动参数 = 规则标题 + 卡片 URL + 列表 Vod 的标题/海报/简介（作信息卡缺失时的回落）；详情页壳照 `HkListActivity` 的 Activity + Fragment 结构 |

### 验收规则的详情输出形状（设计依据，逐条读过规则原文）

- **奇优影院♔**（`detail_find_rule` 是 `js:`）：首卡 `pic_1`（标题
  “线路预览 共 N 条线路”、简介、thumb 海报）；随后每条线路一个
  `text_center_1` 头卡片（tab 名）+ 若干 `text_5` 选集卡片（URL =
  href + `@lazyRule=.js:…`）；末尾一个 `text_center_1` 免责卡片（URL =
  `MY_URL`）。以 `res.data = d; setHomeResult(res)` 交结果。
  `detail_col_type = text_2`，`sdetail_find_rule = *`。
- **童话**（`js:`，自包含、无远程依赖）：首卡 `pic_1`（标题“共有 N 条
  线路”、简介、海报、URL = 详情页地址）；随后每条线路一个 `text_1` 头
  卡片 + 若干**无 col_type**的选集卡片（URL = 绝对地址 +
  `@lazyRule=html&&#player-box&&script&&Html.js:eval(input)；；now`）。
  以 `res.data = d; setHomeResult(res)` 交结果。
  `detail_col_type = text_3`，`sdetail_find_rule = *`。
- **臻品fix**（`js:`）：首卡 `movie_1_vertical_pic`（标题/简介/海报、
  URL = 详情页地址）；随后 `text_center_1`“🔎线路”头 + 每条线路一个
  `text_2`/`text_3` 的 tab 卡片（URL = `hiker://empty@lazyRule=…` 的
  切换指令）+ `line_blank` + `text_center_1`“选集〰️(正序)”头（URL =
  `#noLoading#@lazyRule=…` 的排序切换指令）+ 选中线路的选集卡片
  （`text_2`/`text_3`，URL = href + `@lazyRule=…`）；末尾 `rich_text`
  `<br>`。以 `setResult(d)` 直接交数组。`detail_col_type = movie_1`，
  `sdetail_find_rule = *`。注意：它用 `getVar(MY_URL, '0')` 取默认值
  （见“引擎补丁”）；`request(...)` 只出现在 `@lazyRule=` 字符串里，
  详情执行时不跑。

### 方案比较

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 不做 | 保持点卡片弹“详情页还在做” | 零风险 | 用户已导入的规则点不开详情；第 4 步是用户 2026-11-15 13:34 亲口定“等第 4 步一起推”的下一步 | 否决 |
| B. 照搬上游 | 复刻魔影详情页 + `f22.detail` 全量语义（`hiker://page/` 子页面、`expandPage`、多线路切换、动作 URL 全套） | 与魔影行为最接近 | `detail()` 无源码、`expandPage` 无源码；子页面/线路切换要动执行器核心；工作量数倍于本步；三条验收规则都用不到子页面 | 否决（子页面与线路切换是第 5–7 步） |
| C. 窄化适配（推荐） | 新详情页（Activity + Fragment + 一个多类型 Adapter）；`HkExecutor.detail` 复用首页链；`HkDetailHelper.split` 按信息卡/线路头/选集三类切分（直译 `pickEpisodes` + 官方“连续同 col_type 即连续选集”）；选集列数跟 `detail_col_type` 走（外推）；点选集弹“播放还在做” | 改动集中在 mobile 新文件 + `HkExecutor`/`HkEngine`/`HkParser` 三处小补丁 + 列表页一行接线；复用首页同款取数/解析/状态机；三条验收规则的输出形状一次覆盖 | 分组与列数映射是窄化外推（见“已知缺口”）；`chapter_find`/`movie_find` 回落无规则可验；`sdetail`（`*`）本步不求值 | **采用** |
| D. 复用现有 `VideoActivity` 详情页 | 把 hk 详情结果塞进 `VideoActivity` 的 Vod/选集模型 | 不写新页面 | `VideoActivity` 绑定 `Site` + `Spider` 取数与播放器，hk 不走 `Spider`；要给核心详情页加 hk 分支，爆炸面比新页面大；且详情页还要先解决“无播放地址”的状态，第 5 步才有播放 | 否决 |

### 推荐

采用方案 C。理由：三条验收规则的详情输出形状已逐条读过，都是“信息卡 +
线路头 + 选集卡”的同一结构，窄化切分一次覆盖；取数与解析全部复用第 1 步
的引擎（只补 `.data` 拆包与 `getVar` 默认值两个缺口）；UI 是新的独立页面，
不碰现有详情页与播放器；回滚 = 删新文件 + 恢复列表页一行占位，引擎补丁
可独立回滚（回滚后臻品fix 首页也会回到单张空白卡片，见“回滚路径”）。

## 设计

### 文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `docs/miniapp-detail.md` | 新建（本文档） | 设计评审 + 实现记录 + 回滚 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkDetailHelper.java` | 新建 | `split`（信息卡/线路/选集切分）+ `columnsFor`（`detail_col_type` → 列数）+ `isEpisodeCard`（逐卡版 `pickEpisodes`） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkDetailActivity.java` | 新建 | 详情页壳：Toolbar（影片名 + 返回）+ `FrameLayout`（`@+id/hk_detail_container`，挂 Fragment）；`open(Context, ruleTitle, url, name, pic, desc)` 启动入口；壁纸由 `BaseActivity.setContentView` 自动加 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkDetailFragment.java` | 新建 | 详情 Fragment：后台线程调 `HkExecutor.detail` → `HkDetailHelper.split` → 单 RecyclerView（GridLayoutManager + SpanSizeLookup）渲染信息/线路头/选集；加载/空/错三态 + 下拉重载；点选集弹 `hk_detail_play_pending` |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkDetailAdapter.java` | 新建 | 多类型 Adapter：信息（海报 + 标题 + 简介）、线路头、选集 chip（复用 `adapter_episode_grid.xml` 的 `@+id/text`）；选集点击回 Fragment |
| `app/src/mobile/res/layout/activity_hk_detail.xml` | 新建 | 照 `activity_hk_list.xml`：MaterialToolbar + `FrameLayout`（container） |
| `app/src/mobile/res/layout/fragment_hk_detail.xml` | 新建 | 照 `fragment_hk_list.xml`：SwipeRefreshLayout → ProgressLayout → CustomRecyclerView（无分类 chips） |
| `app/src/mobile/res/layout/item_hk_detail_info.xml` | 新建 | 信息头：海报 ImageView（96×144）+ 标题（18sp 粗体）+ 简介（14sp，最多 6 行）；根布局套 `shape_lab_card`（黑 20% 底，白字在亮壁纸上可读，与 aa77b6d9 同款） |
| `app/src/mobile/res/layout/item_hk_detail_line.xml` | 新建 | 线路头：一行 16sp 粗体白字（上下 12dp 边距） |
| `app/src/mobile/AndroidManifest.xml` | 改 | 注册 `HkDetailActivity`（无 intent-filter，只有 mobile 代码直接启动；`parentActivityName=".ui.hk.HkListActivity"`；照 `HkListActivity` 的写法） |
| `app/src/main/res/values/lab_strings.xml` | 改 | 加 4 条：`hk_detail_play_pending`（播放还在做）、`hk_detail_error`（详情加载失败）、`hk_detail_retry`（重试）、`hk_detail_no_episodes`（暂无选集）；删 1 条：`hk_list_detail_pending`（列表页不再用，本步唯一引用点改掉后无引用） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkExecutor.java` | 改 | 加 `detail(rule, detailUrl)`（见下）+ `HomeOutcome`/`homeDetailed`（列表 0 条命中诊断，见下） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkEngine.java` | 改 | 两处引擎补丁（见下）：`cardsFromJs` 拆 `res.data`；`getVar`/`getMyVar` 支持默认值 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkParser.java` | 改 | 加 `parseDetailList`（详情选择器解析，标题-only 的头卡片保留）+ `parseHomeListDetailed`（返回命中数，供诊断） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkListHelper.java` | 改 | 不改——`pickCards`/`toVods`/`isActionUrl`/`styleFor` 原样复用（详情切分另放 `HkDetailHelper`，不扩 `HkListHelper` 的职责） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkListFragment.java` | 改 | 点卡片改成开详情页（`HkDetailActivity.open(…)`，传规则标题 + 卡片 URL + Vod 的标题/海报/简介）；`loadPage` 改调 `homeDetailed`，首屏 0 条时把诊断写进新增的 `diagText` |
| `app/src/mobile/res/layout/fragment_hk_list.xml` | 改 | 加一行 `diagText`（12sp，默认 GONE，只在首屏 0 条时显示命中诊断） |
| `docs/miniapp-foundation.md` | 改 | 删过时的“`fyAll` 未替换”缺口句（第 3 步已补 `fyAll`） |

### 关键契约

- **启动参数**：`HkDetailActivity.open(context, ruleTitle, detailUrl, name, pic, desc)`——
  规则标题 + 详情页 URL（列表 Vod 的 id）+ 列表卡片的标题/海报/简介（信息卡
  缺失时的回落）。不传整条规则 JSON（与列表页同款理由）。
- **详情取数**（`HkExecutor.detail(rule, detailUrl)`，后台线程）：
  1. 规则校验（与 `home()` 同款 `validate()`）；`detail_find_rule` 为空
     → 抛“规则缺少 detail_find_rule”。
  2. 详情 URL 先去掉 `@lazyRule=` / `@rule=` 后的部分（二级解析标记只在
     第 5 步求值，取数时只要干净的页面地址）；相对地址按规则 `url` 补全
     （`HkParser.resolveUrl`）。
  3. 请求头 = 规则 `firstHeader` + `ua`（与首页同款），编码默认 UTF-8；
     OkHttp 同步抓页（`fetch(spec)` 复用）。
  4. `engine.prepare(rule, 详情 URL, 1, "detail", 正文)` → `evalPreRule()` →
     `js:` 走 `evalFindRule`，否则 `parseDetailList`。
  5. 主规则出 0 张卡片且 `chapter_find` 或 `movie_find` 非空时，用那条
     （`chapter_find` 优先）对同一页再跑一次（JS 或选择器同款路径），取其
     结果。语义是外推（见“已知缺口”）；三条验收规则这两字段都空，走不到。
  6. `sdetail_find_rule`（三条全是 `*`）本步不求值：单层详情已够用；
     `*` = 继承主规则，二级详情是第 5 步的懒加载/二次解析再处理。
  7. `last_chapter_rule` 本步不做（小说/漫画类，第 7 步专项）。
- **详情选择器解析**（`HkParser.parseDetailList`）：与 `parseHomeList` 同
  字段格式（`列表;标题;图片;描述;链接[;显示样式]`），唯一区别是**标题-only
  的头卡片保留**（`parseHomeList` 只留 `usable()` 即标题 + 链接双全的卡片，
  会把无链接的线路头丢掉；详情需要线路头）。实现：`parseHomeList` 内部加
  `keepTitleOnly` 开关，公开方法各走各的默认值。
- **切分**（`HkDetailHelper.split(cards, rule, fallback)`，纯函数）：
  1. 信息卡：第一张 colType 为 `pic_1` 或 `movie_1_vertical_pic` 的卡片
     （标题/简介/海报取自它，缺的字段用列表 Vod 的回落值补）；取走后不再
     参与选集判定。没有信息卡时信息 = 回落值（列表 Vod 的标题/海报/简介）。
  2. 其余卡片按序走：线路头 = colType ∈ {`text_1`, `text_2`, `text_3`,
     `text_center_1`} 且 URL 为空、或为动作 URL、或以 `#` 开头 → 开一个新
     线路（标题去 HTML 标签）；选集 = `isEpisodeCard` 为真 → 归入当前线路，
     尚无线路时归入默认线路“选集”；其余（`line_blank`、`rich_text`、免责
     卡片等）丢弃。
  3. `isEpisodeCard` 是报告 `pickEpisodes` 的逐卡版：先算整表有没有
     `text_5`——有则只有 `text_5` 是选集；没有则用第二遍谓词（colType 不含
     button/input/header/tab/title 且 ≠ `text_1`，URL 非动作且以 `http`
     开头或含 `@lazyRule=`/`@rule=` 或以 `js:` 开头；卡片须 `usable()`）。
     注意：信息卡已先取走、线路头已先判定，臻品fix 的 `hiker://empty`
     tab 卡片是动作 URL、不会被误判为选集。
  4. 末尾丢掉 0 集的空线路；全表 0 集时详情页显示信息 + “暂无选集”
     （`hk_detail_no_episodes`）。
  5. 标题去 HTML：`Html.fromHtml(title, FROM_HTML_MODE_LEGACY).toString().trim()`
     （minSdk 24 可用；规则标题里常带 `<small><font>`）。
- **列数**（`HkDetailHelper.columnsFor(detailColType)`，外推）：`text_2` →
  2 列，`text_3` → 3 列，`movie_1` → 1 列（单列行），其余（含空、`text_1`）
  → 4 列。验收三条分别是 text_2 / text_3 / movie_1，真机可对照。
- **渲染**：单 RecyclerView + GridLayoutManager（列数见上）+
  SpanSizeLookup（信息与线路头占满整行，选集占 1 列）；信息与线路头、
  选集三种 viewType；海报用 `ImgUtil.load(标题, 海报地址, ImageView)`（
  与列表页同款加载器）；点选集弹 `hk_detail_play_pending`，不进播放器；
  长按什么都不做。
- **三态**：加载中 `showProgress()`；成功 `showContent`（有信息无选集也
  是成功态 + “暂无选集”行）；失败 `showError(hk_detail_error, 原因,
  hk_detail_retry, 点重试重跑)`；下拉刷新重跑详情。
- **规则文件被删但条目还在**：详情页读不到规则 → 弹
  `hk_list_rule_missing`（复用列表页同款串）+ `finish()`，与列表页一致。

### 引擎补丁（第 1 步的两个缺口，本步必须补，否则验收规则跑不通）

1. **`cardsFromJs` 拆 `res.data`**：规则常以 `res.data = d; setHomeResult(res)`
   交结果（奇优/童话的详情、臻品fix 的首页都这么写）。第 1 步的
   `cardsFromJs` 收到 `{data: [...]}` 对象时把它当成一张卡片（标题/链接全
   空），等于白跑。补丁：`Scriptable` 分支先看 `data` 属性——是 `NativeArray`
   就按数组转；`String` 分支 JSON 是对象且有 `data` 数组时同样拆。**这同时
   修了第 3 步首页的臻品fix（JS 路径）：之前它会返回一张空白卡片。**第 3 步
   文档“臻品fix 同上（JS 路径）”一句的前提至此才成立，特此更正。
2. **`getVar(k, 默认值)`**：臻品fix 详情用 `getVar(MY_URL, '0')` 选线路，
   缺省要回 `'0'`。第 1 步的 `getVar` 只认一个参数，多余参数被 JS 忽略，
   缺省回 `null` → `lists[null]` → 整段详情 JS 抛错。补丁：BOOTSTRAP 改成
   `function getVar(k, d) { return __hk.getVar(k, d); }`，Bridge 加
   `getVar(String, String)`（缺省回默认值；1 参数版委托给它）；`getMyVar`
   同款加一对（阅动漫用 `getMyVar`，第 7 步用得上，先对齐）。`putVar`
   的对象形式（`putVar({key, value})`）只出现在 `@lazyRule=` 字符串里，
   详情执行时不跑，不做。

### 列表 0 条命中诊断（第 3 步遗留）

- `HkExecutor.homeDetailed(rule, page, classId)` 返回 `HomeOutcome {cards, diag}`；
  `home()` 委托给它（签名不变）。`diag` 只在首屏 0 条时展示，内容按规则类型：
  - 选择器规则：`页面 {KB}KB；列表选择器命中 {matched} 项，得到 {cards} 张卡片`——
    `matched` 来自新增的 `HkParser.parseHomeListDetailed`（列表选择器选到的
    元素数，不过滤）；`matched == 0` → 规则未命中页面结构，`matched > 0` 且
    `cards == 0` → 命中了但卡片都不可用（缺标题或链接）。
  - JS 规则：`页面 {KB}KB；规则返回 {cards} 条`，未调 `setResult` 时追加
    `（规则未调用 setResult）`（`HkEngine` 加 `wasResultSet()` 标记）。
- 展示：`fragment_hk_list.xml` 加一行 `diagText`（12sp，默认 GONE）；
  `HkListFragment.loadPage` 改调 `homeDetailed`，首屏 0 条时把 `diag` 写进去
  并显示，重载/翻页时隐藏。`ProgressLayout` 的空态在手机端没有描述控件
  （见其 `applyEmptyContent` 注释），所以不用它，诊断走独立的一行文本。

### 不做的（明确边界）

- 懒加载播放（第 5 步）、搜索（第 6 步）、云口令/导出（第 6–7 步）、首页
  内嵌（第 7 步）、48 种 `col_type` 全覆盖（第 7 步）、`hiker://page/` 子
  页面（第 7 步；本步这类卡片的 URL 原样展示、不展开）、`webRule://`（第 7
  步）、线路切换（规则内 `putVar` + `refreshPage`，第 5–7 步；臻品fix 的
  tab 卡片本步只当线路头渲染、点它不切换）、`last_chapter_rule`（小说/
  漫画类，第 7 步）、多线路字幕/弹幕/歌词 JSON（第 5 步才解析）、详情页收
  藏/历史（现有详情页的追更与历史走 `SiteApi`，hk 详情本步不接——第 5 步
  播放接通时再定）。

## 验收标准

代码层面可确认（提交前逐行重读 + `git diff --check`）：

1. 列表页点卡片不再弹 `hk_list_detail_pending`（该串已删）；详情页注册在
   mobile 清单里；main 源集零改动。
2. `cardsFromJs` 拆 `res.data`：对象/字符串两种输入都覆盖；无 `data` 时
   行为与原来一致（数组直接转、单对象当一张卡片）。
3. `getVar(k, 默认值)`：1 参数调用行为不变；2 参数缺省回默认值。
4. `parseDetailList` 只在详情路径用，`parseHomeList` 行为不变；
   `home()` 签名与返回值不变（`homeDetailed` 是新增方法）。
5. `menu_nav.xml` 仍 6 项；`BaseLoader` 未动；TV/leanback 源集零改动；
   无 `Json.safeListElement` 式返回类型误用、无泛型原始类型。

只能真机验（需一次 GitHub 构建 + 用户装新版 + 三条验收规则已导入）：

6. 奇优影院♔：列表点一部电影/电视剧进详情，标题/海报/简介是真的；选集按
   线路分组、顺序对；点选集弹“播放还在做”，不进空白播放页。
7. 童话：同上（电影/连续剧任选一部）；简介与海报是真的。
8. 臻品fix：同上（只验详情不点播）；线路头与选集分得开（“🔎线路”与 tab
   卡片不混进选集，选集是第一条线路的集数）。
9. 列表页零回归：分类/翻页/三处入口照旧；首屏 0 条时诊断行出现（拿一条
   故意写错 find_rule 的规则可验，或等真实 0 条场景）。
10. 规则文件被删但条目还在：点卡片进详情弹“规则不存在或已删除”，不崩。

## 回滚路径

- 整步回滚：`git revert <本提交>`（一个原子提交，recovery tag 见提交信息）。
  新文件（详情页 Activity/Fragment/Adapter/Helper/四个布局/任务文档）随
  revert 一起消失；列表页点卡片恢复成弹“详情页还在做”；引擎补丁与诊断
  一起回去——注意：回滚后臻品fix 首页与奇优/童话详情会再次因 `res.data`
  未拆而只剩空白卡片（第 1 步的已知缺口，不影响其它功能）。
- 局部回滚：只回滚列表页接线（`HkListFragment.onItemClick` 改回弹
  `hk_list_detail_pending`，该串需从删除中恢复）而保留详情页代码：详情页
  变成不可达代码，下次构建不受影响。引擎补丁（`.data` 拆包、`getVar` 默认
  值）建议保留——它们同时修了第 3 步首页的臻品fix。

## 已知缺口与风险

- **分组与列数映射是窄化外推**：`split` 的线路头判定与 `columnsFor` 的
  2/3/1 列映射是按三条验收规则的实际输出形状定的，未经真机对照；其它
  规则（尤其 `extra.cls` 带 `playlist` 的自定义分组）可能分得不对。
  `extra.cls` 的 `playlist` 语义本步不解析（验收规则没写它），第 7 步再补。
- **`chapter_find` / `movie_find` 回落无规则可验**：三条验收规则这两字段
  都空；回落只在主规则出 0 张卡片时触发，语义是按逆向报告字段表外推的。
- **`sdetail_find_rule`（`*`）本步不求值**：三条全是 `*`（继承主规则）；
  需要二级详情的规则（详情页里再跳一层）本步只显示第一层。
- **详情页编码默认 UTF-8**：GBK 站的详情简介可能乱码（首页有 URL 修饰符
  可指定编码，详情 URL 来自卡片、无修饰符）；三条验收规则的站都是 UTF-8。
- **`getVar` 默认值只补了 `getVar`/`getMyVar`**：`putVar` 的对象形式、
  `refreshPage()`、`request()` 仍是第 1 步的桩/缺失——它们只出现在
  `@lazyRule=` 字符串里，详情执行时不跑；第 5 步再补。
- **详情页不接追更/历史**：现有详情页的追更与历史走 `SiteApi`，hk 详情本
  步不接；第 5 步播放接通时再定。
- **性能**：详情每次打开都重新抓页 + 重建 Rhino 引擎（与列表页同款做法）；
  无缓存，连续点开同一部会重复请求（`HkSiteAdapter.CACHE` 是第 7 步的事）。

## 实现清单

- [x] 设计评审（本文档）
- [x] `HkEngine` 两处补丁（`.data` 拆包、`getVar`/`getMyVar` 默认值）
- [x] `HkExecutor.detail` + `homeDetailed`/`HomeOutcome`
- [x] `HkParser.parseDetailList` + `parseHomeListDetailed`
- [x] `HkDetailHelper.split` / `columnsFor` / `isEpisodeCard`
- [x] `HkDetailActivity` + `activity_hk_detail.xml` + 清单注册
- [x] `HkDetailFragment` + `fragment_hk_detail.xml`（取数 + 三态 + 下拉重载）
- [x] `HkDetailAdapter` + `item_hk_detail_info.xml` + `item_hk_detail_line.xml`（选集 chip 复用 `adapter_episode_grid.xml`）
- [x] `HkListFragment` 点卡片改成开详情页 + `homeDetailed` + `diagText`
- [x] `fragment_hk_list.xml` 加 `diagText`；`lab_strings.xml` 加 4 条、删 1 条
- [x] `docs/miniapp-foundation.md` 删过时的 `fyAll` 缺口句
- [x] 逐行重读 + `git diff --check`（修了 evalFindRule 拼行、详情布局包名两处）
- [ ] guard finish（本地提交，不推不编）
