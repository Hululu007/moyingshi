import 'dart:async';

import 'package:material_ui/material_ui.dart';

import 'b10_collect_page.dart';
import 'pigeon/b10_collect_messages.g.dart';
import 'pigeon/b9_search_messages.g.dart';
import 'pigeon/c1_messages.g.dart';

/// B8 B9: Flutter version of the mobile search input page
/// (SearchActivity + SearchFragment, 132 + 479 lines), as a top-level
/// route `/search` on the shared C1 engine (plan §4.4, B8 V1/V2 route
/// pattern: the entry params travel URL-encoded in the route query).
///
/// The input owns its state in a Dart TextField (plan §4.5): the host
/// returns the initial snapshot once, Dart filters nothing locally —
/// hot words and suggest words are @async host calls, debounced 300ms
/// like the native text watcher. Back closes the host screen via
/// C1HostApi.closeScreen (C2/B2 pattern).
///
/// Results stay native in this batch: submit opens the native results
/// page in direct mode (SearchActivity.direct) on top of this screen,
/// so back from results returns to the input page, like the native
/// fragment back stack. The Flutter results list is B10. With the B10
/// flag on, _submit pushes the Flutter results page in-family instead
/// (see _submit); the direct-mode path stays for the flag-off case.
///
/// Scope: all-sites / current-site only. The native scope popup also
/// lists site groups, but direct mode has no group slot, so groups are
/// not offered until B10 restores them (see docs/b8-b9-search.md V5).
/// The site button opens the native SiteDialog (search mode) through
/// the host; a site tap only dismisses it, exactly like native — the
/// dialog's per-row searchable toggles are its real function here.
class B9SearchPage extends StatefulWidget {
  const B9SearchPage({
    super.key,
    required this.keyword,
    required this.siteKey,
    required this.pic,
    required this.wallPic,
  });

  /// Entry params from the /search route query (URL-encoded by the
  /// host; empty when the entry carried none). Passed back to the host
  /// on submit.
  final String keyword;
  final String siteKey;
  final String pic;
  final String wallPic;

  @override
  State<B9SearchPage> createState() => _B9SearchPageState();
}

