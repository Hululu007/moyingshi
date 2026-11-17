import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2/b20_hk_messages.dart: generated Dart
    // code lives inside the module; the generated Java code lands in
    // the host app source tree so Gradle compiles it. Paths are
    // relative to the directory `dart run pigeon` runs from (the module
    // root, see .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b21_lab_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B21Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B21Messages',
    ),
  ),
)
@HostApi()
abstract class B21HostApi {
  /// B21 feature flag (persisted in Setting, default false = native
  /// LabActivity / LabDetailActivity). The flag gates every diversion
  /// point: the mobile HomeActivity bottom-bar lab item, the mobile
  /// local-source bottom-bar item (when lab nav is on), the mobile
  /// SettingFragment lab row, the mobile SettingEnhanceFragment lab
  /// row, and the B4 / B7 / B11 Flutter screens' openLab paths.
  /// The leanback SettingEnhanceActivity lab row is untouched, and
  /// the LabRuntimeService notification intent builds its Intent
  /// directly (never through LabActivity.start), so TV and the
  /// "show running" notification always stay native.
  bool isB21Enabled();
  void setB21Enabled(bool enabled);

  /// Static page chrome (toolbar title, group labels, menu labels,
  /// empty texts, import-dialog strings). Synchronous: Dart renders
  /// the page chrome from this before the first lab snapshot
  /// arrives. All strings come from the host; Dart hard-codes no
  /// locale strings. The detail-page button labels that are
  /// hardcoded literals in activity_lab_detail.xml ("下载安装" /
  /// "卸载" / "命令列表" / "暂无命令") are returned here verbatim
  /// from the same source; the download label itself is dynamic
  /// ("更新" vs "下载安装") and travels in the detail snapshot.
  B21LabShell getLabShell();

  /// One-shot snapshot of the lab list (native LabActivity.render:
  /// LabConfig.getLabRoot() groups + items, plus the mobile-only
  /// local-source group and the Hk miniapp group). The first call
  /// also runs LabEnv.syncVodPlusAssets + LabConfig.reload +
  /// autoInstall, exactly like native reload(); later calls build
  /// the snapshot from the cached root (no reload). Runs on the
  /// host's single lab worker thread; completes exactly once on
  /// every path; the 60s watchdog completes with an error snapshot
  /// (config load can hit the network when the source is a URL).
  /// Also carries the file/dir-pick state (pickState/pickEpoch/
  /// pickMessage): Dart polls this while a pick or a local-dir scan
  /// is in flight (B19 polling precedent) because both wait on the
  /// user or on a background scan and cannot fit a watchdog.
  /// Resumed refetch follows plan §4.6. The list page also polls
  /// this every 2s while mounted (same cadence as the native detail
  /// page's 2s handler): the host has no EventChannel, and the
  /// auto-install dialogs + background installs would otherwise
  /// leave stale "未安装" rows behind.
  @async
  B21LabList getLabList();

  /// Forces a config reload (native reload(): syncVodPlusAssets +
  /// LabConfig.reload + autoInstall) and returns the fresh snapshot.
  /// Pull-to-refresh calls this; getLabList does not reload after
  /// the first call.
  @async
  B21LabList reloadLabList();

  /// Current lab source settings (native showSettings dialog
  /// fields: source local/URL, URL or local path, root override,
  /// foreground / battery / global-proxy switches, proxy port and
  /// no-proxy list). Synchronous pure read. Also carries the
  /// local-config-file pick state: Dart polls this while the
  /// settings dialog is open after tapping the folder button.
  B21LabSettings getLabSettings();

  /// Saves the lab source settings (native showSettings positive
  /// handler, step for step: setSource / setUrl / setRoot /
  /// setForeground / setBattery / setGlobalProxy / setGlobalProxyPort
  /// / setGlobalProxyNoProxy, then LabProcManager.updateService and
  /// a config reload; foreground on requests the notification
  /// permission, battery on opens the system battery-optimization
  /// settings). Returns the refreshed list snapshot.
  @async
  B21LabList saveLabSettings(B21LabSettingsInput input);

  /// Opens the native lab about dialog (native showAbout).
  /// Fire-and-forget; the dialog is native and stays native.
  void openLabAbout();

