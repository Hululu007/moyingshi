# 小程序第 5 步：懒加载播放（全链路打通）

> Recovery anchor：目标——详情页点选集经懒加载解析出真实播放地址后复用现有
> VideoActivity 播放入口播放并记入历史，空地址弹“小程序未解析出播放地址”、
> 不进空白播放器、不崩。当前状态：设计已定（窄化适配 C），实现中。
> 本地 main = 149fc3d9（第 3 步 2219a72b + 第 4 步 8f378508 + 字体改亮
> 149fc3d9），guard 会话 miniapp-playback，本地提交、不推、不编。
> 验收：三条规则（奇优影院♔ / 童话 / 臻品fix）点选集进播放器播起来；
> 列表/详情零回归。下一步：写代码 → 逐行重读 → finish 提交 → 汇报父代理。

## 1. 完成句

详情页点某一集，后台线程跑懒加载解析（`@lazyRule=` / 选择器 / 直链），
解析出真实播放地址后复用现有 `VideoActivity` 播放入口播放并记入历史；
解析出空地址弹“小程序未解析出播放地址”，不进空白播放器、不崩。
只改手机端，TV/leanback 不动，`BaseLoader` 不动。

## 2. 设计研究（AGENTS.md §7）

### 2.1 证据表

| # | 来源 | 修订/日期 | 证据等级 | 支持的主张 | 本项目适用性 | 注意点 | 对决策的影响 |
|---|---|---|---|---|---|---|---|
| E1 | 逆向报告 `~/workspace/user/files/__________8_3azg.md`：`player(site, flag, url)`（L211）与空地址分支（L1054 原文 `result.setMsg("小程序未解析出播放地址")`） | 2026-11-14 上传 | A（本轮原文重读） | 播放 = `f22.t(url, ...)` 懒解析出地址+请求头；空地址返回该提示，不进播放器；有头时 Referer 默认补输入 URL（http 时） | 直接采用：空地址文案与 Referer 默认规则照抄 | `f22.t` 本体反编译失败（L5715，1093 条指令跳过），只能按可读 helper 重建 | 懒链按可读 helper 重写，不追求字节级复刻 |
| E2 | 同报告：`f(int, String)` 入口懒求值（L5460–5512 原文） | 同上 | A | `url@lazyRule=rule` 在第一个 `@lazyRule=` 处切分；规则部分先过 `e()` 转义；`.js:`/`js:` 前缀走 Rhino `w02.F(js, url)`，否则抓页后走选择器 `b32.c(page, rule, url)`；失败记 `entry lazyRule eval failed` 返回 null | 直接采用：切分点、`e()` 位置、前缀判定、两条分支 | `e()` 的映射只在 `b()`/`u()` 的调用处可推断（见 E3 交叉验证） | play() 的入口结构照此写 |
| E3 | 同报告：`b()` 分发（L5091–5456 原文）+ `u()` 懒执行 | 同上 | A | 无 `@lazyRule=` → 直链经 `w()` 归一；深度 `i >= 3` 记 `lazy chain too deep` 停；有则 URL 部分过 `w()`、并在第一个 `#` 处截断，再递归 `u(i+1, ...)`；`{`+`"urls"` JSON → `s()`；`webRule://` → `v()`；`pics://` → `r()`；`video://` → `w()` + parse 标记 | 采用：深度上限 3、直链/`#` 截断、JSON 识别；`webRule://`/`pics://` 不做（第 7 步） | `w()` 本体只剩调用点可读：`@key@value&&...` 头对、` ;{headers}` 后缀剥离是按官方文档补的（见 E5） | 递归深度 3、直链归一化、`#` 截断进 play() |
| E4 | 三条验收规则原文（`~/workspace/miniapp/test-rules/`，本轮逐字读过） | 2026-11-15 | A | 奇优：`.js:` 多语句程序（`fetch(input,{})` → `parseDomForHtml(get, ".stui-player__video＆＆＆＆iframe＆＆＆＆src")` → `fetch(js,{headers:{Referer}})` → 三分支取 `ldgurl`/m3u8/回落 input）；童话：选择器 `html&&#player-box&&script&&Html.js:eval(input)；；now`；臻品fix：`.js:eval(parseDomForHtml(request(input),'.embed-responsive&&script&&Html')...)` + `unescape(base64Decode(...))` + 第三方解析 `jx.anje.cn` / `jhjx.anje.cn` + 结果常带 `#isVideo=true#` | 三条就是验收集：JS 路 ×2、选择器路 ×1；`request`/`getUrl`/`unescape`/`MOBILE_UA` 必须可用 | 臻品fix 的 tab 懒加载用 `putVar({key,value})` 对象形式 + `refreshPage()`，但那是 tab 卡片（非选集），不在本步播放路径；`getUrl()` 在童话详情 JS 用 2 次、臻品fix 用 1 次——第 4 步详情 JS 在真机上会因缺 `getUrl` 抛 ReferenceError，本步必须先补 | 引擎补丁清单由此定：`request`、`getUrl`、`putVar` 对象形式、`refreshPage` 空操作、`unescape` 兜底 |
| E5 | 海阔官方文档（上一轮 2026-11-15 已取，见第 4 步任务文档引用；本轮用三条规则原文交叉验证） | 2026-11-15 | B | 二级列表动态解析（`@lazyRule=`、`＆＆＆＆`、不能嵌套、仅最后一层）；视频播放支持 header（`url;{Cookie@aaa&&Referer@...}`）；视频多线路 JSON `{urls:[...], names:[...], headers:[...], subtitle, danmu, lyric}`；嗅探识别优化（`#isVideo=true#` / `#ignoreVideo=true#` / `#ignoreM3U8#` 标记自动剥离）；`#noRecordHistory#` | 采用：`；{headers}` 后缀解析、`#...#` 剥离、多线路 JSON 只取首条 | 官方文档没给 `e()` 的完整映射与 `;now` 的语义——这两处按 E2/E4 的原文行为定（见 §3.2） | 后缀/标记处理与多线路 JSON 的 MVP 口径由此定 |
| E6 | 现有实现（本轮原文重读） | main 149fc3d9 | A | `HkExecutor.detail()`：校验 → `stripLazyMarkers` 去标记 → `resolveUrl(rule.getUrl(), url)` → firstHeader+ua 抓页 → preRule → JS/选择器；`HkExecutor.fetch()` 同步抓页；`HkEngine`：BOOTSTRAP 里 `request` 是抛错桩、无 `getUrl`、`putVar` 只有 2 参数、`refreshPage` 不存在、`evalExpression(js, input)`（input 进 scope 当全局变量）、`eval(js, name)` 返回最后一条语句的值；`HkParser.extract(Element, field, completeUrl, baseUrl, engine)` 支持 `js:` 整段与 `.js:` 尾巴；`HkDetailHelper.split`：信息卡 + 线路头 + 选集（`Detail.lines[].episodes`）；`VideoActivity.startDirectResolved()`（L776）+ `putIntentPlaybackSelection()`（L1121）+ `getPlayer()`（L3013，`isOriginSession()` 分支是现成的 early-return 钩子）+ `setPlayer()`（L3429 唯一收口）+ `setDetail→checkHistory→applyIntentPlaybackSelection(L6285)→checkFlag→onItemClick→onRefresh→getPlayer` 自动起播链；`Vod.setFlags()`：`$$$` 分线路、`#` 分集、`name$url`（第一个 `$` 切，`Flag.splitEpisodes` 在 `#` 处按括号深度 0 切分）；`Result.setUrl/setHeader/setFlag/setParse(0)` | 全部复用，不另起播放器、不动蜘蛛链 | `getEpisode()` 取当前线路的选中集、否则首集——点选集的选中由 `applyIntentPlaybackSelection` + 现有 history 机制完成，与 TMDB 直播入口同款链路，本步只复用不改 | 播放入口与历史都走现有链，本步只加：`HkExecutor.play()` + `HkEngine` 补丁 + `VideoActivity.startHk` 与 `getPlayer` 的 hk 分支 + `HkPlayer`（Vod 组装） |
| E7 | 评估 `~/workspace/miniapp/ASSESSMENT.md` | 2026-11-14 | B | 第 5 步 = 懒加载播放全链路，`f22.t()` 重写、风险最高；播放链路第二验证（`detail + t`）；`webRule://` 的 WebView 注入分支 MVP 先不做（放完整版）；多线路 JSON 的 `urls/names` 做基础支持；验收：导入 → 列表 → 分类 → 搜索 → 详情选集 → 点播出声画 | 范围锚点：本步做到“点播出声画”即止，搜索（第 6 步）与完整版长尾（第 7 步）不动 | — | 方案 C 的边界由此定 |

