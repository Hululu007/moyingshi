# 移植阶段 4c：HkHttpParser + HkLazyRuleParser + 接线 + HkParser 替换

> Recovery anchor（任务：miniapp-xingchen-4c；lane：standard；分支 main；HEAD 3eae9091）
> 目标：阶段 4 收尾——搬星辰 HkHttpParser.java（858 行）、HkLazyRuleParser.java（326 行）
> 及依赖 HkPlayData.java（95 行）、util/HkCodeUtil.java（378 行）；接线
> （HkEngine 门面 selector 字段/getSelector()、HkExecutor home/detail/search/lazy
> 调用点、HkRule.isJsFindRule）；删旧 HkParser.java（548 行）；3 条验收规则
> 等价核对（代码级）；本地提交 + recovery tag，不推不编。做完即停，不开阶段 5。
> 范围：新增 4 文件 + 本文件；改 HkJsRuntime（仅加 newSelector）、HkEngine、
> HkRule、HkExecutor、HkSubpage、HkActionHandler、ui/hk/HkDetailFragment、
> HkParserKit（仅加适配段）、HkCard/HkStore/HkWarmup（仅注释）；删 HkParser.java。
> 状态：新文件已搬、逐行 diff 已验（见 §6），待接线编辑与 guard finish 提交。

## 1. 来源与方法

- 星辰原文：`~/workspace/xingchen-ref`（HEAD 52450c0），
  `app/src/main/java/com/fongmi/android/tv/api/hk/`。
- 移植方式：机械搬运脚本 `/tmp/xc4/port4.py`（包名改写 + 文件头插入 +
  §3 所列适配；不随仓库走）。包名改写 `com.fongmi.android.tv` →
  `com.github.moyingshi.osc.tv`；`com.fongmi.quickjs.*` 不动。
  每个新文件在 package 行后加一行文件头移植说明（3a/3b/3e/4a/4b 同款）。
- 交叉验证：oktv 反编译源 `~/workspace/hk-ref/src/sources/` 只有
  `HkPageParser$HkPage` 类文件可对位，无 HkHttpParser/HkLazyRuleParser
  独立文件；本笔以星辰真源码为准。HkHttpParser 类注释自述直接移植自
  8.83 HttpParser，HkLazyRuleParser 自述直接移植自 8.83 LazyRuleParser。

## 2. 搬入清单

| 类 | 星辰行 | 我方落点 | 我方行 |
|---|---|---|---|
| HkHttpParser | 858 | api/hk/HkHttpParser.java | 859（+1 文件头） |
| HkLazyRuleParser | 326 | api/hk/HkLazyRuleParser.java | 327（+1 文件头） |
| HkPlayData | 95 | api/hk/HkPlayData.java | 96（+1 文件头） |
| HkCodeUtil | 378 | api/hk/util/HkCodeUtil.java | 395（+1 文件头，+16 dealFileByHiker 适配，见 §3） |

## 3. 适配点

### 3.1 HkCodeUtil.dealFileByHiker（唯一实质适配）

星辰调 `HkHikerRuleUtil.getRulesByHiker(url, listener)`（异步版，星辰
HkHikerRuleUtil:267）；我方只搬了同步版 `getRulesByHiker(String)`（3e-1），
异步版未搬。同步版与异步版同源（异步版只是把同步版包进 HEAVY_TASK 回调，
外加 empty/page 两支直回与 404 格式分支）。此处直调同步版：

- `hiker://assets/` 走 `getAssetsFileByHiker`（不变）；
- `hiker://empty…` / `hiker://page…` 按星辰异步版原样直回
  （`replaceOnce(p0, "empty", "")` / `p0` 本身）；
- 其余（home/bookmark/download/collection/history）走同步版
  `getRulesByHiker(url)`，结果进 `listener.onSuccess(...)`；
