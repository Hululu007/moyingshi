import 'dart:convert';

import 'package:material_ui/material_ui.dart';

import 'pigeon/b8_category_messages.g.dart';
import 'pigeon/c1_messages.g.dart';
import 'widgets/pic_image.dart';
import 'widgets/vod_card.dart';

/// B8 B8: Flutter version of the native mobile category list
/// (TypeFragment + FolderFragment + FolderActivity, standalone entry).
/// The first network screen: one page at a time from the host's one-shot
/// SiteViewModel subscription, pull-to-refresh, load-more on scroll, and
/// filter chips above the list (the standalone native screen has no
/// filter UI; the home filter dialog does — see docs/b8-b8-category.md).
///
/// The page renders three card forms by host style, mirroring VodAdapter:
/// rect -> the shared VodCard grid, list -> page-local 40dp-thumb rows,
/// oval -> page-local circular-image grid cells. All strings, computed
/// values and grid geometry come from the host
/// (B8HostApi.getCategoryPage) so this page renders what the native
/// screen shows, in the current locale.
///
/// Navigation: folder cards drill down inside this route's own Navigator
/// (pure Dart, like FolderFragment.openFolder — the drilled page reuses
/// the parent typeJson for style/filters); every other card tap and the
/// long-press search go through the host. Back pops the drill stack
/// first, then closes the host screen.
///
/// Refresh: Dart cannot subscribe to the native EventBus, so this page
/// re-fetches page 1 on every resume (C1/C2/B2 pattern), which covers
/// returning from a detail page. Foreground pushes (e.g. a site update
/// while the page is visible) do not arrive; pull-to-refresh covers them.
class B8CategoryPage extends StatefulWidget {
  const B8CategoryPage({
    super.key,
    required this.siteKey,
    required this.typeId,
    required this.typeJson,
    required this.historyResumeCid,
    required this.historyResumeKey,
    required this.historyResumeTargetCid,
  });

  final String siteKey;
  final String typeId;
  final String typeJson;
  final int historyResumeCid;
  final String historyResumeKey;
  final int historyResumeTargetCid;

  @override
  State<B8CategoryPage> createState() => _B8CategoryPageState();
}

class _B8CategoryPageState extends State<B8CategoryPage> {
  final C1HostApi _c1Api = C1HostApi();
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();

  Future<void> _onBack() async {
    final NavigatorState? navigator = _navigatorKey.currentState;
    if (navigator != null && navigator.canPop()) {
      navigator.pop();
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
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, Object? result) {
        if (didPop) return;
        _onBack();
      },
      child: Navigator(
        key: _navigatorKey,
        onGenerateRoute: (RouteSettings settings) => MaterialPageRoute<void>(
          builder: (_) => _B8CategoryListView(
            siteKey: widget.siteKey,
            typeJson: widget.typeJson,
            typeId: widget.typeId,
            extendJson: '',
            titleOverride: '',
            historyResumeCid: widget.historyResumeCid,
            historyResumeKey: widget.historyResumeKey,
            historyResumeTargetCid: widget.historyResumeTargetCid,
          ),
          settings: settings,
        ),
      ),
    );
  }
}

/// One level of the category list: the root page or one drill-down level.
/// Drill pages reuse the parent typeJson (style + filters come from the
/// parent Class, like FolderFragment.openFolder passing the parent
/// TypeFragment's style and extend map); only the typeId changes.
class _B8CategoryListView extends StatefulWidget {
  const _B8CategoryListView({
    required this.siteKey,
    required this.typeJson,
    required this.typeId,
    required this.extendJson,
    required this.titleOverride,
    required this.historyResumeCid,
    required this.historyResumeKey,
    required this.historyResumeTargetCid,
  });

  final String siteKey;
  final String typeJson;
  final String typeId;

  /// Filter map to send on the first load ('' = let the host apply the
  /// Class filters' init values; drill pages pass the parent's map).
  final String extendJson;