### 2.2 三条规则的懒加载原文（验收依据，逐字）

- **奇优影院♔**（`.js:` 多语句程序，`e()` 后是合法 JS，`eval` 返回最后一条
  `if/else` 链的值）：
  `var get =fetch(input,{})；；var js = parseDomForHtml(get,".stui-player__video＆＆＆＆iframe＆＆＆＆src")；；var jx = fetch(js,{headers:{'Referer':'http://www.anluyg.net/'}})；；if(jx.indexOf('ldgplayer')!=-1){…ldgurl…}else if(jx.indexOf('id="vod"')!=-1){jx.match(/http.*?m3u8/g)[0]；；}else if(jx.indexOf('DPlayer')!=-1){jx.match(/http.*?m3u8/g)[0]；；}else{if(input.indexOf('http')!=-1){input}else{'http:'+input}}`
  结论：需要 `fetch(input)`（input = 选集页 URL）、`parseDomForHtml` 的
  `＆＆＆＆` 转义、`；；` 转义、Rhino `eval` 取多语句程序的末值。
- **童话**（选择器 + `.js:` 尾巴 + `；；now` 后缀）：
  `html&&#player-box&&script&&Html.js:eval(input)；；now`
  结论：`e()` 后得 `…Html.js:eval(input);now`——`now` 是“立即执行”标记，
  不是 JS，`eval` 之前必须剥掉尾部 `;now`，否则 Rhino 报 `now` 未定义。
  选择器取到 `<script>` 的 inner HTML 后 `.js:eval(input)` 求值，末值即地址。
