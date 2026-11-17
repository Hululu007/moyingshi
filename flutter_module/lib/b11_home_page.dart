import 'dart:io' show File;
import 'dart:ui' show ImageFilter;

import 'package:material_ui/material_ui.dart';

import 'pigeon/b11_home_messages.g.dart';
import 'pigeon/b8_category_messages.g.dart';
import 'pigeon/c1_messages.g.dart';

import 'b12_home_block_a.dart';
import 'b13_home_block_b.dart';
import 'widgets/pic_image.dart';
import 'widgets/vod_card.dart';

/// B8 B11: Flutter version of the mobile home shell (HomeActivity +
/// VodFragment top bar / tabs / bottom bar) with the inline video grid
/// for the selected tab (b8-home-grid).
///
/// Scope is the shell plus the tab grid: the top bar (logo / title /
/// search pill / overflow menu), the category tabs, the glass bottom
/// bar, and the grid itself (same list the B8 category screen shows for
/// the tab, same card look, load-more on scroll). The AppBar blocks
/// below the search pill are the B12 widgets (banner + continue
/// watching, flag-gated) and the B13 placeholder (JS widget blocks).
/// All strings, computed values and glass parameters come from the host
/// (B11HostApi.getHomeShell / getTabVideos), so this page renders
/// exactly what the native home shows, in the current locale.
///
/// Cross-screen navigation always goes through the host (plan §4.4):
/// history / keep / settings / category open via host calls, and grid
/// card taps open detail through the B8 host chain
/// (B8HostApi.openCategoryItem, same call the B8 page makes); the
/// native home underneath stays alive while this screen is on top
/// (back lands on the native home — one extra back press to exit).
///
/// Refresh: Dart cannot subscribe to the native EventBus, so this page
/// re-fetches on first load and on every resume (C1/C2 pattern), which
/// covers returning from playback and from other screens. A native
/// dialog produces no lifecycle event, so state changed by a dialog
/// (site switch, sync) is picked up by the explicit refetch after the
/// awaited dialog call, not by resume. The grid reloads page 1 whenever
/// the shell reloads (load epoch) or the selected tab changes.
///
/// Deliberate deviations from native (see docs/b8-b11-home.md and
/// docs/b8-home-glass.md): the glass bar is a BackdropFilter
/// approximation (no lens distortion / dispersion); the logo is a
/// Material icon, not the ic_logo drawable; the title uses single-line
/// ellipsis instead of marquee; the AppBar does not collapse and the
/// tabs do not stick on scroll; the sync-dialog-close refresh is still
/// resume-only; video/gif wallpapers show their static frame-0
/// snapshot, without motion.
class B11HomePage extends StatefulWidget {
  const B11HomePage({super.key});

  @override
  State<B11HomePage> createState() => _B11HomePageState();
}

