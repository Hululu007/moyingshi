import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2_messages.dart: generated Dart code lives
    // inside the module; the generated Java code lands in the host app
    // source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b10_collect_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B10Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B10Messages',
    ),
  ),
)
@HostApi()
abstract class B10HostApi {
  /// Starts the multi-site parallel search and completes with the full
  /// snapshot when every site has answered (or the watchdog fired).
  /// The host owns the whole search session (SiteViewModel observers,
  /// master lists, per-site paging, filter state), mirroring
  /// CollectFragment; Dart only renders snapshots and polls progress.
  /// Async: completes exactly once on every path (finished, empty
  /// site list, or the 35s watchdog). A second call supersedes the
  /// first: the stale call completes with the then-current snapshot.
  /// siteKey rule (factored from the B9 submit rule): when
  /// currentSite is true and siteKey is empty, the home site's key is
  /// used; otherwise siteKey is taken as the explicit filter (empty =
  /// all sites). Callers pre-apply the native scope rule — the B9 page
  /// passes its entry siteKey only under current-site scope, the
  /// native input page passes its already-resolved siteKey.
  /// historyResumeCid is -1 when the entry did not come from a history
  /// resume.
  @async
  B10CollectScreen startSearch(
    String keyword,
    bool currentSite,
    String siteKey,
    String group,
    String pic,
    String wallPic,
    int historyResumeCid,
    String historyResumeKey,
    int historyResumeTargetCid,
  );

  /// Lightweight progress snapshot, polled by Dart every 300ms while
  /// the search (or a page load) is pending (plan §4.6). revision
  /// bumps on every session mutation; Dart re-reads the full snapshot
  /// only when it changes. Synchronous: local state only.
  B10Progress getProgress();

  /// The full current screen state: filtered sources, the active
  /// source's visible items, geometry and all strings. Synchronous:
  /// local state only.
  B10CollectScreen getSnapshot();

  /// Loads the next page for the active source (host computes the page
  /// from its SearchPageState, mirroring CustomScroller). Async:
  /// completes with the fresh snapshot when the page lands (chained
  /// auto-continue pages included), immediately when there is nothing
  /// to load, or on the 35s watchdog — always exactly once.
  @async
  B10CollectScreen loadMore();

  /// Switches the active source (the "all" row or one site). The host
  /// may kick off an auto-continue page load for the new active source
  /// (like native maybeLoadNextPage); Dart keeps polling while
  /// hasPendingPage is true. Synchronous: returns the fresh snapshot.
  B10CollectScreen setActiveSource(String siteKey);

  /// Applies the group filter ("" = all groups). The host rebuilds the
  /// filtered sources and may kick off an auto-continue page load for
  /// the new active source. Synchronous: returns the fresh snapshot.
  B10CollectScreen setGroupFilter(String group);

  /// Switches the result layout between 1 column (list rows) and 2
  /// columns (grid). Persisted via Setting.putSearchColumn. The host
  /// recomputes the grid geometry. Synchronous: returns the fresh
  /// snapshot.
  B10CollectScreen setColumn(int column);

  /// Opens the native similarity slider (the SliderNumberDialog
  /// content, built inline so the dismiss listener can complete the
  /// call — the dialog helper itself exposes no dismiss hook, see
  /// docs/b8-b10-collect.md V9). Async: completes with the fresh
  /// snapshot on confirm (value persisted, visible items rebuilt) and
  /// on cancel/dismiss (value unchanged) — every path completes.
  @async
  B10CollectScreen openSimilarityDialog();

  /// Opens one result card, following the native
  /// CollectFragment.onItemClick chain exactly: folder (via
  /// FolderActivity, so the B8 diversion still applies), history
  /// resume, or the detail page. itemSiteKey is the item's own site;
  /// the history-resume parameters come from the host session, not
  /// from Dart. itemPic is the raw vodPic (the detail page parses the
  /// @Headers suffixes itself, like native).
  void openCollectItem(
    String itemId,
    String itemName,
    String itemPic,
    bool itemIsFolder,
    String itemSiteKey,
  );

  /// Records one search keyword (dedupe, most recent first, max 10,
  /// persisted 250ms later — RecordAdapter.checkToAdd, like native).
  /// Used by the B9 input page when it pushes this results page
  /// inside the family (see docs/b8-b10-collect.md V8).
  void recordSearch(String keyword);

  /// Stops the running search, removes the session observers and
  /// clears the session state. Dart calls this from dispose; a later
  /// startSearch starts a fresh session.
  void cancelSearch();
}

