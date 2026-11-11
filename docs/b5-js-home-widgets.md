# B5 首页自定义 JS 组件区 — 设计文档

任务来源：用户 2026-11-11 07:55 说“开始b5”。B5 是 B 档里最重的一项（1–2 周功能级工程）。
参考：`~/workspace/ui-ref/analysis.md`（B5 在第 4 节；BieCuiLe 组件在第 2.1 节）、参考 APK 解包 `~/workspace/ui-ref/extracted/`（`assets/flutter_assets/assets/widgets/*.js`、`assets/widget_host.js`、`assets/flutter_assets/assets/widget_samples/samples.json`）。
基线：main `4598c4a8`（B1–B4 之后 + 横幅 ViewPager2 闪退修复，已推到线上 main，本地与线上一致）。
约束：只在本地改、本地提交，**不推 GitHub**；不编测试版；每完成一个逻辑单元走一次 task_guard finish（一个 guard 会话 = 一次原子提交 + recovery tag）；保留墨影视已有改动（品牌/包名 com.github.mooyingshi.osc、液态玻璃、短剧、Archive.org 爬虫、本地源、默认搜索本站、底栏 6 项）；底栏上限 6 项；本机无 Android SDK，编译检查靠 GitHub 构建。

## Recovery anchor

- 目标：首页自定义 JS 组件区（仿 BieCuiLe 的 Bangumi/豆瓣/Mikan/TMDB 小组件）：JS 沙箱跑组件脚本 + 首页分区渲染 + 缓存 + 内置四组件 + 设置开关（默认关）。
- 当前阶段：设计文档已写（本文件）；实现未开始。
- 分支/HEAD：main / 4598c4a8。
- 已完成：BieCuiLe 4 个组件脚本 + widget_host.js 已读完（见 §1）；四个内置组件的公开 API 已用 curl 验过（见 §6）；设计文档已定（本文件）。
- 未验证：全部实现（无本地 SDK，编译靠 GitHub 构建；每项只做静态核对 + Node/curl 验公开 API）。
- 风险：task_guard 是单会话状态（`.codex/task-state/current`），不可并行跑多个 guard 会话——B5 各单元必须串行做、串行提交。
- 下一步：按 §7 的单元顺序实现，先做单元 1（引擎核心）。

---

## 1. 参考：BieCuiLe 的 CapyPlayer 组件规范（实证）

来源：解包目录 `assets/flutter_assets/assets/widgets/` 下 4 个内置组件 + `assets/widget_host.js`（宿主 API shim）。以下全部是读源码得到的事实，不是推测。

### 1.1 组件脚本文件格式

一个组件就是一个普通 JS 脚本文件（非 ES module，无 import/export），顶层定义：

- 全局 `WidgetMetadata` 对象：
  ```js
  var WidgetMetadata = {
    id: 'builtin.bangumi',          // 唯一 id
    title: 'Bangumi 番组计划',
    description: '每日放送、季度新番、排行榜与标签索引',
    author: 'BieCuiLe',
    site: 'https://bgm.tv',
    version: '1.0.0',
    globalParams: [ ... ],          // 可选：组件级全局参数（如 Mikan 的站点地址）
    search: { title: '搜索番剧', functionName: 'mikanSearch', params: [] },  // 可选：组件内搜索
    modules: [ ... ]                // 模块列表
  };
  ```
- 模块（module）：
  ```js
  {
    title: '每日放送',
    description: '当季动画按星期的放送表',
    functionName: 'bgmCalendar',    // 宿主按名调用的 async 函数
    cacheDuration: 3600,            // 缓存秒数
    params: [ ... ]                 // 参数定义
  }
  ```
  分类模块多一个 `type: 'category'`，其函数返回分类列表 `[{id, name, params}]`，分类的 `params` 会拼进列表模块的参数里。