- 404 格式分支不可达（`HkCodeUtil.get` 只在 `url.startsWith("hiker://")`
  时才调本方法）；未知 hiker 子类型时同步版回 `""`，与异步版
  `onSuccess("")` 同终态。
- 线程：唯一调用方是 `HkHttpParser.get/post`（经 HkLazyRuleParser HTML
  支的后台执行器调用），同步文件 I/O 不占主线程。异步版
  `getRulesByHiker(url, listener)` 仍未搬（属 HkHikerRuleUtil，阶段 6/7 按需）。

### 3.2 HkParserKit 新增“墨影视适配”段（resolveUrl / jsBody）

星辰 HkParserKit 没有这两个方法；旧 HkParser.java 的调用方需要一个共享
落点，故把两方法**原实现逐字**移入 HkParserKit 末尾的适配段（带注释标明
非星辰原文），其余 HkParserKit 内容与星辰逐字节一致：

- `resolveUrl(baseUrl, value)`：相对地址按 baseUrl 补全；绝对地址、
  data:/blob:/file:/javascript: 原样返回；`//` 补 scheme。
  选用它而不用 `HkCommonParser.joinUrl` 的原因：joinUrl 在值是
  `data:/blob:/javascript:` 等未知 scheme 时会 `MalformedURLException`
  而回 baseUrl（错把规则首页当地）；旧 resolveUrl 的原样返回才是调用方
  （HkDetailFragment、HkActionHandler）依赖的行为。
- `jsBody(rule)`：去 `js:` 前缀返程序体，非 `js:` 原样返回。
- 为此 HkParserKit 新增 `import android.text.TextUtils;` 一行。

### 3.3 HkJsRuntime.newSelector()（星辰原文补入）

星辰 HkJsRuntime:10565-10568 有 `newSelector()`（`new HkSelector()` +
`setJsEvaluator(this)`），我方 3b/3c/3d 搬运时漏了它；本笔按星辰原文
补在 `getError()` 之前。HkEngine 门面与 HkExecutor 均经它取带 JS 求值
能力的选择器（`.js:` 段由运行时求值）。

### 3.4 HkEngine 门面恢复（3e 空位）

- 新增字段 `private HkSelector selector;`（非 final：无参适配构造在
  prepare() 时才有运行时），`(HkRule)` 构造与 `prepare()` 里在运行时
  init 成功后赋值 `this.selector = jsRuntime.newSelector();`；
  新增 `public HkSelector getSelector()`。
- `home()` / `search()` 的选择器分支按星辰原文恢复：`getMarked(url)` 抓页
  后 `selector.parseList(html, findRule, url)` /
  `selector.parseSearch(html, searchFind, url)`，3e 的“not yet ported”
  日志空位删除。
- `isJsRule` 改为委托 `HkSelector.isJsRule`（3e 的本地小方法删除，
  语义逐字相同：`rule != null && rule.trim().startsWith("js:")`）。
- 适配段注释里“选择器分支是空位 / 调用点（HkExecutor、HkParser、HkSubpage）”
  等 3e 措辞同步更新；`evalTextRule` 的 `HkParser.jsBody` 改
  `HkParserKit.jsBody`。其余门面/适配段不动（preRule、URL 拼法、门面与
  HkExecutor 的分工仍按 3e 文档 §6.2）。

### 3.5 HkRule.isJsFindRule 与 getFindRule 复核

- `isJsFindRule()` 改委托 `HkSelector.isJsRule`。
- `getFindRule()` 的 null→`""` 归一**保留**（2a 留的“阶段 4 换 HkSelector
  时再复核”到此闭环）：复核结论是新调用点对 `""` 与 null 同为空结果
  （HkSelector.parseList 空规则回空表；evalFindRule 的 jsRuntime.parseList
  在 `""` 上不跑规则体），归一无 NPE 风险，不跟进星辰直返 null；两处
  注释里的旧行号（HkExecutor:98/:103）已失效，改写为不引行号的描述。

### 3.6 HkExecutor 接线（调用点全量映射）

