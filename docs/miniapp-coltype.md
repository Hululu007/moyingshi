# 海阔小程序第 7 步之六：全部 col_type 布局补齐

- 任务：miniapp-coltype（standard lane；10 步串行工程的第 6 步）
- HEAD：f4028462（main，本地超前 origin 五笔，未推）；工作区干净后开工
- 完成句：把 `HkListHelper.styleFor` 从第 3 步的 6 种窄化映射升级成 42 种实测 col_type 全表（Style + 显式列数，单源供给列表页、搜索页与内嵌首页），未知类型走默认矩形卡片、不崩不空白；只改手机端，本地提交，不推、不编、不触发构建、不动版本记录空间。
- 时间：2026-11-15 18:57 CST 开工；估计 1.5 小时，预期 20:30 CST 前完成并提交。
- 最便宜的决定性验证：逐行重读改动 + `git diff --check`（本地无 JDK/Android SDK，编译检查等推上去之后的 GitHub 构建；真机验收等用户）。

## Recovery anchor

- 目标与验收：见“验收标准”一节；真机验不了的项已在验收表里标明。
- 计划状态：任务文档已写（本文档）；实现待做——`HkListHelper` 全表（`styleFor` + 新增 `columnsFor`，共用一张内部表）、两处 `setStyle` 改走 col_type 单源、`Product.getSpec` 加显式列数重载、`toVods` 仅对 rich_text/long_text 去 HTML 标签。
- 已完成证据：逐行重读改动（`HkListHelper` 全文 + 两处 `setStyle` + `Product` 重载的 diff 全量读过；复核修 1 处：`toVods` 注释里一句错乱的“见下”已删）；`git diff --check` 通过；映射逻辑用 Python 镜像校验过（从 Java 源码解析出 45 条精确登记，42 种实测类型全部符合文档表，movie_4/5/6 与 19 个边界用例全部通过，4255 条里只有 2 条 x5_webview_single 落到默认形态）；改动只在 guard scope 的 5 个文件内；TV/leanback、`BaseLoader`、`menu_nav.xml` 未动；无 `Json.safeListElement` 式返回类型误用、无泛型原始类型、无新增 dialog。
- 未验证的改动：全部（本地无 JDK/Android SDK，编译检查等推上去之后的 GitHub 构建；真机验收等用户）。
- 未解决风险：见“已知缺口与风险”。
- 下一步：`bash .codex/scripts/task_guard.sh finish --verified <证据> --commit-message <消息>`，随后 `git tag -a` 建 recovery tag。

## 1. 证据表

