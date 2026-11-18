import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0_messages.dart / c1_messages.dart: generated Dart
    // code lives inside the module; the generated Java code lands in the
    // host app source tree so Gradle compiles it. Paths are relative to
    // the directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/c2_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/C2Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'C2Messages',
    ),
  ),
)
@HostApi()
abstract class C2HostApi {
  /// One snapshot: the list plus all screen strings and grid geometry, so
  /// Dart never sees a torn state between two calls.
  C2HistoryScreen getHistory();

  /// Opens one card: the host finds the History by (cid, key) and calls
  /// HistoryResumeCoordinator.open, exactly like the native screen.
  void openHistoryItem(int cid, String key);

  /// Deletes one card: the host calls deleteDisplayItem() on the matching
  /// History. Dart re-fetches getHistory() afterwards.
  void deleteHistoryItem(int cid, String key);

  /// Deletes everything: the host shows the native confirm dialog (with
  /// the global-history message variant) and calls
  /// History.deleteForDisplay() on confirm. Async: the Future completes
  /// when the dialog dismisses (any outcome), because a native dialog
  /// produces no Activity lifecycle event for Dart to refresh on. Dart
  /// re-fetches getHistory() after awaiting this.
  @async
  void deleteAllHistory();

  /// Opens the native sync dialog (SyncDialog history mode). Dart
  /// re-fetches getHistory() on resume.
  void openHistorySync();

  /// Opens the native viewing-report range dialog, which starts the
  /// report Activity. No refresh needed.
  void openViewingReport();

  /// Empty-state primary action: returns home with the same intent flags
  /// the native screen uses.
  void goHome();
}

class C2HistoryScreen {
  C2HistoryScreen({
    required this.title,
    required this.emptyTitle,
    required this.emptyDescription,
    required this.emptyActionLabel,
    required this.syncTooltip,
    required this.deleteTooltip,
    required this.reportTooltip,
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
  String reportTooltip;
  int columnCount;
  int cardImageWidthPx;
  int cardImageHeightPx;
  List<C2HistoryItem> items;
}

class C2HistoryItem {
  C2HistoryItem({
    required this.key,
    required this.cid,
    required this.vodName,
    required this.siteName,
    required this.siteVisible,
    required this.remarks,
    required this.remarksVisible,
    required this.playbackText,
    required this.hasPlayback,
    required this.positionMs,
    required this.durationMs,
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
  bool siteVisible;
  String remarks;
  bool remarksVisible;
  String playbackText;
  bool hasPlayback;
  int positionMs;
  int durationMs;

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
