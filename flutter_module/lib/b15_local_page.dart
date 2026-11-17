import 'package:material_ui/material_ui.dart';

import 'pigeon/b15_local_messages.g.dart';
import 'pigeon/c1_messages.g.dart';

/// B8 B15: the mobile local-file browser (FileActivity) and the app
/// branding page (AppBrandingActivity), as two routes on the shared C1
/// engine. The file page is a result-returning picker in native: the
/// host sets RESULT_OK and finishes the C1 screen on a pick, so the
/// caller's ActivityResultLauncher receives the result exactly like
/// the native FileActivity. See docs/b8-b15-local.md.
class B15LocalPage extends StatefulWidget {
  const B15LocalPage({
    super.key,
    required this.selectDir,
    required this.readMode,
  });

  final bool selectDir;
  final bool readMode;

  @override
  State<B15LocalPage> createState() => _B15LocalPageState();
}

class _B15LocalPageState extends State<B15LocalPage>
    with WidgetsBindingObserver {
  final B15HostApi _api = B15HostApi();
  final C1HostApi _c1Api = C1HostApi();
  B15LocalScreen? _screen;
  String? _error;
  bool _loading = false;

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
    // The native page lists the directory once in initView; the
    // resumed refetch (plan §4.6) also covers coming back from the
    // reader page opened in read mode.
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    if (_loading) return;
    setState(() => _loading = true);
    try {
      final B15LocalScreen screen = await _api.getLocalFiles(
        _screen?.currentPath ?? '',
        widget.selectDir,
        widget.readMode,
      );
      if (!mounted) return;
      setState(() {
        _screen = screen;
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

  Future<void> _loadDir(String path) async {
    if (_loading) return;
    setState(() => _loading = true);
    try {
      final B15LocalScreen screen = await _api.getLocalFiles(
        path,
        widget.selectDir,
        widget.readMode,
      );
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '加载失败：$e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _onBack() async {
    final B15LocalScreen? screen = _screen;
    // Native onBackInvoked: at the root the page exits; otherwise it
    // goes to the parent directory.
    if (screen == null || screen.isRoot) {
      await _c1Api.closeScreen();
    } else {
      await _loadDir(screen.parentPath);
    }
  }

  Future<void> _onItemTap(B15LocalItem item) async {
    if (item.isDirectory) {
      await _loadDir(item.path);
      return;
    }
    try {
      await _api.openLocalItem(item.path, widget.readMode);
    } catch (_) {
      // The host already finished the screen on success; a channel
      // break here leaves the list as-is.
    }
  }

  Future<void> _onSelectCurrentDir() async {
    final B15LocalScreen? screen = _screen;
    if (screen == null) return;
    try {
      await _api.returnCurrentDir(screen.currentPath);
    } catch (_) {
      // The host already finished the screen on success; a channel
      // break here leaves the list as-is.
    }
  }

  @override
  Widget build(BuildContext context) {
    final B15LocalScreen? screen = _screen;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, _) {
        if (!didPop) _onBack();
      },
      child: Scaffold(
        appBar: AppBar(
          leading: IconButton(
            icon: const Icon(Icons.arrow_back),
            onPressed: _onBack,
          ),
          // Native toolbar title is the directory absolute path,
          // ellipsized from the start.
          title: Text(
            screen?.currentPath ?? '',
            overflow: TextOverflow.ellipsis,
          ),
        ),
        body: _buildBody(screen),
      ),
    );
  }

  Widget _buildBody(B15LocalScreen? screen) {
    if (_error != null) {
      return _CenterMessage(
        title: _error!,
        actionLabel: screen?.retryLabel ?? '重试',
        onAction: _load,
      );
    }
    if (screen != null && screen.errorText.isNotEmpty) {
      return _CenterMessage(
        title: screen.errorText,
        actionLabel: screen.retryLabel,
        onAction: _load,
      );
    }
    if (_loading && screen == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (screen == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (screen.items.isEmpty) {
      // Native empty state: title + description + one main action
      // ("重新扫描"), which refetches the current directory.
      return _CenterMessage(
        title: screen.emptyTitle,
        description: screen.emptyDescription,
        actionLabel: screen.emptyActionLabel,
        onAction: _load,
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.builder(
        itemCount:
            screen.items.length + (screen.selectDir ? 1 : 0),
        itemBuilder: (BuildContext context, int index) {
          if (screen.selectDir && index == 0) {
            // Native FileAdapter position 0 in select-dir mode:
            // "选择当前目录" returns the current directory.
            return ListTile(
              leading: const Icon(Icons.folder),
              title: Text(screen.selectCurrentDirLabel),
              onTap: _onSelectCurrentDir,
            );
          }
          final B15LocalItem item =
              screen.items[index - (screen.selectDir ? 1 : 0)];
          return ListTile(
            leading: Icon(
              item.isDirectory ? Icons.folder : Icons.insert_drive_file,
            ),
            title: Text(item.name),
            onTap: () => _onItemTap(item),
          );
        },
      ),
    );
  }
}

/// B8 B15: the mobile app-branding page (AppBrandingActivity). Two icon
/// options + save/cancel; the actual switch is 100% host-side
/// (AppBranding.putIconMode + applyLauncherIcon + launcher restart).
class B15BrandingPage extends StatefulWidget {
  const B15BrandingPage({super.key});

  @override
  State<B15BrandingPage> createState() => _B15BrandingPageState();
}

class _B15BrandingPageState extends State<B15BrandingPage>
    with WidgetsBindingObserver {
  final B15HostApi _api = B15HostApi();
  final C1HostApi _c1Api = C1HostApi();
  B15BrandingScreen? _screen;
  String? _error;
  int _selectedMode = 0;
  bool _saving = false;

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
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    try {
      final B15BrandingScreen screen = await _api.getBranding();
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _selectedMode = screen.selectedMode;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '加载失败：$e');
    }
  }

  Future<void> _save() async {
    if (_saving) return;
    setState(() => _saving = true);
    try {
      // Fire-and-forget: the host restarts the app via the launcher
      // intent, so this screen goes away with it.
      await _api.saveBrandingIcon(_selectedMode);
    } catch (_) {
      if (!mounted) return;
      setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final B15BrandingScreen? screen = _screen;
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => _c1Api.closeScreen(),
        ),
        title: Text(screen?.title ?? ''),
      ),
      body: _buildBody(screen),
    );
  }

  Widget _buildBody(B15BrandingScreen? screen) {
    if (_error != null) {
      return _CenterMessage(
        title: _error!,
        actionLabel: screen?.retryLabel ?? '重试',
        onAction: _load,
      );
    }
    if (screen != null && screen.errorText.isNotEmpty) {
      return _CenterMessage(
        title: screen.errorText,
        actionLabel: screen.retryLabel,
        onAction: _load,
      );
    }
    if (screen == null) {
      return const Center(child: CircularProgressIndicator());
    }
    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            screen.selectLabel,
            style: const TextStyle(fontSize: 16),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: _IconOption(
                  // Native previews are the real launcher icons
                  // (mipmap ic_launcher / drawable ic_launcher_history);
                  // Dart approximates them with Material icons.
                  icon: Icons.apps,
                  label: screen.currentLabel,
                  selected: _selectedMode == 0,
                  onTap: () => setState(() => _selectedMode = 0),
                ),
              ),
              Expanded(
                child: _IconOption(
                  icon: Icons.history,
                  label: screen.historyLabel,
                  selected: _selectedMode == 1,
                  onTap: () => setState(() => _selectedMode = 1),
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              TextButton(
                onPressed: () => _c1Api.closeScreen(),
                child: Text(screen.cancelLabel),
              ),
              const SizedBox(width: 8),
              FilledButton(
                onPressed: _saving ? null : _save,
                child: Text(screen.saveLabel),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _IconOption extends StatelessWidget {
  const _IconOption({
    required this.icon,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          border: Border.all(
            color: selected ? colors.primary : colors.outline,
            width: selected ? 2 : 1,
          ),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          children: [
            Icon(icon, size: 64),
            const SizedBox(height: 8),
            Text(
              label,
              style: TextStyle(
                fontSize: 14,
                color: selected ? colors.primary : null,
              ),
            ),
          ],
        ),
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
