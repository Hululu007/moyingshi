# 喜好 B 全套版（dice-b-full）

## Recovery anchor
- 目标：墨影视新增 Rove 同款“喜好选片”（老虎机摇片），B 全套：老虎机动画 + 4 片池（豆瓣/想看/收藏/当前站源）+ 心情/类型/时长筛选 + 不重复/避雷/题材屏蔽 + 历史/预设/背景换色。
- 用户批准：2026-11-20 10:19 定做 B 全套版；10:21 发截图定入口在首页搜索条内右侧、历史图标左侧放骰子按钮；增强设置“特色功能”开关（默认开）控制其显隐。
- 分析报告：~/workspace/rove-like/ANALYSIS.md（290 行权威版）。Rove APK 仅作原理参考，**不搬对方美术资源与代码原文，全部自画自写**。
- 分支/HEAD：main @ 0df4c7ae（dice-fix-2 已提交，待推 origin 编测试版）+ 本次修复一次原子提交（dice-fix-3）。
- 阶段：① 数据层已完成 ② UI 与 ③ 池与逻辑已完成并发布 ④ 修复 2（dice-fix-2）：选中态 + 站源池心情生效 + 老虎机改海报滚动，已做完，待推 origin 编测试版。⑤ 修复 3（dice-fix-3）：空原因分开报 + 站源空时豆瓣兜底并明示 + 进页/切池预取海报 + 占位加亮描边，已做完，待推 origin 编测试版。
- 当前状态：dice-fix-3 已完成并提交，待核对后推 origin 编测试版。约定：dice_duration ""=不限、"long"=长片、"short"=短片；dice_bg 0=跟随主题（默认深色底），1–4 为色板下标；dice_mode 保留未用；摇片历史存 Vod 数组 JSON；豆瓣 Vod 无站源，点详情走站内搜索片名，不硬编站源字段。

## 修复 3（dice-fix-3，2026-11-20 15:10 用户截图，装 v5.6.0-beta-202611201341）
- ① 空原因分开报，不再一句 dice_no_result 盖住所有情况：无站源仍 dice_no_site；站源首页无内容新增 dice_site_empty（“当前站源没有返回内容，换个站源或检查网络再试试”）；历史抽完仍 dice_exhausted（分支保留 candidates 非空守卫，空候选不误判）；全被避雷/题材屏蔽滤掉新增 dice_all_blocked（candidates 非空、base 为空、非全历史时逐项重算 blockedNames/blockedTags，全是才报）；其余仍 dice_no_result。
- ① 站源首页无内容多努力一次：fetchSite() 里首页列表与心情映射分类都为空时，再拉第一个分类第 1 页（fetchFirstCategory，只拉第一个）；仍为空才走“站源无内容”分支。心情映射分类优先于首页列表的 fix-2 顺序不动（站源有内容+心情→分类生效）。
- ① 站源池彻底为空给一条出路：rollSync 里 pool==site 且候选为空（已试过首分类）时调 fetchDouban() 兜底，成功则 onSuccess 带 siteFallback=true 返回，页面 toast dice_site_empty_douban（“当前站源没有内容，已用豆瓣池摇出”），不许静默换池；豆瓣也失败再报 dice_site_empty。豆瓣池原有失败降级收藏池逻辑不动。Callback.onSuccess 新增 siteFallback 参数（第 4 位），唯一实现 DiceFragment 已同步改。
- ② 老虎机进页就显示：新增 DiceEngine.prefetch()（后台线程取数、App.post 回主线程，小回调接口 PrefetchCallback；按当前池走 fetch + filterBase + filterMoodType 同款过滤，心情无匹配同样回落整池；站源池无内容用豆瓣热门 tag“热门”海报兜底；全部失败返回空列表，不抛错）。DiceFragment.initView() 进页调一次，拿到候选就 slotMachine.setItems(empty, candidates) 预填三列；切池（onPoolSelected，含预设切换）也重新预填。预取用代次 mPrefetchGen 作废：摇片时 +1，迟到回调不盖本轮结果；摇动中回调直接丢弃；Fragment 已 detach 时不碰 binding。
- ② 占位必须看得见：SlotMachineView.placeholder() 由纯色 #FF1E293B 改成 GradientDrawable 填充 #FF2A3648 + 1dp 描边 #FF3D4E66 + 12dp 圆角，与机身底 #FF141A26 明显拉开；列间仍 2dp 间隙。代码里无“?”占位。
- ② 预填失败也有东西显示：素色占位 + toast dice_prefetch_empty（“暂时拉不到候选海报，先用占位显示”），一页只 toast 一次。
- ④ 心情多选核实（只看代码）：moodGroup 布局无 singleSelection（默认多选），点一个心情只切换该 chip，addMultiChip 监听调 saveMultiSelection 存整组选中态，mBuilding 守卫拦住重建时的写入；截图四心情全选是之前点选攒下的持久化多选，无 bug，未动心情逻辑。
- Recovery tag：由 task_guard finish 创建 recovery/dice-fix-3/&lt;timestamp&gt;。

