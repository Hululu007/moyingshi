import 'dart:async';

import 'package:material_ui/material_ui.dart';

import 'pigeon/b12_home_a_messages.g.dart';
import 'widgets/pic_image.dart';

/// B8 B12: the mobile home blocks A (banner carousel + continue
/// watching) as widgets inside the /home family (B11 page), not pages.
///
/// The B11 home page mounts these two widgets in place of its B12
/// placeholders and passes a load epoch: the epoch increments every
/// time the B11 shell snapshot loads, so the blocks reload on resume
/// and after a site switch, but NOT on tab switches (the B11 page
/// rebuilds on those without changing the epoch, and the banner must
/// not reset to page 0). Each widget reads the B12 flag itself: off
/// renders the same icon-only placeholder B11 showed, so B11's V2
/// placeholder visuals survive with the flag off.
///
/// Banner data piggybacks on the B11 shell's homeContent result (the
/// host never fetches again — see the contract); continue watching is
/// purely local, so it loads immediately even while the banner is
/// still waiting on the network. All strings and computed values come
/// from the host; Dart hard-codes no locale strings.
///
/// Auto-play mirrors native VodFragment: 4s per page, paused while
/// the user drags and while the app is backgrounded, only when there
/// is more than one page. New data resets to page 0 without animation,
/// like native setCurrentItem(0, false).
class B12BannerWidget extends StatefulWidget {
  const B12BannerWidget({super.key, required this.epoch});

  /// B11 shell load generation; reload when it changes.
  final int epoch;

  @override
  State<B12BannerWidget> createState() => _B12BannerWidgetState();
}

