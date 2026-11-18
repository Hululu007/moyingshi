import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' show Offset, PointerDeviceKind;

import 'package:flutter/gestures.dart'
    show Velocity, VelocityTracker, kTouchSlop;
import 'package:material_ui/material_ui.dart';

import 'pigeon/b21_lab_messages.g.dart';
import 'pigeon/c1_messages.g.dart';

/// B8 B21: the mobile lab list + detail pages (native LabActivity,
/// 940 lines, + LabDetailActivity, 435 lines) as the `/lab` family
/// route on the shared C1 engine, with an in-family Navigator (plan
/// §4.4, B3 precedent): the list is the initial page, a package tap
/// pushes the detail page, and the system back key pops the detail
/// first (then closes the screen through C1HostApi.closeScreen).
///
/// The list shows the lab groups (all / local source / miniapp /
/// lab.json groups) with one package list per group, plus the
/// mobile-only embedded local-source and miniapp lists. The detail
/// page shows one package's install state, download / uninstall
/// buttons, and its command list with run/stop rows. Everything
/// that executes (install, uninstall, run, terminal, export,
/// settings save) is 100% host-side: Dart only sends action
/// requests and renders the @async snapshots (host completes exactly
/// once per call, 60s watchdog on the config-loading calls).
///
/// The terminal and output pages stay native (plan §6 P4): the
/// detail page's terminal button and the command sheet's output
/// button open the native Activities through the host. The command
/// sheet, the add/edit-command dialog, the reset-commands confirm
/// dialog, the install progress dialog and the about dialog are all
/// native dialogs opened host-side; Dart never re-implements them.
///
/// Refresh model (plan §4.6): initial load + resumed refetch +
/// pull-to-refresh, plus a 2s poll on both pages while mounted
/// (the native detail page polls updateButtons every 2s; the list
/// page polls too because the host has no EventChannel and the
/// auto-install dialogs + background installs would otherwise leave
/// stale rows behind). The file/dir pickers wait on the user, so
/// they use the B19 500ms pick polling instead of a watchdog.
class B21LabPage extends StatefulWidget {
  const B21LabPage({super.key});

  @override
  State<B21LabPage> createState() => _B21LabPageState();
}

class _B21LabPageState extends State<B21LabPage> {
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();
  final C1HostApi _c1Api = C1HostApi();

  void _handleBack() {
    final NavigatorState? navigator = _navigatorKey.currentState;
    if (navigator != null && navigator.canPop()) {
      navigator.pop();
      return;
    }
    try {
      _c1Api.closeScreen();
    } catch (_) {
      // The screen is going away; a close failure changes nothing.
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, Object? result) {
        if (didPop) return;
        _handleBack();
      },
      child: Navigator(
        key: _navigatorKey,
        initialRoute: '/list',
        onGenerateRoute: (RouteSettings settings) {
          switch (settings.name) {
            case '/list':
              return MaterialPageRoute(
                builder: (_) => _B21LabListView(onBack: _handleBack),
              );
            case '/detail':
              final String name = settings.arguments is String
                  ? settings.arguments as String
                  : '';
              return MaterialPageRoute(
                builder: (_) => _B21LabDetailView(
                  name: name,
                  onBack: _handleBack,
                ),
              );
            default:
              return MaterialPageRoute(
                builder: (_) => _B21LabListView(onBack: _handleBack),
              );
          }
        },
      ),
    );
  }
}

// ------------------------------------------------------------------
// List page
// ------------------------------------------------------------------

class _B21LabListView extends StatefulWidget {
  const _B21LabListView({required this.onBack});

  final VoidCallback onBack;

  @override
  State<_B21LabListView> createState() => _B21LabListViewState();
}

