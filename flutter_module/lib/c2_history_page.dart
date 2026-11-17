import 'dart:convert';

import 'package:material_ui/material_ui.dart';

import 'pigeon/c1_messages.g.dart';
import 'pigeon/c2_messages.g.dart';

/// B8 C2: Flutter version of the native mobile HistoryActivity.
///
/// One grid of the shared Vod cards (image + site badge + playback badge
/// + 2dp progress bar + name/remarks info area), mirroring
/// adapter_vod.xml. All strings, computed values and grid geometry come
/// from the host (C2HostApi.getHistory) so this page renders exactly what
/// the native screen shows, in the current locale.
///
/// Complex sub-flows stay native: the delete-all confirm dialog, the sync
/// dialog, the viewing-report dialog and opening a card (resume/detail)
/// are all host calls. Delete mode itself is local Dart state, mirroring
/// the native adapter flag: long-press toggles it, back exits it first,
/// tapping a card in delete mode deletes that one card.
///
/// Refresh: Dart cannot subscribe to the native EventBus, so this page
/// re-fetches on first load and on every resume (C1 subtitle-page
/// pattern), which covers returning from playback and from the report
/// Activity. The delete-all dialog is awaited (async host method), so the
/// reload after it always sees the post-dialog state; a native dialog
/// produces no lifecycle event, so resume alone would miss it.
///
/// Deliberate deviations from native (see docs/b8-c2-history.md): no
/// marquee (single-line ellipsis instead); the info area has a fixed
/// height; images load through Image.network with host-resolved headers
/// and no disk cache.
class C2HistoryPage extends StatefulWidget {
  const C2HistoryPage({super.key});

  @override
  State<C2HistoryPage> createState() => _C2HistoryPageState();
}

class _C2HistoryPageState extends State<C2HistoryPage>
    with WidgetsBindingObserver {
  final C2HostApi _c2Api = C2HostApi();
  final C1HostApi _c1Api = C1HostApi();
  C2HistoryScreen? _screen;
  String? _error;
  bool _deleteMode = false;

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
    // Playing, syncing or reporting may have changed history while this
    // page was covered; refresh when we come back.
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    try {
      final C2HistoryScreen screen = await _c2Api.getHistory();
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _error = null;
        // Native exits delete mode when the list becomes empty.
        if (screen.items.isEmpty) _deleteMode = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '加载失败：$e');
    }
  }

  /// Back: exit delete mode first (native onBackInvoked), otherwise close
  /// the host screen. Used by the AppBar button and the system back key.
  Future<void> _onBack() async {
    if (_deleteMode) {
      setState(() => _deleteMode = false);
      return;
    }
    await _c1Api.closeScreen();
  }

  /// Toolbar delete button: enter delete mode when the list is non-empty;
  /// in delete mode, show the native confirm dialog and reload after it
  /// dismisses (the async host call completes on dismiss).
  Future<void> _onDeletePressed() async {
    final C2HistoryScreen? screen = _screen;
    if (screen == null) return;
    if (!_deleteMode) {
      if (screen.items.isNotEmpty) setState(() => _deleteMode = true);
      return;
    }
    try {
      await _c2Api.deleteAllHistory();
    } catch (_) {
      // Reload below shows host truth either way.
    }
    await _load();
  }

  Future<void> _onItemTap(C2HistoryItem item) async {
    if (_deleteMode) {
      try {
        await _c2Api.deleteHistoryItem(item.cid, item.key);
      } catch (_) {
        // Reload below shows host truth either way.
      }
      await _load();
      return;
    }
    _c2Api.openHistoryItem(item.cid, item.key);
  }

  @override
  Widget build(BuildContext context) {
    final C2HistoryScreen? screen = _screen;
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
          title: Text(screen?.title ?? ''),
          actions: [
            IconButton(
              icon: const Icon(Icons.sync),
              tooltip: screen?.syncTooltip,
              onPressed: screen == null
                  ? null
                  : () => _c2Api.openHistorySync(),
            ),
            IconButton(
              icon: const Icon(Icons.delete_outline),
              tooltip: screen?.deleteTooltip,
              onPressed: screen == null ? null : _onDeletePressed,
            ),
            IconButton(
              icon: const Icon(Icons.bar_chart),
              tooltip: screen?.reportTooltip,
              onPressed: screen == null
                  ? null
                  : () => _c2Api.openViewingReport(),
            ),
          ],
        ),
        body: _error != null
            ? Center(child: Text(_error!))
            : screen == null
                ? const Center(child: CircularProgressIndicator())
                : screen.items.isEmpty
                    ? _EmptyView(screen: screen)
                    : _HistoryGrid(
                        screen: screen,
                        deleteMode: _deleteMode,
                        onItemTap: _onItemTap,
                        onLongPress: () =>
                            setState(() => _deleteMode = !_deleteMode),
                      ),
      ),
    );
  }
}

