import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/net/audio.dart';
import 'package:manifold_hub/net/control.dart';
import 'package:manifold_hub/net/crypto.dart';
import 'package:manifold_hub/net/devices.dart';
import 'package:manifold_hub/net/noise.dart';
import 'package:manifold_hub/net/session.dart';
import 'package:manifold_hub/net/video.dart';
import 'package:manifold_hub/net/wire.dart';

/// The Android hub and this one have to speak the same bytes, so both suites read test/fixtures/compat.txt.
void main() {
  final expected = {
    for (final line in File('test/fixtures/compat.txt').readAsLinesSync().where((line) => line.trim().isNotEmpty))
      line.substring(0, line.indexOf('=')): line.substring(line.indexOf('=') + 1),
  };

  void check(String name, Uint8List bytes) => expect(toHex(bytes), expected[name], reason: name);

  test('session packets match the fixture', () {
    final sender = Session(localIndex: 1, remoteIndex: 2, keys: TransportKeys(send: Uint8List(32)..fillRange(0, 32, 1), receive: Uint8List(32)..fillRange(0, 32, 2)));

    check('session_control', sender.seal(StreamKind.control, Uint8List.fromList([0xAA, 0xBB])));
    check('session_video', sender.seal(StreamKind.video, Uint8List.fromList([1, 2, 3, 4])));
  });

  test('control messages match the fixture', () {
    check('control_subscribe', ControlCodec.encode(3, const Subscribe(7, 'alpha', 720, 1080, 2500)));
    check('control_subscribe_audio', ControlCodec.encode(3, const Subscribe(7, 'alpha', 720, 1080, 2500, audio: true)));
    check(
      'control_feedlist',
      ControlCodec.encode(4, const FeedList([FeedInfo('alpha', 720, 1080, 30, false), FeedInfo('camera', 1280, 720, 30, true)])),
    );
    check('control_feedlist_sound', ControlCodec.encode(5, const FeedList([FeedInfo('PC sound', 0, 0, 0, true, soundOnly: true)])));
    check('control_keyframe', ControlCodec.encode(5, const KeyframeRequest(9)));
    check('control_refused', ControlCodec.encode(7, const SubscribeRefused(9, Refusal.notShared)));
    check('control_ack', ControlCodec.encodeAck(77));
    check('control_confirm', ControlCodec.encode(6, const PairConfirm()));
  });

  test('audio packets match the fixture', () {
    check('audiopacket', AudioPacket.encode(12, 90000, Uint8List.fromList([1, 2, 3, 4, 5])));
  });

  test('video fragments match the fixture', () {
    final small = Fragmenter.split(Frame(9, 90000, true, Uint8List.fromList([for (var i = 0; i < 100; i++) (i * 7) & 0xFF]))).single;
    check('fragment_small', small);
    check('videopacket_small', VideoPacket.encode(12, small));

    final large = Fragmenter.split(Frame(-2, -90000, false, Uint8List.fromList([for (var i = 0; i < 3000; i++) (i * 31) & 0xFF])));
    expect(large.length, int.parse(expected['fragment_large_count']!));
    check('fragment_large_hash', Crypto.sha256(large));
  });

  test('handshake packets match the fixture', () {
    check('wire_hello', Wire.encode(HandshakePacket(Wire.typeHello, 1, -5, 77, Uint8List.fromList([1, 2, 3]))));
    check('wire_pair', Wire.encode(HandshakePacket(Wire.typePair, 0, 123456, 0, Uint8List(0))));
  });

  test('a packet sealed by the Kotlin fixture opens here', () {
    // The fixture was made by a sender with the keys below, so the receiving side holds them swapped.
    final receiver = Session(
      localIndex: 2,
      remoteIndex: 1,
      keys: TransportKeys(send: Uint8List(32)..fillRange(0, 32, 2), receive: Uint8List(32)..fillRange(0, 32, 1)),
    );

    final opened = receiver.open(fromHex(expected['session_control']!)!);

    expect(opened?.stream, StreamKind.control);
    expect(opened?.payload, [0xAA, 0xBB]);
  });
}
