# B8 C1 跨屏重定向（relaunch）机制：解决 G2 引擎复用竞态

> 状态：**本地实现完成，未推送**。本机制是短剧方案 B 的前置条件（见
> `docs/b8-short-drama-scoping.md` §2.2/§2.4：Wei 已定方案 B——一点就播，
> 剧场/历史 Tab 进 Flutter）。评估与阶段定义见
> `docs/b8-flutter-rewrite-assessment.md`；全量规划见
> `docs/b8-full-migration-plan.md`（§4.4 单引擎路由不变量、§7 R2）；
> G2 挂账见 `docs/b8-b7-settings-home.md`（G2/V10）、`docs/b8-b11-home.md`
> （V10）、`docs/b8-b19-plugin.md`（V3）、`docs/b8-b21-lab.md`（V11）。
> 分支：`b8-flutter-rewrite`；基线：HEAD `4fec674b`（短剧拆分评估）；
> `main` 未动。
>
> **诚实声明**：本机无 Java、Android SDK、Flutter/Dart，一切构建与运行
> 结论均未验证。行数与调用点均实测于本地源码（2026-11-17）；凡需 CI/真机
> 证明的项见 §6，不得视为已验证。

## 0. §1 执行声明（仓库 AGENTS.md）

- 完成句：给 `C1ScreenActivity` 加跨屏重定向机制——缓存引擎复用时新屏
  显示新路由而不是旧路由，start-then-finish 时旧 Activity 的 onDestroy
  不销毁新屏的引擎；对已有全部批次透明，不改任何已有批代码，不推送，
  不动 `main`。
- 允许路径：仅 `C1ScreenActivity.java`、`flutter_module/lib/main.dart`
  （只加法）、`docs/b8-c1-relaunch-mechanism.md`（task_guard standard 模式）。
- 预存脏文件：无（开始时工作树干净）。
- 时间/时区：2026-11-17 16:33 CST（Asia/Shanghai）开始。
- 工期估计：源码调研约 20 分钟 + 实现约 25 分钟 + 文档约 15 分钟 +
  验证提交约 10 分钟，约 17:25 CST 完成。
- 最便宜的决定性验证：`git diff --check` + 作用域 diff 逐项复核 +
  提交后工作树干净 + `main` 未动（构建与运行留给联合 CI + 真机验收）。

## 1. 问题分析：G2 的两种表现

### 1.1 现状机制（实测）

- 单缓存引擎：`C1ScreenActivity.ensureFlutterEngine()` 在引擎不存在时
  创建 `FlutterEngine`、注册全部 21 个批的 Pigeon impl、执行 Dart 入口、
  放入 `FlutterEngineCache`（ID `b8_c1_engine`）；引擎已存在时直接返回。
- 路由只在创建时定：`C1HostApiImpl(this, route)` 持有创建时 intent 的
  `EXTRA_ROUTE`；Dart 的 `main()` 只在启动时调一次
  `C1HostApi.getInitialRoute()`，之后路由再也不问 host。
- 引擎销毁只看 `isFinishing()`：`onDestroy()` 里 `isFinishing()` 为真
  就从缓存移除并 `engine.destroy()`（旋转时 `isFinishing()` 为假，引擎保留）。

### 1.2 表现一：路由串位（新屏显示旧路由）

时序：Activity A（路由 R1）创建引擎 → Dart 显示 R1 → 再起 Activity B
（路由 R2）→ `ensureFlutterEngine()` 命中缓存直接返回 → B 的 fragment
挂到同一个引擎上 → Dart 仍显示 R1。**B 显示的是 A 的屏。**

触发点分两类（全仓 grep `C1ScreenActivity.start`，共 40+ 调用点）：

1. **start-then-finish**（Flutter→Flutter）：B7 `openAbout`/`openSubtitle`、
   B5 页 6 深链、B4 `openLab`/`openLocalReader`/`openPluginManager`、
   B11 `openHistory`/`openMenuItem`/`openBottomBarItem`/`openTabCategory`、
   C2 `openViewingReport`、B13 `openWidgetMore`、B21 `openLabMiniappManager`
   的 B20-on 路径——先 start 新屏、再 finish 当前屏。新屏创建时引擎
   活着，路由必然串位；随后旧屏 onDestroy 再销毁引擎（表现二）。
