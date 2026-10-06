import 'dart:typed_data';

import 'session.dart';

final class Address {
  const Address(this.host, this.port);

  final String host;
  final int port;

  static Address? parse(String text, int defaultPort) {
    final trimmed = text.trim();
    if (trimmed.isEmpty || trimmed.contains(RegExp(r'\s'))) return null;
    final colon = trimmed.lastIndexOf(':');
    final host = colon < 0 ? trimmed : trimmed.substring(0, colon);
    final port = colon < 0 ? defaultPort : int.tryParse(trimmed.substring(colon + 1));
    if (port == null || host.isEmpty || host.contains(':') || port < 1 || port > 65535) return null;
    return Address(host, port);
  }

  @override
  String toString() => '$host:$port';

  @override
  bool operator ==(Object other) => other is Address && other.host == host && other.port == port;

  @override
  int get hashCode => Object.hash(host, port);
}

final class HandshakePacket {
  const HandshakePacket(this.type, this.step, this.senderIndex, this.receiverIndex, this.message);

  final int type;
  final int step;
  final int senderIndex;
  final int receiverIndex;
  final Uint8List message;
}

/// The receiver index is 0 in the first message, before the other side has picked one.
abstract final class Wire {
  static const typePair = 1;
  static const typeHello = 2;
  static const handshakeHeader = 10;

  static Uint8List encode(HandshakePacket packet) {
    final out = Uint8List(handshakeHeader + packet.message.length);
    final view = ByteData.sublistView(out);
    out[0] = packet.type;
    out[1] = packet.step;
    view.setInt32(2, packet.senderIndex, Endian.little);
    view.setInt32(6, packet.receiverIndex, Endian.little);
    out.setRange(handshakeHeader, out.length, packet.message);
    return out;
  }

  static HandshakePacket? decodeHandshake(Uint8List datagram) {
    if (datagram.length < handshakeHeader) return null;
    final view = ByteData.sublistView(datagram);
    final type = datagram[0];
    if (type != typePair && type != typeHello) return null;
    return HandshakePacket(
      type,
      datagram[1],
      view.getInt32(2, Endian.little),
      view.getInt32(6, Endian.little),
      datagram.sublist(handshakeHeader),
    );
  }

  static int? dataReceiver(Uint8List datagram) {
    if (datagram.length < Session.headerLength || datagram[0] != Session.typeData) return null;
    return ByteData.sublistView(datagram).getInt32(1, Endian.little);
  }
}