  /// Decodes pasted import text (native decodeImport: a
  /// "VodPlusLabConfig://" prefix is base64-decoded, anything else
  /// is used as-is). Synchronous pure computation; an empty or
  /// undecodable input returns errorText and Dart keeps the import
  /// dialog open (native toasts "无效的导入数据" and keeps the
  /// dialog open).
  B21LabImportDecode decodeLabImport(String text);

  /// Imports a lab package from decoded JSON (native
  /// LabConfig.saveImported + reload). Returns the refreshed list
  /// snapshot with the lab_import_success notice.
  @async
  B21LabList importLabPackage(String json);

  /// Fire-and-forget: open the host file picker for a lab package
  /// archive (native pickImport: PermissionUtil.requestFile +
  /// ACTION_OPEN_DOCUMENT). The picked file is copied to the cache
  /// dir and imported host-side (LabEnv.importPackage on a
  /// background thread, exactly like native importArchive); Dart
  /// learns the outcome by polling getLabList() for
  /// pickState/pickEpoch. A cancelled picker stays silent, like
  /// native (no pickMessage).
  void requestLabImportFile();

  /// Fire-and-forget: open the host file picker for the local lab
  /// config file (native openLocalPicker: ACTION_OPEN_DOCUMENT).
  /// The picked URI is stored host-side as the local path and the
  /// source is switched to local, exactly like native
  /// onActivityResult(REQUEST_LOCAL_CONFIG); Dart learns the new
  /// path by polling getLabSettings() for pickEpoch.
  void requestLabLocalConfigFile();

  /// Fire-and-forget: pick the local-source scan directory (native
  /// pickLocalSourceDir: FileChooser directory picker, then the
  /// scanLocalDir background scan + reloadLocalConfig). Dart learns
  /// the outcome by polling getLabList() for pickState/pickEpoch.
  void pickLabLocalSourceDir();

  /// Sets a local source as the home site and closes the lab screen
  /// (native onItemClick for a non-miniapp site: VodConfig.setHome
  /// + ConfigEvent.vod() + finish). Fire-and-forget.
  void setLabLocalSourceHome(String key);

  /// Deletes one local source (native onDeleteClick:
  /// LocalSource.removeByKeys + reloadLocalConfig). Returns the
  /// refreshed list snapshot with the lab_local_source_deleted
  /// notice.
  @async
  B21LabList deleteLabLocalSource(String key);

  /// Opens one miniapp's own list page (native onItemClick for a
  /// miniapp site: the ".hk_list" implicit intent with the miniapp
  /// title). Fire-and-forget; the list page stays native.
  void openLabMiniappList(String key);

  /// Deletes one miniapp site from the embedded miniapp list
  /// (native onDeleteClick in the miniapp group:
  /// LocalSource.removeByKeys + reloadLocalConfig). Returns the
  /// refreshed list snapshot with the lab_local_source_deleted
  /// notice.
  @async
  B21LabList deleteLabMiniapp(String key);

  /// Opens the Hk miniapp manager (native openMiniappManager: when
  /// the B20 flag is on, the Flutter /hk-manager page; otherwise
  /// the ".hk_miniapp" implicit intent). From the Flutter lab page
  /// the B20-on path is start-then-finish (B15/B19 precedent, same
  /// G2 cross-screen race); the native path stacks on top and
  /// returns to the Flutter lab page. Fire-and-forget.
  void openLabMiniappManager();

  /// Opens the native local-source dialog (native
  /// openLocalSourceDialog: the ".local_source_dialog" implicit
  /// intent). Fire-and-forget; the dialog stays native and stacks
  /// on top of the Flutter lab page.
  void openLabLocalSourceDialog();

  /// One-shot snapshot of one lab package's detail page (native
  /// LabDetailActivity.reload: LabConfig.reload + findItem by name
  /// + applyCommandOverrides + bind). Runs on the host's single lab
  /// worker thread; completes exactly once on every path; the 60s
  /// watchdog completes with a not-found snapshot (config load can
  /// hit the network when the source is a URL). A missing item
  /// completes with found=false and Dart pops the detail page
  /// (native toasts "找不到模块" and finishes).
  @async
  B21LabDetail getLabDetail(String name);

