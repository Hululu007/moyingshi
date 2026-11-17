import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import 'pigeon/b19_plugin_messages.g.dart';

/// B8 B19: the mobile plugin manager page (native
/// PluginManagerActivity, 1040 lines) as the literal `/plugins` route
/// on the shared C1 engine.
///
/// Installed plugins as a list (name + host-formatted meta line +
/// enable switch + parameters + delete), three top actions (import a
/// package directory, import from URL, reload) and the empty state.
/// Install / reload / file-picking are 100% host-side: Dart only
/// sends action requests and renders the @async snapshots (host
/// completes exactly once per call, 35s watchdog). The directory
/// picker waits on the user, so it cannot fit a 35s watchdog: Dart
/// fires requestPluginDirectory() and polls getPluginScreen() every
/// 500ms for the pick state (B10 polling precedent) until the pick
/// epoch moves and the state is idle again. The remote import is the
/// native five-step flow split across calls: resolve (dialog stays
/// open on error) → download + index → candidate selection → confirm
/// → install; the confirm text (names + combined permissions +
/// not-sandboxed warning + high-risk lines) is composed host-side.
///
/// Layout mirrors native activity_plugin_manager.xml /
/// adapter_plugin_entry.xml: toolbar + three-button row + list, empty
/// hint + import button when there are no plugins. Resumed refetch
/// follows plan §4.6.
class B19PluginPage extends StatefulWidget {
  const B19PluginPage({super.key});

  @override
  State<B19PluginPage> createState() => _B19PluginPageState();
}

