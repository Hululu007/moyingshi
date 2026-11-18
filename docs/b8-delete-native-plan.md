# B8 删除原生手机界面方案（评估文档）

> 评估基准：分支 `b8-flutter-rewrite`，HEAD `dc296f78`，工作树干净（2026-11-18 核实）。
> 本文档只做评估与方案，不改代码、不提交。实施时每阶段一个 task-guard 会话 + 一个本地提交（见 §5）。
>
> 硬约束（中国主人已定，不可违反）：播放器（Exo/ijk/mpv 三内核 + 播放器界面，C3 最后评估）留原生；
> 直播界面留原生（后续独立 program）；弹窗（dialogs）留原生；数据层 / 配置层 / services 留原生；
> TV（leanback）flavor 一律不动；仅 mobile。
>
> 核心约束（决定分阶段顺序）：main 源集代码直接引用任何被删的 mobile 类都会导致 **mobile 编译失败**，
> 即使引用在死分支里。leanback 有同 FQCN 的平行类（HomeActivity、SearchActivity 等），所以 main 源集的
> 改线必须用“mobile 走 C1ScreenActivity + TV 走反射”两种形态，同一阶段内完成，绝不能留悬空引用。
> 另：`Util.isMobile()` / `Util.isLeanback()` 以 `BuildConfig.FLAVOR_mode` 字符串判定（`Util.java:436-440`），
> 是安全的 flavor 门控。

---

## 1. 留 / 删 / 移三张清单

### 1.1 删：代码文件（48 个）

每个条目标注 Flutter 对应物与代码依据。路径前缀：`app/src/mobile/java/com/github/moyingshi/osc/tv/`。

#### A. Activity（8 个）

| 文件 | Flutter 对应物 | 依据 |
|---|---|---|
| `ui/activity/HomeActivity.java`（769 行） | `/home`（B11）+ 宿主职责移入 `C1ScreenActivity`（见 §2） | mobile `AndroidManifest.xml` launcher 入口；`change()` 内 B7/B11 分流 |
| `ui/activity/SearchActivity.java` | `/search`（B9 输入页）+ `/collect`（B10 结果页） | `start()`（:42 起 B9 分流）、`direct()`（:71 起 B10 分流）、`directFromHistory()`（:108 起） |
| `ui/activity/HistoryActivity.java` | `/history`（C2） | `HistoryActivity:198` 分流点；`goHome()`（:78）随文件消失 |
| `ui/activity/KeepActivity.java` | `/keep`（B2） | `KeepActivity:78` goHome；`:180` search 入口随文件消失 |
| `ui/activity/FileActivity.java` | `/local`（B15） | `FileChooser.localBrowserIntent()`（`FileChooser.java:95`）B15 分流；B4 `openLocalReader`（`B4HostApiImpl.java:322`） |
| `ui/activity/FolderActivity.java` | `/category`（B8） | `FolderActivity.java:40` B8 分流（`ROUTE_CATEGORY` + 首个 Class + hr 参数） |
| `ui/activity/WidgetBrowseActivity.java` | `/widgets`（B18） | `:69` 分流；`:159` search 入口随文件消失 |
| `ui/hk/HkMiniappActivity.kt` | `/hk-manager`（B20） | `LabActivity.openMiniappManager()`（`LabActivity.java:339`）B20 分流；仅剩注释引用（`HkWarmup.java:55`、`B20HostApiImpl.java:40/264`）与隐式 intent `.hk_miniapp` |

#### B. Fragment 与 Fragment 包辅助类（16 个）

| 文件 | Flutter 对应物 | 依据 |
|---|---|---|
| `ui/fragment/VodFragment.java`（1210 行） | `/home`（B11）；hk:// 内嵌 → `HkListActivity`（独立页，见 §6 R2）；WebHome 首页 → **待定**（见 §6 R1） | 唯一 `HkListFragment.EmbedHost` 实现（`mHkEmbedded`）；`mBinding.homeWeb` 承载 `HomeWebController`（`VodFragment.java:544`）；3 个 FAB（`fragment_vod.xml:241-279`）已由 B11 FAB 方案迁移 |
| `ui/fragment/CollectFragment.java` | `/collect`（B10） | `menu_collect` 唯一使用者 |
| `ui/fragment/SearchFragment.java` | `/search`（B9） | `menu_search` 唯一使用者；`SearchFragment.java:236` 分流点 |
| `ui/fragment/TypeFragment.java` | `/category`（B8） | `:224/:237` search 入口随文件消失 |
| `ui/fragment/FolderFragment.java` | `/category`（B8） | `FragmentFolderBinding` 唯一使用者；由 `FolderActivity` 承载 |
| `ui/fragment/SettingFragment.java` | `/settings`（B7） | 25 行 + 下载列表已迁移（B7）；总开关原生行 `:210/:379/:443/:734-737` 随文件消失（见 §4） |
| `ui/fragment/SettingPlayerFragment.java` | `/settings?member=player`（B6） | B6 迁移 23 可见行 |
| `ui/fragment/SettingEnhanceFragment.java` | `/settings?member=enhance`（B4） | B4 迁移 28 行；`:104/:107` Lab 入口随文件消失 |
| `ui/fragment/SettingDanmakuFragment.java` | `/settings?member=danmaku`（B3） | B3 迁移 |
| `ui/fragment/SettingPersonalFragment.java` | `/settings?member=personal`（B5） | B5 迁移 24 行 + 站内搜索 |
| `ui/fragment/SettingSubtitleFragment.java` | `/subtitle`（C1） | C1 迁移 |
| `ui/fragment/SettingTmdbFragment.java` | `/settings?member=tmdb`（B3） | B3 迁移 |
| `ui/fragment/SettingAiFragment.java` | `/settings?member=ai`（B3） | B3 迁移 |
| `ui/fragment/SettingAdFragment.java` | `/settings?member=ad`（B3） | B3 迁移 |
| `ui/fragment/SettingSearchFragment.java` | B5 站内搜索 + B7 搜索行 | 仅 `SettingSearchAdapter` 使用它 |
| `ui/fragment/SettingSearchAdapter.java` | 同上 | 仅 `SettingSearchFragment` 使用（grep 仅 2 个文件命中） |

#### C. 宿主辅助类（4 个）

| 文件 | 对应物 / 去向 | 依据 |
|---|---|---|
| `ui/activity/WebHomeChromeController.java`（234 行） | 无——随 `HomeActivity` 布局消失；WebHome 回退壳如需重建则另议（§6 R1） | 仅 `HomeActivity` + `ActivityHomeBinding` 使用 |
| `ui/fragment/FragmentStateManager.java` | 无——`HomeActivity` 专属 | 仅 `HomeActivity` 使用 |
| `ui/custom/CustomFabBehavior.java` | 无——FAB 行为已由 Flutter 侧实现 | 仅 `fragment_vod.xml` 的 `layout_behavior` 引用 |
| `ui/custom/CustomViewPager.java` | 无 | 仅 `fragment_vod.xml:195` 使用 |

#### D. Glass 包（7 个，整个包删除）

`glass/DampedDragAnimation.kt`、`glass/DragGestureInspector.kt`、`glass/FloatingNavBar.kt`、
`glass/GlassNavBar.kt`（内含 `GlassNavItem` / `GlassNavState` / `GlassNav` / `GlassBackdropCapture`）、
`glass/InteractiveHighlight.kt`、`glass/LiquidGlassConfig.kt`、`glass/NavMetrics.kt`。
依据：使用者只有 `HomeActivity` 与 `activity_home.xml`（res 内无 `tv.glass.` 引用）；Flutter 首页已有自己的
`_GlassBottomBar` + 悬浮胶囊栏（B11 液态玻璃方案）。`menu_nav.xml` 不删（`activity_live.xml` 横竖屏仍用，见 §1.2）。

#### E. Adapter（13 个）

`ui/adapter/` 下：`BannerAdapter`、`CollectAdapter`、`ContinueAdapter`、`FileAdapter`、`HistoryAdapter`、
`HotWordAdapter`、`KeepAdapter`、`RecordAdapter`、`SearchAdapter`、`ThemeAdapter`、`WidgetGridAdapter`、
`WidgetSectionAdapter`、`WordAdapter`。
依据：main 源集 impl 中对这些 adapter 的引用经核实全是注释（doc 注释如“Flattens one X bean the way Y binds it”）；
`ThemeAdapter` 在全仓库除自身文件与 leanback 平行副本外零引用；
`ShortDramaActivity` 用的是自己内嵌的 `HistoryAdapter`（`:796`），不 import `ui.adapter` 的版本；
`Result.java` 用的是 `gson.FilterAdapter`，不是本类。

### 1.2 删：资源文件（62 个，其中 1 个有条件）

路径前缀：`app/src/mobile/res/`。布局的判定依据是 ViewBinding 引用（本工程不用 `R.layout.*` 直接引用，
逐个 binding 类 grep，唯一使用者均为被删文件）。

#### 布局（43 个）

- Activity 布局（8）：`layout/activity_file.xml`、`activity_folder.xml`、`activity_history.xml`、
  `activity_hk_miniapp.xml`、`activity_home.xml`（`WebHomeChromeController` 也用它，同删）、
  `activity_keep.xml`、`activity_search.xml`、`activity_widget_browse.xml`。
- Fragment 布局（15）：`layout/fragment_collect.xml`、`fragment_folder.xml`、`fragment_search.xml`、
  `fragment_type.xml`、`fragment_vod.xml`、`fragment_setting.xml`、`fragment_setting_ad.xml`、
  `fragment_setting_ai.xml`、`fragment_setting_danmaku.xml`、`fragment_setting_enhance.xml`、
  `fragment_setting_personal.xml`、`fragment_setting_player.xml`、`fragment_setting_search.xml`、
  `fragment_setting_subtitle.xml`、`fragment_setting_tmdb.xml`。
