# 海阔小程序第 7 步之七：动作 URL 全套 + webRule:// WebView 解析

> Recovery anchor（任务 `miniapp-action-url`，按仓库 AGENTS.md §5）
>
> - 目标：动作 URL 全套（`input://`/`confirm://`/`select://` 弹框交互；
>   `toast://`/`copy://`/`download://` 接通；`@lazyRule=`/`@input=` 点击执行）
>   + `webRule://` WebView 解析分支；只改手机端，本地提交，不推、不编、
>   不触发构建、不动版本记录空间。
> - 分支/HEAD：`main` / `11a852ff`（第 7 步之六，本地超前 origin 六笔，未推），
>   工作区干净（开始前 `git status` 已核对，无关脏文件：无）。
> - 计划状态：本文档 = 设计评审；实现中。文件/符号见下文“实现清单”。
> - 已验证：本地逐行重读 + `git diff --check`（提交前做）；GitHub 构建
>   （编译检查）未做，本次不推不编，留待推上去之后。
> - 未验证（需要一次构建 + 真机 + 三条验收规则）：全部弹框交互、
>   `webRule://` 真机解析、臻品fix 详情页 tab/排序切换。
> - 未解决的风险：见下文“已知缺口与风险”。
> - 下一个动作：写代码 → 逐行重读 → guard finish 提交 → 汇报父代理。

## 1. 完成句

点一张动作 URL 卡片（列表/搜索/详情三处）不再进空白页或崩溃，而是按
scheme 执行：`toast://` 弹提示、`copy://` 写剪贴板并提示、`download://`
走现有下载通道入队、`input://`/`confirm://`/`select://` 弹框交互、用户
取消即中断不崩；`@lazyRule=`/`@input=` 卡片点开执行懒 JS 再按结果分发
（臻品fix 的线路 tab 与排序切换就此打通）；懒加载解析出 `webRule://`
时用隐藏 WebView 加载页面、注入取数并把结果交回解析链，加载失败/超时
有明确错。只改手机端，TV/leanback 不动，`BaseLoader` 不动，底栏仍 6 项。

## 2. 设计研究（AGENTS.md §7）

### 2.1 证据表

