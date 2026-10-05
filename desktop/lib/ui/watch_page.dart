import 'package:flutter/material.dart';

import '../net/control.dart';
import '../net/devices.dart';
import '../network.dart';
import '../viewer.dart';
import 'page_frame.dart';

class WatchPage extends StatelessWidget {
  const WatchPage(this.network, {required this.openDevices, super.key});

  final Network network;
  final VoidCallback openDevices;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([network, network.watching]),
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
          subtitle: 'What your devices share. A feed opens in a window of its own.',
          children: [for (final device in receiving) _DeviceFeeds(network, device)],
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
  const _DeviceFeeds(this.network, this.device);

  final Network network;
  final Device device;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final online = network.online.contains(device.publicKey);
    final feeds = network.remoteFeeds[device.publicKey] ?? const <FeedInfo>[];
    return Padding(
      padding: const EdgeInsets.only(bottom: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text(device.name, style: theme.textTheme.titleMedium)),
              StatusDot(online: online),
            ],
          ),
          const SizedBox(height: 12),
          if (!online)
            _Note('It is offline. Its feeds appear here when it is reachable.')
          else if (feeds.isEmpty)
            _Note('It is not sharing anything right now.')
          else
            Wrap(
              spacing: 12,
              runSpacing: 12,
              children: [for (final feed in feeds) _FeedTile(network, device, feed)],
            ),
        ],
      ),
    );
  }
}

class _Note extends StatelessWidget {
  const _Note(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Text(text, style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant));
  }
}

class _FeedTile extends StatelessWidget {
  const _FeedTile(this.network, this.device, this.feed);

  final Network network;
  final Device device;
  final FeedInfo feed;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final session = network.watching.of(device, feed);
    final watching = session != null;
    final details = [
      if (feed.width > 0 && feed.height > 0) '${feed.width}x${feed.height}',
      if (feed.fps > 0) '${feed.fps} fps',
      if (feed.hasAudio) 'with sound',
    ].join(' · ');
    return SizedBox(
      width: 240,
      child: Material(
        color: colors.surfaceContainerHigh,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(10),
          side: BorderSide(color: watching ? colors.primary : Colors.transparent),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(10),
          onTap: () => watching ? network.watching.stop(session) : network.watching.watch(device, feed),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(child: Text(feed.name, style: theme.textTheme.titleSmall, maxLines: 1, overflow: TextOverflow.ellipsis)),
                    Icon(watching ? Icons.close : Icons.open_in_new, size: 18, color: colors.onSurfaceVariant),
                  ],
                ),
                if (details.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(details, style: theme.textTheme.labelMedium?.copyWith(color: colors.onSurfaceVariant)),
                ],
                const SizedBox(height: 10),
                if (session == null)
                  Text('Open in a window', style: theme.textTheme.labelMedium?.copyWith(color: colors.primary))
                else
                  ValueListenableBuilder<ViewerStats>(
                    valueListenable: session.stats,
                    builder: (context, stats, _) => Text(
                      stats.frames == 0 ? 'Waiting for the first picture' : 'Playing in its window, click to close',
                      style: theme.textTheme.labelMedium?.copyWith(color: colors.primary),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
