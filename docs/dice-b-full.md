# 喜好 B 全套版（dice-b-full）

## Recovery anchor
- 目标：墨影视新增 Rove 同款“喜好选片”（老虎机摇片），B 全套：老虎机动画 + 4 片池（豆瓣/想看/收藏/当前站源）+ 心情/类型/时长筛选 + 不重复/避雷/题材屏蔽 + 历史/预设/背景换色。
- 用户批准：2026-11-20 10:19 定做 B 全套版；10:21 发截图定入口在首页搜索条内右侧、历史图标左侧放骰子按钮；增强设置“特色功能”开关（默认开）控制其显隐。
- 分析报告：~/workspace/rove-like/ANALYSIS.md（290 行权威版）。Rove APK 仅作原理参考，**不搬对方美术资源与代码原文，全部自画自写**。
- 分支/HEAD：main @ 5a49b6ef（phase 1 已提交，领先 origin 1 笔）+ 本 phase 一次原子提交。
- 阶段：① 数据层（Want 表 + Dice prefs + 开关 + 字符串 + 骰子图标）已完成（5a49b6ef）② UI（SlotMachineView + DiceFragment/Activity + 搜索条入口 + 设置行）与 ③ 池与逻辑（豆瓣拉取 + 当前站源池 + 随机/过滤 + 历史/预设/背景）一次做完（本 phase，dice-ui-engine）④ 核对 → 推 origin → 直接编测试版 → 交链接。
- 当前状态：phase 2 已完成并提交，待核对后推 origin 编测试版。约定：dice_duration ""=不限、"long"=长片、"short"=短片；dice_bg 0=跟随主题（默认深色底），1–4 为色板下标；dice_mode 保留未用；摇片历史存 Vod 数组 JSON；豆瓣 Vod 无站源，点详情走站内搜索片名，不硬编站源字段。

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