- Adapter 布局（13）：`layout/adapter_banner.xml`、`adapter_collect.xml`、`adapter_continue.xml`、
  `adapter_file.xml`、`adapter_search.xml`、`adapter_search_hot_word.xml`、`adapter_search_record.xml`、
  `adapter_search_word.xml`、`adapter_theme.xml`、`adapter_widget_grid_item.xml`、
  `adapter_widget_section.xml`（binding 唯一使用者是 `VodFragment`）、`adapter_widget_item.xml`、
  `adapter_widget_chip.xml`（binding 唯一使用者是 `WidgetBrowseActivity`）。
- Item 布局（6）：`layout/item_download_drama.xml`、`item_download_episode.xml`、
  `item_setting_card_row.xml`、`item_setting_card_row_config.xml`、`item_setting_group_title.xml`
  （以上 5 个 binding 唯一使用者是 `SettingFragment`）、`item_setting_search_result.xml`
  （唯一使用者 `SettingSearchAdapter`）。
- 孤儿布局（1）：`layout/adapter_danmaku_header.xml`——零引用（binding 与 XML include 均无），删除前复核一次即可。

#### 菜单（8 个）

`menu/menu_collect.xml`（`CollectFragment`）、`menu_history.xml`（`HistoryActivity`）、
`menu_keep.xml`（`KeepActivity`）、`menu_search.xml`（`SearchFragment`）、
`menu_setting_enhance.xml`（`fragment_setting_enhance.xml:15` toolbar `app:menu`）、
`menu_vod.xml`（`fragment_vod.xml:22` toolbar `app:menu`）、`menu_vod_more.xml`（`VodFragment.java:666`）、
`menu_widget_browse.xml`（`WidgetBrowseActivity`）。
留：`menu_nav.xml`（`activity_live.xml` 横竖屏使用）、`menu_hk_list.xml`（`HkListActivity`）。

#### Drawable（10 个，1 个有条件）

- `drawable/ic_fab_tune.xml`、`drawable/ic_fab_top.xml`——仅 `fragment_vod.xml:251/:279` 使用。
  `ic_fab_link.xml` **不删**（`dialog_apk_push_method.xml:68` 仍用，弹窗留原生）。
- `drawable/ic_popup_apk.xml`、`ic_popup_enhance.xml`、`ic_popup_history.xml`、`ic_popup_keep.xml`、
  `ic_popup_push.xml`、`ic_popup_refresh.xml`、`ic_popup_sync.xml`——仅 `fragment_vod.xml:95` 与
  `menu_vod_more.xml` 使用。
- `drawable/shape_search_hot_word.xml`（有条件）——仅 `adapter_search_hot_word.xml:14` 使用；
  但 `ThemeBinder.java:126/149` 以字符串形式引用 `shape_search_hot_word_focused` / `_normal` 变体，
  实施时先确认 `ThemeBinder` 是否在运行时由基名派生变体名（`getIdentifier`），确认无派生再删。
- 其余 drawable 一律不删：未用 drawable 不影响编译，误删风险高于收益；后续用 Android Lint `UnusedResources`
  统一清理（已确认的既有孤儿如 `ic_fab_filter`、`ic_action_enhance`、`shape_accent` 等不在本次范围）。

#### 字符串（1 个文件）

- `app/src/main/res/values/b8_c0_strings.xml` 整个文件：3 个字符串——`b8_c0_launcher_label` 已死
  （仅定义处有引用，C0 launcher 入口已删）；`b8_flutter_title` / `b8_flutter_sub` 在总开关拆除后
  无使用者（仅 `SettingFragment.java:210` 与 `B7HostApiImpl.java:247` 使用，均随拆除消失；无翻译文件引用）。
  其余 strings **一律不删**（本次不做字符串清理）。

#### Manifest 条目（mobile `AndroidManifest.xml` 内的删除/改向，见 §3）

- 删除：`SearchActivity`、`HistoryActivity`、`KeepActivity`、`FileActivity`、`FolderActivity`、
  `WidgetBrowseActivity`、`HkMiniappActivity`（含 `.hk_miniapp` intent-filter）共 7 个 activity 声明。
- 改向（不是删除）：`HomeActivity` 声明整体移除，但其 intent-filter（SEARCH/SEND/VIEW）与
  `searchable` meta-data **移**到 `C1ScreenActivity`；两个 launcher alias
  （`HomeActivityCurrent` / `HomeActivityHistory`）保留组件名，只改 `targetActivity`（见 §3）。

### 1.3 留：清单

#### 留（mobile Activity，8 个）

`LiveActivity`（直播界面，后续独立 program）、`VideoActivity`（详情 + 播放器，C3 最后评估）、
`ShortDramaActivity`（scheme B：原生保留为入口 + 播放流宿主，theater/history tab 已分流 Flutter；
`setMode()` 的 `Setting.isFlutterB22Enabled()` 门在 §4 拆除后改为无条件分流）、
`ScanActivity`（无 Flutter 路由；入口仍在：`RemoteTrustDialog.java:454` 反射、`CastDialog.java:136`、
`SyncDialog.java:138`）、`HkListActivity`（`open(Context, title)`，`:46`，hk:// 独立页）、
`HkDetailActivity`、`HkSearchActivity`（`open()`，`:45/:50`）、`LocalSourceDialogActivity`
（透明宿主 + `.local_source_dialog` 隐式 intent，`LabActivity.openLocalSourceDialog()` 仍用）。

#### 留（main Activity，17 个，均有 TV 或共享用途）

`AppBrandingActivity`（leanback `SettingPersonalActivity.java:264` 使用）、`PluginManagerActivity`
（leanback `SettingEnhanceActivity.java:111`）、`ViewingReportActivity`（leanback `HistoryActivity.java:76`）、
`TmdbPersonActivity`（`TmdbDetailActivity.java:12805`、`WebHomeThemeBridge.java:335`）、`FollowingActivity`
（leanback `HomeActivity.java:1207/1366`；mobile 入口改线见 §6 R7）、`LabActivity`、`LabDetailActivity`、
`LabOutputActivity`、`LabTerminalActivity`（Lab 终端/输出留原生；mobile 列表/详情入口已分流 B21）、
`TmdbDetailActivity`、`PlaybackActivity`、`PlaybackRecoveryActivity`、`CrashActivity`、
`WebReaderActivity`、`CatWebActivity`、`GameWebActivity`、`WebThemeDetailActivity`、`AudioActivity`。
注：这些类在 mobile 上删除原生界面后大多不可达，但必须保留编译与声明（TV 用）；mobile 入口一律改线到 Flutter 路由。

#### 留（基础类与共享件）

- `ui/base/BaseActivity.java`、`BaseFragment.java`：main 源集没有 `BaseActivity`/`BaseFragment`，
  所有 main activity 都继承 flavor 的 `BaseActivity`（EventBus 注册、edge-to-edge、动态取色、主题绑定、
  `AudioMiniPlayer`、返回处理）；kept 的 Hk fragment 与 `EpisodeFragment` 继承 `BaseFragment`。
- `BaseEpisodeHolder`、`BaseVodHolder`、`ViewType`。
- Holder（5 个全留）：`EpisodeGridHolder`、`EpisodeHoriHolder`（经 `EpisodeAdapter`）、`VodListHolder`、
  `VodOvalHolder`、`VodRectHolder`（经 `VodAdapter`）。
- Adapter（26 个全留）：`AdRuleAdapter`、`ChannelAdapter`、`ConfigAdapter`、`DeviceAdapter`、`EpgDataAdapter`、
  `EpisodeAdapter`、`EpisodeGroupAdapter`、`EpisodeStillAdapter`、`FilterAdapter`（`TypeFilterDialog` 用）、
  `FlagAdapter`、`GroupAdapter`、`LiveAdapter`、`LiveEpgAdapter`、`LiveLineAdapter`、`LiveProgramAdapter`、
  `LiveProgramDateAdapter`、`ParseAdapter`、`QualityAdapter`、`QuickAdapter`、
  `RecommendationFeedbackAdapter`、`RestoreAdapter`、`SiteAdapter`、`SiteGroupAdapter`、`TypeAdapter`
  （`TypeFilterDialog` + `HkListFragment` + `B11FabHostFragment` + `TypeDialogAdapter`）、
  `TypeDialogAdapter`、`ValueAdapter`、`VodAdapter`（`HkListFragment` + `HkSearchFragment` + `HkListHelper` + holders）。
- 自定义 View（留）：`FixedNestedScrollView`（`activity_video.xml` 各变体）、`CustomKeyDown`（Live + Video）、
  `CustomScroller`（`HkListHelper`/`HkListFragment`/`HkSearchFragment`）、`CustomTextListener`（dialogs）。
- 其他 mobile 文件（留）：`EpisodeGridLayoutPolicy`（`VideoActivity`）、`Biometric`（`LiveActivity`）、
  `MobileWindow`（`Product` + impl）、`PiP`（Live/Video/TmdbDetail）、`Timer`（Video/`TimerDialog`/`ControlDialog`）、
  `HkDetailAdapter`、`HkDialogs`（Hk 引擎 + `VideoActivity` + `HkDetailFragment`）、`SiteDialogTheme`、
  `B11FabHostFragment`（删除后 `TypeFilterDialog` 的宿主，见 §6）、`ShortcutReceiver`（仅 toast）、
  `HkWarmup`（mobile-only，启动预热，见 §2 M7）、`Product.java`、Hk 三 fragment（`HkListFragment`、
  `HkDetailFragment`、`HkSearchFragment`）、`EpisodeFragment`（dialog 内嵌）、DLNACast 系列、`CastVideo`。
- Dialog（44 个 mobile `*Dialog` 全留，硬约束）：`TypeFilterDialog` 的 host-cast 模式由保留的
  `B11FabHostFragment` 承接。
