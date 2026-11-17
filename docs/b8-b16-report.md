# B16 观看报表（B8 全量迁移）

> §0 执行声明：本批把手机端观看报表页（ViewingReportActivity，260 行）迁移到 Flutter，路由 `/report?range=`（C1 引擎）。
> 任务护栏 `b8-b16-viewing-report`（standard），分支 `b8-flutter-rewrite`，基线 B15 提交 `530f4aec`。
> 开关 `flutter_b16_enabled` 默认关，独立于其他开关；开关 UI 只在实验屏。关 = 报表页回原生，原生实现一行不删。
> **本机无 Java / Android / Flutter SDK，构建与运行均未验证**（CI 联编 + 真机验收时按 §6 逐项证明）。不推送。

## §1 原生实现（只读发现）

### 1.1 报表页

原生实现：`app/src/main/java/.../ui/activity/ViewingReportActivity.java`（260 行，main 源集，与 TV 共用 Activity）+ 布局 `app/src/main/res/layout/activity_viewing_report.xml`。

- **形态**：单滚动页，顶部标题（`viewing_report_title`）+ 范围副标题 + 刷新按钮（tonal 按钮，加载中禁用）；加载中是 240dp 高的居中转圈 + 阶段文案；空态只有一行居中文字（`viewing_report_empty`）；内容是 6 张卡片（概览、AI 画像、时间分布、内容偏好、人物偏好、徽章），其中内容偏好卡、人物偏好卡、AI 卡、徽章卡按数据有无隐藏。
- **加载流水线**（`loadReport`，单线程 executor）：强制刷新时先清缓存，否则先读缓存（命中且非空直接显示）；否则依次显示“正在统计本地数据…”→ 生成 →“正在生成报告…”→ AI 分析（`isReady()` 时才调）→“AI 分析中…”→ 写缓存 → 显示。空报告（0 条记录）显示空态，不做 AI 分析、不写缓存。
- **缓存**（`ViewingReportCache`）：24 小时有效；指纹 = 历史记录数 + 最新时间戳的 MD5 前 12 位，history 变化后自动失效；缓存文件在 `viewing_report/<range>_<指纹>.json`，Gson 序列化（含 AI 字段）。
- **本地统计**（`ViewingReportGenerator`，纯本地）：总分钟（position 求和）、去重作品数（名字归一化后去重）、集数 = 记录数、完播率（position/duration ≥ 90%）、平均单条分钟、时段分布（5 档）、周末占比、深夜次数（23:00–06:00）、题材榜（前 5）、地区榜（前 5）、剧集/电影比、演员榜（前 8）、导演榜（前 5）、4 类成就徽章（深夜 ≥10 次、作品 ≥30 部、地区 ≥4 个、完播率 ≥70% 且集数 ≥20）。
- **AI 分析**（`ViewingReportAiAnalyzer`，同步网络调用，必须在子线程）：`AiConfig.objectFrom(Setting.getAiConfig())`；`isReady()` 为假时跳过；prompt 由 `config.getViewingReportPrompt()` + 统计数据拼成；经 `AiCompletionClient` 发请求；解析返回 JSON（summary / persona / tags / highlights / genreInsights / recommendationHint / badges）；`apply` 成功才置 `aiAnalyzed=true`；失败静默降级（不影响本地报告展示）。
- **渲染**（`fill*`，逐字复刻到 host impl）：概览（小时数 `%.1f 小时` + 两行副文案 📺/🏆）、时间（最爱时段/周末占比/平均观看/深夜次数）、偏好（题材行 emoji+名+百分比，`emojiFor` 按中文关键词映射；剧集/电影比；完播率）、人物（演员前 5、导演前 3，`👤`/`🎬` 前缀）、AI（summary + ChipGroup 标签 + `•` 洞察行）、徽章（icon + 名 + 可选描述）。

### 1.2 范围与入口