  /// AppBar title when non-empty (drill pages show the folder name;
  /// the root page uses the snapshot title instead).
  final String titleOverride;
  final int historyResumeCid;
  final String historyResumeKey;
  final int historyResumeTargetCid;

  @override
  State<_B8CategoryListView> createState() => _B8CategoryListViewState();
}

class _B8CategoryListViewState extends State<_B8CategoryListView>
    with WidgetsBindingObserver {
  final B8HostApi _b8Api = B8HostApi();
  final C1HostApi _c1Api = C1HostApi();
  final ScrollController _scrollController = ScrollController();

  B8CategoryScreen? _screen;
  String? _error;
  List<B8CategoryItem> _items = [];
  List<B8FilterGroup> _filters = [];
  String _extendJson = '';
  String _title = '';
  String _styleType = 'rect';
  int _columnCount = 1;
  double _imageWidthDp = 0;
  double _imageHeightDp = 0;
  int _nextPage = 1;
  bool _hasMore = false;
  bool _busy = false;
  bool _loadingMore = false;
  bool _pageError = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _extendJson = widget.extendJson;
    _scrollController.addListener(_onScroll);
    _loadPage(1);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _scrollController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Returning from a detail page (or the background) may have changed
    // the list; refresh the visible level only — covered drill levels
    // stay as they were until the user goes back to them.
    if (state == AppLifecycleState.resumed &&
        (ModalRoute.of(context)?.isCurrent ?? false)) {
      _loadPage(1);
    }
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    final position = _scrollController.position;
    if (position.maxScrollExtent <= 0) return;
    if (position.pixels >= position.maxScrollExtent - 200) {
      if (_hasMore && !_busy) _loadPage(_nextPage);
    }
  }

  /// Loads one page. Serialized: the host ViewModel serves one
  /// subscription at a time, so a second call while one is in flight is
  /// dropped (the in-flight load already fetches the newest state).
  Future<void> _loadPage(int page) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      if (page > 1) _loadingMore = true;
    });
    try {
      final B8CategoryScreen screen = await _b8Api.getCategoryPage(
        widget.siteKey,
        widget.typeJson,
        widget.typeId,
        _extendJson,
        page,
      );
      if (!mounted) return;
      final bool loadMoreFailed = screen.errorMessage.isNotEmpty && page > 1;
      setState(() {
        _busy = false;
        _loadingMore = false;
        _error = null;
        if (loadMoreFailed) {
          // Keep the list, the filters, the style and the pagination
          // state; the user retries by scrolling again. (The error
          // snapshot carries rect/1/0 defaults that must not overwrite
          // the live list's style.)
          _pageError = false;
        } else {
          _screen = screen;
          _title = screen.title;
          _styleType = screen.styleType;
          _columnCount = screen.columnCount;
          // Host geometry is in px; Dart lays out in dp.
          _imageWidthDp =
              screen.cardImageWidthPx / MediaQuery.devicePixelRatioOf(context);
          _imageHeightDp =
              screen.cardImageHeightPx / MediaQuery.devicePixelRatioOf(context);
          _filters = screen.filters;
          _extendJson = screen.extendJson;
          _nextPage = screen.nextPage;
          _hasMore = screen.hasMore;
          _pageError = screen.errorMessage.isNotEmpty;
          if (page == 1) {
            _items = screen.items;
          } else {
            _items = [..._items, ...screen.items];
          }
        }
      });
      if (loadMoreFailed) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(screen.errorMessage)),
        );
      }
    } catch (e) {
      if (!mounted) return;
      // The host always completes getCategoryPage exactly once
      // (including its timeout guard), so reaching here means the
      // channel itself broke; keep the list when there is one.
      setState(() {
        _busy = false;
        _loadingMore = false;
        if (_screen == null) _error = '加载失败：$e';
      });
      if (_screen != null && page > 1) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('加载失败：$e')),
        );
      }
    }
  }

  Future<void> _onBack() async {
    final NavigatorState navigator = Navigator.of(context);
    if (navigator.canPop()) {
      navigator.pop();
      return;
    }
    try {
      await _c1Api.closeScreen();
    } catch (_) {
      // The host screen is already gone; nothing more to do.
    }
  }

  /// Filter chip tap: single-select per group with toggle-off, exactly
  /// like ValueAdapter (tap the selected value to clear the group).
  /// The extend map is the only filter state; the host re-marks the
  /// selected values in the next snapshot.
  void _onFilterTap(B8FilterGroup group, B8FilterValue value) {
    if (_busy) return;
    final Map<String, String> extend = _parseExtend(_extendJson);
    if (value.selected) {
      extend.remove(group.key);
    } else {
      extend[group.key] = value.value;
    }
    _extendJson = jsonEncode(extend);
    _loadPage(1);
  }

  Map<String, String> _parseExtend(String extendJson) {
    if (extendJson.isEmpty) return <String, String>{};
    try {
      final Object? decoded = jsonDecode(extendJson);
      if (decoded is Map<String, dynamic>) {
        return decoded.map(
          (key, value) => MapEntry(key, value?.toString() ?? ''),
        );
      }
    } catch (_) {
      // A malformed map degrades to "no filters selected".
    }
    return <String, String>{};
  }

  Future<void> _onItemTap(B8CategoryItem item) async {
    if (item.isFolder) {
      // In-family drill-down (pure Dart): the drilled page reuses the
      // parent typeJson, like FolderFragment.openFolder passing the
      // parent TypeFragment's style and extend map.
      if (!mounted) return;
      Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => _B8CategoryListView(
            siteKey: widget.siteKey,
            typeJson: widget.typeJson,
            typeId: item.id,
            extendJson: _extendJson,
            titleOverride: item.name,
            historyResumeCid: widget.historyResumeCid,
            historyResumeKey: widget.historyResumeKey,
            historyResumeTargetCid: widget.historyResumeTargetCid,
          ),
        ),
      );
      return;
    }
    try {
      await _b8Api.openCategoryItem(
        widget.siteKey,
        item.id,
        item.name,
        // Raw pic: the detail page parses the headers suffixes itself,
        // like native TypeFragment passing item.getPic().
        item.rawPic,
        item.isFolder,
        item.isAction,
        item.action,
        widget.historyResumeCid,
        widget.historyResumeKey,
        widget.historyResumeTargetCid,
      );
    } catch (_) {
      // The host navigation failed; the page stays as it was.
    }
  }

  Future<void> _onItemLongPress(B8CategoryItem item) async {
    if (item.isFolder || item.isAction) return;
    try {
      await _b8Api.searchCategoryItem(
        item.name,
        widget.historyResumeCid,
        widget.historyResumeKey,
        widget.historyResumeTargetCid,
      );
    } catch (_) {
      // The host navigation failed; the page stays as it was.
    }
  }

  @override
  Widget build(BuildContext context) {
    final B8CategoryScreen? screen = _screen;
    final String title =
        widget.titleOverride.isNotEmpty ? widget.titleOverride : _title;
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: _onBack,
        ),
        title: Text(title),
      ),
      body: Column(
        children: [
          if (_filters.isNotEmpty)
            _FilterSection(
              filters: _filters,
              busy: _busy,
              onTap: _onFilterTap,
            ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: () => _loadPage(1),
              child: _error != null
                  ? _scrollableCenter(Text(_error!))
                  : screen == null
                      ? _scrollableCenter(
                          const CircularProgressIndicator(),
                        )
                      : _pageError
                          ? _scrollableCenter(
                              _LoadErrorView(
                                screen: screen,
                                onRetry: () => _loadPage(1),
                              ),
                            )
                          : _items.isEmpty
                              ? _scrollableCenter(_EmptyView(screen: screen))
                              : _buildList(screen),
            ),
          ),
          if (_loadingMore) const LinearProgressIndicator(),
        ],
      ),
    );
  }

  /// Pull-to-refresh must work on the loading/empty/error states too,
  /// so they live inside a scrollable that always scrolls.
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

  Widget _buildList(B8CategoryScreen screen) {
    switch (_styleType) {
      case 'list':
        return ListView.builder(
          controller: _scrollController,
          itemCount: _items.length,
          itemBuilder: (BuildContext context, int index) {
            final B8CategoryItem item = _items[index];
            return _B8ListRow(
              key: ValueKey<String>('${item.id}:${item.name}'),
              item: item,
              onTap: () => _onItemTap(item),
              onLongPress: () => _onItemLongPress(item),
            );
          },
        );
      case 'oval':
        return GridView.builder(
          controller: _scrollController,
          padding: const EdgeInsets.all(8),
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: _columnCount,
            crossAxisSpacing: 16,
            mainAxisSpacing: 16,
            // Image + one 14sp name line + a 4dp gap (see _B8OvalCard).
            mainAxisExtent: _imageHeightDp + 24,
          ),
          itemCount: _items.length,
          itemBuilder: (BuildContext context, int index) {
            final B8CategoryItem item = _items[index];
            return _B8OvalCard(
              key: ValueKey<String>('${item.id}:${item.name}'),
              item: item,
              imageWidth: _imageWidthDp,
              imageHeight: _imageHeightDp,
              onTap: () => _onItemTap(item),
              onLongPress: () => _onItemLongPress(item),
            );
          },
        );
      case 'rect':
      default:
        return GridView.builder(
          controller: _scrollController,
          padding: const EdgeInsets.only(left: 16, right: 16, bottom: 16),
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: _columnCount,
            crossAxisSpacing: 16,
            mainAxisSpacing: 16,
            // The info area is always two lines (see VodCard): the
            // remarks line is transparent when the item hides it.
            mainAxisExtent: VodCard.totalHeight(
              imageHeight: _imageHeightDp,
              hasProgress: false,
              twoLines: true,
            ),
          ),
          itemCount: _items.length,
          itemBuilder: (BuildContext context, int index) {
            final B8CategoryItem item = _items[index];
            return VodCard(
              key: ValueKey<String>('${item.id}:${item.name}'),
              picUrl: item.picUrl,
              picReferer: item.picReferer,
              picCookie: item.picCookie,
              picUserAgent: item.picUserAgent,
              picExtraHeadersJson: item.picExtraHeadersJson,
              fallbackPicUrl: item.fallbackPicUrl,
              placeholderText: item.placeholderText,
              placeholderColor: item.placeholderColor,
              imageHeight: _imageHeightDp,
              vodName: item.nameVisible ? item.name : '',
              remarks: item.remarks,
              remarksVisible: item.remarkVisible,
              siteName: item.siteName,
              siteVisible: item.siteVisible,
              // Category cards have no playback badge or progress bar.
              playbackText: null,
              progress: null,
              deleteMode: false,
              onTap: () => _onItemTap(item),
              onLongPress: () => _onItemLongPress(item),
            );
          },
        );
    }
  }
}

