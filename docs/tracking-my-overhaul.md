# 追更 + 我的页大改（Arcane 参考）

## Recovery anchor

- 目标：参考 Arcane（com.arcane.android 1.0.51-beta.1）的“我的”页，大改墨影视追更功能，并新建“我的”仪表盘页。用户 2026-11-24 22:55 定范围：追更和我的页一起做。
- 参考分析：~/workspace/drive-incoming/analysis-my-page.md（Arcane 报告全文）。Arcane 是 FongMi/webhtv 同源改版，原生 Kotlin + Compose；“我的”页是 10 张可排序、可隐藏卡片拼成的观影仪表盘；追更是独立日历屏（日期 Tab + 月历 + 时间轴 + 剧卡 + 播出前提醒，可接 Trakt）。
- 分支/HEAD：main @ acbaf1c1（2026-11-24 21:13 下载路径修复后）。工作树干净。
- 完成标准：P1–P3 每阶段独立提交 + 独立编测试版给用户测；每阶段保留现有功能（检查更新、未读、筛选、导入、下一季、通知）一个不少；TV 端不动；底栏不加项（6 项上限）。
- 当前状态：方案已定（见下），P1 未开始。下一步：开 guard 会话做 P1（追更页 UI 大改）。
- 回滚：每阶段一个 guard 会话一个提交；任一阶段出问题可单独 revert 该提交，追更引擎与数据不动。

## 现状（已核对）

- 追更引擎是全的：Following 实体已有播出表字段（nextAirSeason / nextAirEpisode / nextAirAt / nextAirWeekday / seasonTotalEpisodes / seasonReleasedEpisodes / officialStatus / unwatchedCount / notifyEnabled），FollowingMetadataClient 已从 TMDB 拉下一集播出日期（air_date）；FollowingUpdateCoordinator 按 nextAirAt 排检查；FollowingNotifier 目前只有新集通知（notifyUpdate），没有播出前提醒。
- 追更页现状：FollowingActivity（main 源集，手机 + TV 共用）+ 手机端 activity_following.xml：顶部 4 按钮（检查更新 / 全部已读 / 筛选 / 服务器导入）+ “追更 X 部 · 未读 Y 部”+ 一部剧一行的 RecyclerView，5 档筛选（全部 / 有更新 / 未看 / 已完结 / 失败）。
- 我的页现状：没有。最接近的是设置页与首页（继续观看、历史、收藏分散各处）。数据都在：History（观看记录）、KeepDao（收藏）、Following（追更）、首页继续观看。
- 入口约束：底栏 menu_nav.xml 共 6 项（vod / live / drama隐藏 / following隐藏 / local_source / setting），6 项是上限，加第 7 项启动就崩；玻璃底栏也是这 6 项。我的页入口不走底栏，走首页搜索条内图标（骰子喜好按钮同款位置放人物图标）。

## 设计决定

1. **范围**：追更页大改 + 新建我的页；只改手机端，TV 端不动；Trakt 日程源放第二阶段（要接账号登录，工作量大，先不做）。
2. **追更页三段式**（照 Arcane HomeTrackingScreen）：顶部日期 Tab（今天 / 明天 / 本周 / 全部）+ 月历条 + 时间轴，剧卡显示海报 + “N 集待看” + “第 X 季 · 第 Y 集” + 播出平台 + 已完结标识；点卡片进详情 / 继续播。无播出日期的剧（没 TMDB 匹配）进“待定”组，只在“全部”里出现，不丢数据。
3. **播出提醒**（新做）：每部剧可设播出前提醒（提前 15 / 30 / 60 分钟，默认 30），AlarmManager + 通知渠道，到点推“第 X 季 · 第 Y 集”；完结的剧不提醒；重启后恢复闹钟。开关放追更页（单部剧卡 + 统一设置）。
4. **我的页卡片化**（照 Arcane ProfileLayout）：10 张卡——Calendar（日历）/ Overview（总览）/ History（历史）/ Tracking（追更）/ Favorites（收藏）/ Watching（在看）/ Month（月度）/ Genres（题材）/ Trend1（本周最常看）/ Trend2（最近热门）；顺序可调、可隐藏，编辑入口放浮层，布局持久化（order / hidden，仿 encoded()）。
5. **视觉**：按墨影视现有风格（主色 #0B57D0 + 液态玻璃），Arcane 的精确色值/圆角/字号静态拿不到，不硬抄；图标按现有风格另画，不拿对方美术资产。
6. **技术约束**：FollowingActivity 在 main 源集，ViewBinding 双布局同名——新手机端视图要双布局同 ID 或走 findViewById，否则 TV 编译不过；Room 加字段要走数据库升级；删功能连测试文件与 workflow 测试引用一起删；本地无 SDK，GitHub 构建是唯一编译检查，推前逐行核对类型与 lambda（见 mooyingshi_upkeep skill）。

