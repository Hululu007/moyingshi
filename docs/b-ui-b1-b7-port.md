# B 档 UI 改版 — BieCuiLe → 墨影视（B1–B7）

任务来源：用户 2026-11-10 21:50 定“B档全套”（B1–B7，B8 全量 Flutter 重写不做）。
参考：`~/workspace/ui-ref/analysis.md`（第 4 节）、参考 APK `~/workspace/ui-ref/ref-app.apk`（BieCuiLe com.langhuan.tv v1.1.5，Flutter UI，只能仿外观）。
基线：main `c3c507e9`，仓库干净，无预存脏文件。
约束：只在本地改、本地提交，**不推 GitHub**；不编测试版；每做完一项走一次 task_guard finish（一个 guard 会话 = 一次原子提交 + recovery tag）；保留墨影视已有改动（品牌/包名 com.github.mooyingshi.osc、液态玻璃、短剧、Archive.org 爬虫、本地源、默认搜索本站、底栏 6 项）；底栏上限 6 项；本机无 Android SDK，编译检查靠 GitHub 构建。

## 进度

| 项 | 内容 | 状态 | commit |
|---|---|---|---|
| B1 | 首页轮播横幅（ViewPager2，取当前站首页推荐，无数据隐藏） | 已实现，待 GitHub 构建验证 | ef5b9e0d |
| B2 | 首页“继续观看”横滑行（History + 进度条，点进续播） | 已实现，待 GitHub 构建验证 | — |
| B3 | 底栏改悬浮玻璃胶囊（GlassNavBar → 居中悬浮药丸，底栏仍 6 项） | 未开始 | — |
| B4 | 详情页丰富（演员阵容/相关推荐/类似作品/外部链接/换平台/源搜） | 未开始 | — |
| B5 | 首页自定义 JS 组件区（Bangumi/豆瓣/TMDB 小组件引擎） | 未开始 | — |
| B6 | 播放器画面调节 + 黑边虚化 + Anime4K | 未开始 | — |
| B7 | 播放器多内核 media_kit 切换 | 未开始（先评估值不值得做） | — |

## Recovery anchor

- 目标：B1–B7 逐项落地到墨影视原生 UI，B7 先评估再决定做不做；全部本地提交、不推、不编。
- 当前阶段：B2 首页“继续观看”横滑行（已实现，待提交）。
- 分支/HEAD：main / ef5b9e0d（B1 已提交）。
- 已完成：B1 首页轮播横幅（ef5b9e0d，recovery/b1-home-banner/20261110215418-ef5b9e0d1b1c）。
- 未验证：B1、B2（无本地 SDK，编译靠 GitHub 构建；每项只做静态核对）。
- 风险：task_guard 是单会话状态（`.codex/task-state/current`），不可并行跑多个 guard 会话——B 各项必须串行做、串行提交；B5/B6/B7 是功能级工程，工作量以周计，本会话未必能全做完。
- 下一步：静态核对 B2（XML、括号平衡、类型），走 task_guard finish 提交 B2，再做 B3。