- **范围**（`ViewingReportRange`，9 档）：ALL（全部）、THIS_YEAR（本年）、FIRST_HALF（上半年）、SECOND_HALF（下半年）、THIS_QUARTER（本季度）、THIS_MONTH（本月）、THIS_WEEK（本周）、LAST_30_DAYS（最近30天）、LAST_7_DAYS（最近7天）。`getDisplayLabel()` 给本年/本季度/本月加当前年份/季度/月份后缀；`getTimeBounds()` 算起止毫秒。
- **范围选择**（`ViewingReportRangeDialog`，main 源集）：单选列表 + 取消/生成两个按钮；每次打开默认选中 ALL；确认后回调所选范围。
- **入口**（两处，只改分流处）：
  - mobile `HistoryActivity.onReport()`（菜单 `R.id.report`）：弹范围弹窗 → 选中后 `ViewingReportActivity.start(this, range)`。
  - `C2HostApiImpl.openViewingReport()`（Flutter 历史页的报表行）：同一弹窗链 → 选中后 `ViewingReportActivity.start(activity, range)`。
  - leanback `HistoryActivity:75-76` 同样链路，TV 不动。
- **Activity 声明**：`ViewingReportActivity` 在 mobile 与 leanback 两端 manifest 均有声明（main 源集 Activity，与 TV 共用）；B16 只分流 mobile 的两个入口，Activity 本身不动。

### 1.3 字符串与资源归属（按目录分别 grep 验证，无 head 截断）

- 全部 33 个 B16 用到的字符串（`viewing_report_*` 28 个、`menu_viewing_report`、`error_play_timeout` / `error_retry`、`dialog_negative` / `dialog_positive`）只在 `app/src/main/res/values*`（values + zh-rCN + zh-rTW），mobile/leanback res 里一个都没有。所以 impl 里直接 `R.string` 引用是 leanback 安全的，无需 `mobileString()`。

## §2 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- `Setting.flutter_b16_enabled`，默认 false，独立开关；开关 UI 只在实验屏（C0 屏新加一行）。
- **入口分流（两处，B6→B7 同款预授权分流，见 §5 V1）**：范围弹窗本身保留在原生（入口体验逐字不变）；选中后开关开时启动 C1ScreenActivity 的 `/report?range=<范围名>` 路由，关时原样启动原生 ViewingReportActivity。
  - mobile `HistoryActivity.onReport()`：只 start 不 finish（原生历史页留在下面，返回时回到历史页，与原生一致）。
  - `C2HostApiImpl.openViewingReport()`：start-then-finish（B15 V3/V4 同款；返回时落在原生历史页，G2 跨屏竞态风险同款，见 §5 V1）。
- 快照方法本身不判断开关（与 B8/B15 同款）：快照只在 Flutter 页内被调用，入口即防线。

### 2.2 快照与完成规则（本批核心设计）

- `getReportShell()`：同步方法，返回静态页眉（标题、刷新文案、加载文案、空态文案、重试文案、范围弹窗标题/确认/取消）+ 9 个范围（名 + host 算好的 displayLabel）。Dart 在首个报表快照到达前就用它渲染 AppBar 与范围选择器。
- `getReport(rangeName, forceRefresh)`：@async 一次性快照（`Task.execute` 后台跑流水线 + `App.post` 回主线程完成），35s 看门狗；超时按 B8 惯例**成功返回错误快照**（`errorText = error_play_timeout`），Dart 显示错误态 + 重试。流水线与原生 `loadReport` 逐字同序：强制刷新时先清缓存，否则先读缓存（命中且非空直接返回）；否则本地生成 → 空报告直接返回空快照（不做 AI、不写缓存）→ AI 就绪时分析 → 写缓存 → 返回快照。
- **范围解析**：未知或空的范围名回落 ALL（Dart 在拿到 shell 后同样校验一次）。
- **AI 状态**：单次调用内完成；Dart 的加载态覆盖“进行中”，`aiVisible=false` 覆盖“未配置/调用失败”（与原生静默降级一致）。AI 人格字段原生算出但从不渲染，本合约不含它（见 §5 V6）。
- 无 EventChannel；刷新 = 首次加载 + resumed 重取 + 下拉刷新 + AppBar 刷新按钮（强制刷新，清缓存）。resumed 重取在缓存命中时是瞬时的（24h 缓存 + history 指纹失效）。

### 2.3 Pigeon 合约（`flutter_module/pigeons/b16_report_messages.dart`）