- 数据层 / 配置层 / services / Hk 引擎 / 爬虫 / Lab runtime：全部留。
- 资源（留）：`menu_nav.xml`、`menu_hk_list.xml`、全部 dialog 布局、全部 `view_control_*` / `view_widget_*` /
  `view_empty` / `view_progress` / `view_scanner`、壁纸 drawable（`drawable-nodpi/wallpaper_*.webp`，
  Flutter 首页 `resolveWallpaper` 与 `CustomWallView.load()` 共用）、`mobile_startup.png` /
  `mobile_startup_window_background.xml`（splash）、`ic_launcher_history`（alias 图标）、全部 values 文件
  （`Theme.Splash` 双 flavor 都有定义，mobile `styles.xml:21`、leanback `styles.xml:55`）、
  `searchable.xml`（随 SEARCH filter 移到 `C1ScreenActivity`，文件本身在 main res 不动）。

#### 留（Flutter 侧）

全部 23 个 batch 的 Dart 页、Pigeon 契约（仅拆除 flag 方法，见 §4）、单引擎 `b8_c1_engine`、
`C1ScreenActivity` 及其全部 `HostApiImpl`。`C0HostApiImpl.java` 与 `c0_messages.dart` 经核实已是死代码
（零注册、零 Dart 引用），但属于 Flutter 侧清理，不计入本次删除清单，列为 §5 阶段 6 的可选清理。

### 1.4 移：行为 / 职责清单（15 项）

| # | 职责（现属 `HomeActivity`） | 移往 | 依据 / 要点 |
|---|---|---|---|
| M1 | 配置加载序列：`VodConfig.load` + `LiveConfig.init().load()` + `WallConfig.init()`，含回调三态（success / noConfig / error） | `C1ScreenActivity.onCreate`，进程只跑一次 | `HomeActivity.java:220`（`loadConfig`）；回调内的 `setNavigation`/`checkAction` 随原生导航消失，`Notify.show(msg)` 保留；只跑一次的守卫见 §2.1 |
| M2 | 退出清理：`LiveConfig.clear()`、`VodConfig.clear("mobile-home-destroy")`、`OkHttp.get().clear()`、`Source.get().exit()`、`Server.get().stop()` | `C1ScreenActivity.onDestroy`，加守卫 | `HomeActivity.java:755-766`；`Server.get().stop()` 全仓库仅此一处调用（`ManageService.stopManage()` 是另一个方法）；守卫见 §2.2 |
| M3 | `FollowingScheduler.ensurePeriodic()` + `enqueueDueNow()` | `C1ScreenActivity.onCreate` | 幂等，`FollowingActivity`/`TmdbDetailActivity`/B5/B14 impl 也有调用，移动安全 |
| M4 | 权限链：`PermissionUtil.requestFile(this, … → requestNotify)` | `C1ScreenActivity.onCreate`（首次 home 启动） | `PermissionUtil` 在 main，可直接用；B15 已在用点桥接权限，启动时请求是否保留见 §2.1 决策点 |
| M5 | Intent 处理：`EXTRA_NAV_POSITION`、`ACTION_SEND`、`ACTION_VIEW`、`ACTION_SEARCH` | `C1ScreenActivity.onCreate` + `onNewIntent` | `HomeActivity.java:174`（`handleAction`）；映射表见 §2.3 |
| M6 | EventBus 订阅：`ConfigEvent`、`ServerEvent` | `C1ScreenActivity` 手动注册/注销 | `C1ScreenActivity` 直接继承 `AppCompatActivity`，不是 `BaseActivity` 子类，无自动注册；`onConfigEvent` 的 VOD/COMMON/BOOT 三分支与 `onServerEvent` 的 PUSH/SEARCH 分支逐条搬（见 §2.4） |
| M7 | `HkWarmup.warmUpOnStartup()` | `C1ScreenActivity.onCreate`，反射调用 | `HomeActivity.onCreate`；`HkWarmup` 是 mobile-only 类，main 源集不能直接引用——用 FQCN 字符串反射 + ProGuard keep（B20 前例）；不要走 `Product` 钩子（会动 leanback `Product.java`，违反 TV 约束） |
| M8 | 崩溃恢复：`CrashRestartMode.consume()` + `PreviousProcessCrashRecovery.checkBeforeConfig` / `release` | 随 M1/M2 一起搬 | `initialize` 已在 `App.attachBaseContext`（不动）；`checkBeforeConfig` 在 `checkAction` 与 `initConfig` 两处设门 |
| M9 | 自动备份：`AutoBackupPolicy.shouldRun(…)` → `AppDatabase.autoBackup()` | `C1ScreenActivity.onDestroy`，同 M2 守卫 | `shouldRun` 自带 `isFinishing` / `isChangingConfigurations` 参数，与守卫语义一致 |
| M10 | Splash 主题：manifest `Theme.Splash` + `onCreate` 内 `setTheme(R.style.Theme_App)` | `C1ScreenActivity` manifest 声明 + `onCreate` | 双 flavor 都有 `Theme.Splash` 定义，`R.style.Theme_Splash` 双 flavor 可编译；`activity_b8_c1.xml` 在 main res |
| M11 | 桌面快捷方式（长按 live 项钉选） | 已在 B11：`B11HostApiImpl.longPressBottomBarItem`（`:1346` 一带，`ShortcutReceiver` 反射） | 实施时核对 `ShortcutInfoCompat` 构造与 `HomeActivity.addShortcut()`（`:309`，绑定于 `:166` 与 `:397`）一致 |
| M12 | 返回 / 退出语义：`onBackInvoked` 链（chrome → 导航恢复 → 设置子页 → vod → `PlaybackService` 运行时 `moveToBackground`，否则 finish） | `C1ScreenActivity` + Dart 根路由返回信号 | 见 §2.5（根返回判定方式为设计决策点，D1） |
| M13 | 搜索路由构造：`SearchActivity.start/direct/directFromHistory` 的参数编码（keyword/siteKey/pic/wallPic/hr*） | 新建 flavor 安全路由器（§5 阶段 1） | `SearchActivity.java:42-130`；编码逻辑原样搬，mobile 走 `ROUTE_SEARCH`/`ROUTE_COLLECT`，TV 走反射调原生 `SearchActivity` |
| M14 | `nav_position`（0–10）→ 路由映射 | 路由器 / `C1ScreenActivity` intent 处理 | 映射表见 §2.3：0→`/home`，1→`/settings`，2→`/settings?member=player`，3→enhance，4→danmaku，5→personal，6→subtitle，7→tmdb，8→ai，9→ad，10→search |
| M15 | 设置搜索行直达：`SettingFragment.requestRow` 写 `sPendingRow` → `B7HostApiImpl.consumePendingRow`（`:165-180`）读 | 路由 query 或 host 静态量 | 写方随 `SettingFragment` 消失；B5 的反射写方（`B5HostApiImpl.java:490`）同步改线；`consumePendingRow` 失败时无害，可保留过渡 |

**随原生界面消失、无需迁移（4 项）：**液态玻璃导航状态（`setupGlassNav`/`applyBottomBar`/`syncGlassState`/`GlassBackdropCapture`，
Flutter 侧自有胶囊栏）；`StateEvent` 投递（唯一订阅者是 `VodFragment`，leanback 无订阅，投递可直接删）；
`ThemeController.applyWallpaperBlur` 调用（仅 `HomeActivity.java:592-593` 调用，方法本身保留，调用消失）；
`refreshNavigation()`（仅 `SettingEnhanceFragment.java:335` 调用，随文件消失；`B4HostApiImpl.java:253` 已注明故意跳过）。

---

## 2. 生命周期迁移设计

### 2.1 启动序列（`C1ScreenActivity.onCreate`）

现状锚点：`App.onCreate` 已在做应用级启动（`Setting.applyLanguage`、`ThemeController.applyNightModeToApp`、
`AppBranding.applyLauncherIcon`、`Notify.createChannel`、`ProxySetting.apply`、activity 回调注册、
1200ms 延迟的 `startBackgroundServicesNow` 内 `Server.get().start()`、warmers、`PluginBootstrap`、
30s 的 `CacheScheduler`）。**关键不对称：Server 由 `App` 启动、由 `HomeActivity.onDestroy` 停止**——
停的一侧必须随 home  teardown 一起搬（M2），启的一侧不动。

`C1ScreenActivity.onCreate` 新顺序（在现有 `ensureFlutterEngine()` / `super.onCreate()` / `sLiveActivities.add`
之后，`assertRouteIfNeeded` 机制不动）：

1. `setTheme(R.style.Theme_App)`（M10；manifest 声明 `Theme.Splash`，与 `HomeActivity` 同款）。
2. `HkWarmup.warmUpOnStartup()` 反射调用（M7；失败静默，与现行为一致——`HomeActivity.onCreate` 内调用无 try，
   但反射失败必须吞掉，否则 TV 编译/运行风险；mobile 上类一定存在）。
3. 权限链（M4）：`PermissionUtil.requestFile(this, … requestNotify …)`。**决策点**：B15 已在用点桥接权限，
   启动时请求可删；但 `AutoBackupPolicy` 的备份行为依赖文件访问状态，建议保留启动请求以求行为一致，
   实施时由中国主人定（§7 D2）。
4. `FollowingScheduler.ensurePeriodic()` + `enqueueDueNow()`（M3；幂等，每次 `onCreate` 调无害，但建议与 M1 共用 once 守卫）。
5. 配置加载（M1，once 守卫）：静态 `AtomicBoolean sConfigLoadStarted` + `VodConfig`/`LiveConfig` 已加载状态判定
   （`LiveConfig` 有 `hasLoadedLives()` 前例；`VodConfig` 的等价谓词实施时确认，见 §7 D3）。
   守卫条件：`CrashRestartMode.consume()` 先行；`PreviousProcessCrashRecovery.checkBeforeConfig(this, …)` 设门（M8）；
   然后 `VodConfig.load(callback)` + `LiveConfig.init().load()` + `WallConfig.init()`。
   回调保留：error 时 `Notify.show(msg)`；success/noConfig 时的 `setNavigation`/`checkAction`/`StateEvent` 投递删除
   （导航与订阅者都不存在了；Dart 首页经 Pigeon 自取数据，B11 已有 reload 逻辑 `B11HostApiImpl.java:1215`）。
   **旋转不重载**：once 守卫 + §2.2 的 `configChanges` 建议双保险。
