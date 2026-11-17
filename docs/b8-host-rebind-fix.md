# B8: HostApi impl 重绑修复（原生弹窗打不开）

## 现象

2026-11-17 21:15，Wei 真机报：Flutter 设置主屏（B7）点点播 / 直播 / 壁纸三行不出弹窗，无法填写配置源。点按无任何反应，也不崩溃。

## 根因

C1 引擎（`b8_c1_engine`）缓存在 `FlutterEngineCache` 里，生命长于 `C1ScreenActivity`；`ensureFlutterEngine()` 只在引擎首次创建时注册全部 23 个 HostApi impl，impl 持有的是第一个 `C1ScreenActivity` 实例。`onCreate()` 里只重绑了 B20/B21/B22，其余 impl（C1/C2/B2–B19）从不重绑。

Wei 在多个 Flutter 屏之间切换后，B7 impl 持有的 Activity 已停止或已销毁：

- 已停止：`homeHostFragment()` 里 `commitNow()` 抛 `IllegalStateException`（`Can not perform this action after onSaveInstanceState`）；
- 已销毁：`getSupportFragmentManager()` 及后续事务抛 `FragmentManager has been destroyed`。

异常被 `openHistoryDialog` 的 `catch (Throwable)` 吞掉，转 `result.error`，Dart 侧无感，于是弹窗不出、不崩溃。

已排除的假说：

- R8 混淆：`app/proguard-rules.pro:38` 有 `-keep class com.github.moyingshi.osc.tv.** { *; }` 全包保留，反射的类名/方法名不会被改名或删除；`HistoryDialog` 的 `create/vod/live/wall/manage/show(Fragment)` 齐全且 public。
- Dart 侧接线：点行正确调用 `afterDialog(api.openVodManage/openLiveManage/openWallManage)`。
- B7 的 headless fragment（`B7HomeHostFragment`）本身没问题：它实现 `ConfigListener/SiteListener/LiveListener`，`commitNow()` 同步添加。

影响面：所有经 `FragmentManager` 或 Activity launchers 的 Flutter 屏调用（B3/B6/B7/B9/B11 的各类原生弹窗、B19 目录选择结果等），不止点播三行。B20/B21/B22 早有重绑，不受影响。

## 修复

只改 `C1ScreenActivity.java` 一个文件：

- 把 `ensureFlutterEngine()` 里的 23 个注册调用原样抽成 `registerHostApis(BinaryMessenger)`（含 B20 的 `registerB20` 间接层与 B21/B22 的静态注册，注释全部保留）。
- `ensureFlutterEngine()` 首次创建引擎时调它（位置不变，`executeDartEntrypoint` 之前）。
- `onCreate()` 里引擎复用（`engineReused`）时调它，替代原来只重绑 B20/B21/B22 的三行；新引擎时不重复注册，首次创建行为与原来逐字一致。
- B20/B21/B22 的 `register` 方法按原有语义"一引擎一 impl、只更新 Activity 引用"；其余 impl 是无状态请求处理器，重绑时换新实例等价。`b19Impl` 字段随重绑一并刷新，插件目录选择结果在复用引擎上也不再丢失。

## 已知边角

旋转时若 B7 弹窗正开着：弹窗 fragment 由 FragmentManager 恢复，但打开弹窗时登记在旧 impl 实例上的 pending `@async` 结果随重绑丢失，Dart 侧的 Future 不再完成（`afterDialog` 刷新不跑），界面停留在旧快照，下次交互恢复。比修复前（弹窗根本打不开）严格更好；如需彻底处理，需把 pending 状态移到 impl 之外的引擎级注册表，留待以后。

## 验证

- 本地：`git diff --check` 干净；复读确认 23 个注册调用一个不少、全部改用 `messenger` 参数，`engineReused` 门控与 `ensureFlutterEngine` 调用点正确，`onCreate` 内不再有旧 `cached` 变量残留引用。
- 编译与行为无本地 SDK，以 CI + Wei 真机为准：验收动作为——打开任一 Flutter 屏后切到设置主屏，点点播/直播/壁纸三行均应弹出配置管理弹窗；再切去别的屏回来重测一次；旋转后重测一次。

## Recovery anchor

- 目标：`C1ScreenActivity` 每次创建（复用引擎时）重绑全部 HostApi impl 到活 Activity，修复原生弹窗打不开。
- 状态：本地提交完成，未 push；与总开关提交一起推、一起编。
- 唯一待办：推上去后看 CI，Wei 真机按上节验收。