  /// Lightweight detail state for the 2s poll (native
  /// LabDetailActivity.updateButtons + LabCommandAdapter
  /// .refreshStates, on the cached root — no config reload).
  /// Synchronous pure read; Dart merges the command states by id.
  /// found=false means the item disappeared; Dart pops the page.
  B21LabDetailState pollLabDetail(String name);

  /// Downloads / installs / updates one package (native
  /// LabDetailActivity.onDownload, step for step: unavailable toasts
  /// "该包暂未上线"; installed + newer version shows the native
  /// "确认更新" dialog; otherwise LabActions.installWithDialog).
  /// Fire-and-forget: the native progress dialog owns the UI, and
  /// the detail page's 2s poll picks up the installed state.
  void downloadLabItem(String name);

  /// Uninstalls one package (native LabDetailActivity.onUninstall:
  /// the native "确认卸载" dialog, then LabActions.uninstall which
  /// stops all running commands first). Fire-and-forget: the native
  /// confirm dialog owns the UI, and the detail page's 2s poll
  /// picks up the uninstalled state.
  void uninstallLabItem(String name);

  /// Opens the native interactive terminal for one package (native
  /// LabTerminalActivity.start). Fire-and-forget; the terminal stays
  /// native (interactive shell I/O needs a streaming channel, which
  /// this plan does not introduce — plan §6 P4).
  void openLabTerminal(String name);

  /// Opens the native add-command dialog for one package (native
  /// LabCommandEditDialog.show with a null command). Fire-and-
  /// forget; the dialog is native and stays native, and the detail
  /// page's 2s poll picks up the added command.
  void addLabCommand(String name);

  /// Opens the native edit-command dialog for one command (native
  /// LabCommandEditDialog.show; save writes a command override or
  /// updates the custom-command list, delete stops the command and
  /// removes the override / custom entry). Fire-and-forget; the
  /// dialog is native and stays native, and the detail page's 2s
  /// poll picks up the change.
  void editLabCommand(String name, String commandId);

  /// Opens the native command sheet for one command (native
  /// LabCommandSheet.show: variables, download, clicks, run/stop/
  /// output buttons; run opens the native LabOutputActivity).
  /// Fire-and-forget; the sheet is native and stays native, and the
  /// detail page's 2s poll picks up the running state.
  void openLabCommandSheet(String name, String commandId);

  /// Runs or stops one command from its row button (native
  /// LabDetailActivity.onAction, step for step: running stops it
  /// with the lab_command_stopped notice; otherwise the sheet is
  /// shown and run). Fire-and-forget; the sheet / output page are
  /// native, and the detail page's 2s poll picks up the state.
  void toggleLabCommand(String name, String commandId);

  /// Resets one package's command list (native
  /// LabDetailActivity.onRefreshCommands: the native confirm dialog,
  /// then LabActions.stopItem + clearCommandCache +
  /// clearCommandOverrides + reload). The confirm dialog is native;
  /// the @async call completes on every dismiss path (confirm,
  /// cancel, back) with the fresh detail snapshot.
  @async
  B21LabDetail refreshLabCommands(String name);

  /// One package's settings form (native
  /// LabDetailActivity.showPackageSettings: one row per
  /// LabModels.Setting, typed text / number / select / switch /
  /// directory, prefilled from LabConfig.loadUserSettings).
  /// Synchronous pure read. Also carries the directory-pick state:
  /// Dart polls this while the settings dialog is open after
  /// tapping a directory row's folder button.
  B21LabSettingsForm getLabSettingsForm(String name);

  /// Saves one package's settings form (native showPackageSettings
  /// save: LabConfig.saveUserSettings + the "设置已保存" notice).
  /// Returns the fresh detail snapshot. The directory rows' values
  /// arrive as plain strings (the folder button is the only picker).
  @async
  B21LabDetail saveLabSettingsForm(
    String name,
    List<B21LabSettingValue> values,
  );

