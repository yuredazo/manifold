import 'package:flutter/material.dart';

const _iconTile = 34.0;
const _rowPadding = 14.0;
const _iconGap = 12.0;
const _panelRadius = 12.0;

// The stock grey is too faint on this theme, so secondary text is dimmed from the main color instead.
Color secondaryText(BuildContext context) => Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.72);

Color _controlLine(BuildContext context) => Theme.of(context).colorScheme.outline.withValues(alpha: 0.5);

class StatusDot extends StatelessWidget {
  const StatusDot(this.on, {super.key});

  final bool on;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      width: 8,
      height: 8,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: on ? colors.primary : null,
        border: on ? null : Border.all(color: colors.outline, width: 1.5),
      ),
    );
  }
}

class Group extends StatelessWidget {
  const Group({required this.children, this.title, this.meta, this.online, this.action, super.key});

  final List<Widget> children;
  final String? title;
  final String? meta;
  final bool? online;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final line = theme.colorScheme.outlineVariant;
    return Material(
      color: Colors.transparent,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(_panelRadius), side: BorderSide(color: line)),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (title != null) ...[
            Container(
              color: theme.colorScheme.surfaceContainer,
              padding: EdgeInsets.symmetric(horizontal: _rowPadding, vertical: action == null ? 10 : 6),
              constraints: const BoxConstraints(minHeight: 40),
              child: Row(
                children: [
                  if (online != null) ...[StatusDot(online!), const SizedBox(width: 8)],
                  Expanded(child: Text(title!, style: theme.textTheme.labelLarge, maxLines: 1, overflow: TextOverflow.ellipsis)),
                  if (meta != null) Text(meta!, style: theme.textTheme.labelMedium?.copyWith(color: secondaryText(context))),
                  ?action,
                ],
              ),
            ),
            Divider(height: 1, color: line),
          ],
          for (var i = 0; i < children.length; i++) ...[
            if (i > 0) Divider(height: 1, color: line),
            children[i],
          ],
        ],
      ),
    );
  }
}

class Notice extends StatelessWidget {
  const Notice({required this.icon, required this.child, this.error = false, super.key});

  final IconData icon;
  final Widget child;
  final bool error;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final tone = error ? colors.error : colors.tertiary;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: tone.withValues(alpha: 0.08),
        border: Border.all(color: tone.withValues(alpha: 0.45)),
        borderRadius: BorderRadius.circular(_panelRadius),
      ),
      child: Row(
        children: [
          Icon(icon, size: 20, color: tone),
          const SizedBox(width: 12),
          Expanded(child: child),
        ],
      ),
    );
  }
}

class IconBadge extends StatelessWidget {
  const IconBadge(this.icon, {this.active = false, super.key});

  final IconData icon;
  final bool active;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      width: _iconTile,
      height: _iconTile,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: active ? colors.primary.withValues(alpha: 0.5) : _controlLine(context)),
      ),
      child: Icon(icon, size: 18, color: active ? colors.primary : colors.onSurfaceVariant),
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
      leading: icon == null ? null : IconBadge(icon!, active: value),
      onTap: () => onChanged(!value),
      trailing: ExcludeFocus(child: IgnorePointer(child: Switch(value: value, onChanged: (_) {}))),
    );
  }
}

Future<T?> showDetails<T>(BuildContext context, {required String title, required WidgetBuilder builder, String? status, Color? statusColor}) {
  return showDialog<T>(
    context: context,
    builder: (context) {
      final theme = Theme.of(context);
      return Dialog(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480, maxHeight: 640),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 16, 8, 8),
                child: Row(
                  children: [
                    Expanded(child: Text(title, style: theme.textTheme.titleLarge, maxLines: 1, overflow: TextOverflow.ellipsis)),
                    if (status != null) Text(status, style: theme.textTheme.labelLarge?.copyWith(color: statusColor)),
                    IconButton(tooltip: 'Close', onPressed: () => Navigator.pop(context), icon: const Icon(Icons.close)),
                  ],
                ),
              ),
              Flexible(child: SingleChildScrollView(padding: const EdgeInsets.fromLTRB(16, 0, 16, 18), child: builder(context))),
            ],
          ),
        ),
      );
    },
  );
}
