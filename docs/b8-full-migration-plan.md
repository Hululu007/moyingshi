# B8 全量迁移规划：除播放器外全部手机屏（规划文档，非实现）

> 状态：**规划完成，待 Wei 定 §6 事项后按 §3 顺序实施**。本文档只做
> 枚举、分类、排批次与共性决策，不包含任何产品代码改动。评估与阶段
> 定义见 `docs/b8-flutter-rewrite-assessment.md`（§5 方案 C、§6 go/no-go、
> §7 回滚）；C0/C1/C2 已验证形态与偏差决策见 `docs/b8-c0-shell.md`、
> `docs/b8-c1-about-subtitle.md`、`docs/b8-c2-scoping.md`、
> `docs/b8-c2-history.md`（§2.9 的 D1–D8 是后续批次必须沿用的既定
> 决策，不重议）。分支：`b8-flutter-rewrite`；基线：HEAD `699f1f00`
> （含已实施的第一批历史页）；`main` 未动。
>
> **诚实声明**：本机无 Java、Android SDK、Flutter/Dart，一切构建与运行
> 结论均未验证。行数与结构均实测于本地源码（2026-11-17）；凡需 CI/真机
> 证明的项见 §7，不得视为已验证。
>
> **范围令**：Wei 2026-11-17 决定“除了播放器不做，其他全做”。本规划
> 覆盖全部手机端用户可见屏，播放器（§2 排除类）不做，电视端不动，
> 小弹窗一律留原生（既定模式）。

## 0. §1 执行声明（仓库 AGENTS.md）

- 完成句：B8 全量迁移批次规划——枚举手机端全部用户可见屏、分类与排
  批次、拍板共性决策，写成规划文档并本地提交，不改产品代码，不推送，
  不动 `main`。
- 允许路径：仅 `docs/b8-full-migration-plan.md`（task_guard assessment 模式）。
- 预存脏文件：无（开始时工作树干净）。
- 时间/时区：2026-11-17 10:49 CST（Asia/Shanghai）开始。
- 工期估计：源码调研约 50 分钟 + 文档约 30 分钟 + 验证提交约 10 分钟，
  约 12:20 CST 完成。
- 最便宜的决定性验证：`git diff --check` + diff 仅含本文档 + 提交后
  工作树干净 + `main` 未动检查。

## 1. 已完成（本规划的基线）

| 已完成 | 内容 | 状态 |
| --- | --- | --- |
| C0 | 壳工程：Flutter module + Pigeon 往返 + 实验屏入口 + `b8-c0.yml` | 已验收通过 |
| C1 | 关于页 + 字幕设置页（`C1HostApi`，`flutter_c1_enabled`） | Wei 真机验收通过 |
| B1 | 历史页（`C2HostApi`，`flutter_c2_enabled`，`/history`） | 已实施，本地提交 `699f1f00`，未推送、未 CI/真机验证 |

后续批次全部沿用 C1/C2 形态：功能开关默认关、开关 UI 只在实验屏、
全部展示文案与计算值由 host 返回、复杂子流程与弹窗留原生、装箱类型、
方法名与 Dart 定义字符级一致、`@async` 弹窗刷新（D2）、图片解析委托
`ImgUtil.getUrl`（D3）、mobile-only 类可见性三招（D4 复刻 / D5 反射 /
C1 一词放宽）、单引擎路由不变量（C2 §2.2）。**C0/C1/C2 合约与实现冻结，
后续批次不得修改已验收/已实施屏的行为**（共享组件只加法，见 §4.3）。

**联合推送规则（Wei 指示）**：各批做完先不推不编，全部攒齐后一起推、
一起编。因此每批提交必须可独立 revert（revert 掉一批后树仍自洽），
见 §4.9；CI 一次验证全部批次，失败面见 §7。

## 2. 屏枚举与分类（实测）

行数均为 `wc -l` 实测；路径 `mobile` = `app/src/mobile`，`main` =
`app/src/main`。判定：**迁**（排入 §3）/ **排除**（不做，理由写明）/
**待定**（§6 需 Wei 一句话）。弹窗（mobile 有 44 个 `*Dialog`，含站点、
同步、更新、选集、投屏、弹幕源等）全部留原生，不在表内。

### 2.1 设置族（HomeActivity 内 Fragment，`change(0–10)`）

