import 'package:material_ui/material_ui.dart';

import 'pic_image.dart';

/// B8 B2: shared Vod grid card (see docs/b8-full-migration-plan.md §4.3).
///
/// Mirrors adapter_vod.xml: rounded image with site badge, an optional
/// 2dp progress bar, and a translucent-black info area with centered
/// name/remarks lines in constant light text. All geometry and strings
/// come from the host; this widget only renders.
///
/// The card is parameterised so the batches sharing this layout can
/// differ exactly where native differs:
/// - remarks: null hides the remarks line entirely (Keep: remark GONE);
///   non-null keeps its space, with [remarksVisible] choosing white vs
///   transparent text (History: remark INVISIBLE when equal to the name).
/// - progress: null hides the 2dp bar (Keep); non-null shows it.
/// - playbackText: null hides the badge; non-null shows it.
/// - siteVisible: false hides the site badge (Keep always shows it).
/// The C2 history page keeps its own inline copy (frozen, never
/// refactored before joint acceptance).
class VodCard extends StatelessWidget {
  const VodCard({
    super.key,
    required this.picUrl,
    required this.picReferer,
    required this.picCookie,
    required this.picUserAgent,
    required this.picExtraHeadersJson,
    required this.fallbackPicUrl,
    required this.placeholderText,
    required this.placeholderColor,
    required this.imageHeight,
    required this.vodName,
    required this.remarks,
    required this.remarksVisible,
    required this.siteName,
    required this.siteVisible,
    required this.playbackText,
    required this.progress,
    required this.deleteMode,
    required this.onTap,
    required this.onLongPress,
  });

  /// Fixed info-area height: two single lines (14sp name + 2dp gap + 12sp
  /// remarks) with 4dp top/bottom padding = 52 (the C2 history value);
  /// one line (name only) = 30. Fixed so the fixed-extent grid can never
  /// overflow; the native height is content-driven and close.
  static double infoHeight({required bool twoLines}) => twoLines ? 52 : 30;

  /// Full card height for the fixed-extent grid: image + optional 2dp
  /// progress bar + info area. Pages must use this so the extent and the
  /// card can never disagree.
  static double totalHeight({
    required double imageHeight,
    required bool hasProgress,
    required bool twoLines,
  }) =>
      imageHeight + (hasProgress ? 2 : 0) + infoHeight(twoLines: twoLines);

  final String picUrl;
  final String picReferer;
  final String picCookie;
  final String picUserAgent;
  final String picExtraHeadersJson;
  final String fallbackPicUrl;
  final String placeholderText;
  final int placeholderColor;
  final double imageHeight;
  final String vodName;
  final String? remarks;
  final bool remarksVisible;
  final String siteName;
  final bool siteVisible;
  final String? playbackText;

  /// Progress bar value 0..1, or null to hide the bar entirely.
  final double? progress;
  final bool deleteMode;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    final bool twoLines = remarks != null;
    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        borderRadius: BorderRadius.circular(8),
        child: Stack(
          children: [
            Column(
              children: [
                SizedBox(
                  height: imageHeight,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      PicImage(
                        picUrl: picUrl,
                        picReferer: picReferer,
                        picCookie: picCookie,
                        picUserAgent: picUserAgent,
                        picExtraHeadersJson: picExtraHeadersJson,
                        fallbackPicUrl: fallbackPicUrl,
                        placeholderText: placeholderText,
                        placeholderColor: placeholderColor,
                        height: imageHeight,
                      ),
                      if (siteVisible)
                        Positioned(
                          top: 0,
                          left: 0,
                          child: ConstrainedBox(
                            constraints: const BoxConstraints(maxWidth: 92),
                            child: Container(
                              padding: const EdgeInsets.all(4),
                              decoration: BoxDecoration(
                                color: scheme.surfaceContainer,
                                borderRadius: const BorderRadius.only(
                                  topLeft: Radius.circular(8),
                                  topRight: Radius.circular(8),
                                  bottomRight: Radius.circular(8),
                                ),
                              ),
                              child: Text(
                                siteName,
                                style: TextStyle(
                                  color: scheme.primary,
                                  fontSize: 12,
                                ),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          ),
                        ),
                      if (!deleteMode && playbackText != null)
                        Positioned(
                          bottom: 0,
                          left: 0,
                          child: ConstrainedBox(
                            constraints: const BoxConstraints(maxWidth: 100),
                            child: Container(
                              padding: const EdgeInsets.all(4),
                              decoration: BoxDecoration(
                                color: scheme.surfaceContainer,
                                borderRadius: const BorderRadius.only(
                                  topRight: Radius.circular(4),
                                  bottomRight: Radius.circular(4),
                                ),
                              ),
                              child: Text(
                                playbackText!,
                                style: TextStyle(
                                  color: scheme.onSurfaceVariant,
                                  fontSize: 12,
                                ),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
                if (progress != null)
                  SizedBox(
                    height: 2,
                    child: LinearProgressIndicator(
                      value: progress!.clamp(0.0, 1.0),
                      backgroundColor: scheme.surfaceContainer,
                      color: scheme.primary,
                    ),
                  ),
                Container(
                  height: infoHeight(twoLines: twoLines),
                  width: double.infinity,
                  color: const Color(0x66000000),
                  padding: const EdgeInsets.fromLTRB(6, 4, 6, 4),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(
                        vodName,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 14,
                        ),
                        textAlign: TextAlign.center,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (twoLines) ...[
                        const SizedBox(height: 2),
                        Text(
                          remarks!,
                          style: TextStyle(
                            color: remarksVisible
                                ? Colors.white
                                : Colors.transparent,
                            fontSize: 12,
                          ),
                          textAlign: TextAlign.center,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ],
                  ),
                ),
              ],
            ),
            if (deleteMode)
              const Positioned.fill(
                child: ColoredBox(
                  color: Colors.black54,
                  child: Icon(
                    Icons.delete,
                    color: Colors.white,
                    size: 48,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
