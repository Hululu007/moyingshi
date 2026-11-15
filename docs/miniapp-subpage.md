# 小程序第 7 步之三：hiker://page/ 子页面完整展开 + require 执行

- 任务：miniapp-subpage（standard lane；10 步串行工程的第 3 步）
- HEAD：40de793a（main，本地超前 origin 两笔，未推）；工作区干净后开工
- 完成句：`hiker://page/` 子页面按规则 `pages` 展开（JS 内 `fetch` / `require` / 卡片点开三路都通），`require.json` + `libs.zip` 的库加载走 `plugins/hk/libs/`，`readFile`/`writeFile`/`getItem`/`setItem`/`getMyVar` 持久变量最小可用，`playResolved` 选择器懒加载路同款 guard；只改手机端，本地提交，不推、不编、不触发构建、不动版本记录空间。
- 时间：2026-11-15 18:21 CST（UTC 10:21）开工；估计 1.5–2 小时，预期 20:00 CST 前完成并提交。
- 最便宜的决定性验证：逐行重读改动 + `git diff --check`（本地无 JDK/Android SDK，编译检查等推上去之后的 GitHub 构建；真机验收等用户）。

## Recovery anchor

- 目标与验收：见“验收标准”一节；真机验不了的项已在验收表里标明。
- 计划状态：任务文档已写；实现已完成——新文件 `HkSubpage.java`（子页面查找/展开/require），改 `HkEngine.java`（require、持久变量、文件 API、`$` 函数化、`hiker://page/` 与 `hiker://files/` 的 fetch、`getHome` 等补 API、CxHandle 复用 Context）、`HkExecutor.java`（detail 子页面分支 + lazy 动作 URL 明错、playResolved 子页面展开）、`HkStore.java`（data 文件读写 + require 清单解析 + lib 解析）、`HkListHelper.java`（`x5_webview_single` 无标题卡片窄化保留 + `toVods` 回落标题“网页”）；逐行重读完成，复核修 3 处（见 §7）。
- 已完成证据：西瓜影院与阅动漫的执行链路已按规则原文逐段推演（见汇报；代码层面确认，真机待验）；`git diff --check` 通过；改动只在 guard scope 的 6 个文件内；TV/leanback、`BaseLoader`、`menu_nav.xml` 未动。
- 未验证的改动：全部（本地无 JDK/Android SDK，编译检查等推上去之后的 GitHub 构建；真机验收等用户）。
- 未解决风险：子页面展开是按公开规则语义 + 真实规则反推的重写（`expandPage` 本体反编译失败），fidelity 只能真机对；`x5_webview_single` 内联 WebView 渲染不在本步；懒加载动作 URL（`@lazyRule=`/`@input=`）的点击执行不在本步；`Main.view` 不在规则 pages 里。
- 下一步：`bash .codex/scripts/task_guard.sh finish --verified <证据> --commit-message <消息>`，随后 `git tag -a` 建 recovery tag。

## 1. 证据表

