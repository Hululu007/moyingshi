# B8 B5：个人设置页 + 设置内搜索迁移到 Flutter

## §0 执行声明

- 本批迁移手机端**个人设置页**（原生 `SettingPersonalFragment`，327 行）与**设置内搜索**（原生 `SettingSearchFragment`，134 行）到 Flutter，作为 `/settings` 家族的新成员（规划 §3 B5）。
- 开关 `flutter_b5_enabled` 默认关，独立于 C1/C2/B2/B3/B4 开关；开关 UI 只在实验屏（"墨影视·Flutter 实验"图标）。关 = 两页全回原生，原生实现一行不删。
- 本机无 Java / Android / Flutter SDK：**构建与运行均未验证**。Pigeon 生成、Dart analyze、Gradle 编译、四风味构建、键盘行为与真机对照，全部留给联合 CI 构建 + Wei 的设备验收。
- 本批不改 C0/C1/C2/B2/B3/B4 的合约与实现；共享组件只加法；家族路由只加成员。
- 搜索是**第一个小文本输入面**（规划 §4.5）：Dart `TextField` 持有输入态，联想/热词/记录走 `@async` 快照 + 300ms 防抖；搜索结果跳转走 host。

## §1 原生实现（只读发现）

### 1.1 个人设置页

- 文件：`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SettingPersonalFragment.java`（327 行）+ `fragment_setting_personal.xml`。
- 平坦页：**0 个条件可见性**。24 行，布局 XML 顺序 = `setText()` 顺序 = 显示顺序（与增强页不同，增强页是 `reorderItems()` 顺序）。

| # | 行 key | 图标 / 圆圈 | 值文本 | 行为 |
|---|---|---|---|---|
| 1 | searchThread | ic_setting_number / 橙 | 数字（<=0 时显示 `setting_off`） | `SliderNumberDialog`（1–100）→ `putSearchThread` |
| 2 | following | ic_setting_history / 橙 | 开关 | toggle + 追更调度器（开：`ensurePeriodic` + `enqueueDueNow`；关：`cancelAll`）+ `ConfigEvent.common()` + `RefreshEvent.home()` |
| 3 | autoBackup | ic_setting_drive / 蓝 | 开关（有效态） | 开需文件权限（`PermissionUtil.requestFile`，原生用 Fragment 重载）；有效态 = `AutoBackupPolicy.isEffective(isAutoBackup, hasFileAccess)`；拒绝弹 `backup_permission_denied` |
| 4 | playbackOverlay | ic_setting_play / 红 | 开关 | 纯 toggle + `ConfigEvent.common()` |
| 5 | playBackToDetail | ic_setting_switch / 绿 | 开关 | 纯 toggle |
| 6 | episodeHistory | ic_setting_history / 橙 | 开关 | 纯 toggle |
| 7 | globalHistory | ic_setting_history / 橙 | 模式数组 | 循环 `select_global_history_mode` + `RefreshEvent.history()`；**原生有触摸门**：只有触摸序列始于该行时才响应点击（防误触，见 §7 V4） |
| 8 | interfaceFailover | ic_setting_refresh / 绿 | 模式数组 | 循环 `select_interface_failover_mode` |
| 9 | playSpeed | ic_setting_number / 橙 | `%.2f`（如 1.00） | `SpeedSettingDialog`（0.5–5，步进 0.25）→ `PlayerSetting.putDefaultSpeed` |
| 10 | groupRule | ic_setting_list / 紫 | `setting_group_rule_summary`（已启用数/总数） | `GroupRuleDialog`，改动时回调 |
| 11 | searchUi | ic_search / 蓝 | 模式数组 | 循环 `select_search_ui` |
| 12 | searchColumn | ic_setting_list / 紫 | 模式数组 | 循环 `select_search_column`（原生 `getSearchColumnText()` 自带钳制） |
| 13 | siteColumn | ic_setting_list / 紫 | 模式数组 | 1↔2 切换，显示 `array[getSiteColumn()-1]`（原生不钳制） |
| 14 | searchResultSort | ic_setting_list / 紫 | 模式数组 | 循环 `select_search_result_sort` |
| 15 | resetApp | ic_setting_delete / 红 | 无值文本 | `WebHtvAlertDialogBuilder` 确认 → `Util.resetApp()`（失败弹 `reset_app_failed`） |
| 16 | appBranding | ic_setting_appearance / 紫 | `AppBranding.getSummary()` | `AppBrandingActivity.start()`（原生跳转；B15 再迁移它） |
| 17 | touchOptimization | ic_setting_switch / 绿 | 开关 | 纯 toggle（只直接改行文本） |
| 18 | liquidGlass | ic_setting_appearance / 紫 | 开关 | toggle + `ConfigEvent.common()` + `RefreshEvent.theme()`（主题重建） |
| 19 | dramaCollected | ic_setting_play / 红 | 开关 | toggle + `ConfigEvent.common()` |
| 20 | glassBlur | ic_setting_number / 橙 | 数字 | `SliderNumberDialog`（0–40）+ `ConfigEvent.common()` |
| 21 | glassDistortion | ic_setting_number / 橙 | 数字 | `SliderNumberDialog`（0–30）+ `ConfigEvent.common()` |
| 22 | glassTranslucency | ic_setting_number / 橙 | 数字 + "%" | `SliderNumberDialog`（0–100）+ `ConfigEvent.common()` |
| 23 | glassDispersion | ic_setting_switch / 绿 | 开关 | toggle + `ConfigEvent.common()` |
| 24 | glassReset | ic_setting_refresh / 绿 | 无值文本 | 恢复默认值（blur 5 / distortion 30 / translucency 50 / dispersion true）+ `ConfigEvent.common()` |

