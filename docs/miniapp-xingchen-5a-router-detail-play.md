# 移植阶段 5a：路由 + 详情 + 播放核心（HkRouter/HkDetail/HkPlay）

> Recovery anchor（任务：miniapp-xingchen-5a；lane：standard；分支 main；HEAD 1e5a5970）
> 目标：阶段 5 第 1 笔——搬星辰 HkRouter（1420 行）、HkDetail（213）、
> api/hk HkDetailBridge（276）、HkPlay（258）、HkLoading（66）、HkErrorUtil（284，
> 适配见 §3）、HkWebkitFetcher（354）与支撑类 ui/hk HkEngineManager（155）、
> ui/hk HkUrlDetector（419）；恢复 HkEngine.wrapResident/adopted（3e 删掉的常驻
> 包裹，Router 构造器依赖）；本地提交 + recovery tag，不推不编。做完即停，不开 5b。
> 范围：新增 9 文件 + 本文件；改 HkEngine.java（仅 wrapResident/adopted 恢复）。
> 状态：已搬运、逐行 diff 已验（见 §6，verify5a.py 39 PASS 0 FAIL），待 guard finish 提交。

## 1. 来源与方法

- 星辰原文：`~/workspace/xingchen-ref`（HEAD 52450c0），
  `app/src/main/java/com/fongmi/android/tv/api/hk/` 与
  `app/src/mobile/java/com/fongmi/android/tv/ui/hk/`。
- 移植方式：机械搬运脚本 `/tmp/xc5/port5a.py`（包名改写 + 文件头插入 +
  §3 所列适配；不随仓库走）。包名改写 `com.fongmi.android.tv` →
  `com.github.moyingshi.osc.tv`。每个新文件在 package 行后加一行文件头移植说明
  （3a/3b/3e/4a/4b/4c 同款）。
- 交叉验证：oktv 反编译源 `~/workspace/hk-ref/src/sources/` 无 HkRouter 等独立
  文件（只有 HkJsRuntime 大文件内嵌逻辑）；本笔以星辰真源码为准。

## 2. 搬入清单

| 类 | 星辰行 | 我方落点 | 我方行 |
|---|---|---|---|
| HkRouter | 1420 | api/hk/HkRouter.java | 1444（+1 文件头；适配见 §3.1/§3.2，见 §6 diff 细目） |
| HkDetail | 213 | api/hk/HkDetail.java | 214（+1 文件头） |
| HkDetailBridge（api） | 276 | api/hk/HkDetailBridge.java | 277（+1 文件头） |
| HkPlay | 258 | api/hk/HkPlay.java | 259（+1 文件头） |
| HkLoading | 66 | api/hk/HkLoading.java | 67（+1 文件头） |
| HkErrorUtil | 284 | api/hk/HkErrorUtil.java | 310（+1 文件头 +toast 助手 + 程序化视图，见 §3.3/§6） |
| HkWebkitFetcher | 354 | api/hk/HkWebkitFetcher.java | 355（+1 文件头） |
| HkEngineManager | 155 | ui/hk/HkEngineManager.java | 156（+1 文件头） |
| HkUrlDetector | 419 | ui/hk/HkUrlDetector.java | 420（+1 文件头） |

## 3. 适配点

### 3.1 HkRouter.getMarked：SourceGate 摘除（与 HkEngine.getMarked 3e 同款）

星辰 `getMarked`（:1355-1363）用 `SourceGate.mark("hk", rule.getTitle())`
前后恢复打点取数。我方 catvod 快照无 `SourceGate`/`GlobalProxy`（计划 §3.3
已核实，全仓库零命中）；代理选择器链已在 `HkHttpClient` 内生效（阶段 1）。
改为直接 `return HkHttp.get(url, rule.resolvedUa());`，方法名与注释保留，
注明“去 SourceGate 标记”。行为差：仅丢按源定向打点（只在配了代理且多源时
才起作用），无代理时与星辰直连等价。

### 3.2 HkRouter.resolveWebRule：HkIWebViewParser.parseSync 落点改走 HkWebkitFetcher