| 屏 | 原生实现 | 状态与数据 | 图片/弹窗/入口 | 判定 |
| --- | --- | --- | --- | --- |
| 设置主屏 | `SettingFragment` 1012 行（mobile） | 7 个分组 + 站点/配置行 + 下载列表（`DownloadTask` 行 + 展开集数行）+ 隐藏分组；`ConfigListener/SiteListener/LiveListener` 刷新 | 无图；站点/配置/下载操作走原生弹窗；底栏“设置”项 | **迁** B7 |
| 播放器设置 | `SettingPlayerFragment` 588 行 | 最大子设置页：26 处显隐信号；`PlayerSetting` 全量读写（内核/隧道/倍速/UA/AAC 等） | 弹窗留原生；设置主屏行 | **待定** §6-P1 |
| 增强设置 | `SettingEnhanceFragment` 429 行 | 行多但扁平（1 处显隐）；`Setting` 读写 + 实验室/小程序/音频配置开关 | 弹窗留原生；设置主屏行 | **迁** B4 |
| 个人设置 | `SettingPersonalFragment` 327 行 | 扁平（0 处显隐）；搜索线程数、播放浮层、续播等 | 弹窗留原生；设置主屏行 | **迁** B5 |
| TMDB 设置 | `SettingTmdbFragment` 147 行 | 扁平（2 处显隐）；TMDB 配置/匹配模式/详情主题 | 弹窗留原生；设置主屏行 | **迁** B3 |
| AI 设置 | `SettingAiFragment` 188 行 | 扁平（1 处显隐）；`AiConfig` 读写 + 个性化推荐开关 | 弹窗留原生；设置主屏行 | **迁** B3 |
| 广告设置 | `SettingAdFragment` 169 行 | 扁平（1 处显隐）；拦截/跳过片头片尾/AI 检测 | 弹窗留原生；设置主屏行 | **迁** B3 |
| 弹幕设置 | `SettingDanmakuFragment` 105 行 | 小但有 load→api→auto→spider 四级条件显隐链；`DanmakuSetting` 读写 | 弹窗留原生；设置主屏行 | **迁** B3 |
| 字幕设置 | `SettingSubtitleFragment` 90 行 | 已迁（C1） | — | 已完成 |
| 设置内搜索 | `SettingSearchFragment` 134 行 | 设置主屏的搜索框：文本输入 + 过滤设置行；首个文本输入屏（小切面） | 设置主屏搜索按钮 | **迁** B5 |
| 关于 | `AboutDialog`（弹窗） | 已迁为全屏（C1，有意形态变化） | — | 已完成 |

### 2.2 内容屏与列表屏

| 屏 | 原生实现 | 状态与数据 | 图片/弹窗/入口 | 判定 |
| --- | --- | --- | --- | --- |
| 历史 | `HistoryActivity` 205 + `HistoryAdapter` 140 | 已迁（B1） | — | 已完成 |
| 收藏 | `KeepActivity` 220 + `KeepAdapter` 131 | 单一数据源 `Keep.getVod()`；删除模式 1 布尔值；点卡片异步 `VodConfig.load` + 失败重试 + 无配置 fallback 到搜索页 | 同历史卡片（无进度条）；首页/设置入口 | **迁** B2 |
| 分类列表 | `TypeFragment` 240 + `FolderFragment` 125（壳 + 钻取回退栈）+ `FolderActivity` 93（独立入口） | 首个网络屏：`SiteViewModel.categoryContent`（LiveData）+ 分页 + 筛选（`setFilter`，筛选 UI 形态待 B8 批内确认，默认 Dart chips + host 快照）+ 文件夹钻取 | 同卡片；首页 ViewPager 每页一个 + 独立入口 | **迁** B8 |
| 搜索输入页 | `SearchActivity` 132 + `SearchFragment` 479 | 文本输入 + 键盘显隐 + 热词（剧/影/综三组）+ 搜索记录 + 联想；提交后叠加 `CollectFragment` | 无图；首页搜索条；结果页在 B10 落地前走原生 `SearchActivity` direct 模式（host 调用） | **迁** B9 |
| 聚合搜索 | `CollectFragment` 780 | `SiteViewModel` 多站并行搜索 + 逐站进度 + 逐站分页 + 相似度/分组筛选；最大单 Fragment 批次 | 同卡片；搜索提交后 | **迁** B10 |
| 首页 | `VodFragment` 1206 + `HomeActivity` 691（壳 + 玻璃底栏） | 复合壳，必须拆块：顶栏（logo/标题/搜索条/历史/更多）+ 分类 Tab（`TypeAdapter` 96）+ ViewPager（每页 `FolderFragment`）+ 轮播（`BannerAdapter` 110，4 秒自动轮播）+ 继续观看（`ContinueAdapter` 120）+ JS 小组件（`WidgetManager` + `WidgetSectionAdapter` 80）+ 海阔内嵌（hk:// 站时挂 `HkListFragment`）+ WebHome WebView（`HomeWebController`） | 全部子系统一次到齐；应用主入口 | **迁** B11–B13（拆块，§3） |
| 追更 | `FollowingActivity` 562（main） | 本地 `FollowingStore.list()` + 筛选 + 未读数；行渲染 + 标记已读 | 行内图（共享图片组件）；入口见实现时核实 | **迁** B14 |
| 本地文件 | `FileActivity` 161 | 本地目录浏览（`Path` + `FileAdapter`）；存储权限申请在先 | 无图；插件管理等入口 | **迁** B15 |
| 应用图标 | `AppBrandingActivity` 69（main） | 极小：2 个图标选项 + 保存/取消（`AppBranding` 读写） | 无图；设置相关入口 | **迁** B15 |
| 观看报表 | `ViewingReportActivity` 260（main） | `ViewingReportGenerator` 异步生成 + AI 分析 + 缓存；范围选择 | 无图；历史页报表入口 | **迁** B16 |
| TMDB 人物 | `TmdbPersonActivity` 958（main） | TMDB 网络：人物详情 + 照片 Pager + 作品列表；`ImgUtil`/`Glide` 图片 | 多图 + Pager；详情页人物入口（原生详情调起，Flutter 页本身独立） | **迁** B17 |
| 小组件浏览 | `WidgetBrowseActivity` 417 | JS 小组件浏览（`WidgetManager`）+ chips 筛选 + 点项进搜索 | 卡片图；首页小组件区入口 | **迁** B18 |
| 插件管理 | `PluginManagerActivity` 1040（main） | 插件包列表 + 启用开关 + 目录选择（`FileChooser`）+ 后台单线程安装/重载（`PluginBootstrap`）；存储权限 | 无图；设置/实验室入口 | **待定** §6-P2 |

