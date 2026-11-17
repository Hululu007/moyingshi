import 'package:material_ui/material_ui.dart';

import 'pigeon/b2_keep_messages.g.dart';
import 'pigeon/c1_messages.g.dart';
import 'widgets/vod_card.dart';

/// B8 B2: Flutter version of the native mobile KeepActivity.
///
/// One grid of the shared Vod cards (image + site badge + one-line name,
/// no remarks line, no playback badge, no progress bar — the native
/// KeepAdapter binds remark/playback/progress to GONE), mirroring
/// adapter_vod.xml. All strings, computed values and grid geometry come
/// from the host (B2HostApi.getKeep) so this page renders exactly what
/// the native screen shows, in the current locale.
///
/// Complex sub-flows stay native: the delete-all confirm dialog, the sync
/// dialog, the config load on card tap (VodConfig.load) and opening a
/// card (detail/search) are all host calls. Delete mode itself is local
/// Dart state, mirroring the native adapter flag: long-press toggles it,
/// back exits it first, tapping a card in delete mode deletes that one
/// card.
///
/// Refresh: Dart cannot subscribe to the native EventBus, so this page
/// re-fetches on first load and on every resume (C1/C2 pattern), which
/// covers returning from playback and from the sync dialog. The
/// delete-all dialog is awaited (async host method), so the reload after
/// it always sees the post-dialog state; a native dialog produces no
/// lifecycle event, so resume alone would miss it.
///
/// Deliberate deviations from native (see docs/b8-b2-keep.md): no
/// marquee (single-line ellipsis instead); the info area has a fixed
/// height; images load through Image.network with host-resolved headers
/// and no disk cache; the toolbar is disabled while a card load is
/// pending.
class B2KeepPage extends StatefulWidget {
  const B2KeepPage({super.key});

  @override
  State<B2KeepPage> createState() => _B2KeepPageState();
}

class _B2KeepPageState extends State<B2KeepPage>
    with WidgetsBindingObserver {
  final B2HostApi _b2Api = B2HostApi();
  final C1HostApi _c1Api = C1HostApi();
  B2KeepScreen? _screen;
  String? _error;
  bool _deleteMode = false;

  /// Card whose config is loading right now (host openKeepItem pending).
  B2KeepItem? _loadingItem;

  /// Last failed card open (host error state), with the item to retry.
  B2LoadResult? _loadError;
  B2KeepItem? _failedItem;

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
    // Playing or syncing may have changed the list while this page was
    // covered; refresh when we come back. The load-error overlay is
    // left alone: it clears on retry or back, like the native error
    // state.
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    try {
      final B2KeepScreen screen = await _b2Api.getKeep();
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
    final B2KeepScreen? screen = _screen;
    if (screen == null) return;
    if (!_deleteMode) {
      if (screen.items.isNotEmpty) setState(() => _deleteMode = true);
      return;
    }
    try {
      await _b2Api.deleteAllKeep();
    } catch (_) {
      // Reload below shows host truth either way.
    }
    await _load();
  }

  Future<void> _onItemTap(B2KeepItem item) async {
    if (_loadingItem != null) return;
    if (_deleteMode) {
      try {
        await _b2Api.deleteKeepItem(item.cid, item.key);
      } catch (_) {
        // Reload below shows host truth either way.
      }
      await _load();
      return;
    }
    await _openItem(item);
  }

  /// Opens one card through the host. While the config loads, a modal
  /// spinner blocks input (native shows full-screen progress); on
  /// failure the host error state replaces the list, with retry.
  Future<void> _openItem(B2KeepItem item) async {
    if (_loadingItem != null) return;
    setState(() {
      _loadingItem = item;
      _loadError = null;
      _failedItem = null;
    });
    try {
      final B2LoadResult result = await _b2Api.openKeepItem(item.cid, item.key);
      if (!mounted) return;
      setState(() => _loadingItem = null);
      if (result.status == 'failed') {
        setState(() {
          _loadError = result;
          _failedItem = item;
        });
      }
      // "opened": the host started the detail/search Activity; the page
      // is covered, nothing more to do here.
    } catch (_) {
      if (!mounted) return;
      // The host always completes openKeepItem exactly once (including
      // its timeout guard), so reaching here means the channel itself
      // broke; clear the spinner and show the list again instead of
      // stranding the page.
      setState(() {
        _loadingItem = null;
        _loadError = null;
        _failedItem = null;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final B2KeepScreen? screen = _screen;
    final bool loading = _loadingItem != null;
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
              onPressed: screen == null || loading
                  ? null
                  : () => _b2Api.openKeepSync(),
            ),
            IconButton(
              icon: const Icon(Icons.delete_outline),
              tooltip: screen?.deleteTooltip,
              onPressed: screen == null || loading ? null : _onDeletePressed,
            ),
          ],
        ),
        body: Stack(
          children: [
            _error != null
                ? Center(child: Text(_error!))
                : screen == null
                    ? const Center(child: CircularProgressIndicator())
                    : _loadError != null
                        ? _LoadErrorView(
                            result: _loadError!,
                            onRetry: _failedItem == null
                                ? null
                                : () => _openItem(_failedItem!),
                          )
                        : screen.items.isEmpty
                            ? _EmptyView(screen: screen)
                            : _KeepGrid(
                                screen: screen,
                                deleteMode: _deleteMode,
                                onItemTap: _onItemTap,
                                onLongPress: () => setState(
                                  () => _deleteMode = !_deleteMode,
                                ),
                              ),
            if (loading) ...[
              const ModalBarrier(dismissible: false, color: Colors.black45),
              const Center(child: CircularProgressIndicator()),
            ],
          ],
        ),
      ),
    );
  }
}