- **臻品fix**（`.js:eval(...)` + 第三方解析）：
  `eval(parseDomForHtml(request(input),'.embed-responsive&&script&&Html').replace(/player_.*?={/,'player_aaaa={'));var url=unescape(base64Decode(player_aaaa.url));var fr=player_aaaa.from;if(fr=='alizy'){request('https://jx.anje.cn/?url='+url).match(/"url":"(.*?)"/)[1]}else if(fr=='mgtv'|fr=='qq'|fr=='qiyi'|fr=='sohu'|fr=='youku'){…request('https://jhjx.anje.cn/api/?url='+url…)…url=parseDomForHtml(html2,'video&&source&&src')+"#isVideo=true#"}else if(fr=='ledou'){…+"#isVideo=true#"}else if(fr=='sohu'){…+"#isVideo=true#"}else{url}`
  结论：需要 `request()`（与 `fetch` 同义）、`unescape`、`base64Decode`、
  `MOBILE_UA`、`eval` 出的隐式全局 `player_aaaa`（Rhino 非严格模式直接
  `eval` 赋值即全局属性）；结果常带 `#isVideo=true#`，交给播放器前剥离；
  第三方解析是规则自带写法，照规则走，不修规则、不代理。

### 2.3 方案比较

| 方案 | 内容 | 优点 | 缺点 / 风险 | 结论 |
|---|---|---|---|---|
| A 不做 | 详情页继续弹“播放还在做” | 零风险 | 用户已明确要第 5 步；全链路不断 | 拒绝 |
| B 照搬上游 | 全量复刻 `f22.t` 的 `u/b/f/w/a/s/d/n` + `v12`，含 `webRule://`（WebView 桥）、`pics://`、多线路 JSON 全解析、parse 标记二次解析、嗅探预加载 | 与魔影行为最接近 | `f22.t` 本体反编译失败（1093 指令跳过），只能按 helper 拼， fidelity 无法自证；`webRule://` 需 WebView 桥（第 7 步量级）；三条验收规则用不到其中大半；验证面大、一次难对 | 拒绝（推迟到第 7 步） |
| **C 窄化适配（推荐）** | 只做三条规则走得通的最小闭环：`play()` 懒链（`@lazyRule=` / `.js:` / 选择器 / 直链 + 深度上限 3 + `e()` 转义 + `;now` 去尾 + `;{headers}` 剥离 + `#...#` 剥离 + Referer 默认）；JS 只补三条规则用到的（`request`、`getUrl`、`putVar` 对象形式、`refreshPage` 空操作、`unescape` 兜底）；播放走现有 `VideoActivity` 链（`startHk` + `getPlayer` hk 分支 + `setPlayer`）；历史走现有 `updateHistory` | 改动面最小、全部落在已验证的现有链上、可真机验收；`webRule://` 等缺口明确留给第 7 步 | 多线路 JSON 只取首条（无选线路 UI）；`video://` 的 parse 标记按直链处理（见 §3.4）；`putVar` 对象形式与 `refreshPage` 只去抛错桩，tab 切换本身仍是第 7 步 | **采用** |
| D 复用蜘蛛链 | 把 hk 当普通站走 `SiteApi.playerContent` | 不用写播放分支 | `BaseLoader` 没有 hk 蜘蛛，`hk://` 会进 `SpiderNull` 空页；懒链语义不在蜘蛛链里；且任务明确要求 `BaseLoader` 不动 | 拒绝 |