| 证据 | 位置 | 结论 |
|---|---|---|
| `subPageId` / `subPageRealUrl` / `subPageTitle` 可读 | 逆向报告 `~/workspace/user/files/__________8_3azg.md` §7.1（L654–L690 附近） | 卡片 id 编码 `?hk_u=<url 编码>&hk_t=<标题编码>`；本步本地不走 Vod id 编码（`HkListHelper.toVods` 直接用卡片 url 当 id），只复用“子页面地址挂在卡片 url 上、打开时再解”的语义，不照搬编解码 |
| `expandPage(site, f22, url, uy1)` 反编译失败 | 同上 §7.1（L422–L427，527 条指令被跳过）、§6 | 无源码，只能按调用方 + 子页面编解码逻辑 + 公开规则语义重写；本步不声称复刻魔影 |
| 子页面 URL 语义 | 同上 §2.5 | `hiker://page/` 开头；`detail()` 里处理子页面与动作 URL |
| `f22.detail` / `f22.t()` 反编译失败 | 同上 §7.10（L5027 起）、§6 | 详情与懒加载是重写版（第 4/5 步），本步只在它们上面加子页面分支，不重写 |
| `pages` 字段是子页面定义 | 报告 §3（`pages` = 分页规则，实为子页面数组）+ 规则实测 | 规则 JSON 的 `pages` 是 JSON 字符串数组，每页 `{name, path, rule, col_type}`；`hiker://page/<name>` 按 `name` 或 `path` 匹配 |
| 西瓜影院 `fetch('hiker://page/home')` | 4255 合集（标题 exactly“西瓜影院”）：`url=hiker://empty`，find_rule `js: var 网站='https://www.ait.net.cn/'; eval(JSON.parse(fetch('hiker://page/home')).rule)`；`pages` 6 页，`home` 页（path=home）规则以 `setResult(d)` 结尾，内调 `$.require("hiker://page/x5ai")(...)` 与 `$.require("hiker://page/u")(...)` | fetch 子页面必须返回**含 `rule` 字段的 JSON 字符串**（`JSON.parse(...).rule` 才能 eval）；`home` 页只在顶层设 `$.exports`（x5ai/u 页同款），`window`/`document` 引用全在 `$.toString` 序列化的函数体内，不在执行路径上 |
| 西瓜影院 home 页结果 | 同上 | 2 张卡片：`x5_webview_single`（title 为空、url=`https://www.ait.net.cn/`）+ `line`（无标题无链接）；规则 `col_type=movie_3`，`detail_find_rule` 为空；现行 `pickCards` 三遍都会丢掉这两张（`usable()` 要求标题 + 链接双全）——锚点“首页出卡片”需要列表侧窄化保留（见实现清单第 7 条），内联 WebView 渲染不在本步 |
| 阅动漫 `.hkzip` | `~/workspace/miniapp/test-rules/阅动漫.hkzip`（rule.json + require.json + libs.zip） | `url=hiker://empty`，`detail_find_rule` 为空；`find_rule` 用 `getMyVar('myCollection')` / `readFile('newWindow')` / `getMyVar('typeShow','@')` / `$.require('hiker://page/dataLoad?rule=...')` / `$.require('hiker://page/funcTools?rule=...', {...})` / `$(...).input(...)` / `$('#noLoading#').lazyRule(...)` / `getItem("read_policy")`；`preRule` 用 `putMyVar` + `getItem/setItem` + `clearVar('remoteCache')` + `refreshPage()` |
| 阅动漫 `pages` 12 页 | 同上 rule.json | `ClassTab` / `dataLoad` / `funcTools` 等；`dataLoad` 尾 `$.exports = data`（返回规则数组），用 `fetch('hiker://files/libs/' + md5(url) + '.js')` 读缓存、`writeFile` / `saveFile` 写缓存、`readFile(timeKey)` 读时间戳；`funcTools` 尾 `$.exports = {runCode, indexUrl, paramHandle, ...}`，内调 `$.require('hiker://page/ClassTab?rule=...', Object.assign({QING_TITLE}, MY_RULE))`；`ClassTab` 尾 `$.exports = {fyAll, tabHeader: new ClassTab(...)}`，用 `$.importParam` 解构、`readFile('tabFold')` |
| `require.json` + `libs.zip` | 同上；第 2 步导入已落盘（见 `docs/miniapp-import.md`） | require.json 是 `[{url, file, accessTime}]`，`file` 已改写到 `plugins/hk/libs/<basename>`；libs.zip 解出 `fc4ffbfdc3d68e07e6f7589de746b80b.js`（Ver.js，SlideX 版本清单）与 `7653baa883a41f39253dbdcc820786d2.js`（x5ui.js，工具库）；规则本体与 pages 里 0 次提到这两个库名——库是框架全局脚本，供远程拉回的子规则用，不是本规则直接引用 |
| 两条规则 pages 的 JS API 用量扫描 | 对阅动漫 12 页 + 西瓜影院 6 页 + 各自主规则做 identifier 统计 | 缺口清单：`getHome`（3 次）、`saveFile`（16 次）、`getItem/setItem`（各 3–4 次）、`clearVar(name)`（带参，2 次）、`readFile/writeFile`（13/4 次）、`fileExist`（Ver.js 用）、`deleteFile`（1 次）、`setPageTitle`（4 次，全在序列化函数体内）、`confirm/select/input`（3 次，全在序列化函数体内）；已有：`fetch/post/request/getResCode/setResult/log/toast/pdfh/pdfa/md5/base64/getVar/putVar/getMyVar/putMyVar/getParam/refreshPage` |
| 地基 require 是抛错的桩 | `docs/miniapp-foundation.md` + `HkEngine.java` BOOTSTRAP（`var require = __unsupported('require')`） | 本步把它换成真实现 |
| `httpFetch` 对 `hiker://page/...` 返回 `""` | `HkEngine.java` + `docs/miniapp-hk-fixes.md`（遗留第 2 条） | 本步修：`hiker://page/` 走展开返回页面 JSON；`hiker://files/` 读文件；未知 `hiker://` 返回 `""` + 日志 |
| `playResolved` 选择器懒加载路的 fetch 会 scheme 错 | `HkExecutor.java` + `docs/miniapp-hk-fixes.md`（遗留第 1 条） | 选集地址是 `hiker://page/...` 时本步走展开/报明确错，不再抛 OkHttp scheme 错 |
| `prepare()` 会 `myVars.clear()` | `HkEngine.java` | 跨页持久变量现在会被清掉；本步改成从 `plugins/hk/data/<标题>/myvar.json` 加载，`putMyVar` 直写回盘 |
| 卡片点开链路 | `HkListFragment.onItemClick` → `HkDetailActivity.open(..., item.getId(), ...)` → `HkDetailFragment` → `HkExecutor.detail(rule, url)` | 子页面卡片（url=`hiker://page/...`）的展开点在 `HkExecutor.detail()`，UI 不用动 |
| `libsDir()` / `saveConfig` / `readConfig` 已有 | `HkStore.java` | require 清单读配置目录、lib 文件读 `plugins/hk/libs/`，复用现成路径，不新定目录 |

