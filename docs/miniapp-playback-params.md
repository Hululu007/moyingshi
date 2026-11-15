# 海阔小程序第 7 步之八：多线路 JSON 的 subtitle/danmu/lyric 接入播放器

> Recovery anchor（任务 `miniapp-playback-params`，按仓库 AGENTS.md §5）
>
> - 目标：`HkExecutor.play()` 的多线路 JSON 从“只取首条”升级为全量解析
>   `{urls, names, headers, subtitle, danmu, lyric}`，多条线路进现有播放器
>   的画质（线路）切换器、切线路时各线路 headers 跟着走；字幕进
>   `Result.subs`、弹幕进 `Result.danmaku`、歌词取文本后进 `Result.lrc` +
>   视频内嵌歌词通道；无这些参数的普通规则零回归；空地址仍报
>   “小程序未解析出播放地址”、不进空白播放器。只改手机端，本地提交，
>   不推、不编、不触发构建、不动版本记录空间。
> - 分支/HEAD：`main` / `7d2eb69b`（第 7 步之七，本地超前 origin 七笔，未推），
>   工作区干净（开始前 `git status` 已核对，无关脏文件：无）。
> - 计划状态：本文档 = 设计评审；实现中。文件/符号见下文“实现清单”。
> - 已验证：本地逐行重读 + `git diff --check`（2026-11-15 19:5x，提交前做，
>   见 §4.1 全部打勾；复核中修了 2 处：歌词喂入第一次错放到短剧过渡方法里
>   （已移回 `setPlayer`）、names 数组改位置对齐（非字符串项占位不跳过）+
>   外挂地址收紧为 http(s)/file）；GitHub 构建（编译检查）未做，本次不推不编，
>   留待推上去之后。
> - 未验证（需要一次构建 + 真机 + 带多线路 JSON 的规则样本）：全部播放器
>   接通效果（见 §4.2；三条验收规则都不产多线路 JSON，只能验零回归）。
> - 未解决的风险：见下文“已知缺口与风险”。
> - 下一个动作：写代码 → 逐行重读 → guard finish 提交 → 汇报父代理。

## 1. 完成句

详情页点某一集，懒加载解析出多线路 JSON 时：全部线路（带各自的名字与
请求头）进播放器现有的画质/线路切换器，播放器内切线路时该线路的请求头
跟着换；`subtitle` 字幕地址进现有外挂字幕通道、`danmu` 弹幕地址进现有
弹幕通道、`lyric` 歌词地址取回文本后进现有视频歌词通道；只有一条线路
或没有这三个参数时行为与第 5 步完全一致；解析不出地址仍弹
“小程序未解析出播放地址”，不进空白播放器。只改手机端，TV/leanback
不动，`BaseLoader` 不动，底栏仍 6 项。

## 2. 设计研究（AGENTS.md §7）

### 2.1 证据表