- 开关值文本用 `setting_on` / `setting_off`（**不是** B4 增强页的 `setting_enable` / `setting_disable`）。
- 个人页用到的类**全部在 main 源集**（`FollowingSettings`、`FollowingScheduler`、`AppBranding`、`AutoBackupPolicy`、`GroupRuleConfig`、`PlayerSetting`、`ConfigEvent`、`RefreshEvent`、`GroupRuleDialog`、`SpeedSettingDialog`、`SliderNumberDialog`、`PermissionUtil`、`Notify`、`Util`），直接引用即可，**零反射**。
- 全部文案与数组**全部在 main res**（按目录分别 grep 验证：values + values-zh-rCN，只有 main 有命中；mobile / leanback 无命中）。**不需要 `mobileString()`**。
- 回调签名已逐字核对：`SliderNumberDialog.IntCallback.onValue(int)`；`SpeedSettingDialog.FloatCallback.onValue(float)`；`SliderNumberDialog.show(FragmentActivity, int, int, int, int, IntCallback)` + 七参重载；`SpeedSettingDialog.show(FragmentActivity, int, float, float, float, float, FloatCallback)`；`AppBrandingActivity.start(Activity)`；`GroupRuleDialog.create(FragmentActivity)` + `onChanged(Runnable)` + `show()`（`changed()` 在 4 处改动点触发回调）；`PermissionUtil.requestFile(FragmentActivity, Consumer<Boolean>)`（第 23 行）与 Fragment 重载（第 31 行）。

### 1.2 设置内搜索

