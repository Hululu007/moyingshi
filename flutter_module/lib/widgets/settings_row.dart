import 'package:material_ui/material_ui.dart';

/// B8 B3: shared settings-row widget for the migrated settings pages.
///
/// Same shape as the C1 subtitle page's private row (Card, 16dp radius,
/// 72dp min height, 48dp icon circle, title + value text + optional
/// chevron) and the native sub-settings rows in
/// fragment_setting_tmdb/ai/ad/danmaku.xml (icon circle + title + value +
/// chevron on dialog rows only). Icons and circle colors are Dart-side
/// presentation choices approximating the native drawables; all titles
/// and values come from the host, never from here.
///
/// Additive-only: later batches may add optional parameters, but must
/// not change the meaning of the existing ones.
class SettingsRow extends StatelessWidget {
  const SettingsRow({
    super.key,
    required this.icon,
    required this.iconBackground,
    required this.title,
    required this.value,
    this.showChevron = true,
    this.isError = false,
    required this.onTap,
    // B8 B4: optional long-press (site-health / WebHome-extension clear
    // rows). Additive-only: existing callers are unaffected.
    this.onLongPress,
  });

  final IconData icon;
  final Color iconBackground;
  final String title;
  final String value;
  final bool showChevron;
  final bool isError;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.only(top: 8),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
      ),
      elevation: 0,
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        borderRadius: BorderRadius.circular(16),
        child: Container(
          constraints: const BoxConstraints(minHeight: 72),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: Row(
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  color: iconBackground,
                  shape: BoxShape.circle,
                ),
                child: Icon(icon, color: Colors.white, size: 24),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Text(
                  title,
                  style: const TextStyle(fontSize: 16),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              const SizedBox(width: 8),
              Flexible(
                child: Text(
                  value,
                  style: TextStyle(
                    color: isError ? Colors.red : scheme.onSurfaceVariant,
                    fontSize: 16,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.end,
                ),
              ),
              if (showChevron) ...[
                const SizedBox(width: 8),
                Icon(
                  Icons.chevron_right,
                  color: scheme.onSurfaceVariant,
                  size: 20,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Section label above a group of settings rows, mirroring the native
/// group-title TextViews in the fragment_setting_*.xml layouts.
class SettingsGroupHeader extends StatelessWidget {
  const SettingsGroupHeader({super.key, required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 16, left: 4, bottom: 4),
      child: Text(
        title,
        style: TextStyle(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          fontSize: 14,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}