| # | 来源 | 等级 | 支持的主张 | 适用性/注意 | 对决策的影响 |
|---|---|---|---|---|---|
| E1 | 逆向报告 `~/workspace/user/files/__________8_3azg.md` §2.5 L216、§7.1 L452、§7.10 L5157 | A（原文） | 动作 URL 只有 `isActionUrl()` 谓词可读：`toast://`/`copy://`/`input://`/`confirm://`/`select://`/`download://`/`hiker://empty`；执行分支本身反编译失败，无源码 | 语义只能按 scheme 名 + 下游用法推断，见 E2–E4；执行器位置不在报告里 | 本步的执行器是新建的 `HkActionHandler`，不声称复刻上游 |
| E2 | 同报告 §7.10 `f22.b()`（L5399 起）+ `f22.v()`（L5754 起） | A（原文） | 懒分发：`{urls}` JSON → `s()`；`webRule://` → `v()`；`pics://` → `r()`；`video://` → `w()`。`v()` 格式 `webRule://<url>@<js>`，无 `@` 记 `webRule invalid (no @)` 返回 false；拼 `__wrq/__wrcheck` 轮询 JS（250ms 一次，最多 120 次 ≈30s），经 `fy_bridge_app.finishParse(u,'webkit',uuid)` 回传，`CountDownLatch.await(30000)` 等；空结果记 `webRule parse empty` 返回 false；嵌套 `webRule://` 结果返回 false；`{urls}` 结果走 `s()` | `v()` 是 `webRule://` 唯一可信的完整实现，直接照抄结构 | `HkWebRule.resolve()` 按 `v()` 逐字移植：`@` 切分、转义规则、120 次轮询、30 秒 latch、`fy_bridge_app` 桥名 |
| E3 | 同报告官方文档节选（L172–L374，2026-11-15 已取） | A（官方） | 二级解析 `@lazyRule=`（只能最后一层）、`#isVideo=true#` 等嗅探标记、`{urls,names,headers,subtitle,danmu,lyric}` 多线路 JSON；长按自定义：`extra.longClick[].js` 可返回 `toast://点击了`（L360）——`toast://<文本>` 即弹提示的约定 | `toast://` 后接提示文本是唯一有书面佐证的动作语义 | `toast://` 实现 = 去前缀后原样 toast |
| E4 | 三条验收规则原文（`~/workspace/miniapp/test-rules/`，本轮逐字读过） | A | 臻品fix `detail_find_rule` 里 tab 卡片 URL：`hiker://empty@lazyRule=.js:putVar('<var>','<i>');refreshPage();'toast://切换成功！'`；排序卡片 URL：`#noLoading#@lazyRule=.js:…;'toast://切换排序成功';'#noHistory#hiker://empty'`；无线路/无集数时卡片 URL 就是 `toast://当前线路暂无线路,请检查线路是否正确` / `toast://当前线路暂无集数,请检查选集是否正确`。三条规则里没有 `input://`/`confirm://`/`select://`/`download://`/`webRule://` 实例 | 臻品fix 的 tab/排序是本步唯一可真机验证的动作链路；其余四种 scheme 无真机样本 | tab/排序必须端到端打通（执行 JS → toast → 刷新详情）；其余 scheme 按约定实现、写清“无样本、待规则验证” |
| E5 | 同报告 §7.3 子页面 + `docs/miniapp-subpage.md` | A（本地） | `$(...).input(fn)` 只产字符串 `<sel>@input=<$.toString(fn,args)>`，不执行；点击执行是后续步骤。阅动漫实测：`.input(() => { putMyVar('typeShow', '@' + input); refreshPage(false); return 'hiker://empty' })`——fn 的 `input` 形参与 scope 全局 `input` 同名，执行时 `input` = 用户输入（与 `HkEngine.evalExpression` 的 input 进 scope 设计一致） | `@input=` 执行 = 先弹输入框，再 `evalExpression(序列化表达式, 用户输入)` | `@input=` 不拆序列化函数：直接 eval 整串，`input` 变量即用户输入 |
| E6 | 现有实现（本轮原文重读） | A（本地代码） | `HkListHelper.isActionUrl`（直译谓词）；`pickCards` 第二遍滤动作 URL、第三遍又放回（动作卡片可达列表）；`HkDetailHelper.split`：动作 URL 的线路头被当空线路丢掉（臻品fix 的 tab/排序卡片今天不可见、不可点）；`HkDetailHelper.isEpisodeCard` 排除动作 URL；`HkExecutor.detail()` 遇 `@lazyRule=`/`@input=` 抛“懒加载动作链接暂不支持点击执行（第 7 步后续）”；`HkExecutor.dispatchResolved` 遇 `pics://`/`webRule://` 只记日志回空结果；`HkEngine.Bridge.confirm/select` 是抛明确错的桩（“第 7 步未实现”），`toast` 只是记日志；`VideoActivity.resolveHkPlayerContent` 是 `HkExecutor.play()` 唯一调用方，失败只弹通用 `hk_play_no_url` | 六处断点就是本步的接线清单 | 接线点全部落在已有断点上，不新开链路 |
| E7 | 现有实现：`ActionCardHelper`（main 源集）+ `dialog_action_input.xml` + `WebHtvAlertDialogBuilder` | A（本地代码） | 项目已有输入框弹框范式：`dialog_action_input.xml`（`@+id/action_input` 的 EditText）+ `WebHtvAlertDialogBuilder` + 确认时空输入弹“内容不能为空”且不关框；已有 `GameWebActivity`（内置 WebView 打开页）与 `CustomWebView`（嗅探解析 WebView 弹框） | 输入框直接复用同一套布局与 Builder，不另起 UI；`webRule://` 是隐藏取数、不是给用户看网页，不用 `GameWebActivity` | 输入框复用 E7；WebView 只复用 `WebViewUtil.configureBase` 的基础配置，不复用嗅探弹框 |
| E8 | 现有实现：下载通道 `VideoDownloadManager.enqueue` + `SiteApi.playerContentIsolated` + 下载列表点播 | A（本地代码） | `enqueue(剧名, siteKey, flag, 集名, 集 URL, 海报)` 入队；worker 用 `playerContentIsolated(siteKey, …)` 解析出直链再下；下载列表点播用 `file:// + task.filePath`，不经过 siteKey；`VodConfig.getSite(未知 key)` 返回空 `Site`（type 非 3/4），`SiteApi.playerContent` 的 else 分支把 URL 原样当直链返回（`setParse(0/1)`） | `download://` 后是直链文件 URL：用空 siteKey 入队可走直链透传分支，不经过蜘蛛解析；siteKey 只影响 worker 解析一步，不影响列表点播与重试 | `download://` 用空 siteKey 入队（见 §3.3）；hk 站源本身仍走蜘蛛链，`download://` 不经过它 |
| E9 | 现有实现：`CustomWebView` + `WebViewUtil.configureBase` + `CatWebActivity` 注释 | A（本地代码） | `configureBase(webView, role)`：开 JS + DOM 存储 + 混合内容；`CatWebActivity` 注释：`addJavascriptInterface` 一旦挂上就等于把 Java 方法交给页面脚本 | `HkWebRule` 复用 `configureBase`；桥只暴露 `finishParse` 一个方法，且只加载规则指定的页面 | 桥面最小化写进 §3.4 与已知缺口 |

### 2.2 方案比较

