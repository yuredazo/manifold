import 'dart:collection';
import 'dart:typed_data';

import 'session.dart';

/// One encoded frame, rebuilt from its fragments. [timestamp] counts 90 kHz ticks.
final class Frame {
  const Frame(this.id, this.timestamp, this.keyframe, this.encoded);

  final int id;
  final int timestamp;
  final bool keyframe;
  final Uint8List encoded;
}

abstract final class VideoPacket {
  static const streamIdLength = 2;

  static Uint8List encode(int streamId, Uint8List fragment) {
    final out = Uint8List(streamIdLength + fragment.length);
    ByteData.sublistView(out).setUint16(0, streamId, Endian.big);
    out.setRange(streamIdLength, out.length, fragment);
    return out;
  }

  static int? streamOf(Uint8List payload) {
    if (payload.length <= streamIdLength) return null;
    return ByteData.sublistView(payload).getUint16(0, Endian.big);
  }

  static Uint8List fragmentOf(Uint8List payload) => payload.sublist(streamIdLength);
}

abstract final class Fragmenter {
  static const headerLength = 13;
  static const maxChunk = Session.maxPayload - VideoPacket.streamIdLength - headerLength;
  static const maxFragments = 4096;

  static const _flagKeyframe = 1;

  static List<Uint8List> split(Frame frame) {
    final size = frame.encoded.length;
    final count = size == 0 ? 1 : (size + maxChunk - 1) ~/ maxChunk;
    if (count > maxFragments) throw ArgumentError('a frame of $size bytes is too large');
    return List.generate(count, (index) {
      final from = index * maxChunk;
      final to = from + maxChunk < size ? from + maxChunk : size;
      final out = Uint8List(headerLength + (to - from));
      final view = ByteData.sublistView(out);
      view.setInt32(0, frame.id, Endian.big);
      view.setInt32(4, frame.timestamp, Endian.big);
      out[8] = frame.keyframe ? _flagKeyframe : 0;
      view.setUint16(9, index, Endian.big);
      view.setUint16(11, count, Endian.big);
      out.setRange(headerLength, out.length, frame.encoded.sublist(from, to));
      return out;
    });
  }

  static bool isKeyframe(int flags) => flags & _flagKeyframe != 0;
}

final class Assembled {
  const Assembled(this.frame, this.assemblyTime);

  final Frame frame;
  final int assemblyTime;
}

final class MissingFragments {
  const MissingFragments(this.frameId, this.indexes);

  final int frameId;
  final List<int> indexes;
}

final class Poll {
  const Poll(this.frames, this.missing);

  final List<Assembled> frames;
  final List<MissingFragments> missing;
}

final class _Nacks {
  int lastAt = FrameBuffer._longAgo;
  int count = 0;
}

final class _Partial {
  _Partial(this.id, this.timestamp, this.keyframe, int count, this.firstAt)
      : chunks = List.filled(count, null),
        lastAt = firstAt;

  final int id;
  final int timestamp;
  final bool keyframe;
  final int firstAt;
  final List<Uint8List?> chunks;
  final _Nacks nacks = _Nacks();
  int received = 0;
  int lastAt;

  bool get complete => received == chunks.length;
}

final class FrameBuffer {
  static const _maxAhead = 120;
  static const _maxRequests = 6;

  /// Far enough back that the time since it is large, yet far enough from the limit that subtracting cannot overflow.
  static const _longAgo = -(1 << 60);

  final SplayTreeMap<int, _Partial> _pending = SplayTreeMap((a, b) => (a - b).toSigned(32).sign);
  final Map<int, _Nacks> _wholeFrameNacks = {};
  int _nextId = 0;
  bool _started = false;

  int maxWait = 50000000;

  int reorderWait = 4000000;

  int nackInterval = 15000000;

  bool needsKeyframe = true;
  int lostFrames = 0;

  int requestedFragments = 0;