| 证据 | 位置 | 等级 | 支撑的结论 | 适用性/注意 |
|---|---|---|---|---|
| 4255 条规则合集的 col_type 分布（实测统计） | `~/workspace/user/files/4255_____10_q31w.json`（57MB，2026-11-15 现场统计） | 高（本地实测） | 42 种不同取值、全部 4255 条都有非空 col_type；前三名 movie_3 2234 / movie_2 563 / movie_3_marquee 503，占 77.6%；完整 42 种名单与计数见 §4 全表 | “48+ 种”是官方表/社区 skill 的说法；真实合集里只有 42 种，本步按 42 种实测 + 数字族通用解析覆盖未见过的编号变体 |
| 逆向报告 §2.5/§7.1/§7.12/§7.13 | `~/workspace/user/files/__________8_3azg.md` | 高（反编译源码引用） | `col_type` 是规则字段；`pickCards` 按规则 col_type 筛卡片；`x5_webview_single` 是内联 WebView 页；卡片字段 title/url/pic/desc/sel/colType/extra | 报告里 **没有** o0 卡片类型表的全文（w02 5772 行未收入，L8560 明说）；逐类型的渲染形态在报告里查不到，不能直译 |
| 逆向报告 §3 g22 字段表 | 同上 | 高 | `detail_col_type` / `sdetail_col_type` 是详情类型，与列表 col_type 分开 | 详情类型不在本步范围（第 4 步 `HkDetailHelper.columnsFor` 已有选集网格映射），本步只动列表 col_type |
| 评估报告 §2 列表页行 + §3.2 完整版 | `~/workspace/miniapp/ASSESSMENT.md` | 高 | “全部 48+ 种 col_type 布局”是完整版长尾；MVP 只做常用 6–8 种（movie_1/movie_3/text_1/2/3 等），其余 fallback 默认卡片 | 本步就是把这个长尾项做完 |
| 第 3 步任务文档 | `docs/miniapp-list.md`（`col_type` 映射一节 + 已知缺口） | 高（本地代码/文档） | 窄化映射：movie_3/movie_2→rect，movie_1/text_1/2/3→list，其余默认 rect；“`col_type` 映射是窄化外推：48 种表未重新取到” | 本步的升级对象；`movie_1→list`、`text_*→list` 沿用，不改语义 |
| 第 5 步任务文档 | `docs/miniapp-home-embed.md` | 高 | 内嵌首页复用 `HkListFragment` 同一条渲染链路，样式从 `HkListHelper.styleFor` 取 | 本步只改 `HkListHelper` + 两处 `setStyle` 的取样式方式，内嵌首页自动受益，不另起映射 |
| 官方规则文档 col_type 表 | https://docs.189.tyrantg.com/docs/hikerview/help_rules.html（评估时确认可访问） | —（本次未取到） | 本次 `browser.open` 取该页失败（unresolvable，未重试）；两次 web 搜索只返回无关结果，按 AGENTS.md §3 停止追查 | 官方表本次未重新核对；全表形态按“名字语义 + 42 种实测分布”外推，逐条在 §4 标明直译/外推 |
| 社区 skill 48+ 种 col_type 表 | `Djjywj/openhands-skills` hikerview-rule-generator（评估时引用） | —（本次未取到） | 拟取的 raw 路径 404；搜索无果，按 §3 停止追查 | 同上，未重新核对 |
| 本地渲染能力：`Style` | `app/src/main/java/…/bean/Style.java` | 高（本地代码） | 只有 3 种视图：`rect`（ratio，默认 0.75 海报）、`oval`（圆图，ratio 1.0）、`list`（单列行）；`getViewType()` → RECT/OVAL/LIST | 全表必须落到这 3 种 + ratio 上；没有文字多列网格、没有横向滚动 holder——形态缺口见 §5 |
| 本地渲染能力：`VodAdapter` + 3 个 holder | `app/src/mobile/java/…/ui/adapter/VodAdapter.java`、`ui/holder/VodRectHolder.java`、`VodListHolder.java`、`VodOvalHolder.java` | 高 | RECT/OVAL holder 取 `size()` 定图宽高；RECT 显示标题+备注（desc→remarks）+年份/站点（hk 为空）；LIST 显示标题+备注+小图；OVAL 只显示标题+圆图，**desc 无处显示**；LIST 行图片位固定，无图时占位 | 字段取舍的依据：oval 类型的 desc 注定不显示（数据仍在 `Vod.content` 里）；其余类型 desc 走 remarks 显示 |
| 本地渲染能力：列数与尺寸 | `app/src/mobile/java/…/Product.java`（`getColumn`/`getSpec`）、`HkListFragment.setStyle`、`HkSearchFragment.setStyle` | 高 | 网格列数 = `Product.getColumn(context, style)`（窄屏默认 3 列：size 默认 2，\|2-5\|=3；宽屏 5 列）；卡片尺寸 `getSpec` 按同一列数算；`VodRectHolder.size()` 把 spec 写进根宽/图高，**列数与尺寸必须成对改**，只改 span 会导致图尺寸错位 | 本步加 `Product.getSpec(context, style, column)` 重载，与现有私有 `getSpec(context, space, column, style)` 同款算式，只换列数 |
| 分页机制是纯纵向 | `app/src/mobile/java/…/ui/custom/CustomScroller.java` | 高 | `onScrolled` 要求 `dy > 0` 且 `!canScrollVertically(1)` 才翻页；`checkMore` 同样看 `canScrollVertically(1)` | 横向 LayoutManager 下 `canScrollVertically(1)` 恒 false → `checkMore` 会反复触发翻页直至页码耗尽，且横向滑不到底没有“下一页”入口；**真横向滚动（marquee）本步不做**，降级为竖向网格，见 §5 |
| 详情页已有列数映射先例 | `app/src/mobile/java/…/api/hk/HkDetailHelper.java:columnsFor` | 高 | `detail_col_type → 选集网格列数`：text_2→2、text_3→3、movie_1→1、其余 4 | 本步 `HkListHelper.columnsFor` 照同一模式写（返回 0 = 走 App 默认列数） |
| 验收规则 | `~/workspace/miniapp/test-rules/` | 高 | 三条验收规则全是 movie_3；阅动漫.hkzip 的 `col_type` 是 `icon_round_small_4`（`detail_col_type` 是 movie_1） | movie_3 必须零回归；icon_round_small_4 是本步的必验类型 |

