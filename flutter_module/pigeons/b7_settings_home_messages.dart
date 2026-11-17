import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as b5_personal_messages.dart: generated Dart code
    // lives inside the module; the generated Java code lands in the host
    // app source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b7_settings_home_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B7Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B7Messages',
    ),
  ),
)
@HostApi()
abstract class B7HostApi {
  /// B7 feature flag (persisted in Setting, default false = native page).
  bool isB7Enabled();
  void setB7Enabled(bool enabled);

  /// B8 master switch (see docs/b8-master-switch.md): writes the single
  /// `flutter_enabled` key. Called from the Flutter settings home's
  /// master-switch row; Dart closes the screen afterwards so the next
  /// entry follows the new value.
  void setFlutterEnabled(bool enabled);

  /// Settings home (native SettingFragment): one snapshot with every
  /// group's rows in native order, the download list, and the pending
  /// deep-link row. All strings and computed values come from the host;
  /// Dart never hard-codes locale strings and never recomputes a value.
  /// Icons and circle colors are Dart-side presentation choices keyed
  /// by row key (see b7_settings_home_page.dart), mirroring
  /// fragment_setting.xml / item_setting_card_row.xml.
  /// Visibility rules are computed by the host: the hidden group is
  /// only present when hiddenVisible, the download list only when
  /// hiddenVisible && downloadEnabled (mirroring
  /// SettingFragment.refreshDownloadList).
  B7SettingsHome getSettingsHome();

  /// Config rows (vod / live / wall): the manage dialog (native
  /// HistoryDialog, manage variant). Async: completes when a config is
  /// picked (the host applies it, mirroring
  /// SettingFragment.setConfig); closing it without picking completes
  /// nothing, and Dart does not await these calls (see the dialog
  /// contract in docs/b8-b3-settings-a.md §2.5).
  @async
  void openVodManage();
  @async
  void openLiveManage();
  @async
  void openWallManage();

  /// Config-row home buttons: vod opens the site picker (native
  /// SiteDialog, search + change), live opens the live picker (native
  /// LiveDialog). Async: each completes when a home is picked.
  @async
  void openVodHome();
  @async
  void openLiveHome();

  /// Wall home button: cycles to the next default wall, synchronously
  /// (mirrors SettingFragment.setWallDefault).
  void setWallDefault();

  /// Config-row history buttons: the history dialog (no manage
  /// variant). Async: completes when a config is picked.
  @async
  void openVodHistory();
  @async
  void openLiveHistory();

  /// Wall history button: reloads the wall config (mirrors
  /// SettingFragment.setWallRefresh). Async: completes when the load
  /// succeeds; a failed load completes nothing.
  @async
  void setWallRefresh();

  /// Wall history button long-press: the wall history dialog. Async:
  /// completes when a config is picked.
  @async
  void openWallHistory();

  /// Appearance row: the native AppearanceDialog. Async: completes when
  /// the dialog is dismissed (it has no change callback, so the host
  /// watches the dialog's destruction — see
  /// docs/b8-b7-settings-home.md §2.6).
  @async
  void openAppearance();

  /// Local-source row: the native LocalSourceDialog (mobile-only, opened
  /// via reflection). Async: completes when the dialog is dismissed
  /// (no change callback; same dismissal watcher as openAppearance).
  @async
  void openLocalSource();

  /// Lab row: opens the native LabActivity. Fire-and-forget: it opens
  /// another screen.
  void openLab();

  /// Git-cloud row: the native GitCloudDialog. Async: completes when
  /// the dialog is dismissed (its Runnable runs in onDestroyView).
  @async
  void openGitCloud();

  /// One-key-sync row: the native OneKeySyncDialog. Fire-and-forget:
  /// it syncs in the background and shows its own result.
  void openOneKeySync();

  /// Drive-check row: flips the drive-check switch. Dart re-fetches
  /// afterwards.
  void toggleDriveCheck();

  /// Site-health row: the native SiteHealthDialog. Async: completes
  /// only when the confirm button is pressed (its Runnable runs in
  /// onPositive); cancelling completes nothing.
  @async
  void openSiteHealth();

  /// Site-health row long-press: clears the site-health data.
  /// Dart re-fetches afterwards.
  void clearSiteHealth();

  /// Cache row tap: one-key clear (mirrors SettingFragment
  /// .onCacheClear: FileUtil.clearCache when cache management is off,
  /// CacheManagementDialog.cleanEverything() when it is on). The
  /// cleanup runs in the background with no completion callback, so
  /// Dart polls the snapshot for up to 15s until the cache text
  /// changes (see docs/b8-b7-settings-home.md §2.7).
  void openCacheClear();

  /// Cache row long-press: the native CacheManagementDialog (when cache
  /// management is on; otherwise falls back to openCacheClear).
  /// Async: completes when the dialog is dismissed (same dismissal
  /// watcher as openAppearance); Dart also polls the cache text
  /// afterwards, as for openCacheClear.
  @async
  void openCacheManage();