星辰 `resolveWebRule`（:737-759）调 `HkIWebViewParser.parseSync(webRuleUrl,
itemExtra)`——全桥嗅探（HkWebkitHolder 2528 行 + loadAllJs 两拍 + finishParse
回传）。HkWebkitHolder 不在本阶段搬运范围（它是 HkPageActivity 网页桥生态的
一部分，随阶段 7 评估），故本笔改走本阶段同批搬入的 `HkWebkitFetcher`：
按星辰注释的拆串口径（前缀真值 x5Rule→"x5"、webRule→"webkit"，页址取首个
`@` 前段，规则 JS 取 `@` 后段）调 `HkWebkitFetcher.executeWebRule(loadUrl,
ruleJs, null)`。已知降级（写进 §5）：无全桥/无注入/无 loadAllJs，依赖桥注入
（`fy_bridge_app.finishParse`）的嗅探式规则会回空；不依赖桥的
webRule/x5Rule（纯页面 JS 求值出地址）可走通。`HkIWebViewParser` import 删除；
`HkUrlDetector.isImage` 全限定引用经包名改写指向本笔同批搬入的
`ui/hk/HkUrlDetector`（419 行全文件搬入，import 仅 Context/Log/
SharedPreferences/TextUtils + java.util/regex，无其他依赖）。

### 3.3 HkErrorUtil：ToastUtil、两处 R.layout、HkDialogs 三处适配

星辰原文依赖我方没有的三样东西：

- `ToastUtil`（星辰 `utils/ToastUtil.java`）：我方全仓库无此工具类。
  5 处 `ToastUtil.show(ctx, msg, dur)` 改为 `Toast.makeText(ctx, msg, dur).show()`
  （`android.widget.Toast` 原文已 import）。语义同：同文案、同 duration。
- `R.layout.dialog_hk_error_bottom`（`msg_text`/`btn_show`/`dismiss`）与
  `R.layout.dialog_hk_error_detail`（`get_html_txt`）：两布局在我方 res 内
  不存在（本阶段 scope 不含 res/，不新增布局文件）。`BottomErrorDialog.
  onCreateDialog` 与 `showDetail` 改为程序化建视图（LinearLayout +
  TextView/Button，文案/按钮/5 秒自动消失/“错误详细信息”标题/“报告作者/
  忽略/删除规则”三按钮一字不动）；详情文本字色仍置 `0xFFF2F4F8`（原文注释
  的深色弹窗标准字色）。
- `ui/hk/HkDialogs`（星辰版 extends AlertDialog.Builder）：我方
  `ui/hk/HkDialogs` 是自研 final 工具类（inputAsync/confirmAsync/selectAsync），
  不是 Builder，API 不兼容。`showDetail` 改用 `androidx.appcompat.app.
  AlertDialog.Builder` 直建（其余链式调用一字不动）。`R` 与 `HkDialogs`
  两个 import 删除。
- 其余逐行与原文一致：`HkJsRuntime.getLastFetchDiag()`（我方 :916 在位）、
  `HkRuleManager.get().delete(title)`（我方 :1246 在位，返 boolean）均已核对。

### 3.4 HkEngine.wrapResident / adopted 恢复（3e 删掉的部分）

星辰 `HkEngine.java:36/63-80/249`：`adopted` 字段 + 私有常驻构造器 +
`wrapResident(rule, resident)` 工厂 + `destroy()` 按 adopted 分流（常驻不销毁）。
3e-2 以“HkEngineManager 在星辰仓库里根本没有这个类”为由整段删掉（3e §6.1）——
该判断是错的：`HkEngineManager` 在星辰 `app/src/mobile/.../ui/hk/` 内（155 行，
本笔同批搬入）。HkRouter 构造器（:38-49）依赖 `HkEngineManager.runtimeFor/
ensureInit` + `HkEngine.wrapResident`（修法 A：点击求值 putMyVar 与页面刷新
求值必须落在同一台常驻运行时上，否则分类点不进去）。本笔按星辰原文恢复
`adopted` 字段、私有构造器、`wrapResident` 工厂，并把 `destroy()` 改回按
adopted 分流（常驻空操作，独立引擎 `jsRuntime.destroy()`，加 null 守卫——
无参适配构造的 jsRuntime 可能为 null，3e 现有 destroy 已有该守卫，保留）。
公开构造器与门面 home()/search()、适配段一字不动。`HkEngineManager` 本笔
搬入后，3e 文档 §6.1/§6.2 里“HkEngineManager 不存在 / 常驻路径已删”的措辞
即过期，以本文件为准。