  /// Fire-and-forget: open the host picker for a directory-type
  /// settings row (native LabFilePicker.pick with directory=true:
  /// the native file-browser dialog when external storage is
  /// readable, otherwise the SAF tree picker). The picked path is
  /// reported through getLabSettingsForm()'s pickValue/pickEpoch;
  /// Dart polls while the settings dialog is open. A cancelled
  /// picker leaves the field unchanged, like native.
  void requestLabSettingDirectory();

  /// Exports one package (native LabDetailActivity.exportPackage:
  /// the native progress dialog on a background thread, then the
  /// "已导出到" notice). Fire-and-forget: the native dialog owns
  /// the UI. Not installed toasts "该包尚未安装", like native.
  void exportLabPackage(String name);

  /// Destroys the lab session: shuts the worker down. Fire-and-
  /// forget; called from the Dart page dispose (B18/B19 precedent).
  void disposeLab();
}

/// Static page chrome. All display strings come from the host.
class B21LabShell {
  B21LabShell({
    required this.title,
    required this.allLabel,
    required this.localSourceLabel,
    required this.miniappLabel,
    required this.switchGroupLabel,
    required this.refreshLabel,
    required this.importLabel,
    required this.settingsLabel,
    required this.helpLabel,
    required this.scanLabel,
    required this.emptyText,
    required this.localSourceEmptyText,
    required this.miniappEmptyText,
    required this.cancelLabel,
    required this.retryLabel,
    required this.confirmLabel,
    required this.importTitle,
    required this.importHint,
    required this.importPlaceholder,
    required this.importSelectFileLabel,
    required this.importButtonLabel,
    required this.downloadLabel,
    required this.uninstallLabel,
    required this.commandsTitle,
    required this.noCommandsText,
    required this.addCommandLabel,
    required this.refreshCommandsLabel,
    required this.terminalLabel,
    required this.exportLabel,
    required this.settingsMenuLabel,
    required this.deleteLabel,
  });

  /// lab_title, the toolbar title.
  String title;

  /// "全部" (hardcoded in native LabActivity.render), the "all" group.
  String allLabel;

  /// setting_local_source, the local-source group name.
  String localSourceLabel;

  /// "海阔小程序" (hardcoded in native LabActivity.render), the
  /// miniapp group name.
  String miniappLabel;

  /// lab_switch_group, the group-switch FAB description.
  String switchGroupLabel;

  /// lab_refresh, the pull-to-refresh / retry label.
  String refreshLabel;

  /// lab_import, the toolbar "+" (import package) label.
  String importLabel;

  /// nav_setting, the toolbar settings label.
  String settingsLabel;

  /// menu_help, the toolbar help ("?") label.
  String helpLabel;

  /// lab_local_source_scan, the "scan directory" menu label, visible
  /// only in the local-source group.
  String scanLabel;

  /// lab_empty_no_source, the empty state for package groups.
  String emptyText;

  /// lab_local_source_empty, the empty state for the local-source group.
  String localSourceEmptyText;

  /// hk_miniapp_empty, the empty state for the miniapp group.
  String miniappEmptyText;

  /// dialog_cancel.
  String cancelLabel;

  /// error_retry, for the error state retry button.
  String retryLabel;

  /// dialog_confirm.
  String confirmLabel;

  /// lab_import_title, the import dialog title.
  String importTitle;

  /// lab_import_hint, the import dialog hint.
  String importHint;

  /// lab_import_placeholder, the import dialog input hint.
  String importPlaceholder;

  /// lab_import_select_file, the "choose a file" link in the import dialog.
  String importSelectFileLabel;

  /// lab_import, the import dialog's confirm button.
  String importButtonLabel;

  /// "下载安装" (hardcoded in activity_lab_detail.xml), the
  /// download button's default label. The live label travels in
  /// the detail snapshot (it flips to "更新" when a newer version
  /// is available).
  String downloadLabel;

  /// "卸载" (hardcoded in activity_lab_detail.xml), the uninstall button.
  String uninstallLabel;

  /// "命令列表" (hardcoded in activity_lab_detail.xml), the
  /// commands section title.
  String commandsTitle;

  /// "暂无命令" (hardcoded in activity_lab_detail.xml), the empty
  /// commands text.
  String noCommandsText;

