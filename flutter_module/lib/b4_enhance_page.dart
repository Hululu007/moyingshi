import 'dart:async';

import 'package:material_ui/material_ui.dart';

import 'pigeon/b4_enhance_messages.g.dart';
import 'widgets/settings_row.dart';

/// B8 B4: Flutter version of the mobile enhance settings page
/// (SettingEnhanceFragment), as a new member of the `/settings` family
/// (plan §4.4). The family shell lives in b3_settings_a_page.dart;
/// this file only adds the enhance member view, registered under
/// `/settings?member=enhance`.
///
/// Row order, chevrons and icon circles mirror
/// fragment_setting_enhance.xml after SettingEnhanceFragment.reorderItems()
/// (the layout XML order is NOT the display order). Every row's title,
/// value text and error flag comes from the host snapshot; Dart only
/// switches on the row key for the icon, circle color, chevron and tap
/// handler. Dialogs stay native and are opened through host calls.
/// Dialog methods are @async on the host, but Dart never awaits them: it
/// attaches a `.then` refetch, so a dialog closed without a change (which
/// completes nothing — there is nothing to refresh) leaves no dangling
/// wait. See the dialog contract in docs/b8-b3-settings-a.md §2.5.
class EnhanceSettingsView extends StatefulWidget {
  const EnhanceSettingsView({super.key, required this.onBack});

  final VoidCallback onBack;

  @override
  State<EnhanceSettingsView> createState() => _EnhanceSettingsViewState();
}

