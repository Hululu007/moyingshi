import 'package:material_ui/material_ui.dart';

import 'pigeon/b16_report_messages.g.dart';
import 'pigeon/c1_messages.g.dart';

/// B8 B16: the mobile viewing-report page (ViewingReportActivity), as
/// the '/report' route on the shared C1 engine. The report is generated
/// and AI-analyzed 100% host-side; Dart only renders the snapshot the
/// host returns. Range selection happens in-page (a dialog mirroring
/// the native ViewingReportRangeDialog); the entry points pass the
/// initially selected range as a '?range=' query. See
/// docs/b8-b16-report.md.
class B16ReportPage extends StatefulWidget {
  const B16ReportPage({
    super.key,
    required this.initialRange,
  });

  /// ViewingReportRange name from the route query; unknown names fall
  /// back to "ALL" (validated against the host range list).
  final String initialRange;

  @override
  State<B16ReportPage> createState() => _B16ReportPageState();
}

class _B16ReportPageState extends State<B16ReportPage>
    with WidgetsBindingObserver {
  final B16HostApi _api = B16HostApi();
  final C1HostApi _c1Api = C1HostApi();
  B16ReportShell? _shell;
  B16ReportScreen? _screen;
  String _range = 'ALL';
  String? _error;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _range = widget.initialRange;
    _loadShell();
    _load(false);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The native page loads once in initView; the resumed refetch
    // (plan §4.6) also covers coming back after the history changed
    // elsewhere. The host cache makes the refetch cheap when nothing
    // changed (24h cache, history-fingerprint invalidation).
    if (state == AppLifecycleState.resumed) _load(false);
  }

  Future<void> _loadShell() async {
    try {
      final B16ReportShell shell = await _api.getReportShell();
      if (!mounted) return;
      setState(() {
        _shell = shell;
        // Validate the route range against the host list; unknown
        // names fall back to ALL. The host does the same validation,
        // so this only affects the selector before the first snapshot.
        if (!shell.ranges.any((B16RangeItem r) => r.name == _range)) {
          _range = 'ALL';
        }
      });
    } catch (_) {
      // The shell stays null; the AppBar title stays empty and the
      // range selector stays hidden until the report snapshot arrives.
    }
  }

  Future<void> _load(bool forceRefresh) async {
    if (_loading) return;
    setState(() => _loading = true);
    try {
      final B16ReportScreen screen =
          await _api.getReport(_range, forceRefresh);
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _range = screen.rangeName;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      // Channel breakage only: the host timeout path completes with an
      // error screen instead (B8 precedent). Same prefix as B2.
      setState(() => _error = '加载失败：$e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _pickRange() async {
    final B16ReportShell? shell = _shell;
    if (shell == null) return;
    String selected = _range;
    final String? chosen = await showDialog<String>(
      context: context,
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter setDialogState) {
            return AlertDialog(
              title: Text(shell.selectRangeTitle),
              content: SizedBox(
                width: double.maxFinite,
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: shell.ranges.length,
                  itemBuilder: (BuildContext context, int index) {
                    final B16RangeItem item = shell.ranges[index];
                    return RadioListTile<String>(
                      title: Text(item.displayLabel),
                      value: item.name,
                      groupValue: selected,
                      onChanged: (String? value) {
                        if (value != null) {
                          setDialogState(() => selected = value);
                        }
                      },
                    );
                  },
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: Text(shell.cancelLabel),
                ),
                FilledButton(
                  onPressed: () => Navigator.of(context).pop(selected),
                  child: Text(shell.generateLabel),
                ),
              ],
            );
          },
        );
      },
    );
    if (chosen != null && chosen != _range) {
      setState(() => _range = chosen);
      await _load(false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final B16ReportShell? shell = _shell;
    final B16ReportScreen? screen = _screen;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, _) {
        if (!didPop) _c1Api.closeScreen();
      },
      child: Scaffold(
        appBar: AppBar(
          leading: IconButton(
            icon: const Icon(Icons.arrow_back),
            onPressed: () => _c1Api.closeScreen(),
          ),
          title: Text(shell?.title ?? ''),
          actions: [
            IconButton(
              icon: const Icon(Icons.refresh),
              tooltip: shell?.refreshLabel,
              // Native onRefresh disables the button while loading.
              onPressed: _loading ? null : () => _load(true),
            ),
          ],
        ),
        body: _buildBody(shell, screen),
      ),
    );
  }

  Widget _buildBody(B16ReportShell? shell, B16ReportScreen? screen) {
    if (_error != null) {
      return _CenterMessage(
        title: _error!,
        actionLabel: shell?.retryLabel ?? '重试',
        onAction: () => _load(false),
      );
    }
    if (screen != null && screen.errorText.isNotEmpty) {
      return _CenterMessage(
        title: screen.errorText,
        actionLabel: shell?.retryLabel ?? '重试',
        onAction: () => _load(false),
      );
    }
    if (_loading && screen == null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const CircularProgressIndicator(),
            const SizedBox(height: 12),
            Text(shell?.loadingLabel ?? ''),
          ],
        ),
      );
    }
    if (screen == null) {
      return const Center(child: CircularProgressIndicator());
    }
    // Content and empty state share the pull-to-refresh scroll view;
    // native has no pull-to-refresh, but plan §4.6 requires one and
    // the header refresh button is the native equivalent.
    return RefreshIndicator(
      onRefresh: () => _load(true),
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 24, 16, 32),
        children: [
          _buildRangeRow(shell, screen),
          const SizedBox(height: 16),
          if (_loading) ...[
            const LinearProgressIndicator(),
            const SizedBox(height: 16),
          ],
          if (screen.isEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 80),
              child: Text(
                shell?.emptyText ?? '',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurface,
                ),
              ),
            )
          else ...[
            _buildOverviewCard(screen),
            if (screen.aiVisible) ...[
              const SizedBox(height: 12),
              _buildAiCard(screen),
            ],
            const SizedBox(height: 12),
            _buildTextCard(screen.timeTitle, screen.timeContent),
            if (screen.genreVisible) ...[
              const SizedBox(height: 12),
              _buildTextCard(screen.genreTitle, screen.genreContent),
            ],
            if (screen.peopleVisible) ...[
              const SizedBox(height: 12),
              _buildTextCard(screen.peopleTitle, screen.peopleContent),
            ],
            if (screen.badgesVisible) ...[
              const SizedBox(height: 12),
              _buildTextCard(screen.badgesTitle, screen.badgeContent),
            ],
          ],
        ],
      ),
    );
  }

  Widget _buildRangeRow(B16ReportShell? shell, B16ReportScreen screen) {
    return Row(
      children: [
        Expanded(
          child: Text(
            screen.rangeLabel,
            style: const TextStyle(fontSize: 13),
          ),
        ),
        OutlinedButton.icon(
          onPressed: shell == null ? null : _pickRange,
          icon: const Icon(Icons.calendar_month, size: 18),
          label: Text(shell?.selectRangeTitle ?? ''),
        ),
      ],
    );
  }

  Widget _buildOverviewCard(B16ReportScreen screen) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    return _ReportCard(
      child: Column(
        children: [
          Text(
            screen.overviewLabel,
            style: const TextStyle(fontSize: 13),
          ),
          const SizedBox(height: 4),
          Text(
            screen.overviewHours,
            style: TextStyle(
              fontSize: 34,
              fontWeight: FontWeight.bold,
              color: colors.primary,
            ),
          ),
          const SizedBox(height: 12),
          Text(
            screen.overviewSub,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 14),
          ),
        ],
      ),
    );
  }

  Widget _buildAiCard(B16ReportScreen screen) {
    return _ReportCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            screen.aiTitle,
            style: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 10),
          Text(
            screen.aiSummary,
            style: const TextStyle(fontSize: 14, height: 1.3),
          ),
          if (screen.aiTags.isNotEmpty) ...[
            const SizedBox(height: 12),
            Wrap(
              spacing: 4,
              runSpacing: 4,
              children: screen.aiTags
                  .map((String tag) => Chip(label: Text(tag)))
                  .toList(),
            ),
          ],
          if (screen.aiInsights.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text(
              screen.aiInsights,
              style: const TextStyle(fontSize: 13, height: 1.4),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildTextCard(String title, String content) {
    return _ReportCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 10),
          Text(
            content,
            style: const TextStyle(fontSize: 14, height: 1.5),
          ),
        ],
      ),
    );
  }
}

/// Native cards are MaterialCardView "Filled" style (surface-container
/// fill, no shadow); a plain Dart Card is elevated, so the report
/// cards pin the filled look explicitly.
class _ReportCard extends StatelessWidget {
  const _ReportCard({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Card(
      elevation: 0,
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
      ),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: child,
      ),
    );
  }
}

class _CenterMessage extends StatelessWidget {
  const _CenterMessage({
    required this.title,
    this.description,
    required this.actionLabel,
    required this.onAction,
  });

  final String title;
  final String? description;
  final String actionLabel;
  final VoidCallback onAction;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              title,
              style: const TextStyle(fontSize: 18),
              textAlign: TextAlign.center,
            ),
            if (description != null && description!.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(
                description!,
                textAlign: TextAlign.center,
              ),
            ],
            const SizedBox(height: 16),
            FilledButton(
              onPressed: onAction,
              child: Text(actionLabel),
            ),
          ],
        ),
      ),
    );
  }
}