6. EventBus 注册（M6）：`onCreate` 注册、`onDestroy` 注销（手动，`C1ScreenActivity` 非 `BaseActivity` 子类）。
7. Intent 处理（M5）：`savedInstanceState == null` 时处理当前 intent（沿用 `HomeActivity.java:143` 的条件），
   映射见 §2.3；处理完移除 `nav_position` extra（沿用 `:176`）。

`Application` 不新增职责：`App` 的启动序列已完整，`HomeActivity` 的启动职责全部归入 `C1ScreenActivity`，
因为其中多项需要 `Activity`（权限、intent、EventBus 订阅的生命周期）。`App.stopBackgroundServices()` 保留
（仅 leanback `AppExitCoordinator.java:27` 调用，TV 退出路径）。

### 2.2 退出清理（`C1ScreenActivity.onDestroy`）

`HomeActivity.onDestroy` 是无条件的——安全只因其 manifest `configChanges` 含 orientation/screenSize，
旋转永不销毁。`C1ScreenActivity` 现状 `configChanges="keyboard|keyboardHidden|uiMode"`，旋转会销毁重建，
因此**清理必须加守卫**，与现有引擎销毁守卫同形（`C1ScreenActivity.java` onDestroy 内
`if (isFinishing() && sLiveActivities.isEmpty())`）：

```java
// 伪代码：与引擎销毁同一守卫块内，顺序与 HomeActivity.onDestroy 一致
if (isFinishing() && sLiveActivities.isEmpty()) {
    PreviousProcessCrashRecovery.release(this);   // M8
    LiveConfig.get().clear();                     // M2
    VodConfig.get().clear("mobile-home-destroy"); // M2（reason 字符串保留，便于日志追溯）
    if (AutoBackupPolicy.shouldRun(...)) AppDatabase.autoBackup(); // M9
    OkHttp.get().clear();                         // M2
    Source.get().exit();                          // M2（LiveActivity.java:2458 另有调用，直播退出路径，不动）
    Server.get().stop();                          // M2
    // …现有引擎销毁逻辑…
}
```

另两项 `HomeActivity.onDestroy` 内容：`glassCapture.stop()` 与 `mChrome.destroy()` 随玻璃/Chrome 消失，不搬。

**建议（设计决策 D4）：**给 `C1ScreenActivity` 的 manifest `configChanges` 加上
`screenSize|smallestScreenSize|screenLayout|orientation`（与原生 rotation-stable activity 同值，
mobile manifest `:23/:126/:131`），使旋转不再销毁重建，从根上消除“旋转误触发清理”的风险；
守卫仍保留（进程死亡恢复、配置变更之外的销毁路径仍需它）。Flutter 侧经 `MediaQuery` 响应尺寸变化，
网格宽度已有 D4 已知偏差（`docs/b8-home-sticky-tabs.md`），旋转验收见 §6 R3。

**栈语义核对（scheme-B 栈）：**Flutter home → 原生 drama 页 → Flutter drama 页时，栈内有两个 C1 activity；
顶层 Flutter drama 页 finish 时 `sLiveActivities` 非空，不清理。用户从 Flutter home 一路返回、栈空时才清理，
与原生“home finish 即清理”一致。`VideoActivity`/`LiveActivity` 的 `isTaskRoot` 分支改线后指向 C1 `ROUTE_HOME`
（见 §5 阶段 1），不会绕过清理。

### 2.3 Intent / deep-link 处理映射

`HomeActivity.handleAction`（`:174`）的四路输入，搬入 `C1ScreenActivity`（`onCreate` + `onNewIntent`，见 §3）：

| 输入 | 现行为 | 迁移后 |
|---|---|---|
| extra `nav_position`（`HomeActivity.EXTRA_NAV_POSITION = "nav_position"`，`:90`） | `change(position)` | 按 M14 映射为路由，经 relaunch 机制断言（`sendRouteToDart` + `assertRouteIfNeeded`）；`LiveActivity.java:431` 的底栏返回（position 0/1）与 `VideoActivity` 的同类调用在阶段 1 直接改发带 `EXTRA_ROUTE` 的 intent |
| `ACTION_SEND`（text/plain） | `VideoActivity.push(text)` | 不变（`VideoActivity.push` 留原生） |
| `ACTION_VIEW` + data | `requestFile` → `checkType`：`.m3u`/text/plain → `loadLive`，否则 `VideoActivity.push(uri)` | 不变，逻辑原样搬；`FileChooser.getPathFromUri` 等工具在 main，可直接用 |
| `ACTION_SEARCH` | `SearchActivity.start(keyword)` | `ROUTE_SEARCH?keyword=…`（经路由器，§5 阶段 1） |
| launcher / alias（MAIN/LAUNCHER，无 extra） | 显示原生 home | `ROUTE_HOME`（见 §3 默认路由） |

`ServerEvent` 的 PUSH/SEARCH 分支（`VideoActivity.push` / `SearchActivity.start`）同表改线（M6）。

### 2.4 EventBus 订阅迁移

`HomeActivity` 订阅 `ConfigEvent` + `ServerEvent`：

- `onConfigEvent`：VOD → `RefreshEvent.home()` + `setNavigation()`；COMMON → `setNavigation()` + `applyBottomBar()`；
  BOOT → `LiveActivity.start`。迁移后：VOD/COMMON 的导航刷新删除（Flutter 侧经 Pigeon 刷新；窗口形状变化时
  Dart 经 `MediaQuery` 重建）；**BOOT 分支保留**（mobile 开机进直播路径；leanback 另有 `BootReceiver`，TV 不动）。
- `onServerEvent`：PUSH → `VideoActivity.push`；SEARCH → 路由器 search。保留。
- `VodFragment` 的订阅（Config/Refresh/State/Cast）、`SettingFragment` 的订阅（Config/Refresh）、
  `HistoryActivity`/`KeepActivity` 的订阅（Refresh/Config）随文件消失；Flutter 侧 resume 时重取快照已覆盖
  （C2/B2 的 resume-refetch 机制）。`CustomWallView`（main）的 `ConfigEvent` 订阅不受影响
  （使用者：`AudioActivity`、`FollowingActivity`、mobile `BaseActivity`、Lab 布局，均保留）。

### 2.5 返回 / 退出语义（设计决策点 D1）

`HomeActivity.onBackInvoked` 链（chrome → 导航恢复 → returnVodFromEnhance → 设置子页 → 设置 → vod →
`canBack(0)` → `PlaybackService` 运行时 `moveToBackground`，否则 super）在 Flutter 侧的对应物：

- 栈内导航（设置子页 → 设置 → 首页）由 Dart `Navigator` 承担，已有行为。
- **根返回**（Dart 栈已空时的系统返回）：现 `C1ScreenActivity.onBackPressed` 只转发给 `flutterFragment`；
  FlutterFragment 在 Dart 不处理时 finish activity。需确认并验收两点：(1) `PlaybackService` 运行时应
  `moveToBackground` 而不是 finish（否则后台播放被 teardown 杀死——M2 清理会停 Server/清配置）；
  (2) 根返回是否应 finish（触发 M2 清理）还是 `moveToBackground`（保留进程，下次进 launcher 时 `onNewIntent` 回 `/home`）。
  建议：复用 relaunch 机制的 raw MethodChannel 加一个 Dart→host 的“根返回”信号，host 按 `PlaybackService` 运行状态
  决定 `moveToBackground` / finish；**实施前需中国主人确认期望的根返回行为**（§7 D1）。
  在 D1 未定前，阶段 3 按“`PlaybackService` 运行时后台化，否则 finish”实现（与原生 `onBackInvoked` 末段一致）。

---

## 3. Launcher 入口改向设计

### 3.1 Manifest 改动（mobile `AndroidManifest.xml`，main manifest 不动）

`C1ScreenActivity` 现状：仅 mobile manifest 声明，`exported=false`，无 launcher 入口，无 `launchMode`，
`configChanges="keyboard|keyboardHidden|uiMode"`，`windowSoftInputMode="adjustResize"`。
改动（阶段 3，一次性）：

1. `android:exported="true"`（launcher + VIEW/SEND/SEARCH filter 要求）。
2. `android:launchMode="singleTop"`（与 `HomeActivity` 一致；`onNewIntent` 设计见 §3.3）。
3. `android:theme="@style/Theme.Splash"` + `onCreate` 内 `setTheme(R.style.Theme_App)`（M10）。
4. `configChanges` 加 `screenSize|smallestScreenSize|screenLayout|orientation`（§2.2 D4；保留现有的
   `keyboard|keyboardHidden|uiMode`）。
5. intent-filter 整体从 `HomeActivity` 声明搬过来：SEARCH（+ `searchable` meta-data，`searchable.xml` 在 main res，
   label `app_name` + hint `search_keyword`）、SEND（text/plain）、VIEW（content/file：video/audio/text/torrent；
   smb/rtmp/rtsp/http/https：video/audio；ed2k/magnet/thunder/jianpian）。`HomeActivity` 声明删除。
6. `windowSoftInputMode="adjustResize"` 保留（B5 键盘/IME 观察项依赖它）。

### 3.2 Launcher alias 改向

两个 alias（`HomeActivityCurrent` enabled、`HomeActivityHistory` disabled）**保留组件名、图标、enabled 状态**，
只改 `android:targetActivity` 指向 `.flutter.C1ScreenActivity`。理由：