| 方案 | 内容 | 优点 | 缺点 / 风险 | 结论 |
|---|---|---|---|---|
| A. 不做 | 保持六处断点（E6）：动作卡片点开进详情错误态或弹“暂不支持”，`webRule://`/`pics://` 回空结果 | 零风险 | 臻品fix 的 tab/排序永远点不了；动作卡片点开是空白页/报错，正好踩中本步验收的反面；用户已定“后边的工程一次做完” | 否决 |
| B. 照搬上游 | 全量复刻魔影动作执行器 + `v12` + WebView 桥（含 `fetchCodeByWebView`、`refreshX5WebView`、X5 内核） | 与魔影行为最接近 | 执行器本体反编译失败，无源码可抄，只能按 helper 拼；X5 内核项目没有；`f22.v` 以外的动作执行分支无任何可读文本，fidelity 无法自证 | 否决（`v()` 的轮询结构照抄，见 E2；其余按约定重写） |
| **C. 窄化适配（推荐）** | 新建 `HkActionHandler`（卡片点击分发：裸 scheme + `@lazyRule=`/`@input=` 执行 + 结果分发）+ `HkDialogs`（弹框：同步 latch 版供 Rhino 桥、异步回调版供点击）+ `HkWebRule`（隐藏 WebView，照抄 `v()` 轮询）；`HkEngine` 只把 `confirm/select` 桩换成真实现（经 `DialogHost`）、`toast` 接 `Notify`、`putVar` 变量加跨次快照；详情页 `split` 把动作卡片收进新增的 `Detail.actions` 并渲染成可点 chip；`play()` 分发里 `webRule://` 走 `HkWebRule`、`toast://`/`pics://`/`copy://`/`download://` 走同一套裸 scheme 处理；`download://` 用空 siteKey 入队走直链透传 | 改动全部落在已有断点上（E6）；臻品fix tab/排序端到端可验；无样本的四种 scheme 有明确约定与降级；`webRule://` 的轮询结构与上游逐字对应；回滚 = 一个提交 revert | `input://`/`confirm://`/`select://` 的裸 URL 文本格式（标题/选项分隔符）无上游样本，是约定不是复刻；`vars` 只在详情页内跨次传递（列表/搜索页不保留）；`HkSubpage.expand` 路径的引擎拿不到 `DialogHost`（见缺口） | **采用** |
| D. 只做弹框 + webRule，不动详情页 split | C 减去 `Detail.actions`：tab/排序卡片继续被丢掉，只接列表/搜索页的裸动作卡片 | 改动更小 | 臻品fix 里唯一的 `toast://` 样本不可达，本步验收里“`toast://` 行为正确”真机无法验证，等于只做了半步 | 否决 |

### 推荐

采用方案 C。理由：六处断点（E6）已经把接线位置标好了，本步只是把它们逐一接通；
臻品fix 的 tab/排序是唯一有样本的动作链路，必须端到端打通，否则 `toast://`
无从验证；`webRule://` 有 `v()` 全文可抄，照抄是最稳的路；其余无样本的
scheme 用最小约定实现并写清，宁可报明确错也不静默吞掉。

## 3. 推荐设计（方案 C）

### 3.1 调用链

```
列表/搜索卡片点击（Vod.id = 卡片 URL）
  → HkListFragment/HkSearchFragment.onItemClick：先 HkActionHandler.handle(host, card)
    true = 已接管（弹框/执行/入队已开始），false = 普通 URL，按原路进详情页
详情页动作行点击
  → HkDetailAdapter.Listener.onActionClick(card) → HkDetailFragment → 同一个 handle()
详情页选集点击（第 5 步链路不变）
  → HkPlayer.play → VideoActivity.getPlayer hk 分支 → resolveHkPlayerContent
    → HkExecutor.play(rule, url, activity, dialogHost) → dispatchResolved
      → webRule:// → HkWebRule.resolve；toast:// → PlayOutcome.notice；
        pics:// → PlayOutcome.notice（不支持）；copy:// → 剪贴板 + notice；
        download:// → 入队 + notice
```

### 3.2 `HkActionHandler`（新建，`api/hk/`）

`handle(Host, HkCard)` 返回 `boolean`（true = 已接管，调用方不再进详情页）。
`Host` 接口由三个 Fragment 实现：

```java
interface Host {
    Activity activity();                 // 弹框 / WebView 用（null 则只做无需 UI 的动作）
    HkRule rule();
    String pageUrl();                    // 懒执行时 MY_URL：详情页 = 详情 URL；列表/搜索 = 规则 url
    Map<String, Object> vars();          // 当前 vars 快照（详情页持有，列表/搜索页返回空 map）
    void updateVars(Map<String, Object> vars);
    void reloadPage();                   // refreshPage() 语义：重跑当前页
    void openDetail(String url, String title, String pic, String desc);
}
```

分发顺序（先判 `@lazyRule=`/`@input=`，再判裸 scheme——臻品fix 的 tab 卡片
是 `hiker://empty@lazyRule=…`，`@` 后半段才是动作）：

