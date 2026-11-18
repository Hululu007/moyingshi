import 'dart:async';
import 'dart:ui' show AppExitResponse, ViewFocusEvent;

import 'package:flutter/services.dart' show PredictiveBackEvent;
import 'package:material_ui/material_ui.dart';

import 'pigeon/b7_settings_home_messages.g.dart';
import 'widgets/settings_row.dart';

/// B8 B7: Flutter version of the mobile settings home (native
/// SettingFragment), the `/settings` family home page. It closes the
/// in-family navigation loop: member rows go through the family
/// Navigator, unmigrated pages and native function rows go through the
/// host (see docs/b8-b7-settings-home.md).
///
/// Every row's title, subtitle, value and visibility comes from the host
/// snapshot; Dart never hard-codes locale strings. Icons and circle
/// colors are Dart-side presentation choices keyed by row key (B3 V8
/// pattern). Dialogs stay native and are opened through host calls.
/// Dialog methods are @async on the host, but Dart never awaits them: it
/// attaches a `.then` refetch, so a dialog closed without a change
/// (which completes nothing — there is nothing to refresh) leaves no
/// dangling wait (B3 V1 contract).
///
/// Polling: while the snapshot says a download is active or the cache
/// text is still being computed, this view re-fetches every 500ms
/// (B3 V2 pattern). After a cache action the cleanup runs in the
/// background with no completion callback, so Dart additionally polls
/// the cache text for up to 15s until it changes (see §2.7 of the task
/// doc).
class SettingsHomeView extends StatefulWidget {
  const SettingsHomeView({super.key, required this.onBack});

  final VoidCallback onBack;

  @override
  State<SettingsHomeView> createState() => _SettingsHomeViewState();
}

