import 'dart:async';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:media_kit/media_kit.dart';

import '../core/clock.dart';
import '../network/protocol/audio.dart';
import '../network/protocol/control.dart';
import '../network/protocol/video.dart';
import 'native_windows.dart';
import 'ts_muxer.dart';

const _keyframeRequestEveryMs = 500;
const _reportEveryNs = 500000000;

const _unknownRttMs = 20.0;

/// Long enough to ride out a radio stall of about 150 ms.
const _holdLimitNs = 300000000;
const _minNackIntervalNs = 10000000;

/// An AAC frame is 1024 samples, about 21 ms, so this is roughly a second.
const _soundTablesEvery = 47;

const _windowMaxWidth = 960;
const _windowMaxHeight = 720;

/// A one second head start, so the first timestamps are never negative.
const _startTimestamp = 90000;

int _lastStreamId = 0;

@immutable
final class ViewerStats {
  const ViewerStats({this.frames = 0, this.lostFrames = 0, this.bytes = 0});

  final int frames;
  final int lostFrames;
  final int bytes;
}

final class ViewerSession {
  ViewerSession({
    required this.feedName,
    required this.label,
    required this.deviceName,
    required this.deviceKey,
    required this.width,
    required this.height,
    required this.withAudio,
    this.soundOnly = false,
    required this.onClosed,
    required this._subscribe,
    required this._unsubscribe,
    required this._requestKeyframe,
    required this._requestResend,
    required this._report,
    required this._rttMs,
  }) : streamId = (_lastStreamId = _lastStreamId % 0xFFFF + 1);

  final String feedName;

  /// The feed's title as last announced, shown on the player window.
  String label;
  final String deviceName;
  final String deviceKey;

  final int width;
  final int height;
  final bool withAudio;

  /// There is no picture, so no window and no keyframes, and the audio carries the clock.
  final bool soundOnly;

  final void Function() onClosed;
  final int streamId;
  final void Function(int) _subscribe;
  final void Function(int) _unsubscribe;
  final void Function(int) _requestKeyframe;
  final void Function(Nack) _requestResend;
  final void Function(StreamReport) _report;
  final double? Function() _rttMs;

  // The default output draws into a Flutter texture, which this window does not have.
  final Player player = Player(configuration: const PlayerConfiguration(vo: 'gpu'));
  final ValueNotifier<ViewerStats> stats = ValueNotifier(const ViewerStats());

  final FrameBuffer _buffer = FrameBuffer();
  final TsMuxer _muxer = TsMuxer();
  ServerSocket? _server;
  Socket? _client;
  bool _clientNeedsKeyframe = true;
  int? _firstTimestamp;
  int _lastPts = _startTimestamp;
  int _lastKeyframeRequest = 0;
  int? _firstAudioTimestamp;
  bool _restarting = false;
  int _audioStart = _startTimestamp;
  int _lastAudioPts = _startTimestamp;
  int _frames = 0;
  int _audioFramesSinceTables = 0;
  int _bytes = 0;
  int _reportStart = monotonicNs();
  int _reportFragments = 0;
  int _reportResends = 0;
  int _reportLostBase = 0;
  bool _stopped = false;
  int? _window;
  StreamSubscription<int>? _windowClosed;

  Future<void> start() async {
    final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    _server = server;
    server.listen(_accept);
    int? window;
    if (!soundOnly) {
      final fit = min(1.0, min(_windowMaxWidth / width, _windowMaxHeight / height));
      final opened = await NativeWindows.instance.open('$label · $deviceName', (width * fit).round(), (height * fit).round());
      window = opened;
      _window = opened;
      _windowClosed = NativeWindows.instance.closed.where((handle) => handle == opened).listen((_) {
        _window = null;
        onClosed();
      });
    }
    await _configurePlayer(window);
    _subscribe(streamId);
    await player.open(Media('tcp://127.0.0.1:${server.port}'));
  }

  void retitle(String label) {
    if (label == this.label) return;
    this.label = label;
    final window = _window;
    if (window != null && !_stopped) unawaited(NativeWindows.instance.retitle(window, '$label · $deviceName'));
  }

  Future<void> stop() async {
    if (_stopped) return;
    _stopped = true;
    _unsubscribe(streamId);
    await _windowClosed?.cancel();
    final window = _window;
    if (window != null) await NativeWindows.instance.close(window);
    _client?.destroy();
    await _server?.close();
    await player.dispose();
    stats.dispose();
  }

  void onFragment(int id, Uint8List fragment) {
    if (id != streamId || _stopped) return;
    _bytes += fragment.length;
    _reportFragments++;
    _play(_buffer.add(fragment, monotonicNs()));
  }

