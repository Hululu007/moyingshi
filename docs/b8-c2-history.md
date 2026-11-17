# B8 C2：历史页迁移到 Flutter（实施记录）

> 状态：**已实施（本地提交，未推送，未经 CI/真机验证）**。评估与阶段定义见
> `docs/b8-flutter-rewrite-assessment.md`（§5 方案 C、§6 后续阶段 go/no-go、
> §7 回滚路径）；选屏调研与设计草图见 `docs/b8-c2-scoping.md`（本文档按它
> 实施，全部偏差见 §2.9）；C0/C1 已验证形态见 `docs/b8-c0-shell.md` 与
> `docs/b8-c1-about-subtitle.md`。分支：`b8-flutter-rewrite`；基线：HEAD
> `04c2d959`（含调研文档）；`main` 未动。
>
> **诚实声明**：本机无 Java、Android SDK、Flutter/Dart，一切构建与运行结论
> 均未在本地验证。所有“待 CI 证明 / 待真机证明”项见 §6，不得视为已验证。

## 0. §1 执行声明（仓库 AGENTS.md）

- 完成句：C2 在 `b8-flutter-rewrite` 上把手机端历史页迁移到 Flutter——
  新 Pigeon C2 合约 + `C2HistoryPage`（`/history` 路由，复用 C1 引擎）+
  功能开关控制原生/Flutter 切换（默认原生）+ C2 任务文档，本地提交，
  不推送，不动 `main`。
- 允许路径：`flutter_module/pigeons/c2_messages.dart`、
  `flutter_module/lib/c2_history_page.dart`、
  `flutter_module/lib/main.dart`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C2HostApiImpl.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java`、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/VodFragment.java`、
  `.gitignore`、`.github/workflows/b8-c0.yml`、`docs/b8-c2-history.md`。
  `ImgUtil.java` 有意不在作用域内：实施决定不改它（见 §2.5）。
- 预存脏文件：无（开始时工作树干净）。
- 时间/时区：2026-11-17 10:42 CST（Asia/Shanghai）开始。
- 工期估计：源码复核与 Pigeon 形态核实 ~60 分钟 + Dart/Java ~60 分钟 +
  文档验证提交 ~30 分钟，约 13:10 CST 完成；构建与真机验证在 CI/手机上，
  不在本会话内。
- 最便宜的决定性验证：`git diff --check` + 作用域 diff 复核 + `main` 未动
  检查 + 提交后工作树干净。构建验证只能走 CI（门 G6/G7）。

## 1. 选屏与理由

调研结论见 `docs/b8-c2-scoping.md` §1–§2，Wei 已批准按此实施。复述要点：

- **历史页**（手机端 `HistoryActivity` 205 行 + `HistoryAdapter` 140 行）：
  单屏、单本地数据源（`History.getForDisplay()` 一次调用）、读接口与删除
  语义均已封装、卡片含图含进度条——C2 要证明的滚动与图片加载都能测到，
  又不碰网络、播放器与数据层。
- 首页（1200 行复合壳）、详情（万行级 + 继承 `PlaybackActivity`，C3 的事）、
  分类（蜘蛛网络 + 分页 + 筛选）、搜索/聚合搜索（异步多站）均不适合做
  第一屏；收藏与历史同构，定为天然第二屏。
- **移动端限定**：Flutter 入口只接在手机端 `VodFragment` 的历史按钮上。
  电视端有独立的 leanback `HistoryActivity`，C2 不碰，TV 零影响。

## 2. 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- **新开关**，不复用 C1 开关：`Setting` 里加 `isFlutterC2Enabled()` /
  `putFlutterC2Enabled(boolean)`，键 `flutter_c2_enabled`，默认 `false`。
  C1 已验收通过，C2 必须能独立回滚而不动 C1 的验收状态。
