# 喜好 B 全套版（dice-b-full）

## Recovery anchor
- 目标：墨影视新增 Rove 同款“喜好选片”（老虎机摇片），B 全套：老虎机动画 + 4 片池（豆瓣/想看/收藏/当前站源）+ 心情/类型/时长筛选 + 不重复/避雷/题材屏蔽 + 历史/预设/背景换色。
- 用户批准：2026-11-20 10:19 定做 B 全套版；10:21 发截图定入口在首页搜索条内右侧、历史图标左侧放骰子按钮；增强设置“特色功能”开关（默认开）控制其显隐。
- 分析报告：~/workspace/rove-like/ANALYSIS.md（290 行权威版）。Rove APK 仅作原理参考，**不搬对方美术资源与代码原文，全部自画自写**。
- 分支/HEAD：main @ 1082f96c（第 6 轮已发状态）。
- 阶段：① 数据层已完成 ② UI 与 ③ 池与逻辑已完成并发布 ④ 修复 2（dice-fix-2）：选中态 + 站源池心情生效 + 老虎机改海报滚动，已发 v5.6.0-beta-202611201341。⑤ 修复 3（dice-fix-3）：空原因分开报 + 站源空时豆瓣兜底并明示 + 进页/切池预取海报 + 占位加亮描边，已发 v5.6.0-beta-202611201517。⑥ 修复 4（dice-fix-4）：老虎机三状态全程空白，建带时序重构，已发 v5.6.0-beta-202611202049，用户 22:23 截图确认三列海报已出。⑦ 第 5 轮（dice-round-5）：点老虎机海报直接播 + 类别三档（电影/电视剧/全部），已发 v5.6.0-beta-202611202227。⑧ 第 6 轮（dice-round-6）：按用户参考图 1:1 复刻喜好页版式（黄条标题、紫色帽檐老虎机 + 每列片名、池子/预设/心情横滚、可折叠随机设置、历史横卡），影片类型取自当前站源筛选数据，已发 v5.6.0-beta-202611210626。⑨ 第 7 轮（dice-round-7）：老虎机机身 220dp→150dp、摇一摇改通栏按钮、未选中 chip 改深底白字，见文末“第 7 轮”一节，已发 v5.6.0-beta-202611211043。
- 当前状态：第 7 轮已发 v5.6.0-beta-202611211043（2026-11-21 10:54 构建一次成功），待用户真机确认（用户 2026-11-21 10:37 截图反馈三点：海报太高、摇一摇单独一行、未选中 chip 看不清）。约定：dice_duration ""=不限、"long"=长片、"short"=短片；dice_bg 0=跟随主题（默认深色底），1–4 为色板下标；dice_mode 保留未用；摇片历史存 Vod 数组 JSON；豆瓣 Vod 无站源，点详情走站内搜索片名，不硬编站源字段，点老虎机海报则后台搜名解析后直接播。

