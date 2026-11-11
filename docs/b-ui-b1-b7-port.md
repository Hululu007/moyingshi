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
| B6 | 播放器画面调节五项 + Anime4K（黑边虚化不做） | 已实现，待 GitHub 构建验证（见下 B6 小节） | 28b19c98、8d901a1e、9501d64f、ff3eb885、92b2976b、ff4af5ba、b1a27158 |
| B7 | 播放器多内核 media_kit 切换 | 不做：已有等价实现（见评估） | — |

## Recovery anchor

- 目标：B1–B7 逐项落地到墨影视原生 UI，B7 先评估再决定做不做；全部本地提交、不推、不编。
- 当前阶段：B1–B6 已提交；B7 已评估为不做。
- 分支/HEAD：main / b1a27158（B6 已提交，本地 only，未推、未编）。
- 已完成：B1 首页轮播横幅（ef5b9e0d）；B2 继续观看横滑行（7e23040d）；B3 悬浮玻璃胶囊底栏（6d163ba1）；B4 详情页演员回退（1be0182f，其余五项原已存在）；B5 首页 JS 组件区（见下 B5 小节，6 笔提交）；B6 画面调节 + Anime4K（见下 B6 小节，7 笔提交）。
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
- B6 已做（2026-11-11，用户 14:38 说“b6”后单独立项完成；设计文档 docs/b6-picture-adjust-anime4k.md）：
  - 单元 0 设计文档（28b19c98）：五项口径 -100..100 默认 0（mpv 手册 + mpv_kit_video 文档实证）；Anime4K 上游文件 sha256 4c53ec2e…49a4 与 APK 内文件逐字节相同；ijk 不支持有据（vf0 只能 prepare 时设）；黑边虚化不做。
  - 单元 1 模型 + 持久化 + 文案（8d901a1e）：`player/picture/PictureAdjust.java`（值对象，clamp -100..100）+ `PictureAdjustSetting.java`（Prefers：5×int 默认 0 + anime4k 默认 false）+ 三端 strings 12 条（play_picture_adjust + pic_*）。
  - 单元 2 Exo 效果器（9501d64f）：`player/picture/PictureAdjustEffect.java`（GlEffect + BaseGlShaderProgram，抄 DynamicLutEffect；五项 uniform 映射按设计文档 §2.4；中性短路；HDR 抛错）。
  - 单元 3 mpv 侧（ff3eb885）：`assets/shaders/Anime4K_Upscale_CNN_x2_S.glsl`（上游原文件，一字不改）；`Anime4kShaderStore.java`（assets → cacheDir/mpv_shaders/，字节一致跳过）；`MpvPlayer` 加 `setVideoProperty` + `setAnime4kShader`，`applyShaderPipeline` 改双槽位（先卸后挂，Anime4K 在前、LUT 在后，LUT 预览清理保留）；`MpvPlayerEngine` 加 `setPictureAdjust`/`setAnime4kShader`（本单元暂无 @Override，单元 4 补）；`mpv_shaders` 登记到 CacheCleanupManager + CacheModuleRegistry。
  - 单元 4 接线（92b2976b）：`PlayerEngine` 加 4 个默认方法（supportsPictureAdjust/setPictureAdjust/supportsAnime4k/setAnime4kShader，默认不支持，ijk/System 零改动）；Exo supportsPictureAdjust=true；mpv 两方法补 @Override 且 supports*=supportsNativeLut；`LutEligibility` 加公开 `isHdrContent`；`PlayerManager` 加 `pictureAdjustEffect` + `currentVideoEffects()`（调节在前、LUT 在后）+ `setPictureAdjust`/`applyPictureAdjust`/两个不可用原因方法；4 处 safeSetVideoEffects 改走组合列表；clear/setNeutral/reset/prepare 各处同步调节状态；`applyLutForCurrentItem` 顶部调 `applyPictureAdjust` 重应用。
  - 单元 5 面板（ff4af5ba）：`ui/dialog/PictureAdjustDialog.java`（5 条 Slider 行 + Anime4K Switch + 重置；拖动实时生效并持久化；不可用时置灰 + 原因文案；样式抄 SliderNumberDialog）。
  - 单元 6 按钮（b1a27158）：`PlayerButtonSetting` 加 PICTURE_ADJUST（DEFAULT 里 LUT 后一位，默认可见）；`activity_tmdb_detail.xml` 加 playerPictureAdjust、`view_control_vod_action_tmdb.xml` 加 pictureAdjust（均在 LUT 按钮后）；`VodPlayerChrome` 加 picture 字段 + 三构造 + fromTmdbDetail；Controller 绑定（null 守卫）；Host 加 `showPictureAdjust()`；TmdbDetailActivity 加 host 实现 + `onInlinePictureAdjust()` + 两处按钮 map + 移动端点击接线。
  - 与设计文档的偏差：单元 4 scope 增加了 `MpvPlayerEngine.java`（补 @Override + supports*）与 `LutEligibility.java`（isHdrContent 公开 helper），设计文档单元 4 文件表里没列这两处；§2.1 的“引擎层调节照样生效”修正为——`lutAllowed=false` 的场景（部分直播/TV Activity 主动禁 LUT）下调节 effect 同样不挂（门禁复用 `canWarmLutPipeline`，与 LUT 同款），设计文档 §2.3 已补这句。
  - B6 静态核对（单元 7）：全量 `git diff d3f2b211..HEAD --check` 通过；12 条新 strings 三端齐全；底栏仍 6 项；Anime4K 文件 sha256 复核一致；ijk/System 走默认方法零改动；`TmdbDetailActivity.java` 括号 -6 与 HEAD 一致（改动前已有，字符串/注释里的括号）。
  - B6 待真机验证（推后编测试版才能验）：五项滑杆在 Exo/mpv 下实时生效、归零恢复原样；切 ijk 后面板提示不支持、切回后上次的值还在；Anime4K 开后 720p 动漫明显变锐、无掉帧；HDR 片源下 Exo 调节不生效（有提示）、mpv 下生效；LUT 与 Anime4K 同时开不闪退；开 Anime4K 播 10 分钟看发热/帧率/电量。
  - B6 未做：黑边虚化（需第二层模糊视频，是独立工程）；ijk 五项调节（vf0 只能 prepare 时设、硬解下未验证）；Exo Anime4K（需多 pass GlEffect 链 + 浮点中间纹理，未验证）；SystemPlayer；直播/TV 播放器按钮（引擎层已通用，只缺按钮接线）；全屏 VideoActivity 的控制栏（`view_control_vod_action.xml` / `dialog_control.xml` 里的 LUT 按钮旁未加画面按钮，见 B6 小节末尾）。
- B7 评估结论：不做。media_kit 是 Flutter 专属包（Dart 封装 libmpv），原生 Android App 无法使用；墨影视已有等价且更多的实现——三内核 Exo/ijk/mpv（player/engine/ 下 ExoPlayerEngine、IjkPlayerEngine、MpvPlayerEngine），渲染路径（PlaybackAutoContext.Kernel：EXO/IJK/MPV；RenderPath：exo-tunneling、mpv-surface-direct、mpv-gpu、ijk-native），用户可选手动切换（PlayerSetting → PlaybackAutoContext.Kernel，见 PlayerManager.java 约 8498 行）。BieCuiLe 的 media_kit 底层就是 libmpv，与墨影视的 MpvPlayerEngine 同源；引入 media_kit 需嵌 Flutter（即 B8，已排除），零播放收益。
- 风险：task_guard 是单会话状态（`.codex/task-state/current`），不可并行跑多个 guard 会话——B 各项必须串行做、串行提交。
- 下一步：把 B6 的汇报交回主对话；用户说“推”再推 main、按新规矩直接编测试版。