## 2. 方案比较

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 不做 | 保留第 3 步窄化映射（6 种 + 默认 rect） | 零风险、零回归 | 42 种里 36 种全挤在默认 3 列海报网格里：icon 类被裁成方图、text 类占位图难看、pic_1 单图被压成 3 列小图、movie_2 本应 2 列却 3 列；第 7 步“全部 col_type”长尾项永远欠着 | 否决（本步任务就是补齐它） |
| B. 照搬上游（魔影 o0 全表 + kv1 布局） | 按逆向报告复刻魔影 48 种布局 | 与魔影行为最接近 | o0 表无源码（w02 未收入报告）；kv1 无源码；魔影的布局文件只有 `activity_hk_list.xml` 等壳，没有逐类型 item 布局；等于按二手表猜 48 套布局，工作量数倍于本步且无法验证 | 否决（无源码可搬，只能按名字语义 + 实测分布窄化适配） |
| C. 窄化适配——全表映射到现有 3 种 holder（推荐） | `HkListHelper` 一张内部表：42 种实测类型逐一映射到 rect/oval/list + ratio + 显式列数；数字族（movie_N/pic_N/icon_N…）加通用解析，覆盖未见过的编号变体；未知类型 fallback 默认 rect；两处 `setStyle` 改走 col_type 单源；列数与尺寸成对改（`Product.getSpec` 重载） | 改动集中在 mobile 4 个代码文件 + 1 个任务文档；复用首页同款 3 种 holder，不新增布局/holder；movie_3（77.6% 里的头名）形态与列数与今天完全一致，验收三条零回归；内嵌首页与搜索页同源受益；回滚 = 一个 revert | 形态是外推（名字语义 + 分布），未经真机对照；marquee 真横向滚动、text_2/3 多列文本网格、card 背景、居中对齐 4 项无对应 holder，只能降级（见 §5）；icon/pic 的 ratio（1.0 方图 / 1.33 横图）是按名字猜的 | **采用** |
| D. 全表 + 新 holder/新布局 | 为 marquee 横向条、text 多列网格、card 背景、圆角图标各写新 holder + 新布局 + 新分页机制 | 形态最接近海阔原 App | 新 holder ×4、新布局 ×4、横向分页机制要重写 `CustomScroller` 的纵向假设、还要处理横向条里的翻页入口；爆炸面大、一次做不完、真机验证轮次多；与“只改手机端、窄化”原则冲突 | 否决（降级项留给后续步骤，见 §7 缺口） |

### 子问题“列数要不要按规则字面走”的比较

| 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 列数一律走 App 默认（`Product.getColumn`） | 不动 `Product`，改动最小 | movie_2/movie_4/5/6、pic_1、icon_5 等“列数就是类型定义本身”的类型无法区分，全表只剩 holder 种类不同，补齐不彻底 | 否决 |
| 列数按规则字面走，尺寸成对改（推荐） | movie_2=2 列、movie_3=3 列、pic_1=1 列，形态与类型名一致；`getSpec` 重载与现有算式同款，风险可控 | 宽屏/窄屏下字面列数不变（海阔原 App 同样字面列数）；用户“字号/列数”设置对小程序列表不再生效（规则列数优先） | 采用；movie_3 字面 3 列 = 窄屏默认 3 列，验收三条在默认设置下视觉零变化 |

## 3. 推荐