class _B11HomePageState extends State<B11HomePage>
    with WidgetsBindingObserver {
  final B11HostApi _b11Api = B11HostApi();
  final C1HostApi _c1Api = C1HostApi();
  B11HomeScreen? _screen;
  String? _error;
  int _selectedTab = 0;

  /// B8 B12: shell load generation, passed to the B12 block widgets so
  /// they reload on resume / site switch but not on tab switches (a
  /// tab switch rebuilds this page without changing the epoch, and the
  /// banner must not reset to page 0).
  int _homeLoadEpoch = 0;

  /// errorToast already shown for the current snapshot (shown once).
  String _shownToast = '';

  /// True while the fallback close is already scheduled (one shot per
  /// snapshot, so a hk:// / WebHome site does not close twice).
  bool _fallbackClosing = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    _fallbackClosing = false;
    try {
      final B11HomeScreen screen = await _b11Api.getHomeShell();
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _error = null;
        _homeLoadEpoch++;
        if (_selectedTab >= screen.tabs.length) _selectedTab = 0;
      });
      _maybeShowToast(screen);
      _maybeCloseForFallback(screen);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '$e');
    }
  }

  void _maybeShowToast(B11HomeScreen screen) {
    if (screen.errorToast.isEmpty || screen.errorToast == _shownToast) {
      return;
    }
    _shownToast = screen.errorToast;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(screen.errorToast)),
    );
  }

  /// hk:// miniapp sites and WebHome-themed sites stay native (plan
  /// §4.4): close this screen, the native home underneath takes over.
  /// The diversion in HomeActivity already filters these, so this only
  /// fires after a site switch inside the Flutter home.
  void _maybeCloseForFallback(B11HomeScreen screen) {
    if (_fallbackClosing || (!screen.isHkHome && !screen.isWebHome)) {
      return;
    }
    _fallbackClosing = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _c1Api.closeScreen();
    });
  }

  Future<void> _close() async {
    await _c1Api.closeScreen();
  }

  Future<void> _openSiteDialog() async {
    try {
      final bool changed = await _b11Api.openSiteDialog();
      if (changed && mounted) _load();
    } catch (_) {
      // The dialog failed to open; the home stays as-is.
    }
  }

  Future<void> _reloadConfig() async {
    try {
      await _b11Api.reloadConfig();
      if (mounted) _load();
    } catch (_) {
      // The reload failed; the home stays as-is.
    }
  }

  Future<void> _onMenuItem(B11MenuItem item) async {
    if (item.id == 'refresh') {
      _load();
      return;
    }
    try {
      await _b11Api.openMenuItem(item.id);
    } catch (_) {
      // The host action failed; the home stays as-is.
    }
  }

  void _onBottomBarTap(B11NavItem item) {
    if (item.token == 'vod') return;
    _b11Api.openBottomBarItem(item.token);
  }

  void _onBottomBarLongPress(B11NavItem item) {
    _b11Api.longPressBottomBarItem(item.token);
  }

  @override
  Widget build(BuildContext context) {
    final B11HomeScreen? screen = _screen;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, Object? result) async {
        if (didPop) return;
        await _close();
      },
      child: Scaffold(
        // Transparent once the shell has loaded so the wallpaper shows
        // through; the loading/error state keeps the default surface.
        backgroundColor: screen == null ? null : Colors.transparent,
        body: screen == null
            ? SafeArea(child: _buildLoadingOrError())
            : _buildHomeStack(screen),
      ),
    );
  }

  /// Home layers (b8-home-glass): the full-screen wallpaper at the
  /// bottom (native: CustomWallView as child 0 of android.R.id.content,
  /// behind all content), the content column inside the safe area, and
  /// the floating glass capsule overlaid at the bottom. The Positioned
  /// capsule only hit-tests on the bar itself, so content beneath the
  /// overlay margins stays tappable.
  Widget _buildHomeStack(B11HomeScreen screen) {
    return Stack(
      children: [
        Positioned.fill(
          child: _WallpaperBackground(screen: screen),
        ),
        SafeArea(child: _buildHome(screen)),
        Positioned(
          left: _glassBarInsetX,
          right: _glassBarInsetX,
          bottom: _glassBarFloat + MediaQuery.paddingOf(context).bottom,
          child: _GlassBottomBar(
            screen: screen,
            onTap: _onBottomBarTap,
            onLongPress: _onBottomBarLongPress,
          ),
        ),
      ],
    );
  }

  Widget _buildLoadingOrError() {
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_error!, textAlign: TextAlign.center),
              const SizedBox(height: 16),
              IconButton(
                icon: const Icon(Icons.refresh),
                onPressed: _load,
              ),
            ],
          ),
        ),
      );
    }
    return const Center(child: CircularProgressIndicator());
  }

  Widget _buildHome(B11HomeScreen screen) {
    return Column(
      children: [
        _buildTopBar(screen),
        _buildSearchPill(screen),
        // B8 B12: the banner carousel + continue-watching row. The
        // widgets read the B12 flag themselves: off renders the same
        // icon-only placeholders B11 showed, on renders the real
        // blocks. The epoch reloads them on resume / site switch.
        B12BannerWidget(epoch: _homeLoadEpoch),
        B12ContinueWidget(epoch: _homeLoadEpoch),
        // B8 B13: the JS widget sections. The widget reads the B13
        // flag itself: off renders the same icon-only placeholder B11
        // showed, on renders the real sections. Same epoch as B12.
        B13WidgetsWidget(epoch: _homeLoadEpoch),
        _buildTabBar(screen),
        Expanded(child: _buildTabContent(screen)),
      ],
    );
  }

  /// Native toolbar: logo + title (AppBar in fragment_vod.xml).
  /// Logo tap opens the read-only history dialog, title tap opens the
  /// site dialog; long-press on either reloads the config.
  Widget _buildTopBar(B11HomeScreen screen) {
    return SizedBox(
      height: 56,
      child: Row(
        children: [
          IconButton(
            icon: const Icon(Icons.local_movies_outlined),
            onPressed: () async {
              try {
                await _b11Api.openLogoDialog();
              } catch (_) {
                // The dialog failed to open; the home stays as-is.
              }
            },
            onLongPress: _reloadConfig,
          ),
          Expanded(
            child: GestureDetector(
              onTap: _openSiteDialog,
              onLongPress: _reloadConfig,
              child: Text(
                screen.title,
                style: const TextStyle(fontSize: 16),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// Native search pill (searchBar in fragment_vod.xml, 40dp): tap to
  /// search, long-press to search the current site, history icon opens
  /// history, overflow opens the ⋮ menu.
  Widget _buildSearchPill(B11HomeScreen screen) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: Container(
        height: 40,
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Row(
          children: [
            Expanded(
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => _b11Api.openSearch(),
                onLongPress: () => _b11Api.openSearchCurrentSite(),
                child: Row(
                  children: [
                    const SizedBox(width: 12),
                    const Icon(Icons.search, size: 20),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        screen.searchHint,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            IconButton(
              icon: const Icon(Icons.history, size: 20),
              tooltip: screen.historyTooltip,
              onPressed: () => _b11Api.openHistory(),
            ),
            PopupMenuButton<B11MenuItem>(
              icon: const Icon(Icons.more_vert, size: 20),
              tooltip: screen.moreTooltip,
              onSelected: _onMenuItem,
              itemBuilder: (BuildContext context) => screen.menuItems
                  .map(
                    (B11MenuItem item) => PopupMenuItem<B11MenuItem>(
                      value: item,
                      child: Text(item.label),
                    ),
                  )
                  .toList(),
            ),
          ],
        ),
      ),
    );
  }

  /// Category tabs (TypeAdapter chips, native: 14sp text).
  Widget _buildTabBar(B11HomeScreen screen) {
    if (screen.tabs.isEmpty) return const SizedBox.shrink();
    final Color accent = Color(screen.accentColor);
    return SizedBox(
      height: 44,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 8),
        itemCount: screen.tabs.length,
        itemBuilder: (BuildContext context, int index) {
          final bool selected = index == _selectedTab;
          return Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
            child: ChoiceChip(
              label: Text(
                screen.tabs[index].typeName,
                style: const TextStyle(fontSize: 14),
              ),
              selected: selected,
              selectedColor: accent,
              labelStyle: TextStyle(
                color: selected
                    ? Colors.white
                    : Theme.of(context).colorScheme.onSurface,
              ),
              onSelected: (_) {
                setState(() => _selectedTab = index);
              },
            ),
          );
        },
      ),
    );
  }

  Widget _buildTabContent(B11HomeScreen screen) {
    if (screen.emptyTitle.isNotEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.inbox_outlined, size: 48),
              const SizedBox(height: 12),
              Text(
                screen.emptyTitle,
                style: const TextStyle(fontSize: 16),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              Text(
                screen.emptyDescription,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: _load,
                child: Text(screen.emptyActionLabel),
              ),
            ],
          ),
        ),
      );
    }
    if (screen.tabs.isEmpty) {
      return Center(
        child: Text(
          screen.tabPlaceholderEmptyText,
          textAlign: TextAlign.center,
        ),
      );
    }
    // Inline video grid for the selected tab (b8-home-grid): the same
    // list the B8 category screen shows, rendered here. The grid
    // reloads page 1 on tab switch (new tab) and on shell reload (new
    // epoch); folder cards drill down into the /category screen.
    final B11HomeTab tab = screen.tabs[_selectedTab];
    return _TabVideoGrid(
      tab: tab,
      epoch: _homeLoadEpoch,
      emptyText: screen.tabPlaceholderEmptyText,
      bottomPadding: _glassSlotHeight,
    );
  }
}

