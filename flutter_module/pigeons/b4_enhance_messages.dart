import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as b3_settings_a_messages.dart: generated Dart code
    // lives inside the module; the generated Java code lands in the host
    // app source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b4_enhance_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B4Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B4Messages',
    ),
  ),
)
@HostApi()
abstract class B4HostApi {
  /// Enhance settings page (native SettingEnhanceFragment): one snapshot
  /// with every row's title, value text and error flag, in the native
  /// display order (SettingEnhanceFragment.reorderItems()). All strings
  /// are resolved by the host; Dart never hard-codes locale strings and
  /// never recomputes a value or a visibility rule. Icons, circle
  /// colors and chevrons are Dart-side presentation choices keyed by
  /// row key (see b4_enhance_page.dart), mirroring
  /// fragment_setting_enhance.xml. The always-GONE shellProxyConfig row
  /// is not in the snapshot at all (it never renders natively either).
  B4EnhanceSettings getEnhanceSettings();

  /// Toggles. Each flips its Setting and Dart re-fetches afterwards.
  /// Native side effects that need the mobile HomeActivity are skipped
  /// on purpose (see docs/b8-b4-enhance.md §2.10 V6/V7): the lab-nav
  /// refresh is covered by HomeActivity.onResume, and the WebHome chrome
  /// reads the Setting lazily on the next WebHome load.
  void toggleLabNav();
  void toggleHkMiniapp();
  void toggleDriveCheck();
  void toggleDebugLog();
  void toggleSearchCurrentSite();
  void toggleHomeWidget();
  void togglePlaybackArtworkWall();
  void toggleWebHomeFullscreen();

  /// Opens the native LabActivity (main source set, shared with TV).
  /// Fire-and-forget: it opens another screen.
  void openLab();

  /// Opens the native FileActivity in read mode (local reader).
  /// Fire-and-forget: it opens another screen.
  void openLocalReader();

  /// Opens the native PluginManagerActivity (main source set).
  /// Fire-and-forget: it opens another screen.
  void openPluginManager();

  /// Opens the native site-name dialog. Async: completes when the dialog
  /// reports a change; closing it without a change completes nothing,
  /// and Dart does not await these calls (see the dialog contract in
  /// docs/b8-b3-settings-a.md §2.5 / docs/b8-b4-enhance.md §2.5), so no
  /// refresh is owed then.
  @async
  void openSiteName();

  /// Opens the native novel/comic/audio/short-drama source dialogs.
  /// Async: each completes on dismiss (any outcome), because the dialog
  /// reports through onDismiss and a native dialog produces no Activity
  /// lifecycle event for Dart to refresh on. Dart re-fetches
  /// getEnhanceSettings() after awaiting.
  @async
  void openNovelSource();
  @async
  void openComicSource();
  @async
  void openAudioSource();
  @async
  void openShortDramaSource();

  /// Opens the native site-health dialog. Async: completes when the
  /// dialog reports (change path); Dart does not await.
  @async
  void openSiteHealthSort();

  /// Opens the native WebHome extension dialog. Async: completes when
  /// the dialog reports; Dart does not await.
  @async
  void openWebHomeExtension();

  /// Opens the native WebHome theme dialog. Async: completes when the
  /// dialog reports; Dart does not await.
  @async
  void openWebHomeTheme();

  /// Opens the native CSP warmup dialog. Async: completes when the
  /// dialog reports; Dart does not await.
  @async
  void openCspWarmup();

  /// Opens the native viewing-record sync dialog. Async: completes when
  /// the dialog dismisses (its callback runs from onDismiss); Dart does
  /// not await.
  @async
  void openPlaybackWebhook();

  /// Opens the native manage-page dialog. Fire-and-forget: it changes no
  /// setting and reports nothing back.
  void openManagePage();

  /// Opens the native remote-trust dialog. Async: completes when the
  /// dialog reports; Dart does not await.
  @async
  void openRemoteTrust();

  /// Opens the native Git cloud dialog. Async: completes when the dialog
  /// reports; Dart does not await.
  @async
  void openGitCloud();

  /// Opens the native shell-proxy dialog. Async: completes when the
  /// dialog reports (its callback runs from onDismiss); Dart does not
  /// await.
  @async
  void openShellProxy();

  /// Opens the native login-state learning dialog. Async: completes when
  /// the dialog reports; Dart does not await.
  @async
  void openLoginState();

  /// Custom CSP row: asks for file access first (PermissionX, via the
  /// host Activity), then opens the native custom-CSP dialog when
  /// granted. Async: completes when the dialog reports; if permission is
  /// denied the host shows a toast and completes nothing, and Dart does
  /// not await.
  @async
  void openCustomCsp();

  /// Opens the native one-key sync dialog. Fire-and-forget: it changes
  /// no setting on this page and reports nothing back.
  void openOneKeySync();

  /// Long-press actions (native onLongClickListener): clear the stored
  /// site-health samples / WebHome extensions and show a toast.
  /// Dart re-fetches afterwards.
  void clearSiteHealth();
  void clearWebHomeExtension();

  /// Toolbar menu: open the Mooyingshi repo in a browser (GitHub / CNB).
  /// Fire-and-forget; the host shows a toast when no browser is found.
  void openGithubRepo();
  void openCnbRepo();
}

/// One row of the enhance page, in native display order. The key is a
/// fixed token (never a locale string); Dart switches on it for the icon,
/// circle color, chevron and tap handler.
class B4EnhanceRow {
  B4EnhanceRow({
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

class B4EnhanceSettings {
  B4EnhanceSettings({
    required this.screenTitle,
    required this.repoGithubTitle,
    required this.repoCnbTitle,
    required this.rows,
  });

  String screenTitle;
  String repoGithubTitle;
  String repoCnbTitle;
  List<B4EnhanceRow> rows;
}
