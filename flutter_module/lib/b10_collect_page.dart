import 'dart:async';

import 'package:material_ui/material_ui.dart';

import 'pigeon/b10_collect_messages.g.dart';
import 'pigeon/c1_messages.g.dart';
import 'widgets/pic_image.dart';
import 'widgets/vod_card.dart';

/// B8 B10: Flutter version of the mobile aggregate search page
/// (CollectFragment, 780 lines), as a top-level route `/collect` on the
/// shared C1 engine and as a family push from the B9 search input page
/// (plan §4.4: the /search family — input + results, family-internal
/// navigation is pure Dart; see docs/b8-b10-collect.md).
///
/// The host owns the whole search session (SiteViewModel observers,
/// master lists, per-site paging, filter state), mirroring
/// CollectFragment; Dart only renders snapshots. The multi-site search
/// is one long @async call; while it is pending Dart polls the
/// lightweight progress snapshot every 300ms (plan §4.6, no
/// EventChannel) and re-reads the full snapshot when the revision
/// changes, so results appear site by site like native. Load-more,
/// source switch, group filter and column toggle all return fresh
/// snapshots from the host; a per-site page load in flight keeps the
/// poller running (hasPendingPage).
///
/// Back / edit: inside the family (pushed from the B9 page) this pops
/// the Navigator back to the input page, keyword intact — the B9 V8
/// "result" edit callback restored (the keyboard pop itself is not
/// carried over, see docs/b8-b10-collect.md V8). As the initial route
/// (opened from a native caller via SearchActivity.direct) it closes
/// the host screen instead.
class B10CollectPage extends StatefulWidget {
  const B10CollectPage({
    super.key,
    required this.keyword,
    required this.currentSite,
    required this.siteKey,
    required this.group,
    required this.pic,
    required this.wallPic,
    required this.historyResumeCid,
    required this.historyResumeKey,
    required this.historyResumeTargetCid,
  });

  /// Entry params from the /collect route query (URL-encoded by the
  /// host) or from the B9 family push. currentSite + siteKey follow
  /// the B9 submitSearch rule: the entry siteKey only counts under
  /// current-site scope (the host resolves the home key when it is
  /// empty); direct() callers pass the explicit siteKey with
  /// currentSite false.
  final String keyword;
  final bool currentSite;
  final String siteKey;
  final String group;
  final String pic;
  final String wallPic;
  final int historyResumeCid;
  final String historyResumeKey;
  final int historyResumeTargetCid;

  @override
  State<B10CollectPage> createState() => _B10CollectPageState();
}