class _B12BannerWidgetState extends State<B12BannerWidget>
    with WidgetsBindingObserver {
  final B12HostApi _api = B12HostApi();
  final PageController _controller = PageController();

  bool? _enabled;
  List<B12BannerItem>? _items;
  int _page = 0;
  Timer? _autoPlayTimer;
  bool _userDragging = false;
  bool _appResumed = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initFlagAndLoad();
  }

  @override
  void didUpdateWidget(B12BannerWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.epoch != oldWidget.epoch) _initFlagAndLoad();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) {
      _appResumed = false;
      _stopAutoPlay();
    } else if (state == AppLifecycleState.resumed) {
      _appResumed = true;
      _restartAutoPlay();
    }
  }

  @override
  void dispose() {
    _stopAutoPlay();
    WidgetsBinding.instance.removeObserver(this);
    _controller.dispose();
    super.dispose();
  }

  Future<void> _initFlagAndLoad() async {
    try {
      final bool enabled = await _api.isB12Enabled();
      if (!mounted) return;
      setState(() => _enabled = enabled);
      if (enabled) _loadBanner();
    } catch (_) {
      // The flag read failed; keep the placeholder, native blocks stay.
      if (!mounted) return;
      setState(() => _enabled = false);
    }
  }

  Future<void> _loadBanner() async {
    try {
      final B12BannerScreen screen = await _api.getBanner();
      if (!mounted) return;
      setState(() {
        _items = screen.items;
        _page = 0;
      });
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _controller.hasClients) _controller.jumpToPage(0);
      });
      _restartAutoPlay();
    } catch (_) {
      // Keep the previous items (or the placeholder); the banner is
      // hidden, never an error view, like native.
      _stopAutoPlay();
    }
  }

  void _restartAutoPlay() {
    _stopAutoPlay();
    final List<B12BannerItem>? items = _items;
    if (items == null || items.length <= 1) return;
    if (_userDragging || !_appResumed) return;
    _autoPlayTimer = Timer.periodic(const Duration(seconds: 4), (_) {
      if (!mounted || !_controller.hasClients) return;
      final int count = _items?.length ?? 0;
      if (count <= 1) return;
      final int next = (_page + 1) % count;
      _controller.animateToPage(
        next,
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeInOut,
      );
    });
  }

  void _stopAutoPlay() {
    _autoPlayTimer?.cancel();
    _autoPlayTimer = null;
  }

  @override
  Widget build(BuildContext context) {
    if (_enabled != true) {
      // Flag off (or still loading): the B11 icon-only placeholder.
      return const _B12BlockPlaceholder(
        icon: Icons.slideshow_outlined,
        height: 188,
      );
    }
    final List<B12BannerItem>? items = _items;
    if (items == null) {
      // Still loading: same placeholder, no spinner (native hides the
      // banner until data arrives).
      return const _B12BlockPlaceholder(
        icon: Icons.slideshow_outlined,
        height: 188,
      );
    }
    if (items.isEmpty) {
      // No recommendations: hidden, like native View.GONE.
      return const SizedBox.shrink();
    }
    return SizedBox(
      height: 188,
      child: NotificationListener<ScrollNotification>(
        onNotification: (ScrollNotification notification) {
          if (notification is ScrollStartNotification) {
            _userDragging = true;
            _stopAutoPlay();
          } else if (notification is ScrollEndNotification) {
            _userDragging = false;
            _restartAutoPlay();
          }
          return false;
        },
        child: PageView.builder(
          controller: _controller,
          itemCount: items.length,
          onPageChanged: (int page) {
            // setState: the dots row must follow the page (auto-play
            // and drag both land here).
            if (mounted) setState(() => _page = page);
          },
          itemBuilder: (BuildContext context, int index) {
            final B12BannerItem item = items[index];
            return GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => _api.openBannerItem(index),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 4,
                ),
                child: Stack(
                  children: [
                    SizedBox(
                      width: double.infinity,
                      height: 180,
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(16),
                        child: PicImage(
                          picUrl: item.picUrl,
                          picReferer: item.picReferer,
                          picCookie: item.picCookie,
                          picUserAgent: item.picUserAgent,
                          picExtraHeadersJson: item.picExtraHeadersJson,
                          fallbackPicUrl: item.fallbackPicUrl,
                          placeholderText: item.placeholderText,
                          placeholderColor: item.placeholderColor,
                          height: 180,
                        ),
                      ),
                    ),
                    if (items.length > 1)
                      Positioned(
                        top: 12,
                        right: 12,
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            for (int i = 0; i < items.length; i++)
                              Container(
                                width: i == _page ? 18 : 6,
                                height: 6,
                                margin: EdgeInsets.only(
                                  right: i < items.length - 1 ? 6 : 0,
                                ),
                                decoration: BoxDecoration(
                                  color: i == _page
                                      ? Colors.white
                                      : Colors.white.withAlpha(102),
                                  borderRadius: BorderRadius.circular(3),
                                ),
                              ),
                          ],
                        ),
                      ),
                    Positioned(
                      left: 12,
                      right: 12,
                      bottom: 28,
                      child: Text(
                        item.name,
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                          color: Colors.white,
                          shadows: [
                            Shadow(
                              color: Color(0x80000000),
                              offset: Offset(0, 1),
                              blurRadius: 3,
                            ),
                          ],
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    if (item.remarksVisible)
                      Positioned(
                        left: 12,
                        right: 12,
                        bottom: 8,
                        child: Text(
                          item.remarks,
                          style: const TextStyle(
                            fontSize: 12,
                            color: Colors.white,
                            shadows: [
                              Shadow(
                                color: Color(0x80000000),
                                offset: Offset(0, 1),
                                blurRadius: 3,
                              ),
                            ],
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// B8 B12: the continue-watching row (native VodFragment.refreshContinue
/// + ContinueAdapter). Horizontal list, newest unfinished first, at
/// most 10 items; hidden when empty. Taps resume via the host.
class B12ContinueWidget extends StatefulWidget {
  const B12ContinueWidget({super.key, required this.epoch});

  /// B11 shell load generation; reload when it changes.
  final int epoch;

  @override
  State<B12ContinueWidget> createState() => _B12ContinueWidgetState();
}

class _B12ContinueWidgetState extends State<B12ContinueWidget> {
  final B12HostApi _api = B12HostApi();

  bool? _enabled;
  B12ContinueScreen? _screen;

  @override
  void initState() {
    super.initState();
    _initFlagAndLoad();
  }

  @override
  void didUpdateWidget(B12ContinueWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.epoch != oldWidget.epoch) _initFlagAndLoad();
  }

  Future<void> _initFlagAndLoad() async {
    try {
      final bool enabled = await _api.isB12Enabled();
      if (!mounted) return;
      setState(() => _enabled = enabled);
      if (enabled) _loadContinue();
    } catch (_) {
      if (!mounted) return;
      setState(() => _enabled = false);
    }
  }

  Future<void> _loadContinue() async {
    try {
      final B12ContinueScreen screen = await _api.getContinueWatching();
      if (!mounted) return;
      setState(() => _screen = screen);
    } catch (_) {
      // Keep the previous row (or the placeholder); never an error view.
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_enabled != true) {
      // Flag off (or still loading): the B11 icon-only placeholder.
      return const _B12BlockPlaceholder(
        icon: Icons.history_toggle_off_outlined,
        height: 96,
      );
    }
    final B12ContinueScreen? screen = _screen;
    if (screen == null) {
      return const _B12BlockPlaceholder(
        icon: Icons.history_toggle_off_outlined,
        height: 96,
      );
    }
    if (screen.items.isEmpty) {
      // Nothing unfinished: hidden, like native View.GONE.
      return const SizedBox.shrink();
    }
    return Padding(
      padding: const EdgeInsets.only(top: 8, bottom: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 16),
            child: Text(
              screen.title,
              style: const TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
          const SizedBox(height: 8),
          SizedBox(
            height: 184,
            child: ListView.builder(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              itemCount: screen.items.length,
              itemBuilder: (BuildContext context, int index) {
                final B12ContinueItem item = screen.items[index];
                return Container(
                  width: 104,
                  margin: const EdgeInsets.all(4),
                  child: GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: () => _api.openContinueItem(item.cid, item.key),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          width: 104,
                          height: 140,
                          child: ClipRRect(
                            borderRadius: BorderRadius.circular(16),
                            child: Stack(
                              children: [
                                SizedBox(
                                  width: double.infinity,
                                  height: 140,
                                  child: item.hasRealPic
                                      ? PicImage(
                                          picUrl: item.picUrl,
                                          picReferer: item.picReferer,
                                          picCookie: item.picCookie,
                                          picUserAgent:
                                              item.picUserAgent,
                                          picExtraHeadersJson:
                                              item.picExtraHeadersJson,
                                          fallbackPicUrl:
                                              item.fallbackPicUrl,
                                          placeholderText:
                                              item.placeholderText,
                                          placeholderColor:
                                              item.placeholderColor,
                                          height: 140,
                                        )
                                      : Container(
                                          // Native continue_placeholder.xml:
                                          // soft diagonal gradient
                                          // (surfaceContainerHigh ->
                                          // surfaceContainer), no text.
                                          decoration: BoxDecoration(
                                            gradient: LinearGradient(
                                              begin: Alignment.topLeft,
                                              end: Alignment.bottomRight,
                                              colors: [
                                                Theme.of(context)
                                                    .colorScheme
                                                    .surfaceContainerHigh,
                                                Theme.of(context)
                                                    .colorScheme
                                                    .surfaceContainer,
                                              ],
                                            ),
                                          ),
                                        ),
                                ),
                                Positioned(
                                  left: 0,
                                  right: 0,
                                  bottom: 0,
                                  child: SizedBox(
                                    height: 4,
                                    child: LinearProgressIndicator(
                                      value: item.progressPercent / 100,
                                      backgroundColor:
                                          Colors.white.withAlpha(64),
                                      valueColor:
                                          AlwaysStoppedAnimation<Color>(
                                        Theme.of(context)
                                            .colorScheme
                                            .primary,
                                      ),
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          item.displayName,
                          style: const TextStyle(fontSize: 12),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        if (item.remarksVisible)
                          Text(
                            item.remarks,
                            style: TextStyle(
                              fontSize: 12,
                              color: Theme.of(context)
                                  .colorScheme
                                  .onSurfaceVariant,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

/// The B11 icon-only block placeholder, copied here so the B12 widgets
/// render exactly what B11 showed while the flag is off or data is
/// still loading (B11's own copy stays for the B13 block).
class _B12BlockPlaceholder extends StatelessWidget {
  const _B12BlockPlaceholder({required this.icon, required this.height});

  final IconData icon;
  final double height;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: Container(
        height: height,
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Center(
          child: Icon(
            icon,
            size: 28,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ),
    );
  }
}