- 开关 UI 只在 C0 实验屏：C1 开关旁再加一个 SwitchListTile（“用 Flutter
  打开历史记录”），读/写走 C2 合约的 `isC2Enabled()` / `setC2Enabled(bool)`。
  开关只在实验屏出现，不进正式设置页。
- 唯一入口改动：`VodFragment` 的 `searchHistory` 点击查开关——开 →
  `C1ScreenActivity.start(activity, "/history")`；关 → 原样
  `HistoryActivity.start(...)`。其余调用点不动。
- 回滚 = 关开关（实验屏上点一下），或删分支；原生实现一行未删。

### 2.2 宿主 Activity 与引擎（复用 C1 引擎，不变量）

- 复用 `C1ScreenActivity` + `b8_c1_engine`，加 `ROUTE_HISTORY = "/history"`；
  `ensureFlutterEngine` 里同时注册 `C2Messages.C2HostApi`。不建第三个
  engine。C0 引擎上同样注册 C2 合约（只为实验屏的开关用，与 C1 做法一致）。
- **不变量**：C1 引擎的路由在引擎创建时固定，同一时刻只能有一个 C1 引擎屏
  存活，开新路由前前一屏必须已 finish。历史屏符合该不变量（全屏屏，返回
  即 finish；点卡片打开的详情/播放是原生 Activity，盖在上面时引擎不销毁、
  路由不变）。若将来出现两个 Flutter 屏叠加的需求，须另起 engine 或改
  Dart 内导航，不得破坏此不变量。
- C2 不需要自己的 `getInitialRoute` / `closeScreen`，复用 C1 的：路由分发
  仍走 `main()` 启动时的 `C1HostApi.getInitialRoute()`，返回键关闭走
  `C1HostApi.closeScreen()`。

### 2.3 Dart 路由与返回键

- `main.dart` 的 `routes` 表加 `'/history'`；其余路由机制不动。
- 返回键行为对齐原生 `onBackInvoked`：删除模式下先退出删除模式，再按才
  退出页面。AppBar 返回按钮与系统返回键共用同一处理：系统返回键经
  `PopScope(canPop: false, onPopInvokedWithResult: ...)` 拦截（该形态在
  本嵌入方式下是否生效待真机证明，见 §6）。若拦截不生效，最坏情况是系统
  返回键直接退出页面（删除模式优先退出只对 AppBar 按钮成立）。

### 2.4 Pigeon C2 合约（最小扩展，只够历史屏用）

新文件 `flutter_module/pigeons/c2_messages.dart`，CI 内一次生成 Dart +
Java 两边。数据类只做扁平字段；**全部展示文案与计算值由 host 返回**
（`getString` 解析后的当前语言文案 + bean getter 算好的显示值），Dart
侧不硬编码文案、不算时间格式、不拼站点名。Java 侧 `bool`/`int` 一律用
装箱类型（`Boolean`/`Long`，C1 教训）；方法名与 Dart 定义字符级一致，
绝不推断大小写归一（C1 的 `setSubtitleAutoMatch` 一案）。

```dart
@HostApi()
abstract class C2HostApi {
  bool isC2Enabled();
  void setC2Enabled(bool enabled);
  C2HistoryScreen getHistory();            // 一次快照：列表 + 文案 + 网格规格
  void openHistoryItem(int cid, String key);
  void deleteHistoryItem(int cid, String key);
  @async
  void deleteAllHistory();                 // 见 §2.8：弹窗关闭时才完成
  void openHistorySync();
  void openViewingReport();
  void goHome();
}

class C2HistoryScreen {
  String title;
  String emptyTitle; String emptyDescription; String emptyActionLabel;
  String syncTooltip; String deleteTooltip; String reportTooltip;
  int columnCount;
  int cardImageWidthPx; int cardImageHeightPx; // host 按原生规则算，Dart 按 dpr 换算
  List<C2HistoryItem> items;
}

class C2HistoryItem {
  String key; int cid;
  String vodName; String siteName; bool siteVisible;
  String remarks; bool remarksVisible; // host 算 name != remarks（原生 remark INVISIBLE 语义）
  String playbackText; bool hasPlayback;
  int positionMs; int durationMs;      // 进度条；Java 侧 Long
  String picUrl;                       // host 解析后的干净 URL（§2.5）
  String picReferer; String picCookie; String picUserAgent;
  String picExtraHeadersJson;          // 偏差 D1：其余 @Headers= 条目（JSON），空 = 无
  String fallbackPicUrl;               // host 预算的 TmdbProxy 备用图，空 = 无
  String placeholderText; int placeholderColor; // 首字 + ColorGenerator.get400
}
```

