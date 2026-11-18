import 'dart:async';
import 'dart:ui' show AppExitResponse, ViewFocusEvent;

import 'package:flutter/services.dart' show PredictiveBackEvent;
import 'package:material_ui/material_ui.dart';

import 'b4_enhance_page.dart';
import 'b5_personal_page.dart';
import 'b6_player_page.dart';
import 'b7_settings_home_page.dart';
import 'pigeon/b3_settings_a_messages.g.dart';
import 'widgets/settings_row.dart';

/// B8 B3: Flutter version of the four mobile sub-settings pages in the
/// settings family (TMDB / AI / ad / danmaku), hosted on the shared
/// `/settings` family route with an in-family Navigator (plan §4.4).
///
/// One route string per member (`/settings?member=home|tmdb|ai|ad|danmaku`,
/// default member `home` when the query is absent); the family home page
/// has landed in B7 (see b7_settings_home_page.dart) and is the Navigator's initial page — new members
/// only add one route entry and one member view, never touching the
/// existing members. Cross-family navigation always goes through the
/// host (closeScreen here); family-internal navigation is pure Dart.
///
/// Every row's title, value text and visibility comes from the host
/// snapshot; Dart never hard-codes locale strings. Dialogs stay native
/// and are opened through host calls. Dialog methods are @async on the
/// host, but Dart never awaits them: it attaches a `.then` refetch, so
/// a dialog closed without a change (which completes nothing — there is
/// nothing to refresh) leaves no dangling wait. See the dialog contract
/// in docs/b8-b3-settings-a.md.
class B3SettingsPage extends StatefulWidget {
  const B3SettingsPage({super.key, required this.member});

  /// Family member to show first: 'home' | 'tmdb' | 'ai' | 'ad' |
  /// 'danmaku' | 'enhance' | 'personal' | 'search' | 'player'.
  final String member;

  @override
  State<B3SettingsPage> createState() => _B3SettingsPageState();
}

class _B3SettingsPageState extends State<B3SettingsPage> {
  // B8 B4: the enhance page joins the family the same way; only this
  // list entry and the '/enhance' case below are added, the B3 entries
  // are untouched.
  // B8 B5: the personal page and the settings search join the family
  // the same way; only these list entries and the '/personal' /
  // '/search' cases below are added, the B3/B4 entries are untouched.
  // B8 B7: the settings home joins the family the same way; only this
  // list entry and the '/home' case below are added, the B3/B4/B5
  // entries are untouched. It is first so '/settings' without a member
  // opens it (main.dart maps the bare route to member 'home').
  // B8 B6: the player settings page joins the family the same way;
  // only this list entry and the '/player' case below are added, the
  // B3/B4/B5/B7 entries are untouched.
  static const List<String> _members = <String>[
    'home',
    'tmdb',
    'ai',
    'ad',
    'danmaku',
    'enhance',
    'personal',
    'search',
    'player',
  ];

  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();
  final B3HostApi _api = B3HostApi();

  /// Member currently showing in the inner Navigator, tracked by
  /// [_MemberObserver] (plain assignment, never setState — it is only
  /// read by [_handleBack], never by build). Starts at the entry
  /// member so the direct-entry case below is correct before the
  /// first observer callback.
  late String _currentMember;

  @override
  void initState() {
    super.initState();
    _currentMember =
        _members.contains(widget.member) ? widget.member : 'tmdb';
  }

  void _handleBack() {
    final NavigatorState? navigator = _navigatorKey.currentState;
    if (navigator != null && navigator.canPop()) {
      navigator.pop();
      return;
    }
    if (_currentMember != 'home') {
      // Direct entry: the host asserted /settings?member=X, so the
      // inner Navigator starts at depth 1 on the member and there is
      // nothing to pop. Replace the stack with the settings home
      // (native: sub-page -> settings home); replace, not push, so
      // the next back does not return to the member. Only the home
      // member falls through to the host root-back handler.
      navigator?.pushNamedAndRemoveUntil('/home', (_) => false);
      return;
    }
    _api.closeScreen();
  }