| # | 来源 | 修订/日期 | 证据等级 | 支持的主张 | 本项目适用性 | 注意点 | 对决策的影响 |
|---|---|---|---|---|---|---|---|
| E1 | 海阔官方规则文档 https://docs.189.tyrantg.com/docs/hikerview/help_rules.html “视频多线路、外挂字幕、弹幕、歌词”一节（本轮重取，2026-11-15） | 2026-11-15 取 | A（官方） | JSON 格式：`{urls:[...], names:[...]}`，names 可不填；headers 是与 urls 下标对应的对象数组；subtitle 是单个地址，支持 srt/vtt/ass；danmu 是单个地址：B站 xml（`http://xxx/1.xml`）、JSON（链接须含 `.json`，内容 `[{"text":"弹幕","time":5.23}]`）、`web://...` WebView 形式（监听 `window.isPlaying`/`window.seek`/`window.lineCount`）；lyric 是单个 `.lrc` 地址；另有 `audioUrls`（音频分离，不在本步） | 格式的唯一权威来源，直接采用 | 文档只给格式，不给播放器接法；`subtitle/danmu/lyric` 都是顶层单个值，不是按线路分的数组 | 解析层按此格式写；三条通道各接项目现有入口（见 E4） |
| E2 | 逆向报告 `~/workspace/user/files/__________8_3azg.md`：`f22.s()` 多线路 JSON 解析（L5268–5352，全文可读） | 2026-11-14 上传 | A（原文） | `urls` 全收、`names` 全收（缺的补 `线路N`、多的截掉，与 urls 对齐）；`headers` 是数组且长度 == urls 长度时按下标一一对应，否则全部对象合并成一个 map；`audioUrls` 与 `subtitle` 只读了一遍就丢掉（`optString(...).getClass()` 是反编译丢弃值的残留，无后续使用） | 采用：names 补齐/截断规则、headers 的“等长按下标、否则合并”口径 | `s()` 里 `subtitle` 没有消费者；全文 grep `danmu`/`lyric` 0 命中——**上游播放器怎么接 subtitle/danmu/lyric，报告里没有代码** | 解析口径照 `s()` 写；播放器接法不能声称“照搬上游”，只能按官方格式 + 本项目现有通道设计（见 §2.2 方案 B 的否决理由） |
| E3 | 同报告 `HkListActivity.e1()`（L1440–1500，可读） | 同上 | B（原文） | 单条直链 + 规则头 + `subtitle`/`audioUrls` 会被重包成 `{urls:[一条], headers, subtitle?, audioUrls?}` 再进播放链 | 佐证 `subtitle` 是跟地址走的顶层参数、不是按线路分的 | 仍无播放器消费者；`e1` 是列表页的预处理，不在 `play()` 路径上 | 不动 `e1` 逻辑；本步只动 `play()` 的 `outcomeFromMultiLine` |
| E4 | 现有实现（本轮原文重读）：三条播放器通道都已存在且接在 `Result` 上 | main 7d2eb69b | A（本地代码） | 字幕：`PlaySpec.from(Result)` 取 `result.getSubs()` → `ExoUtil.buildSubtitleConfigs`（外挂字幕轨道）+ `SubtitlePlaybackSession.onPlaybackStarted(this, result)` 用 `result.getSubs()`；`Sub.from(url)` 由地址建 Sub（名取文件名、`PlayerHelper.getSubtitleMimeType`：.vtt/.ssa/.ass/.ttml/.xml/.dfxp，否则 SubRip），与官方支持的 srt/vtt/ass 对得上。弹幕：`PlaySpec` 取 `result.getDanmaku()` → `PlayerManager.setDanmakus` → 首条 `setDanmaku(item)` → `DanmakuUrlPolicy.classify`：http(s)/file/content 且 host 合法为 STATIC，走 `danmakuController.setDataSource(Uri)`；ws/wss 为 LIVE，走直播会话；其余 UNSUPPORTED 直接清掉；`Danmaku.from(url)` 由地址建对象。歌词：`Result.lrc` 只有 `AudioActivity.loadLyrics` 在读，经 `LyricUtil.loadProvided`（http(s) 地址抓文本、否则原样当内嵌文本）；`VideoActivity` 的歌词是另一条线：`mInlineLyrics`（定时文本）→ `LyricsController.setInlineLyrics`（要求含 `[mm:ss.xx]` 定时行）→ `setPlaybackLyrics(result.getDesc())` 在 `setPlayer` 里喂 desc；`Result.getLrc()` 在 `VideoActivity` 里无人读 | 三条通道全部复用，不新写播放器、不新写渲染器 | `Sub` 无 header 字段（需 Referer 的字幕站可能拉不到）；`Result` 没有 `setDanmaku`、没有 `setUrl(Url)`（见 E8）；`web://` 弹幕不在 `classify` 支持的 scheme 里；歌词要先进 `VideoActivity` 必须先有文本、且 `setPlayer` 在主线程不能同步抓网 | 字幕/弹幕走 `Result` 字段；歌词在后台线程先取文本再进 `Result.lrc`，`setPlayer` 里 hk-only 喂内嵌歌词 |
| E5 | 现有实现：多线路切换器就是画质切换器（本轮原文重读） | 同上 | A | `Url`：`add(n, v)`（名+地址）、`isMulti()`（values > 1）、`set(position)`；`QualityAdapter` 显示 `result.getUrl().n(position)`、点选调 `setPosition` 后 `listener.onItemClick(result)`；`VideoActivity.setPlayer` 里 `setQualityVisible(result.getUrl().isMulti())`；`onItemClick(Result)`（画质切换）直接 `startPlayer(..., result, ...)` 重播同一 Result，不重跑 `getPlayer`、不重解析 | 多线路进 `Url` 多值即进现有切换器，零新 UI | `Result.header` 是单 map：切线路重播时 header 不变，**各线路 headers 不会自动跟着走**，必须另挂一个按 position 换 header 的钩子（见 §3.4） | 方案 C 的切换器设计由此定 |
| E6 | 现有实现：`HkExecutor.play()` 调用链（本轮原文重读） | 同上 | A | 唯一调用方是 `VideoActivity.resolveHkPlayerContent`（`play(rule, episodeUrl, activity, dialogHost)`，后台线程；有地址建 `Result`（`setUrl/setHeader/setFlag/setParse(0)`）→ `setPlayer`，空地址/notice 走原有分支）；`outcomeFromMultiLine` 只取 `urls[0]`、headers 按 E2 口径取首条/合并；`PlayOutcome` 只有 `url/headers/notice`；`HkActionHandler` 的懒结果 `{urls}` 分支也只取 `urls[0]`（点动作卡片路径，不在播放链上） | 改动面：`outcomeFromMultiLine` + `PlayOutcome` + `resolveHkPlayerContent` 三处 | `url`/`headers` 字段是现有调用方的契约，保留为“首条线路”的值，只新增字段，不改现有字段语义 | 兼容性设计：`url/headers` = 首条，新增 `lines/subtitles/danmaku/lyric` |
| E7 | 现有实现：详情页线路分组（本轮原文重读） | 同上 | A | `HkDetailHelper.split` 的线路来自 `detail_find_rule` 的输出卡片（线路头卡片开新线，选集归入当前线）；`HkPlayer.play` 按 `Detail.lines` 拼 Vod（`$$$` 分线路）；`play()` 是点某一集之后、按选集 URL 跑懒加载才调到的，多线路 JSON 是**某一集的多个备选源**，在详情页切分完成之后才出现 | **任务书“names 能落到详情页线路头上”的前提不成立**：names 的落点不是详情页，是播放器内切换器；详情页的线路头本来就有（来自详情规则），不需要、也不能由 `play()` 的 names 回填 | 这是本步的一处设计纠正，不是实现遗漏（见 §3.1） | 不改 `HkDetailHelper`、`HkPlayer`；names → `Url` 多值的名（画质切换器显示） |
| E8 | 现有实现：`Result` 的 setter 现状（本轮原文重读） | 同上 | A | 有 `setUrl(String)`（只换当前 position 的值）、`setSubs`（仅空时设）、`setHeader`（仅空时设）、`setLrc`；**无 `setDanmaku`、无 `setUrl(Url)`**；`getHeader()` 在 header 已设时返回 live map（可原地换内容）；`getDanmaku()` 受 `DanmakuSetting.isLoad()` 门控（关了弹幕加载则返回空，与蜘蛛结果同款行为）；`Value.create(n, v)` 会过 `Trans.s2t`（用户繁简设置门控，与全 App 显示名同款处理） | 弹幕与多值 Url 需要两个新增 setter（main 源集、纯新增、无现有调用方受影响） | main 源集改动只此两处新增方法，TV 行为零变化（见 §3.5） | 接受两处 main 源集 additive 改动，不用 Gson 拼 JSON / 不用反射 |