采用方案 C + 列数按规则字面走。理由：42 种里 77.6% 是 movie_3/movie_2/movie_3_marquee 三种影视海报形态，全表映射后这三类一次覆盖且 movie_3 零回归；icon/pic/text 各族按名字语义分流到 oval/rect/list 三种现成 holder，不引入新布局；未知类型 fallback 默认 rect 的行为与第 3 步一致（今天所有冷门类型本来就走这条路），是“补齐”不是“改行为”；回滚 = 一个 revert。

## 4. 设计

### 文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `docs/miniapp-coltype.md` | 新建（本文档） | 设计评审 + 全表 + 实现记录 + 回滚 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkListHelper.java` | 改 | `styleFor` 升级成全表；新增 `columnsFor`；两者共用一张内部表（`formFor`）；`toVods` 仅对 rich_text/long_text 卡片去 HTML 标签 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkListFragment.java` | 改 | `setStyle(Style)` → `setStyle(String colType)`：内部同时取 `styleFor` + `columnsFor`，列数与尺寸成对传给 adapter/layout |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkSearchFragment.java` | 改 | 同上（搜索页与列表页同一处取样式；内嵌首页复用 `HkListFragment`，自动受益） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/Product.java` | 改 | 加 `getSpec(Context, Style, int column)` 重载：与现有 `getSpec(Context, Style)` 同款算式，只把列数换成显式值；不改现有方法 |

### 关键契约

- **单源映射**：类型 → 形态只在 `HkListHelper.formFor` 一张内部表里定义；`styleFor` / `columnsFor` 都是它的薄壳；两个 Fragment 只传 col_type 字符串，不各自写映射。内嵌首页（第 5 步）复用 `HkListFragment`，样式自动一致。
- **形态三元组**：每种类型 = `Style`（rect+ratio / oval / list）+ 列数（0 = 走 App 默认列数；list 形态下恒 0，无意义）+ 字段取舍（见 §4 全表“字段”列）。
- **显式列数**：`columnsFor` 返回 1–6（ clamp 上限 6，海阔表里最大就是 movie_6 / icon_5 一档）；0 = 默认。Fragment 里列数与尺寸成对取：`columns > 0 ? Product.getSpec(context, style, columns) : Product.getSpec(context, style)`，span 同值。`getSpec` 重载的字面列数不随横竖屏减 1（规则列数是设备无关的；现有默认路径横屏 rect 才减 1）。
- **数字族通用解析**：没见过的编号变体（如将来出现 movie_4/5/6、icon_6、pic_4）按“族前缀 + 首个数字”解析，不用逐一加表；族规则见 §4 表后“通用解析”一节。解析不到数字或数字越界 → fallback。
- **fallback**：空、未知、无法解析的 col_type 一律 `Style.rect()`（0.75 海报）+ 默认列数 —— 与第 3 步今天的行为完全一致，绝不崩、绝不空白（`pickCards` 保证至少有可用卡片才进列表；0 条时走既有空态 + 第 4 步的命中诊断）。
- **控制类/结构类类型**（`input` / `flex_button` / `scroll_button` / `icon_1_search` / `big_big_blank_block`）：在海阔原 App 里是输入框/按钮行/滚动按钮/空白占位，不是内容卡片；本 App 没有对应控件，映射为单列文字行（有标题就显示、可点，点开进详情错误态，不崩）；`big_big_blank_block` 的卡片通常无标题，会被 `pickCards` 的 `usable()`（title+url 非空）直接滤掉，等于不占位——这是正确行为，不是 bug。
- **`x5_webview_single`**：沿用第 7 步之三行为（rect 默认列数；卡片标题回落“网页”），本步不改形态；内联 WebView 渲染仍是后续步骤。
- **字段取舍总规则**（`toVods` 本来就统一做，见 §4“字段”列）：标题 → `Vod.name`（卡片标题），图片 → `Vod.pic`，描述 → `Vod.remarks` + `Vod.content`；RECT 显示标题+备注、LIST 显示标题+备注+小图、OVAL 只显示标题+圆图（desc 无处显示，数据仍在 `content` 里）。仅 rich_text/long_text 两类在 `toVods` 里对标题/描述去 HTML 标签（规则这类卡片常带 `<font>`/`<small>` 标签，不去会原样显示标签文本；其余类型不动，避免改动验收三条的渲染）。
- **不做的**：`detail_col_type` / `sdetail_col_type`（详情选集网格，第 4 步 `HkDetailHelper.columnsFor` 已有映射）；TV/leanback；`BaseLoader`；底栏（`menu_nav.xml` 仍 6 项）；新 holder/新布局（见 §5 降级项）。