## 2. 方案比较

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 不做 | 保持现状：require 抛错桩、`httpFetch` 对 hiker 返回 `""`、play 选择器路 scheme 错 | 零风险、零回归 | 西瓜影院首页永远 0 卡片（`JSON.parse('')` 抛错）、阅动漫完全跑不起来、两处 hk-fixes 遗留一直挂着 | 否决（本步的任务就是做这两处） |
| B. 照搬上游（魔影） | 按报告逐字复刻 `expandPage` + `w02` 的 require/`$.exports`/`$.importParam` 语义 | 与魔影行为最接近 | `expandPage`/`detail`/`t`/`w02` 全无源码，无从照搬；`w02` 5772 行只给了结论；`HkSiteAdapter` 的 `CACHE`/`toVods` 编码与本地 `HkListHelper` 结构不同，硬搬要重构详情/列表链 | 否决（无源码可搬，只能按语义重写） |
| C. 窄化适配（推荐） | 新 `HkSubpage`：按规则 `pages` 查页（name/path）；`fetch` 返回页面 JSON；`require` 在子引擎里执行页面规则，取 `$.exports` 优先、其次 setResult 卡片转 JS 数组、再其次 eval 返回值；`|` 多页取最后一页导出；递归深度 cap 8；`readFile/writeFile/saveFile/fileExist/deleteFile` 与 `getItem/setItem`、`getMyVar` 持久化到 `plugins/hk/data/<标题>/`；`$` 函数化（`lazyRule`/`input` 只产字符串，不执行）；detail/play 各加子页面分支；列表侧只对 `x5_webview_single` 无标题卡片做窄化保留 | 改动集中在 hk 包 5 个文件；三条验收规则零触碰（它们没有 `pages`、不用 require、不走子页面分支）；未知地址不崩、有明确错；阅动漫能推到首页出卡片 | 子页面 fidelity 只能真机对；`x5_webview_single` 卡片点开仍是详情错误态（内联 WebView 是后续步骤）；懒加载动作 URL 点击执行不在本步 | **采用** |

子问题“require 在调用方 scope 执行还是子引擎执行”的比较：

