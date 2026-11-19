# B8 返回仍坏 — #167 真机诊断

- 构建：#167（头 `7d2b520c`，prerelease `b8-c0-167-7d2b520`），含 §1 返回栈修复（`ba8ef568`）。
- 真机反馈：2026-11-19 08:37，滑动 / 玻璃 / 设置搜索三项都好了，返回还是坏。
- 录像：`~/workspace/user/media_library/video/a3/a33e841ffed7aa7b0e79af818dbcd92a4829e3dc33d7154f45b458ca71d3535e.mp4`，20.9s，720×1584；已逐帧核对（1fps 全片 + 关键段 4fps + 流程 1/4 的 30fps）。
- **结论（一句话）：返回按压根本到不了 Dart / host，§1 修的 host 历史在真机上一次都没跑到——断点在 Android 侧的返回分发，不在返回栈逻辑里。host 历史、`recordPush`/`recordSwitch`/`handleRootBack`、Dart 各页的 `PopScope` + `closeScreen` 委托，逐行核对后全部正确，无需改动。**

## 1. 录像逐帧证据

时间均为录像内秒数。`f_XX` 为 1fps 帧，`a/b/c/d_XX` 为 4fps 段，`hi30` 为 30fps 段。

| 段 | 时间 | 所见 |
|---|---|---|
| 启动 | 00:00 桌面 → 00:01 加载圈 → 00:03 首页 | 冷启动，首页停留 1 秒以上 |
| 流程 1 | 00:04 实验室（加载中）→ 00:04.75 实验室已加载 → 00:05.25 窗口缩小动画 → 00:05.5 桌面 | 实验室返回 → 桌面 |
| 流程 2 | 00:08 设置 → 00:09 桌面 | 设置返回 → 桌面 |
| 流程 3 | 00:12 直播（加载中）→ 00:13 首页 → 00:14 桌面 | 直播返回 → 首页（**正常**）；首页再返回 → 桌面 |
| 流程 4 | 00:18 ⋮ 菜单打开 → 00:19 桌面 | 菜单打开时返回 → 桌面 |
| 重进 | 00:10、00:15 每次重进都是加载圈 | 每次退出后重进都是冷启动 |

三点决定性帧证据（30fps，33ms 粒度）：

1. **流程 1 无首页闪现。** 5.00–5.60s 共 18 帧：5.00–5.13s 实验室白底（`whiteTR=0.98`），5.17–5.33s 亮度 223→128（窗口缩小动画），5.37s 起桌面。18 帧里 `green=0.00`，即**没有任何一帧是首页**。若 `handleRootBack` 曾 pop 回首页再退出，首页至少会出现一帧（断言 + Dart 重建 + 首帧 > 33ms）。没有。pop 路径没跑。
2. **流程 4 菜单从未收起。** 18.50–19.50s 共 30 帧：18.73s 菜单打开，18.87s 菜单仍完整打开（窗口开始变暗），18.93s 整个窗口（含菜单卡片）开始缩小，19.00s 桌面。**菜单路由没有先 pop**——若返回曾到达 Dart 的 `Navigator.maybePop`，最顶的菜单路由（可 pop、无 `PopScope`）必先收起，应用留在首页。它没收起，返回根本没进 Dart 的 Navigator。
3. **首页返回是 finish，不是后台。** 流程 3 里直播返回后首页稳定 13.5→13.75s（`c_07`/`c_08`），13.9–14.0s 窗口才开始缩小（`c_09`），是用户在首页上的第二次返回，不是直播返回后的自动退出。且两次重进（00:10、00:15）都出现加载圈：若只是 `moveToBackground`，重进应瞬间恢复上一帧；加载圈 = Activity 已销毁 + 引擎已销毁 = 冷启动。见 §4。

## 2. 断点：返回分发——按压到不了 Dart

链条（按执行顺序，断在第 3 步）：