  /// lab_add_custom, the add-command button tooltip.
  String addCommandLabel;

  /// The refresh-commands button tooltip. Native shows only the
  /// refresh icon; the host returns the reset dialog's title
  /// ("重置命令列表", hardcoded in LabDetailActivity) as the label.
  String refreshCommandsLabel;

  /// lab_terminal, the terminal button tooltip.
  String terminalLabel;

  /// lab_export_file, the export menu item.
  String exportLabel;

  /// nav_setting, the package-settings menu item.
  String settingsMenuLabel;

  /// setting_delete, the per-row delete button tooltip.
  String deleteLabel;
}

/// One lab group tab (native LabGroupAdapter.Row).
class B21LabGroup {
  B21LabGroup({
    required this.id,
    required this.name,
  });

  /// Group id ("all", "__local_source__", "__miniapp__", or a
  /// lab.json group id).
  String id;

  /// Group display name.
  String name;
}

/// One lab package row (native LabPackageAdapter row). All display
/// strings and computed values come from the host; Dart only renders.
class B21LabPackage {
  B21LabPackage({
    required this.name,
    required this.titleText,
    required this.info,
    required this.iconUrl,
    required this.versionText,
    required this.sizeText,
    required this.statusText,
    required this.statusKind,
    required this.hasUpdate,
    required this.groupId,
  });

  /// Package name (the identity; tap opens the detail page).
  String name;

  /// "name + displayVersion" (native holder.title).
  String titleText;

  /// Package info text (native holder.info, "" when null).
  String info;

  /// Package icon URL (native Glide load; "" when null/empty —
  /// Dart shows the placeholder).
  String iconUrl;

  /// Display version (native holder.version).
  String versionText;

  /// Download size text (native holder.size, "" when unknown).
  String sizeText;

  /// Status text: "未上线" / lab_running / lab_installed /
  /// lab_not_installed (native holder.status).
  String statusText;

  /// Status kind: "unavailable" / "running" / "installed" /
  /// "not_installed" (drives the status chip colour in Dart).
  String statusKind;

  /// True when installed and a newer version is available (native
  /// newVersion badge).
  bool hasUpdate;

  /// The lab.json group id ("" when null); Dart filters packages by
  /// the selected group ("all" shows every package with show=true).
  String groupId;
}

/// One local-source / miniapp row (native LocalSourceAdapter row in
/// the lab page's embedded lists).
class B21LabSite {
  B21LabSite({
    required this.key,
    required this.name,
  });

  /// Site key (the row identity + the delete / set-home key).
  String key;

  /// Site display name (local sources: Site name; miniapps: the
  /// "标题⁽ʰᵏ⁾┃[类型]" name, exactly what the native adapter shows).
  String name;
}

/// One lab-list snapshot. Transient per-call fields (noticeText) are
/// set only by the call that produced them; a plain getLabList()
/// returns them empty.
class B21LabList {
  B21LabList({
    required this.groups,
    required this.packages,
    required this.localSources,
    required this.miniappSites,
    required this.loadErrorText,
    required this.noticeText,
    required this.pickState,
    required this.pickEpoch,
    required this.pickMessage,
    required this.isHkMiniapp,
  });

  /// Groups in native render() order: all, local source (mobile
  /// only), miniapp (mobile + Hk switch on), then lab.json groups
  /// in file order.
  List<B21LabGroup> groups;

  /// Packages with show=true, in lab.json order (native render()
  /// filters by group in Dart).
  List<B21LabPackage> packages;

  /// Local sources (LocalSource.getSites() minus miniapps), for the
  /// "__local_source__" group.
  List<B21LabSite> localSources;

  /// Miniapp sites (LocalSource.getSites() filtered by
  /// LocalSource.isMiniapp), for the "__miniapp__" group.
  List<B21LabSite> miniappSites;

  /// Whole-snapshot error text (config load failure, or the
  /// watchdog error). Empty means no error.
  String loadErrorText;

  /// One-shot notice for the action that produced this snapshot
  /// (imported / deleted / scanned / busy). Empty means no notice;
  /// Dart shows it as a toast once.
  String noticeText;