## 修复 3（dice-fix-3，2026-11-20 15:10 用户截图，装 v5.6.0-beta-202611201341）
- ① 空原因分开报，不再一句 dice_no_result 盖住所有情况：无站源仍 dice_no_site；站源首页无内容新增 dice_site_empty（“当前站源没有返回内容，换个站源或检查网络再试试”）；历史抽完仍 dice_exhausted（分支保留 candidates 非空守卫，空候选不误判）；全被避雷/题材屏蔽滤掉新增 dice_all_blocked（candidates 非空、base 为空、非全历史时逐项重算 blockedNames/blockedTags，全是才报）；其余仍 dice_no_result。
- ① 站源首页无内容多努力一次：fetchSite() 里首页列表与心情映射分类都为空时，再拉第一个分类第 1 页（fetchFirstCategory，只拉第一个）；仍为空才走“站源无内容”分支。心情映射分类优先于首页列表的 fix-2 顺序不动（站源有内容+心情→分类生效）。
- ① 站源池彻底为空给一条出路：rollSync 里 pool==site 且候选为空（已试过首分类）时调 fetchDouban() 兜底，成功则 onSuccess 带 siteFallback=true 返回，页面 toast dice_site_empty_douban（“当前站源没有内容，已用豆瓣池摇出”），不许静默换池；豆瓣也失败再报 dice_site_empty。豆瓣池原有失败降级收藏池逻辑不动。Callback.onSuccess 新增 siteFallback 参数（第 4 位），唯一实现 DiceFragment 已同步改。
- ② 老虎机进页就显示：新增 DiceEngine.prefetch()（后台线程取数、App.post 回主线程，小回调接口 PrefetchCallback；按当前池走 fetch + filterBase + filterMoodType 同款过滤，心情无匹配同样回落整池；站源池无内容用豆瓣热门 tag“热门”海报兜底；全部失败返回空列表，不抛错）。DiceFragment.initView() 进页调一次，拿到候选就 slotMachine.setItems(empty, candidates) 预填三列；切池（onPoolSelected，含预设切换）也重新预填。预取用代次 mPrefetchGen 作废：摇片时 +1，迟到回调不盖本轮结果；摇动中回调直接丢弃；Fragment 已 detach 时不碰 binding。
- ② 占位必须看得见：SlotMachineView.placeholder() 由纯色 #FF1E293B 改成 GradientDrawable 填充 #FF2A3648 + 1dp 描边 #FF3D4E66 + 12dp 圆角，与机身底 #FF141A26 明显拉开；列间仍 2dp 间隙。代码里无“?”占位。
- ② 预填失败也有东西显示：素色占位 + toast dice_prefetch_empty（“暂时拉不到候选海报，先用占位显示”），一页只 toast 一次。
- ④ 心情多选核实（只看代码）：moodGroup 布局无 singleSelection（默认多选），点一个心情只切换该 chip，addMultiChip 监听调 saveMultiSelection 存整组选中态，mBuilding 守卫拦住重建时的写入；截图四心情全选是之前点选攒下的持久化多选，无 bug，未动心情逻辑。
- Recovery tag：由 task_guard finish 创建 recovery/dice-fix-3/&lt;timestamp&gt;。

## 修复 4（dice-fix-4，2026-11-20 20:33 用户截图 + 20:34 6.7 秒录屏，装 v5.6.0-beta-202611201517）
- 现象：摇片能出结果（结果区海报正常），但老虎机进页、摇动中、停下后三个状态全程空白。录屏抽帧（f_001 摇之前、f_003 摇片中、f_007 第二摇摇片中）像素核查：机器区 y≈250–570 全是均匀 (20,27,37) 即 view 自身背景 #141A26；占位色 #2A3648、描边 #3D4E66、列分隔、海报一概没有，占位色像素全在 y≥600（下方 chips），机器里一个子 View 都没画出来。
- 根因：建带只发生在两条路——initView 的 setItems（当时 mItemHeight==0，只建了数据表，没建子 View）和 onSizeChanged（layout 阶段、measure 已过）；竖带是 WRAP_CONTENT 高、每格又占满整机高，measure 时带子还是空的（高 0），之后没有任何一次重测把 12 格铺进可见区。摇片流程本身在跑（onSuccess→setItems→spin→onStop→结果区出片，f_007 结果已换新），spin 跑起来说明当时 mItemHeight>0、setItems 也调过 buildStrips，但子 View 始终没画出来——丢在“建带时序 + WRAP_CONTENT 带子”这一环，不是某一行写错，也不是网络/Glide（结果区同一批海报正常加载）。两版占位 Drawable（fix-2 纯色 #1E293B、fix-3 渐变描边）都空白，佐证与 Drawable 无关。
- 改了什么（只动 SlotMachineView.java 一个文件，spin 减速/停转回调、占位色、DiceFragment/DiceEngine/布局均未动）：① init() 先按已知的固定机身高 220dp（fragment_dice.xml 定值）建带，36 格占位在第一次 measure 之前就存在，进页第一帧就有东西；② 竖带改精确高度 12 格 × 格高，不用 WRAP_CONTENT，12 格永远落在带子自身边界内，不再靠事后重测；③ onSizeChanged 换成 onLayout() + onAttachedToWindow() 的 ensureStrips 兜底（高度或数据代次变了才重建，高度/数据没变是空操作，摇动中不打断平移）；④ setItems 记数据代次 mDataGen，高度未知时 post 补建；⑤ buildStripLists 取末格结果加越界守卫（init 时 mResults 为空，直接 get 会越界）；⑥ onDetachedFromWindow 置 mStripsBuilt=false，重挂时重建（Glide clear 会置空已加载海报的 drawable）。
- 验证：本机 git diff --check 通过；逐行核对（进页占位、摇动滚动可见、停下是结果海报、占位可见、无悬空引用、无旧包名回流、底栏仍 6 项）；无 JDK/SDK 不本地编译，编译检查走 GitHub Actions；真机三状态验证待用户装新测试版确认。
- 顺手清理：核查“修复 3”一节全文无 U+FFFD 乱码字符，“心情无匹配同样回落整池”一句已是正确文字，无需改；Recovery anchor 两行旧状态文案（fix-2/fix-3“待推 origin 编测试版”）已按实际更新：fix-2 已发 v5.6.0-beta-202611201341，fix-3 已发 v5.6.0-beta-202611201517。
- Recovery tag：由 task_guard finish 创建 recovery/dice-fix-4/&lt;timestamp&gt;。