| 旧调用点 | 新调用点 |
|---|---|
| homeDetailed:103 `HkParser.parseHomeListDetailed`（ParseOutcome.matched 诊断） | `engine.getSelector().parseList(pageText, findRule, spec.url)` → `toCards(List<HkItem>)`（本文件新增私有转换）；诊断去掉命中数，见 §4.1 |
| search:140/143 `HkParser.isJsRule` / `parseSearchList` | `HkSelector.isJsRule` / `engine.getSelector().parseSearch(...)` → `searchResultsToCards(List<SearchResult>)`（新增私有转换） |
| splitUrlModifiers:230 `HkParser.splitOutsideQuotes` | 本文件私有 static `splitOutsideQuotes`（旧实现逐字移入，仅此一处调用） |
| detailOutcome/sdetail:313/386/494 `HkParser.resolveUrl` | `HkParserKit.resolveUrl` |
| lastChapter:362/363 `HkParser.isJsRule` / `extract(Jsoup.parse…)` | `HkSelector.isJsRule` / `engine.getSelector().evalField(pageText, field, detailUrl)` |
| evalDetailRule:407/410 `HkParser.isJsRule` / `parseDetailList` | `HkSelector.isJsRule` / `engine.getSelector().parseDetail(...)` → `detailItemsToCards(List<HkDetailItem>)`（新增私有转换，只映射 title/url/pic/desc/colType） |
| playResolved:539 `HkParser.extract(Jsoup.parse…)`（选择器懒分支） | `engine.getSelector().evalField(pageText, lazy, pageUrl)` |
| evalLazy:666 `HkParser.extract(Jsoup.parse…)`（选择器懒分支） | `engine.getSelector().evalField(pageText, js, page)` |
| resolveMediaUrl:784 `HkParser.resolveUrl` | `HkParserKit.resolveUrl` |
| `import org.jsoup.Jsoup;` | 三处 Jsoup.parse 均已去掉，import 删除 |

HkLazyRuleParser 的承接说明：类本身按星辰原文搬入；其异步 `parse()` 入口
（Activity + 回调）在本阶段**无调用方**——与星辰快照一致（全树 grep 星辰
`setJsRuntime` 无调用处，其 JsRuntime 桥同样未接线）。HkExecutor 的同步
懒路径（playResolved/evalLazy）仍保留同步 `LazyOutcome/PlayOutcome` API，
但求值改走新解析器族（`HkSelector.evalField`——与 HkLazyRuleParser HTML
支经 HkCommonParser 做的字段求值同源）；`HkLazyRuleParser.parse` 的
JsRuntime 桥注入与异步点击链路留给阶段 5 路由（HkRouter/HkEngineBridge）时接线。

### 3.7 其余调用点

- HkSubpage:270 `HkParser.jsBody` → `HkParserKit.jsBody`。
- HkActionHandler:181 `HkParser.resolveUrl` → `HkParserKit.resolveUrl`。
- ui/hk/HkDetailFragment:24/274/276：import `HkParser` → `HkParserKit`，
  `HkParser.resolveUrl` → `HkParserKit.resolveUrl`，注释同步。

### 3.8 仅注释清理（任务书指定三处）

- HkCard.java:13：删“见 HkEngine.Bridge”（Bridge 类 3e 已删）。
- HkStore.java:227：删“（与 HkEngine.Bridge.md5 同款格式”。
- HkWarmup.java:25：`HkExecutor.fetch` 早已改走 `HkHttpClient.client()`
  （阶段 1），文档仍写 `OkHttp.client()` 已过期，改为 `HkHttpClient.client()`。

## 4. 语义变化（星辰行为为准，逐项列出）

### 4.1 命中诊断 mất 命中数

旧 `ParseOutcome.matched`（列表选择器命中元素数）无星辰对应物：
HkSelector.parseList 只返回卡片表。homeDetailed 的 0 条诊断改为只写
页面大小与卡片数（“页面约 XKB，得到 N 张卡片”），不再区分“未命中结构”
与“命中了但卡片不可用”。UI 文案口径变窄，数据本身不变。