  /// Backup row: asks for file access, then runs the native backup with
  /// its progress dialog. Async: completes only when the backup
  /// succeeds; a denied permission or a failed backup completes
  /// nothing.
  @async
  void openBackup();

  /// Restore row: asks for file access, then opens the native
  /// RestoreDialog (mobile-only, via reflection). Async: completes
  /// only when the restore succeeds; a denied permission, a failed
  /// restore, or cancelling completes nothing.
  @async
  void openRestore();

  /// DoH row: the native single-choice dialog. Async: completes when a
  /// DoH is picked; cancelling completes nothing.
  @async
  void openDoh();

  /// Incognito row: flips the incognito switch. Dart re-fetches
  /// afterwards.
  void toggleIncognito();

  /// About row: mirrors SettingFragment.onVersion — the Flutter About
  /// screen when the C1 flag is on, the native dialog otherwise.
  /// Fire-and-forget: it opens another screen.
  void openAbout();

  /// Subtitle row: mirrors SettingFragment.onSubtitle — the Flutter
  /// subtitle screen when the C1 flag is on, the native page otherwise.
  /// Fire-and-forget: it opens another screen.
  void openSubtitle();

  /// Download row: flips the download switch. Turning it off also
  /// re-locks the hidden settings (mirrors
  /// SettingFragment.setDownloadEnabled). Dart re-fetches afterwards.
  void toggleDownloadEnabled();

  /// Opens a native HomeActivity page (used for member rows whose flag
  /// is off, for the player row, and for the search button when the B5
  /// flag is off). Fire-and-forget: it leaves this screen.
  void openNativePage(int page);

  /// One download episode row tap. Mirrors
  /// SettingFragment.onEpisodeRowClick: DONE plays the downloaded
  /// file, FAILED retries, PAUSED resumes, QUEUED / RESOLVING /
  /// DOWNLOADING pauses. Dart re-fetches afterwards.
  void episodeAction(String id);

  /// One download drama's delete button: the native confirm dialog.
  /// Async: completes only when the drama is actually deleted;
  /// cancelling completes nothing.
  @async
  void deleteDrama(String dramaName);
}

/// One row of the settings home, in native group order. The key is a
/// fixed token (never a locale string); Dart switches on it for the
/// icon, circle color, and tap handler. `member` is the family member
/// id ("" when the row is not a family member); `memberFlutter` says
/// whether that member is currently a Flutter screen. `nativePage` is
/// the HomeActivity fragment index for the native fallback (-1 when
/// the row has no native page). `value` is "" when the native row
/// shows no value; `subtitle` is "" when the native row shows no
/// subtitle.
class B7SettingsRow {
  B7SettingsRow({
    required this.key,
    required this.title,
    required this.subtitle,
    required this.value,
    required this.hasError,
    required this.isConfig,
    required this.member,
    required this.memberFlutter,
    required this.nativePage,
  });

  String key;
  String title;
  String subtitle;
  String value;
  bool hasError;
  bool isConfig;
  String member;
  bool memberFlutter;
  int nativePage;
}

class B7SettingsGroup {
  B7SettingsGroup({
    required this.title,
    required this.rows,
  });

  String title;
  List<B7SettingsRow> rows;
}

/// One download episode row. `error` is "" unless the task failed with
/// a message; `progress` is the 0-100 progress (-1 when unknown);
/// `showProgress` says whether the native row shows a progress bar at
/// all, `indeterminate` whether that bar spins.
class B7DownloadEpisode {
  B7DownloadEpisode({
    required this.id,
    required this.name,
    required this.statusText,
    required this.error,
    required this.progress,
    required this.showProgress,
    required this.indeterminate,
  });

  String id;
  String name;
  String statusText;
  String error;
  int progress;
  bool showProgress;
  bool indeterminate;
}

class B7DownloadDrama {
  B7DownloadDrama({
    required this.dramaName,
    required this.summary,
    required this.episodes,
  });

  String dramaName;
  String summary;
  List<B7DownloadEpisode> episodes;
}

class B7SettingsHome {
  B7SettingsHome({
    required this.screenTitle,
    required this.searchFlutter,
    required this.cacheManagementEnabled,
    required this.hiddenVisible,
    required this.downloadEnabled,
    required this.downloadDeleteLabel,
    required this.downloadEmptyText,
    required this.groups,
    required this.downloadGroups,
    required this.downloadsActive,
    required this.cacheScanning,
    required this.pendingRowId,
    required this.flutterEnabled,
  });

  String screenTitle;
  bool searchFlutter;
  bool cacheManagementEnabled;
  bool hiddenVisible;
  bool downloadEnabled;
  String downloadDeleteLabel;
  String downloadEmptyText;
  List<B7SettingsGroup> groups;
  List<B7DownloadDrama> downloadGroups;
  bool downloadsActive;
  bool cacheScanning;
  String pendingRowId;

  /// B8 master switch state (see docs/b8-master-switch.md): the
  /// settings home is only reachable while it is on, so this is true
  /// in practice — Dart still reads it from the snapshot instead of
  /// hard-coding it, and renders the master-switch row from it.
  bool flutterEnabled;
}