## 阶段

- **P1 追更页 UI 大改**（进行中，guard tracking-p1-agenda-ui，standard lane，base acbaf1c1）：
  - 完成句：追更页改成 Arcane 式日历呈现——顶部日期 Tab（今天/明天/本周/全部）+ 14 天日期条 + 按播出日期分组的时间轴列表，剧卡显示播出时间线与完结标识，旧功能全保留。
  - 范围：following/FollowingAgenda.java（新建）、ui/adapter/FollowingAdapter.java、ui/adapter/AgendaDateAdapter.java（新建）、ui/activity/FollowingActivity.java、main/res/layout/item_following.xml、main/res/layout/item_following_date_header.xml（新建）、main/res/layout/item_agenda_day.xml（新建）、mobile/res/layout/activity_following.xml、mobile/res/layout/item_following.xml、values/strings.xml、values-zh-rCN/strings.xml、docs/tracking-my-overhaul.md（已领用）。
  - 受保护脏文件：docs/tracking-my-overhaul.md（本任务内新建，已 --adopt-dirty）。
  - 本地时间 2026-11-24 23:05 Asia/Shanghai；P1 预估代码 60–90 分钟，构建约 10 分钟 + 修补轮次，目标今晚推上去触发构建。
  - 最便宜的决定性验证：GitHub 构建双 flavor 全绿 + 用户真机确认 Tab/日期条/时间轴/播出时间线可见（本地无 SDK 编不了）。
  - 关键事实：nextAirAt 是 TMDB air_date 当地 20:00（FollowingMetadataClient.airTime）；coreLibraryDesugaring 已开，java.time 可用；FollowingActivity 与 FollowingAdapter 在 main 源集双 flavor 共用，新视图要双布局同 ID 或 findViewById；LIST_ORDER = 有更新优先 → 未看数 → updatedAt；onDelete 会 cancelNext（P2 提醒取消接这里）。
  - 2026-11-24 23:05–23:10 代码写完：FollowingAgenda（日期桶/播出时间线文案）、AgendaDateAdapter（14 天日期条）、FollowingAdapter 加 Header 分组头 + airLine/ended、FollowingActivity 加 Tab/日期条/时间轴分组 + 详情弹窗播出时间线、双布局 item_following 加 airLine/ended、手机端布局加 Tab/日期条、TV 布局加同名 gone 占位（否则 R.id 在 leanback 编译不过）、中英文各 18 条新文案、3 个新颜色。XML 全解析通过；逐行核过类型/lambda/泛型（Adapter 改泛型为 RecyclerView.ViewHolder、setItems 传 ArrayList<Object> 显式类型）。待验证：GitHub 构建双 flavor 全绿 + 用户真机确认。
  - 验收：日历/时间轴/剧卡显示对、待定组不丢剧、旧功能全在。
