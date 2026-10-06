import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:url_launcher/url_launcher.dart';

import 'autostart.dart';
import '../core/page_frame.dart';
import '../core/rows.dart';
import 'settings.dart';
import '../update/updater.dart';

class AboutPage extends StatelessWidget {
  const AboutPage({required this.settings, required this.updater, required this.autostart, super.key});

  final Settings settings;
  final Updater updater;
  final Autostart autostart;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = secondaryText(context);
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
                Text('Hub for Windows  \u00b7  version ${updater.currentVersion}', style: theme.textTheme.bodyMedium?.copyWith(color: muted)),
              ],
            ),
          ],
        ),
        const SizedBox(height: 16),
        Text(
          'Shows the feeds your paired devices share, with their sound, and shares your own windows with them. '
          'Everything between two devices is encrypted, and a device has to be paired before it can see anything.',
          style: theme.textTheme.bodyMedium?.copyWith(color: muted),
        ),
        const SectionLabel('Updates'),
        _UpdatePanel(settings: settings, updater: updater),
        const SectionLabel('Sharing'),
        Group(
          children: [
            ListenableBuilder(
              listenable: settings,
              builder: (context, _) => SwitchRow(
                title: 'Offer cameras in the Share list',
                icon: Icons.videocam_outlined,
                value: settings.offerCameras,
                onChanged: (on) => settings.offerCameras = on,
              ),
            ),
          ],
        ),
        const SectionLabel('Window'),
        Group(
          children: [
            ListenableBuilder(
              listenable: settings,
              builder: (context, _) => SwitchRow(
                title: 'Keep running in the tray when the window is closed',
                icon: Icons.web_asset_outlined,
                value: settings.closeToTray,
                onChanged: (on) => settings.closeToTray = on,
              ),
            ),
            ListenableBuilder(
              listenable: autostart,
              builder: (context, _) => SwitchRow(
                title: 'Start with Windows, hidden in the tray',
                icon: Icons.power_settings_new,
                value: autostart.enabled,
                onChanged: autostart.set,
              ),
            ),
          ],
        ),
        const SectionLabel('Project'),
        Group(
          children: [
            ListRow(
              title: 'Source code and security notes',
              subtitle: 'github.com/yuredazo/manifold',
              leading: const IconBadge(Icons.code),
              trailing: const Icon(Icons.open_in_new),
              onTap: () => launchUrl(Uri.parse('https://github.com/yuredazo/manifold')),
            ),
          ],
        ),
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
        return Group(
          children: [
            _StatusRow(updater: updater),
            if (state is Downloading || state is Installing) LinearProgressIndicator(value: state is Downloading ? state.fraction : null, minHeight: 3),
            SwitchRow(
              title: 'Check for updates on launch',
              icon: Icons.update,
              value: settings.checkOnLaunch,
              onChanged: (on) => settings.checkOnLaunch = on,
            ),
          ],
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
    final colors = Theme.of(context).colorScheme;
    final version = updater.currentVersion;
    final state = updater.state;
    final checkedAt = updater.checkedAt;

    final (IconData icon, String title, String detail, Color? titleColor) = switch (state) {
      Idle() => (Icons.system_update_alt, 'Version $version', 'Not checked yet', null),
      Checking() => (Icons.sync, 'Checking for updates', '', null),
      UpToDate() => (Icons.check_circle_outline, 'Up to date', '$version${checkedAt == null ? '' : '  \u00b7  checked ${_clock(checkedAt)}'}', null),
      Available(:final release) => (Icons.arrow_circle_up, 'Version ${release.version} is available', 'now $version', null),
      Downloading(:final release, :final fraction) => (Icons.sync, 'Downloading ${release.version}', fraction == null ? '' : '${(fraction * 100).round()}%', null),
      Installing(:final release) => (Icons.sync, 'Installing ${release.version}', 'Manifold restarts by itself', null),
      UpdateFailed(:final message, :final installing) => (Icons.error_outline, installing ? 'Update failed' : 'Update check failed', message, colors.error),
    };

    return ListRow(
      title: title,
      titleColor: titleColor,
      subtitle: detail.isEmpty ? null : detail,
      subtitleMaxLines: 2,
      leading: IconBadge(icon),
      trailing: Row(mainAxisSize: MainAxisSize.min, children: _actions(state)),
    );
  }

  List<Widget> _actions(UpdateState state) {
    return switch (state) {
      Available(:final release) => [
          TextButton(onPressed: () => launchUrl(release.page), child: const Text('Release page')),
          const SizedBox(width: 4),
          FilledButton(onPressed: updater.install, child: const Text('Download and restart')),
        ],
      Checking() || Downloading() || Installing() => const [],
      UpdateFailed() => [FilledButton.tonal(onPressed: updater.check, child: const Text('Try again'))],
      UpToDate() => [TextButton(onPressed: updater.check, child: const Text('Check again'))],
      Idle() => [FilledButton.tonal(onPressed: updater.check, child: const Text('Check now'))],
    };
  }

  static String _clock(DateTime time) => '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
}
