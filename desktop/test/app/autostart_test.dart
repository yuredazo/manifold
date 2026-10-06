import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/app/autostart.dart';

void main() {
  const key = r'HKCU\Software\ManifoldTests\Run';
  final executable = '${Directory.systemTemp.path}${Platform.pathSeparator}Program Files${Platform.pathSeparator}manifold_hub.exe';

  tearDown(() => Process.run('reg', ['delete', r'HKCU\Software\ManifoldTests', '/f']));

  Autostart open([String path = '']) => Autostart(executable: path.isEmpty ? executable : path, key: key, name: 'ManifoldTest');

  test('it is off until switched on, then survives a restart of the app, and switching it off removes it', () async {
    final autostart = open();
    await autostart.refresh();
    expect(autostart.enabled, isFalse);

    await autostart.set(true);
    expect(autostart.enabled, isTrue);

    final again = open();
    await again.refresh();
    expect(again.enabled, isTrue, reason: 'read back from the registry, with the spaces in the path intact');

    await again.set(false);
    expect(again.enabled, isFalse);
    expect(await Process.run('reg', ['query', key, '/v', 'ManifoldTest']).then((result) => result.exitCode), isNot(0));
  });

  test('an entry that starts another copy is not counted as this one, and switching on replaces it', () async {
    await open(r'C:\Old place\manifold_hub.exe').set(true);

    final autostart = open();
    await autostart.refresh();
    expect(autostart.enabled, isFalse);

    await autostart.set(true);
    expect(autostart.enabled, isTrue);
  });

  test('the command starts the hub hidden', () {
    expect(open().command, endsWith('" $hiddenFlag'));
  });

  test('switching off when nothing is registered is harmless', () async {
    final autostart = open();

    await autostart.set(false);

    expect(autostart.enabled, isFalse);
  });
}
