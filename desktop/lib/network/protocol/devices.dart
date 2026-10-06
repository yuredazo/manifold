import 'package:flutter/foundation.dart';

import 'control.dart';
import 'crypto.dart';

String toHex(Uint8List bytes) => bytes.map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();

Uint8List? fromHex(String text) {
  if (text.length.isOdd) return null;
  try {
    return Uint8List.fromList([for (var i = 0; i < text.length; i += 2) int.parse(text.substring(i, i + 2), radix: 16)]);
  } on FormatException {
    return null;
  }
}

/// SHA-256 of a public key, in hex. This, not an address or a name, is what identifies a device.
String fingerprintOf(Uint8List publicKey) => toHex(Crypto.sha256([publicKey]));

String readableFingerprint(String fingerprint) {
  final head = fingerprint.length > 16 ? fingerprint.substring(0, 16) : fingerprint;
  return [for (var i = 0; i < head.length; i += 4) head.substring(i, i + 4 > head.length ? head.length : i + 4)].join(' ');
}

final class Identity {
  const Identity(this.keys, this.name);

  final KeyPair keys;
  final String name;

  String get fingerprint => fingerprintOf(keys.public);
}

@immutable
final class Device {
  const Device({
    required this.publicKey,
    required this.name,
    this.address,
    this.receive = false,
    this.send = false,
    this.sendCamera = false,
    this.sendSpout = false,
  });

  final String publicKey;
  final String name;
  final String? address;
  final bool receive;
  final bool send;

  /// Cameras and Spout senders are allowed separately from windows and displays.
  final bool sendCamera;
  final bool sendSpout;

  String get fingerprint {
    final bytes = fromHex(publicKey);
    return bytes == null ? '' : fingerprintOf(bytes);
  }

  Device copyWith({String? name, String? address, bool? receive, bool? send, bool? sendCamera, bool? sendSpout}) => Device(
        publicKey: publicKey,
        name: name ?? this.name,
        address: address ?? this.address,
        receive: receive ?? this.receive,
        send: send ?? this.send,
        sendCamera: sendCamera ?? this.sendCamera,
        sendSpout: sendSpout ?? this.sendSpout,
      );
}

final class DeviceBook {
  DeviceBook(String? stored, this._save) {
    if (stored != null) {
      for (final line in stored.split('\n')) {
        _parse(line);
      }
    }
    devices.value = List.unmodifiable(_entries.values);
  }

  final void Function(String) _save;
  final Map<String, Device> _entries = {};
  final ValueNotifier<List<Device>> devices = ValueNotifier(const []);

  Device? find(String publicKeyHex) => _entries[publicKeyHex];

  Device? findKey(Uint8List publicKey) => _entries[toHex(publicKey)];

  void put(Device device) {
    _entries[device.publicKey] = device.copyWith(name: _clean(device.name));
    _changed();
  }

  void update(String publicKeyHex, Device Function(Device) change) {
    final current = _entries[publicKeyHex];
    if (current == null) return;
    _entries[publicKeyHex] = change(current);
    _changed();
  }

  void remove(String publicKeyHex) {
    if (_entries.remove(publicKeyHex) != null) _changed();
  }

  void _changed() {
    devices.value = List.unmodifiable(_entries.values);
    _save(_entries.values.map((d) => [d.publicKey, d.name, d.address ?? '', d.receive, d.send, d.sendCamera, d.sendSpout].join('\t')).join('\n'));
  }

  // A book from before cameras or Spout could be shared has fewer fields, and what is missing stays off. Extra fields are
  // ignored, so a book written by a newer version still loads after a downgrade.
  void _parse(String line) {
    final parts = line.split('\t');
    if (parts.length < 5) return;
    final key = parts[0];
    if (key.length != 2 * Crypto.keyLength || fromHex(key) == null) return;
    _entries[key] = Device(
      publicKey: key,
      name: parts[1],
      address: parts[2].isEmpty ? null : parts[2],
      receive: parts[3] == 'true',
      send: parts[4] == 'true',
      sendCamera: parts.length > 5 && parts[5] == 'true',
      sendSpout: parts.length > 6 && parts[6] == 'true',
    );
  }

  String _clean(String name) {
    final cleaned = name.replaceAll('\t', ' ').replaceAll('\n', ' ').trim();
    return cleaned.length > maxNameLength ? cleaned.substring(0, maxNameLength) : cleaned;
  }
}