## 3. 推荐设计（方案 C）

### 3.1 调用链（全程复用现有入口，不造播放器）

```
详情页点选集
  → HkDetailAdapter.Listener.onEpisodeClick(Line, HkCard)   // 本步把 Line 也传出来
  → HkDetailFragment.onEpisodeClick：HkPlayer.play(activity, mRule, mDetail, line, episode, detailUrl)
  → HkPlayer.play：按 Detail.lines 拼 Vod（见 §3.5），VideoActivity.startHk(...)
  → VideoActivity.startHk：startDirectResolved 同款 intent（detail_mode DIRECT + key/id/name/pic/mark/content + putDetailVodCache）+ putIntentPlaybackSelection(flag=线路名, name=集名, url=选集原始 URL)
  → 现有链：setDetail → checkHistory（applyIntentPlaybackSelection 选中该集）→ checkFlag → onItemClick → onRefresh → getPlayer(getFlag(), getEpisode())
  → getPlayer 内 hk 分支（见 §3.6）：后台线程 HkExecutor.play(rule, episodeUrl) → Result → setPlayer(result)（唯一收口）
  → 历史：getPlayer 内现有 updateHistory(episode) + checkHistory 里 mHistory.setVodName/setVodPic（Vod 自带的真片名/海报）
```

### 3.2 `HkExecutor.play(rule, episodeUrl)`（新增，`HkExecutor.java`）

入口 `play(HkRule, String)` 返回 `PlayOutcome{url, headers}`（空地址时
`url=""`），调用方必须在后台线程跑。流程（对应 E1/E2/E3）：

1. 校验 rule；`episodeUrl` 为空 → 空结果。
2. 在**第一个** `@lazyRule=` 处切分：`pageUrl` = 之前部分（trim）、
   `lazy` = 之后部分（trim，先过 `e()` 转义，见下）。无 `@lazyRule=` 时
   `pageUrl` = 整串、`lazy` = 空。`@rule=` 同款处理（出现即当懒标记，
   语义与 `@lazyRule=` 相同，见 E2 的 `f()` 旁支）。
3. `pageUrl` 按规则基地址补全：`HkParser.resolveUrl(rule.getUrl(), pageUrl)`。
   为空 → 空结果。`pageUrl` 里的 `#...#` 标记段先剥掉（`b()` 在递归前对
   URL 部分做的事；`Flag.splitEpisodes` 也会在 `#` 处切分，见 §3.5）。
4. `lazy` 为空 → 直链：`normalizeUrl(pageUrl)`（见 §3.3）即结果。
5. `lazy` 去尾：剥掉尾部 `;now`（大小写不敏感，仅末尾；童话的
   `eval(input)；；now` 经 `e()` 后是 `eval(input);now`，见 §2.2）。
