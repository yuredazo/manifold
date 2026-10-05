import 'dart:io';

import 'net/crypto.dart';
import 'net/devices.dart';

/// Another program running as the same user could read it, as with the phone's private storage.
final class Storage {
  Storage._(this._directory);

  Storage.at(Directory directory) : _directory = directory;

  final Directory _directory;

  static Storage open() {
    final base = Platform.environment['APPDATA'] ?? Directory.systemTemp.path;
    final directory = Directory('$base${Platform.pathSeparator}Manifold')..createSync(recursive: true);
    return Storage._(directory);
  }

  String? read(String name) {
    final file = File('${_directory.path}${Platform.pathSeparator}$name');
    return file.existsSync() ? file.readAsStringSync() : null;
  }

  /// Written to a temporary file first, so a crash cannot leave half a file behind.
  void write(String name, String text) {
    final target = '${_directory.path}${Platform.pathSeparator}$name';
    final temporary = File('$target.tmp')..writeAsStringSync(text, flush: true);
    temporary.renameSync(target);
  }

  /// The name is the computer's name, which is what the phone shows when it asks to pair.
  Identity loadIdentity() {
    final stored = read('identity.key')?.trim();
    final private = stored == null ? null : fromHex(stored);
    final keys = private != null && private.length == Crypto.keyLength ? Crypto.keyPairFrom(private) : _create();
    return Identity(keys, Platform.localHostname);
  }

  DeviceBook loadDevices() => DeviceBook(read('devices.txt'), (text) => write('devices.txt', text));

  KeyPair _create() {
    final keys = Crypto.generateKeyPair();
    write('identity.key', toHex(keys.private));
    return keys;
  }
}