/// Empty state: title + explanation + one primary action, mirroring the
/// native ProgressLayout empty content.
class _EmptyView extends StatelessWidget {
  const _EmptyView({required this.screen});

  final B2KeepScreen screen;

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
              onPressed: () => B2HostApi().goHome(),
              child: Text(screen.emptyActionLabel),
            ),
          ],
        ),
      ),
    );
  }
}

/// Config-load error state: title + host message + retry, mirroring the
/// native ProgressLayout error content (error_config_load / error_retry).
/// The message line is hidden when the host message is empty (the
/// no-config path, same as native error("")).
class _LoadErrorView extends StatelessWidget {
  const _LoadErrorView({required this.result, required this.onRetry});

  final B2LoadResult result;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              result.errorTitle,
              style: const TextStyle(fontSize: 18),
              textAlign: TextAlign.center,
            ),
            if (result.errorMessage.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(
                result.errorMessage,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
            ],
            const SizedBox(height: 24),
            FilledButton(
              onPressed: onRetry,
              child: Text(result.retryLabel),
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
/// margin). The row height comes from VodCard.totalHeight so the extent
/// and the card can never disagree.
class _KeepGrid extends StatelessWidget {
  const _KeepGrid({
    required this.screen,
    required this.deleteMode,
    required this.onItemTap,
    required this.onLongPress,
  });

  final B2KeepScreen screen;
  final bool deleteMode;
  final ValueChanged<B2KeepItem> onItemTap;
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
        mainAxisExtent: VodCard.totalHeight(
          imageHeight: imageHeight,
          hasProgress: false,
          twoLines: false,
        ),
      ),
      itemCount: screen.items.length,
      itemBuilder: (BuildContext context, int index) {
        final B2KeepItem item = screen.items[index];
        return VodCard(
          key: ValueKey<String>('${item.cid}:${item.key}'),
          picUrl: item.picUrl,
          picReferer: item.picReferer,
          picCookie: item.picCookie,
          picUserAgent: item.picUserAgent,
          picExtraHeadersJson: item.picExtraHeadersJson,
          fallbackPicUrl: item.fallbackPicUrl,
          placeholderText: item.placeholderText,
          placeholderColor: item.placeholderColor,
          imageHeight: imageHeight,
          vodName: item.vodName,
          // Keep binds remark to GONE: no remarks line at all.
          remarks: null,
          remarksVisible: true,
          // Keep binds site to VISIBLE always.
          siteName: item.siteName,
          siteVisible: true,
          // Keep binds playback and progress to GONE.
          playbackText: null,
          progress: null,
          deleteMode: deleteMode,
          onTap: () => onItemTap(item),
          onLongPress: onLongPress,
        );
      },
    );
  }
}
