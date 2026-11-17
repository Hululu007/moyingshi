import 'dart:async';

import 'package:material_ui/material_ui.dart';

import 'pigeon/b20_hk_messages.g.dart';

/// B8 B20: the mobile Hk miniapp manager page (native
/// HkMiniappActivity, 321 lines Kotlin) as the literal `/hk-manager`
/// route on the shared C1 engine.
///
/// Installed miniapps as a checkable list (name + row-end delete),
/// five full-width actions (import file / directory, paste JSON,
/// sync installed rules, export, cloud-code import) and the empty
/// state. Import / export / cloud-code work is 100% host-side: Dart
/// only sends action requests and renders the @async snapshots (host
/// completes exactly once per call, 35s watchdog). The SAF picker
/// waits on the user, so it cannot fit a 35s watchdog: Dart fires
/// requestHkImportPick() and polls getHkScreen() every 500ms for the
/// pick state (B19 polling precedent) until the pick epoch moves and
/// the state is idle again.
///
/// The Hk list / detail / search pages stay native (plan §6 P3): a
/// row tap opens the native list page through the host, and the
/// export share sheet is opened host-side too (Dart cannot open it).
/// Native deletes a row immediately with no confirmation dialog;
/// Dart does the same.
///
/// Layout mirrors native activity_hk_miniapp.xml: toolbar + five
/// full-width outlined buttons stacked vertically + list, empty hint
/// when there are no miniapps. Resumed refetch follows plan §4.6.
class B20HkPage extends StatefulWidget {
  const B20HkPage({super.key});

  @override
  State<B20HkPage> createState() => _B20HkPageState();
}

class _B20HkPageState extends State<B20HkPage> with WidgetsBindingObserver {
  final B20HostApi _api = B20HostApi();

  B20HkShell? _shell;
  List<B20HkItem> _entries = <B20HkItem>[];
  bool _loading = true;
  bool _busy = false;
  String? _errorText;
  String _emptyText = '';

  /// Export selection: the checkboxes serve the export only (native
  /// LocalSourceAdapter is setCheckable(true) for the same purpose);
  /// there is no selection-count UI.
  final Set<String> _selected = <String>{};

