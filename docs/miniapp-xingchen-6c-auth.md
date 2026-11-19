# 阶段 6c：授权链（HkAuthDialog/HkAuthManager）+ 接线闭合 + 收尾

> Recovery anchor — 目标：阶段 6 最后一笔。新增 `HkAuthDialog`/`HkAuthManager`
> （星辰原文 + §2 适配）；`HkJsRuntime` 注册 `openAppIntent`/`publishRule` 两个 JS
> 函数（星辰原文），授权链就此有调用方、接线闭合；`HkHikerRuleUtil` 恢复异步版
> `getRulesByHiker(String, OnCodeGetListener)`（星辰原文）+ `HkCodeUtil.dealFileByHiker`
> 改走异步版（星辰调用点）。阶段 6 至此完工。
> 验收：授权链调用闭合（§4）；`git diff --check` 通过；0 leanback。只做阶段 6c，
> 做完即停，不开阶段 7；不推、不编、不触发 workflow。
> 状态：文档先行（2026-11-19 17:15）；实现已完成（guard 6c 活跃中，HEAD 仍为
> `ceb6795d`）；§4 验收已全部通过（见 §4）。下一步唯一动作：`task_guard.sh finish`
> 提交并打 recovery tag；阶段 6 随即完工。

- 完成句：授权链落地并接进 JS 引擎、异步版恢复，6c 提交后阶段 6 完工。
- 允许路径：`app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/HkAuthDialog.java`（新）、
  `HkAuthManager.java`（新）、`HkJsRuntime.java`（只加两个 putFunction）、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/util/HkHikerRuleUtil.java`、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/api/hk/util/HkCodeUtil.java`、
  `docs/miniapp-xingchen-6c-auth.md`（本文档）。
- 保护路径：无初始脏文件（`git status` 干净，HEAD `ceb6795d`，6b 已提交）。
- 本地时间：2026-11-19 17:15 CST（Asia/Shanghai）。
- 预计：实现约 1 小时；验收约 20 分钟；预计 18:45 前提交。
- 最便宜的决定性验证：`grep -n "HkAuthManager\." HkJsRuntime.java` 两处调用在位
  + `grep -rn "HkAuthDialog\|HkAuthManager"` 仅 6c 文件命中 + `git diff --check` 通过。

## 1. 来源与范围

- 任务书：阶段 6 任务（6c 授权链 + 收尾）；计划文档 `docs/miniapp-xingchen-port-plan.md`
  §2 阶段 6、§1.4；6a/6b 文档；仓库规约 `~/workspace/projects/Mooyingshi/AGENTS.md`。
- 星辰源码：`~/workspace/xingchen-ref`（HEAD `52450c0`）。
- 本笔不做：阶段 7（`HkPageActivity`、剩余 event、首页订阅定去留、ConfirmHandler 接管、
  合集浏览面、`HkFavoriteStore`/`HkFavorite` 对接）。
- 起点已核实：6b 提交 `ceb6795d`；`HkAuthDialog`/`HkAuthManager` 在我方树内不存在；
  我方 `HkJsRuntime` 未注册 `openAppIntent`/`publishRule` JS 函数；异步版
  `getRulesByHiker` 未搬（`HkCodeUtil.java:311` 已记名）。

## 2. 设计决定（每项都有 grep/代码证据，不是推测）

### 2.1 授权链评估结论：搬，但只搬星辰实际用的那两条线

核对结果（星辰 + 我方双树 grep）：

| 敏感面 | 星辰接线 | 我方接线 | 6c 动作 |
|---|---|---|---|
| `findJavaClass`/`loadJavaClass` | `authGate`（`AuthBridgeEvent`，星辰 `HkJsRuntime.java:5710-5745`） | JS 入口根本不存在（全树零命中；`AuthBridgeEvent.java:16` 只在契约注释里提 method 名） | **不搬入口、不搬 `HkAuthManager` 的这条线**。理由：dex 动态加载是硬约束禁区（“不引入 dex 动态加载”）；星辰自己也是经 `authGate` 而非 `checkAuth` 授权这两法；我方 `authGate` 已有（`HkJsRuntime.java:7502`），缺的只是被禁的 JS 入口 |
| `registerDNS` | `authGate`（星辰同款） | `authGate`（`HkJsRuntime.java:5025/5049`，3e 已改官链、“旧 checkAuth 直问＋SP 持久授权摘除”） | 不动，已闭合 |
| `openAppIntent` | `HkAuthManager.checkOpenAppIntent`（星辰 `HkJsRuntime.java:5564`） | JS 入口不存在；`$15` 助手（`hkTryGetString`/`appInstalled`/`paramStringValue`/`hkToast`）3e 已备（`HkJsRuntime.java:6076-6110`） | **搬 `HkAuthManager` + 注册 putFunction**（§2.3） |
| `publishRule` | `HkAuthManager.checkAuth(key, "publishRule", …)` → `HkPublishHelper.publishRule`（星辰 `HkJsRuntime.java:5637-5639`） | JS 入口不存在；`HkPublishHelper` 6b 已落 | **搬 `HkAuthManager` + 注册 putFunction**（§2.3） |