/// Filter chips above the list: one group per row of chips, capped at
/// 40% of the screen height with its own scroll (the native home filter
/// dialog caps its sections the same way). Hidden when the Class has no
/// filters — folder entries (Result.folder) have none.
class _FilterSection extends StatelessWidget {
  const _FilterSection({
    required this.filters,
    required this.busy,
    required this.onTap,
  });

  final List<B8FilterGroup> filters;
  final bool busy;
  final void Function(B8FilterGroup, B8FilterValue) onTap;

  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      constraints: BoxConstraints(
        maxHeight: MediaQuery.sizeOf(context).height * 0.4,
      ),
      child: SingleChildScrollView(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final B8FilterGroup group in filters) ...[
                    if (group.name.isNotEmpty)
                      Text(
                        group.name,
                        style: TextStyle(
                          color: Theme.of(context)
                              .colorScheme
                              .onSurfaceVariant,
                          fontSize: 12,
                        ),
                      ),
                    const SizedBox(height: 4),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        for (final B8FilterValue value in group.values)
                          ChoiceChip(
                            label: Text(value.label),
                            selected: value.selected,
                            onSelected: busy
                                ? null
                                : (_) => onTap(group, value),
                          ),
                      ],
                    ),
                    const SizedBox(height: 12),
                  ],
                ],
              ),
            ),
            const Divider(height: 1),
          ],
        ),
      ),
    );
  }
}

