# B8 B2：收藏页迁移到 Flutter（实施记录）

> 状态：**已实施（本地提交，未推送，未经 CI/真机验证）**。评估与阶段定义见
> `docs/b8-flutter-rewrite-assessment.md`（§5 方案 C、§6 后续阶段 go/no-go、
> §7 回滚路径）；全量批次规划见 `docs/b8-full-migration-plan.md`（§3 的 B2
> 范围句 + §4 共性决策全部适用；本文档按它实施，全部偏差见 §2.10）；
> C0/C1/C2 已验证形态见 `docs/b8-c0-shell.md`、`docs/b8-c1-about-subtitle.md`、
> `docs/b8-c2-history.md`（§2.9 的 D1–D8 是既定决策，必须沿用）。分支：
> `b8-flutter-rewrite`；基线：HEAD `5a82e71e`（全量规划文档提交）；
> `main` 未动。
>
> **诚实声明**：本机无 Java、Android SDK、Flutter/Dart，一切构建与运行结论
> 均未在本地验证。所有“待 CI 证明 / 待真机证明”项见 §6，不得视为已验证。

## 0. §1 执行声明（仓库 AGENTS.md）

- 完成句：B2 在 `b8-flutter-rewrite` 上把手机端收藏页迁移到 Flutter——
  新 Pigeon B2 合约 + `B2KeepPage`（`/keep` 路由，复用 C1 引擎）+ 新共享
  组件 `VodCard` / `PicImage`（只加法）+ 功能开关控制原生/Flutter 切换
  （默认原生）+ B2 任务文档，本地提交，不推送，不动 `main`。
