import 'dart:typed_data';

final class AudioPacket {
  const AudioPacket(this.streamId, this.timestamp, this.frame);

  static const headerLength = 6;

  final int streamId;
  final int timestamp;
  final Uint8List frame;

  /// AAC-LC at 48 kHz in two channels is the only format, so it is not sent along.
  static Uint8List encode(int streamId, int timestamp, Uint8List frame) {
    final out = Uint8List(headerLength + frame.length);
    final view = ByteData.sublistView(out);
    view.setUint16(0, streamId, Endian.big);
    view.setInt32(2, timestamp, Endian.big);
    out.setRange(headerLength, out.length, frame);
    return out;
  }

  static AudioPacket? decode(Uint8List payload) {
    if (payload.length <= headerLength) return null;
    final view = ByteData.sublistView(payload);
    return AudioPacket(view.getUint16(0, Endian.big), view.getInt32(2, Endian.big), payload.sublist(headerLength));
  }
}

abstract final class Adts {
  static const headerLength = 7;

  static Uint8List wrap(Uint8List frame) {
    final length = headerLength + frame.length;
    final out = Uint8List(length);
    out[0] = 0xFF;
    out[1] = 0xF1; // MPEG-4, no CRC
    out[2] = (1 << 6) | (3 << 2); // AAC-LC, 48 kHz
    out[3] = (2 << 6) | (length >> 11); // two channels
    out[4] = (length >> 3) & 0xFF;
    out[5] = ((length & 7) << 5) | 0x1F;
    out[6] = 0xFC; // variable bit rate, one frame
    out.setRange(headerLength, length, frame);
    return out;
  }
}