1. **Manifest 打开了 predictive back。** `app/src/main/AndroidManifest.xml:36`：`<application android:enableOnBackInvokedCallback="true">`。API 33+ 上返回手势走平台 `OnBackInvokedDispatcher`，不再走 legacy `onBackPressed()`。
2. **C1 没有注册任何返回回调。** `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java` 全文件 grep：无 `OnBackPressedCallback`、无 `onBackInvoked()` 重写（仅 `:866` 注释提到原生 `HomeActivity.onBackInvoked`）。这是唯一一个没跟原生一致的地方——原生 `WebHomeShellActivity.java:269`、`AudioActivity.java:1373` 都重写了 `onBackInvoked()`。
3. **FlutterFragment 也没开自动处理。** `C1ScreenActivity.java:436`：`FlutterFragment.withCachedEngine(ENGINE_ID).build()`，没有 `.shouldAutomaticallyHandleOnBackPressed(true)`（默认关闭）。Flutter 官方文档（add-to-app / add-flutter-fragment）写明：API 33+ 上要自动处理系统返回与 predictive back 手势，须在构造 fragment 时开此项，“without manually forwarding `onBackPressed()`”；API 文档补充：开启后“do not invoke `FlutterFragment.onBackPressed()` manually”。
4. **C1 唯一的返回接线是 legacy 重写**，`C1ScreenActivity.java:1052-1063`：`onBackPressed()` → `flutterFragment.onBackPressed()`。predictive back 开启后，系统返回不再经过这个重写：手势 → `OnBackInvokedDispatcher` → `ComponentActivity` 的 dispatcher → 无 enabled 回调 → fallback → 平台默认 `finish()`。Dart 收不到 `popRoute`，host 的 `handleRootBack` 收不到 `closeScreen`，菜单路由收不到 `maybePop`。
5. **旁证（Android 16 行为变更报道）：** 目标 API 36 的应用在 Android 16 上“`onBackPressed` is not called and `KeyEvent.KEYCODE_BACK` is no longer dispatched”；本应用 `targetSdk=28`（`gradle/libs.versions.toml:8`），但 manifest 的 `enableOnBackInvokedCallback="true"` 是同一机制的手动 opt-in，在 API 33+ 设备（用户手机 SDK 36）上同样启用 dispatcher 路径。OsmAnd 的同类修复记录也写明：无默认回调注册时，`ViewRootImpl` 把返回直接路由给窗口顶层 `OnBackInvokedCallback`。

**为什么 §1 修复（`ba8ef568`）在真机上毫无效果：** 它的全部推理（H1 强制断言、D1 底栏 push 等价 switch、R1 首页返回恒后台）都假设“返回信号能到达 host”。`docs/b8-back-stack-fix.md` §“危害与风险处理”R2 自己写明了：**“embedding 回调 live path 仍无法从代码验证”**——这正是断点所在。H1 的“pop 目标恒不等于已断言路由，故 Dart no-op 吞不掉 pop”没错，但 pop 信号从未发出。

## 3. 分流程诊断

### 流程 1：首页 → 底栏 → 实验室 → 返回 → 桌面（应回首页）

- **前向链路正确（已逐行核对）。** Dart 底栏点按只调 host：`flutter_module/lib/b11_home_page.dart:460-463` `_onBottomBarTap` → `_b11Api.openBottomBarItem(item.token)`。`app/src/main/java/com/github/moyingshi/osc/tv/flutter/B11HostApiImpl.java:1306`（`local_source` + `Setting.isLabNav()`）→ `C1ScreenActivity.start(activity, ROUTE_LAB)` → `C1ScreenActivity.java:1034-1037` `onNewIntent`（无 switch 标记）→ `recordPush("/lab")`，历史 `[/home, /lab]`。`handleRootBack`（`:875-885`）在该历史下必 pop 回 `/home`。
- **断点：** 返回按压在 §2 的分发层被系统默认 `finish()` 吃掉，`B21LabPage` 的 `PopScope(canPop: false)`（`flutter_module/lib/b21_lab_page.dart:69-73`）与 `_handleBack`（`:54-66`，内层 Navigator 不能 pop 时调 `_c1Api.closeScreen()`）从未执行。30fps 无首页闪现（§1 证据 1）是直接证明。
- **修复：** 见 §5 F1（只需修分发层；本流程的 host/Dart 代码不动）。