- `List<C2HistoryItem>` 返回值与 `Long` 字段的 codegen 形态**待 CI 证明**
  （C1 只证明了 `List<String>` 字段与 `Boolean`）；fallback 是拆成两次调用。
- `@async` 的 Java 形态已按 Pigeon 官方文档核实：`@async` 的 `void` 方法
  在 Java 侧生成 `VoidResult` 回调（Pigeon v16.0.0 起，`void success()` 无参
  + `void error(Throwable)`），`C2HostApiImpl.deleteAllHistory` 按此签名手写；
  仍待 CI 证明（§6 第 2 项）。
- 删除模式是 Dart 本地状态（长按切换、工具栏删除图标进入/确认、返回键
  先退出删除模式），与原生 `onBackInvoked` 行为一致；删除模式下的卡片
  遮罩用半透明黑底 + material_ui 删除图标近似原生 `ic_vod_delete`，不追求
  像素一致。集数行隐藏时保持占位（原生 `INVISIBLE`），Dart 用透明文字实现。

### 2.5 图片加载：委托 ImgUtil，不抽取、不复制

原生图片语义（实测 `ImgUtil.getUrl`）：`UrlUtil.convert` 后解析 URL 内嵌的
`@Headers=`（JSON 头表）、`@Cookie=`、`@Referer=`、`@User-Agent=` 后缀并剥离
成请求头，另有豆瓣图 referer 策略；失败时走 `TmdbProxy.nextAutoImageUrl`
备用链，最终显示首字 TextDrawable（`ColorGenerator.get400` 定色）。

调研文档推荐把 `getUrl` 的纯解析部分抽成静态方法。实施决定**不抽取**，
理由：`ImgUtil.getUrl(String)` 已是公开静态方法，返回的 `GlideUrl` 自带
干净 URL（`getStringURL()`）与最终头表（`getHeaders()`）——host 直接委托它，
解析逻辑零复制、零漂移，`getUrl` 行为零变化，原生各屏零风险。
`ImgUtil.java` 因此未动，也不在本次作用域内。

- host 每条 `vodPic` 经 `getUrl` 解析成干净 URL + 头字段 + 备用 URL
  （`nextAutoImageUrl` 的返回值同样过 `getUrl` 清洗）+ 占位首字/颜色；
  头表拆成 referer/cookie/user-agent 三个命名字段，其余条目进
  `picExtraHeadersJson`（偏差 D1，见 §2.9）。单条解析失败只影响该条
  （降级为占位图），不拖垮整屏——与原生 `load()` 的 catch 路径一致。
- Dart 第一版用 `Image.network(url, headers:)`，`errorBuilder` 里先试
  `fallbackPicUrl`（一次），再显示首字占位；不引入新 Dart 依赖。
  `Image.network` 只有内存缓存、无 Glide 式磁盘缓存，滑回去是否重新下载
  以真机对照为准（§4 第 9 项）。
- **已知缺口**：Flutter 屏的图片成功/失败不会更新
  `TmdbProxy.RouteSelector` 的路由健康信号（原生 `ImgUtil` 的 listener
  才做）；`data:` URL 在 Dart 侧显示占位图（`Image.network` 无法加载
  data: URI）。实验阶段接受，见 §6。