- 文件：`app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SettingSearchFragment.java`（134 行）+ 移动端专属的 `SettingSearchIndex.java` 与 `SettingSearchAdapter.java`。
- 索引：`SettingSearchIndex.all()` 构建约 150 条，字段 `titleRes` / `subtitleRes` / `destRes` / `page` / `rowId`（全部 public final）。24 条主设置页行（`page = -1`，`rowId` 非空，`destRes = R.string.nav_setting`）+ 子页面行：播放器页 2（约 40 条）、增强页 3（28 条）、弹幕页 4（4 条）、个人页 5（24 条）、字幕页 6（3 条）、TMDB 页 7（6 条）、AI 页 8（6 条）、广告页 9（6 条）。
- 匹配：`search(context, entries, query)`——query 为空返回空结果；否则对 `title + " " + subtitle`（subtitleRes 为 0 时不加）做 `Locale.getDefault()` 小写后的子串匹配。
- 原生 UI：返回键 → `getRoot().change(1)`；搜索框 hint `setting_search_hint`，`imeOptions=actionSearch`，`inputType=text`，TextWatcher 触发 `onQuery`；IME 搜索键 → 隐藏键盘；空查询 → hint 视图；无结果 → 空视图（`setting_search_empty`）；有结果 → RecyclerView（`ItemSettingSearchResultBinding`：标题 16sp 标题色、副标题 13sp 副标题色且为空时隐藏、去向 13sp 值色）。`onEntry`：先隐藏键盘；`page >= 0` → `getRoot().change(page)`；否则 `SettingFragment.requestRow(rowId)` + `change(1)`。`onResume` 请求焦点 + 显示键盘（`SHOW_IMPLICIT`）。
- `SettingFragment.requestRow`：静态方法，置 `sPendingRow`；`SettingFragment.onResume()` 与 `onHiddenChanged(false)` 中 `consumePendingRow()` 触发 `performRow(rowId)`。

### 1.3 入口（只改分流处）

- 个人行：`SettingFragment.java:233`（`buildGroups`，账号组，`ROW_PERSONAL`）与 `performRow` 的 `ROW_PERSONAL`（:437）都经新共享方法 `openPersonalSettings()`（B3/B4 模式）。
- 搜索按钮：`SettingFragment.java:180`，`mBinding.searchBtn` → 新共享方法 `openSettingSearch()`。全库唯一 `change(10)` 调用点。
- 开关开 → `C1ScreenActivity.start(..., ROUTE_SETTINGS + "?member=personal" / "?member=search")`；开关关 → 原生 `getRoot().change(5)` / `change(10)`。
- `C1ScreenActivity` 已在 `SettingFragment` 中 import（:36）。

## §2 设计

### 2.1 Pigeon 合约（`flutter_module/pigeons/b5_personal_messages.dart`）

`B5HostApi`（29 个方法）+ 扁平数据类 `B5PersonalSettings` / `B5PersonalRow` / `B5SearchScreen` / `B5SearchEntry`。`@ConfigurePigeon` 路径照 B4（`lib/pigeon/b5_personal_messages.g.dart` 与 `../app/src/main/java/.../flutter/B5Messages.java`）。

- `isB5Enabled()` / `setB5Enabled(bool)`：开关。
- `getPersonalSettings()` → `B5PersonalSettings{screenTitle, List<B5PersonalRow>}`：一次快照，24 行按原生布局顺序；`B5PersonalRow{key, title, text, hasError}`，key 为固定 token（非文案）。
- 8 个 toggle（following / playbackOverlay / playBackToDetail / episodeHistory / touchOptimization / liquidGlass / dramaCollected / glassDispersion）、6 个 cycle（globalHistory / interfaceFailover / searchUi / searchColumn / siteColumn / searchResultSort）、`resetGlass()`：同步写回，Dart 随后重取。
- 7 个 `@async` 弹窗：`openSearchThread` / `openPlaySpeed` / `openGroupRule` / `openGlassBlur` / `openGlassDistortion` / `openGlassTranslucency` / `openAutoBackup`。弹窗契约照 B3 V1：**只在状态可能变时完成回调，Dart 不 await、只挂 `.then` 重取**；无改动关闭时不完成回调，也就无需刷新。
- `showResetAppDialog()` / `openAppBranding()`：fire-and-forget（前者确认后进程重启，无物可刷新；后者打开另一屏）。
- `getSettingSearch()` → `B5SearchScreen{hintText, emptyText, List<B5SearchEntry>}`：**整份索引一次返回**，Dart 本地过滤（见 §2.4）；`B5SearchEntry{title, subtitle, dest, page, rowId, flutterAvailable}`。
- `openSearchEntry(int page, String rowId)`：fire-and-forget，host 结束当前屏再开目标（见 §2.5）。

### 2.2 Dart 页（`flutter_module/lib/b5_personal_page.dart`）