class _B21LabListViewState extends State<_B21LabListView>
    with WidgetsBindingObserver, SingleTickerProviderStateMixin {
  final B21HostApi _api = B21HostApi();

  B21LabShell? _shell;
  List<B21LabGroup> _groups = <B21LabGroup>[];
  List<B21LabPackage> _packages = <B21LabPackage>[];
  List<B21LabSite> _localSources = <B21LabSite>[];
  List<B21LabSite> _miniappSites = <B21LabSite>[];
  String _currentGroup = 'all';
  bool _loading = true;
  bool _busy = false;
  String? _errorText;
  bool _refreshing = false;

  /// 2s poll (see the file header): the host has no EventChannel,
  /// and the auto-install dialogs + background installs would
  /// otherwise leave stale rows behind. The poll calls getLabList(),
  /// which builds from the cached root after the first load.
  Timer? _pollTimer;

  /// File/dir-pick + scan polling (B19 precedent): while a pick or
  /// a local-dir scan is in flight, getLabList() is polled every
  /// 500ms; the poll stops when the pick epoch moves and the state
  /// is idle again, or after 10 minutes.
  Timer? _pickTimer;
  int _pickTicks = 0;
  int _lastPickEpoch = 0;

  // Content-area group swipe (native LabActivity GroupSwipeListener
  // port, see docs/b8-lab-swipe-fix.md): a Listener observes pointer
  // events without entering the gesture arena, so vertical scrolling,
  // pull-to-refresh and card taps are unaffected by construction.
  // The drag offset lives in a ValueNotifier, so pointer moves never
  // call setState or rebuild the list — only the Transform wrapper
  // rebuilds.
  final ValueNotifier<double> _swipeOffset = ValueNotifier<double>(0);
  late final AnimationController _settleController;
  final Tween<double> _swipeTween = Tween<double>(begin: 0.0, end: 0.0);
  late final Animation<double> _settleAnimation;
  int? _swipePointer;
  Offset? _swipeDownPosition;
  VelocityTracker? _swipeTracker;
  double _swipeBaseX = 0;
  bool _swipeHorizontal = false;
  bool _swipeVertical = false;
  int _swipeFlingDelta = 0;

  /// Group the swipe offset is synced to: a group change from any
  /// path other than the swipe settle (chip tap, snapshot group
  /// reset) drops a stale offset in build(), mirroring native
  /// render() zeroing translationX on content swap.
  String _swipeGroup = 'all';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _settleController = AnimationController(vsync: this);
    _settleAnimation = _swipeTween.animate(
      CurvedAnimation(
        parent: _settleController,
        curve: Curves.decelerate,
      ),
    );
    _settleAnimation.addListener(() {
      _swipeOffset.value = _settleAnimation.value;
    });
    _loadInitial();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pollTimer?.cancel();
    _pickTimer?.cancel();
    _settleController.dispose();
    _swipeOffset.dispose();
    try {
      _api.disposeLab();
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
      final B21LabShell shell = await _api.getLabShell();
      if (!mounted) return;
      setState(() => _shell = shell);
    } catch (_) {
      // Chrome is static text; the snapshot carries its own error
      // state, so a shell failure does not block the page.
    }
    await _refresh();
    _pollTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (!_refreshing && mounted) _refresh();
    });
  }

  Future<void> _refresh() async {
    if (_refreshing) return;
    _refreshing = true;
    try {
      final B21LabList list = await _api.getLabList();
      if (!mounted) return;
      _applyList(list);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _errorText = e.toString();
      });
    } finally {
      _refreshing = false;
    }
  }

  Future<void> _reload() async {
    if (_refreshing) return;
    _refreshing = true;
    try {
      final B21LabList list = await _api.reloadLabList();
      if (!mounted) return;
      _applyList(list);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _errorText = e.toString();
      });
    } finally {
      _refreshing = false;
    }
  }

  void _applyList(B21LabList list) {
    final bool pickChanged = list.pickEpoch != _lastPickEpoch;
    final bool pickDone = pickChanged && list.pickState == 'idle';
    setState(() {
      _loading = false;
      _groups = list.groups;
      _packages = list.packages;
      _localSources = list.localSources;
      _miniappSites = list.miniappSites;
      _errorText = list.loadErrorText.isEmpty ? null : list.loadErrorText;
      if (list.groups.isNotEmpty &&
          !list.groups.any((B21LabGroup g) => g.id == _currentGroup)) {
        _currentGroup = list.groups.first.id;
      }
      _lastPickEpoch = list.pickEpoch;
      if (pickDone) _pickTimer?.cancel();
    });
    // A pick outcome (imported / scanned) toasts its pickMessage;
    // anything else toasts the one-shot notice from the action that
    // produced this snapshot. Plain polls carry both empty.
    if (pickDone) {
      _showNotice(list.pickMessage);
    } else {
      _showNotice(list.noticeText);
    }
  }

  void _showNotice(String notice) {
    if (notice.isEmpty || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(notice)),
    );
  }

  void _startPickPoll() {
    _pickTimer?.cancel();
    _pickTicks = 0;
    _pickTimer = Timer.periodic(const Duration(milliseconds: 500), (_) {
      _pickTicks++;
      // 10-minute cap: the user may never come back from the picker.
      if (_pickTicks > 1200) {
        _pickTimer?.cancel();
        return;
      }
      _refresh();
    });
  }

  void _switchGroup(int delta) {
    if (_groups.isEmpty) return;
    int index = _groups.indexWhere((B21LabGroup g) => g.id == _currentGroup);
    if (index < 0) index = 0;
    final int next = index + delta;
    if (next < 0 || next >= _groups.length) return;
    setState(() => _currentGroup = _groups[next].id);
  }

  // ----------------------------------------------------------------
  // Content-area group swipe (native GroupSwipeListener port)
  // ----------------------------------------------------------------

  /// Whether a group exists in the swipe direction (+1 next, -1
  /// previous); native LabActivity.hasGroup.
  bool _hasGroup(int delta) {
    if (_groups.isEmpty) return false;
    int index =
        _groups.indexWhere((B21LabGroup g) => g.id == _currentGroup);
    if (index < 0) index = 0;
    final int next = index + delta;
    return next >= 0 && next < _groups.length;
  }

  void _onSwipePointerDown(PointerDownEvent event) {
    // Track the first pointer only; a second finger never disturbs
    // the drag in progress.
    if (_swipePointer != null) return;
    _swipePointer = event.pointer;
    // A new gesture interrupts a running settle animation and
    // continues from the current offset, without a jump (native
    // onDown: animate().cancel() + swipeDragBaseX).
    _settleController.stop();
    _swipeBaseX = _swipeOffset.value;
    _swipeDownPosition = event.position;
    _swipeHorizontal = false;
    _swipeVertical = false;
    _swipeFlingDelta = 0;
    _swipeTracker = VelocityTracker.withKind(PointerDeviceKind.touch)
      ..addPosition(event.timeStamp, event.position);
  }

  void _onSwipePointerMove(PointerMoveEvent event) {
    final Offset? down = _swipeDownPosition;
    if (event.pointer != _swipePointer || down == null) return;
    _swipeTracker?.addPosition(event.timeStamp, event.position);
    if (_swipeVertical) return;
    final Offset delta = event.position - down;
    if (!_swipeHorizontal) {
      // Direction lock on the first significant move (native
      // onScroll): horizontal wins only if |dx| > slop and
      // |dx| > |dy|; otherwise vertical locks and the drag is left
      // to the list.
      if (delta.dx.abs() > kTouchSlop &&
          delta.dx.abs() > delta.dy.abs()) {
        _swipeHorizontal = true;
      } else if (delta.dy.abs() > kTouchSlop &&
          delta.dy.abs() > delta.dx.abs()) {
        _swipeVertical = true;
        return;
      } else {
        return;
      }
    }
    // Follow the finger; at the ends (no group in that direction)
    // the 0.35 resistance hints at the end without crossing it.
    // The offset is clamped to one screen width, as native does.
    final double width = MediaQuery.sizeOf(context).width;
    final double raw = _swipeBaseX + delta.dx;
    double tx = raw;
    if ((raw < 0 && !_hasGroup(1)) || (raw > 0 && !_hasGroup(-1))) {
      tx = _swipeBaseX + (raw - _swipeBaseX) * 0.35;
    }
    _swipeOffset.value = tx.clamp(-width, width);
  }

  void _onSwipePointerUp(PointerUpEvent event) {
    final Offset? down = _swipeDownPosition;
    if (event.pointer != _swipePointer || down == null) return;
    _swipePointer = null;
    _swipeDownPosition = null;
    // Fling (native onFling): recorded, direction from the total
    // displacement sign, settled on release together with the
    // displacement fallback. Requires a fast horizontal-dominant
    // release and at least 50dp of travel.
    final VelocityTracker? tracker = _swipeTracker;
    _swipeTracker = null;
    if (tracker != null) {
      final Velocity velocity = tracker.getVelocity();
      final double vx = velocity.pixelsPerSecond.dx;
      final double vy = velocity.pixelsPerSecond.dy;
      final double dx = event.position.dx - down.dx;
      if (vx.abs() > 500 && vx.abs() > vy.abs() && dx.abs() > 50) {
        _swipeFlingDelta = dx < 0 ? 1 : -1;
      }
    }
    _settleSwipe();
  }

  void _onSwipePointerCancel(PointerCancelEvent event) {
    if (event.pointer != _swipePointer) return;
    _swipePointer = null;
    _swipeDownPosition = null;
    _swipeTracker = null;
    _swipeFlingDelta = 0;
    _bounceSwipeBack();
  }

  /// Release settlement (native settleSwipe): fling first, else the
  /// displacement over max(120dp, 1/4 screen width). Over threshold
  /// the new group slides in once (~220ms) from the gesture
  /// direction; otherwise it bounces back (~180ms).
  void _settleSwipe() {
    int delta = _swipeFlingDelta;
    _swipeFlingDelta = 0;
    if (delta == 0 && _swipeHorizontal) {
      final double tx = _swipeOffset.value;
      final double threshold =
          math.max(120.0, MediaQuery.sizeOf(context).width / 4);
      if (tx < -threshold) {
        delta = 1;
      } else if (tx > threshold) {
        delta = -1;
      }
    }
    if (delta == 0 || !_hasGroup(delta)) {
      _bounceSwipeBack();
      return;
    }
    final double width = MediaQuery.sizeOf(context).width;
    final double enterX = delta > 0 ? width : -width;
    _switchGroup(delta);
    _swipeGroup = _currentGroup;
    _animateSwipeOffset(
      0,
      const Duration(milliseconds: 220),
      from: enterX,
    );
  }

  void _bounceSwipeBack() {
    _swipeFlingDelta = 0;
    _animateSwipeOffset(0, const Duration(milliseconds: 180));
  }

  void _animateSwipeOffset(
    double target,
    Duration duration, {
    double? from,
  }) {
    _settleController.stop();
    final double start = from ?? _swipeOffset.value;
    _swipeOffset.value = start;
    _swipeTween.begin = start;
    _swipeTween.end = target;
    _settleController.duration = duration;
    _settleController.forward(from: 0);
  }

  List<B21LabPackage> get _visiblePackages {
    if (_currentGroup == 'all') return _packages;
    return _packages
        .where((B21LabPackage p) => p.groupId == _currentGroup)
        .toList();
  }

  bool get _isLocalSourceGroup => _currentGroup == '__local_source__';
  bool get _isMiniappGroup => _currentGroup == '__miniapp__';

  // ----------------------------------------------------------------
  // Toolbar actions (native LabActivity menu, per current group)
  // ----------------------------------------------------------------

  void _onAddPressed() {
    // Native toolbar "+": local-source group opens the local-source
    // dialog, miniapp group opens the miniapp manager, all other
    // groups open the import dialog.
    if (_isLocalSourceGroup) {
      try {
        _api.openLabLocalSourceDialog();
      } catch (_) {}
      return;
    }
    if (_isMiniappGroup) {
      try {
        _api.openLabMiniappManager();
      } catch (_) {}
      return;
    }
    _showImportDialog();
  }

  void _onHelpPressed() {
    try {
      _api.openLabAbout();
    } catch (_) {}
  }

  void _onSettingsPressed() {
    _showLabSettingsDialog();
  }

  void _onScanPressed() {
    _startPickPoll();
    try {
      _api.pickLabLocalSourceDir();
    } catch (_) {}
  }

  Future<void> _showImportDialog() async {
    final B21LabShell? shell = _shell;
    if (shell == null) return;
    final TextEditingController input = TextEditingController();
    String dialogError = '';
    final bool? imported = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter setDialogState) {
            return AlertDialog(
              title: Text(shell.importTitle),
              content: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(shell.importHint),
                    const SizedBox(height: 12),
                    TextField(
                      controller: input,
                      minLines: 4,
                      maxLines: 8,
                      style: const TextStyle(fontFamily: 'monospace'),
                      decoration: InputDecoration(
                        hintText: shell.importPlaceholder,
                        errorText:
                            dialogError.isEmpty ? null : dialogError,
                      ),
                    ),
                    const SizedBox(height: 8),
                    TextButton(
                      onPressed: () {
                        Navigator.of(context).pop(false);
                        _startPickPoll();
                        try {
                          _api.requestLabImportFile();
                        } catch (_) {}
                      },
                      child: Text(shell.importSelectFileLabel),
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
                    final B21LabImportDecode decoded =
                        await _api.decodeLabImport(input.text);
                    if (!mounted) return;
                    if (decoded.errorText.isNotEmpty) {
                      // Native toasts "无效的导入数据" and keeps the
                      // dialog open; Dart keeps it open with the same
                      // text inline.
                      setDialogState(
                        () => dialogError = decoded.errorText,
                      );
                      return;
                    }
                    try {
                      final B21LabList list =
                          await _api.importLabPackage(decoded.json);
                      if (!mounted) return;
                      Navigator.of(context).pop(true);
                      _applyList(list);
                    } catch (e) {
                      if (!mounted) return;
                      setDialogState(() => dialogError = e.toString());
                    }
                  },
                  child: Text(shell.importButtonLabel),
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

  Future<void> _showLabSettingsDialog() async {
    final B21LabShell? shell = _shell;
    if (shell == null) return;
    B21LabSettings settings;
    try {
      settings = await _api.getLabSettings();
    } catch (e) {
      _showNotice(e.toString());
      return;
    }
    int source = settings.source;
    bool foreground = settings.foreground;
    bool battery = settings.battery;
    bool globalProxy = settings.globalProxy;
    int lastPickEpoch = settings.pickEpoch;
    // Controllers live for the whole dialog (created once here, not
    // in the builder — rebuilding on a switch toggle must not reset
    // the typed text or the cursor).
    final TextEditingController urlController =
        TextEditingController(text: settings.url);
    final TextEditingController localPathController =
        TextEditingController(text: settings.localPath);
    final TextEditingController rootController =
        TextEditingController(text: settings.rootOverride);
    final TextEditingController proxyPortController =
        TextEditingController(text: settings.proxyPort.toString());
    final TextEditingController proxyNoProxyController =
        TextEditingController(text: settings.proxyNoProxy);
    Timer? settingsPickTimer;
    final bool? saved = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter setDialogState) {
            settingsPickTimer ??=
                Timer.periodic(const Duration(milliseconds: 500), (_) async {
              if (!mounted) return;
              try {
                final B21LabSettings current =
                    await _api.getLabSettings();
                if (current.pickEpoch != lastPickEpoch &&
                    current.pickState == 'idle') {
                  lastPickEpoch = current.pickEpoch;
                  if (current.pickMessage.isNotEmpty) {
                    // Picking a local config file switches the source
                    // to local immediately (native onActivityResult);
                    // the dialog stays open and shows the new path.
                    setDialogState(() {
                      localPathController.text = current.pickMessage;
                      source = 0;
                    });
                    _refresh();
                  }
                }
              } catch (_) {}
            });
            final bool isUrl = source == 1;
            return AlertDialog(
              title: Text(settings.title),
              content: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(settings.sourceHint),
                    const SizedBox(height: 12),
                    DropdownButton<int>(
                      value: source,
                      isExpanded: true,
                      hint: Text(settings.sourceDropdownHint),
                      items: [
                        DropdownMenuItem<int>(
                          value: 0,
                          child: Text(settings.sourceLocalLabel),
                        ),
                        DropdownMenuItem<int>(
                          value: 1,
                          child: Text(settings.sourceUrlLabel),
                        ),
                      ],
                      onChanged: (int? value) {
                        if (value == null) return;
                        setDialogState(() => source = value);
                      },
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: isUrl
                                ? urlController
                                : localPathController,
                            readOnly: !isUrl,
                            decoration: InputDecoration(
                              hintText: isUrl
                                  ? settings.urlHint
                                  : settings.localPathHint,
                            ),
                          ),
                        ),
                        if (!isUrl)
                          IconButton(
                            icon: const Icon(Icons.folder_open),
                            onPressed: () {
                              try {
                                _api.requestLabLocalConfigFile();
                              } catch (_) {}
                            },
                          ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    TextField(
                      controller: rootController,
                      decoration: InputDecoration(
                        hintText: settings.rootHint,
                      ),
                    ),
                    SwitchListTile(
                      title: Text(settings.foregroundTitle),
                      subtitle: Text(settings.foregroundHint),
                      value: foreground,
                      onChanged: (bool value) =>
                          setDialogState(() => foreground = value),
                    ),
                    SwitchListTile(
                      title: Text(settings.batteryTitle),
                      subtitle: Text(settings.batteryHint),
                      value: battery,
                      onChanged: (bool value) =>
                          setDialogState(() => battery = value),
                    ),
                    SwitchListTile(
                      title: Text(settings.proxyTitle),
                      subtitle: Text(settings.proxyHint),
                      value: globalProxy,
                      onChanged: (bool value) =>
                          setDialogState(() => globalProxy = value),
                    ),
                    if (globalProxy) ...[
                      TextField(
                        controller: proxyPortController,
                        keyboardType: TextInputType.number,
                        decoration: InputDecoration(
                          hintText: settings.proxyPortHint,
                        ),
                      ),
                      const SizedBox(height: 8),
                      TextField(
                        controller: proxyNoProxyController,
                        decoration: InputDecoration(
                          hintText: settings.proxyNoProxyHint,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(false),
                  child: Text(settings.cancelLabel),
                ),
                FilledButton(
                  onPressed: () async {
                    final int port = int.tryParse(
                            proxyPortController.text.trim()) ??
                        7890;
                    try {
                      final B21LabList list = await _api.saveLabSettings(
                        B21LabSettingsInput(
                          source: source,
                          url: urlController.text.trim(),
                          localPath: localPathController.text,
                          rootOverride: rootController.text.trim(),
                          foreground: foreground,
                          battery: battery,
                          globalProxy: globalProxy,
                          proxyPort: port,
                          proxyNoProxy: proxyNoProxyController.text.trim(),
                        ),
                      );
                      if (!mounted) return;
                      Navigator.of(context).pop(true);
                      _applyList(list);
                    } catch (e) {
                      if (!mounted) return;
                      _showNotice(e.toString());
                    }
                  },
                  child: Text(settings.saveLabel),
                ),
              ],
            );
          },
        );
      },
    );
    settingsPickTimer?.cancel();
    urlController.dispose();
    localPathController.dispose();
    rootController.dispose();
    proxyPortController.dispose();
    proxyNoProxyController.dispose();
    if (saved != true) return;
  }

  void _showGroupSheet() {
    if (_groups.isEmpty) return;
    showModalBottomSheet<void>(
      context: context,
      builder: (BuildContext context) {
        return ListView(
          shrinkWrap: true,
          children: _groups.map((B21LabGroup group) {
            final bool selected = group.id == _currentGroup;
            return ListTile(
              title: Text(group.name),
              trailing:
                  selected ? const Icon(Icons.check) : null,
              onTap: () {
                setState(() => _currentGroup = group.id);
                Navigator.of(context).pop();
              },
            );
          }).toList(),
        );
      },
    );
  }

  Future<void> _deleteLocalSource(B21LabSite site) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final B21LabList list = await _api.deleteLabLocalSource(site.key);
      if (!mounted) return;
      _applyList(list);
    } catch (e) {
      if (!mounted) return;
      _showNotice(e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _deleteMiniapp(B21LabSite site) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final B21LabList list = await _api.deleteLabMiniapp(site.key);
      if (!mounted) return;
      _applyList(list);
    } catch (e) {
      if (!mounted) return;
      _showNotice(e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _openPackage(B21LabPackage package) {
    Navigator.of(context).pushNamed('/detail', arguments: package.name);
  }

  Color _statusColor(String kind) {
    switch (kind) {
      case 'running':
        return Colors.green;
      case 'installed':
        return Colors.blue;
      case 'unavailable':
        return Colors.grey;
      case 'not_installed':
      default:
        return Colors.orange;
    }
  }

  Widget _packageIcon(String iconUrl) {
    const Widget placeholder = CircleAvatar(
      child: Icon(Icons.extension),
    );
    if (iconUrl.isEmpty) return placeholder;
    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: Image.network(
        iconUrl,
        width: 48,
        height: 48,
        fit: BoxFit.cover,
        errorBuilder: (_, __, ___) => placeholder,
      ),
    );
  }

  // ----------------------------------------------------------------
  // Layout
  // ----------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    // A group change from any path other than the swipe settle (chip
    // tap, snapshot group reset) drops a stale drag offset, mirroring
    // native render() zeroing translationX on content swap. The
    // settle path syncs _swipeGroup before the rebuild, so the
    // slide-in is unaffected. No setState here: only the offset
    // notifier and the settle controller are touched.
    if (_swipeGroup != _currentGroup) {
      _swipeGroup = _currentGroup;
      _settleController.stop();
      _swipeOffset.value = 0;
    }
    final B21LabShell? shell = _shell;
    final String title = shell?.title ?? '';
    final List<B21LabPackage> visible = _visiblePackages;
    final bool showScan = _isLocalSourceGroup;
    Widget body;
    if (_loading) {
      body = const Center(child: CircularProgressIndicator());
    } else if (_errorText != null) {
      body = Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_errorText!),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: _reload,
              child: Text(shell?.retryLabel ?? ''),
            ),
          ],
        ),
      );
    } else if (_isLocalSourceGroup) {
      body = _localSources.isEmpty
          ? Center(child: Text(shell?.localSourceEmptyText ?? ''))
          : ListView.builder(
              itemCount: _localSources.length,
              itemBuilder: (BuildContext context, int index) {
                final B21LabSite site = _localSources[index];
                return ListTile(
                  title: Text(site.name),
                  trailing: IconButton(
                    icon: const Icon(Icons.delete_outline),
                    tooltip: shell?.deleteLabel,
                    onPressed:
                        _busy ? null : () => _deleteLocalSource(site),
                  ),
                  onTap: () {
                    try {
                      _api.setLabLocalSourceHome(site.key);
                    } catch (_) {}
                  },
                );
              },
            );
    } else if (_isMiniappGroup) {
      body = _miniappSites.isEmpty
          ? Center(child: Text(shell?.miniappEmptyText ?? ''))
          : ListView.builder(
              itemCount: _miniappSites.length,
              itemBuilder: (BuildContext context, int index) {
                final B21LabSite site = _miniappSites[index];
                return ListTile(
                  title: Text(site.name),
                  trailing: IconButton(
                    icon: const Icon(Icons.delete_outline),
                    tooltip: shell?.deleteLabel,
                    onPressed: _busy ? null : () => _deleteMiniapp(site),
                  ),
                  onTap: () {
                    try {
                      _api.openLabMiniappList(site.key);
                    } catch (_) {}
                  },
                );
              },
            );
    } else {
      body = visible.isEmpty
          ? Center(child: Text(shell?.emptyText ?? ''))
          : ListView.builder(
              itemCount: visible.length,
              itemBuilder: (BuildContext context, int index) {
                final B21LabPackage package = visible[index];
                return Card(
                  margin: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 6,
                  ),
                  child: ListTile(
                    leading: _packageIcon(package.iconUrl),
                    title: Text(package.titleText),
                    subtitle: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (package.info.isNotEmpty) Text(package.info),
                        Row(
                          children: [
                            Text(package.versionText),
                            if (package.sizeText.isNotEmpty) ...[
                              const Text(' · '),
                              Text(package.sizeText),
                            ],
                            const Spacer(),
                            Chip(
                              label: Text(package.statusText),
                              backgroundColor:
                                  _statusColor(package.statusKind),
                            ),
                            if (package.hasUpdate)
                              const Padding(
                                padding: EdgeInsets.only(left: 4),
                                child: Icon(
                                  Icons.arrow_circle_up,
                                  size: 18,
                                ),
                              ),
                          ],
                        ),
                      ],
                    ),
                    onTap: () => _openPackage(package),
                    onLongPress: () => _openPackage(package),
                  ),
                );
              },
            );
    }

    return Scaffold(
      appBar: AppBar(
        title: Text(title),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: widget.onBack,
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.help_outline),
            tooltip: shell?.helpLabel,
            onPressed: _onHelpPressed,
          ),
          IconButton(
            icon: const Icon(Icons.add),
            tooltip: shell?.importLabel,
            onPressed: _onAddPressed,
          ),
          if (showScan)
            IconButton(
              icon: const Icon(Icons.folder_open),
              tooltip: shell?.scanLabel,
              onPressed: _onScanPressed,
            ),
          IconButton(
            icon: const Icon(Icons.settings),
            tooltip: shell?.settingsLabel,
            onPressed: _onSettingsPressed,
          ),
        ],
      ),
      body: Column(
        children: [
          if (_busy) const LinearProgressIndicator(),
          if (_groups.length > 1)
            SizedBox(
              height: 48,
              child: ListView.builder(
                scrollDirection: Axis.horizontal,
                itemCount: _groups.length,
                itemBuilder: (BuildContext context, int index) {
                  final B21LabGroup group = _groups[index];
                  final bool selected = group.id == _currentGroup;
                  return Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: ChoiceChip(
                      label: Text(group.name),
                      selected: selected,
                      onSelected: (_) {
                        setState(() => _currentGroup = group.id);
                      },
                    ),
                  );
                },
              ),
            ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: _reload,
              child: Listener(
                // Native content swipe switches groups (left = next,
                // right = previous), ported from LabActivity's
                // observe-don't-consume OnTouchListener: this
                // Listener never enters the gesture arena, so
                // vertical scrolling, pull-to-refresh and card taps
                // keep their own paths. Translucent hit-testing so
                // the empty/loading states swipe anywhere, matching
                // native (gesture on the recycler + empty view).
                behavior: HitTestBehavior.translucent,
                onPointerDown: _onSwipePointerDown,
                onPointerMove: _onSwipePointerMove,
                onPointerUp: _onSwipePointerUp,
                onPointerCancel: _onSwipePointerCancel,
                child: ValueListenableBuilder<double>(
                  valueListenable: _swipeOffset,
                  builder: (
                    BuildContext context,
                    double offset,
                    Widget? child,
                  ) {
                    return Transform.translate(
                      offset: Offset(offset, 0),
                      child: child,
                    );
                  },
                  child: body,
                ),
              ),
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        tooltip: shell?.switchGroupLabel,
        onPressed: _showGroupSheet,
        child: const Icon(Icons.tune),
      ),
    );
  }
}