  @override
  Widget build(BuildContext context) {
    final String initial =
        _members.contains(widget.member) ? '/${widget.member}' : '/tmdb';
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, Object? result) {
        if (didPop) return;
        _handleBack();
      },
      child: Navigator(
        key: _navigatorKey,
        initialRoute: initial,
        observers: <NavigatorObserver>[
          _MemberObserver((String member) => _currentMember = member),
        ],
        onGenerateRoute: (RouteSettings settings) {
          switch (settings.name) {
            case '/home':
              return MaterialPageRoute(
                builder: (_) => SettingsHomeView(onBack: _handleBack),
              );
            case '/tmdb':
              return MaterialPageRoute(
                builder: (_) => _TmdbSettingsView(onBack: _handleBack),
              );
            case '/ai':
              return MaterialPageRoute(
                builder: (_) => _AiSettingsView(onBack: _handleBack),
              );
            case '/ad':
              return MaterialPageRoute(
                builder: (_) => _AdSettingsView(onBack: _handleBack),
              );
            case '/danmaku':
              return MaterialPageRoute(
                builder: (_) => _DanmakuSettingsView(onBack: _handleBack),
              );
            case '/enhance':
              return MaterialPageRoute(
                builder: (_) => EnhanceSettingsView(onBack: _handleBack),
              );
            case '/personal':
              return MaterialPageRoute(
                builder: (_) => PersonalSettingsView(onBack: _handleBack),
              );
            case '/search':
              return MaterialPageRoute(
                builder: (_) => SettingSearchView(onBack: _handleBack),
              );
            case '/player':
              return MaterialPageRoute(
                builder: (_) => PlayerSettingsView(onBack: _handleBack),
              );
            default:
              return MaterialPageRoute(
                builder: (_) => _TmdbSettingsView(onBack: _handleBack),
              );
          }
        },
      ),
    );
  }
}

/// Reports the inner Navigator's top member to the family shell so
/// [_B3SettingsPageState._handleBack] can tell a depth-1 member from
/// the home member (direct-entry case). Only push/pop/replace are
/// tracked: removals (pushNamedAndRemoveUntil) always accompany a
/// push, whose report is the final one.
class _MemberObserver extends NavigatorObserver {
  _MemberObserver(this.onMemberChanged);

  final ValueChanged<String> onMemberChanged;

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) {
    super.didPush(route, previousRoute);
    _report(route);
  }

  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) {
    super.didPop(route, previousRoute);
    if (previousRoute != null) _report(previousRoute);
  }

  @override
  void didReplace({Route<dynamic>? newRoute, Route<dynamic>? oldRoute}) {
    super.didReplace(newRoute: newRoute, oldRoute: oldRoute);
    if (newRoute != null) _report(newRoute);
  }

  void _report(Route<dynamic> route) {
    final String? name = route.settings.name;
    if (name != null && name.startsWith('/')) {
      onMemberChanged(name.substring(1));
    }
  }
}

/// Shared helpers for the four member views: resumed refetch, dialog
/// refetch without awaiting, and sync action + refetch.
mixin _B3MemberState<T extends StatefulWidget> on State<T>
    implements WidgetsBindingObserver {
  final B3HostApi api = B3HostApi();

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
    required List<Widget> children,
  }) {
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: onBack,
        ),
        title: Text(title),
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

/// TMDB settings (native SettingTmdbFragment). Row order, chevrons and
/// icons mirror fragment_setting_tmdb.xml.
class _TmdbSettingsView extends StatefulWidget {
  const _TmdbSettingsView({required this.onBack});

  final VoidCallback onBack;

  @override
  State<_TmdbSettingsView> createState() => _TmdbSettingsViewState();
}

class _TmdbSettingsViewState extends State<_TmdbSettingsView>
    with _B3MemberState<_TmdbSettingsView> {
  B3TmdbSettings? _info;
  String? _error;

  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  Future<void> load() async {
    try {
      final B3TmdbSettings info = await api.getTmdbSettings();
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

  @override
  Widget build(BuildContext context) {
    final B3TmdbSettings? info = _info;
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
        SettingsRow(
          icon: Icons.movie_outlined,
          iconBackground: Colors.blue,
          title: info.sourceTitle,
          value: info.sourceText,
          showChevron: true,
          onTap: () => afterDialog(api.openTmdbSource()),
        ),
        SettingsRow(
          icon: Icons.list_outlined,
          iconBackground: Colors.deepPurple,
          title: info.openModeTitle,
          value: info.openModeText,
          showChevron: true,
          onTap: () => afterDialog(api.openDetailOpenMode()),
        ),
        if (info.themeModeVisible)
          SettingsRow(
            icon: Icons.palette_outlined,
            iconBackground: Colors.purple,
            title: info.themeModeTitle,
            value: info.themeModeText,
            showChevron: true,
            onTap: () => afterDialog(api.openDetailThemeMode()),
          ),
        SettingsRow(
          icon: Icons.list_outlined,
          iconBackground: Colors.deepPurple,
          title: info.matchModeTitle,
          value: info.matchModeText,
          showChevron: false,
          onTap: () => act(() => api.cycleTmdbMatchMode()),
        ),
        SettingsRow(
          icon: Icons.toggle_on,
          iconBackground: Colors.green,
          title: info.episodeFileSizeTitle,
          value: info.episodeFileSizeText,
          showChevron: false,
          onTap: () => act(() => api.toggleTmdbEpisodeFileSize()),
        ),
        if (info.historyAggregationVisible)
          SettingsRow(
            icon: Icons.toggle_on,
            iconBackground: Colors.green,
            title: info.historyAggregationTitle,
            value: info.historyAggregationText,
            showChevron: false,
            onTap: () => act(() => api.toggleHistoryAggregation()),
          ),
      ],
    );
  }
}