  /// Pick/scan state: "idle", "picking" (the user is choosing a
  /// file / directory in the system picker), "importing" (a picked
  /// archive is being imported on a background thread) or
  /// "scanning" (a local directory is being scanned).
  String pickState;

  /// Monotonically increasing pick outcome counter; Dart shows
  /// pickMessage as a toast when this changes and pickState is idle.
  int pickEpoch;

  /// Pick outcome text (imported / import failed / scanned N items
  /// / too many files). Empty when there is nothing to report (e.g.
  /// the user cancelled the picker — native stays silent too).
  String pickMessage;

  /// True when the Hk miniapp switch is on (the miniapp group is
  /// shown only then, exactly like native render()).
  bool isHkMiniapp;
}

/// One lab package detail snapshot (native LabDetailActivity.bind +
/// updateButtons). Transient per-call fields (noticeText) are set
/// only by the call that produced them.
class B21LabDetail {
  B21LabDetail({
    required this.found,
    required this.name,
    required this.title,
    required this.versionText,
    required this.info,
    required this.iconUrl,
    required this.installed,
    required this.running,
    required this.hasUpdate,
    required this.downloadLabel,
    required this.downloadVisible,
    required this.uninstallVisible,
    required this.statusText,
    required this.statusKind,
    required this.hasSettings,
    required this.commands,
    required this.loadErrorText,
    required this.noticeText,
  });

  /// False when the item no longer exists (native toasts
  /// "找不到模块" and finishes); Dart pops the detail page.
  bool found;

  /// Package name (the identity).
  String name;

  /// Toolbar title (the package name).
  String title;

  /// "name + displayVersion" (native version line).
  String versionText;

  /// Package info text ("" when null).
  String info;

  /// Package icon URL ("" when null/empty — Dart shows the
  /// placeholder).
  String iconUrl;

  /// True when the package is installed on disk.
  bool installed;

  /// True when any of its commands (built-in or custom) is running.
  bool running;

  /// True when installed and a newer version is available.
  bool hasUpdate;

  /// Download button label: "更新" when hasUpdate, "下载安装"
  /// otherwise (native updateButtons).
  String downloadLabel;

  /// Download button visibility: hidden when installed and no
  /// update is available (native updateButtons).
  bool downloadVisible;

  /// Uninstall button visibility: shown only when installed.
  bool uninstallVisible;

  /// Status text: lab_running / lab_installed / lab_not_installed.
  String statusText;

  /// Status kind: "running" / "installed" / "not_installed".
  String statusKind;

  /// True when the package declares settings (native menu_lab_detail
  /// is inflated only then — the export menu item lives in the same
  /// menu, so export is only offered when hasSettings is true).
  bool hasSettings;

  /// Commands: built-in commands in file order, then custom
  /// commands in stored order (native LabCommandAdapter.setItem).
  List<B21LabCommand> commands;

  /// Whole-snapshot error text (config load failure, or the
  /// watchdog error). Empty means no error.
  String loadErrorText;

  /// One-shot notice for the action that produced this snapshot.
  /// Empty means no notice; Dart shows it as a toast once.
  String noticeText;
}

/// One command row (native LabCommandAdapter row).
class B21LabCommand {
  B21LabCommand({
    required this.id,
    required this.displayName,
    required this.description,
    required this.running,
    required this.actionLabel,
    required this.supported,
    required this.minVersion,
    required this.isCustom,
  });

  /// Command id (built-in id, or "custom_<...>" for custom ones).
  String id;

  /// Display name (name, or description when name is null — native
  /// holder.name).
  String displayName;

  /// Command description ("" when null).
  String description;

  /// True when this command is currently running.
  bool running;

  /// Row action label: lab_stop when running, lab_run otherwise.
  String actionLabel;

  /// False when the app version is below min_version (native
  /// isSupported check — the sheet toasts in that case).
  bool supported;

  /// Minimum app version code (0 when unset).
  int minVersion;

  /// True for user-added custom commands (LabCustomCommands).
  bool isCustom;
}

