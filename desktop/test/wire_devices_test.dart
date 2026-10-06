import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/net/crypto.dart';
import 'package:manifold_hub/net/devices.dart';
import 'package:manifold_hub/net/noise.dart';
import 'package:manifold_hub/net/session.dart';
import 'package:manifold_hub/net/wire.dart';

void main() {
  group('wire', () {
    test('a handshake packet keeps every field', () {
      final packet = HandshakePacket(Wire.typeHello, 1, -5, 77, Uint8List.fromList([1, 2, 3]));
      final decoded = Wire.decodeHandshake(Wire.encode(packet))!;
      expect(decoded.type, Wire.typeHello);
      expect(decoded.step, 1);
      expect(decoded.senderIndex, -5);
      expect(decoded.receiverIndex, 77);
      expect(decoded.message, [1, 2, 3]);
    });

    test('other datagrams are not handshakes', () {
      expect(Wire.decodeHandshake(Uint8List(0)), isNull);
      expect(Wire.decodeHandshake(Uint8List(9)), isNull);
      expect(Wire.decodeHandshake(Uint8List(40)), isNull);
      expect(Wire.decodeHandshake(Uint8List(40)..fillRange(0, 40, 1)..[0] = Session.typeData), isNull);
    });

    test('the receiver of a data packet is read without decrypting', () {
      final alice = NoiseHandshake(Pattern.xx, true, Crypto.generateKeyPair());
      final bob = NoiseHandshake(Pattern.xx, false, Crypto.generateKeyPair());
      bob.readMessage(alice.writeMessage());
      alice.readMessage(bob.writeMessage());
      bob.readMessage(alice.writeMessage());
      final datagram = Session(localIndex: 1, remoteIndex: 4242, keys: alice.split()).seal(StreamKind.control, Uint8List.fromList([1]));

      expect(Wire.dataReceiver(datagram), 4242);
      expect(Wire.dataReceiver(Uint8List(3)), isNull);
      expect(Wire.dataReceiver(Wire.encode(HandshakePacket(Wire.typePair, 0, 1, 0, Uint8List(20)))), isNull);
    });

    test('addresses typed by the owner are parsed', () {
      expect(Address.parse('192.168.0.103:47200', 1), const Address('192.168.0.103', 47200));
      expect(Address.parse('  192.168.0.103  ', 9), const Address('192.168.0.103', 9));
      expect(Address.parse('hub.example.com:5000', 1), const Address('hub.example.com', 5000));
    });

    test('unusable addresses are refused', () {
      for (final bad in ['', '   ', ':4000', 'host:', 'host:abc', 'host:0', 'host:70000', 'two words', 'a:b:c', 'host:-1']) {
        expect(Address.parse(bad, 47200), isNull, reason: "'$bad'");
      }
    });
  });

  group('device book', () {
    final key = Crypto.generateKeyPair().public;
    final keyHex = toHex(key);

    test('a device is found by its public key', () {
      final book = DeviceBook(null, (_) {});
      book.put(Device(publicKey: keyHex, name: 'Beta', address: '10.0.0.2:4000'));
      expect(book.findKey(key)!.name, 'Beta');
      expect(book.findKey(Crypto.generateKeyPair().public), isNull);
    });

    test('changes survive a restart', () {
      final saved = <String>[];
      final first = DeviceBook(null, saved.add);
      first.put(Device(publicKey: keyHex, name: 'Beta', address: '10.0.0.2:4000'));
      first.update(keyHex, (device) => device.copyWith(receive: true));

      final device = DeviceBook(saved.last, (_) {}).findKey(key)!;
      expect(device.name, 'Beta');
      expect(device.address, '10.0.0.2:4000');
      expect(device.receive, isTrue);
      expect(device.send, isFalse);
    });

    test('the camera permission is saved, and a book from before it existed reads as camera off', () {
      final saved = <String>[];
      final book = DeviceBook(null, saved.add)..put(Device(publicKey: keyHex, name: 'Beta', send: true));
      book.update(keyHex, (device) => device.copyWith(sendCamera: true));

      expect(DeviceBook(saved.last, (_) {}).findKey(key)!.sendCamera, isTrue);

      final old = DeviceBook('$keyHex\tBeta\t\ttrue\ttrue', (_) {}).findKey(key)!;
      expect(old.send, isTrue);
      expect(old.sendCamera, isFalse);
    });

    test('the Spout permission is saved, is off in older books, and fields from a newer version are ignored', () {
      final saved = <String>[];
      final book = DeviceBook(null, saved.add)..put(Device(publicKey: keyHex, name: 'Beta', send: true));
      book.update(keyHex, (device) => device.copyWith(sendSpout: true));

      final reloaded = DeviceBook(saved.last, (_) {}).findKey(key)!;
      expect(reloaded.sendSpout, isTrue);
      expect(reloaded.sendCamera, isFalse);

      final withCamera = DeviceBook('$keyHex\tBeta\t\ttrue\ttrue\ttrue', (_) {}).findKey(key)!;
      expect(withCamera.sendCamera, isTrue);
      expect(withCamera.sendSpout, isFalse);

      final newer = DeviceBook('$keyHex\tBeta\t\ttrue\ttrue\ttrue\ttrue\tsomething new', (_) {}).findKey(key)!;
      expect(newer.sendSpout, isTrue);
    });

    test('removing forgets the device', () {
      final saved = <String>[];
      final book = DeviceBook(null, saved.add)..put(Device(publicKey: keyHex, name: 'Beta'));
      book.remove(keyHex);
      expect(book.findKey(key), isNull);
      expect(saved.last.contains(keyHex), isFalse);
    });

    test('a name cannot break the stored format', () {
      final saved = <String>[];
      DeviceBook(null, saved.add).put(Device(publicKey: keyHex, name: 'Evil\tName\nnext line'));
      final reloaded = DeviceBook(saved.last, (_) {});
      expect(reloaded.devices.value.length, 1);
      expect(reloaded.findKey(key), isNotNull);
    });

    test('broken lines and bad keys are ignored', () {
      final book = DeviceBook('garbage\nnot-a-key\tName\t\ttrue\tfalse\n$keyHex\tOk\t\ttrue\tfalse', (_) {});
      expect(book.devices.value.map((device) => device.name), ['Ok']);
    });

    test('the fingerprint is a stable hash of the key', () {
      final device = Device(publicKey: keyHex, name: 'Beta');
      expect(device.fingerprint.length, 64);
      expect(device.fingerprint, fingerprintOf(key));
      expect(readableFingerprint(device.fingerprint).split(' ').length, 4);
    });
  });
}
