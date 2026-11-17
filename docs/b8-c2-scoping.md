# B8 C2 选屏调研：第一个内容屏（调研文档，非实现）

> 状态：**调研完成，待决策**。本文档只做选屏调研与设计草图，不包含任何
> 产品代码改动。评估与阶段定义见 `docs/b8-flutter-rewrite-assessment.md`
> （§5 方案 C 的 C2 定义、§6 go/no-go、§7 回滚、§8 门 G7）；C0/C1 已验证
> 形态见 `docs/b8-c0-shell.md` 与 `docs/b8-c1-about-subtitle.md`。
> 分支：`b8-flutter-rewrite`；基线：HEAD `51f15a7`；`main` 未动。
>
> **诚实声明**：本机无 Java、Android SDK、Flutter/Dart，一切构建与运行
> 结论均未验证。行数与结构均实测于本地源码（2026-11-17）；凡需 CI/真机
> 证明的项见 §6，不得视为已验证。

## 0. §1 执行声明（仓库 AGENTS.md）

- 完成句：C2 选屏调研——盘点手机端内容屏候选、推荐恰好一个最小第一屏、
  给出 Pigeon 读合约草图与验收清单，写成调研文档并本地提交，不改产品
  代码，不推送，不动 `main`。
- 允许路径：仅 `docs/b8-c2-scoping.md`（task_guard assessment 模式）。
- 预存脏文件：无（开始时工作树干净）。
- 时间/时区：2026-11-17 10:24 CST（Asia/Shanghai）开始。
- 工期估计：源码调研约 40 分钟 + 文档约 20 分钟，约 11:30 CST 完成。
- 最便宜的决定性验证：`git diff --check` + diff 仅含本文档 + 提交后
  工作树干净。

## 1. 候选屏盘点（实测）

C2 定义（评估文档 §5）：首页/详情等读模型为主的屏，桥接 Room/设置/历史
读接口，验收加列表滚动性能与图片加载对照。以下行数均为 `wc -l` 实测，
路径均为手机端（`app/src/mobile` 除非注明 `main`）。

| 候选 | 原生实现 | 状态与数据 | 图片/导航/弹窗 | 判定 |
| --- | --- | --- | --- | --- |
| **历史**（选） | `HistoryActivity` 205 行 + `HistoryAdapter` 140 行，单网格 + 工具栏 3 个菜单 | 单一数据源：`History.getForDisplay()` 一次调用（见 §2）；删除模式 1 个布尔值；无分页、无筛选、无网络 | 卡片走 `ImgUtil.load`（见 §3.4）；点卡片交 `HistoryResumeCoordinator.open`（277 行，留原生）；同步/报表/删除确认 3 个原生弹窗 | **选，第一屏** |
| 收藏 | `KeepActivity` 220 行 + `KeepAdapter` 131 行，结构与历史几乎同构 | 单一数据源：`Keep.getVod()`；删除模式 1 个布尔值 | 点卡片有异步 `VodConfig.load` + 失败重试错误态 + 无配置时 fallback 到搜索页，host 调用面比历史大；卡片无进度条，代表性弱于历史 | 不选首屏；天然第二屏，复用同一卡片与合约形态 |
| 分类/Vod 列表 | `TypeFragment` 240 行 + `VodAdapter`，分页 + 筛选 | 数据走 `SiteViewModel.categoryContent`（蜘蛛实时网络请求），异步 + 分页 + 筛选状态 + 错误态；不是本地读模型 | 同卡片；筛选弹窗留原生也需状态回传 | 不选。网络/分页/筛选三重复杂度，不适合做第一个桥 |
| 首页 | `VodFragment` 1200 行 + `HomeActivity` 691 行 | 复合壳，不是单个屏：轮播（`BannerAdapter` 110 行，4 秒自动轮播）、继续观看（`ContinueAdapter` 120 行）、JS 小组件（`WidgetManager` + `WidgetSectionAdapter` 80 行）、分类 ViewPager（每页一个 `TypeFragment`）、海阔小程序内嵌、WebHome WebView、切站/加载配置、5 个 Listener 接口 | 全部子系统一次到齐；迁首页 = 同时迁所有东西 | 不选。应拆成 C2 后续多块（先单列表屏，再轮播/继续观看/小组件各一块） |
| 详情 | `VideoActivity` 12139 行、`TmdbDetailActivity` 14420 行（`main`），均继承 `PlaybackActivity` | 详情与播放器同一 Activity：选集/线路/解析/投屏/弹幕/字幕/诊断全在里面 | 贴近播放器 | **排除**。这是 C3 的事（评估文档 §5），且体量本身就不可做第一屏 |
| 搜索 | `SearchActivity` 132 行 + `SearchFragment` 479 行 | 热词/联想（含腾讯联想源）/搜索记录/多站 scope 弹窗/异步多站搜索 | 文本输入 + 联想是已知复杂面 | 不选 |
| 聚合搜索 | `CollectFragment` 780 行 | 多站并行搜索进度、相似度筛选、分组筛选、分页 | 同上，更复杂 | 不选 |
| 短剧/直播 | `ShortDramaActivity` 793 行、`LiveActivity` | 播放形态 | 贴近播放器 | 排除，C3 的事 |

