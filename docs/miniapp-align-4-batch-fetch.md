# 对齐第 4 项：batchFetch 改 {url, options} 嵌套 + 并发

> Recovery anchor：目标是把 `HkEngine.Bridge.batchFetch` 按官方改成 `{url, options}`
> 嵌套入参 + `min(n,16)` 并发 + 官方失败语义；options 键真正生效。只改
> `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkEngine.java`（+
> 本文档）。本地提交、不推、不编、不动版本记录。验收：① 草莓 preRule 调用链与
> 官方一致 ② 并发上限 `min(n,16)` ③ 单项失败返回值与官方一致 ④ 3 条验收规则
> 零回归（均不用 batchFetch）。下一步：写代码 → 逐行重读 → guard finish 提交 + tag。

## 0. 完成句

batchFetch 按官方改成 `{url, options}` 嵌套入参 + `min(n,16)` 并发 +
官方失败语义（单项异常 → `""`、整体超时未完成 → `null`），options 键
（headers/method/body/timeout/withHeaders/withStatusCode/redirect/onlyHeaders）
真正生效；草莓 preRule 调用链通，3 条验收规则零回归。

- 允许路径：`docs/miniapp-align-4-batch-fetch.md`、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkEngine.java`
- 保护路径：HEAD 已含第 1/2/3 项（31fd3c80 等），一律不动；TV/leanback、
  BaseLoader、底栏（menu_nav.xml 6 项）不动。
- 当前时间：2026-11-15 23:04 CST；预计 45 分钟，约 23:50 完成。
- 最廉价决定性验证：本地无 JDK/Android SDK，编不了；以逐行重读 diff +
  静态核查（返回类型/泛型/三元类型一致）为准，编译检查等推上去后的 GitHub 构建。

## 1. 证据（先读透的权威依据）

### 1.1 官方 batchFetch（`~/workspace/hk-ref/src/sources/.../HkJsRuntime.java`）

- 入口 `lambda$registerApi$78`（1053–1160 行）：
  - 无参/空参 → 空数组；首参 NativeArray → `jsStringify`，否则 `argStr`；
    字符串不以 `[` 开头 → 当 `"[]"` 处理；Gson 解析成 `List<Object>`，
    null/空 → 空数组。
  - 线程池 `min(size, 16)`；第二参 `threadNum` 可覆盖：
    `max(1, min(size, (int) toDouble(arg[1])))`。
  - 每项：是 Map → `url = String.valueOf(map.get("url"))`，
    `options = GSON.toJson(map.get("options"))`（无 options 则 null）；
    非 Map → `url = String.valueOf(item)`，options 为 null。
  - `CountDownLatch.await((size / 16) * 10 + 10, SECONDS)`（**整除**，
    如 n=3 → 10s，n=510 → 320s）；随后按下标从 ConcurrentHashMap 组装，
    未填的槽位 `map.get(i)` 得 null → 数组该位就是 null；`shutdownNow()`；
    外层任何 Throwable → 记日志 + 返回空数组。
- 工作线程 `lambda$batchFetchImpl$213`（4704–4732 行）：
  - 每线程 `enterAmbientScope`（Rhino Context 进线程），`hiker://`/`file://`
    走 `fetchByHiker(url, null, optFlag(options,"toHex"), optFlag(options,"inputStream"))`，
    否则 `fetchSync(url, optionsJson)`；
  - 返回是 String → 原样入位；非 String（流）→ 关流 + 该位 `""`；
  - **catch Throwable → 该位 `""`**。
  - finally 里 `countDownLatch.countDown()`。
- 结果组装 `toJsArrayObj`（25309–25319 行）：`nativeArray.put(i, nativeArray, list.get(i))`，
  null 元素原样进位 → JS 侧读到 `null`。

**纠正 ANALYSIS.md §6.1 的一处不精确说法**：报告写“单项失败 → 该位 null”，
实际官方代码是两档：单项抓取抛异常 → `""`；只有整体 `await` 超时、
任务没来得及入位 → 该位 `null`（ConcurrentHashMap 根本不接受 null，
所以 null 只能来自“超时未填槽”，不是失败标记）。本项以代码为准。

### 1.2 官方 fetchSync 的 options 与返回包络

- JS `fetch`（约 6103 行）与 `request`（约 6453 行）都把 options 原样
  `stringify` 后传给 `fetchSync(url, optionsJson)`；batchFetch 的 options
  与之一致，是同一套 fetch options。
- `withHeadersOpt`（25379–25389 行）：内部调用方在 options 里强制注入
  `"withHeaders":true` 再调 fetchSync。
- `wrapFetch(body, headers, url, statusCode, error)`（25391–25399 行）：
  fetchSync 在 withHeaders 时返回的 JSON 包络，键固定为
  `{"body", "headers", "url", "statusCode", "error"}`，其中 headers 是
  `Map<String, List<String>>`（值是数组）。
- 推论（规则用法反推，fetchSync 本体未反编译出）：失败时 statusCode 为 -1
  （猫咪av 判 `res.statusCode != -1`）；包络是 JSON **字符串**，规则侧一律
  `JSON.parse(...)` 后取 `.statusCode` / `.headers.location[0]` /
  `.headers['set-cookie']`。

### 1.3 规则合集 `~/workspace/user/files/4255_____10_q31w.json`（4255 条）

- `batchFetch` 出现 67 次（与 ANALYSIS.md 一致），`bf(` 别名 81 次（文本计数）。
- 入参形态：
  - 纯 URL 字符串数组（多数，如 `batchFetch(urls)`、`batchFetch(htmlUrl)`、
    `batchFetch(st)`、`batchFetch(url)`）；
  - `{url, options}` 对象数组（9 处内联字面量 + 大量经变量构造，如
    Av大聚合模板 / 瓜果聚合 / LSP聚合：
    `batchFetch(urlMap.map(data => ({url: data.url + MY_URL, options: {headers:
    {'User-Agent': 'Documents/HttpCanary/3.3.6'}}})))`；
    lsp轻合集.dz：`{url: urlReq.MY_URL, options: urlReq.MY_URL_Options}`）；
  - `{url}` 无 options 对象（5 处，如草莓）；
  - **扁平 `{url, headers}`：0 处**——无需兼容分支，严格按官方只读
    `url` + 嵌套 `options` 即可。
  - 两参调用 `batchFetch(x, n)`：0 处（threadNum 无规则在用，但官方签名有，
    照做）。
- options 里实际出现的键：`headers`（17 处）、`body`（9 处）、`timeout`、
  `withHeaders`、`withStatusCode`、`redirect:false`、`onlyHeaders:true`。
  典型：
  - 猫咪av / 猫mi：`options:{timeout:1000, withHeaders:true, redirect:false}`，
    随后 `JSON.parse(res)` 取 `statusCode` / `headers.location[0]`（要 302
    不跟随 + 包络）。
  - XYQ推送：510 项（255 IP × 2 端口），
    `options:{timeout:100, onlyHeaders:true, withStatusCode:true}`，
    随后 `JSON.parse(bat[i]).statusCode == 200`（只要头 + 状态码，不要体）。
  - 歌词适配：`options: Object.assign({withHeaders:true, withStatusCode:true,
    redirect:false, timeout:2000, onlyHeaders:true, ...})`，取
    `headers['set-cookie']`。
  - `toHex` / `inputStream` 在 batchFetch 附近：0 处（只青苹果一条 plain
    fetch 用 `toHex:true`，见 §5 缺口）。
- 返回值用法：一律按下标对齐（`forEach((html, index))`、`y[k]`、`ht[i]`、
  `bat[i]`、`DAaW3[R4]`），部分先判 `!= ''` 再 `JSON.parse`，
  部分直接 `JSON.parse(a)`。**无一处判 null**——与官方“异常 → ''”一致；
  超时留 null 时这些规则会抛错，但那是官方同款行为，不另加保护。

### 1.4 草莓最终版 preRule 全文（合集原文）

```js
if (!getVar("cmBody")) {
    var Mw1 = "/api/videosort";
    var SeIqa2 = ['https://api.cmbe2.xyz', 'https://cmjk2.com', 'https://cmjk3.com'];
    var DAaW3 = batchFetch([{
        url: SeIqa2[0] + Mw1
    }, {
        url: SeIqa2[1] + Mw1
    }, {
        url: SeIqa2[2] + Mw1
    }]);
    for (var R4 in DAaW3) {
        if ((DAaW3[R4])["indexOf"]('rescont') != -1) {
            putVar("cmUrl", SeIqa2[R4]);
            putVar("cmBody", DAaW3[R4]);
        }
    }
};
```

- 3 个 `{url}` 对象、无 options；返回值按下标循环，
  `DAaW3[R4]["indexOf"]('rescont')`——单项失败时该位必须是字符串 `""`
  才能安全调用 `indexOf`（若为 null 则抛 TypeError）。这正是官方“异常 → ''”
  语义的直接证据：本项修完后草莓 preRule 不再因 batchFetch 本身报错；
  三个后端 API 在沙箱里是死的（429/DNS 失败），真机拿不到数据仍是后端问题，
  与 batchFetch 无关（见 docs/miniapp-nodata-fix.md）。

### 1.5 3 条验收规则

`~/workspace/miniapp/test-rules/`：奇优影院♔、童话、臻品fix——grep 确认
**均不用 batchFetch**（也无 `bf(`），本项改动不经过它们的调用路径，
零回归只需确认不改动 `fetch()` 的默认返回形态（无 withHeaders/
withStatusCode 时仍返回纯 body 字符串）。

## 2. 现状（f0c10fb7 的实现，`HkEngine.java`）

- `Bridge.batchFetch(Object arg)`（约 314–337 行）：入参
  `[{url, method?, headers?, body?, timeout?}]` 或纯 URL 数组，逐项同步串行，
  单项失败记 `""`，返回 `cx.newArray(scope, String[])`；非数组入参返回空数组。
  JS 侧 BOOTSTRAP（217 行）：`function batchFetch(arr) { return __hk.batchFetch(arr); }`，
  `var bf = batchFetch;`（别名已在）。
- 问题（ANALYSIS.md §6.1）：规则传嵌套 `options` 时，现有代码把整个 item
  当 options 传给 `fetch(url, item)`，`Bridge.fetch` 只读顶层
  headers/method/body/timeout，嵌套的 `options` 对象被整体丢弃——headers/body/
  withHeaders/redirect/onlyHeaders 全丢；且串行，无并发。
- `Bridge.fetch(url, options)`（约 273–297 行）只解析 headers/method/body/
  timeout，不认 withHeaders/withStatusCode/redirect/onlyHeaders；
  `httpFetch(url, headers, method, body, timeout)`（约 1411–1460 行）一律跟随
  重定向、一律读全 body、一律返回纯 body 字符串。
- `httpFetch` 已处理 `hiker://page/`（`HkSubpage.pageJson`）、
  `hiker://files/`（`HkStore.readDataFile`）、其他 `hiker://` → `""`；
  二者均为纯 Java、无 Rhino 引用（HkSubpage.java 无 Context/Scriptable 导入），
  工作线程可直接调。
- `OkHttp` 是 `com.github.catvod.net.OkHttp`；`OkHttp.client(timeout)` 返回的
  `OkHttpClient` 可用 `.newBuilder().followRedirects(false)
  .followSslRedirects(false).build()` 派生不跟随客户端（仓库内
  NodeBundle.java:538 已有同款用法）。

## 3. 方案

### 3.1 batchFetch 重做（照官方 `lambda$registerApi$78` + `lambda$batchFetchImpl$213`）

- 签名：保留 `batchFetch(Object arg)`，加 `batchFetch(Object arg, Object threadNum)`；
  BOOTSTRAP 改为 `function batchFetch(arr, threadNum) { return __hk.batchFetch(arr, threadNum); }`
  （一参调用仍走一参重载；`bf` 别名不动）。
- 调用线程（JS 线程）上先把入参抽成纯 Java 的 `BatchItem` 持有者
  （url / headers / method / body / timeout / withHeaders / followRedirects /
  onlyHeaders）：**Rhino 对象（NativeArray/NativeObject）绝不跨线程**，
  工作线程只碰持有者 + `httpFetch`，与官方“先 jsStringify + Gson 解析、
  线程只碰 Map/String”的做法等价。工作线程不进 Rhino Context——我们
  `httpFetch` 全程不碰 Rhino（§2），不需要官方 `enterAmbientScope` 那一步。
  - 入参形态：NativeArray 逐项抽取；String 入参按官方试 JSON 解析
    （是 JSON 数组则逐项抽取，否则空数组）；其他类型 / null / 空 → 空数组。
  - 每项：String → 纯 URL；NativeObject/JsonObject → 取 `url`
    （`String.valueOf`，缺失/undefined → 该位 `""`），再读嵌套 `options`
    对象（无则默认）；options 的键按 §3.2 解析。
- 并发：`Executors.newFixedThreadPool(poolSize)`，
  `poolSize = min(size, 16)`，第二参 threadNum 给出时
  `max(1, min(size, (int) toDouble(threadNum)))`（解析失败则回落默认）；
  `CountDownLatch(size)`；每项工作线程 `try { 结果入 ConcurrentHashMap }
  catch (Throwable) { 该位 "" } finally { countDown }`。
- 整体超时：`latch.await((size / 16) * 10 + 10, TimeUnit.SECONDS)`（整除，
  照官方）；超时后未入位的槽组装时就是 `null`；随后 `pool.shutdownNow()`。
  外层任何 Throwable → 记日志 + 返回空数组；`shutdownNow()` 放 finally，
  不学官方在 await 抛错时漏关池子。
- 组装：按下标从 map 取值，`Object[size]`，缺位保持 null；
  有当前 Context 时 `cx.newArray(engine.scope, out)`（null 元素即 JS null，
  与官方 `toJsArrayObj` 同款）；无 Context 时回落返回 `Object[]` 本身
  （沿用现有 fallback 形态）。

### 3.2 options 键生效（与 batchFetch 同一次改动，范围只在 `Bridge.fetch` / `httpFetch`）

嵌套 options 若只解析不生效，Av大聚合/猫咪av/XYQ推送/歌词适配仍是断的
（§1.3），所以 options 必须直通到底层 fetch，与官方 fetchSync 同款：

- 沿用已有：headers / method / body / timeout（`Bridge.fetch` 已有解析，
  抽成 `fetch` 与 batchFetch 共用的抽取逻辑；body 为 NativeObject 时在
  调用线程转成 `Map<String,String>` 表单，`buildBody` 加 Map 分支）。
- 新增 `withHeaders` / `withStatusCode`：任一为 true 时返回 JSON 包络字符串
  `{"body": "...", "headers": {"location": [...]}, "url": "...",
  "statusCode": 200, "error": ""}`（照官方 `wrapFetch` 的键与顺序；
  headers 值是数组；键统一小写化——规则一律用小写 `location` /
  `set-cookie` 访问，OkHttp 原始大小写不可靠）。
  失败（抛异常）时包络为 `statusCode: -1, body: "", headers: {}, error: "<msg>"`
  （猫咪av 判 `!= -1` 的依据）；HTTP 非 2xx 不抛，包络带真实 statusCode 与体。
  无这两个键时返回形态不变（纯 body 字符串），3 条验收规则与现有规则零影响。
- 新增 `redirect: false` → 不跟随客户端（`followRedirects(false)` +
  `followSslRedirects(false)`），返回 3xx 包络供规则读 `headers.location[0]`；
  默认跟随不变。
- 新增 `onlyHeaders: true` → GET 拿状态码 + 头后即关体、不读 body
  （包络 body 为 `""`；无包络时返回 `""`）。XYQ推送 510 项扫描用。
- `httpFetch` 保持原 5 参签名供 require 等现有调用方（默认：无包络、跟随、
  读体），新增带 `FetchOpts` 的重载走同一实现，避免两份抓取逻辑。

### 3.3 不做的

- `toHex` / `inputStream`：batchFetch 附近 0 处在用；`toHex` 只有青苹果一条
  plain fetch 在用（见 §5），本次不动。
- 扁平 `{url, headers}` 兼容：合集 0 处在用，不做（严格按官方）。
- 第 1/2/3/5 项范围不动；不推、不编、不动版本记录空间。

## 4. 验收

1. 草莓 preRule 调用链与官方一致：代码级推演——3 个 `{url}` 项抽成
   BatchItem → 并发抓 → 失败位 `""` → `DAaW3[R4]["indexOf"]('rescont')`
   在 `""` 上安全返回 -1、不抛；成功位是体字符串，可 `putVar("cmBody", …)`，
   后续 `find_rule` 里 `JSON.parse(getVar("cmBody"))` 不再报
   `Unexpected token: u`（后端死仍是后端问题，见 §1.4）。
   本地校验：逐行重读 + grep 核查（无 JDK/SDK，编译等 GitHub 构建）。
2. 并发上限 `min(n,16)`：代码核查 `newFixedThreadPool` 入参与 threadNum
   覆盖分支（`max(1, min(size, …))`）。
3. 单项失败返回值与官方一致：异常 → `""`（工作线程 catch Throwable 入位）；
   整体超时未入位 → `null`（组装时 `map.get(i)` 缺位）；外层异常 → 空数组。
4. 3 条验收规则零回归：grep 确认均不用 batchFetch/`bf`；且无
   withHeaders/withStatusCode 时 `fetch()` 返回形态不变。

## 5. 已知缺口 / 只能等真机验的

- `toHex:true`（青苹果 plain fetch 用）：body 应 hex 编码，本次不支持，
  该规则仍走老行为。
- 官方 `fetchSync` 本体未反编译出：包络 `error` 字段在成功时的取值
  （本项取 `""`）、`onlyHeaders` 在官方是 HEAD 还是 GET 弃体（本项取
  GET 弃体，状态码语义更稳）、`withStatusCode` 单独出现时包络是否含
  headers（本项按 `wrapFetch` 全量给）——三处是按 `wrapFetch` + 规则用法
  的最合理外推，需真机以猫咪av / XYQ推送 / 歌词适配三条规则验证。
- 草莓三个后端 API 在沙箱里全死，真机能否出数据取决于手机网络与后端本身；
  本项只保证 batchFetch 语义与官方一致。
- XYQ推送 510 项、每项 timeout=100ms 的大并发形态，只能真机验耗时与
  latch 超时（320s 上限）是否符合预期。
- 编译检查只能等推上去后的 GitHub 构建（本地无 JDK/Android SDK）。

## 6. 回滚

- 单一原子提交 + recovery tag；回滚即 `git revert <提交>`（只动
  HkEngine.java + 本文档，无其他文件牵连）。

## 7. 验收记录（2026-11-15 23:2x CST，本地）

- 静态核查：`git diff --check` 通过；完整 diff 逐行重读两遍——三元表达式
  两侧类型一致（String/String、OkHttpClient/OkHttpClient、
  FetchOpts/FetchOpts），泛型一致（`ConcurrentHashMap<Integer,String>`、
  `Map.Entry<String,List<String>>`、`ArrayMap<String,String>`），
  无悬空引用；旧 5 参 `httpFetch` 唯一剩余调用方是 require（1423 行），
  经委托重载走同一实现；`JsonParser.parseString` 仓库内已有使用；
  `menu_nav.xml` 仍 6 项；3 条验收规则 grep 确认不用 batchFetch/`bf`。
- JS 侧模拟（node，草莓 preRule 原文逻辑）：全失败 `["","",""]` →
  循环安全、cmBody 不设、不抛；一后端成功 → 按下标对上、cmBody/cmUrl
  正确设置；超时留 null 位 → 抛 TypeError，与官方同款行为一致；
  包络 `JSON.parse` 后 `statusCode` / `headers.location[0]` 可取。
- 未做：编译（本地无 JDK/Android SDK，等推上去后的 GitHub 构建）；
  真机验证（草莓后端、猫咪av 302、XYQ推送 510 项、歌词适配 set-cookie）。
