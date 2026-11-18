# WebHome 最小原生回退壳（b8-webhome-shell）

删原生计划 `docs/b8-delete-native-plan.md` §6 R1 / §7 D6，中国主人 2026-11-18 08:22
选 (b)：建最小原生回退壳，先于 `VodFragment` 删除（阶段 4）建成并验收。

## 背景

Flutter module 无 WebView 插件（`docs/b8-full-migration-plan.md` §4.3：不新增 Dart
依赖），WebHome 主题的首页无法在 Flutter 首页渲染。原生形态下，`HomeWebController`
（2288 行）挂在 `VodFragment.mBinding.homeWeb`，chrome 由绑死 `HomeActivity` 布局的
`WebHomeChromeController` 承担，`Listener.openSetting` 唯一实现是
`VodFragment.openSetting`（切 `HomeActivity` 设置 tab）。

阶段 3 之后 `C1ScreenActivity` 已是 launcher，`HomeActivity` 不可达，旧 P6 回退（Flutter
首页 `closeScreen` → 露出底下原生首页）已失效：WebHome 站点至此无可用首页。回退壳补上
这条路，且不碰 `VodFragment` / `HomeActivity`（阶段 4 才删）。

## 设计

单 Activity `WebHomeShellActivity`（main 源集，与 `WebThemeDetailActivity` 同款形态：
`BaseActivity` + `HomeWebController.Listener`），只承载 `HomeWebController` + 最小
chrome，不重写 2263 行控制器。

| 部分 | 实现 |
|---|---|
| Activity | `app/src/main/java/.../ui/activity/WebHomeShellActivity.java`（新，~370 行） |
| 布局 | `app/src/main/res/layout/activity_web_home_shell.xml`（新，`WebView` + loading 浮层，镜像 `activity_web_theme_detail.xml`） |
| 字符串 | `web_home_shell_loading`（`values/strings.xml` 新增 1 条；zh 无译文时回退英文，与 `web_theme_detail_loading` 现状一致） |
| 声明 | 仅 mobile manifest（`exported=false`，`configChanges` 含 orientation 系，`fullUser`，`parentActivityName=.flutter.C1ScreenActivity`）。leanback 编译该类但永不启动它 |
| 入口 | B11 契约新增 `@async bool openWebHomeShell()`（additive）；`B11HostApiImpl` 实现；Dart `_maybeCloseForFallback` 分流 |

### 入口判定（与现有 P6 回退一致）

三处用同一个谓词，零新判定逻辑：

1. 快照：`B11HostApiImpl.buildScreen` 已有 `.setIsWebHome(WebHomeTarget.canLoad(home))`（未改）。
2. Dart：`_maybeCloseForFallback` 在 `isWebHome` 时调 `openWebHomeShell()`；`isHkHome`
   时仍 `closeScreen()`；两者皆真时 hk 优先（与原生 `VodFragment.loadHome` 先试 hk 内嵌
   的顺序一致）。
3. Host：`openWebHomeShell` 复核 `Util.isMobile()` + `WebHomeTarget.canLoad(home)` 后调
   `WebHomeShellActivity.start(activity, home)`；壳 `onCreate` 再以
   `WebHomeTarget.resolve(site)` 复核，`load(site)` 失败则直接 `finish`。

`start()` 额外两道门：壳已打开时拒绝叠加；见下“不再重开守卫”。

### Listener 映射（相对原生 VodFragment）

| 回调 | 壳行为 | 原生对照 |
|---|---|---|
| `onWebLoading` / `onWebReady` | loading 浮层显 / 隐 + `requestFocus("shell-ready")` | `hideProgress()`；detail 页同款 requestFocus |
| `onWebError` | `finish()`（底下 Flutter 首页即回退） | `showNativeContent()` + `homeContent()` |
| `setToolbar` / `setChrome` / `restoreChrome` / `applyDefaultChrome` | 壳内最小 chrome（下节）；fullscreen 关时恒 NORMAL | 经 `HomeActivity` 转 `WebHomeChromeController` |
| `getViewport` | 壳的 insets 实时 viewport | `HomeActivity.getWebHomeViewport()` |
| `openVod` | `finish()`（底下 Flutter 首页即点播首页） | `HomeActivity.openVod()`（切 vod tab + `openVodHome()`） |
| `openSite` | 反射开 `SiteDialog`（见偏差 W2）；换站后壳 `finish` | `SiteDialog.create().change().show(this)` |
| `openSetting` | `C1ScreenActivity.start(ROUTE_SETTINGS)` + `finish` | `HomeActivity.change(1)` |

### 壳内最小 chrome

`WebHomeChromeController` 无法复用（包内可见 + 绑死 `HomeActivity` 与
`ActivityHomeBinding`），壳内重实现其必需部分（约 80 行），语义逐项镜像：

- 模式/选项状态机：`NORMAL / EDGE / IMMERSIVE` + `previousMode`，`WebHomeChromeOptions`
  解析，`savedInstanceState` 存取，`WebHomeChromeStartup.restore/resolve/remember`
  （含 `startup` 标记的 setChrome 记忆）。
- 系统栏：scrim 颜色 + 浅/深图标（`ThemeController.isNight` 回退）+ immersive 时
  `Util.hideSystemUI` / 否则 `showSystemUI`；cutout `SHORT_EDGES` 当且仅当
  `hidesNativeChrome(mode)`。
