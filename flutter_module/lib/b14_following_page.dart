import 'package:material_ui/material_ui.dart';

import 'pigeon/b14_following_messages.g.dart';
import 'pigeon/c1_messages.g.dart';
import 'widgets/pic_image.dart';

/// B8 B14: Flutter version of the native mobile FollowingActivity.
///
/// One list of following cards (86x120 image + title + official /
/// metadata / source / progress lines + "新 N 集" badge + error line +
/// one horizontal row of action buttons), mirroring
/// item_following.xml and FollowingAdapter.onBindViewHolder. All strings,
/// computed values and filter/sort order come from the host
/// (B14HostApi.getFollowing) so this page renders exactly what the
/// native screen shows, in the current locale.
///
/// Complex sub-flows stay native: the row-tap detail dialog (5 actions),
/// the unfollow confirm dialog and the notify permission request are all
/// host calls; the server-import form is Dart text input (B5/B9
/// precedent) with the fetch/import itself host-side.
///
/// Refresh: Dart cannot subscribe to the native EventBus, so this page
/// re-fetches on first load, on every resume (C1/C2 pattern) and on
/// pull-to-refresh, which covers returning from playback, from the
/// search page (change source) and from the detail dialog actions. The
/// @async dialog calls complete when their action settles, so the reload
/// after them always sees the post-action state; a native dialog
/// produces no lifecycle event, so resume alone would miss it.
///
/// Deliberate deviations from native (see docs/b8-b14-following.md):
/// no global wallpaper under the page (plain background); the "新 N 集"
/// badge sits next to the title instead of overlaid; the button row uses
/// FilledButton.tonal instead of the native 38dp tonal MaterialButtons;
/// mark-read has no optimistic local update (the list refreshes after
/// the bridge write lands); the B11 home bottom-bar following item still
/// opens the native page (B11 frozen).
class B14FollowingPage extends StatefulWidget {
  const B14FollowingPage({super.key});

  @override
  State<B14FollowingPage> createState() => _B14FollowingPageState();
}

