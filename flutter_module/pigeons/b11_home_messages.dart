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
  /// bar; the tab-page content itself comes from getTabVideos (the
  /// B12/B13 widgets own the AppBar blocks above the tabs).
  /// A second call while one is in flight completes with an error
  /// immediately (host keeps a single subscription, like B8).
  @async
  B11HomeScreen getHomeShell();

  /// One page of the selected tab's inline video grid (b8-home-grid):
  /// the same list the B8 category screen shows for the tab, rendered
  /// inside the home tab content area instead of a placeholder. The
  /// host reuses the B8 list path exactly (SiteViewModel
  /// .categoryContent for the home site, Class filters' init values,
  /// one-shot subscription, 35s watchdog, error snapshot on timeout).
  /// Dart passes the tab's typeId/typeJson (from getHomeShell) and the
  /// page to load; the host owns the page/hasMore computation and
  /// echoes the effective filter map and the home site key. A second
  /// call while a home load (shell or grid) is in flight completes
  /// with an error immediately (the two loads share one ViewModel).
  @async
  B11TabVideos getTabVideos(String typeId, String typeJson, int page);

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

  /// Folder-card action from the inline tab grid: open the B8 category
  /// screen for the folder (host builds the /category route with the
  /// folder's typeId and the parent tab's Class JSON, start-then-finish,
  /// shared G2 race) — the B8 drill-down semantics, from the home grid.
  void openTabCategory(String typeId, String typeJson);

  /// browse FAB (tune icon): open the native TypeFilterDialog — all
  /// categories + the current tab's filter groups, exactly like
  /// VodFragment.onBrowse (items = the home Result's types, filter =
  /// the current tab's filters, filterProvider per position). tabIndex
  /// is the currently selected tab (the dialog's initial filter
  /// section + highlight). The dialog is opened reflectively through
  /// a mobile headless fragment (B7/B9 pattern: the dialog casts its
  /// host to the mobile-only TypeAdapter.OnClickListener). While it
  /// is open, Dart polls getTypeFilterState for live selections
  /// (type taps switch the tab, filter picks reload the grid, both
  /// without closing the panel, like native). Completes on dismiss
  /// with the final selection state; on any failure it completes
  /// with the unchanged state and the home stays as-is.
  @async
  B11TypeFilterState openTypeFilterDialog(int tabIndex);

  /// Poll the live TypeFilterDialog selection while it is open
  /// (B19/B20 polling pattern): dialogOpen is false once dismissed,
  /// and version bumps on every type tap / filter pick. Dart applies
  /// the state whenever the version differs from the last applied
  /// one; the dismiss completion of openTypeFilterDialog is the
  /// backstop, so a missed poll cannot lose the final selection.
  @async
  B11TypeFilterState getTypeFilterState();

  /// link FAB: open the native LinkDialog (paste-a-link play, like
  /// VodFragment.onLink; reflection + the B7 headless fragment).
  /// Completes on dismiss; the dialog plays directly (VideoActivity),
  /// so no state comes back.
  @async
  void openLinkDialog();
}

/// Live selection state of the native TypeFilterDialog opened from
/// the home browse FAB (b8-home-fabs). The host owns the state: type
/// taps and filter picks update it through the mobile headless
/// fragment's listener callbacks, and getTabVideos applies the
/// picked filter overrides over the Class filters' init values.
class B11TypeFilterState {
  B11TypeFilterState({
    required this.dialogOpen,
    required this.selectedTabIndex,
    required this.version,
  });

  /// True while the dialog is on screen (set when shown, cleared on
  /// dismiss). False when no dialog is open or opening failed.
  bool dialogOpen;

  /// Currently selected type position, in getHomeShell tabs order.
  int selectedTabIndex;

  /// Selection generation: reset to 0 when the dialog opens, bumped
  /// on every type tap / filter pick. Dart applies the state
  /// whenever this differs from the last applied version.
  int version;
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
    required this.wallpaperPath,
    required this.wallpaperColor,
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

  /// Full-screen home wallpaper (b8-home-glass), resolved by the host
  /// exactly like CustomWallView.load(): the absolute path of a
  /// renderable image file — a files-dir JPEG snapshot of the
  /// built-in design drawable, or the WallConfig frame-0 cache file
  /// for a custom / video / gif wall. Empty when the wallpaper is a
  /// flat color or no image exists; Dart then paints wallpaperColor.
  String wallpaperPath;

  /// Fallback color (ARGB) painted behind the wallpaper image and
  /// used on its own when wallpaperPath is empty: the built-in
  /// design's representative color, or the default dream purple.
  int wallpaperColor;
}