- 组件名是桌面图标的身份，改名会导致已钉选图标失效；`AppBranding.applyLauncherIcon`（`App.onCreate` 调用）
  以组件名派生 alias（`AppBranding.java:140/152` 现直接引用 `HomeActivity.class`），阶段 1 改为字符串常量
  `com.github.moyingshi.osc.tv.ui.activity.HomeActivityCurrent/History`——FQCN 双 flavor 相同，
  字符串常量对 TV 同样成立，`AppBranding` 不再引用被删类。
- alias 后缀常量 `ALIAS_SUFFIX_CURRENT="Current"` / `ALIAS_SUFFIX_HISTORY="History"`
  （`AppBranding.java:33-34`）不动；`B15HostApiImpl.java:279` 的 `launcherIntent` 用法随 alias 改向自动生效。
- `getLaunchIntentForPackage` 的调用方自动跟随改向：`PlaybackService.java:232`（播放通知）、
  `CrashActivity.java:229` 的主路径（其 `HomeActivity.class` fallback 在阶段 1 删除）。

### 3.3 默认路由与 `onNewIntent`

- **默认路由**：`C1ScreenActivity.onCreate` 现为 `mRoute = getIntent().getStringExtra(EXTRA_ROUTE)`，
  null 时默认 `ROUTE_ABOUT`（`:251-252`）。改向后 launcher/alias/最近任务 intent 均无 extra，
  默认值必须改为 `ROUTE_HOME`。安全：全部 49 处 `C1ScreenActivity.start(...)` 调用都传 route，
  无 extra 的唯一来源是 launcher 类 intent；进程死亡恢复时 `getIntent()` 仍是带 extra 的原始 intent。
- **`onNewIntent`**：现只转发给 `flutterFragment`（`:541-544`），不更新 `mRoute`、不断言。
  `singleTop` 后 launcher 二次点击与 `CLEAR_TOP|SINGLE_TOP` 重入（改线后的 goHome/openHomeAt）都走这里，
  必须：`setIntent(intent)`；若 intent 带 `EXTRA_ROUTE` → `mRoute` = 该路由；否则（MAIN/LAUNCHER）
  → `mRoute = ROUTE_HOME`；然后 `sendRouteToDart(mRoute)` + 经 `assertRouteIfNeeded()` 记录断言
  （复用 `onPostResume` 的既有机制与 `sAssertedRoute` / `sRouteOwnerToken`）；保留
  `flutterFragment.onNewIntent(intent)` 转发（插件 deep-link）。
- **`C1ScreenActivity.start()`**（`:243-245`）不动：普通 start + extra，start-then-finish 的 G2 语义由
  relaunch 机制承担（`engineReused` early hint + `onPostResume` 断言）。

### 3.4 其他入口核对

- 通知：`FollowingNotifier.java:47` → `FollowingActivity.intent(...)`（见 §6 R7，mobile 需改线）；
  `ManageService` → 浏览器 VIEW intent（不受影响）；`PlaybackService` → `getLaunchIntentForPackage`（自动跟随）；
  `PlaybackActivity.java:755` → 自身（不受影响）；`Notify` 其余为 toast/progress，无 content intent。
- 快捷方式/小部件：全仓库无 `AppWidgetProvider`、无 `shortcuts.xml`、无 widget-info XML；
  `ShortcutReceiver` 仅 toast，保留；钉选直播快捷方式（`ShortcutInfoCompat` → `LiveActivity`）不受影响。
- 隐式 intent 动作：`.hk_list`（`HkListActivity`，保留）、`.local_source_dialog`（保留）、`.hk_miniapp`
  （随 `HkMiniappActivity` 删除；`LabActivity.openMiniappManager` 与 `B21HostApiImpl.openLabMiniappManager`
  的隐式分支在阶段 2 删除，mobile 一律走 `ROUTE_HK_MANAGER`；`resolveActivity` 空检查使旧分支即使残留也无害）。

---

## 4. 总开关拆除清单

### 4.1 `Setting.java`（`Setting.java:416-655`）

- 删除：`isFlutterEnabled()`（`flutter_enabled`，默认 false）+ 23 个 alias getter
  （`isFlutterC1Enabled` / `isFlutterC2Enabled` / `isFlutterB2Enabled` … `isFlutterB22Enabled`，
  每个都是 `return isFlutterEnabled()`）。
- 删除：24 个 putter——master 写 `flutter_enabled`；23 个 per-batch putter 写的是已无人读的旧 key
  （注释自述 “Dead… Kept so the existing Pigeon flag methods keep compiling”）。
- 存储的 `flutter_enabled` 偏好值不做迁移、不删除（无害残留）。

### 4.2 Pigeon 契约与 HostApiImpl

- 删除 23 个契约（`b2_…_messages.dart` … `b22_…_messages.dart`）+ `c1_messages.dart:28-29` +
  `c2_messages.dart:22-23` 中的 `isB<N>Enabled` / `setB<N>Enabled` 方法；删除
  `b7_settings_home_messages.dart:29` 的 `setFlutterEnabled` 与 `B7SettingsInfo.flutterEnabled` 字段
  （`:292/:312`）；删除各 `HostApiImpl` 内对应的 get/set 对（如 `C1HostApiImpl.java:55/60`、
  `B11HostApiImpl.java:206/211`）；重新生成 Pigeon 代码（C0/C1/C2 契约冻结规则在拆除阶段例外——
  拆除本身就是契约变更，生成物与手写 impl 同阶段改）。
- 删除 `B9` 契约的 `submitSearch`（Dart 改为恒走 in-family B10 push，见 §4.3；host 的
  `SearchActivity.direct` 调用 `B9HostApiImpl.java:388` 随之消失）。
- 删除 `B7` 契约的 `openNativePage`（Dart 的 `'player'` case 与 `nativePage` 回退分支改为 in-family 导航，
  见 §4.3；`B7HostApiImpl.row()` 的 `memberFlutter` / `nativePage` 字段（`:188-198`）与
  `searchFlutter`（`:150`，取自 `isFlutterB5Enabled`）同步删除）。
- 删除 `B5`/`B7`/`B11` 的 `openHomeAt`（改线见 §5 阶段 1）与 `C2`/`B2` 的 `goHome`（同上）——
  或保留方法名但改为 C1 路由 intent，二选一在阶段 1 定（建议：方法删除，Dart 侧改调统一的 home 路由；
  Dart 调用点：`b2_keep_page.dart:269`、`c2_history_page.dart:211`）。

### 4.3 Dart 侧

- 4 处 live flag 读取改为无条件 Flutter 路径：`b9_search_page.dart:220`（`isB10Enabled`，恒走 in-family
  `B10CollectPage` push，`submitSearch` 回退分支删除）、`b12_home_block_a.dart:86/324`（`isB12Enabled`，
  恒加载 banner/continue）、`b13_home_block_b.dart:67`（`isB13Enabled`，恒加载 JS widgets）。
  其余 per-batch Pigeon flag 方法在 Dart 侧零调用，只删契约与 host 侧即可。
- B7 设置首页：删除 Flutter 侧开关行（`b7_settings_home_page.dart:142/178/334-335`，`setFlutterEnabled`
  调用与行定义）；`_openSearch`（`:158`，page 10 回退）、`:167`（`row.nativePage` 回退）、`:182`
  （`'player'` case 无条件 page 2）全部改为 in-family 导航；`searchFlutter` / `memberFlutter` 恒真，相关字段删除。
- 原生行：`SettingFragment` 整文件删除（§1.1），行定义 `:210`、显示 `:379`、处理 `:443`、切换 `:734-737`
  一并消失；`ROW_FLUTTER` 常量（`:120`）无其他使用者。

### 4.4 分流点改线（约 50 处，阶段 2 逐处改为无条件 C1 路由）

Native 侧（文件删除后自然消失，无需改）：`FolderActivity:40`、`HistoryActivity:198`、`HomeActivity`（6 处）、
`SearchActivity`（3 处）、`ShortDramaActivity:613`（文件保留，门删除，恒分流）、`WidgetBrowseActivity:69`、
`SearchFragment:236`、`SettingEnhanceFragment`（2 处）、`SettingFragment`（多处）、`VodFragment`（2 处）、
`LabActivity:339`（文件保留，门删除）、`AppBrandingActivity:31`、`TmdbPersonActivity:109`、
`FileChooser:95`（路由器形态，见 §5 阶段 1）。
Host impl 内部分流（阶段 2 改为无条件 C1）：B4（`:308` Lab、`:322` 本地阅读、`:338` 插件管理）、
B5（`:454-475`）、B7（`:728` Lab、`:918` About、`:929` Subtitle、`:934/:950` openHomeAt 调用）、
B11（`:886/:896` search、`:920` history、`:936` keep、`:950/:1300` openHomeAt 调用、`:1274/:1285/:1296`
following/lab/setting 菜单项）、B13（`:264` widget item、`:278-290` 已用反射的 `openWidgetMore` 分支——
反射分支删除，恒走 Flutter）、B21（`:1068` hk-manager）、C2（`:177` report）。
`B11HostApiImpl` 的 hk 分支（`openHkSearch` 反射）与 `isMiniapp` 判定保留（见 §6 R2）。

### 4.5 默认行为

拆除后不存在“开关”概念：mobile 上所有已迁移屏恒走 Flutter；无原生回退。TV 不受影响
（TV 从不走这些分流，`Util.isMobile()` 门与 flag 门一并消失后，TV 路径由路由器反射分支承担）。

---

## 5. 分阶段实施顺序

