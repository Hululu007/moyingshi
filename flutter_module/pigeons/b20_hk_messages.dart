import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2/b19_plugin_messages.dart: generated
    // Dart code lives inside the module; the generated Java code lands
    // in the host app source tree so Gradle compiles it. Paths are
    // relative to the directory `dart run pigeon` runs from (the module
    // root, see .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b20_hk_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B20Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B20Messages',
    ),
  ),
)
@HostApi()
abstract class B20HostApi {
  /// B20 feature flag (persisted in Setting, default false = native
  /// HkMiniappActivity). The flag gates the single diversion point:
  /// LabActivity.openMiniappManager (the "海阔小程序" group + menu).
  /// The Hk list / detail / search pages stay native (plan §6 P3),
  /// and the leanback LabActivity keeps firing the implicit intent
  /// (TV untouched).
  bool isB20Enabled();
  void setB20Enabled(bool enabled);

  /// Static page chrome (toolbar title, the five button labels, the
  /// dialog button labels, pick-chooser strings, paste / cloud dialog
  /// strings). Synchronous: Dart renders the page chrome from this
  /// before the first miniapp snapshot arrives. All strings come from
  /// the host; Dart hard-codes no locale strings.
  B20HkShell getHkShell();

  /// One-shot snapshot of the installed miniapp list (native
  /// HkMiniappActivity.refreshList: LocalSource.getSites() filtered
  /// by HkStore.isMiniapp). Runs on the host's single Hk worker
  /// thread; completes exactly once on every path; the 35s watchdog
  /// completes with an error snapshot (Dart shows its error/retry
  /// state). Also carries the file/dir-pick state
  /// (pickState/pickEpoch/pickMessage): Dart polls this while a pick
  /// is in flight (B19 polling precedent) because the SAF picker
  /// waits on the user and cannot fit a 35s watchdog. Resumed
  /// refetch follows plan §4.6.
  @async
  B20HkScreen getHkScreen();

  /// Imports pasted rule JSON (native showPasteDialog confirm:
  /// HkStore.importJsonText on a background thread, then the
  /// onImported callback). Returns the refreshed snapshot; a blank
  /// paste completes with dialogErrorText and Dart keeps the dialog
  /// open (native toasts and keeps the dialog open). A successful
  /// import also reloads the local config and warms up, exactly like
  /// native onImported.
  @async
  B20HkScreen importPasteJson(String text);

  /// Reads the clipboard and extracts cloud codes (native
  /// showCloudCodeDialog open: ClipboardManager + HkCloudImport
  /// .extractCloudCodes). Synchronous pure read; Dart pre-fills the
  /// cloud-code dialog with the returned text (empty when the
  /// clipboard holds no code — native then shows an empty box, no
  /// toast).
  B20CloudCodePrefill getCloudCodePrefill();

  /// Imports cloud codes (native showCloudCodeDialog confirm:
  /// HkCloudImport.importCloudCodes on a background thread, then the
  /// same partial / failed / empty handling as native). The input is
  /// the raw dialog text; the host extracts the codes. No code found
  /// completes with dialogErrorText and Dart keeps the dialog open
  /// (native toasts hk_miniapp_cloud_code_none and keeps the dialog
  /// open). A partial import travels in noticeText (native toast)
  /// and still reloads + warms up, exactly like native.
  @async
  B20HkScreen importCloudCodes(String text);

  /// Re-registers the installed rules (native runSync:
  /// HkStore.syncSites on a background thread, then the
  /// hk_miniapp_synced notice; the local config is reloaded only
  /// when something was newly registered). Returns the refreshed
  /// snapshot. No warm-up (native runSync does not warm up).
  @async
  B20HkScreen syncMiniapps();

  /// Fire-and-forget: open the host SAF picker for a file or a
  /// directory (native showPickChooser + HkImportPicker.pickFiles /
  /// pickDirectory). The kind is "file" or "dir". The picked files
  /// are imported host-side (HkStore.importFiles + the onImported
  /// callback); Dart learns the outcome by polling getHkScreen()
  /// for pickState/pickEpoch. Ignored when an Hk task is running or
  /// a pick is already in flight. A cancelled picker completes
  /// through the pick state too (pickMessage empty — native stays
  /// silent on cancel), via the onCancelled callback added to
  /// HkImportPicker in this batch.
  void requestHkImportPick(String kind);

  /// Exports the selected miniapps (native runExport: one
  /// HkCloudExport.uploadTextDb per title on a background thread,
  /// then the system share sheet with HkCloudExport.shareText).
  /// The share sheet is opened host-side (Dart cannot open it). The
  /// outcome travels in noticeText (export_failed when nothing
  /// uploaded, export_partial when some failed, empty on full
  /// success — native shows no toast then, only the share sheet).
  /// Nothing selected completes with the export_select_first notice,
  /// exactly like native.
  @async
  B20HkScreen exportMiniapps(List<String> keys);

  /// Deletes one miniapp (native onDeleteClick: LocalSource
  /// .removeByKeys + HkStore.delete + HkCache.invalidate, then the
  /// lab_local_source_deleted notice + a config reload + a list
  /// refresh). Returns the post-delete snapshot. Native deletes
  /// immediately with no confirmation dialog; Dart does the same.
  @async
  B20HkScreen deleteMiniapp(String key);

