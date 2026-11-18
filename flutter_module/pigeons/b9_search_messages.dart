import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2_messages.dart: generated Dart code lives
    // inside the module; the generated Java code lands in the host app
    // source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b9_search_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B9Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B9Messages',
    ),
  ),
)
@HostApi()
abstract class B9HostApi {
  /// Initial snapshot for the search input page: every display string,
  /// the cached hot-word groups (Setting, as the native page shows them
  /// before the network refresh), the stored search records, and the
  /// initial scope state. The route params are echoed back by Dart on
  /// submit; the host does not store them. Synchronous: everything here
  /// is local state, no I/O.
  B9SearchScreen getSearchScreen(
    String keyword,
    String siteKey,
    String pic,
    String wallPic,
  );

  /// Fresh hot words: three parallel 360kan rank calls (TV / movie /
  /// variety, same URLs, Referer and per-group save as the native
  /// page), each group falling back to its cached values on failure.
  /// Async: the Future completes with the merged result when all three
  /// calls returned or the timeout fired, so Dart can paint the cached
  /// groups immediately and swap in the fresh ones.
  @async
  B9HotWords refreshHotWords();

  /// Suggest words for the current query: iqiyi + tencent in parallel,
  /// merged and deduped (SearchSuggest.merge, LIMIT 20), exactly like
  /// the native page — except the native page paints each source as it
  /// arrives, while this returns one merged batch (see
  /// docs/b8-b9-search.md V4). Async: completes with the merge of
  /// whatever arrived when both calls returned or the timeout fired.
  /// Dart drops stale results with its own sequence guard.
  @async
  B9SuggestResult getSuggest(String keyword);

  /// Stored search records only (re-read after returning from the
  /// native results page, whose submit added the keyword 250ms later).
  List<String> getRecords();

  /// Deletes one record immediately (no confirm dialog — the native
  /// long-press deletes directly, RecordAdapter.ViewHolder.onLongClick).
  /// Dart updates its local list after the call returns.
  void deleteRecord(String record);

  /// Opens the native site dialog in search mode (SiteDialog, via
  /// reflection — it is mobile-only). The native search page passes no
  /// SiteListener, so the headless parent fragment implements none and
  /// a site tap only dismisses, exactly like native. Async: completes
  /// with the post-dismiss current-site availability, so Dart can
  /// refresh the scope state the dialog's searchable toggles may have
  /// changed.
  @async
  bool openSiteDialog();
}

/// Initial snapshot of the search input page, so Dart never sees a torn
/// state between two calls. The hot lists are the cached values; Dart
/// refreshes them once via refreshHotWords after first paint.
class B9SearchScreen {
  B9SearchScreen({
    required this.hintText,
    required this.recordTitle,
    required this.hotTitle,
    required this.suggestTitle,
    required this.allScopeLabel,
    required this.currentScopeLabel,
    required this.notSearchableMessage,
    required this.hotTvTitle,
    required this.hotMovieTitle,
    required this.hotVarietyTitle,
    required this.initialCurrentSite,
    required this.currentSiteAvailable,
    required this.records,
    required this.hotTv,
    required this.hotMovie,
    required this.hotVariety,
  });

  /// TextField hint (search_keyword).
  String hintText;

  /// Section titles: records (search_record), hot words (search_hot),
  /// suggest words (search_suggest).
  String recordTitle;
  String hotTitle;
  String suggestTitle;

  /// Scope popup labels: all sites (search_scope_all), current site
  /// (search_scope_current).
  String allScopeLabel;
  String currentScopeLabel;

  /// Toast text when the current site is picked but the home site is
  /// not searchable (detail_site_not_searchable). Dart shows it as a
  /// SnackBar; native shows a toast (see docs/b8-b9-search.md V6).
  String notSearchableMessage;

  /// Hot group titles (search_hot_tv / search_hot_movie /
  /// search_hot_variety).
  String hotTvTitle;
  String hotMovieTitle;
  String hotVarietyTitle;

  /// Initial scope: true when the entry carried a siteKey, or when the
  /// "current site by default" preference is on and the home site is
  /// searchable — the native SearchFragment.initView computation.
  bool initialCurrentSite;

  /// True when the home site exists and is searchable; gates the
  /// current-site scope pick.
  bool currentSiteAvailable;

  /// Stored search records, most recent first, max 10.
  List<String> records;

  /// Cached hot words per group, max 10 each.
  List<String> hotTv;
  List<String> hotMovie;
  List<String> hotVariety;
}

/// Fresh hot words, one list per group.
class B9HotWords {
  B9HotWords({
    required this.tv,
    required this.movie,
    required this.variety,
  });

  List<String> tv;
  List<String> movie;
  List<String> variety;
}

/// Merged suggest words for one query (iqiyi + tencent, deduped,
/// max 20).
class B9SuggestResult {
  B9SuggestResult({required this.words});

  List<String> words;
}
