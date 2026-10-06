import 'dart:collection';
import 'dart:typed_data';

final class _Kept {
  _Kept(this.frameId, this.storedAt, this.fragments) : size = fragments.fold(0, (total, fragment) => total + fragment.length);

  final int frameId;
  final int storedAt;
  final List<Uint8List> fragments;
  final int size;
  int answered = 0;
}

/// A frame is answered only so often: a receiver that keeps asking is on a link that will not deliver it.
final class RetransmitStore {
  RetransmitStore({this.keepFor = 1000000000, this.keepBytes = 4 << 20});

  static const _maxAnswers = 6;

  final int keepFor;
  final int keepBytes;
  final Queue<_Kept> _frames = Queue();
  int _bytes = 0;

  void remember(int frameId, List<Uint8List> fragments, int now) {
    final kept = _Kept(frameId, now, fragments);
    _frames.addLast(kept);
    _bytes += kept.size;
    while (_frames.isNotEmpty && (now - _frames.first.storedAt > keepFor || _bytes > keepBytes)) {
      _bytes -= _frames.removeFirst().size;
    }
  }

  List<Uint8List> fragments(int frameId, List<int> indexes) {
    _Kept? kept;
    for (final candidate in _frames) {
      if (candidate.frameId == frameId) kept = candidate;
    }
    if (kept == null || kept.answered >= _maxAnswers) return const [];
    kept.answered++;
    if (indexes.isEmpty) return kept.fragments;
    return [
      for (final index in indexes)
        if (index >= 0 && index < kept.fragments.length) kept.fragments[index],
    ];
  }
}
