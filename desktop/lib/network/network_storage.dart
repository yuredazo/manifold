import 'dart:io';
import 'dart:typed_data';

import '../core/dpapi.dart';
import '../core/storage.dart';
import 'protocol/crypto.dart';
import 'protocol/devices.dart';

const _identityFile = 'identity.key';
const _sealedPrefix = 'dpapi:';

/// What the network keeps on disk: this computer's identity key and the paired devices.
extension NetworkStorage on Storage {
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
