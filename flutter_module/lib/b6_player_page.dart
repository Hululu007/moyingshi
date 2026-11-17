import 'package:material_ui/material_ui.dart';

import 'pigeon/b6_player_messages.g.dart';
import 'widgets/settings_row.dart';

/// B8 B6: Flutter version of the mobile player settings page
/// (SettingPlayerFragment), hosted as a member of the `/settings`
/// family (plan §4.4). Row order mirrors fragment_setting_player.xml;
/// only the 23 rows the native page leaves visible are rendered — the
/// 16 performance rows hidden by hidePerformanceRows() are managed
/// through the exo4kCompat performance dialog instead.
///
/// Every row's title, value text and visibility comes from the host
/// snapshot; Dart never hard-codes locale strings. Dialogs stay native
/// and are opened through host calls. Dialog methods are @async on the
/// host, but Dart never awaits them: it attaches a `.then` refetch, so
/// a dialog closed without a change (which completes nothing — there is
/// nothing to refresh) leaves no dangling wait. See the dialog contract
/// in docs/b8-b6-player.md.
class PlayerSettingsView extends StatefulWidget {
  const PlayerSettingsView({super.key, required this.onBack});

  final VoidCallback onBack;

  @override
  State<PlayerSettingsView> createState() => _PlayerSettingsViewState();
}

