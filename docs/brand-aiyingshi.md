# 品牌升级：墨影视 → AI影视

- 升级日期：2026-11-27
- 应用名：墨影视 → **AI影视**（`app_name`，三语 strings.xml）
- 包名：`com.github.moyingshi.osc.tv` → **`com.github.aiyingshi.osc.tv`**
  （`namespace`、`applicationId`、全部源码目录与 import、AndroidManifest、
  EventBus index、ProGuard 规则、模块命名空间 `*.chaquo` / `*.nodejs` /
  `*.quickjs`、JNI 测试路径一并改）
- 启动图标：蓝底白播放键 + 神经网络环（adaptive icon 前景 + 蓝底背景分层，
  各密度 legacy mipmap 同步换）
- 不变：GitHub 仓库名（仍是 `xinyonghuu/moyingshi`）、签名 keystore（沿用现有，
  alias moyingshi）、版本号编排（仍是 `5.6.0-beta-<时间>`）、全部功能、
  TV 端、模块、液态玻璃、短剧、本地源

## 老版本不会自动更新

包名不同 = 不同的应用。已装墨影视版（`com.github.moyingshi.osc.tv`）的手机
不会自动更新到 AI影视版，需手动从 GitHub 下载新 APK 安装；新版会作为全新
应用与老版并存，历史、收藏、设置等数据不自动迁移。

## 上游同步：路径归一化改成新包名

以后每次同步上游 Silent1566/webhtv beta，cherry-pick 后的路径归一化改成：

- 上游 `com/fongmi/android/tv` → **`com/github/aiyingshi/osc/tv`**
  （原来是 `com/github/moyingshi/osc/tv`，自本次升级起作废）

同步时仍按老规矩：逐个 cherry-pick 非合并提交，跳过合并提交但用树等价核对
不漏内容；冲突优先保留 AI影视改动；核对时 `com.github.moyingshi` 在代码里
应为 0 处（仓库地址 `xinyonghuu/moyingshi` 不变，不是包名，别误改）；
同步基线不受本次升级影响（仍是上游 beta `4cd2532b`）。

## 保留项核对（本次升级后应全为真）

- `app/build.gradle` 的 `namespace` / `applicationId` 是新包名
- `app/src` 下 `com.github.moyingshi` 出现 0 次（`xinyonghuu/moyingshi` 仓库
  地址除外）；`package` 声明与所在目录一一对应，无错位
- `墨影视` 在 `app/src` 的 strings 与代码注释里为 0 处（历史文档除外）
- 底栏仍为 6 项；`select_player` 各语言 5 项；新 Activity 先对 Manifest
