import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2_messages.dart: generated Dart code lives
    // inside the module; the generated Java code lands in the host app
    // source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b12_home_a_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B12Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B12Messages',
    ),
  ),
)
@HostApi()
abstract class B12HostApi {
  /// Banner snapshot. Piggybacks on the B11 shell's homeContent
  /// result: this method NEVER calls SiteViewModel.init() or
  /// homeContent() itself. SiteViewModel.execute() cancels the previous
  /// in-flight TaskType.RESULT future, so a second homeContent() call
  /// would cancel the B11 shell load (or be cancelled by it). Instead
  /// it reads the shared activity-scoped SiteViewModel.getResult():
  /// the value already present (the B11 shell just loaded it), or the
  /// next non-null value if a B11 refetch is in flight. 35s watchdog;
  /// completes exactly once on every path. An empty item list means
  /// "hide the banner", exactly like native setBanner() with an empty
  /// list (error results carry an empty list too).
  /// A second call while one is in flight completes with an error
  /// immediately (host keeps a single subscription, like B8/B11).
  @async
  B12BannerScreen getBanner();

  /// Continue-watching snapshot. Purely local (History.getForDisplay(),
  /// same source as native VodFragment.refreshContinue()), so it is a
  /// plain synchronous call and never waits on the network: the row
  /// shows immediately, like native initView does, even while the
  /// banner is still loading. An empty item list means "hide the row".
  B12ContinueScreen getContinueWatching();

  /// Banner card tap, by position in the CURRENT shared home result
  /// (the host re-reads getResult() at tap time, so a stale Dart
  /// index can never open the wrong card; out of range is a no-op).
  /// Mirrors VodFragment.onBannerClick: action cards go through
  /// ActionCardHelper, index sites open search, everything else opens
  /// the detail page with the raw vodPic.
  void openBannerItem(int index);

  /// Continue-watching card tap. Mirrors
  /// HistoryResumeCoordinator.open(activity, history) via the same
  /// (cid, key) lookup C2 uses; a stale item is a no-op.
  void openContinueItem(int cid, String key);
}

/// Banner snapshot: the current home site's recommended list
/// (Result.getList() from the B11 shell's homeContent load), in native
/// order. Empty when the site has no recommendations or the load
/// failed — Dart hides the banner in both cases, like native.
class B12BannerScreen {
  B12BannerScreen({required this.items});

  List<B12BannerItem> items;
}

/// One banner card. The PicSpec image field group (picUrl / referer /
/// cookie / user-agent / extraHeadersJson / fallbackUrl / placeholder)
/// is re-declared per contract (plan §4.2: shared shapes are not
/// shared types); rawPic is the untouched vodPic, passed to the detail
/// page exactly like native onBannerClick does.
class B12BannerItem {
  B12BannerItem({
    required this.index,
    required this.id,
    required this.name,
    required this.remarks,
    required this.remarksVisible,
    required this.isAction,
    required this.action,
    required this.rawPic,
    required this.picUrl,
    required this.picReferer,
    required this.picCookie,
    required this.picUserAgent,
    required this.picExtraHeadersJson,
    required this.fallbackPicUrl,
    required this.placeholderText,
    required this.placeholderColor,
  });

  /// Position in Result.getList(); the click key (see openBannerItem).
  int index;

  String id;
  String name;
  String remarks;
  bool remarksVisible;
  bool isAction;
  String action;

  /// Untouched vodPic (with any @Headers= suffix), for the detail page.
  String rawPic;

  /// Host-resolved display image (ImgUtil.getUrl delegation, D3).
  String picUrl;
  String picReferer;
  String picCookie;
  String picUserAgent;
  String picExtraHeadersJson;
  String fallbackPicUrl;
  String placeholderText;
  int placeholderColor;
}

/// Continue-watching snapshot: the section title plus the unfinished
/// items, newest first, at most 10 (native refreshContinue() rule).
class B12ContinueScreen {
  B12ContinueScreen({required this.title, required this.items});

  /// Section title (home_continue_watching, host-resolved).
  String title;

  List<B12ContinueItem> items;
}

/// One continue-watching card. displayName is host-computed with the
/// ContinueAdapter.displayName rules (URL-ish names are cleaned);
/// progressPercent is host-computed and clamped 0-100; hasRealPic is
/// false for missing pictures and for the legacy push_image data URI,
/// in which case Dart shows the soft gradient placeholder (native
/// continue_placeholder.xml) instead of a picture.
class B12ContinueItem {
  B12ContinueItem({
    required this.key,
    required this.cid,
    required this.displayName,
    required this.remarks,
    required this.remarksVisible,
    required this.progressPercent,
    required this.hasRealPic,
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
  String displayName;
  String remarks;
  bool remarksVisible;

  /// position * 100 / duration, clamped 0-100 (native ContinueAdapter).
  int progressPercent;

  /// False when there is no usable picture (native shows the gradient
  /// placeholder drawable instead of trying to load).
  bool hasRealPic;

  /// Host-resolved display image (ImgUtil.getUrl delegation, D3).
  String picUrl;
  String picReferer;
  String picCookie;
  String picUserAgent;
  String picExtraHeadersJson;
  String fallbackPicUrl;
  String placeholderText;
  int placeholderColor;
}