class _B10CollectPageState extends State<B10CollectPage>
    with WidgetsBindingObserver {
  final B10HostApi _api = B10HostApi();
  final C1HostApi _c1Api = C1HostApi();
  final ScrollController _scrollController = ScrollController();
  Timer? _pollTimer;

  B10CollectScreen? _screen;
  String? _error;
  int _revision = -1;
  int _searchSeq = 0;
  bool _searchPending = false;
  bool _loadingMore = false;
  bool _dialogBusy = false;

  bool get _needsPolling =>
      _searchPending ||
      _loadingMore ||
      (_screen?.hasPendingPage ?? false);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _scrollController.addListener(_onScroll);
    _pollTimer = Timer.periodic(
      const Duration(milliseconds: 300),
      (_) => _poll(),
    );
    _startSearch();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pollTimer?.cancel();
    _scrollController.dispose();
    // Fire-and-forget: the page is going away; the host stops the
    // search and drops the session observers.
    _api.cancelSearch().catchError((_) {});
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Returning from a detail page (or the background) re-runs the
    // search, like B8's resumed refetch (native does not refetch; see
    // docs/b8-b10-collect.md V7).
    if (state == AppLifecycleState.resumed &&
        (ModalRoute.of(context)?.isCurrent ?? false)) {
      _startSearch();
    }
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    final position = _scrollController.position;
    if (position.maxScrollExtent <= 0) return;
    if (position.pixels >= position.maxScrollExtent - 200) {
      if ((_screen?.canLoadMore ?? false) &&
          !_searchPending &&
          !_loadingMore) {
        _loadMore();
      }
    }
  }

  /// One poll tick: only while something is in flight; a revision
  /// change re-reads the full snapshot.
  Future<void> _poll() async {
    if (!mounted || !_needsPolling) return;
    try {
      final B10Progress progress = await _api.getProgress();
      if (!mounted) return;
      if (progress.revision == _revision) return;
      final B10CollectScreen screen = await _api.getSnapshot();
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _revision = screen.revision;
      });
    } catch (_) {
      // A failed poll leaves the last snapshot on screen; the
      // in-flight @async call (or the next tick) still completes.
    }
  }

  /// Starts (or restarts) the multi-site search. Serialized: a second
  /// call while one is in flight is dropped (B8 pattern); the stale
  /// call's result is ignored by the sequence guard.
  Future<void> _startSearch() async {
    if (_searchPending) return;
    final int seq = ++_searchSeq;
    setState(() {
      _searchPending = true;
      _error = null;
    });
    try {
      final B10CollectScreen screen = await _api.startSearch(
        widget.keyword,
        widget.currentSite,
        widget.siteKey,
        widget.group,
        widget.pic,
        widget.wallPic,
        widget.historyResumeCid,
        widget.historyResumeKey,
        widget.historyResumeTargetCid,
      );
      if (!mounted || seq != _searchSeq) return;
      setState(() {
        _screen = screen;
        _revision = screen.revision;
        _searchPending = false;
      });
      if (screen.errorMessage.isNotEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(screen.errorMessage)),
        );
      }
    } catch (e) {
      if (!mounted || seq != _searchSeq) return;
      // The host always completes startSearch exactly once (including
      // its watchdog), so reaching here means the channel itself broke;
      // keep the list when there is one.
      setState(() {
        _searchPending = false;
        if (_screen == null) _error = '加载失败：$e';
      });
    }
  }

  Future<void> _loadMore() async {
    if (_loadingMore) return;
    setState(() => _loadingMore = true);
    try {
      final B10CollectScreen screen = await _api.loadMore();
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _revision = screen.revision;
        _loadingMore = false;
      });
      if (screen.errorMessage.isNotEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(screen.errorMessage)),
        );
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _loadingMore = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('加载失败：$e')),
      );
    }
  }

  Future<void> _applyScreen(Future<B10CollectScreen> call) async {
    try {
      final B10CollectScreen screen = await call;
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _revision = screen.revision;
      });
    } catch (_) {
      // The host action failed; the page stays as it was.
    }
  }

  Future<void> _onSourceTap(B10SourceItem source) async {
    if (_searchPending || _loadingMore) return;
    if (source.siteKey == _screen?.activeSiteKey) return;
    await _applyScreen(_api.setActiveSource(source.siteKey));
  }

  Future<void> _onGroupPicked(String value) async {
    if (_searchPending || _loadingMore) return;
    await _applyScreen(_api.setGroupFilter(value));
  }

  Future<void> _onColumnToggle() async {
    final B10CollectScreen? screen = _screen;
    if (screen == null || _searchPending || _loadingMore) return;
    await _applyScreen(
      _api.setColumn(screen.columnCount == 1 ? 2 : 1),
    );
  }

  Future<void> _onSimilarity() async {
    if (_dialogBusy) return;
    setState(() => _dialogBusy = true);
    try {
      final B10CollectScreen screen = await _api.openSimilarityDialog();
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _revision = screen.revision;
      });
    } catch (_) {
      // The dialog failed to open; the page stays as it was.
    } finally {
      if (mounted) setState(() => _dialogBusy = false);
    }
  }

  Future<void> _onItemTap(B10CollectItem item) async {
    try {
      await _api.openCollectItem(
        item.id,
        item.name,
        item.rawPic,
        item.isFolder,
        item.siteKey,
      );
    } catch (_) {
      // The host navigation failed; the page stays as it was.
    }
  }

  Future<void> _onBack() async {
    // Family push: pop back to the input page. Initial route: close
    // the host screen (B8 pattern).
    if (Navigator.of(context).canPop()) {
      Navigator.of(context).pop();
      return;
    }
    try {
      await _c1Api.closeScreen();
    } catch (_) {
      // The host screen is already gone; nothing more to do.
    }
  }

  @override
  Widget build(BuildContext context) {
    final B10CollectScreen? screen = _screen;
    final double ratio = MediaQuery.devicePixelRatioOf(context);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, Object? result) {
        if (didPop) return;
        _onBack();
      },
      child: Scaffold(
        appBar: AppBar(
          leading: IconButton(
            icon: const Icon(Icons.arrow_back),
            onPressed: _onBack,
          ),
          // Native: tapping the toolbar is the "edit" action (pop back
          // to the input page). The title tap does the same here; the
          // edit icon makes it discoverable.
          title: GestureDetector(
            onTap: _onBack,
            child: Text(screen?.title ?? ''),
          ),
          actions: [
            if (screen != null && screen.canFilterGroup)
              PopupMenuButton<String>(
                onSelected: _onGroupPicked,
                itemBuilder: (BuildContext context) =>
                    <PopupMenuEntry<String>>[
                  PopupMenuItem<String>(
                    value: '',
                    child: Text(screen.allScopeLabel),
                  ),
                  for (final String group in screen.groups)
                    PopupMenuItem<String>(
                      value: group,
                      child: Text(group),
                    ),
                ],
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  child: Center(
                    child: Text(
                      screen.currentGroup.isEmpty
                          ? screen.allScopeLabel
                          : screen.currentGroup,
                      style: const TextStyle(fontSize: 14),
                    ),
                  ),
                ),
              ),
            if (screen != null)
              TextButton(
                onPressed: _dialogBusy ? null : _onSimilarity,
                child: Text(screen.similarityTitle),
              ),
            if (screen != null)
              IconButton(
                icon: Icon(
                  screen.columnCount == 1
                      ? Icons.view_module
                      : Icons.view_list,
                ),
                onPressed: _onColumnToggle,
              ),
            IconButton(
              icon: const Icon(Icons.edit_outlined),
              onPressed: _onBack,
            ),
          ],
        ),
        body: _error != null
            ? _scrollableCenter(Text(_error!))
            : screen == null
                ? _scrollableCenter(const CircularProgressIndicator())
                : _buildBody(screen, ratio),
      ),
    );
  }

  Widget _buildBody(B10CollectScreen screen, double ratio) {
    final Widget results = Expanded(
      child: RefreshIndicator(
        onRefresh: _startSearch,
        child: _buildResults(screen, ratio),
      ),
    );
    final Widget sources = _buildSources(screen, ratio);
    final Widget content;
    if (screen.horizontalSources) {
      content = Column(
        children: [sources, results],
      );
    } else {
      content = Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [sources, results],
      );
    }
    return Column(
      children: [
        Expanded(child: content),
        if (_searchPending || _loadingMore)
          const LinearProgressIndicator(),
      ],
    );
  }

  /// Source list: a horizontal 56dp row on top, or a left column
  /// (native setSearchLayout; adapter_collect.xml rows are 14sp).
  Widget _buildSources(B10CollectScreen screen, double ratio) {
    if (screen.horizontalSources) {
      return SizedBox(
        height: 56,
        child: ListView.builder(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 8),
          itemCount: screen.sources.length,
          itemBuilder: (BuildContext context, int index) {
            final B10SourceItem source = screen.sources[index];
            return _SourceRow(
              source: source,
              onTap: () => _onSourceTap(source),
            );
          },
        ),
      );
    }
    return SizedBox(
      width: screen.sourceColumnWidthPx / ratio,
      child: ListView.builder(
        padding: const EdgeInsets.only(bottom: 8),
        itemCount: screen.sources.length,
        itemBuilder: (BuildContext context, int index) {
          final B10SourceItem source = screen.sources[index];
          return _SourceRow(
            source: source,
            onTap: () => _onSourceTap(source),
          );
        },
      ),
    );
  }

  Widget _buildResults(B10CollectScreen screen, double ratio) {
    if (screen.errorMessage.isNotEmpty && screen.items.isEmpty) {
      return _scrollableCenter(
        _LoadErrorView(
          screen: screen,
          onRetry: _startSearch,
        ),
      );
    }
    if (screen.emptyVisible) {
      return _scrollableCenter(
        Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            screen.emptyText,
            style: const TextStyle(fontSize: 16),
            textAlign: TextAlign.center,
          ),
        ),
      );
    }
    if (screen.items.isEmpty) {
      // Still searching with nothing yet: a spinner. (When the search
      // finished with no items at all, emptyVisible above shows the
      // host empty text instead.)
      return _scrollableCenter(const CircularProgressIndicator());
    }
    if (screen.isGrid) {
      final double imageHeightDp = screen.cardImageHeightPx / ratio;
      return GridView.builder(
        controller: _scrollController,
        padding: const EdgeInsets.fromLTRB(0, 8, 8, 8),
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: screen.gridSpanCount < 1
              ? 1
              : screen.gridSpanCount,
          crossAxisSpacing: 8,
          mainAxisSpacing: 8,
          mainAxisExtent: VodCard.totalHeight(
            imageHeight: imageHeightDp,
            hasProgress: false,
            twoLines: true,
          ),
        ),
        itemCount: screen.items.length,
        itemBuilder: (BuildContext context, int index) {
          final B10CollectItem item = screen.items[index];
          return VodCard(
            key: ValueKey<String>('${item.siteKey}:${item.id}'),
            picUrl: item.picUrl,
            picReferer: item.picReferer,
            picCookie: item.picCookie,
            picUserAgent: item.picUserAgent,
            picExtraHeadersJson: item.picExtraHeadersJson,
            fallbackPicUrl: item.fallbackPicUrl,
            placeholderText: item.placeholderText,
            placeholderColor: item.placeholderColor,
            imageHeight: imageHeightDp,
            vodName: item.nameVisible ? item.name : '',
            remarks: item.remarks,
            remarksVisible: item.remarkVisible,
            siteName: item.siteName,
            siteVisible: item.siteVisible,
            // Search cards have no playback badge or progress bar.
            playbackText: null,
            progress: null,
            deleteMode: false,
            onTap: () => _onItemTap(item),
            onLongPress: () {},
          );
        },
      );
    }
    return ListView.builder(
      controller: _scrollController,
      itemCount: screen.items.length,
      itemBuilder: (BuildContext context, int index) {
        final B10CollectItem item = screen.items[index];
        return _B10ListRow(
          key: ValueKey<String>('${item.siteKey}:${item.id}'),
          item: item,
          onTap: () => _onItemTap(item),
        );
      },
    );
  }

  /// Pull-to-refresh must work on the loading/empty/error states too,
  /// so they live inside a scrollable that always scrolls (B8 pattern).
  Widget _scrollableCenter(Widget child) {
    return CustomScrollView(
      physics: const AlwaysScrollableScrollPhysics(),
      slivers: [
        SliverFillRemaining(
          hasScrollBody: false,
          child: Center(child: child),
        ),
      ],
    );
  }
}