/// Empty state: host title (+ description when the host sends one).
/// No primary action: the native category screen has none.
class _EmptyView extends StatelessWidget {
  const _EmptyView({required this.screen});

  final B8CategoryScreen screen;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            screen.emptyTitle,
            style: const TextStyle(fontSize: 18),
            textAlign: TextAlign.center,
          ),
          if (screen.emptyDescription.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              screen.emptyDescription,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.center,
            ),
          ],
        ],
      ),
    );
  }
}

/// Page-load error state: host title + message + retry, mirroring the
/// native ProgressLayout error content (error_config_load / error_retry).
/// The message line is hidden when the host message is empty.
class _LoadErrorView extends StatelessWidget {
  const _LoadErrorView({required this.screen, required this.onRetry});

  final B8CategoryScreen screen;
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

/// List-style row, mirroring adapter_vod_list.xml: 40dp thumb + name
/// 14sp + remark 12sp, 8dp margins, 12dp padding.
class _B8ListRow extends StatelessWidget {
  const _B8ListRow({
    super.key,
    required this.item,
    required this.onTap,
    required this.onLongPress,
  });

  final B8CategoryItem item;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        borderRadius: BorderRadius.circular(8),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              SizedBox(
                width: 40,
                height: 40,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: PicImage(
                    picUrl: item.picUrl,
                    picReferer: item.picReferer,
                    picCookie: item.picCookie,
                    picUserAgent: item.picUserAgent,
                    picExtraHeadersJson: item.picExtraHeadersJson,
                    fallbackPicUrl: item.fallbackPicUrl,
                    placeholderText: item.placeholderText,
                    placeholderColor: item.placeholderColor,
                    height: 40,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      item.nameVisible ? item.name : '',
                      style: const TextStyle(fontSize: 14),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (item.remarkVisible) ...[
                      const SizedBox(height: 2),
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
            ],
          ),
        ),
      ),
    );
  }
}