### 4.2 保留条件：usable（标题且链接）→ 标题或链接

旧 parseHomeList/parseSearchList 只留 `usable()` 卡片（标题与链接双全）；
星辰 parseList/parseSearch 留标题或链接任一非空。同一页面下星辰可能多出
标题-only 或链接-only 的卡片（详情页的标题-only 头卡片在旧 parseDetailList
里本就保留，新 parseDetail 同款保留，详情路无变化）。

### 4.3 搜索 desc 回落：`detail` 段 `*` 不再取条目文字

旧 parseSearchList 对第 5 段 `*` 调 `extract` 得条目全文，desc 为空时回落；
星辰 parseSearch 里 `evalField("*")` 回 `""`（且 `desc + " " + detail` 的
拼接只在 detail 非空时发生）。只有 desc 段本身取空时才有差别（童话的
`.set&&Text` 正常非空，见 §5）。

### 4.4 详情 `*` 列表段不再把整页当一条

旧 parseDetailList 有特例：列表段是 `*` 且走详情路时整页 body 当一个条目
（为 ` *;*;body&&div@option;Text;value` 这类 chapter_find 样本服务）。
星辰 HkSelector.evalList 对 `*` 直接回空表，parseDetail 同款。影响面：
chapter_find/sdetail_find 以 ` *` 开头的规则（如小说/漫画类备选详情路）
在新版下回空表——旧版会得一张 body 衍生卡。属已知回归风险，阶段 5
HkDetail/HkRouter 落地时复核（星辰的详情路由不走本分支）。

### 4.5 详情标题/链接回落是星辰新增行为

星辰 parseDetail 在标题段取空时回落 `a&&Text` 再 `Text`，链接段取空时
回落 `a&&href`；旧 parseDetailList 无回落（空即空，卡片可能被丢弃）。
同页下星辰可能多出旧版丢弃的卡片。

### 4.6 lastChapter 字段求值口径

旧 `HkParser.extract(doc, field)` 与新 `HkSelector.evalField(pageText, field)`：
含 `&&` 的字段链两者同义（jsoup 选链 + 末段取值）；无 `&&` 的裸字段（如单个
属性名/标签名）在边界上可能分叉（旧按“有属性取属性、无属性当子标签”运行时
判断，新经 quickjs Parser.pdfh 的 getUrl 分支）。lastChapter 是装饰信息
（求值失败回 `""`，不炸详情页），且野外 last_chapter_rule 绝大多数是
`pdfh(…)` 式 `&&` 链，本笔接受该边界差。

## 5. 三条验收规则等价核对（代码级，不跑真机）

核对方法：对每条规则的 find_rule/searchFind/detail_find_rule 逐段比对
旧 HkParser 与新 HkSelector 的求值路径——旧 extract/extractOne/selectSegments
与新 evalField/evalCandidates→quickjs Parser.pdfh/pdfa（我方 quickjs
Parser.java 与星辰同文件 diff 仅文件头注释一行，见 4a §3.2，故新路 =
星辰行为）。JS 整段规则（`js:` 开头）不受解析器替换影响（仍走
HkJsRuntime.parseList/parseSearch/evalFindRule）。

### 5.1 奇优影院♔（selector 首页 + selector 搜索，详情 js:）

