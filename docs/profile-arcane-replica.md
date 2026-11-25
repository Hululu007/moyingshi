# 我的页按 Arcane 截图 1:1 复刻

## Recovery anchor

- 目标：我的页（ProfileFragment）按用户 2026-11-25 发的 Arcane 截图 1:1 复刻：页头“我的”+日历图标、7 天日期条、历史观看格子、追剧行、今天三页横滑、收藏/在看双 tile、本月曲线+刷剧 TOP3、类型分布、管理模块按钮。底栏 6 项不动，数据口径用咱们自己的历史/追更/收藏。
- 验收：GitHub 构建全绿，编出测试版发用户；用户真机截图确认版式与 Arcane 一致（或按截图再改一轮）。
- 计划状态：实现已完成（ProfileStats 数据、3 个自定义画图 View、ProfileFragment 重写、字符串、页头、日历图标），待推上去编测试版验证。
- 当前文件/符号：
  - `app/src/main/java/com/github/moyingshi/osc/tv/profile/ProfileStats.java`（加 today/week/month 小时与分布、月度每日剧集/电影小时、上月小时、月 TOP3、近 12 周每日小时）
  - `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/ProfileFragment.java`（重写 render/fillCard）
  - `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/custom/ProfileContributionView.java`（新建，历史格）
  - `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/custom/ProfileMonthChartView.java`（新建，本月双线图）
  - `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/custom/ProfileDotsChartView.java`（新建，今天/本周/本月点图）
  - `app/src/mobile/res/layout/fragment_profile.xml`（页头改“我的”+日历图标）
  - `app/src/mobile/res/drawable/ic_profile_calendar.xml`（新建，自画日历图标）
  - `app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh-rCN/strings.xml`（新文案各一处）
- 已完成：Arcane 分析文档已读；用户录屏已抽帧核对（管理面板分组与 10 模块清单）；guard `profile-arcane-replica`（standard）已开，基线 fcf0bc34，工作区干净。
- 未验证：全部实现与构建（待做）。
- 未决风险：见“待用户确认的假设”；三张图（历史格/点图/曲线）与横滑页数只能等用户真机截图再对。
- 下一步：推上 main，触发构建，核对构建结果与 4 个 APK 后发用户。

## 参考证据（设计研究已完成）

- 用户截图 `~/workspace/user/files/1000306115_8_opqy.jpg`（2026-11-25 10:55，Arcane 我的页全貌）与 `1000306115_5_l4y5.jpg`、`1000306116_4_v7zz.jpg`（08:46）：版式权威来源。
- 用户录屏 `~/workspace/user/media_library/video/6c/6c8a04584054320c80b1ac4c98b962a2886487f65163f270b1c0fc2caf29c3b5.mp4` 已抽帧（`/tmp/arcane-frames/`）：管理面板 10 模块与分组（日历/历史观看/追剧｜切换为入口卡片：观看概览/收藏｜切换为海报栏：本月观看/在看｜切换为海报栏：分类走势 1/分类走势 2/类型分布）已核对，与静态分析一致。
- 静态分析 `~/workspace/drive-incoming/analysis-my-page.md`：Arcane 是 Compose，无法直接搬代码，只能仿结构/文案/交互；`ProfileCard` 10 模块、`ProfileLayout` order/hidden/tiles 与咱们 P3 已抄的结构一致。
- 方案比较：①不动（保持 P3 的 10 张纵向卡片）——用户已否决，要 1:1；②按截图复刻页面本身，底栏与数据不动——采纳（用户 2026-11-25 11:00 亲口定“就按这个复刻”）。不搬 Arcane 的底栏（首页/我的/设置+搜索）与美术资产（图标自画、颜色走咱们主色）。

## 模块对照（截图 → 咱们的 ProfileCard）

| 截图位置 | 咱们的模块 | 做法 |
|---|---|---|
| 页头“我的”+ 右上日历图标 | —（页头） | 年份头改成“我的”+ 自画日历图标，点图标进追更日历（FollowingActivity） |
| 7 天日期条（22 周日–28 周六，今天高亮）+ 下方“暂无更新” | CALENDAR | 本周（周日–周六）条；点某天切状态行（当天播出，无则“暂无更新”）；点行进追更日历 |
| 历史观看卡（格子）+ 打开播放记录 › | HISTORY | 7 行（周一–周日）× 12 周贡献格 + 打开播放记录进 HistoryActivity |
| 追剧 N › + … + 暂无追剧 | TRACKING | 无卡片的普通行；点进追更日历 |
| 今天卡（0.00 小时 / 0 部电影 / 0 集剧 / 小时点图 / 三圆点） | OVERVIEW | ViewPager2 三页：今天 / 本周 / 本月，每页小时数+电影/剧集数+点图；圆点指示器 |
| 收藏 tile（0 收藏 …） | FAVORITES + WATCHING | 双 tile 行：收藏（数+…进 KeepActivity）、在看（数+…进 HistoryActivity） |
| 本月观看卡（11 月 / 小时 / ↑ 小时 / 剧集·电影图例 / 曲线 / 刷剧 TOP3） | MONTH（+ TREND1/2 并入 TOP3） | 双线图（剧集橙/电影蓝）+ TOP3；TREND1/2 都隐藏时 TOP3 段隐藏 |
| 类型分布卡（暂无类型数据） | GENRES | “N 部电影 · M 部剧”+ 每题材一行（名+占比条+%）；无数据“暂无类型数据” |
| 管理模块按钮 | —（已做） | 不动 |

## 待用户确认的假设（先按此实现，真机截图再改）

1. 今天卡三圆点 = 三页横滑，页为今天 / 本周 / 本月（截图只露了今天页；录屏没拍到横滑）。
2. 收藏 tile 旁边的空位放“在看”tile（截图里那部 Arcane 是空号，只露了收藏；咱们有真实在看数据，两个 tile 一行更完整）。
3. 刷剧 TOP3 = 本月播放次数前 3（即咱们 TREND1/2 的月度版），放在本月卡内部，不再单独成卡。
4. 日期条 = 本周周日–周六（截图 22 周日–28 周六，今天 25 周三高亮）。
5. 小时数口径 = 历史记录已看进度之和（position，未看完按进度、看完按全长；毫秒转小时保留两位）。

## 数据口径（ProfileStats 新增）

- todayHours / todayMovies / todaySeries / todayHourly[24]：今天（createTime 当天）历史的已看小时、电影部数、剧集数、按开始小时分的小时数。
- weekHours / weekMovies / weekSeries / weekDaily[7]：本周（周一 0 点起）同上，weekDaily 按周一–周日。
- monthHours / monthDailySeries[days] / monthDailyMovies[days] / lastMonthHours：本月每日剧集/电影小时、上月总小时（环比 ↑/↓ 用）。
- monthTop3：本月播放次数前 3（NameCount 列表，复用 topWatchedList）。
- dailyHours：近 12 周每日已看小时（Map<LocalDate, Double> 或 float[84]，供贡献格）。
- 类型判断沿用现有：typeName 含“电影”→电影，含“电视”/“剧”→剧集，其余不进双线图。