### 2.3 播放器与播放形态（排除：Wei 的范围令）

| 屏 | 原生实现 | 排除理由 |
| --- | --- | --- |
| 详情（普通） | `VideoActivity` 12139 行（mobile），继承 `PlaybackActivity` | 详情与播放器同一 Activity：选集/线路/解析/投屏/弹幕/字幕/诊断全在里面；迁详情 = 动播放器，属 C3 |
| 详情（TMDB） | `TmdbDetailActivity` 14420 行（main），继承 `PlaybackActivity` | 同上 |
| 播放器基类 | `PlaybackActivity` 1434 行（main） | 播放器本体（Service + Media3/mpv + 输出模式） |
| 直播 | `LiveActivity` 2468 行（mobile），继承 `PlaybackActivity` | 播放器 + 频道/EPG/投屏/密码，播放形态 |
| 音频播放 | `AudioActivity` 1474 行（main），继承 `PlaybackActivity` | 后台音频 + MediaSession + 迷你播放器，播放器 |
| 短剧 | `ShortDramaActivity` 793 行（mobile） | 自带 `ExoPlayer` 纵滑播放器（`buildPlayer` + PageHolder 绑定）；浏览网格与播放器同一 Activity，无法只迁一半而不先拆原生 Activity |
| 选集 Fragment | `EpisodeFragment` 79 行 | 仅被 `EpisodeGridDialog` 使用，弹窗内嵌，非独立屏，留原生 |

详情排除是范围令的直接推论（详情 = 播放器 Activity），不需另行决策；
若 Wei 未来想迁“详情 UI 但不动播放控制”，那是先拆原生 Activity 的
重构 + C3，不在本规划内。

### 2.4 实验室 / 小程序 / Web / 硬件（排除或待定）

