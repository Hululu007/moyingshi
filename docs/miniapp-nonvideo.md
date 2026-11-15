# 海阔小程序第 7 步之九：非影视类规则专项（小说/漫画/音乐/图片）

- 任务：miniapp-nonvideo（standard lane；10 步串行工程的第 9 步）
- HEAD：291eb62c（main，本地超前 origin 八笔，未推）；工作区干净后开工
- 完成句：小说/漫画类接通 `last_chapter_rule`（详情页显示最新章节）+ `chapter_find`/`movie_find` 回落（`*` 列表选择器按单页处理、非法选择器不炸整页）+ `sdetail_find_rule` 第二层详情（章节页二次解析进阅读器）；音乐类解析出音频地址后走现有 `AudioActivity`（歌词走第 8 步已接通的 `PlayOutcome.lyric`）；图片类 `pics://` 走现有 `WebReaderActivity` 图集（`NovelRouter.openPics`），解析不出的图集报明确提示、不崩、不进空白页；阅读主题直接复用 `WebReaderActivity` 自带主题/翻页/目录，不新造阅读器；只改手机端，本地提交，不推、不编、不触发构建、不动版本记录空间。
- 时间：2026-11-15 19:13 CST 开工；估计 1.5–2 小时，预期 21:00 CST 前完成并提交。
- 最便宜的决定性验证：逐行重读改动 + `git diff --check`（本地无 JDK/Android SDK，编译检查等推上去之后的 GitHub 构建；真机验收等用户）。

## Recovery anchor

- 目标与验收：见“验收标准”一节；真机验不了的项已在验收表里标明。
- 计划状态：任务文档已写；实现已完成——新文件 `HkNonVideo.java`（非影视类点选集分流），改 `HkEngine.java`（`setResult` 原值记录 + `evalTextRule`）、`HkExecutor.java`（`lastChapter` / `sdetail` / `detailOutcome` / `PlayOutcome.pics`）、`HkParser.java`（详情路 `*` 列表选择器单页化 + 非法选择器容错）、`HkPlayer.java`（`buildVod` 抽出供阅读器复用）、`HkDetailFragment.java`（`detailOutcome` + 非影视类点选集走分流）、`lab_strings.xml`（2 条新串）；逐行重读完成，复核修 1 处（见 §7）。
- 已完成证据：`git diff --check` 通过；改动只在 guard scope 的 8 个文件内；TV/leanback、`BaseLoader`、`menu_nav.xml` 未动；三条影视验收规则走原路（video 类型不进分流）。
- 未验证的改动：全部（本地无 JDK/Android SDK，编译检查等推上去之后的 GitHub 构建；真机验收等用户）。
- 未解决风险：见“已知缺口与遗留”。
- 下一步：`bash .codex/scripts/task_guard.sh finish --verified <证据> --commit-message <消息>`，随后 `git tag -a` 建 recovery tag。

## 1. 证据表

