# B 档 UI 改版 — BieCuiLe → 墨影视（B1–B7）

任务来源：用户 2026-11-10 21:50 定“B档全套”（B1–B7，B8 全量 Flutter 重写不做）。
参考：`~/workspace/ui-ref/analysis.md`（第 4 节）、参考 APK `~/workspace/ui-ref/ref-app.apk`（BieCuiLe com.langhuan.tv v1.1.5，Flutter UI，只能仿外观）。
基线：main `c3c507e9`，仓库干净，无预存脏文件。
约束：只在本地改、本地提交，**不推 GitHub**；不编测试版；每做完一项走一次 task_guard finish（一个 guard 会话 = 一次原子提交 + recovery tag）；保留墨影视已有改动（品牌/包名 com.github.mooyingshi.osc、液态玻璃、短剧、Archive.org 爬虫、本地源、默认搜索本站、底栏 6 项）；底栏上限 6 项；本机无 Android SDK，编译检查靠 GitHub 构建。

## 进度

| 项 | 内容 | 状态 | commit |
|---|---|---|---|
| B1 | 首页轮播横幅（ViewPager2，取当前站首页推荐，无数据隐藏） | 已实现，待 GitHub 构建验证 | ef5b9e0d |
| B2 | 首页“继续观看”横滑行（History + 进度条，点进续播） | 已实现，待 GitHub 构建验证 | 7e23040d |
| B3 | 底栏改悬浮玻璃胶囊（GlassNavBar → 居中悬浮药丸，底栏仍 6 项） | 已实现，待 GitHub 构建验证 | 6d163ba1 |
| B4 | 详情页丰富（演员阵容/相关推荐/类似作品/外部链接/换平台/源搜） | 已实现，待 GitHub 构建验证 | 1be0182f |
| B5 | 首页自定义 JS 组件区（Bangumi/豆瓣/Mikan/TMDB 小组件引擎） | 已实现，待 GitHub 构建验证（见下 B5 小节） | 1ea6db7d、da8878f9、50c4489f、a7bd9322、96541e74、757b9c81 |
| B6 | 播放器画面调节 + 黑边虚化 + Anime4K | 未做：需单独立项（见下） | — |
| B7 | 播放器多内核 media_kit 切换 | 不做：已有等价实现（见评估） | — |

## Recovery anchor