- find_rule `.stui-vodlist&&li;a&&title;.lazyload&&data-original;.pic-text&&Text;a&&href`：
  5 段。列表段 `.stui-vodlist&&li`：旧 selectSegments（`&&` 逐段 jsoup
  select）与新 pdfa（getTrueElement 链 + selectElements）同义。
  `a&&title`：旧 extractOne（末段 `title` 无选择器字符→有属性取属性）与新
  pdfh→getUrl（`title` 属 NORMAL_ATTRS→取属性）同值。
  `.lazyload&&data-original`：旧取属性后 `resolveUrl` 补全；新 pdfh→getUrl
  取属性，`data-original` 命中 joinUrl 的 `^(data-|…)` 模式→`UriUtil.resolve`
  补全；绝对/相对地址同结果。
  `.pic-text&&Text`：两路皆取元素文字。`a&&href`：两路皆取 href 并补全。
  结论：卡片数与四字段等价（保留条件放宽见 §4.2：该站每条 li 标题与链接
  双全，无实际增量）。
- searchFind `.stui-vodlist__media&&li;.title&&Text;a&&href;.pic-text&&Text;.detail&&Text;.lazyload&&data-original`：
  6 段，逐段与首页同款映射（标题 `.title&&Text` 取文字、链接 `a&&href`、
  desc `.pic-text&&Text`、detail `.detail&&Text`、图片
  `.lazyload&&data-original`）；旧“desc 空时回落 detail”与新
  “detail 非空时拼进 desc”在两段皆非空时同值（desc 取 `.pic-text`，
  detail 取 `.detail`，新 desc 为两者空格拼接——旧 desc 只取前者）：
  **此处有差**：新搜索 desc 会比旧版多拼一段 `.detail&&Text` 的文字
  （星辰行为；搜索卡片 desc 只做展示，不影响标题/链接/图片与点击）。
- detail_find_rule 是 `js:` 整段：不受本笔影响。

### 5.2 童话（selector 首页 + selector 搜索，详情 js:）

- find_rule `.plist&&li;a&&title;a&&img&&src;.set&&Text;a&&href`：
  `a&&img&&src`：旧 extractOne 三段链（a→img→取 src 属性补全）与新
  pdfh（getTrueElement a→img→getUrl `src` 补全）同值；其余段与 5.1 同款映射。
  结论：等价（保留条件放宽见 §4.2：该站每条 li 标题与链接双全，无实际增量）。
- searchFind `.plist&&li;a&&title;a&&href;.set&&Text;*;a&&img&&src`：
  第 5 段 `*`：旧 detail 取条目全文（仅 desc 为空时回落用），新 detail 为
  `""`（见 §4.3）；`.set&&Text` 正常非空时 desc 同值，差别仅在 desc 取空
  的边界。图片 `a&&img&&src` 同首页映射。
- detail_find_rule 是 `js:` 整段：不受本笔影响。

### 5.3 臻品fix（全 js:）

- find_rule / searchFind / detail_find_rule 全是 `js:` 整段，解析器替换
  不经过选择器分支；懒加载 `@lazyRule=.js:eval(...)` 走 JS 分支
  （engine.evalExpression），本笔只改了其周围的 `isJsRule` 委托
  （HkParser→HkSelector，谓词逐字相同）与 `resolveUrl` 落点
  （HkParser→HkParserKit，实现逐字移入）。结论：等价。

## 6. 验收证据（代码级，未构建）

- **逐行 diff**（`diff <(sed 包名还原 + 去文件头 我方文件) 星辰原文`）：
  HkHttpParser、HkLazyRuleParser、HkPlayData 均为
  `IDENTICAL-MODULO-PACKAGE-HEADER`（零差异）；util/HkCodeUtil 的 diff
- **旧 HkParser 引用清零**：删文件前全树 grep `HkParser[^Ki]`（排除
  HkParserKit）的**代码引用**零命中——调用点清单：HkRule:168、HkEngine:25/253/314/454、
  HkExecutor:103/118/140/143/230/313/350/362/363/386/407/410/494/539/666/784、
  HkSubpage:270、HkActionHandler:181、HkDetailFragment:24/274/276，
  逐一改走 HkSelector/HkParserKit（见 §3.6/§3.7 映射表）；仅剩两处说明移入
  来源的注释（HkExecutor:900、HkParserKit:319），无代码引用。
  HkJsRuntime 与 HkHikerRuleUtil 对 HkParser 的提及仅为注释里的 HkParserKit，不动。