- 结论：星辰 `HkAuthManager.checkAuth`（SharedPreferences 持久授权）只服务
  `publishRule` 这一法，不服务三敏感法——与我方 3e“摘除旧 checkAuth 直问＋SP 持久授权”
  的决定不冲突（3e 摘的是三敏感法的旧链，`publishRule` 不在其列）。`checkAuth` 的
  SP 存储（`hk_auth`）与 `authGate` 的 `_auth` 存储并存，各管一法，记名。
- `HkAuthDialog` 是 `checkAuth` 的弹窗本体（星辰 `HkAuthDialog.java:53` 行）；
  `openAppIntent` 的每回确认不用它（`checkOpenAppIntent` 自带 XPopup→原生 Dialog，
  见 §2.2）。两者一起搬，调用关系不变。

### 2.2 `HkAuthManager`/`HkAuthDialog`：星辰原文 + 三处适配

- `HkAuthDialog.java`（53 行）：`HkDialogs`（5e 已删）→ `AlertDialog.Builder`
  （同标题「规则请求授权」、同文案（含 `findJavaClass`/`loadJavaClass` 高风险警告）、
  同「允许」/「拒绝」钮、`setCancelable(false)`；无宿主/异常时回调 `onDeny` 不变）。
  其 import 的 `AlertDialog` 在星辰版里是闲置的，在我方版里正用上。
- `HkAuthManager.java`（243 行）适配：
  1. `AiActivityTracker.top()`（星辰 AI 模块，我方无）→ `App.activity()`
     （`App.java:99` 静态方法，`ActivityLifecycleCallbacks` 跟踪当前 resumed activity，
     `onActivityResumed` 置位、`onActivityPaused` 清空；两处调用点同换）。
  2. `DisplayUtil.dpToPx(activity, 16)`（hikerview 工具类，我方无）→
     `(float) (16 * activity.getResources().getDisplayMetrics().density + 0.5f)`
     内联（与 `HkPublishHelper` 星辰原文的 radius 算法同形）。
  3. XPopup→原生 `AlertDialog.Builder`（`checkOpenAppIntent` 的每回确认）：
     同标题「温馨提示」、同正文（含包名括号段与 `com.alicloud.databox`→「阿里云盘」
     逐字映射）、同「取消」/「确定」钮、`setCancelable(false)`（对齐星辰
     `dismissOnBackPressed(false)` + `dismissOnTouchOutside(false)`）；
     `borderRadius` 无原生等价物，丢弃（记名）。
- 其余一字不改：`hk_auth` SP、`TIMEOUT_SEC=120`、`canTouchUI` 1000ms 频控
  （`UI_USE_MAP`）、`clearStored`（openAppIntent 首调清历史持久条目）、
  `checkAuth` 的 latch 120 秒、`checkOpenAppIntent` 的 latch 1 分钟与“锁存的是本回
  确认结果、不是授权缓存”、`currentRuleTitle()` 兜底回 `""`。
- ConfirmHandler 归属（任务书点名核对）：星辰 `HkAuthManager.java` 全文件零引用
  `ConfirmHandler`；我方 `HkJsRuntime.ConfirmHandler`（`:725-732`、调用点 `:3709`）
  3e 已搬、5e 文档确认“全树零设置方”，接管仍是阶段 7——本笔不动。

### 2.3 `HkJsRuntime`：注册两个 putFunction（星辰原文，插在 `registerDNS` 之前）

- 位置：`putFunction("registerDNS", …)`（我方 `HkJsRuntime.java:5025`）之前；
  星辰顺序正是 openAppIntent → publishRule → registerDNS（星辰 `HkJsRuntime
  .java:5556/5616/5645` 附近）。
