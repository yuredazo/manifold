import 'dart:convert';
import 'dart:typed_data';

import 'devices.dart';

/// What a device broadcasts while it lets others pair with it. [id] is the first 8 bytes of its fingerprint, in hex.
final class Beacon {
  const Beacon(this.id, this.port, this.name);

  final String id;
  final int port;
  final String name;
}

/// `"MNFD" | version(1) | port(2) | id(8) | name length(1) | name`, big-endian. Nothing in it is authenticated, so it only
/// says where to try; the pairing code is what establishes who answers.
abstract final class BeaconCodec {
  static const port = 47201;
  static const maxNameBytes = 64;

  static const _magic = [0x4D, 0x4E, 0x46, 0x44];
  static const _version = 1;
  static const _idBytes = 8;
  static const _header = 16;

  static Uint8List encode(Beacon beacon) {
    final id = fromHex(beacon.id);
    if (id == null || id.length != _idBytes) throw ArgumentError.value(beacon.id, 'id', 'must be $_idBytes bytes of hex');
    if (beacon.port < 1 || beacon.port > 0xFFFF) throw ArgumentError.value(beacon.port, 'port');
    var name = beacon.name.runes.toList();
    while (utf8.encode(String.fromCharCodes(name)).length > maxNameBytes) {
      name = name.sublist(0, name.length - 1);
    }
    final nameBytes = utf8.encode(String.fromCharCodes(name));
    return Uint8List.fromList([..._magic, _version, beacon.port >> 8, beacon.port & 0xFF, ...id, nameBytes.length, ...nameBytes]);
  }

  static Beacon? decode(Uint8List bytes) {
    if (bytes.length < _header) return null;
    for (var i = 0; i < _magic.length; i++) {
      if (bytes[i] != _magic[i]) return null;
    }
    if (bytes[4] != _version) return null;
    final port = (bytes[5] << 8) | bytes[6];
    final nameLength = bytes[15];
    if (port == 0 || nameLength > maxNameBytes || bytes.length < _header + nameLength) return null;
    final String text;
    try {
      text = utf8.decode(bytes.sublist(_header, _header + nameLength));
    } on FormatException {
      return null;
    }
    final name = text.replaceAll(RegExp(r'[\x00-\x1F\x7F]'), '').trim();
    if (name.isEmpty) return null;
    return Beacon(toHex(Uint8List.fromList(bytes.sublist(7, 7 + _idBytes))), port, name);
  }
}