2. **栈底活屏**（短剧方案 B 的活缺口）：B11 底栏 `case "drama"` 起原生
   `ShortDramaActivity` 时**不 finish Flutter 首页**（`B11HostApiImpl.java`
   约 623 行，反射起 Activity 即 break），C1 引擎带着 `/home` 活在栈底；
   在原生短剧页里点“剧场”/“历史”再起 `C1ScreenActivity`，必然显示
   `/home` 而不是剧场/历史页。短剧 scoping 文档 §2.2 已把这一项记为
   方案 B 的否决主因——本机制落地后该否决理由消除。

### 1.3 表现二：引擎被旧屏销毁（新屏黑屏/崩溃）

时序：start-then-finish 时，新 Activity 的 `onCreate` 先跑（挂上缓存
引擎），旧 Activity 的 `onDestroy` 后跑；旧屏 `isFinishing()` 为真，
执行 `engine.destroy()`——新屏的 fragment 挂在一个已销毁的引擎上。

### 1.4 不在机制内的相邻缺口（记录，不修）

- impl 持有的 Activity 引用：除 B20/B21（注册器/静态表 + onCreate 重绑）
  外，其余 impl 都在引擎创建时绑定第一个 Activity，之后不再重绑。
  start-then-finish 后 impl 拿的是已 finish 的 Activity 引用（取资源
  无碍，`startActivity` 依赖系统对已销毁 token 的容忍）。这是全部批次
  共有的既有条件，本机制不改变它；全面重绑是另一次跨批重构，见 §6
  第 5 项。短剧方案 B 反而更安全：创建引擎的 Flutter 首页一直活着
  （不 finish），impl 引用始终有效。
- B20/B21 的重绑只能在 `onCreate` 做（`registerForActivityResult` 在
  RESUMED 态调用会抛异常），不能搬到 `onPostResume`；因此栈底的 B20/B21
  屏回前台时，其文件选择器仍绑定在已销毁的上层 Activity 上，直到下一
  次新 C1 屏创建时重绑。见 §6 第 6 项。

## 2. 设计

### 2.1 机制比较

| 方案 | 做法 | 结论 |
|---|---|---|
| A. 销毁重建 | 路由不同时销毁缓存引擎、按新路由重建 | **否决**。每次跨屏导航都丢 Dart 状态（滚动位置、已加载数据、族内深度——比规划 §7 R2 已接受的“进程死亡丢深度”缺口大得多）；引擎重建慢（isolate 重启）；栈底 Activity 的 fragment 仍挂着已销毁的引擎，返回时黑屏，问题只转移不消除。 |
| B. 路由断言（本批） | 引擎活着；前台 Activity 把自己的路由推给 Dart，Dart 重置根 Navigator；onDestroy 只在最后一个 C1 屏 finish 时销毁引擎 | **采用**。引擎与 Dart 状态（除被替换的路由栈外）全部保留；对已有批次零改动；栈底回前台时再断言一次，天然闭环。 |
| C. 先 finish 再 start | 改全部调用点为 finish-then-start | **否决**。违反批次冻结（要改 B5/B7/B11/B13/B21 等全部已有批）；回退栈语义变化；Pigeon 回调里 finish 后再 start 依赖已 finish 的 context，不可靠。 |

### 2.2 机制：两条规则

**规则 1——路由断言（route assertion）。** 前台的 `C1ScreenActivity`
负责让 Dart 显示自己的路由：

- host 侧：一条原始 `MethodChannel`（名
  `com.github.moyingshi.osc.tv/c1_relaunch`，方法 `showRoute`，参数为
  路由字符串）。不用 Pigeon：C1 合约已冻结，且这是 host→Dart 单向推送，
  不是请求/响应；不用 EventChannel：每次断言都是一次性消息，规划
  §4.6 的“无流式通道”纪律不受影响。
- 发送点（两处，缺一不可）：
  1. `onCreate()`：仅当引擎已存在（复用）时发一次早期提示，争取在
     fragment 首帧前让 Dart 切好路由。**故意不记状态**——`onPostResume`
     会再做一次已记录的断言，万一早期消息在 Dart 启动窗口内丢失，
     第二次发送补上（Dart 侧去重，重复无害）。
  2. `onPostResume()`：已记录的断言。条件：断言过的路由 ≠ 本屏路由，
     或断言属主不是本实例（旋转后新实例）时才发送；本实例已是属主
     且路由一致时跳过（原生弹窗/子流程返回时的普通 resume 不打扰
     当前屏）。