### 2.2 方案比较

| 方案 | 内容 | 优点 | 缺点 / 风险 | 结论 |
|---|---|---|---|---|
| A. 不做 | 保持只取首条，`subtitle/danmu/lyric` 继续丢掉 | 零风险 | 用户已定第 8 步；多线路规则只能播首条线路、无字幕/弹幕/歌词 | 否决 |
| B. 照搬上游 | 全量复刻魔影播放器接 subtitle/danmu/lyric 的做法 | 与魔影行为最接近 | 报告里根本没有这部分代码：`s()` 里 subtitle 读完即弃、`danmu`/`lyric` 全文 0 命中（E2）；魔影播放器与本项目播放器（Exo/mpv + 自有弹幕控制器 + LyricsController）结构不同，无从照搬，只能按官方格式 + 本项目通道重写 | 否决（解析口径照 `s()`，接法按本项目通道） |
| **C. 窄化适配（推荐）** | 解析全量进 `PlayOutcome`（`lines` + `subtitles` + `danmaku` + `lyric`，口径照 E1/E2）；多线路进 `Result` 的 `Url` 多值（画质切换器显示 names，切线路时按 stash 换各线路 headers）；字幕 `Sub.from` → `Result.setSubs`；弹幕 `Danmaku` → 新增的 `Result.setDanmaku`；歌词后台线程取文本（`LyricUtil.loadProvided`）→ `Result.setLrc`，`setPlayer` 里 hk-only 喂内嵌歌词；`web://` 弹幕与 `audioUrls` 明确不支持 | 全部落在现有通道上（E4/E5），无新播放器、无新渲染器、无新 UI；单线路/无参数规则行为与第 5 步完全一致；每条通道的接通程度可逐条核对 | 切线路换 headers 要在 `onItemClick(Result)` 挂一个 hk-only 钩子（见 §3.4）；`Sub` 无 header 字段，需 Referer 的字幕站可能拉不到；歌词要多一次后台抓取（失败只丢歌词、不影响播放） | **采用** |
| D. 只解析不接播放器 | `PlayOutcome` 里解析全，但 `resolveHkPlayerContent` 仍只用首条 url/headers | 改动最小 | 等于半步：参数解析了但用户看不到字幕/弹幕/歌词、切不了线路，验收标准达不到 | 否决（可作 C 失败时的降级，但本步不取） |

