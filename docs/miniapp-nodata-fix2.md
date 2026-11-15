# 小程序第 2 轮没数据修复（miniapp-nodata-fix2）

- 完成句：补上海阔 JS 内置 API `config`/`initConfig`/`rc`（海W剧汇 preRule 报 `initConfig` 未定义），require 报错带上 HTTP 状态码，并把 foundation 文档里 batchFetch 未实现的两行过时描述改掉；gitcode 418 经多形状 curl 证实无法从本端绕过，只留证据不上代理；一个 guard 会话、一个本地原子提交，不推、不编、不动版本记录空间。
- 允许路径：`docs/miniapp-nodata-fix2.md`、`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkEngine.java`、`docs/miniapp-foundation.md`。
- 保护路径：无（开始时工作区干净，main = `f0c10fb7`）。
- 时间：2026-11-15 21:52 CST；预计 30 分钟，22:25 前交回。
- 最便宜的决定性验证：本地无 JDK/SDK，编译等推上去后的 GitHub 构建；本地只做逐行重读 + `git diff --check` + 关键符号 grep + 确认 TV/leanback、`BaseLoader`、`menu_nav.xml` 未动。
- 适用通道：AGENTS.md §1 quick-fix；§7 设计门豁免（窄化修法，已有既定设计：BOOTSTRAP 内置 API、require 取数链路都在第 1/7 步里定过）。

## 触发

用户 2026-11-15 21:45 装 `v5.6.0-beta-202611152108`（上一轮 `f0c10fb7` 那版）试了 3 条规则，还是不行：

1. 鱼鱼🐟：加载失败，`JS 执行失败：Wrapped HkEngine$HkException: require 取到的不是 JS 脚本（可能是拦截/错误页）：https://gitcode.net/qq_32394351/dr/-/raw/master/js/资源网预处理.js (hk-bootstrap#41)`。
2. 海W剧汇♨️：加载失败，`JS 执行失败：ReferenceError: "initConfig" 未定义。(preRule#1)`。
3. 西瓜影音：加载失败，`JS 执行失败：Wrapped HkEngine$HkException: require 取到的不是 JS 脚本（可能是拦截/错误页）：https://gitcode.net/qq_32394351/dr/-/raw/master/js/预处理.js (hk-bootstrap#41)`。

上一轮的 require 守卫在正常工作（报错变清楚了），但规则还是没数据——本轮修的是"让规则真跑起来"，不只是报错变清楚。规则原文按标题从 `~/workspace/user/files/4255_____10_q31w.json` 精确取出，副本在 `~/workspace/miniapp/nodata2-rules/`（`yuyu.json` / `haiw.json` / `xigua.json`）。

## 根因 1：`config` / `initConfig` / `rc` 三个内置 API 缺失——海W剧汇（代码缺口，必修）

- 规则：`url: "hiker://empty##https://www.hwjh.cc##fypage"`，`ua: "mobile"`；preRule 全文只有 `initConfig({ Btea: 'https://gitcode.net/m0_73099089/btea/-/raw/master/webpre.js', })`；find_rule 开头 `rc(config.Btea)`，随后用 `取随机颜色()`、`过验()` 等——这些函数都在 `webpre.js` 库里。
- 代码位置：`HkEngine.java` 的 BOOTSTRAP（:106 起）没有任何 `config`、`initConfig`、`rc`——grep 全 hk 包确认：`config` 只出现在 `HkStore.java:55` 的目录名（无关），逆向报告里 0 次提到 `initConfig`/`rc`（反编译缺口）。
- 证据（合集统计）：
  - `initConfig({...})`：232 条规则在用，全部是 preRule 里传一个对象字面量；参数键各不相同（`Btea` / `host` / `依赖` / `模板` / `xiao` …共 38 种键组合），语义统一是"把这些键值合并进规则的 `config` 对象"。
  - `config.*`：665 条规则在 find_rule 里读（`config.Btea`、`config.模板`、`config.host`…），19 条在 preRule 里读；没有任何一条规则 JSON 带顶层 `config` 键——`config` 是纯 JS 侧的全局对象。
  - `rc(...)`：79 条规则在用；`rc(config.Btea)`（17 条）、`rc('https://…', 24)`（65 条，第二个参数疑似缓存小时数）、`rc(fc('…'))`（14 条）；只有 1 条规则自己定义过 `rc`（'九石预处理转换器'，那是改写其他规则的元规则，它的 find_rule 里 `rc` 是字符串不是函数）。
  - 同款 Btea 家族（青苹果♨️、七味影视♨️）与海W剧汇的 preRule/find_rule 开头逐字相同：`initConfig({Btea: '…webpre.js'})` + `rc(config.Btea)`。
