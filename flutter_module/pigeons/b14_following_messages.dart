import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2/b13_messages.dart: generated Dart code
    // lives inside the module; the generated Java code lands in the host
    // app source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b14_following_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B14Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B14Messages',
    ),
  ),
)
@HostApi()
abstract class B14HostApi {
  /// One-shot snapshot of the following list for the given filter index
  /// (0 all, 1 updates, 2 unwatched, 3 ended, 4 failed — native
  /// FILTER_* order). The host filters, sorts (LIST_ORDER) and formats
  /// every line; Dart only renders. Filtered rows are also what
  /// markAllRead acts on, like the native adapter items. Completes
  /// exactly once on every path; the 35s watchdog completes with an
  /// error (Dart shows its error/retry state).
  @async
  B14FollowingScreen getFollowing(int filterIndex);

  /// Check all followed items for updates (native checkAll: loops
  /// checkNow over FollowingStore.list()). The host toasts
  /// following_checking at start and check_done / check_failed at the
  /// end, like native. 180s watchdog (one network-bound check per
  /// item, native has no timeout); a late background completion after
  /// the watchdog is a no-op. Completes exactly once.
  @async
  B14ActionResult checkAll();

  /// Check one item for updates (native onCheck). The host toasts
  /// check_done / check_failed. 60s watchdog. Completes exactly once.
  @async
  B14ActionResult checkItem(String identityKey);

  /// Mark one item read (native onRead: local markRead + async bridge
  /// write, then the unread summary refreshes on the next snapshot).
  /// 10s watchdog. Completes exactly once.
  @async
  B14ActionResult markRead(String identityKey);

  /// Mark all currently visible (filtered) items read (native
  /// markAllRead: the keys come from the host's last snapshot, same
  /// filter as the list on screen). 10s watchdog. Completes exactly
  /// once.
  @async
  B14ActionResult markAllRead();

  /// Toggle the per-item notify switch (native onToggleNotify). On
  /// enable with SDK >= 33 and no POST_NOTIFICATIONS grant, the host
  /// requests the permission (PermissionX, B4 precedent); on deny the
  /// host toasts following_notification_permission_hint and the state
  /// stays off. 30s watchdog (covers the permission dialog wait).
  /// Completes exactly once.
  @async
  B14ActionResult toggleNotify(String identityKey);

  /// Follow the next season (native onFollowNextSeason: copies the
  /// Following + its preferred source, saves the new season, enqueues
  /// its check and toasts following_added). Returns the new season's
  /// identity key so Dart can scroll to it after refetch; empty when
  /// the item cannot be followed or the season already exists (the
  /// host then starts nothing). 10s watchdog. Completes exactly once.
  @async
  B14ActionResult followNextSeason(String identityKey);

  /// Continue watching (native onContinue). Fire-and-forget: the host
  /// reconciles + resolves history on a background thread, then starts
  /// VideoActivity.startFromFollowingHistory or TmdbDetailActivity, or
  /// toasts following_check_failed when no playable target exists.
  /// Dart refetches on resumed when the player/detail returns.
  void continuePlay(String identityKey);

  /// Change source (native onChangeSource). Fire-and-forget: opens
  /// SearchActivity via reflection (SearchActivity is mobile+leanback
  /// only, like native); errors toast. Dart refetches on resumed when
  /// the search page returns.
  void changeSource(String identityKey);

  /// Row tap detail dialog (native onOpenDetail). The host shows the
  /// same native dialog: official / source / watched lines plus the
  /// last error, and the 5 actions [continue, check, read, change
  /// source, unfollow] + close. The result completes when the chosen
  /// action settles (check / read / unfollow-confirm) or immediately
  /// for navigation actions and for a plain dismiss, so Dart can
  /// refetch right after. 60s watchdog (covers the check action
  /// inside the dialog). Completes exactly once.
  @async
  B14ActionResult openDetail(String identityKey);

  /// Unfollow confirm dialog (native onDelete). The host shows the
  /// confirm dialog; on confirm it cancels the scheduler + deletes,
  /// then the result completes; on cancel it completes immediately.
  /// Dart refetches after. 30s watchdog. Completes exactly once.
  @async
  B14ActionResult deleteItem(String identityKey);

  /// Server-import preview fetch (native startServerImport preview
  /// path). Validates the http(s) prefix, enables server import, then
  /// fetches the candidates; the fetched list is kept host-side for
  /// importServerCandidates, so the preview and the import see the same
  /// list. 60s watchdog (network). Completes exactly once.
  @async
  B14ImportPreview fetchServerCandidates(String url, String token);

  /// Imports the candidates kept by the last successful
  /// fetchServerCandidates call (native showServerPreview confirm
  /// path). The message carries the following_server_result text
  /// ("已导入 X，更新 Y"). 30s watchdog. Completes exactly once.
  @async
  B14ActionResult importServerCandidates();
}

/// Following-list snapshot, in native display order. All display strings
/// and computed values come from the host; Dart only renders them.
class B14FollowingScreen {
  B14FollowingScreen({
    required this.items,
    required this.summary,
    required this.unreadCount,
    required this.filterIndex,
    required this.filterLabel,
    required this.emptyLabel,
    required this.checkAllLabel,
    required this.readAllLabel,
    required this.serverImportLabel,
    required this.continueLabel,
    required this.checkLabel,
    required this.readLabel,
    required this.changeSourceLabel,
    required this.deleteLabel,
    required this.importTitle,
    required this.importUrlHint,
    required this.importTokenHint,
    required this.importPreviewLabel,
    required this.importActionLabel,
    required this.cancelLabel,
    required this.title,
    required this.retryLabel,
    required this.errorText,
  });