### 推荐

采用方案 C。理由：解析口径有两处可信来源（官方文档格式 + 报告 `s()` 的
names/headers 口径），三条播放器通道在本项目里都已存在且都挂在 `Result`
上（E4），多线路切换器就是现成的画质切换器（E5）；本步只是把解析结果
喂进这些已有的口子，不造新播放器、不造新渲染器；唯一的新增是两个
`Result` setter（纯新增方法）和一个 hk-only 的切线路换 header 钩子，
回滚干净。

## 3. 推荐设计（方案 C）

### 3.1 调用链（全程复用现有入口，不造播放器）

```
详情页点选集（链路与第 5 步相同，HkPlayer/Vod 不变）
  → VideoActivity.getPlayer → isHkSite() → resolveHkPlayerContent
    → 后台线程 HkExecutor.play(rule, episodeUrl, activity, dialogHost)
      → dispatchResolved：{urls} JSON → outcomeFromMultiLine（本步重写，全量解析）
    → 回主线程：PlayOutcome → Result（见 §3.3）→ setPlayer(result)（唯一收口）
      → PlaySpec.from(result)：subs/danmaku 进播放器；Url 多值 → 画质切换器显形
      → setPlayer 内 hk-only：result.getLrc() → setPlaybackLyrics → 内嵌歌词
播放器内切线路（画质切换器点另一条线路）
  → QualityAdapter.onItemClick → VideoActivity.onItemClick(Result)
    → hk-only：按 Url 当前 position 把 stash 里该线路的 headers 换进 result
    → startPlayer(同一 Result) 重播：新线路地址 + 新线路 headers + 同一套 subs/danmaku/lrc
```

设计纠正（E7）：多线路 JSON 的 `names` 不落到详情页线路头上——详情页的
线路分组在点选集之前已由 `detail_find_rule` 的输出定好（`HkDetailHelper.split`），
`play()` 是点某一集之后才跑的，names 在那时才出现，回填不到详情页；
names 的正确落点是播放器内画质切换器的选项名（`Url.n(position)`）。
详情页本身不用改，`HkDetailHelper` 与 `HkPlayer` 一行不动。

### 3.2 `HkExecutor` 改动（`api/hk/HkExecutor.java`，mobile）

`PlayOutcome` 新增字段（现有 `url/headers/notice` 语义不变，仍是首条线路的值）：