| 证据 | 位置 | 结论 |
|---|---|---|
| `last_chapter_rule` 字段语义 | 逆向报告 `~/workspace/user/files/__________8_3azg.md` §3（L260）+ g22 字段表 | “最后一章规则（小说/漫画类）”；只有字段名，没有执行语义源码 |
| `last_chapter_rule` 真实写法（214/4255 条规则有此字段） | 4255 合集统计 + 样例 | 全看网（video）：`js: MY_URL=...; var html = fetchPC(MY_URL); var last = pdfh(html, '.module-play-list-content&&a,-1&&Text'); setResult(last);`——对详情页求值，`setResult` 交一个**纯字符串**（最新一集/章名）。青豆/影视资源网走子页面/file 远程 JS 变体。结论：求值目标是详情页地址，结果是字符串 |
| `cardsFromJs` 对纯字符串 `setResult` 返回 null | `HkEngine.java` L991 起 | `setResult("更新至第10集")` 会被当成“不是 JSON 数组/对象”丢掉 → `last_chapter_rule` 必须走单独的**字符串结果**路径，不能复用 `evalFindRule` |
| `chapter_find` / `movie_find` 真实覆盖 | 4255 合集统计 | `chapter_find` 全合集只有 1 条（首页29，type=tool）：` *;*;body&&div@option;Text;value`；`movie_find` 0 条。第 4 步的回落已存在，本步只能把它从“无规则可验”推进到“按语义完整实现 + 不炸”，无法拿小说/漫画规则真机对 |
| `*` 列表选择器的现行行为 | `HkParser.selectSegments`（`el.select(seg)`，jsoup 原生） | 列表字段 `*` 时 `select("*")` 返回全部后代元素（上千个），再逐个 extract——对唯一一条 `chapter_find` 样本是错的（应是整页当一个条目）；且 `div@option` 这类非法 CSS 会抛 `SelectorParseException` 直接炸掉整次解析 |
| `sdetail_find_rule` 真实覆盖 | 4255 合集统计 | 非 `*` 的真实二级详情规则 117 条（如影视资源网 `js: eval(fetch(...)); hikseaerji();`）；`*` = 继承主规则（第 4 步文档已定此口径）。第 4 步只在详情里留了“不求值”的缺口 |
| 报告 `pickEpisodes`（选集识别） | 逆向报告 §7.1（L582–606，全文可读） | 章节与选集是同一套识别（`text_5` 优先，否则第二遍谓词）；小说/漫画的“章节”就是详情切分出的选集卡片，不用另建章节模型 |
| 音乐类真实写法 | 4255 合集样例（麦田音乐网，music） | 列表选择器版，`detail_find_rule` 是 JS，输出 `rich_text` 卡片，内嵌 `mp3` 地址与“点击试听”链接；`sdetail_find_rule = *`。结论：音乐详情的音频地址藏在详情卡片里，点选集后的懒解析（第 5 步）才能拿到它 |
| 图片类真实写法 | 4255 合集样例（千叶网壁纸，picture，`col_type = pic_2`；小鸟壁纸，`col_type = pic_1_card`） | 列表/详情都是标准卡片（标题/封面/链接），与影视规则同款取数链；图集内容在懒解析结果里以 `pics://url1&&url2` 形式出现 |
| 音频播放已有能力 | `app/src/main/java/com/github/moyingshi/osc/tv/ui/activity/AudioActivity.java` L176 | `start(Activity, key, url, title, subtitle, pic, headers, lrc)`——直接接受音频地址 + 请求头 + 歌词（lrc 地址或内嵌文本）；`AudioUtil.isAudioUrl` 认 mp3/flac/m4a/aac/wav/ogg/oga/opus/ape/wma/amr/mka + `mp3://`/`music://` 前缀 |
| 歌词已接通 | 第 8 步 `HkExecutor.PlayOutcome.lyric`（`docs/miniapp-playback-params.md`） | 多线路 JSON 的 `lyric` 字段已解析进 `PlayOutcome.lyric`（.lrc 地址已补全，或内嵌歌词文本原样保留）；本步只确认音乐规则走同一条路，不另做歌词解析 |
| 阅读器已有能力 | `app/src/main/java/com/github/moyingshi/osc/tv/ui/web/WebReaderActivity.java` L41–42 | “加载本地 assets/reader.html 阅读器模板（自带小说翻页 / 漫画瀑布流 / 目录 / 主题 / 自动滚动 UI）”；payload 口径：小说 `novel://{title,content}` JSON（非 JSON 的纯文本也当正文），漫画 `pics://url1&&url2...`（`&&` 分隔，或单个 PDF） |
| 阅读器入口已有 | `app/src/main/java/com/github/moyingshi/osc/tv/ui/novel/NovelRouter.java` | `openReader(ctx, siteKey, flag, vod, ep, payload)`（kind=1 小说）、`openPics(ctx, siteKey, flag, vod, ep, payload)`（kind=2 漫画/图集）；`Flag(String)` + `Episode.create(name, url)` 可直接构造章节表，不依赖蜘蛛 `Result` |
| 图集现行状态 | `HkExecutor.dispatchResolved` pics:// 分支 + `HkActionHandler.java` L270–271 | 两处都弹 `hk_action_pics_unsupported`“图集浏览暂不支持（pics://）”——第 7 步的结论基于“图集浏览暂不支持”的前提；本步核实到 `NovelRouter.openPics` + `WebReaderActivity` kind=2 已在 main 源集里存在，所以 `pics://` 可以接通，不再是“不支持” |
| 三条影视验收规则 | `~/workspace/miniapp/test-rules/` | 奇优影院♔/童话/臻品fix 都是 video 类型，无 `last_chapter_rule`/`chapter_find`/`sdetail` 非 `*` 字段；本步的分支只在 type 为 read/cartoon/music/picture 时触发，三条规则走原路 |