- 修法（只实现证据支持的语义，不编）：
  - BOOTSTRAP 加 `var config = {};`——每次 `prepare()` 建新 scope 时重建，preRule 在每次求值前都重跑（`HkExecutor` home/search/detail/play 全是 prepare → preRule → 规则体），所以同一次求值内 initConfig 设的值一定在，后面的 find_rule/detail 都能读到。
  - BOOTSTRAP 加 `function initConfig(o) { if (o && typeof o === 'object') { for (var k in o) { config[k] = o[k]; } } return config; }`——纯 JS 合并，不动 Java；非对象入参直接返回现有 `config`，不抛错。
  - BOOTSTRAP 加 `function rc(url, hours) { return __hk.require(url); }`——与 `require` 同款语义（取库 eval，同一引擎内同一 URL 只 eval 一次，`requiredLibs` 去重已在）；第二个参数（疑似缓存小时数）只接受不兑现——缓存语义无证据，不编；每次重抓行为正确、只是多一次请求。
  - 诚实限定：`config` 本轮是每次求值新建、不跨求值持久化；若某条规则在某次求值里读 `config.*` 而它的 preRule 没设过（例如详情页 preRule 为空却读首页 preRule 设的值），仍是 undefined——海阔原生的 config 持久化语义无证据，不编实现，记为遗留。
- 回归：验收 3 条（奇优影院♔/童话/臻品fix）grep 确认 0 次用 `initConfig`/`rc(`/`config.`，新内置只加不改旧行为。

## 根因 2：gitcode WAF 418 全面拦截——西瓜影音、鱼鱼（本端修不了，只留证据）

- 规则：西瓜影音 preRule `require('https://gitcode.net/qq_32394351/dr/-/raw/master/js/预处理.js')`；鱼鱼 preRule `require('https://gitcode.net/qq_32394351/dr/-/raw/master/js/资源网预处理.js')`。两条规则的 find_rule 都靠这个库定义 `config.模板` 与 `依赖检测()` / `一级书签()` / `一级()`——库拿不到，规则寸步难行。
- 证据（curl，沙箱，本轮重测，全部 418，响应体一律 3223 字节 CloudWAF "访问被拦截！"页）：
  - 原始中文 URL、百分号编码 URL、PC UA、手机 UA、curl 默认 UA；
  - `Referer: https://gitcode.net/` + `Accept: text/javascript`、完整浏览器头（Accept-Language/Sec-Fetch/Upgrade-Insecure-Requests）；
  - 先访问首页拿 cookie（`HWWAFSESTIME`/`HWWAFSESID`）再带 cookie 重试；
  - URL 变体：`/-/blob/` 页、`?inline=false`、gitcode API v5 `api/v5/repos/qq_32394351/dr/contents/js/…`；
  - HTTP/1.1 与默认 HTTP/2。
  - **连 gitcode 首页本身都 418**——不是某个文件路径的问题，是 WAF 按出口 IP/TLS 指纹整站拦截；手机上同样拿到拦截页（截图里的守卫报错就是证据），不是沙箱 IP 独有。