// ------------------------------------------------------------------
// Detail page
// ------------------------------------------------------------------

class _B21LabDetailView extends StatefulWidget {
  const _B21LabDetailView({required this.name, required this.onBack});

  final String name;
  final VoidCallback onBack;

  @override
  State<_B21LabDetailView> createState() => _B21LabDetailViewState();
}

class _B21LabDetailViewState extends State<_B21LabDetailView>
    with WidgetsBindingObserver {
  final B21HostApi _api = B21HostApi();

  B21LabShell? _shell;
  B21LabDetail? _detail;
  bool _loading = true;
  String? _errorText;

  /// 2s poll (native LabDetailActivity's mRefreshHandler): the light
  /// state call reads the cached root only, no config reload.
  Timer? _pollTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadInitial();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pollTimer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Plan §4.6: refetch on resumed (native refreshes buttons in
    // onResume). Only when a detail is already shown.
    if (state == AppLifecycleState.resumed && !_loading) {
      _refresh();
    }
  }

  Future<void> _loadInitial() async {
    try {
      final B21LabShell shell = await _api.getLabShell();
      if (!mounted) return;
      setState(() => _shell = shell);
    } catch (_) {
      // Chrome is static text; the snapshot carries its own error
      // state, so a shell failure does not block the page.
    }
    await _refresh();
    _pollTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (mounted) _poll();
    });
  }

  Future<void> _refresh() async {
    try {
      final B21LabDetail detail = await _api.getLabDetail(widget.name);
      if (!mounted) return;
      _applyDetail(detail);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _errorText = e.toString();
      });
    }
  }

  void _applyDetail(B21LabDetail detail) {
    setState(() {
      _loading = false;
      _detail = detail;
      _errorText = detail.loadErrorText.isEmpty ? null : detail.loadErrorText;
    });
    if (!detail.found) {
      // Native toasts "找不到模块" and finishes; Dart pops back to
      // the list (the toast already travelled in noticeText, if any).
      _showNotice(detail.noticeText);
      widget.onBack();
      return;
    }
    _showNotice(detail.noticeText);
  }

  Future<void> _poll() async {
    // Light call (no reload). Merges the running states into the
    // shown snapshot by command id.
    late final B21LabDetailState state;
    try {
      state = await _api.pollLabDetail(widget.name);
    } catch (_) {
      return;
    }
    if (!mounted) return;
    final B21LabDetail? detail = _detail;
    if (detail == null) return;
    if (!state.found) {
      widget.onBack();
      return;
    }
    setState(() {
      _detail = B21LabDetail(
        found: detail.found,
        name: detail.name,
        title: detail.title,
        versionText: detail.versionText,
        info: detail.info,
        iconUrl: detail.iconUrl,
        installed: state.installed,
        running: state.running,
        hasUpdate: state.hasUpdate,
        downloadLabel: state.downloadLabel,
        downloadVisible: state.downloadVisible,
        uninstallVisible: state.uninstallVisible,
        statusText: state.statusText,
        statusKind: state.statusKind,
        hasSettings: detail.hasSettings,
        commands: detail.commands.map((B21LabCommand command) {
          final B21LabCommandState? polled = state.commandStates
              .where((B21LabCommandState s) => s.id == command.id)
              .firstOrNull;
          if (polled == null) return command;
          return B21LabCommand(
            id: command.id,
            displayName: command.displayName,
            description: command.description,
            running: polled.running,
            actionLabel: polled.actionLabel,
            supported: command.supported,
            minVersion: command.minVersion,
            isCustom: command.isCustom,
          );
        }).toList(),
        loadErrorText: detail.loadErrorText,
        noticeText: '',
      );
    });
  }

  void _showNotice(String notice) {
    if (notice.isEmpty || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(notice)),
    );
  }

  void _download() {
    try {
      _api.downloadLabItem(widget.name);
    } catch (_) {}
  }

  void _uninstall() {
    try {
      _api.uninstallLabItem(widget.name);
    } catch (_) {}
  }

  void _openTerminal() {
    try {
      _api.openLabTerminal(widget.name);
    } catch (_) {}
  }

  void _addCommand() {
    try {
      _api.addLabCommand(widget.name);
    } catch (_) {}
  }

  void _refreshCommands() {
    _api.refreshLabCommands(widget.name).then((B21LabDetail detail) {
      if (!mounted) return;
      _applyDetail(detail);
    }).catchError((Object e) {
      if (!mounted) return;
      _showNotice(e.toString());
    });
  }

  void _openSheet(B21LabCommand command) {
    try {
      _api.openLabCommandSheet(widget.name, command.id);
    } catch (_) {}
  }

  void _toggleCommand(B21LabCommand command) {
    try {
      _api.toggleLabCommand(widget.name, command.id);
    } catch (_) {}
  }

  void _editCommand(B21LabCommand command) {
    try {
      _api.editLabCommand(widget.name, command.id);
    } catch (_) {}
  }

  Future<void> _showPackageSettings() async {
    B21LabSettingsForm form;
    try {
      form = await _api.getLabSettingsForm(widget.name);
    } catch (e) {
      _showNotice(e.toString());
      return;
    }
    // One controller per row, created once for the whole dialog (see
    // the lab settings dialog: rebuilding on a switch toggle must
    // not reset typed text or the cursor).
    final Map<String, TextEditingController> controllers =
        <String, TextEditingController>{};
    final Map<String, bool> switches = <String, bool>{};
    for (final B21LabSettingRow row in form.rows) {
      if (row.type == 'switch') {
        switches[row.key] = row.value == 'true' || row.value == '1';
      } else {
        controllers[row.key] = TextEditingController(text: row.value);
      }
    }
    int lastPickEpoch = form.pickEpoch;
    String? pickingRowKey;
    Timer? settingsPickTimer;
    final bool? saved = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter setDialogState) {
            settingsPickTimer ??=
                Timer.periodic(const Duration(milliseconds: 500), (_) async {
              if (pickingRowKey == null || !mounted) return;
              try {
                final B21LabSettingsForm current =
                    await _api.getLabSettingsForm(widget.name);
                if (current.pickEpoch != lastPickEpoch &&
                    current.pickState == 'idle') {
                  lastPickEpoch = current.pickEpoch;
                  final String? key = pickingRowKey;
                  pickingRowKey = null;
                  if (current.pickValue.isNotEmpty && key != null) {
                    // A cancelled picker leaves pickValue empty (or
                    // unchanged) — native leaves the field unchanged
                    // too.
                    setDialogState(() {
                      controllers[key]?.text = current.pickValue;
                    });
                  }
                }
              } catch (_) {}
            });
            return AlertDialog(
              title: Text(form.title),
              content: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: form.rows.map((B21LabSettingRow row) {
                    if (row.type == 'switch') {
                      return SwitchListTile(
                        title: Text(row.name),
                        subtitle: row.hint.isEmpty
                            ? null
                            : Text(row.hint),
                        value: switches[row.key] ?? false,
                        onChanged: (bool value) => setDialogState(
                          () => switches[row.key] = value,
                        ),
                      );
                    }
                    if (row.type == 'select') {
                      final String current =
                          controllers[row.key]?.text ?? row.value;
                      return DropdownButtonFormField<String>(
                        initialValue: row.options.any(
                          (B21LabSettingOption o) => o.value == current,
                        )
                            ? current
                            : null,
                        decoration: InputDecoration(
                          labelText: row.name,
                          hintText: row.hint,
                        ),
                        items: row.options
                            .map(
                              (B21LabSettingOption option) =>
                                  DropdownMenuItem<String>(
                                value: option.value,
                                child: Text(option.label),
                              ),
                            )
                            .toList(),
                        onChanged: (String? value) {
                          if (value == null) return;
                          controllers[row.key]?.text = value;
                        },
                      );
                    }
                    final bool isDirectory = row.type == 'directory';
                    return Padding(
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: Row(
                        children: [
                          Expanded(
                            child: TextField(
                              controller: controllers[row.key],
                              keyboardType: row.type == 'number'
                                  ? TextInputType.number
                                  : TextInputType.text,
                              maxLines: row.type == 'text_multiline' ? 4 : 1,
                              decoration: InputDecoration(
                                labelText: row.name,
                                hintText: row.hint,
                              ),
                            ),
                          ),
                          if (isDirectory)
                            IconButton(
                              icon: const Icon(Icons.folder_open),
                              onPressed: () {
                                pickingRowKey = row.key;
                                try {
                                  _api.requestLabSettingDirectory();
                                } catch (_) {}
                              },
                            ),
                        ],
                      ),
                    );
                  }).toList(),
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(false),
                  child: Text(form.cancelLabel),
                ),
                FilledButton(
                  onPressed: () async {
                    final List<B21LabSettingValue> values =
                        form.rows.map((B21LabSettingRow row) {
                      final String value = row.type == 'switch'
                          ? (switches[row.key] == true ? 'true' : 'false')
                          : (controllers[row.key]?.text ?? row.value);
                      return B21LabSettingValue(
                        key: row.key,
                        value: value,
                      );
                    }).toList();
                    try {
                      final B21LabDetail detail = await _api
                          .saveLabSettingsForm(widget.name, values);
                      if (!mounted) return;
                      Navigator.of(context).pop(true);
                      _applyDetail(detail);
                    } catch (e) {
                      if (!mounted) return;
                      _showNotice(e.toString());
                    }
                  },
                  child: Text(form.saveLabel),
                ),
              ],
            );
          },
        );
      },
    );
    settingsPickTimer?.cancel();
    for (final TextEditingController controller in controllers.values) {
      controller.dispose();
    }
    if (saved != true) return;
  }

  void _export() {
    try {
      _api.exportLabPackage(widget.name);
    } catch (_) {}
  }

  Color _statusColor(String kind) {
    switch (kind) {
      case 'running':
        return Colors.green;
      case 'installed':
        return Colors.blue;
      case 'not_installed':
      default:
        return Colors.orange;
    }
  }

  Widget _packageIcon(String iconUrl) {
    const Widget placeholder = CircleAvatar(
      radius: 28,
      child: Icon(Icons.extension, size: 28),
    );
    if (iconUrl.isEmpty) return placeholder;
    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: Image.network(
        iconUrl,
        width: 56,
        height: 56,
        fit: BoxFit.cover,
        errorBuilder: (_, __, ___) => placeholder,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final B21LabShell? shell = _shell;
    final B21LabDetail? detail = _detail;
    Widget body;
    if (_loading) {
      body = const Center(child: CircularProgressIndicator());
    } else if (_errorText != null) {
      body = Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_errorText!),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: _refresh,
              child: Text(shell?.retryLabel ?? ''),
            ),
          ],
        ),
      );
    } else if (detail == null) {
      body = Center(child: Text(shell?.emptyText ?? ''));
    } else {
      body = SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _packageIcon(detail.iconUrl),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        detail.versionText,
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 4),
                      if (detail.info.isNotEmpty) Text(detail.info),
                      const SizedBox(height: 8),
                      Chip(
                        label: Text(detail.statusText),
                        backgroundColor: _statusColor(detail.statusKind),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            if (detail.downloadVisible)
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: _download,
                  child: Text(detail.downloadLabel),
                ),
              ),
            if (detail.uninstallVisible) ...[
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton(
                  onPressed: _uninstall,
                  child: Text(shell?.uninstallLabel ?? ''),
                ),
              ),
            ],
            const SizedBox(height: 24),
            Row(
              children: [
                Expanded(
                  child: Text(
                    shell?.commandsTitle ?? '',
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.terminal),
                  tooltip: shell?.terminalLabel,
                  onPressed: _openTerminal,
                ),
                IconButton(
                  icon: const Icon(Icons.add),
                  tooltip: shell?.addCommandLabel,
                  onPressed: _addCommand,
                ),
                IconButton(
                  icon: const Icon(Icons.refresh),
                  tooltip: shell?.refreshCommandsLabel,
                  onPressed: _refreshCommands,
                ),
              ],
            ),
            const SizedBox(height: 8),
            if (detail.commands.isEmpty)
              Center(
                child: Padding(
                  padding: const EdgeInsets.all(32),
                  child: Text(shell?.noCommandsText ?? ''),
                ),
              )
            else
              ...detail.commands.map((B21LabCommand command) {
                return Card(
                  margin: const EdgeInsets.symmetric(vertical: 4),
                  child: ListTile(
                    leading: Container(
                      width: 10,
                      height: 10,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: command.running
                            ? Colors.green
                            : Colors.grey,
                      ),
                    ),
                    title: Text(command.displayName),
                    subtitle: command.running
                        ? Text(shell?.terminalLabel ?? '')
                        : null,
                    trailing: FilledButton.tonal(
                      onPressed: () => _toggleCommand(command),
                      child: Text(command.actionLabel),
                    ),
                    onTap: () => _openSheet(command),
                    onLongPress: () => _editCommand(command),
                  ),
                );
              }),
          ],
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: Text(detail?.title ?? widget.name),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: widget.onBack,
        ),
        actions: [
          if (detail != null && detail.hasSettings) ...[
            IconButton(
              icon: const Icon(Icons.file_upload),
              tooltip: shell?.exportLabel,
              onPressed: _export,
            ),
            IconButton(
              icon: const Icon(Icons.settings),
              tooltip: shell?.settingsMenuLabel,
              onPressed: _showPackageSettings,
            ),
          ],
        ],
      ),
      body: body,
    );
  }
}
