import 'package:flutter/material.dart';

const _iconColumn = 40.0;
const _rowPadding = 16.0;
const _iconGap = 12.0;

/// The stock grey is too faint on this dark theme, so secondary text is dimmed from the main color instead.
Color secondaryText(BuildContext context) => Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.72);

class SectionLabel extends StatelessWidget {
  const SectionLabel(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 24, 16, 8),
      child: Text(text, style: Theme.of(context).textTheme.labelLarge?.copyWith(color: secondaryText(context))),
    );
  }
}

/// Rows that belong together sit in one rounded container, with an inset divider between them.
class Group extends StatelessWidget {
  const Group({required this.children, this.indented = true, super.key});

  final List<Widget> children;

  /// Dividers start under the text instead of under the icon when rows have an [IconBadge].
  final bool indented;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Material(
      color: colors.surfaceContainer,
      borderRadius: BorderRadius.circular(16),
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          for (var i = 0; i < children.length; i++) ...[
            if (i > 0)
              Divider(
                height: 1,
                indent: indented ? _rowPadding + _iconColumn + _iconGap : _rowPadding,
                color: colors.outlineVariant.withValues(alpha: 0.6),
              ),
            children[i],
          ],
        ],
      ),
    );
  }
}

/// A neutral circle with an icon. [badge] adds a small status dot, used for online and offline.
class IconBadge extends StatelessWidget {
  const IconBadge(this.icon, {this.badge, super.key});

  final IconData icon;
  final Color? badge;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return SizedBox(
      width: _iconColumn,
      height: _iconColumn,
      child: Stack(
        children: [
          Container(
            width: _iconColumn,
            height: _iconColumn,
            decoration: BoxDecoration(shape: BoxShape.circle, color: colors.surfaceContainerHighest),
            child: Icon(icon, size: 20, color: colors.onSurfaceVariant),
          ),
          if (badge != null)
            Positioned(
              right: 0,
              bottom: 0,
              child: Container(
                width: 12,
                height: 12,
                decoration: BoxDecoration(shape: BoxShape.circle, color: badge, border: Border.all(color: colors.surfaceContainer, width: 2)),
              ),
            ),
        ],
      ),
    );
  }
}

class ListRow extends StatelessWidget {
  const ListRow({
    required this.title,
    this.subtitle,
    this.subtitleMaxLines,
    this.titleColor,
    this.leading,
    this.trailing,
    this.onTap,
    this.below,
    super.key,
  });

  final String title;
  final String? subtitle;
  final int? subtitleMaxLines;
  final Color? titleColor;
  final Widget? leading;
  final Widget? trailing;
  final VoidCallback? onTap;
  final List<Widget>? below;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final body = ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 56),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: _rowPadding, vertical: 10),
        child: Row(
          children: [
            if (leading != null) ...[leading!, const SizedBox(width: _iconGap)],
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: theme.textTheme.titleSmall?.copyWith(color: titleColor), maxLines: 1, overflow: TextOverflow.ellipsis),
                  if (subtitle != null)
                    Text(
                      subtitle!,
                      style: theme.textTheme.bodySmall?.copyWith(color: secondaryText(context)),
                      maxLines: subtitleMaxLines,
                      overflow: subtitleMaxLines == null ? null : TextOverflow.ellipsis,
                    ),
                  ...?below,
                ],
              ),
            ),
            if (trailing != null) ...[const SizedBox(width: 8), trailing!],
          ],
        ),
      ),
    );
    return onTap == null ? body : InkWell(onTap: onTap, child: body);
  }
}

/// The whole row toggles, so the target is large without the row being tall.
class SwitchRow extends StatelessWidget {
  const SwitchRow({required this.title, required this.value, required this.onChanged, this.subtitle, this.icon, super.key});

  final String title;
  final bool value;
  final ValueChanged<bool> onChanged;
  final String? subtitle;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    return ListRow(
      title: title,
      subtitle: subtitle,
      leading: icon == null ? null : IconBadge(icon!),
      onTap: () => onChanged(!value),
      trailing: ExcludeFocus(child: IgnorePointer(child: Switch(value: value, onChanged: (_) {}))),
    );
  }
}

/// A window for one thing and the controls that belong to it, the desktop counterpart of the phone's bottom sheet.
Future<T?> showDetails<T>(BuildContext context, {required String title, required WidgetBuilder builder, String? status, Color? statusColor}) {
  return showDialog<T>(
    context: context,
    builder: (context) {
      final theme = Theme.of(context);
      return Dialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480, maxHeight: 640),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 20, 12, 8),
                child: Row(
                  children: [
                    Expanded(child: Text(title, style: theme.textTheme.titleLarge, maxLines: 1, overflow: TextOverflow.ellipsis)),
                    if (status != null) Text(status, style: theme.textTheme.labelLarge?.copyWith(color: statusColor)),
                    IconButton(tooltip: 'Close', onPressed: () => Navigator.pop(context), icon: const Icon(Icons.close)),
                  ],
                ),
              ),
              Flexible(child: SingleChildScrollView(padding: const EdgeInsets.fromLTRB(16, 0, 16, 20), child: builder(context))),
            ],
          ),
        ),
      );
    },
  );
}