6. `lazy` 以 `.js:` 或 `js:` 开头 → JS 路：`engine.prepare(rule, pageUrl, 1, "play", "")`
   后 `engine.evalExpression(jsBody, pageUrl)`（现成方法：`input` = pageUrl
   进 scope，返回多语句程序的末值——奇优/臻品fix 的 `.js:` 程序靠这个取值；
   本步不另加 `evalLazy`，复用即够）。
   否则 → 选择器路：先 `fetch` 该页（firstHeader + ua，与 `detail()` 同款请求头），
   再 `engine.prepare(rule, pageUrl, 1, "play", pageText)`，
   `HkParser.extract(Jsoup.parse(pageText, pageUrl), lazy, true, pageUrl, engine)`
   （`extract` 自带 `js:` 整段与 `.js:` 尾巴处理，`evalExpression` 的 input
   是取到的值——童话的 `.js:eval(input)` 由此求值）。
7. 求值结果 `s`（trim 后）再分发（对应 `b()`，深度从 0 起，上限 3）：
   - 空 → 空结果；
   - 含 `@lazyRule=` → 递归 `playResolved(rule, s, depth+1)`，`depth >= 3` 时
     记日志停、返回空结果（“lazy chain too deep”）；
   - 以 `{` 开头且 JSON 含 `urls` 数组 → 多线路 JSON：取 `urls[0]` 为地址；
     `headers` 是数组时取与下标对应的对象（没有则合并全部对象），是对象
     时直接用；`names/subtitle/danmu/lyric` 本步不用（第 7 步）；
   - 以 `video://` 开头 → 去前缀当直链（见 §3.4 的取舍说明）；
   - 以 `pics://` / `webRule://` 开头 → 不支持，空结果（第 7 步）；
   - 否则 → 直链：`normalizeUrl(s)`。
8. `e()` 转义（只对规则部分，不对 URL 部分）：`？？`→`?`、`＆＆`→`&`、
   `；；`→`;`、`，，`→`,`（按此顺序逐段替换；`＆＆＆＆` → `&&`、
   `；；` → `;`，与三条规则原文对得上）。

### 3.3 `normalizeUrl`（直链归一，`HkExecutor` 内私有）

1. `;{...}` 后缀：从第一个 `;` 起是修饰符；若后缀以 `{` 开头，按 JSON
   解析出请求头（复用已有的 `parseJsonHeaders`），其余后缀丢弃。
2. `#...#` 标记段：从第一个 `#` 起截断（`#isVideo=true#` 等嗅探标记，
   播放器不认，见 E5；`b()` 对 URL 部分同款处理）。
3. 返回干净 URL + 头。头里没有 Referer 且 `pageUrl` 是 http(s) 时，
   Referer 默认补 `pageUrl`（E1 的 player() 规则）。

### 3.4 `HkEngine` 补丁（只补三条规则用到的，不写大而全的通用实现）

| 补丁 | 做法 | 依据 |
|---|---|---|
| `request()` | BOOTSTRAP 里把抛错桩换成真函数：`function request(url, options) { return __hk.fetch(url, options); }`，与 `fetch` 同义 | 臻品fix 选集懒加载用 `request(input)` 与 `request(url, {headers})` 共 6 次（E4） |
| `getUrl()` | 新增：`function getUrl() { return __hk.getPageUrl(); }`（即 `MY_URL`） | 童话详情 JS 用 2 次、臻品fix 用 1 次；第 4 步缺它会在真机上抛 ReferenceError，本步是前置修复 |
| `putVar` 对象形式 | `Bridge` 加重载 `putVar(Scriptable obj)`：读 `key`/`value` 两个属性写进 `vars`（Rhino 按参数个数/类型选重载：1 个对象参数走新重载，2 参数走原方法，BOOTSTRAP 不用改） | 臻品fix 的 tab 懒加载有 `putVar({key:'shsort', value:'0'})`（E4）；只去抛错，tab 切换本身仍是第 7 步 |
| `refreshPage()` | 新增空操作 + 日志：`function refreshPage() { __hk.log('refreshPage (no-op)'); }`，不真刷新 | 同上，tab 懒加载调 2 次；去抛错桩，不实现切换 |
| `unescape` 兜底 | BOOTSTRAP 加条件定义：`typeof unescape === 'undefined'` 时才定义（`decodeURIComponent` 实现，失败回原值） | 臻品fix 用 `unescape(base64Decode(...))` 1 次；Rhino 标准对象本就有 `unescape`，兜底只是防万一 |
| `evalLazy` | 不新增：复用现成的 `evalExpression(js, input)`（`input` 进 scope，返回多语句程序的末值） | 奇优与臻品fix 的懒加载都是多语句 `.js:` 程序（E4），`evalExpression` 的语义正好就是“求值并返回末值字符串” |