/// One category tab. typeJson is the full Class JSON (B8 route input)
/// so the tab grid can load its list and folder drill-downs can build
/// the /category route without re-fetching the type list.
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

/// One page of a home tab's inline video grid, mirroring the B8
/// category screen minus the title and filters (the home grid has
/// neither: the tab bar above it is the title, and filters live on the
/// /category screen). errorMessage is empty when there is no error; on
/// error the items list is empty and hasMore is false, but the echoed
/// extendJson is still present so a load-more failure can keep the list
/// and retry.
class B11TabVideos {
  B11TabVideos({
    required this.siteKey,
    required this.styleType,
    required this.styleRatio,
    required this.columnCount,
    required this.cardImageWidthPx,
    required this.cardImageHeightPx,
    required this.items,
    required this.hasMore,
    required this.pageCount,
    required this.nextPage,
    required this.extendJson,
    required this.errorTitle,
    required this.errorMessage,
    required this.retryLabel,
  });

  /// Home site key: Dart passes it to B8HostApi.openCategoryItem /
  /// searchCategoryItem so a grid tap opens detail exactly like a B8
  /// category tap (same host chain, same history-resume args).
  String siteKey;

  /// Effective card style for this page: "rect", "list" or "oval"
  /// (same resolution as B8: folder mode is always "list"; otherwise
  /// the site style, the Class style, or the first item's style).
  String styleType;

  /// Effective style ratio (image width / height) for rect/oval cards.
  double styleRatio;
  int columnCount;
  int cardImageWidthPx;
  int cardImageHeightPx;

  /// This page's items (appended by Dart on load-more).
  List<B11TabVideoItem> items;

  /// True when another page may exist (page < pageCount, or pageCount
  /// is 0 = unknown, mirroring CustomScroller.setEnable).
  bool hasMore;
  int pageCount;

  /// Page Dart should request next (same page when this page came back
  /// empty, mirroring CustomScroller.endLoading's page--).
  int nextPage;

  /// Effective filter map as a JSON object string, after the host
  /// applied the Class filters' init values; echoed for load-more
  /// consistency with B8 (the home grid sends no filter map).
  String extendJson;

  String errorTitle;
  String errorMessage;
  String retryLabel;
}

/// One grid card. Field-for-field mirror of B8CategoryItem: the host
/// flattens the Vod bean the same way (same ImgUtil delegation, same
/// visibility flags), so the shared VodCard renders grid cards exactly
/// like B8 category cards.
class B11TabVideoItem {
  B11TabVideoItem({
    required this.id,
    required this.name,
    required this.year,
    required this.remarks,
    required this.siteName,
    required this.siteVisible,
    required this.nameVisible,
    required this.remarkVisible,
    required this.yearVisible,
    required this.isFolder,
    required this.isAction,
    required this.action,
    required this.picUrl,
    required this.rawPic,
    required this.picReferer,
    required this.picCookie,
    required this.picUserAgent,
    required this.picExtraHeadersJson,
    required this.fallbackPicUrl,
    required this.placeholderText,
    required this.placeholderColor,
  });

  String id;
  String name;

  /// Release year. The native rect card shows it as a badge only when
  /// the item has no site and the year is at least 4 characters; the
  /// shared VodCard has no year slot, so Dart does not render it (same
  /// as B8, see docs/b8-b8-category.md).
  String year;
  String remarks;
  String siteName;
  bool siteVisible;
  bool nameVisible;
  bool remarkVisible;
  bool yearVisible;
  bool isFolder;
  bool isAction;

  /// Action JSON for action cards (empty otherwise).
  String action;

  /// Clean image URL: the host ran ImgUtil.getUrl() and stripped the
  /// `@Headers=` / `@Cookie=` / `@Referer=` / `@User-Agent=` suffixes.
  String picUrl;

  /// Raw vodPic as the site returned it (headers suffixes intact).
  /// Dart passes this to openCategoryItem so the detail page loads the
  /// image with the same headers native would use.
  String rawPic;
  String picReferer;
  String picCookie;
  String picUserAgent;

  /// Any remaining `@Headers=` entries (headers other than referer /
  /// cookie / user-agent) as a JSON object string; empty when none. Dart
  /// merges these under the three named headers.
  String picExtraHeadersJson;

  /// TmdbProxy auto-fallback URL (already cleaned by the host); empty
  /// when there is none. Dart tries it once if the primary URL fails.
  String fallbackPicUrl;

  /// First-character placeholder (TextDrawable parity): the character and
  /// its ColorGenerator.get400() ARGB color, for failed / missing images.
  String placeholderText;
  int placeholderColor;
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