/// AI settings (native SettingAiFragment). Row order, chevrons and icons
/// mirror fragment_setting_ai.xml. The realtime-model row downloads a
/// model in the background after picking; while the snapshot says the
/// model is downloading or being deleted, this view polls the snapshot
/// so the progress text stays live (plan §4.6 polling, same mechanism
/// as aggregate-search progress).
class _AiSettingsView extends StatefulWidget {
  const _AiSettingsView({required this.onBack});

  final VoidCallback onBack;

  @override
  State<_AiSettingsView> createState() => _AiSettingsViewState();
}

class _AiSettingsViewState extends State<_AiSettingsView>
    with _B3MemberState<_AiSettingsView> {
  B3AiSettings? _info;
  String? _error;
  Timer? _pollTimer;

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
      final B3AiSettings info = await api.getAiSettings();
      if (!mounted) return;
      setState(() {
        _info = info;
        _error = null;
      });
      _maybePoll(info);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '加载失败：$e');
    }
  }

  void _maybePoll(B3AiSettings info) {
    final bool busy =
        info.realtimeModelDownloading || info.realtimeModelDeleting;
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

  @override
  Widget build(BuildContext context) {
    final B3AiSettings? info = _info;
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
        SettingsGroupHeader(title: info.recommendationGroupTitle),
        SettingsRow(
          icon: Icons.psychology_outlined,
          iconBackground: Colors.purple,
          title: info.recommendationTitle,
          value: info.recommendationText,
          showChevron: true,
          onTap: () => afterDialog(api.openAiConfig()),
        ),
        SettingsRow(
          icon: Icons.person_outline,
          iconBackground: Colors.purple,
          title: info.personalTitle,
          value: info.personalText,
          showChevron: true,
          onTap: () => afterDialog(api.togglePersonalRecommendation()),
        ),
        SettingsRow(
          icon: Icons.edit_outlined,
          iconBackground: Colors.purple,
          title: info.feedbackTitle,
          value: info.feedbackText,
          showChevron: true,
          onTap: () => afterDialog(api.openRecommendationFeedback()),
        ),
        SettingsGroupHeader(title: info.subtitleGroupTitle),
        if (info.subtitleAiSettingsVisible) ...[
          SettingsRow(
            icon: Icons.subtitles_outlined,
            iconBackground: Colors.blue,
            title: info.realtimeModelTitle,
            value: info.realtimeModelText,
            showChevron: true,
            onTap: () => afterDialog(api.openRealtimeModel()),
          ),
          SettingsRow(
            icon: Icons.format_list_numbered,
            iconBackground: Colors.orange,
            title: info.maxConcurrencyTitle,
            value: info.maxConcurrencyValue.toString(),
            showChevron: true,
            onTap: () => afterDialog(api.setSubtitleAiMaxConcurrency()),
          ),
          SettingsRow(
            icon: Icons.format_list_numbered,
            iconBackground: Colors.orange,
            title: info.chunkCountTitle,
            value: info.chunkCountValue.toString(),
            showChevron: true,
            onTap: () => afterDialog(api.setSubtitleAiChunkCount()),
          ),
        ],
      ],
    );
  }
}

/// Ad settings (native SettingAdFragment). Row order, chevrons and icons
/// mirror fragment_setting_ad.xml. Rows whose summary failed on the host
/// carry the host error text plus isError and render in red.
class _AdSettingsView extends StatefulWidget {
  const _AdSettingsView({required this.onBack});

  final VoidCallback onBack;

  @override
  State<_AdSettingsView> createState() => _AdSettingsViewState();
}