/// Empty state: title + explanation + one primary action, mirroring the
/// native ProgressLayout empty content.
class _EmptyView extends StatelessWidget {
  const _EmptyView({required this.screen});

  final C2HistoryScreen screen;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              screen.emptyTitle,
              style: const TextStyle(fontSize: 18),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              screen.emptyDescription,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: () => C2HostApi().goHome(),
              child: Text(screen.emptyActionLabel),
            ),
          ],
        ),
      ),
    );
  }
}

/// The card grid. Spacing mirrors the native RecyclerView: 8dp recycler
/// padding + 8dp card margin = 16dp at the edges and between cards, 0dp
/// above the first row (the native -8dp top margin cancels the card
/// margin). The row height is the host image height + the 2dp progress
/// bar + the fixed info-area height.
class _HistoryGrid extends StatelessWidget {
  const _HistoryGrid({
    required this.screen,
    required this.deleteMode,
    required this.onItemTap,
    required this.onLongPress,
  });

  final C2HistoryScreen screen;
  final bool deleteMode;
  final ValueChanged<C2HistoryItem> onItemTap;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    final double imageHeight =
        screen.cardImageHeightPx / MediaQuery.devicePixelRatioOf(context);
    return GridView.builder(
      padding: const EdgeInsets.only(left: 16, right: 16, bottom: 16),
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: screen.columnCount,
        crossAxisSpacing: 16,
        mainAxisSpacing: 16,
        mainAxisExtent: imageHeight + 2 + _HistoryCard.infoHeight,
      ),
      itemCount: screen.items.length,
      itemBuilder: (BuildContext context, int index) {
        final C2HistoryItem item = screen.items[index];
        return _HistoryCard(
          item: item,
          imageHeight: imageHeight,
          deleteMode: deleteMode,
          onTap: () => onItemTap(item),
          onLongPress: onLongPress,
        );
      },
    );
  }
}

/// One Vod card, mirroring adapter_vod.xml: rounded image with site and
/// playback badges, a 2dp progress bar, and a translucent-black info area
/// with centered name/remarks lines in constant light text. The remarks
/// line keeps its space when hidden (native INVISIBLE), rendered
/// transparent instead of being removed.
class _HistoryCard extends StatelessWidget {
  const _HistoryCard({
    required this.item,
    required this.imageHeight,
    required this.deleteMode,
    required this.onTap,
    required this.onLongPress,
  });

  /// Fixed info-area height: two single lines (14sp name + 2dp gap + 12sp
  /// remarks) with 4dp top/bottom padding. Fixed so the fixed-extent grid
  /// can never overflow; the native height is content-driven and close.
  static const double infoHeight = 52;

