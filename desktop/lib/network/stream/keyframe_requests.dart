import 'dart:math';

final class KeyframeRequests {
  static const _firstWaitMs = 500;
  static const _maxWaitMs = 2000;

  int _lastAt = -(1 << 60);
  int _asked = 0;

  bool due(int nowMs, {required bool needed, required bool repairing}) {
    if (!needed) {
      _asked = 0;
      return false;
    }
    if (repairing) return false;
    final wait = _asked == 0 ? 0 : min(_firstWaitMs << (_asked - 1).clamp(0, 10), _maxWaitMs);
    if (nowMs - _lastAt < wait) return false;
    _lastAt = nowMs;
    _asked++;
    return true;
  }
}