### 流程 2：首页 → 底栏 → 设置 → 返回 → 桌面（应回首页）

- **前向链路正确。** `B11HostApiImpl.java:1314-1315`（`setting`）→ `openHomeAt(1)` → `FlutterNav.java:168-171` `openHomeAt` → `C1ScreenActivity.startSwitch(activity, ROUTE_SETTINGS)`（`FlutterNav.java:115-116`，position 1 = `/settings`）→ `onNewIntent`（`C1ScreenActivity.java:1034-1035`）→ `recordSwitch("/settings")`，历史 `[/settings]`。`handleRootBack` 的 D5 分支（`:886-889`，单条非 home 先回 `/home`）正是为这个用例加的，在该历史下必回首页。
- **B3 家族内返回也正确。** `flutter_module/lib/b3_settings_a_page.dart:86-103` `_handleBack`：内层能 pop 则 pop；member 非 home 则替换为 `/home`；仅 home member 落到 `_api.closeScreen()`。视频里是设置首页（member home），应走 `closeScreen` → `handleRootBack` → `/home`。
- **断点：** 与流程 1 相同——按压没到 Dart。4fps 段（7.5–10.0s）同样无首页闪现：8.50s 设置页，8.75s 设置窗口缩小动画，9.00s 桌面。
- **修复：** 见 §5 F1。

### 流程 3：直播返回 → 首页（正常）；首页返回 → 桌面（应为后台，非 finish）

- **直播返回正常的原因：** `B11HostApiImpl.java:1270-1271`（`live`）→ `LiveActivity.start(activity)`，直播是**原生 Activity**，盖在 C1 之上。它的返回走原生 `onBackInvoked`，`finish()` 后露出底下的 C1——全程不经过 Flutter/Dart/§2 的断点。这也反证了断点位置：原生返回路径完好，坏的只是 Flutter 宿主这一层。
- **首页返回 finish 而非后台：** `handleRootBack` 在 `/home` 时调 `Util.moveToBackground(this)`（`C1ScreenActivity.java:891`；`Util.java:57-62` 为 `moveTaskToBack(true)`），设计意图（fix doc R1）是后台。但录像显示两次重进都有加载圈（§1），即 Activity 已销毁。`onDestroy` 的 teardown guard（`C1ScreenActivity.java:946` 起，`if (isFinishing() && sLiveActivities.isEmpty())`）会清配置、停 Server、销毁引擎并 `sRouteHistory.clear()`（`:991-993`）——只有 `finish()` 才会触发这条路。结论：首页返回实际走了平台默认 `finish()`，R1 的“首页返回恒后台”在真机上从未生效。
- **修复：** 见 §5 F1。修好分发后，首页返回将首次真正到达 `handleRootBack` → `moveToBackground`；验收时以“重进瞬间恢复、无加载圈”为判据（§6）。

### 流程 4：首页 ⋮ 菜单打开 → 返回 → 桌面（应只收起菜单）

- **菜单是标准 Flutter 路由。** `flutter_module/lib/b11_home_page.dart:927` `PopupMenuButton<B11MenuItem>`（`onSelected: _onMenuItem`），`showMenu` 推送的是根 Navigator 上的 `_PopupMenuRoute`，可 pop、无 `PopScope`。
- **断点：** 30fps 证明菜单从未收起（§1 证据 2）：返回按压发生时，Dart 的 `maybePop` 本应先 pop 掉菜单路由、应用留在首页；实际是整个窗口（含菜单）直接缩小退出。**这条排除了一切 Dart 侧成因**（`PopScope`  veto、`_showRoute` no-op、Pigeon 异步等都不影响一个可 pop 的顶层路由）。断点只可能在 §2 的分发层。
- **修复：** 见 §5 F1。修好后，菜单打开时返回应先收菜单（Dart 路由 pop），再按一次才走首页返回逻辑。