| 屏 | 原生实现 | 判定与理由 |
| --- | --- | --- |
| 实验室列表/详情 | `LabActivity` 940 + `LabDetailActivity` 435（main，手机与 TV 共用） | **待定** §6-P4：环境/命令管理，交互重；TV 共用同一 Activity，移动端 Flutter 化须谨慎处理 TV 入口 |
| 实验室终端/输出 | `LabTerminalActivity` 321 + `LabOutputActivity` 188（main） | **待定** §6-P4，建议留原生：交互式 shell I/O 需要流式通道（EventChannel），本规划不引入该机制（§4.6） |
| 海阔列表/详情/搜索 | `HkListFragment` 461 + `HkDetailFragment` 299 + `HkSearchFragment` 356（mobile，Rhino 引擎） | **待定** §6-P3，建议留原生：规则驱动渲染（JS 规则定字段）+ 详情接 `HkPlayer`；且 Hk API 全在 mobile 源码集（20 个文件），impl 落点需另行设计（§4.8） |
| 海阔管理 | `HkMiniappActivity` 321 行 Kotlin（mobile） | **待定** §6-P3：小程序导入/导出/云口令管理（文件 + 剪贴板），可迁但与 Hk 族同批决策 |
| WebView 容器 | `WebReaderActivity` 1851（小说/漫画阅读器）+ `CatWebActivity` 323（JS 桥）+ `GameWebActivity` 200（小游戏）+ `WebThemeDetailActivity` 238（Web 主题详情） | **排除**：屏本身就是 WebView；本 module 无 WebView 插件，只迁 chrome 无意义 |
| 服务器/WebHome | 无独立 Activity（本地 HTTP 服务 + Web 页面 + `HomeWebController`） | **排除**：管理面是 Web 页面，不是原生屏；首页 Web 区走原生 fallback（§3 B11） |
| 扫码 | `ScanActivity` 93（ZXing 相机预览） | **排除**：相机硬件屏；Flutter 相机插件 = 新依赖 + 新桥接，收益为零 |
| 崩溃屏 | `CrashActivity` 266 | **排除**：崩溃处理屏，绝不能依赖可能已损坏的 Flutter 引擎 |
| 播放恢复 | `PlaybackRecoveryActivity` 173 | **排除**：瞬时恢复跳板，非用户屏 |
| 本地源弹窗 Activity | `LocalSourceDialogActivity` 35 | 留原生：弹窗形态 Activity，属弹窗类 |

`HomeActivity` 本身（691 行）是宿主壳 + 玻璃底栏，不是内容屏：它一直
留原生，作为原生 Fragment 的宿主与 Flutter 屏的启动器；B11 在 Dart 内
复刻玻璃底栏（仅 Flutter 首页用），原生底栏不动。

## 3. 批次顺序表

每批 = C1 规模（1–2 屏，或 1 个拆块）、独立提交、独立开关、独立回滚、
自带任务文档（结构同 C1/C2 文档）与逐项对照清单。顺序按“已验证形态 →
新机制”排列：先把已证明的模式用尽，再引入新机制（网络 LiveData、文本
输入、首页壳），每种新机制只在一个批次首次出现。

| 批 | 范围句 | 规模/复用 | 首次证明的新东西 |
| --- | --- | --- | --- |
| B1 | 历史页（已完成，`699f1f00`） | — | 列表 + 图片 + 删除模式（已实施，待 CI/真机） |
| B2 | 收藏页：网格 + 删除模式 + 点卡片异步加载/重试/搜索 fallback | 220+131 行；复用历史卡片形态与合约形态，抽共享 `PicImage`/`VodCard`（只加法，不改历史页代码） | 异步加载错误态 |
| B3 | 设置 A：TMDB + AI + 广告 + 弹幕 4 个子设置页 | 147+188+169+105 行；建 `/settings` 族路由与族内 Navigator（§4.4），首批只含 4 个子页（无 sub 参数时默认首个子页，族主页待 B7） | 族路由 + 条件显隐链（弹幕） |
| B4 | 设置 B：增强设置页（族内新增一页） | 429 行；只加族注册表条目 + 一页 | — |
| B5 | 设置 C：个人设置页 + 设置内搜索 | 327+134 行；设置内搜索是首个文本输入切面（小） | 文本输入（小切面，§4.5） |
| B6 | 播放器设置页（待 P1 确认；若否决则跳过，原生保留） | 588 行；族内新增一页 | 最大条件显隐面 |
| B7 | 设置主屏：分组 + 行 + 站点/配置行 + 下载列表 + 隐藏分组 | 1012 行；族路由的主页落地，族内导航闭环（主屏 ↔ 子页，返回语义与原生一致） | 族内导航闭环 + 下载列表行 |
| B8 | 分类列表：`TypeFragment` + 文件夹钻取 + 独立入口 | 240+125+93 行；复用 B2 卡片；路由 `/category`，钻取走族内 Navigator | 网络 LiveData 一次性快照（§4.6）+ 分页 + 筛选 |
| B9 | 搜索输入页：输入框 + 热词 + 记录 + 联想 | 132+479 行；路由 `/search`；结果暂走原生 direct 模式 | 文本输入（全量，§4.5） |
| B10 | 聚合搜索：多站并行搜索 + 进度 + 逐站分页 + 筛选 | 780 行；族内新增结果页，搜索族导航闭环 | 进度轮询快照（§4.6） |
| B11 | 首页壳：顶栏 + 分类 Tab + ViewPager + Dart 玻璃底栏；hk:// 站与 Web 主题时 fallback 到原生首页 | VodFragment 顶栏/Tab/页部分；复用 B8 合约形态；路由 `/home` | 首页壳 + Dart 底栏 + host 侧 fallback 路由 |
| B12 | 首页块 A：轮播（自动轮播）+ 继续观看 | 110+120 行对应块；`/home` 内新增 widget，同一合约扩展 | 自动轮播定时器 + 继续观看行 |
| B13 | 首页块 B：JS 小组件区 | 80 行对应块 + `WidgetManager` 快照；`/home` 内新增 widget | JS 小组件快照 |
| B14 | 追更列表：筛选 + 未读 + 行渲染 + 标记已读 | 562 行；复用列表与图片形态 | — |
| B15 | 本地文件浏览 + 应用图标选择（两小屏一批） | 161+69 行；权限申请留 host（`@async` 等权限回调完成） | 权限流桥接 |
| B16 | 观看报表：范围选择 + 异步生成 + AI 分析（host）+ 渲染 | 260 行；AI 分析 100% 留 host | 异步生成快照 |
| B17 | TMDB 人物：详情 + 照片 Pager + 作品列表 | 958 行；图片形态复用 | 照片 Pager |
| B18 | 小组件浏览：chips 筛选 + 点项进搜索 | 417 行；复用 B13 小组件快照 | — |
| B19 | 插件管理（待 P2 确认；若否决则留原生） | 1040 行；安装/重载/文件选择 100% 留 host | 文件选择器桥接 |
| B20 | 海阔管理屏（待 P3 确认；Hk 列表/详情/搜索建议留原生） | 321 行 Kotlin 对应屏；impl 落点按 §4.8 | mobile 源码集 impl |
| B21 | 实验室列表/详情（待 P4 确认；终端/输出建议留原生） | 940+435 行；TV 入口隔离是批内必查项 | TV 共用 Activity 的移动端门控 |