1. **含 `@lazyRule=` 或 `@input=`**：在第一个标记处切分（`HkExecutor` 的
   `unescapeLazy`/`stripNowSuffix` 改为包内可见复用，不另写一份转义）。
   `@input=` 先弹输入框（`HkDialogs.inputAsync`，标题 = 卡片标题，hint 留空；
   空输入弹“内容不能为空”且不关框，与 `ActionCardHelper` 同款；取消弹“已取消”，
   中断链路）。拿到输入（或 `@lazyRule=` 无需输入）后后台线程：
   `new HkEngine` → `setDialogHost(host 桥接的 DialogHost)` →
   `prepare(rule, host.pageUrl(), 1, "action", "", host.vars())` →
   `evalExpression(js, input)`（`input` = page 部分经 `HkParser.resolveUrl`
   补全后的地址；`@input=` 时 `input` = 用户输入——与 E5 的语义一致，
   fn 形参 `input` 与 scope 变量同名，eval 整串即可，不拆序列化函数）。
   取 `getVarsSnapshot()` + `wasRefreshRequested()` → 主线程按 §3.5 分发结果。
2. **裸 `toast://<文本>`**：去前缀原样 `Notify.show`（E3 的约定；空文本不弹）。
3. **裸 `copy://<文本>`**：`ClipboardManager.setPrimaryClip` + `Notify`“已复制”
   （剪贴板写失败时 toast 失败原因，不崩）。
4. **裸 `download://<直链>`**：去前缀，trim 后必须以 `http://`/`https://`
   开头，否则 toast“下载地址无效”；否则 `VideoDownloadManager.enqueue`
   （剧名 = 规则标题，siteKey = `""`，flag = `""`，集名 = URL 文件名或卡片标题，
   海报 = 卡片 pic）+ toast“已加入下载”/“已在下载列表中”（见 §3.3）。
5. **裸 `input://<提示>`**：`HkDialogs.inputAsync`（标题 = 卡片标题或提示文本，
   hint = 提示文本）；确认后无后续链路可跑（URL 里没有 continuation），toast
   显示输入内容（截断 100 字）；取消弹“已取消”。（格式是约定，见 §5 缺口 2。）
6. **裸 `confirm://<文案>`**：`HkDialogs.confirmAsync`（message = 文案或卡片标题）；
   确认 toast“已确认”，取消 toast“已取消”。
7. **裸 `select://<标题>@<选项1>@<选项2>…`**：`@` 切分（无 `@` 时按 `,` 切分，
   标题回落卡片标题）；`HkDialogs.selectAsync` 弹选项列表；选中 toast
   “已选择：<项>”，取消 toast“已取消”。（格式是约定，见 §5 缺口 2。）
8. **裸 `hiker://empty`**：静默 no-op（占位地址，无动作），返回 true。
9. 其余（http(s)/`hiker://page/`/普通 URL）：返回 false，调用方走原路。

### 3.3 `download://` 与空 siteKey（E8）

`download://` 后是直链文件 URL，不需要蜘蛛解析。`enqueue` 的 worker 只在
解析一步用 siteKey：`playerContentIsolated(siteKey, …)`——传 hk 站 key 会进
type 3 蜘蛛链（hk 没有蜘蛛）而失败；传空 key 时 `VodConfig.getSite("")`
返回空 `Site`，`SiteApi.playerContent` 走 else 分支把 URL 原样当直链返回。
下载列表点播与重试都不用 siteKey（点播用 `file:// + task.filePath`，重试
原样重传 siteKey），所以空 siteKey 不影响后续环节。来源记录在
`dramaName`（规则标题）与 `episodeName` 里。非视频直链（如 .zip/.apk）：
worker 的 `Sniffer.isVideoFormat` 为假时会走一次解析（与普通直链下载同款
行为），可能失败——报任务失败，不崩。

### 3.4 `HkWebRule`（新建，`api/hk/`）

`resolve(Context, String webRule)`，必须在后台线程调（主线程调直接抛错，
防 latch 卡死主线程）。流程照抄 `f22.v()`（E2）：

1. 去 `webRule://` 前缀（大小写不敏感），在第一个 `@` 处切分：
   `pageUrl` / `js`；无 `@` 抛 `HkException("webRule 格式错误（缺少 @）")`；
   任一半为空抛同款明确错；`pageUrl` 非 http(s) 抛明确错（不走 WebView）。
2. 主线程建隐藏 WebView（不挂到任何父布局）：`WebViewUtil.configureBase`
   + 关文件访问（`setAllowFileAccess(false)` / `setAllowContentAccess(false)`，
   远程页不需要本地文件）+ `addJavascriptInterface(bridge, "fy_bridge_app")`
   （桥只暴露 `finishParse(String, String, String)` 一个方法，见 E9；
   uuid 校验防过期回调）。
3. `WebViewClient.onPageFinished` 后 `evaluateJavascript` 注入轮询脚本
   （与 `v()` 逐字对应结构，`__wrq` 里 `eval('<转义后 js>')`，转义规则：
   `\n`→`\\n`、`\r` 丢弃、`'`→`\\'`、`\`→`\\\\`；`__wrcheck` 每 250ms
   一次，最多 120 次，超时调 `finishParse('')`）。
   `onReceivedError` 记下错误并 `finishParse('')`（不悬着等 30 秒）。
4. 后台线程 `CountDownLatch.await(30000)`；拿到结果后主线程销毁 WebView
   （`stopLoading()` + `destroy()`，finally 里一定执行）。
5. 结果为空抛 `HkException("webRule 未解析出结果：<pageUrl>（页面加载失败或规则 JS 无返回值）")`
   或超时/加载失败的对应文案（字符串见 §3.8）；非空返回。
   嵌套 `webRule://` 结果由调用方（`dispatchResolved`）按上游口径当失败处理。