- **P2 播出提醒**（2026-11-25 04:50 代码写完，提交待编）：不走 AlarmManager——Manifest 没有 SCHEDULE_EXACT_ALARM，Android 16 上精确闹钟要用户去设置里另开，改走现有 WorkManager 一次性延迟任务（FollowingScheduler.scheduleNext 同款机制），无新权限、重启后 WorkManager 自己恢复（定时可能差几分钟，追剧提醒可接受）。模型：全局提前分钟数放 FollowingSettings（15/30/60，默认 30；0=关闭），单部开关复用现有 notifyEnabled（提醒开 = 更新通知 + 播出提醒都开）；新 FollowingReminderScheduler（每部剧唯一任务名，延迟 = nextAirAt - 提前分钟 - now），在元数据检查刷新 nextAirAt 后、开关/提前分钟改动后重排，删剧时取消；新 FollowingReminderWorker 到点核对（仍在追、开关开、集数仍是下一集、播出时间未过、该集没提醒过）再推；通知走 FollowingNotifier 新开的提醒渠道（内容复用 FollowingAgenda.airLineText，如“今晚 20:00 · 第 2 季第 5 集”）；设置入口在主设置页特色功能分组“播出提醒”行（关闭/提前 15/30/60 分钟）。验收：设了提醒的剧到点收到通知、重启后还在、完结剧不推。
- **P3 我的页**：新建我的仪表盘（Activity/Fragment，手机端）+ 10 卡片 + 布局编辑器（排序/隐藏/持久化）+ 首页搜索条人物图标入口；卡片点进对应页（追更/历史/收藏/继续观看）。验收：10 卡全有数、排序隐藏重启后还在、入口进得去。
  - 2026-11-25 04:50 预查锚点：入口在 VodFragment 首页搜索条（fragment_vod.xml：searchDice 第 87 行、searchHistory 第 99 行，VodFragment.java:217-219 接法同款，显隐走 Setting 开关）；数据源现成：AppDatabase.get().getKeepDao().findAll()（收藏）、getHistoryDao().findAll()（历史）、FollowingStore.list()（追更）；新页形态参照手机端 DiceActivity/DiceFragment。
  - 2026-11-25 05:10 代码写完，提交待编：ProfileCard（10 卡枚举）+ ProfileLayout（顺序/隐藏存 Prefers）+ ProfileStats（一次后台取数：追更/收藏/历史展示口径/在看/本月/题材/Trend1 本周最常看/Trend2 最近 7 天）+ ProfileActivity/ProfileFragment（仿 DiceActivity，纵向卡片列，每卡无数据也显示说明）+ ProfileEditorDialog（上移/下移/隐藏，关闭即保存）+ 首页搜索条人物图标入口（骰子左侧）；卡片点进追更/历史/收藏页，海报点开走 HistoryResumeCoordinator / KeepActivity 同一路。TV 端不动。
- **P4 联调发版**：三阶段合起来走一遍，编测试版给用户测，版本记录补一笔。
  - 2026-11-25 05:14 已完成：P1/P2/P3 各自独立编测试版发给用户（v5.6.0-beta-202611242304 / …250434 / …250501），版本记录补到第 67 条。追更+我的页三段全部做完，待用户真机验收。
  - 2026-11-25 08:04 用户定“点追更就进我的页”：底栏追更（普通+玻璃都走 handleNavItem）改开 ProfileActivity；FollowingSettings.isEnabled 默认改 true（底栏追更默认显示、后台检查默认跑）；搜索条人物图标入口去掉。追更日历页仍在，从我的页追更卡片和通知点进去。只改本地、不推不编，等他开口。

## 未决小项（P1 动手前定）

- 我的入口图标：搜索条内人物图标（与骰子并排）——P3 动手时按首页搜索条实际布局定。
- 提醒默认提前分钟：30（15 / 30 / 60 三档）。
- Trend1/Trend2 口径：Trend1 = 本周最常看（按观看次数），Trend2 = 最近热门（按最近 7 天观看）。
