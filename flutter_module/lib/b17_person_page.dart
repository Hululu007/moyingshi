import 'dart:convert';

import 'package:material_ui/material_ui.dart';

import 'pigeon/b17_person_messages.g.dart';
import 'widgets/pic_image.dart';

/// B8 B17: the mobile TMDB person page (TmdbPersonActivity), as the
/// '/person' route on the shared C1 engine. Person data, photos and
/// works are fetched and parsed 100% host-side; Dart only renders the
/// snapshot the host returns. The photo viewer is implemented in Dart
/// (swipe to change photo, tap zones: left third previous, right third
/// next, center dismiss, long-press to save). See
/// docs/b8-b17-person.md.
class B17PersonPage extends StatefulWidget {
  const B17PersonPage({
    super.key,
    required this.personId,
    required this.name,
    required this.subtitle,
    required this.profileUrl,
    required this.department,
    required this.biography,
    required this.siteKey,
    required this.detailMode,
  });

  /// TMDB person id from the route query.
  final int personId;

  /// Initial person fields from the route query (the intent extras the
  /// native page shows before its TMDB request returns).
  final String name;
  final String subtitle;
  final String profileUrl;
  final String department;
  final String biography;

  /// Entry site key + detail mode, stored host-side as the session for
  /// opening works (native openWork needs both).
  final String siteKey;
  final int detailMode;

  @override
  State<B17PersonPage> createState() => _B17PersonPageState();
}