## 2. 方案比较

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 不做 | 保持第 4–8 步现状：小说/漫画点章节进视频播放器、`last_chapter_rule`/`sdetail` 不求值、pics:// 弹“暂不支持” | 零风险 | 本步任务就是做这四类；小说点开进播放器是错的（播不出正文），音乐进视频轨道违背任务要求 | 否决 |
| B. 照搬上游（魔影） | 复刻魔影的小说/漫画/音乐/图片页（`f22.detail` 的章节回落 + 阅读器 + 音频页全量语义） | 与魔影行为最接近 | `f22.detail`/`t()` 无源码；魔影的阅读器/音频页实现不在报告里；工作量数倍于本步；4255 条里小说/漫画/音乐/图片规则各只有 65/74/130/98 条，且 `chapter_find` 样本只有 1 条，照搬无从验证 | 否决 |
| C. 窄化适配（推荐） | 新 `HkNonVideo` 分流（见“设计”）：详情页点选集时按规则 type 分流——read/cartoon 先跑 `sdetail` 再跑懒解析，结果是正文进 `WebReaderActivity`（小说）、是 `pics://`/图片地址进图集、是音频进 `AudioActivity`；music 直接懒解析后音频进 `AudioActivity`（带第 8 步的 lyric）、非音频回落视频播放器；picture 的 `pics://`/图片地址进图集、解析不出报明确提示；`last_chapter_rule` 在详情取数时复用已抓的详情页正文求值，显示在详情简介下方；`chapter_find`/`movie_find` 回落保留第 4 步结构，只修 `*` 列表选择器与非法选择器容错 | 改动集中在 hk 包 5 个文件 + 详情 Fragment + 2 条新串；全部复用项目已有的 `AudioActivity`/`WebReaderActivity`/`NovelRouter`，不新造播放器与阅读器；三条影视验收规则走原路零触碰；解析失败时有明确提示、不崩、不进空白页 | `sdetail` 与章节正文的取数语义是按字段名 + 117 条非 `*` 样本外推的，未经真机对照；`chapter_find` 只有 1 条 tool 样本，无法真机验证小说/漫画回落 | **采用** |
| D. 新造阅读器/音频页 | 为小程序另写一套阅读器（字体/背景/亮度）与音频播放页 | 样式完全按小程序定制 | 任务明确要求“复用项目现有的阅读或详情样式，不要新造一套阅读器”“只接项目已有的音频播放能力”；重复造轮子，维护面翻倍 | 否决 |

子问题“点选集时在哪里分流”的比较：

| 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 在 `VideoActivity` 的 hk 播放分支里分流 | 一处改动覆盖所有进播放器的 hk 选集 | `VideoActivity.startHk` 是手机/TV 双 flavor 共用的播放入口，hk 分支在取流时才跑懒解析，分流点太深；且阅读器/音频页不需要 VideoActivity 的详情页壳 | 否决 |
| 在 `HkDetailFragment.onEpisodeClick` 里按 type 分流（推荐） | 分流点就是用户点选集的地方；video 类型一行不改走原 `HkPlayer.play`；非影视类在后台线程先解析再按结果进阅读器/音频页/提示 | 非影视类点选集要多一次懒解析（回落视频播放器时播放器取流还会再解析一次，同地址两次请求） | 采用（双倍解析只发生在回落路径，见“已知缺口”） |

