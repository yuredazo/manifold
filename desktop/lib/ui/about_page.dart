import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:url_launcher/url_launcher.dart';

import '../settings.dart';
import '../updates.dart';
import 'page_frame.dart';

const _rowPadding = EdgeInsets.symmetric(horizontal: 16, vertical: 10);

class AboutPage extends StatelessWidget {
  const AboutPage({required this.settings, required this.updater, super.key});

  final Settings settings;
  final Updater updater;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    return PageFrame(
      title: 'About',
      children: [
        Row(
          children: [
            SvgPicture.asset('assets/manifold.svg', width: 56, height: 56),
            const SizedBox(width: 16),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Manifold', style: theme.textTheme.titleLarge),
                Text('Hub for Windows  ·  version ${updater.currentVersion}', style: theme.textTheme.bodyMedium?.copyWith(color: muted)),
              ],
            ),
          ],
        ),
        const SizedBox(height: 24),
        const Text(
          'Shows the feeds your paired devices share, with their sound, and shares your own windows with them. '
          'Pair a device on the Devices page, then use Watch or Share.',
        ),
        const SizedBox(height: 12),
        Text('Everything between two devices is encrypted, and a device has to be paired before it can see anything.', style: TextStyle(color: muted)),
        const SizedBox(height: 28),
        const SectionTitle('Updates'),
        _UpdatePanel(settings: settings, updater: updater),
        const SizedBox(height: 20),
        const SectionTitle('Window'),
        Panel(
          padding: EdgeInsets.zero,
          child: ListenableBuilder(
            listenable: settings,
            builder: (context, _) => _ToggleRow(
              label: 'Keep running in the tray when the window is closed',
              value: settings.closeToTray,
              onChanged: (on) => settings.closeToTray = on,
            ),
          ),
        ),
        const SizedBox(height: 24),
        const SelectableText('Source code and security notes: github.com/yuredazo/manifold'),
      ],
    );
  }
}

class _UpdatePanel extends StatelessWidget {
  const _UpdatePanel({required this.settings, required this.updater});

  final Settings settings;
  final Updater updater;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([settings, updater]),
      builder: (context, _) {
        final state = updater.state;
        return Panel(
          padding: EdgeInsets.zero,
          highlighted: state is Available,
          child: Column(
            children: [
              _StatusRow(updater: updater),
              if (state is Downloading || state is Installing) LinearProgressIndicator(value: state is Downloading ? state.fraction : null, minHeight: 3),
              const Divider(height: 1),
              _ToggleRow(label: 'Check for updates on launch', value: settings.checkOnLaunch, onChanged: (on) => settings.checkOnLaunch = on),
            ],
          ),
        );
      },
    );
  }
}

class _StatusRow extends StatelessWidget {
  const _StatusRow({required this.updater});

  final Updater updater;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final version = updater.currentVersion;
    final state = updater.state;
    final checkedAt = updater.checkedAt;

    final (Widget leading, String title, String detail, Color titleColor) = switch (state) {
      Idle() => (Icon(Icons.system_update_alt, size: 20, color: colors.onSurfaceVariant), 'Version $version', 'Not checked yet', colors.onSurface),
      Checking() => (const _Spinner(), 'Checking for updates', '', colors.onSurface),
      UpToDate() => (
          Icon(Icons.check_circle_outline, size: 20, color: colors.primary),
          'Up to date',
          '$version${checkedAt == null ? '' : '  ·  checked ${_clock(checkedAt)}'}',
          colors.onSurface,
        ),
      Available(:final release) => (Icon(Icons.arrow_circle_up, size: 20, color: colors.primary), 'Version ${release.version} is available', 'now $version', colors.onSurface),
      Downloading(:final release, :final fraction) => (
          const _Spinner(),
          'Downloading ${release.version}',
          fraction == null ? '' : '${(fraction * 100).round()}%',
          colors.onSurface,
        ),
      Installing(:final release) => (const _Spinner(), 'Installing ${release.version}', 'Manifold restarts by itself', colors.onSurface),
      UpdateFailed(:final message, :final installing) => (
          Icon(Icons.error_outline, size: 20, color: colors.error),
          installing ? 'Update failed' : 'Update check failed',
          message,
          colors.error,
        ),
    };

    return Padding(
      padding: _rowPadding,
      child: Row(
        children: [
          SizedBox(width: 20, height: 20, child: Center(child: leading)),
          const SizedBox(width: 12),
          Expanded(
            child: Text.rich(
              TextSpan(
                children: [
                  TextSpan(text: title, style: TextStyle(color: titleColor)),
                  if (detail.isNotEmpty) TextSpan(text: '   $detail', style: theme.textTheme.bodySmall?.copyWith(color: colors.onSurfaceVariant)),
                ],
              ),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const SizedBox(width: 12),
          ..._actions(state),
        ],
      ),
    );
  }

  List<Widget> _actions(UpdateState state) {
    const compact = VisualDensity.compact;
    return switch (state) {
      Available(:final release) => [
          TextButton(style: TextButton.styleFrom(visualDensity: compact), onPressed: () => launchUrl(release.page), child: const Text('Release page')),
          const SizedBox(width: 4),
          FilledButton(style: FilledButton.styleFrom(visualDensity: compact), onPressed: updater.install, child: const Text('Download and restart')),
        ],
      Checking() || Downloading() || Installing() => const [],
      UpdateFailed() => [FilledButton.tonal(style: FilledButton.styleFrom(visualDensity: compact), onPressed: updater.check, child: const Text('Try again'))],
      UpToDate() => [TextButton(style: TextButton.styleFrom(visualDensity: compact), onPressed: updater.check, child: const Text('Check again'))],
      Idle() => [FilledButton.tonal(style: FilledButton.styleFrom(visualDensity: compact), onPressed: updater.check, child: const Text('Check now'))],
    };
  }

  static String _clock(DateTime time) => '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
}

class _ToggleRow extends StatelessWidget {
  const _ToggleRow({required this.label, required this.value, required this.onChanged});

  final String label;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: () => onChanged(!value),
      child: Padding(
        padding: _rowPadding,
        child: Row(
          children: [
            Expanded(child: Text(label)),
            const SizedBox(width: 12),
            Transform.scale(
              scale: 0.8,
              child: Switch(value: value, onChanged: onChanged, materialTapTargetSize: MaterialTapTargetSize.shrinkWrap),
            ),
          ],
        ),
      ),
    );
  }
}

class _Spinner extends StatelessWidget {
  const _Spinner();

  @override
  Widget build(BuildContext context) => const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2));
}