  List<Assembled> add(Uint8List fragment, int now) {
    if (fragment.length < Fragmenter.headerLength) return const [];
    final view = ByteData.sublistView(fragment);
    final id = view.getInt32(0, Endian.big);
    final timestamp = view.getInt32(4, Endian.big);
    final flags = fragment[8];
    final index = view.getUint16(9, Endian.big);
    final count = view.getUint16(11, Endian.big);
    if (count == 0 || count > Fragmenter.maxFragments || index >= count) return const [];

    if (!_started) {
      _started = true;
      _nextId = id;
    }
    final ahead = (id - _nextId).toSigned(32);
    if (ahead < 0) return const [];
    if (ahead > _maxAhead) _restartAt(id);

    final partial = _pending.putIfAbsent(id, () => _Partial(id, timestamp, Fragmenter.isKeyframe(flags), count, now));
    if (count != partial.chunks.length || partial.chunks[index] != null) return const [];
    partial.chunks[index] = fragment.sublist(Fragmenter.headerLength);
    partial.received++;
    partial.lastAt = now;
    _wholeFrameNacks.remove(id);
    return _release();
  }

  Poll poll(int now) {
    final frames = <Assembled>[];
    while (_pending.isNotEmpty) {
      final waitingSince = (_pending[_nextId] ?? _pending[_pending.firstKey()!]!).firstAt;
      if (now - waitingSince < maxWait) break;
      _pending.remove(_nextId);
      _wholeFrameNacks.remove(_nextId);
      _nextId = (_nextId + 1).toSigned(32);
      lostFrames++;
      needsKeyframe = true;
      frames.addAll(_release());
    }
    return Poll(frames, _missingAt(now));
  }

  void needKeyframe() {
    needsKeyframe = true;
  }

  List<Assembled> _release() {
    final out = <Assembled>[];
    while (true) {
      final head = _pending[_nextId];
      if (head == null || !head.complete) break;
      _pending.remove(_nextId);
      _nextId = (_nextId + 1).toSigned(32);
      if (head.keyframe) needsKeyframe = false;
      if (needsKeyframe) continue;
      final encoded = BytesBuilder(copy: false);
      for (final chunk in head.chunks) {
        encoded.add(chunk!);
      }
      out.add(Assembled(Frame(head.id, head.timestamp, head.keyframe, encoded.toBytes()), head.lastAt - head.firstAt));
    }
    return out;
  }

  List<MissingFragments> _missingAt(int now) {
    if (_pending.isEmpty) return const [];
    final missing = <MissingFragments>[];
    final newest = _pending.lastKey()!;
    var id = _nextId;
    while ((id - newest).toSigned(32) <= 0) {
      final partial = _pending[id];
      if (partial == null) {
        final afterKey = _pending.firstKeyAfter(id);
        final after = afterKey == null ? null : _pending[afterKey];
        final nacks = _wholeFrameNacks.putIfAbsent(id, _Nacks.new);
        if (after != null && now - after.firstAt >= reorderWait && _due(nacks, now)) {
          missing.add(MissingFragments(id, const []));
        }
      } else if (!partial.complete && now - partial.lastAt >= reorderWait && _due(partial.nacks, now)) {
        final indexes = [
          for (var i = 0; i < partial.chunks.length; i++)
            if (partial.chunks[i] == null) i,
        ];
        requestedFragments += indexes.length;
        missing.add(MissingFragments(id, indexes));
      }
      id = (id + 1).toSigned(32);
    }
    return missing;
  }

  bool _due(_Nacks nacks, int now) {
    // Each ask waits twice as long as the one before, so one bad stretch of radio does not use them all up.
    final wait = nackInterval << (nacks.count - 1).clamp(0, 30);
    if (nacks.count >= _maxRequests || now - nacks.lastAt < wait) return false;
    nacks.lastAt = now;
    nacks.count++;
    return true;
  }

  void _restartAt(int id) {
    lostFrames += _pending.length;
    _pending.clear();
    _wholeFrameNacks.clear();
    _nextId = id;
    needsKeyframe = true;
  }
}