每阶段：一个 task-guard 会话 + 一个本地提交 + CI（b8-c0.yml）+ 真机验收门。每阶段都必须双 flavor 可编译；
**D5 已确认（2026-11-18，阶段 1 收尾）**：`b8-c0.yml` 同时编 leanback 变体
（`assembleLeanbackArm64_v8aRelease` 与 `assembleLeanbackArmeabi_v7aRelease`），双 flavor 覆盖已有；
阶段 1 的 CI 即为第一次双 flavor 真实编译检查。

| 阶段 | 内容 | 关键产出 / 验收 |
|---|---|---|
| **阶段 1：路由器 + 改线**（✅ 已完成 2026-11-18，见 §5.1） | 新建 main 源集 flavor 安全路由器（建议名 `FlutterNav`，`flutter` 包内；mobile 分支直接引用 `C1ScreenActivity`，TV 分支用 FQCN 字符串反射，`Util.isMobile()` 门控）。改线全部 main 源集对被删类的直接引用（行为不变，flag 门保留）：`AppBranding.java:140/152`（alias 派生改字符串常量）、`CrashActivity.java:229`（删 `HomeActivity.class` fallback，主路径 `getLaunchIntentForPackage` 已自动跟随）、`TmdbDetailActivity.java:9806`（`finishPlaybackToHome` 改 launcher-intent 形态）、`B5:513`/`B7:956`/`B11:1383` 的 `openHomeAt`（改为 C1 路由 intent，`nav_position`→路由映射 M14）、`C2:196`/`B2:269` 的 `goHome`（同上）、`HomeWebBridge.java:434/455` 与 `WebHomeThemeBridge.java:443`（search/keep 路由器化）、`TmdbNavigation.java:112`、`HistoryResumeCoordinator.java:88/150`（`directFromHistory` 路由器化）、`TmdbPersonActivity.java:278/288`（`openWork`）、`TmdbDetailActivity.java:12608/12798/12823`（`direct` 调用）、`B9HostApiImpl.java:388`（`direct` 调用，阶段 2 随 `submitSearch` 删除——阶段 1 先改路由器形态）、`FileChooser.java:95`（mobile 恒 C1，TV 反射 `FileActivity`）、`VideoActivity.java:4293`（`onSearchGlobal` 改 C1 路由）、`LiveActivity.java:431`（底栏返回改 C1 路由）与 `:676`（`isTaskRoot` 改 C1 `ROUTE_HOME`）、`VideoActivity.java:10612/12120`（`isTaskRoot` 同上）、`B11:920/936` 的 `HistoryActivity.start`/`KeepActivity.start` 回退分支（改为路由器调用）。`SearchActivity.direct` 的 `!(activity instanceof C1ScreenActivity)` G2 例外随路由器消失：C1 调用方一律走 in-family push（Dart）或 start-then-finish（host），relaunch 机制承担断言（见 §6 R4） | 零删除、零行为变化；mobile + leanback 双编译通过；真机回归：搜索/收藏/历史/设置跳转、WebHome 主题内 search/keep、详情页搜同名、直播底栏返回 |
| **阶段 2：总开关拆除**（✅ 已完成 2026-11-18，见 §5.2） | §4 全部内容：`Setting.java` 24 getter + 24 putter、Pigeon 契约 regen、Dart 4 处读取 + B7 开关行 + `openNativePage`/`submitSearch` 删除、全部 impl 内分流改为无条件 C1、`sPendingRow` 改线（M15）、`ShortDramaActivity.setMode` 门删除、`LabActivity.openMiniappManager` 门删除、`AppBrandingActivity:31`/`TmdbPersonActivity:109` 门删除、`b8_c0_strings.xml` 删除 | 行为：mobile 恒 Flutter；真机验收：开关消失、每屏直达、B9 提交直达 B10、B7 player 行 in-family、短剧 tab 恒分流 |
| **阶段 3：生命周期 + launcher 迁移** | §2 + §3 全部内容：`C1ScreenActivity` 承担 M1–M10/M12、manifest 改向（exported/singleTop/Theme.Splash/configChanges/全部 intent-filter + searchable）、alias `targetActivity` 改向、默认路由 `ROUTE_HOME`、`onNewIntent` 路由断言。`HomeActivity` 文件与声明**本阶段不删**（仅不再是 launcher、不可达），作为回退垫 | 真机验收（关键门）：冷启动、回跳消失、launcher 二次点击回首页、SEND/VIEW/SEARCH intent、旋转、退出清理（Server 停、配置清）、开机 BOOT 进直播、根返回行为（D1） |
| **阶段 4：代码删除** | §1.1 的 48 个文件 + manifest 7 个 activity 声明删除。建议拆两个提交：4a activities + fragments + SettingSearchAdapter（24 个）+ manifest；4b adapters + glass + 4 个 helper（24 个）。**前置条件**：§6 R1 的 WebHome 决策已定；如选“保留回退壳”，回退壳在本阶段前另立阶段建成 | 双编译通过；真机全量回归（§7 验收清单） |
| **阶段 5：资源删除** | §1.2 的 62 个资源文件（布局 43 + 菜单 8 + drawable 10 + strings 文件 1）；`shape_search_hot_word.xml` 按条件执行 | 编译通过（binding 引用已随阶段 4 消失）；APK 体积对照 |
| **阶段 6（可选）：死代码清理** | `C0HostApiImpl.java` + `c0_messages.dart` + 生成物（零注册、零 Dart 引用，已核实）；`ThemeController.applyWallpaperBlur` 方法（调用已消失，方法可留可删）；Lint `UnusedResources` 扫 drawable/strings 孤儿 | 与删除 program 无关，可独立排期 |

### 5.1 阶段 1 实施记录（2026-11-18，已完成）

- **产出**：新建 `app/src/main/java/com/github/moyingshi/osc/tv/flutter/FlutterNav.java`
  （flavor 安全路由器；mobile 分支直接引用 `C1ScreenActivity`，TV 分支用 FQCN 字符串反射，
  `Util.isMobile()` 门控）+ 改线 **43 处调用点**，覆盖 24 个文件（`AppBranding` 2、`CrashActivity` 1、
  `TmdbDetailActivity` 4、`TmdbNavigation` 1、`HistoryResumeCoordinator` 2、`TmdbPersonActivity` 2、
  `HomeWebBridge` 3、`WebHomeThemeBridge` 1、`FileChooser` 1、`FollowingNotifier` 1、`B2` 2、`B4` 1、
  `B5` 1、`B7` 1、`B8` 2、`B9` 1、`B11` 5、`B12` 1、`B13` 1、`B17` 3、`B18` 1、`C2` 1、
  mobile `VideoActivity` 3、mobile `LiveActivity` 2）。§5 阶段 1 行列出的全部调用点均已改线，
  另补改了行内未单列的同类调用（`B8:278/304`、`B12:217`、`B13:264`、`B17:176/182/193`、`B18:286` 的
  `SearchActivity.start/direct`）。零删除、零行为变化：flag 门全部保留，总开关未动，
  `HomeActivity` / `VodFragment` 未动，播放器 / 直播 / 弹窗 / 数据层 / TV 未动。
- **静态核查（本机无 SDK，CI 为第一次真实编译检查）**：
  `grep -rn "HomeActivity|SearchActivity|HistoryActivity|KeepActivity|FileActivity" app/src/main/java`
  ——除注释外仅剩 `FlutterNav.java` 的 FQCN 字符串常量与 2 处既有字符串反射
  （`B14:362`、`FollowingActivity:530`）；48 个被删类全名核查同理，仅剩注释与既有反射点
  （`B10:111/742` FolderActivity、`B13:284` WidgetBrowseActivity、`B5:488` / `B7:86` SettingFragment）。
  路由编码与原生 `SearchActivity` 逐字核对一致（`start(Activity)` → `""` 关键字、
  `directFromHistory` 2/3 参数形态与原生重载一致）；全部 C1 路由常量存在。
- **偏差**：
  - **CrashActivity**：未按字面“删 fallback”，改为 `FlutterNav.homeIntent(this)`。空 intent 进
    `restartApplicationWithIntent` 有 NPE 风险（crash 处理器内，无法本地验证），保留路由器形态更安全。
  - **D7**：mobile 通知落地 `/following` 时不拼 identityKey query（Dart 只注册了字面路由）。
  - **D10**：保留 `openHomeAt` / `goHome` 方法名，改实现为 C1 路由 intent，Dart 零改动。
  - **D5**：已确认，`b8-c0.yml` 覆盖 leanback 编译（见 §7 D5）。
  - **D9**：已复核，无新增 mobile 宿主 fragment 需求。
  - 任务简报写 `--mode architecture`，但 task-guard 脚本无此模式，实际以 `--mode standard` 启动
    （会话 `b8-delete-p1-router`）。
- **遗留（非阶段 1 阻塞，阶段 2/4 前处理）**：字符串反射点 `B14:362`、`FollowingActivity:530`、
  `B10:111/742`（FolderActivity）、`B13:273-290`（WidgetBrowseActivity）——阶段 4 删除对应类后
  会在运行时失效（ClassNotFoundException 被捕获后 toast/静默），其中 B13 分支计划已定阶段 2 处理。
- **待验**：推送后 CI（b8-c0.yml）跑通即为双 flavor 首次真实编译检查；真机回归按阶段 1 行
  （搜索/收藏/历史/设置跳转、WebHome 主题内 search/keep、详情页搜同名、直播底栏返回）。

### 5.2 阶段 2 实施记录（2026-11-18，已完成）

