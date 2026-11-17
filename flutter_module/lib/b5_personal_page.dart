import 'dart:async';

import 'package:material_ui/material_ui.dart';

import 'pigeon/b5_personal_messages.g.dart';
import 'widgets/settings_row.dart';

/// B8 B5: Flutter version of the mobile personal settings page
/// (SettingPersonalFragment, 24 flat rows) and the settings search
/// screen (SettingSearchFragment, the first small text-input surface),
/// as new members of the `/settings` family (plan §4.4). The family
/// shell lives in b3_settings_a_page.dart; this file only adds the two
/// member views, registered under `/settings?member=personal` and
/// `/settings?member=search`.
///
/// Row order, chevrons and icon circles mirror
/// fragment_setting_personal.xml (layout order IS the display order for
/// this page, unlike the enhance page). Every row's title, value text
/// and error flag comes from the host snapshot; Dart only switches on
/// the row key for the icon, circle color, chevron and tap handler.
/// Dialogs stay native and are opened through host calls. Dialog
/// methods are @async on the host, but Dart never awaits them: it
/// attaches a `.then` refetch, so a dialog closed without a change
/// (which completes nothing — there is nothing to refresh) leaves no
/// dangling wait. See the dialog contract in docs/b8-b3-settings-a.md
/// §2.5.
///
/// The search screen keeps its own text input in a Dart TextField (plan
/// §4.5): the host returns the whole search index once, Dart filters it
/// locally with a 300ms debounce, mirroring SettingSearchIndex.search().
/// Tapping a result pushes a migrated family member internally when
/// the host says it is available, and otherwise asks the host to open
/// the native destination (which finishes this screen first).
class PersonalSettingsView extends StatefulWidget {
  const PersonalSettingsView({super.key, required this.onBack});

  final VoidCallback onBack;

  @override
  State<PersonalSettingsView> createState() => _PersonalSettingsViewState();
}