class _PlayerSettingsViewState extends State<PlayerSettingsView>
    with _B6MemberState<PlayerSettingsView> {
  B6PlayerSettings? _info;
  String? _error;

  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  Future<void> load() async {
    try {
      final B6PlayerSettings info = await api.getPlayerSettings();
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
    final B6PlayerSettings? info = _info;
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
          icon: Icons.smart_button_outlined,
          iconBackground: Colors.blue,
          title: info.playerButtonsTitle,
          value: info.playerButtonsText,
          showChevron: true,
          onTap: () => afterDialog(api.openPlayerButtons()),
        ),
        if (info.padLiveVisible)
          SettingsRow(
            icon: Icons.tablet_outlined,
            iconBackground: Colors.purple,
            title: info.padLiveTitle,
            value: info.padLiveText,
            showChevron: false,
            onTap: () => act(() => api.cyclePadLiveMode()),
          ),
        SettingsRow(
          icon: Icons.memory_outlined,
          iconBackground: Colors.orange,
          title: info.exo4kCompatTitle,
          value: info.exo4kCompatText,
          showChevron: true,
          onTap: () => afterDialog(api.openExo4kCompat()),
        ),
        SettingsRow(
          icon: Icons.developer_board_outlined,
          iconBackground: Colors.deepPurple,
          title: info.kernelTitle,
          value: info.kernelText,
          showChevron: true,
          onTap: () => afterDialog(api.openKernel()),
        ),
        if (info.mpvVisible) ...[
          SettingsRow(
            icon: Icons.description_outlined,
            iconBackground: Colors.blue,
            title: info.mpvConfigTitle,
            value: info.mpvConfigText,
            showChevron: true,
            onTap: () => afterDialog(api.openMpvConfig()),
          ),
          SettingsRow(
            icon: Icons.layers_outlined,
            iconBackground: Colors.teal,
            title: info.mpvRenderTitle,
            value: info.mpvRenderText,
            showChevron: true,
            onTap: () => afterDialog(api.openMpvRender()),
          ),
          SettingsRow(
            icon: Icons.album_outlined,
            iconBackground: Colors.blue,
            title: info.blurayMenuTitle,
            value: info.blurayMenuText,
            showChevron: false,
            onTap: () => act(() => api.toggleBlurayMenu()),
          ),
        ],
        SettingsRow(
          icon: Icons.aspect_ratio_outlined,
          iconBackground: Colors.green,
          title: info.scaleTitle,
          value: info.scaleText,
          showChevron: true,
          onTap: () => afterDialog(api.openScale()),
        ),
        SettingsRow(
          icon: Icons.palette_outlined,
          iconBackground: Colors.purple,
          title: info.lutTitle,
          value: info.lutText,
          showChevron: true,
          onTap: () => afterDialog(api.openLut()),
        ),
        if (info.captionVisible)
          SettingsRow(
            icon: Icons.closed_caption_outlined,
            iconBackground: Colors.blue,
            title: info.captionTitle,
            value: info.captionText,
            showChevron: false,
            onTap: () => act(() => api.toggleCaption()),
            // Native long-press opens the system captioning settings
            // when captions are on; tap toggles Default/System.
            onLongPress: () => api.openSystemCaptioning(),
          ),
        SettingsRow(
          icon: Icons.tv_outlined,
          iconBackground: Colors.orange,
          title: info.osdTitle,
          value: info.osdText,
          showChevron: true,
          onTap: () => afterDialog(api.openOsd()),
        ),
        SettingsRow(
          icon: Icons.label_outlined,
          iconBackground: Colors.deepPurple,
          title: info.osdMediaBadgePositionTitle,
          value: info.osdMediaBadgePositionText,
          showChevron: true,
          onTap: () => afterDialog(api.openOsdMediaBadgePosition()),
        ),
        SettingsRow(
          icon: Icons.play_circle_outlined,
          iconBackground: Colors.green,
          title: info.autoPlayTitle,
          value: info.autoPlayText,
          showChevron: false,
          onTap: () => act(() => api.toggleAutoPlay()),
        ),
        SettingsRow(
          icon: Icons.autorenew_outlined,
          iconBackground: Colors.green,
          title: info.autoChangeTitle,
          value: info.autoChangeText,
          showChevron: false,
          onTap: () => act(() => api.toggleAutoChange()),
        ),
        SettingsRow(
          icon: Icons.flag_outlined,
          iconBackground: Colors.orange,
          title: info.deferFlagSwitchTitle,
          value: info.deferFlagSwitchText,
          showChevron: false,
          onTap: () => act(() => api.toggleDeferFlagSwitch()),
        ),
        SettingsRow(
          icon: Icons.brightness_6_outlined,
          iconBackground: Colors.amber,
          title: info.rememberBrightnessTitle,
          value: info.rememberBrightnessText,
          showChevron: false,
          onTap: () => act(() => api.toggleRememberBrightness()),
        ),
        SettingsRow(
          icon: Icons.live_tv_outlined,
          iconBackground: Colors.red,
          title: info.liveSourceFallbackTitle,
          value: info.liveSourceFallbackText,
          showChevron: false,
          onTap: () => act(() => api.toggleLiveSourceFallback()),
        ),
        SettingsRow(
          icon: Icons.replay_outlined,
          iconBackground: Colors.orange,
          title: info.failureFallbackTitle,
          value: info.failureFallbackText,
          showChevron: true,
          onTap: () => afterDialog(api.openFailureFallback()),
        ),
        SettingsRow(
          icon: Icons.speed_outlined,
          iconBackground: Colors.blue,
          title: info.speedTitle,
          value: info.speedText,
          showChevron: true,
          onTap: () => afterDialog(api.openSpeed()),
        ),
        SettingsRow(
          icon: Icons.music_note_outlined,
          iconBackground: Colors.pink,
          title: info.musicNotificationTitle,
          value: info.musicNotificationText,
          showChevron: false,
          onTap: () => act(() => api.toggleMusicNotification()),
        ),
        SettingsRow(
          icon: Icons.menu_book_outlined,
          iconBackground: Colors.brown,
          title: info.audioBookNotificationTitle,
          value: info.audioBookNotificationText,
          showChevron: false,
          onTap: () => act(() => api.toggleAudioBookNotification()),
        ),
        SettingsRow(
          icon: Icons.headphones_outlined,
          iconBackground: Colors.teal,
          title: info.backgroundTitle,
          value: info.backgroundText,
          showChevron: true,
          onTap: () => afterDialog(api.openBackground()),
        ),
        SettingsRow(
          icon: Icons.language_outlined,
          iconBackground: Colors.blue,
          title: info.uaTitle,
          value: info.uaText,
          showChevron: true,
          onTap: () => afterDialog(api.openUa()),
        ),
      ],
    );
  }
}

/// Member-view helpers, copied from the B5 family member's
/// _B5MemberState (b5_personal_page.dart). Duplicated on purpose, not
/// shared: the B5 mixin is file-private, and per-batch independence
/// (plan §4.9) means B6 must stay revertible without touching B5 code.
mixin _B6MemberState<T extends StatefulWidget> on State<T>
    implements WidgetsBindingObserver {
  final B6HostApi api = B6HostApi();

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