- Dart 侧（`main.dart`，只加法）：`main()` 最先注册 channel handler；
  `MaterialApp` 加 `navigatorKey`；handler 收到 `showRoute` 时，若与
  `_currentTopRoute`（初始路由或上次接受的断言）相同则忽略——**旋转
  与同路由 resume 因此永不重置族内 Navigator 深度**；不同则
  `pushNamedAndRemoveUntil(route, (_) => false)` 重置根路由栈（含字面
  路由表与 `onGenerateRoute` 的全部参数路由），成功后更新
  `_currentTopRoute`。未知路由名或 navigator 未建成时保持当前屏，
  host 下次 resume 时再断言。
- 新鲜引擎路径零变化：引擎创建时 Dart 照旧走 `getInitialRoute()`；
  host 在创建时把创建路由记为已断言、属主记为创建实例，`onPostResume`
  直接跳过。

**规则 2——引擎销毁守卫（live-set guard）。** 静态弱引用集合
`sLiveActivities` 记录活着的 `C1ScreenActivity` 实例（`onCreate` 加、
`onDestroy` 删）；`onDestroy()` 仅当 `isFinishing()` **且**集合为空时
才移除缓存并销毁引擎（含 B20/B21 的反注册）。start-then-finish 时新屏
已在集合里，旧屏的 onDestroy 不再杀引擎；旋转时 `isFinishing()` 为假，
行为与今天一致；栈叠两屏时先 finish 的一屏不杀引擎，最后 finish 的
一屏才杀。

### 2.3 时序说明

```
start-then-finish（B7 openAbout：/settings → /about）
  A(/settings) Pigeon openAbout → start(B) → B.onCreate：引擎复用，
    早期 showRoute(/about) → Dart 重置为 /about
  → A.finish() → B.onPostResume：已断言属主是 B？早期发送未记状态，
    故再发一次（Dart 忽略重复）→ A.onDestroy：集合={B}，不销毁引擎
  → 用户看到 /about；按返回 → B finish → 集合空 → 引擎销毁

栈底活屏（短剧方案 B：/home → 原生短剧页 → /drama-theater）
  C1(/home) 活着 → 原生 ShortDramaActivity（不 finish 首页）
  → 点“剧场”起 C1(/drama-theater)：引擎复用，断言 → Dart 显示剧场页
  → 按返回 → C1 finish（集合={首页}，引擎保留）→ 回原生短剧页
  → 按返回 → 短剧页 finish → 首页 C1 onPostResume：断言过的路由是
    /drama-theater ≠ /home → 再断言 /home → Dart 回到首页

旋转（/settings?member=home，族内已切到 player）
  新实例 onCreate：引擎复用，早期 showRoute(同串) → Dart 忽略（与
    _currentTopRoute 相同），族内 player 页保留
  → onPostResume：属主 token 不同 → 再发一次 → Dart 再次忽略
  → 旧实例 onDestroy：isFinishing() 为假，引擎保留

同路由新屏（两次 /settings?member=home）
  断言字符串相同 → Dart 忽略；行为与今天一致（今天也是同屏）

进程死亡恢复
  FragmentManager 恢复 fragment；引擎重建（缓存已空），Dart 读
  getInitialRoute()；host 记创建路由为已断言；族内深度不恢复
  （规划 §4.4 已接受缺口，与今天一致）
```

### 2.4 覆盖的 G2 站点清单

本机制在宿主层统一生效，以下全部站点**不改代码**即被覆盖（括号内为
分流形态；全部实测于 `C1ScreenActivity.start` 调用点）：

| 站点 | 来源 | 形态 |
|---|---|---|
| `openAbout` / `openSubtitle` | B7 设置主屏 | start-then-finish |
| 页 6 subtitle 深链 | B5 设置内搜索 | start-then-finish |
| `openLab` / `openLocalReader` / `openPluginManager` | B4 增强设置 | start-then-finish |
| `openHistory` / `openMenuItem`(keep/enhance) / `openBottomBarItem`(following/lab/setting) / `openTabCategory` | B11 首页壳 | start-then-finish |
| `openViewingReport` | C2 历史页 | start-then-finish |
| `openWidgetMore` | B13 首页块 B | start-then-finish |
| `openLabMiniappManager`（B20-on 路径） | B21 实验室 | start-then-finish |
| 剧场/历史 Tab | B22 短剧（原生短剧页内，方案 B） | start-only，栈底活屏 |
| 全部原生入口（HomeActivity / SettingFragment / VodFragment / SearchActivity / FolderActivity / WidgetBrowseActivity / LabActivity / AppBrandingActivity / TmdbPersonActivity / HistoryActivity） | 各原生屏 | start-only（栈叠时受益于销毁守卫与回前台断言） |

