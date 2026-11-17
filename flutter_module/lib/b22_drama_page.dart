import 'package:material_ui/material_ui.dart';

import 'pigeon/b22_drama_messages.g.dart';
import 'pigeon/c1_messages.g.dart';
import 'widgets/pic_image.dart';

/// B8 B22: the short-drama theater grid and history list (scheme B, see
/// docs/b8-short-drama-scoping.md §2.2 and docs/b8-b22-drama.md).
///
/// Two independent literal routes on the shared C1 engine:
/// `/drama-theater` (the theater grid) and `/drama-history` (the
/// history list). The native ShortDramaActivity stays the entry point
/// and the play-stream host: tapping its theater/history tabs opens
/// one of these screens (start-only, the native Activity stays on the
/// back stack), and pressing back returns to the native feed. The
/// player never moves to Flutter: a theater card tap asks the host to
/// jump the native feed to the tapped drama and finishes this screen;
/// a history row tap opens the native detail page.
///
/// Both pages are dark (black background, white text), matching the
/// native immersive screen (activity_short_drama.xml is black with
/// white text); the shared light theme is deliberately not used here.
/// All strings come from the host snapshots; Dart hard-codes no locale
/// strings. Data arrives as @async snapshots (host completes exactly
/// once per call, 35s watchdog); resumed refetch follows plan §4.6.

// Refresh indicator background: the native poster placeholder color
// (#1A1A1A, item_drama_grid.xml / item_drama_history.xml).
const Color _dramaRefreshBackground = Color(0xFF1A1A1A);

/// Theater grid card geometry (native item_drama_grid.xml): 6dp card
/// padding, 160dp poster, 13sp name, 11sp remarks. The fixed row
/// height is poster + text block + padding; a little slack beats
/// clipping the remarks line under font scaling.
const double _gridPosterHeight = 160;
const double _gridRowHeight = 216;

/// History row poster (native item_drama_history.xml: 56x74dp).
const double _historyPosterWidth = 56;
const double _historyPosterHeight = 74;

Future<void> _closeScreen() async {
  try {
    await C1HostApi().closeScreen();
  } catch (_) {
    // The host owns the Activity; a failed close leaves the screen
    // in place, and the user can retry with the system back key.
  }
}

/// The theater grid: `/drama-theater` (native GridAdapter, 3 columns).
class B22TheaterPage extends StatefulWidget {
  const B22TheaterPage({super.key});

  @override
  State<B22TheaterPage> createState() => _B22TheaterPageState();
}

