import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2/b18_widget_messages.dart: generated
    // Dart code lives inside the module; the generated Java code lands
    // in the host app source tree so Gradle compiles it. Paths are
    // relative to the directory `dart run pigeon` runs from (the module
    // root, see .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b19_plugin_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B19Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B19Messages',
    ),
  ),
)
@HostApi()
abstract class B19HostApi {
  /// Static page chrome (toolbar title, button labels, empty text,
  /// dialog button labels, remote-import dialog strings). Synchronous:
  /// Dart renders the page chrome from this before the first plugin
  /// snapshot arrives. All strings come from the host; Dart hard-codes
  /// no locale strings.
  B19PluginShell getPluginShell();

  /// One-shot snapshot of the installed plugin list (native
  /// PluginManagerActivity.readEntries: PluginSourceConfig.entries()
  /// re-read from SharedPreferences). Runs on the host's single plugin
  /// worker thread; completes exactly once on every path; the 35s
  /// watchdog completes with an error snapshot (Dart shows its
  /// error/retry state). Also carries the directory-pick state
  /// (pickState/pickEpoch/pickMessage): Dart polls this while a pick
  /// is in flight (B10 polling precedent) because the FileChooser
  /// directory picker waits on the user and cannot fit a 35s
  /// watchdog. Resumed refetch follows plan §4.6.
  @async
  B19PluginScreen getPluginScreen();

  /// Toggles one plugin (native setEnabled: write the config, save,
  /// then reload). Returns the post-reload snapshot. When another
  /// plugin task is running, completes at once with the current
  /// snapshot and a busy notice (native disables the buttons while
  /// busy, so this path is only reachable via a race). A save failure
  /// completes with a notice and the re-read snapshot, so the switch
  /// falls back to the persisted state, exactly like native.
  @async
  B19PluginScreen setPluginEnabled(String pluginId, bool enabled);

  /// Reloads all plugins (native scheduleReload: install + reload,
  /// then re-read). Returns the post-reload snapshot. When another
  /// plugin task is running, completes at once with the current
  /// snapshot and a busy notice.
  @async
  B19PluginScreen reloadPlugins();

  /// Fire-and-forget: request storage permission and open the host
  /// FileChooser directory picker (native chooseDirectory():
  /// PermissionUtil.requestFile + FileChooser.from(launcher)
  /// .showDirectory()). The picked directory is imported host-side
  /// (PluginPackageImporter.importFrom + reload); Dart learns the
  /// outcome by polling getPluginScreen() for pickState/pickEpoch.
  /// Ignored when a plugin task is running or a pick is already in
  /// flight. The permission denial and the invalid path both complete
  /// through the pick state (pickMessage), never through a dangling
  /// call.
  void requestPluginDirectory();

  /// Validates a remote plugin URL without downloading (native
  /// importFromUrl dialog validation: PluginRemoteSource.resolve).
  /// Synchronous pure computation. Dart keeps the URL dialog open
  /// with errorText when validation fails.
  B19RemoteResolve resolveRemoteUrl(String rawUrl);

  /// Remote import step 2: downloads the bundle (20s timeout) and
  /// indexes it (native startRemoteImport: download + inspect).
  /// Returns the candidates for the Dart selection dialog. The
  /// downloaded zip is held host-side (cache dir) until
  /// installRemoteCandidates or disposePlugins. On failure completes
  /// with an error index (Dart shows the error as a notice).
  @async
  B19RemoteIndex downloadRemoteBundle(String rawUrl);

  /// Builds the install-confirmation text for the selected candidates
  /// (native confirmInstall: names + combined permissions + the
  /// not-sandboxed warning + plain-language high-risk lines). The
  /// message is fully composed host-side; Dart only renders
  /// messageText and riskText (in the error color, bold). Synchronous
  /// pure computation over the held index.
  B19RemoteConfirm getRemoteConfirm(List<String> entryPaths);