  /// File/dir-pick polling (B19 precedent): while a pick is in
  /// flight, getHkScreen() is polled every 500ms; the poll stops
  /// when the pick epoch moves and the state is idle again, or after
  /// 10 minutes (the user never came back from the SAF picker).
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
    // Tell the host to drop its worker; a stale engine must not
    // keep the Hk session alive (B18/B19 precedent).
    try {
      _api.disposeHk();
    } catch (_) {
      // The page is going away; a dispose failure changes nothing.
    }
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Plan §4.6: refetch on resumed (native refreshes in onResume).
    // Only when a list is already shown; the initial load covers the
    // fresh case.
    if (state == AppLifecycleState.resumed && !_loading) {
      _refresh();
    }
  }

  Future<void> _loadInitial() async {
    try {
      final B20HkShell shell = await _api.getHkShell();
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
      final B20HkScreen screen = await _api.getHkScreen();
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
  /// empty state when the list is empty), prunes the export selection
  /// to keys that still exist, and toasts a one-shot notice.
  void _applyScreen(B20HkScreen screen) {
    setState(() {
      _loading = false;
      _busy = screen.busy;
      _entries = screen.entries;
      _emptyText = screen.emptyText;
      _errorText = screen.loadErrorText.isEmpty ? null : screen.loadErrorText;
      _lastPickEpoch = screen.pickEpoch;
      _selected.removeWhere(
        (String key) => !screen.entries.any((B20HkItem e) => e.key == key),
      );
    });
    _showNotice(screen.noticeText);
  }

  void _showNotice(String notice) {
    if (notice.isEmpty || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(notice)),
    );
  }

  Future<void> _sync() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final B20HkScreen screen = await _api.syncMiniapps();
      if (!mounted) return;
      _applyScreen(screen);
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy = false);
      _showNotice(e.toString());
    }
  }

  Future<void> _export() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final B20HkScreen screen =
          await _api.exportMiniapps(_selected.toList());
      if (!mounted) return;
      _applyScreen(screen);
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy = false);
      _showNotice(e.toString());
    }
  }

  Future<void> _delete(B20HkItem item) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final B20HkScreen screen = await _api.deleteMiniapp(item.key);
      if (!mounted) return;
      _applyScreen(screen);
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy = false);
      _showNotice(e.toString());
    }
  }

  void _openList(B20HkItem item) {
    // Native row tap: the miniapp's own list page, still native.
    try {
      _api.openMiniappList(item.key);
    } catch (_) {
      // A failed open changes nothing on this page.
    }
  }

  // ------------------------------------------------------------------
  // File / directory import: host SAF picker + pick polling
  // ------------------------------------------------------------------

  Future<void> _importFromPicker() async {
    final B20HkShell? shell = _shell;
    if (shell == null || _busy) return;
    final String? kind = await showDialog<String>(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: Text(shell.pickTitle),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ListTile(
                title: Text(shell.pickFileLabel),
                onTap: () => Navigator.of(context).pop('file'),
              ),
              ListTile(
                title: Text(shell.pickDirLabel),
                onTap: () => Navigator.of(context).pop('dir'),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(null),
              child: Text(shell.cancelLabel),
            ),
          ],
        );
      },
    );
    if (kind == null || !mounted) return;
    setState(() => _busy = true);
    _pickTimer?.cancel();
    _pickTicks = 0;
    try {
      _api.requestHkImportPick(kind);
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
    // 10-minute cap: the user may never come back from the SAF picker.
    if (_pickTicks > 1200) {
      _pickTimer?.cancel();
      if (mounted) setState(() => _busy = false);
      return;
    }
    B20HkScreen screen;
    try {
      screen = await _api.getHkScreen();
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
    // Still picking: keep the list fresh, stay busy.
    setState(() {
      _entries = screen.entries;
      _emptyText = screen.emptyText;
      _busy = true;
    });
  }

  // ------------------------------------------------------------------
  // Paste JSON import + cloud-code import (dialogs stay native-shaped)
  // ------------------------------------------------------------------

  Future<void> _importFromPaste() async {
    final B20HkShell? shell = _shell;
    if (shell == null || _busy) return;
    final TextEditingController input = TextEditingController();
    String? dialogError;
    final bool? imported = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter setDialogState) {
            return AlertDialog(
              title: Text(shell.pasteTitle),
              content: TextField(
                controller: input,
                autofocus: true,
                minLines: 6,
                maxLines: 10,
                style: const TextStyle(fontFamily: 'monospace'),
                decoration: InputDecoration(
                  hintText: shell.pasteHint,
                  errorText: dialogError,
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(false),
                  child: Text(shell.cancelLabel),
                ),
                FilledButton(
                  onPressed: () async {
                    final B20HkScreen screen = await _api.importPasteJson(
                      input.text,
                    );
                    if (!mounted) return;
                    if (screen.dialogErrorText.isNotEmpty) {
                      // Native keeps the dialog open with a toast on a
                      // blank paste; Dart keeps it open with the same
                      // text inline (B19 dialogErrorText precedent).
                      setDialogState(
                        () => dialogError = screen.dialogErrorText,
                      );
                      return;
                    }
                    Navigator.of(context).pop(true);
                    _applyScreen(screen);
                  },
                  child: Text(shell.importLabel),
                ),
              ],
            );
          },
        );
      },
    );
    input.dispose();
    if (imported != true) return;
  }

  Future<void> _importFromCloudCode() async {
    final B20HkShell? shell = _shell;
    if (shell == null || _busy) return;
    String prefill = '';
    try {
      final B20CloudCodePrefill got = await _api.getCloudCodePrefill();
      prefill = got.prefillText;
    } catch (_) {
      // Native reads the clipboard best-effort too; an empty box is
      // the fallback either way.
    }
    if (!mounted) return;
    final TextEditingController input = TextEditingController(text: prefill);
    String? dialogError;
    final bool? imported = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter setDialogState) {
            return AlertDialog(
              title: Text(shell.cloudTitle),
              content: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(shell.cloudNote),
                    const SizedBox(height: 12),
                    TextField(
                      controller: input,
                      autofocus: true,
                      minLines: 5,
                      maxLines: 12,
                      style: const TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 12,
                      ),
                      decoration: InputDecoration(
                        hintText: shell.cloudHint,
                        errorText: dialogError,
                      ),
                    ),
                  ],
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(false),
                  child: Text(shell.cancelLabel),
                ),
                FilledButton(
                  onPressed: () async {
                    final B20HkScreen screen = await _api.importCloudCodes(
                      input.text,
                    );
                    if (!mounted) return;
                    if (screen.dialogErrorText.isNotEmpty) {
                      // Native toasts hk_miniapp_cloud_code_none and
                      // keeps the dialog open when no code is found;
                      // Dart keeps it open with the same text inline.
                      setDialogState(
                        () => dialogError = screen.dialogErrorText,
                      );
                      return;
                    }
                    Navigator.of(context).pop(true);
                    _applyScreen(screen);
                  },
                  child: Text(shell.importLabel),
                ),
              ],
            );
          },
        );
      },
    );
    input.dispose();
    if (imported != true) return;
  }

  // ------------------------------------------------------------------
  // Layout
  // ------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final B20HkShell? shell = _shell;
    final String title = shell?.title ?? '';
    return Scaffold(
      appBar: AppBar(
        title: Text(title),
      ),
      body: Column(
        children: [
          if (_busy) const LinearProgressIndicator(),
          Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              children: [
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton(
                    onPressed: _busy ? null : _importFromPicker,
                    child: Text(shell?.importFileLabel ?? ''),
                  ),
                ),
                const SizedBox(height: 8),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton(
                    onPressed: _busy ? null : _importFromPaste,
                    child: Text(shell?.importPasteLabel ?? ''),
                  ),
                ),
                const SizedBox(height: 8),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton(
                    onPressed: _busy ? null : _sync,
                    child: Text(shell?.syncLabel ?? ''),
                  ),
                ),
                const SizedBox(height: 8),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton(
                    onPressed: _busy ? null : _export,
                    child: Text(shell?.exportLabel ?? ''),
                  ),
                ),
                const SizedBox(height: 8),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton(
                    onPressed: _busy ? null : _importFromCloudCode,
                    child: Text(shell?.cloudCodeLabel ?? ''),
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
          padding: const EdgeInsets.all(32),
          child: Text(_emptyText, textAlign: TextAlign.center),
        ),
      );
    }
    return ListView.builder(
      itemCount: _entries.length,
      itemBuilder: (BuildContext context, int position) {
        final B20HkItem item = _entries[position];
        final bool isChecked = _selected.contains(item.key);
        // Native: tapping the row opens the miniapp's list page, the
        // checkbox alone toggles the export selection — so the tile
        // tap and the checkbox tap are separate targets (a
        // CheckboxListTile would toggle on a row tap).
        return ListTile(
          leading: Checkbox(
            value: isChecked,
            onChanged: _busy
                ? null
                : (bool? value) {
                    setState(() {
                      if (value == true) {
                        _selected.add(item.key);
                      } else {
                        _selected.remove(item.key);
                      }
                    });
                  },
          ),
          title: Text(item.name),
          onTap: _busy ? null : () => _openList(item),
          trailing: IconButton(
            icon: const Icon(Icons.delete),
            tooltip: _shell?.deleteLabel ?? '',
            onPressed: _busy ? null : () => _delete(item),
          ),
        );
      },
    );
  }
}