- 参数（param）：
  - `type: 'enumeration'`：`enumOptions: [{title, value}]`，可带 `belongTo: {paramName, value: [...]}`（条件显示）。
  - `type: 'string'`：`description`、`placeholders: [{title, value}]`、`required`。
  - `type: 'page'`：页码（宿主翻页时递增）。
  - `type: 'constant'`：隐藏固定值（如豆瓣榜单的 collection 名）。
- 模块函数：`async function bgmCalendar(params)`，`params` 是 `{名: 值}` 的对象；返回条目数组（见 §1.2）。另有可选 `loadDetail(link)`（Mikan 用：点开条目后取详情/选集，返回含 seasons/episodes 的详情对象）。

### 1.2 条目数据结构（函数返回值）

```js
{
  id: '123',                    // 字符串，组件内唯一
  type: 'bangumi' | 'douban' | 'tmdb' | 'link',
  title: '片名',
  originalTitle: '原名',        // 可选
  mediaType: 'movie' | 'tv',
  posterUrl: 'https://...',     // 或 posterPath（TMDB 相对路径）
  backdropPath: '/...',         // 可选
  rating: 8.2,                  // 可选
  releaseDate: '2021-01-01',    // 可选
  description: '简介',          // 可选
  genreTitle: '标签1 标签2',    // 可选
  remark: '12 话 · #1',         // 可选，角标
  customHeaders: { Referer: ..., 'User-Agent': ... },  // 可选：图片加载时带的请求头（豆瓣用）
  link: 'mikan:123',            // type=link 时用，点开走 loadDetail
  // 其余字段原样透传（如 doubanId / bangumiId / tmdbId）
}
```

### 1.3 宿主 API（widget_host.js 提供的 `Widget` 全局对象）

| API | 说明 |
|---|---|
| `Widget.http.get(url, {headers, params, timeout})` | 返回 `{ok, status, statusCode, data, headers}`；`data` 是自动解析的 JSON（像 JSON 就 parse，否则原文本）；`timeout` 单位毫秒（小值按秒算）；`params` 拼进 URL query；`body` 对象按 Content-Type 转 JSON 或 form。 |
| `Widget.http.post(url, body, opts)` / `put` / `delete` / `request(opts)` | 同上。 |
| `Widget.tmdb.get(path, {params})` | 走宿主的 TMDB 通道，API Key 在宿主侧，**不进组件脚本**；非 2xx 抛错。 |
| `Widget.storage.get(key[, def])` / `set(key, value)` / `remove(key)` / `clear()` | 按组件 id 分命名空间的持久存储（Mikan 用来记站点地址）。 |
| `Widget.dom.parse(html)` / `select(target, selector)` / `selectFirst` / `text(node)` / `html(node)` / `attr(node, name)` / `remove(id)` | DOM 解析（基于浏览器 DOMParser；4 个内置组件实际没用它，Mikan 用正则）。 |
| `fetch(input, init)` | 套在 `Widget.http` 上的 fetch 兼容层（`ok/status/headers.get/json()/text()`）。 |
| `console.log/info/debug/warn/error` | 转发给宿主日志。 |

宿主加载流程（`__load` / `__invoke`）：宿主 eval 组件源码 → 读全局 `WidgetMetadata`（缺失则报错）→ 按 `functionName` 查函数（名字必须匹配 `^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$`，否则拒绝）→ 调函数传 JSON 参数 → 结果经 `safeJson`（去函数/undefined/循环引用）回传；出错走 `Native.result(id, '', message)`。

### 1.4 四个内置组件（数据来源与要点）