### 全表（42 种实测 + 族通用 + fallback）

计数 = 4255 条合集里该 col_type 的规则数。来源：直译 = 逆向报告/已发布代码有据可依；外推 = 按名字语义 + 分布定的形态，未经真机对照。字段列只写与总规则不同的地方。

**影视海报族（movie_*，共 2907 条，68.3%）**

| col_type | 计数 | 布局形态 | 列数 | 字段 | 来源 |
|---|---|---|---|---|---|
| `movie_3` | 2234 | 海报网格 rect 0.75 | 3 | 标题+海报+备注 | 直译（第 3 步已发布；三条验收规则全是它） |
| `movie_2` | 563 | 海报网格 rect 0.75 | 2 | 同上 | 外推（名字语义：2 列海报；第 3 步已认 rect，本步只补列数） |
| `movie_3_marquee` | 503 | 海报网格 rect 0.75（横向滚动降级为竖向网格，见 §5） | 3 | 同上 | 外推（名字语义；横向滚动无对应分页机制） |
| `movie_1` | 40 | 单列行 list | — | 标题+描述+小图 | 直译（第 3 步已发布） |
| `movie_1_left_pic` | 50 | 单列行 list（左图行） | — | 同上 | 外推（名字语义：1 列左图行；无左图专用 holder，用 list 行近似） |
| `movie_1_vertical_pic` | 40 | 单列行 list（竖图行） | — | 同上 | 外推（名字语义；list 行图位固定尺寸，竖图会被裁切，见 §7） |
| `movie_4` / `movie_5` / `movie_6` | 0（合集里没有） | 海报网格 rect 0.75 | 4 / 5 / 6 | 同上 | 外推（族通用解析；海阔表里有这三档，合集未出现） |

**文字族（text_* / long_text / rich_text，共 364 条）**

| col_type | 计数 | 布局形态 | 列数 | 字段 | 来源 |
|---|---|---|---|---|---|
| `text_1` | 151 | 单列行 list | — | 标题+描述，无图占位 | 直译（第 3 步已发布） |
| `text_2` | 34 | 单列行 list | — | 同上 | 直译（第 3 步已发布；多列文本网格无 holder，降级单列行） |
| `text_3` | 141 | 单列行 list | — | 同上 | 直译（第 3 步已发布；同上） |
| `text_4` | 12 | 单列行 list | — | 同上 | 外推（同族） |
| `text_5` | 11 | 单列行 list | — | 同上（详情/选集语义在列表页按普通文字行处理） | 外推（同族；第 4 步 text_5 是选集识别优先类型，那是详情页逻辑，本步不动） |
| `text_center_1` | 20 | 单列行 list（居中降级为左对齐，见 §5） | — | 同上 | 外推（名字语义） |
| `long_text` | 5 | 单列行 list（长文本行；标题/描述去 HTML 标签） | — | 标题+描述（去标签后） | 外推（名字语义） |
| `rich_text` | 5 | 单列行 list（富文本行；标题/描述去 HTML 标签） | — | 同上 | 外推（名字语义） |

**图片族（pic_* / card_pic_*，共 139 条）**