## 第 5 轮（dice-round-5，2026-11-20 22:23 用户截图确认第 4 轮修好，三列海报已出，同时提两点新要求）
- 需求 1：点老虎机任意一列海报直接开始播那部片（真的开始播放，不停在详情页）。SlotMachineView 新增 OnPosterClickListener：每列 FrameLayout 加点击监听，滚动中（mSpinning）直接忽略；否则按当前竖带位移算正中格下标（Math.round(-strip.getTranslationY() / mItemHeight)，夹在 0..11），取 mStripLists[column] 里那格的 Vod 回调给 DiceFragment。停下后点到的是结果片，进页未摇时点到的是预取候选片，都能播。DiceFragment.playDirect(Vod)：有站源（siteKey + id）直接调现成的 VideoActivity.startDirect(activity, key, id, name, pic, remarks)，无选集参数即从头播第一集，不造播放器、不改 VideoActivity；豆瓣片无站源时后台线程用 SiteApi.searchContent（当前站，SearchActivity 用的同一条路）按片名搜一次，取第一个有 siteKey + id 的结果调 startDirect，搜不到回落 SearchActivity.start(name) 并 toast dice_direct_not_found（“站内没搜到这部片，已打开搜索页”），不静默失败。
- 需求 2：类别三档（全部 / 电影 / 电视剧，默认全部）。DiceSetting 新增 dice_category（"all" 默认 / "movie" / "tv"）+ getCategory()/putCategory()（非法值回落 all）；fragment_dice.xml 在 poolGroup 下方加单选 ChipGroup categoryGroup，复用 DiceFragment.addSingleChip + styleSelectableChip，选中存 DiceSetting 并 prefetchPrefill()（与切池同款行为），切池/切类别各记各的键，不互相串。DiceEngine：DOUBAN_URL 的 type 参数化，movie 档 type=movie，tv 档 type=tv，all 档 movie 和 tv 各拉一页合并后打乱（单路成功就用，两路都失败才按现有降级逻辑走）；站源池新增 filterCategory（只对站源池生效，收藏/想看池不筛，豆瓣池已按 type 拉取不再筛），Vod 无 typeId，用片名 + 类型名 + 备注含关键词判断——电影档 ["电影"]，电视剧档 ["电视剧", "连续剧", "剧集"]（不用单字“剧”，会误中“剧情”）；筛不到回落到不过滤的整池并 toast dice_category_fallback（“当前类别在站源里没有片，已按全部类别摇出”），绝不摇空；摇片（rollSync）和预取（prefetchSync）两条路都过这一层，预取失败/回落不 toast。Callback.onSuccess 新增 categoryFallback 布尔参数（第 5 位，与 fallback/moodFallback/siteFallback 同款），唯一实现 DiceFragment 已同步改；预取回调不用加。预设：DicePreset 新增 category 字段，保存/应用与 pool/moods/types/duration 同款，否则切了预设会丢类别。
- 字符串：dice_category_all/movie/tv、dice_category_fallback、dice_direct_not_found（strings.xml dice 串区）。
- 验证：本机 git diff --check 通过；逐行核对（点三列海报播的都是点的那部、豆瓣片搜不到回落搜索页、三档各摇一次、切池/切类别不串、预设带类别、无悬空引用、无旧包名回流、底栏仍 6 项）；无 JDK/SDK 不本地编译，编译检查走 GitHub Actions；真机验证待用户装新测试版确认。
- Recovery tag：由 task_guard finish 创建 recovery/dice-round-5/&lt;timestamp&gt;。

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

