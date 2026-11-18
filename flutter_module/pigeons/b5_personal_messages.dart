import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as b4_enhance_messages.dart: generated Dart code
    // lives inside the module; the generated Java code lands in the host
    // app source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b5_personal_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B5Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B5Messages',
    ),
  ),
)
@HostApi()
abstract class B5HostApi {
  /// Personal settings page (native SettingPersonalFragment): one
  /// snapshot with every row's title, value text and error flag, in the
  /// native layout order. All strings are resolved by the host; Dart
  /// never hard-codes locale strings and never recomputes a value.
  /// Icons, circle colors and chevrons are Dart-side presentation
  /// choices keyed by row key (see b5_personal_page.dart), mirroring
  /// fragment_setting_personal.xml. The page is flat: no conditional
  /// visibility anywhere (0 in the plan).
  B5PersonalSettings getPersonalSettings();

  /// Toggles. Each flips its Setting (plus the native side effects in
  /// the doc) and Dart re-fetches afterwards.
  void toggleFollowing();
  void togglePlaybackOverlay();
  void togglePlayBackToDetail();
  void toggleEpisodeHistory();
  void toggleTouchOptimization();
  void toggleLiquidGlass();
  void toggleShortDramaCollected();
  void toggleGlassDispersion();

  /// Cycles. Each advances its Setting to the next array value (clamped
  /// indices on the host) and Dart re-fetches afterwards.
  void cycleGlobalHistory();
  void cycleInterfaceFailover();
  void cycleSearchUi();
  void cycleSearchColumn();
  void cycleSiteColumn();
  void cycleSearchResultSort();

  /// Resets the four glass values to their defaults. Dart re-fetches
  /// afterwards.
  void resetGlass();

  /// Opens the native number-slider dialog for the search-thread count
  /// (1-100). Async: completes when a value is picked; closing it
  /// without picking completes nothing, and Dart does not await these
  /// calls (see the dialog contract in docs/b8-b3-settings-a.md §2.5 /
  /// docs/b8-b4-enhance.md §2.5), so no refresh is owed then.
  @async
  void openSearchThread();

  /// Opens the native speed dialog (0.5-5, step 0.25). Async: completes
  /// when a value is picked.
  @async
  void openPlaySpeed();

  /// Opens the native group-rule dialog. Async: completes when a rule
  /// change is reported; closing it without a change completes nothing.
  @async
  void openGroupRule();

  /// Opens the native number-slider dialogs for the glass values
  /// (blur 0-40, distortion 0-30, translucency 0-100). Async: each
  /// completes when a value is picked.
  @async
  void openGlassBlur();
  @async
  void openGlassDistortion();
  @async
  void openGlassTranslucency();

  /// Auto-backup row: if backup is effectively enabled, turns it off
  /// (completes, state changed); otherwise asks for file access first
  /// (PermissionX, via the host Activity) and turns it on when granted
  /// (completes). If permission is denied the host shows a toast and
  /// completes nothing, and Dart does not await.
  @async
  void openAutoBackup();

  /// Opens the native reset-app confirm dialog. Fire-and-forget: the
  /// positive button resets the app (the process restarts), so there is
  /// nothing to refresh afterwards.
  void showResetAppDialog();

  /// Opens the native app-branding activity (B15 migrates it later).
  /// Fire-and-forget: it opens another screen.
  void openAppBranding();

  /// Settings search (native SettingSearchFragment): the full search
  /// index with every row's resolved title, subtitle and destination,
  /// plus the hint/empty texts. Dart filters this list locally
  /// (case-insensitive substring on title + subtitle, 300ms debounce),
  /// mirroring SettingSearchIndex.search(); the host does not filter per
  /// keystroke. The entries are built by reflecting the mobile-only
  /// SettingSearchIndex (see docs/b8-b5-personal.md §2.9); the
  /// flutterAvailable flag tells Dart whether the destination can be
  /// reached by a family-internal push (a migrated member) or must go
  /// through the host instead.
  B5SearchScreen getSettingSearch();

  /// Opens a search result's destination: finishes this Flutter
  /// screen, then opens the destination — a settings-family member via
  /// the router, or the subtitle page (page 6, a top-level C1 route).
  /// When rowId is non-empty, the settings home opens with that row
  /// deep-linked (B7HostApiImpl pending row). Fire-and-forget: it
  /// leaves this screen.
  void openSearchEntry(int page, String rowId);
}

/// One row of the personal page, in native layout order. The key is a
/// fixed token (never a locale string); Dart switches on it for the icon,
/// circle color, chevron and tap handler.
class B5PersonalRow {
  B5PersonalRow({
    required this.key,
    required this.title,
    required this.text,
    required this.hasError,
  });

  String key;
  String title;
  String text;
  bool hasError;
}

class B5PersonalSettings {
  B5PersonalSettings({
    required this.screenTitle,
    required this.rows,
  });

  String screenTitle;
  List<B5PersonalRow> rows;
}

/// One settings-search index entry, with all strings resolved by the
/// host. page is the native HomeActivity fragment index (>= 0) or -1
/// for a settings-home row deep link (rowId != ""); rowId is "" when
/// the entry points at a page. flutterAvailable is true only when the
/// destination is a migrated family member (reachable by a
/// family-internal push).
class B5SearchEntry {
  B5SearchEntry({
    required this.title,
    required this.subtitle,
    required this.dest,
    required this.page,
    required this.rowId,
    required this.flutterAvailable,
  });

  String title;
  String subtitle;
  String dest;
  int page;
  String rowId;
  bool flutterAvailable;
}

class B5SearchScreen {
  B5SearchScreen({
    required this.hintText,
    required this.emptyText,
    required this.entries,
  });

  String hintText;
  String emptyText;
  List<B5SearchEntry> entries;
}
