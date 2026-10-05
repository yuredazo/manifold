import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/net/control.dart';
import 'package:manifold_hub/net/control_channel.dart';
import 'package:manifold_hub/net/session.dart';

Control? _roundTrip(Control control) => switch (ControlCodec.decode(ControlCodec.encode(7, control))) {
      DecodedMessage(:final control) => control,
      _ => null,
    };

List<FeedInfo> _feedsOf(Control control) => (control as FeedList).feeds;

int? _unrecognizedId(Decoded? decoded) => decoded is DecodedUnrecognized ? decoded.id : null;

class _Wired {
  final received = <Control>[];
  final sentByB = <Uint8List>[];
  bool failed = false;
  bool Function(Uint8List) lossRule = (_) => false;
  final _toB = <Uint8List>[];
  final _toA = <Uint8List>[];
  late final a = ControlChannel(transmit: _toB.add, onMessage: (_) {}, onFailed: () => failed = true);
  late final b = ControlChannel(
    transmit: (bytes) {
      _toA.add(bytes);
      sentByB.add(bytes);
    },
    onMessage: received.add,
    onFailed: () {},
  );

  void pump() {
    for (var round = 0; round < 10; round++) {
      final forB = _toB.toList()..removeWhere(lossRule);
      final forA = _toA.toList()..removeWhere(lossRule);
      _toB.clear();
      _toA.clear();
      forB.forEach(b.receive);
      forA.forEach(a.receive);
    }
  }
}