| 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 同 scope 执行页面规则 | 与 `eval(fetch(...).rule)` 的行为一致；页面顶层声明直接进调用方 scope | `const`/`let` 重声明会抛错：`ClassTab` 在一次首页执行里会被 require 两次（find_rule 顶层 + funcTools 的 indexUrl 内）；`var d` 等名字也会撞 | 否决 |
| 子引擎执行（推荐） | 每页独立 scope，无重声明问题；`$.exports`/`setResult`/返回值的优先级清晰；递归 require 自然工作 | 页面内 `putVar` 的页面变量不出子引擎（符合“页面变量”的语义）；子引擎的 `putMyVar` 需写盘回读（本步做直写 + 父引擎 require 后重载） | 采用 |

## 3. 实现清单

1. **新 `HkSubpage.java`**（`api/hk`，mobile 源集）：
   - `findPage(HkRule, name)`：解析 `rule.getPages()`（JSON 数组字符串，空/非法 → 空表），按 `name` 或 `path` 精确匹配，失败再大小写不敏感匹配；找不到返回 null。
   - `pageJson(HkRule, name)`：返回页面对象的 JSON 字符串（供 `fetch('hiker://page/...')`，使 `JSON.parse(...).rule` 成立）；页不存在抛 `HkException("子页面不存在：<name>（规则 pages 里没有这一页）")`。
   - `expand(HkRule, pageUrl)` → `List<HkCard>`：解析 `hiker://page/<名>[?query]`；子引擎 `prepare(rule, pageUrl, 1, "page", "")` + `evalPreRule()` + `evalFindRule(jsBody(页面 rule))`；页面 rule 为空抛明确错；返回 setResult/返回值的卡片（复用 `HkEngine.evalFindRule` 的转换）。
   - `require(HkEngine caller, HkRule, pageUrl, importParam)` → `Object`：拆 `|`（多页按序执行，取最后一页导出）；每页子引擎执行，`$.importParam` = 第二参数（非 Undefined 时）否则为 query 参数对象；返回值优先级：`$.exports`（已设置且非 Undefined）→ 子引擎 setResult 卡片（转 JS 数组，供调用方 `.filter/.map`）→ eval 返回值；页不存在/规则空抛明确错；递归深度 ThreadLocal cap 8，超了抛明确错。
   - `isSubpageUrl(String)` / `subpageName(String)` 等小工具（`hiker://page/` 前缀判定与名/参数拆分，供 Engine/Executor 共用）。
2. **`HkEngine.java`**：
   - BOOTSTRAP：`require` 换成真实现（`__hk.require`），`$` 改为可调用函数（保留 `$.toString`/`$.stringify` 属性，新增 `$.require` / `$.hiker = {}` / `$.importParam = {}`），`$()` 返回带 `lazyRule(fn, ...args)` / `input(fn, ...args)` 的构造器，产字符串 `<sel>@lazyRule=<$.toString(fn,args)>` / `<sel>@input=<$.toString(fn,args)>`（只产串，不执行；点击执行是后续步骤）。
   - Bridge 新增：`require(url, params)`（`hiker://page/` 走 `HkSubpage.require`，其余走 lib 清单/HTTP fetch 后 eval，见下；未知地址抛明确错）、`getHome(url)`（复用 `homeOf`）、`readFile/writeFile/saveFile/fileExist/deleteFile`（经 `HkStore` 落 `data/<标题>/`，`hiker://files/` 前缀剥掉，`..` 越界回落 basename）、`getItem/setItem`（`items.json`，字符串 KV）、`clearVar(String)`（删单个页面变量；无参版保留清全部）、`setPageTitle(String)`（记日志的空操作）、`confirm/select/input` 三个抛明确错的桩（与现有 `__unsupported` 同款口径）。
   - 持久变量：`prepare()` 不再 `myVars.clear()`，改为从 `HkStore` 读 `myvar.json` 加载（字符串直接进 map；数字/布尔进对应 Java 值；对象/数组在 scope 里 `JSON.parse` 还原）；`putMyVar` 直写回盘；`require` 返回后父引擎重载一次 `myVars`（子引擎可能写盘）。
   - `httpFetch`：`hiker://page/...` → `HkSubpage.pageJson`（页不存在抛 `HkException`，JS 侧得明确错而非 `JSON.parse('')`）；`hiker://files/...` → 读 `data/<标题>/` 下对应文件，缺失返回 `""`；`hiker://empty` 与其他 `hiker://`（search/collection/history 等导航动作）→ 返回 `""` + 一条日志；http(s) 保持原样。
   - lib 加载（按需，不自动注入——报告里无自动注入证据）：`require(<http(s) URL>)` 先查 `HkStore.resolveLibFile(title, url)`（require.json 清单 → `plugins/hk/libs/`），命中读本地文件 eval；未命中且 http(s) 时 fetch 后 eval；已 require 过的 URL 记 set 防 `let` 重声明；其余抛明确错。
   - 新增包内方法：`getExports()`（读 scope 里 `$.exports`）、`setImportParam(Object)`（写 scope 里 `$.importParam`）、`setImportParams(Map)`（query 参数对象版）、`jsValueFromCards(List<HkCard>)`（子引擎的 setResult 卡片转回调用方 scope 的 JS 数组）、`reloadMyVars()`。
