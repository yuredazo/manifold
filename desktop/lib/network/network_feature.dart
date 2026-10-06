import 'dart:typed_data';

import 'protocol/control.dart';
import 'protocol/devices.dart';

/// What a feature of the hub hears from [Network]. Everything does nothing until a feature overrides it.
abstract mixin class NetworkFeature {
  /// Every 250 ms while the network is listening.
  void tick(int nowMs) {}

  /// A feature that is waiting for a missing piece wants [poll] every few milliseconds instead.
  bool get wantsFastPoll => false;

  void poll(int nowNs) {}

  Future<void> stopped() async {}

  List<FeedInfo> feedsFor(Device device) => const [];

  void onLinkDown(Device device) {}

  void onUnpaired(String publicKey) {}

  void onFeeds(Device device, List<FeedInfo> feeds) {}

  void onSubscribe(Device device, Subscribe request) {}

  void onSubscribeRefused(Device device, SubscribeRefused refusal) {}

  void onUnsubscribe(Device device, int streamId) {}

  void onKeyframeRequest(Device device, int streamId) {}

  void onNack(Device device, Nack nack) {}

  void onStreamReport(Device device, StreamReport report) {}

  void onVideo(Device device, int streamId, Uint8List fragment) {}

  void onAudio(Device device, int streamId, int timestamp, Uint8List frame) {}
}