| col_type | 计数 | 布局形态 | 列数 | 字段 | 来源 |
|---|---|---|---|---|---|
| `pic_1` | 24 | 大图 rect 1.33（横图） | 1 | 标题+大图+备注 | 外推（名字语义：1 列单图通栏；横图 ratio 是猜的，见 §7） |
| `pic_1_card` | 45 | 大图卡片 rect 0.75 | 1 | 同上 | 外推（名字语义：1 列卡片图；card 在海阔里是带背景的卡片，本 App 无 card 背景，用 rect 近似） |
| `pic_1_full` | 5 | 大图 rect 1.33（横图通栏） | 1 | 同上 | 外推（名字语义） |
| `pic_2` | 11 | 图片网格 rect 1.0（方图） | 2 | 同上 | 外推（名字语义） |
| `pic_2_card` | 16 | 图片网格 rect 0.75 | 2 | 同上 | 外推（名字语义） |
| `pic_3` | 14 | 图片网格 rect 1.0（方图） | 3 | 同上 | 外推（名字语义） |
| `card_pic_1` | 1 | 大图卡片 rect 0.75 | 1 | 同上 | 外推（同族） |
| `card_pic_2` | 10 | 图片网格 rect 0.75 | 2 | 同上 | 外推（名字语义） |
| `card_pic_3` | 25 | 图片网格 rect 0.75 | 3 | 同上 | 外推（名字语义） |
| `card_pic_3_center` | 11 | 图片网格 rect 0.75（居中降级，见 §5） | 3 | 同上 | 外推（名字语义） |
| `card_pic_2_2_left` | 6 | 图片网格 rect 0.75（“2_2_left”后缀含义不明，按首个数字 2 列） | 2 | 同上 | 外推（低置信，见 §7） |

**图标族（icon_* / avatar，共 238 条）**

| col_type | 计数 | 布局形态 | 列数 | 字段 | 来源 |
|---|---|---|---|---|---|
| `icon_2` | 49 | 方图网格 rect 1.0 | 2 | 标题+方图+备注 | 外推（名字语义：无 round 后缀 = 方图） |
| `icon_2_round` | 36 | 圆图网格 oval 1.0 | 2 | 标题+圆图（desc 不显示） | 外推（名字语义） |
| `icon_3_fill` | 2 | 方图网格 rect 1.0 | 3 | 标题+方图+备注 | 外推（名字语义：fill = 填充背景，方图近似） |
| `icon_3_round_fill` | 7 | 圆图网格 oval 1.0 | 3 | 标题+圆图（desc 不显示） | 外推（名字语义） |
| `icon_4` | 7 | 方图网格 rect 1.0 | 4 | 标题+方图+备注 | 外推（名字语义） |
| `icon_4_card` | 10 | 方图网格 rect 1.0（card 背景无对应，方图近似） | 4 | 同上 | 外推（名字语义） |
| `icon_5` | 7 | 方图网格 rect 1.0 | 5 | 标题+方图+备注 | 外推（名字语义） |
| `icon_small_4` | 20 | 方图网格 rect 1.0（小图标） | 4 | 同上 | 外推（名字语义） |
| `icon_round_4` | 16 | 圆图网格 oval 1.0 | 4 | 标题+圆图（desc 不显示） | 外推（名字语义） |
| `icon_round_small_4` | 89 | 圆图网格 oval 1.0（小圆图标） | 4 | 标题+圆图（desc 不显示） | 外推（名字语义；**阅动漫的 col_type，本步必验类型**） |
| `avatar` | 14 | 圆图网格 oval 1.0 | 4 | 标题+圆图（desc 不显示） | 外推（名字语义：头像 = 圆图；4 列是按 icon_round_small_4 同类取的） |

**控制 / 结构类（共 17 条）**

| col_type | 计数 | 布局形态 | 列数 | 字段 | 来源 |
|---|---|---|---|---|---|
| `flex_button` | 13 | 单列行 list（按钮行降级：显示标题行，可点） | — | 标题行 | 外推（海阔原 App 是按钮组；本 App 无按钮行控件） |
| `input` | 2 | 单列行 list（输入框降级：显示标题行，可点） | — | 标题行 | 外推（同上） |
| `scroll_button` | 1 | 单列行 list（滚动按钮降级） | — | 标题行 | 外推（同上） |
| `icon_1_search` | 1 | 单列行 list（搜索入口行降级） | — | 标题行 | 外推（同上；点开不进搜索页，进详情错误态，见 §7） |
| `big_big_blank_block` | 1 | 无形态：卡片通常无标题，被 `pickCards.usable()` 滤掉，不占位 | — | — | 外推（名字语义：空白占位块；万一有标题+URL 则按 list 行显示） |
| `x5_webview_single` | 2 | 海报网格 rect 0.75，默认列数（沿用第 7 步之三，不改） | 0 | 标题回落“网页”+链接 | 直译（逆向报告 §7.1/§2.5 + 第 7 步之三已发布；内联 WebView 渲染仍是后续步骤） |