## 4. 已排除的假说（逐项有据）

- **host 历史记错 / `recordPush` 漏调：排除。** 流程 1/2 的前向链路逐行核对（§3），历史在按压瞬间必为 `[/home, /lab]` / `[/settings]`；且流程 4 的菜单与 host 历史完全无关，却同样退出。
- **Dart `PopScope` veto 后 `SystemNavigator.pop()` 抢跑：排除为主因。** 该机制即使存在，也解释不了流程 4（菜单路由无 veto、可 pop）。且标准 `PopScope(canPop: false)` 模式在 Flutter 应用里是成立的（veto 即视为已处理，不触发平台 pop）。
- **`_maybeCloseForFallback`（hk/WebHome 壳）误关首页：排除。** `flutter_module/lib/b11_home_page.dart:213-214`（resume 即 `_load()`）+ `:216`（每次 `_load` 重置 `_fallbackClosing=false`）+ `:252-258`（`isHkHome || isWebHome` 即关屏）。若本站点命中，首页在启动加载完成后一帧内就会退出；录像里首页在启动后稳定停留 1 秒以上（00:03、00:07、00:11、00:13、00:16 均可见），且流程 3 直播返回后首页稳定 13.5→13.75s。同一站点、同一配置下，fallback 不可能只在“从实验室/设置返回”时触发。
- **`handleRootBack` 首分支 `!isTaskRoot()` → `finish()`：排除。** `C1ScreenActivity.java:876-879`。C1 是 launcher Activity（manifest `singleTop`，`:57`），且流程 1/2 里它一直在栈顶、是 task root；该分支只对叠加实例（非 root 的第二个 C1 实例）生效，本录像里不存在叠加实例。
- **`checkAction` / `handleAction` 干扰：排除。** `C1ScreenActivity.java:595-612`：无 `EXTRA_NAV_POSITION`、无 action 的普通 `start()` intent 不做任何事。
- **`onPostResume` 的 `assertRouteIfNeeded` 覆盖 pop：排除。** `C1ScreenActivity.java:1000-1007` 只在 resume 时断言，不改变 `mRoute`；返回按压不经过 resume。
- **系统杀进程 / 电量：排除。** 两次退出后 1–2 秒内重进即冷启动，间隔太短，且两次一致；更符合 `finish()` + `onDestroy` teardown（`C1ScreenActivity.java:949-993`）。

## 5. 修复提案（按顺序，最小改动）

**F1（唯一必需的修复，C1 一个文件，两处）：**

1. `C1ScreenActivity.java:436`，fragment 构造加自动返回处理：
   ```java
   flutterFragment = FlutterFragment.withCachedEngine(ENGINE_ID)
           .shouldAutomaticallyHandleOnBackPressed(true)
           .build();
   ```
   作用：fragment 向 Activity 的 `OnBackPressedDispatcher` 注册回调，返回按压经 dispatcher 转发 `popRoute` 给 Dart；Dart 的 `PopScope` veto → `closeScreen()` → `handleRootBack` pop；菜单路由直接 pop 收起。
2. 同文件删手动转发的 `onBackPressed()` 重写（`:1052-1063`）。Flutter 文档明确：开启自动处理后不要再手动调 `FlutterFragment.onBackPressed()`（双通道会重复分发）。其中 `flutterFragment == null` 时调 `handleRootBack()` 的 fallback 分支（`:1057-1062`）随重写一并删除——该窗口只存在于 `onCreate` 完成前，dispatcher 无回调时走系统默认，与删除前在该窗口的行为一致（都是系统默认）；如需保留，可改为在 `onCreate` 注册一个 Activity 级 `OnBackPressedCallback`（见 F2）。
3. **不动：** manifest 的 `enableOnBackInvokedCallback="true"`、host 历史（`sRouteHistory`/`recordPush`/`recordSwitch`/`handleRootBack`）、Dart 各页 `PopScope` 与 `closeScreen` 委托、B11/B21/B3 页面代码、Pigeon 契约。