class _PersonalSettingsViewState extends State<PersonalSettingsView>
    with _B5MemberState<PersonalSettingsView> {
  B5PersonalSettings? _info;
  String? _error;

  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  Future<void> load() async {
    try {
      final B5PersonalSettings info = await api.getPersonalSettings();
      if (!mounted) return;
      setState(() {
        _info = info;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '加载失败：$e');
    }
  }

  void _onRowTap(B5PersonalRow row) {
    switch (row.key) {
      case 'searchThread':
        afterDialog(api.openSearchThread());
      case 'following':
        act(() => api.toggleFollowing());
      case 'autoBackup':
        afterDialog(api.openAutoBackup());
      case 'playbackOverlay':
        act(() => api.togglePlaybackOverlay());
      case 'playBackToDetail':
        act(() => api.togglePlayBackToDetail());
      case 'episodeHistory':
        act(() => api.toggleEpisodeHistory());
      case 'globalHistory':
        act(() => api.cycleGlobalHistory());
      case 'interfaceFailover':
        act(() => api.cycleInterfaceFailover());
      case 'playSpeed':
        afterDialog(api.openPlaySpeed());
      case 'groupRule':
        afterDialog(api.openGroupRule());
      case 'searchUi':
        act(() => api.cycleSearchUi());
      case 'searchColumn':
        act(() => api.cycleSearchColumn());
      case 'siteColumn':
        act(() => api.cycleSiteColumn());
      case 'searchResultSort':
        act(() => api.cycleSearchResultSort());
      case 'resetApp':
        api.showResetAppDialog();
      case 'appBranding':
        api.openAppBranding();
      case 'touchOptimization':
        act(() => api.toggleTouchOptimization());
      case 'liquidGlass':
        act(() => api.toggleLiquidGlass());
      case 'dramaCollected':
        act(() => api.toggleShortDramaCollected());
      case 'glassBlur':
        afterDialog(api.openGlassBlur());
      case 'glassDistortion':
        afterDialog(api.openGlassDistortion());
      case 'glassTranslucency':
        afterDialog(api.openGlassTranslucency());
      case 'glassDispersion':
        act(() => api.toggleGlassDispersion());
      case 'glassReset':
        act(() => api.resetGlass());
    }
  }

  @override
  Widget build(BuildContext context) {
    final B5PersonalSettings? info = _info;
    if (_error != null) {
      return errorScaffold(title: '', error: _error!, onBack: widget.onBack);
    }
    if (info == null) {
      return loadingScaffold(title: '', onBack: widget.onBack);
    }
    return rowsScaffold(
      title: info.screenTitle,
      onBack: widget.onBack,
      children: [
        for (final B5PersonalRow row in info.rows)
          SettingsRow(
            icon: _iconFor(row.key),
            iconBackground: _colorFor(row.key),
            title: row.title,
            value: row.text,
            showChevron: _chevronFor(row.key),
            isError: row.hasError,
            onTap: () => _onRowTap(row),
          ),
      ],
    );
  }
}

/// Icon, circle color and chevron per row key, mirroring
/// fragment_setting_personal.xml (material icons approximate the native
/// drawables; B3 V8 same practice). Unknown keys fall back to a plain
/// settings icon with no chevron and no action.
IconData _iconFor(String key) {
  switch (key) {
    case 'searchThread':
    case 'glassBlur':
    case 'glassDistortion':
    case 'glassTranslucency':
      return Icons.format_list_numbered;
    case 'following':
    case 'episodeHistory':
    case 'globalHistory':
      return Icons.history_outlined;
    case 'autoBackup':
      return Icons.backup_outlined;
    case 'playbackOverlay':
    case 'dramaCollected':
      return Icons.play_arrow_outlined;
    case 'playBackToDetail':
    case 'glassDispersion':
      return Icons.toggle_on;
    case 'interfaceFailover':
    case 'glassReset':
      return Icons.refresh_outlined;
    case 'playSpeed':
      return Icons.speed_outlined;
    case 'groupRule':
    case 'searchColumn':
    case 'siteColumn':
    case 'searchResultSort':
      return Icons.list_outlined;
    case 'searchUi':
      return Icons.search_outlined;
    case 'resetApp':
      return Icons.delete_outline;
    case 'appBranding':
    case 'liquidGlass':
      return Icons.palette_outlined;
    case 'touchOptimization':
      return Icons.touch_app_outlined;
    default:
      return Icons.settings_outlined;
  }
}

Color _colorFor(String key) {
  switch (key) {
    case 'searchThread':
    case 'following':
    case 'episodeHistory':
    case 'globalHistory':
    case 'playSpeed':
    case 'glassBlur':
    case 'glassDistortion':
    case 'glassTranslucency':
      return Colors.orange;
    case 'autoBackup':
    case 'searchUi':
      return Colors.blue;
    case 'playbackOverlay':
    case 'dramaCollected':
    case 'resetApp':
      return Colors.red;
    case 'playBackToDetail':
    case 'interfaceFailover':
    case 'touchOptimization':
    case 'glassDispersion':
    case 'glassReset':
      return Colors.green;
    case 'groupRule':
    case 'searchColumn':
    case 'siteColumn':
    case 'searchResultSort':
      return Colors.deepPurple;
    case 'appBranding':
    case 'liquidGlass':
      return Colors.purple;
    default:
      return Colors.grey;
  }
}

bool _chevronFor(String key) {
  switch (key) {
    case 'searchThread':
    case 'playSpeed':
    case 'groupRule':
    case 'resetApp':
    case 'appBranding':
    case 'glassBlur':
    case 'glassDistortion':
    case 'glassTranslucency':
      return true;
    default:
      return false;
  }
}

/// Settings search (native SettingSearchFragment): the first small
/// text-input surface (plan §4.5). The host returns the whole search
/// index once; this view filters it locally, case-insensitively, on
/// title + subtitle after a 300ms debounce, mirroring
/// SettingSearchIndex.search() (empty query shows the hint, a
/// non-empty query with no match shows the empty text).
///
/// The input owns its state in a Dart TextField (autofocus + search
/// IME action, mirroring the native onResume focus + IME_ACTION_SEARCH
/// hide-keyboard behavior). Whether the keyboard actually appears in
/// the embedded FlutterFragment is the plan's R4 risk, recorded in
/// docs/b8-b5-personal.md §6: if it misbehaves at joint acceptance,
/// B9 stops for a decision.
class SettingSearchView extends StatefulWidget {
  const SettingSearchView({super.key, required this.onBack});

  final VoidCallback onBack;

  @override
  State<SettingSearchView> createState() => _SettingSearchViewState();
}

class _SettingSearchViewState extends State<SettingSearchView>
    with _B5MemberState<SettingSearchView> {
  B5SearchScreen? _info;
  String? _error;
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();
  Timer? _debounce;
  List<B5SearchEntry> _results = const [];
  bool _searched = false;

  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  @override
  Future<void> load() async {
    try {
      final B5SearchScreen info = await api.getSettingSearch();
      if (!mounted) return;
      setState(() {
        _info = info;
        _error = null;
      });
      _applyFilter();
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '加载失败：$e');
    }
  }

  void _onQueryChanged(String _) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), _applyFilter);
  }

  /// Mirrors SettingSearchIndex.search(): trim, lowercase, substring
  /// match on title + " " + subtitle; an empty query matches nothing.
  void _applyFilter() {
    final B5SearchScreen? info = _info;
    if (info == null || !mounted) return;
    final String needle = _controller.text.trim().toLowerCase();
    if (needle.isEmpty) {
      setState(() {
        _results = const [];
        _searched = false;
      });
      return;
    }
    final List<B5SearchEntry> results = <B5SearchEntry>[];
    for (final B5SearchEntry entry in info.entries) {
      String haystack = entry.title;
      if (entry.subtitle.isNotEmpty) haystack += ' ${entry.subtitle}';
      if (haystack.toLowerCase().contains(needle)) results.add(entry);
    }
    setState(() {
      _results = results;
      _searched = true;
    });
  }

  void _onEntryTap(B5SearchEntry entry) {
    if (entry.flutterAvailable) {
      // Family-internal navigation (plan §4.4): push the migrated
      // member onto the family Navigator; back returns to search.
      final String member = _memberFor(entry.page);
      if (member.isNotEmpty) {
        Navigator.of(context).pushNamed('/$member');
      }
      return;
    }
    // Cross-screen navigation goes through the host (plan §4.4): the
    // host finishes this screen, then opens the native destination.
    // Dart never awaits this call.
    api.openSearchEntry(entry.page, entry.rowId);
  }

  /// HomeActivity fragment index -> family member name. Only members
  /// that live inside the family Navigator are listed; the player page
  /// (2) and the subtitle page (6, a top-level C1 route) always go
  /// through the host instead.
  String _memberFor(int page) {
    switch (page) {
      case 3:
        return 'enhance';
      case 4:
        return 'danmaku';
      case 5:
        return 'personal';
      case 7:
        return 'tmdb';
      case 8:
        return 'ai';
      case 9:
        return 'ad';
      default:
        return '';
    }
  }

  @override
  Widget build(BuildContext context) {
    final B5SearchScreen? info = _info;
    if (_error != null) {
      return errorScaffold(title: '', error: _error!, onBack: widget.onBack);
    }
    if (info == null) {
      return loadingScaffold(title: '', onBack: widget.onBack);
    }
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: widget.onBack,
        ),
        title: TextField(
          controller: _controller,
          focusNode: _focusNode,
          autofocus: true,
          textInputAction: TextInputAction.search,
          decoration: InputDecoration(
            hintText: info.hintText,
            border: InputBorder.none,
          ),
          onChanged: _onQueryChanged,
          onSubmitted: (_) => _focusNode.unfocus(),
        ),
      ),
      body: !_searched
          ? Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text(
                  info.hintText,
                  style: TextStyle(color: scheme.onSurfaceVariant),
                  textAlign: TextAlign.center,
                ),
              ),
            )
          : _results.isEmpty
              ? Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Text(
                      info.emptyText,
                      style: TextStyle(color: scheme.onSurfaceVariant),
                      textAlign: TextAlign.center,
                    ),
                  ),
                )
              : ListView.builder(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                  itemCount: _results.length,
                  itemBuilder: (BuildContext context, int index) {
                    final B5SearchEntry entry = _results[index];
                    return _SearchResultTile(
                      entry: entry,
                      onTap: () => _onEntryTap(entry),
                    );
                  },
                ),
    );
  }
}

