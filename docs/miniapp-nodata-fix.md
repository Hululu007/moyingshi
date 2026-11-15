# 小程序 4 条规则没数据修复（miniapp-nodata-fix）

- 完成句：修掉 4 条规则没数据里属于代码的 3 处根因（UA 简写原样发出、batchFetch 仍是抛错桩、require 把拦截页 HTML 当 JS 执行），站点/规则本身的问题只留证据不上代码；一个 guard 会话、一个本地原子提交，不推、不编、不动版本记录空间。
- 允许路径：`docs/miniapp-nodata-fix.md`、`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkEngine.java`、`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkExecutor.java`。
- 保护路径：无（开始时工作区干净，main = `b57287bd`）。
- 时间：2026-11-15 21:03 CST；预计 20 分钟，21:25 前交回。
- 最便宜的决定性验证：本地无 JDK/SDK，编译等推上去后的 GitHub 构建；本地只做逐行重读 + `git diff --check` + 关键符号 grep + 确认 TV/leanback、`BaseLoader`、`menu_nav.xml` 未动。
- 适用通道：AGENTS.md §1 quick-fix；§7 设计门豁免（窄化修法，已有既定设计：UA 常量、batchFetch 语义、require 取数链路都在第 1/7 步里定过）。

## 触发

用户 2026-11-15 20:54 装 `v5.6.0-beta-202611151958`（第 6+7 步那版）试了 4 条规则，全没数据：

1. 小萝莉：顶部"页面约 0KB，规则返回 0 条"，空态"这里什么都没有"。
2. 西瓜影音：加载失败，`JS 执行失败：Wrapped HkEngine$HkException: JS 执行失败：语法错误 (require: https://gitcode.net/qq_32394351/dr/-/raw/master/js/预处理.js#1) (hk-bootstrap#41)`。
3. 电影蜜蜂：顶部"页面约 0KB，规则返回 0 条"，空态"加载失败"。
4. 草莓最终版：加载失败，`JS 执行失败：SyntaxError: Unexpected token: u (find_rule#1)`。

规则原文按标题从 `~/workspace/user/files/4255_____10_q31w.json`（57MB）精确取出，副本在 `~/workspace/miniapp/nodata-rules/`。验收 3 条（奇优影院♔/童话/臻品fix，`~/workspace/miniapp/test-rules/`）修完不得回归——它们的 `ua` 也是简写（见"回归检查"）。

## 根因 1：UA 简写被原样发出——小萝莉（代码 bug，必修）

- 规则：`url: "https://sisterasian.com/category_fyclass?page=fypage"`，`ua: "mobile"`，无 preRule；find_rule 是 JS（`getResCode()` + `pdfa(h, '.video-block&&.video-card')`）。
- 代码位置：`HkExecutor.java:185 / 868 / 976` 三处都是 `headers.put("User-Agent", rule.getUa());`——规则 `ua` 字段被原样当 User-Agent 发出。海阔规则的 `ua` 是简写（`"mobile"` / `"auto"` / `"pc"`），不是真 UA。
- 证据（curl，沙箱）：
  - UA 为 `okhttp/4.12.0` 或空 → HTTP 200，**1010 字节**——一段 GDPR 同意管理器 + 广告注入壳；1010 / 1024 = 0KB，与真机诊断"页面约 0KB"完全一致。
  - UA 为真浏览器（Pixel 8 Chrome 126）→ HTTP 200，约 27.6KB，跳转到 `ww17.sisterasian.com`。
  - 所以真机收到的是 1KB 壳，`.video-block` 选择器无从命中 → 0 条。
- 修法：`HkEngine` 加 `resolveUa(String)`（`"mobile"`→`MOBILE_UA`，`"pc"`→`PC_UA`，`"auto"`/空→`MOBILE_UA`，已是 `Mozilla/` 开头的真 UA 原样过，其余原样过）；`HkExecutor` 三处改调它；`HkEngine.httpFetch` 在 headers 缺 UA 时默认补 `MOBILE_UA`（JS 侧 `fetch()`/`require()` 走这条，原先是 OkHttp 默认 `okhttp/4.x`，同样是反爬磁铁）。优先级不变（foundation 文档已定：firstHeader < 规则 ua < URL 修饰符）。
- 诚实限定：**UA 修完仍未必够**。真浏览器 UA 抓回的 27KB 页全文 0 次出现 "video"，仍是同意/广告壳，没有视频列表 HTML——列表是站内 JS 在同意后才渲染的，静态抓取拿不到。除非手机（国内 IP）拿到的是真列表 HTML，否则这条规则仍 0 条，需真机验。