## 3. 设计

### 文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `docs/miniapp-nonvideo.md` | 新建（本文档） | 设计评审 + 实现记录 + 回滚 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkNonVideo.java` | 新建 | 非影视类分流（见下）：type 判定 + 点选集后台解析 + 阅读器/音频页/提示三路出口 + payload 拼装 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkEngine.java` | 改 | Bridge.`setResult` 同步记 `resultRaw`（原始交值）；新增 `evalTextRule(String)`（跑一段 JS 规则，取 `setResult` 原值或直接返回值当字符串；字符串原值优先，否则卡片首标题，否则 ""） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkExecutor.java` | 改 | 新增 `DetailOutcome {cards, lastChapter}` + `detailOutcome(...)`（`detail()` 委托给它，签名与行为不变）；新增 `lastChapter(rule, detailUrl, pageText)`（复用已抓的详情页正文求值，失败回 ""，绝不炸详情页）；新增 `sdetail(rule, url, initialVars, host)`（`*` 继承主规则、非 `*` 跑二级规则、空返回空列表）；`PlayOutcome` 加 `pics` 字段（pics:// 分支同时填 `pics` 与 `notice`，老调用方只读 `notice`，行为不变） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkParser.java` | 改 | `parseHomeListDetailed`：列表字段是 `*` 且走详情路（`keepTitleOnly`）时，整页当一个条目（`doc.body()`）；`selectSegments`：`el.select(seg)` 包 try/catch，非法 CSS 记日志跳过，不炸整次解析 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkPlayer.java` | 改 | `play()` 里拼 Vod 的部分抽成包内可见 `buildVod(rule, detail, detailUrl)`（行为不变），供 `HkNonVideo` 给阅读器拼 Vod/Flag 复用 |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkDetailFragment.java` | 改 | `loadDetail` 改调 `detailOutcome`，`lastChapter` 非空时按 `hk_detail_last_chapter` 格式追加到简介末尾；`onEpisodeClick`：`HkNonVideo.isNonVideoRule(mRule)` 时走 `HkNonVideo.routeEpisode(...)`，否则原 `HkPlayer.play(...)` |
| `app/src/main/res/values/lab_strings.xml` | 改 | 加 2 条：`hk_detail_last_chapter`（`最新章节：%1$s`）、`hk_nonvideo_no_content`（`章节内容未解析出`） |

### 关键契约

- **type 判定**（`HkNonVideo.isNonVideoRule`）：`rule.getType()` 去空格转小写后 ∈ {`read`, `cartoon`, `music`, `picture`}。`all`/`video`/`tool`/`other`/`live`/`news`/空一律走原视频路（`all` 是混合聚合规则，不按非影视处理）。
- **最新章节**（`HkExecutor.lastChapter`，只在 type 为 read/cartoon 时求值）：
  1. 字段为空 → ""（不求值）；求值复用 `detail()` 已抓的详情页正文，不另发请求。
  2. JS 规则（`js:` 开头）：新引擎 `prepare(rule, detailUrl, 1, "detail", pageText)` → `evalPreRule()` → `evalTextRule(lastChapterRule)`。
  3. 选择器规则：`HkParser.extract(Jsoup.parse(pageText, detailUrl), rule, false, detailUrl, engine)`。
  4. 任何异常 → 记日志回 ""；最新章节是装饰信息，绝不能让详情页变错误态。
  5. 展示：详情简介末尾另起一行 `最新章节：X`（`hk_detail_last_chapter` 格式串）；简介为空时只显示这一行。