不做的：`xpath`/`batchFetch`/`getCryptoJS`/`aes`/`require` 仍是抛错桩
（第 7 步）；`putVar` 对象形式不做 `putMyVar` 对称版（规则里没出现）；
`getResCode()` 在懒加载 JS 路返回 `""`（懒加载的页由 JS 自己 `fetch(input)`
去取，与 `detail()` 的“已抓正文”不同——三条规则的选集懒加载都没调它，
记为已知缺口）。

### 3.5 `HkPlayer`（新增，`api/hk/HkPlayer.java`）

`play(Activity, HkRule, Detail, Line, HkCard, detailUrl)`：

- Vod 组装：`name/pic/content` 取 `Detail.info`（详情信息卡，缺失时用列表页
  传进来的 fallback——`HkDetailFragment` 的 `arg("name"/"pic"/"desc")`
  已经是 fallback，`split` 里已补位，这里直接用 `info`）；
  `site` 取 `HkStore.siteOf(rule)`（`key=api=hk://<标题>`，与站源登记一致，
  `Vod.getSiteKey()` 由它导出）；`id` 取 `detailUrl`（同一规则下按剧区分，
  历史键稳定）；`remarks` 留空（集名由 `applyIntentPlaybackSelection`
  写进历史，见 §3.1）。
- `playFrom` = 各 `Line.title` 以 `$$$` 连接；`playUrl` = 每条线路内各集
  `集名$原始URL` 以 `#` 连接（与 `Vod.setFlags()` 的切分规则逐字对应：
  `$$$` 分线路、`#` 分集、第一个 `$` 分集名/地址）。
- 选集原始 URL 在拼之前先剥 `#...#` 标记段：`Flag.splitEpisodes` 会在括号
  深度 0 的 `#` 处切分，带标记的 URL 会被切碎；三条规则的选集 URL 本来就
  不含 `#`，剥离只是防卫（标记语义见 E5，解析时才有意义，存 Vod 时不需要）。
- 组装完调 `vod.setFlags()`（显式建好 `Flag` 列表，`VideoActivity.setDetail`
  直接用 `item.getFlags()`，不依赖别处再建），再调
  `VideoActivity.startHk(activity, vod, line.title, episode.getTitle(), episode.getUrl())`。
- 集名取 `HkCard.getTitle()`（`stripHtml` 后的干净标题，与详情页 chip 显示一致）。

### 3.6 `VideoActivity` 改动（`ui/activity/VideoActivity.java`，mobile 源集）

1. 新增 `startHk(Activity, Vod, playFlag, playEpisodeName, playEpisodeUrl)`：
   `startDirectResolved` 同款 intent（`detail_mode` DIRECT +
   `EXTRA_DETAIL_RUNTIME_MODE` DIRECT + `scan/collect/cast` false +
   `key/id/name/pic/mark/content` + `putDetailVodCache`），再加
   `putIntentPlaybackSelection(intent, playFlag, playEpisodeName, playEpisodeUrl)`
   ——点哪集就选中哪集，沿用 TMDB 直播入口同款选中机制（E6）。
