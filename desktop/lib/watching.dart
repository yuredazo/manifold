import 'dart:async';

import 'package:flutter/foundation.dart';

import 'net/control.dart';
import 'net/devices.dart';
import 'viewer.dart';

final class Watching extends ChangeNotifier {
  Watching({
    required this.subscribe,
    required this.unsubscribe,
    required this.requestKeyframe,
    required this.requestResend,
    required this.report,
    required this.rttMs,
  });

  final void Function(String deviceKey, Subscribe request) subscribe;
  final void Function(String deviceKey, int streamId) unsubscribe;
  final void Function(String deviceKey, int streamId) requestKeyframe;
  final void Function(String deviceKey, Nack nack) requestResend;
  final void Function(String deviceKey, StreamReport report) report;
  final double? Function(String deviceKey) rttMs;

  final Map<int, ViewerSession> _sessions = {};

  ViewerSession? of(Device device, FeedInfo feed) {
    for (final session in _sessions.values) {
      if (session.deviceKey == device.publicKey && session.feedName == feed.name) return session;
    }
    return null;
  }

  ViewerSession? sessionOf(Device device, int streamId) {
    final session = _sessions[streamId];
    return session != null && session.deviceKey == device.publicKey ? session : null;
  }

  Future<void> watch(Device device, FeedInfo feed) async {
    if (of(device, feed) != null) return;
    final width = feed.width > 0 ? feed.width : 1280;
    final height = feed.height > 0 ? feed.height : 720;
    final fps = feed.fps > 0 ? feed.fps.clamp(1, Subscribe.maxFps) : Subscribe.defaultFps;
    final kbps = (width * height * fps * 7 ~/ 100 ~/ 1000).clamp(500, 20000);
    late final ViewerSession session;
    session = ViewerSession(
      feedName: feed.name,
      deviceName: device.name,
      deviceKey: device.publicKey,
      width: width,
      height: height,
      withAudio: feed.hasAudio,
      onClosed: () => unawaited(stop(session)),
      subscribe: (streamId) => subscribe(device.publicKey, Subscribe(streamId, feed.name, width, height, kbps, audio: feed.hasAudio, fps: fps)),
      unsubscribe: (streamId) => unsubscribe(device.publicKey, streamId),
      requestKeyframe: (streamId) => requestKeyframe(device.publicKey, streamId),
      requestResend: (nack) => requestResend(device.publicKey, nack),
      report: (streamReport) => report(device.publicKey, streamReport),
      rttMs: () => rttMs(device.publicKey),
    );
    _sessions[session.streamId] = session;
    notifyListeners();
    try {
      await session.start();
    } catch (_) {
      await stop(session);
      rethrow;
    }
  }

  Future<void> stop(ViewerSession session) async {
    if (_sessions.remove(session.streamId) == null) return;
    notifyListeners();
    await session.stop();
  }

  Future<void> stopAll() => Future.wait([for (final session in _sessions.values.toList()) stop(session)]);

  void stopWhere(bool Function(ViewerSession session) test) {
    for (final session in _sessions.values.toList()) {
      if (test(session)) unawaited(stop(session));
    }
  }

  void onVideo(Device device, int streamId, Uint8List fragment) {
    final session = _sessions[streamId];
    if (session != null && session.deviceKey == device.publicKey) session.onFragment(streamId, fragment);
  }

  void onAudio(Device device, int streamId, int timestamp, Uint8List frame) {
    final session = _sessions[streamId];
    if (session != null && session.deviceKey == device.publicKey) session.onAudio(streamId, timestamp, frame);
  }

  bool get isEmpty => _sessions.isEmpty;

  void poll(int now) {
    for (final session in _sessions.values) {
      session.poll(now);
    }
  }

  void tick(int nowMs) {
    for (final session in _sessions.values) {
      session.tick(nowMs);
    }
  }
}
