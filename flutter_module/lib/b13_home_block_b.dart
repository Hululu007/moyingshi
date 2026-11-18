import 'dart:async';

import 'package:material_ui/material_ui.dart';

import 'pigeon/b13_home_b_messages.g.dart';
import 'widgets/pic_image.dart';

/// B8 B13: the mobile home blocks B (JS widget sections) as a widget
/// inside the /home family (B11 page), not a page.
///
/// The B11 home page mounts this widget below the B12 blocks and passes
/// the same load epoch: the epoch increments every time the B11 shell
/// snapshot loads, so the widget sections reload on resume and after a
/// site switch, but NOT on tab switches. The widget reads the B13 flag
/// itself: off renders the same icon-only placeholder B11 showed, so
/// B11's placeholder visuals survive with the flag off.
///
/// All JavaScript runs host-side (WidgetManager / WidgetHost, QuickJS);
/// Dart never executes scripts and takes no JS dependency. Data arrives
/// as one @async snapshot per epoch (host completes with the best
/// available sections; see the contract). All strings and computed
/// values come from the host; Dart hard-codes no locale strings.
///
/// Layout mirrors native VodFragment's widget sections
/// (adapter_widget_section.xml / adapter_widget_item.xml): 16sp bold
/// section title + colorPrimary "more" (host-resolved), horizontal list
/// of 104dp-wide cards, 104x140dp image with 16dp corners, 12sp name
/// and 12sp remark (onSurfaceVariant), one line each.
class B13WidgetsWidget extends StatefulWidget {
  const B13WidgetsWidget({super.key, required this.epoch});

  /// B11 shell load generation; reload when it changes.
  final int epoch;

  @override
  State<B13WidgetsWidget> createState() => _B13WidgetsWidgetState();
}

class _B13WidgetsWidgetState extends State<B13WidgetsWidget> {
  final B13HostApi _api = B13HostApi();

  B13WidgetScreen? _screen;

  @override
  void initState() {
    super.initState();
    _loadWidgets();
  }

  @override
  void didUpdateWidget(B13WidgetsWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.epoch != oldWidget.epoch) _loadWidgets();
  }

  @override
  void dispose() {
    // Tell the host to destroy its WidgetManager (executor threads);
    // a stale engine must not keep four idle threads per home visit.
    unawaited(_api.disposeWidgets().catchError((_) {}));
    super.dispose();
  }

  Future<void> _loadWidgets() async {
    try {
      final B13WidgetScreen screen = await _api.getWidgets();
      if (!mounted) return;
      setState(() => _screen = screen);
    } catch (_) {
      // Keep the previous sections (or the placeholder); never an
      // error view. The next epoch (resume / site switch) retries.
    }
  }

  @override
  Widget build(BuildContext context) {
    final B13WidgetScreen? screen = _screen;
    if (screen == null) {
      return const _B13BlockPlaceholder(
        icon: Icons.widgets_outlined,
        height: 96,
      );
    }
    if (screen.sections.isEmpty) {
      // No widget delivered: hidden, like native View.GONE
      // (updateWidgetVisibility: visible iff >= 1 section).
      return const SizedBox.shrink();
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final B13WidgetSection section in screen.sections)
          _buildSection(section),
      ],
    );
  }

  Widget _buildSection(B13WidgetSection section) {
    return Padding(
      padding: const EdgeInsets.only(top: 4, bottom: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 16, right: 16),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    section.title,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => _api.openWidgetMore(section.widgetId),
                  child: Padding(
                    padding: const EdgeInsets.all(4),
                    child: Text(
                      section.moreLabel,
                      style: TextStyle(
                        fontSize: 12,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 8),
          SizedBox(
            height: 184,
            child: ListView.builder(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              itemCount: section.items.length,
              itemBuilder: (BuildContext context, int index) {
                final B13WidgetItem item = section.items[index];
                return Container(
                  width: 104,
                  margin: const EdgeInsets.all(4),
                  child: GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: () => _api.openWidgetItem(item.title),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          width: 104,
                          height: 140,
                          child: ClipRRect(
                            borderRadius: BorderRadius.circular(16),
                            child: PicImage(
                              picUrl: item.picUrl,
                              picReferer: item.picReferer,
                              picCookie: item.picCookie,
                              picUserAgent: item.picUserAgent,
                              picExtraHeadersJson:
                                  item.picExtraHeadersJson,
                              fallbackPicUrl: item.fallbackPicUrl,
                              placeholderText: item.placeholderText,
                              placeholderColor: item.placeholderColor,
                              height: 140,
                            ),
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          item.title,
                          style: const TextStyle(fontSize: 12),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        if (item.remarksVisible)
                          Text(
                            item.remarks,
                            style: TextStyle(
                              fontSize: 12,
                              color: Theme.of(context)
                                  .colorScheme
                                  .onSurfaceVariant,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

/// B11's icon-only placeholder copy (same shape as
/// _B12BlockPlaceholder): shown while the flag is off or the first
/// snapshot is still loading.
class _B13BlockPlaceholder extends StatelessWidget {
  const _B13BlockPlaceholder({required this.icon, required this.height});

  final IconData icon;
  final double height;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: Container(
        height: height,
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Center(
          child: Icon(
            icon,
            size: 28,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ),
    );
  }
}
