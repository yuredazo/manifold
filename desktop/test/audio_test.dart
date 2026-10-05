import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/net/audio.dart';
import 'package:manifold_hub/net/devices.dart';

void main() {
  group('AudioPacket', () {
    test('survives encoding', () {
      final frame = Uint8List.fromList([for (var i = 0; i < 300; i++) i & 0xFF]);

      final decoded = AudioPacket.decode(AudioPacket.encode(65535, -1000, frame))!;

      expect(decoded.streamId, 65535);
      expect(decoded.timestamp, -1000);
      expect(decoded.frame, frame);
    });

    test('without a frame is not decoded', () {
      expect(AudioPacket.decode(Uint8List(0)), isNull);
      expect(AudioPacket.decode(Uint8List(AudioPacket.headerLength)), isNull);
    });
  });

  group('Adts', () {
    test('header of a 100 byte frame is the one a 48 kHz stereo AAC-LC stream has', () {
      final wrapped = Adts.wrap(Uint8List(100));

      // Worked out by hand from the ADTS layout: length 107 = 0b1101011.
      expect(toHex(wrapped.sublist(0, Adts.headerLength)), 'fff14c800d7ffc');
      expect(wrapped.length, 107);
    });

    test('keeps the frame after the header and spreads a long length over the header', () {
      final frame = Uint8List.fromList([for (var i = 0; i < 3000; i++) i & 0xFF]);

      final wrapped = Adts.wrap(frame);

      expect(wrapped.sublist(Adts.headerLength), frame);
      final length = ((wrapped[3] & 3) << 11) | (wrapped[4] << 3) | (wrapped[5] >> 5);
      expect(length, 3007);
    });
  });
}