- 允许路径：`flutter_module/pigeons/b2_keep_messages.dart`、
  `flutter_module/lib/b2_keep_page.dart`、
  `flutter_module/lib/widgets/vod_card.dart`、
  `flutter_module/lib/widgets/pic_image.dart`、
  `flutter_module/lib/main.dart`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B2HostApiImpl.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java`、
  `app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java`、
  `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/VodFragment.java`、
  `.gitignore`、`.github/workflows/b8-c0.yml`、`docs/b8-b2-keep.md`。
- 预存脏文件：无（开始时工作树干净）。
- 时间/时区：2026-11-17 10:45 CST（Asia/Shanghai）开始。
- 工期估计：只读发现 ~25 分钟 + Dart/Java ~70 分钟 + 文档验证提交
  ~30 分钟，约 12:50 CST 完成；构建与真机验证在 CI/手机上，不在本会话内。
- 最便宜的决定性验证：`git diff --check` + 作用域 diff 复核（共享文件只含
  加法行）+ `main` 未动检查 + 提交后工作树干净。构建验证只能走 CI（门 G6/G7）。

## 1. 选屏与理由

规划文档 §3 的 B2 范围句：**收藏页：网格 + 删除模式 + 点卡片异步加载/
重试/搜索 fallback**。复述要点：

- **收藏页**（手机端 `KeepActivity` 220 行 + `KeepAdapter` 131 行）：
  单屏、单本地数据源（`Keep.getVod()` 一次调用，DAO 限定 `type = 0` 按
  `createTime DESC`）、删除模式 1 个布尔值、卡片与历史页同布局
  （`adapter_vod.xml`，但 remark/playback/progress 三项绑 GONE，无进度条）——
  是历史页的天然第二屏，复用历史卡片形态与合约形态。
- B2 首次证明的新东西：**异步加载错误态**——点卡片走 `VodConfig.load`
  异步加载，失败重试、无配置 fallback 到搜索页（见 §2.8）。
- **移动端限定**：Flutter 入口只接在手机端 `VodFragment` 的收藏菜单项上
  （见 §2.1 入口清单）。电视端有独立的 leanback `KeepActivity`，B2 不碰，
  TV 零影响。

## 2. 设计

### 2.1 功能开关与入口（默认原生，可回滚）

- **新开关**，不复用 C1/C2 开关：`Setting` 里加 `isFlutterB2Enabled()` /
  `putFlutterB2Enabled(boolean)`，键 `flutter_b2_enabled`，默认 `false`。
  每批一个开关（规划 §4.1）；B2 必须能独立回滚而不动 C1/C2 的验收状态。
- 开关 UI 只在 C0 实验屏：C2 开关旁再加一个 SwitchListTile（“用 Flutter
  打开收藏”），读/写走 B2 合约的 `isB2Enabled()` / `setB2Enabled(bool)`。
  开关只在实验屏出现，不进正式设置页。
- 实验屏容器改动（规划 §4.1 已定）：开关增多后 `C0HomePage` 的 `Column`
  会溢出，B2 批内顺手改成 `SingleChildScrollView` 包裹原 `Center` >
  `Padding` > `Column`（只改容器，不动已有开关语义）。
- 入口改动（唯一一处）：`VodFragment.onMenuItemClick` 的 `R.id.keep` 分支
  （行 675）查开关——开 →
  `C1ScreenActivity.start(activity, C1ScreenActivity.ROUTE_KEEP)`；关 → 原样
  `KeepActivity.start(...)`。其余调用点一律不动：
  - leanback `HomeActivity` 两处（菜单项 + 数字键 8）：电视端入口，TV 不动。
  - `HomeWebBridge.openKeep()`（main 源码集）：Web 桥入口，按风味解析到
    各端 `KeepActivity`；若在此分流，leanback 的 Web 桥也会走到 Flutter
    屏，故不改。手机端 Web 桥进收藏仍走原生 `KeepActivity`——已知缺口，
    见 §6（与 C2 的 Web 桥历史入口同款处理：C2 亦未改 `HomeWebBridge`）。
- 回滚 = 关开关（实验屏上点一下），或 revert 本批提交；原生实现一行未删。

### 2.2 宿主 Activity 与引擎（复用 C1 引擎，不变量）

- 复用 `C1ScreenActivity` + `b8_c1_engine`，加 `ROUTE_KEEP = "/keep"`；
  `ensureFlutterEngine` 里同时注册 `B2Messages.B2HostApi`。不建第三个
  engine。C0 引擎上同样注册 B2 合约（只为实验屏的开关用，与 C1/C2 做法一致）。
- **不变量**：C1 引擎的路由在引擎创建时固定，同一时刻只能有一个 C1 引擎屏
  存活，开新路由前前一屏必须已 finish。收藏屏符合该不变量（全屏屏，从
  首页菜单进、返回即 finish；点卡片打开的详情/搜索是原生 Activity，盖在
  上面时引擎不销毁、路由不变）。若将来出现两个 Flutter 屏叠加的需求，须另
  起 engine 或改 Dart 内导航，不得破坏此不变量。
- B2 不需要自己的 `getInitialRoute` / `closeScreen`，复用 C1 的：路由分发
  仍走 `main()` 启动时的 `C1HostApi.getInitialRoute()`，返回键关闭走
  `C1HostApi.closeScreen()`。

### 2.3 Dart 路由与返回键

- `main.dart` 的 `routes` 表加 `'/keep'`；其余路由机制不动。
- 返回键行为对齐原生 `onBackInvoked`：删除模式下先退出删除模式，再按才
  退出页面。AppBar 返回按钮与系统返回键共用同一处理：系统返回键经
  `PopScope(canPop: false, onPopInvokedWithResult: ...)` 拦截（该形态在
  本嵌入方式下是否生效待真机证明，见 §6；C2 同款）。

### 2.4 Pigeon B2 合约（最小扩展，只够收藏屏用）

新文件 `flutter_module/pigeons/b2_keep_messages.dart`，CI 内一次生成 Dart +
Java 两边。数据类只做扁平字段；**全部展示文案与计算值由 host 返回**
（`getString` 解析后的当前语言文案 + bean getter 算好的显示值），Dart
侧不硬编码文案、不算时间格式、不拼站点名。Java 侧 `bool`/`int` 一律用
装箱类型（`Boolean`/`Long`，C1 教训）；方法名与 Dart 定义字符级一致，
绝不推断大小写归一（C1 的 `setSubtitleAutoMatch` 一案）。

```dart
@HostApi()
abstract class B2HostApi {
  bool isB2Enabled();
  void setB2Enabled(bool enabled);
  B2KeepScreen getKeep();              // 一次快照：列表 + 文案 + 网格规格
  @async
  B2LoadResult openKeepItem(int cid, String key); // 点卡片：异步加载，见 §2.8
  void deleteKeepItem(int cid, String key);
  @async
  void deleteAllKeep();                // 弹窗关闭时才完成（D2 同款）
  void openKeepSync();                 // 原生同步弹窗（keep 模式）
  void goHome();                       // 空态主按钮回首页
}