B14–B18 之间无依赖，可由 Wei 调换顺序。B19–B21 全部待定，未确认前
不排入联合推送。

## 4. 共性决策（已拍板，附理由）

### 4.1 开关策略：每批一个开关，无总开关

- 每批一个 `Setting` 布尔键（`flutter_b<N>_enabled`，默认 `false`），
  读写方法走该批自己的合约（C1/C2 模式）；开关 UI 只在实验屏。
- 不设总开关：总开关 + 分开关是两个门控维度，“哪屏走 Flutter”会变得
  不可一眼判断；独立回滚（评估文档 §7）只要求分开关。
- 实验屏改动：`C0HomePage` 的 `Column` 在开关增多后会溢出，B2 批内
  顺手改成可滚动列表（只改实验屏容器，不动已有开关语义）。开关数量
  上限即批次数，列表形态可容纳。

### 4.2 Pigeon 合约策略：每批一个合约文件，历史合约冻结

- 每批一个 `flutter_module/pigeons/b<N>_<slug>_messages.dart`，一个
  `B<N>HostApi`，一个 impl 类（main 源码集，leanback 安全）。一批多屏
  时方法按屏加前缀（如 `getTmdbSettings` / `getAiSettings`）。
- 不用 `cN` 续编：评估文档的 C3/C4 已指播放器/TV 阶段，`bN` 避免
  引用 collision。C0/C1/C2 文件与类冻结，永不改。
- 共享形态不共享类型：图片字段组（`PicSpec` 形态）、网格规格等在各
  合约内各自声明（Pigeon 按文件生成类型，跨文件共享会耦合批次）。
  代价是重复声明，收益是批次独立与 CI 失败可定位到文件。Dart 侧由各
  页把自家合约字段映射到共享 widget（§4.3），映射代码每页数行。
- 每个新合约：`b8-c0.yml` 的 Pigeon 步骤加两行（生成 + 存在性检查，
  C1/C2 同款），`.gitignore` 加生成的 `B<N>Messages.java`。

### 4.3 Dart 共享组件：只加法，不重构未验证代码

- `flutter_module/lib/widgets/`：`VodCard`（网格卡：图 + 角标 + 进度条
  + 信息区，几何与文案全由 host 给）、`PicImage`（`Image.network` +
  头字段 + 一次备用 + 首字占位，即 C2 历史卡图片逻辑的复刻）、
  `SettingsRow`（设置行：标题 + 摘要 + 开关/箭头）、`EmptyState`、
  `LoadingState`。主题沿用现有 `MaterialApp`（accent `#0B57D0`）。
- B2 写共享组件时**不**回头改 C2 历史页：历史页代码未经 CI/真机验证，
  重构它等于动未验证代码。历史页保留内联实现；联合验收通过后如需
  dedup，另立一批（不在本规划内）。
- 后续批次只许向共享组件加新 widget 或新可选参数，不得改已有参数语义；
  某屏需要不同行为时写屏内私有 widget，不污染共享组件。
