import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../net/devices.dart';
import '../net/control.dart';
import '../network.dart';
import '../public_address.dart';
import 'page_frame.dart';

class DevicesPage extends StatefulWidget {
  const DevicesPage(this.network, {super.key});

  final Network network;

  @override
  State<DevicesPage> createState() => _DevicesPageState();
}

class _DevicesPageState extends State<DevicesPage> {
  Timer? _clock;

  @override
  void initState() {
    super.initState();
    // The countdown on the pairing window needs a redraw every second.
    _clock = Timer.periodic(const Duration(seconds: 1), (_) {
      if (widget.network.pairingOpenUntil != 0 && mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _clock?.cancel();
    super.dispose();
  }

  Future<void> _askForAddress() async {
    final text = await showDialog<String>(context: context, builder: (_) => const _AddressDialog());
    if (text != null && text.trim().isNotEmpty) await widget.network.pair(text);
  }

  @override
  Widget build(BuildContext context) {
    final network = widget.network;
    return ListenableBuilder(
      listenable: network,
      builder: (context, _) {
        final devices = network.book.devices.value;
        return PageFrame(
          title: 'Devices',
          subtitle: 'Devices stay paired until you unpair them. Everything between them is encrypted.',
          children: [
            _ThisComputer(network, onPair: _askForAddress),
            const SizedBox(height: 28),
            const SectionTitle('Paired devices'),
            if (devices.isEmpty)
              const Panel(child: Text('No device is paired yet. Use "Pair with a device" above, or let a device pair with this one.'))
            else
              for (final device in devices) ...[_DeviceCard(network, device), const SizedBox(height: 12)],
          ],
        );
      },
    );
  }
}

class _ThisComputer extends StatelessWidget {
  const _ThisComputer(this.network, {required this.onPair});

  final Network network;
  final VoidCallback onPair;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    final openFor = network.pairingOpenUntil - network.nowMs;
    final pairingOpen = openFor > 0;
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.computer_outlined, color: muted),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(network.identity.name, style: theme.textTheme.titleMedium),
                    Text(
                      'Fingerprint ${readableFingerprint(network.identity.fingerprint)}',
                      style: theme.textTheme.bodySmall?.copyWith(fontFamily: 'Consolas', color: muted),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Accept connections'),
            subtitle: Text(
              !network.listening
                  ? 'Off. Your paired devices cannot reach this computer.'
                  : network.addresses.isEmpty
                      ? 'On, but no network address was found.'
                      : 'On. Another device on this network can reach it at:',
            ),
            value: network.listening,
            onChanged: (on) => on ? network.start() : network.stop(),
          ),
          if (network.listening) ...[
            for (final address in network.addresses) _CopyableAddress(label: 'Local', address: address),
            const _PublicAddress(port: listenPort),
          ],
          const Divider(height: 24),
          _PairingRow(
            title: 'Pair with a device',
            detail: 'Type the address the other device shows.',
            button: FilledButton.tonal(onPressed: onPair, child: const Text('Pair')),
          ),
          const SizedBox(height: 12),
          _PairingRow(
            title: 'Let a device pair with this one',
            detail: pairingOpen
                ? 'Open for ${openFor ~/ 60000}:${(openFor ~/ 1000 % 60).toString().padLeft(2, '0')} more. Start pairing on the other device.'
                : 'Opens pairing for two minutes.',
            button: pairingOpen
                ? OutlinedButton(onPressed: network.closePairing, child: const Text('Close'))
                : FilledButton.tonal(onPressed: network.openPairing, child: const Text('Allow')),
          ),
        ],
      ),
    );
  }
}

class _CopyableAddress extends StatelessWidget {
  const _CopyableAddress({required this.label, required this.address});