  final C2HistoryItem item;
  final double imageHeight;
  final bool deleteMode;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  double get _progress {
    if (item.durationMs <= 0) return 0;
    return item.positionMs.clamp(0, item.durationMs) / item.durationMs;
  }

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        borderRadius: BorderRadius.circular(8),
        child: Stack(
          children: [
            Column(
              children: [
                SizedBox(
                  height: imageHeight,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      _HistoryImage(
                        key: ValueKey<String>('${item.cid}:${item.key}'),
                        item: item,
                        imageHeight: imageHeight,
                      ),
                      if (item.siteVisible)
                        Positioned(
                          top: 0,
                          left: 0,
                          child: ConstrainedBox(
                            constraints: const BoxConstraints(maxWidth: 92),
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
                      if (!deleteMode && item.hasPlayback)
                        Positioned(
                          bottom: 0,
                          left: 0,
                          child: ConstrainedBox(
                            constraints: const BoxConstraints(maxWidth: 100),
                            child: Container(
                              padding: const EdgeInsets.all(4),
                              decoration: BoxDecoration(
                                color: scheme.surfaceContainer,
                                borderRadius: const BorderRadius.only(
                                  topRight: Radius.circular(4),
                                  bottomRight: Radius.circular(4),
                                ),
                              ),
                              child: Text(
                                item.playbackText,
                                style: TextStyle(
                                  color: scheme.onSurfaceVariant,
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
                SizedBox(
                  height: 2,
                  child: LinearProgressIndicator(
                    value: _progress,
                    backgroundColor: scheme.surfaceContainer,
                    color: scheme.primary,
                  ),
                ),
                Container(
                  height: infoHeight,
                  width: double.infinity,
                  color: const Color(0x66000000),
                  padding: const EdgeInsets.fromLTRB(6, 4, 6, 4),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(
                        item.vodName,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 14,
                        ),
                        textAlign: TextAlign.center,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      const SizedBox(height: 2),
                      Text(
                        item.remarks,
                        style: TextStyle(
                          color: item.remarksVisible
                              ? Colors.white
                              : Colors.transparent,
                          fontSize: 12,
                        ),
                        textAlign: TextAlign.center,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
              ],
            ),
            if (deleteMode)
              const Positioned.fill(
                child: ColoredBox(
                  color: Colors.black54,
                  child: Icon(
                    Icons.delete,
                    color: Colors.white,
                    size: 48,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// Card image: Image.network with the host-resolved URL and headers.
/// On failure it tries the host fallback URL once, then shows the
/// first-character placeholder. The container background shows while the
/// image loads (native shows the surface color meanwhile).
class _HistoryImage extends StatefulWidget {
  const _HistoryImage({super.key, required this.item, required this.imageHeight});

  final C2HistoryItem item;
  final double imageHeight;

  @override
  State<_HistoryImage> createState() => _HistoryImageState();
}

class _HistoryImageState extends State<_HistoryImage> {
  bool _triedFallback = false;

  Map<String, String> _headers() {
    final Map<String, String> headers = <String, String>{};
    final C2HistoryItem item = widget.item;
    if (item.picExtraHeadersJson.isNotEmpty) {
      try {
        final Object? decoded = jsonDecode(item.picExtraHeadersJson);
        if (decoded is Map<String, dynamic>) {
          decoded.forEach((key, value) {
            if (value != null) headers[key] = value.toString();
          });
        }
      } catch (_) {
        // Malformed extras are ignored; the named headers still apply.
      }
    }
    if (item.picReferer.isNotEmpty) headers['Referer'] = item.picReferer;
    if (item.picCookie.isNotEmpty) headers['Cookie'] = item.picCookie;
    if (item.picUserAgent.isNotEmpty) {
      headers['User-Agent'] = item.picUserAgent;
    }
    return headers;
  }

  Widget _placeholder() {
    return Container(
      width: double.infinity,
      height: widget.imageHeight,
      color: Color(widget.item.placeholderColor),
      alignment: Alignment.center,
      child: Text(
        widget.item.placeholderText,
        style: const TextStyle(color: Colors.white, fontSize: 32),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final C2HistoryItem item = widget.item;
    final String url = _triedFallback ? item.fallbackPicUrl : item.picUrl;
    if (url.isEmpty) return _placeholder();
    return Container(
      color: Theme.of(context).colorScheme.surfaceContainer,
      child: Image.network(
        url,
        headers: _headers(),
        fit: BoxFit.cover,
        width: double.infinity,
        height: widget.imageHeight,
        errorBuilder: (context, error, stackTrace) {
          if (!_triedFallback && item.fallbackPicUrl.isNotEmpty) {
            // setState during build is not allowed; retry with the
            // fallback URL on the next frame.
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (mounted) setState(() => _triedFallback = true);
            });
          }
          return _placeholder();
        },
      ),
    );
  }
}