| 组件 | 数据源 | 要点 |
|---|---|---|
| Bangumi | `https://api.bgm.tv` 公开接口，免登录 | `POST /v0/search/subjects`（浏览/季度/排行）、`GET /calendar`（每日放送）；条目只有资料，点开后在当前资源里搜片名。 |
| 豆瓣 | `https://m.douban.com/rexxar/api/v2` 移动版公开接口，免登录 | 需 Referer + 浏览器 UA 请求头；图片要带 `customHeaders`（Referer: movie.douban.com）否则被拒；榜单/分类/豆列。 |
| Mikan | `https://mikanani.kas.pub`（镜像，默认）/ `https://mikanani.me`（主站），HTML 抓取 | 季度新番/每日放送/最近更新；条目是 `type: 'link'`，点开走 `loadDetail` 列各字幕组磁力（字幕组=线路）；墨影视没有磁力引擎，点开改为在当前资源里搜片名（见 §5）。 |
| TMDB | 宿主 `Widget.tmdb`（设置里填的 API Key，Key 不进脚本） | 探索发现/趋势/播出平台/出品公司/系列电影；401 时提示去设置里检查 Key。 |

### 1.5 widget_samples

`assets/flutter_assets/assets/widget_samples/samples.json` 是 8 条示例数据（title/year/rating/overview/poster/backdrop），只用于组件 UI 预览，不参与数据逻辑。墨影视不需要它。

## 2. 决策：墨影视怎么做（CapyPlayer 兼容 + 原生适配）

### 2.1 总体方案

**复用现有 QuickJS 引擎，不另起 WebView。** 墨影视已有 QuickJS（`quickjs/` 模块 + `com.whl.quickjs.wrapper.QuickJSContext`，app 模块直接依赖 wrapper，见 app/build.gradle:172-173），drpy 蜘蛛就是这么跑的。B5 在 app 模块新建一套 Widget 引擎：

- `WidgetHost`（Java）：建 QuickJSContext → 挂 console → 注入 `Widget` 宿主 API（JS shim + Java 桥）→ 加载组件脚本 → 读 `WidgetMetadata` → 按名调模块函数 → 结果转 JSON。
- 组件脚本格式与 CapyPlayer 完全兼容（`WidgetMetadata` + async 函数 + 同样的条目结构），BieCuiLe 的 4 个内置组件脚本改少量适配就能直接用（主要是 `Widget.*` API 名字保持一致，`loadDetail` 的磁力语义改掉）。
- 与 drpy 引擎并行：drpy 跑的是“源”（home/category/detail/search/play），Widget 跑的是“首页组件”（metadata/modules/invoke）；两者共用 QuickJS 运行时模式，但各建各的 Context、各走各的宿主 API，互不干扰。

**不采用的方案**：WebView 宿主（BieCuiLe 的做法）——墨影视是原生 App，为 4 个组件专门起 WebView 宿主太重，且 QuickJS 已有现成运行时与 drpy 实战验证；`Widget.dom` 用 jsoup 实现（quickjs 模块已依赖 jsoup 1.22.2），不依赖浏览器 DOMParser。

### 2.2 组件文件格式（墨影视版）

与 §1.1 完全一致（CapyPlayer 兼容），另加两条墨影视约定：

- 组件文件是普通 JS 脚本（非 ES module），顶层 `var WidgetMetadata` + 顶层 `async function`。不支持 `import`（组件要自包含；如需共用逻辑，用宿主提供的 `Widget.*` API）。
- 首页默认模块：每个内置组件指定一个 `homeModule`（metadata 里加 `homeModule: '函数名'` 字段，或默认取 modules[0] 中非 category 的第一个）。首页只渲染各组件的 homeModule 一行；点“更多”进浏览页才能换模块/改参数。
- Mikan 的 `loadDetail` 不采用：墨影视没有磁力引擎，Mikan 条目点开改为在当前资源里搜片名（与 Bangumi/豆瓣一致）。

### 2.3 沙箱 API（`Widget` 全局对象）

Java 桥（`WidgetBridge`，`@JSMethod` 方法，挂在 QuickJS 全局对象上）+ 一层薄 JS shim（`assets/js/lib/widget_host.js`，复用 BieCuiLe widget_host.js 的结构与语义，改掉 `window`/`Native` 依赖，改走 Java 桥）。