/// Lightweight detail state for the 2s poll (native updateButtons +
/// refreshStates on the cached root).
class B21LabDetailState {
  B21LabDetailState({
    required this.found,
    required this.installed,
    required this.running,
    required this.hasUpdate,
    required this.downloadLabel,
    required this.downloadVisible,
    required this.uninstallVisible,
    required this.statusText,
    required this.statusKind,
    required this.commandStates,
  });

  /// False when the item disappeared from the cached root; Dart
  /// pops the detail page.
  bool found;

  /// True when the package is installed on disk.
  bool installed;

  /// True when any of its commands is running.
  bool running;

  /// True when installed and a newer version is available.
  bool hasUpdate;

  /// Download button label ("更新" / "下载安装").
  String downloadLabel;

  /// Download button visibility.
  bool downloadVisible;

  /// Uninstall button visibility.
  bool uninstallVisible;

  /// Status text: lab_running / lab_installed / lab_not_installed.
  String statusText;

  /// Status kind: "running" / "installed" / "not_installed".
  String statusKind;

  /// Per-command running states, in the same order as the detail
  /// snapshot's commands list; Dart merges by id.
  List<B21LabCommandState> commandStates;
}

/// One command's polled running state.
class B21LabCommandState {
  B21LabCommandState({
    required this.id,
    required this.running,
    required this.actionLabel,
  });

  /// Command id.
  String id;

  /// True when this command is currently running.
  bool running;

  /// Row action label: lab_stop when running, lab_run otherwise.
  String actionLabel;
}

/// Current lab source settings (native showSettings dialog fields).
class B21LabSettings {
  B21LabSettings({
    required this.source,
    required this.sourceLocalLabel,
    required this.sourceUrlLabel,
    required this.url,
    required this.localPath,
    required this.rootOverride,
    required this.foreground,
    required this.battery,
    required this.globalProxy,
    required this.proxyPort,
    required this.proxyNoProxy,
    required this.title,
    required this.saveLabel,
    required this.cancelLabel,
    required this.localPathHint,
    required this.urlHint,
    required this.rootHint,
    required this.sourceHint,
    required this.sourceDropdownHint,
    required this.foregroundTitle,
    required this.foregroundHint,
    required this.batteryTitle,
    required this.batteryHint,
    required this.proxyTitle,
    required this.proxyHint,
    required this.proxyPortHint,
    required this.proxyNoProxyHint,
    required this.pickState,
    required this.pickEpoch,
    required this.pickMessage,
  });

  /// Config source: 0 = local file, 1 = URL (LabConfig
  /// SOURCE_LOCAL / SOURCE_URL).
  int source;

  /// lab_source_local, the "local file" dropdown label.
  String sourceLocalLabel;

  /// lab_source_url, the "URL" dropdown label.
  String sourceUrlLabel;

  /// Config URL (shown when source is URL).
  String url;

  /// Local config path (shown when source is local; read-only, the
  /// folder button picks it).
  String localPath;

  /// User-typed root override ("" when unset — native shows the raw
  /// override, no fallback).
  String rootOverride;

  /// Foreground service switch.
  bool foreground;

  /// Battery-optimization switch.
  bool battery;

  /// Global proxy switch.
  bool globalProxy;

  /// Global proxy port (7890 default).
  int proxyPort;

  /// Global proxy no-proxy list.
  String proxyNoProxy;

  /// lab_source_title, the dialog title.
  String title;

  /// android.R.string.ok, the save button.
  String saveLabel;

  /// dialog_cancel.
  String cancelLabel;

  /// The local-path field hint (lab_source_placeholder — native
  /// shows the same hint on the shared URL/path input).
  String localPathHint;

  /// The URL field hint (lab_source_placeholder).
  String urlHint;

  /// The root-override field hint ("根目录（留空跟随配置）",
  /// hardcoded in dialog_lab_settings.xml).
  String rootHint;

  /// The dialog's top explainer text (lab_source_hint).
  String sourceHint;

  /// The source dropdown hint (lab_source_switch, "配置源").
  String sourceDropdownHint;

  /// lab_keepalive_foreground_title.
  String foregroundTitle;

  /// lab_keepalive_foreground_hint.
  String foregroundHint;

  /// lab_keepalive_battery_title.
  String batteryTitle;

  /// lab_keepalive_battery_hint.
  String batteryHint;