**F2（等价替代，如不想动 fragment 构造）：** 在 `C1ScreenActivity.onCreate` 注册一个 Activity 级 `OnBackPressedCallback(true)`，其 `handleOnBackPressed()` 在 `flutterFragment != null` 时调 `flutterFragment.onBackPressed()`（即现在的重写体），否则调 `handleRootBack()`；并删除 `onBackPressed()` 重写。效果与 F1 相同，代码更多；仅当 F1 在 `withCachedEngine` 构造下有兼容问题时采用。

**F3（明确不做）：** manifest 改 `android:enableOnBackInvokedCallback="false"`。这能恢复 legacy 路径（让 `onBackPressed()` 重写重新生效），但会关掉全应用的 predictive back 动画，是官方标注的临时桥接，不是修复；且治标不治本（Android 16 + targetSdk 36 下该 flag 只是临时措施）。

**范围：** 修复任务 = `C1ScreenActivity.java` 1 个文件 + 1 篇任务文档（`docs/b8-back-dispatch-fix.md`）；CI 验收用现有 `b8-c0.yml`；无需 Dart/Java 其他改动。

## 6. 修复版验收清单（真机，逐项）

- V1：首页 → 实验室 → 返回 → **首页**（不再是桌面）；再返回 → 后台（见 V4）。
- V2：首页 → 设置 → 返回 → **首页**；设置内成员页 → 返回 → 设置首页 → 返回 → 首页。
- V3：首页 ⋮ 菜单打开 → 返回 → **菜单收起、留在首页**；再返回 → 后台。
- V4（关键回归判据）：首页返回后，**立即点图标重进，应瞬间恢复首页、无加载圈**（证明是 `moveToBackground` 而非 `finish`；修复前每次重进都有加载圈）。
- V5：直播返回 → 首页（不变）；播放中首页返回照常后台。
- V6：三键导航（如手机可切）下返回行为与手势一致（分发层统一后应一致；若不一致，说明另有分发分支，单独立案）。
- V7（日志确认，修好后必做一次）：`adb logcat` 抓一次返回按压，确认 `popRoute` / `closeScreen` / `handleRootBack` 有记录、且无 `finish()` 紧跟其后；修复前同一抓包应无任何上述记录（佐证“按压没进应用”）。

## 7. 待定

- **T1（AndroidX fallback 的精确行）：** “无 enabled 回调时 dispatcher fallback 调平台 `finish()`、绕过子类 `onBackPressed()` 重写”这一行，系依据 Android predictive-back 文档与 Flutter 文档的机制描述，未能在本机直接读到 AndroidX `ComponentActivity` / Flutter 3.50 `FlutterFragment.java` 源码（本机无 Android/Flutter SDK）。但三项应用内证据已闭合：manifest flag（`:36`）+ 零回调注册 + 录像（菜单不收起）。V7 的 logcat 是最终确认手段。
- **T2：** 三键导航下返回是否同样坏（理论上 flag 开启后同样走 dispatcher，应同样坏；待真机确认，见 V6）。
- **T3：** `shouldAutomaticallyHandleOnBackPressed(true)` 与 `withCachedEngine` 构造的组合在 Flutter 3.50 上有无已知限制——CI 编译 + V1–V5 真机验收即覆盖；若编译期报错，改走 F2。

## 8. 对既有文档的更正

- `docs/b8-back-stack-fix.md` 的 R1（“首页返回恒后台，无 in-app 退出路径”）与 R2（“embedding 回调 live path 仍无法从代码验证”）：R1 描述的设计行为在真机上从未生效（实际是 finish + 冷启动）；R2 标记的未验证点正是本次根因。§1 修复的 host 侧设计（H1 强制断言、D1/D5）本身无错，无需返工。
- `docs/b8-acceptance-159-diagnosis.md` §1 的“返回栈缺失”诊断仍然成立（host 确无历史），但它不是 #167 仍坏的完整解释：补上历史后，信号仍到不了 host。两者叠加才是全貌。