| API | Java 实现 | 说明 |
|---|---|---|
| `Widget.http.get/post/put/delete/request` | `WidgetBridge.http(method, url, headersJson, body, timeoutMs)`：OkHttp 同步请求（复用 `Connect.to` 的模式，`com.github.catvod.net.OkHttp`），返回 JSON 串 `{status, headers, body}`；shim 负责拼 query、转 body、parse JSON、算 `ok`。 | 与 drpy 的 `req` 同样的同步阻塞模式（JS 单线程执行器上跑，UI 侧永远在后台线程调 WidgetHost）。超时默认 20 秒；单次调用超时不抛到宿主，shim 返回 `{ok:false, status:0}` 由组件脚本自己决定。 |
| `Widget.tmdb.get(path, {params})` | `WidgetBridge.tmdb(path, paramsJson)`：调 `TmdbService` 新增的通用 `get(path, params, config)`（见 §2.4），API Key 在宿主侧，**绝不进脚本**；非 2xx 时 Java 侧抛错，shim 转成 JS Error（401 时提示去设置里检查 Key）。 | TMDB 未配置（`TmdbConfig.objectFrom(Setting.getTmdbConfig()).isReady()` 为 false）时直接抛错，组件行隐藏。 |
| `Widget.storage.get/set/remove/clear` | `WidgetBridge.storageGet/Set/Remove/Clear(prefix, key, value)`：`Prefers`（SharedPreferences），key 前缀 `widget_<组件id>_`（与 drpy 的 `local` 隔离）。 | 只存字符串/JSON；不存大对象。 |
| `Widget.dom.parse/select/selectFirst/text/html/attr/remove` | `WidgetBridge.domParse/domSelect/domText/domHtml/domAttr/domRemove`：jsoup 实现（quickjs 模块已有 jsoup 1.22.2）。 | 4 个内置组件不用 dom，但规范里有，第三方组件可能用；先实现基础版。 |
| `fetch(input, init)` | shim 套 `Widget.http`（照搬 BieCuiLe 的 fetch 兼容层）。 | — |
| `console.*` | 复用 quickjs 的 `Console`（`ctx.setConsole(new Console())`，日志进 Logcat）。 | — |

**沙箱收紧（必须）**：
- 脚本只能碰到 `Widget` + 标准 JS 内置对象；不暴露 `local`（drpy 的本地文件桥）、不暴露任何 Android 类、不暴露文件系统、不暴露账号/配置（Setting、TmdbConfig、VodConfig 都不进脚本）。
- HTTP 只走桥：脚本自己发不了请求（没有 fetch 原生实现、没有 XMLHttpRequest），所有网络都过 `WidgetBridge.http`（可控超时、可控请求头、不可读本地文件）。
- TMDB Key 不进脚本（见上）。
- 函数名白名单：`__invoke` 只接受 `^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$`（照搬 BieCuiLe 的 safePath），非法名字直接拒绝。
- 超时与异常隔离：单个组件加载/调用超时（默认 20 秒）或抛错，只隐藏/跳过该组件行，绝不影响首页其它内容；错误写 Logcat（`SpiderDebug.log("widget", ...)`）。

### 2.4 TmdbService 通用 GET（Widget.tmdb 用）

现状：`TmdbService` 只有 typed 方法（search/detail/season/episode/person…），没有“任意 path GET”。新增一个 public 方法（放在 `TmdbService.java`）：

```java
public JsonObject get(@NonNull String path, Map<String, String> params, @NonNull TmdbConfig config) throws Exception
```

实现：`ensureReady(config)` → `apiBuilder(config.getApiBase() + path, config)` → 逐个 `addQueryParameter`（params）→ `requestJson(url, config, "widget", cacheKey, 3600_000L, ...)`（复用现有 requestJson 的缓存与 401 熔断；cacheKey 由 path + 排序后的 params 拼）。path 必须以 `/` 开头且不含 `..`（防路径穿越到非 TMDB 域——apiBuilder 的 base 是固定的官方地址，path 只能拼在后面）。