  /// Remote import step 3: installs the selected candidates (native
  /// runBundleImport: importBundle + reload + re-read). Returns the
  /// refreshed plugin screen; the per-item outcome travels in
  /// remoteResultLines (shown as a result dialog when partial) or in
  /// noticeText (all-ok toast / batch-failed toast), exactly like
  /// native showRemoteResult.
  @async
  B19PluginScreen installRemoteCandidates(List<String> entryPaths);

  /// Reads one plugin's parameters (native editParams prefill:
  /// PluginEnvironmentStore.get). Synchronous: a SharedPreferences
  /// read. The title is host-formatted with the plugin display name.
  B19PluginParams getPluginParams(String pluginId);

  /// Saves one plugin's parameters (native editParams confirm:
  /// JSON-object validation, then PluginEnvironmentStore.put, then a
  /// reload — params only take effect after a reload). Returns the
  /// post-reload snapshot; on validation failure the screen carries
  /// dialogErrorText and Dart keeps the dialog open with that error.
  @async
  B19PluginScreen savePluginParams(String pluginId, String json);

  /// Builds the delete-confirmation text for one plugin (native
  /// confirmDelete). Synchronous pure computation.
  B19DeleteConfirm getDeleteConfirm(String pluginId);

  /// Deletes one plugin (native deletePlugin: remove the config entry
  /// + save, remove its stored params, delete the owned package
  /// directory, then reload + re-read). Returns the post-delete
  /// snapshot; save/delete failures travel in noticeText.
  @async
  B19PluginScreen deletePlugin(String pluginId);

  /// Destroys the plugin session: deletes the held remote zip when no
  /// task is running (native onDestroy) and shuts the worker down.
  /// Fire-and-forget; called from the Dart page dispose (B18
  /// disposeBrowse precedent).
  void disposePlugins();
}

/// Static page chrome. All display strings come from the host.
class B19PluginShell {
  B19PluginShell({
    required this.title,
    required this.importLabel,
    required this.remoteImportLabel,
    required this.reloadLabel,
    required this.emptyText,
    required this.emptyImportLabel,
    required this.cancelLabel,
    required this.confirmLabel,
    required this.retryLabel,
    required this.remoteTitle,
    required this.remoteHint,
    required this.remoteHelper,
    required this.remotePasteLabel,
    required this.remoteStartLabel,
    required this.paramsLabel,
    required this.deleteLabel,
    required this.selectAllLabel,
    required this.selectNoneLabel,
    required this.doImportLabel,
    required this.remoteConfirmTitle,
    required this.remoteInstallLabel,
    required this.remoteResultTitle,
    required this.remoteEmptyText,
    required this.busyText,
    required this.enableLabel,
    required this.disableLabel,
  });

  /// setting_plugin_manager, the toolbar title.
  String title;

  /// plugin_manager_import, the local import button.
  String importLabel;

  /// plugin_manager_remote_import, the URL import button.
  String remoteImportLabel;

  /// plugin_manager_reload, the reload button.
  String reloadLabel;

  /// plugin_manager_empty, the empty-state text.
  String emptyText;

  /// plugin_manager_import, the empty-state import button.
  String emptyImportLabel;

  /// dialog_cancel.
  String cancelLabel;

  /// dialog_confirm.
  String confirmLabel;

  /// error_retry, for the error state retry button.
  String retryLabel;

  /// plugin_manager_remote_title, the URL dialog title.
  String remoteTitle;

  /// plugin_manager_remote_hint, the URL input hint.
  String remoteHint;

  /// plugin_manager_remote_helper, the URL dialog helper text.
  String remoteHelper;

  /// plugin_manager_remote_paste, the paste-from-clipboard button.
  String remotePasteLabel;

  /// plugin_manager_remote_start, the URL dialog fetch button.
  String remoteStartLabel;

  /// plugin_manager_params, the per-row parameters button label.
  String paramsLabel;

  /// setting_delete, the per-row delete button label.
  String deleteLabel;

  /// plugin_manager_remote_select_all.
  String selectAllLabel;

  /// plugin_manager_remote_select_none.
  String selectNoneLabel;

  /// plugin_manager_remote_do_import.
  String doImportLabel;