class _B14FollowingPageState extends State<B14FollowingPage>
    with WidgetsBindingObserver {
  final B14HostApi _b14Api = B14HostApi();
  final C1HostApi _c1Api = C1HostApi();
  final ScrollController _scrollController = ScrollController();
  B14FollowingScreen? _screen;
  String? _error;
  int _filterIndex = 0;
  bool _checkingAll = false;
  bool _importBusy = false;

  /// "$identityKey:$action" for row actions currently in flight; the
  /// matching button is disabled until the host call settles.
  final Set<String> _busyActions = <String>{};

  /// Per-row keys for scrolling to a newly followed season.
  final Map<String, GlobalKey> _rowKeys = <String, GlobalKey>{};

  /// Identity key to scroll to after the next load (followNextSeason).
  String? _pendingFocusKey;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _scrollController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Playing, searching or checking may have changed the list while
    // this page was covered; refresh when we come back.
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    try {
      final B14FollowingScreen screen =
          await _b14Api.getFollowing(_filterIndex);
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _error = null;
        // Drop keys for rows that are gone, so a stale key can never
        // scroll to a recycled row.
        _rowKeys.removeWhere(
          (String key, _) =>
              !screen.items.any((B14FollowingItem item) =>
                  item.identityKey == key),
        );
      });
      final String? focusKey = _pendingFocusKey;
      if (focusKey != null) {
        _pendingFocusKey = null;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          final BuildContext? target = _rowKeys[focusKey]?.currentContext;
          if (target != null && mounted) {
            Scrollable.ensureVisible(
              target,
              duration: const Duration(milliseconds: 300),
            );
          }
        });
      }
    } catch (e) {
      if (!mounted) return;
      // Channel breakage only: the host timeout path completes with an
      // error screen instead (B8 precedent). Same prefix as B2.
      setState(() => _error = '加载失败：$e');
    }
  }

  Future<void> _onBack() async {
    await _c1Api.closeScreen();
  }

  void _showMessage(String message) {
    if (message.isEmpty || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  Future<void> _cycleFilter() async {
    // Native FILTER_COUNT is 5 (all / updates / unwatched / ended /
    // failed); the host clamps out-of-range values anyway.
    _filterIndex = (_filterIndex + 1) % 5;
    await _load();
  }

  Future<void> _checkAll() async {
    if (_checkingAll) return;
    setState(() => _checkingAll = true);
    try {
      await _b14Api.checkAll();
    } catch (_) {
      // Reload below shows host truth either way.
    }
    if (!mounted) return;
    setState(() => _checkingAll = false);
    await _load();
  }

  Future<void> _markAllRead() async {
    try {
      await _b14Api.markAllRead();
    } catch (_) {
      // Reload below shows host truth either way.
    }
    await _load();
  }

  bool _isBusy(B14FollowingItem item, String action) =>
      _busyActions.contains('${item.identityKey}:$action');

  /// Runs one row action with its button disabled until the host call
  /// settles; double taps cannot start the same action twice.
  Future<void> _runRowAction(
    B14FollowingItem item,
    String action,
    Future<void> Function() run,
  ) async {
    final String key = '${item.identityKey}:$action';
    if (_busyActions.contains(key)) return;
    setState(() => _busyActions.add(key));
    try {
      await run();
    } catch (_) {
      // Each action reloads below, showing host truth either way.
    } finally {
      if (mounted) setState(() => _busyActions.remove(key));
    }
  }

  Future<void> _onRowTap(B14FollowingItem item) async {
    await _runRowAction(item, 'detail', () async {
      await _b14Api.openDetail(item.identityKey);
      await _load();
    });
  }

  Future<void> _onContinue(B14FollowingItem item) async {
    await _runRowAction(item, 'continue', () async {
      // Fire-and-forget: the host starts the player/detail Activity;
      // the resumed refetch covers the return.
      await _b14Api.continuePlay(item.identityKey);
    });
  }

  Future<void> _onCheck(B14FollowingItem item) async {
    await _runRowAction(item, 'check', () async {
      await _b14Api.checkItem(item.identityKey);
      await _load();
    });
  }

  Future<void> _onRead(B14FollowingItem item) async {
    await _runRowAction(item, 'read', () async {
      await _b14Api.markRead(item.identityKey);
      await _load();
    });
  }

  Future<void> _onToggleNotify(B14FollowingItem item) async {
    await _runRowAction(item, 'notify', () async {
      final B14ActionResult result =
          await _b14Api.toggleNotify(item.identityKey);
      _showMessage(result.message);
      await _load();
    });
  }

  Future<void> _onNextSeason(B14FollowingItem item) async {
    await _runRowAction(item, 'nextSeason', () async {
      final B14ActionResult result =
          await _b14Api.followNextSeason(item.identityKey);
      if (result.newIdentityKey.isNotEmpty) {
        _pendingFocusKey = result.newIdentityKey;
      }
      await _load();
    });
  }

  Future<void> _onChangeSource(B14FollowingItem item) async {
    await _runRowAction(item, 'changeSource', () async {
      // Fire-and-forget: the host opens the search page; the resumed
      // refetch covers the return.
      await _b14Api.changeSource(item.identityKey);
    });
  }

  Future<void> _onDelete(B14FollowingItem item) async {
    await _runRowAction(item, 'delete', () async {
      await _b14Api.deleteItem(item.identityKey);
      await _load();
    });
  }

  Future<void> _showImportDialog() async {
    final B14FollowingScreen? screen = _screen;
    if (screen == null || _importBusy) return;
    final TextEditingController urlController = TextEditingController();
    final TextEditingController tokenController = TextEditingController();
    final String? choice = await showDialog<String>(
      context: context,
      builder: (BuildContext context) => AlertDialog(
        title: Text(screen.importTitle),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: urlController,
              decoration: InputDecoration(hintText: screen.importUrlHint),
              keyboardType: TextInputType.url,
              textInputAction: TextInputAction.next,
            ),
            const SizedBox(height: 12),
            TextField(
              controller: tokenController,
              decoration: InputDecoration(hintText: screen.importTokenHint),
              obscureText: true,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop('cancel'),
            child: Text(screen.cancelLabel),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop('preview'),
            child: Text(screen.importPreviewLabel),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop('import'),
            child: Text(screen.importActionLabel),
          ),
        ],
      ),
    );
    final String url = urlController.text;
    final String token = tokenController.text;
    urlController.dispose();
    tokenController.dispose();
    if (choice == 'preview') {
      await _fetchPreview(url, token);
    } else if (choice == 'import') {
      await _importDirect(url, token);
    }
  }

  Future<void> _fetchPreview(String url, String token) async {
    setState(() => _importBusy = true);
    try {
      final B14ImportPreview preview =
          await _b14Api.fetchServerCandidates(url, token);
      if (!mounted) return;
      setState(() => _importBusy = false);
      if (!preview.ok) {
        _showMessage(preview.message);
        return;
      }
      final bool? importIt = await showDialog<bool>(
        context: context,
        builder: (BuildContext context) => AlertDialog(
          title: Text(preview.title),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (final String line in preview.lines) Text(line),
                if (preview.moreCount > 0)
                  Text('… +${preview.moreCount}'),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(_screen?.cancelLabel ?? ''),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: Text(preview.importLabel),
            ),
          ],
        ),
      );
      if (importIt == true) await _doImport();
    } catch (_) {
      if (!mounted) return;
      setState(() => _importBusy = false);
    }
  }

  Future<void> _importDirect(String url, String token) async {
    setState(() => _importBusy = true);
    try {
      final B14ImportPreview preview =
          await _b14Api.fetchServerCandidates(url, token);
      if (!mounted) return;
      if (!preview.ok) {
        setState(() => _importBusy = false);
        _showMessage(preview.message);
        return;
      }
      await _doImport();
    } catch (_) {
      if (!mounted) return;
      setState(() => _importBusy = false);
    }
  }

  Future<void> _doImport() async {
    setState(() => _importBusy = true);
    try {
      final B14ActionResult result =
          await _b14Api.importServerCandidates();
      if (!mounted) return;
      setState(() => _importBusy = false);
      _showMessage(result.message);
      await _load();
    } catch (_) {
      if (!mounted) return;
      setState(() => _importBusy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final B14FollowingScreen? screen = _screen;
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
        ),
        body: Stack(
          children: [
            if (_error != null)
              _ChannelErrorView(
                message: _error!,
                retryLabel: screen?.retryLabel ?? '',
                onRetry: _load,
              )
            else if (screen == null)
              const Center(child: CircularProgressIndicator())
            else if (screen.errorText.isNotEmpty)
              _ErrorView(screen: screen, onRetry: _load)
            else
              Column(
                children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: Text(screen.summary),
                    ),
                  ),
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    child: Row(
                      children: [
                        TextButton(
                          onPressed: _cycleFilter,
                          child: Text(screen.filterLabel),
                        ),
                        TextButton(
                          onPressed: _checkingAll ? null : _checkAll,
                          child: Text(screen.checkAllLabel),
                        ),
                        TextButton(
                          onPressed: _markAllRead,
                          child: Text(screen.readAllLabel),
                        ),
                        TextButton(
                          onPressed:
                              _importBusy ? null : _showImportDialog,
                          child: Text(screen.serverImportLabel),
                        ),
                      ],
                    ),
                  ),
                  Expanded(
                    child: RefreshIndicator(
                      onRefresh: _load,
                      child: screen.items.isEmpty
                          ? _EmptyList(screen: screen)
                          : ListView.builder(
                              controller: _scrollController,
                              physics:
                                  const AlwaysScrollableScrollPhysics(),
                              padding: const EdgeInsets.only(top: 8),
                              itemCount: screen.items.length,
                              itemBuilder:
                                  (BuildContext context, int index) {
                                final B14FollowingItem item =
                                    screen.items[index];
                                return _FollowingCard(
                                  key: _rowKeys.putIfAbsent(
                                    item.identityKey,
                                    () => GlobalKey(),
                                  ),
                                  item: item,
                                  continueLabel: screen.continueLabel,
                                  checkLabel: screen.checkLabel,
                                  readLabel: screen.readLabel,
                                  changeSourceLabel:
                                      screen.changeSourceLabel,
                                  deleteLabel: screen.deleteLabel,
                                  onTap: () => _onRowTap(item),
                                  onContinue: () => _onContinue(item),
                                  onCheck: () => _onCheck(item),
                                  onRead: () => _onRead(item),
                                  onToggleNotify: () =>
                                      _onToggleNotify(item),
                                  onNextSeason: () =>
                                      _onNextSeason(item),
                                  onChangeSource: () =>
                                      _onChangeSource(item),
                                  onDelete: () => _onDelete(item),
                                  isBusy: (String action) =>
                                      _isBusy(item, action),
                                );
                              },
                            ),
                    ),
                  ),
                ],
              ),
            if (_importBusy) ...[
              const ModalBarrier(dismissible: false, color: Colors.black45),
              const Center(child: CircularProgressIndicator()),
            ],
          ],
        ),
      ),
    );
  }
}

