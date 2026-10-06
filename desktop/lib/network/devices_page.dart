import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/page_frame.dart';
import '../core/rows.dart';
import 'network.dart';
import 'protocol/control.dart';
import 'protocol/devices.dart';
import 'public_address.dart';

const _chevron = Icon(Icons.chevron_right);

class DevicesPage extends StatelessWidget {
  const DevicesPage(this.network, {super.key});

  final Network network;

  void _pair(BuildContext context) {
    showDetails<void>(
      context,
      title: 'Pair a device',
      builder: (_) => _PairBody(
        network,
        onPick: (address) {
          Navigator.pop(context);
          network.pair(address);
        },
        onType: () async {
          Navigator.pop(context);
          final text = await showDialog<String>(context: context, builder: (_) => const _AddressDialog());
          if (text != null && text.trim().isNotEmpty) await network.pair(text);
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: network,
      builder: (context, _) {
        final devices = network.book.devices.value;
        final address = !network.listening
            ? 'Off'
            : network.addresses.isEmpty
                ? 'No network address found'
                : network.addresses.first;
        return PageFrame(
          title: 'Devices',
          action: FilledButton.icon(onPressed: () => _pair(context), icon: const Icon(Icons.add), label: const Text('Pair a device')),
          children: [
            const SectionLabel('This computer'),
            Group(
              children: [
                SwitchRow(
                  title: 'Accept connections',
                  subtitle: address,
                  icon: Icons.wifi,
                  value: network.listening,
                  onChanged: (on) => on ? network.start() : network.stop(),
                ),
                ListRow(
                  title: 'Connection details',
                  subtitle: readableFingerprint(network.identity.fingerprint),
                  leading: const IconBadge(Icons.fingerprint),
                  trailing: _chevron,
                  onTap: () => showDetails<void>(context, title: 'Connection details', builder: (_) => _ConnectionDetails(network)),
                ),
              ],
            ),
            const SectionLabel('Paired devices'),
            if (devices.isEmpty)
              const Group(children: [ListRow(title: 'No device is paired yet.', leading: IconBadge(Icons.devices_outlined))])
            else
              Group(
                children: [
                  for (final device in devices)
                    _DeviceRow(
                      device,
                      online: network.online.contains(device.publicKey),
                      onTap: () => showDetails<void>(
                        context,
                        title: device.name,
                        builder: (_) => _DeviceBody(network, device.publicKey),
                      ),
                    ),
                ],
              ),
          ],
        );
      },
    );
  }
}

class _DeviceRow extends StatelessWidget {
  const _DeviceRow(this.device, {required this.online, required this.onTap});

  final Device device;
  final bool online;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final summary = [
      online ? 'Online' : 'Offline',
      if (device.receive) 'receiving',
      if (device.send || device.sendCamera || device.sendSpout) 'sharing',
    ].join(' · ');
    return ListRow(
      title: device.name,
      subtitle: summary,
      leading: IconBadge(Icons.devices_outlined, badge: online ? colors.primary : colors.outline),
      trailing: _chevron,
      onTap: onTap,
    );
  }
}

class _ConnectionDetails extends StatelessWidget {
  const _ConnectionDetails(this.network);

  final Network network;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: network,
      builder: (context, _) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Group(
            children: [
              ListRow(title: 'Fingerprint', subtitle: readableFingerprint(network.identity.fingerprint), leading: const IconBadge(Icons.fingerprint)),
              if (network.listening) ...[
                for (final address in network.addresses) _AddressRow(label: 'Local', address: address),
                const _PublicAddress(port: listenPort),
              ],
            ],
          ),
          if (!network.listening)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
              child: Text(
                'Accept connections is off, so there is no address to show.',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(color: secondaryText(context)),
              ),
            ),
        ],
      ),
    );
  }
}

class _AddressRow extends StatelessWidget {
  const _AddressRow({required this.label, required this.address});

  final String label;
  final String address;