### 3.5 HkDetailBridge（api）：bean 接缝已核对

`toVod` 用的 `Vod.setSite/setFlags`、`Site.setKey/setName`、`Flag.create`、
`Episode.create` 在我方 bean 内均已在位（Flag.create/ Episode.create/
Vod.setFlags 均已 grep 核对）；`KEY_PREFIX = "hk_"` 与 `isHkKey` 原文照搬，
站 key 命名空间取舍见 5d（HkHikerRuleUtil history 支恢复时定：星辰 `hk_`
前缀 vs 我方 `hk://` scheme）。

### 3.6 其余零差异

HkDetail、HkPlay、HkLoading、HkWebkitFetcher、HkEngineManager、HkUrlDetector
与星辰原文逐字节一致（除包名与文件头行）。HkLoading 的 Listener 静态持有、
主线程 post 口径原文照搬；调用方（列表页注册实现弹窗）属 5c/5d 接线，
本笔只建类。HkDetailBridge（api）的 `setClickPosition/consumeClickPosition`
等点击位置账原文照搬。

## 4. 前向引用（本笔之后仍悬空，5b/5c/5d 补）

| 符号 | 归属 | 说明 |
|---|---|---|
| HkSearchEngine（HkRouter.detail :4 处，fromRule 折叠） | 5b | HkJsRuntime:11102 等 3 处同款悬空 |
| HkChapters + HkEpisodeDryClassifier（HkRouter.classifyDetailItems :5 处） | 5b | HkDetailItem.setDryVerdict 亦待 5b 恢复 |
| HkEpisodeMerger/HkEpisodeQualifier/HkEpisodeSorter（HkDetailBridge.toVod） | 5b | — |
| HkFindDelivery（HkJsRuntime:473 等 8 处） | 5b | 与 HkSearchEngine 同为 17 处悬空引用的两大头 |
| HkRouter 调用方（列表/搜索/详情/播放 UI） | 5c/5d | 本笔只建路由层，不接 UI；老链路 HkExecutor 仍在跑 |
| HkLoading.Listener 注册方 | 5c/5d | 无注册时 show/hide 恒空操作（listener 为 null 即 return） |

## 5. 已知语义差与降级

1. **resolveWebRule 无全桥**（§3.2）：依赖 `fy_bridge_app.finishParse` 注入的
   嗅探式 webRule/x5Rule 会回空；纯页面 JS 求值出地址的可走通。阶段 7 随
   HkPageActivity/HkWebkitHolder 评估是否补全桥。
2. **getMarked 无 SourceGate 打点**（§3.1）：仅影响配了代理且多源时的按源定向；
   无代理时与星辰直连等价（计划 §3.2）。
3. **HkErrorUtil 两处弹窗改程序化建视图**（§3.3）：布局结构与文案不变，
   视觉（圆角/间距）与星辰 res 布局有细微差别；不在 hk 引擎行为内。
4. **HkRouter.detail 的 `*` 列表段复核**（任务书指定，4c §4.4）：星辰详情路由
   不走 `HkSelector.parseDetail` 的 `*` 整页当一条分支——`HkRouter.detail`
   先看条目 URL 自带逻辑（@lazyRule/@rule=/js:），再看 detail_find_rule，
   经 `HkDetail`/`classifyDetailItems` 收编；`*;*;body&&div@option;Text;value`
   这类 chapter_find 样本在星辰原生链路下同样不走“整页当一条”特例（星辰
   HkSelector.evalList 对 `*` 回空表，4c §4.4 已证）。结论：**星辰原生行为，
   接受并记录，不补**。影响面只限 chapter_find/sdetail_find 以 ` *` 开头的
   规则（小说/漫画类备选详情路）；8 条验收规则无一用此写法（逐条核对见 5d）。