- `PersonalSettingsView`：家族成员。行用共享 `SettingsRow`（图标/圆圈/标题/值/chevron/`isError`/`onTap`）；图标、圆圈色、chevron 按 key 查表，照 `fragment_setting_personal.xml`（material 图标近似原生 drawable，B3 V8 同款）；未知 key 回退为 `Icons.settings_outlined`、灰色、无 chevron、无动作。
- `_B5MemberState` mixin：从 B4 的 `_B4MemberState` **复刻**（B4 的 mixin 是文件私有的；按批独立原则不共享、不改 B4 代码）。含 resumed 重取、`afterDialog`（不 await 的 `.then` 重取）、`act`（同步动作后重取）、`loadingScaffold` / `errorScaffold` / `rowsScaffold`。
- `SettingSearchView`：家族成员。`TextField`（autofocus，`TextInputAction.search`，提交时收键盘）+ 300ms 防抖（`Timer`）+ 本地过滤；空查询显示 hint 文本；有查询无结果显示 empty 文本；结果行 `_SearchResultTile`（标题 16 + 副标题 13 + 去向 13 值色，照 `item_setting_search_result.xml` 的字号与颜色，见 §7 V5）。
- 族壳（`b3_settings_a_page.dart`）：`_members` 加 `'personal'`、`'search'`；`onGenerateRoute` 加 `/personal`、`/search` 两个 case。只加法，不动现有成员。

### 2.3 Host 实现（`B5HostApiImpl.java`，约 600 行）

- 快照：`row()` / `safeRow()`（单行解析失败 → `error_config_get` + `hasError`，B4 同款）；`getSwitch` 用 `setting_on` / `setting_off`；`arrayText()` 下标钳制（B3 V10 同款；siteColumn 用 `getSiteColumn()-1`，钳制后与原生一致，脏偏好下更安全）；`cycleNext()` 循环取模；siteColumn 保持原生 1↔2 切换。
- toggle / cycle：逐项复刻 §1.1 的原生副作用（追更调度器、`ConfigEvent.common()`、`RefreshEvent.home()/history()/theme()`）。`resetGlass()` 恢复 4 个默认值。
- 弹窗：每个 `@async` 方法一个 `boolean[1] completed` + `completeOnce()`（B3/B4 同款）；`openGroupRule` 用 `GroupRuleDialog.create(activity).onChanged(...).show()`（改动时才完成）；`openAutoBackup`：有效态已开 → 直接关并完成；否则用 **Activity 重载** `PermissionUtil.requestFile(activity, ...)`（B4 V9 同款；impl 持有的是 FragmentActivity），拒绝时弹 `backup_permission_denied` 且不完成回调；`activity.isFinishing()/isDestroyed()` 守卫。
- `showResetAppDialog`：`WebHtvAlertDialogBuilder` 内联确认（`dialog_reset_app` / `dialog_reset_app_data` / `dialog_negative` / `dialog_positive`），确认 → `Util.resetApp()`，失败弹 `reset_app_failed`。

### 2.4 搜索索引：反射（规划 §4.8 第四招）

`SettingSearchIndex` 是 mobile-only 类，main 源集的 impl 不能直接引用。`getSettingSearch()` 用反射：

```java
Class.forName("com.github.moyingshi.osc.tv.ui.fragment.SettingSearchIndex")
  .getMethod("all").invoke(null)
```

再取各条目的 public final 字段（`titleRes` / `subtitleRes` / `destRes` / `page` / `rowId`），host 用 `activity.getString()` 解析文案。ProGuard 的 `-keep com.github.moyingshi.osc.tv.**` 覆盖（C2/B2 前例）。反射失败时退化为**空索引**（搜索屏显示 empty 文本），个人页不受影响（见 §7 V10）。

`flutterAvailable` 由 host 按各批开关算出：页 3 → `isFlutterB4Enabled()`；页 4/7/8/9 → `isFlutterB3Enabled()`；页 5 → `isFlutterB5Enabled()`；页 2（播放器）与页 6（字幕，顶层 C1 路由而非家族成员）→ false，恒走 host。