- **章节回落**（`chapter_find`/`movie_find`，第 4 步已有结构，本步只修两处）：
  1. 主规则出 0 张卡片且 `chapter_find` 或 `movie_find` 非空时，用那条（`chapter_find` 优先）对同一页再跑一次（`evalDetailRule`，JS 或选择器同款路径）——第 4 步的逻辑一行不动。
  2. 新修：列表字段是 `*` 时整页当一个条目（只改详情路 `keepTitleOnly`，首页路不动）；非法 CSS 选择器记日志跳过，不抛 `SelectorParseException` 炸整次解析。
  3. 仍无规则可验：全合集 `chapter_find` 只有 1 条 tool 样本、`movie_find` 0 条；回落语义只能等真机拿小说/漫画规则对（见“已知缺口”）。
- **二级详情**（`HkExecutor.sdetail(rule, url, initialVars, host)`，供章节点击时调）：
  1. `sdetail_find_rule` 为空 → 空列表（调用方走懒解析回落）。
  2. `*` → 对目标 URL 重跑 `detail_find_rule`（继承主规则；主规则为空时回空列表，不抛“规则缺少 detail_find_rule”）。
  3. 非 `*` → 对目标 URL 跑二级规则（JS 走 Rhino，否则选择器；与 `evalDetailRule` 同款：先去懒标记、子页面走 `HkSubpage.expand`、http(s) 才抓页）。
  4. 返回原始卡片，内容拼装由 `HkNonVideo` 做（见下）。
- **章节内容拼装**（`HkNonVideo` 内，sdetail 卡片 → 阅读器 payload）：
  1. 任一卡片 url 以 `pics://` 开头 → 整条当图集 payload，进 `openPics`。
  2. 否则收图片地址（卡片 pic 是图片 URL 的）与正文（卡片 desc 非空取 desc，否则取标题，去 HTML 标签后拼接，`\n\n` 分隔）；有图无正文 → 图集 payload；有正文 → `novel://{"title": 章节名, "content": 正文}` JSON payload，进 `openReader`。
  3. 两者都空 → 当本次 sdetail 无可用内容，继续走懒解析回落。
- **点选集分流**（`HkNonVideo.routeEpisode`，后台线程跑解析，主线程进阅读器/音频页/提示）：
  1. type 为 read/cartoon：先试 `sdetail`（章节页二次解析；`sdetail` 为空/`*` 且主规则为空时跳过）；拼出 payload 就进阅读器/图集，不再跑懒解析。
  2. 再跑 `HkExecutor.play(rule, episodeUrl)`（懒解析，与第 5 步同款）：
     - `outcome.pics` 非空 → `openPics(pics)`（图集浏览接通；第 7 步“暂不支持”的提示只在下面第 4 步的兜底里出现）。
     - `outcome.url` 是音频（`AudioUtil.isAudioUrl`）→ `AudioActivity.start(activity, siteKey, url, 书名, 章节名, 海报, headers, outcome.lyric)`（歌词走第 8 步的 `lyric`，.lrc 地址或内嵌文本都由音频页处理）。
     - `outcome.notice` 非空（toast/copy/download 动作结果）→ 弹 `notice`，不进播放器（与第 7 步老行为一致）。
     - `outcome.url` 非空但不是 URL（无 http(s) scheme、不是音频/图片）→ 当章节正文，进 `openReader(novel://{title, content})`。
     - `outcome.url` 是图片地址（.jpg/.jpeg/.png/.webp/.gif/.bmp，去 query/fragment 后判断）→ `openPics(pics://<url>)`（picture/cartoon 才走；music 的图片地址不进图集，见下）。
     - 其余按 type 回落：music → `HkPlayer.play`（视频播放器也能播音频流；URL 无音频后缀时靠播放器兜底）；picture → 弹 `hk_action_pics_unsupported`（图集解析不出，明确提示，不进空白页）；read/cartoon → 若 `sdetail` 已配置，对解析出的 http URL 再跑一次 `sdetail`（懒结果可能只是一个章节页地址），仍无内容则弹 `hk_nonvideo_no_content`，不进视频播放器。
  3. `play()`/`sdetail()` 抛异常（网络/解析失败）→ 主线程弹异常信息，不回落进播放器（回落会把同一失败再跑一次）。
