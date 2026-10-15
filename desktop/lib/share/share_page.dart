import 'package:flutter/material.dart';

import '../core/page_frame.dart';
import '../core/rows.dart';
import '../network/network.dart';
import 'activity.dart';
import 'sharing.dart';

const _shownActivity = 6;

class SharePage extends StatelessWidget {
  const SharePage(this.network, this.sharing, this.activity, {required this.openDevices, super.key});

  final Network network;
  final Sharing sharing;
  final Activity activity;
  final VoidCallback openDevices;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([sharing, network, activity]),
      builder: (context, _) {
        final allowed = network.book.devices.value.where((device) => device.send || device.sendCamera || device.sendSpout).length;
        return PageFrame(
          title: 'Share',
          action: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (sharing.shared.isNotEmpty && !sharing.paused) ...[
                OutlinedButton.icon(
                  onPressed: () => sharing.paused = true,
                  icon: const Icon(Icons.pause_circle_outline, size: 18),
                  label: const Text('Stop all sharing'),
                ),
                const SizedBox(width: 8),
              ],
              FilledButton.icon(
                onPressed: () => showDialog<void>(context: context, builder: (_) => _PickWindow(sharing)),
                icon: const Icon(Icons.add, size: 18),
                label: const Text('Share'),
              ),
            ],
          ),
          children: [
            if (sharing.paused) _Paused(sharing),
            if (sharing.problem != null) _Problem(sharing),
            if (allowed == 0)
              Group(
                children: [
                  ListRow(
                    title: 'No device can watch yet',
                    subtitle: 'Choose what each paired device may watch in Devices.',
                    leading: const IconBadge(Icons.info_outline),
                    trailing: TextButton(onPressed: openDevices, child: const Text('Open Devices')),
                  ),
                ],
              ),
            if (sharing.shared.isEmpty)
              const EmptyState(
                icon: Icons.present_to_all_outlined,
                title: 'Nothing shared yet',
                message: 'Windows, displays and Spout senders you add are captured only while a device is watching.',
              )
            else
              Group(
                title: 'Shared',
                meta: allowed == 1 ? '1 device can watch' : '$allowed devices can watch',
                children: [for (final window in sharing.shared) _SharedRow(sharing, window)],
              ),
            if (activity.entries.isNotEmpty)
              Group(
                title: 'Recent activity',
                children: [for (final entry in activity.entries.take(_shownActivity)) _ActivityRow(entry)],
              ),
          ],
        );
      },
    );
  }
}

class _ActivityRow extends StatelessWidget {
  const _ActivityRow(this.entry);

  final ActivityEntry entry;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final time = '${entry.time.hour.toString().padLeft(2, '0')}:${entry.time.minute.toString().padLeft(2, '0')}';
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      child: Row(
        children: [
          SizedBox(width: 52, child: Text(time, style: theme.textTheme.labelMedium?.copyWith(color: secondaryText(context)))),
          Expanded(
            child: Text('${entry.device} ${entry.started ? 'started' : 'stopped'} watching ${entry.feed}', maxLines: 1, overflow: TextOverflow.ellipsis),
          ),
        ],
      ),
    );
  }
}

class _Paused extends StatelessWidget {
  const _Paused(this.sharing);

  final Sharing sharing;

  @override
  Widget build(BuildContext context) {
    return Notice(
      icon: Icons.pause_circle_outline,
      child: Row(
        children: [
          const Expanded(child: Text('Sharing is paused. Nothing is captured and paired devices see no feeds.')),
          TextButton(onPressed: () => sharing.paused = false, child: const Text('Resume')),
        ],
      ),
    );
  }
}

class _Problem extends StatelessWidget {
  const _Problem(this.sharing);

  final Sharing sharing;

  @override
  Widget build(BuildContext context) {
    return Notice(
      icon: Icons.error_outline,
      error: true,
      child: Row(
        children: [
          Expanded(child: Text(sharing.problem!)),
          TextButton(onPressed: sharing.dismissProblem, child: const Text('Dismiss')),
        ],
      ),
    );
  }
}

class _SharedRow extends StatelessWidget {
  const _SharedRow(this.sharing, this.window);

  final Sharing sharing;
  final SharedWindow window;

  @override
  Widget build(BuildContext context) {
    final watching = window.watchers.length;
    return ListRow(
      title: window.label,
      subtitle: [
        switch (window.kind) {
          ShareKind.display => 'display',
          ShareKind.camera => 'camera',
          ShareKind.spout => 'Spout',
          ShareKind.window => window.process,
        },
        if (window.soundOnly) 'sound only' else if (window.withAudio) 'sound on',
        if (!window.soundOnly) '${window.frameRate} fps',
        if (window.spout && window.withAlpha && !window.soundOnly) 'transparent',
        if (!window.showCursor && !window.soundOnly && !window.pictureOnly) 'no pointer',
        if (!window.present)
          'waiting for the ${window.kind.name}'
        else if (watching == 0)
          'nobody watching'
        else
          '$watching watching',
      ].join('  ·  '),
      subtitleMaxLines: 1,
      leading: IconBadge(_kindIcon(window.kind), active: watching > 0),
      trailing: TextButton(onPressed: () => sharing.unshare(window), child: const Text('Stop sharing')),
    );
  }
}

