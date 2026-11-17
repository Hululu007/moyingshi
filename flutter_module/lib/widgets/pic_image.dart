import 'dart:convert';

import 'package:material_ui/material_ui.dart';

/// B8 B2: shared card image (see docs/b8-full-migration-plan.md §4.3/§4.7).
///
/// Image.network with the host-resolved URL and headers. On failure it
/// tries the host fallback URL once, then shows the first-character
/// placeholder. This is the C2 history-card image logic, extracted
/// verbatim so later batches share it; the C2 history page keeps its own
/// inline copy (frozen, never refactored before joint acceptance).
///
/// Known gaps (same as C2): no disk cache (Image.network is memory-only),
/// `data:` URLs show the placeholder, and image load success/failure
/// does not update TmdbProxy.RouteSelector health signals.
class PicImage extends StatefulWidget {
  const PicImage({
    super.key,
    required this.picUrl,
    required this.picReferer,
    required this.picCookie,
    required this.picUserAgent,
    required this.picExtraHeadersJson,
    required this.fallbackPicUrl,
    required this.placeholderText,
    required this.placeholderColor,
    required this.height,
  });

  final String picUrl;
  final String picReferer;
  final String picCookie;
  final String picUserAgent;
  final String picExtraHeadersJson;
  final String fallbackPicUrl;
  final String placeholderText;
  final int placeholderColor;
  final double height;

  @override
  State<PicImage> createState() => _PicImageState();
}

class _PicImageState extends State<PicImage> {
  bool _triedFallback = false;

  Map<String, String> _headers() {
    final Map<String, String> headers = <String, String>{};
    if (widget.picExtraHeadersJson.isNotEmpty) {
      try {
        final Object? decoded = jsonDecode(widget.picExtraHeadersJson);
        if (decoded is Map<String, dynamic>) {
          decoded.forEach((key, value) {
            if (value != null) headers[key] = value.toString();
          });
        }
      } catch (_) {
        // Malformed extras are ignored; the named headers still apply.
      }
    }
    if (widget.picReferer.isNotEmpty) headers['Referer'] = widget.picReferer;
    if (widget.picCookie.isNotEmpty) headers['Cookie'] = widget.picCookie;
    if (widget.picUserAgent.isNotEmpty) {
      headers['User-Agent'] = widget.picUserAgent;
    }
    return headers;
  }

  Widget _placeholder() {
    return Container(
      width: double.infinity,
      height: widget.height,
      color: Color(widget.placeholderColor),
      alignment: Alignment.center,
      child: Text(
        widget.placeholderText,
        style: const TextStyle(color: Colors.white, fontSize: 32),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final String url = _triedFallback ? widget.fallbackPicUrl : widget.picUrl;
    if (url.isEmpty) return _placeholder();
    return Container(
      color: Theme.of(context).colorScheme.surfaceContainer,
      child: Image.network(
        url,
        headers: _headers(),
        fit: BoxFit.cover,
        width: double.infinity,
        height: widget.height,
        errorBuilder: (context, error, stackTrace) {
          if (!_triedFallback && widget.fallbackPicUrl.isNotEmpty) {
            // setState during build is not allowed; retry with the
            // fallback URL on the next frame.
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (mounted) setState(() => _triedFallback = true);
            });
          }
          return _placeholder();
        },
      ),
    );
  }
}