风险（写进缺口）：未挂到窗口的 WebView 在个别机型上 `setTimeout` 可能被节流，
导致 30 秒轮询无果而报超时；若真机出现，改走 Java 侧 `Handler` 每 250ms
主动 `evaluateJavascript("__wrq()")` 轮询，不动接口。

### 3.5 懒执行结果分发（`HkActionHandler` 内，主线程）

| 结果 | 处理 |
|---|---|
| 开头的 `#…#` 标记段 | 先剥掉再分发（臻品fix 排序卡片末值 `#noHistory#hiker://empty` → `hiker://empty`；剥完为空则按“空”行处理） |
| 空 | `refreshRequested` 或 vars 变了 → `host.reloadPage()`，否则什么都不做 |
| `toast://…` | toast 后半段；`refreshRequested` 时再 `reloadPage()`（臻品fix tab：`refreshPage()` 已调 + 末值是 toast，两个都命中） |
| `hiker://empty` | `refreshRequested` 时 `reloadPage()`，否则 no-op |
| http(s) / `hiker://page/…` | `host.openDetail(url, 卡片标题, pic, desc)`（`hiker://page/` 走 `HkExecutor.detail` 的子页面展开分支，已有） |
| `webRule://…` | 后台线程 `HkWebRule.resolve` 后把结果再按本表分发一次（嵌套 `webRule://` 直接报“webRule 未解析出结果”，不再递归） |
| `{urls,…}` JSON | 取 `urls[0]`，http(s) 则 `openDetail`，否则 toast“未解析出结果” |
| `pics://…` | toast“图集浏览暂不支持（pics://）”（本步不做图集浏览，见任务要求） |
| `copy://` / `download://` | 按 §3.2 第 3/4 条执行（与裸 scheme 同款处理） |
| 其他字符串 | 记日志，不 toast、不跳转（规则返回了无法识别的结果，不是地址） |

### 3.6 `HkDialogs`（新建，`ui/hk/`）

弹框只分两层：核心（异步回调，供点击流程，主线程调）+ 同步 latch 包装
（供 Rhino 桥，后台线程调）。全部用 `WebHtvAlertDialogBuilder`（项目现有
对话框样式，不透明背景，不另起 UI）。

- `inputAsync(Activity, title, hint, preset, Consumer<String> onResult)`：
  复用 `R.layout.dialog_action_input`（`@+id/action_input`）；确认时空输入
  toast“内容不能为空”且不关框（与 `ActionCardHelper` 同款）；取消/返回键/
  点框外 → `onResult(null)`。`onDismiss` 兜底调一次 `onResult(null)`（防漏调）。
- `confirmAsync(Activity, title, message, Consumer<Boolean> onResult)`：
  确认/取消按钮；取消路径一律 `onResult(false)`。
- `selectAsync(Activity, title, List<String> options, Consumer<String> onResult)`：
  `setItems` 列表；点项 `onResult(选项)`，取消 `onResult(null)`。
- 同步版 `input/confirm/select(Activity, …)`：主线程调直接回取消默认值
  （不能在主线程 latch 等自己）；否则 `CountDownLatch` 等异步回调；
  Activity 已 finish/destroyed 时直接回取消默认值。
- 全部回调都在主线程执行；同步版在后台线程拿到结果后返回。

### 3.7 `HkEngine` 改动（只改桥与快照，不动解析器）

- 新增 `DialogHost` 接口：`boolean confirm(String title, String message)` /
  `String select(String title, List<String> options)` / `String input(String title, String hint, String preset)`，
  返回 null/false = 用户取消。`setDialogHost` / `getDialogHost`（实例字段，
  不用 static 全局——多引擎并发时各归各）。
- `Bridge.confirm/select`：有 host 时调 host（参数解析：String 当 message；
  `NativeObject` 取 `title` + `message|msg|content|text`，`select` 另取
  `options|list|items` 数组；`NativeArray` 当选项列表；无选项抛明确错）；
  无 host 时保持原来的抛错文案不变。取消时 `confirm` 回 `false`，
  `select` 回 `null`（JS 侧得 `null`，不断链）。
- `Bridge.toast`：有 host 时 `App.post(() -> Notify.show(msg))`（真弹提示），
  无 host 时保持记日志。
- `Bridge.refreshPage`：除记日志外再置 `engine.refreshRequested = true`
  （只给点击执行路径读，别的路径不读它，行为不变）。
- `prepare` 加重载 `prepare(rule, pageUrl, page, pageType, resCode, Map<String,Object> initialVars)`：
  原签名委托它（initialVars = null）；initialVars 非空时预填 `vars`。
  新增 `getVarsSnapshot()`（返回 `new HashMap<>(vars)`）与
  `wasRefreshRequested()`。