/// Oval-style grid cell, mirroring adapter_vod_oval.xml: circular image
/// (host spec size) + name 14sp below.
class _B8OvalCard extends StatelessWidget {
  const _B8OvalCard({
    super.key,
    required this.item,
    required this.imageWidth,
    required this.imageHeight,
    required this.onTap,
    required this.onLongPress,
  });

  final B8CategoryItem item;
  final double imageWidth;
  final double imageHeight;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      onLongPress: onLongPress,
      borderRadius: BorderRadius.circular(8),
      child: Column(
        children: [
          SizedBox(
            width: imageWidth,
            height: imageHeight,
            child: ClipOval(
              child: PicImage(
                picUrl: item.picUrl,
                picReferer: item.picReferer,
                picCookie: item.picCookie,
                picUserAgent: item.picUserAgent,
                picExtraHeadersJson: item.picExtraHeadersJson,
                fallbackPicUrl: item.fallbackPicUrl,
                placeholderText: item.placeholderText,
                placeholderColor: item.placeholderColor,
                height: imageHeight,
              ),
            ),
          ),
          const SizedBox(height: 4),
          Text(
            item.nameVisible ? item.name : '',
            style: const TextStyle(fontSize: 14),
            textAlign: TextAlign.center,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
        ],
      ),
    );
  }
}