/// One source row, mirroring adapter_collect.xml: 14sp text, 8dp
/// margin + padding, rounded background; the selected row takes the
/// primary color (native uses the selector_text state list). The "all"
/// row appends the "current/total" progress while the search runs.
class _SourceRow extends StatelessWidget {
  const _SourceRow({
    required this.source,
    required this.onTap,
  });

  final B10SourceItem source;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    final String text = source.progressText.isEmpty
        ? source.displayName
        : '${source.displayName} ${source.progressText}';
    final Widget label = Text(
      text,
      style: TextStyle(
        color:
            source.selected ? scheme.primary : scheme.onSurface,
        fontSize: 14,
      ),
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      textAlign: TextAlign.center,
    );
    return InkWell(
      onTap: onTap,
      borderRadius: const BorderRadius.circular(8),
      child: Container(
        margin: const EdgeInsets.all(8),
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: scheme.surfaceContainer,
          borderRadius: const BorderRadius.circular(8),
        ),
        alignment: Alignment.center,
        child: label,
      ),
    );
  }
}

/// List-style result row, mirroring adapter_search.xml: 128dp row, 96dp
/// thumb, name 14sp (up to 3 lines) + remark 12sp, 8dp margin, site
/// badge (12sp, max 90dp) overlaid at the image's top-left corner.
class _B10ListRow extends StatelessWidget {
  const _B10ListRow({
    super.key,
    required this.item,
    required this.onTap,
  });