- `HkSubpage.require` 里新建的子引擎从父引擎抄 `DialogHost`
  （`HkEngine.require` 调 `HkSubpage.require(this, …)`，父引擎在手）；
  `expand` 路径没有父引擎，保持无 host（缺口，见 §5）。

### 3.8 `HkExecutor` 改动

- `play(rule, url)` 委托 `play(rule, url, null, null)`；新增
  `play(rule, url, Context, HkEngine.DialogHost)`，context/host 一路传进
  `playResolved` / `dispatchResolved`（私有方法加参）。
- `PlayOutcome` 加 `public String notice = ""`（有 notice 且 url 为空 =
  “解析完了但不是播放地址，提示用户”）。
- `dispatchResolved` 新增分支（在原有 `pics://`/`webRule://` 回空结果的位置）：
  `webRule://` → context 为空抛“webRule 需要页面上下文”；否则
  `HkWebRule.resolve`，空结果回空 outcome（记日志），嵌套 `webRule://`
  回空 outcome（与 `v()` 返回 false 同款），`{urls}` 结果走现有
  `outcomeFromMultiLine`，其余走 `normalizeDirect`；
  `toast://` → notice = 后半段，回空 url 的 outcome；
  `pics://` → notice = “图集浏览暂不支持（pics://）”；
  `copy://` → 写剪贴板（context 为空时抛明确错）+ notice = “已复制”；
  `download://` → §3.2 第 4 条入队 + notice = “已加入下载”/“已在下载列表中”。
- `detail(rule, url)` 委托 `detail(rule, url, null, null)`；新增
  `detail(rule, url, Map<String,Object> initialVars, HkEngine.DialogHost host)`，
  initialVars 进 `evalDetailRule` → `prepare` 重载，host 进每个新建引擎。
  `@lazyRule=`/`@input=` 的抛错保留（点击已由 `HkActionHandler` 拦截，
  这里是防直接调用的安全网）。
- `unescapeLazy` / `stripNowSuffix` 由 private 改为包内可见 static，
  供 `HkActionHandler` 复用（同一包，不新增 API）。

### 3.9 详情页动作行（`HkDetailHelper` + `HkDetailAdapter` + `HkDetailFragment`）

- `HkDetailHelper.Detail` 加 `public List<HkCard> actions`（按原顺序）。
  `split` 循环改动：线路头分支里，URL 可动作（动作 URL 或含
  `@lazyRule=`/`@input=`）的进 `actions`，**同时仍按原样开线路**（行结构与
  行标题不变，空线路照常丢掉——臻品fix 的选集仍挂在排序卡片开的那条线上）；
  URL 为空的纯标签头保持开线路（现有行为）；非线路头、非选集、但 URL 可动作
  的卡片也进 `actions`（如 col_type 不在 text 族的动作卡片）。其余逻辑一行不动。
- `HkDetailAdapter`：`TYPE_ACTION = 3`，复用 `adapter_episode_grid.xml`
  的 chip 渲染 actions（全行 chip，与选集同款样式）；`Listener` 加
  `onActionClick(HkCard)`；`setDetail` 铺平顺序：信息头 → actions → 各线路。
  Span：与选集同款 1 列（Fragment 的 `SpanSizeLookup` 里 action 与选集同等处理）。
- `HkDetailFragment`：实现 `HkActionHandler.Host`（`pageUrl()` = 详情 URL
  arg，`vars()` = `mVars`，`updateVars` 写回 `mVars`，`reloadPage()` =
  `loadDetail()`，`openDetail()` = `HkDetailActivity.open`）；`loadDetail`
  调 `detail(rule, url, mVars, host)`；`onActionClick` 调
  `HkActionHandler.handle(host, card)`。
- `HkListFragment` / `HkSearchFragment`：`onItemClick` 先把 Vod 转回
  `HkCard(标题, id, pic, remarks)` 调 `handle(host, card)`，true 则返回，
  false 走原路进详情；Host 实现里 `pageUrl()` = 规则 url，vars 不保留
  （`vars()` 返回空 map，`updateVars` 空操作——列表/搜索页没有 tab 状态要留），
  `reloadPage()` = `loadPage(1, false)`。
- `VideoActivity.resolveHkPlayerContent`：`HkExecutor.play(rule, episodeUrl, this, host)`，
  host 用 `HkDialogs` 桥接当前 Activity；回主线程后：有地址照常 `setPlayer`；
  无地址但 `outcome.notice` 非空 → `Notify.show(notice)`（代替通用
  `hk_play_no_url`）；无地址无 notice → 通用提示不变。

### 3.10 字符串（`app/src/main/res/values/lab_strings.xml`，只加不改）