- **阅读主题**（任务第 4 项）：不新造。`WebReaderActivity` 的 `reader.html` 模板自带主题/翻页/目录/自动滚动 UI（字体/背景/亮度走模板自带控制）；章节正文只拼 `novel://` payload 交过去。模板主题控制的具体项以真机上的阅读器设置为准，本步不改模板。
- **图片类列表/详情**：不改取数链。`pic_2`/`pic_1_card` 等 col_type 在第 6 步已补齐布局；列表/详情正常展示封面与标题（与影视规则同款 `HkListHelper.styleFor`/`HkDetailHelper.split`）。
- **不碰**：`BaseLoader`、`menu_nav.xml`（仍 6 项）、TV/leanback 源集、三条影视验收规则的执行路径（video 类型走原路）、`HkActionHandler` 里 pics:// 的提示（动作行点开仍弹“暂不支持”，与本步详情选集的图集接通是两条路，动作行接通是后续工作，见“已知缺口”）。

## 4. 验收标准

代码层面可确认（提交前逐行重读 + `git diff --check`）：

1. `HkEngine.evalTextRule`：`setResult("字符串")` 取到原字符串；没调 `setResult` 时直接返回值是字符串也取到；数组/卡片结果不崩（回 "" 或首卡标题）。
2. `lastChapter` 只在 read/cartoon 且字段非空时求值，复用已抓正文、不另发请求；求值失败不影响详情页（回 ""）。
3. `sdetail`：空 → 空列表；`*` → 重跑主规则（主规则为空不抛错）；非 `*` → 跑二级规则；子页面地址走 `HkSubpage.expand`。
4. `PlayOutcome.pics`：pics:// 分支同时填 `pics` 与 `notice`；老调用方（VideoActivity hk 播放分支）只读 `notice`，行为不变。
5. `HkParser`：详情路列表字段 `*` 时整页当一个条目；非法 CSS 选择器记日志跳过、不抛异常；首页路 `*` 行为不变。
6. `menu_nav.xml` 仍 6 项；`BaseLoader` 未动；TV/leanback 源集零改动；无 `Json.safeListElement` 式返回类型误用、无泛型原始类型；dialog 根布局不透明背景（本步不改 dialog）。

只能真机验（需一次 GitHub 构建 + 用户装新版 + 真实非影视规则已导入）：

7. 小说类规则（如合集里的 read 类型）：列表出封面/标题；详情有章节列表，简介下方有“最新章节：X”（规则带 `last_chapter_rule` 时）；点章节进阅读器看正文（字体/背景/亮度用阅读器自带主题调）。
8. 漫画类规则（如合集里的 cartoon 类型）：章节列表能出；点章节进图集看图片（`pics://` 或图片地址时），或进阅读器看正文。
9. 音乐类规则（如麦田音乐网）：列表/详情正常；点一首进音频播放器出声，歌词（有 `lyric` 时）按音频页现有能力显示；无音频后缀的流地址回落视频播放器也能出声。
10. 图片类规则（如千叶网壁纸/小鸟壁纸）：列表/详情正常出封面与标题；点开图集能进图集浏览，或报明确提示，不崩、不进空白页。
11. 三条影视验收规则（奇优影院♔/童话/臻品fix）列表/详情/搜索/播放零回归（video 类型走原路，代码层面已隔离；真机抽查）。
12. `chapter_find`/`movie_find` 回落：暂无小说/漫画样本规则可验（全合集只有 1 条 tool 样本）；等用户提供带 `chapter_find` 的小说规则再验。

## 5. 回滚路径

- 整步回滚：`git revert <本提交>`（一个原子提交，recovery tag 见提交信息）。新文件 `HkNonVideo.java` 与任务文档随 revert 一起消失；`HkDetailFragment.onEpisodeClick` 恢复成全部走 `HkPlayer.play`；`HkExecutor.detail()` 恢复成第 4 步版本（`detailOutcome`/`lastChapter`/`sdetail` 一起回去）；`PlayOutcome.pics` 字段消失（老调用方只读 `notice`，不受影响）；`HkParser` 的 `*` 单页化与非法选择器容错一起回去（回落到第 4 步行为）。
- 局部回滚：只回滚 `HkDetailFragment.onEpisodeClick` 的分流（非影视类也走 `HkPlayer.play`），保留引擎层的 `lastChapter`/`sdetail`（详情页仍显示最新章节；sdetail 变成不可达代码，下次构建不受影响）。