- 目标：B1–B7 逐项落地到墨影视原生 UI，B7 先评估再决定做不做；全部本地提交、不推、不编。
- 当前阶段：B1–B5 已提交；B6 未做（需单独立项）；B7 已评估为不做。
- 分支/HEAD：main / 757b9c81（B5 已提交，本地 only，未推、未编）。
- 已完成：B1 首页轮播横幅（ef5b9e0d）；B2 继续观看横滑行（7e23040d）；B3 悬浮玻璃胶囊底栏（6d163ba1）；B4 详情页演员回退（1be0182f，其余五项原已存在）；B5 首页 JS 组件区（见下 B5 小节，6 笔提交）。
- 未验证：B1、B2、B3、B4、B5（无本地 SDK，编译靠 GitHub 构建；每项只做静态核对）。
- B5 已做（2026-11-11，用户 07:55 说“开始b5”后单独立项完成；设计文档 docs/b5-js-home-widgets.md）：
  - 单元 0 设计文档（1ea6db7d）：组件格式（`var WidgetMetadata` + 顶层 async 函数，非 ES 模块）、沙箱 API（Widget.http/tmdb/storage/dom/fetch/console，禁 local/Android 类/文件系统/Setting/VodConfig，TMDB Key 不进脚本）、缓存（widget-cache/ 按模块调用 + cacheDuration TTL）、首页分区渲染、内置 4 组件、失败只隐藏该行。
  - 单元 1 引擎（da8878f9）：WidgetHost（每组件一个 QuickJSContext，load 10s / invoke 20s+5s 超时）、WidgetBridge（`__widget*` 全局，OkHttp http、TmdbService.get、Prefers 前缀存储、jsoup DOM）、WidgetCache、WidgetMetadata/Module/Item、widget_host.js 适配、B5 专用 TmdbService.get(path, params, config)（TTL 1h，401 熔断 + 陈旧缓存回退）、build.gradle 加 jsoup 1.22.2。Node 桩测试 24/24。
  - 单元 2 内置组件（50c4489f）：assets/widgets/{bangumi,douban,mikan,tmdb}.js（改编自 BieCuiLe 脚本，加 homeModule；mikan 的 loadDetail 等磁力详情代码已删；tmdb 的 `window` 变量改名 timeWindow）。Node 桩测试 54/54。
  - 单元 3 首页渲染（a7bd9322）：WidgetManager（4 组件并行加载，先贴缓存再后台刷新，会话内 15 分钟节流，单组件失败只隐藏该行）、WidgetSectionAdapter + 两布局、VodFragment 接入（widgetSections 在继续观看行之后、分类栏之前，hide/show/网页首页/沉浸模式同步显隐，onDestroyView 销毁）、Setting.isHomeWidget/putHomeWidget（Prefers home_widget，默认关）。
  - 单元 4 浏览页（96541e74）：WidgetBrowseActivity（模块片 + 参数片 + 3 列分页网格，enumeration 弹单选框、string 弹输入框、belongTo 联动显隐，分类模块不进浏览页；第 1 页先贴缓存再刷新；点条目在当前资源里搜片名）+ WidgetGridAdapter + 3 布局/菜单；WidgetManager.defaultParams/paramsKey 改公开、抽 isParamVisible、加 visibleParams；行标题加“更多”按钮进浏览页；mobile Manifest 注册；3 语言 strings 加 5 条。
  - 单元 5 开关（757b9c81）：增强设置加“首页组件区”行（紧跟“默认搜索本站”，点行切换，默认关）+ setting_home_widget 三语言文案。
  - 与设计文档的偏差：Setting.isHomeWidget/putHomeWidget 落在单元 3（不是单元 5，单元 5 只加开关行 + 文案）；单元 3 多了一个布局文件 adapter_widget_item.xml（设计文档只列了 section 布局）。
  - B5 静态核对（单元 6，未编译）：全量 `git diff --check` 通过；9 个 XML 解析通过；全部新 R.string/R.id 与三语言 strings/布局 id 交叉核对无缺失；WidgetHost/WidgetCache/WidgetMetadata/Module/Item/TmdbService.get 的调用签名逐项 grep 核对；底栏仍 6 项；工作树干净，本地 main 757b9c81，未推、未编。
  - B5 待真机验证（推后编测试版才能验）：开关开后首页出现 4 行（Bangumi 每日放送 / 豆瓣热门电影 / Mikan 每日放送 / TMDB 趋势）、点“更多”进浏览页切模块改参数能翻页、点条目进站内搜片名、关开关行消失、断网/超时只隐藏该行不影响首页；Bangumi 搜索接口 POST 本机 curl 曾 25s 超时（GET 正常），组件须容错、真机上再看。
- B6 未做原因：播放器画面调节（对比度/饱和度/色相/伽马/亮度）+ 黑边虚化 + Anime4K，1–2 周；Anime4K 要 shader/逐帧处理，手机端性能与发热必须真机测；同属功能级工程，需单独立项 + 设计文档。
- B7 评估结论：不做。media_kit 是 Flutter 专属包（Dart 封装 libmpv），原生 Android App 无法使用；墨影视已有等价且更多的实现——三内核 Exo/ijk/mpv（player/engine/ 下 ExoPlayerEngine、IjkPlayerEngine、MpvPlayerEngine），渲染路径（PlaybackAutoContext.Kernel：EXO/IJK/MPV；RenderPath：exo-tunneling、mpv-surface-direct、mpv-gpu、ijk-native），用户可选手动切换（PlayerSetting → PlaybackAutoContext.Kernel，见 PlayerManager.java 约 8498 行）。BieCuiLe 的 media_kit 底层就是 libmpv，与墨影视的 MpvPlayerEngine 同源；引入 media_kit 需嵌 Flutter（即 B8，已排除），零播放收益。
- 风险：task_guard 是单会话状态（`.codex/task-state/current`），不可并行跑多个 guard 会话——B 各项必须串行做、串行提交。
- 下一步：把 B5 的汇报交回主对话；用户说“推”再推 main、按新规矩直接编测试版；B6 是否单独立项、由用户定。