- 结论：不编代理/镜像绕过（任务明确禁止），不改 require 去别的源取同一个库——那等于替规则作者换托管站。西瓜影音与鱼鱼在 gitcode 放行前就是用不了的；本轮能给的只有根因 3 的"报错带状态码"，让手机上的拦截一目了然。

## 根因 3：require 报错看不到 HTTP 状态码（小改进，顺手做）

- 现状：`httpFetch`（`HkEngine.java:1215` 起）从不记录 `Response.code()`，异常吞成 `""`；require 的两处报错（空内容、HTML 守卫）都只写 URL——手机上拿到的是 418 还是 404/500 完全无法区分。
- 修法（只限 require 路径，不动通用页面抓取）：`HkEngine` 加字段 `lastHttpCode`（默认 -1），`httpFetch` 入口先置 -1、拿到真实 HTTP 响应后记 `res.code()`；require 的 HTML 守卫报错改成 `require 取到 HTTP <code>（不是 JS 脚本，可能是拦截/错误页）：<url>`（code 未知时保持旧文案），空内容报错同样带上已知状态码。`httpFetch` 本身仍返回 `""`、仍不抛错——页面抓取行为原样不动。

## 顺手：foundation 文档两行过时描述

- `docs/miniapp-foundation.md:45` 与 `:104` 仍写 `batchFetch` 未实现（上一轮遗留）：`batchFetch` 已在上一轮真实现、`require`/`request` 在第 7 步已实现——把"暂不支持"列表里的已实现项去掉，只留仍是桩的（`xpath`/`getCryptoJS`/`aesEncode`/`encodeStr` 等）。

## 回归检查（验收 3 条）

- 奇优影院♔/童话/臻品fix：0 次用 `initConfig`/`rc(`/`config.`；`require` 的状态码只进报错文案，不改取数行为；`httpFetch` 返回值与异常处理原样。
- 未动：TV/leanback、`BaseLoader`、`menu_nav.xml`（仍 6 项）、三条验收规则的规则文件、任何开关默认值。

## 回滚

单提交回滚：`git revert <本次提交>`；recovery tag 见提交信息。`config`/`initConfig`/`rc` 与 require 状态码两处改动互不依赖，可单独 revert。

## 实施记录

- 改动：`HkEngine.java`（BOOTSTRAP +3 行 JS：`var config = {}` / `initConfig` 合并 / `rc` 委托 `__hk.require`，注释 +2 行；`lastHttpCode` 字段 1 个 + `httpFetch` 入口置 -1、响应后记 `res.code()`；require 两处报错改三元带状态码，code 未知时保持旧文案）；`docs/miniapp-foundation.md` 2 行过时描述修正。
- 验证：node `--check` + 运行新 JS 片段通过（initConfig 合并、保留旧键、容错 null 与非对象）；`git diff --check` 通过；grep 确认 `config`/`initConfig`/`rc` 各只声明一次、`lastHttpCode` 只在 `HkEngine` 内读写；`menu_nav.xml` 仍 6 项；验收 3 条 0 次用新 API；本地无 JDK/SDK，编译等推上去后的 GitHub 构建。
- 真机待验：海W剧汇 preRule 不再报 `initConfig` 未定义（但随后 `rc(config.Btea)` 取 gitcode 库仍会报 `require 取到 HTTP 418…`）；西瓜影音/鱼鱼的报错将带上 HTTP 418。

## 遗留

- 西瓜影音、鱼鱼：gitcode WAF 418（见根因 2），等 gitcode 放行或规则作者换库托管；海W剧汇的 `webpre.js` 也在 gitcode 上（`m0_73099089/btea`），initConfig 修完后它仍会撞同一堵墙——真机上它会报 `require 取到 HTTP 418…`。
- `config` 不跨求值持久化（见根因 1 限定）；`rc` 的第二个参数（疑似缓存小时数）暂不兑现，每次重抓。
- `xpath`/`getCryptoJS`/`aesEncode` 等仍是抛错桩（地基原状），本次不动。