class _SettingsHomeViewState extends State<SettingsHomeView>
    with _B7MemberState<SettingsHomeView> {
  B7SettingsHome? _info;
  String? _error;
  Timer? _pollTimer;
  bool _pendingRowHandled = false;
  final Set<String> _expandedDramas = <String>{};
  final Set<String> _deleteShownDramas = <String>{};

  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  @override
  Future<void> load() async {
    try {
      final B7SettingsHome info = await api.getSettingsHome();
      if (!mounted) return;
      setState(() {
        _info = info;
        _error = null;
      });
      _maybePoll(info);
      _maybeHandlePendingRow();
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '加载失败：$e');
    }
  }

  void _maybePoll(B7SettingsHome info) {
    final bool busy = info.downloadsActive || info.cacheScanning;
    if (busy && _pollTimer == null) {
      _pollTimer = Timer.periodic(
        const Duration(milliseconds: 500),
        (_) => load(),
      );
    } else if (!busy) {
      _pollTimer?.cancel();
      _pollTimer = null;
    }
  }

  /// Settings-search deep link: run the same handler as tapping the row
  /// (mirrors SettingFragment.consumePendingRow + performRow), once.
  void _maybeHandlePendingRow() {
    final B7SettingsHome? info = _info;
    if (info == null || _pendingRowHandled) return;
    _pendingRowHandled = true;
    final String rowId = info.pendingRowId;
    if (rowId.isEmpty) return;
    final B7SettingsRow? row = _findRow(rowId);
    if (row == null) return;
    _onRowTap(row);
  }

  B7SettingsRow? _findRow(String key) {
    final B7SettingsHome? info = _info;
    if (info == null) return null;
    for (final B7SettingsGroup group in info.groups) {
      for (final B7SettingsRow row in group.rows) {
        if (row.key == key) return row;
      }
    }
    return null;
  }

  String _cacheValue() {
    return _findRow('cache')?.value ?? '';
  }

  /// After a cache action, the cleanup runs in the background with no
  /// completion callback (native updates via RefreshEvent.CACHE, which
  /// Flutter cannot hear): re-read the snapshot every second for up to
  /// 15s, stopping as soon as the cache text changes.
  void _cacheAction(Future<void> call) {
    final String before = _cacheValue();
    afterDialog(call);
    _pollCacheUntilChanged(before);
  }

  Future<void> _pollCacheUntilChanged(String before) async {
    for (int i = 0; i < 15; i++) {
      await Future.delayed(const Duration(seconds: 1));
      if (!mounted) return;
      await load();
      if (_cacheValue() != before) return;
    }
  }

  void _openSearch() {
    Navigator.of(context).pushNamed('/search');
  }

  void _onRowTap(B7SettingsRow row) {
    if (row.member.isNotEmpty) {
      Navigator.of(context).pushNamed('/${row.member}');
      return;
    }
    switch (row.key) {
      case 'vod':
        afterDialog(api.openVodManage());
      case 'live':
        afterDialog(api.openLiveManage());
      case 'wall':
        afterDialog(api.openWallManage());
      case 'appearance':
        afterDialog(api.openAppearance());
      case 'subtitle':
        api.openSubtitle();
      case 'local_source':
        afterDialog(api.openLocalSource());
      case 'lab':
        api.openLab();
      case 'gitcloud':
        afterDialog(api.openGitCloud());
      case 'onekey':
        api.openOneKeySync();
      case 'drive':
        act(() => api.toggleDriveCheck());
      case 'site_health':
        afterDialog(api.openSiteHealth());
      case 'cache':
        _cacheAction(api.openCacheClear());
      case 'backup':
        afterDialog(api.openBackup());
      case 'restore':
        afterDialog(api.openRestore());
      case 'doh':
        afterDialog(api.openDoh());
      case 'incognito':
        act(() => api.toggleIncognito());
      case 'about':
        api.openAbout();
      case 'download':
        act(() => api.toggleDownloadEnabled());
    }
  }

  void _onRowLongPress(B7SettingsRow row) {
    switch (row.key) {
      case 'site_health':
        act(() => api.clearSiteHealth());
      case 'cache':
        _cacheAction(api.openCacheManage());
    }
  }

  void _onConfigHome(B7SettingsRow row) {
    switch (row.key) {
      case 'vod':
        afterDialog(api.openVodHome());
      case 'live':
        afterDialog(api.openLiveHome());
      case 'wall':
        act(() => api.setWallDefault());
    }
  }

  void _onConfigHistory(B7SettingsRow row) {
    switch (row.key) {
      case 'vod':
        afterDialog(api.openVodHistory());
      case 'live':
        afterDialog(api.openLiveHistory());
      case 'wall':
        afterDialog(api.setWallRefresh());
    }
  }

  void _onConfigHistoryLongPress(B7SettingsRow row) {
    if (row.key == 'wall') afterDialog(api.openWallHistory());
  }

  void _deleteDrama(String dramaName) {
    afterDialog(api.deleteDrama(dramaName).then((_) {
      if (mounted) {
        setState(() {
          _deleteShownDramas.remove(dramaName);
          _expandedDramas.remove(dramaName);
        });
      }
    }));
  }

  @override
  Widget build(BuildContext context) {
    final B7SettingsHome? info = _info;
    if (_error != null) {
      return Scaffold(
        appBar: AppBar(title: const Text('')),
        body: Center(child: Text(_error!)),
      );
    }
    if (info == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('')),
        body: const Center(child: CircularProgressIndicator()),
      );
    }
    return Scaffold(
      appBar: AppBar(
        // The home is the family root: no leading back button (native
        // has none either); the system back closes the screen.
        automaticallyImplyLeading: false,
        title: Text(info.screenTitle),
        actions: [
          IconButton(
            icon: const Icon(Icons.search),
            onPressed: _openSearch,
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final B7SettingsGroup group in info.groups) ...[
              SettingsGroupHeader(title: group.title),
              for (final B7SettingsRow row in group.rows) _buildRow(row),
              // The download list lives inside the hidden card in
              // native; here it follows the hidden group's rows.
              if (group.rows.any((B7SettingsRow r) => r.key == 'download'))
                _buildDownloadList(info),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildRow(B7SettingsRow row) {
    final (IconData, Color) icon = _rowIcon(row.key);
    if (row.isConfig) {
      return _ConfigRow(
        icon: icon.$1,
        iconBackground: icon.$2,
        title: row.title,
        value: row.value,
        onTap: () => _onRowTap(row),
        onHome: () => _onConfigHome(row),
        onHistory: () => _onConfigHistory(row),
        onHistoryLongPress:
            row.key == 'wall' ? () => _onConfigHistoryLongPress(row) : null,
      );
    }
    return _HomeRow(
      icon: icon.$1,
      iconBackground: icon.$2,
      title: row.title,
      subtitle: row.subtitle,
      value: row.value,
      showChevron: true,
      isError: row.hasError,
      onTap: () => _onRowTap(row),
      onLongPress: row.key == 'site_health' || row.key == 'cache'
          ? () => _onRowLongPress(row)
          : null,
    );
  }

  Widget _buildDownloadList(B7SettingsHome info) {
    if (!info.hiddenVisible || !info.downloadEnabled) {
      return const SizedBox.shrink();
    }
    if (info.downloadGroups.isEmpty) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(80, 12, 16, 12),
        child: Text(
          info.downloadEmptyText,
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
            fontSize: 13,
          ),
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final B7DownloadDrama drama in info.downloadGroups)
          _DownloadDramaCard(
            drama: drama,
            deleteLabel: info.downloadDeleteLabel,
            expanded: _expandedDramas.contains(drama.dramaName),
            deleteShown: _deleteShownDramas.contains(drama.dramaName),
            onTap: () {
              setState(() {
                _deleteShownDramas.remove(drama.dramaName);
                if (_expandedDramas.contains(drama.dramaName)) {
                  _expandedDramas.remove(drama.dramaName);
                } else {
                  _expandedDramas.add(drama.dramaName);
                }
              });
            },
            onLongPress: () {
              setState(() {
                _deleteShownDramas.add(drama.dramaName);
              });
            },
            onDelete: () => _deleteDrama(drama.dramaName),
            onEpisodeTap: (B7DownloadEpisode episode) =>
                act(() => api.episodeAction(episode.id)),
          ),
      ],
    );
  }
}