IconData _kindIcon(ShareKind kind) => switch (kind) {
      ShareKind.display => Icons.desktop_windows_outlined,
      ShareKind.camera => Icons.videocam_outlined,
      ShareKind.spout => Icons.layers_outlined,
      ShareKind.window => Icons.web_asset_outlined,
    };

class _PickWindow extends StatefulWidget {
  const _PickWindow(this.sharing);

  final Sharing sharing;

  @override
  State<_PickWindow> createState() => _PickWindowState();
}

class _PickWindowState extends State<_PickWindow> {
  late Future<List<ShareableWindow>> _choices = _find();
  bool _withAudio = true;
  bool _withPicture = true;
  bool _withCursor = true;
  bool _withAlpha = true;
  int _frameRate = defaultShareFrameRate;

  Future<List<ShareableWindow>> _find() async {
    final found = await Future.wait([widget.sharing.displays(), widget.sharing.spouts(), widget.sharing.cameras(), widget.sharing.windows()]);
    return [for (final kind in found) ...kind];
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return AlertDialog(
      title: Row(
        children: [
          const Expanded(child: Text('Choose what to share')),
          IconButton(
            tooltip: 'Look again',
            onPressed: () => setState(() {
              _choices = _find();
            }),
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      content: SizedBox(
        width: 760,
        height: 470,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: FutureBuilder(
                future: _choices,
                builder: (context, snapshot) {
                  if (snapshot.connectionState != ConnectionState.done) return const Center(child: CircularProgressIndicator());
                  final shared = widget.sharing.shared.map((window) => window.handle).toSet();
                  final choices = (snapshot.data ?? const <ShareableWindow>[]).where((choice) => !shared.contains(choice.handle)).toList();
                  if (choices.isEmpty) return const Center(child: Text('There is nothing else to share.'));
                  return ListView(
                    children: [
                      Group(
                        children: [
                          for (final choice in choices)
                            ListRow(
                              title: choice.label,
                              subtitle: switch (choice.kind) {
                                ShareKind.display => '${choice.width}x${choice.height}${choice.primary ? '  ·  main display' : ''}',
                                ShareKind.camera => 'camera  ·  ${choice.width}x${choice.height}',
                                ShareKind.spout => 'Spout  ·  ${choice.width}x${choice.height}',
                                ShareKind.window => choice.process,
                              },
                              subtitleMaxLines: 1,
                              leading: IconBadge(_kindIcon(choice.kind)),
                              onTap: () {
                                widget.sharing.share(choice, audio: _withAudio, soundOnly: !_withPicture, showCursor: _withCursor, alpha: _withAlpha, frameRate: _frameRate);
                                Navigator.of(context).pop();
                              },
                            ),
                        ],
                      ),
                    ],
                  );
                },
              ),
            ),
            const SizedBox(width: 16),
            SizedBox(
              width: 290,
              child: SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Group(
                      title: 'Options',
                      children: [
                        SwitchRow(title: 'Share the picture', icon: Icons.image_outlined, value: _withPicture, onChanged: (on) => setState(() => _withPicture = on)),
                        if (_withPicture) ...[
                          SwitchRow(title: 'Share its sound', icon: Icons.volume_up_outlined, value: _withAudio, onChanged: (on) => setState(() => _withAudio = on)),
                          SwitchRow(title: 'Show the pointer', icon: Icons.mouse_outlined, value: _withCursor, onChanged: (on) => setState(() => _withCursor = on)),
                          SwitchRow(title: 'Keep transparency', icon: Icons.layers_outlined, value: _withAlpha, onChanged: (on) => setState(() => _withAlpha = on)),
                          Padding(
                            padding: const EdgeInsets.all(14),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('Frame rate', style: theme.textTheme.titleSmall),
                                const SizedBox(height: 8),
                                Wrap(
                                  spacing: 6,
                                  children: [
                                    for (final rate in shareFrameRates)
                                      FilterChip(
                                        label: Text('$rate'),
                                        visualDensity: VisualDensity.compact,
                                        selected: _frameRate == rate,
                                        onSelected: (_) => setState(() => _frameRate = rate),
                                      ),
                                  ],
                                ),
                              ],
                            ),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text(
                      [
                        if (_withPicture) 'A window shares its app’s sound, a display everything but Manifold. Transparency is kept for Spout senders and doubles the picture sent.' else 'Sound only, which a phone can play.',
                        if (!widget.sharing.offerCameras) 'Cameras are hidden. Turn them on in Settings.',
                      ].join(' '),
                      style: theme.textTheme.bodySmall?.copyWith(color: secondaryText(context)),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
      actions: [TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel'))],
    );
  }
}
