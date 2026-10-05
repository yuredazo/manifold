import 'package:flutter/material.dart';

import '../network.dart';

class PairingDialogHost extends StatelessWidget {
  const PairingDialogHost(this.network, {super.key});

  final Network network;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: network,
      builder: (context, _) {
        final dialog = switch (network.pairing) {
          NoPairing() => null,
          Connecting(:final address) => AlertDialog(
              title: const Text('Pair a device'),
              content: Text('Connecting to $address...'),
              actions: [TextButton(onPressed: network.cancelPairing, child: const Text('Cancel'))],
            ),
          final CodeShown shown => AlertDialog(
              title: Text('Pair with ${shown.remoteName}?'),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Check that this code is the same on both devices.'),
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 20),
                    child: Text(
                      '${shown.code.substring(0, 3)} ${shown.code.substring(3)}',
                      style: Theme.of(context).textTheme.displaySmall?.copyWith(fontFamily: 'Consolas'),
                    ),
                  ),
                  if (shown.confirmed) Text('Waiting for ${shown.remoteName} to confirm.'),
                ],
              ),
              actions: shown.confirmed
                  ? [TextButton(onPressed: network.cancelPairing, child: const Text('Cancel'))]
                  : [
                      TextButton(onPressed: () => network.confirmPairing(false), child: const Text('They do not match')),
                      FilledButton(onPressed: () => network.confirmPairing(true), child: const Text('They match')),
                    ],
            ),
          PairingFailed(:final reason) => AlertDialog(
              title: const Text('Pairing failed'),
              content: Text('Reason: $reason.'),
              actions: [TextButton(onPressed: network.dismissPairing, child: const Text('OK'))],
            ),
        };
        if (dialog == null) return const SizedBox.shrink();
        return Stack(
          children: [
            const ModalBarrier(dismissible: false, color: Colors.black54),
            Center(child: dialog),
          ],
        );
      },
    );
  }
}