3. **`HkExecutor.java`**：
   - `detail()`：先判 `hiker://page/`（stripLazyMarkers 之后）→ `HkSubpage.expand` 直接返回卡片，跳过 `detail_find_rule` 非空校验；再判含 `@lazyRule=`/`@input=` 的懒加载动作 URL → 抛明确错“懒加载动作链接暂不支持点击执行（第 7 步后续）”；其余保持原样。
   - `playResolved()`：`pagePart`（剥 `#...#` 后）是 `hiker://page/` 时先 `expand`，在展开卡片里找第一条可播地址（http(s) / `video://` / 含 `@lazyRule=`/`@rule=`），找到则 `dispatchResolved` 继续，否则抛明确错“子页面未解析出播放地址：<页名>（展开得 N 张卡片）”；展开本身失败时原错上抛。不动 `.js:` 分支与直链归一。
4. **`HkStore.java`**：
   - 新增：`dataFile(title, name)`（`hiker://files/` 前缀剥掉、去首 `/`、`..` 越界回落 basename，根目录恒为 `data/<标题>/`）、`readDataFile/writeDataFile/deleteDataFile`、`readMyVars/saveMyVars`（`myvar.json`，整文件 JSON 对象读写）、`readItems/saveItems`（`items.json`，字符串 KV）、`readRequireManifest(title)`（读 `config/<标题>.json` 的 require.json 数组）、`resolveLibFile(title, url)`（按清单 `url` 找 `file`，存在即返回；否则按 basename 在 `libsDir()` 里找）。
5. **`HkListHelper.java`**（窄化，只为西瓜影院锚点）：
   - `pickCards` 增加第四遍：前三遍都空时，留 URL 非空但标题为空且 `col_type` 为 `x5_webview_single` 的卡片。
   - `toVods`：标题为空但 URL 非空的卡片不再跳过，回落标题为“网页”（只影响标题为空的卡片；三条验收规则的卡片都有标题，行为不变）。
   - 不碰 `styleFor`（`x5_webview_single` 仍走默认 rect 卡片；内联 WebView 渲染是后续步骤）。

## 4. 验收标准

| # | 标准 | 验证方式 |
|---|---|---|
| 1 | 西瓜影院首页：JS `fetch('hiker://page/home')` 拿到含 `rule` 的 JSON，`eval` 后 `$.require(x5ai/u)` 各自返回可调用导出，`setResult` 出 2 张卡片，列表页显示 1 张（`x5_webview_single`，标题“网页”） | 真机（代码层面：`HkSubpage.pageJson`/`require`/`expand` 链路 + `pickCards` 第四遍 + `toVods` 回落标题已覆盖；`line` 卡片无链接仍丢弃） |
| 2 | 阅动漫首页：preRule（`putMyVar('myCollection')` 等）→ find_rule 跑通，`$.require(dataLoad)` 返回规则数组、`$.require(funcTools)` 返回 `{paramHandle,...}`，`$(...).input/lazyRule` 不抛错，首页出卡片 | 真机（代码层面：require/持久变量/文件 API/`$` 函数化已覆盖；**不承诺全通**：它还要远程拉规则列表 `https://14719.kstore.space/阅动漫.json`，在线与否、子规则行为都在真机上看；推到哪一步如实记） |
| 3 | 三条验收规则（奇优影院♔/童话/臻品fix）列表/详情/搜索/播放零回归 | 真机（代码层面：它们无 `pages`、不用 require、不走子页面分支；`prepare` 改 myVars 加载只影响有持久变量的规则；`pickCards` 第四遍与 `toVods` 回落标题只影响标题为空的卡片） |
| 4 | 未知 `hiker://` 地址不崩、有明确错：`fetch('hiker://page/不存在的页')` 抛“子页面不存在：…”；`require('hiker://page/不存在的页')` 同款；`detail()` 点开未知子页面卡片报明确错；`playResolved` 选集地址是未知子页面时报明确错 | 代码层面确认（异常文案在代码里）；真机抽查 |
| 5 | 子页面展开不影响普通 http(s) 规则：`httpFetch` 的 http 分支、`homeDetailed`/`search`/`detail`/`play` 的 http 路径一行不改行为 | 代码层面：diff 只加分支，不改 http 路径 |
| 6 | 只改手机端：TV/leanback 不动，`BaseLoader` 不动，底栏仍 6 项（`menu_nav.xml` 不加项） | 代码层面：改动文件全在 `app/src/mobile/java/.../api/hk/` 与 `docs/`；`grep` 核对 |