## 2. 推荐：历史页（手机端 `HistoryActivity`）

**一句话理由：它是唯一同时满足“单屏、单本地数据源、读接口已封装成一次
调用、卡片含图含进度条”的内容屏，C2 要证明的滚动与图片加载都能在它
身上测到，又不碰网络、播放器与数据层。**

支撑事实（全部实测）：

1. **读接口已封装**：`History.getForDisplay()`（`bean/History.java:203`）
   一次调用即返回展示列表，内部已处理全部复杂性——非全局模式走
   `HistoryDao.find(cid)`，全局模式走 `findAll()` + `HistoryDisplayPolicy`
   投影 + `TmdbSeasonProgressDao` 聚合。桥接层只需调这一个静态方法，
   再按 bean 的 getter 扁平化，**不直接碰 DAO、不碰 schema、不复刻聚合
   策略**。`History` bean 本身 1442 行，但屏只用约 6 个 getter 与 2 个
   删除方法，其余播放续看逻辑一律留原生。
2. **展示字段已算好**：卡片 7 个显示点全部由 bean 方法直接给出——
   `getVodName`、`getSiteName`（已解析站点显示名/“其他配置”/“源不可用”）、
   `getSiteVisible`、`getVodRemarks`（与名字相同时原生隐藏）、
   `getPlaybackTimeText`（`Util.timeMs` 格式化）、`hasPlaybackTime`、
   `getPosition`/`getDuration`（进度条）。Dart 侧只渲染，不算、不拼串。
3. **删除语义已封装**：单条删走 `deleteDisplayItem()`、全删走
   `History.deleteForDisplay()`，内部经 `PlaybackProgressWriter` 处理
   聚合/跨 cid 删除。Dart 只传 `(cid, key)` 回 host，删完重取列表。
4. **入口唯一、电视端隔离**：手机端唯一入口是 `VodFragment:319` 的
   `searchHistory` 按钮（`HistoryActivity.start`）；电视端有独立的
   leanback `HistoryActivity`，C2 只改手机端调用点，TV 零影响。
5. **卡片即通用卡片**：`HistoryAdapter` 用的是共享布局 `adapter_vod.xml`（图 + 站点角标 + 播放时间角标 + 2dp 进度条 + 名字/集数信息区），与 Vod 列表同卡。历史页的 Flutter 卡片实现可直接复用到后续的收藏、分类列表屏。
6. **规模最小**：Activity + Adapter 共 345 行；对比收藏 351 行（但点卡
   片多一条异步加载路径）、分类 240 行（但背后是蜘蛛网络）、首页 1200 行
   复合壳、详情万行级。

## 3. 设计草图