- `openAppIntent`（星辰 `:5556-5608`）：门序照官——先取 `rule` 标题、空直回
  `Boolean.FALSE`；`p/a` 经 `hkTryGetString` 定型（非串抛「参数类型只能是字符串」）；
  授权门 `HkAuthManager.checkOpenAppIntent(ruleTitle, pkg, onAllow)`；`onAllow` 内
  ACTION_VIEW + flags `0x10008000` + 装机门 `appInstalled(pkg)`（param 消费之前）+
  param 仅 JSONObject 时逐键拼 extra（`intentData` 走 `setData`）+ 组件＝包名＋类名
  + 启动异常 toast「出错：」＋msg；回 `Boolean` 本体（非 `"false"` 字符串）。
  助手全在我方已有（§2.1 表）；`Map`/`Intent`/`ComponentName`/`Uri` 全用星辰原文的
  全限定名写法，不新增 import。
- `publishRule`（星辰 `:5616-5650`）：标题取第二参 `ruleTitleKey` 本身（key 空直返；
  我方无票据表，星辰注“非空取 key 本身”照搬）；规则对象经 `argsNativeObjectAdjust`；
  `HkAuthManager.checkAuth(key, "publishRule", "提交上传规则或口令到云仓库", () ->
  HkPublishHelper.publishRule(App.activity(), ruleObj))`（星辰原文的
  `AiActivityTracker.top()` 按 §2.2 换 `App.activity()`）；异常包
  `RuntimeException("publishRule 失败：" + …)`。`TextUtils`/`Undefined` 我方已有
  import（`:36` 等）。
- 不碰 `registerDNS` 与其余 putFunction；不碰 `confirm`（阶段 7）。

### 2.4 `HkHikerRuleUtil` 异步版 + `HkCodeUtil` 调用点（收尾）

- 星辰 `util/HkHikerRuleUtil.java:267` 的异步版 `getRulesByHiker(String,
  HkCodeUtil.OnCodeGetListener)`：任务书点名“若有则按星辰原文恢复”。调用方确认：
  星辰全树唯一调用点是 `util/HkCodeUtil.java:315`（`dealFileByHiker`）；我方
  `HkCodeUtil.java:311-318` 的注释已记名“只搬了同步版、异步版未搬”，调用方直调同步版。
- 搬：`HEAVY_TASK` 执行器定义（星辰 `:247-253`，`newCachedThreadPool`，守护线程
  `hk-hiker-rule-heavy`）+ 异步版方法体（星辰 `:267-290`：404 分发段、empty/page
  直回、home/bookmark/download/collection/history 五支投 `HEAVY_TASK` 后回调同步版、
  尾支恒 `onSuccess("")`、支内无 try/catch、分发段 catch→`onFailure(500, …)`）。
  我方文件无 `HEAVY_TASK` 字段（仅 `:172` 注释称“既有”，实为 6c 前的记名缺口），
  一并补上。插入点：同步版 `getRulesByHiker(String)` 方法之后、`// ---- §history`
  标记之前。`HkParserKit`/`TextUtils` 我方已有 import。
- 改：我方 `HkCodeUtil.dealFileByHiker` 按星辰 `HkCodeUtil.java:311-318` 原文改写——
  `hiker://assets/` 支不变；其余全部删繁就简为 `HkHikerRuleUtil.getRulesByHiker(url,
  listener)`（empty/page 两支由异步版内部分发，不必再内联）。注释改写为星辰口径。
- 不搬：星辰 `HkHikerRuleUtil` 同步版内的 `HkFavoriteStore` 行（我方 3e 已按
  「阶段 7 搬 `HkFavoriteStore` 后恢复」记名，回 `[]` 不动）。

## 3. 授权链调用闭合表（本笔后）

| 调用点 | 方法 | 弹窗/存储 |
|---|---|---|
| `HkJsRuntime.putFunction("openAppIntent")` | `HkAuthManager.checkOpenAppIntent` | 每回原生 Dialog 确认 + `canTouchUI` 1000ms 频控；无持久授权 |
| `HkJsRuntime.putFunction("publishRule")` | `HkAuthManager.checkAuth` → `HkAuthDialog.show` | 首次原生 Dialog + `hk_auth` SP 持久授权；`HkPublishHelper.publishRule` 为落点 |
| `HkJsRuntime.putFunction("registerDNS")` | `authGate`（3e） | `AuthBridgeEvent` + `_auth` 存储（不动） |
| `findJavaClass`/`loadJavaClass` | —（JS 入口不存在，dex 禁区） | — |
| `HkCodeUtil.dealFileByHiker` | `HkHikerRuleUtil.getRulesByHiker(url, listener)`（异步版） | — |

