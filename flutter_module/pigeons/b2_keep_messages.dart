import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2_messages.dart: generated Dart code lives
    // inside the module; the generated Java code lands in the host app
    // source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b2_keep_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B2Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B2Messages',
    ),
  ),
)
@HostApi()
abstract class B2HostApi {
  /// B2 feature flag (persisted in Setting, default false = native screen).
  bool isB2Enabled();
  void setB2Enabled(bool enabled);

  /// One snapshot: the list plus all screen strings and grid geometry, so
  /// Dart never sees a torn state between two calls.
  B2KeepScreen getKeep();

  /// Opens one card: the host finds the Keep by (cid, key) and follows
  /// the native KeepActivity.onItemClick chain exactly — no config ->
  /// native search page; another config -> async VodConfig.load, then
  /// detail on success; current config -> detail directly. Async: the
  /// Future completes with the outcome when the load finishes (or
  /// immediately for the synchronous paths), so Dart can show the host
  /// error state on failure and retry the same call. Dart re-fetches
  /// getKeep() on resume.
  @async
  B2LoadResult openKeepItem(int cid, String key);

  /// Deletes one card: the host calls delete() on the matching Keep.
  /// Dart re-fetches getKeep() afterwards.
  void deleteKeepItem(int cid, String key);

  /// Deletes everything: the host shows the native confirm dialog and
  /// calls Keep.deleteAll() on confirm. Async: the Future completes
  /// when the dialog dismisses (any outcome), because a native dialog
  /// produces no Activity lifecycle event for Dart to refresh on. Dart
  /// re-fetches getKeep() after awaiting this.
  @async
  void deleteAllKeep();

  /// Opens the native sync dialog (SyncDialog keep mode). Dart
  /// re-fetches getKeep() on resume.
  void openKeepSync();

  /// Empty-state primary action: returns home with the same intent flags
  /// the native screen uses.
  void goHome();
}

class B2KeepScreen {
  B2KeepScreen({
    required this.title,
    required this.emptyTitle,
    required this.emptyDescription,
    required this.emptyActionLabel,
    required this.syncTooltip,
    required this.deleteTooltip,
    required this.columnCount,
    required this.cardImageWidthPx,
    required this.cardImageHeightPx,
    required this.items,
  });

  String title;
  String emptyTitle;
  String emptyDescription;
  String emptyActionLabel;
  String syncTooltip;
  String deleteTooltip;
  int columnCount;
  int cardImageWidthPx;
  int cardImageHeightPx;
  List<B2KeepItem> items;
}

class B2KeepItem {
  B2KeepItem({
    required this.key,
    required this.cid,
    required this.vodName,
    required this.siteName,
    required this.picUrl,
    required this.picReferer,
    required this.picCookie,
    required this.picUserAgent,
    required this.picExtraHeadersJson,
    required this.fallbackPicUrl,
    required this.placeholderText,
    required this.placeholderColor,
  });

  String key;
  int cid;
  String vodName;
  String siteName;

  /// Clean image URL: the host ran ImgUtil.getUrl() and stripped the
  /// `@Headers=` / `@Cookie=` / `@Referer=` / `@User-Agent=` suffixes.
  String picUrl;
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

/// Outcome of opening one card (see B2HostApi.openKeepItem). `status` is
/// "opened" when the host started the detail/search Activity, "failed"
/// when the config load failed (Dart shows the error state with the host
/// strings and retries the same call).
class B2LoadResult {
  B2LoadResult({
    required this.status,
    required this.errorTitle,
    required this.errorMessage,
    required this.retryLabel,
  });

  String status;
  String errorTitle;
  String errorMessage;
  String retryLabel;
}