## 5. 回滚

整步一个提交，`git revert <提交号>` 即可。`HkSubpage` 是新文件，revert 后即消失；`HkEngine`/`HkExecutor`/`HkStore`/`HkListHelper` 的改动互相独立，也可按文件单独 revert（`HkListHelper` 的窄化保留只影响西瓜影院锚点，单独 revert 不影响子页面展开本身）。

## 6. 已知缺口与遗留（本步不做）

- `x5_webview_single` 内联 WebView 渲染：西瓜影院那张卡片点开会走到 `detail()`，`detail_find_rule` 为空 → 报“规则缺少 detail_find_rule”，不是 WebView 打开。WebView 解析分支（`webRule://` 同款）是第 7 步长尾。
- 懒加载动作 URL 的点击执行：`$(...).lazyRule/input` 产出的 `@lazyRule=`/`@input=` 字符串目前只是卡片 url，点开报明确错；执行它们（跑序列化函数、`putMyVar` + `refreshPage` 后刷新列表）是后续步骤。
- `refreshPage()` 仍是空操作：阅动漫里 `refreshPage(false)` 调了也不会刷新列表；列表刷新机制是后续步骤。
- `getMyVar`/`putMyVar` 跨引擎的同步靠直写盘 + require 后重载；同一引擎实例内是内存 map，不存在跨实例竞态以外的同步问题（规则执行在后台单线程）。
- require 的 lib 自动注入未做（无证据）：只有 `require(<lib URL>)` 按需解析；若真机发现子规则不 require 就直接用库全局变量，再补自动注入。
- `$.exports` 为函数时跨 scope 调用依赖 Rhino 函数保留定义 scope 的行为；`$.require` 返回函数后在调用方 scope 立即调用是西瓜影院已覆盖的路径，真机验证时重点看。
- `hiker://page/` 子页面在搜索路径（`searchFind` 里 `fetch/require` 子页面）走同一套引擎分支，无需另改；卡片点开的子页面搜索（`hiker://search?s=...`）与收藏/历史导航卡片（`hiker://collection` / `hiker://history`，阅动漫首页有这两张）是后续步骤：点开目前报“规则缺少 detail_find_rule”。
- 阅动漫首页第二张卡片指向 `hiker://page/Main.view?rule=本地资源管理`，但 `Main.view` 不在规则 `pages` 的 12 页里（疑似海阔 App 内置页），点开会报“子页面不存在：Main.view”明确错；`Config` 页在 pages 里，点开可展开。

## 7. 复核修（提交前逐行重读发现，一处小错直接修一轮）

- 库文件 require 前先 `clearExports()`：同一引擎里先后 require 两个库时，第二个库若不设 `$.exports` 会拿到第一个库的旧导出；eval 前重置为 undefined 修掉。
- `stringifyInScope` 用后删 `__hk_tmp` 临时全局变量，不留在 scope 里污染规则环境。
- `HkSubpage` 删未用的 `JsonArray` import；`HkStore.dataFile` 的 `toLowerCase()` 改 `Locale.ROOT`。