/// B8 b8-home-glass: the full-screen home wallpaper, painted behind
/// all content (native: CustomWallView, BaseActivity child 0). The
/// image file and fallback color come from the host snapshot, resolved
/// exactly like CustomWallView.load(); a missing or unreadable file
/// degrades to the fallback color, never to a blank background. The
/// wallpaper itself is blurred 3dp when glass is on (native home:
/// ThemeController.applyWallpaperBlur(this, 3)); glass off shows it
/// sharp. Foreground content is never blurred.
class _WallpaperBackground extends StatelessWidget {
  const _WallpaperBackground({required this.screen});

  final B11HomeScreen screen;

  @override
  Widget build(BuildContext context) {
    final Color fallback = Color(screen.wallpaperColor);
    final Widget image = screen.wallpaperPath.isEmpty
        ? Container(color: fallback)
        : Image.file(
            File(screen.wallpaperPath),
            fit: BoxFit.cover,
            errorBuilder: (BuildContext context, Object error,
                StackTrace? stackTrace) {
              return Container(color: fallback);
            },
          );
    if (!screen.glassEnabled) return image;
    return ImageFiltered(
      imageFilter: ImageFilter.blur(sigmaX: 3, sigmaY: 3),
      child: image,
    );
  }
}

/// B8 b8-home-grid: the inline video grid for one home tab. Mirrors the
/// B8 category list (same host data path, same card look, same
/// load-more-on-scroll), without filters or drill-down navigation: a
/// folder card opens the B8 /category screen for the folder, every
/// other card opens detail through the B8 host chain.
///
/// Reload triggers: a new tab (chip switch), or a new shell load epoch
/// (resume / refresh / site switch). Loads are serialized: a reload
/// requested while one is in flight is remembered and runs when the
/// in-flight load settles, and results that arrive for a stale tab or
/// epoch are discarded, so the grid can never show another tab's list.
class _TabVideoGrid extends StatefulWidget {
  const _TabVideoGrid({
    required this.tab,
    required this.epoch,
    required this.emptyText,
    required this.bottomPadding,
  });