### 2.5 缓存策略

- 粒度：每个模块调用一次 = 一条缓存；key = `widgetId + '|' + functionName + '|' + 稳定序列化(params)`（params 按 key 排序后 JSON，`page` 参与 key）。
- TTL：用模块的 `cacheDuration`（秒）；没写 `cacheDuration` 默认 3600 秒；`cacheDuration: 0` 表示不缓存。
- 存储：App 缓存目录下 `widget-cache/`，每条一个 JSON 文件 `{savedAt, data}`（data 是模块返回的条目数组 JSON）；读缓存前先判 `savedAt + TTL > now`。
- 失效：组件脚本更新（内置组件随 App 版本走，版本号变则清对应组件的缓存）、用户在浏览页点刷新、TTL 到期。
- 首页加载时：先读缓存渲染（有缓存立刻显示），再后台刷新（刷新回来有变化再更新行；刷新失败保留缓存、无缓存则隐藏该行）。

### 2.6 首页分区渲染

位置：`fragment_vod.xml` 的 AppBarLayout 内，`continueSection` 之后、`typeBar` 之前，加一个 `widgetSections` 垂直容器（默认 GONE）。与 B1/B2 一致：随 AppBar 折叠、原生内容隐藏时（网页首页/沉浸模式）一起隐藏。

每组件一行（section）：
- 标题行：组件名 + 模块名（如“Bangumi 番组计划 · 每日放送”）+ 右侧“更多”（点进浏览页）。
- 横向 RecyclerView（复用 B2 的横滑行模式 + 现有海报卡片样式 `adapter_vod_rect` 的数据映射——条目先转成 `Vod` 对象：`id→vodId`、`title→name`、`posterUrl/posterPath→pic`、`remark→remarks`、`rating/releaseDate→角标`，再交给现有卡片渲染，图片用 `ImgUtil`；豆瓣条目的 `customHeaders` 在图片加载时带上）。
- 点条目：**在当前资源里搜片名**（`SearchActivity.start(activity, title)`，与 B1 横幅的索引站行为一致；当前站是索引站时同样走搜索）。不直接播（组件条目只有资料，没有播放地址）。
- 点“更多”：进 `WidgetBrowseActivity`（见 §2.7）。
- 行的可见判据：开关开 + 该组件 homeModule 有数据（缓存或 fresh）；无数据/失败/超时 → 隐藏该行；全部行都无 → 隐藏整个 `widgetSections` 容器。

加载时机：与 `setAdapter(Result)` 同步——首页数据回来后，若开关开，`WidgetManager` 在后台线程逐个调各组件的 homeModule（先缓存后刷新），回来后主线程更新对应行。`onResume` / `RefreshEvent` 时不重复刷（除非 TTL 到期或用户在浏览页点了刷新）。

### 2.7 浏览页（WidgetBrowseActivity）

点某组件行的“更多”进浏览页（新 Activity，手机端；TV 端暂不做，见 §8）：

- 顶部：组件标题 + 模块选择器（横向 chip，列该组件全部非 category 模块；category 模块渲染成二级分类行）。
- 参数区：按选中模块的 `params` 渲染控件——`enumeration` 用横向 chip 单选、`string` 用输入框（带 placeholders 下拉建议）、`page` 不渲染（翻页按钮控制）；`belongTo` 条件显示；`constant` 不渲染。
- 内容区：网格 RecyclerView（复用现有 `adapter_vod_rect` 卡片），上拉加载更多（`page` 参数递增）；点条目同样走“当前资源搜片名”。
- 刷新按钮（清该模块缓存重取）。

### 2.8 内置四组件（assets）

放在 `app/src/main/assets/widgets/`（新建目录）：`bangumi.js`、`douban.js`、`mikan.js`、`tmdb.js`，以 BieCuiLe 的 4 个脚本为底本改写：