### 3.1 开关与入口（沿用 C1 模式，新开关）

- **新开关**，不复用 C1 开关：`Setting` 里加 `isFlutterC2Enabled()` /
  `putFlutterC2Enabled(boolean)`，键 `flutter_c2_enabled`，默认 `false`。
  理由：C1 已验收通过，C2 必须能独立回滚而不动 C1 的验收状态；每阶段
  一个开关是评估文档 §7“已迁移屏逐个可逆”的直接落地。
- 开关 UI 只在 C0 实验屏：C1 开关旁再加一个 SwitchListTile（“用 Flutter
  打开历史记录”），走 C2 合约的开关方法（见 §3.3）。不进正式设置页。
- 唯一入口改动：`VodFragment:319` 查开关——开 →
  `C1ScreenActivity.start(activity, "/history")`；关 → 原样
  `HistoryActivity.start(...)`。其余调用点（leanback、Web 桥）不动。

### 3.2 宿主 Activity 与引擎（复用 C1 引擎，加一条不变量）

- 复用 `C1ScreenActivity` + `b8_c1_engine`，加 `ROUTE_HISTORY = "/history"`；
  在 `ensureFlutterEngine` 里同时注册 `C2Messages.C2HostApi`。不建第三个
  engine——C1 文档已接受双 engine 内存代价，第三个 engine 没有新增收益。
- **不变量（必须遵守）**：C1 引擎的路由在引擎创建时固定（`ensureFlutterEngine`
  只在缓存缺失时读 Intent；`onDestroy` 只在 `isFinishing()` 时销毁引擎）。
  因此同一时刻只能有一个 C1 引擎屏存活，且开新路由前前一屏必须已 finish
  （返回即 finish，引擎随之销毁）。历史屏符合该不变量：它是全屏屏，从
  首页进、返回即 finish；点卡片打开的详情/播放是原生 Activity，盖在上面
  时历史引擎不销毁、路由不变，返回后仍是历史。**若将来出现两个 Flutter
  屏叠加的需求，须另起 engine 或改 Dart 内导航，不得破坏此不变量。**
- Dart 路由：`main.dart` 的 `getInitialRoute()` 机制不动（C1 合约已提供），
  `routes` 表加 `'/history'`。C2 合约不需要自己的 `getInitialRoute`，
  也不需要 `closeScreen`（复用 C1 的；返回键走 `C1ScreenActivity` 已有
  的 fragment 转发）。C0 引擎上同样注册 C2 合约（只为实验屏的开关用，
  与 C1 在 C0 引擎上的做法一致）。

### 3.3 Pigeon C2 合约草图（只够历史屏用）

新文件 `flutter_module/pigeons/c2_messages.dart`，CI 内一次生成两边
（`b8-c0.yml` 的 Pigeon 步骤加一行，与 C1 同款；`.gitignore` 加
`C2Messages.java`）。**全部展示文案与计算值由 host 返回**，Dart 不硬编码
文案、不算时间格式、不拼站点名。方法名大小写以 Dart 定义为准，Java 侧
原样保留（C1 教训：`setSubtitleAutoMatch` 一案）；Java 侧 `bool`/`int`/
`double` 一律用装箱类型（`Boolean`/`Long`/`Double`，C1 教训）。