  /// Opens one miniapp's own list page (native onItemClick:
  /// HkListActivity.open with HkStore.titleOf). Fire-and-forget;
  /// the list page stays native (plan §6 P3).
  void openMiniappList(String key);

  /// Destroys the Hk session: shuts the worker down. Fire-and-
  /// forget; called from the Dart page dispose (B18/B19 precedent).
  void disposeHk();
}

/// Static page chrome. All display strings come from the host.
class B20HkShell {
  B20HkShell({
    required this.title,
    required this.importFileLabel,
    required this.importPasteLabel,
    required this.syncLabel,
    required this.exportLabel,
    required this.cloudCodeLabel,
    required this.cancelLabel,
    required this.importLabel,
    required this.retryLabel,
    required this.pickTitle,
    required this.pickFileLabel,
    required this.pickDirLabel,
    required this.pasteTitle,
    required this.pasteHint,
    required this.cloudTitle,
    required this.cloudHint,
    required this.cloudNote,
    required this.deleteLabel,
  });

  /// hk_miniapp_title, the toolbar title.
  String title;

  /// hk_miniapp_import_file, the "import file / directory" button.
  String importFileLabel;

  /// hk_miniapp_import_paste, the "paste JSON" button.
  String importPasteLabel;

  /// hk_miniapp_sync, the "sync installed rules" button.
  String syncLabel;

  /// hk_miniapp_export, the "export" button.
  String exportLabel;

  /// hk_miniapp_cloud_code, the "cloud code import" button.
  String cloudCodeLabel;

  /// dialog_cancel.
  String cancelLabel;

  /// lab_import, the import button in the paste / cloud dialogs.
  String importLabel;

  /// error_retry, for the error state retry button.
  String retryLabel;

  /// hk_miniapp_pick_title, the file-or-directory chooser title.
  String pickTitle;

  /// hk_miniapp_pick_file, the "choose a file" option.
  String pickFileLabel;

  /// hk_miniapp_pick_dir, the "choose a directory" option.
  String pickDirLabel;

  /// hk_miniapp_import_paste, the paste dialog title.
  String pasteTitle;

  /// hk_miniapp_paste_hint, the paste dialog input hint.
  String pasteHint;

  /// hk_miniapp_cloud_code, the cloud-code dialog title.
  String cloudTitle;

  /// hk_miniapp_cloud_code_hint, the cloud-code dialog input hint.
  String cloudHint;

  /// hk_miniapp_export_share_note, the cloud dialog message (the
  /// "cloud codes are public links, not encryption" warning).
  String cloudNote;

  /// setting_delete, the per-row delete button tooltip.
  String deleteLabel;
}

/// One installed miniapp row. All display strings and computed values
/// come from the host; Dart only renders.
class B20HkItem {
  B20HkItem({
    required this.key,
    required this.name,
  });

  /// Site key ("hk://<title>", the row identity + the export /
  /// delete selection key).
  String key;

  /// Site display name ("标题⁽ʰᵏ⁾┃[类型]", exactly what the native
  /// LocalSourceAdapter shows).
  String name;
}

/// One miniapp-list snapshot. Transient per-call fields (noticeText,
/// dialogErrorText) are set only by the call that produced them; a
/// plain getHkScreen() returns them empty.
class B20HkScreen {
  B20HkScreen({
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
  });

  /// hk_miniapp_title, the toolbar title.
  String title;

  /// Installed miniapps in LocalSource order (getSites() filtered by
  /// HkStore.isMiniapp).
  List<B20HkItem> entries;

  /// hk_miniapp_empty, shown when entries is empty.
  String emptyText;

  /// True while an Hk task (import / sync / export / delete) is
  /// running on the host worker; Dart disables its buttons.
  bool busy;

  /// Whole-snapshot error text (load failure, or error_play_timeout
  /// on watchdog). Empty means no error.
  String loadErrorText;

  /// One-shot notice for the action that produced this snapshot
  /// (imported / synced / export failed / partial / deleted / busy).
  /// Empty means no notice; Dart shows it as a toast once.
  String noticeText;

  /// One-shot dialog error for importPasteJson / importCloudCodes
  /// (blank paste / no cloud code found / busy). Non-empty means
  /// Dart keeps the dialog open and shows this error inline.
  String dialogErrorText;

  /// File/dir-pick state: "idle" or "picking" (the user is choosing
  /// in the SAF picker). The Hk picker imports on its own thread and
  /// reports only via the onImported callback, so there is no separate
  /// "importing" state: the state stays "picking" until the import
  /// finishes or the picker is cancelled.
  String pickState;

  /// Monotonically increasing pick outcome counter; Dart shows
  /// pickMessage as a toast when this changes and pickState is idle.
  int pickEpoch;

  /// Pick outcome text (imported / import failed / no rule found).
  /// Empty when there is nothing to report (e.g. the user cancelled
  /// the picker — native stays silent too).
  String pickMessage;
}

/// Clipboard prefill for the cloud-code dialog.
class B20CloudCodePrefill {
  B20CloudCodePrefill({
    required this.prefillText,
  });

  /// Cloud codes extracted from the clipboard, one per line; empty
  /// when the clipboard holds no code.
  String prefillText;
}
