import 'dart:async';

import 'package:flutter/foundation.dart';

import '../network/network_feature.dart';
import '../network/protocol/control.dart';
import '../network/protocol/devices.dart';
import 'viewer.dart';

final class Watching extends ChangeNotifier with NetworkFeature {
  Watching({
    required this.book,
    required this.refused,
    required this.subscribe,
    required this.unsubscribe,
    required this.requestKeyframe,
    required this.requestResend,
    required this.report,
    required this.rttMs,
  }) {
    book.devices.addListener(_devicesChanged);
  }

  final DeviceBook book;
  final void Function(String deviceKey, String feed, Refusal reason) refused;
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
      label: feed.label,
      deviceName: device.name,
      deviceKey: device.publicKey,
      width: width,
      height: height,
      withAudio: feed.hasAudio,
      soundOnly: feed.soundOnly,
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

  @override
  Future<void> stopped() => stopAll();

  void stopWhere(bool Function(ViewerSession session) test) {
    for (final session in _sessions.values.toList()) {
      if (test(session)) unawaited(stop(session));
    }
  }

  @override
  void onVideo(Device device, int streamId, Uint8List fragment) {
    final session = _sessions[streamId];
    if (session != null && session.deviceKey == device.publicKey) session.onFragment(streamId, fragment);
  }

  @override
  void onAudio(Device device, int streamId, int timestamp, Uint8List frame) {
    final session = _sessions[streamId];
    if (session != null && session.deviceKey == device.publicKey) session.onAudio(streamId, timestamp, frame);
  }

  bool get isEmpty => _sessions.isEmpty;

  @override
  bool get wantsFastPoll => !isEmpty;

  @override
  void poll(int nowNs) {
    for (final session in _sessions.values) {
      session.poll(nowNs);
    }
  }

  @override
  void tick(int nowMs) {
    for (final session in _sessions.values) {
      session.tick(nowMs);
    }
  }

  // A device that stopped being received from loses its stream at once.
  void _devicesChanged() => stopWhere((session) => book.find(session.deviceKey)?.receive != true);

  @override
  void onLinkDown(Device device) => stopWhere((session) => session.deviceKey == device.publicKey);

  @override
  void onFeeds(Device device, List<FeedInfo> feeds) {
    stopWhere((session) => session.deviceKey == device.publicKey && !feeds.any((feed) => feed.name == session.feedName));
    for (final feed in feeds) {
      final session = of(device, feed);
      if (session != null) session.retitle(feed.label);
    }
  }

  @override
  void onSubscribeRefused(Device device, SubscribeRefused refusal) {
    final session = sessionOf(device, refusal.streamId);
    if (session == null) return;
    refused(device.publicKey, session.feedName, refusal.reason);
    stopWhere((other) => identical(other, session));
  }

  @override
  void dispose() {
    book.devices.removeListener(_devicesChanged);
    super.dispose();
  }
}