- Viewport：根布局 `setFitsSystemWindows(false)` + insets 监听 →
  `WebHomeViewport.from(insets, mode, safeBottomMax)`（`safeBottomMax` 累积，与原生一致）
  → `controller.setViewport()`；`onConfigurationChanged` 重置并重分发。
- 返回顺序与 `HomeActivity.onBackInvoked` 一致：chrome 恢复（immersive 且
  `restoreAffordance != none`）→ `controller.handleBack()`（WebView 历史，首页边界返回
  false）→ `finish()`。`onWindowFocusChanged` 时 immersive 重藏系统栏；`onDestroy` 时
  immersive 恢复系统栏。

壳无底栏/工具栏，故无 `navigation` / `container` 布局逻辑；页面靠 viewport CSS 变量
留安全区。

### 不再重开守卫（新行为，无原生等价物）

壳叠在 Flutter 首页之上：用户退出壳回到首页，首页 resume 重载快照仍报 `isWebHome`，
无守卫会立刻重开壳 → 死循环。守卫：

- 壳 `onDestroy` 且 `isFinishing()` 时，把本次主题 identity
  （`WebHomeTarget.identity(siteKey)`：site 模式为 URL，global 模式含 site key）记入静态
  `sDismissedIdentity`；旋转不触发（`configChanges` 不重建）。
- `start()` 在 identity 等于守卫值时拒绝启动，Dart 收到 `false` 后留在网格页。
- 为另一 identity 启动时清守卫：换站（或同站换主题 URL）后壳可再次打开。
- 进程死亡丢静态守卫：冷启动重新打开壳，与原生一致。

### 生命周期

`onResume/onPause/onDestroy` 转发 `controller`（与 `WebThemeDetailActivity` 一致）；
`load(site)` 在 `onCreate` 内 `try/catch (RuntimeException)`，失败 `finish()`。
`Server.get().start()` 由 `loadResolved` 内部调用，与原生一致。

## 偏差

- **W1**：`WebHomeChromeController` 未复用（见上），壳内重实现必需部分。
- **W2**：`openSite` 走反射——`SiteDialog` 是 flavor-split 类，mobile/leanback API
  不同（mobile `show(Fragment)`，leanback `show(FragmentActivity)`），main 源集不能直
  接引用。反射调 `create()/change()` 后用继承自 `DialogFragment` 的
  `show(FragmentManager, String)` 打开，并以 `FragmentLifecycleCallbacks`（B11 既有
  模式）在对话框销毁后复核 home 站点是否变化、变化则 `finish()`。`Util.isMobile()` 门
  + `catch (Throwable)` 兜底。
- **W3**：`openSetting` 改走 C1 `/settings`（start-then-finish），不再切原生 tab。
- **W4**：`openVod` 改为 `finish()`，底下 Flutter 首页即点播首页。
- **W5**：不再重开守卫为新增语义（见上）；原生无此问题（WebHome 即首页本身）。
- **W6**：壳只在 mobile manifest 声明；leanback 编译通过但无入口。
- **W7**：退出壳回到网格后，FAB 仍按既有 `isWebHome` 规则隐藏（未改 FAB 可见性规则）。
- **W8**：Dart 分流 hk 优先（两者皆真时），与原生 `loadHome` 顺序一致。

## 静态核查（本机无 SDK，CI 为第一次真实编译检查）

- 入口谓词三处一致：快照 `canLoad`（既有）、host 复核 `canLoad`、壳 `resolve` +
  `load` 返回值。
- `WebHomeShellActivity` 的 import 全为 main 源集类；唯一 mobile-only 触点
  `SiteDialog` 走 `Class.forName` 字符串 + `Util.isMobile()` 门。
- Manifest：mobile 新增 1 条声明，leanback/main manifest 未动；XML 结构与
  `WebThemeDetailActivity` 条目一致。
- Pigeon：契约方法名 `openWebHomeShell` 在契约 / Java impl / Dart 三处逐字一致；
  `@async bool` 形态与同契约 `openSiteDialog` 一致（`Result<Boolean>`）。
- `git diff` 仅限 8 个 scope 文件；`VodFragment` / `HomeActivity` / 播放器 / 直播 /
  弹窗 / 数据层 / TV 零改动；零文件删除。

## 真机验收清单（RMX3800）

1. 站点自带 homePage 的 WebHome 站点：冷启动进壳，Web 首页正常渲染。
2. 全局主题（Eclipse，`Setting` 开 WebHome 主题）：普通站点也进壳渲染。
3. 壳内交互：点卡片进详情/播放、主题内搜索、主题内换站入口。
4. 返回：主题内多级页面逐级回退；到首页边界再返回 → 回到 Flutter 首页网格，
   不重开壳、不闪原生首页。
5. 换站：壳内或网格换到普通站点 → 网格正常；再换回 WebHome 站点 → 壳重开。
6. 主题内点设置 → Flutter 设置页；返回后行为正常。
7. 全屏主题：immersive 下首次返回先恢复 chrome，再返回才退出壳。
8. 旋转：壳内旋转页面不重载、chrome/viewport 正常。
9. 普通站点：首页/网格/搜索/设置与改动前一致，无壳干扰。
10. TV（leanback）构建：CI 通过即视为未受影响（壳无 leanback 声明与入口）。
