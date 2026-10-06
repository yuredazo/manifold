import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/network/protocol/crypto.dart';
import 'package:manifold_hub/network/protocol/noise.dart';
import 'package:manifold_hub/network/protocol/session.dart';

({Session a, Session b}) _connected() {
  final alice = NoiseHandshake(Pattern.xx, true, Crypto.generateKeyPair());
  final bob = NoiseHandshake(Pattern.xx, false, Crypto.generateKeyPair());
  bob.readMessage(alice.writeMessage());
  alice.readMessage(bob.writeMessage());
  bob.readMessage(alice.writeMessage());
  return (
    a: Session(localIndex: 1, remoteIndex: 2, keys: alice.split()),
    b: Session(localIndex: 2, remoteIndex: 1, keys: bob.split()),
  );
}

Uint8List _bytes(List<int> values) => Uint8List.fromList(values);

void main() {
  group('replay window', () {
    test('a new counter is accepted once only', () {
      final window = ReplayWindow();
      expect(window.accept(0), isTrue);
      expect(window.accept(0), isFalse);
      expect(window.accept(1), isTrue);
      expect(window.accept(1), isFalse);
    });

    test('an older counter inside the window is accepted once', () {
      final window = ReplayWindow(64);
      expect(window.accept(10), isTrue);
      expect(window.accept(7), isTrue);
      expect(window.accept(7), isFalse);
      expect(window.accept(9), isTrue);
    });

    test('a counter older than the window is refused', () {
      final window = ReplayWindow(64);
      expect(window.accept(100), isTrue);
      expect(window.accept(36), isFalse);
      expect(window.accept(37), isTrue);
    });

    test('a big jump forgets everything before', () {
      final window = ReplayWindow(64);
      expect(window.accept(5), isTrue);
      expect(window.accept(1005), isTrue);
      expect(window.accept(5), isFalse);
      expect(window.accept(1005), isFalse);
    });

    test('the window keeps working after it wraps around', () {
      final window = ReplayWindow(64);
      for (var counter = 0; counter < 500; counter++) {
        expect(window.accept(counter), isTrue, reason: 'counter $counter');
      }
      for (var counter = 440; counter < 500; counter++) {
        expect(window.accept(counter), isFalse, reason: 'replay of $counter');
      }
      expect(window.accept(500), isTrue);
    });

    test('a negative counter is refused', () {
      expect(ReplayWindow().accept(-1), isFalse);
    });
  });

  group('session', () {
    test('a packet arrives intact in both directions', () {
      final pair = _connected();

      final atBob = pair.b.open(pair.a.seal(StreamKind.video, _bytes('frame'.codeUnits)))!;
      expect(atBob.stream, StreamKind.video);
      expect(atBob.payload, 'frame'.codeUnits);

      final atAlice = pair.a.open(pair.b.seal(StreamKind.control, _bytes('ack'.codeUnits)))!;
      expect(atAlice.payload, 'ack'.codeUnits);
    });

    test('the content is not visible on the wire', () {
      final datagram = _connected().a.seal(StreamKind.video, _bytes('secret secret secret'.codeUnits));
      expect(String.fromCharCodes(datagram).contains('secret'), isFalse);
    });

    test('a recorded packet cannot be played again', () {
      final pair = _connected();
      final datagram = pair.a.seal(StreamKind.control, _bytes('once'.codeUnits));
      expect(pair.b.open(datagram), isNotNull);
      expect(pair.b.open(datagram), isNull);
    });

    test('any changed byte is rejected', () {
      final pair = _connected();
      final datagram = pair.a.seal(StreamKind.video, _bytes('payload'.codeUnits));
      for (var position = 0; position < datagram.length; position++) {
        final tampered = Uint8List.fromList(datagram)..[position] ^= 1;
        expect(pair.b.open(tampered), isNull, reason: 'byte $position');
      }
      expect(pair.b.open(datagram), isNotNull, reason: 'the untouched packet still opens');
    });

    test('a packet for another session is ignored', () {
      final first = _connected();
      final second = _connected();
      expect(second.b.open(first.a.seal(StreamKind.control, _bytes([1]))), isNull);
    });

    test('a packet cannot be turned around and sent back', () {
      final pair = _connected();
      expect(pair.a.open(pair.a.seal(StreamKind.control, _bytes([1]))), isNull);
    });

    test('packets may arrive out of order', () {
      final pair = _connected();
      final sent = List.generate(5, (i) => pair.a.seal(StreamKind.video, _bytes([i])));
      for (final i in [2, 0, 4, 1, 3]) {
        expect(pair.b.open(sent[i])!.payload, [i]);
      }
    });

    test('junk and short datagrams are dropped', () {
      final pair = _connected();
      expect(pair.b.open(Uint8List(0)), isNull);
      expect(pair.b.open(Uint8List(5)), isNull);
      expect(pair.b.open(Uint8List(200)..fillRange(0, 200, 7)), isNull);
    });

    test('the largest payload fits in one datagram', () {
      final pair = _connected();
      final payload = Uint8List.fromList([for (var i = 0; i < Session.maxPayload; i++) i & 0xFF]);
      final datagram = pair.a.seal(StreamKind.video, payload);
      expect(datagram.length, Session.maxDatagram);
      expect(pair.b.open(datagram)!.payload, payload);
      expect(() => pair.a.seal(StreamKind.video, Uint8List(Session.maxPayload + 1)), throwsArgumentError);
    });

    test('pairing gives both sides the same code to compare', () {
      final alice = NoiseHandshake(Pattern.xx, true, Crypto.generateKeyPair());
      final bob = NoiseHandshake(Pattern.xx, false, Crypto.generateKeyPair());
      bob.readMessage(alice.writeMessage());
      alice.readMessage(bob.writeMessage());
      bob.readMessage(alice.writeMessage());
      expect(alice.handshakeHash, bob.handshakeHash);
    });
  });
}