  @override
  Widget build(BuildContext context) {
    return ListRow(
      title: label,
      subtitle: address,
      leading: const IconBadge(Icons.lan_outlined),
      trailing: IconButton(
        tooltip: 'Copy',
        icon: const Icon(Icons.copy_outlined, size: 20),
        onPressed: () async {
          await Clipboard.setData(ClipboardData(text: address));
          if (context.mounted) {
            ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Copied'), duration: Duration(seconds: 1)));
          }
        },
      ),
    );
  }
}

/// Looked up only when asked, since it tells an outside service this computer's address.
class _PublicAddress extends StatefulWidget {
  const _PublicAddress({required this.port});

  final int port;

  @override
  State<_PublicAddress> createState() => _PublicAddressState();
}

class _PublicAddressState extends State<_PublicAddress> {
  String? _address;
  bool _looking = false;
  bool _failed = false;

  Future<void> _find() async {
    setState(() {
      _looking = true;
      _failed = false;
    });
    final found = await findPublicAddress();
    if (!mounted) return;
    setState(() {
      _looking = false;
      _address = found == null ? null : '$found:${widget.port}';
      _failed = found == null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final address = _address;
    if (address != null) return _AddressRow(label: 'Public', address: address);
    return ListRow(
      title: 'Public',
      subtitle: _looking ? 'Looking up...' : (_failed ? 'Could not find it.' : null),
      leading: const IconBadge(Icons.public),
      trailing: TextButton(onPressed: _looking ? null : _find, child: const Text('Look up')),
    );
  }
}

class _DeviceBody extends StatelessWidget {
  const _DeviceBody(this.network, this.publicKey);

  final Network network;
  final String publicKey;

  Future<void> _confirmUnpair(BuildContext context, Device device) async {
    final colors = Theme.of(context).colorScheme;
    final unpair = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Unpair ${device.name}?'),
        content: const Text('It has to be paired again before it can connect.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: colors.error, foregroundColor: colors.onError),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Unpair'),
          ),
        ],
      ),
    );
    if (unpair != true) return;
    network.unpair(device.publicKey);
    if (context.mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: network,
      builder: (context, _) {
        final device = network.book.find(publicKey);
        if (device == null) return const SizedBox.shrink();
        final theme = Theme.of(context);
        final online = network.online.contains(publicKey);
        final offered = network.remoteFeeds[publicKey]?.map((feed) => feed.label).toList() ?? const <String>[];
        final refused = network.refused[publicKey] ?? const <String, Refusal>{};
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Group(
              children: [
                ListRow(
                  title: online ? 'Online' : 'Offline',
                  subtitle: readableFingerprint(device.fingerprint) + (device.address == null ? '' : '  ·  ${device.address}'),
                  leading: IconBadge(Icons.devices_outlined, badge: online ? theme.colorScheme.primary : theme.colorScheme.outline),
                ),
                SwitchRow(
                  title: 'Watch its feeds',
                  subtitle: !device.receive
                      ? 'What it shares appears on the Watch page.'
                      : !online
                          ? 'It is offline.'
                          : offered.isEmpty
                              ? 'It is not sharing anything right now.'
                              : 'Sharing: ${offered.join(', ')}',
                  icon: Icons.live_tv_outlined,
                  value: device.receive,
                  onChanged: (on) => network.setReceive(publicKey, on),
                ),
                SwitchRow(
                  title: 'Let it watch my windows',
                  subtitle: 'The windows and displays you share.',
                  icon: Icons.present_to_all_outlined,
                  value: device.send,
                  onChanged: (on) => network.setSend(publicKey, on),
                ),
                SwitchRow(
                  title: 'Let it watch my camera',
                  subtitle: 'The cameras you share.',
                  icon: Icons.videocam_outlined,
                  value: device.sendCamera,
                  onChanged: (on) => network.setSendCamera(publicKey, on),
                ),
                SwitchRow(
                  title: 'Let it watch my Spout senders',
                  subtitle: 'The Spout senders you share.',
                  icon: Icons.layers_outlined,
                  value: device.sendSpout,
                  onChanged: (on) => network.setSendSpout(publicKey, on),
                ),
              ],
            ),
            for (final entry in refused.entries)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                child: Text(_refusalText(entry.key, entry.value), style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error)),
              ),
            const SizedBox(height: 12),
            Group(
              children: [
                ListRow(
                  title: 'Unpair',
                  titleColor: theme.colorScheme.error,
                  leading: const IconBadge(Icons.link_off),
                  onTap: () => _confirmUnpair(context, device),
                ),
              ],
            ),
          ],
        );
      },
    );
  }
}