- `B16HostApi`：`isB16Enabled` / `setB16Enabled`；同步 `getReportShell()` → `B16ReportShell`；@async `getReport(String rangeName, bool forceRefresh)` → `B16ReportScreen`。
- `B16ReportShell`：title、refreshLabel、loadingLabel、emptyText、retryLabel、selectRangeTitle、generateLabel、cancelLabel、ranges（`List<B16RangeItem>`）。
- `B16RangeItem`：name（枚举名）、displayLabel（host 算好的展示文案）。
- `B16ReportScreen`：rangeName、rangeLabel、isEmpty、errorText、overviewLabel/overviewHours/overviewSub、timeTitle/timeContent、genreVisible/genreTitle/genreContent、peopleVisible/peopleTitle/peopleContent、aiVisible/aiTitle/aiSummary/aiTags（`List<String>`）/aiInsights、badgesVisible/badgesTitle/badgeContent。章节内容是 host 按原生 `fill*` 逐字算好的多行字符串（原生每章节就是一个 TextView），Dart 只渲染。

### 2.4 Dart 页（`flutter_module/lib/b16_report_page.dart`）

- `B16ReportPage(initialRange)`：AppBar（返回 + 标题 + 刷新按钮，加载中禁用）→ 范围行（当前范围文案 + “选择时间范围”按钮，弹单选弹窗，默认选中当前范围，确认后重载）→ `RefreshIndicator` + `ListView`（卡片列）。
- 卡片：概览卡（13sp 标签 + 34sp 粗体主色小时数 + 居中副文案）、AI 卡（标题 + 摘要 + Chip 标签 + 洞察行）、时间/偏好/人物/徽章卡（标题 + 多行内容）；卡片用 `_ReportCard`（Filled 样式：无阴影 + surfaceContainerHighest 底色 + 16dp 圆角，对齐原生 MaterialCardView Filled）。
- 状态：加载中（首屏转圈 + shell 加载文案；切范围时顶部线性进度条 + 保留旧内容）、空态（shell 空态文案居中）、错误态（快照 errorText 或通道 `加载失败：$e` + 重试按钮）。返回键经 `PopScope` 拦截 → `closeScreen()`。
- resumed 重取（C2/B2 同款 `WidgetsBindingObserver`），加载中时不重复触发。

### 2.5 Host 实现（`B16HostApiImpl.java`）

- `buildScreen`：原生 `loadReport` 逐字同序（缓存/生成/AI/写缓存）；`toScreen`：原生 `display` + 6 个 `fill*` 方法逐字复刻（含 `emojiFor` 原样复制、`%.1f` 格式化、题材前 5/演员前 5/导演前 3 的截断、卡片隐藏规则）。
- `Completion` 看门狗：B15 同款逐字复制（B16 结果类型）。
- B16 字符串全部 main-res（§1.3），直接 `R.string` 引用，leanback 安全。

### 2.6 文件清单

- 新 4 个：`flutter_module/pigeons/b16_report_messages.dart`、`flutter_module/lib/b16_report_page.dart`、`app/src/main/java/com/github/moyingshi/osc/tv/flutter/B16HostApiImpl.java`、本文档。
- 改 9 个：`flutter_module/lib/main.dart`（`/report` 的 onGenerateRoute 分支 + B16 开关）、`C1ScreenActivity.java`（`ROUTE_REPORT` + 注册 B16HostApi）、`C0ShellActivity.java`（注册 B16HostApi，仅开关用）、`Setting.java`（`flutter_b16_enabled`）、`app/src/mobile/java/.../ui/activity/HistoryActivity.java`（`onReport` 分流，§5 V1）、`C2HostApiImpl.java`（`openViewingReport` 分流，§5 V1）、`.gitignore`（`B16Messages.java`）、`.github/workflows/b8-c0.yml`（Pigeon 步骤加 b16 生成 + 存在性检查）。
- 未动：`ViewingReportActivity` / `ViewingReport*` / `ViewingReportRangeDialog` / leanback HistoryActivity / TV 入口 / C0–C2/B2–B15 合约与实现（除 C2 一处 §5 V1 分流）/ 共享组件（本批不用 VodCard/PicImage，无图）。

## §3 状态来源清单（全部 host 快照，Dart 不计算）