Dart 过滤与原生 `SettingSearchIndex.search()` 同义：trim → 小写 → 对 `title + " " + subtitle`（subtitle 为空时不加空格）做子串匹配；空查询匹配零条（显示 hint）。

### 2.5 搜索结果跳转（跨屏导航恒走 host，规划 §4.4）

- `flutterAvailable` 为真 → Dart 族内 `Navigator.pushNamed('/$member')`（member 表：3→enhance、4→danmaku、5→personal、7→tmdb、8→ai、9→ad）；返回时回到搜索页，查询与结果保留（同一 Navigator 栈内 state 不丢；resumed 重取后按当前查询重算结果）。
- 否则 → `api.openSearchEntry(page, rowId)`（Dart 不 await；host 结束当前屏）：
  - 页 6（字幕）：C1 开关开 → `C1ScreenActivity.start(..., ROUTE_SUBTITLE)`；关 → 原生 HomeActivity 页 6。
  - `rowId` 为空 → `openHomeAt(page)`。
  - `rowId` 非空 → 反射调 `SettingFragment.requestRow(rowId)`（mobile-only 静态方法），再 `openHomeAt(1)`，原生 `onResume` 时消费深链。
- `openHomeAt(int)`：照 `LiveActivity:431` 前例，`Intent(HomeActivity)` + `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP` + extra `"nav_position"`。**注意 leanback 约束**：leanback 的 `HomeActivity` 没有 `change(int)` 方法、也没有 `EXTRA_NAV_POSITION` 常量（已 grep 确认两者皆无），所以常量以内联字面量 `"nav_position"` 传入（mobile 源集字段值相同）；`HomeActivity` 类本身双风味都存在，引用可编译。leanback 无入口进本屏，该路径在 leanback 上不可达。
- 原生 `FragmentStateManager.change()` 不用回退栈（`addToBackStack` 从未使用），原生从子页面返回**不回到**上一子页面；Flutter 家族的"返回上一成员"是**有意偏差**（见 §7 V2）。

### 2.6 文件清单

| 文件 | 动作 |
|---|---|
| `flutter_module/pigeons/b5_personal_messages.dart` | 新 |
| `flutter_module/lib/b5_personal_page.dart` | 新 |
| `flutter_module/lib/main.dart` | 改：import + `/settings?member=personal`、`/settings?member=search` 两条字面路由 + B5 开关（照 B4 模式） |
| `flutter_module/lib/b3_settings_a_page.dart` | 改：import + `_members` 加两项 + `onGenerateRoute` 加两 case（只加法） |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/B5HostApiImpl.java` | 新 |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java` | 改：注册 `B5HostApi`（一行 + 注释） |
| `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0ShellActivity.java` | 改：C0 引擎上注册 `B5HostApi`（开关用） |
| `app/src/main/java/com/github/moyingshi/osc/tv/setting/Setting.java` | 改：`flutter_b5_enabled`（默认 false） |
| `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/fragment/SettingFragment.java` | 改：`openPersonalSettings()` + `openSettingSearch()` 两个共享分流方法；个人行、搜索按钮、`performRow ROW_PERSONAL` 改走它们（只改分流处） |
| `.gitignore` | 改：加 `B5Messages.java` |
| `.github/workflows/b8-c0.yml` | 改：Pigeon 步骤加 b5 生成 + 存在性检查（B4 同款 3 行） |
| `docs/b8-b5-personal.md` | 新（本文档） |

## §3 状态来源清单

### 个人页（全部 host 快照，Dart 不计算）

- searchThread：`Setting.getSearchThread()`；following：`FollowingSettings.isEnabled()`；autoBackup：`AutoBackupPolicy.isEffective(Setting.isAutoBackup(), Setting.hasFileAccess())`；playbackOverlay / playBackToDetail / episodeHistory / touchOptimization / liquidGlass / dramaCollected / glassDispersion：对应 `Setting.*`；globalHistory / interfaceFailover / searchUi / searchColumn / siteColumn / searchResultSort：对应 `Setting.*` + main res 数组；playSpeed：`PlayerSetting.getDefaultSpeed()`；groupRule：`GroupRuleConfig.enabledCount()/totalCount()` + `setting_group_rule_summary`；appBranding：`AppBranding.getSummary(activity)`；glassBlur / glassDistortion / glassTranslucency：对应 `Setting.*`；标题：`R.string.setting_personal`。