- 不新增 Dart 依赖：图片只用 `Image.network`（磁盘缓存缺口的决策门见
  §7）；不引入 `cached_network_image`、WebView、相机等插件。

### 4.4 引擎 / 路由：单引擎 + 族路由 + Dart 内导航

- 沿用一个 C1 引擎（`b8_c1_engine`）+ `C1ScreenActivity` 承载全部迁移屏；
  不建第三个引擎。`ensureFlutterEngine` 内注册的合约随批次增多（每批
  加一行注册，C0 引擎上同样注册——只为实验屏开关用）。
- **路由带参**：现有机制只传路由字符串（Intent extra → `getInitialRoute()`）。
  需要参数的屏（分类 key/typeId、搜索 keyword、人物 id）用 query 字符串
  路由，如 `/category?key=..&typeId=..`；host 侧建路由字符串时 URL 编码
  参数值，Dart 侧 `Uri.parse` 解析。零新机制，`C1HostApi` 不用改。
- **族路由（核心决策）**：同一导航闭环内的屏共享一条路由 + Dart 内
  `Navigator`，族内跳转不经过 host Activity 栈：
  - `/settings` 族：主屏 + 全部子设置页（B3 建壳与注册表，B4/B5/B6/B7
    只加页与注册表条目）；返回键先 pop 到族主页，再按才 finish。
  - `/search` 族：输入页 + 聚合结果页（B9 建壳，B10 加页）。
  - `/home` 族：首页壳 + 各块（B11 建壳，B12/B13 加块；块是 widget，
    不是 Navigator 页）。
  - `/category` 族：列表页 + 文件夹钻取（B8 一批内建壳与钻取）。
  - 其余屏各一条独立路由（`/history`、`/keep`、`/following`、`/files`、
    `/report`、`/person`、`/widgets`、`/branding`、`/plugins`、…）。
- **跨屏导航归 host**：Dart 永远不直接打开另一条路由；点卡片/行触发的
  跨屏跳转一律调 host 方法，由 impl `finish()` 当前屏后再
  `C1ScreenActivity.start(新路由)`（或打开原生 Activity）。族内跳转
  则纯 Dart（`Navigator.push`），不经过 host。
- **不变量（重申 + 扩展）**：同一时刻只能有一个 C1 引擎屏存活；开新路由
  前前一屏必须已 finish；族内 Navigator 深度在进程死亡后不恢复（只恢复
  到路由初始页，见 §7-R2，接受为已知缺口）。

### 4.5 文本输入屏：Dart 持有输入框，host 只给数据

- 适用屏：设置内搜索（B5，小切面先行）、搜索输入页（B9）。
- Dart `TextField` 持有输入状态；联想/热词/记录走 `@async` 快照方法，
  Dart 侧 300ms 防抖后调用；IME 搜索键提交 → 族内推结果页或调 host
  打开原生结果（B9/B10 交界期）。
- 不用 host 侧 `EditText`、不用 PlatformView；键盘显隐与焦点行为以
  B5 真机验收为准——若嵌入式 `FlutterFragment` 下键盘行为异常，B9
  停下等决策，不得绕进 host 输入框。
- 设置内搜索（B5）先行就是为了用最小切面证明键盘链路，再做 B9。

### 4.6 异步 / LiveData 数据源：`@async` 一次性快照 + 轮询进度

- 适用屏：分类列表、聚合搜索、观看报表、收藏点卡片加载、文件权限。
- 原则：Pigeon 是请求/响应，LiveData 是推送。impl 用 `@async` 方法
  一次性订阅（`observeForever` + 全路径 `removeObserver`，或等效
  latch），拿到一次结果即完成回调；Dart 侧显示加载态，失败显示 host
  文案 + 重试。**本规划不引入 EventChannel/流式通道**：机制单一，
  全部批次同一套写法；流式只在实验室终端场景出现，而终端建议留原生。
- 进度（仅聚合搜索）：host 提供进度快照方法，Dart 在搜索 `@async`
  pending 期间每 300ms 轮询一次，完成后停止轮询。逐站进度条以轮询
  快照渲染，与原生推送进度在观感上等价即可，不逐帧对齐。
- 超时与必完成：每个 `@async` host 方法必须在一切路径完成回调（含超
  时 → 错误结果 + host 文案）；Dart 不得无限等待。`SiteViewModel`
  实例化细节（`ViewModelProvider(hostActivity)` + 一次性订阅）由 B8
  批内确认并写入该批任务文档。