### 2.5 C0 引擎：不动

`C0ShellActivity` 用独立引擎 `b8_c0_engine`，只显示 `/` 一条路由，
无多路由、无 start-then-finish、无栈底活屏，G2 无从发生。`main.dart`
的 handler 与 navigatorKey 在 C0 引擎的 isolate 里同样存在，但
`C0ShellActivity` 从不发送，零影响。

## 3. 文件清单

| 文件 | 动作 |
|---|---|
| `app/src/main/java/.../flutter/C1ScreenActivity.java` | 改：重定向机制（§2.2 两条规则 + 类注释；其余逻辑一行不改） |
| `flutter_module/lib/main.dart` | 改：只加法——`services.dart` 导入、relaunch channel/handler/`_currentTopRoute`/`_showRoute`、MaterialApp 的 `navigatorKey`；已有路由表与 `onGenerateRoute` 一行不改 |
| `docs/b8-c1-relaunch-mechanism.md` | 新：本文档 |

无新 Pigeon 合约、无 workflow 改动、无 `.gitignore` 改动、无开关
（基础设施修复，默认生效，只在 C1 引擎复用冲突时起作用）、无新
`R.string` 引用（diff 复核确认：新增内容无 `R.` 引用，leanback 安全）、
无新 Dart 依赖。

## 4. 逐项对照清单（Wei 真机验收用）

实验屏打开若干批开关后，逐项与机制落地前（或开关关闭后）的原生行为比：

**不同路由（重定向）**
- [ ] Flutter 设置主屏 → 关于页：显示关于页内容，不是设置主屏；按返回退出到原生设置页（B7 G2 验收）。
- [ ] Flutter 设置主屏 → 字幕设置页：显示字幕设置页；按返回退出到原生设置页。
- [ ] Flutter 首页底栏 → 追更/设置/实验室/历史/收藏：每次都显示目标屏，不串屏；连续切换 5 次以上路由始终正确（B11 §6 第 6 项）。
- [ ] 历史页 → 观看报表：显示报表页（C2 start-then-finish）；首页小组件“更多”→ 小组件浏览页显示对应 widget（B13）。

**相同路由（无操作）**
- [ ] 同一路由连续打开两次（如两次进设置主屏）：显示正常，无闪烁、无重置。

**旋转（引擎复用、路由保留）**
- [ ] Flutter 屏旋转：不崩溃，当前屏不变；族内已切到某成员页（如播放器设置）时旋转，仍在该成员页（深度不丢）。
- [ ] 旋转后继续点跨屏行（如关于页）：路由正确。

**start-then-finish（引擎不被旧屏销毁）**
- [ ] 连续跨屏 3 次以上：新屏始终正常显示，不黑屏、不闪退（旧屏 onDestroy 不杀引擎）。
- [ ] 跨屏后按返回：回到原生上一屏（start-then-finish 的回退语义不变）。

**栈底活屏（短剧方案 B 验收，B22 落地后）**
- [ ] Flutter 首页 → 短剧（一点就播）→ 点“剧场”Tab：显示 Flutter 剧场页，不是首页。
- [ ] 剧场页按返回 → 回原生短剧播放流；再按返回 → 回 Flutter 首页（首页内容正确，不是剧场页）。

**进程死亡**
- [ ] 开发者选项“不保留活动”开时进 Flutter 屏再切回：屏能恢复显示（族内深度回到路由初始页，与规划已接受缺口一致）。

**开关与回滚**
- [ ] 全部开关关闭：全部原生，行为零变化；电视端不受影响（C1ScreenActivity 只在 mobile 清单）。

## 5. 回滚

- 机制级：revert 本批提交（1 个宿主文件 + `main.dart` 只加法部分 +
  本文档），即回到今天的 G2 挂账状态；已有批次代码零改动，revert 后
  树仍自洽。
