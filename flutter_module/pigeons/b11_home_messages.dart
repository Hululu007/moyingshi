import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2_messages.dart: generated Dart code lives
    // inside the module; the generated Java code lands in the host app
    // source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b11_home_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B11Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B11Messages',
    ),
  ),
)
@HostApi()
abstract class B11HostApi {
  /// B11 feature flag (persisted in Setting, default false = native home).
  bool isB11Enabled();
  void setB11Enabled(bool enabled);

  /// One-shot home shell snapshot (B8 pattern: SiteViewModel.homeContent
  /// one-shot subscription, 35s watchdog, completes exactly once on
  /// every path). Covers the top bar, the category tabs and the bottom
  /// bar; the tab-page content itself is a placeholder this batch
  /// (B12/B13 own the AppBar blocks, the tab pages are not B11 scope).
  /// A second call while one is in flight completes with an error
  /// immediately (host keeps a single subscription, like B8).
  @async
  B11HomeScreen getHomeShell();

  /// Search pill tap: hk:// home site -> HkSearchActivity (reflection,
  /// mobile-only), otherwise SearchActivity.start.
  void openSearch();

  /// Search pill long-press: search within the current home site.
  void openSearchCurrentSite();

  /// History icon tap: C2 flag on -> /history route (start-then-finish,
  /// shared G2 race, see docs/b8-b11-home.md), else native
  /// HistoryActivity.
  void openHistory();

  /// Overflow menu item tap. itemId is one of "keep", "search", "sync",
  /// "enhance" (refresh is handled in Dart by re-calling getHomeShell).
  /// keep -> B2 /keep or native KeepActivity; sync -> OneKeySyncDialog
  /// (main); enhance -> B4 /settings?member=enhance or native page 3.
  void openMenuItem(String itemId);

  /// Title tap: SiteDialog (reflection + headless fragment, B9 pattern).
  /// Completes true when the home site changed (Dart refetches the
  /// snapshot, which may now be a hk:// / WebHome fallback -> close),
  /// false when it was dismissed unchanged.
  @async
  bool openSiteDialog();

  /// Logo tap: HistoryDialog vod().readOnly() (reflection). Completes
  /// on dismiss; the dialog is read-only so no state can change.
  @async
  void openLogoDialog();

  /// Title/logo long-press: VodConfig.clear().config().load() (native
  /// VodFragment.setHome reload path), then Dart refetches the snapshot.
  /// Completes when the config load callback fires (success or error;
  /// on error the host shows the native toast, like VodFragment).
  @async
  void reloadConfig();

  /// Bottom bar tap. token is one of "vod", "live", "drama",
  /// "following", "local_source", "setting". "vod" is a no-op in the
  /// host (Dart stays on the home screen). "setting" -> B7 /settings
  /// or native page 1; "drama" -> ShortDramaActivity (reflection,
  /// mobile-only); "live" -> LiveActivity; "following" ->
  /// FollowingActivity; "local_source" -> LabActivity when labNav is
  /// on, else LocalSourceDialog (reflection).
  void openBottomBarItem(String token);

  /// Bottom bar long-press. Only "live" does anything (pin a Live
  /// shortcut, native VodFragment pattern); other tokens are no-ops.
  void longPressBottomBarItem(String token);

  /// Tab-page placeholder action: open the B8 category screen for this
  /// tab (host builds the /category route with the tab's Class JSON,
  /// start-then-finish, shared G2 race).
  void openTabCategory(String typeId, String typeJson);
}

/// Flat home shell snapshot. Every display string and computed value
/// comes from the host; Dart hard-codes no locale strings.
class B11HomeScreen {
  B11HomeScreen({
    required this.title,
    required this.searchHint,
    required this.historyTooltip,
    required this.moreTooltip,
    required this.tabs,
    required this.errorToast,
    required this.emptyTitle,
    required this.emptyDescription,
    required this.emptyActionLabel,
    required this.isHkHome,
    required this.isWebHome,
    required this.navItems,
    required this.selectedNavToken,
    required this.glassEnabled,
    required this.glassBlurDp,
    required this.glassDistortionDp,
    required this.glassTranslucency,
    required this.glassDispersion,
    required this.darkMode,
    required this.accentColor,
    required this.menuItems,
    required this.tabPlaceholderEmptyText,
  });

  /// Top bar title (AppBranding display name, host-resolved).
  String title;

  /// Search pill hint (menu_search).
  String searchHint;

  /// History icon tooltip (app_history).
  String historyTooltip;

  /// Overflow menu icon tooltip (control_other).
  String moreTooltip;

  /// Category tabs from Result.getTypes(), in native order. Empty when
  /// the home load produced no types (no-config / empty site).
  List<B11HomeTab> tabs;

  /// Non-empty when the site reported a failure: Dart shows it once as
  /// a SnackBar and still renders the content area (native shows the
  /// native content + toast on the same error).
  String errorToast;

  /// Empty-state texts (home_empty_title / description / action),
  /// shown when the load succeeded but produced no tabs and no list.
  String emptyTitle;
  String emptyDescription;
  String emptyActionLabel;

  /// True when the current home site is a hk:// miniapp or a WebHome
  /// theme can load for it: the Flutter home must not own this site,
  /// Dart closes the screen and the native home underneath takes over.
  bool isHkHome;
  bool isWebHome;

  /// Bottom bar items in native setNavigation() order, visibility
  /// already applied by the host (same rules as HomeActivity).
  List<B11NavItem> navItems;

  /// Selected bottom bar token; always "vod" on the home screen.
  String selectedNavToken;

  /// Glass bar (Setting.isLiquidGlass()) and its parameters
  /// (glassBlur / glassDistortion / glassTranslucency / glassDispersion
  /// / dark mode / colorPrimary accent, all host-resolved).
  bool glassEnabled;
  int glassBlurDp;
  int glassDistortionDp;
  int glassTranslucency;
  bool glassDispersion;
  bool darkMode;
  int accentColor;

  /// Overflow menu items (refresh / keep / search / sync / enhance;
  /// push_apk / push_play are not offered, see docs/b8-b11-home.md V8).
  List<B11MenuItem> menuItems;

  /// Tab-page placeholder text (widget_empty).
  String tabPlaceholderEmptyText;
}

/// One category tab. typeJson is the full Class JSON (B8 route input)
/// so the placeholder "open in category screen" action can build the
/// /category route without re-fetching the type list.
class B11HomeTab {
  B11HomeTab({
    required this.typeId,
    required this.typeName,
    required this.typeJson,
  });

  String typeId;
  String typeName;
  String typeJson;
}

/// One bottom bar item (HomeActivity.setNavigation rules).
class B11NavItem {
  B11NavItem({
    required this.token,
    required this.label,
    required this.visible,
    required this.isLive,
    required this.badgeCount,
  });

  /// "vod" | "live" | "drama" | "following" | "local_source" |
  /// "setting".
  String token;

  /// Display label (host-resolved, mobile-only strings via
  /// mobileString()).
  String label;

  /// Visibility after the native setNavigation() rules.
  bool visible;

  /// True only for the live item (long-press pins a shortcut).
  bool isLive;

  /// Following unread badge (0 = none / hidden).
  int badgeCount;
}

/// One overflow menu item.
class B11MenuItem {
  B11MenuItem({required this.id, required this.label});

  /// "refresh" | "keep" | "search" | "sync" | "enhance".
  String id;
  String label;
}