```java
/** 多线路 JSON 的一条线路：归一后的地址 + 线路名 + 该线路的请求头（含 Referer 默认）。 */
public static class PlayLine {
    public String url = "";
    public String name = "";
    public Map<String, String> headers = new HashMap<>();
}
public List<PlayLine> lines = new ArrayList<>();   // 空 = 不是多线路 JSON（直链路径）
public List<String> subtitles = new ArrayList<>(); // subtitle 地址（已按 referer 补全，见 §3.2.1）
public List<String> danmaku = new ArrayList<>();   // danmu 地址（已按 referer 补全；web:// 形式已滤掉，见 §3.2.2）
public String lyric = "";                          // lyric 原值：.lrc 地址（已按 referer 补全）或内嵌歌词文本
```

#### 3.2.1 `outcomeFromMultiLine(JsonObject, referer)` 重写（口径照 E1/E2）

1. `urls`：数组，逐项 trim、去空；每项先过 `normalizeDirect(单条, referer)`
   （` ;{headers}` 后缀剥离 + `#...#` 剥离 + Referer 默认补 referer），
   得到该线路的干净地址与头。归一后地址为空的跳过（不占线路位）。
2. `names`：数组；与 urls 对齐——缺的按 `s()` 口径补 `线路N`（1-based，
   如第 2 条缺名 → `线路2`），多的截掉。空字符串名也视为缺名补 `线路N`。
3. `headers`：沿用现有 `mergeJsonHeaders`。数组且长度 == urls 长度时，
   第 i 个对象进第 i 条线路（`normalizeDirect` 已给的头优先，JSON 头只补
   缺的键，与现有首条逻辑的优先顺序一致）；否则全部对象合并成一个 map，
   每条线路都补一遍缺的键（`s()` “数量对不上则合并”的口径）。
   非对象/非数组的 `headers`（如字符串）忽略并记日志。
4. `subtitle`：字符串取 1 条；数组取全部字符串项（每条都是一个外挂字幕，
   播放器支持多字幕切换）。相对地址按 `HkParser.resolveUrl(referer, v)`
   补全（referer 是选集页地址）。补全后非 http(s)/file 的值记日志丢掉
   （`Sub.from` 的地址最终要进 Exo 的 HTTP/文件数据源）。
5. `danmu`：同款字符串/数组；相对地址同款补全；`web://` 开头的值直接
   滤掉并记日志（E4：`DanmakuUrlPolicy.classify` 不认 `web` scheme，
   现有弹幕通道没有 WebView 弹幕入口，见 §5 缺口 3）。
6. `lyric`：字符串原值；看起来像地址（无换行、不以 `[` 开头）时按
   `HkParser.resolveUrl(referer, v)` 补全；已是内嵌定时文本（含 `[mm:ss`）
   时原样保留。数组形式只取第 1 项（歌词只有一条通道）。
7. `audioUrls`：读到也不接（官方“音频分离”字段，本步范围外；见 §5 缺口 4）。
8. 填 `out.url/out.headers` = `lines.get(0)`（现有调用方契约不变）；
   `lines` 为空（urls 全空）时 `out` 保持空结果（调用方走原有空地址分支）。

#### 3.2.2 不动的部分

- `play()` / `playResolved()` / `dispatchResolved()` 的分发结构不动；
  `{urls}` JSON 仍只在 `dispatchResolved` 的 `{` 分支进
  `outcomeFromMultiLine`（`webRule://` 解析回来的 `{urls}` 结果同款走这里）。
- `HkActionHandler` 的懒结果 `{urls}` 分支（点动作卡片，只取 `urls[0]`）
  不动——那是点击执行路径，不是播放链；记为遗留（§5 缺口 5）。

### 3.3 `VideoActivity.resolveHkPlayerContent` 改动（mobile）

后台线程内（已在后台线程，歌词抓取不占主线程）：