class B2KeepScreen {
  String title;
  String emptyTitle; String emptyDescription; String emptyActionLabel;
  String syncTooltip; String deleteTooltip;
  int columnCount;
  int cardImageWidthPx; int cardImageHeightPx; // host 按原生规则算，Dart 按 dpr 换算
  List<B2KeepItem> items;
}

class B2KeepItem {
  String key; int cid;
  String vodName; String siteName;     // 收藏卡无集数行/播放行/进度条
  String picUrl;                       // host 解析后的干净 URL（§2.5）
  String picReferer; String picCookie; String picUserAgent;
  String picExtraHeadersJson;          // 偏差 D1 同款：其余 @Headers= 条目（JSON）
  String fallbackPicUrl;               // host 预算的 TmdbProxy 备用图，空 = 无
  String placeholderText; int placeholderColor; // 首字 + ColorGenerator.get400
}

class B2LoadResult {
  String status;                       // "opened" | "failed"
  String errorTitle; String errorMessage; String retryLabel; // 失败时的 host 文案
}
```

- `List<B2KeepItem>` 返回值与 `Long` 字段的 codegen 形态**待 CI 证明**
  （C1/C2 已证明 `List<String>` / `List<C2HistoryItem>` 字段与 `Boolean`/
  `Long`；B2 是同一形态的新文件）；fallback 是拆成两次调用。
- `@async` 带返回值的 Java 形态**待 CI 证明**：按 Pigeon 官方形态，
  `@async B2LoadResult openKeepItem(...)` 在 Java 侧生成
  `void openKeepItem(Long cid, String key, Result<B2LoadResult> result)`，
  `Result` 有 `success(B2LoadResult)` / `error(Throwable)`；无返回值的
  `@async` 则生成 `VoidResult`（C2 已证明）。`B2HostApiImpl.openKeepItem`
  按此签名手写。
- 删除模式是 Dart 本地状态（长按切换、工具栏删除图标进入/确认、返回键
  先退出删除模式），与原生 `onBackInvoked` 行为一致；删除模式下的卡片
  遮罩用半透明黑底 + material_ui 删除图标近似原生 `ic_vod_delete`，不追求
  像素一致。卡片无集数行（原生 `remark` GONE），`VodCard` 的 remarks
  传 null 即不渲染该行（见 §2.5）。

### 2.5 图片加载与共享组件：委托 ImgUtil，Dart 侧只加法

原生图片语义与 C2 完全相同（`ImgUtil.getUrl` 解析 `@Headers=` /
`@Cookie=` / `@Referer=` / `@User-Agent=` 后缀 + 豆瓣 referer 策略；
失败走 `TmdbProxy.nextAutoImageUrl` 备用链，最终首字 TextDrawable）。
host 委托 `getUrl` + `GlideUrl`（D3，不抽取、不复制、不改 `ImgUtil.java`）。

- **共享组件（本批新建，只加法）**：
  - `flutter_module/lib/widgets/pic_image.dart`（`PicImage`）：C2 历史卡
    图片逻辑的逐字复刻——`Image.network(url, headers:)` + 一次备用 +
    首字占位，入参为纯 Dart 值（URL/头字段/备用/占位/高度），不依赖任何
    合约类型。C2 历史页保留内联实现（冻结，不回头重构）。
  - `flutter_module/lib/widgets/vod_card.dart`（`VodCard`）：`adapter_vod.xml`
    的 Dart 版——圆角图 + 站点角标 + 可选 2dp 进度条 + 半透明黑底信息区
    （名字/集数行，恒定亮色白字）。参数化差异点：`remarks == null` 时不
    渲染集数行（收藏）；`remarksVisible == false` 时集数行透明占位（历史
    的 INVISIBLE 语义）；`progress == null` 时不渲染进度条；`playbackText
    == null` 时不渲染播放角标；`siteVisible` 控制站点角标。行高由
    `VodCard.totalHeight(imageHeight, hasProgress, twoLines)` 统一算出，
    页与卡片不可能不一致。信息区固定高度：两行 52（C2 的值），单行 30。
- **host 侧图片解析与网格几何复刻（有意重复，不共享 helper）**：
  `B2HostApiImpl.resolvePic/splitHeaders` 与 `keepColumn/keepSpec` 等方法
  是 `C2HostApiImpl` 对应代码的逐行复刻（仅改名）。理由：每批提交必须可
  独立 revert（规划 §4.9）——若 B2 与 C2 共享一个 host helper，revert B2
  会动到 C2 依赖的代码。两处复刻均在注释中标明来源与漂移警告；若任一处
  改动，另一处必须同步改。Dart 侧共享组件不存在此问题（只加法、不改旧页）。
- Dart 第一版用 `Image.network`，不引入新 Dart 依赖；`data:` URL 显示占位
  图（D6）；不更新 `RouteSelector` 健康信号（C2 已知缺口，推广到全部屏）。

### 2.6 网格几何：host 给数，算法复刻（附漂移警告）

`KeepActivity` 与 `HistoryActivity` 用同一组调用算网格：
`Product.getColumn(this)` 列数、`Product.getSpec(this)` 图尺寸，且共用
`adapter_vod.xml`。因此 B2 的 `keepColumn()/keepSpec()` 与 C2 的
`historyColumn()/historySpec()` 是同一算式（列数 = 宽屏时
`|PlayerSetting.getSize() - (7 + 是否平板)|`、否则 3；卡片宽 = （窗口宽 −
`dp2px(32) + dp2px(16*(列数−1))` − 刘海）/ 列数；卡片高 = 宽 /
`Style.rect()` 的 0.75；刘海取法含原版按位或写法，原样复刻，未“修正”）。
mobile 的 `Product`/`MobileWindow` 在 main 源码集不可引用（D4），故复刻。
**若 mobile 原版改动，C2 与 B2 两处复刻必须同步改**，见 §6。

Dart 按 `devicePixelRatio` 把 host 的 px 换算成逻辑像素；网格用固定
行高（图高 + 单行信息区 30，无进度条），行高由 `VodCard.totalHeight`
算出。

### 2.7 同步弹窗：反射调用（SyncDialog 只在 mobile 源码集）

`SyncDialog` 只存在于 mobile 源码集，main 源码集的 impl 无法直接引用。
`openKeepSync()` 用反射调用 `KeepActivity.onSync()` 的同一链：
`SyncDialog.create().keep().show(activity)`（`keep()` 方法已核实存在）。
安全依据与 C2 相同：`proguard-rules.pro` 有
`-keep class com.github.moyingshi.osc.tv.** { *; }`，release 构建不改名；
仓库内已有反射先例。leanback 上此屏无入口，失败不可达，吞错处理与
`ImgUtil` 的 catch 风格一致。

### 2.8 点卡片：异步加载 + 错误态 + 重试 + 搜索 fallback

原生链（`KeepActivity.onItemClick` + `loadConfig`，实测）：

```
Config config = Config.find(item.getCid());
config == null              -> SearchActivity.start(activity, vodName)
cid == VodConfig.getCid()   -> VideoActivity.start(activity, siteKey, vodId, vodName, vodPic)
否则                        -> VodConfig.load(config, Callback):
                               success -> VideoActivity.start(...)
                               error(msg) -> Notify.show(msg) + progressLayout.showError(
                                               error_config_load, msg, error_retry, retry -> loadConfig)