  /// plugin_manager_remote_confirm_title.
  String remoteConfirmTitle;

  /// plugin_manager_remote_install.
  String remoteInstallLabel;

  /// plugin_manager_remote_result_title.
  String remoteResultTitle;

  /// plugin_manager_remote_empty.
  String remoteEmptyText;

  /// plugin_manager_remote_busy, shown when an action arrives while
  /// another plugin task is running.
  String busyText;

  /// setting_enable, the switch state text for an enabled plugin.
  String enableLabel;

  /// setting_disable, the switch state text for a disabled plugin.
  String disableLabel;
}

/// One installed plugin row. All display strings and computed values
/// come from the host; Dart only renders.
class B19PluginItem {
  B19PluginItem({
    required this.pluginId,
    required this.name,
    required this.version,
    required this.runtimeApi,
    required this.enabled,
    required this.metaText,
    required this.stateText,
  });

  /// PluginSourceConfig.Entry.id().
  String pluginId;

  /// Display name (entry.name(), or the id when the name is empty).
  String name;

  /// Entry.version().
  String version;

  /// Entry.runtimeApi() ("js" / "py" / "html").
  String runtimeApi;

  /// Entry.enabled().
  bool enabled;

  /// plugin_manager_entry_meta % version, runtimeApi, state.
  String metaText;

  /// setting_enable / setting_disable for the current state.
  String stateText;
}

/// One plugin-list snapshot. Transient per-call fields (noticeText,
/// dialogErrorText, remoteResultLines) are set only by the call that
/// produced them; a plain getPluginScreen() returns them empty.
class B19PluginScreen {
  B19PluginScreen({
    required this.title,
    required this.entries,
    required this.emptyText,
    required this.busy,
    required this.loadErrorText,
    required this.noticeText,
    required this.dialogErrorText,
    required this.pickState,
    required this.pickEpoch,
    required this.pickMessage,
    required this.remoteResultLines,
    required this.remoteResultTitle,
  });

  /// setting_plugin_manager, the toolbar title.
  String title;

  /// Installed plugins in config order (PluginSourceConfig.entries()).
  List<B19PluginItem> entries;

  /// plugin_manager_empty, shown when entries is empty.
  String emptyText;

  /// True while a plugin task (install / reload / delete / import)
  /// is running on the host worker; Dart disables its buttons.
  bool busy;

  /// Whole-snapshot error text (plugin_manager_load_failed % message,
  /// or error_play_timeout on watchdog). Empty means no error.
  String loadErrorText;

  /// One-shot notice for the action that produced this snapshot
  /// (import ok/failed, save failed, all-ok, batch failed, busy).
  /// Empty means no notice; Dart shows it as a toast once.
  String noticeText;

  /// One-shot dialog error for savePluginParams (params invalid /
  /// too large / busy). Non-empty means Dart keeps the params dialog
  /// open and shows this error inline.
  String dialogErrorText;

  /// Directory-pick state: "idle", "picking" (user is choosing a
  /// directory in FileActivity) or "importing" (the picked directory
  /// is being imported on the host worker).
  String pickState;

  /// Monotonically increasing pick outcome counter; Dart shows
  /// pickMessage as a toast when this changes and pickState is idle.
  int pickEpoch;

  /// Pick outcome text (import ok/failed, permission required,
  /// invalid path). Empty when there is nothing to report (e.g. the
  /// user cancelled the picker — native stays silent too).
  String pickMessage;

  /// Per-item remote-import outcome lines (native showRemoteResult:
  /// "%name: installed" / "%name: %error"). Non-empty only when the
  /// install was partial; Dart shows them in a result dialog.
  List<String> remoteResultLines;

  /// plugin_manager_remote_result_title, for the partial-result dialog.
  String remoteResultTitle;
}

/// Result of validating a remote plugin URL.
class B19RemoteResolve {
  B19RemoteResolve({
    required this.ok,
    required this.errorText,
  });

  /// True when PluginRemoteSource.resolve succeeded.
  bool ok;