## 根因 2：require 把 WAF 拦截页 HTML 当 JS 执行——西瓜影音（代码缺口，必修）

- 规则：`url: "hiker://empty##https://m.kkw300.com/"`，`ua: "mobile"`，preRule 先设 Chrome UA 变量，再 `require('https://gitcode.net/qq_32394351/dr/-/raw/master/js/预处理.js')`；find_rule：`require(config.模板); 依赖检测(); 一级书签('/vodtype/fypage.html','');`。
- 代码位置：`HkEngine.require()`（:1006-1030）经 `httpFetch(u, null, "GET", null, ...)` 取回后直接 `eval(code, "require:" + u)`；`httpFetch`（:1166 起）不看 HTTP 状态、任何响应体都当成功返回，异常也吞成 `""`。
- 证据（curl，沙箱）：该 gitcode URL 原始中文与百分号编码两种写法、默认 curl UA 与完整浏览器 UA（含 Referer）**全部返回 HTTP 418 "访问被拦截！"**（CloudWAF HTML 错误页，约 3.2KB）。HTML 当 JS 在第 1 行报语法错，与真机报错完全一致（`(hk-bootstrap#41)` 是引导脚本第 41 行 `eval` 的位置）。
- 修法：① `httpFetch` 缺 UA 时默认补真浏览器 UA（见根因 1；OkHttp 默认 UA 过 WAF 的概率更低）；② `require()` 在 eval 前拒收 HTML：trim 后以 `<!DOCTYPE` 或 `<html` 开头即抛 `HkException("require 取到的不是 JS 脚本（可能是拦截/错误页）: " + u)`，不再把错误页当 JS 啃。只拦这两种前缀，合法 JS 不会以它们开头。
- 诚实限定：这只让报错变清楚、且给了 WAF 放行的最好机会；gitcode 的 WAF 是服务端拦截，手机网络能否通过**只能真机验**。若仍 418，这条规则在该设备网络下就是用不了的。

## 根因 3：batchFetch 仍是抛错桩——草莓最终版（代码缺口，必修）

- 规则：`url: "hiker://empty"`，preRule 用 `batchFetch([{url: 'https://api.cmbe2.xyz/api/videosort'}, {url: 'https://cmjk2.com/api/videosort'}, {url: 'https://cmjk3.com/api/videosort'}])` 循环找含 `rescont` 的响应，`putVar("cmBody", …)`；find_rule：`var json = JSON.parse(getVar("cmBody")); … setHomeResult(res)`。
- 代码位置：`HkEngine.java:93` `__unsupported(name)` 返回调用即抛错的函数；`:159` `var batchFetch = __unsupported('batchFetch');`——地基第 1 步就留下的桩，至今未实现（`docs/miniapp-foundation.md:104`）。
- 证据：Rhino 的 `Unexpected token: u` 就是 `JSON.parse("undefined")`（`getVar("cmBody")` 得 undefined → 转字符串 "undefined"，首字符 'u'）。`cmBody` 从未被设置，因为唯一能设置它的 preRule 里的 `batchFetch` 一调就抛。
- 修法：`HkEngine.Bridge` 加 `batchFetch(Object)` 真实现：入参是 JS 数组时逐项取 `url`，复用 `Bridge.fetch(url, item)`（item 本身就是 options，headers/method/body/timeout 都认；字符串元素直接当 URL），逐个同步抓、单项失败记 `""`（不抛，让规则自己的循环跳过），返回 `NativeArray` 字符串数组；BOOTSTRAP 里 `var batchFetch = __unsupported('batchFetch');` 改成 `function batchFetch(arr) { return __hk.batchFetch(arr); }`，`var bf = batchFetch;` 别名不动。
- 诚实限定：① 后端接口从沙箱看全死：`api.cmbe2.xyz/api/videosort` → HTTP 429，`cmjk2/3.com` → DNS/连接失败。手机网络若同样不通，batchFetch 实现后仍拿不到 `cmBody`，规则照样失败——需真机验。② **未解的疑点**：按合集原文，preRule 应先抛 `batchFetch 暂不支持 (preRule#…)`，真机报的却是 find_rule 的错。`prepare()` 会 `vars.clear()`（`HkEngine.java`），`cmBody` 不可能跨页残留；且西瓜影音的报错证明 preRule 确实会跑。最可能的解释是用户装机那份草莓规则的 preRule 与合集不同（为空或另一版本）。batchFetch 实现后若真机仍报同一错，即坐实装机规则无 preRule——那时是规则本身的问题，代码修不了，需规则自带能设 `cmBody` 的 preRule。