| 显示项 | 来源（host） |
|---|---|
| 标题、刷新/加载/空态/重试文案、范围弹窗文案 | `viewing_report_title` / `viewing_report_refresh` / `viewing_report_loading_local` / `viewing_report_empty` / `error_retry` / `viewing_report_select_range` / `viewing_report_generate` / `dialog_negative` |
| 范围列表与展示文案 | `ViewingReportRange.values()` + `getDisplayLabel()`（年份/季度/月份后缀 host 算） |
| 概览小时数与副文案 | `ViewingReportGenerator` 统计 + 原生 `fillOverview` 格式化 |
| 时间/偏好/人物/徽章章节内容 | 原生 `fillTimePattern` / `fillPreference` / `fillPeople` / `fillBadges` 逐字复刻 |
| AI 摘要/标签/洞察 | `ViewingReportAiAnalyzer.analyze()`（host 侧同步网络调用）+ 原生 `fillAi` |
| 卡片显隐 | 原生 `fillPreference` / `fillPeople` / `fillAi` / `fillBadges` 的隐藏规则 |
| 空态判定 | `ViewingReport.isEmpty()`（recordCount == 0） |
| 错误/重试文案 | `error_play_timeout` / `error_retry` |
| 缓存 | `ViewingReportCache`（24h + history 指纹），与原生同一缓存文件 |

## §4 逐项对照清单（Wei 真机验收用）

### 4.1 报表内容

- [ ] 开关开后，历史页报表菜单进 Flutter 报表页（先弹原生范围弹窗，选范围后进页）；开关关回原生报表页。
- [ ] 全部范围（9 档）各出一份报表，内容与原生逐项一致：小时数、副文案、时间分布、题材行（含 emoji）、剧集/电影比、完播率、演员/导演行、徽章行。
- [ ] 页内切范围（范围按钮 → 弹窗选另一范围 → 生成）后内容更新为新范围；弹窗默认选中当前范围。
- [ ] 空范围（如最近 7 天无记录）显示原生空态文案。
- [ ] 刷新按钮与下拉刷新都强制重新生成（清缓存）；加载中刷新按钮禁用。

### 4.2 AI 分析

- [ ] 已配 AI 时，AI 卡出现：摘要 + 风格标签 chips + 洞察行，与原生一致。
- [ ] 未配 AI 时，无 AI 卡（与原生一致），其余卡片正常。
- [ ] AI 调用失败时，无 AI 卡，其余卡片正常（静默降级，与原生一致）。

### 4.3 缓存与刷新

- [ ] 同一范围第二次进入瞬间显示（缓存命中，与原生一致）。
- [ ] 看完新片后重进报表，内容更新（history 指纹失效，与原生一致）。
- [ ] 从报表页返回后重进，列表刷新（resumed 重取；缓存命中时瞬时）。

### 4.4 开关与回滚

- [ ] 实验屏 B16 开关开/关立即生效（下次进报表页）。
- [ ] 开关关 = 报表页回原生，功能全量正常。
- [ ] TV 上报表页始终原生（leanback 历史入口不动）。

## §5 相对规划的全部偏差