/// Shared helpers for this member view: resumed refetch, dialog refetch
/// without awaiting, and sync action + refetch. A copy of the B3
/// `_B3MemberState` mixin (per-batch copy, not shared, so batches stay
/// independently revertible); the loading/error scaffolds are local
/// here because the home has no leading back button.
mixin _B7MemberState<T extends StatefulWidget> on State<T>
    implements WidgetsBindingObserver {
  final B7HostApi api = B7HostApi();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // A native dialog may have changed settings while covering this
    // page; refresh when we come back.
    if (state == AppLifecycleState.resumed) load();
  }

  // Remaining WidgetsBindingObserver handlers. This mixin only reacts to
  // lifecycle changes; the rest are empty so the mixin satisfies the
  // interface. Signatures copied from Flutter 3.50.0
  // packages/flutter/lib/src/widgets/binding.dart (the interface has 17
  // methods; didChangeAppLifecycleState above is the 17th).
  @override
  Future<bool> didPopRoute() => Future<bool>.value(false);

  @override
  bool handleStartBackGesture(PredictiveBackEvent backEvent) => false;

  @override
  void handleUpdateBackGestureProgress(PredictiveBackEvent backEvent) {}

  @override
  void handleCommitBackGesture() {}

  @override
  void handleCancelBackGesture() {}

  @override
  void handleStatusBarTap() {}

  @override
  Future<bool> didPushRoute(String route) => Future<bool>.value(false);

  @override
  Future<bool> didPushRouteInformation(RouteInformation routeInformation) =>
      Future<bool>.value(false);

  @override
  void didChangeMetrics() {}

  @override
  void didChangeTextScaleFactor() {}

  @override
  void didChangePlatformBrightness() {}

  @override
  void didChangeLocales(List<Locale>? locales) {}

  @override
  void didChangeViewFocus(ViewFocusEvent event) {}

  @override
  Future<AppExitResponse> didRequestAppExit() =>
      Future<AppExitResponse>.value(AppExitResponse.exit);

  @override
  void didHaveMemoryPressure() {}

  @override
  void didChangeAccessibilityFeatures() {}

  /// Reloads this member's snapshot. Implemented by the member view.
  Future<void> load();

  /// Runs a dialog host call without awaiting it: when the host reports
  /// a change (the Future completes), reload; a dialog closed without a
  /// change completes nothing and needs no reload.
  void afterDialog(Future<void> call) {
    call.then((_) {
      if (mounted) load();
    }, onError: (_) {
      if (mounted) load();
    });
  }

  /// Runs a synchronous host action (toggle / cycle) and reloads.
  Future<void> act(Future<void> Function() call) async {
    try {
      await call();
      await load();
    } catch (_) {
      // State stays as the host reports it; load() above would have
      // failed only if the channel itself broke.
    }
  }
}