- **产出**：总开关彻底拆除，mobile 恒走 Flutter。`Setting.java` 删 24 getter + 24 putter
  （239 行，含注释）；23 个 Pigeon 契约各删 flag 方法对（B7 另删 `setFlutterEnabled`、`openNativePage`、
  `B7SettingsHome.searchFlutter`/`flutterEnabled`、`B7SettingsRow.memberFlutter`/`nativePage`；
  B9 另删 `submitSearch`；B5 契约只改注释）；全部 23 个 HostApiImpl 的 is/set 对删除，B4/B5/B7/B9/
  B11/B13/B21/C2 的分流改为无条件 C1（TV 分支保留：`Util.isMobile()` / `!Util.isLeanback()` 门与
  反射回退）；B7 `row()` 签名去掉 `memberFlutter`/`nativePage`（26 处调用点同步），快照删
  `flutter` 行；B9 删 `submitSearch` 连带死代码 `addRecord` + `RECORD_LIMIT`；Dart：B7 删开关行/
  `_setFlutterEnabled`/`'flutter'`/`'player'` case（player 行走通用 member 分支 in-family）、
  `_openSearch` 恒 `pushNamed('/search')`，B9 `_submit` 恒走 in-family B10 push（删 `isB10Enabled`
  读与 `submitSearch` 回退），B12 两处 + B13 一处 `_initFlagAndLoad` 删除、恒加载；
  mobile 门：`ShortDramaActivity.setMode` 恒分流、`LabActivity.openMiniappManager` 恒 Flutter、
  `AppBrandingActivity:31`/`TmdbPersonActivity:109` 去 flag（TV 门保留）、`HomeActivity` 6 处/
  `VodFragment` 2 处/`SettingFragment`（删 `ROW_FLUTTER` 行 + `setFlutterEnabled` + 10 处门）/
  `FolderActivity`/`HistoryActivity`/`SearchActivity`（3 处，G2 的 `!(activity instanceof
  C1ScreenActivity)` 例外保留）/`WidgetBrowseActivity`（原生 Intent 回退删除）/`SearchFragment`/
  `SettingEnhanceFragment`（2 处）的 flag 条件剥离；M15 落地为 `B7HostApiImpl.setPendingRowId`
  host 静态（见偏差 P2-3）；`b8_c0_strings.xml` 删除（3 个字符串零引用已核实）。
  播放器 / 直播 / 弹窗 / 数据层 / TV 未动；`HomeActivity` 与 `VodFragment` 只动了 flag 条件，
  生命周期与删除工作仍在阶段 3/4。
- **静态核查（本机无 SDK，CI 为第一次真实编译检查）**：
  `grep -rn "isFlutter[A-Za-z0-9]*Enabled|putFlutter" --include=*.java app/src` ——仅剩
  B5 有意保留的 `isFlutterDestination`/`flutterAvailable`（见偏差 P2-4）与阶段 6 待清的
  `C0HostApiImpl` 死代码；`grep -rn "isB[0-9]*Enabled|isC[0-9]*Enabled|submitSearch|
  openNativePage|memberFlutter|nativePage|searchFlutter|flutterEnabled" flutter_module/lib/`
  ——零命中；`grep -rn "b8_c0_launcher_label|b8_flutter_title|b8_flutter_sub" app/src` ——零命中；
  `git diff` 复查仅 66 个 scope 文件（含计划文档本体与已删 strings 文件），无超范围改动。
- **偏差**：
  - **P2-1**：§4.4 称 native 侧“文件删除后自然消失，无需改”——不成立。阶段 2 删 `Setting` 的
    24 个 getter 后，阶段 4 文件（`HomeActivity`、`VodFragment`、`SettingFragment`、
    `FolderActivity`、`HistoryActivity`、`SearchActivity`、`WidgetBrowseActivity`、
    `SearchFragment`、`SettingEnhanceFragment`）的 flag 条件引用会编译失败，因此阶段 2 一并
    剥离了这些文件的 flag 条件，改为 flag-on（Flutter）分支；`HomeActivity`/`VodFragment`
    只动 flag 条件，不做生命周期/删除工作（仍在阶段 3/4）。
  - **P2-2**：§4.2 称“删除 23 个契约”——不成立。每个 `b<N>_messages.dart` 文件还承载该 batch 的
    真实契约方法，只删了各文件的 flag 方法对（+ 注释），文件保留。
  - **P2-3**：M15 未用路由 query（§6 R12 的“路由 query 优先”不可行）：`"/settings?rowId="` 不是
    已注册路由（路由表精确匹配，`onGenerateRoute` 返回 null），且 Dart 从快照读 `pendingRowId`
    而非路由。改为 host 静态 `B7HostApiImpl.setPendingRowId(String)`，`getSettingsHome()` 消费
    一次；`consumePendingRow` 的 `SettingFragment.sPendingRow` 反射读保留为过渡回退。
  - **P2-4**：B5 的 `flutterAvailable` 字段与 `isFlutterDestination` 保留（Dart 仍需区分走
    family push 的页 3/4/5/7/8/9 与走 host 的页 2/6）；方法体简化为字面量 true/false，
    不再读 flag。
  - **P2-5**：B9 删 `submitSearch` 后其私有 `addRecord` + `RECORD_LIMIT` 成死代码，一并删除；
    B9 Dart 的 `_b10Api.isB10Enabled()` 读随 B10 契约 flag 对删除而删除，`_submit` 恒走 B10。
  - **P2-6**：§4.4 称 B13 `openWidgetMore` 的反射分支删除——未按字面执行：反射分支作为 TV 回退
    保留（只去 flag，`Util.isMobile()` 门保留）。该方法只在 mobile C1 引擎内被调用，TV 侧从未
    到达；删除会使 TV 侧彻底无路，保留无害。
- **待验**：推送后 CI（b8-c0.yml）跑通即为双 flavor 真实编译检查；真机验收按阶段 2 行
  （开关消失、每屏直达、B9 提交直达 B10、B7 player 行 in-family、短剧 tab 恒分流、
  设置内搜索 → 点结果 → 落在对应行）。

**回退策略**：阶段 1–2 完全可回退（单提交 revert）；阶段 3 回退 = manifest + `C1ScreenActivity` 改动 revert，
`HomeActivity` 仍在；阶段 4 起回退成本陡增，因此阶段 3 的真机验收门必须通过后才进阶段 4。

---

## 6. 风险清单

| # | 风险 | 影响 / 依据 | 缓解 / 决策 |
|---|---|---|---|
| R1 | **WebHome 首页无 Flutter 渲染器（P6 回退）** | `HomeWebController`（2263+ 行）首页渲染挂在 `VodFragment.mBinding.homeWeb`（`:544`）；Flutter module 无 WebView 插件（`docs/b8-full-migration-plan.md` §4.3：不新增 Dart 依赖）；`WebHomeChromeController`（234 行）绑死 `HomeActivity` 布局；`Listener.openSetting` 唯一实现是 `VodFragment.java:1102`。`HomeWebController` 类本身保留（`WebThemeDetailActivity:98` 与 `WebHomeExtensionDebugDialog:120` 仍用），但**首页形态**随 `VodFragment` 死亡。使用 Web 主题的用户将失去 Web 首页 | **已决策（2026-11-18，中国主人选 b，见 §7 D6）**：建最小原生回退壳（承载 `HomeWebController` + chrome 的单 Activity，仅 WebHome home 站点进入）。回退壳先于 `VodFragment` 删除建成并验收（阶段 4 前置条件） |
| R2 | **hk:// 首页失去内嵌列表** | `VodFragment` 是唯一 `HkListFragment.EmbedHost`；hk:// 站点首页现为内嵌列表。独立替代存在：`HkListActivity.open(Context, title)`（`:46`）+ 隐式 intent `.hk_list`，`B11HostApiImpl` 已有 `isMiniapp` 判定（`:829-832`，带漂移警告——`LocalSource.isMiniapp` 才是 main 可见等价实现）与 `openHkSearch` 反射前例。变化：内嵌 → 独立 Activity，返回栈与视觉不同；需核对全部 hk 入口形态（首页 tab、搜索、本地源、SiteDialog）是否都经独立页覆盖 | 阶段 1 路由器覆盖 hk 路由；阶段 3 真机验收 hk:// 站点全流程；`isMiniapp` 漂移点在阶段 2 统一为 `LocalSource.isMiniapp` |
| R3 | **旋转与 teardown 守卫** | `C1ScreenActivity` 现旋转重建；M2 清理若误触发，旋转即杀 Server/清配置。另有 B17 旋转观察项（person 页）与 PiP（Video/Live/TmdbDetail 不受影响，独立 activity） | §2.2 双保险：`configChanges` 加 orientation 系 + `isFinishing() && sLiveActivities.isEmpty()` 守卫；阶段 3 真机旋转验收（首页/设置页/搜索页旋转后数据与栈正常） |
| R4 | **G2 引擎竞态（C1→C1 无条件导航）** | 阶段 2 后所有分流恒走 start-then-finish；`SearchActivity.direct` 的 C1 调用方例外消失。relaunch 机制（`engineReused` early hint + `onPostResume` 断言 + `sAssertedRoute`）已覆盖 start-then-finish，但 C1→C1 的 collect 直达（原 `direct()` 的 C1 例外路径）是新形态 | 阶段 2 验收：B9 提交、B10 直达、跨屏连续跳转无串屏/闪屏；`C1ScreenActivity.start` 从 C1 调用方发起时必须配 `activity.finish()`（现有 impl 均已如此，改线时保持） |
| R5 | **根返回 / 退出语义** | `onBackInvoked` 链消失；`PlaybackService` 运行时误 finish 会触发 M2 清理杀后台播放；反之误后台化则“退出不清” | D1 决策（§2.5）+ 阶段 3 真机验收：播放中返回、非播放返回、launcher 再进 |
| R6 | **Intent / deep-link 边缘** | SEND/VIEW/SEARCH 全量形态（torrent/magnet/ed2k/thunder、smb/rtmp/rtsp、`.m3u`、文件选择结果）；`ACTION_SEARCH` 系统搜索；`ManageService` 推送；`FileChooser` 结果回传（B15 观察项，经 C1 的 activity-result launcher 与 `onActivityResult` 转发，B21 3000+ SAF 码路径已存在） | §2.3 映射逐项验收；阶段 3 真机测：分享文本进详情、文件管理器打开视频/m3u、磁力链、系统搜索 |
| R7 | **通知入口** | `FollowingNotifier.java:47` → `FollowingActivity.intent(...)`：TV 正确，mobile 上 `FollowingActivity` 不可达后点击通知将打开原生页（与 Flutter-only 冲突）。`PlaybackService` 通知自动跟随 launcher 改向，无问题 | 决策（§7 D7）：`FollowingNotifier` 加 `Util.isMobile()` 分支，mobile 发 C1 `ROUTE_FOLLOWING` intent（带 identityKey query）；或保留原生 `FollowingActivity` 作为通知落地页（与“原生全删”冲突，不推荐） |
| R8 | **Alias / 桌面图标状态** | alias 改向后 `AppBranding` 的 enable/disable 仍以组件名为键（不变），但 `targetActivity` 变化是否影响已钉选图标的解析需真机验证；`B15HostApiImpl:279` 的 `launcherIntent` 随改向 | 阶段 3 真机验收：切换品牌图标（current/history）后桌面图标与启动正常 |
| R9 | **未迁移边缘屏** | `ScanActivity`（留，入口在 dialogs，不受影响）、`EpisodeFragment`（dialog 内嵌，留）、Hk list/detail/search（留）、WebView 容器（留）、`CrashActivity`/`PlaybackRecoveryActivity`（留）、`LocalSourceDialogActivity`（留）、详情屏（留）、`AudioActivity`（留）、下载列表（`SettingFragment` 的下载行随文件消失，B7 已含下载列表——验收时核对下载入口与进度）、`HistoryDialog`/`WallConfig` 壁纸历史（`SettingFragment.onWallHistory` 经 `HistoryDialog`，dialog 留原生；Flutter 侧壁纸入口为 B11/B4 既有路径） | 阶段 4 验收清单逐项过；下载与壁纸历史重点核对 |
| R10 | **leanback 编译** | 每个 main 源集改动都必须 leanback 可编译：禁止直接引用 mobile 类；TV 分支一律反射；路由器 mobile 分支引用的 `C1ScreenActivity` 在 leanback 构建中存在（main 源集）但未声明——`Util.isMobile()` 门必须万无一失，否则 TV 上启动未声明 activity 崩溃 | 阶段 1 即建双编译验证（D5 确认 CI 覆盖）；代码评审点：所有新 main 代码的 flavor 门 |
| R11 | **配置加载 once 语义** | 旋转/进程死亡恢复时不得重复加载（闪屏/重复回调）也不得漏加载（Dart 首页 Pigeon 空数据）；`VodConfig.load` 回调的 `StateEvent` 投递删除后，Dart 首页首帧数据依赖 Pigeon 自取时序 | §2.1 once 守卫 + 阶段 3 冷启动/旋转/杀进程恢复验收 |
| R12 | **设置搜索行直达（sPendingRow）** | 写方随 `SettingFragment` 消失；B5 反射写方与 B7 `consumePendingRow` 的契约需重建（路由 query 优先） | M15 在阶段 2 落地并验收：设置内搜索 → 点结果 → 落在对应行 |
| R13 | **主题 / 对话框宿主** | `C1ScreenActivity` 非 `BaseActivity` 子类，无 edge-to-edge/动态取色/主题绑定；经它弹出的原生 dialog（配置弹窗、TypeFilterDialog 经 `B11FabHostFragment`）外观依赖 activity 主题。#51 已证明配置弹窗可在 C1 弹出，但删除后 C1 成为唯一宿主，全量 dialog 需复验 | 阶段 3 真机验收：全部 dialog 类型在 C1 宿主下的外观与交互 |
| R14 | **B11FabHostFragment 的留存** | mobile 源集 headless fragment，因 `TypeAdapter.OnClickListener` 是 mobile-only 接口而必须存在于 mobile 源集；它是 `TypeFilterDialog` 在 Flutter 侧的宿主，删除 program 不得误删 | §1.3 已列留；阶段 4 清单复核时重点标注 |

