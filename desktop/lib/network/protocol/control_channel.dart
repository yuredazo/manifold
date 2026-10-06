import 'dart:typed_data';

import 'control.dart';
import 'session.dart';

final class ControlChannel {
  ControlChannel({required this._transmit, required this._onMessage, required this._onFailed});

  static const resendAfterMs = 400;
  static const maxAttempts = 8;

  final void Function(Uint8List) _transmit;
  final void Function(Control) _onMessage;
  final void Function() _onFailed;

  int _nextId = 1;
  final Map<int, _Waiting> _waiting = {};
  final ReplayWindow _seen = ReplayWindow(256);
  int _newestFeedList = 0;
  bool _failed = false;

  // Pairing gives up fast. A link that is up is judged by silence, so it retries for as long.
  int giveUpAfter = maxAttempts;

  void send(Control control, int now) {
    final id = _nextId++;
    final bytes = ControlCodec.encode(id, control);
    if (ControlCodec.needsAck(control)) _waiting[id] = _Waiting(bytes, now);
    _transmit(bytes);
  }

  void receive(Uint8List payload) {
    switch (ControlCodec.decode(payload)) {
      case null:
        return;
      case DecodedAck(:final id):
        _waiting.remove(id);
      case DecodedUnrecognized(:final id):
        _transmit(ControlCodec.encodeAck(id));
      case DecodedMessage(:final id, :final control):
        if (ControlCodec.needsAck(control)) _transmit(ControlCodec.encodeAck(id));
        // The ack above is sent again for a repeat: the first one may have been lost.
        if (!_seen.accept(id)) return;
        if (control is FeedList) {
          if (id < _newestFeedList) return;
          _newestFeedList = id;
        }
        _onMessage(control);
    }
  }

  void tick(int now) {
    if (_failed) return;
    for (final message in _waiting.values.toList()) {
      if (now - message.sentAt < resendAfterMs) continue;
      if (message.attempts >= giveUpAfter) {
        _failed = true;
        _onFailed();
        return;
      }
      message.attempts++;
      message.sentAt = now;
      _transmit(message.bytes);
    }
  }
}

final class _Waiting {
  _Waiting(this.bytes, this.sentAt);

  final Uint8List bytes;
  int sentAt;
  int attempts = 1;
}