- 刷新：网络屏必须提供显式下拉刷新（Dart 侧重新调快照方法）；`resumed`
  重取继续适用（C1/C2 同款），但前台实时更新缺口（EventBus 推送在
  Flutter 前台时不可见）对全部屏一律接受，不为此加机制（见 §7-R3）。

### 4.7 图片加载：host 解析 + 共享 Dart 组件（D3 的推广）

- 全部图片屏：host 委托 `ImgUtil.getUrl` 解析（干净 URL + 头表），
  字段形态固定为 `PicSpec`（url + referer/cookie/user-agent +
  `extraHeadersJson` + fallbackUrl + placeholderText/Color，D1 形态）；
  备用 URL 同样过 `getUrl` 清洗；单条解析失败只降级该条。
- Dart 侧统一用共享 `PicImage`；`data:` URL 显示占位（D6，已知缺口）；
  不更新 `RouteSelector` 健康信号（C2 已知缺口，推广到全部屏，接受）。
- 磁盘缓存决策门：若联合真机验收中多屏出现“滑回闪烁/长时间空白”，
  再评估引入 `cached_network_image`（pin 版本 + 过 CI），不在本规划
  的批次内预设。

### 4.8 mobile-only 类可见性：五招按序选用

impl 全部在 main 源码集（leanback 构建也要编译它们），mobile-only 类
（`Product`/`MobileWindow`/`SyncDialog`/Hk API 等）不得直接引用。
选用顺序（前者不可行才用后者）：

1. 委托 main 可见的公开 API（D3：`ImgUtil.getUrl`）；
2. 一词放宽可见性（C1：`AboutDialog.showGithubProxy` private→public，
   零行为变化）；
3. 复刻纯计算并标注漂移警告（D4：网格几何）；
4. 反射 + ProGuard keep（D5：`SyncDialog`；仓库已有 `-keep ...tv.**`）。
5. （仅 Hk/B20 备用）impl 放 mobile 源码集 + 注册器间接层：`C1ScreenActivity`
  （main）不能直接引用 mobile impl，需一层注册器接口；设计细节留到
   B20 批内，本文档不定死——这是 Hk 待定的技术原因之一。

### 4.9 联合推送下的批次纪律

- 顺序实施：全部批次共享同一工作树/分支与若干 choke-point 文件
  （`main.dart`、`C1ScreenActivity`、`C0ShellActivity`、`Setting.java`、
  `b8-c0.yml`、`.gitignore`），**禁止并行实施**；一批提交干净后下一批
  才开始。每批一个 task_guard 会话 + 一个提交 + 一个恢复标签。
- 每批提交可独立 revert：只含该批的合约/impl/页面/注册表条目/开关/
  任务文档；共享组件只加法；族注册表条目只加法。revert 后树仍自洽
  （路由表少一条、开关少一个，不影响其他屏）。
- 主线漂移：`main` 在联合推送期间会继续移动；父代理每完成约 5 批或
  `main` 有实质移动时，把 `main` 合并进分支一次（与 C0 时的 PR #2
  同款），避免最后合并爆炸。合并是父代理的工作，不占用批次编号。
- 联合推送前：父代理做一次全 diff 复核（范围、开关默认关、TV 零影响、
  无 schema 变更），再推；CI 失败按 §7-R1 归因到批，fix-forward，
  不改写历史。

## 5. 回滚

- 开关级：实验屏关掉某批开关，该批屏立即回到原生；其他批不受影响。
  全部开关关闭 = 全部原生（零行为变化，含已验收的 C1/C2）。
- 批次级：联合推送前 revert 该批提交；推送后如需撤批，关开关即可，
  代码保留在分支里不生效。
- 分支级：丢弃 `b8-flutter-rewrite`，`main` 零影响。无数据层变更，
  无需数据回滚；开关布尔键残留无害。

## 6. 待 Wei 定事项（每项一句话即可）

| # | 事项 | 建议 | 不定会怎样 |
| --- | --- | --- | --- |
| P1 | 播放器设置页（B6）迁不迁？它是设置 UI，但每行都配置播放器 | 迁（设置工作，非播放器工作；风险低） | B6 跳过，该页永久原生；不影响其他批 |
| P2 | 插件管理（B19）迁不迁？安装/重载/文件选择重且险 | 排最后迁，安装/重载/文件选择 100% 留 host；留原生也可接受 | B19 不排入联合推送 |
| P3 | 海阔族：列表/详情/搜索留原生？管理屏迁不迁？ | 列表/详情/搜索留原生（规则驱动 + 接播放器）；管理屏可迁 | Hk 族全部留原生；首页 hk:// 站走原生 fallback（已定，不受影响） |
| P4 | 实验室：列表/详情迁不迁？终端/输出留原生？ | 列表/详情可迁（排最后）；终端/输出留原生（流式 I/O 无机制） | 实验室全部留原生 |
| P5 | 短剧确认：整屏排除（含浏览网格），不做拆分迁移 | 确认排除（播放形态；拆分需先重构原生 Activity） | 按排除执行；Wei 随时可改口 |
| P6 | 首页终态：Flutter 首页取代原生首页（含 Dart 玻璃底栏），hk:// 站与 Web 主题时 fallback 原生 | 确认该终态 | B11 按此终态实施；Wei 随时可改口 |

