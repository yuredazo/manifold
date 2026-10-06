import 'package:flutter/material.dart';

import '../core/page_frame.dart';
import '../core/rows.dart';
import '../network/network.dart';
import '../network/protocol/control.dart';
import '../network/protocol/devices.dart';
import 'viewer.dart';
import 'watching.dart';

class WatchPage extends StatelessWidget {
  const WatchPage(this.network, this.watching, {required this.openDevices, super.key});

  final Network network;
  final Watching watching;
  final VoidCallback openDevices;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([network, watching]),
      builder: (context, _) {
        final paired = network.book.devices.value;
        final receiving = paired.where((device) => device.receive).toList();
        if (paired.isEmpty) {
          return _Elsewhere(
            icon: Icons.devices_outlined,
            title: 'No device is paired',
            message: 'Pair a phone or another computer to watch what it shares.',
            button: 'Pair a device',
            onPressed: openDevices,
          );
        }
        if (receiving.isEmpty) {
          return _Elsewhere(
            icon: Icons.visibility_off_outlined,
            title: 'Watching is off',
            message: 'Switch on "Watch its feeds" for a device to see what it shares.',
            button: 'Open Devices',
            onPressed: openDevices,
          );
        }
        return PageFrame(
          title: 'Watch',
          children: [for (final device in receiving) _DeviceFeeds(network, watching, device)],
        );
      },
    );
  }
}

class _Elsewhere extends StatelessWidget {
  const _Elsewhere({required this.icon, required this.title, required this.message, required this.button, required this.onPressed});

  final IconData icon;
  final String title;
  final String message;
  final String button;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return PageFrame(
      title: 'Watch',
      children: [
        EmptyState(
          icon: icon,
          title: title,
          message: message,
          action: FilledButton(onPressed: onPressed, child: Text(button)),
        ),
      ],
    );
  }
}

class _DeviceFeeds extends StatelessWidget {
  const _DeviceFeeds(this.network, this.watching, this.device);

  final Network network;
  final Watching watching;
  final Device device;

  @override
  Widget build(BuildContext context) {
    final online = network.online.contains(device.publicKey);
    final feeds = network.remoteFeeds[device.publicKey] ?? const <FeedInfo>[];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionLabel(online ? device.name : '${device.name} (offline)'),
        Group(
          children: [
            if (!online)
              const ListRow(title: 'It is offline', subtitle: 'Its feeds appear here when it is reachable.', leading: IconBadge(Icons.cloud_off_outlined))
            else if (feeds.isEmpty)
              const ListRow(title: 'Nothing shared right now', leading: IconBadge(Icons.hourglass_empty))
            else
              for (final feed in feeds) _FeedRow(watching, device, feed),
          ],
        ),
      ],
    );
  }
}

class _FeedRow extends StatelessWidget {
  const _FeedRow(this.watching, this.device, this.feed);

  final Watching watching;
  final Device device;
  final FeedInfo feed;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final session = watching.of(device, feed);
    final details = feed.soundOnly
        ? 'sound only'
        : [
            if (feed.width > 0 && feed.height > 0) '${feed.width}x${feed.height}',
            if (feed.fps > 0) '${feed.fps} fps',
            if (feed.hasAudio) 'with sound',
          ].join(' · ');
    final icon = feed.soundOnly ? Icons.volume_up_outlined : Icons.videocam_outlined;
    if (session == null) {
      return ListRow(
        title: feed.label,
        subtitle: details.isEmpty ? null : details,
        leading: IconBadge(icon),
        trailing: Icon(feed.soundOnly ? Icons.play_circle_outline : Icons.open_in_new),
        onTap: () => watching.watch(device, feed),
      );
    }
    return ValueListenableBuilder<ViewerStats>(
      valueListenable: session.stats,
      builder: (context, stats, _) => ListRow(
        title: feed.label,
        subtitle: switch ((feed.soundOnly, stats.frames == 0)) {
          (true, true) => 'Waiting for the sound',
          (true, false) => 'Playing, click to stop',
          (false, true) => 'Waiting for the first picture',
          (false, false) => 'Playing in its window, click to close',
        },
        leading: IconBadge(icon, badge: colors.primary),
        trailing: const Icon(Icons.close),
        onTap: () => watching.stop(session),
      ),
    );
  }
}