- **V1（入口保留原生范围弹窗 + 两处分流）**：规划只说“范围选择 + 异步生成 + AI 分析（host）+ 渲染”，未定范围选择落在哪。两处入口（mobile HistoryActivity.onReport、C2 openViewingReport）仍先弹原生范围弹窗，选中后开关开时进 `/report?range=<范围名>`、关时进原生报表页；Flutter 页内另有范围弹窗可切范围。C2 的分流是 start-then-finish（返回落原生历史页，G2 跨屏竞态风险同款，B7 文档 §5 V10）；mobile 历史页的分流只 start 不 finish（返回落原生历史页，与原生一致）。两处分流均只改分流处，开关关时行为逐字不变。
- **V2（加载阶段文案不流式显示）**：原生加载中依次显示“正在统计本地数据…”→“正在生成报告…”→“AI 分析中…”；Dart 只显示 shell 的加载文案（“正在统计本地数据…”）直至快照到达。规划 §4.6 不引入 EventChannel/流式通道，故中间两阶段文案无法实时推送。
- **V3（页内范围弹窗默认选中当前范围）**：原生入口弹窗每次打开默认选中 ALL；Flutter 页内弹窗默认选中当前范围（原生没有页内切范围，无对照物；当前范围是唯一合理的默认值）。
- **V4（下拉刷新是新增）**：原生只有顶部刷新按钮；规划 §4.6 要求下拉刷新，Dart 用 `RefreshIndicator` 补上（同样强制刷新、清缓存）；顶部刷新按钮保留为原生等价物。
- **V5（resumed 重取）**：原生 `initView` 只加载一次；Dart 按规划 §4.6 在 resumed 时重取。缓存命中时瞬时返回（24h 缓存 + history 指纹），行为差异只在 history 变化后——此时原生需手动刷新才更新，Flutter 自动更新。
- **V6（AI 人格不渲染）**：原生 AI 分析算出 `aiPersona`，但布局里没有人格视图，`fillAi` 从不显示它；本合约不含 aiPersona 字段。
- **V7（地区榜不渲染）**：原生生成器算出地区榜（前 5），但报表页从不显示地区列表（只用地区数算“跨文化探索者”徽章）；Dart 同样不渲染地区榜。
- **V8（35s 看门狗与慢速 AI）**：原生 AI 调用无应用层超时（catvod OkHttp 每阶段超时 30s，极慢网络下单次 AI 调用可接近 60s+本地生成时间）。Flutter 的 35s 看门狗会先完成一个可重试的错误快照；后台线程仍继续跑完并写缓存，重试点重试时命中热缓存、瞬时显示。见 §6.2。
- **V9（无其他偏差）**：开关默认关、只实验屏有 UI、TV 不动、无 schema 变化、无新 Dart 依赖、无图（不复用 PicImage/VodCard）、全部文案与计算值 host 返回、卡片 Filled 样式与原生一致。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Pigeon 代码生成（CI 唯一可证）**：`B16ReportShell` 内含 `List<B16RangeItem>`（嵌套列表，B8/B10/B13 同款形态）+ 同步方法返回快照（B10 同款形态）+ `@async` 返回 22 字段快照；手写 Java 的 Builder/Result 签名是否与 CI 生成的 `B16Messages.java` 一致。CI 若报错，按文件路径归属本批修。
2. **慢速 AI 与看门狗（设备验收）**：弱网下 AI 分析超过 35s 时，Dart 应先显示可重试的错误态，点重试后命中热缓存瞬时显示（含 AI 卡）；后台线程的缓存写入不应被打断。见 §5 V8。
3. **AI 卡对照（设备验收）**：已配 AI 时，摘要/标签/洞察与原生逐字一致；未配 AI 与 AI 失败时无卡（静默降级）。
4. **范围弹窗（设备验收）**：入口弹窗（原生）与页内弹窗（Flutter）的 9 档范围展示文案一致（本年/本季度/本月带当前后缀）；页内切范围后内容更新；取消不改范围。
5. **缓存行为（设备验收）**：二次进入瞬时显示；看完新片后内容更新；强制刷新清缓存后重新生成（含重新 AI 分析）。
6. **G2 跨屏竞态**：从 Flutter 历史页进报表页是 start-then-finish（B7/B11 V10 同款风险）；复现路由串位时需在 `C1ScreenActivity` 加重启机制（跨批修复，本批不做）。
7. **卡片目视**：Filled 卡片样式、34sp 小时数、emoji 行与原生逐项比对。

## Recovery anchor

- 目标：B16 手机端观看报表页迁移到 Flutter（`/report?range=` 路由 + B16HostApi），开关 `flutter_b16_enabled` 默认关，不推送。
- 状态：本地实现完成（护栏 `b8-b16-viewing-report`），待 `finish` 提交 + 恢复标签；main 仍为 `f39a6e0`；工作树待验证干净。
- 文件：新 4 个（合约、Dart 页、Host impl、本文档），改 9 个（main.dart 路由 + 开关、C1/C0 注册、Setting 开关、mobile HistoryActivity 分流、C2 openViewingReport 分流、.gitignore、b8-c0.yml）。
- 未验证：构建与运行（本机无 SDK），按 §6 逐项留给 CI 联编 + Wei 真机验收。
- 下一步：B17 TMDB 人物迁移实施。