- 保留 `WidgetMetadata`（id 保持 `builtin.*`，author 改回 BieCuiLe——注明来源）、modules/params/函数逻辑。
- 改动点：Mikan 的 `loadDetail` 删掉（墨影视无磁力引擎；条目点开走搜片名）；`Widget.storage` 的 key 命名空间不变；TMDB 组件的 `Widget.tmdb` 走墨影视桥（语义一致）。
- 各组件加 `homeModule`：Bangumi=`bgmCalendar`（每日放送）、豆瓣=`doubanHotMovies`（近期热门电影）、Mikan=`mikanDaily`（每日放送）、TMDB=`tmdbTrending`（趋势，周榜）。
- 版本：`version: '1.0.0'`，随 App 发版走；脚本更新时清对应缓存。

### 2.9 设置开关（默认关）

走“增强设置里加开关、默认关”的老路子（与“默认搜索本站”开关同款）：

- `Setting.java` 加 `isHomeWidget()` / `putHomeWidget(boolean)`（Prefers key `home_widget`，默认 false）。
- 设置 → 增强设置加一行“首页组件区”（副标题说明：首页显示 Bangumi/豆瓣/Mikan/TMDB 推荐行）。
- 开关关 → 首页不加载任何组件（`widgetSections` 保持 GONE，不建 WidgetHost、不读缓存）；开关开 → 按 §2.6 加载。
- strings 加三端（mobile/leanback 只有 mobile 用，leanback 不做浏览页但开关文案仍加 mobile 一套即可——leanback 首页结构不同，本期不做，开关只影响手机端；文案放 `app/src/main/res/values/strings.xml` 主串 + mobile 串，按现有开关的落点走）。

### 2.10 失败与回退

- 组件脚本加载失败（无 WidgetMetadata / 语法错）：该组件不显示，Logcat 记错，首页其它内容不受影响。
- 模块调用失败/超时/返回空：该行隐藏（首页）或浏览页显示空态 + 重试按钮。
- 网络异常：同上，不弹全局 Toast（首页静默失败；浏览页内给重试）。
- TMDB 未配置：TMDB 组件行隐藏（首页不报错）；浏览页进 TMDB 模块时提示去设置里填 Key。
- 开关默认关：即使用户升级 App，首页行为与以前一模一样（零回归）。

## 3. 与现有 drpy 引擎的关系（并行，不混用）

| | drpy 蜘蛛（现有） | Widget 组件（新增） |
|---|---|---|
| 脚本格式 | ES module（`export default {home, homeVod, category, detail, search, play}`） | 普通脚本（`var WidgetMetadata` + 顶层 async 函数） |
| 入口 | `JsLoader` → `quickjs Spider`（`com.fongmi.quickjs.crawler.Spider`） | 新增 `WidgetHost`（app 模块，`com.fongmi.android.tv.widget`） |
| JS 宿主 API | `local`、`req`/`_http`、`pd`/`pdfh`、drpy2-fast 引擎 | `Widget.http/tmdb/storage/dom`、`fetch`、`console` |
| 数据来源 | 站点（py/js/json） | 公开 API（Bangumi/豆瓣/Mikan/TMDB） |
| 渲染 | 点播页/分类页/详情页/搜索页/播放器 | 首页分区行 + 浏览页 |
| 缓存 | loader 缓存（JsLoader/PyLoader） | §2.5 的 widget-cache 文件缓存 |

两者共用 QuickJS 运行时模式（`QuickJSContext` + 单线程执行器 + `Async.run`），但 Context 与宿主 API 各自独立，互不干扰。Widget 不复用 `Spider` 类（那是给“源”用的，`home/category/detail/search/play` 语义不对）。

## 4. 安全（沙箱收紧）