`hk_action_copied`（已复制）、`hk_action_copy_failed`（复制失败）、
`hk_action_download_queued`（已加入下载）、
`hk_action_download_exists`（已在下载列表中）、`hk_action_download_bad_url`
（下载地址无效）、`hk_action_cancelled`（已取消）、`hk_action_confirmed`
（已确认）、`hk_action_chosen`（已选择：%1$s）、`hk_action_input_empty`
（内容不能为空）、`hk_action_input_title`（请输入）、
`hk_action_confirm_title`（请确认）、
`hk_action_input_done`（已输入：%1$s）、`hk_action_no_result`（未解析出结果）、
`hk_action_select_title`（请选择）、
`hk_action_pics_unsupported`（图集浏览暂不支持（pics://））、
`hk_webrule_timeout`（WebView 解析超时（30 秒））、
`hk_webrule_load_failed`（页面加载失败：%1$s）、
`hk_webrule_empty`（WebView 未解析出结果）、
`hk_webrule_bad_format`（webRule 格式错误（缺少 @））。

### 3.11 不改的

- `BaseLoader`、蜘蛛链、播放器本身、`menu_nav.xml`（仍 6 项）、TV/leanback、
  `HkPlayer`、首页内嵌、`HkSubpage.expand` 的引擎创建（无父引擎，见 §5 缺口）。
- `pics://` 图集浏览仍不支持（报明确错，见 §3.5/§3.8）；
  多线路 JSON 的选线路 UI、`subtitle/danmu/lyric`（第 7 步长尾，不在本步）。
- `hiker://empty` 仍是静默 no-op；`#noHistory#` / `#noRecordHistory#` /
  `#noRefresh#` 等 `#…#` 标记仍按现有逻辑剥离，不新接语义。

## 4. 验收标准

### 4.1 代码层面可确认（提交前逐行重读 + `git diff --check`）

- [x] 三处点击（列表/搜索/详情动作行）都先过 `HkActionHandler.handle`，
  返回 false 才走原路；普通 http(s) 卡片行为一行未动。
- [x] `HkWebRule` 的轮询脚本结构与 E2 的 `v()` 对得上：`@` 切分、转义四则、
  250ms/120 次、`fy_bridge_app.finishParse`、30 秒 latch、finally 销毁 WebView。
- [x] `download://` 入队用空 siteKey + 直链透传（E8），不经过 hk 蜘蛛链。
- [x] 弹框全部走 `WebHtvAlertDialogBuilder` + 现有 `dialog_action_input` 布局；
  每个弹框的取消路径（按钮/返回键/点框外/Activity 销毁）都回取消默认值，不悬挂 latch。
- [x] `HkEngine` 的 `confirm/select` 无 host 时保持原抛错；`refreshRequested`
  只被点击路径读取；`prepare` 原签名行为不变。
- [x] 返回类型复核：`Json.safeListElement` 返回 List 不赋给 JsonArray；
  无泛型原始类型（`Consumer<String>` 等写全类型参数）；
  dialog 根布局/窗口背景不透明（复用现有布局与 Builder，不新写布局）。
- [x] `menu_nav.xml` 仍 6 项；`BaseLoader` 未动；TV/leanback 零改动。

### 4.2 只能真机验（推上去编出包后，用户按三条规则测）

- [ ] 臻品fix 详情页：出现线路 tab chips（带 🚩 当前线路标记）与“选集〰️(正序/逆序)”
  动作行；点 tab → toast“切换成功！”且列表切到对应线路；
  点排序 → 选集顺序翻转（注意：排序卡片懒 JS 的末值是
  `#noHistory#hiker://empty`，中间的 `'toast://切换排序成功'` 按 JS 求值语义
  不触发——与上游 `u()` 只取末值一致，翻转 + 刷新即成功，不以 toast 为准）；
  无线路/无集数时点对应行 toast 对应提示，不崩。
- [ ] 臻品fix / 奇优 / 童话：列表/详情/搜索/播放零回归（分类切换、翻页、
  详情信息、选集顺序、点播出声画照旧）。
- [ ] `input://` / `confirm://` / `select://`：三条规则里无样本，需另找带这类
  卡片的规则验证——弹框能出、能确认/取消、取消不崩（代码层面已确认弹框链路，
  真机样本待补）。
- [ ] `copy://` / `download://`：同上，无样本；按约定实现，待带这类卡片的规则验证。
- [ ] `webRule://`：三条规则里无样本，需带 `webRule://` 的规则验证——能出结果、
  加载失败/超时有明确错（代码层面已确认轮询与超时结构，真机样本待补）。
- [ ] 动作 URL 不会把用户带进空白页或崩溃：点任意动作卡片（列表第三遍筛出的、
  详情动作行的）都不进详情错误态。

## 5. 回滚路径

- 整步回滚：`git revert <本提交>`（一个原子提交，recovery tag 见提交信息）。
  新文件（任务文档 + `HkActionHandler` / `HkWebRule` / `HkDialogs`）随 revert
  消失；三处点击恢复成直接进详情；`play()` 的 webRule/notice 分支回到回空结果；
  `confirm/select` 回到抛错桩；臻品fix 的 tab/排序卡片回到被丢掉的状态
  （第 6 步已验证的状态）。
- 局部回滚：只回滚详情页动作行（`HkDetailHelper.split` 恢复成丢掉动作头 +
  `HkDetailAdapter` 去掉 `TYPE_ACTION`）：列表/搜索页的动作处理与 `webRule://`
  不受影响。