  /// Filtered rows, LIST_ORDER sorted (hasUpdate first, then
  /// unwatchedCount desc, updatedAt desc, metadataUpdatedAt desc,
  /// createdAt desc). Empty when the following feature is disabled or
  /// no row matches the filter.
  List<B14FollowingItem> items;

  /// following_summary formatted with (visible rows, unread count).
  String summary;

  /// FollowingStore.unreadCount() — the same number B11 shows as the
  /// home bottom-bar badge.
  int unreadCount;

  int filterIndex;

  /// following_filter_* label for the current filter.
  String filterLabel;

  /// following_empty when the filter is ALL, else
  /// following_filter_empty.
  String emptyLabel;

  String checkAllLabel;
  String readAllLabel;
  String serverImportLabel;

  /// Row action labels (same for every row): following_continue,
  /// following_check, following_read, following_change_source,
  /// following_cancel (the unfollow action — native labels the 5th
  /// detail-dialog action and the delete confirm button with it).
  String continueLabel;
  String checkLabel;
  String readLabel;
  String changeSourceLabel;
  String deleteLabel;

  /// Server-import dialog labels: following_server_import (title),
  /// following_server_url / following_server_token (field hints),
  /// following_server_preview (preview button),
  /// following_server_import_action (import button),
  /// dialog_negative (cancel button).
  String importTitle;
  String importUrlHint;
  String importTokenHint;
  String importPreviewLabel;
  String importActionLabel;
  String cancelLabel;

  /// Page title (nav_following) and the error-state retry label
  /// (error_retry).
  String title;
  String retryLabel;

  /// Whole-snapshot error text (load timeout: error_play_timeout).
  /// Empty means no error; Dart shows its error/retry state when
  /// non-empty (B8 errorScreen precedent).
  String errorText;
}

/// One following row. Every line is host-computed with the native
/// adapter rules (FollowingAdapter.onBindViewHolder); the PicSpec image
/// field group (picUrl / referer / cookie / user-agent /
/// extraHeadersJson / fallbackPicUrl / placeholder) is re-declared per
/// contract (plan §4.2: shared shapes are not shared types).
class B14FollowingItem {
  B14FollowingItem({
    required this.identityKey,
    required this.title,
    required this.picUrl,
    required this.picReferer,
    required this.picCookie,
    required this.picUserAgent,
    required this.picExtraHeadersJson,
    required this.fallbackPicUrl,
    required this.placeholderText,
    required this.placeholderColor,
    required this.officialLine,
    required this.metadataLine,
    required this.metadataVisible,
    required this.sourceLine,
    required this.progressLine,
    required this.badgeVisible,
    required this.badgeText,
    required this.errorVisible,
    required this.errorText,
    required this.notifyLabel,
    required this.notifyEnabled,
    required this.nextSeasonVisible,
    required this.nextSeasonLabel,
    required this.readVisible,
  });

  String identityKey;
  String title;

  /// Host-resolved display image (ImgUtil.getUrl delegation, D3).
  String picUrl;
  String picReferer;
  String picCookie;
  String picUserAgent;
  String picExtraHeadersJson;
  String fallbackPicUrl;
  String placeholderText;
  int placeholderColor;

  /// following_official (released episode) or
  /// following_official_unknown.
  String officialLine;

  /// "季总集数 · 剧总集数 · 周X" joined with " · " (native
  /// bindMetadata rule); empty when no part applies.
  String metadataLine;
  bool metadataVisible;

  /// following_source (playable episode), following_source_failed or
  /// following_source_unknown.
  String sourceLine;

  /// Watched line + unwatched line joined with "\n" (native progress
  /// rule: watched count or "未知", then unwatched count or "无未看").
  String progressLine;

  /// Badge "新 N 集" (following_new_count), visible only when hasUpdate
  /// && unwatchedCount > 0.
  bool badgeVisible;
  String badgeText;

  /// lastError, visible only when non-blank.
  bool errorVisible;
  String errorText;

  /// following_notify_on / following_notify_off for the current state.
  String notifyLabel;
  bool notifyEnabled;

  /// Next-season button, visible only when latestReleasedSeason >
  /// trackedSeason; label is following_next_season with
  /// latestReleasedSeason + 1 (native rule).
  bool nextSeasonVisible;
  String nextSeasonLabel;

  /// Read button, visible only when hasUpdate (native rule).
  bool readVisible;
}

/// Result of a following action. message is host text for a Dart
/// SnackBar; empty means silent (the host already toasted, or nothing
/// needs saying). newIdentityKey is only set by followNextSeason.
class B14ActionResult {
  B14ActionResult({
    required this.ok,
    required this.message,
    required this.newIdentityKey,
  });

  bool ok;
  String message;
  String newIdentityKey;
}

/// Server-import preview. lines are the first 20 candidates, each
/// "title · S<n+1>[ · E<m>]" (native showServerPreview rule);
/// moreCount is how many candidates lie beyond those 20.
class B14ImportPreview {
  B14ImportPreview({
    required this.ok,
    required this.message,
    required this.title,
    required this.lines,
    required this.moreCount,
    required this.totalCount,
    required this.importLabel,
  });

  bool ok;

  /// Error text when !ok (following_server_invalid, the fetch
  /// IOException message, or following_server_empty when the list is
  /// empty); empty when ok.
  String message;

  /// following_server_preview_title.
  String title;
  List<String> lines;
  int moreCount;
  int totalCount;

  /// following_server_import_action (the preview dialog's import
  /// button).
  String importLabel;
}