5. 本笔不改：HkExecutor、HkListHelper、HkActionHandler、HkNonVideo、HkDialogs、
   HkSubpage、HkWebRule、HkPlayer、HkDetailHelper、VideoActivity、VodFragment、
   HkMiniappActivity、BaseLoader、底栏、TV/leanback。HkImage 已在位（3a，与星辰
   33 行原文逐字节一致，仅文件头一行之差，见 3a 文档），本笔不动。

## 6. 验收证据（代码级，未构建）

- **逐行 diff**（`diff <(sed 包名还原 + 去文件头 我方文件) 星辰原文`，
  脚本 `/tmp/xc5/verify5a.py`，**39 PASS，0 FAIL**）：
  HkDetail、HkPlay、HkLoading、HkWebkitFetcher、HkEngineManager、HkUrlDetector、
  HkDetailBridge（api）均为 `IDENTICAL-MODULO-PACKAGE-HEADER`（零差异）；
  HkRouter 仅 5 处 diff hunk（2 处 import 删除 + getMarked 替换 + resolveWebRule
  替换 + javadoc 1 行），全是 §3.1/§3.2 的适配；HkErrorUtil 17 处 diff hunk
  （4 处 import 删除 + toast 助手插入 + 5 处 ToastUtil→toast + 两处程序化视图
  + AlertDialog.Builder 替换），全是 §3.3 的适配；HkEngine.java 仅
  wrapResident/adopted 恢复段有差异（+39/−4，另补无参构造 `adopted=false`
  一行——重读时发现 final 字段在无参构造里未赋值会 javac 报错，已修）。
- **包名/禁入残留**：全树 grep `com.fongmi.android.tv` 在 hk 源集内只剩各新文件
  头注释里的星辰原文路径，无代码引用；`LitePal|DexClassLoader|loadClass|
  SourceGate.|GlobalProxy|XPopup|Clash|ToastUtil|HkIWebViewParser.parseSync(webRuleUrl`
  在新增九文件内零命中（代码行口径，注释里的说明性提及除外）。
- **git diff --check** 通过（verify 脚本内跑过）。
- **改动文件列表与行数**：新增 9 文件共 3502 行（Router 1444、Detail 214、
  DetailBridge 277、Play 259、Loading 67、ErrorUtil 310、WebkitFetcher 355、
  EngineManager 156、UrlDetector 420）；改 HkEngine.java +39/−4；本文件。
- **HkFindDelivery/HkSearchEngine 仍悬空**：本笔不闭合（5b 的事）；grep 计数：
  HkJsRuntime 内 HkFindDelivery 12 处、HkSearchEngine 6 处（代码+注释合计，
  任务书的 17 处是代码引用口径，见 5b 统一对照）。

## 7. 遗留（5a 结束后仍缺什么）

- 5b：HkSearchEngine、HkChapters、选集四件套、HkFindDelivery、HkDetailItem
  dryVerdict 恢复、HkColType、HkM3u8ClearAdRule/Util（按需评估结论）、
  HkLyricLoader/HkImage（按需评估结论）。
- 5c：列表/搜索 UI 替换（星辰 HkListActivity 3268 行因 XPopup 禁令 + 阅读器/
  ArticleFragment/RouteBlocker 生态无法逐字搬，见 5c 文档定案；搜索页同款
  核对 HkSearchFragment/HkSearchModel 的 Clash 引用与缺失布局后定案）。
- 5d：接线（HkRouter 接 HkEngine 门面经 HkEngineManager 常驻运行时——本笔
  已把构造器链打通；UI 调用点改走 HkRouter；HkExecutor 瘦身/删除；
  HkListHelper 等删除清单；HkHikerRuleUtil history 支恢复；3e 适配段缺口复核；
  HkLazyRuleParser.parse 桥注入定案；x5 卡二选一；8 条规则全链路核对）。
- 阶段 6：云口令两法、HkCloudClip 等；阶段 7：HkPageActivity、剩余 event、
  HkWebkitHolder 全桥。均不提前搬。