/// Icon + circle color per row key. Dart-side presentation choices
/// approximating the native drawables in SettingFragment.buildGroups
/// (B3 V8 pattern); all titles and values come from the host.
(IconData, Color) _rowIcon(String key) {
  switch (key) {
    case 'vod':
      return (Icons.live_tv_outlined, Colors.blue);
    case 'live':
      return (Icons.live_tv_outlined, Colors.red);
    case 'wall':
      return (Icons.wallpaper_outlined, Colors.deepPurple);
    case 'appearance':
      return (Icons.palette_outlined, Colors.purple);
    case 'widget':
      return (Icons.tune_outlined, Colors.orange);
    case 'player':
      return (Icons.play_circle_outlined, Colors.red);
    case 'danmaku':
      return (Icons.comment_outlined, Colors.blue);
    case 'subtitle':
      return (Icons.subtitles_outlined, Colors.green);
    case 'ai':
      return (Icons.psychology_outlined, Colors.purple);
    case 'ad':
      return (Icons.block_outlined, Colors.red);
    case 'account':
      return (Icons.account_circle_outlined, Colors.blue);
    case 'personal':
      return (Icons.person_outline, Colors.green);
    case 'local_source':
      return (Icons.folder_outlined, Colors.blue);
    case 'lab':
      return (Icons.science_outlined, Colors.orange);
    case 'gitcloud':
      return (Icons.cloud_upload_outlined, Colors.purple);
    case 'onekey':
      return (Icons.sync_outlined, Colors.green);
    case 'drive':
      return (Icons.cloud_done_outlined, Colors.blue);
    case 'site_health':
      return (Icons.health_and_safety_outlined, Colors.green);
    case 'cache':
      return (Icons.delete_outlined, Colors.orange);
    case 'backup':
      return (Icons.backup_outlined, Colors.blue);
    case 'restore':
      return (Icons.restore_outlined, Colors.green);
    case 'doh':
      return (Icons.dns_outlined, Colors.deepPurple);
    case 'incognito':
      return (Icons.visibility_off_outlined, Colors.blue);
    case 'about':
      return (Icons.info_outlined, Colors.blue);
    case 'download':
      return (Icons.download_outlined, Colors.blue);
    default:
      return (Icons.settings_outlined, Colors.blue);
  }
}

/// A normal home row: icon circle + bold title + subtitle + value +
/// chevron, mirroring item_setting_card_row.xml. Local to this batch
/// (the shared SettingsRow has no subtitle slot, and the task scope
/// keeps shared components untouched); same card shape as the shared
/// row so the family pages stay visually consistent.
class _HomeRow extends StatelessWidget {
  const _HomeRow({
    required this.icon,
    required this.iconBackground,
    required this.title,
    required this.subtitle,
    required this.value,
    this.showChevron = true,
    this.isError = false,
    required this.onTap,
    this.onLongPress,
  });