### 2.6 网格几何：host 给数，算法复刻（附漂移警告）

原生列数与卡片尺寸由手机端 `Product` + `MobileWindow` 算出，但这两个类
都在 mobile 源码集（leanback 的 `Product` 签名不同、且无 `MobileWindow`），
main 源码集的 `C2HostApiImpl` 无法引用——引用即导致 leanback 构建失败。
因此 host 内复刻了它们的算式（逐行对照，注释中标明来源）：列数 =
宽屏时 `|PlayerSetting.getSize() - (7 + 是否平板)|`、否则 3；卡片宽 =
（窗口宽 − `dp2px(32) + dp2px(16*(列数−1))` − 刘海）/ 列数；卡片高 =
宽 / `Style.rect()` 的 0.75。刘海取法含原版的按位或写法，原样复刻，未
“修正”。**若 mobile 原版改动，此处必须同步改**，见 §6。

Dart 按 `devicePixelRatio` 把 host 的 px 换算成逻辑像素；网格用固定
行高（图高 + 2dp 进度条 + 52 固定信息区高），行高固定保证不溢出。
信息区 52 是 Dart 侧固定值（原生为内容高，接近但未逐像素对齐），见 §2.9。

### 2.7 同步弹窗：反射调用（SyncDialog 只在 mobile 源码集）

`SyncDialog` 只存在于 mobile 源码集，main 源码集的 impl 无法直接引用。
`openHistorySync()` 用反射调用 `HistoryActivity.onSync()` 的同一链：
`SyncDialog.create().history().show(activity)`。安全依据有二：
`proguard-rules.pro` 有 `-keep class com.github.moyingshi.osc.tv.** { *; }`，
release 构建不改名；仓库内已有反射先例（`Startup`、`JarLoader`）。
leanback 上此屏无入口，失败不可达，吞错处理与 `ImgUtil` 的 catch 风格一致。

### 2.8 刷新语义：resumed 重取 + deleteAllHistory 的 @async 修正

- 原生靠 EventBus（`RefreshEvent.HISTORY` / `ConfigEvent`），Dart 订阅不了。
  设计仍是初次加载 + 每次 `resumed` 重取（C1 字幕页同款），覆盖播完返回、
  报表页返回、切后台再回。
- **对调研文档 §3.5 的修正**：原生弹窗（删除确认、同步、报表范围）不会
  触发 Activity 的 pause/resume，因此“弹窗关闭后 resumed 重取”对弹窗
  并不成立——调研文档的该表述是错的。删除确认是必经路径（确认后列表
  必须清空），故 `deleteAllHistory()` 改为 `@async`：host 在弹窗
  `onDismiss`（一切关闭路径）时完成回调，Dart `await` 后重取，必定看到
  弹窗关闭后的状态。同步弹窗仍走 resumed 重取（经反射的 DialogFragment
  不便挂 dismiss 钩子），同步结果在下次 resume 时出现——已知缺口，见 §6。
- 前台时后台同步写入不会实时刷新列表（原生会）。实验阶段接受，不为此
  引入 EventChannel/轮询。

### 2.9 相对调研文档的全部偏差