class _B9SearchPageState extends State<B9SearchPage>
    with WidgetsBindingObserver {
  final B9HostApi _api = B9HostApi();
  final B10HostApi _b10Api = B10HostApi();
  final C1HostApi _c1Api = C1HostApi();
  B9SearchScreen? _info;
  String? _error;
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();
  Timer? _debounce;
  List<String> _records = const [];
  List<String> _hotTv = const [];
  List<String> _hotMovie = const [];
  List<String> _hotVariety = const [];
  List<String> _suggest = const [];
  bool _showHot = true;
  bool _currentSite = false;
  bool _currentSiteAvailable = false;
  int _suggestSeq = 0;
  bool _autoSubmitted = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _controller.text = widget.keyword;
    _showHot = widget.keyword.trim().isEmpty;
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _debounce?.cancel();
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Coming back from the native results page: the submit added the
    // keyword to the records 250ms later (like native), so re-read
    // them. Nothing else can have changed while the results page was
    // on top.
    if (state == AppLifecycleState.resumed) _reloadRecords();
  }

  Future<void> _load() async {
    try {
      final B9SearchScreen info = await _api.getSearchScreen(
        widget.keyword,
        widget.siteKey,
        widget.pic,
        widget.wallPic,
      );
      if (!mounted) return;
      setState(() {
        _info = info;
        _error = null;
        _records = info.records;
        _hotTv = info.hotTv;
        _hotMovie = info.hotMovie;
        _hotVariety = info.hotVariety;
        _currentSite = info.initialCurrentSite;
        _currentSiteAvailable = info.currentSiteAvailable;
      });
      _refreshHot();
      if (_showHot) return;
      // Opened with a keyword: fetch its suggestions behind the
      // auto-submitted results page, so the list is ready on return.
      _fetchSuggest(widget.keyword.trim());
      // Native SearchFragment auto-submits when it opens with a keyword
      // (initView -> search()); do the same once, on first load only,
      // after the first frame so the page exists underneath.
      if (!_autoSubmitted) {
        _autoSubmitted = true;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) _submit();
        });
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '加载失败：$e');
    }
  }

  Future<void> _reloadRecords() async {
    try {
      final List<String> records = await _api.getRecords();
      if (!mounted) return;
      setState(() => _records = records);
    } catch (_) {
      // Records stay as they were; the page remains usable.
    }
  }

  Future<void> _refreshHot() async {
    try {
      final B9HotWords hot = await _api.refreshHotWords();
      if (!mounted) return;
      setState(() {
        _hotTv = hot.tv;
        _hotMovie = hot.movie;
        _hotVariety = hot.variety;
      });
    } catch (_) {
      // Cached groups stay visible; a failed refresh is silent like
      // native (its per-group callbacks just never fire).
    }
  }

  void _onQueryChanged(String _) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () {
      final String query = _controller.text.trim();
      if (query.isEmpty) {
        // Drop any in-flight suggest results; show hot words again and
        // refresh them, like the native empty-query path (getHot()).
        _suggestSeq++;
        setState(() {
          _suggest = const [];
          _showHot = true;
        });
        _refreshHot();
        return;
      }
      setState(() => _showHot = false);
      _fetchSuggest(query);
    });
  }

  /// One suggest fetch with a sequence guard: a newer keystroke (or a
  /// cleared query) invalidates the result, mirroring the native
  /// mSuggestSeq check in SearchFragment.setSuggestAdapter.
  void _fetchSuggest(String query) {
    final int seq = ++_suggestSeq;
    _api.getSuggest(query).then((B9SuggestResult result) {
      if (!mounted || seq != _suggestSeq) return;
      setState(() => _suggest = result.words);
    }).catchError((_) {});
  }

  /// Submits the current query: records it optimistically (dedupe,
  /// most recent first, max 10 — RecordAdapter.checkToAdd), then opens
  /// the results page. The results page joins this /search family: it
  /// is pushed on this engine's Navigator, so back / edit returns to
  /// this input page with the keyword intact — the B9 V8 "result" edit
  /// callback restored (see docs/b8-b10-collect.md V8; the keyboard pop
  /// itself is not carried over). The record is persisted by the B10
  /// host (250ms delayed, like native); the entry siteKey only counts
  /// under current-site scope.
  Future<void> _submit() async {
    final String keyword = _controller.text.trim();
    if (keyword.isEmpty) return;
    final List<String> records = List<String>.from(_records);
    records.remove(keyword);
    records.insert(0, keyword);
    if (records.length > 10) records.removeRange(10, records.length);
    setState(() => _records = records);
    _focusNode.unfocus();
    try {
      await _b10Api.recordSearch(keyword);
    } catch (_) {
      // The record stays in memory only; the page still opens.
    }
    if (!mounted) return;
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => B10CollectPage(
          keyword: keyword,
          currentSite: _currentSite,
          siteKey: _currentSite ? widget.siteKey : '',
          group: '',
          pic: widget.pic,
          wallPic: widget.wallPic,
          historyResumeCid: -1,
          historyResumeKey: '',
          historyResumeTargetCid: -1,
        ),
      ),
    );
  }

  /// Hot / record / suggest tap: fill the query and submit, like the
  /// native tap handlers (setKeyword + search()).
  void _onWordTap(String text) {
    _controller.text = text;
    _controller.selection =
        TextSelection.fromPosition(TextPosition(offset: text.length));
    _submit();
  }

  Future<void> _onRecordLongPress(String text) async {
    try {
      await _api.deleteRecord(text);
    } catch (_) {
      // The record stays; the page remains usable.
      return;
    }
    if (!mounted) return;
    setState(
      () => _records = _records.where((String r) => r != text).toList(),
    );
  }

  void _onScopePicked(String value) {
    final B9SearchScreen? info = _info;
    if (info == null) return;
    if (value == 'current') {
      if (!_currentSiteAvailable) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(info.notSearchableMessage)),
        );
        return;
      }
      setState(() => _currentSite = true);
    } else {
      setState(() => _currentSite = false);
    }
  }

  Future<void> _onSite() async {
    _focusNode.unfocus();
    try {
      final bool available = await _api.openSiteDialog();
      if (!mounted) return;
      setState(() => _currentSiteAvailable = available);
    } catch (_) {
      // The dialog failed to open; the page stays usable.
    }
  }

  Future<void> _onBack() async {
    await _c1Api.closeScreen();
  }

  @override
  Widget build(BuildContext context) {
    final B9SearchScreen? info = _info;
    if (_error != null) {
      return Scaffold(
        appBar: AppBar(
          leading: IconButton(
            icon: const Icon(Icons.arrow_back),
            onPressed: _onBack,
          ),
        ),
        body: Center(child: Text(_error!)),
      );
    }
    if (info == null) {
      return Scaffold(
        appBar: AppBar(
          leading: IconButton(
            icon: const Icon(Icons.arrow_back),
            onPressed: _onBack,
          ),
        ),
        body: const Center(child: CircularProgressIndicator()),
      );
    }
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
          title: TextField(
            controller: _controller,
            focusNode: _focusNode,
            // Native shows the keyboard when the page opens with an
            // empty keyword (checkKeyword); with a keyword it
            // auto-submits instead.
            autofocus: widget.keyword.isEmpty,
            textInputAction: TextInputAction.search,
            maxLength: 255,
            decoration: InputDecoration(
              hintText: info.hintText,
              border: InputBorder.none,
              counterText: '',
            ),
            onChanged: _onQueryChanged,
            onSubmitted: (_) => _submit(),
          ),
          actions: [
            // Current scope as the button label, mirroring the native
            // scope menu item title (search_scope_all /
            // search_scope_current).
            PopupMenuButton<String>(
              onSelected: _onScopePicked,
              itemBuilder: (BuildContext context) => <PopupMenuEntry<String>>[
                PopupMenuItem<String>(
                  value: 'all',
                  child: Text(info.allScopeLabel),
                ),
                PopupMenuItem<String>(
                  value: 'current',
                  child: Text(info.currentScopeLabel),
                ),
              ],
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Center(
                  child: Text(
                    _currentSite
                        ? info.currentScopeLabel
                        : info.allScopeLabel,
                    style: const TextStyle(fontSize: 14),
                  ),
                ),
              ),
            ),
            IconButton(
              icon: const Icon(Icons.public_outlined),
              onPressed: _onSite,
            ),
          ],
        ),
        body: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (_records.isNotEmpty) ...[
                _sectionTitle(info.recordTitle),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (final String record in _records)
                      _chip(
                        record,
                        fontSize: 14,
                        onTap: () => _onWordTap(record),
                        onLongPress: () => _onRecordLongPress(record),
                      ),
                  ],
                ),
                const SizedBox(height: 16),
              ],
              if (_showHot) ...[
                _sectionTitle(info.hotTitle),
                _hotGroup(info.hotTvTitle, _hotTv),
                _hotGroup(info.hotMovieTitle, _hotMovie),
                _hotGroup(info.hotVarietyTitle, _hotVariety),
              ] else ...[
                _sectionTitle(info.suggestTitle),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (final String word in _suggest)
                      _chip(word, fontSize: 14, onTap: () => _onWordTap(word)),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _sectionTitle(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text(text, style: const TextStyle(fontSize: 18)),
    );
  }

  /// One hot-word group (title + chips); hidden when the group is
  /// empty. Native keeps the empty title visible, but an empty group
  /// carries no information (see docs/b8-b9-search.md V8).
  Widget _hotGroup(String title, List<String> words) {
    if (words.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: 4),
          child: Text(title, style: const TextStyle(fontSize: 16)),
        ),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final String word in words)
              _chip(word, fontSize: 13, onTap: () => _onWordTap(word)),
          ],
        ),
        const SizedBox(height: 12),
      ],
    );
  }

  /// One word chip, mirroring adapter_search_word.xml /
  /// adapter_search_record.xml (14sp, rounded background) and
  /// adapter_search_hot_word.xml (13sp, 32dp chip height). The
  /// background is the theme's surface container; the native chips use
  /// shape_item_round / shape_search_hot_word on the wallpaper color.
  Widget _chip(
    String text, {
    required VoidCallback onTap,
    VoidCallback? onLongPress,
    double fontSize = 14,
  }) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      onLongPress: onLongPress,
      borderRadius: BorderRadius.circular(16),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: scheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Text(
          text,
          style: TextStyle(fontSize: fontSize),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ),
    );
  }
}
