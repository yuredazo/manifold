import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/network/discovery.dart';
import 'package:manifold_hub/network/protocol/beacon.dart';

const _id = '0123456789abcdef';

Beacon _beacon(String id, String name, {int port = 47200}) => Beacon(id.padRight(16, '0'), port, name);

void main() {
  group('beacons', () {
    test('survive the round trip', () {
      final decoded = BeaconCodec.decode(BeaconCodec.encode(const Beacon(_id, 47200, 'Pixel')));

      expect(decoded?.id, _id);
      expect(decoded?.port, 47200);
      expect(decoded?.name, 'Pixel');
    });

    test('a long name is cut to the limit without splitting a character', () {
      final name = BeaconCodec.decode(BeaconCodec.encode(Beacon(_id, 1, 'é' * 50)))!.name;

      expect(utf8.encode(name).length, lessThanOrEqualTo(BeaconCodec.maxNameBytes));
      expect(name.runes.every((rune) => rune == 0xE9), isTrue);
    });

    test('control characters are dropped from the name', () {
      expect(BeaconCodec.decode(BeaconCodec.encode(const Beacon(_id, 1, 'Pi\u0000x\nel')))!.name, 'Pixel');
    });

    test('datagrams that are not beacons are ignored', () {
      final good = BeaconCodec.encode(const Beacon(_id, 47200, 'Pixel'));
      Uint8List changed(void Function(Uint8List bytes) edit) {
        final copy = Uint8List.fromList(good);
        edit(copy);
        return copy;
      }

      expect(BeaconCodec.decode(Uint8List.sublistView(good, 0, 10)), isNull);
      expect(BeaconCodec.decode(changed((b) => b[0] = 0x58)), isNull);
      expect(BeaconCodec.decode(changed((b) => b[4] = 2)), isNull);
      expect(BeaconCodec.decode(changed((b) => b[5] = b[6] = 0)), isNull);
      expect(BeaconCodec.decode(Uint8List.sublistView(good, 0, good.length - 1)), isNull);
      expect(BeaconCodec.decode(changed((b) => b[15] = 65)), isNull);
      expect(BeaconCodec.decode(BeaconCodec.encode(const Beacon(_id, 1, '\u0001 '))), isNull);
    });

    test('trailing bytes from a later version do not matter', () {
      final longer = Uint8List.fromList([...BeaconCodec.encode(const Beacon(_id, 47200, 'Pixel')), 1, 2, 3]);

      expect(BeaconCodec.decode(longer)!.name, 'Pixel');
    });

    test('an id that is not eight bytes of hex is refused', () {
      for (final bad in ['xyz', '00', '0123456789abcdef00']) {
        expect(() => BeaconCodec.encode(Beacon(bad, 1, 'n')), throwsArgumentError, reason: bad);
      }
    });
  });

  group('the nearby list', () {
    Duration at(int ms) => Duration(milliseconds: ms);

    test('lists devices by name', () {
      final book = NearbyBook()
        ..hear(_beacon('b', 'tablet'), '192.168.1.5', at(0))
        ..hear(_beacon('a', 'Phone'), '192.168.1.6', at(0));

      expect(book.current(at(100)).map((d) => d.name), ['Phone', 'tablet']);
    });

    test('a device that stops announcing drops out', () {
      final book = NearbyBook()
        ..hear(_beacon('a', 'Phone'), '192.168.1.6', at(0))
        ..hear(_beacon('b', 'Tablet'), '192.168.1.5', at(3000));

      expect(book.current(at(5000)).map((d) => d.name), ['Tablet']);
      expect(book.current(at(9000)), isEmpty);
    });

    test('the same device announcing again keeps its newest address', () {
      final book = NearbyBook()
        ..hear(_beacon('a', 'Phone'), '192.168.1.6', at(0))
        ..hear(_beacon('a', 'Phone', port: 5000), '192.168.1.9', at(1000));

      final only = book.current(at(1500)).single;
      expect(only.host, '192.168.1.9');
      expect(only.port, 5000);
    });

    test('a noisy network cannot grow the list without bound', () {
      final book = NearbyBook(limit: 3);
      for (var i = 0; i < 10; i++) {
        book.hear(_beacon(i.toRadixString(16), 'd$i'), '10.0.0.$i', at(0));
      }

      expect(book.current(at(100)), hasLength(3));
    });
  });
}