| # | 偏差 | 理由 |
| --- | --- | --- |
| D1 | 新增 `picExtraHeadersJson` 字段 | `@Headers=` JSON 可含 referer/cookie/user-agent 之外的头；只留三个命名字段会静默丢头。JSON 承载剩余条目，Dart 合并时命名字段优先 |
| D2 | `deleteAllHistory()` 改为 `@async` | 见 §2.8：弹窗不触发 lifecycle，同步调用 + resumed 重取无法保证确认后刷新 |
| D3 | 未抽取 `ImgUtil.getUrl` 解析逻辑，改委托 `getUrl` + `GlideUrl` | 见 §2.5：零复制、零漂移、零行为变化；`ImgUtil.java` 未动 |
| D4 | 网格几何算法在 impl 内复刻 + 钳制（列数 ≥ 1、宽高 ≥ 1） | 见 §2.6：mobile 类无法引用；钳制只防退化偏好导致 Dart 布局异常，原生同条件下亦会崩溃 |
| D5 | 同步弹窗经反射打开 | 见 §2.7：`SyncDialog` 只在 mobile 源码集 |
| D6 | `data:` 图片显示占位图 | `Image.network` 无法加载 data: URI；原生 Glide 可以。罕见路径，接受缺口 |
| D7 | Dart 信息区高度固定 52 逻辑像素 | 固定行高网格要求行高可算；原生内容高接近此值，视觉对照为准 |
| D8 | 系统返回键经 `PopScope` 拦截 | 对齐原生 `onBackInvoked` 的“先退删除模式”；嵌入方式下是否生效待真机证明 |

### 2.10 哪些东西明确不做

- 不碰 Room/设置/历史 schema（评估文档 §7 硬性前提）；只读已有
  `History` bean + 1 个新开关布尔键；删除走 bean 已有方法。
- 不碰播放器、蜘蛛、实验室、小程序；不改 `C0HostApi` / `C1HostApi` 合约
  与实现（C0/C1 冻结）；不改 `History.java`、`HistoryActivity`、
  `HistoryAdapter`、`HistoryResumeCoordinator`。
- 不新增 Dart 依赖；不改 Flutter/Pigeon 版本锁定。
- 不改 `android-release.yml`；C2 构建仍走 `b8-c0.yml`（只加 Pigeon 生成命令）。
- 不改任何电视端代码与布局；不改 `ImgUtil.java`。

### 2.11 leanback 构建隐患修复（2026-11-17，任务 b8-c2-leanback-fix）

§2.9 实施时留下的隐患：`C2HostApiImpl.getHistory()` 直接引用了 3 个只在
mobile res 存在的字符串——`R.string.app_history`（标题）、
`R.string.menu_sync` / `R.string.menu_delete`（工具栏提示）。leanback
变体的 R 类由 main + leanback 资源合并生成，不含 mobile res，故这 3 处
在 leanback 编译时不存在；C2 从未经过 CI，联合推送时 `b8-c0.yml` 的
leanback 风味构建会在此失败。

修复方式取 B2 文档 §2.11 的选项 (a)：3 处改为 `mobileString()` 运行时
按名解析（B2 的 V4 同款），`C2HostApiImpl` 内新增私有的 `mobileString()`
helper、照 B2 的实现复刻——不跨 impl 共享 helper（每批独立 revert 的纪律）。
查不到时的兜底文案均为 main res 文案（非空串，leanback 上不可达，因
此屏无 leanback 入口）：`app_history` → `setting_home_history`、
`menu_sync` → `sync_start`、`menu_delete` → `setting_delete`。