## 第 6 轮（dice-round-6，2026-11-21 用户发参考界面截图，要求照着 1:1 复刻）
- 版式来源：用户发的参考图 `~/workspace/user/files/1000305733_0_h1p2.jpg`，从上到下 7 个分区一一对应：① 顶栏（标题左侧黄色竖条 accent + 右上角彩色圆点图标）② 老虎机（三列、每列上下紫色圆角帽檐、中间海报、下方每列对应片名白字截断省略号）③ 池子行（药丸 chips 横向滚动）④ 预设行（描边药丸 chips）⑤ 心情（标题 + 两行横向滚动 chips）⑥ 随机设置（可折叠，默认展开：时长筛选 + 影片类型 + 类别 + 三个 iOS 风格开关）⑦ 骰子历史记录（横向滚动卡片：海报 + 片名 + 再摇一次 / 加入收藏 / 永久避雷）。整页底色保持现有深色 #0B0F16 一系。
- 版式改动（fragment_dice.xml 全面重排）：标题行加 4dp 黄色竖条（新 drawable dice_title_accent.xml）+ 右上角背景按钮改成彩色圆点图标（新 vector drawable ic_dice_bg.xml，圆圈描边 + 黄/绿/蓝/粉四色圆点，点它仍是切换背景色）；老虎机上下各加一排三条紫色圆角帽檐（新 drawable dice_slot_brim.xml，#FF8A5CF6，10dp 圆角，与三列同宽、2dp 间隙对齐）；老虎机下方新增三列片名行（slotName0/1/2，白字 13sp，maxLines=1 截断省略号）；池子行改成 HorizontalScrollView（4 个池药丸 + 摇一摇药丸，旧大宽摇片按钮、类别旧位置、提示语、纵向“摇出的片”列表全部去掉）；预设行挪到池子行下方（横向滚动描边药丸，行末“+ 保存”小 chip，点预设应用、长按删除、保存入口都在行内）；心情改成两行横向滚动（moodGroupRow1/Row2，内置 + 自定义心情按序交错分两行）；类别三档挪进随机设置折叠区（影片类型下面单独一行，带“类别”小标题）；三个开关顺序按图改成不重复抽取 / 题材屏蔽 / 避雷影片，并换成 iOS 风格（新 res/color/dice_switch_track.xml 开=浅蓝 #FF8AB4F8、关=深灰，dice_switch_thumb.xml 白圆点）；随机设置折叠箭头按图改成展开“∧”/折叠“∨”；历史改成横向 RecyclerView（新 DiceHistoryAdapter + adapter_dice_history_card.xml，卡片 120dp 宽：海报 120x160 + 片名 + 再摇一次 / 加入收藏 / 永久避雷三个蓝色小字按钮）。
- 选中态配色（贴图）：池子选中粉紫实心 #FFAC6F9C、心情选中鼠尾草绿实心 #FF7F9E7F、时长/类型/类别选中紫色实心 #FF8A7CC8；未选中一律透明底 + 灰描边 #FF5A6B80 + 浅字；可选 chip 统一 40dp 高、18dp 圆角、14sp 字；池子药丸与摇一摇药丸最小宽 96dp。
- 老虎机（SlotMachineView.java，只加一个方法，建带修法原封不动）：新增 visibleNames()，按当前竖带位移算每列正中可见格的片名；DiceFragment 在预取填入后与停转回调里调 updateSlotNames() 刷新下方三列片名（停下后是结果片，进页未摇时是预取候选片）。点海报直接播（第 5 轮 playDirect）、预取、兜底 toast 全部保留。
- 摇一摇药丸就是摇片按钮：点它摇片，摇动中显示“摇片中…”并禁用，摇完恢复“再摇一次”；历史卡片“再摇一次”同样调 onShake() 按当前设置重新摇一次；“加入收藏”进 Keep 表（原 onKeep），“永久避雷”进屏蔽名单并从历史记录里删掉这部片（原 onBlock + 删历史 JSON）。
- 想看入口：长按历史卡片海报加入想看（Want 表，原 onWant，toast 不变）。原结果行想看按钮随纵向结果列表一起去掉，收藏/避雷已在历史卡片上有。
- 影片类型取自站源筛选数据（用户明确要求，不写死）：DiceFragment.loadSiteTypes() 后台调 SiteApi.homeContent(当前站源)，从返回 Result 的各 Class.getFilters() 里取表“类型/题材”的那一组——判定是 key == "class"（忽略大小写）或 name 含“类型/分类/题材”，值取 Filter.Value.getN()（空则 getV()），去重、去掉“全部/全部类型”后展示；这份数据就是 SiteApi.setTypes 按 typeId 把 Result.getFilters() 挂到各 type 上之后的那份，首页筛选弹窗 TypeFilterDialog 用的同一份（TypeFilterDialog.FilterProvider 取 Class.getFilters()）。切站后跟着换（onResume 发现 home key 变了才重取，代次 mTypeGen 拦住迟到回调）；某站源没有这类筛选数据（无站源、拉取失败、filters 为空）时回落到现有写死列表（喜剧/爱情/动作/科幻/悬疑/剧情/动画/恐怖），绝不出现空行；已选但不在新列表里的类型值追加在末尾，选中态不悄悄丢。类型 chips 仍存 DiceSetting dice_types，进 DiceEngine.filterMoodType 那条筛选路，逻辑与之前一致。
- 删掉的旧东西（本轮不再用）：DiceResultAdapter.java + adapter_dice_result.xml（纵向结果/历史行），纵向“摇出的片”列表与结果区随之去掉——摇出的片由老虎机三列海报 + 片名展示。dice_hint / dice_result_title 两个字符串暂留（未删串，只是不再引用）。其余逻辑（四池、类别三档、海报点播、预取、兜底 toast、预设保存/应用带类别、不重复/避雷/题材屏蔽、背景换色、清空历史）全部保留，只换皮不动逻辑。
- 字符串：新增 dice_category_title（类别）、dice_preset_save_chip（+ 保存）、dice_history_reshake（再摇一次）、dice_history_keep（加入收藏）、dice_history_block（永久避雷）；dice_duration_title 由“时长”改成“时长筛选”（贴图）。
- 验证：本机 git diff --check 通过；逐行核对（进页版式与参考图一致；三列海报/片名/点播正常；四池 + 摇一摇 pill 正常；预设应用正常；心情多选正常；类型 chips 跟着站源换、无筛选时有回落；三个开关正常；历史横卡三个按钮正常；长按海报加想看能用；无悬空引用、无旧包名回流、底栏仍 6 项）；无 JDK/SDK 不本地编译，编译检查走 GitHub Actions；真机验证待用户装新测试版确认。
- 构建修复：首版 4 处 HorizontalScrollView 用了不存在的 XML 属性 android:horizontalScrollBarEnabled（只有 View 方法，没有同名 XML 属性），手机两个构建与测试 job 全在 process*Resources 资源链接失败；改成 android:scrollbars="none"，重编。
- Recovery tag：由 task_guard finish 创建 recovery/dice-round-6/&lt;timestamp&gt;。