- 脚本只能访问 `Widget` + JS 内置对象；`local`、Android 类、文件系统、Setting/VodConfig/TmdbConfig 均不暴露。
- 网络只走 `WidgetBridge.http`（OkHttp，超时可控）；脚本无法自发请求、无 XHR、无 WebSocket。
- TMDB Key 不进脚本（宿主侧注入）。
- `Widget.storage` 仅限 `widget_<id>_` 前缀的 Prefers，不碰其它配置。
- 函数名白名单（safePath 正则），非法名字拒绝调用。
- 单个组件超时 20 秒、失败只隐藏该行，不影响首页；错误只写 Logcat，不弹全局提示。
- 内置组件脚本随 App 发版（assets），不从网络下载执行；后续如支持用户自加组件文件，需另立评审（文件来源、签名/校验、沙箱复核），本期不做。

## 5. 与 BieCuiLe 的差异（诚实记录）

- BieCuiLe 的组件点开后：Bangumi/豆瓣条目在当前资源里搜片名；Mikan 条目走 `loadDetail` 列字幕组磁力直接播（它有磁力引擎）。墨影视无磁力引擎，**Mikan 条目点开一律改为搜片名**（与 Bangumi/豆瓣一致），磁力播放不在本期范围。
- BieCuiLe 的组件有 `search`（组件内搜索）和 `globalParams`（Mikan 站点地址可换）；墨影视 v1 只做 homeModule 行 + 浏览页模块/参数，组件内搜索与全局参数设置不在 v1（Mikan 站点地址固定用镜像 `mikanani.kas.pub`，主站备用写进脚本常量，不暴露设置项；后续可加）。
- BieCuiLe 的 widget 行在“热门推荐”之下；墨影视放在 AppBar 内 continue 行之后、分类 chip 之前（与 B1/B2 同一折叠区）。

## 6. 证据：公开 API 实测（2026-11-11 上午，curl）

- Bangumi `GET /calendar`：200。`GET /v0/subjects/1`：200。
- Bangumi `POST /v0/search/subjects`：本机 VM 上 25 秒超时、0 字节返回（重试两次一致；GET 同域名正常）。原因未定（可能是本机出口代理对该 POST 路径的处理，也可能是接口侧限流）；**组件必须容忍该接口超时**（shim 超时 20 秒 → 行隐藏/重试），真机验证待 GitHub 构建后用户实测。
- 豆瓣 `GET /movie/recommend`（带 Referer + 浏览器 UA）：200。
- Mikan 镜像 `https://mikanani.kas.pub/`：200；主站 `https://mikanani.me/`：200。
- TMDB：需用户在设置里填的 API Key，本机无 Key 可测；走现有 TmdbService 通道（已有缓存/熔断/401 处理）。

## 7. 实施计划（单元 + 文件 + 提交）

按顺序串行做，每单元一个 guard 会话、一次 finish 提交。