class _AdSettingsViewState extends State<_AdSettingsView>
    with _B3MemberState<_AdSettingsView> {
  B3AdSettings? _info;
  String? _error;

  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  Future<void> load() async {
    try {
      final B3AdSettings info = await api.getAdSettings();
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

  @override
  Widget build(BuildContext context) {
    final B3AdSettings? info = _info;
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
        SettingsGroupHeader(title: info.basicGroupTitle),
        SettingsRow(
          icon: Icons.block_outlined,
          iconBackground: Colors.orange,
          title: info.adblockTitle,
          value: info.adblockText,
          showChevron: false,
          isError: info.adblockError,
          onTap: () => act(() => api.toggleAdblock()),
        ),
        if (info.aiAdDetectionVisible)
          SettingsRow(
            icon: Icons.psychology_outlined,
            iconBackground: Colors.purple,
            title: info.aiAdDetectionTitle,
            value: info.aiAdDetectionText,
            showChevron: false,
            isError: info.aiAdDetectionError,
            onTap: () => act(() => api.toggleAiAdDetection()),
          ),
        SettingsGroupHeader(title: info.ruleGroupTitle),
        SettingsRow(
          icon: Icons.list_outlined,
          iconBackground: Colors.deepPurple,
          title: info.ruleManageTitle,
          value: info.ruleManageText,
          showChevron: true,
          isError: info.ruleManageError,
          onTap: () => afterDialog(api.openAdRuleManage()),
        ),
        SettingsRow(
          icon: Icons.bar_chart_outlined,
          iconBackground: Colors.orange,
          title: info.blockStatsTitle,
          value: '',
          showChevron: true,
          onTap: () => api.openAdBlockStats(),
        ),
        SettingsGroupHeader(title: info.segmentGroupTitle),
        SettingsRow(
          icon: Icons.toggle_on,
          iconBackground: Colors.green,
          title: info.autoSkipTitle,
          value: info.autoSkipText,
          showChevron: false,
          isError: info.autoSkipError,
          onTap: () => act(() => api.cycleAutoSkipIntroOutro()),
        ),
        SettingsRow(
          icon: Icons.list_outlined,
          iconBackground: Colors.deepPurple,
          title: info.introSkipKindsTitle,
          value: info.introSkipKindsText,
          showChevron: true,
          isError: info.introSkipKindsError,
          onTap: () => afterDialog(api.openIntroSkipKinds()),
        ),
      ],
    );
  }
}

/// Danmaku settings (native SettingDanmakuFragment). Row order, chevrons
/// and icons mirror fragment_setting_danmaku.xml. The load -> api ->
/// auto -> spider visibility chain is computed by the host; this view
/// only renders the visible flags.
class _DanmakuSettingsView extends StatefulWidget {
  const _DanmakuSettingsView({required this.onBack});

  final VoidCallback onBack;

  @override
  State<_DanmakuSettingsView> createState() => _DanmakuSettingsViewState();
}

class _DanmakuSettingsViewState extends State<_DanmakuSettingsView>
    with _B3MemberState<_DanmakuSettingsView> {
  B3DanmakuSettings? _info;
  String? _error;

  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  Future<void> load() async {
    try {
      final B3DanmakuSettings info = await api.getDanmakuSettings();
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

  @override
  Widget build(BuildContext context) {
    final B3DanmakuSettings? info = _info;
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
        SettingsRow(
          icon: Icons.comment_outlined,
          iconBackground: Colors.blue,
          title: info.loadTitle,
          value: info.loadText,
          showChevron: false,
          onTap: () => act(() => api.toggleDanmakuLoad()),
        ),
        if (info.apiVisible)
          SettingsRow(
            icon: Icons.list_outlined,
            iconBackground: Colors.deepPurple,
            title: info.apiTitle,
            value: info.apiText,
            showChevron: true,
            onTap: () => afterDialog(api.openDanmakuApi()),
          ),
        if (info.autoVisible)
          SettingsRow(
            icon: Icons.toggle_on,
            iconBackground: Colors.green,
            title: info.autoTitle,
            value: info.autoText,
            showChevron: false,
            onTap: () => act(() => api.toggleDanmakuAuto()),
          ),
        if (info.spiderVisible)
          SettingsRow(
            icon: Icons.comment_outlined,
            iconBackground: Colors.blue,
            title: info.spiderTitle,
            value: info.spiderText,
            showChevron: false,
            onTap: () => act(() => api.toggleDanmakuSpider()),
          ),
      ],
    );
  }
}