  final IconData icon;
  final Color iconBackground;
  final String title;
  final String subtitle;
  final String value;
  final bool showChevron;
  final bool isError;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.only(top: 8),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
      ),
      elevation: 0,
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        borderRadius: BorderRadius.circular(16),
        child: Container(
          constraints: const BoxConstraints(minHeight: 72),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: Row(
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  color: iconBackground,
                  shape: BoxShape.circle,
                ),
                child: Icon(icon, color: Colors.white, size: 24),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                          fontSize: 16, fontWeight: FontWeight.bold),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (subtitle.isNotEmpty)
                      Text(
                        subtitle,
                        style: TextStyle(
                          color: scheme.onSurfaceVariant,
                          fontSize: 14,
                        ),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                  ],
                ),
              ),
              if (value.isNotEmpty) ...[
                const SizedBox(width: 8),
                Flexible(
                  child: Text(
                    value,
                    style: TextStyle(
                      color: isError ? Colors.red : scheme.onSurfaceVariant,
                      fontSize: 14,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.end,
                  ),
                ),
              ],
              if (showChevron) ...[
                const SizedBox(width: 8),
                Icon(
                  Icons.chevron_right,
                  color: scheme.onSurfaceVariant,
                  size: 20,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// A config row (vod / live / wall): title + value + a home button and
/// a history button, no chevron — mirroring
/// item_setting_card_row_config.xml. The wall history button also takes
/// a long-press (wall history dialog); the other two do not.
class _ConfigRow extends StatelessWidget {
  const _ConfigRow({
    required this.icon,
    required this.iconBackground,
    required this.title,
    required this.value,
    required this.onTap,
    required this.onHome,
    required this.onHistory,
    this.onHistoryLongPress,
  });

  final IconData icon;
  final Color iconBackground;
  final String title;
  final String value;
  final VoidCallback onTap;
  final VoidCallback onHome;
  final VoidCallback onHistory;
  final VoidCallback? onHistoryLongPress;

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.only(top: 8),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
      ),
      elevation: 0,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Container(
          constraints: const BoxConstraints(minHeight: 72),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: Row(
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  color: iconBackground,
                  shape: BoxShape.circle,
                ),
                child: Icon(icon, color: Colors.white, size: 24),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                          fontSize: 16, fontWeight: FontWeight.bold),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (value.isNotEmpty)
                      Text(
                        value,
                        style: TextStyle(
                          color: scheme.onSurfaceVariant,
                          fontSize: 13,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              _ConfigButton(
                icon: Icons.home_outlined,
                onTap: onHome,
              ),
              const SizedBox(width: 8),
              _ConfigButton(
                icon: Icons.history_outlined,
                onTap: onHistory,
                onLongPress: onHistoryLongPress,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// One square config button (home / history), mirroring the shape_item
/// background of the native config-row buttons.
class _ConfigButton extends StatelessWidget {
  const _ConfigButton({
    required this.icon,
    required this.onTap,
    this.onLongPress,
  });

  final IconData icon;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        borderRadius: BorderRadius.circular(8),
        child: SizedBox(
          width: 40,
          height: 40,
          child: Icon(icon, size: 20),
        ),
      ),
    );
  }
}

/// One download drama group: folder icon + name + summary + an outlined
/// delete button (shown after a long-press), with the episode rows
/// below when expanded. Mirrors item_download_drama.xml; the
/// expanded / delete-shown state is Dart-local, like the native
/// mExpandedDownloadDramas / mDeleteShownDownloadDramas sets.
class _DownloadDramaCard extends StatelessWidget {
  const _DownloadDramaCard({
    required this.drama,
    required this.deleteLabel,
    required this.expanded,
    required this.deleteShown,
    required this.onTap,
    required this.onLongPress,
    required this.onDelete,
    required this.onEpisodeTap,
  });

  final B7DownloadDrama drama;
  final String deleteLabel;
  final bool expanded;
  final bool deleteShown;
  final VoidCallback onTap;
  final VoidCallback onLongPress;
  final VoidCallback onDelete;
  final void Function(B7DownloadEpisode episode) onEpisodeTap;

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.only(top: 8),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
      ),
      elevation: 0,
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        borderRadius: BorderRadius.circular(16),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Container(
                    width: 48,
                    height: 48,
                    decoration: const BoxDecoration(
                      color: Colors.blue,
                      shape: BoxShape.circle,
                    ),
                    child: const Icon(Icons.folder_outlined,
                        color: Colors.white, size: 24),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          drama.dramaName,
                          style: const TextStyle(
                              fontSize: 16, fontWeight: FontWeight.bold),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        Text(
                          drama.summary,
                          style: TextStyle(
                            color: scheme.onSurfaceVariant,
                            fontSize: 14,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ),
                  ),
                  if (deleteShown) ...[
                    const SizedBox(width: 8),
                    OutlinedButton(
                      onPressed: onDelete,
                      style: OutlinedButton.styleFrom(
                        foregroundColor: scheme.error,
                        side: BorderSide(color: scheme.error),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(18),
                        ),
                        minimumSize: const Size(0, 36),
                      ),
                      child: Text(deleteLabel),
                    ),
                  ],
                ],
              ),
              if (expanded)
                for (final B7DownloadEpisode episode in drama.episodes)
                  _DownloadEpisodeRow(
                    episode: episode,
                    onTap: () => onEpisodeTap(episode),
                  ),
            ],
          ),
        ),
      ),
    );
  }
}

/// One download episode row: name + status, an error line when the
/// task failed, and a progress bar per the host's flags. Mirrors
/// item_download_episode.xml.
class _DownloadEpisodeRow extends StatelessWidget {
  const _DownloadEpisodeRow({
    required this.episode,
    required this.onTap,
  });

  final B7DownloadEpisode episode;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(64, 10, 0, 10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    episode.name,
                    style: const TextStyle(fontSize: 15),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 8),
                Text(
                  episode.statusText,
                  style: TextStyle(
                    color: scheme.onSurfaceVariant,
                    fontSize: 13,
                  ),
                ),
              ],
            ),
            if (episode.error.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(
                  episode.error,
                  style: TextStyle(
                    color: scheme.error,
                    fontSize: 12,
                  ),
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            if (episode.showProgress)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: episode.indeterminate
                    ? const LinearProgressIndicator()
                    : LinearProgressIndicator(
                        value: episode.progress.clamp(0, 100) / 100,
                      ),
              ),
          ],
        ),
      ),
    );
  }
}
