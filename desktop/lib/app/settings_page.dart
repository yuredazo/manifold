import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:url_launcher/url_launcher.dart';

import 'autostart.dart';
import '../core/page_frame.dart';
import '../core/rows.dart';
import 'settings.dart';
import '../update/updater.dart';

const _repo = 'https://github.com/yuredazo/manifold';

class SettingsPage extends StatelessWidget {
  const SettingsPage({required this.settings, required this.updater, required this.autostart, super.key});

  final Settings settings;
  final Updater updater;
  final Autostart autostart;

  @override
  Widget build(BuildContext context) {
    return PageFrame(
      title: 'Settings',
      children: [
        _AboutCard(updater),
        ListenableBuilder(
          listenable: Listenable.merge([settings, autostart]),
          builder: (context, _) => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Group(
                      title: 'Sharing',
                      children: [
                        SwitchRow(
                          title: 'Offer cameras',
                          subtitle: 'In the Share list',
                          icon: Icons.videocam_outlined,
                          value: settings.offerCameras,
                          onChanged: (on) => settings.offerCameras = on,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Group(
                      title: 'Updates',
                      children: [
                        SwitchRow(
                          title: 'Check on launch',
                          subtitle: 'Looks for a new version',
                          icon: Icons.update,
                          value: settings.checkOnLaunch,
                          onChanged: (on) => settings.checkOnLaunch = on,
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Group(
                title: 'Window',
                children: [
                  SwitchRow(
                    title: 'Keep running in the tray when the window is closed',
                    icon: Icons.web_asset_outlined,
                    value: settings.closeToTray,
                    onChanged: (on) => settings.closeToTray = on,
                  ),
                  SwitchRow(
                    title: 'Start with Windows, hidden in the tray',
                    icon: Icons.power_settings_new,
                    value: autostart.enabled,
                    onChanged: autostart.set,
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _AboutCard extends StatelessWidget {
  const _AboutCard(this.updater);

  final Updater updater;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: updater,
      builder: (context, _) {
        final theme = Theme.of(context);
        final colors = theme.colorScheme;
        final muted = secondaryText(context);
        final version = updater.currentVersion;
        final state = updater.state;
        final checkedAt = updater.checkedAt;

        final (String status, String? detail) = switch (state) {
          Idle() => ('Not checked yet', null),
          Checking() => ('Checking for updates', null),
          UpToDate() => ('Up to date', checkedAt == null ? null : 'checked ${_clock(checkedAt)}'),
          Available(:final release) => ('Version ${release.version} is available', null),
          Downloading(:final release, :final fraction) => ('Downloading ${release.version}', fraction == null ? null : '${(fraction * 100).round()}%'),
          Installing(:final release) => ('Installing ${release.version}', 'Manifold restarts by itself'),
          UpdateFailed(:final message, :final installing) => (installing ? 'Update failed' : 'Update check failed', message),
        };
        final failed = state is UpdateFailed;

        return Group(
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      SvgPicture.asset('assets/manifold.svg', width: 44, height: 44, colorFilter: ColorFilter.mode(colors.primary, BlendMode.srcIn)),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('Manifold $version', style: theme.textTheme.titleLarge),
                            Text(
                              failed ? status : [status, ?detail].join('  ·  '),
                              style: theme.textTheme.bodySmall?.copyWith(color: failed ? colors.error : muted),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 12),
                      ..._actions(state),
                    ],
                  ),
                  if (failed && detail != null) ...[
                    const SizedBox(height: 8),
                    Text(detail, style: theme.textTheme.bodySmall?.copyWith(color: colors.error)),
                  ],
                  if (state is Downloading || state is Installing) ...[
                    const SizedBox(height: 12),
                    LinearProgressIndicator(value: state is Downloading ? state.fraction : null, minHeight: 3),
                  ],
                  const SizedBox(height: 14),
                  Text(
                    'Shows the feeds your paired devices share, with their sound, and shares your own windows with them. '
                    'Everything between two devices is encrypted, and a device has to be paired before it can see anything.',
                    style: theme.textTheme.bodySmall?.copyWith(color: muted),
                  ),
                  const SizedBox(height: 14),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      _LinkButton(Icons.code, 'Source code', _repo),
                      _LinkButton(Icons.description_outlined, 'How it works', '$_repo/blob/main/docs/PROTOCOL.md'),
                      _LinkButton(Icons.bug_report_outlined, 'Report a vulnerability', '$_repo/blob/main/SECURITY.md'),
                      _LinkButton(Icons.gavel_outlined, 'License', '$_repo/blob/main/LICENSE'),
                    ],
                  ),
                ],
              ),
            ),
          ],
        );
      },
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
      UpdateFailed() => [OutlinedButton(onPressed: updater.check, child: const Text('Try again'))],
      UpToDate() => [OutlinedButton.icon(onPressed: updater.check, icon: const Icon(Icons.refresh, size: 18), label: const Text('Check again'))],
      Idle() => [OutlinedButton(onPressed: updater.check, child: const Text('Check now'))],
    };
  }

  static String _clock(DateTime time) => '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
}

class _LinkButton extends StatelessWidget {
  const _LinkButton(this.icon, this.label, this.url);

  final IconData icon;
  final String label;
  final String url;

  @override
  Widget build(BuildContext context) {
    return OutlinedButton.icon(onPressed: () => launchUrl(Uri.parse(url)), icon: Icon(icon, size: 18), label: Text(label));
  }
}
