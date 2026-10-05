import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/net/control.dart';
import 'package:manifold_hub/net/rate_control.dart';
import 'package:manifold_hub/net/retransmit_store.dart';
import 'package:manifold_hub/net/video.dart';

const _second = 1000000000;

List<Uint8List> _fragments(int size) => Fragmenter.split(Frame(1, 0, false, Uint8List(size)));

StreamReport _report({int lostFrames = 0, int fragments = 400, int resendRequests = 0}) =>
    StreamReport(streamId: 1, windowMs: 500, fragments: fragments, resendRequests: resendRequests, lostFrames: lostFrames, jitterMs10: 0);

void main() {
  group('retransmit store', () {
    test('the requested fragments come back unchanged', () {
      final store = RetransmitStore();
      final kept = _fragments(10000);
      store.remember(1, kept, 0);

      final again = store.fragments(1, [0, 2]);

      expect(again, [kept[0], kept[2]]);
    });

    test('no indexes means the whole frame', () {
      final store = RetransmitStore();
      final kept = _fragments(10000);
      store.remember(1, kept, 0);

      expect(store.fragments(1, const []), hasLength(kept.length));
    });

    test('a frame that was never kept or is out of range gives nothing', () {
      final store = RetransmitStore();
      store.remember(1, _fragments(3000), 0);

      expect(store.fragments(2, const []), isEmpty);
      expect(store.fragments(1, [99]), isEmpty);
    });

    test('a frame is answered at most six times', () {
      final store = RetransmitStore();
      store.remember(1, _fragments(3000), 0);

      final answers = [for (var i = 0; i < 10; i++) store.fragments(1, const [])].where((fragments) => fragments.isNotEmpty).length;

      expect(answers, 6);
    });

    test('old frames are forgotten', () {
      final store = RetransmitStore(keepFor: _second);
      store.remember(1, _fragments(3000), 0);
      store.remember(2, _fragments(3000), 2 * _second);

      expect(store.fragments(1, const []), isEmpty);
      expect(store.fragments(2, const []), isNotEmpty);
    });

    test('the oldest frames go first when the store is full', () {
      final store = RetransmitStore(keepBytes: 25000);
      for (var id = 1; id <= 4; id++) {
        store.remember(id, _fragments(10000), id);
      }

      expect(store.fragments(1, const []), isEmpty);
      expect(store.fragments(2, const []), isEmpty);
      expect(store.fragments(4, const []), isNotEmpty);
    });
  });

  group('rate control', () {
    test('it starts at what the receiver asked for', () {
      expect(RateControl(minKbps: 200, maxKbps: 4000).kbps, 4000);
    });

    test('a frame lost for good makes it back off', () {
      final control = RateControl(minKbps: 200, maxKbps: 4000);

      expect(control.onReport(_report(lostFrames: 1), 0), 3400);
    });

    test('it backs off at most once a second', () {
      final control = RateControl(minKbps: 200, maxKbps: 4000);
      control.onReport(_report(lostFrames: 1), 0);

      expect(control.onReport(_report(lostFrames: 1), _second ~/ 2), isNull);
      expect(control.onReport(_report(lostFrames: 1), _second), 2890);
    });

    test('it never goes below the floor', () {
      final control = RateControl(minKbps: 500, maxKbps: 600);

      for (var i = 0; i < 10; i++) {
        control.onReport(_report(lostFrames: 3), i * 2 * _second);
      }

      expect(control.kbps, 500);
    });

    test('pieces that were lost and recovered are not a reason to back off', () {
      final control = RateControl(minKbps: 200, maxKbps: 4000);

      for (var i = 0; i < 20; i++) {
        expect(control.onReport(_report(fragments: 380, resendRequests: 20), i * _second), isNull);
      }
    });

    test('it raises the bitrate after a few clean reports and never past the ask', () {
      final control = RateControl(minKbps: 200, maxKbps: 4000);
      control.onReport(_report(lostFrames: 1), 0);
      final afterBackOff = control.kbps;

      var now = _second;
      for (var i = 0; i < 6; i++) {
        control.onReport(_report(), now);
        now += _second ~/ 2;
      }
      expect(control.kbps, greaterThan(afterBackOff));

      for (var i = 0; i < 400; i++) {
        control.onReport(_report(), now);
        now += _second ~/ 2;
      }
      expect(control.kbps, 4000);
    });
  });
}
