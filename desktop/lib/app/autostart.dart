import 'dart:io';

import 'package:flutter/foundation.dart';

const hiddenFlag = '--hidden';

const _runKey = r'HKCU\Software\Microsoft\Windows\CurrentVersion\Run';
const _valueName = 'Manifold';

/// Starts the hub, hidden in the tray, when its owner signs in. It lives in the owner's Run key, so no administrator is needed.
final class Autostart extends ChangeNotifier {
  Autostart({required String executable, this.key = _runKey, this.name = _valueName}) : command = '"$executable" $hiddenFlag';

  final String command;
  final String key;
  final String name;

  bool _enabled = false;

  /// Only true while the entry runs this very copy, so a hub that was moved offers to start itself again.
  bool get enabled => _enabled;

  Future<void> refresh() async {
    final result = await Process.run('reg', ['query', key, '/v', name]);
    final now = result.exitCode == 0 && (result.stdout as String).contains(command);
    if (now == _enabled) return;
    _enabled = now;
    notifyListeners();
  }

  Future<void> set(bool on) async {
    final result = on
        ? await Process.run('reg', ['add', key, '/v', name, '/t', 'REG_SZ', '/d', command, '/f'])
        : await Process.run('reg', ['delete', key, '/v', name, '/f']);
    if (result.exitCode != 0 && on) debugPrint('autostart: ${result.stderr}');
    await refresh();
  }
}