  /// User-facing reason when validation failed; empty otherwise.
  String errorText;
}

/// One candidate plugin found in a downloaded bundle.
class B19RemoteCandidate {
  B19RemoteCandidate({
    required this.entryPath,
    required this.pluginId,
    required this.name,
    required this.version,
    required this.runtimeApi,
    required this.metaText,
    required this.permissionText,
    required this.permissionLabels,
  });

  /// Zip-internal directory (the selection key; never shown as a name).
  String entryPath;

  /// Manifest id (the install directory name).
  String pluginId;

  /// Display name (or the id when the name is empty).
  String name;

  /// Manifest version.
  String version;

  /// "js" / "py" / "html".
  String runtimeApi;

  /// plugin_manager_remote_candidate_meta % version, runtimeApi.
  String metaText;

  /// Declared permissions as one host-joined string (labels sorted
  /// by token, plugin_manager_perm_none when empty).
  String permissionText;

  /// Declared permissions as individual labels (high-risk ones
  /// flagged for the error-color bold rendering).
  List<B19PermissionLabel> permissionLabels;
}

/// One permission label (native permissionLabel + isHighRisk).
class B19PermissionLabel {
  B19PermissionLabel({
    required this.label,
    required this.highRisk,
  });

  /// Localized label (or the raw token for an unknown permission).
  String label;

  /// True for net.request and ui.panel.
  bool highRisk;
}

/// Downloaded-bundle index for the candidate selection dialog.
class B19RemoteIndex {
  B19RemoteIndex({
    required this.candidates,
    required this.countText,
    required this.sourceText,
    required this.errorText,
  });

  /// Candidates in entryPath order (max 64, native inspect rule).
  /// Empty when errorText is non-empty.
  List<B19RemoteCandidate> candidates;

  /// plugin_manager_remote_found % count.
  String countText;

  /// plugin_manager_remote_source % host.
  String sourceText;

  /// Whole-index error text (resolve / download / inspect failure,
  /// or the empty-bundle text). Empty means success.
  String errorText;
}

/// Install-confirmation text for the selected candidates.
class B19RemoteConfirm {
  B19RemoteConfirm({
    required this.valid,
    required this.title,
    required this.messageText,
    required this.riskText,
    required this.installLabel,
    required this.cancelLabel,
  });

  /// False when there is no held index (e.g. the page was disposed
  /// between download and confirm); Dart aborts the flow then.
  bool valid;

  /// plugin_manager_remote_confirm_title.
  String title;

  /// plugin_manager_remote_confirm_message % count, names, combined
  /// permissions, followed by the not-sandboxed warning.
  String messageText;

  /// Plain-language high-risk lines (net.request / ui.panel), or
  /// empty when none of the selected candidates requests them. Dart
  /// renders this in the error color, bold.
  String riskText;

  /// plugin_manager_remote_install.
  String installLabel;

  /// dialog_cancel.
  String cancelLabel;
}

/// One plugin's stored parameters for the edit dialog.
class B19PluginParams {
  B19PluginParams({
    required this.pluginId,
    required this.pluginName,
    required this.title,
    required this.hint,
    required this.jsonText,
    required this.cancelLabel,
    required this.confirmLabel,
  });

  /// Plugin id the params belong to.
  String pluginId;

  /// Display name (or the id when the name is empty).
  String pluginName;

  /// plugin_manager_params_title % display name.
  String title;

  /// plugin_manager_params_hint.
  String hint;

  /// Current stored JSON ("" when none).
  String jsonText;

  /// dialog_cancel.
  String cancelLabel;

  /// dialog_confirm.
  String confirmLabel;
}

/// Delete-confirmation text for one plugin.
class B19DeleteConfirm {
  B19DeleteConfirm({
    required this.title,
    required this.messageText,
    required this.cancelLabel,
    required this.deleteLabel,
  });

  /// plugin_manager_delete_title.
  String title;

  /// plugin_manager_delete_message % display name.
  String messageText;

  /// dialog_cancel.
  String cancelLabel;

  /// setting_delete.
  String deleteLabel;
}