class _B22TheaterPageState extends State<B22TheaterPage>
    with WidgetsBindingObserver {
  final B22HostApi _api = B22HostApi();

  B22DramaTheaterScreen? _screen;
  bool _loading = true;
  String? _errorText;

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
    // Plan §4.6: refetch on resumed (native reloads the feed only on
    // source switch; the Flutter page re-reads the current source so
    // a source switched in the native dialog shows up on return).
    // Only when a list is already shown; the initial load covers the
    // fresh case.
    if (state == AppLifecycleState.resumed && _screen != null) {
      _refresh();
    }
  }

  Future<void> _load() async {
    try {
      final B22DramaTheaterScreen screen = await _api.getTheaterScreen();
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _loading = false;
        _errorText = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _errorText = e.toString();
      });
    }
  }

  /// Silent refetch (pull-to-refresh and resumed): the old list stays
  /// visible until the new snapshot arrives; a channel failure keeps
  /// the old list.
  Future<void> _refresh() async {
    try {
      final B22DramaTheaterScreen screen = await _api.getTheaterScreen();
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _errorText = null;
      });
    } catch (_) {
      // Keep the old list; the next resumed refetch retries.
    }
  }

  Future<void> _onCardTap(B22DramaItem item, int index) async {
    try {
      await _api.openFeedItem(item.id, index);
    } catch (_) {
      // The host finishes the screen underneath; a failed call leaves
      // the grid in place and the user can tap again.
    }
  }

  @override
  Widget build(BuildContext context) {
    final B22DramaTheaterScreen? screen = _screen;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, Object? result) {
        if (didPop) return;
        _closeScreen();
      },
      child: Scaffold(
        backgroundColor: Colors.black,
        appBar: AppBar(
          backgroundColor: Colors.black,
          foregroundColor: Colors.white,
          title: Text(screen?.title ?? ''),
          leading: IconButton(
            icon: const Icon(Icons.arrow_back),
            onPressed: _closeScreen,
          ),
        ),
        body: _buildBody(screen),
      ),
    );
  }

  Widget _buildBody(B22DramaTheaterScreen? screen) {
    if (_loading) {
      return const Center(
        child: CircularProgressIndicator(color: Colors.white),
      );
    }
    if (_errorText != null) {
      // Channel failure (diagnostic text, B2 pattern): no snapshot
      // arrived yet, so there is no host retry label; the whole view
      // is tappable to retry.
      return Center(
        child: GestureDetector(
          onTap: _load,
          behavior: HitTestBehavior.opaque,
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text(
              _errorText!,
              style: const TextStyle(color: Colors.white),
              textAlign: TextAlign.center,
            ),
          ),
        ),
      );
    }
    if (screen == null) return const SizedBox.shrink();
    if (screen.loadErrorText.isNotEmpty) {
      // Watchdog timeout: the host snapshot carries the timeout text.
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                screen.loadErrorText,
                style: const TextStyle(color: Colors.white),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: _load,
                child: Text(screen.retryLabel),
              ),
            ],
          ),
        ),
      );
    }
    if (screen.items.isEmpty) {
      // Native showState: centered text, tappable to retry.
      return Center(
        child: GestureDetector(
          onTap: _load,
          behavior: HitTestBehavior.opaque,
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text(
              screen.stateText,
              style: const TextStyle(color: Colors.white, fontSize: 15),
              textAlign: TextAlign.center,
            ),
          ),
        ),
      );
    }
    return RefreshIndicator(
      color: Colors.white,
      backgroundColor: _dramaRefreshBackground,
      onRefresh: _refresh,
      child: GridView.builder(
        padding: const EdgeInsets.all(8),
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: screen.columnCount,
          mainAxisExtent: _gridRowHeight,
        ),
        itemCount: screen.items.length,
        itemBuilder: (context, index) {
          final B22DramaItem item = screen.items[index];
          return GestureDetector(
            onTap: () => _onCardTap(item, index),
            behavior: HitTestBehavior.opaque,
            child: Padding(
              padding: const EdgeInsets.all(6),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  PicImage(
                    picUrl: item.picUrl,
                    picReferer: item.picReferer,
                    picCookie: item.picCookie,
                    picUserAgent: item.picUserAgent,
                    picExtraHeadersJson: item.picExtraHeadersJson,
                    fallbackPicUrl: item.fallbackPicUrl,
                    placeholderText: item.placeholderText,
                    placeholderColor: item.placeholderColor,
                    height: _gridPosterHeight,
                  ),
                  const SizedBox(height: 6),
                  Text(
                    item.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white, fontSize: 13),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    item.remarks,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        color: Color(0x99FFFFFF), fontSize: 11),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}

/// The history list: `/drama-history` (native HistoryAdapter).
class B22HistoryPage extends StatefulWidget {
  const B22HistoryPage({super.key});

  @override
  State<B22HistoryPage> createState() => _B22HistoryPageState();
}

class _B22HistoryPageState extends State<B22HistoryPage>
    with WidgetsBindingObserver {
  final B22HostApi _api = B22HostApi();

  B22DramaHistoryScreen? _screen;
  bool _loading = true;
  String? _errorText;

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
    // Plan §4.6: refetch on resumed (native reloads history on every
    // setMode(HISTORY); the Flutter page re-reads on return from the
    // detail page so a just-watched drama shows up).
    if (state == AppLifecycleState.resumed && _screen != null) {
      _refresh();
    }
  }

  Future<void> _load() async {
    try {
      final B22DramaHistoryScreen screen = await _api.getHistoryScreen();
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _loading = false;
        _errorText = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _errorText = e.toString();
      });
    }
  }

  /// Silent refetch (resumed): the old list stays visible until the
  /// new snapshot arrives; a channel failure keeps the old list.
  Future<void> _refresh() async {
    try {
      final B22DramaHistoryScreen screen = await _api.getHistoryScreen();
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _errorText = null;
      });
    } catch (_) {
      // Keep the old list; the next resumed refetch retries.
    }
  }

  Future<void> _onRowTap(B22DramaHistoryItem item) async {
    try {
      await _api.openHistoryItem(
          item.siteKey, item.vodId, item.vodName, item.vodPic);
    } catch (_) {
      // The host opens the native detail page; a failed call leaves
      // the list in place and the user can tap again.
    }
  }

  @override
  Widget build(BuildContext context) {
    final B22DramaHistoryScreen? screen = _screen;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, Object? result) {
        if (didPop) return;
        _closeScreen();
      },
      child: Scaffold(
        backgroundColor: Colors.black,
        appBar: AppBar(
          backgroundColor: Colors.black,
          foregroundColor: Colors.white,
          title: Text(screen?.title ?? ''),
          leading: IconButton(
            icon: const Icon(Icons.arrow_back),
            onPressed: _closeScreen,
          ),
        ),
        body: _buildBody(screen),
      ),
    );
  }

  Widget _buildBody(B22DramaHistoryScreen? screen) {
    if (_loading) {
      return const Center(
        child: CircularProgressIndicator(color: Colors.white),
      );
    }
    if (_errorText != null) {
      // Channel failure (diagnostic text, B2 pattern).
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            _errorText!,
            style: const TextStyle(color: Colors.white),
            textAlign: TextAlign.center,
          ),
        ),
      );
    }
    if (screen == null) return const SizedBox.shrink();
    // An empty list renders blank, exactly like the native history
    // view (no empty text exists for it).
    return ListView.builder(
      itemCount: screen.items.length,
      itemBuilder: (context, index) {
        final B22DramaHistoryItem item = screen.items[index];
        return GestureDetector(
          onTap: () => _onRowTap(item),
          behavior: HitTestBehavior.opaque,
          child: Padding(
            padding: const EdgeInsets.all(10),
            child: Row(
              children: [
                SizedBox(
                  width: _historyPosterWidth,
                  height: _historyPosterHeight,
                  child: PicImage(
                    picUrl: item.picUrl,
                    picReferer: item.picReferer,
                    picCookie: item.picCookie,
                    picUserAgent: item.picUserAgent,
                    picExtraHeadersJson: item.picExtraHeadersJson,
                    fallbackPicUrl: item.fallbackPicUrl,
                    placeholderText: item.placeholderText,
                    placeholderColor: item.placeholderColor,
                    height: _historyPosterHeight,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        item.vodName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            color: Colors.white, fontSize: 14),
                      ),
                      const SizedBox(height: 5),
                      Text(
                        item.meta,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            color: Color(0x99FFFFFF), fontSize: 12),
                      ),
                    ],
                  ),
                ),
                Text(
                  item.timeText,
                  style: const TextStyle(
                      color: Color(0x80FFFFFF), fontSize: 11),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