/// The full aggregate-search screen state, so Dart never sees a torn
/// state between two calls. errorMessage is empty when there is no
/// error; the error view only shows when the search finished with no
/// items and an error message is present.
class B10CollectScreen {
  B10CollectScreen({
    required this.title,
    required this.groups,
    required this.currentGroup,
    required this.canFilterGroup,
    required this.allScopeLabel,
    required this.sources,
    required this.items,
    required this.activeSiteKey,
    required this.columnCount,
    required this.isGrid,
    required this.gridSpanCount,
    required this.cardImageWidthPx,
    required this.cardImageHeightPx,
    required this.sourceColumnWidthPx,
    required this.horizontalSources,
    required this.similarity,
    required this.similarityTitle,
    required this.emptyVisible,
    required this.emptyText,
    required this.errorTitle,
    required this.errorMessage,
    required this.retryLabel,
    required this.progressCurrent,
    required this.progressTotal,
    required this.searchFinished,
    required this.hasPendingPage,
    required this.canLoadMore,
    required this.revision,
  });

  /// Toolbar title (search_result_all / _current / _group, with the
  /// keyword filled in by the host).
  String title;

  /// Group options for the group filter menu (empty when the entry is
  /// a site or group search); currentGroup is "" for "all groups".
  List<String> groups;
  String currentGroup;

  /// True when the group menu may be shown (not a site/group search
  /// and groups non-empty), mirroring CollectFragment.canFilterGroup.
  bool canFilterGroup;

  /// "All" label (search_scope_all): the group menu's first item and
  /// the "all" source row's name.
  String allScopeLabel;

  /// Filtered source rows, "all" first, then one row per site that has
  /// visible results (SearchSourceVisibility rule).
  List<B10SourceItem> sources;

  /// The active source's visible items (filtered by the similarity
  /// threshold, like the native visible lists).
  List<B10CollectItem> items;

  /// Active source key ("all" or a site key).
  String activeSiteKey;

  /// Result layout: 1 = list rows, 2 = grid (Setting.getSearchColumn,
  /// 0 = 1 or 2 by pad/landscape, clamped to 1..2).
  int columnCount;
  bool isGrid;

  /// Grid columns from SearchGridLayout.resolveSpanCount (1 when the
  /// layout is a list).
  int gridSpanCount;

  /// Grid card image size in px (0 in list mode; the list row is a
  /// fixed 128dp x 96dp thumb, see b10_collect_page.dart).
  int cardImageWidthPx;
  int cardImageHeightPx;

  /// Source column width in px (vertical layout only; 0 when the
  /// sources are a horizontal row on top).
  int sourceColumnWidthPx;

  /// True when the sources render as a horizontal row on top
  /// (Setting.getSearchUi() == 0); false when they are a left column.
  bool horizontalSources;

  /// Similarity filter percent (0 = unlimited) and its menu title
  /// (search_filter_similarity_*).
  int similarity;
  String similarityTitle;

  /// Empty state: shown only when the host says so (similarity filter
  /// emptied the list with raw results behind it, or the search
  /// finished with no results at all).
  bool emptyVisible;
  String emptyText;

  String errorTitle;
  String errorMessage;
  String retryLabel;

  /// Multi-site progress (SearchProgress current/total); searchFinished
  /// is true when every site has answered.
  int progressCurrent;
  int progressTotal;
  bool searchFinished;

  /// True while a per-site page load is in flight; Dart keeps polling
  /// while this is true.
  bool hasPendingPage;

  /// True when the active source may load another page.
  bool canLoadMore;

  /// Bumps on every session mutation; Dart re-reads the snapshot when
  /// the polled progress reports a new revision.
  int revision;
}

/// One source row ("all" or one site), mirroring CollectAdapter rows.
class B10SourceItem {
  B10SourceItem({
    required this.siteKey,
    required this.displayName,
    required this.selected,
    required this.progressText,
  });

  String siteKey;

  /// Site display name (or the "all" label for the first row).
  String displayName;
  bool selected;

  /// "current/total" on the "all" row while the search runs, else "".
  String progressText;
}

/// One search result card: B8's PicSpec image field shape plus the
/// card fields SearchAdapter binds (name/site/remark visibilities,
/// folder flag, own site key).
class B10CollectItem {
  B10CollectItem({
    required this.id,
    required this.name,
    required this.year,
    required this.remarks,
    required this.siteName,
    required this.siteKey,
    required this.siteVisible,
    required this.nameVisible,
    required this.remarkVisible,
    required this.yearVisible,
    required this.isFolder,
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
  String year;
  String remarks;
  String siteName;

  /// The item's own site key (results are per-site; the page has no
  /// single site key).
  String siteKey;
  bool siteVisible;
  bool nameVisible;
  bool remarkVisible;
  bool yearVisible;
  bool isFolder;

  /// Clean image URL: the host ran ImgUtil.getUrl() and stripped the
  /// `@Headers=` / `@Cookie=` / `@Referer=` / `@User-Agent=` suffixes.
  String picUrl;

  /// Raw vodPic as the site returned it (headers suffixes intact).
  /// Dart passes this to openCollectItem so the detail page loads the
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

/// Lightweight polled progress (plan §4.6).
class B10Progress {
  B10Progress({
    required this.current,
    required this.total,
    required this.finished,
    required this.revision,
    required this.hasPendingPage,
  });

  int current;
  int total;
  bool finished;
  int revision;
  bool hasPendingPage;
}
