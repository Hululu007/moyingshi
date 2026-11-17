import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2/b14_messages.dart: generated Dart code
    // lives inside the module; the generated Java code lands in the host
    // app source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b15_local_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B15Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B15Messages',
    ),
  ),
)
@HostApi()
abstract class B15HostApi {
  /// B15 feature flag (persisted in Setting, default false = native
  /// FileActivity / AppBrandingActivity). The flag only gates the
  /// mobile entries; the TV entries stay native (the host diversion
  /// checks Util.isLeanback()).
  bool isB15Enabled();
  void setB15Enabled(bool enabled);

  /// One-shot snapshot of one directory (native FileActivity.update +
  /// list). dirPath empty means the root (Path.root()). The host
  /// requests storage permission on the first call of this engine
  /// lifetime (PermissionUtil.requestFile, native checkPermission
  /// parity) and lists afterwards regardless of the grant, like
  /// native. Completes exactly once on every path; the 35s watchdog
  /// completes with an error snapshot (Dart shows its error/retry
  /// state). Directory navigation is pure Dart: Dart calls this again
  /// with the new path.
  @async
  B15LocalScreen getLocalFiles(String dirPath, bool selectDir, bool readMode);

  /// File-row tap (native FileActivity.onItemClick for a file). In
  /// read mode with a readable extension the host opens the native
  /// WebReaderActivity and the Flutter page stays (back returns to
  /// the list, like native); otherwise the host sets RESULT_OK with
  /// the file Uri and finishes the C1 screen, so the caller's
  /// ActivityResultLauncher receives the result exactly like the
  /// native FileActivity. Fire-and-forget: Dart refetches on resumed
  /// after the reader returns.
  void openLocalItem(String path, bool readMode);

  /// "Select current directory" row in select-dir mode (native
  /// FileAdapter position 0 / onCurrentDirClick). The host sets
  /// RESULT_OK with the directory Uri and finishes the C1 screen.
  /// Fire-and-forget.
  void returnCurrentDir(String path);

  /// One-shot snapshot of the app-branding page (native
  /// AppBrandingActivity: two icon options + save/cancel). The host
  /// reads the current mode from AppBranding; Dart only renders the
  /// selection. Completes exactly once; the 35s watchdog completes
  /// with an error snapshot.
  @async
  B15BrandingScreen getBranding();

  /// Save the chosen icon (native AppBrandingActivity.save: putIconMode
  /// + applyLauncherIcon + restart via the launcher intent).
  /// Fire-and-forget: the host restarts the app, so the C1 screen
  /// goes away with it; Dart does not await a result.
  void saveBrandingIcon(int mode);
}

/// One directory snapshot. All display strings come from the host;
/// Dart only renders. No thumbnails anywhere (plan: 无图); rows show a
/// folder/file Material icon, like the native ic_folder/ic_file
/// drawables in FileAdapter.
class B15LocalScreen {
  B15LocalScreen({
    required this.currentPath,
    required this.parentPath,
    required this.isRoot,
    required this.selectDir,
    required this.readMode,
    required this.selectCurrentDirLabel,
    required this.emptyTitle,
    required this.emptyDescription,
    required this.emptyActionLabel,
    required this.retryLabel,
    required this.errorText,
    required this.items,
  });

  /// Absolute path of the listed directory (native toolbar title).
  String currentPath;

  /// Absolute path of the parent directory; empty at the root.
  String parentPath;

  /// True when currentPath is Path.root() (native isRoot: back exits
  /// the page instead of going up).
  bool isRoot;

  bool selectDir;
  bool readMode;

  /// lut_select_current_dir ("选择当前目录"); only shown when
  /// selectDir is true, as the first row (native FileAdapter
  /// position 0).
  String selectCurrentDirLabel;

  /// Empty-state texts (file_empty_title / file_empty_description /
  /// file_empty_action); the action rescans the current directory.
  String emptyTitle;
  String emptyDescription;
  String emptyActionLabel;

  /// error_retry, for the error state.
  String retryLabel;

  /// Whole-snapshot error text (load timeout: error_play_timeout).
  /// Empty means no error; Dart shows its error/retry state when
  /// non-empty (B8 errorScreen precedent).
  String errorText;

  /// Directory entries in native order (Path.list, or directories
  /// only + Path.sort in select-dir mode).
  List<B15LocalItem> items;
}

/// One file or directory row.
class B15LocalItem {
  B15LocalItem({
    required this.name,
    required this.path,
    required this.isDirectory,
  });

  String name;

  /// Absolute path; the tap target the host acts on.
  String path;
  bool isDirectory;
}

/// App-branding snapshot (native AppBrandingActivity). The two icon
/// previews are approximated in Dart with Material icons; the host
/// only supplies labels and the current selection.
class B15BrandingScreen {
  B15BrandingScreen({
    required this.title,
    required this.selectLabel,
    required this.currentLabel,
    required this.historyLabel,
    required this.selectedMode,
    required this.cancelLabel,
    required this.saveLabel,
    required this.errorText,
    required this.retryLabel,
  });

  /// setting_app_branding (toolbar title).
  String title;

  /// app_branding_icon_select (section label above the options).
  String selectLabel;

  /// app_branding_icon_current / app_branding_icon_history.
  String currentLabel;
  String historyLabel;

  /// AppBranding.getIconMode(): 0 = current, 1 = history.
  int selectedMode;

  /// dialog_negative (cancel) / dialog_positive (save).
  String cancelLabel;
  String saveLabel;

  /// Whole-snapshot error text; empty means no error.
  String errorText;

  /// error_retry, for the error state.
  String retryLabel;
}