## 根因 4：电影蜜蜂——站点本身已死（不修代码，只留证据）

- 规则：`url: "https://www.idybee.com/fyAll/page/fypage"`，`ua: "auto"`，find_rule 是 JS（`getResCode()` + `parseDom(html, 'body&&.i_list,…')`，`res.data = items; setHomeResult(res)`）。
- 证据（curl，沙箱）：`https://www.idybee.com/movie/page/1`（fyAll→movie）与根域名 `https://www.idybee.com/`，桌面 UA 与手机 UA **全部 302 到 `http://survey-twist.com`**，再到广告点击域 `click-v4.junclikrmedi.com`，最终 HTTP 200 但 **0 字节**。全站广告跳转，与 UA 无关。真机"页面约 0KB"与之吻合：我们的 `fetch()` 正常跟随重定向、拿到空体返回 `""`，JS 在空页上跑出 0 条。
- 结论：站点已挂/被停车页接管，代码无责、无可修。等站点恢复或换规则。

## 回归检查（验收 3 条）

- 奇优影院♔：`ua: "mobile"` → 映射后 `MOBILE_UA`。此前它用字面 `"mobile"` 也能出数据，映射成真浏览器 UA 只会更像正常浏览器；同站已用浏览器 UA 验过 200。
- 童话：`ua: "mobile"` → 同上。
- 臻品fix：`ua` 字段（见 `~/workspace/miniapp/test-rules/臻品fix.json`）→ 按同一映射表走；`fetch()`/`require()` 的默认 UA 只影响原来发 `okhttp/4.x` 的 JS 侧请求。
- `batchFetch` 原先是抛错桩，任何调它的规则之前都是失败；实现后只可能变好。`require` 的 HTML 拒收只拦 `<!DOCTYPE`/`<html` 开头，合法 JS 不受影响。
- 未动：TV/leanback、`BaseLoader`、`menu_nav.xml`（仍 6 项）、三条验收规则的规则文件、任何开关默认值。

## 回滚

单提交回滚：`git revert <本次提交>`；recovery tag 见提交信息。UA 映射与 batchFetch、require 守卫都在同一提交内，各自独立可单独 revert（三个改动点互不依赖）。

## 遗留

- 小萝莉：同意墙/JS 渲染问题（见根因 1 限定），静态抓取天花板；若真机仍 0 条，选项是规则改走同意后接口，或放弃这条规则。
- 西瓜影音：gitcode WAF 418 能否在手机网络通过（见根因 2 限定），真机验。
- 草莓最终版：三个后端 API 是否在手机网络可达（见根因 3 限定）；以及装机规则 preRule 是否与合集一致的疑点，batchFetch 实现后真机复测可定。
- 电影蜜蜂：站点问题，等站恢复或换规则。
- `batchFetch` 本次只支持逐项同步抓（顺序、默认 30s 超时）；海阔的并发/超时细节未实现——3 条验收规则不用它，够用即可。
- `docs/miniapp-foundation.md:45、104` 仍写着 batchFetch 未实现（过时行，本次 guard scope 没含它，下次顺手改）。
- `xpath`/`getCryptoJS`/`aesEncode` 等仍是抛错桩（地基原状），本次不动。
