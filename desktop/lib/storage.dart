import 'dart:io';

import 'dart:typed_data';

import 'dpapi.dart';
import 'net/crypto.dart';
import 'net/devices.dart';

const _identityFile = 'identity.key';
const _sealedPrefix = 'dpapi:';

/// Files are readable by programs running as the same user. Only the identity key is protected.
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
  Identity loadIdentity() => Identity(_loadKeys(), Platform.localHostname);

  // 1.0.0 wrote the key as plain hex.
  KeyPair _loadKeys() {
    final stored = read(_identityFile)?.trim();
    Uint8List? private;
    if (stored != null && stored.startsWith(_sealedPrefix)) {
      final sealed = fromHex(stored.substring(_sealedPrefix.length));
      private = sealed == null ? null : unprotect(sealed);
    } else if (stored != null) {
      private = fromHex(stored);
      if (private != null && private.length == Crypto.keyLength) _keep(private);
    }
    return private != null && private.length == Crypto.keyLength ? Crypto.keyPairFrom(private) : _create();
  }

  void _keep(Uint8List private) => write(_identityFile, '$_sealedPrefix${toHex(protect(private))}');

  DeviceBook loadDevices() => DeviceBook(read('devices.txt'), (text) => write('devices.txt', text));

  KeyPair _create() {
    final keys = Crypto.generateKeyPair();
    _keep(keys.private);
    return keys;
  }
}