**族通用解析（合集里没出现、将来可能出现的编号变体）**

| 族 | 形态 | 列数 | 说明 |
|---|---|---|---|
| `movie_N`（N≥2） | rect 0.75 | N（clamp 1–6） | N=1 时走 list（movie_1 已定） |
| `movie_N_marquee` | rect 0.75（横向滚动降级） | N（clamp 1–6） | 同上 |
| `text_N` / `text_center_N` | list | — | 任意 N |
| `pic_N` / `pic_N_card` / `pic_N_full` | N=1 → rect 1.33；N≥2 → rect 1.0 | N（clamp 1–6） | `_card` 后缀改 ratio 为 0.75（卡片图按海报比例） |
| `card_pic_N` / `card_pic_N_center` | rect 0.75 | N（clamp 1–6） | `card_pic_2_2_left` 之类多数字后缀取首个数字 |
| `icon_N` / `icon_N_fill` / `icon_N_card` / `icon_small_N` | rect 1.0 | N（clamp 1–6） |  |
| `icon_N_round` / `icon_N_round_fill` / `icon_round_N` / `icon_round_small_N` | oval 1.0 | N（clamp 1–6） |  |

**fallback（一律）**

| col_type | 布局形态 | 列数 | 说明 |
|---|---|---|---|
| 空 / 未知 / 解析不到数字 / 数字越界 | rect 0.75 | 0（App 默认） | 与第 3 步今天的行为完全一致；不崩、不空白（0 条时走既有空态 + 命中诊断） |

## 5. 降级项（有明确形态定义、无对应 holder 而做的取舍）

| 想要的形态 | 本步实际形态 | 原因 | 后续 |
|---|---|---|---|
| `movie_3_marquee` 横向滚动条 | 竖向 3 列海报网格 | `CustomScroller` 只认纵向滚动（`dy > 0` / `canScrollVertically`）；横向 LayoutManager 下 `checkMore` 会反复触发翻页、且横向滑不到底没有“下一页”入口 | 真横向滚动需横向触底检测 + “加载更多”入口，另起一步 |
| `text_2` / `text_3` 多列文本网格 | 单列文字行 | 无文字多列网格 holder；`VodListHolder` 是唯一文字行 | 如需多列文本网格，需新 holder，另起一步 |
| `card_*` 卡片背景 | 普通 rect/方图（无卡片背景） | `VodRectHolder` 无卡片背景层；加背景要改 holder 布局，全界面风格会变 | 不做（卡片背景只是装饰，内容不少） |
| `text_center_1` / `card_pic_3_center` 居中 | 左对齐（现有 holder 的对齐） | holder 写死左对齐；居中要改 holder 或加新 holder | 不做（对齐不影响内容） |
| `pic_1` / `pic_1_full` 横图 ratio 1.33 | 同左 | ratio 是按“单图通栏多为横图”猜的，未经真机对照 | 真机看样本后可调 ratio |
| 控制类（`input`/`flex_button`/`scroll_button`/`icon_1_search`）的交互 | 只显示标题行，点开进详情错误态 | 没有输入框/按钮组控件；动作 URL（`input://` 等）已被 `pickCards` 第二遍滤掉，能到列表的只剩有普通 URL 的 | 动作 URL 全套是第 7 步另一项（本步不动 `isActionUrl`） |

## 6. 验收标准

代码层面可确认（提交前逐行重读 + `git diff --check`）：

1. 42 种实测 col_type 在代码层面都有映射（精确表或族通用解析覆盖）；空/未知走 fallback 默认 rect，不崩。
2. `styleFor` / `columnsFor` 共用一张内部表；`HkListFragment` 与 `HkSearchFragment` 的 `setStyle` 都只传 col_type 字符串，不各自写映射；内嵌首页复用 `HkListFragment`，样式与列表页同源。
3. 列数与尺寸成对改：显式列数时 spec 用 `Product.getSpec(context, style, columns)`，span 同值；`Product` 现有方法一行未改，只加一个重载。
4. `menu_nav.xml` 仍是 6 项；`BaseLoader` 未动；TV/leanback 源集零改动；无 `Json.safeListElement` 式返回类型误用、无泛型原始类型、无新增 dialog。
5. `movie_3` 的 Style 与列数与第 3 步完全一致（rect 0.75、3 列 = 窄屏默认列数）；`movie_1`/`text_1/2/3` 仍是 list。