### 搜索页

- 索引：反射 `SettingSearchIndex.all()`（约 150 条）+ host 解析文案；hint：`R.string.setting_search_hint`；empty：`R.string.setting_search_empty`；`flutterAvailable`：各批开关（§2.4）。

## §4 逐项对照清单

### 个人页（24 行；原生 `SettingPersonalFragment` 逐行对照）

1. searchThread：值 = 数字（<=0 显示 `setting_off`）；点 → 数字滑块弹窗（1–100）；选值后 `putSearchThread` + `ConfigEvent.common()`；无改动关闭 → 无刷新。✓
2. following：开关文本 `setting_on/off`；点 → toggle；开 → 调度器 `ensurePeriodic` + `enqueueDueNow`，关 → `cancelAll`；+ `ConfigEvent.common()` + `RefreshEvent.home()`。✓
3. autoBackup：值 = 有效态（`isEffective`）；点 → 已有效则关；未有效则请求文件权限，授权后开，拒绝弹 `backup_permission_denied` 且不刷新。✓
4. playbackOverlay：开关；点 → toggle + `ConfigEvent.common()`。✓
5. playBackToDetail：开关；点 → toggle + `ConfigEvent.common()`。✓
6. episodeHistory：开关；点 → toggle + `ConfigEvent.common()`。✓
7. globalHistory：值 = 模式数组项；点 → 循环下一项 + `ConfigEvent.common()` + `RefreshEvent.history()`。**偏差见 V4（触摸门未复刻）。**
8. interfaceFailover：值 = 模式数组项；点 → 循环 + `ConfigEvent.common()`。✓
9. playSpeed：值 = `%.2f`；点 → 速度弹窗（0.5–5，步进 0.25）；选值后 `putDefaultSpeed` + `ConfigEvent.common()`；无改动关闭 → 无刷新。✓
10. groupRule：值 = `已启用/总数`；点 → 规则弹窗；改动时回调 → 重取；无改动关闭 → 无刷新。✓
11. searchUi：值 = 模式数组项；点 → 循环 + `ConfigEvent.common()`。✓
12. searchColumn：值 = 模式数组项；点 → 循环 + `ConfigEvent.common()`。✓
13. siteColumn：值 = `array[getSiteColumn()-1]`；点 → 1↔2 切换 + `ConfigEvent.common()`。✓
14. searchResultSort：值 = 模式数组项；点 → 循环 + `ConfigEvent.common()`。✓
15. resetApp：无值文本；点 → 确认弹窗；确认 → `Util.resetApp()`（失败弹 `reset_app_failed`）；取消 → 无事发生。✓
16. appBranding：值 = `AppBranding.getSummary()`；点 → 原生 `AppBrandingActivity`（B15 再迁移）。✓
17. touchOptimization：开关；点 → toggle + `ConfigEvent.common()`。✓
18. liquidGlass：开关；点 → toggle + `ConfigEvent.common()` + `RefreshEvent.theme()`。✓
19. dramaCollected：开关；点 → toggle + `ConfigEvent.common()`。✓
20. glassBlur：值 = 数字；点 → 滑块弹窗（0–40）；选值后落盘 + `ConfigEvent.common()`。✓
21. glassDistortion：值 = 数字；点 → 滑块弹窗（0–30）；同上。✓
22. glassTranslucency：值 = 数字 + "%"；点 → 滑块弹窗（0–100）；同上。✓
23. glassDispersion：开关；点 → toggle + `ConfigEvent.common()`。✓
24. glassReset：无值文本；点 → 恢复 4 个默认值 + `ConfigEvent.common()`。✓
25. （整页）图标/圆圈色/chevron 照 `fragment_setting_personal.xml`；无条件可见行；返回键 → 族壳 `_handleBack`（无更深栈时关屏）。✓
26. （整页）开关关 → 个人行与搜索按钮均回原生；原生实现未动。✓