## 6. 已知缺口与遗留（本步不做）

- **`chapter_find`/`movie_find` 无小说/漫画样本可验**：全合集只有 1 条 tool 样本（` *;*;body&&div@option;Text;value`，其中 `div@option` 的 `@` 语法本步解析器不支持）；`movie_find` 0 条。回落逻辑按字段名 + 第 4 步结构实现，等用户提供带 `chapter_find` 的小说规则再真机对。
- **`sdetail` 语义是外推**：117 条非 `*` 样本只证明“二级规则存在”，没证明“章节点击时跑 sdetail 取正文”就是魔影的调用点；真机拿小说规则对不上时再按实际行为调。
- **非影视类点选集回落时双倍解析**：分流先跑一次 `play()`，回落视频播放器后取流时再跑一次（同地址两次请求）；只发生在 music 非音频后缀回落时，可接受，未做去重。
- **动作行的 pics:// 仍弹“暂不支持”**：`HkActionHandler`（L270–271）与 `dispatchResolved` 的 notice 口径没动——本步只接通了详情选集点击的图集路；动作行（列表页/详情页动作卡片）点开的 pics:// 是后续工作。
- **音乐规则的歌词显示以音频页现有能力为准**：`lyric` 只传过去（第 8 步已解析），音频页怎么渲染歌词本步不改；无 `lyric` 字段的音乐规则自然无歌词。
- **阅读器主题不改模板**：`WebReaderActivity` 的 `reader.html` 模板自带主题控制，本步只确认复用、不调模板；用户要的字体/背景/亮度以真机阅读器设置为准。
- **`last_chapter_rule` 只对 read/cartoon 求值**：214 条带此字段的规则大多是 video 类型（如全看网取最新一集名）；video 类型本步不动，列表/详情里仍能看到选集本身，不缺这行字。
- **漫画章节可能是“图片页地址”而非图片直链**：懒解析只返回章节页 http 地址、且 `sdetail` 未配置时，cartoon 按 read 同款弹“章节内容未解析出”，不进视频播放器；要支持需对章节页再跑一次详情规则（即 sdetail `*` 语义），等有真实漫画规则再对。

## 7. 实现清单

- [x] 设计评审（本文档）
- [x] `HkEngine`：`resultRaw` + `evalTextRule`
- [x] `HkExecutor`：`DetailOutcome`/`detailOutcome`/`lastChapter`/`sdetail`/`PlayOutcome.pics`
- [x] `HkParser`：详情路 `*` 单页化 + 非法选择器容错
- [x] `HkPlayer`：`buildVod` 抽出（包内可见）
- [x] `HkNonVideo`：type 判定 + `routeEpisode` + payload 拼装
- [x] `HkDetailFragment`：`detailOutcome` + 非影视类分流 + 最新章节追加到简介
- [x] `lab_strings.xml`：加 `hk_detail_last_chapter`、`hk_nonvideo_no_content`
- [x] 逐行重读 + `git diff --check`（复核修 1 处，见下）
- [ ] guard finish（本地提交，不推不编）

## 8. 复核修（提交前逐行重读发现，一处小错直接修一轮）

- `HkNonVideo.sdetailContent` 原先直接抛 `HkException`：章节卡片 URL 常带
  `@lazyRule=` 标记，`sdetail()` 遇到它会抛“懒加载动作链接请从卡片点击执行”，
  外层 catch 会直接弹错、拦住后面的懒解析回落。已改成内部 try/catch，
  求值失败记日志回 null（sdetail 只是“先试一次”，失败就走懒解析回落）。
