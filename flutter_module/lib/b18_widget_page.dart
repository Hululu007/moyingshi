import 'dart:async';

import 'package:material_ui/material_ui.dart';

import 'pigeon/b18_widget_messages.g.dart';
import 'pigeon/c1_messages.g.dart';
import 'widgets/pic_image.dart';

/// B8 B18: the mobile widget browse page (native WidgetBrowseActivity,
/// 417 lines) as the `/widgets?widgetId=` route on the shared C1
/// engine (onGenerateRoute, B17 pattern).
///
/// One widget's non-category modules as horizontal chips, its visible
/// params as horizontal chips (enumeration opens a single-choice
/// dialog, string opens a text input dialog — both Dart-side, values
/// go back to the host), and its items as a paged grid (column count
/// from the host, 150dp poster, name + remark). Item tap searches the
/// title in the current site (host). All JavaScript runs host-side
/// (WidgetHost, QuickJS); Dart never executes scripts and takes no JS
/// dependency. Data arrives as @async snapshots (host completes
/// exactly once per call, 35s watchdog); the page-1 cache-first rule
/// is the same as native: a cached snapshot is shown first, then
/// reloadBrowsePage fetches fresh.
///
/// Layout mirrors native activity_widget_browse.xml /
/// adapter_widget_grid_item.xml / adapter_widget_chip.xml: toolbar +
/// two horizontal chip rows + grid with 8dp side padding, 150dp
/// poster with 16dp corners, 12sp name and 12sp remark (onSurface
/// Variant), one line each. The toolbar refresh action and the
/// pull-to-refresh both force a fresh page-1 load (native menu
/// refresh); resumed refetch follows plan §4.6.
class B18BrowsePage extends StatefulWidget {
  const B18BrowsePage({super.key, required this.widgetId});

  /// Widget id from the route query (native intent extra "widgetId").
  final String widgetId;

  @override
  State<B18BrowsePage> createState() => _B18BrowsePageState();
}