```dart
@HostApi()
abstract class C2HostApi {
  bool isC2Enabled();
  void setC2Enabled(bool enabled);

  /// 一次快照：列表 + 全部屏文案 + 网格规格，避免两次调用之间数据撕裂。
  C2HistoryScreen getHistory();

  /// 点卡片：host 按 (cid, key) 找回 History，调 HistoryResumeCoordinator.open。
  void openHistoryItem(int cid, String key);

  /// 单条删：host 调 deleteDisplayItem()；Dart 随后重取 getHistory()。
  void deleteHistoryItem(int cid, String key);

  /// 全删：host 内弹原生确认弹窗（含全局历史文案变体），确认后调
  /// History.deleteForDisplay()；Dart 在 resumed 时重取（弹窗异步，C1 同款）。
  void deleteAllHistory();

  /// 原生同步弹窗 SyncDialog.create().history()；Dart 在 resumed 时重取。
  void openHistorySync();

  /// 原生报表范围弹窗 -> ViewingReportActivity；无需重取。
  void openViewingReport();

  /// 空态主按钮：与原生 goHome 相同的 Intent flags 回首页。
  void goHome();
}

class C2HistoryScreen {
  String title;
  String emptyTitle; String emptyDescription; String emptyActionLabel;
  String syncTooltip; String deleteTooltip; String reportTooltip;
  int columnCount;        // host 按原生规则算：宽屏 Product.getColumn，否则 3
  int cardImageWidthPx; int cardImageHeightPx; // Product.getSpec 结果，Dart 按 dpr 换算
  List<C2HistoryItem> items;
}

class C2HistoryItem {
  String key; int cid;
  String vodName; String siteName; bool siteVisible;
  String remarks; bool remarksVisible; // host 算 name != remarks
  String playbackText; bool hasPlayback;
  int positionMs; int durationMs;      // 进度条；Java 侧 Long
  String picUrl;                       // host 解析后的干净 URL（§3.4）
  String picReferer; String picCookie; String picUserAgent; // 空 = 无
  String fallbackPicUrl;               // host 预算的 TmdbProxy 备用图，空 = 无
  String placeholderText; int placeholderColor; // 首字 + ColorGenerator.get400，失败占位用
}
```

- `List<C2HistoryItem>` 返回值与 `int`/`double` 字段的 Pigeon codegen
  形态**待 CI 证明**（C1 只证明了 `List<String>` 字段与 `Boolean`）；
  若生成形态有问题， fallback 是拆成 `getHistoryItems()` +
  `getHistoryScreenInfo()` 两次调用（§6 第 1 项）。
- 删除模式是 Dart 本地状态（长按切换、工具栏删除图标进入/确认、返回键
  先退出删除模式），与原生 `onBackInvoked` 行为一致；删除模式下的卡片
  遮罩用 material_ui 图标近似原生 `ic_vod_delete`，不追求像素一致。

### 3.4 图片加载（本屏最关键的设计点）

原生图片加载不是“拿 URL 直接下载”。`ImgUtil.load`（`utils/ImgUtil.java`）
的实际语义（实测）：

1. `ImgUtil.getUrl` 先 `UrlUtil.convert`，再解析 URL 里内嵌的
   `@Headers=`（JSON 头表）、`@Cookie=`、`@Referer=`、`@User-Agent=`
   后缀并剥离成请求头，另有豆瓣图 referer 策略（`ImageHeaderPolicy`）；
   `data:` URL 原样通过。
2. 失败时有 `failed` 集合记忆 + `TmdbProxy.nextAutoImageUrl` 自动备用链
   + 显式 fallback URL 链；最终失败显示首字 TextDrawable
   （`ColorGenerator.get400` 定色）。
3. 成功/失败还会更新 `TmdbProxy.RouteSelector` 的路由健康信号。

Dart 侧 `Image.network` 若直接拿原始 `vodPic` 会踩中两点：带 `@` 后缀
的 URL 非法/无请求头导致 403 或 404；无备用链与失败占位。因此：

- **host 负责解析**：`C2HostApiImpl` 把每条 `vodPic` 解析成干净 URL +
  头字段 + 备用 URL + 占位首字/颜色（即 `C2HistoryItem` 的图片字段）。
  解析逻辑的推荐落点是把 `ImgUtil.getUrl` 里的纯解析部分抽成一个静态
  方法返回“URL + 头表”，`getUrl` 改为委托它——`getUrl` 行为零变化，
  原生各屏不受影响；抽取方式是实施时决策，本文档不定死（备选是在 impl
  里复制约 20 行解析代码，但会漂移，不推荐）。