void main() {
  group('codec', () {
    test('every message survives encoding', () {
      for (final control in const [Ping(), PairConfirm(), PairReject(), Bye()]) {
        expect(_roundTrip(control).runtimeType, control.runtimeType);
      }
      final feeds = [const FeedInfo('alpha', 720, 1080, 30, false), const FeedInfo('cam é', 1920, 1080, 60, true)];
      expect(_feedsOf(_roundTrip(FeedList(feeds))!), feeds);
      expect(_feedsOf(_roundTrip(const FeedList([]))!), isEmpty);
    });

    test('the id and ack are carried', () {
      expect((ControlCodec.decode(ControlCodec.encodeAck(42)) as DecodedAck).id, 42);
      expect((ControlCodec.decode(ControlCodec.encode(9, const Bye())) as DecodedMessage).id, 9);
    });

    test('a feed list never overflows a packet', () {
      final many = List.generate(200, (i) => FeedInfo('feed number $i with a long name', 1, 1, 1, false));
      final bytes = ControlCodec.encode(1, FeedList(many));
      expect(bytes.length, lessThanOrEqualTo(Session.maxPayload));
      final decoded = _feedsOf((ControlCodec.decode(bytes) as DecodedMessage).control);
      expect(decoded, isNotEmpty);
      expect(decoded.length, lessThanOrEqualTo(maxFeeds));
      expect(decoded.first, many.first);
    });

    test('feeds with unusable names from the other side are dropped', () {
      final sent = ControlCodec.encode(
        1,
        const FeedList([FeedInfo('bad\nname', 1, 1, 1, false), FeedInfo('   ', 1, 1, 1, false), FeedInfo('good', 1, 1, 1, false)]),
      );
      expect(_feedsOf((ControlCodec.decode(sent) as DecodedMessage).control).map((feed) => feed.name), ['good']);
    });

    test('feeds with an absurd size from the other side are dropped', () {
      final sent = ControlCodec.encode(1, const FeedList([FeedInfo('huge', 60000, 1, 1, false), FeedInfo('fine', 1920, 1080, 30, false)]));
      expect(_feedsOf((ControlCodec.decode(sent) as DecodedMessage).control).map((feed) => feed.name), ['fine']);
    });

    test('junk is refused without throwing', () {
      expect(ControlCodec.decode(Uint8List(0)), isNull);
      expect(ControlCodec.decode(Uint8List(4)), isNull);
      final truncated = ControlCodec.encode(1, const FeedList([FeedInfo('alpha', 720, 1080, 30, false)]));
      for (var length = 5; length < truncated.length; length++) {
        expect(_unrecognizedId(ControlCodec.decode(truncated.sublist(0, length))), 1, reason: 'length $length');
      }
      final liar = ControlCodec.encode(1, const FeedList([]))..[5] = 0x7F..[6] = 0x7F;
      expect(_unrecognizedId(ControlCodec.decode(liar)), 1);
    });

    test('a kind from a newer version is recognized as unknown and keeps its id', () {
      expect(_unrecognizedId(ControlCodec.decode(Uint8List.fromList([99, 0, 0, 0, 1]))), 1);
      expect(_unrecognizedId(ControlCodec.decode(Uint8List.fromList([40, 1, 2, 3, 4, 9, 9]))), 0x01020304);
    });

    test('subscription messages survive encoding', () {
      const subscribe = Subscribe(65535, 'alpha', 720, 1080, 2500);
      expect(_roundTrip(subscribe), subscribe);
      const withSound = Subscribe(65535, 'alpha', 720, 1080, 2500, audio: true);
      expect(_roundTrip(withSound), withSound);
      expect((_roundTrip(const Unsubscribe(3)) as Unsubscribe).streamId, 3);
      expect((_roundTrip(const KeyframeRequest(65000)) as KeyframeRequest).streamId, 65000);
    });

    test('a refusal carries its reason and an unknown reason reads as failed', () {
      for (final reason in Refusal.values) {
        expect(_roundTrip(SubscribeRefused(9, reason)), SubscribeRefused(9, reason));
      }
      final decoded = ControlCodec.decode(Uint8List.fromList([14, 0, 0, 0, 5, 0, 9, 99]));
      expect((decoded as DecodedMessage).control, const SubscribeRefused(9, Refusal.failed));
      expect(ControlCodec.needsAck(const SubscribeRefused(9, Refusal.notFound)), isTrue);
    });

    test('measurement messages survive encoding and are never resent', () {
      const request = TimeRequest(1234567890123);
      const reply = TimeReply(0x7FFFFFFFFFFFFFFF);
      const stats = SenderStats(streamId: 9, bitrateKbps: 4300, fps10: 598, encodeMs10: 87, sendMs10: 12);

      expect((_roundTrip(request) as TimeRequest).sentAt, request.sentAt);
      expect((_roundTrip(reply) as TimeReply).sentAt, reply.sentAt);
      expect(_roundTrip(stats), stats);
      for (final control in const [request, reply, stats, Ping()]) {
        expect(ControlCodec.needsAck(control), isFalse, reason: '$control');
      }
      expect(ControlCodec.needsAck(const Subscribe(1, 'alpha', 1, 1, 1)), isTrue);
    });

    test('the bytes of the measurement messages are the ones the Android hub writes', () {
      expect(ControlCodec.encode(1, const TimeRequest(0x0102030405060708)), [9, 0, 0, 0, 1, 1, 2, 3, 4, 5, 6, 7, 8]);
      expect(ControlCodec.encode(2, const SenderStats(streamId: 3, bitrateKbps: 2000, fps10: 300, encodeMs10: 80, sendMs10: 5)), [
        11, 0, 0, 0, 2, 0, 3, 0x07, 0xD0, 0x01, 0x2C, 0, 80, 0, 5,
      ]);
    });

    test('a request to send fragments again survives encoding and is never resent', () {
      const some = Nack(streamId: 7, frameId: -5, indexes: [0, 3, 4095]);
      const whole = Nack(streamId: 7, frameId: 1000000, indexes: []);

      expect(_roundTrip(some), some);
      expect(_roundTrip(whole), whole);
      expect(ControlCodec.needsAck(some), isFalse);
    });

    test('a request for more pieces than fit becomes a request for the whole frame', () {
      final huge = Nack(streamId: 1, frameId: 2, indexes: List.generate(Nack.maxIndexes + 1, (i) => i));

      expect(_roundTrip(huge), const Nack(streamId: 1, frameId: 2, indexes: []));
      final bytes = ControlCodec.encode(1, Nack(streamId: 1, frameId: 2, indexes: List.generate(Nack.maxIndexes, (i) => i)));
      expect(bytes.length, lessThanOrEqualTo(Session.maxPayload));
    });

    test('a request that claims too many pieces is not decoded', () {
      final bytes = ControlCodec.encode(1, const Nack(streamId: 1, frameId: 2, indexes: [5]))..[11] = 0x7F..[12] = 0x7F;

      expect(_unrecognizedId(ControlCodec.decode(bytes)), 1);
    });

    test('the bytes of a request to send again are the ones the Android hub writes', () {
      expect(ControlCodec.encode(3, const Nack(streamId: 7, frameId: 256, indexes: [1, 9])), [12, 0, 0, 0, 3, 0, 7, 0, 0, 0x01, 0x00, 0, 2, 0, 1, 0, 9]);
    });

    test('a report on a stream survives encoding and is never resent', () {
      const report = StreamReport(streamId: 5, windowMs: 500, fragments: 420, resendRequests: 6, lostFrames: 1, jitterMs10: 38);

      expect(_roundTrip(report), report);
      expect(ControlCodec.needsAck(report), isFalse);
      expect(
        _roundTrip(const StreamReport(streamId: 5, windowMs: 900000, fragments: 900000, resendRequests: 900000, lostFrames: 900000, jitterMs10: 900000)),
        const StreamReport(streamId: 5, windowMs: 65535, fragments: 65535, resendRequests: 65535, lostFrames: 65535, jitterMs10: 65535),
      );
    });

    test('the bytes of a report are the ones the Android hub writes', () {
      expect(
        ControlCodec.encode(4, const StreamReport(streamId: 5, windowMs: 500, fragments: 420, resendRequests: 6, lostFrames: 1, jitterMs10: 38)),
        [13, 0, 0, 0, 4, 0, 5, 0x01, 0xF4, 0x01, 0xA4, 0, 6, 0, 1, 0, 38],
      );
    });

    test('the frame rate travels with a subscribe and is left out when it is the default', () {
      const sixty = Subscribe(1, 'alpha', 720, 1080, 2000, audio: true, fps: 60);
      const plain = Subscribe(1, 'alpha', 720, 1080, 2000, audio: true);

      expect(_roundTrip(sixty), sixty);
      expect((_roundTrip(plain) as Subscribe).fps, 30);
      expect(ControlCodec.encode(1, sixty).length, ControlCodec.encode(1, plain).length + 1, reason: 'the default adds no byte, so older hubs read the same message');
    });

    test('a frame rate out of range is brought back into it', () {
      final bytes = ControlCodec.encode(1, const Subscribe(1, 'alpha', 720, 1080, 2000, fps: 60));

      expect(((ControlCodec.decode(bytes..[bytes.length - 1] = 0xC8) as DecodedMessage).control as Subscribe).fps, 60);
      expect(((ControlCodec.decode(bytes..[bytes.length - 1] = 0) as DecodedMessage).control as Subscribe).fps, 1);
    });

    test('sender stats that do not fit the fields are capped', () {
      const stats = SenderStats(streamId: 1, bitrateKbps: 900000, fps10: -5, encodeMs10: 70000, sendMs10: 0);
      expect(_roundTrip(stats), const SenderStats(streamId: 1, bitrateKbps: 65535, fps10: 0, encodeMs10: 65535, sendMs10: 0));
    });

    test('a subscribe request nobody should make is not decoded', () {
      for (final bad in const [
        Subscribe(1, 'bad\nname', 720, 1080, 2000),
        Subscribe(1, '   ', 720, 1080, 2000),
        Subscribe(1, 'alpha', 0, 1080, 2000),
        Subscribe(1, 'alpha', 720, 0, 2000),
        Subscribe(1, 'alpha', 60000, 1080, 2000),
        Subscribe(1, 'alpha', 720, 1080, 0),
      ]) {
        expect(_unrecognizedId(ControlCodec.decode(ControlCodec.encode(1, bad))), 1);
      }
    });

    test('a subscribe without the audio byte means no audio', () {
      final withFlag = ControlCodec.encode(1, const Subscribe(1, 'alpha', 720, 1080, 2000, audio: true));

      final decoded = ControlCodec.decode(withFlag.sublist(0, withFlag.length - 1)) as DecodedMessage;

      expect(decoded.control, const Subscribe(1, 'alpha', 720, 1080, 2000));
    });

    test('a truncated subscribe is refused without throwing', () {
      final full = ControlCodec.encode(1, const Subscribe(1, 'alpha', 720, 1080, 2000));
      // The audio byte is optional, so a request cut right after the name is still a request.
      for (var length = 5; length < full.length - 1; length++) {
        expect(_unrecognizedId(ControlCodec.decode(full.sublist(0, length))), 1, reason: 'length $length');
      }
    });
  });

  group('channel', () {
    test('a message is delivered once and acknowledged', () {
      final wired = _Wired();
      wired.a.send(const PairConfirm(), 0);
      wired.pump();
      wired.a.tick(10000);
      wired.pump();
      expect(wired.received.length, 1);
      expect(wired.failed, isFalse);
    });

    test('a lost message is sent again', () {
      final wired = _Wired();
      var first = true;
      wired.lossRule = (_) {
        final lose = first;
        first = false;
        return lose;
      };
      wired.a.send(const Bye(), 0);
      wired.pump();
      expect(wired.received, isEmpty);
      wired.a.tick(ControlChannel.resendAfterMs + 1);
      wired.pump();
      expect(wired.received.length, 1);
    });

    test('a lost ack does not deliver the message twice', () {
      final wired = _Wired();
      var acks = 0;
      wired.lossRule = (bytes) => bytes[0] == 0 && acks++ == 0;
      wired.a.send(const PairConfirm(), 0);
      wired.pump();
      wired.a.tick(ControlChannel.resendAfterMs + 1);
      wired.pump();
      expect(wired.received.length, 1);
    });

    test('the sender gives up when nothing gets through', () {
      final wired = _Wired();
      wired.lossRule = (_) => true;
      wired.a.send(const PairConfirm(), 0);
      var now = 0;
      for (var i = 0; i < ControlChannel.maxAttempts + 2; i++) {
        now += ControlChannel.resendAfterMs + 1;
        wired.a.tick(now);
      }
      expect(wired.failed, isTrue);
    });

    test('an older feed list never replaces a newer one', () {
      final wired = _Wired();
      wired.b.receive(ControlCodec.encode(2, const FeedList([FeedInfo('new', 1, 1, 1, false)])));
      wired.b.receive(ControlCodec.encode(1, const FeedList([FeedInfo('old', 1, 1, 1, false)])));
      expect(wired.received.map((control) => _feedsOf(control).single.name), ['new']);
    });

    test('pings are not acknowledged or resent', () {
      final wired = _Wired();
      var sent = 0;
      wired.lossRule = (_) {
        sent++;
        return true;
      };
      wired.a.send(const Ping(), 0);
      wired.pump();
      wired.a.tick(100000);
      wired.pump();
      expect(sent, 1);
      expect(wired.failed, isFalse);
    });

    test('a message of an unknown kind is acknowledged and ignored', () {
      final wired = _Wired();
      // What a newer version might send: a kind this one has never heard of.
      final future = Uint8List.fromList([40, 0, 0, 0, 5, 1, 2, 3]);

      wired.b.receive(future);
      wired.b.receive(future);

      expect(wired.received, isEmpty);
      // The sender is told twice, since the first ack may be lost.
      expect(wired.sentByB.map((bytes) => bytes.toList()), [
        [0, 0, 0, 0, 5],
        [0, 0, 0, 0, 5],
      ]);
    });

    test('garbage is ignored', () {
      final wired = _Wired();
      wired.b.receive(Uint8List(0));
      wired.b.receive(Uint8List.fromList([1, 2, 3]));
      wired.b.receive(Uint8List(50)..fillRange(0, 50, 99));
      expect(wired.received, isEmpty);
    });
  });
}