2. `getPlayer(Flag, Episode)` 内，在 `isOriginSession()` 分支之前加 hk 分支
   （与 origin 分支同款 early-return 形态）：
   ```java
   if (isHkSite()) {
       resolveHkPlayerContent(flag, episode);
   } else if (isOriginSession()) {
       ...
   } else mViewModel.playerContent(...);
   ```
   `isHkSite()` = `getKey().startsWith("hk://")`（与 `HkStore.isMiniapp`
   的判定同款规则；直接用 key 前缀，不多一次 `VodConfig` 查询；
   `getKey()` 永不返回 null）。hk 分支**不调** `mViewModel.playerContent`
   （蜘蛛链没有 hk 蜘蛛），`updateHistory(episode)` 与 `showProgress()`
   仍在分支后照常执行（E6 的 `getPlayer` 原文：两者在 if/else 之后）。
3. 新增 `resolveHkPlayerContent(Flag, Episode)`：取 `playFlag`、记
   `playerContentGeneration` 快照（防串位：在途旧解析被切集/切线路作废时
   丢弃，与 `invalidatePlayerContent()` 的 generation 语义一致）；
   后台线程 `HkExecutor.play(rule, episode.getUrl())`，rule 由
   `HkStore.read(key 去 "hk://" 前缀)` 取（取不到 → 当空地址处理）；
   回主线程先核 generation 与 `isFinishing/isDestroyed`，再：
   - 有地址：`Result result = new Result(); result.setUrl(url); result.setHeader(headers); result.setFlag(playFlag); result.setParse(0);` → `setPlayer(result)`（直链已是最终地址，不走二次解析，故 `parse=0`；`setPlayer` 是唯一收口，后续解码/字幕/选集切换都不动）。
   - 空地址或异常：`invalidatePlayerContent(); hideProgress(); Notify.show(R.string.hk_play_no_url)`（“小程序未解析出播放地址”，E1 原文），并记日志（异常信息进 `SpiderDebug`，不在 toast 里展开）。

### 3.7 详情页接线（`ui/hk/`）

- `HkDetailAdapter.Listener` 改为 `onEpisodeClick(HkDetailHelper.Line line, HkCard episode)`：
  `setDetail` 铺平时把选集包成（Line, HkCard）对传下去，不再只传卡片。
  （只改 hk 自己的 adapter，`onEpisodeClick` 的唯一实现者是 `HkDetailFragment`。）
- `HkDetailFragment`：`onDetailLoaded` 里留一份 `mDetail`；`onEpisodeClick`
  改为 `HkPlayer.play(requireActivity(), mRule, mDetail, line, episode, arg("url"))`；
  类注释里“第 5 步只弹‘播放还在做’”的占位说明删掉。
- `lab_strings.xml`：新增 `hk_play_no_url` = `小程序未解析出播放地址`；
  `hk_detail_play_pending`（“播放还在做”）无剩余引用后删除。

### 3.8 不改的

- `BaseLoader`、蜘蛛链、`SiteApi.playerContent`、播放器本身（选集/解码/字幕/
  画质切换）、`menu_nav.xml`（仍 6 项，本步不加底栏项）、TV/leanback、
  `LocalSource.scan()` 的 hk `.json` 登记问题（第 2 步已知缺口，不在本步）。
- 搜索（第 6 步）、云口令/导出/首页内嵌/全部 48 种 `col_type`/`webRule://`/
  `hiker://page/` 子页/`require`（第 7 步）。

## 4. 验收标准

### 4.1 代码层面可确认（本步提交前逐行重读核对）

- [ ] `play()` 的切分/`e()`/前缀判定/`;now` 去尾/深度 3/直链归一，与 E2/E3
  的报告原文逐条对得上；三条规则的懒加载字符串逐字走一遍推演（§2.2）。
- [ ] `HkEngine` 补丁只覆盖 E4 出现过的 API；`request` 与 `fetch` 同义、
  `getUrl` 返回 `MY_URL`、`putVar` 重载不干扰 2 参数调用。
- [ ] `HkPlayer` 拼出的 `playFrom/playUrl` 能被 `Vod.setFlags()` 原样还原
  （`$$$`/`#`/首个 `$` 三处切分点）；`#...#` 已剥离；`site` 是
  `HkStore.siteOf(rule)`；`id` 是详情页 URL。