String _refusalText(String feed, Refusal reason) => switch (reason) {
      Refusal.notShared => '$feed: that device has not allowed this one to watch. Switch on Share my feeds there.',
      Refusal.notFound => '$feed: that device no longer has this feed.',
      Refusal.busy => '$feed: that device is already sending as many streams as it allows.',
      Refusal.failed => '$feed: that device could not start sending it.',
    };

class _PairBody extends StatefulWidget {
  const _PairBody(this.network, {required this.onPick, required this.onType});

  final Network network;
  final void Function(String address) onPick;
  final VoidCallback onType;

  @override
  State<_PairBody> createState() => _PairBodyState();
}

class _PairBodyState extends State<_PairBody> {
  Timer? _clock;

  @override
  void initState() {
    super.initState();
    unawaited(widget.network.discovery.listen());
    _clock = Timer.periodic(const Duration(seconds: 1), (_) {
      if (widget.network.pairingOpenUntil != 0 && mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _clock?.cancel();
    widget.network.discovery.stop();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final network = widget.network;
    return ListenableBuilder(
      listenable: Listenable.merge([network, network.discovery]),
      builder: (context, _) {
        final nearby = network.nearby;
        final openFor = network.pairingOpenUntil - network.nowMs;
        return Group(
          children: [
            if (nearby.isEmpty)
              const ListRow(
                title: 'Looking for devices',
                subtitle: 'Devices that allow pairing show up here.',
                leading: IconBadge(Icons.search),
              ),
            for (final found in nearby)
              ListRow(
                title: found.name,
                subtitle: '${found.host}  ·  ${readableFingerprint(found.id)}',
                leading: const IconBadge(Icons.devices_outlined),
                trailing: _chevron,
                onTap: () => widget.onPick('${found.host}:${found.port}'),
              ),
            ListRow(
              title: 'Enter its address',
              subtitle: 'Type the address the other device shows.',
              leading: const IconBadge(Icons.keyboard_outlined),
              onTap: widget.onType,
            ),
            if (openFor > 0)
              ListRow(
                title: 'Pairing open, ${openFor ~/ 60000}:${(openFor ~/ 1000 % 60).toString().padLeft(2, '0')} left',
                subtitle: 'Nearby devices can see this computer',
                leading: const IconBadge(Icons.wifi),
                trailing: TextButton(onPressed: network.closePairing, child: const Text('Close')),
              )
            else
              ListRow(
                title: 'Let another device pair with this one',
                subtitle: 'Then type this computer’s address there.',
                leading: const IconBadge(Icons.wifi),
                onTap: network.openPairing,
              ),
          ],
        );
      },
    );
  }
}

class _AddressDialog extends StatefulWidget {
  const _AddressDialog();

  @override
  State<_AddressDialog> createState() => _AddressDialogState();
}

class _AddressDialogState extends State<_AddressDialog> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Pair a device'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('On the other device, open Devices and allow pairing. It shows its address.'),
            const SizedBox(height: 12),
            TextField(
              controller: _controller,
              autofocus: true,
              decoration: const InputDecoration(labelText: 'Address', hintText: '192.168.1.20', border: OutlineInputBorder()),
              onSubmitted: (text) => Navigator.pop(context, text),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: () => Navigator.pop(context, _controller.text), child: const Text('Pair')),
      ],
    );
  }
}