  final B10CollectItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return Container(
      height: 128,
      margin: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: scheme.surfaceContainer,
        borderRadius: const BorderRadius.circular(8),
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: const BorderRadius.circular(8),
        child: Row(
          children: [
            SizedBox(
              width: 96,
              height: double.infinity,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  ClipRRect(
                    borderRadius: const BorderRadius.circular(8),
                    child: PicImage(
                      picUrl: item.picUrl,
                      picReferer: item.picReferer,
                      picCookie: item.picCookie,
                      picUserAgent: item.picUserAgent,
                      picExtraHeadersJson: item.picExtraHeadersJson,
                      fallbackPicUrl: item.fallbackPicUrl,
                      placeholderText: item.placeholderText,
                      placeholderColor: item.placeholderColor,
                      height: 128,
                    ),
                  ),
                  if (item.siteVisible)
                    Positioned(
                      top: 0,
                      left: 0,
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 90),
                        child: Container(
                          padding: const EdgeInsets.all(4),
                          decoration: BoxDecoration(
                            color: scheme.surfaceContainer,
                            borderRadius: const BorderRadius.only(
                              topLeft: Radius.circular(8),
                              topRight: Radius.circular(8),
                              bottomRight: Radius.circular(8),
                            ),
                          ),
                          child: Text(
                            item.siteName,
                            style: TextStyle(
                              color: scheme.primary,
                              fontSize: 12,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Text(
                        item.nameVisible ? item.name : '',
                        style: const TextStyle(fontSize: 14),
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    if (item.remarkVisible) ...[
                      const SizedBox(height: 4),
                      Text(
                        item.remarks,
                        style: const TextStyle(fontSize: 12),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Search error state: host title + message + retry (B8 pattern).
class _LoadErrorView extends StatelessWidget {
  const _LoadErrorView({required this.screen, required this.onRetry});

  final B10CollectScreen screen;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            screen.errorTitle,
            style: const TextStyle(fontSize: 18),
            textAlign: TextAlign.center,
          ),
          if (screen.errorMessage.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              screen.errorMessage,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.center,
            ),
          ],
          const SizedBox(height: 24),
          FilledButton(
            onPressed: onRetry,
            child: Text(screen.retryLabel),
          ),
        ],
      ),
    );
  }
}