/// Channel-breakage error state (the host timeout path completes with
/// an error screen instead, so this is only reached when the channel
/// itself breaks). Same bare shape as B2.
class _ChannelErrorView extends StatelessWidget {
  const _ChannelErrorView({
    required this.message,
    required this.retryLabel,
    required this.onRetry,
  });

  final String message;
  final String retryLabel;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(message, textAlign: TextAlign.center),
            if (retryLabel.isNotEmpty) ...[
              const SizedBox(height: 24),
              FilledButton(
                onPressed: onRetry,
                child: Text(retryLabel),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Host error state (load timeout): host text + retry, B8 errorScreen
/// precedent.
class _ErrorView extends StatelessWidget {
  const _ErrorView({required this.screen, required this.onRetry});

  final B14FollowingScreen screen;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              screen.errorText,
              style: const TextStyle(fontSize: 18),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: onRetry,
              child: Text(screen.retryLabel),
            ),
          ],
        ),
      ),
    );
  }
}

/// Empty state that still scrolls, so pull-to-refresh works with no
/// rows. The icon is an approximation: native shows ic_home_following.
class _EmptyList extends StatelessWidget {
  const _EmptyList({required this.screen});

  final B14FollowingScreen screen;

  @override
  Widget build(BuildContext context) {
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      children: [
        SizedBox(
          height: MediaQuery.of(context).size.height * 0.6,
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.rss_feed,
                    size: 48,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    screen.emptyLabel,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                    textAlign: TextAlign.center,
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// One following card: 86x120 image + text column + one horizontal row
/// of action buttons. Spacing mirrors item_following.xml: 16dp card
/// padding, 14dp bottom margin.
class _FollowingCard extends StatelessWidget {
  const _FollowingCard({
    super.key,
    required this.item,
    required this.continueLabel,
    required this.checkLabel,
    required this.readLabel,
    required this.changeSourceLabel,
    required this.deleteLabel,
    required this.onTap,
    required this.onContinue,
    required this.onCheck,
    required this.onRead,
    required this.onToggleNotify,
    required this.onNextSeason,
    required this.onChangeSource,
    required this.onDelete,
    required this.isBusy,
  });

  final B14FollowingItem item;

  /// The five row action labels live on the screen snapshot, not on
  /// the item; the parent passes them down.
  final String continueLabel;
  final String checkLabel;
  final String readLabel;
  final String changeSourceLabel;
  final String deleteLabel;
  final VoidCallback onTap;
  final VoidCallback onContinue;
  final VoidCallback onCheck;
  final VoidCallback onRead;
  final VoidCallback onToggleNotify;
  final VoidCallback onNextSeason;
  final VoidCallback onChangeSource;
  final VoidCallback onDelete;
  final bool Function(String action) isBusy;

  @override
  Widget build(BuildContext context) {
    final TextStyle secondary = TextStyle(
      fontSize: 13,
      color: Theme.of(context).colorScheme.onSurfaceVariant,
    );
    return Card(
      margin: const EdgeInsets.only(left: 16, right: 16, bottom: 14),
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 86,
                    height: 120,
                    child: PicImage(
                      picUrl: item.picUrl,
                      picReferer: item.picReferer,
                      picCookie: item.picCookie,
                      picUserAgent: item.picUserAgent,
                      picExtraHeadersJson: item.picExtraHeadersJson,
                      fallbackPicUrl: item.fallbackPicUrl,
                      placeholderText: item.placeholderText,
                      placeholderColor: item.placeholderColor,
                      height: 120,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                item.title,
                                style: const TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.bold,
                                ),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                            if (item.badgeVisible) ...[
                              const SizedBox(width: 8),
                              Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 8,
                                  vertical: 2,
                                ),
                                decoration: BoxDecoration(
                                  color: Theme.of(context)
                                      .colorScheme
                                      .primaryContainer,
                                  borderRadius: BorderRadius.circular(10),
                                ),
                                child: Text(
                                  item.badgeText,
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: Theme.of(context)
                                        .colorScheme
                                        .onPrimaryContainer,
                                  ),
                                ),
                              ),
                            ],
                          ],
                        ),
                        const SizedBox(height: 4),
                        Text(item.officialLine, style: secondary),
                        if (item.metadataVisible) ...[
                          const SizedBox(height: 2),
                          Text(item.metadataLine, style: secondary),
                        ],
                        const SizedBox(height: 2),
                        Text(item.sourceLine, style: secondary),
                        const SizedBox(height: 2),
                        Text(item.progressLine, style: secondary),
                        if (item.errorVisible) ...[
                          const SizedBox(height: 2),
                          Text(
                            item.errorText,
                            style: TextStyle(
                              fontSize: 13,
                              color: Theme.of(context).colorScheme.error,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  children: [
                    if (item.nextSeasonVisible)
                      _actionButton(
                        item.nextSeasonLabel,
                        isBusy('nextSeason'),
                        onNextSeason,
                      ),
                    _actionButton(
                      continueLabel,
                      isBusy('continue'),
                      onContinue,
                    ),
                    _actionButton(
                      checkLabel,
                      isBusy('check'),
                      onCheck,
                    ),
                    if (item.readVisible)
                      _actionButton(
                        readLabel,
                        isBusy('read'),
                        onRead,
                      ),
                    _actionButton(
                      item.notifyLabel,
                      isBusy('notify'),
                      onToggleNotify,
                    ),
                    _actionButton(
                      changeSourceLabel,
                      isBusy('changeSource'),
                      onChangeSource,
                    ),
                    _actionButton(
                      deleteLabel,
                      isBusy('delete'),
                      onDelete,
                    ),
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

/// Per-row tonal action button, approximating the native 38dp tonal
/// MaterialButtons in the card's HorizontalScrollView.
Widget _actionButton(String label, bool busy, VoidCallback onPressed) {
  return Padding(
    padding: const EdgeInsets.only(right: 8),
    child: FilledButton.tonal(
      onPressed: busy ? null : onPressed,
      style: FilledButton.styleFrom(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        minimumSize: const Size(0, 38),
        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
      ),
      child: Text(label),
    ),
  );
}