  final String label;
  final String address;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(left: 4),
      child: Row(
        children: [
          SizedBox(width: 56, child: Text(label, style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant))),
          Expanded(child: SelectableText(address, style: theme.textTheme.bodyMedium?.copyWith(fontFamily: 'Consolas'))),
          IconButton(
            tooltip: 'Copy',
            visualDensity: VisualDensity.compact,
            icon: const Icon(Icons.copy_outlined, size: 18),
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: address));
              if (context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Copied'), duration: Duration(seconds: 1)));
              }
            },
          ),
        ],
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
    final theme = Theme.of(context);
    final address = _address;
    if (address != null) return _CopyableAddress(label: 'Public', address: address);
    return Padding(
      padding: const EdgeInsets.only(left: 4, top: 4),
      child: Row(
        children: [
          SizedBox(width: 56, child: Text('Public', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant))),
          TextButton(onPressed: _looking ? null : _find, child: Text(_looking ? 'Looking up...' : 'Look up')),
          if (_failed) Text('Could not find it.', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error)),
        ],
      ),
    );
  }
}

class _PairingRow extends StatelessWidget {
  const _PairingRow({required this.title, required this.detail, required this.button});

  final String title;
  final String detail;
  final Widget button;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: theme.textTheme.bodyLarge),
              Text(detail, style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            ],
          ),
        ),
        const SizedBox(width: 16),
        SizedBox(width: 96, child: button),
      ],
    );
  }
}

class _DeviceCard extends StatelessWidget {
  const _DeviceCard(this.network, this.device);

  final Network network;
  final Device device;

  Future<void> _confirmUnpair(BuildContext context) async {
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
    if (unpair == true) network.unpair(device.publicKey);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    final online = network.online.contains(device.publicKey);
    final offered = network.remoteFeeds[device.publicKey]?.map((feed) => feed.name).toList() ?? const <String>[];
    final refused = network.refused[device.publicKey] ?? const <String, Refusal>{};
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text(device.name, style: theme.textTheme.titleMedium)),
              StatusDot(online: online),
              PopupMenuButton<void>(
                tooltip: 'More',
                icon: const Icon(Icons.more_vert, size: 20),
                itemBuilder: (_) => [PopupMenuItem(onTap: () => _confirmUnpair(context), child: const Text('Unpair'))],
              ),
            ],
          ),
          Text(
            '${readableFingerprint(device.fingerprint)}${device.address == null ? '' : '  ·  ${device.address}'}',
            style: theme.textTheme.bodySmall?.copyWith(fontFamily: 'Consolas', color: muted),
          ),
          const SizedBox(height: 8),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            dense: true,
            title: const Text('Watch its feeds'),
            subtitle: Text(
              !device.receive
                  ? 'What it shares appears on the Watch page.'
                  : !online
                      ? 'It is offline.'
                      : offered.isEmpty
                          ? 'It is not sharing anything right now.'
                          : 'Sharing: ${offered.join(', ')}',
            ),
            value: device.receive,
            onChanged: (on) => network.setReceive(device.publicKey, on),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            dense: true,
            title: const Text('Let it watch my windows'),
            subtitle: const Text('It sees the windows you share on the Share page.'),
            value: device.send,
            onChanged: (on) => network.setSend(device.publicKey, on),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            dense: true,
            title: const Text('Let it watch my camera'),
            subtitle: const Text('It sees the cameras you share on the Share page. Off by default, and separate from windows.'),
            value: device.sendCamera,
            onChanged: (on) => network.setSendCamera(device.publicKey, on),
          ),
          for (final entry in refused.entries)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(_refusalText(entry.key, entry.value), style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error)),
            ),
        ],
      ),
    );
  }
}

String _refusalText(String feed, Refusal reason) => switch (reason) {
      Refusal.notShared => '$feed: that device has not allowed this one to watch. Switch on Share my feeds there.',
      Refusal.notFound => '$feed: that device no longer has this feed.',
      Refusal.busy => '$feed: that device is already sending as many streams as it allows.',
      Refusal.failed => '$feed: that device could not start sending it.',
    };

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
      title: const Text('Pair with a device'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('On the other device, open Devices, switch on Accept connections and choose Allow pairing. It shows its address.'),
            const SizedBox(height: 16),
            TextField(
              controller: _controller,
              autofocus: true,
              decoration: const InputDecoration(labelText: 'Address', hintText: '192.168.0.104', border: OutlineInputBorder()),
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