## 6. 已知缺口与风险

1. **`webRule://` 无真机样本**：三条验收规则都不含 `webRule://`；轮询结构与
   `v()` 逐字对应，但未挂窗口的 WebView 在个别机型上 `setTimeout` 可能被节流，
   届时按 §3.4 末尾改走 Java 侧轮询。
2. **裸 `input://`/`confirm://`/`select://` 的文本格式是约定**：上游无样本；
   `select://` 用 `@` 分标题与选项（无 `@` 按 `,` 切）；裸 `input://` 确认后
   没有 continuation 可跑，只 toast 回显输入。若拿到真实规则样本对不上，
   按样本改解析，不动弹框层。
3. **`vars` 只在详情页内跨次传递**：列表/搜索页的懒执行不保留 `putVar` 结果；
   详情页 `mVars` 只活在 Fragment 实例里，退出详情页即丢（与页面变量语义一致，
   不是持久化）。
4. **`HkSubpage.expand` 的引擎拿不到 `DialogHost`**：`expand` 没有父引擎，
   其中跑的规则 JS 调 `confirm/select` 仍抛原错；`require` 路径已接通。
   若阅动漫的子页面里出现 `confirm/select`，需把 host 线程进 `expand` 再补。
5. **`download://` 只接直链文件 URL**：非视频直链会走 worker 的一次解析，
   可能失败（与普通直链下载同款行为）；`download://` 后带 `@lazyRule=` 等
   懒标记的暂不支持（当无效地址报）。
6. **规则安全**：同第 1 步——`webRule://` 让规则指定任意页面进 WebView 跑 JS，
   桥只暴露 `finishParse`，但页面本身仍能联网；只导可信规则。
7. **`HkWebRule` 的 `evaluateJavascript` 注入点**：只在 `onPageFinished` 注入一次；
   SPA 内跳转/二次导航后不重注，结果可能为空而报“未解析出结果”。
8. **点击执行的懒结果不递归**：官方文档“`@lazyRule=` 不能嵌套使用，只能用在
   最后一级解析规则”——懒结果里再含 `@lazyRule=`/`@input=` 时不重跑，
   只记日志（播放链 `play()` 的递归是 `f22.b` 的原有行为，不动）。

## 7. 实现清单

- [x] 设计评审（本文档）
- [x] `HkActionHandler`（裸 scheme + `@lazyRule=`/`@input=` 执行 + 结果分发）
- [x] `HkDialogs`（异步弹框核心 + 同步 latch 包装）
- [x] `HkWebRule`（隐藏 WebView + `fy_bridge_app` + 30 秒 latch）
- [x] `HkEngine`：`DialogHost` + `confirm/select` 真实现 + `toast` 接 `Notify` +
  `refreshRequested` + `prepare` initialVars 重载 + `getVarsSnapshot`
- [x] `HkExecutor`：`play/detail` 新重载 + `dispatchResolved` 新分支 +
  `PlayOutcome.notice` + `unescapeLazy/stripNowSuffix` 包内可见
- [x] `HkSubpage.require`：子引擎抄父引擎 `DialogHost`
- [x] `HkDetailHelper.split`：`Detail.actions` 收集动作卡片
- [x] `HkDetailAdapter`：`TYPE_ACTION` chip + `onActionClick`
- [x] `HkDetailFragment`：Host 实现 + `mVars` + `loadDetail` 传 vars
- [x] `HkListFragment` / `HkSearchFragment`：点击拦截 + Host 实现
- [x] `VideoActivity.resolveHkPlayerContent`：传 context/host + notice 展示
- [x] `lab_strings.xml`：§3.10 的新字符串
- [x] 逐行重读 + `git diff --check` + guard finish（本地提交，不推不编）

## 8. 提交前核对记录（2026-11-15）

- `git diff --check` 干净；工作区只有本步 scope 内的 10 改 + 3 新代码文件 + 本文档。
- 全量 diff 逐行重读一遍（`HkExecutor` / `HkEngine` / `HkDetailHelper` /
  `HkSubpage` / `HkDetailAdapter` / 三个 Fragment / `VideoActivity` /
  `lab_strings.xml` + 三个新文件全文）。
- 核对中发现并修好 1 处编译错：`HkActionHandler.dispatchLazyResult` 的
  `webRule://` 分支里，`Thread` lambda 捕获了被 `stripLeadingHashMarkers`
  重赋过的 `result`（不是 effectively final，javac 报错）→ 先抄
  `final String webRuleUrl = result` 再捕获。另修 2 处过期注释
  （`HkExecutor.detail`、`HkEngine` BOOTSTRAP 里的“点击执行是后续步骤”）。
- 无未用 import、无原始泛型；`JsonArray` 经 `getAsJsonArray` 取（未用
  `Json.safeListElement`）；`VideoDownloadManager.enqueue` 签名 6 参已核对；
  §4.1 全部代码层面确认；§4.2 六项留待真机。