class _EnhanceSettingsViewState extends State<EnhanceSettingsView>
    with _B4MemberState<EnhanceSettingsView> {
  B4EnhanceSettings? _info;
  String? _error;

  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  Future<void> load() async {
    try {
      final B4EnhanceSettings info = await api.getEnhanceSettings();
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

  void _onRowTap(B4EnhanceRow row) {
    switch (row.key) {
      case 'lab':
        api.openLab();
      case 'labNav':
        act(() => api.toggleLabNav());
      case 'hkMiniapp':
        act(() => api.toggleHkMiniapp());
      case 'customCsp':
        afterDialog(api.openCustomCsp());
      case 'webHomeExtension':
        afterDialog(api.openWebHomeExtension());
      case 'gitCloud':
        afterDialog(api.openGitCloud());
      case 'remoteTrust':
        afterDialog(api.openRemoteTrust());
      case 'oneKeySync':
        api.openOneKeySync();
      case 'loginState':
        afterDialog(api.openLoginState());
      case 'shellProxy':
        afterDialog(api.openShellProxy());
      case 'managePage':
        api.openManagePage();
      case 'webHomeTheme':
        afterDialog(api.openWebHomeTheme());
      case 'webHomeFullscreen':
        act(() => api.toggleWebHomeFullscreen());
      case 'cspWarmup':
        afterDialog(api.openCspWarmup());
      case 'playbackArtworkWall':
        act(() => api.togglePlaybackArtworkWall());
      case 'driveCheck':
        act(() => api.toggleDriveCheck());
      case 'siteName':
        afterDialog(api.openSiteName());
      case 'localReader':
        api.openLocalReader();
      case 'novelSource':
        afterDialog(api.openNovelSource());
      case 'comicSource':
        afterDialog(api.openComicSource());
      case 'audioSource':
        afterDialog(api.openAudioSource());
      case 'shortDramaSource':
        afterDialog(api.openShortDramaSource());
      case 'siteHealthSort':
        afterDialog(api.openSiteHealthSort());
      case 'debugLog':
        act(() => api.toggleDebugLog());
      case 'searchCurrentSite':
        act(() => api.toggleSearchCurrentSite());
      case 'homeWidget':
        act(() => api.toggleHomeWidget());
      case 'playbackWebhook':
        afterDialog(api.openPlaybackWebhook());
      case 'pluginManager':
        api.openPluginManager();
    }
  }

  void _onRowLongPress(B4EnhanceRow row) {
    switch (row.key) {
      case 'siteHealthSort':
        act(() => api.clearSiteHealth());
      case 'webHomeExtension':
        act(() => api.clearWebHomeExtension());
    }
  }

  @override
  Widget build(BuildContext context) {
    final B4EnhanceSettings? info = _info;
    if (_error != null) {
      return errorScaffold(title: '', error: _error!, onBack: widget.onBack);
    }
    if (info == null) {
      return loadingScaffold(title: '', onBack: widget.onBack);
    }
    return rowsScaffold(
      title: info.screenTitle,
      onBack: widget.onBack,
      actions: [
        IconButton(
          icon: const Icon(Icons.code_outlined),
          tooltip: info.repoGithubTitle,
          onPressed: () => api.openGithubRepo(),
        ),
        IconButton(
          icon: const Icon(Icons.cloud_outlined),
          tooltip: info.repoCnbTitle,
          onPressed: () => api.openCnbRepo(),
        ),
      ],
      children: [
        for (final B4EnhanceRow row in info.rows)
          SettingsRow(
            icon: _iconFor(row.key),
            iconBackground: _colorFor(row.key),
            title: row.title,
            value: row.text,
            showChevron: _chevronFor(row.key),
            isError: row.hasError,
            onTap: () => _onRowTap(row),
            onLongPress: _hasLongPress(row.key)
                ? () => _onRowLongPress(row)
                : null,
          ),
      ],
    );
  }
}

/// Icon, circle color and chevron per row key, mirroring
/// fragment_setting_enhance.xml (material icons approximate the native
/// drawables; B3 V8 same practice). Unknown keys fall back to a plain
/// settings icon with no chevron and no action.
IconData _iconFor(String key) {
  switch (key) {
    case 'lab':
      return Icons.badge_outlined;
    case 'labNav':
      return Icons.science_outlined;
    case 'hkMiniapp':
      return Icons.grid_view_outlined;
    case 'customCsp':
      return Icons.content_paste_outlined;
    case 'webHomeExtension':
      return Icons.home_outlined;
    case 'gitCloud':
      return Icons.sync_outlined;
    case 'remoteTrust':
      return Icons.visibility_off_outlined;
    case 'oneKeySync':
      return Icons.sync_outlined;
    case 'loginState':
      return Icons.account_circle_outlined;
    case 'shellProxy':
      return Icons.dns_outlined;
    case 'managePage':
      return Icons.list_outlined;
    case 'webHomeTheme':
      return Icons.palette_outlined;
    case 'webHomeFullscreen':
      return Icons.fullscreen_outlined;
    case 'cspWarmup':
      return Icons.refresh_outlined;
    case 'playbackArtworkWall':
      return Icons.wallpaper_outlined;
    case 'driveCheck':
      return Icons.cloud_outlined;
    case 'siteName':
      return Icons.ondemand_video_outlined;
    case 'localReader':
      return Icons.folder_outlined;
    case 'novelSource':
    case 'comicSource':
    case 'audioSource':
    case 'shortDramaSource':
      return Icons.list_outlined;
    case 'siteHealthSort':
      return Icons.health_and_safety_outlined;
    case 'debugLog':
      return Icons.content_paste_outlined;
    case 'searchCurrentSite':
      return Icons.search_outlined;
    case 'homeWidget':
      return Icons.home_outlined;
    case 'playbackWebhook':
      return Icons.sync_outlined;
    case 'pluginManager':
      return Icons.folder_outlined;
    default:
      return Icons.settings_outlined;
  }
}

Color _colorFor(String key) {
  switch (key) {
    case 'lab':
    case 'managePage':
    case 'novelSource':
    case 'comicSource':
    case 'audioSource':
    case 'shortDramaSource':
    case 'playbackArtworkWall':
      return Colors.deepPurple;
    case 'labNav':
    case 'remoteTrust':
    case 'webHomeTheme':
      return Colors.purple;
    case 'hkMiniapp':
    case 'customCsp':
    case 'shellProxy':
    case 'webHomeFullscreen':
    case 'cspWarmup':
    case 'siteHealthSort':
    case 'debugLog':
      return Colors.green;
    case 'webHomeExtension':
    case 'localReader':
    case 'homeWidget':
    case 'pluginManager':
      return Colors.orange;
    case 'gitCloud':
    case 'oneKeySync':
    case 'loginState':
    case 'driveCheck':
    case 'siteName':
    case 'searchCurrentSite':
    case 'playbackWebhook':
      return Colors.blue;
    default:
      return Colors.grey;
  }
}

bool _chevronFor(String key) {
  switch (key) {
    case 'labNav':
    case 'hkMiniapp':
    case 'webHomeFullscreen':
    case 'playbackArtworkWall':
    case 'driveCheck':
    case 'searchCurrentSite':
    case 'homeWidget':
      return false;
    default:
      return true;
  }
}

bool _hasLongPress(String key) {
  return key == 'siteHealthSort' || key == 'webHomeExtension';
}

/// Member-view helpers, copied from the B3 family shell's
/// _B3MemberState (b3_settings_a_page.dart). Duplicated on purpose, not
/// shared: the B3 mixin is file-private, and per-batch independence
/// (plan §4.9) means B4 must stay revertible without touching B3 code.
mixin _B4MemberState<T extends StatefulWidget> on State<T>
    with WidgetsBindingObserver {
  final B4HostApi api = B4HostApi();

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
