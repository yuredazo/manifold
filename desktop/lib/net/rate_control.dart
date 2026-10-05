import 'control.dart';

/// Recovered losses do not count: a radio loses packets at any bitrate.
final class RateControl {
  RateControl({required this.minKbps, required this.maxKbps}) : kbps = maxKbps;

  static const _cleanBelow = 0.03;
  static const _backOffFactor = 0.85;
  static const _backOffEveryNs = 1000000000;
  static const _raiseEveryNs = 1500000000;
  static const _raiseFactor = 1.08;
  static const _raiseStepKbps = 50;
  static const _cleanReportsToRaise = 3;

  final int minKbps;
  final int maxKbps;
  int kbps;

  int _cleanReports = 0;
  int _changedAt = -(1 << 60);

  int? onReport(StreamReport report, int now) {
    final requested = report.resendRequests;
    final share = requested / (report.fragments + requested).clamp(1, 1 << 30);
    final before = kbps;
    if (report.lostFrames > 0) {
      _cleanReports = 0;
      if (now - _changedAt >= _backOffEveryNs) {
        kbps = (kbps * _backOffFactor).toInt().clamp(minKbps, maxKbps);
        _changedAt = now;
      }
    } else if (share < _cleanBelow) {
      _cleanReports++;
      if (_cleanReports >= _cleanReportsToRaise && now - _changedAt >= _raiseEveryNs) {
        kbps = ((kbps * _raiseFactor).toInt() + _raiseStepKbps).clamp(minKbps, maxKbps);
        _cleanReports = 0;
        _changedAt = now;
      }
    }
    return kbps == before ? null : kbps;
  }
}