```

- **合约形态**：`openKeepItem` 为 `@async` 且带 `B2LoadResult` 返回值
  （见 §2.4）。host 在三条路径下完成一次回调：同步路径（无配置/同配置）
  立即完成 `"opened"`；异步路径在 `VodConfig.load` 的 `success` /
  `error` / `noConfig` 回调里完成（`BaseConfig` 已把回调 `App.post` 到主
  线程，故全部完成都在主线程，与 C2 的 dialog-dismiss 完成一致）。
- **超时守卫**：`VodConfig.load` 本身无超时（原生会一直转圈）。规划 §4.6
  要求每个 `@async` host 方法在一切路径完成回调，故 impl 加 30 秒超时守卫
  （主线程 Handler）：超时未回调则完成 `"failed"`，错误文案为 host 的
  `error_play_timeout`（"Timed out"）。超时与回调以 `completed` 标志互斥，
  只完成一次（C2 的 `completeOnce` 同款）。
- **`noConfig` 平等对待**：`BaseConfig` 在无配置时 post `callback.noConfig()`，
  默认实现转调 `error("")`。host 的 `noConfig()` 覆写完成 `"failed"`，
  错误信息为空串——与原生完全一致（原生同样显示空信息的错误态，重试永
  远不成功，见 `Callback.java` 的注释）。Dart 在错误信息为空时隐藏该行。
- **Dart 侧状态**：点卡片后 `_loadingItem` 非空，全屏模态转圈遮罩盖住列
  表（原生 `progressLayout.showProgress()` 的对应物），工具栏同步/删除
  按钮同时禁用（偏差 V3，见 §2.10）；成功（`"opened"`）时 host 已启动
  详情/搜索页，Dart 无事可做；失败时 `_loadError` 全屏错误态（标题 +
  信息 + 重试按钮，`error_config_load` / `error_retry` 均由 host 给），
  重试复用同一条加载路径（与原生重试语义一致）。
- **返回键与加载中**：加载中按返回直接关闭 Flutter 屏（`closeScreen`），
  与原生一致（原生加载中按返回也会退出 `KeepActivity`，加载回调随后
  仍可能启动详情页——同一暴露，两边同款）。Dart 的 `setState` 一律以
  `mounted` 守卫，屏已关时回调不触 UI。
- **直接引用的两条 host 调用**：`VideoActivity.start(activity, key, id,
  name, pic)` 与 `SearchActivity.start(activity, keyword)` 在 mobile 与
  leanback 两端源码集均存在且签名字符级一致（已逐行核实），故 main 源码集
  的 impl 可直接引用——与 `HomeWebBridge` 从 main 引用 `KeepActivity.start`
  是同一既定模式，不是新机制。leanback 上此屏无入口，调用不可达。
- **删除语义**：单条删走 `Keep.delete()`（实例方法，删 DAO 行）；全删走
  `Keep.deleteAll()`（`DELETE FROM Keep WHERE type = 0`）。Dart 删完重取
  列表；删到 0 条时自动退出删除模式（`_load` 内与 C2 同款处理）。

### 2.9 刷新语义：resumed 重取 + deleteAllKeep 的 @async 修正

- 原生靠 EventBus（`RefreshEvent.Type.KEEP`）+ `ConfigEvent`，Dart 订阅
  不了。设计仍是初次加载 + 每次 `resumed` 重取（C1/C2 同款），覆盖播完
  返回、同步弹窗关闭、切后台再回。
- 原生弹窗（删除确认、同步）不会触发 Activity 的 pause/resume，因此
  “弹窗关闭后 resumed 重取”对弹窗不成立（C2 §2.8 的同一修正）：
  `deleteAllKeep()` 改为 `@async`，host 在弹窗 `onDismiss`（一切关闭路径）
  时完成回调，Dart `await` 后重取。同步弹窗仍走 resumed 重取（经反射的
  DialogFragment 不便挂 dismiss 钩子），同步结果在下次 resume 时出现——
  已知缺口，见 §6。
- 前台时后台同步写入不会实时刷新列表（原生会）。实验阶段接受，不为此
  引入 EventChannel/轮询。

### 2.10 相对规划的全部偏差

| # | 偏差 | 理由 |
| --- | --- | --- |
| V1 | `openKeepItem` 用 `@async` + `B2LoadResult` 结构化返回值 | 规划只说“点卡片加载用 @async”，未定返回形态；错误态需 host 文案 + Dart 重试，结构化结果是最窄的形态（VoidResult 承载不了错误信息） |
| V2 | 30 秒超时守卫（`LOAD_TIMEOUT_MS`） | 规划 §4.6 要求 @async 在一切路径完成回调；原生加载无超时，超时后 Dart 显示 host 的 `error_play_timeout` 文案 |
| V3 | 加载中工具栏同步/删除按钮禁用 | 原生加载中工具栏仍可点；禁用防弹窗盖在待导航之上，实验期接受的小偏差 |
| V4 | mobile-only 字符串运行时解析（`mobileString()`） | 规划 §4.8 未覆盖字符串；`app_keep` / `menu_sync` / `menu_delete` / `dialog_delete_keep` 只在 mobile res 存在，直接 R 引用会断 leanback 构建（并发现 C2 同类隐患，见 §2.11） |
| V5 | `VideoActivity` / `SearchActivity` 直接引用（非反射） | 两端源码集均存在且签名字符级一致，`HomeWebBridge` 已有同款先例；leanback 上不可达 |
| V6 | 图片解析与网格几何在 `B2HostApiImpl` 内复刻 C2 代码 | 见 §2.5：保证每批提交可独立 revert；两处复刻须同步改 |
| V7 | 实验屏 `Column` 改 `SingleChildScrollView` | 规划 §4.1 已定（只改容器，不动已有开关语义） |
| V8 | 无跑马灯；信息区固定高度（单行 30，两行沿用 C2 的 52） | C2 D7/D8 同款处理；固定行高网格要求行高可算 |

### 2.11 发现：C2 的 leanback 构建隐患（本批未修复，上报父代理决策）

实施 B2 时核实字符串归属，发现 **`C2HostApiImpl` 很可能断 leanback
构建**：它直接引用了只在 mobile res 存在的字符串——`R.string.app_history`
（标题）、`R.string.menu_sync` / `R.string.menu_delete`（工具栏提示）。
leanback 变体的 R 类由 main + leanback 资源合并生成，不含 mobile res，
故 `R.string.app_history` 在 leanback 编译时不存在。证据：

- `grep -rln` 全量核实：`app_history`、`menu_sync`、`menu_delete` 仅存于
  `app/src/mobile/res/`（values + values-zh-rCN + values-zh-rTW）；
  `dialog_delete_record` / `dialog_delete_history` 两端均有，安全；
  `history_empty_*` / `menu_viewing_report` / `dialog_delete_global_history` /
  `dialog_negative` / `dialog_positive` 在 main res，安全。
- main 源码集除 flutter 包外无任何文件引用这三个 mobile-only 字符串
  （`HomeWebBridge` 等均不引用），故 C2 是唯一命中者。
- C2 从未经过 CI（未推送），该问题尚未被构建验证过；联合推送时
  `b8-c0.yml` 的 4 风味构建会在 leanback 风味上失败。

**本批未修复**：C2 合约与实现冻结（规划 §1/§4.2），修 C2 须另立任务。
修复选项（供父代理定）：(a) C2 改用 B2 同款 `mobileString()` 运行时解析；
(b) 把 `app_history` / `menu_sync` / `menu_delete` 移入 main res（mobile
值优先覆盖，leanback 取 main 值）。B2 本批已用 (a) 避开同类问题。

**修复结果（2026-11-17，任务 `b8-c2-leanback-fix`，已选 (a)）**：C2 的
3 处引用已改为 `mobileString()` 运行时解析（C2 内复刻 helper，兜底文案
为 main res 文案，见 `docs/b8-c2-history.md` §2.11）；**B2 本批无需改动**——
`dialog_delete_record` 经逐文件核实，leanback res 也有（`values/strings.xml`
+ `values-zh-rCN/strings.xml`，与 mobile 同值语义），直接引用可编译。
另记一笔：父代理最初的 grep 用了 `head -3`，mobile 的 3 个同名文件截断了
leanback 的命中，曾误判 B2:223 为 mobile-only；以后核查资源归属须按目录
分别 grep，不用 `head` 截断。

### 2.12 哪些东西明确不做

- 不碰 Room/设置/收藏 schema（评估文档 §7 硬性前提）；只读已有
  `Keep` bean + 1 个新开关布尔键；删除走 bean 已有方法。
- 不碰播放器、蜘蛛、实验室、小程序；不改 `C0HostApi` / `C1HostApi` /
  `C2HostApi` 合约与实现（C0/C1/C2 冻结）；不改 `Keep.java`、
  `KeepActivity`、`KeepAdapter`、`VodConfig`、`Callback`。
- 不回头重构 C2 历史页（冻结）；共享组件只加法。
- 不新增 Dart 依赖；不改 Flutter/Pigeon 版本锁定。
- 不改 `android-release.yml`；B2 构建仍走 `b8-c0.yml`（只加 Pigeon 生成命令）。
- 不改任何电视端代码与布局；不改 `ImgUtil.java`。

## 3. 文件清单

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `flutter_module/pigeons/b2_keep_messages.dart` | 新：B2HostApi + `B2KeepScreen` / `B2KeepItem` / `B2LoadResult`（扁平数据类） |
| 2 | `flutter_module/lib/b2_keep_page.dart` | 新：`B2KeepPage`（网格 + 删除模式 + 异步加载态/重试） |
| 3 | `flutter_module/lib/widgets/vod_card.dart` | 新：`VodCard` 共享卡片（见 §2.5 参数化差异点） |
| 4 | `flutter_module/lib/widgets/pic_image.dart` | 新：`PicImage` 共享图片（C2 图片逻辑复刻） |
| 5 | `flutter_module/lib/main.dart` | 改：`/keep` 路由 + 实验屏 B2 开关 + 实验屏 `SingleChildScrollView` |
| 6 | `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B2HostApiImpl.java` | 新：host 实现（快照/几何/图片解析/加载/删除/同步反射/回首页） |
| 7 | `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java` | 改：`ROUTE_KEEP` + 注册 `B2HostApi` + 类注释提及收藏页 |
| 8 | `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java` | 改：C0 引擎注册 `B2HostApi`（只为开关） |
| 9 | `app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java` | 改：加 `flutter_b2_enabled` 开关方法 |
| 10 | `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/VodFragment.java` | 改：`R.id.keep` 入口分流（1 处） |
| 11 | `.gitignore` | 改：加 `B2Messages.java` |
| 12 | `.github/workflows/b8-c0.yml` | 改：Pigeon 步骤加 b2 生成 + 存在性检查 |
| 13 | `docs/b8-b2-keep.md` | 新：本文档 |

## 4. 逐项对照清单（Wei 真机验收用）

### 4.1 列表与卡片（与原生逐项比）

- [ ] 开关开：首页菜单“收藏”进 Flutter 屏；开关关：进原生收藏页。
- [ ] 列表内容与原生一致：同一批收藏、同一顺序（`createTime` 倒序）。
- [ ] 卡片尺寸与列数：窄屏 3 列；宽屏（横屏/平板）与原生同列数。
- [ ] 卡片信息：图片 + 站点角标（左上）+ 名字一行；**无**集数行、
      **无**播放角标、**无**进度条。
- [ ] 卡片颜色与圆角：信息区半透明黑底 + 亮色白字；图圆角 8dp。
- [ ] 空收藏：原生空态文案 + “回首页”按钮，点击回首页。

### 4.2 删除模式

- [ ] 长按卡片进入删除模式；工具栏删除图标进入删除模式；返回键先退出
      删除模式，再按退出页面。
- [ ] 删除模式下卡片有半透明黑底 + 删除图标遮罩；点卡片删除该条，
      列表立即少一条。
- [ ] 工具栏删除图标在删除模式下点 → 原生确认弹窗（标题 `dialog_delete_record`
      + 内容 `dialog_delete_keep`）→ 确认后全删，列表变空，退出删除模式。
- [ ] 删到 0 条自动退出删除模式。
- [ ] 取消确认弹窗不删除（弹窗关闭后列表不变）。

### 4.3 弹窗与跳转

- [ ] 工具栏同步图标打开原生同步弹窗（keep 模式），弹窗内操作与原生一致。
- [ ] 点卡片：同一源直接进详情页；无配置的源进原生搜索页（关键字 = 影片名）。
- [ ] 点卡片后返回：回到 Flutter 收藏页（resumed 重取）。

### 4.4 异步加载 / 重试 / fallback

- [ ] 点卡片（需加载配置的源）：先出现全屏加载态，转圈期间点卡片/工具栏无反应。
- [ ] 加载失败：全屏错误态显示 host 文案（标题 `error_config_load` + 错误
      信息 + “Retry”按钮），点重试重新加载。
- [ ] 加载失败且错误信息为空：只显示标题 + 重试按钮（无空行）。
- [ ] 无配置的源：直接进搜索页，不出现错误态。
- [ ] 加载中按返回：退出 Flutter 屏（与原生一致）。

### 4.5 滚动与图片对照

- [ ] 滚动流畅度与原生相当；上下滑动无明显卡顿。
- [ ] 图片加载与原生一致（同一 URL、同一头字段，豆瓣图有 referer）。
- [ ] 失败图/备用图：与原生同表现；`data:` 图显示首字占位。
- [ ] 占位图：首字 + 同色（与原生 TextDrawable 同色）。

### 4.6 开关与回滚

- [ ] 实验屏 B2 开关开/关立即生效（下次进收藏页）。
- [ ] 开关关 = 原生收藏页，功能全量正常（删除/同步/点卡片）。
- [ ] 开关状态重启 App 后保留（`Prefers` 持久化）。

## 5. 回滚步骤

- **实验屏上关 B2 开关**，立即回到原生收藏页。
- **完整回滚**：revert 本批提交；无需改 main。
- **失败时**：`b8-c0` 产物不发布；用实验屏开关/回滚恢复；C1/C2
  验收状态不受 B2 回滚影响（独立开关、独立合约、独立 impl）。

## 6. 未能验证的项

| # | 项 | 风险/备注 |
| --- | --- | --- |
| G6-1 | Pigeon codegen：`List<B2KeepItem>`、`Long` 字段、`@async` 带返回值（`Result<B2LoadResult>`） | C1/C2 已证明 `List<String>` / `List<C2HistoryItem>` 字段、`Boolean` / `Long` 字段、`@async void`（`VoidResult`）；B2 是同一形态的新文件，CI 生成时校验 |
| G6-2 | `flutter analyze`（一个警告即失败，C1 #8 教训） | 本地无 Dart，全部 Dart 文件未静态检查；CI 失败先看分析日志再修 |
| G6-3 | Gradle 编译：手写 `B2HostApiImpl` vs 生成接口 | 装箱类型、方法名与 Dart 定义字符级一致（C1 的教训：`setSubtitleAutoMatch` 大小写）；生成 Java 接口的签名以 CI 日志为准 |
| G6-4 | `b8-c0.yml` 4 风味 release 构建（mobile/arm64、mobile/arm、TV、leanback） | 特别注意 leanback：B2 避开了 mobile-only 直接引用（`mobileString()`），C2 的 3 处直接引用已于 2026-11-17 改为 `mobileString()`（任务 `b8-c2-leanback-fix`，见 §2.11）；待 CI 证明 leanback 风味构建通过 |
| G6-5 | R8/minify：反射同步弹窗 + `getIdentifier` 字符串解析 | R8 保留全部 app 成员名（proguard 规则）；`getIdentifier` 按名字查字符串，不受混淆影响；但 `SyncDialog` 的方法名若被改名则反射失败——proguard 规则已覆盖 |
| G7-1 | `PopScope` 在缓存引擎 `FlutterFragment` 内的返回键拦截 | C2 同款未验证；若无效，删除模式下返回键直接退出页面（与原生行为的差异点） |
| G7-2 | 单引擎路由不变量：`/about`、`/subtitle`、`/history`、`/keep` 共用 C1 引擎 | 若 Wei 在 Flutter 屏内再打开另一 Flutter 屏，需另起 engine 或 Dart 内导航；验收时只测单屏进出 |
| G7-3 | 图片对等：头字段、备用图、占位图、`data:` 图 | C2 同款未验证；`Image.network` 无磁盘缓存，上下滑回时图片可能重闪/重下 |
| G7-4 | 滚动性能与滑回图片行为（无磁盘缓存） | 收藏卡无进度条、无播放角标，形态比历史卡简单；但图片缓存缺口与 C2 相同 |
| G7-5 | 网格几何：宽屏/平板/刘海 | 复刻算式未验证；刘海取法含原版按位或写法（原样复刻） |
| G7-6 | 超时守卫（30 秒）行为 | 超时后 Dart 显示 `error_play_timeout`；原生无超时，超时后若配置随后加载成功，不会再进详情页（回调已完成，`completed` 标志互斥） |
| G7-7 | 同步弹窗关闭后的刷新 | 同步结果在下次 resume 时出现（见 §2.9 已知缺口）；删除确认弹窗关闭后重取已由 `@async` 保证 |
| G7-8 | `HomeWebBridge.openKeep()` 仍走原生收藏页 | 手机端 Web 桥进收藏未分流（见 §2.1）；与 C2 的 Web 桥历史入口同款处理 |

## 7. Recovery anchor（恰好一个下一步：B3）

**下一步：B3**——按 `docs/b8-full-migration-plan.md` §3 的批次顺序，继续下一批
（B3：设置页）。不推送、不编包；各批完成后统一联合推送（Wei 2026-11-17
10:44 指示）。

---

*任务护栏：`b8-b2-keep`（见 §0）。提交与恢复标签见提交记录。*
