import 'dart:ui' show ImageFilter;

import 'package:material_ui/material_ui.dart';

import 'pigeon/b11_home_messages.g.dart';
import 'pigeon/c1_messages.g.dart';

import 'b12_home_block_a.dart';
import 'b13_home_block_b.dart';

/// B8 B11: Flutter version of the mobile home shell (HomeActivity +
/// VodFragment top bar / tabs / bottom bar).
///
/// Scope is the SHELL only: the top bar (logo / title / search pill /
/// overflow menu), the category tabs and the glass bottom bar. The
/// AppBar blocks below the search pill are the B12 widgets (banner +
/// continue watching, flag-gated) and the B13 placeholder (JS widget
/// blocks), and the tab-page content area is a placeholder too
/// (tapping it opens the B8 category screen for the selected tab, so
/// the tab -> category mapping stays verifiable). All strings, computed
/// values and glass parameters come from the host
/// (B11HostApi.getHomeShell), so this page renders exactly what the
/// native home shows, in the current locale.
///
/// Cross-screen navigation always goes through the host (plan §4.4):
/// history / keep / settings / category open via host calls, and the
/// native home underneath stays alive while this screen is on top
/// (back lands on the native home — one extra back press to exit).
///
/// Refresh: Dart cannot subscribe to the native EventBus, so this page
/// re-fetches on first load and on every resume (C1/C2 pattern), which
/// covers returning from playback and from other screens. A native
/// dialog produces no lifecycle event, so state changed by a dialog
/// (site switch, sync) is picked up by the explicit refetch after the
/// awaited dialog call, not by resume.
///
/// Deliberate deviations from native (see docs/b8-b11-home.md): the
/// glass bar is a BackdropFilter approximation (no lens distortion /
/// dispersion); the logo is a Material icon, not the ic_logo drawable;
/// the title uses single-line ellipsis instead of marquee; the AppBar
/// does not collapse and the tabs do not stick on scroll (the content
/// area is a static placeholder, so there is nothing to scroll yet);
/// the sync-dialog-close refresh is still resume-only.
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

  void _openTabCategory() {
    final B11HomeScreen? screen = _screen;
    if (screen == null || screen.tabs.isEmpty) return;
    final B11HomeTab tab = screen.tabs[_selectedTab];
    _b11Api.openTabCategory(tab.typeId, tab.typeJson);
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
        body: SafeArea(
          child: screen == null ? _buildLoadingOrError() : _buildHome(screen),
        ),
        bottomNavigationBar: screen == null
            ? null
            : _GlassBottomBar(
                screen: screen,
                onTap: _onBottomBarTap,
                onLongPress: _onBottomBarLongPress,
              ),
      ),
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
    // Tab-page placeholder (B12/B13 own the real content): shows the
    // selected tab and the host empty text. Tapping it opens the B8
    // category screen for the tab, so the tab -> category mapping stays
    // verifiable; there is no button label to hard-code, the whole area
    // is the action.
    final B11HomeTab tab = screen.tabs[_selectedTab];
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: _openTabCategory,
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                tab.typeName,
                style: const TextStyle(fontSize: 18),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              Text(
                screen.tabPlaceholderEmptyText,
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The Dart copy of the native glass bottom bar (HomeActivity +
/// GlassNavBar): the same items in the same order, the same selection
/// pill, the same glass parameters from the host snapshot. Glass mode
/// uses a BackdropFilter blur over a translucent surface; plain mode is
/// an opaque bar. Icons are Material approximations of the native
/// ic_nav_* drawables (see docs/b8-b11-home.md V5).
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
    final Widget bar = Container(
      height: screen.glassEnabled ? 76 : 56,
      decoration: BoxDecoration(
        color: screen.glassEnabled
            ? surface.withAlpha((opacity * 255).round())
            : surface,
        borderRadius: screen.glassEnabled
            ? const BorderRadius.vertical(top: Radius.circular(20))
            : null,
      ),
      child: SafeArea(
        top: false,
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
      ),
    );
    if (!screen.glassEnabled || blur <= 0) return bar;
    return ClipRRect(
      borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: blur, sigmaY: blur),
        child: bar,
      ),
    );
  }
}