```java
if (outcome != null && !outcome.isEmpty()) {
    resolved = new Result();
    if (!outcome.lines.isEmpty()) {
        // 多线路（含单条）：Url 多值进画质切换器；names 即切换器选项名。
        Url url = Url.create();
        List<Map<String, String>> lineHeaders = new ArrayList<>();
        for (PlayLine line : outcome.lines) {
            url.add(line.name, line.url);
            lineHeaders.add(line.headers);
        }
        resolved.setUrl(url);                 // 新增的 Result.setUrl(Url)，见 §3.6
        mHkLineHeaders = lineHeaders;         // stash：切线路时按 position 换 header
    } else {
        resolved.setUrl(outcome.url);         // 直链路径：与第 5 步完全一致
        mHkLineHeaders = null;
    }
    resolved.setHeader(outcome.headers);      // 首条线路的头（与 out.headers 一致）
    resolved.setFlag(playFlag);
    resolved.setParse(0);
    if (!outcome.subtitles.isEmpty()) {       // 字幕：Sub.from(地址) → Result.setSubs
        List<Sub> subs = new ArrayList<>();
        for (String s : outcome.subtitles) subs.add(Sub.from(s));
        resolved.setSubs(subs);
    }
    if (!outcome.danmaku.isEmpty()) {         // 弹幕：Danmaku → 新增的 Result.setDanmaku
        List<Danmaku> items = new ArrayList<>();
        for (String d : outcome.danmaku) {
            Danmaku item = Danmaku.from(d);
            item.setName(UrlUtil.path(d));    // 名取文件名，不用整条 URL 当名
            items.add(item);
        }
        resolved.setDanmaku(items);
    }
    if (!TextUtils.isEmpty(outcome.lyric)) {  // 歌词：后台取文本（URL 抓、内嵌原样），失败只丢歌词
        try {
            String text = LyricUtil.loadProvided(outcome.lyric);
            if (!TextUtils.isEmpty(text)) resolved.setLrc(text);
        } catch (Exception e) {
            Log.w(TAG, "hk lyric load failed", e);
        }
    }
}
```

注意：`resolveHkPlayerContent` 开头先 `mHkLineHeaders = null`（新一次解析
开始，旧 stash 作废）；成功回调里 `setPlayer(result)` 之前才写新 stash，
stash 与 Result 永远同步（画质切换器只在 `setPlayer` 之后才显形，不存在
stash 滞后被误用的窗口）。

### 3.4 播放器内切线路（`VideoActivity`，mobile）

`onItemClick(Result)`（画质切换器回调）在 `startPlayer` 之前加 hk-only 钩子：

```java
if (isHkSite()) applyHkLineHeaders(result);
```

```java
/** 海阔多线路：按 Url 当前 position 把该线路的 headers 换进 Result 再重播。 */
private void applyHkLineHeaders(Result result) {
    if (result == null || mHkLineHeaders == null) return;
    int position = result.getUrl().getPosition();
    if (position < 0 || position >= mHkLineHeaders.size()) return;
    Map<String, String> headers = mHkLineHeaders.get(position);
    if (headers == null) return;
    Map<String, String> current = result.getHeader(); // header 已设时是 live map
    current.clear();
    current.putAll(headers);
}
```

依据：`onItemClick(Result)` 重播的是同一个 Result（E5），`PlaySpec.from`
在 `startPlayer` 内才读 `result.getHeader()`，换 header 的时机正好；
字幕/弹幕/歌词都在同一个 Result 里，切线路时原样跟着走（`PlaySpec` 重建
即重读它们；弹幕 `setDanmaku` 遇相同 URL 会跳过重载，不同线路共用同一条
danmu 地址时不重复拉取）。

### 3.5 `setPlayer` 喂歌词（`VideoActivity`，mobile）

`setPlayer(Result)` 里 desc 处理块之后加 hk-only 一行：

```java
if (isHkSite() && !TextUtils.isEmpty(result.getLrc())) setPlaybackLyrics(result.getLrc());
```

依据：`setPlaybackLyrics` 只收含定时行的文本（`getTimedLyrics` 过滤），
`mInlineLyrics` 随后由 `refreshLyrics → showInlineLyrics` 走现有内嵌
歌词通道显示；hk-only 门控保证蜘蛛结果零回归（蜘蛛的 `Result.lrc` 本来
只在 `AudioActivity` 里读，`VideoActivity` 从不读它）。

### 3.6 `Result` 新增 setter（`bean/Result.java`，main 源集，纯新增）

```java
public void setUrl(Url url) { this.url = url; }
public void setDanmaku(List<Danmaku> danmaku) { this.danmaku = danmaku; }
```