## 修复 2（dice-fix-2，2026-11-20 13:36 用户两张截图）
- ① 选中态：全部可选 chip（池/心情/类型/时长）共用 styleSelectableChip——选中实心主色 #0B57D0 + 白字 + 对勾，未选中深灰底 #232D3D + 浅字；预设 chip 深灰底白字。
- ① 站源池生效：心情映射到分类名（开心→喜剧/爱情、治愈→剧情/爱情、热血→动作/武侠、悬疑→悬疑、浪漫→爱情、搞笑→喜剧、烧脑→悬疑/科幻、轻松→喜剧/剧情）；站源池先拉首页分类，命中分类直接拉该分类第 1 页（最多 3 个分类）做候选；收藏/想看池用映射类型词在片名/类型/备注里包含匹配；本地池心情/类型无任何匹配时回落到整池摇出并提示“心情在当前站源没有匹配，已按全部片摇出”，不再只剩 toast + 空格子；不重复/避雷照常工作；豆瓣池不动（心情当 tag 拉取）。
- ② 老虎机：SlotMachineView 由 Canvas 画“?”改成三列海报竖带（Glide 加载，CENTER_CROP），900/1400/1900ms 错峰减速停在摇出的 3 部片海报正中；引擎回传本轮候选池做滚动填充（前 11 格打乱循环 + 末格结果）；海报缺失/加载失败用素色占位，首次摇之前三列也是中性素色占位，绝不再出现“?”；不够 3 部时缺位占位不崩。
- Recovery tag：由 task_guard finish 创建 recovery/dice-fix-2/&lt;timestamp&gt;。

## 硬限制
- 底栏 menu_nav.xml 已 6 项满额，第 7 项启动就崩，**绝对不加底栏项**。
- TV 端不动；品牌、包名 com.github.moyingshi.osc.tv、玻璃、短剧、本地源、实验室、隐藏下载全保留。
- 不动 .github/workflows/*（gh token 无 workflow 权限）；不动 libexo_ass.so、keystore。
- 本机无 JDK/Android SDK，不尝试本地编译；唯一编译检查是 GitHub Actions。

## 设计决策
- 入口：首页搜索条（fragment_vod.xml，searchBar 内 divider 之后、searchHistory 之前）加 36dp 骰子按钮 searchDice，样式与 searchHistory/searchMore 同款（36dp、padding 8dp、tint colorOnSurfaceVariant）；VodFragment 接点击进 DiceActivity，显隐跟 Setting.isDiceEntry()。
- 开关：Setting.java 加 isDiceEntry()/putDiceEntry()（pref `dice_entry`，默认 true）；SettingEnhanceFragment“特色功能”分组加一行（仿 labNav 行写法），fragment_setting_enhance.xml 里 labNav 行附近加 diceEntry 行。
- 想看：按任务要求建独立 Room 表 Want（bean/Want.java + dao/WantDao.java + AppDatabase v49→v50 + MIGRATION_49_50），不复用 Collect（按站分组的 Vod 列表，语义不同）。注：分析报告建议 prefs JSON 免迁移，但任务明确要求建表，执行任务要求。
- 喜好状态：SharedPreferences，键名前缀 `dice_`，照 Rove `rove_dice_*` 15 键思路自定（pool / moods / custom_moods / types / duration / mode / no_repeat / block_film / block_genre / blocked_names / blocked_tags / presets / history / bg；want 在 Want 表，不放 prefs）。
- 收藏池：直接复用现有 Keep.getVod()（Room Keep 表，type=0）。
- 豆瓣池：GET https://movie.douban.com/j/search_subjects?type=movie&tag=%s&sort=rank&page_limit=20&page_start=%d；失败给明确报错并提示想看/收藏池离线可用，自动降级到当前站源/收藏池。豆瓣是未公开 web 接口，随时可能变/限流/418，**绝不当唯一池**。
- 当前站源池：走现有蜘蛛取数；无站源时提示先回首页选站源。
- 老虎机：自研 SlotMachineView（ui/custom/，Canvas 三列滚动 + 减速停转 + 停转回调），B 版核心，必须有动画，不用卡片代替。
- 页面：DiceFragment + fragment_dice.xml（手机端），仿 Rove fragment_rove_dice 外观自画（深色底、标题“喜好选片”、提示语“摇一下老虎机，选三部今晚的片”、结果区、心情 chips、随机设置折叠区、历史列表）；DiceActivity 仿 HistoryActivity 模式承载。
- 骰子图标：自画 vector drawable ic_dice，单色符号，与导航图标同框。

## 验收标准
- 摇一摇一次出 3 部，可再摇；每部可进详情、加入收藏、加入想看、永久避雷。
- 4 个池可轮换；豆瓣失败有明确报错 + 降级；无站源有提示。
- 类型/心情/时长筛选、不重复（默认开）、避雷（默认开）、题材屏蔽（默认关）均生效。
- 历史可看可点可清空；预设可保存/切换；背景可换色并持久化。
- 开关关掉后搜索条骰子入口隐藏，打开恢复。
- 底栏仍 6 项；leanback/BaseLoader 零改动；无悬空引用；无旧包名 com.fongmi.android.tv / 旧仓库地址回流；无 `Json.safeListElement` 赋 JsonArray、原始类型 ChipAdapter、lambda 捕获非 final 变量这类已知坑。
- 推 origin main 后直接触发测试版构建（beta + prerelease + publish_oci=false），构建成功，发布核对通过（tag/isPrerelease/8 资产，手机 arm64 在先）。

## 回滚
- 各阶段独立 guard 会话 + 独立 recovery tag；出问题按 tag 回退该阶段提交；数据库 v50 迁移只加表，不改旧表，降级安装会清库是 Room 默认行为，接受（测试版）。
- 未确认点（报告 §7：Keep type 映射、x0 分支、收藏同步云端目标、站源池取数细节）不写死，按墨影视现有实现走，不把推断说成定论。