### 搜索页（输入 / 联想 / 跳转对照）

1. 打开：设置主屏搜索按钮（开关开时）或搜索结果深链（rowId 指向搜索页时，见 §2.5）。✓
2. 输入框：hint = `setting_search_hint`；autofocus；IME 动作为搜索；提交 → 收键盘。**键盘实际行为待验收，见 §6 G2。**
3. 输入：每键入一次，300ms 防抖后本地过滤；过滤语义与原生 `SettingSearchIndex.search()` 一致（trim → 小写 → title + " " + subtitle 子串匹配；空查询零条）。✓
4. 空查询 → hint 视图；有查询无结果 → empty 文本（`setting_search_empty`）；有结果 → 列表。✓
5. 结果行：标题 16sp + 副标题 13sp（为空隐藏）+ 去向 13sp 值色。**去向右置为推断，见 §7 V5。**
6. 点结果（已迁移成员且开关开）→ 族内 `pushNamed`；返回 → 回到搜索页，查询与结果保留。✓
7. 点结果（播放器页 / 字幕页 / 未迁移页 / 开关关）→ host `openSearchEntry`：结束当前屏 → 原生目标（字幕页按 C1 开关分流；rowId 深链经反射 `requestRow` + 设置主屏）。✓
8. 返回键 → 族壳 `_handleBack`（无更深栈时关屏；原生是 `change(1)` 回设置主屏——族内返回语义见 §7 V2）。✓
9. resumed 时重取索引（开关状态可能变了），并按当前查询重算结果。✓

## §5 回滚

- 开关级：`flutter_b5_enabled` 关 → 个人页与搜索页均回原生，零行为变化；全部开关关 = 全原生。
- 批级：本批提交独立可 revert（共享文件只含加法行；新文件 4 个可整体删除）。
- 分支级：丢弃 `b8-flutter-rewrite` 分支。
- 无数据层改动，无需数据回滚。

## §6 待证明项（本机无 SDK，全部留给 CI + 设备验收）

1. **G1 — Pigeon 生成与手写 impl 签名**：`B5HostApi` 29 个方法 + `List<B5PersonalRow>` / `List<B5SearchEntry>` 返回值 + `Long` 字段 + `@async VoidResult`，均为手写 Java 对 CI 生成接口的匹配。方法名须与 Dart 定义**字符级一致**（C1 `setSubtitleAutoMatch` 教训：不得推断大小写归一化）；`bool` / `int` / `double` 一律装箱；数据类 Builder 字段名须与 Pigeon 生成的一致。任一不匹配在 CI 的 Gradle 编译失败中按文件路径归属到本批。
2. **G2 — 键盘 / 输入法行为（本批最大风险）**：`TextField` 的 autofocus、键盘弹出/收起、搜索键行为在嵌入式 `FlutterFragment` 中**本机无法验证**。**若 B5 联合验收时键盘行为异常，B9（搜索输入页）停下来等决策（规划 §4.5），不得按原计划继续做。** 验收时须逐项核对 §4 搜索对照第 2、8 条。
3. **G3 — 反射三处 + 混淆存活**：`SettingSearchIndex.all()` + 5 个 public final 字段、`SettingFragment.requestRow(String)`，均依赖 ProGuard `-keep com.github.moyingshi.osc.tv.**`。若混淆后失效：搜索退化为空索引（G3a）、rowId 深链退化为只打开设置主屏（G3b）。验收时须在 release 包上验证搜索有结果且深链能定位到行。
4. **G4 — 四风味 release 构建**：leanback 侧 `HomeActivity` 与 `AppBrandingActivity` 均双风味存在（已核对）；全部 `R.string` / `R.array` 均在 main res；`mobileString()` 不需要。但 leanback 构建仍须 CI 证明（C2 的 leanback 隐患教训：未进 CI 前不得宣称可构建）。
5. **G5 — 搜索跳转分流**：字幕页 C1 开/关两路、rowId 深链（反射 + `onResume` 消费）、`openHomeAt` 的 `CLEAR_TOP | SINGLE_TOP` + `"nav_position"` 字面量，均须在真机上逐条验证（§4 搜索对照第 7 条）。
6. **G6 — 族内返回语义**：搜索页内 push 成员后返回，查询与结果须保留；族壳 `_handleBack` 在栈空时关屏（原生是 `change(1)` 回设置主屏，见 V2）。
7. **G7 — autoBackup 权限流**：Activity 重载 `PermissionUtil.requestFile`（B4 V9 同款）在真机上的授权/拒绝两路；拒绝时 toast 且不刷新。
8. **G8 — globalHistory 触摸门差异**（V4）：Flutter 版无触摸门，点击即循环；验收时确认该差异可接受。