  final B11HomeTab tab;

  /// Shell load generation from the parent page; a change means the
  /// shell refetched (resume / refresh / site switch) and the grid
  /// must reload page 1.
  final int epoch;

  /// Host empty text (widget_empty), shown when the tab has no videos.
  final String emptyText;

  /// Bottom padding reserved inside the scrollable so the last row
  /// scrolls clear of the floating glass capsule (the native 88dp
  /// glass slot height).
  final double bottomPadding;

  @override
  State<_TabVideoGrid> createState() => _TabVideoGridState();
}

class _TabVideoGridState extends State<_TabVideoGrid> {
  final B11HostApi _b11Api = B11HostApi();
  final B8HostApi _b8Api = B8HostApi();
  final ScrollController _scrollController = ScrollController();

  B11TabVideos? _videos;
  List<B11TabVideoItem> _items = [];
  String _siteKey = '';
  String _styleType = 'rect';
  int _columnCount = 1;
  double _imageWidthDp = 0;
  double _imageHeightDp = 0;
  int _nextPage = 1;
  bool _hasMore = false;
  bool _busy = false;
  bool _loadingMore = false;
  bool _pageError = false;
  bool _channelBroken = false;

  /// A reload was requested while a load was in flight; it runs when
  /// the in-flight load settles.
  bool _pendingReload = false;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    _loadPage(1);
  }

  @override
  void didUpdateWidget(_TabVideoGrid oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.tab.typeId != widget.tab.typeId ||
        oldWidget.tab.typeJson != widget.tab.typeJson ||
        oldWidget.epoch != widget.epoch) {
      _loadPage(1);
    }
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
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
  /// remembered as a pending reload instead of dropped (a chip switch
  /// during a load must still show the new tab's list).
  Future<void> _loadPage(int page) async {
    if (_busy) {
      _pendingReload = true;
      return;
    }
    final B11HomeTab tab = widget.tab;
    final int epoch = widget.epoch;
    _pendingReload = false;
    setState(() {
      _busy = true;
      _pageError = false;
      _channelBroken = false;
      if (page > 1) _loadingMore = true;
    });
    B11TabVideos? videos;
    try {
      videos = await _b11Api.getTabVideos(tab.typeId, tab.typeJson, page);
    } catch (_) {
      // The channel itself broke; handled below (the host always
      // completes getTabVideos exactly once, including its timeout).
      videos = null;
    }
    if (!mounted) return;
    final bool stale =
        widget.tab.typeId != tab.typeId || widget.epoch != epoch;
    if (!stale && videos != null) {
      final B11TabVideos loaded = videos;
      final bool loadMoreFailed =
          loaded.errorMessage.isNotEmpty && page > 1;
      setState(() {
        _busy = false;
        _loadingMore = false;
        if (!loadMoreFailed) {
          // A load-more failure keeps the list, the style and the
          // pagination state; the user retries by scrolling again.
          // (The error snapshot carries rect/1/0 defaults that must
          // not overwrite the live list's style.)
          _videos = loaded;
          _siteKey = loaded.siteKey;
          _styleType = loaded.styleType;
          _columnCount = loaded.columnCount;
          // Host geometry is in px; Dart lays out in dp.
          _imageWidthDp =
              loaded.cardImageWidthPx / MediaQuery.devicePixelRatioOf(context);
          _imageHeightDp = loaded.cardImageHeightPx /
              MediaQuery.devicePixelRatioOf(context);
          _nextPage = loaded.nextPage;
          _hasMore = loaded.hasMore;
          _pageError = loaded.errorMessage.isNotEmpty;
          if (page == 1) {
            _items = loaded.items;
          } else {
            _items = [..._items, ...loaded.items];
          }
        }
      });
      if (loadMoreFailed) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(loaded.errorMessage)),
        );
      }
    } else {
      setState(() {
        _busy = false;
        _loadingMore = false;
        // A channel break with no snapshot yet shows the empty text
        // with a retry affordance; with a list already on screen the
        // list simply stays (a load-more retry is another scroll).
        if (!stale && videos == null && _videos == null) {
          _channelBroken = true;
        }
      });
    }
    if (stale || _pendingReload) {
      _pendingReload = false;
      _loadPage(1);
    }
  }

  Future<void> _onItemTap(B11TabVideoItem item) async {
    if (item.isFolder) {
      // Folder drill-down, B8 semantics: the /category screen for the
      // folder reuses the parent tab's Class JSON; only the typeId
      // changes. The host builds the route (openTabCategory).
      try {
        await _b11Api.openTabCategory(item.id, widget.tab.typeJson);
      } catch (_) {
        // The host navigation failed; the grid stays as it was.
      }
      return;
    }
    // Same call the B8 page makes, with the same arguments: the host
    // follows the native TypeFragment.onItemClick chain (action card,
    // index-site search, or the detail page). Not a history resume.
    try {
      await _b8Api.openCategoryItem(
        _siteKey,
        item.id,
        item.name,
        // Raw pic: the detail page parses the headers suffixes itself,
        // like native TypeFragment passing item.getPic().
        item.rawPic,
        item.isFolder,
        item.isAction,
        item.action,
        -1,
        '',
        -1,
      );
    } catch (_) {
      // The host navigation failed; the grid stays as it was.
    }
  }

  Future<void> _onItemLongPress(B11TabVideoItem item) async {
    if (item.isFolder || item.isAction) return;
    try {
      await _b8Api.searchCategoryItem(item.name, -1, '', -1);
    } catch (_) {
      // The host navigation failed; the grid stays as it was.
    }
  }

  @override
  Widget build(BuildContext context) {
    final B11TabVideos? videos = _videos;
    final Widget content;
    if (videos == null) {
      content = _channelBroken
          ? _buildChannelError()
          : const Center(child: CircularProgressIndicator());
    } else if (_pageError && _items.isEmpty) {
      content = _buildHostError(videos);
    } else if (_items.isEmpty) {
      content = Center(
        child: Text(
          widget.emptyText,
          textAlign: TextAlign.center,
        ),
      );
    } else {
      content = _buildList();
    }
    return Column(
      children: [
        Expanded(child: content),
        if (_loadingMore) const LinearProgressIndicator(),
      ],
    );
  }

  /// Page-load error state: host title + message + retry, mirroring the
  /// B8 category error view. Retry reloads page 1 of this tab.
  Widget _buildHostError(B11TabVideos videos) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              videos.errorTitle,
              style: const TextStyle(fontSize: 18),
              textAlign: TextAlign.center,
            ),
            if (videos.errorMessage.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(
                videos.errorMessage,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
            ],
            const SizedBox(height: 24),
            FilledButton(
              onPressed: () => _loadPage(1),
              child: Text(videos.retryLabel),
            ),
          ],
        ),
      ),
    );
  }

  /// Channel-break state (no snapshot ever arrived): the host empty
  /// text plus a retry affordance. No host error strings exist on this
  /// path, so the retry is an icon button like the shell's own
  /// loading/error view, and no label is hard-coded.
  Widget _buildChannelError() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              widget.emptyText,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            IconButton(
              icon: const Icon(Icons.refresh),
              onPressed: () => _loadPage(1),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildList() {
    switch (_styleType) {
      case 'list':
        return ListView.builder(
          controller: _scrollController,
          padding: EdgeInsets.only(bottom: widget.bottomPadding),
          itemCount: _items.length,
          itemBuilder: (BuildContext context, int index) {
            final B11TabVideoItem item = _items[index];
            return _B11ListRow(
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
          padding: EdgeInsets.fromLTRB(8, 8, 8, 8 + widget.bottomPadding),
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: _columnCount,
            crossAxisSpacing: 16,
            mainAxisSpacing: 16,
            // Image + one 14sp name line + a 4dp gap (see _B11OvalCard).
            mainAxisExtent: _imageHeightDp + 24,
          ),
          itemCount: _items.length,
          itemBuilder: (BuildContext context, int index) {
            final B11TabVideoItem item = _items[index];
            return _B11OvalCard(
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
          padding: EdgeInsets.only(
              left: 16, right: 16, bottom: 16 + widget.bottomPadding),
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
            final B11TabVideoItem item = _items[index];
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
              // Grid cards have no playback badge or progress bar.
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

/// List-style row, mirroring adapter_vod_list.xml: 40dp thumb + name
/// 14sp + remark 12sp, 8dp margins, 12dp padding. Deliberately
/// duplicated from the B8 page's row (B11 item type): each batch keeps
/// its own copy so batches stay independently revertible.
class _B11ListRow extends StatelessWidget {
  const _B11ListRow({
    super.key,
    required this.item,
    required this.onTap,
    required this.onLongPress,
  });

  final B11TabVideoItem item;
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
/// (host spec size) + name 14sp below. Deliberately duplicated from the
/// B8 page's cell (B11 item type): each batch keeps its own copy so
/// batches stay independently revertible.
class _B11OvalCard extends StatelessWidget {
  const _B11OvalCard({
    super.key,
    required this.item,
    required this.imageWidth,
    required this.imageHeight,
    required this.onTap,
    required this.onLongPress,
  });

  final B11TabVideoItem item;
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

// Native glass slot geometry (GlassNavBar.kt, activity_home.xml):
// the floating capsule is 64dp tall, floats 16dp above the bottom of
// its 88dp slot, with 24dp side insets. The grid reserves the full
// slot height as bottom padding so the last row scrolls clear of the
// capsule.
const double _glassBarHeight = 64;
const double _glassBarFloat = 16;
const double _glassBarInsetX = 24;
const double _glassSlotHeight = 88;

/// The Dart copy of the native glass bottom bar (HomeActivity +
/// GlassNavBar): a floating capsule (64dp tall, 24dp side insets, 16dp
/// float — the native 88dp glass slot), the same items in the same
/// order, the same selection pill, the same glass parameters from the
/// host snapshot. Glass mode uses a BackdropFilter blur over a
/// translucent surface, refracting the wallpaper and content behind
/// it; plain mode (glass off) keeps the capsule shape but renders
/// opaque (see docs/b8-home-glass.md). Icons are Material
/// approximations of the native ic_nav_* drawables (see
/// docs/b8-b11-home.md V5).
class _GlassBottomBar extends StatelessWidget {
  const _GlassBottomBar({
    required this.screen,
    required this.onTap,
    required this.onLongPress,
  });

  final B11HomeScreen screen;
  final ValueChanged<B11NavItem> onTap;
  final ValueChanged<B11NavItem> onLongPress;

  IconData _iconFor(String token) {
    switch (token) {
      case 'live':
        return Icons.live_tv_outlined;
      case 'drama':
        return Icons.movie_outlined;
      case 'following':
        return Icons.subscriptions_outlined;
      case 'local_source':
        return Icons.folder_open_outlined;
      case 'setting':
        return Icons.settings_outlined;
      case 'vod':
      default:
        return Icons.home_outlined;
    }
  }

  @override
  Widget build(BuildContext context) {
    final List<B11NavItem> items =
        screen.navItems.where((B11NavItem item) => item.visible).toList();
    if (items.isEmpty) return const SizedBox.shrink();
    final Color accent = Color(screen.accentColor);
    final Color surface = screen.darkMode
        ? const Color(0xFF1E1E20)
        : const Color(0xFFF4F4F6);
    final Color unselected = screen.darkMode
        ? Colors.white.withAlpha(184)
        : Colors.black.withAlpha(158);
    final double blur = screen.glassBlurDp.toDouble();
    // Native translucency is 0-100 (default 50); higher means more
    // see-through. Clamped so the bar never turns fully invisible or
    // fully opaque (approximation, see docs/b8-b11-home.md V4).
    final double opacity =
        (1 - screen.glassTranslucency / 100).clamp(0.15, 0.9);
    final BorderRadius capsule = BorderRadius.circular(_glassBarHeight / 2);
    final Widget bar = Container(
      height: _glassBarHeight,
      decoration: BoxDecoration(
        color: screen.glassEnabled
            ? surface.withAlpha((opacity * 255).round())
            : surface,
        borderRadius: capsule,
      ),
      child: Row(
        children: items.map((B11NavItem item) {
          final bool selected = item.token == screen.selectedNavToken;
          final Color color = selected ? accent : unselected;
          final Widget label = Text(
            item.label,
            style: TextStyle(fontSize: 11, color: color),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          );
          final Widget icon = Stack(
            clipBehavior: Clip.none,
            children: [
              Icon(_iconFor(item.token), size: 22, color: color),
              if (item.badgeCount > 0)
                Positioned(
                  right: -6,
                  top: -4,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 4,
                      vertical: 1,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.red,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(
                      item.badgeCount > 99
                          ? '99+'
                          : item.badgeCount.toString(),
                      style: const TextStyle(
                        fontSize: 9,
                        color: Colors.white,
                      ),
                    ),
                  ),
                ),
            ],
          );
          final Widget content = Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              icon,
              const SizedBox(height: 2),
              label,
            ],
          );
          final Widget pill = Container(
            padding:
                const EdgeInsets.symmetric(horizontal: 14, vertical: 5),
            decoration: selected
                ? BoxDecoration(
                    color: accent.withAlpha(38),
                    borderRadius: BorderRadius.circular(16),
                  )
                : null,
            child: content,
          );
          return Expanded(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => onTap(item),
              onLongPress: () => onLongPress(item),
              child: Center(child: pill),
            ),
          );
        }).toList(),
      ),
    );
    if (!screen.glassEnabled || blur <= 0) return bar;
    return ClipRRect(
      borderRadius: capsule,
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: blur, sigmaY: blur),
        child: bar,
      ),
    );
  }
}