## 4. 验收证据（代码级，不等构建）——2026-11-19 17:2x 已验，全部通过

- [x] `HkAuthDialog`（58 行）/`HkAuthManager`（252 行）在位；`HkJsRuntime` 内
      `HkAuthManager.checkAuth`（`:5119`，`publishRule` 内）/`checkOpenAppIntent`
      （`:5046`，`openAppIntent` 内）各一处调用；`App.activity()` 两处替换在位
      （`HkAuthManager.java:130/203`）；无 `AiActivityTracker`/`DisplayUtil`/
      `XPopup` 代码引用（仅注释提及星辰原文用法）。
- [x] `openAppIntent`（`:5038`）/`publishRule`（`:5098`）putFunction 在位，位置在
      `registerDNS`（`:5132`）之前；`findJavaClass`/`loadJavaClass` putFunction
      仍不存在（grep 为零，dex 禁区）。
- [x] 异步版 `getRulesByHiker(String, OnCodeGetListener)`（`HkHikerRuleUtil.java:160`）
      + `HEAVY_TASK`（`:140`）在位；`HkCodeUtil.dealFileByHiker`（`:314-320`）调异步版；
      `HkHikerRuleUtil.java` 头注“异步版未搬”已改“6c 已搬回”，`:221` 的“既有
      HEAVY_TASK”不再悬空。
- [x] `git diff --check` 通过；全树 grep 无 `com.fongmi.android.tv`（仅
      `HkRule.java:18` 头注一处、6c 前已存在）、无 LitePal 依赖（diff 中唯一
      “LitePal”命中是 `HkHikerRuleUtil` 类注里的官行为描述上下文行，非新增代码）、
      无 dex 动态加载、无 XPopup 调用/依赖；0 leanback 文件。
- [x] 改动文件：新 `HkAuthDialog.java`（58 行）、`HkAuthManager.java`（252 行）、
      `docs/miniapp-xingchen-6c-auth.md`（177 行）；改 `HkJsRuntime.java` +107/−0、
      `HkHikerRuleUtil.java` +51/−2、`HkCodeUtil.java` +5/−18——三文件合计
      +163/−20（`git diff --numstat`）。

## 5. 实现步骤

1. 开 guard 会话 6c（scope 见首部允许路径；新文件先建再 `--adopt-dirty`）。
2. 新建 `HkAuthDialog.java`（§2.2）、`HkAuthManager.java`（§2.2），逐行重读。
3. `HkJsRuntime.java` 在 `registerDNS` 之前插入两个 putFunction（§2.3）。
4. `HkHikerRuleUtil.java` 补 `HEAVY_TASK` + 异步版；`HkCodeUtil.java` 改
   `dealFileByHiker`（§2.4）。
5. 验收（§4）：调用闭合 grep + `git diff --check` + 行数统计；逐行重读改动
   （注意 javac 坑：见 6a 文档 §5 末段同款清单；`openAppIntent` 的 lambda 捕获
   `pkg`/`cls`/`result` 须 effectively final——星辰原文已用 `final` 修饰，照搬）。
6. `task_guard.sh finish` 提交并打 recovery tag；阶段 6 完工。

## 6. 遗留（不归 6c / 阶段 6）

- 阶段 7：`HkPageActivity`（8476 行，评估是否全搬）、剩余 `event/*`、
  `HkHomeSubService`（首页订阅）定去留、`HkFavorite`/`HkFavoriteStore`/
  `HkHistoryRecorder` 对接现有收藏/历史、`HkM3u8ClearAdRule/Util`、`HkLyricLoader`
  按需收尾、ConfirmHandler 接管（JS `confirm()` 在 5e 内走“取消”分支，
  6c 未动）、合集浏览面（`HkPageActivity.openCollectionBrowser`）。
- `publishRule` 的云仓库上传落点是用户 JS（`publish_code`）自行提交，官无写死后端、
  星辰无此服务（星辰原文注）；`publish_code` 为空时只弹说明窗（`HkPublishHelper.
  showPublishGuide` 的确认钮暂只关窗，`PublishCodeEditActivity` 编辑页不存在）。
- `openAppIntent` 每回确认、无持久授权（官姿态）；已存的 openAppIntent 持久条目
  在新路首调时清除（星辰原文 `clearStored`）。
- 云1上传无真实 pastebin dev key、云9取回侧站点已停服（6a 已记名）；管理页导出仍是
  云 2 单通道（6b 已记名）；`HkQrUtil`/`HkShareHelper.detectFromClipboard` 暂无
  UI 调用方（6b 已记名）。