---

## 7. 待定事项汇总（需中国主人决策 / 实施时确认）

| # | 事项 | 选项 / 确认点 | 阻塞阶段 |
|---|---|---|---|
| D1 | 根返回行为 | `PlaybackService` 运行时后台化、否则 finish（建议，与原生一致）；或恒后台化 | 阶段 3 |
| D2 | 启动权限请求保留与否 | 保留（行为一致，建议）/ 删除（B15 用点桥接已覆盖） | 阶段 3 |
| D3 | `VodConfig` 已加载判定谓词 | 实施时确认与 `LiveConfig.hasLoadedLives()` 对等的 `VodConfig` API 名（`Manage.java:835` 有 `getConfig().isEmpty()` 前例）；once 守卫为主、谓词为辅 | 阶段 3 |
| D4 | `C1ScreenActivity` 加 orientation 系 `configChanges` | 建议加（§2.2）；复制原生 rotation-stable activity 的确切字符串 | 阶段 3 |
| D5 | leanback 编译的 CI 覆盖 | **已确认（2026-11-18，阶段 1 收尾）**：`b8-c0.yml` 覆盖 leanback 编译（`assembleLeanbackArm64_v8aRelease` 与 `assembleLeanbackArmeabi_v7aRelease`），双 flavor 覆盖已有，无需再确认 | 阶段 1（已解决） |
| D6 | **WebHome 首页去留** | **已决策（2026-11-18 08:22，中国主人选 b）**：建最小原生回退壳（承载 `HomeWebController` + chrome 的单 Activity，仅 WebHome home 站点进入），回退壳先于 `VodFragment` 删除建成并验收 | 阶段 4 |
| D7 | `FollowingNotifier` mobile 落地 | **已决策（2026-11-18，阶段 1 实施）**：mobile 发 C1 `/following` intent；identityKey 不拼进路由（Dart 只注册了字面 `/following` 路由，带 query 无法解析），列表打开后由用户点选条目。TV 保持原生 `FollowingActivity.intent` | 阶段 1（已完成） |
| D8 | `shape_search_hot_word.xml` 删除条件 | 确认 `ThemeBinder` 无运行时基名派生 | 阶段 5 |
| D9 | `B11FabHostFragment` 之外的 mobile-only 接口宿主 | **已复核（2026-11-18，阶段 1）**：改线时全量核查 main 源集对 mobile 类的引用，未发现 B11 FAB 之外需要新增 mobile 宿主 fragment 的 impl；无新增宿主需求 | 阶段 1（已完成） |
| D10 | `openHomeAt` / `goHome` 方法去留形态 | **已决策（2026-11-18，阶段 1 实施）**：保留方法名，改实现为 C1 路由 intent（`FlutterNav.openHomeAt` / `FlutterNav.goHome`），Dart 侧零改动 | 阶段 1（已完成） |

---

## 8. 真机验收清单（阶段 3/4 门禁，RMX3800）

1. 冷启动进 Flutter 首页，无原生首页闪现；回跳消失。
2. launcher 图标二次点击、alias 图标、最近任务回前台，均回 `/home` 且不串屏。
3. 旋转：首页 / 设置页 / 搜索页 / person 页旋转后数据与栈正常，无重复加载。
4. 退出：非播放时根返回退出后，Server 已停、配置已清（下次冷启动重新加载）；播放中根返回后台化，播放继续。
5. Intent：分享文本、文件管理器开视频、`.m3u` 进直播、磁力/ed2k/thunder 链、系统搜索。
6. hk:// 站点：首页列表、搜索、详情、管理页（`/hk-manager`）全流程。
7. WebHome 站点：按 D6 决策验收（降级或回退壳）。
8. 通知：追更通知点击进 `/following`；播放通知点击回首页。
9. 短剧：feed 播放、theater/history tab 分流、返回回播放流。
10. 设置搜索行直达、下载列表、壁纸历史、插件管理、Lab 列表/详情/终端。
11. 全部 dialog 在 C1 宿主下外观与交互正常（含 TypeFilterDialog、配置弹窗、选站弹窗）。
12. 直播：底栏返回首页/设置、`isTaskRoot` 退出回首页、PiP。
13. 详情/播放：搜同名、person 页、选集、投屏、下载。
14. 品牌图标切换（current/history）后桌面图标与启动正常。

---

## 附：核查过的文件（主要）

- `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/activity/HomeActivity.java`（全文）
- `app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1ScreenActivity.java`（全文）
- `app/src/main/java/com/github/moyingshi/osc/tv/App.java`（全文）
- `app/src/mobile/AndroidManifest.xml`、`app/src/main/AndroidManifest.xml`（全文）、
  `app/src/leanback/AndroidManifest.xml`（grep 确认无 Flutter/C1 声明）
- `app/src/main/java/com/github/moyingshi/osc/tv/Setting.java`（flag 段 `:416-655`）
- `app/src/main/java/com/github/moyingshi/osc/tv/ui/activity/SearchActivity.java`（mobile 版 `:42-130`，
  经 `FolderActivity`/`B9` 交叉核对）、`HistoryResumeCoordinator.java`、`FileChooser.java`（`:85-110`）
- `app/src/main/java/com/github/moyingshi/osc/tv/web/HomeWebBridge.java`（`:430-460`）、
  `HomeWebController.java`（使用者核查）、`WebHomeThemeBridge.java`（`:335/:443`）
- `app/src/main/java/com/github/moyingshi/osc/tv/flutter/` 全部 `HostApiImpl` 分流点（grep + 定向读）
- `flutter_module/lib/main.dart`（路由表）、`b9_search_page.dart`（`:220-250`）、
  `b7_settings_home_page.dart`（开关行与回退分支）
- `app/src/mobile/res/` 全量布局/菜单/drawable 清单与 binding/引用逐项 grep
- `docs/b8-full-migration-plan.md`（屏枚举与排除清单）、`docs/b8-b*.md`（各 batch 偏差）
