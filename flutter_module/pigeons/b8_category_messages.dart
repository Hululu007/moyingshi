import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2_messages.dart: generated Dart code lives
    // inside the module; the generated Java code lands in the host app
    // source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b8_category_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B8Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B8Messages',
    ),
  ),
)
@HostApi()
abstract class B8HostApi {
  /// B8 feature flag (persisted in Setting, default false = native screen).
  bool isB8Enabled();
  void setB8Enabled(bool enabled);

  /// One page of the category list: first load, pull-to-refresh,
  /// load-more and filter change all go through this one method with
  /// the page to load. The host owns the page/hasMore computation (from
  /// Result.pageCount, mirroring CustomScroller.endLoading) and the
  /// effective filter map (initial values come from the Class filters'
  /// init values when extendJson is empty); Dart only passes the page
  /// number and the current filter map, and stores the echoed
  /// extendJson / nextPage for the next call. Async: the Future
  /// completes with the snapshot when the one-shot SiteViewModel
  /// subscription returns (or with an error snapshot on timeout), so
  /// Dart can render the error state and retry the same call. Dart
  /// re-fetches page 1 on resume.
  @async
  B8CategoryScreen getCategoryPage(
    String key,
    String typeJson,
    String typeId,
    String extendJson,
    int page,
  );

  /// Opens one card: the host follows the native
  /// TypeFragment.onItemClick chain exactly — action card, index site
  /// (search / history-resume search), history-resume open, or the
  /// detail page. Folder cards never reach the host: Dart intercepts
  /// them for the in-family drill-down (pure Dart navigation, see
  /// docs/b8-full-migration-plan.md §4.4); the host treats a folder
  /// item defensively as a detail open so a tap can never silently do
  /// nothing. historyResumeCid is -1 when the entry did not come from a
  /// history resume.
  void openCategoryItem(
    String key,
    String itemId,
    String itemName,
    String itemPic,
    bool itemIsFolder,
    bool itemIsAction,
    String itemAction,
    int historyResumeCid,
    String historyResumeKey,
    int historyResumeTargetCid,
  );

  /// Long-press on a card: native opens the search page for the item
  /// name (or the history-resume search when resuming). Dart only calls
  /// this for non-folder, non-action items, like the native long-press.
  void searchCategoryItem(
    String keyword,
    int historyResumeCid,
    String historyResumeKey,
    int historyResumeTargetCid,
  );
}

/// One page of the category list plus all screen strings and geometry,
/// so Dart never sees a torn state between two calls. errorMessage is
/// empty when there is no error; on error the items list is empty and
/// hasMore is false, but the filters and the echoed extendJson are still
/// present so a load-more failure can keep the list and retry.
class B8CategoryScreen {
  B8CategoryScreen({
    required this.title,
    required this.styleType,
    required this.styleRatio,
    required this.columnCount,
    required this.cardImageWidthPx,
    required this.cardImageHeightPx,
    required this.filters,
    required this.items,
    required this.hasMore,
    required this.pageCount,
    required this.nextPage,
    required this.extendJson,
    required this.emptyTitle,
    required this.emptyDescription,
    required this.errorTitle,
    required this.errorMessage,
    required this.retryLabel,
    required this.filterTitle,
  });

  /// Class typeName (the FolderActivity toolbar title).
  String title;

  /// Effective card style for this page: "rect", "list" or "oval"
  /// (folder mode is always "list"; otherwise the site style, the
  /// Class style, or the first item's style, in that order).
  String styleType;

  /// Effective style ratio (image width / height) for rect/oval cards.
  double styleRatio;
  int columnCount;
  int cardImageWidthPx;
  int cardImageHeightPx;

  /// Filter groups from the Class, with the selected value of each
  /// group marked from the effective extend map.
  List<B8FilterGroup> filters;

  /// This page's items (appended by Dart on load-more).
  List<B8CategoryItem> items;

  /// True when another page may exist (page < pageCount, or pageCount
  /// is 0 = unknown, mirroring CustomScroller.setEnable).
  bool hasMore;
  int pageCount;

  /// Page Dart should request next (same page when this page came back
  /// empty, mirroring CustomScroller.endLoading's page--).
  int nextPage;

  /// Effective filter map as a JSON object string, after the host
  /// applied the Class filters' init values; Dart stores it and sends
  /// it back on the next call.
  String extendJson;

  String emptyTitle;
  String emptyDescription;
  String errorTitle;
  String errorMessage;
  String retryLabel;

  /// "Filter" section header; empty when there are no filters.
  String filterTitle;
}

class B8FilterGroup {
  B8FilterGroup({
    required this.key,
    required this.name,
    required this.values,
  });

  String key;
  String name;
  List<B8FilterValue> values;
}

class B8FilterValue {
  B8FilterValue({
    required this.label,
    required this.value,
    required this.selected,
  });

  /// Display label (Value.n).
  String label;

  /// Filter value sent to the site (Value.v).
  String value;
  bool selected;
}

class B8CategoryItem {
  B8CategoryItem({
    required this.id,
    required this.name,
    required this.year,
    required this.remarks,
    required this.siteName,
    required this.siteVisible,
    required this.nameVisible,
    required this.remarkVisible,
    required this.yearVisible,
    required this.isFolder,
    required this.isAction,
    required this.action,
    required this.picUrl,
    required this.rawPic,
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

  /// Release year. The native rect card shows it as a badge only when
  /// the item has no site and the year is at least 4 characters; the
  /// shared VodCard has no year slot, so Dart does not render it (see
  /// docs/b8-b8-category.md).
  String year;
  String remarks;
  String siteName;
  bool siteVisible;
  bool nameVisible;
  bool remarkVisible;
  bool yearVisible;
  bool isFolder;
  bool isAction;

  /// Action JSON for action cards (empty otherwise).
  String action;

  /// Clean image URL: the host ran ImgUtil.getUrl() and stripped the
  /// `@Headers=` / `@Cookie=` / `@Referer=` / `@User-Agent=` suffixes.
  String picUrl;

  /// Raw vodPic as the site returned it (headers suffixes intact).
  /// Dart passes this to openCategoryItem so the detail page loads the
  /// image with the same headers native would use.
  String rawPic;
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