class _B18BrowsePageState extends State<B18BrowsePage>
    with WidgetsBindingObserver {
  final B18HostApi _api = B18HostApi();
  final C1HostApi _c1Api = C1HostApi();
  final ScrollController _scrollController = ScrollController();

  B18BrowseShell? _shell;
  B18BrowseScreen? _screen;
  final List<B18BrowseItem> _items = <B18BrowseItem>[];
  int _page = 1;
  bool _hasMore = false;
  int _columnCount = 3;
  bool _loading = true;
  bool _loadingMore = false;
  bool _reloading = false;
  String? _errorText;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _scrollController.addListener(_onScroll);
    _loadInitial();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    // Tell the host to destroy its WidgetHost + executor; a stale
    // engine must not keep the browse session alive.
    unawaited(_api.disposeBrowse().catchError((_) {}));
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Plan §4.6: refetch on resumed (native has no onResume refetch;
    // see docs/b8-b18-widget.md V9). Only when a list is already
    // shown; the initial load covers the fresh case.
    if (state == AppLifecycleState.resumed && _items.isNotEmpty) {
      _reload(forceRefresh: false);
    }
  }

  void _onScroll() {
    // Native loads the next page when the last visible item is within
    // 6 of the end; Dart approximates with a near-bottom threshold.
    if (!_scrollController.hasClients) return;
    final double remaining =
        _scrollController.position.maxScrollExtent -
        _scrollController.position.pixels;
    if (remaining < 600) _loadMore();
  }

  Future<void> _loadInitial() async {
    try {
      final B18BrowseShell shell = await _api.getBrowseShell();
      if (!mounted) return;
      setState(() => _shell = shell);
    } catch (_) {
      // Chrome is static text; the snapshot carries its own error
      // state, so a shell failure does not block the page.
    }
    try {
      final B18BrowseScreen screen = await _api.getBrowse(widget.widgetId);
      if (!mounted) return;
      _applyPageOne(screen);
      // Cache-first completion: show the cached list now, then fetch
      // fresh once (native applyItems(cache) + fresh invoke). The
      // reload's own fromCache fallback does not re-trigger this.
      if (screen.fromCache && screen.errorText.isEmpty) {
        _reload(forceRefresh: false);
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _errorText = e.toString();
      });
    }
  }

  /// Applies a page-1 snapshot: replaces the list (or shows the error
  /// / empty state when the list is empty).
  void _applyPageOne(B18BrowseScreen screen) {
    setState(() {
      _loading = false;
      _screen = screen;
      _columnCount = screen.columnCount;
      _page = screen.page;
      _hasMore = screen.hasMore;
      if (screen.errorText.isNotEmpty && screen.items.isEmpty) {
        _errorText = screen.errorText;
        _items.clear();
      } else {
        _errorText = null;
        _items
          ..clear()
          ..addAll(screen.items);
      }
    });
  }

  Future<void> _reload({required bool forceRefresh}) async {
    if (_loading || _reloading) return;
    setState(() => _reloading = true);
    try {
      final B18BrowseScreen screen =
          await _api.reloadBrowsePage(forceRefresh);
      if (!mounted) return;
      if (screen.errorText.isNotEmpty && _items.isNotEmpty) {
        // Native keeps the list silently on a page-1 failure; Dart
        // shows the same failure as a transient message instead.
        _showMessage(screen.errorText);
        setState(() {
          _screen = screen;
          _hasMore = screen.hasMore;
        });
      } else {
        _applyPageOne(screen);
      }
    } catch (e) {
      if (!mounted) return;
      if (_items.isNotEmpty) _showMessage(e.toString());
    } finally {
      if (mounted) setState(() => _reloading = false);
    }
  }

  Future<void> _loadMore() async {
    if (_loading || _loadingMore || _reloading || !_hasMore) return;
    setState(() => _loadingMore = true);
    try {
      final B18BrowseScreen screen =
          await _api.loadMoreBrowsePage(_page + 1);
      if (!mounted) return;
      if (screen.errorText.isNotEmpty) {
        _showMessage(screen.errorText);
      } else if (screen.page > _page) {
        setState(() {
          _items.addAll(screen.items);
          _page = screen.page;
          _hasMore = screen.hasMore;
        });
      }
    } catch (e) {
      if (!mounted) return;
      _showMessage(e.toString());
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  Future<void> _selectModule(String functionName) async {
    if (_loading || _reloading) return;
    setState(() => _loading = true);
    try {
      final B18BrowseScreen screen =
          await _api.selectBrowseModule(functionName);
      if (!mounted) return;
      _applyPageOne(screen);
      if (screen.fromCache && screen.errorText.isEmpty) {
        _reload(forceRefresh: false);
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        if (_items.isEmpty) _errorText = e.toString();
      });
      if (_items.isNotEmpty) _showMessage(e.toString());
    }
  }

  Future<void> _onParamTap(B18ParamItem param) async {
    if (_loading || _reloading) return;
    if (param.type == 'enumeration' && param.options.isNotEmpty) {
      final B18ParamOption? chosen = await showDialog<B18ParamOption>(
        context: context,
        builder: (BuildContext context) {
          return AlertDialog(
            title: Text(param.title),
            content: SingleChildScrollView(
              child: RadioGroup<String>(
                groupValue: param.value,
                onChanged: (String? value) {
                  if (value == null) return;
                  for (final B18ParamOption option in param.options) {
                    if (option.value == value) {
                      Navigator.of(context).pop(option);
                      return;
                    }
                  }
                },
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    for (final B18ParamOption option in param.options)
                      RadioListTile<String>(
                        title: Text(option.title),
                        value: option.value,
                      ),
                  ],
                ),
              ),
            ),
            actions: <Widget>[
              TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: Text(_shell?.negativeLabel ?? ''),
              ),
            ],
          );
        },
      );
      if (chosen == null || chosen.value == param.value || !mounted) return;
      await _setParamAndReload(param.name, chosen.value);
    } else if (param.type == 'string') {
      final TextEditingController controller =
          TextEditingController(text: param.value);
      final String? submitted = await showDialog<String>(
        context: context,
        builder: (BuildContext context) {
          return AlertDialog(
            title: Text(param.title),
            content: TextField(
              controller: controller,
              autofocus: true,
              maxLines: 1,
              decoration: InputDecoration(hintText: param.hint),
            ),
            actions: <Widget>[
              TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: Text(_shell?.negativeLabel ?? ''),
              ),
              FilledButton(
                onPressed: () =>
                    Navigator.of(context).pop(controller.text.trim()),
                child: Text(_shell?.positiveLabel ?? ''),
              ),
            ],
          );
        },
      );
      controller.dispose();
      if (submitted == null || submitted == param.value || !mounted) return;
      await _setParamAndReload(param.name, submitted);
    }
  }

  Future<void> _setParamAndReload(String name, String value) async {
    try {
      await _api.setBrowseParam(name, value);
    } catch (_) {
      return;
    }
    // Native param change reloads page 1 (cache read + fresh invoke).
    _reload(forceRefresh: false);
  }

  void _showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  Future<void> _onBack() async {
    // Root-back signal: the host history decides (pop / home /
    // background).
    try {
      await _c1Api.closeScreen();
    } catch (_) {
      // The host screen is already gone; nothing more to do.
    }
  }

  @override
  Widget build(BuildContext context) {
    final B18BrowseScreen? screen = _screen;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, Object? result) {
        if (didPop) return;
        _onBack();
      },
      child: Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: _onBack,
        ),
        title: Text(screen?.title ?? ''),
        actions: <Widget>[
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: _shell?.refreshLabel,
            onPressed: _loading
                ? null
                : () => _reload(forceRefresh: true),
          ),
        ],
      ),
      body: _buildBody(screen),
    ),
    );
  }

  Widget _buildBody(B18BrowseScreen? screen) {
    if (_loading && _items.isEmpty && _errorText == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_errorText != null && _items.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Text(
                _errorText!,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: _loading ? null : _loadInitial,
                child: Text(_shell?.retryLabel ?? ''),
              ),
            ],
          ),
        ),
      );
    }
    if (_items.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            screen?.emptyText ?? '',
            textAlign: TextAlign.center,
          ),
        ),
      );
    }
    return Column(
      children: <Widget>[
        if (screen != null && screen.modules.isNotEmpty)
          _buildChipRow(
            children: <Widget>[
              for (final B18ModuleItem module in screen.modules)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: ChoiceChip(
                    label: Text(module.title),
                    selected: module.selected,
                    onSelected: (_) {
                      if (!module.selected) _selectModule(module.functionName);
                    },
                  ),
                ),
            ],
          ),
        if (screen != null && screen.params.isNotEmpty)
          _buildChipRow(
            children: <Widget>[
              for (final B18ParamItem param in screen.params)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: ChoiceChip(
                    label: Text('${param.title}：${param.displayValue}'),
                    selected: false,
                    onSelected: (_) => _onParamTap(param),
                  ),
                ),
            ],
          ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: () => _reload(forceRefresh: true),
            child: GridView.builder(
              controller: _scrollController,
              padding: const EdgeInsets.fromLTRB(8, 8, 8, 8),
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: _columnCount,
                crossAxisSpacing: 8,
                mainAxisSpacing: 8,
                // Native item: 150dp poster + 4dp gap + 12sp name +
                // 12sp remark (see docs/b8-b18-widget.md V6).
                mainAxisExtent: 188,
              ),
              itemCount: _items.length + (_loadingMore ? 1 : 0),
              itemBuilder: (BuildContext context, int index) {
                if (index >= _items.length) {
                  return const Center(child: CircularProgressIndicator());
                }
                return _buildItem(_items[index]);
              },
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildChipRow({required List<Widget> children}) {
    return SizedBox(
      height: 48,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 8),
        children: children,
      ),
    );
  }

  Widget _buildItem(B18BrowseItem item) {
    return InkWell(
      onTap: () {
        // Fire-and-forget; the host opens the search page.
        unawaited(_api.openBrowseItem(item.title).catchError((_) {}));
      },
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: PicImage(
              picUrl: item.picUrl,
              picReferer: item.picReferer,
              picCookie: item.picCookie,
              picUserAgent: item.picUserAgent,
              picExtraHeadersJson: item.picExtraHeadersJson,
              fallbackPicUrl: item.fallbackPicUrl,
              placeholderText: item.placeholderText,
              placeholderColor: item.placeholderColor,
              height: 150,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            item.title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 12),
          ),
          if (item.remarksVisible)
            Text(
              item.remarks,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 12,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
        ],
      ),
    );
  }
}