只能真机验（需一次 GitHub 构建 + 用户装新版 + 验收规则已导入）：

6. 三条验收规则（movie_3）渲染与原来一致、零回归：奇优影院♔、童话、臻品fix 首页出卡片（海报+标题），分类可切，翻页正常。
7. 阅动漫（`.hkzip`，`icon_round_small_4`）：进列表页显示 4 列小圆图标 + 标题，不崩（它还要远程拉规则列表，在线与否看真机；第 3 步同款保留意见）。
8. 从 4255 条合集里抽查各 col_type：至少在代码层面有映射；冷门类型（`avatar`、`flex_button`、`pic_1_full`、`card_pic_2_2_left` 等）进列表不崩、不空白——抽查需用户另挑规则导入，本步只做到代码层面覆盖。
9. 列表页与内嵌首页样式一致（同一条规则在两处看是同一形态）。
10. 未知 col_type（改一条规则的 col_type 为 `movie_9_xyz` 之类再导入）：进列表走默认矩形卡片，不崩、不空白。

## 回滚路径

- 整步回滚：`git revert <本提交>`（一个原子提交，recovery tag 见提交信息）。新文件只有任务文档；`HkListHelper` / 两处 `setStyle` / `Product` 重载随 revert 一起回去，列表样式回到第 3 步窄化映射；`toVods` 的 rich_text/long_text 去标签随之回去。
- 局部回滚：只回滚列数（`columnsFor` 全返回 0 + 两处 `setStyle` 改回只传 Style）：样式种类保留全表，列数回到 App 默认；`Product.getSpec` 重载变成死代码，下次构建不受影响。

## 7. 已知缺口与风险

- **全表形态是外推**：官方 col_type 表与社区 skill 表本次都没取到（`browser.open` 官方页 unresolvable 未重试；两次搜索无果，按 §3 停止追查；社区 raw 路径 404）。逐类型的形态按“名字语义 + 42 种实测分布”定，未经真机对照；最没把握的是 `card_pic_2_2_left`（后缀含义不明，按首个数字 2 列）、`pic_1`/`pic_1_full` 的横图 ratio 1.33、`avatar` 的 4 列、`movie_1_vertical_pic` 的 list 行竖图裁切。真机看样本后逐项调 ratio/列数。
- **marquee 不是真横向滚动**：503 条规则（第二大类）本步是竖向网格降级，内容全、翻页正常，但样子不是海阔原 App 的横向条；用户若在意需另起一步做横向分页机制。
- **text_2/3 不是多列文本网格**：降级单列行，内容全但占屏多。
- **控制类点开无真交互**：`icon_1_search` 点开不进搜索页、`input` 点开不弹输入框——详情错误态是现有行为，本步只保证不崩。
- **rich_text/long_text 去标签只在 `toVods` 做**：详情页的富文本渲染是第 4 步的逻辑，本步不动。
- **本地无编译检查**：无 JDK/Android SDK，`Product.getSpec` 重载签名、`GridLayoutManager` 显式 span、`Style("oval", 1.0f)` 构造等只能等 GitHub 构建验；提交前逐行重读是唯一本地防线。

## 8. 实现清单

- [x] 设计评审（本文档）
- [x] `HkListHelper`：`styleFor` 全表 + 新增 `columnsFor`（共用 `formFor` 内部表）+ `toVods` 仅 rich_text/long_text 去 HTML 标签
- [x] `HkListFragment.setStyle` / `HkSearchFragment.setStyle` 改走 col_type 单源
- [x] `Product.getSpec(context, style, column)` 重载
- [x] 逐行重读 + 映射逻辑镜像校验 + `git diff --check` + guard finish（本地提交，不推不编）