- [ ] `startHk` 的 intent 与 `startDirectResolved` 逐项对得上，只多
  `putIntentPlaybackSelection`；`getPlayer` 的 hk 分支在 origin 分支之前、
  只在 `hk://` key 时触发；失败路径调 `invalidatePlayerContent() +
  hideProgress() + hk_play_no_url`，不进空白播放器。
- [ ] `git diff --check` 通过；无新增底栏项；无 `BaseLoader` 改动；无 TV 改动。
- [ ] 返回类型复核：`Json.safeListElement` 返回 List 不赋给 JsonArray；
  `cardsFromJs` 等既有方法签名不动；`Result.setHeader(Map<String,String>)`、
  `Result.setParse(Integer)`、`Vod.setFlags()` 返回值类型与调用点一致。

### 4.2 只能真机验（推上去编出包后，用户按三条规则测）

- [ ] 奇优影院♔：电影/电视剧分类进详情，点某一集进播放器、有声画
  （直链 m3u8 或 ldgurl 解析结果）；只测电影/电视剧分类。
- [ ] 童话：点某一集进播放器、有声画（选择器懒加载 + `.js:eval(input)`）。
- [ ] 臻品fix：点某一集进播放器、有声画（第三方解析 `jx.anje.cn` /
  `jhjx.anje.cn` 是规则自带写法，照规则走）；详情页本身能打开
  （`getUrl()` 前置修复生效，之前会 ReferenceError）。
- [ ] 空地址：某条选集解析出空地址时弹“小程序未解析出播放地址”，
  不崩、不进空白播放器。
- [ ] 历史记录里出现这次播放：真片名、真海报、点的那一集。
- [ ] 列表/详情零回归：分类切换、翻页、详情信息、选集顺序照旧；
  详情页点选集不再弹“播放还在做”。
- [ ] 播放器内点别的集：走同一条 hk 分支接着播（`getPlayer` 每次都过分支）。

## 5. 回滚

- 单次提交回滚：`git revert <本提交>` 或 `git reset --hard <父提交>`，
  详情页点选集回到第 4 步的“播放还在做” toast；无数据迁移、无 schema
  改动、无需清缓存。恢复标签见提交时的 `recovery/miniapp-playback/*`。
- `HkEngine` 的 `request`/`getUrl` 是新增/去桩：回滚后规则再调它们又会
  抛错，但第 4 步的详情页本身（除童话/臻品fix 的 `getUrl()` 调用外）
  不受影响——回滚即回到第 4 步已验证的状态。

## 6. 遗留缺口（不在本步）

1. `webRule://`（需 WebView 桥）、`pics://`（图集浏览）、多线路 JSON 的
   选线路 UI（本步只取 `urls[0]`）、`subtitle/danmu/lyric` 不接、
   `video://` 的 parse 标记按直链处理（第 7 步再按 `v12.a` 对齐）。
2. 懒加载 JS 路的 `getResCode()` 返回 `""`（页由 JS 自己 `fetch(input)` 取；
   三条规则的选集懒加载没调它）。
3. `play()` 只实现 GET（与 `home()`/`detail()` 一致；POST 在第 1 步已明确
   不支持，遇到仍按 `buildHomeRequest` 的口径抛错——本步的 `fetch` 走
   `detail()` 同款请求头，不走 `buildHomeRequest` 的 POST 检查，
   规则 URL 里带 `;POST` 时行为未定义，记一笔）。
4. 详情编码默认 UTF-8（第 4 步遗留，GBK 站简介可能乱码，本步不动）。
5. `putVar` 对象形式只做了 `vars`，`putMyVar` 对象形式没做（规则里没出现）；
   `refreshPage()` 是空操作，tab 切换仍是第 7 步（点 tab 卡片目前无动作）。
6. 多线路 JSON 的 `headers` 若是“与 urls 数量不匹配的数组”，本步按“合并
   全部对象”处理（与报告 `s()` 的口径一致，未经真机验证）。
7. 选集 URL 里若含单个不成对的 `#`（非 `#...#` 标记），`normalizeUrl` 从
   第一个 `#` 截断会丢掉 fragment——按海阔语义 `#xxx#` 是标记，单 `#`
   出现在选集地址里的概率极低，真机遇到再修。