  /// lab_global_proxy_title.
  String proxyTitle;

  /// lab_global_proxy_hint.
  String proxyHint;

  /// lab_global_proxy_port.
  String proxyPortHint;

  /// lab_global_proxy_no_proxy.
  String proxyNoProxyHint;

  /// Local-config-file pick state: "idle" or "picking".
  String pickState;

  /// Monotonically increasing pick counter; Dart fills the local
  /// path field when this changes and pickState is idle.
  int pickEpoch;

  /// Picked local-config URI ("" when nothing was picked yet or the
  /// user cancelled — native keeps the old path then).
  String pickMessage;
}

/// Lab source settings input for saveLabSettings (flat, no nested
/// types — Pigeon codegen stays simple).
class B21LabSettingsInput {
  B21LabSettingsInput({
    required this.source,
    required this.url,
    required this.localPath,
    required this.rootOverride,
    required this.foreground,
    required this.battery,
    required this.globalProxy,
    required this.proxyPort,
    required this.proxyNoProxy,
  });

  /// Config source: 0 = local file, 1 = URL.
  int source;

  /// Config URL (applied only when source is URL and non-empty,
  /// exactly like native).
  String url;

  /// Local config path.
  String localPath;

  /// Root override.
  String rootOverride;

  /// Foreground service switch.
  bool foreground;

  /// Battery-optimization switch.
  bool battery;

  /// Global proxy switch.
  bool globalProxy;

  /// Global proxy port (<= 0 falls back to 7890, like native).
  int proxyPort;

  /// Global proxy no-proxy list.
  String proxyNoProxy;
}

/// One package's settings form (native showPackageSettings dialog).
class B21LabSettingsForm {
  B21LabSettingsForm({
    required this.name,
    required this.title,
    required this.saveLabel,
    required this.cancelLabel,
    required this.rows,
    required this.pickState,
    required this.pickEpoch,
    required this.pickValue,
  });

  /// Package name (the identity).
  String name;

  /// Dialog title ("<name> 设置", hardcoded in native).
  String title;

  /// "保存" (hardcoded in native).
  String saveLabel;

  /// dialog_cancel.
  String cancelLabel;

  /// One row per LabModels.Setting, in file order.
  List<B21LabSettingRow> rows;

  /// Directory-pick state: "idle" or "picking".
  String pickState;

  /// Monotonically increasing pick counter; Dart fills the
  /// directory field when this changes and pickState is idle.
  int pickEpoch;

  /// Last picked directory path ("" when nothing was picked yet or
  /// the user cancelled — native leaves the field unchanged then).
  String pickValue;
}

/// One package-settings row (native LabDetailActivity.inflateSetting).
class B21LabSettingRow {
  B21LabSettingRow({
    required this.key,
    required this.name,
    required this.type,
    required this.value,
    required this.hint,
    required this.options,
  });

  /// Setting key.
  String key;

  /// Setting display name.
  String name;

  /// Row type: "text" / "number" / "select" / "switch" /
  /// "directory" (anything else renders as text, like native).
  String type;

  /// Current value (stored value, or the declared default).
  String value;

  /// Setting hint ("" when null).
  String hint;

  /// Select options (empty for other types).
  List<B21LabSettingOption> options;
}

/// One select option (native LabModels.Option).
class B21LabSettingOption {
  B21LabSettingOption({
    required this.label,
    required this.value,
  });

  /// Option display label.
  String label;

  /// Option value.
  String value;
}

/// One saved package-settings value (flat key/value pair for
/// saveLabSettingsForm — avoids a Pigeon Map type).
class B21LabSettingValue {
  B21LabSettingValue({
    required this.key,
    required this.value,
  });

  /// Setting key.
  String key;

  /// Setting value.
  String value;
}

/// Decoded import text (native decodeImport).
class B21LabImportDecode {
  B21LabImportDecode({
    required this.json,
    required this.errorText,
  });

  /// Decoded JSON ("" when the input was empty or undecodable).
  String json;

  /// Error text ("" when decoding succeeded; Dart keeps the import
  /// dialog open and shows this inline when non-empty).
  String errorText;
}
