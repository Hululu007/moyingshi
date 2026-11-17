import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2/b21_lab_messages.dart: generated Dart
    // code lives inside the module; the generated Java code lands in
    // the host app source tree so Gradle compiles it. Paths are
    // relative to the directory `dart run pigeon` runs from (the module
    // root, see .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b22_drama_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B22Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B22Messages',
    ),
  ),
)
@HostApi()
abstract class B22HostApi {
  /// B22 feature flag (persisted in Setting, default false = native
  /// theater grid and history list inside ShortDramaActivity). The
  /// flag gates only the THEATER / HISTORY tab diversion in
  /// ShortDramaActivity.setMode: the entry (tap-to-play vertical
  /// feed) always stays native, and the player never moves. TV has
  /// no short-drama screen, so the flag is unreachable on leanback.
  bool isB22Enabled();
  void setB22Enabled(bool enabled);

  /// One-shot snapshot of the theater grid (native GridAdapter over
  /// the same feed ShortDramaActivity.loadSource builds: homeContent
  /// -> fetchPic -> empty + types -> categoryContent of the first
  /// type). Runs on the host's single drama worker thread; completes
  /// exactly once on every path; the 35s watchdog completes with an
  /// error snapshot (the spider calls are synchronous network calls
  /// with no timeout of their own). A load exception mirrors native
  /// loadSource: it is swallowed and the snapshot carries an empty
  /// list with the no-content state text. Resumed refetch follows
  /// plan §4.6; pull-to-refresh re-calls this method.
  @async
  B22DramaTheaterScreen getTheaterScreen();

  /// One-shot snapshot of the drama history list (native
  /// HistoryAdapter + reloadHistory: History.get() filtered by the
  /// drama-site key prefix). Runs on the host worker thread (a Room
  /// read, like native); completes exactly once on every path; the
  /// 35s watchdog completes with an empty list. Resumed refetch
  /// follows plan §4.6.
  @async
  B22DramaHistoryScreen getHistoryScreen();

  /// Theater card tap: the host asks the native ShortDramaActivity
  /// underneath to jump its feed to this item (matched by vodId,
  /// falling back to the list position) and finishes this Flutter
  /// screen, so the user lands back on the native play stream
  /// playing the tapped drama — the native grid-tap behavior
  /// (setMode(FEED) + setCurrentItem). Fire-and-forget: Dart needs
  /// no result; the Activity finishes underneath it.
  void openFeedItem(String vodId, int position);

  /// History row tap: the host opens the native detail page
  /// (VideoActivity.start, 5 args), exactly like the native history
  /// row. Fire-and-forget.
  void openHistoryItem(
      String siteKey, String vodId, String vodName, String vodPic);
}

/// Theater grid snapshot. All display strings come from the host;
/// Dart hard-codes no locale strings.
class B22DramaTheaterScreen {
  B22DramaTheaterScreen({
    required this.title,
    required this.stateText,
    required this.retryLabel,
    required this.loadErrorText,
    required this.columnCount,
    required this.items,
  });

  /// Toolbar title (drama_theater, mobile-only string resolved by
  /// the host at runtime).
  String title;

  /// Centered state text shown when the list is empty, decided by
  /// the host: drama_empty when there is no drama source at all,
  /// drama_no_content when the current source returned nothing
  /// (native showState, same two strings).
  String stateText;

  /// Retry button label (error_retry, main res).
  String retryLabel;

  /// Non-empty only when the watchdog fired: the timeout text
  /// (error_play_timeout). Dart shows the error state with a retry
  /// button; empty means the snapshot is a genuine load result.
  String loadErrorText;

  /// Grid column count (native GridLayoutManager is fixed at 3).
  int columnCount;

  /// Grid cards in feed order; the index is the feed position used
  /// by openFeedItem.
  List<B22DramaItem> items;
}

/// One theater grid card (native item_drama_grid.xml: poster + name
/// + remarks). Image fields follow the D3 PicSpec shape: the host
/// delegates ImgUtil.getUrl, Dart renders with the shared PicImage.
class B22DramaItem {
  B22DramaItem({
    required this.id,
    required this.name,
    required this.remarks,
    required this.picUrl,
    required this.picReferer,
    required this.picCookie,
    required this.picUserAgent,
    required this.picExtraHeadersJson,
    required this.fallbackPicUrl,
    required this.placeholderText,
    required this.placeholderColor,
  });

  String id;
  String name;
  String remarks;
  String picUrl;
  String picReferer;
  String picCookie;
  String picUserAgent;
  String picExtraHeadersJson;
  String fallbackPicUrl;
  String placeholderText;
  int placeholderColor;
}

/// Drama history snapshot. The native history view has no empty
/// text (an empty list is just blank), so there is none here either;
/// Dart renders the blank list faithfully.
class B22DramaHistoryScreen {
  B22DramaHistoryScreen({
    required this.title,
    required this.items,
  });

  /// Toolbar title (drama_history, mobile-only string resolved by
  /// the host at runtime).
  String title;

  /// History rows in History.get() order, filtered to drama sites.
  List<B22DramaHistoryItem> items;
}

/// One history row (native item_drama_history.xml: poster + name +
/// meta + time). The meta line ("site [· remarks]") and the time
/// text ("MM-dd HH:mm") are computed by the host, exactly like the
/// native adapter; Dart renders them verbatim.
class B22DramaHistoryItem {
  B22DramaHistoryItem({
    required this.siteKey,
    required this.vodId,
    required this.vodName,
    required this.vodPic,
    required this.meta,
    required this.timeText,
    required this.picUrl,
    required this.picReferer,
    required this.picCookie,
    required this.picUserAgent,
    required this.picExtraHeadersJson,
    required this.fallbackPicUrl,
    required this.placeholderText,
    required this.placeholderColor,
  });

  String siteKey;
  String vodId;
  String vodName;
  String vodPic;
  String meta;
  String timeText;
  String picUrl;
  String picReferer;
  String picCookie;
  String picUserAgent;
  String picExtraHeadersJson;
  String fallbackPicUrl;
  String placeholderText;
  int placeholderColor;
}