- 开关级：无新开关；全部批开关关闭 = 全部原生，机制代码不执行
  （`C1ScreenActivity` 只在某批开关开时才启动）。
- 无数据层变更，无需数据回滚。

## 6. 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **Gradle 编译**：`MethodChannel`/`StandardMethodCodec` 导入与调用
   形态、`WeakHashMap` 集合、4 个 flavor（mobile/leanback × 2 ABI）——
   CI 联编证明；本批无新 `R.string` 引用，leanback 风险低。
2. **`flutter analyze`**：`main.dart` 新增的 `services.dart` 导入被引用
   （`MethodChannel`/`MethodCall`）、无未使用 import、无 warning
   （C1 run #8 教训：一个 warning 即失败）。
3. **首帧闪烁**：复用引擎时，新屏首帧是否闪现旧路由一帧。早期提示
   （onCreate 发送）争取在 surface 就绪前让 Dart 切好路由；若真机
   可见闪烁，接受为已知缺口（不阻塞，见 §4 备注），或后续在 Dart 侧
   加首帧门控（另批，不在本批）。
4. **Dart 启动窗口内的消息丢失**：引擎刚创建、Dart `main()` 尚未注册
   handler 的窗口内，若有第二个 C1 屏启动，早期提示会丢失；onPostResume
   的已记录断言是第二道发送（窗口已过）。残余窗口只剩“Dart 在首个
   onPostResume 前仍未就绪”，实测上要求两次 C1 启动间隔 < isolate 启动
   时间且无 Dart 参与——用户操作与 Dart 驱动的启动都到不了这个窗口。
   真机按 §4 逐项验收即覆盖。
5. **impl 的 Activity 引用**（§1.4）：除 B20/B21 外 impl 绑定首个 Activity
   的既有条件；本机制不改变它。若真机发现某批跨屏后 host 方法行为异常
   （如打不开原生子屏），优先按 impl 归因，不要先怀疑本机制；全面重绑
   另立跨批任务。
6. **栈底 B20/B21 屏的文件选择器**（§1.4）：重绑只能在 onCreate，栈底
   屏回前台时选择器仍绑定已销毁的上层 Activity。触发路径极窄（栈底是
   B20/B21 屏 + 中间叠过另一 C1 屏 + 回来直接用文件选择器）；真机若
   复现，另立任务（可能方案：B20/B21 impl 在动作时校验 activity 存活
   并重绑 launcher 持有者）。
7. **对话框与路由重置的竞合**：路由重置会清掉根 Navigator 上的全部路由
   （含打开中的 Dart 对话框）。断言只发生在新屏创建/回前台时，正常流程
   里对话框已随旧屏离开而关闭；若真机发现某批对话框内动作触发跨屏后
   表现异常，按该批归因。

## Recovery anchor

- **目标**：C1 跨屏重定向机制（见 §0 完成句）；短剧方案 B 的前置条件；
  G2 挂账见 B7/B11/B19/B21 文档。
- **状态**：本地实现完成，未推送。基线 `4fec674b`；分支
  `b8-flutter-rewrite`；工作树待提交（见 `git status`）。
- **已完成**：只读发现（C1ScreenActivity 全文、main.dart 路由表、C0ShellActivity
  只读核查、全部 40+ `C1ScreenActivity.start` 调用点、B7/B11/B19/B21 的 G2
  挂账）→ 护栏 `b8-c1-relaunch-mechanism` → host 侧两条规则（路由断言 +
  销毁守卫）+ Dart 侧只加法配合（channel handler + navigatorKey + 去重）
  + 本文档。验证：`git diff --check`（finish 时复核）、作用域 diff 复核、
  main 仍为 `f39a6e0`。
- **未验证**：全部构建与运行（本机无 SDK）；§6 的 7 项留给 CI 联编 +
  真机验收。
- **下一步（恰好一个）**：B22 短剧批按方案 B 实施（原生短剧页留作入口，
  剧场/历史 Tab 分流进 Flutter；新合约 `b22_drama_messages.dart`，开关
  `flutter_b22_enabled` 默认关，不推送）——起新 task_guard standard 会话，
  实施前重读本文档 §2.4 的栈底活屏时序与 `docs/b8-short-drama-scoping.md`
  §2.2。
