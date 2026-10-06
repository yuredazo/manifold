import 'dart:typed_data';

import 'crypto.dart';
import 'noise.dart';

enum StreamKind {
  control(1),
  video(2),
  audio(3);

  const StreamKind(this.id);

  final int id;

  static StreamKind? of(int id) {
    for (final kind in values) {
      if (kind.id == id) return kind;
    }
    return null;
  }
}

final class Opened {
  const Opened(this.stream, this.payload);

  final StreamKind stream;
  final Uint8List payload;
}

/// Remembers which packet counters have been seen, so a recorded packet cannot be played back.
final class ReplayWindow {
  ReplayWindow([this.size = 1024]) : _seen = List.filled(size, false);

  final int size;
  final List<bool> _seen;
  int _newest = -1;

  bool accept(int counter) {
    if (counter < 0) return false;
    if (counter > _newest) {
      if (counter - _newest >= size) {
        _seen.fillRange(0, size, false);
      } else {
        for (var c = _newest + 1; c <= counter; c++) {
          _seen[c % size] = false;
        }
      }
      _newest = counter;
      _seen[counter % size] = true;
      return true;
    }
    if (_newest - counter >= size) return false;
    final slot = counter % size;
    if (_seen[slot]) return false;
    _seen[slot] = true;
    return true;
  }
}

/// The counter is the nonce and must never repeat, so [seal] throws long before it could run out.
final class Session {
  Session({required this.localIndex, required this.remoteIndex, required this._keys});

  static const typeData = 3;
  static const headerLength = 13;
  static const maxDatagram = 1200;

  static const maxPayload = maxDatagram - headerLength - Crypto.tagLength - 1;

  static const _rekeyLimit = 1 << 40;

  final int localIndex;
  final int remoteIndex;
  final TransportKeys _keys;
  int _sendCounter = 0;
  final ReplayWindow _window = ReplayWindow();

  Uint8List seal(StreamKind stream, Uint8List payload) {
    if (payload.length > maxPayload) throw ArgumentError('payload of ${payload.length} bytes does not fit one datagram');
    if (_sendCounter >= _rekeyLimit) throw StateError('this session has sent too many packets and must be replaced');
    final counter = _sendCounter++;
    final header = Uint8List(headerLength);
    final view = ByteData.sublistView(header);
    header[0] = typeData;
    view.setInt32(1, remoteIndex, Endian.little);
    view.setUint64(5, counter, Endian.little);
    final body = Crypto.seal(_keys.send, counter, header, Uint8List.fromList([stream.id, ...payload]));
    return Uint8List.fromList([...header, ...body]);
  }

  Opened? open(Uint8List datagram) {
    if (datagram.length < headerLength + Crypto.tagLength + 1) return null;
    final header = datagram.sublist(0, headerLength);
    final view = ByteData.sublistView(header);
    if (header[0] != typeData) return null;
    if (view.getInt32(1, Endian.little) != localIndex) return null;
    final counter = view.getUint64(5, Endian.little);
    final plain = Crypto.open(_keys.receive, counter, header, datagram.sublist(headerLength));
    if (plain == null) return null;
    final stream = StreamKind.of(plain[0]);
    if (stream == null) return null;
    if (!_window.accept(counter)) return null;
    return Opened(stream, plain.sublist(1));
  }
}