| 单元 | 内容 | 文件（新建/改动） | 验收 |
|---|---|---|---|
| 0 | 设计文档（本文件） | 新建 `docs/b5-js-home-widgets.md` | 文档完整，含 Recovery anchor |
| 1 | 引擎核心：WidgetBridge（Java 桥：http/tmdb/storage/dom）+ WidgetHost（Context/加载/metadata/invoke）+ widget_host.js shim + WidgetMetadata/WidgetItem/WidgetModule 数据类 + WidgetCache（文件缓存） | 新建 `app/src/main/java/com/fongmi/android/tv/widget/` 下：`WidgetHost.java`、`WidgetBridge.java`、`WidgetCache.java`、`WidgetMetadata.java`（或 bean 包）、`WidgetItem.java`；新建 `app/src/main/assets/js/lib/widget_host.js`；`TmdbService.java` 加通用 `get(path, params, config)` | Node 桩验 shim 逻辑（见 §8）；静态核对：桥方法签名与 shim 调用一致、无 Android 类进脚本、超时与异常隔离 |
| 2 | 内置四组件 assets | 新建 `app/src/main/assets/widgets/{bangumi,douban,mikan,tmdb}.js`（以 BieCuiLe 脚本为底本改写，见 §2.8） | Node 桩跑四脚本的 metadata 解析 + 各 homeModule 函数的纯逻辑（网络部分用桩）；curl 验公开 API（§6 已做，构建后真机复验） |
| 3 | 首页分区渲染：VodFragment 接 WidgetManager + fragment_vod.xml 加 widgetSections 容器 + WidgetSectionAdapter（横滑行，复用海报卡片）+ 条目转 Vod + 点条目搜片名 | 改 `VodFragment.java`、`fragment_vod.xml`；新建 `WidgetManager.java`（加载/缓存/刷新编排）、`WidgetSectionAdapter.java`、`adapter_widget_section.xml` | 静态核对：开关关时零行为变化；行隐藏逻辑与 B1/B2 一致；点条目走 SearchActivity |
| 4 | 浏览页：WidgetBrowseActivity（模块 chip + 参数控件 + 分页网格 + 刷新） | 新建 `WidgetBrowseActivity.java` + `activity_widget_browse.xml` + 布局；`AndroidManifest.xml` 注册 | 静态核对：参数 belongTo/constant/page 处理与脚本一致；翻页递增 page |
| 5 | 设置开关（默认关）+ strings | 改 `Setting.java`（`isHomeWidget`/`putHomeWidget`）、增强设置布局与 Fragment、3 端 strings | 静态核对：默认关；开关关时首页不建 WidgetHost |
| 6 | 收尾：tracker 更新（b-ui-b1-b7-port.md 的 B5 行）+ 全量静态核对（diff --check、类型、底栏 6 项、硬限制）+ 汇报 | 改 `docs/b-ui-b1-b7-port.md` | 汇报见 §9 |

## 8. 验证计划

- curl 验公开 API：§6 已做（Bangumi POST 超时待真机复验）。
- Node 桩：用 Node 跑 widget_host.js shim + 四组件脚本的 metadata 解析与 homeModule 纯逻辑（网络调用用桩返回录制的 JSON/HTML 片段），验：metadata 结构、参数默认值、条目映射（poster/rating/remark）、Mikan 正则解析、TMDB 401 提示文案。
- 静态核对（每单元提交前）：`git diff --check`、括号平衡、改动行重读（返回值类型：`Json.safeListElement` 返回 List 不能赋给 JsonArray 之类；`WidgetBridge` 返回给 JS 的必须是 JSObject/String，不能把 Java 对象直接丢给 JS）、底栏 6 项、开关默认关、沙箱收紧点逐项对 §4。
- 编译：本机无 SDK，靠 GitHub 构建（用户新规矩：有更新直接编；但 B5 代码未推，构建待用户说“推”后再触发）。

## 9. 汇报清单（做完后交回主对话）

设计文档路径、实现了什么、改了哪些文件、commit 号、内置组件实测情况（Node 桩 + curl，哪些验过、哪些待真机）、没做完/有风险的地方（Bangumi POST 超时、TMDB 需用户 Key、dom 基础版、Mikan 无磁力改为搜片名）、建议用户先测什么。

## 10. 备选方案（为什么不选）

- **不做**：用户已说“开始b5”，不做不是选项；但若实现中发现 QuickJS 无法稳定跑 widget 脚本，回退到“不做”并汇报。
- **WebView 宿主（照搬 BieCuiLe）**：为 4 个组件起 WebView 太重，且 QuickJS 已有 drpy 实战验证；否决。
- **复用 drpy 蜘蛛 API（把组件写成 .js 源）**：语义不对——组件是“首页推荐行”，不是“源”；且 drpy 的 `home/category/detail/search/play` 与组件的 `metadata/modules` 结构不同；否决。
- **组件数据走服务端代理**：增加服务端依赖与隐私面；公开 API 直连已够用；否决。