这是本步唯一的 main 源集改动：两个纯新增方法，无现有调用方、无行为变化，
TV flavor 编译同一份文件但行为零变化（TV 上没有 hk 调用链）。
不用 Gson 拼 JSON、不用反射——setter 是最直接的类型安全做法。

### 3.7 不改的

- `BaseLoader`、蜘蛛链、`SiteApi.playerContent`、播放器本身（选集/解码/
  字幕/弹幕/歌词渲染、画质切换器）、`menu_nav.xml`（仍 6 项）、TV/leanback、
  `HkPlayer`（Vod 组装不感知多线路 JSON）、`HkDetailHelper`（详情页线路分组
  不感知多线路 JSON，见 §3.1 纠正）、`HkActionHandler`（§3.2.2）、
  `HkEngine`、`HkSubpage`、`HkWebRule`。
- `pics://` 图集浏览仍不支持（第 7 步之七已定）；`audioUrls` 不接（§5 缺口 4）。

## 4. 验收标准

### 4.1 代码层面可确认（本步提交前逐行重读 + `git diff --check`）

- [x] `outcomeFromMultiLine` 的 urls/names/headers 口径与 E1/E2 逐条对得上：
  names 缺补 `线路N`/多截掉、headers 等长按下标否则合并、每条线路过
  `normalizeDirect`（Referer 默认补 referer）。
- [x] `PlayOutcome.url/headers` 仍是首条线路的值；直链（非 JSON）路径
  `lines` 为空，`resolveHkPlayerContent` 走原来的 `setUrl(String)` 分支。
- [x] `Result.setUrl(Url)` / `setDanmaku(...)` 是纯新增方法；
  `Sub.from` / `Danmaku.from` / `LyricUtil.loadProvided` 的调用参数
  与现有签名一致；`setSubs`/`setHeader` 只在空时设（新 Result 满足）。
- [x] 切线路钩子只在 `isHkSite()` 时跑、`startPlayer` 之前换 header；
  stash 在解析开始时清零、成功回调里与 Result 同步写；`onItemClick(Result)`
  的其余逻辑一行未动。
- [x] `setPlayer` 的歌词喂入有 `isHkSite()` 门控；歌词抓取在后台线程、
  try/catch 包住，失败不影响播放。
- [x] `git diff --check` 通过；无新增底栏项；无 `BaseLoader` 改动；无 TV 改动；
  返回类型复核：`JsonArray` 经 `getAsJsonArray` 取、不把
  `Json.safeListElement` 的 List 赋给 JsonArray；无泛型原始类型；
  无新增 dialog/布局（无不透明背景问题）。

### 4.2 只能真机验（推上去编出包后，用户按规则测）

- [ ] 三条验收规则（奇优影院♔/童话/臻品fix）零回归：点选集照常播出声画；
  它们不产多线路 JSON，本步对它们只改解析路径的 JSON 分支，直链路径未动。
- [ ] 多线路 JSON 规则（需另找一条返回 `{urls, names, headers}` 的规则，
  三条验收规则里没有样本）：点选集播首条线路；画质切换器出现各线路名，
  点另一条线路能切过去且不断流失败（各线路 headers 跟着走）。
- [ ] 带 subtitle 的多线路 JSON：播放器字幕菜单出现外挂字幕、能选中显示
  （srt/vtt/ass 任一格式）。
- [ ] 带 danmu 的多线路 JSON：.xml/.json 弹幕地址能加载显示；
  `web://` 形式明确不显示（无 WebView 弹幕通道，见缺口 3）。
- [ ] 带 lyric 的多线路 JSON：歌词按视频内嵌歌词通道显示（定时行滚动）。
- [ ] 空地址：某条选集解析出空地址时弹“小程序未解析出播放地址”，
  不崩、不进空白播放器（与第 5 步一致）。
- [ ] 列表/详情零回归：分类切换、翻页、详情信息、选集顺序照旧。

## 5. 回滚路径