class _B17PersonPageState extends State<B17PersonPage>
    with WidgetsBindingObserver {
  final B17HostApi _api = B17HostApi();
  B17PersonShell? _shell;
  B17PersonScreen? _screen;
  String? _error;
  String _countText = '';
  String _filter = 'all';
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadShell();
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The native page loads once in initView; the resumed refetch
    // (plan §4.6) also covers coming back after the TMDB config
    // changed elsewhere. TmdbService caches the person request for
    // 7 days, so the refetch is cheap when nothing changed.
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _loadShell() async {
    try {
      final B17PersonShell shell = await _api.getPersonShell();
      if (!mounted) return;
      setState(() => _shell = shell);
    } catch (_) {
      // The shell stays null; section titles fall back to empty and
      // the page still renders from the snapshot.
    }
  }

  Future<void> _load() async {
    if (_loading) return;
    setState(() => _loading = true);
    try {
      final B17PersonScreen screen = await _api.getPerson(
        widget.personId,
        widget.siteKey,
        widget.detailMode,
      );
      if (!mounted) return;
      setState(() {
        _screen = screen;
        _error = null;
        if (!screen.filters.any((B17FilterItem f) => f.key == _filter)) {
          _filter = 'all';
        }
      });
      _refreshCountText();
    } catch (e) {
      if (!mounted) return;
      // Channel breakage only: the host timeout path completes with an
      // error screen instead (B8 precedent). Same prefix as B2.
      setState(() => _error = '加载失败：$e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  /// Local filter, mirroring native TmdbPersonActivity.filteredWorks:
  /// cast/crew/director pick the source list, movie/tv filter by
  /// mediaType, director additionally checks the credit string.
  List<B17WorkItem> get _filteredWorks {
    final B17PersonScreen? screen = _screen;
    if (screen == null) return <B17WorkItem>[];
    final List<B17WorkItem> source;
    switch (_filter) {
      case 'cast':
        source = screen.castWorks;
        break;
      case 'crew':
      case 'director':
        source = screen.crewWorks;
        break;
      default:
        source = screen.allWorks;
    }
    return source.where((B17WorkItem w) {
      if (_filter == 'movie') return w.mediaType == 'movie';
      if (_filter == 'tv') return w.mediaType == 'tv';
      if (_filter == 'director') {
        final String credit = w.credit.toLowerCase();
        return credit.contains('director') ||
            credit.contains('directing') ||
            credit.contains('导演');
      }
      return true;
    }).toList();
  }

  Future<void> _refreshCountText() async {
    try {
      final String text =
          await _api.getWorkCountText(_filteredWorks.length);
      if (!mounted) return;
      setState(() => _countText = text);
    } catch (_) {
      // The count line stays empty; the list still renders.
    }
  }

  void _setFilter(String key) {
    if (_filter == key) return;
    setState(() => _filter = key);
    _refreshCountText();
  }

  Future<void> _openViewer(int index) async {
    final B17PersonScreen? screen = _screen;
    if (screen == null || screen.photos.isEmpty) return;
    await showDialog<void>(
      context: context,
      barrierColor: Colors.black,
      builder: (BuildContext context) {
        return Dialog(
          backgroundColor: Colors.black,
          insetPadding: EdgeInsets.zero,
          child: _PhotoViewer(
            photos: screen.photos,
            initialIndex: index,
            saveLabel: _shell?.saveLabel ?? '',
            api: _api,
          ),
        );
      },
    );
  }

  Widget _placeholderBox(double width, double height, String text) {
    return Container(
      width: width,
      height: height,
      color: Colors.grey.shade300,
      alignment: Alignment.center,
      child: Text(
        text,
        style: const TextStyle(color: Colors.white, fontSize: 32),
      ),
    );
  }

  /// Profile photo before the first snapshot arrives: the raw route
  /// URL, no host-resolved headers yet (native setInitialPerson shows
  /// the intent photo the same way, via ImgUtil.load).
  Widget _initialPhoto() {
    if (widget.profileUrl.isEmpty) {
      return _placeholderBox(120, 170, _initialPlaceholder());
    }
    return SizedBox(
      width: 120,
      height: 170,
      child: Image.network(
        widget.profileUrl,
        fit: BoxFit.cover,
        errorBuilder: (context, error, stackTrace) =>
            _placeholderBox(120, 170, _initialPlaceholder()),
      ),
    );
  }

  String _initialPlaceholder() {
    if (widget.name.isEmpty) return '！';
    return widget.name.substring(0, 1);
  }

  Widget _headerCard() {
    final B17PersonScreen? screen = _screen;
    final String name = screen != null && screen.loaded
        ? screen.name
        : widget.name;
    final String subtitle = screen != null && screen.loaded
        ? screen.subtitle
        : widget.subtitle;
    final String biography = screen != null && screen.loaded
        ? screen.biography
        : widget.biography;
    final B17PersonShell? shell = _shell;
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: screen != null && screen.loaded
                    ? SizedBox(
                        width: 120,
                        child: PicImage(
                          picUrl: screen.profilePicUrl,
                          picReferer: screen.profilePicReferer,
                          picCookie: screen.profilePicCookie,
                          picUserAgent: screen.profilePicUserAgent,
                          picExtraHeadersJson:
                              screen.profilePicExtraHeadersJson,
                          fallbackPicUrl: screen.profilePicFallbackUrl,
                          placeholderText: screen.profilePicPlaceholderText,
                          placeholderColor:
                              screen.profilePicPlaceholderColor,
                          height: 170,
                        ),
                      )
                    : _initialPhoto(),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(
                      name,
                      style: const TextStyle(
                        fontSize: 23,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    if (subtitle.isNotEmpty) ...<Widget>[
                      const SizedBox(height: 6),
                      Text(
                        subtitle,
                        style: const TextStyle(fontSize: 12),
                      ),
                    ],
                    if (screen != null && screen.scoreVisible) ...<Widget>[
                      const SizedBox(height: 12),
                      Text(
                        shell?.scoreTitle ?? '',
                        style: const TextStyle(fontSize: 13),
                      ),
                      Text(
                        screen.scoreValue,
                        style: const TextStyle(
                          fontSize: 24,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      Text(
                        screen.scoreMeta,
                        style: const TextStyle(fontSize: 12),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
          if (biography.isNotEmpty) ...<Widget>[
            const SizedBox(height: 16),
            Text(
              shell?.biographyTitle ?? '',
              style: const TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 6),
            Text(biography, style: const TextStyle(fontSize: 13)),
          ],
        ],
      ),
    );
  }

  Widget _photosSection() {
    final B17PersonScreen? screen = _screen;
    if (screen == null || screen.photos.isEmpty) {
      return const SizedBox.shrink();
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
          child: Text(
            _shell?.photosTitle ?? '',
            style: const TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
        SizedBox(
          height: 142,
          child: ListView.builder(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16),
            itemCount: screen.photos.length,
            itemBuilder: (BuildContext context, int index) {
              final B17PhotoItem photo = screen.photos[index];
              return Padding(
                padding: const EdgeInsets.only(right: 12),
                child: GestureDetector(
                  onTap: () => _openViewer(index),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: SizedBox(
                      width: 92,
                      child: PicImage(
                        picUrl: photo.picUrl,
                        picReferer: photo.picReferer,
                        picCookie: photo.picCookie,
                        picUserAgent: photo.picUserAgent,
                        picExtraHeadersJson: photo.picExtraHeadersJson,
                        fallbackPicUrl: photo.fallbackPicUrl,
                        placeholderText: photo.placeholderText,
                        placeholderColor: photo.placeholderColor,
                        height: 138,
                      ),
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _worksSection() {
    final B17PersonScreen? screen = _screen;
    if (screen == null || !screen.loaded) {
      return const SizedBox.shrink();
    }
    if (screen.filters.isEmpty) {
      return const SizedBox.shrink();
    }
    final List<B17WorkItem> works = _filteredWorks;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
          child: Row(
            children: <Widget>[
              Text(
                _shell?.worksTitle ?? '',
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const Spacer(),
              if (_countText.isNotEmpty)
                Text(_countText, style: const TextStyle(fontSize: 12)),
            ],
          ),
        ),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Row(
            children: screen.filters.map((B17FilterItem f) {
              final bool selected = _filter == f.key;
              return Padding(
                padding: const EdgeInsets.only(right: 8),
                child: ChoiceChip(
                  label: Text(f.label),
                  selected: selected,
                  onSelected: (_) => _setFilter(f.key),
                ),
              );
            }).toList(),
          ),
        ),
        const SizedBox(height: 8),
        ListView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          padding: const EdgeInsets.symmetric(horizontal: 16),
          itemCount: works.length,
          itemBuilder: (BuildContext context, int index) {
            final B17WorkItem work = works[index];
            return InkWell(
              onTap: () => _api.openPersonWork(
                work.tmdbId,
                work.mediaType,
                work.title,
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    ClipRRect(
                      borderRadius: BorderRadius.circular(6),
                      child: SizedBox(
                        width: 72,
                        child: PicImage(
                          picUrl: work.picUrl,
                          picReferer: work.picReferer,
                          picCookie: work.picCookie,
                          picUserAgent: work.picUserAgent,
                          picExtraHeadersJson: work.picExtraHeadersJson,
                          fallbackPicUrl: work.fallbackPicUrl,
                          placeholderText: work.placeholderText,
                          placeholderColor: work.placeholderColor,
                          height: 102,
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: <Widget>[
                          Text(
                            work.title,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                          if (work.subtitle.isNotEmpty) ...<Widget>[
                            const SizedBox(height: 2),
                            Text(
                              work.subtitle,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontSize: 11),
                            ),
                          ],
                          if (work.credit.isNotEmpty) ...<Widget>[
                            const SizedBox(height: 2),
                            Text(
                              work.credit,
                              style: TextStyle(
                                fontSize: 12,
                                color: Colors.green.shade700,
                              ),
                            ),
                          ],
                          if (work.overview.isNotEmpty) ...<Widget>[
                            const SizedBox(height: 2),
                            Text(
                              work.overview,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontSize: 12),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        ),
        const SizedBox(height: 16),
      ],
    );
  }

  /// Status area below the header card. The native page keeps the
  /// initial person info visible on failure (it only hides the
  /// progress bar and toasts); Dart keeps the same header and adds an
  /// explicit error/need-key line with a retry button below it.
  Widget _statusBody() {
    final B17PersonScreen? screen = _screen;
    Widget? status;
    if (_loading && screen == null && _error == null) {
      status = const Padding(
        padding: EdgeInsets.symmetric(vertical: 24),
        child: Center(child: CircularProgressIndicator()),
      );
    } else if (_error != null) {
      status = _statusLine(_error!, _load);
    } else if (screen != null && screen.needKey) {
      status = _statusLine(_shell?.needKeyText ?? '', _load);
    } else if (screen != null && !screen.loaded && screen.errorText.isNotEmpty) {
      status = _statusLine(screen.errorText, _load);
    }
    return SingleChildScrollView(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          _headerCard(),
          if (status != null) status,
          if (status == null) ...<Widget>[
            _photosSection(),
            _worksSection(),
          ],
        ],
      ),
    );
  }

  Widget _statusLine(String text, VoidCallback onRetry) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(text),
          const SizedBox(height: 12),
          FilledButton(
            onPressed: onRetry,
            child: Text(_shell?.retryLabel ?? ''),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final B17PersonScreen? screen = _screen;
    final String title = screen != null && screen.loaded && screen.name.isNotEmpty
        ? screen.name
        : widget.name;
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: Stack(
        children: <Widget>[
          _statusBody(),
          if (_loading && screen != null)
            const Positioned(
              top: 8,
              right: 16,
              child: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
        ],
      ),
    );
  }
}

/// The full-screen photo viewer (native TmdbPersonActivity.showPhotoDialog
/// on mobile): black background, FIT_CENTER image, 38dp progress spinner
/// while loading, tap zones (left third previous, right third next,
/// center dismiss), swipe via PageView, long-press opens the one-item
/// save menu, rotate button top-left, save button top-right.
class _PhotoViewer extends StatefulWidget {
  const _PhotoViewer({
    required this.photos,
    required this.initialIndex,
    required this.saveLabel,
    required this.api,
  });

  final List<B17PhotoItem> photos;
  final int initialIndex;
  final String saveLabel;
  final B17HostApi api;

  @override
  State<_PhotoViewer> createState() => _PhotoViewerState();
}

class _PhotoViewerState extends State<_PhotoViewer> {
  late final PageController _controller;
  late int _index;

  @override
  void initState() {
    super.initState();
    _index = widget.initialIndex;
    _controller = PageController(initialPage: _index);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Map<String, String> _headers(B17PhotoItem photo) {
    final Map<String, String> headers = <String, String>{};
    if (photo.picExtraHeadersJson.isNotEmpty) {
      try {
        final Object? decoded = jsonDecode(photo.picExtraHeadersJson);
        if (decoded is Map<String, dynamic>) {
          decoded.forEach((key, value) {
            if (value != null) headers[key] = value.toString();
          });
        }
      } catch (_) {
        // Malformed extras are ignored; the named headers still apply.
      }
    }
    if (photo.picReferer.isNotEmpty) headers['Referer'] = photo.picReferer;
    if (photo.picCookie.isNotEmpty) headers['Cookie'] = photo.picCookie;
    if (photo.picUserAgent.isNotEmpty) {
      headers['User-Agent'] = photo.picUserAgent;
    }
    return headers;
  }

  void _goTo(int index) {
    if (index < 0 || index >= widget.photos.length) return;
    _controller.animateToPage(
      index,
      duration: const Duration(milliseconds: 250),
      curve: Curves.easeOut,
    );
  }

  void _onTapUp(TapUpDetails details, double width) {
    final double x = details.localPosition.dx;
    if (x < width / 3) {
      _goTo(_index - 1);
    } else if (x > width * 2 / 3) {
      _goTo(_index + 1);
    } else {
      Navigator.of(context).pop();
    }
  }

  Future<void> _onLongPress() async {
    final B17PhotoItem photo = widget.photos[_index];
    final String? chosen = await showDialog<String>(
      context: context,
      builder: (BuildContext context) {
        return SimpleDialog(
          children: <Widget>[
            SimpleDialogOption(
              onPressed: () => Navigator.of(context).pop('save'),
              child: Text(widget.saveLabel),
            ),
          ],
        );
      },
    );
    if (chosen == 'save') {
      widget.api.savePersonPhoto(photo.fullPicUrl);
    }
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (BuildContext context, BoxConstraints constraints) {
        return GestureDetector(
          onTapUp: (TapUpDetails details) =>
              _onTapUp(details, constraints.maxWidth),
          onLongPress: _onLongPress,
          child: Stack(
            children: <Widget>[
              PageView.builder(
                controller: _controller,
                itemCount: widget.photos.length,
                onPageChanged: (int index) {
                  setState(() => _index = index);
                },
                itemBuilder: (BuildContext context, int index) {
                  final B17PhotoItem photo = widget.photos[index];
                  if (photo.fullPicUrl.isEmpty) {
                    return const SizedBox.shrink();
                  }
                  return Image.network(
                    photo.fullPicUrl,
                    headers: _headers(photo),
                    fit: BoxFit.contain,
                    loadingBuilder: (BuildContext context, Widget child,
                        ImageChunkEvent? loadingProgress) {
                      if (loadingProgress == null) return child;
                      return const Center(
                        child: SizedBox(
                          width: 38,
                          height: 38,
                          child: CircularProgressIndicator(
                            color: Colors.white,
                          ),
                        ),
                      );
                    },
                    errorBuilder: (context, error, stackTrace) {
                      return const Center(
                        child: Icon(
                          Icons.image_not_supported,
                          color: Colors.white54,
                          size: 48,
                        ),
                      );
                    },
                  );
                },
              ),
              Positioned(
                top: 8,
                left: 8,
                child: IconButton(
                  icon: const Icon(Icons.screen_rotation,
                      color: Colors.white),
                  onPressed: () => widget.api.togglePhotoOrientation(),
                ),
              ),
              Positioned(
                top: 8,
                right: 8,
                child: IconButton(
                  icon: const Icon(Icons.save, color: Colors.white),
                  onPressed: () => widget.api
                      .savePersonPhoto(widget.photos[_index].fullPicUrl),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}
