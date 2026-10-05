import 'package:flutter/material.dart';

import '../network.dart';
import '../sharing.dart';
import 'page_frame.dart';

class SharePage extends StatelessWidget {
  const SharePage(this.network, {required this.openDevices, super.key});

  final Network network;
  final VoidCallback openDevices;

  @override
  Widget build(BuildContext context) {
    final sharing = network.sharing;
    final theme = Theme.of(context);
    return ListenableBuilder(
      listenable: Listenable.merge([sharing, network]),
      builder: (context, _) {
        final allowed = network.book.devices.value.where((device) => device.send).length;
        return PageFrame(
          title: 'Share',
          subtitle: 'Offer a window or a whole display to your devices, with sound.',
          action: FilledButton.icon(
            onPressed: () => showDialog<void>(context: context, builder: (_) => _PickWindow(sharing)),
            icon: const Icon(Icons.add),
            label: const Text('Share'),
          ),
          children: [
            if (sharing.problem != null) ...[_Problem(sharing), const SizedBox(height: 12)],
            if (allowed == 0)
              Panel(
                child: Row(
                  children: [
                    Icon(Icons.info_outline, size: 20, color: theme.colorScheme.onSurfaceVariant),
                    const SizedBox(width: 12),
                    const Expanded(child: Text('None of your devices may watch yet. Switch on "Let it watch my windows" for one.')),
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
          ],
        );
      },
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
                    window.display ? 'display' : window.process,
                    if (window.withAudio) 'with sound',
                    if (!window.present)
                      window.display ? 'waiting for the display' : 'waiting for the window'
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

  Future<List<ShareableWindow>> _find() async => [...await widget.sharing.displays(), ...await widget.sharing.windows()];

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
                          leading: Icon(choice.display ? Icons.desktop_windows_outlined : Icons.web_asset_outlined, size: 20),
                          title: Text(choice.label, maxLines: 1, overflow: TextOverflow.ellipsis),
                          subtitle: Text(choice.display ? '${choice.width}x${choice.height}${choice.primary ? ' · main display' : ''}' : choice.process),
                          onTap: () {
                            widget.sharing.share(choice, audio: _withAudio);
                            Navigator.of(context).pop();
                          },
                        ),
                    ],
                  );
                },
              ),
            ),
            const Divider(),
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              controlAffinity: ListTileControlAffinity.leading,
              title: const Text('Include sound'),
              subtitle: Text(
                'A window brings the sound of its application only. A display brings everything this computer plays except Manifold.',
                style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
              ),
              value: _withAudio,
              onChanged: (on) => setState(() => _withAudio = on ?? true),
            ),
          ],
        ),
      ),
      actions: [TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel'))],
    );
  }
}