- 整步回滚：`git revert <本提交>`（一个原子提交，recovery tag 见提交信息）。
  `play()` 回到只取首条；`resolveHkPlayerContent` 回到单地址 Result；
  画质切换器不再出现多线路名；`Result` 的两个新增 setter 随 revert 消失
  （它们是纯新增，无调用方残留）；`setPlayer`/`onItemClick` 的 hk-only
  钩子一并回去。无数据迁移、无 schema 改动、无需清缓存。
- 局部回滚：只回滚切线路换 header 钩子（`onItemClick` 里的一行 +
  `mHkLineHeaders` 字段）：多线路仍能进切换器、仍能切，但切线路后 headers
  保持首条线路的（第 5 步的行为）；字幕/弹幕/歌词不受影响。

## 6. 已知缺口与风险

1. **三条验收规则都不产多线路 JSON**：奇优/童话/臻品fix 的懒加载结果
   都是单条直链（第 5 步任务文档 §2.2 逐字读过）。§4.2 里多线路/字幕/
   弹幕/歌词五项只能等一条带 `{urls, names, subtitle, danmu, lyric}`
   的规则样本再真机验；本步提交前只能做到 §4.1 的代码层面确认。
2. **字幕无请求头**：`Sub` 没有 header 字段，外挂字幕走 Exo 默认 HTTP
   数据源；需要 Referer/Cookie 才给字幕的站可能拉不到。要接头需改
   字幕加载链（播放器内部），超出“只接已有通道”的范围。
3. **`web://` 弹幕明确不支持**：官方文档的 WebView 弹幕形式
   （`danmu:'web://...'`，监听 `window.isPlaying`/`window.seek`/
   `window.lineCount`）在现有弹幕通道里没有入口
   （`DanmakuUrlPolicy.classify` 不认 `web` scheme）；本步滤掉并记日志，
   不假装接通。要支持需在播放器上挂 WebView 弹幕层，另立任务。
4. **`audioUrls` 不接**：官方“音频分离”字段（`{urls, audioUrls}`），
   报告 `s()` 里读到但同样无消费者；本项目播放链里没有“另配一条音轨”
   的现成入口，留待后续。
5. **`HkActionHandler` 的 `{urls}` 分支仍只取首条**：点动作卡片、懒结果
   是多线路 JSON 时只拿 `urls[0]` 去开详情页（第 7 步之七的路径），
   与本步播放链无关；要统一需另起一小步。
6. **切线路换 headers 只覆盖画质切换器路径**：自动连播下一集、切集都
   重走 `getPlayer → resolveHkPlayerContent`（重新解析，headers 重新算），
   不受 stash 影响；stash 只在同集内切线路时用。
7. **歌词多一次后台抓取**：`.lrc` 与视频不同源时抓取无 headers
   （`LyricUtil.loadProvided` 不接头）；抓取失败/超时只丢歌词，不影响播放；
   超大歌词文件无截断（.lrc 通常很小，真机遇到再加 cap）。
8. **弹幕地址是 JSON 对象/内嵌数组时不接**：只认字符串/字符串数组地址；
   规则直接把 `[{text,time}]` 写进 JSON 的写法（官方文档是链接形式，
   不是内嵌）不在本步处理。
9. **弹幕自动搜索可能盖掉规则弹幕**：与蜘蛛结果同款行为——用户开了弹幕
   自动搜索且“蜘蛛优先”关时，`searchAutoDanmaku` 的回调会用自动搜到的
   弹幕替换规则给的 danmu；要规则弹幕优先，需开“蜘蛛优先”。本步不改
   这个既有语义。

## 7. 实现清单

- [x] 设计评审（本文档）
- [x] `HkExecutor`：`PlayOutcome` 加 `PlayLine/lines/subtitles/danmaku/lyric` +
  `outcomeFromMultiLine` 重写（全量解析）
- [x] `VideoActivity.resolveHkPlayerContent`：多线路 Url + subs/danmaku/lrc
  进 Result + `mHkLineHeaders` stash
- [x] `VideoActivity.onItemClick(Result)`：hk-only 切线路换 header 钩子
- [x] `VideoActivity.setPlayer`：hk-only 歌词喂入
- [x] `Result`：新增 `setUrl(Url)` / `setDanmaku(List<Danmaku>)`
- [x] 逐行重读 + `git diff --check` + guard finish（本地提交，不推不编）
