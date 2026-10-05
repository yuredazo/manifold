import 'package:flutter/material.dart';

import '../activity.dart';
import '../network.dart';
import '../sharing.dart';
import 'page_frame.dart';

const _shownActivity = 6;

class SharePage extends StatelessWidget {
  const SharePage(this.network, {required this.openDevices, super.key});

  final Network network;
  final VoidCallback openDevices;

  @override
  Widget build(BuildContext context) {
    final sharing = network.sharing;
    final theme = Theme.of(context);
    return ListenableBuilder(
      listenable: Listenable.merge([sharing, network, network.activity]),
      builder: (context, _) {
        final allowed = network.book.devices.value.where((device) => device.send || device.sendCamera).length;
        return PageFrame(
          title: 'Share',
          subtitle: 'Offer a window, a whole display or a camera to your devices.',
          action: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (sharing.shared.isNotEmpty && !sharing.paused) ...[
                TextButton.icon(
                  onPressed: () => sharing.paused = true,
                  icon: const Icon(Icons.pause_circle_outline),
                  label: const Text('Stop all sharing'),
                ),
                const SizedBox(width: 8),
              ],
              FilledButton.icon(
                onPressed: () => showDialog<void>(context: context, builder: (_) => _PickWindow(sharing)),
                icon: const Icon(Icons.add),
                label: const Text('Share'),
              ),
            ],
          ),
          children: [
            if (sharing.paused) ...[_Paused(sharing), const SizedBox(height: 12)],
            if (sharing.problem != null) ...[_Problem(sharing), const SizedBox(height: 12)],
            if (allowed == 0)
              Panel(
                child: Row(
                  children: [
                    Icon(Icons.info_outline, size: 20, color: theme.colorScheme.onSurfaceVariant),
                    const SizedBox(width: 12),
                    const Expanded(child: Text('None of your devices may watch yet. Switch on "Let it watch my windows" or "Let it watch my camera" for one.')),
                    TextButton(onPressed: openDevices, child: const Text('Open Devices')),
                  ],
                ),
              ),
            if (sharing.shared.isEmpty)
              const EmptyState(
                icon: Icons.present_to_all_outlined,
                title: 'Nothing is shared',
                message: 'It is only captured while a device is watching it.',
              )
            else ...[
              const SizedBox(height: 20),
              SectionTitle(allowed == 1 ? 'Shared, 1 device can watch' : 'Shared, $allowed devices can watch'),
              for (final window in sharing.shared) ...[_SharedRow(sharing, window), const SizedBox(height: 8)],
            ],
            if (network.activity.entries.isNotEmpty) ...[
              const SizedBox(height: 20),
              const SectionTitle('Recent activity'),
              Panel(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                child: Column(
                  children: [for (final entry in network.activity.entries.take(_shownActivity)) _ActivityRow(entry)],
                ),
              ),
            ],
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
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          SizedBox(width: 48, child: Text(time, style: theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant))),
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
    final colors = Theme.of(context).colorScheme;
    return Panel(
      tone: colors.tertiaryContainer,
      child: Row(
        children: [
          Icon(Icons.pause_circle_outline, size: 20, color: colors.onTertiaryContainer),
          const SizedBox(width: 12),
          Expanded(child: Text('Sharing is stopped. Your devices see nothing, and nothing is captured.', style: TextStyle(color: colors.onTertiaryContainer))),
          TextButton(onPressed: () => sharing.paused = false, child: Text('Resume', style: TextStyle(color: colors.onTertiaryContainer))),
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
    final colors = Theme.of(context).colorScheme;
    return Panel(
      tone: colors.errorContainer,
      child: Row(
        children: [
          Icon(Icons.error_outline, size: 20, color: colors.onErrorContainer),
          const SizedBox(width: 12),
          Expanded(child: Text(sharing.problem!, style: TextStyle(color: colors.onErrorContainer))),
          TextButton(onPressed: sharing.dismissProblem, child: Text('Dismiss', style: TextStyle(color: colors.onErrorContainer))),
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
    final theme = Theme.of(context);
    final watching = window.watchers.length;
    return Panel(
      highlighted: watching > 0,
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(window.feedName, style: theme.textTheme.titleSmall, maxLines: 1, overflow: TextOverflow.ellipsis),
                const SizedBox(height: 2),
                Text(
                  [
                    switch (window.kind) {
                      ShareKind.display => 'display',
                      ShareKind.camera => 'camera',
                      ShareKind.window => window.process,
                    },
                    if (window.soundOnly) 'sound only' else if (window.withAudio) 'with sound',
                    if (!window.showCursor && !window.soundOnly && !window.camera) 'no pointer',
                    if (!window.present)
                      'waiting for the ${window.kind.name}'
                    else if (watching == 0)
                      'nobody watching'
                    else
                      '$watching watching',
                  ].join(' · '),
                  style: theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
              ],
            ),
          ),
          TextButton(onPressed: () => sharing.unshare(window), child: const Text('Stop sharing')),
        ],
      ),
    );
  }
}

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

  Future<List<ShareableWindow>> _find() async {
    final found = await Future.wait([widget.sharing.displays(), widget.sharing.cameras(), widget.sharing.windows()]);
    return [for (final kind in found) ...kind];
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return AlertDialog(
      title: Row(
        children: [
          const Expanded(child: Text('Pick what to share')),
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
        width: 460,
        height: 380,
        child: Column(
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
                      for (final choice in choices)
                        ListTile(
                          dense: true,
                          leading: Icon(
                            switch (choice.kind) {
                              ShareKind.display => Icons.desktop_windows_outlined,
                              ShareKind.camera => Icons.videocam_outlined,
                              ShareKind.window => Icons.web_asset_outlined,
                            },
                            size: 20,
                          ),
                          title: Text(choice.label, maxLines: 1, overflow: TextOverflow.ellipsis),
                          subtitle: Text(switch (choice.kind) {
                            ShareKind.display => '${choice.width}x${choice.height}${choice.primary ? ' · main display' : ''}',
                            ShareKind.camera => 'camera · ${choice.width}x${choice.height}',
                            ShareKind.window => choice.process,
                          }),
                          onTap: () {
                            widget.sharing.share(choice, audio: _withAudio, soundOnly: !_withPicture, showCursor: _withCursor);
                            Navigator.of(context).pop();
                          },
                        ),
                    ],
                  );
                },
              ),
            ),
            const Divider(height: 16),
            Row(
              children: [
                FilterChip(
                  label: const Text('Picture'),
                  selected: _withPicture,
                  onSelected: (on) => setState(() => _withPicture = on),
                ),
                const SizedBox(width: 8),
                FilterChip(
                  label: const Text('Sound'),
                  selected: _withAudio || !_withPicture,
                  onSelected: _withPicture ? (on) => setState(() => _withAudio = on) : null,
                ),
                const SizedBox(width: 8),
                FilterChip(
                  label: const Text('Pointer'),
                  selected: _withCursor && _withPicture,
                  onSelected: _withPicture ? (on) => setState(() => _withCursor = on) : null,
                ),
              ],
            ),
            const SizedBox(height: 6),
            Align(
              alignment: Alignment.centerLeft,
              child: Text(
                [
                  if (_withPicture) 'A window brings its app\'s sound, a display all sound except Manifold.' else 'Sound only, which a phone can play.',
                  if (!widget.sharing.offerCameras) 'Cameras are hidden. Switch them on in About.',
                ].join(' '),
                style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
            ),
          ],
        ),
      ),
      actions: [TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel'))],
    );
  }
}
