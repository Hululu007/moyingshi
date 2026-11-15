# 小程序两处修复：切站弹窗去 hk + hiker://empty 首页不抓页

- 任务：miniapp-hk-fixes（quick-fix lane；§7 设计门豁免：已有既定设计下的窄化修复）
- 触发：用户 2026-11-15 17:15 真机反馈（刚装 v5.6.0-beta-202611151625）
- HEAD：78a0ff45（main，与 origin 一致）；工作区干净后开工
- 完成句：切站弹窗不再显示海阔小程序（行与“小程序”分组 chip 随之消失），hiker://empty 等非 http(s) 首页地址不再走 OkHttp 抓页、JS 规则直接在空页上跑、选择器规则返回空列表加明确诊断；详情页同款 guard 一并修；三条验收规则零回归。

## 问题 A：切站弹窗里不该出现海阔小程序

- 用户原话：“海阔没数据，另外海阔小程序别出现在这个列表里可以吗？我点击打开他就行”。
- 证据：截图 `~/workspace/user/files/1000305172_0_xq6f.jpg` 是点工具栏标题开的切站弹窗 SiteDialog（搜索框 + video/小程序/music/read 分类 chips + 两列站名胶囊），里面列出了小程序条目。
- 根因：手机端 SiteDialog 的两处数据源都没过滤 hk——`SiteDialog.getGroups()`（`Site.getGroups(SiteBlockSetting.filter(VodConfig.get().getSites(), block))`）算分组 chips，手机端 `SiteAdapter.addAll()`（同款列表进 `mAllItems`）供行；hk 站源名字是 `标题⁽ʰᵏ⁾┃[类型]`，`SiteNameStore.getGroups → SiteNameRules.groups → GroupRuleConfig.extract` 从 `┃[类型]` 取分组，type 为空/other 的规则 typeName 就是“小程序”，所以“小程序”chip 就是 hk 条目贡献的。
- 修复：`HkStore.withoutMiniapps(List<Site>)`（复用 `isMiniapp`，api/key 以 `hk://` 开头即滤掉，返回新列表不改原列表），在 `SiteDialog.getGroups()` / `getAllGroups()` / `onStart()` 的空列表自关判断与 `SiteAdapter.addAll()` 四处包住 `SiteBlockSetting.filter(...)` 的结果。chip 是从过滤后的分组算出来的，hk 滤掉后“小程序”chip 随之消失；`SiteGroupOrderStore.order()` 只重排输入里已有的分组，不会从保存的顺序里把“小程序”加回来；若某非 hk 站源本身就叫“小程序”分组，它会正确留下来。
- 未动：`LocalSourceDialog`（600a4581 已把 hk 移出）、实验室本地源分组（已处理）、`SiteDialog.onTextClick` 的 hk 分支（弹窗里已到不了，留作防御：万一 hk 条目从别的路进来，仍进列表页而不是切站源）。
- TV 核对：leanback 有独立的 `SiteDialog.java`，里面没有任何 hk 处理；TV 上 `VodConfig.initSite` 追加的 `LocalSource.getSites()` 读 `localSource/sites.json`，TV 没有本地源文件（无导入/扫描入口），文件不存在时返回空列表，所以 TV 的 VodConfig 里不会有 hk 条目——TV 不动。

## 问题 B：西瓜影院列表页“加载失败 / Expected URL scheme 'http' or 'https' but was 'hiker'”

- 证据：截图 `~/workspace/user/files/1000305171_1_t9s6.jpg`，HkListActivity（标题栏“西瓜影院”）加载失败态，错误文案是 OkHttp 的 scheme 错。
- 规则原文（4255 条合集里标题 exactly“西瓜影院”的那条）：`url = "hiker://empty"`，`class_name/class_url/search_url/searchFind/detail_find_rule` 全空，`find_rule` 是 JS：`var 网站 = 'https://www.ait.net.cn/'; eval(JSON.parse(fetch('hiker://page/home')).rule)`。
- 根因定位（已查清，出在首页抓页，不是 JS 内部 fetch）：
  1. `HkExecutor.homeDetailed()` 无条件 `fetch(buildHomeRequest(...))`；该规则无分类，template 就是 `rule.getUrl() = "hiker://empty"`，`Request.Builder().url("hiker://empty")` 抛 `IllegalArgumentException: Expected URL scheme 'http' or 'https' but was 'hiker'`，被 `fetch()` 包成 HkException“请求失败: hiker://empty (...)”→ 列表页加载失败。截图文案与这条链路逐字对应。
  2. JS 内部的 `fetch()` 不是这条错的来源：`HkEngine.httpFetch`  catches 所有异常返回 `""`，不抛错。
  3. 但修完首页抓页后，西瓜影院这条规则仍不能全通：它的 JS 要 `fetch('hiker://page/home')`，即子页面展开（第 7 步 `expandPage`），目前 `httpFetch` 对 hiker:// 返回 `""`，随后 `JSON.parse('')` 会抛 JS 执行错。本轮不做子页面展开，只修首页抓页这一处；不承诺西瓜影院全通。
  4. 用户随后说“显示都正常了”：判断是他切了别的 http(s) 规则（奇优/童话/臻品fix 都是正常 URL）后显示正常，hiker://empty 的确定性缺陷仍在，所以照修不缓。
- 修复：`HkExecutor.isHttpUrl(String)`（trim 后小写以 `http://`/`https://` 开头才算）。`homeDetailed()`：非 http(s) 时 `pageText = ""` 不抓页，`HkEngine.prepare` 本来就接受空 pageText（`resCode == null ? ""`）；JS find_rule 直接在空页上跑（规则自己 fetch 取数），诊断写明“首页地址 hiker://empty 不抓页”；选择器 find_rule 对空页解析得 0 条，诊断写明“选择器无页面可解析”，不再抛原始 OkHttp scheme 错。`detail()` 同款 guard 一并修（同一行共用 helper，spec.url 非 http(s) 时不抓页：JS 详情在空页上跑，选择器详情得 0 张卡片→详情页显示“暂无选集”，不崩）。
- 未动（遗留，第 7 步）：`playResolved` 选择器懒加载路的 `fetch(spec)`（选集地址是 hiker://page/... 时同样会 scheme 错）；`HkEngine.httpFetch` 对 hiker://page/... 返回 `""`（子页面展开未实现）。

## 验收标准

- 切站弹窗打开后 hk 条目一条不剩，“小程序”chip 消失（除非有非 hk 站源本身就叫这分组）；弹窗搜索、分组切换、切站、屏蔽开关行为不变。
- hiker://empty 的 JS 规则首页不再报 scheme 错：JS 能跑、0 条时诊断明确（用 阅动漫.hkzip 的 `url=hiker://empty` 做代码层面推演——它还要 require/子页面，第 7 步才全通，本轮不承诺阅动漫全通）；同类静态 setResult 规则（如 西瓜影院2、合成大西瓜🄴）首页应能出卡片。
- 三条验收规则（奇优影院♔/童话/臻品fix）列表/详情/播放零回归；列表/详情/播放链路其余行为不变。
- 以上真机项只能等构建后用户真机验；代码层面确认：逐行重读 + `git diff --check` + grep 核对过滤点与 guard 点。

## 回滚

整步一个提交，`git revert` 即可；两个修复互相独立，也可只 revert 其中一处文件（HkExecutor 只影响小程序取数，SiteDialog/SiteAdapter/HkStore 只影响切站弹窗）。
