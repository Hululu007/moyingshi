import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2_messages.dart: generated Dart code lives
    // inside the module; the generated Java code lands in the host app
    // source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b13_home_b_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B13Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B13Messages',
    ),
  ),
)
@HostApi()
abstract class B13HostApi {
  /// One-shot snapshot of the JS widget sections, in native display
  /// order (builtinOrder: Bangumi -> Douban -> Mikan -> TMDB). All
  /// JavaScript runs host-side (WidgetManager / WidgetHost, QuickJS);
  /// Dart only renders this snapshot. The host completes with the best
  /// available sections: a section that delivered only its cached
  /// items is held briefly for its fresh result (2s grace after all
  /// four builtins have delivered a first outcome), and the 35s
  /// watchdog completes with whatever has arrived. An empty sections
  /// list hides the whole area (native updateWidgetVisibility rule:
  /// visible iff >= 1 section). Completes exactly once on every path.
  /// A second call while one is in flight completes with an error
  /// immediately (host keeps a single load, like B8/B11/B12).
  @async
  B13WidgetScreen getWidgets();

  /// Widget item tap, by title. Mirrors
  /// VodFragment.onWidgetItemClick: searches the tapped title in the
  /// current site (SearchActivity.start, B9-diverted when its flag is
  /// on, same as native).
  void openWidgetItem(String title);

  /// Section "more" tap. Mirrors the native header: opens
  /// WidgetBrowseActivity.start(activity, widgetId) for that widget
  /// (mobile-only class, opened via reflection in the host impl).
  void openWidgetMore(String widgetId);

  /// Destroys the host-side WidgetManager (its executor threads) when
  /// the widget is disposed; also completes any in-flight getWidgets()
  /// call with the best available sections.
  void disposeWidgets();
}

/// Widget-area snapshot: the sections that delivered items, in native
/// display order. Empty when the home-widget switch is off, when no
/// widget delivered, or when the load failed — Dart hides the area in
/// all three cases, like native.
class B13WidgetScreen {
  B13WidgetScreen({required this.sections});

  List<B13WidgetSection> sections;
}

/// One widget section. The title is host-computed
/// (metadata.getTitle() + " · " + module.getTitle(), native
/// Section.title() rule); moreLabel is the host-resolved
/// "widget_more" string.
class B13WidgetSection {
  B13WidgetSection({
    required this.widgetId,
    required this.title,
    required this.moreLabel,
    required this.items,
  });

  /// Builtin widget id (builtin.bangumi / builtin.douban /
  /// builtin.mikan / builtin.tmdb); the click key for openWidgetMore.
  String widgetId;

  String title;
  String moreLabel;
  List<B13WidgetItem> items;
}

/// One widget item card. The PicSpec image field group (picUrl /
/// referer / cookie / user-agent / extraHeadersJson / fallbackUrl /
/// placeholder) is re-declared per contract (plan §4.2: shared shapes
/// are not shared types); the raw poster URL is host-resolved through
/// WidgetManager.posterUrlWithHeaders + ImgUtil.getUrl (D3).
class B13WidgetItem {
  B13WidgetItem({
    required this.title,
    required this.remarks,
    required this.remarksVisible,
    required this.picUrl,
    required this.picReferer,
    required this.picCookie,
    required this.picUserAgent,
    required this.picExtraHeadersJson,
    required this.fallbackPicUrl,
    required this.placeholderText,
    required this.placeholderColor,
  });

  String title;

  /// item.getDisplayRemark(): the explicit remark, else "rating ·
  /// year" (rating %.1f, year = first 4 chars of releaseDate).
  String remarks;
  bool remarksVisible;

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
