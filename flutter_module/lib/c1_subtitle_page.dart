import 'package:material_ui/material_ui.dart';

import 'pigeon/c1_messages.g.dart';

/// B8 C1: Flutter version of the native SettingSubtitleFragment.
///
/// Three rows, mirroring fragment_setting_subtitle.xml in order: auto-match
/// switch, preferred language, subtitle sources. The two dialogs (language
/// picker, source summary) stay native and are opened through host calls;
/// this page re-fetches its state when the host Activity resumes, because
/// the native dialogs may have changed it while this page was covered.
class C1SubtitlePage extends StatefulWidget {
  const C1SubtitlePage({super.key});

  @override
  State<C1SubtitlePage> createState() => _C1SubtitlePageState();
}

class _C1SubtitlePageState extends State<C1SubtitlePage>
    with WidgetsBindingObserver {
  final C1HostApi _api = C1HostApi();
  C1SubtitleInfo? _info;
  String? _error;

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
    // A native dialog (language picker / source summary) may have changed
    // settings while covering this page; refresh when we come back.
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    try {
      final C1SubtitleInfo info = await _api.getSubtitleInfo();
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

  Future<void> _toggleAutoMatch() async {
    final C1SubtitleInfo? info = _info;
    if (info == null) return;
    try {
      await _api.setSubtitleAutoMatch(!info.autoMatchEnabled);
      await _load();
    } catch (e) {
      // State stays as the host reports it; _load() above would have
      // failed only if the channel itself broke.
    }
  }

  Widget _row({
    required IconData icon,
    required Color iconBackground,
    required String title,
    required String value,
    required bool showChevron,
    required VoidCallback onTap,
  }) {
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
                child: Text(
                  title,
                  style: const TextStyle(fontSize: 16),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              const SizedBox(width: 8),
              Text(
                value,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                  fontSize: 16,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              if (showChevron) ...[
                const SizedBox(width: 8),
                Icon(
                  Icons.chevron_right,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                  size: 20,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final C1SubtitleInfo? info = _info;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, Object? result) {
        if (didPop) return;
        _api.closeScreen();
      },
      child: Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => _api.closeScreen(),
        ),
        title: Text(info?.screenTitle ?? ''),
      ),
      body: _error != null
          ? Center(child: Text(_error!))
          : info == null
              ? const Center(child: CircularProgressIndicator())
              : SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                  child: Column(
                    children: [
                      _row(
                        icon: Icons.subtitles_outlined,
                        iconBackground: Colors.blue,
                        title: info.autoMatchTitle,
                        value: info.autoMatchEnabled ? info.onText : info.offText,
                        showChevron: false,
                        onTap: _toggleAutoMatch,
                      ),
                      _row(
                        icon: Icons.subtitles_outlined,
                        iconBackground: Colors.blue,
                        title: info.languageTitle,
                        value: info.languageText,
                        showChevron: true,
                        onTap: () => _api.openSubtitleLanguage(),
                      ),
                      _row(
                        icon: Icons.edit_outlined,
                        iconBackground: Colors.purple,
                        title: info.sourceTitle,
                        value: info.sourceText,
                        showChevron: true,
                        onTap: () => _api.openSubtitleSource(),
                      ),
                    ],
                  ),
                ),
    ),
    );
  }
}