- **包名/禁入残留**：全树 grep `com.fongmi.android.tv` 在 hk 源集内只剩
  各新文件头注释里的星辰原文路径（3e/4a/4b 同款写法），无代码引用；
  `LitePal|DexClassLoader|loadClass` 在新增四文件内零命中（`indexOf` 里的
  `dex` 子串除外，无动态加载）。
- **git diff --check** 通过（finish 前复核）。
- **改动文件列表与行数**：新增 HkHttpParser 859 + HkLazyRuleParser 327 +
  HkPlayData 96 + util/HkCodeUtil 395 = 1677 行；删 HkParser.java 548 行；
  改（`git diff --stat` 的 `+-` 行数）：HkJsRuntime 10（+newSelector）、
  HkEngine 46、HkRule 15、HkExecutor 122、HkSubpage 2、HkActionHandler 2、
  HkDetailFragment 6、HkParserKit 35（+TextUtils import 与适配段）、
  HkCard/HkStore/HkWarmup 各 2（注释）。
- **HkSelector/HkHttpParser 两个 ★ 类在位**：`HkJsRuntime:93` 的
  `implements HkSelector.JsEvaluator`、`:2411` 的 `HkPageParser.getPageList`、
  `:3709/:6488` 的 `HkHttpParser.getParamsByUrl`、`:6489` 的
  `HkParserKit.clearTag/replaceOnce/splitUrlByQuestionMark` 引用全部闭合
  （4a/4b/4c 三笔补齐；grep 计数：阶段 4 前上述符号 0 定义，阶段 4 后
  6 类 + 3 依赖类全部在位）。
- **HkParserKit.getHomeUrl 在位可用**：`HkParserKit.java:162`（4a 已搬，4c
  加 TextUtils import 后行号由 159 移到 162），
  HkHikerRuleUtil historyJson 依赖的该方法就绪；historyJson 本体仍是
  阶段 5/7 的事（该文件 history 支仍回 `"[]"`，不动）。

## 7. 遗留（阶段 4 结束后仍缺什么）

- **阶段 5 的类，一个没提前搬**：HkFindDelivery（HkJsRuntime:473 等 8 处
  引用）、HkSearchEngine（HkJsRuntime:11102 等 3 处引用）、HkRouter、
  HkDetail/HkDetailItem（注：HkDetailItem 已在 4a 搬入，HkDetail 本体是
  阶段 5）、HkPlay/HkPlayData（注：HkPlayData 已在 4c 作为 HkHttpParser
  依赖搬入，HkPlay 本体是阶段 5）、选集四件套、HkLoading、HkErrorUtil、
  HkWebkitFetcher、ui/hk 列表/搜索/详情三页替换。
  **因此中间态仍编不过**——阶段 4 只补齐了解析器，HkJsRuntime 对
  HkFindDelivery/HkSearchEngine 的引用仍悬空，与阶段 3 结束时同款缺口。
- **已知语义差**（§4 全列）：4.1 诊断丢命中数、4.2 保留条件放宽、
  4.3 搜索 desc `*` 回落丢失、4.4 详情 `*` 列表段回归风险（chapter_find）、
  4.5 详情标题/链接回落新增、4.6 lastChapter 裸字段边界、5.1 奇优搜索
  desc 多拼一段。以上皆以星辰行为为准，未做补偿；4.4 在阶段 5 路由
  落地时复核。
- **HkLazyRuleParser.parse 的 JsRuntime 桥未注入**（§3.6）：星辰快照
  同款未接线，阶段 5 接线。
- **HkCommonParser.setJsEval 未注入**（4b §3）：同款，阶段 5 定注入方。
- **HkWarmup 去留**：计划 §1.7 定移植后删除（官方没有 warmUp），用户已接受
  其存在；阶段 4 未动它（只改了一行过期 doc 注释），去留由用户在阶段 7 定。