class _B19PluginPageState extends State<B19PluginPage>
    with WidgetsBindingObserver {
  final B19HostApi _api = B19HostApi();

  B19PluginShell? _shell;
  List<B19PluginItem> _entries = <B19PluginItem>[];
  bool _loading = true;
  bool _busy = false;
  bool _remoteLoading = false;
  String? _errorText;
  String _emptyText = '';

  /// Directory-pick polling (B10 precedent): while a pick is in
  /// flight, getPluginScreen() is polled every 500ms; the poll stops
  /// when the pick epoch moves and the state is idle again, or after
  /// 10 minutes (the user never came back from FileActivity).
  Timer? _pickTimer;
  int _pickTicks = 0;
  int _lastPickEpoch = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadInitial();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pickTimer?.cancel();
    // Tell the host to drop its held remote zip and worker; a stale
    // engine must not keep the plugin session alive (B18 precedent).
    try {
      _api.disposePlugins();
    } catch (_) {
      // The page is going away; a dispose failure changes nothing.
    }
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Plan §4.6: refetch on resumed (native has no onResume refetch,
    // but the plugin list can change while another screen installs).
    // Only when a list is already shown; the initial load covers the
    // fresh case.
    if (state == AppLifecycleState.resumed && !_loading) {
      _refresh();
    }
  }

  Future<void> _loadInitial() async {
    try {
      final B19PluginShell shell = await _api.getPluginShell();
      if (!mounted) return;
      setState(() => _shell = shell);
    } catch (_) {
      // Chrome is static text; the snapshot carries its own error
      // state, so a shell failure does not block the page.
    }
    await _refresh();
  }

  Future<void> _refresh() async {
    try {
      final B19PluginScreen screen = await _api.getPluginScreen();
      if (!mounted) return;
      _applyScreen(screen);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _errorText = e.toString();
      });
    }
  }

  /// Applies a list snapshot: replaces the list (or shows the error /
  /// empty state when the list is empty) and toasts a one-shot notice.
  void _applyScreen(B19PluginScreen screen) {
    setState(() {
      _loading = false;
      _busy = screen.busy;
      _entries = screen.entries;
      _emptyText = screen.emptyText;
      _errorText = screen.loadErrorText.isEmpty ? null : screen.loadErrorText;
      _lastPickEpoch = screen.pickEpoch;
    });
    _showNotice(screen.noticeText);
  }

  void _showNotice(String notice) {
    if (notice.isEmpty || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(notice)),
    );
  }

  Future<void> _setEnabled(B19PluginItem item, bool enabled) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final B19PluginScreen screen =
          await _api.setPluginEnabled(item.pluginId, enabled);
      if (!mounted) return;
      _applyScreen(screen);
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy = false);
      _showNotice(e.toString());
    }
  }

  Future<void> _reload() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final B19PluginScreen screen = await _api.reloadPlugins();
      if (!mounted) return;
      _applyScreen(screen);
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy = false);
      _showNotice(e.toString());
    }
  }

  // ------------------------------------------------------------------
  // Local import: host FileChooser directory picker + pick polling
  // ------------------------------------------------------------------

  void _importFromDirectory() {
    if (_busy) return;
    setState(() => _busy = true);
    _pickTimer?.cancel();
    _pickTicks = 0;
    try {
      _api.requestPluginDirectory();
    } catch (_) {
      if (mounted) setState(() => _busy = false);
      return;
    }
    _pickTimer = Timer.periodic(const Duration(milliseconds: 500), (_) {
      _pollPick();
    });
  }

  Future<void> _pollPick() async {
    _pickTicks++;
    // 10-minute cap: the user may never come back from FileActivity.
    if (_pickTicks > 1200) {
      _pickTimer?.cancel();
      if (mounted) setState(() => _busy = false);
      return;
    }
    B19PluginScreen screen;
    try {
      screen = await _api.getPluginScreen();
    } catch (_) {
      return;
    }
    if (!mounted) return;
    // The pick finished when the epoch moved and the state is idle.
    // The outcome text travels in pickMessage (empty on cancel —
    // native stays silent too, so nothing is shown then).
    if (screen.pickEpoch != _lastPickEpoch && screen.pickState == 'idle') {
      _pickTimer?.cancel();
      _applyScreen(screen);
      _showNotice(screen.pickMessage);
      return;
    }
    // Still picking/importing: keep the list fresh, stay busy.
    setState(() {
      _entries = screen.entries;
      _emptyText = screen.emptyText;
      _busy = true;
    });
  }

  // ------------------------------------------------------------------
  // Remote import: URL dialog → download → candidates → confirm
  // ------------------------------------------------------------------

  Future<void> _importFromUrl() async {
    final B19PluginShell? shell = _shell;
    if (shell == null || _busy) return;
    final TextEditingController input = TextEditingController();
    String? dialogError;
    final bool? fetch = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter setDialogState) {
            return AlertDialog(
              title: Text(shell.remoteTitle),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextField(
                    controller: input,
                    autofocus: true,
                    keyboardType: TextInputType.url,
                    decoration: InputDecoration(
                      hintText: shell.remoteHint,
                      helperText: shell.remoteHelper,
                      errorText: dialogError,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton(
                      onPressed: () async {
                        final ClipboardData? data =
                            await Clipboard.getData('text/plain');
                        final String? text = data?.text;
                        if (text != null && text.isNotEmpty) {
                          input.text = text.trim();
                          input.selection = TextSelection.fromPosition(
                            TextPosition(offset: input.text.length),
                          );
                        }
                      },
                      child: Text(shell.remotePasteLabel),
                    ),
                  ),
                ],
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(false),
                  child: Text(shell.cancelLabel),
                ),
                FilledButton(
                  onPressed: () async {
                    final String url = input.text.trim();
                    final B19RemoteResolve resolved =
                        await _api.resolveRemoteUrl(url);
                    if (!mounted) return;
                    if (!resolved.ok) {
                      setDialogState(
                          () => dialogError = resolved.errorText);
                      return;
                    }
                    Navigator.of(context).pop(true);
                  },
                  child: Text(shell.remoteStartLabel),
                ),
              ],
            );
          },
        );
      },
    );
    final String url = input.text.trim();
    input.dispose();
    if (fetch != true || !mounted) return;
    setState(() {
      _remoteLoading = true;
      _busy = true;
    });
    final B19RemoteIndex index;
    try {
      index = await _api.downloadRemoteBundle(url);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _remoteLoading = false;
        _busy = false;
      });
      _showNotice(e.toString());
      return;
    }
    if (!mounted) return;
    setState(() {
      _remoteLoading = false;
      _busy = false;
    });
    if (index.errorText.isNotEmpty) {
      _showNotice(index.errorText);
      return;
    }
    if (index.candidates.isEmpty) {
      _showNotice(_shell?.remoteEmptyText ?? '');
      return;
    }
    await _showCandidates(index);
  }

  Future<void> _showCandidates(B19RemoteIndex index) async {
    final B19PluginShell? shell = _shell;
    if (shell == null || !mounted) return;
    final Set<String> checked = <String>{
      for (final B19RemoteCandidate c in index.candidates) c.entryPath,
    };
    final List<String>? selected = await showDialog<List<String>>(
      context: context,
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter setDialogState) {
            final bool allChecked =
                checked.length == index.candidates.length;
            return AlertDialog(
              content: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(index.countText),
                  Text(index.sourceText),
                  const SizedBox(height: 8),
                  TextButton(
                    onPressed: () {
                      setDialogState(() {
                        if (allChecked) {
                          checked.clear();
                        } else {
                          checked.addAll(
                            index.candidates.map((c) => c.entryPath),
                          );
                        }
                      });
                    },
                    child: Text(
                      allChecked ? shell.selectNoneLabel : shell.selectAllLabel,
                    ),
                  ),
                  SizedBox(
                    width: double.maxFinite,
                    height: 320,
                    child: ListView.builder(
                      shrinkWrap: true,
                      itemCount: index.candidates.length,
                      itemBuilder: (BuildContext context, int position) {
                        final B19RemoteCandidate candidate =
                            index.candidates[position];
                        final bool isChecked =
                            checked.contains(candidate.entryPath);
                        return CheckboxListTile(
                          value: isChecked,
                          title: Text(candidate.name),
                          subtitle: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(candidate.metaText),
                              Text(
                                candidate.permissionText,
                                style: TextStyle(
                                  color: candidate.permissionLabels.any(
                                          (p) => p.highRisk)
                                      ? Theme.of(context).colorScheme.error
                                      : null,
                                  fontWeight: candidate.permissionLabels.any(
                                          (p) => p.highRisk)
                                      ? FontWeight.bold
                                      : null,
                                ),
                              ),
                            ],
                          ),
                          onChanged: (bool? value) {
                            setDialogState(() {
                              if (value == true) {
                                checked.add(candidate.entryPath);
                              } else {
                                checked.remove(candidate.entryPath);
                              }
                            });
                          },
                        );
                      },
                    ),
                  ),
                ],
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(null),
                  child: Text(shell.cancelLabel),
                ),
                FilledButton(
                  onPressed: checked.isEmpty
                      ? null
                      : () => Navigator.of(context).pop(
                            index.candidates
                                .where((c) => checked.contains(c.entryPath))
                                .map((c) => c.entryPath)
                                .toList(),
                          ),
                  child: Text(shell.doImportLabel),
                ),
              ],
            );
          },
        );
      },
    );
    if (selected == null || selected.isEmpty || !mounted) return;
    final B19RemoteConfirm confirm = await _api.getRemoteConfirm(selected);
    if (!mounted) return;
    if (!confirm.valid) {
      _showNotice(shell.remoteEmptyText);
      return;
    }
    final bool? install = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: Text(confirm.title),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(confirm.messageText),
                if (confirm.riskText.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text(
                    confirm.riskText,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ],
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(confirm.cancelLabel),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: Text(confirm.installLabel),
            ),
          ],
        );
      },
    );
    if (install != true || !mounted) return;
    setState(() => _busy = true);
    try {
      final B19PluginScreen screen =
          await _api.installRemoteCandidates(selected);
      if (!mounted) return;
      _applyScreen(screen);
      if (screen.remoteResultLines.isNotEmpty) {
        await showDialog<void>(
          context: context,
          builder: (BuildContext context) {
            return AlertDialog(
              title: Text(screen.remoteResultTitle),
              content: SingleChildScrollView(
                child: Text(screen.remoteResultLines.join('\n')),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: Text(_shell?.confirmLabel ?? 'OK'),
                ),
              ],
            );
          },
        );
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy = false);
      _showNotice(e.toString());
    }
  }

  // ------------------------------------------------------------------
  // Parameters + delete
  // ------------------------------------------------------------------

  Future<void> _editParams(B19PluginItem item) async {
    if (_busy) return;
    final B19PluginParams params;
    try {
      params = await _api.getPluginParams(item.pluginId);
    } catch (e) {
      _showNotice(e.toString());
      return;
    }
    if (!mounted) return;
    final TextEditingController input =
        TextEditingController(text: params.jsonText);
    String? dialogError;
    final bool? saved = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter setDialogState) {
            return AlertDialog(
              title: Text(params.title),
              content: TextField(
                controller: input,
                autofocus: true,
                minLines: 4,
                maxLines: 8,
                decoration: InputDecoration(
                  hintText: params.hint,
                  errorText: dialogError,
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(false),
                  child: Text(params.cancelLabel),
                ),
                FilledButton(
                  onPressed: () async {
                    final B19PluginScreen screen =
                        await _api.savePluginParams(
                            item.pluginId, input.text.trim());
                    if (!mounted) return;
                    if (screen.dialogErrorText.isNotEmpty) {
                      // Native keeps the dialog open with an inline
                      // error (invalid JSON / too large); the list
                      // still refreshes underneath via _applyScreen.
                      setDialogState(
                          () => dialogError = screen.dialogErrorText);
                      _applyScreen(screen);
                      return;
                    }
                    Navigator.of(context).pop(true);
                    _applyScreen(screen);
                  },
                  child: Text(params.confirmLabel),
                ),
              ],
            );
          },
        );
      },
    );
    input.dispose();
    if (saved != true) return;
  }

  Future<void> _delete(B19PluginItem item) async {
    if (_busy) return;
    final B19DeleteConfirm confirm;
    try {
      confirm = await _api.getDeleteConfirm(item.pluginId);
    } catch (e) {
      _showNotice(e.toString());
      return;
    }
    if (!mounted) return;
    final bool? deleted = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: Text(confirm.title),
          content: Text(confirm.messageText),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(confirm.cancelLabel),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: Text(confirm.deleteLabel),
            ),
          ],
        );
      },
    );
    if (deleted != true || !mounted) return;
    setState(() => _busy = true);
    try {
      final B19PluginScreen screen = await _api.deletePlugin(item.pluginId);
      if (!mounted) return;
      _applyScreen(screen);
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy = false);
      _showNotice(e.toString());
    }
  }

  // ------------------------------------------------------------------
  // Layout
  // ------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final B19PluginShell? shell = _shell;
    final String title = shell?.title ?? '';
    return Scaffold(
      appBar: AppBar(
        title: Text(title),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: shell?.reloadLabel ?? '',
            onPressed: _busy ? null : _reload,
          ),
        ],
      ),
      body: Column(
        children: [
          if (_busy || _remoteLoading) const LinearProgressIndicator(),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
            child: Row(
              children: [
                Expanded(
                  child: FilledButton(
                    onPressed: _busy ? null : _importFromDirectory,
                    child: Text(shell?.importLabel ?? ''),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton(
                    onPressed: _busy ? null : _importFromUrl,
                    child: Text(shell?.remoteImportLabel ?? ''),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton(
                    onPressed: _busy ? null : _reload,
                    child: Text(shell?.reloadLabel ?? ''),
                  ),
                ),
              ],
            ),
          ),
          Expanded(child: _buildList()),
        ],
      ),
    );
  }

  Widget _buildList() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_errorText != null && _entries.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_errorText!, textAlign: TextAlign.center),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: _refresh,
                child: Text(_shell?.retryLabel ?? ''),
              ),
            ],
          ),
        ),
      );
    }
    if (_entries.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_emptyText, textAlign: TextAlign.center),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: _busy ? null : _importFromDirectory,
                child: Text(_shell?.emptyImportLabel ?? ''),
              ),
            ],
          ),
        ),
      );
    }
    return ListView.builder(
      itemCount: _entries.length,
      itemBuilder: (BuildContext context, int position) {
        final B19PluginItem item = _entries[position];
        return ListTile(
          title: Text(item.name),
          subtitle: Text(item.metaText),
          trailing: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                item.stateText,
                style: const TextStyle(fontSize: 12),
              ),
              Switch(
                value: item.enabled,
                onChanged: _busy
                    ? null
                    : (bool value) => _setEnabled(item, value),
              ),
              IconButton(
                icon: const Icon(Icons.tune),
                tooltip: _shell?.paramsLabel ?? '',
                onPressed: _busy ? null : () => _editParams(item),
              ),
              IconButton(
                icon: const Icon(Icons.delete),
                tooltip: _shell?.deleteLabel ?? '',
                onPressed: _busy ? null : () => _delete(item),
              ),
            ],
          ),
        );
      },
    );
  }
}