  // Called every few milliseconds because a missing piece is only waited for a few frames.
  void poll(int now) {
    if (_stopped) return;
    final roundTripNs = ((_rttMs() ?? _unknownRttMs) * 1000000).round();
    _buffer.maxWait = _holdLimitNs;
    _buffer.nackInterval = (roundTripNs * 13 ~/ 10).clamp(_minNackIntervalNs, 1 << 40);
    final result = _buffer.poll(now);
    for (final missing in result.missing) {
      _reportResends += missing.indexes.isEmpty ? 1 : missing.indexes.length;
      _requestResend(Nack(streamId: streamId, frameId: missing.frameId, indexes: missing.indexes));
    }
    _play(result.frames);
    if (now - _reportStart >= _reportEveryNs) {
      _report(StreamReport(
        streamId: streamId,
        windowMs: (now - _reportStart) ~/ 1000000,
        fragments: _reportFragments,
        resendRequests: _reportResends,
        lostFrames: _buffer.lostFrames - _reportLostBase,
        jitterMs10: 0,
      ));
      _reportStart = now;
      _reportFragments = 0;
      _reportResends = 0;
      _reportLostBase = _buffer.lostFrames;
    }
  }

  void _play(List<Assembled> frames) {
    for (final assembled in frames) {
      final frame = assembled.frame;
      final first = _firstTimestamp ??= frame.timestamp;
      // The sender's clock wraps at 32 bits, so only the difference means anything.
      final pts = max(_lastPts, _startTimestamp + (frame.timestamp - first).toSigned(32));
      _lastPts = pts;
      final out = BytesBuilder(copy: false);
      if (frame.keyframe) out.add(_muxer.tables(withAudio: withAudio));
      out.add(_muxer.videoFrame(frame.encoded, pts, keyframe: frame.keyframe));
      _write(out.toBytes(), keyframe: frame.keyframe);
      _frames++;
    }
    if (frames.isNotEmpty) stats.value = ViewerStats(frames: _frames, lostFrames: _buffer.lostFrames, bytes: _bytes);
  }

  // libmpv stays stuck for many seconds on the timestamp gap after a break, and reconnecting resets its clock.
  Future<void> restartPlayer() async {
    final server = _server;
    if (server == null || _stopped || _restarting) return;
    _restarting = true;
    try {
      await player.open(Media('tcp://127.0.0.1:${server.port}'));
    } finally {
      _restarting = false;
    }
  }

  /// Audio counts from its own start, so it is pinned to where the picture is when the first
  /// frame arrives, and follows its own clock from there.
  void onAudio(int id, int timestamp, Uint8List frame) {
    if (id != streamId || _stopped || !withAudio) return;
    final first = _firstAudioTimestamp ??= timestamp;
    if (first == timestamp) _audioStart = _lastPts;
    final pts = max(_lastAudioPts, _audioStart + (timestamp - first).toSigned(32));
    _lastAudioPts = pts;
    if (!soundOnly) {
      _write(_muxer.audioFrame(Adts.wrap(frame), pts), keyframe: false);
      return;
    }
    // Keyframes carry the tables when there is a picture. Here they are repeated by count, about once a second.
    final out = BytesBuilder(copy: false);
    if (_audioFramesSinceTables++ % _soundTablesEvery == 0) out.add(_muxer.tables(withAudio: true, withVideo: false));
    out.add(_muxer.audioFrame(Adts.wrap(frame), pts, withClock: true));
    _write(out.toBytes(), keyframe: false);
    if (_frames == 0) {
      _frames = 1;
      stats.value = ViewerStats(frames: _frames, bytes: _bytes);
    }
  }

  /// The first request may be lost, so it is repeated while the picture waits.
  void tick(int nowMs) {
    if (_stopped || soundOnly || !_buffer.needsKeyframe || nowMs - _lastKeyframeRequest < _keyframeRequestEveryMs) return;
    _lastKeyframeRequest = nowMs;
    _requestKeyframe(streamId);
  }

/// The player may connect more than once, so a new connection replaces the old one.
  void _accept(Socket socket) {
    _client?.destroy();
    _client = socket;
    _clientNeedsKeyframe = !soundOnly;
    socket.setOption(SocketOption.tcpNoDelay, true);
    socket.listen(
      (_) {},
      onDone: () {
        if (identical(_client, socket)) _client = null;
      },
      onError: (_) {
        if (identical(_client, socket)) _client = null;
      },
      cancelOnError: true,
    );
    socket.add(_muxer.tables(withAudio: withAudio, withVideo: !soundOnly));
    if (!soundOnly) _requestKeyframe(streamId);
  }

  void _write(Uint8List bytes, {required bool keyframe}) {
    final client = _client;
    if (client == null) return;
    if (_clientNeedsKeyframe) {
      if (!keyframe) return;
      _clientNeedsKeyframe = false;
    }
    client.add(bytes);
  }

  Future<void> _configurePlayer(int? window) async {
    final native = player.platform as dynamic;
    for (final (name, value) in [
      if (window != null) ('wid', '$window'),
      // media_kit keeps video off until a texture controller attaches, and this window has none.
      ('vid', soundOnly ? 'no' : 'auto'),
      // A window that only shows the picture has no use for the player's own key and mouse bindings, and
      // one of them quits.
      ('input-default-bindings', 'no'),
      ('input-vo-keyboard', 'no'),
      // media_kit hands the address to the player inside a playlist, and the player refuses a network
      // address from one.
      ('load-unsafe-playlists', 'yes'),
      ('profile', 'low-latency'),
      ('cache', 'no'),
      ('demuxer-readahead-secs', '0'),
      ('demuxer-lavf-analyzeduration', '0.2'),
      ('demuxer-lavf-probesize', '65536'),
    ]) {
      await native.setProperty(name, value);
    }
  }
}