/// One search result row, mirroring item_setting_search_result.xml:
/// title (16sp) + subtitle (13sp, hidden when empty) + destination
/// (13sp, value color).
class _SearchResultTile extends StatelessWidget {
  const _SearchResultTile({required this.entry, required this.onTap});

  final B5SearchEntry entry;
  final VoidCallback onTap;

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
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      entry.title,
                      style: const TextStyle(fontSize: 16),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (entry.subtitle.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        entry.subtitle,
                        style: TextStyle(
                          color: scheme.onSurfaceVariant,
                          fontSize: 13,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Text(
                entry.dest,
                style: TextStyle(
                  color: scheme.onSurfaceVariant,
                  fontSize: 13,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Member-view helpers, copied from the B4 family member's
/// _B4MemberState (b4_enhance_page.dart). Duplicated on purpose, not
/// shared: the B4 mixin is file-private, and per-batch independence
/// (plan §4.9) means B5 must stay revertible without touching B4 code.
mixin _B5MemberState<T extends StatefulWidget> on State<T>
    with WidgetsBindingObserver {
  final B5HostApi api = B5HostApi();

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

  /// Reloads this member's snapshot. Implemented by each member view.
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

  /// Runs a synchronous host action (toggle / cycle / clear) and reloads.
  Future<void> act(Future<void> Function() call) async {
    try {
      await call();
      await load();
    } catch (_) {
      // State stays as the host reports it; load() above would have
      // failed only if the channel itself broke.
    }
  }

  Widget loadingScaffold({required String title, required VoidCallback onBack}) {
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: onBack,
        ),
        title: Text(title),
      ),
      body: const Center(child: CircularProgressIndicator()),
    );
  }

  Widget errorScaffold({
    required String title,
    required String error,
    required VoidCallback onBack,
  }) {
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: onBack,
        ),
        title: Text(title),
      ),
      body: Center(child: Text(error)),
    );
  }

  Widget rowsScaffold({
    required String title,
    required VoidCallback onBack,
    List<Widget> actions = const [],
    required List<Widget> children,
  }) {
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: onBack,
        ),
        title: Text(title),
        actions: actions,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: children,
        ),
      ),
    );
  }
}
