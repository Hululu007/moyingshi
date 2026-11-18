import 'package:material_ui/material_ui.dart';

import 'pigeon/c1_messages.g.dart';

/// B8 C1: Flutter version of the native About dialog (AboutDialog).
///
/// All display strings come from the host (C1HostApi.getAboutInfo) so the
/// page shows exactly the locale strings the native dialog shows. The
/// three complex sub-flows (check update, update settings, GitHub proxy)
/// stay native and are opened through host calls.
///
/// Deliberate deviation from native: the native About is a dialog; this is
/// a full screen. Window-level dialog behavior (size, corners, dim) is not
/// replicated — the comparison checklist in docs/b8-c1-about-subtitle.md
/// §4 compares content, strings and behavior only.
class C1AboutPage extends StatefulWidget {
  const C1AboutPage({super.key});

  @override
  State<C1AboutPage> createState() => _C1AboutPageState();
}

class _C1AboutPageState extends State<C1AboutPage> {
  final C1HostApi _api = C1HostApi();
  C1AboutInfo? _info;
  String? _error;

  // Version-line 5-tap easter egg, mirroring AboutDialog: taps more than
  // 2 seconds apart restart the count; the 5th tap toggles hidden settings.
  static const int _tapWindowMs = 2000;
  static const int _tapCount = 5;
  int _versionTapCount = 0;
  int _versionTapLastMs = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final C1AboutInfo info = await _api.getAboutInfo();
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

  Future<void> _onVersionTap() async {
    final int now = DateTime.now().millisecondsSinceEpoch;
    if (now - _versionTapLastMs > _tapWindowMs) _versionTapCount = 0;
    _versionTapLastMs = now;
    if (++_versionTapCount < _tapCount) return;
    _versionTapCount = 0;
    try {
      final bool enabled = await _api.toggleHiddenSettings();
      if (!mounted) return;
      setState(() {
        if (_info != null) _info!.hiddenSettingsEnabled = enabled;
      });
    } catch (e) {
      // The host shows the toast on success; a failed call leaves the
      // native state untouched, so there is nothing to roll back here.
    }
  }

  @override
  Widget build(BuildContext context) {
    final C1AboutInfo? info = _info;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, Object? result) {
        if (didPop) return;
        _api.closeScreen();
      },
      child: Scaffold(
      appBar: AppBar(
        title: Text(info?.title ?? ''),
      ),
      body: _error != null
          ? Center(child: Text(_error!))
          : info == null
              ? const Center(child: CircularProgressIndicator())
              : Padding(
                  padding: const EdgeInsets.fromLTRB(24, 22, 24, 18),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              info.title,
                              style: const TextStyle(
                                fontSize: 20,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ),
                          Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 10, vertical: 5),
                            decoration: BoxDecoration(
                              color: Theme.of(context)
                                  .colorScheme
                                  .primaryContainer,
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: Text(
                              info.badge,
                              style: TextStyle(
                                color: Theme.of(context).colorScheme.primary,
                                fontSize: 12,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      GestureDetector(
                        onTap: _onVersionTap,
                        child: Text(
                          info.versionLine,
                          style: TextStyle(
                            color: Theme.of(context)
                                .colorScheme
                                .onSurfaceVariant,
                            fontSize: 13,
                          ),
                        ),
                      ),
                      const SizedBox(height: 16),
                      const Divider(height: 1),
                      const SizedBox(height: 14),
                      Expanded(
                        child: SingleChildScrollView(
                          child: Text(
                            info.disclaimer,
                            style: const TextStyle(fontSize: 14, height: 1.35),
                          ),
                        ),
                      ),
                      const SizedBox(height: 18),
                      Row(
                        children: [
                          Expanded(
                            child: SizedBox(
                              height: 44,
                              child: FilledButton.tonal(
                                onPressed: () => _api.checkUpdate(),
                                child: Text(info.checkUpdateLabel),
                              ),
                            ),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: SizedBox(
                              height: 44,
                              child: FilledButton.tonal(
                                onPressed: () => _api.openGithubProxy(),
                                child: Text(info.githubProxyLabel),
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 10),
                      SizedBox(
                        width: double.infinity,
                        height: 44,
                        child: FilledButton(
                          onPressed: () => _api.closeScreen(),
                          child: Text(info.confirmLabel),
                        ),
                      ),
                      const SizedBox(height: 10),
                      Align(
                        alignment: Alignment.centerRight,
                        child: IconButton(
                          onPressed: () => _api.openUpdateSettings(),
                          tooltip: info.updateSettingsLabel,
                          icon: const Icon(Icons.settings_outlined),
                        ),
                      ),
                    ],
                  ),
                ),
    ),
    );
  }
}