P1–P4 在对应批次开工前定即可，不阻塞 B2–B5、B7–B18。P5/P6 是确认
项，按建议执行，Wei 有异议随时叫停。

## 7. 风险与未能验证的项

| # | 风险/待证明项 | 影响面 | 缓解/证明方式 |
| --- | --- | --- | --- |
| R1 | 联合构建失败面：N 个合约 codegen + N 个 impl 签名 + N 个 Dart 页一次验证；`flutter analyze` 一个 warning 即失败（C1 run #8 教训）；一批出错，全批无包 | 全部批次 | 每批镜像 C1/C2 已验证形态手写；联合推送前全 diff 复核；CI 失败按文件路径归因到批，fix-forward；实在修不好则 revert 该批后重推 |
| R2 | 单引擎路由不变量在多屏下的压力：路由表 + query 参数 + 族 Navigator；进程死亡后族内深度丢失（只恢复到路由初始页），与原生 Fragment 回退栈不等价 | 族路由屏 | 族内深度丢失记为接受缺口，写进各批验收备注；路由/参数形态由 B3（首个族）真机证明后，后续族复用 |
| R3 | 刷新语义边界：`resumed` 重取覆盖不了前台 EventBus 推送；网络屏加载后无实时更新；多屏一致性只靠原生 resume 刷新 | 全部屏 | 接受缺口（C2 同款）；网络屏强制下拉刷新；各批验收清单写明刷新用例 |
| R4 | 嵌入式 `FlutterFragment` 下键盘/IME/焦点行为未验证 | B5、B9 | B5 小切面先行证明；异常则 B9 停下等决策 |
| R5 | 图片语义对等放大：`PicSpec` + `PicImage` 是全部图片屏的单点；系统性缺口（`data:`、健康信号、磁盘缓存）会同时命中多屏 | 全部图片屏 | 每批验收清单含图片对照项；磁盘缓存决策门见 §4.7 |
| R6 | Choke-point 文件（`main.dart`、两宿主 Activity、`Setting.java`、workflow）被每批编辑，一处坏改动影响全部屏 | 全部屏 | 顺序实施 + 每批最小 diff + 复核；禁止并行 |
| R7 | 规模与漂移：约 20 批、单设备验收；`main` 持续移动，分支漂移成本随批数增长 | 全规划 | §4.9 的定期合并；批顺序可调（B14–B18）；Wei 可随时叫停剩余批次，已做批次不受影响 |
| R8 | `SiteViewModel` 一次性订阅形态（`ViewModelProvider` + `observeForever`）未验证，泄漏/生命周期风险 | B8、B10 | B8 批内确认并写进任务文档；CI + 真机证明 |
| R9 | 族注册表与共享组件的“只加法”纪律被打破时，revert 独立性丢失 | 联合推送 | 每批复核 diff：共享文件只含加法行；违规即拆批 |

## 8. Recovery anchor（恢复锚点）

- 目标：B8 全量迁移批次规划（见 §0 完成句）；评估见
  `docs/b8-flutter-rewrite-assessment.md`，C0/C1/C2 见对应文档。
- 状态：规划完成；§3 批次顺序与 §4 共性决策已定；§6 的 P1–P6 待 Wei
  定（P1–P4 不阻塞 B2–B5、B7–B18）。无产品代码改动。
- 分支/HEAD：`b8-flutter-rewrite`，基线 `699f1f00`（B1 历史页提交）；
  本文档为本次唯一新增文件。
- 变更文件：仅 `docs/b8-full-migration-plan.md`（新建）。
- 验证：`git diff --check` 通过；diff 仅含本文档；`main` 仍指向 `f39a6e0`；
  工作树干净；构建/真机验证全部待 CI（§7）。
- 未解决风险：§7 的 R1–R9；§6 的 P1–P6 待决策；任一关键项失败即按
  §5 回滚或停在当批。
- 下一步：Wei 对 §6 表态（P1–P4 可后定）后，按 §3 顺序开 B2（收藏页），
  另开 task_guard standard 会话实施；本文档不直接转为实施。