## 第 7 轮（dice-round-7，2026-11-21 10:37 用户发截图反馈三点，装 v5.6.0-beta-202611210626）
- ① 海报变矮：机身 220dp→150dp。fragment_dice.xml 老虎机 layout_height 改 150dp；SlotMachineView.java MACHINE_HEIGHT_DP 220→150，类注释与 onLayout 注释里的 220dp 同步改掉；紫色帽檐（上下各 24dp）和每列片名行不动，海报 CENTER_CROP 比例不变只是整体矮下来。第 4 轮建带修法（固定高度提前建带、竖带精确高度 12 格 × 格高、onLayout/onAttachedToWindow 兜底、数据代次守卫）跟着新高度走，只换数值不改逻辑；三状态（进页占位/滚动中/停下后海报）仍由同一套建带保证。
- ② 摇一摇单独列一行：池子行只留 4 个池 chips（poolGroup 横向滚动）；旧 shakeChip（池子行里的药丸）删除，DiceFragment 里 6 处引用（initView/styleShakeChip、initEvent 点击、onShake 禁用+“摇片中…”、onError 恢复、onSlotStopped 恢复）全部改到新按钮，无悬空引用。新增通栏 MaterialButton shakeButton（match_parent 宽、48dp 高、24dp 圆角药丸形，背景帽檐紫实心 #FF8A5CF6 + 白字加粗，与池子选中粉紫 #FFAC6F9C 同紫色系协调）；行为不变：点它调 onShake() 摇片，摇动中 setEnabled(false) + 文案“摇片中…”，摇完/失败恢复启用 + 文案“再摇一次”；禁用态背景暗紫 #FF4E4368 + 70% 白字（0xB3FFFFFF），摇动中也看得清，不用系统默认蓝按钮样子。
- ③ 未选中 chip 改深底白字：根因——第 6 轮把未选中底改成 Color.TRANSPARENT，但 ChipDrawable 会把透明底与主题默认的浅色 surface 合成，实测整片变浅灰底，浅字（0xFFE6EAF2）压上去几乎看不见（截图：豆瓣/收藏/心情各 chip、“+ 保存”全是浅灰底配浅灰字）；选中态用的是不透明实心色所以一直正常。修法集中在两个统一样式入口，不逐个改：styleSelectableChip（池子/预设外的全部可选 chip：池、心情、类型、时长、类别）与 styleOutlineChip（+ 保存 / 预设 chip）——未选中底改成深 slate 半透明 0xE61E2430（CHIP_UNCHECKED_BG，90% 不透明，盖住浅色 surface，在深红棕 0xFF241016、默认深色 0xFF0B0F16 及其他背景色下都呈深底），文字改纯白 Color.WHITE，描边由 slate 灰 0xFF5A6B80 改成 50% 白 0x80FFFFFF（CHIP_UNCHECKED_STROKE）；选中态实心色（池 #FFAC6F9C、心情 #FF7F9E7F、时长/类型/类别 #FF8A7CC8）+ 白字 + 对勾原封不动，与未选中深底区分开；没有改成全白底或高亮色。
- ③ 顺带核查同页其他文字对比度：随机设置区小标题 #B3FFFFFF（70% 白）、三个开关白字、历史卡片片名白字 + 三个小按钮浅蓝 #FF8AB4F8、每列片名白字、标题/心情/随机设置/历史标题白字，在深红棕与默认深色背景下对比度都够（次级文字不低于 70% 白），无须改；背景换色功能本身不动；两个弹窗输入框的 hint 是系统浅色弹窗上的默认灰字，不在深色页上，无须改。
- 不动：摇片逻辑、类型取站源筛选数据的逻辑、点播、类别三档、底栏（仍 6 项）、TV、数据库、workflows、libexo_ass.so。
- 验证：本机 git diff --check 通过；逐行核对（机身 150dp 两处一致且注释无 220dp 残留、三状态建带逻辑只换数值、摇一摇通栏且点按/文案/禁用切换正常、全部 chips 未选中深底白字且选中态不变、其他文字对比度、无悬空引用、无旧包名回流、底栏仍 6 项）；无 JDK/SDK 不本地编译，编译检查走 GitHub Actions；真机验证待用户装新测试版确认。
- Recovery tag：由 task_guard finish 创建 recovery/dice-round-7/<timestamp>。