全量排查（同一会话，flutter/*.java 全部 `R.*` 引用，逐个按目录核对归属，
共 35 个 distinct `R.string`）：除上述 3 个外，其余 32 个均在 main res
或 leanback res。`dialog_delete_record`（本文件 :125）与
`dialog_delete_history`（:126）经核实 leanback res 也有（`values/` +
`values-zh-rCN/`，与 mobile 同值语义），可直接引用，无需改；B2 的
`dialog_delete_record`（`B2HostApiImpl.java:223`）同理安全，B2 本批无需改动。
C0/C1 的引用已随 CI run #11 四风味构建成功而证明无碍。`R.layout` /
`R.id` 的两处引用（`activity_b8_c0` / `activity_b8_c1` +
`fragment_container`）同样已随 CI 证明。两 impl 的其余 import 均为 main
源码集类（`HistoryResumeCoordinator`、`ViewingReportActivity`、
`ViewingReportRangeDialog` 已核实位置）或两端源码集均有类
（`HomeActivity` / `SearchActivity` / `VideoActivity`），无其他
mobile-only 引用。

仍待 CI 证明（§6 第 13 项）：本修复只去掉了编译期障碍，leanback 风味
是否真正构建通过以联合推送的 CI 为准。

## 3. 文件清单

新增（`*`）与修改（`M`）。全部 UTF-8 文本，无二进制文件。

```
* flutter_module/pigeons/c2_messages.dart   C2 合约（§2.4，含 @async 方法）
* flutter_module/lib/c2_history_page.dart   历史页（Flutter：网格 + 卡片 + 删除模式）
M flutter_module/lib/main.dart              路由表加 /history；C0 实验屏加 C2 开关
* app/src/main/java/com/github/moyingshi/osc/tv/flutter/C2HostApiImpl.java
                                            C2 合约实现（快照/图片解析/几何/弹窗/跳转）
M app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java
                                            加 ROUTE_HISTORY；注册 C2HostApi
M app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java
                                            C0 引擎上也注册 C2HostApi（只为实验屏的开关用）
M app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java
                                            + isFlutterC2Enabled / putFlutterC2Enabled
M app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/VodFragment.java
                                            历史入口查开关分流（1 处 + 1 个 import）
M .gitignore                                + C2Messages.java（CI 生成，不提交）
M .github/workflows/b8-c0.yml               Pigeon 步骤加 c2_messages.dart 生成 + 存在性检查
* docs/b8-c2-history.md                     本文档
```

CI 生成、永不提交的文件（已在 `.gitignore` 中）：

```
flutter_module/lib/pigeon/c2_messages.g.dart
app/src/main/java/com/github/moyingshi/osc/tv/flutter/C2Messages.java
```

## 4. 逐项对照清单（Wei 真机验收用）

先在实验屏打开 C2 开关，再从首页搜索条旁的历史入口进 Flutter 历史页，
每项与原生（开关关闭后）逐条比：

**列表与卡片**
1. 标题、同步/删除/报表三个菜单图标与原生一致；空态时标题 + 解释 +
   一个主动作与原生一致，点主动作回首页。
2. 列数与原生一致（手机 3 列；宽屏/平板按原生规则）；卡片图尺寸、
   圆角、站点角标、播放时间角标（`mm:ss / mm:ss`）、2dp 进度条位置一致。
3. 名字与原生一致；集数行在与名字相同时隐藏、不同时显示；站点行在无
   站点名时隐藏；“其他配置”与“源不可用”文案与原生一致。
4. 进度条比例与原生一致（播完/未播/接近播完各一条）；无时长时不显示
   播放时间角标。
5. 旋转、切后台再回、划掉重进：不崩溃，列表与状态还在。

**删除模式**
6. 长按进删除模式（卡片出现删除遮罩、播放时间角标隐藏）；返回键先退出
   删除模式，再按才退出页面（AppBar 返回键与系统返回键都应如此）；
   工具栏删除图标在删除模式下弹原生确认弹窗（含全局历史时的文案变体），
   确认后列表清空、回到空态；取消后仍在删除模式。
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

本机无 Flutter SDK，Dart/Java 代码均未编译。以下每一项都必须在 CI 或真机上
证明后才能视为成立：

| # | 待证明项 | 证明方式 | 关联门 |
| --- | --- | --- | --- |
| 1 | `dart run pigeon --input pigeons/c2_messages.dart` 能生成 Dart + Java 两边（含 `List<C2HistoryItem>` 字段、`Long` 字段、`@async` 方法） | CI 步骤 `test -f` 两处生成物 | G6/G7 |
| 2 | 生成的 Java 接口形态与 `C2HostApiImpl` 手写实现一致（方法签名、`Long`/`Boolean` 装箱、`VoidResult` 回调、`setUp` 形态、Builder 形态） | Gradle 编译通过 | G6/G7 |
| 3 | 生成的 Dart 客户端形态与 `main.dart` / `c2_history_page.dart` 的手写调用一致 | `flutter analyze` + Gradle 编译通过 | G6/G7 |
| 4 | `PopScope` 拦截在 cached-engine `FlutterFragment` 嵌入方式下生效（系统返回键先退删除模式） | Wei 真机验收（§4 第 6 项） | — |
| 5 | 复用 C1 引擎 + `/history` 路由无“旧路由残留”（§2.2 不变量成立） | 真机：进历史 → 返回 → 进关于/字幕 → 返回 → 再进历史，各屏路由正确 | — |
| 6 | `Image.network` + host 解析头字段能加载全部历史图片（含 `@` 后缀 URL、豆瓣图）；备用链与占位行为与原生一致；`data:` URL 显示占位（已知缺口） | Wei 真机对照（§4 第 10 项） | — |
| 7 | 滚动性能与滑回图片表现与原生主观相当（无磁盘缓存的影响） | Wei 真机对照（§4 第 9、11 项） | — |
| 8 | 网格几何与原生一致（含宽屏/平板/刘海机型；复刻算式未漂移） | Wei 真机对照（§4 第 2 项） | — |
| 9 | 反射打开的同步弹窗在 release 构建中正常出现（R8 keep 生效） | Wei 真机验收（§4 第 8 项） | — |
| 10 | `resumed` 重取覆盖播完返回/报表返回/切配置后的刷新；同步弹窗关闭后的刷新缺口如 §2.8 所述 | Wei 真机验收（§4 第 8 项） | — |
| 11 | 删除确认弹窗的 `@async` 完成语义：确认后列表清空、取消后仍在删除模式；弹窗关闭前旋转不挂起 Dart 等待 | Wei 真机验收（§4 第 6 项） | — |
| 12 | §4 对照清单逐项通过；开关关闭后回到原生 | Wei 真机验收 | 验收 |
| 13 | 4 风味 release 构建通过；leanback 构建不受影响（main 源码集的 impl 不引用 mobile-only 类） | CI（`b8-c0.yml`） | G6 |
| 13a | leanback 隐患已修（§2.11：3 处直接引用改为 `mobileString()`），仍待 CI 证明 leanback 风味构建通过 | CI（`b8-c0.yml`） | G6 |
| 14 | R8/minify 下 C2 的 Pigeon 生成代码无混淆问题 | release 构建 + 真机运行 | G6 |

## 7. Recovery anchor（恢复锚点）

- 目标：C2 历史页迁移（见 §0 完成句）；评估见
  `docs/b8-flutter-rewrite-assessment.md`，调研见 `docs/b8-c2-scoping.md`，
  C0/C1 见 `docs/b8-c0-shell.md` / `docs/b8-c1-about-subtitle.md`。
- 状态：已实施并本地提交；未推送；未 CI 构建；未真机验证。下一步唯一动作：
  父代理决定推送分支后，CI 自动跑 `b8-c0.yml`，Wei 按 §4 验收。
- 分支/HEAD：`b8-flutter-rewrite`，基线 `04c2d959`（调研文档提交）；
  C2 提交见提交记录（`git log --oneline -3`）。
- 变更文件：见 §3 文件清单；无产品行为改动（默认开关关闭时零行为变化），
  无数据层变更；`ImgUtil.java`、`History.java`、原生历史屏、C0/C1 合约均未动。
- 验证：`git diff --check` 通过；`main` 仍指向 `f39a6e0`；工作树干净；
  构建/真机验证全部待 CI（§6）。
- 未解决风险：§6 的 14 项待证明项；最大两项是 Pigeon 新形态 codegen
  （`List<C2HistoryItem>` + `Long` + `@async VoidResult`）与图片语义对等
  （头解析/备用链/占位/无磁盘缓存）；任一关键项失败即按 §5 回滚或停在 C2。
- 下一步：推送分支 → CI 构建 → Wei 真机验收（§4）→ 决定是否继续 C2
  内其他屏（收藏为天然第二屏）或进入 C3。
