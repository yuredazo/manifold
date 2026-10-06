import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/network/protocol/session.dart';
import 'package:manifold_hub/network/protocol/video.dart';

const _ms = 1000000;

Frame _frame(int id, int size, {bool keyframe = false}) =>
    Frame(id, id * 3000, keyframe, Uint8List.fromList([for (var i = 0; i < size; i++) (i * 31 + id) & 0xFF]));

List<Frame> _feed(FrameBuffer buffer, Frame frame, {int now = 0, Set<int> drop = const {}, List<int>? order}) {
  final fragments = Fragmenter.split(frame);
  return [
    for (final index in (order ?? List.generate(fragments.length, (i) => i)).where((i) => !drop.contains(i)))
      ...buffer.add(fragments[index], now).map((assembled) => assembled.frame),
  ];
}

void main() {
  test('frames of every size come back identical', () {
    final sizes = [0, 1, 100, Fragmenter.maxChunk - 1, Fragmenter.maxChunk, Fragmenter.maxChunk + 1, 50000, 300000];
    for (var index = 0; index < sizes.length; index++) {
      final original = _frame(index, sizes[index], keyframe: true);
      final rebuilt = _feed(FrameBuffer(), original).single;
      expect(rebuilt.id, original.id, reason: 'size ${sizes[index]}');
      expect(rebuilt.timestamp, original.timestamp);
      expect(rebuilt.keyframe, isTrue);
      expect(rebuilt.encoded, original.encoded, reason: 'size ${sizes[index]}');
    }
  });

  test('every fragment fits in one encrypted packet', () {
    for (final fragment in Fragmenter.split(_frame(1, 200000))) {
      expect(fragment.length, lessThanOrEqualTo(Session.maxPayload));
    }
  });

  test('fragments may arrive in any order and twice', () {
    final original = _frame(1, 10000, keyframe: true);
    final count = Fragmenter.split(original).length;
    final rebuilt = _feed(FrameBuffer(), original, order: [...List.generate(count, (i) => count - 1 - i), 0, 1]);
    expect(rebuilt.single.encoded, original.encoded);
  });

  test('the time a frame took to arrive is reported', () {
    final buffer = FrameBuffer();
    final fragments = Fragmenter.split(_frame(1, 5000, keyframe: true));

    final before = [for (final fragment in fragments.take(fragments.length - 1)) ...buffer.add(fragment, 10 * _ms)];
    final done = buffer.add(fragments.last, 16 * _ms);

    expect(before, isEmpty);
    expect(done.single.assemblyTime, 6 * _ms);
  });

  test('nothing is handed out before the first keyframe', () {
    final buffer = FrameBuffer();
    expect(buffer.needsKeyframe, isTrue);
    expect(_feed(buffer, _frame(1, 500)), isEmpty);
    expect(_feed(buffer, _frame(2, 500, keyframe: true)), hasLength(1));
    expect(buffer.needsKeyframe, isFalse);
  });

  test('a missing fragment is asked for and the frame goes on when it arrives', () {
    final buffer = FrameBuffer();
    _feed(buffer, _frame(1, 5000, keyframe: true));
    final fragments = Fragmenter.split(_frame(2, 5000));
    for (var i = 0; i < fragments.length; i++) {
      if (i != 2) buffer.add(fragments[i], 100 * _ms);
    }

    expect(buffer.poll(101 * _ms).missing, isEmpty, reason: 'too early to tell loss from reordering');
    final asked = buffer.poll(100 * _ms + buffer.reorderWait).missing.single;
    expect(asked.frameId, 2);
    expect(asked.indexes, [2]);

    final delivered = buffer.add(fragments[2], 110 * _ms);
    expect(delivered.map((assembled) => assembled.frame.id), [2]);
    expect(buffer.lostFrames, 0);
  });

  test('the same piece is asked for at most six times', () {
    final buffer = FrameBuffer()..maxWait = 1 << 60;
    buffer.add(Fragmenter.split(_frame(1, 5000, keyframe: true)).first, 0);

    final asked = [for (var i = 1; i <= 40; i++) buffer.poll(i * 20 * _ms).missing].where((missing) => missing.isNotEmpty).length;

    expect(asked, 6);
  });

  test('each ask waits longer than the one before', () {
    final buffer = FrameBuffer()..maxWait = 1 << 60;
    buffer.add(Fragmenter.split(_frame(1, 5000, keyframe: true)).first, 0);

    final askedAt = [for (var i = 1; i <= 1000; i++) if (buffer.poll(i * _ms).missing.isNotEmpty) i];
    final gaps = [for (var i = 1; i < askedAt.length; i++) askedAt[i] - askedAt[i - 1]];

    expect(askedAt.length, 6);
    for (var i = 1; i < gaps.length; i++) {
      expect(gaps[i], greaterThanOrEqualTo(2 * gaps[i - 1] - 1), reason: 'gaps $gaps ms');
    }
  });

  test('later frames wait behind an incomplete one and are released in order', () {
    final buffer = FrameBuffer();
    _feed(buffer, _frame(1, 500, keyframe: true));
    final blocked = Fragmenter.split(_frame(2, 5000));
    for (final fragment in blocked.take(blocked.length - 1)) {
      buffer.add(fragment, 0);
    }

    expect(_feed(buffer, _frame(3, 500), now: 5 * _ms), isEmpty, reason: 'frame 3 is whole but frame 2 comes first');

    expect(buffer.add(blocked.last, 8 * _ms).map((assembled) => assembled.frame.id), [2, 3]);
  });

  test('a whole frame that never arrived is asked for as a whole', () {
    final buffer = FrameBuffer();
    _feed(buffer, _frame(1, 500, keyframe: true));
    _feed(buffer, _frame(2, 500));
    _feed(buffer, _frame(4, 500), now: 100 * _ms);

    final asked = buffer.poll(100 * _ms + buffer.reorderWait).missing.single;

    expect(asked.frameId, 3);
    expect(asked.indexes, isEmpty);
  });

  test('a frame that never completes is given up and the next keyframe is waited for', () {
    final buffer = FrameBuffer();
    _feed(buffer, _frame(1, 5000, keyframe: true));
    _feed(buffer, _frame(2, 5000));
    _feed(buffer, _frame(3, 5000), drop: {2});
    _feed(buffer, _frame(4, 5000), now: 10 * _ms);

    final result = buffer.poll(buffer.maxWait + 1);

    expect(result.frames, isEmpty, reason: 'frame 4 is a delta frame that cannot be decoded without frame 3');
    expect(buffer.lostFrames, 1);
    expect(buffer.needsKeyframe, isTrue);

    expect(_feed(buffer, _frame(5, 5000, keyframe: true), now: buffer.maxWait + 2 * _ms).map((frame) => frame.id), [5]);
    expect(buffer.needsKeyframe, isFalse);
    expect(_feed(buffer, _frame(6, 5000), now: buffer.maxWait + 3 * _ms).map((frame) => frame.id), [6]);
  });

  test('a late fragment of an old frame is ignored', () {
    final buffer = FrameBuffer();
    final old = Fragmenter.split(_frame(1, 5000, keyframe: true));
    _feed(buffer, _frame(2, 5000, keyframe: true));

    expect(buffer.add(old.first, 0), isEmpty);
    expect(buffer.lostFrames, 0);
  });

  test('malformed fragments are dropped', () {
    final buffer = FrameBuffer();
    final good = Fragmenter.split(_frame(1, 5000, keyframe: true)).first;
    expect(buffer.add(Uint8List(3), 0), isEmpty);
    expect(buffer.add(Uint8List.fromList(good)..[11] = 0..[12] = 0, 0), isEmpty);
    expect(buffer.add(Uint8List.fromList(good)..[9] = 0x7F..[10] = 0x7F, 0), isEmpty);
    expect(buffer.add(Uint8List.fromList(good)..[11] = 0x7F..[12] = 0x7F, 0), isEmpty);
  });

  test('frame ids keep working when they wrap around', () {
    final buffer = FrameBuffer();
    const max = 0x7FFFFFFF;
    expect(_feed(buffer, _frame(max - 1, 100, keyframe: true)), hasLength(1));
    expect(_feed(buffer, _frame(max, 100)), hasLength(1));
    expect(_feed(buffer, _frame(-0x80000000, 100)), hasLength(1));
    expect(buffer.lostFrames, 0);
  });

  test('a jump far ahead starts over and counts what was waiting', () {
    final buffer = FrameBuffer();
    _feed(buffer, _frame(1, 500, keyframe: true));
    _feed(buffer, _frame(3, 500));

    _feed(buffer, _frame(1000, 500, keyframe: true), now: _ms);

    expect(buffer.lostFrames, 1, reason: 'the frame that was waiting behind the gap');
    expect(buffer.needsKeyframe, isFalse);
  });

  test('a decoder that fell behind makes the buffer wait for a keyframe', () {
    final buffer = FrameBuffer();
    expect(_feed(buffer, _frame(1, 500, keyframe: true)), hasLength(1));
    buffer.needKeyframe();
    expect(buffer.needsKeyframe, isTrue);
    expect(_feed(buffer, _frame(2, 500)), isEmpty);
    expect(_feed(buffer, _frame(3, 500, keyframe: true)), hasLength(1));
    expect(buffer.needsKeyframe, isFalse);
  });

  test('the stream id travels with the fragment and still fits one packet', () {
    final fragment = Fragmenter.split(_frame(1, 500000, keyframe: true)).first;
    final packet = VideoPacket.encode(54321, fragment);
    expect(packet.length, lessThanOrEqualTo(Session.maxPayload));
    expect(VideoPacket.streamOf(packet), 54321);
    expect(VideoPacket.fragmentOf(packet), fragment);
    expect(VideoPacket.streamOf(Uint8List(0)), isNull);
    expect(VideoPacket.streamOf(Uint8List(2)), isNull);
  });
}