## §7 相对规划的偏差

- **V1 — 搜索 `flutterAvailable` 的两类例外**：页 2（播放器）恒为 false；页 6（字幕）恒为 false 且走 host 的 C1 分流（顶层路由，非家族成员）。规划 §4.4 的"家族内导航"只覆盖已迁移的家族成员。
- **V2 — 族内返回语义**：原生 `FragmentStateManager.change()` 不用回退栈，子页面返回不回到上一子页面；Flutter 家族用 Navigator 栈，搜索页内 push 成员后返回**回到搜索页**。有意偏差：搜索→成员→返回搜索是更自然的体验；进程死亡后族内深度不恢复（规划 §4.4 已接受）。
- **V3 — 键盘行为**：原生 `onResume` 请求焦点 + `SHOW_IMPLICIT` 显键盘；Flutter 版 `autofocus: true` + 提交时收键盘。实际效果见 §6 G2。
- **V4 — globalHistory 触摸门未复刻**：原生只有触摸序列始于该行时才响应点击（`globalHistoryTouchStarted` 标志，`ACTION_DOWN` 置位、`ACTION_CANCEL` 清除），防误触；Flutter 行点击无此门，点击即循环。行为差异，验收确认（§6 G8）。
- **V5 — 搜索结果行去向位置为推断**：只核对了 `item_setting_search_result.xml` 的字号与颜色（标题 16sp、副标题 13sp、去向 13sp 值色），未逐像素对照原生 item 布局；Dart 版将去向右置于同一行。验收时对照原生截图。
- **V6 — autoBackup 权限用 Activity 重载**：原生用 `PermissionUtil.requestFile` 的 Fragment 重载；impl 持有 FragmentActivity，改用 Activity 重载（B4 V9 同款，同一 PermissionX 调用）。
- **V7 — 图标/圆圈色硬编码**：个人页图标与圆圈色为 Dart 侧 material 近似（B3 V8 同款）；resetApp / glassReset 行无值文本（空字符串，与原生无值 TextView 一致）。
- **V8 — 搜索筛选在 Dart 本地做**：host 一次返回整份索引，Dart 按 300ms 防抖本地过滤（规划 §4.5）；过滤语义与原生 `search()` 逐字一致，含"空查询零条"。
- **V9 — `openHomeAt` 用字面量 extra 名**：leanback `HomeActivity` 无 `EXTRA_NAV_POSITION` 常量，`nav_position` 以字面量传入（值与 mobile 字段相同）；`HomeActivity` 类本身双风味存在。见 §2.5。
- **V10 — 搜索索引反射失败时退化为空索引**：`getSettingSearch()` 捕获全部异常并返回空列表（个人页不受影响）；`openSearchEntry` 的 `requestRow` 反射失败时仍打开设置主屏，只丢深链。
- **V11 — `_B5MemberState` 从 B4 复刻**：不共享、不改 B4 代码（按批独立原则）。
- **V12 — searchThread <= 0 显示 `setting_off`**：照原生 `setText()` 的三元逻辑（`value > 0 ? 数字 : setting_off`）。

## Recovery anchor

- 下一步：**B6 播放器设置页**（规划 §3）。**但 B6 依赖 P1 决策——若 P1（播放器设置页迁不迁移）未定，B6 跳过**，见规划 §6；跳过时直接做 B7 设置主屏。
- 本批未推送、未构建；全部待证明项见 §6。