- **Dart 侧第一版用 `Image.network(url, headers: {...})`**，`errorBuilder`
  里先试 `fallbackPicUrl`（一次），再显示 host 给的首字占位。
  `Image.network` 只有内存缓存、无 Glide 式磁盘缓存，滑回去是否重新下载
  以真机对照为准（§4 第 9 项）；若不可接受，后续再评估引入
  `cached_network_image`（新 Dart 依赖，需 pin 版本并过 CI，C2 第一版
  不引入）。
- **已知缺口**：Flutter 屏的图片成功/失败不会更新 `TmdbProxy.RouteSelector`
  的路由健康信号（原生 `ImgUtil` 的 listener 才做）。实验阶段接受；若
  后续 Flutter 屏成为主流入口，须补一条 host 回报调用。

### 3.5 刷新语义（与原生的有意偏差）

- 原生靠 EventBus：`RefreshEvent.HISTORY`（播放写进度、备份恢复、远程
  同步、服务端 Action 均会 post）与 `ConfigEvent`（切配置后刷新站点名）。
  Dart 订阅不了 EventBus。设计：**Dart 在初次加载 + 每次 `resumed` 时
  重取 `getHistory()`**（C1 字幕页同款），覆盖“播完返回、同步弹窗关闭、
  全删确认、切后台再回”全部常见路径。
- 偏差：Flutter 历史屏在前台时，后台同步写入不会实时刷新列表（原生会）。
  实验阶段接受，记入 §4 验收备注；不为此在 C2 引入 EventChannel/轮询。

### 3.6 有意接受的偏差（C1 同款处理）

1. **无跑马灯**：原生只在滚动停止时让可见行的名字/集数跑马灯
   （`setMarqueeRange` + `setSelected`）。Flutter 版用单行居中省略，
   不复刻跑马灯。
2. **卡片几何视觉对照，不逐像素**：列数与图尺寸由 host 给（§3.3），
   信息区高度、圆角（`Vod.Grid` shape）、半透明黑底与恒定亮色文字
   （`webhtvColorOnWallpaper`，`adapter_vod.xml` 有对比度事故注释）
   在 Flutter 侧按观感复刻，验收以 Wei 目视“一致”为准。
3. **删除确认/同步/报表弹窗全部留原生**：与 C1“复杂子流程留原生”一致，
   Dart 只发调用。

## 4. 逐项对照清单（Wei 真机验收用）

先在实验屏打开 C2 开关，再从首页搜索条旁的历史入口进 Flutter 历史页，
每项与原生（开关关闭后）逐条比：

**列表与卡片**
1. 标题、同步/删除/报表三个菜单图标与原生一致；空态时标题 + 解释 +
   一个主动作（“去首页挑一部”）与原生一致，点主动作回首页。
2. 列数与原生一致（手机 3 列；宽屏/平板按原生规则）；卡片图尺寸、
   圆角、站点角标、播放时间角标（`mm:ss / mm:ss`）、2dp 进度条位置一致。
3. 名字与原生一致；集数行在与名字相同时隐藏、不同时显示；站点行在无
   站点名时隐藏；“其他配置”与“源不可用”文案与原生一致。
4. 进度条比例与原生一致（播完/未播/接近播完各一条）；无时长时不显示
   播放时间角标。
5. 旋转、切后台再回、划掉重进：不崩溃，列表与状态还在。

**删除模式**
6. 长按进删除模式（卡片出现删除遮罩、播放时间角标隐藏）；返回键先退出
   删除模式，再按才退出页面；工具栏删除图标在删除模式下弹原生确认弹窗
   （含全局历史时的文案变体），确认后列表清空、回到空态。
7. 删除模式下点卡片删单条；删到 0 条时自动退出删除模式；退出重进后
   删除结果保持（与原生 Room 写入一致）。

**弹窗与跳转**
8. 同步图标弹原生同步弹窗；报表图标弹原生范围弹窗、进报表页；点卡片
   正常续播/进详情（与原生 `HistoryResumeCoordinator` 行为一致）；播完
   返回后列表顺序与进度已更新（resumed 重取）。

**滚动与图片加载对照（C2 新增验收）**
9. 快速上下滚动：无明显掉帧感（与原生主观对照）；滑回去时图片不闪烁/
   不长时间空白（与原生 Glide 对照，记录差异）。
10. 含 `@Referer=`/`@Headers=` 等后缀的图片正常加载（与原生对照）；
    失败图片显示首字占位（颜色与原生 `ColorGenerator` 一致）；豆瓣图
    正常加载。
11. 大列表（Wei 实际历史条数）下首次打开速度与原生主观对照。

**开关与回滚**
12. 开关关掉后，历史入口回到原生页；电视端历史页不受影响（无 Flutter 入口）。

## 5. 回滚步骤

- 开关级：实验屏关掉 C2 开关，历史入口立即回到原生；Flutter 代码留在
  分支里不生效。C1 开关与屏不受影响。
- 分支级：直接丢弃 `b8-flutter-rewrite` 分支，`main` 不受任何影响（C2
  从未合入）。无数据层变更，无需数据回滚；开关布尔键残留无害。

## 6. 未能验证的项（全部标注“待 CI 证明 / 待真机证明”）

| # | 待证明项 | 证明方式 | 关联门 |
| --- | --- | --- | --- |
| 1 | Pigeon 能生成 `List<C2HistoryItem>` 返回值与 `Long`/`Double` 字段的 Dart + Java 两边，且手写 impl 签名一致 | CI：`dart run pigeon` + `flutter analyze` + Gradle 编译 | G6/G7 |
| 2 | `Image.network` + host 解析头字段能加载全部历史图片（含 `@` 后缀 URL、豆瓣图）；备用链与占位行为与原生一致 | Wei 真机对照（§4 第 10 项） | — |
| 3 | 滚动性能与滑回图片表现与原生主观相当（无磁盘缓存的影响） | Wei 真机对照（§4 第 9、11 项） | — |
| 4 | 复用 C1 引擎 + `/history` 路由无“旧路由残留”（§3.2 不变量成立） | 真机：进历史 → 返回 → 进关于/字幕 → 返回 → 再进历史，各屏路由正确 | — |
| 5 | `resumed` 重取覆盖播完返回/同步/全删/切配置后的刷新 | Wei 真机验收（§4 第 8 项） | — |
| 6 | `ImgUtil.getUrl` 解析抽取（如实施）后原生各屏图片行为零变化 | CI 构建 + 原生屏图片目视 | — |
| 7 | §4 对照清单逐项通过；开关关闭后回到原生 | Wei 真机验收 | 验收 |
| 8 | 4 风味 release 构建通过；leanback 构建不受影响 | CI（`b8-c0.yml`） | G6 |

## 7. Recovery anchor（恢复锚点）

- 目标：C2 选屏调研（见 §0 完成句）；评估见
  `docs/b8-flutter-rewrite-assessment.md`，C0/C1 见 `docs/b8-c0-shell.md` /
  `docs/b8-c1-about-subtitle.md`。
- 状态：调研完成；推荐第一屏为手机端历史页（§2）；设计草图见 §3；
  待决策是否按此草图实施 C2。无产品代码改动。
- 分支/HEAD：`b8-flutter-rewrite`，基线 `51f15a7`；本文档为本次唯一新增文件。
- 变更文件：仅 `docs/b8-c2-scoping.md`（新建）。
- 验证：`git diff --check` 通过；diff 仅含本文档；`main` 仍指向 `f39a6e0`；
  工作树干净；构建/真机验证全部待 CI（§6）。
- 未解决风险：§6 的 8 项待证明项；最大三项见汇报（图片语义对等、
  Pigeon 新形态 codegen、单引擎路由不变量 + 刷新语义偏差）。
- 下一步：Wei（或父代理）确认按 §2–§3 实施历史页迁移后，另开一个
  task_guard standard 会话实施 C2，本文档不直接转为实施。
