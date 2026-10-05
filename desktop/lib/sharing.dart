import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'clock.dart';
import 'net/control.dart';
import 'net/rate_control.dart';
import 'net/retransmit_store.dart';
import 'net/video.dart';
import 'window_match.dart';

const _maxStreamsPerDevice = 4;
const _maxStreams = 8;
const _minSize = 160;
const _maxSize = 3840;
const _minBitrateKbps = 500;
const _maxBitrateKbps = 20000;
const _floorBitrateKbps = 200;

const _checkEveryMs = 2000;
const _maxSavedShares = 32;
const _maxSavedText = 512;

@immutable
final class ShareableWindow {
  const ShareableWindow({
    required this.handle,
    required this.title,
    required this.process,
    required this.windowClass,
    required this.width,
    required this.height,
  });

  final int handle;
  final String title;
  final String process;
  final String windowClass;

  final int width;
  final int height;
}

final class Watcher {
  Watcher(this.deviceKey, this.streamId, {required this.wantsSound, required this.rate});

  final String deviceKey;
  final int streamId;
  final bool wantsSound;

  final RateControl rate;

  bool isStream(String deviceKey, int streamId) => this.deviceKey == deviceKey && this.streamId == streamId;
}

final class SharedWindow {
  SharedWindow({
    required this.feedName,
    required this.title,
    required this.process,
    required this.windowClass,
    required this.withAudio,
    this.handle = 0,
    this.width = 1280,
    this.height = 720,
  });

  final String feedName;

  /// What the window was called when it was shared. Kept as it was, so it still finds the window after the title changes.
  final String title;
  final String process;
  final String windowClass;
  final bool withAudio;

  /// Zero while the window is not open. The share stays saved and is not offered until a matching window shows up.
  int handle;
  int width;
  int height;

  bool get present => handle != 0;

  final List<Watcher> watchers = [];
  final RetransmitStore store = RetransmitStore();
  bool capturing = false;
  int nextFrame = 0;

  bool isWatchedBy(String deviceKey, int streamId) => watchers.any((watcher) => watcher.isStream(deviceKey, streamId));
}

/// A window is only captured and encoded while somebody is watching it.
final class Sharing extends ChangeNotifier {
  Sharing({required this.sendVideo, required this.sendAudio, required this.feedsChanged, this.saveShares, String? saved}) {
    _channel.setMethodCallHandler(_fromRunner);
    _load(saved);
  }

  static const _channel = MethodChannel('manifold/capture');

  final void Function(String deviceKey, int streamId, List<Uint8List> fragments) sendVideo;
  final void Function(String deviceKey, int streamId, int timestamp, Uint8List frame) sendAudio;

  final void Function() feedsChanged;
  final void Function(String)? saveShares;

  final List<SharedWindow> shared = [];

  int _lastCheck = -_checkEveryMs;

  String? problem;

  List<FeedInfo> get feeds => [
        for (final window in shared.where((window) => window.present)) FeedInfo(window.feedName, window.width, window.height, 30, window.withAudio),
      ];

  Future<List<ShareableWindow>> windows() async {
    final found = await _channel.invokeListMethod<Map>('windows') ?? const [];
    return [
      for (final entry in found)
        ShareableWindow(
          handle: entry['handle'] as int,
          title: entry['title'] as String,
          process: entry['process'] as String,
          windowClass: entry['class'] as String,
          width: entry['width'] as int,
          height: entry['height'] as int,
        ),
    ];
  }

  /// A window that is being watched says so itself, but one that nobody watches is only noticed by asking.
  Future<void> tick(int nowMs) async {
    if (shared.isEmpty || nowMs - _lastCheck < _checkEveryMs) return;
    _lastCheck = nowMs;
    final present = shared.where((window) => window.present).toList();
    if (present.isNotEmpty) {
      final open = await _channel.invokeListMethod<int>('alive', {'handles': [for (final window in present) window.handle]}) ?? const [];
      for (final window in present) {
        if (!open.contains(window.handle)) _lose(window);
      }
    }
    if (shared.any((window) => !window.present)) await _findAbsent();
  }

  Future<void> _findAbsent() async {
    final open = await windows();
    final taken = {for (final window in shared) if (window.present) window.handle};
    var found = false;
    for (final window in shared.where((window) => !window.present)) {
      final match = findWindow(
        title: window.title,
        windowClass: window.windowClass,
        process: window.process,
        candidates: open.where((entry) => !taken.contains(entry.handle)),
      );
      if (match == null) continue;
      window
        ..handle = match.handle
        ..width = match.width
        ..height = match.height;
      taken.add(match.handle);
      found = true;
    }
    if (!found) return;
    notifyListeners();
    feedsChanged();
  }

  void _lose(SharedWindow window) {
    if (!window.present) return;
    window.watchers.clear();
    _stopCapture(window);
    window.handle = 0;
    notifyListeners();
    feedsChanged();
  }

  void dismissProblem() {
    problem = null;
    notifyListeners();
  }

  SharedWindow share(ShareableWindow window, {required bool audio}) {
    final existing = _byHandle(window.handle);
    if (existing != null) return existing;
    final entry = SharedWindow(
      feedName: _feedNameFor(window),
      handle: window.handle,
      title: window.title,
      process: window.process,
      windowClass: window.windowClass,
      width: window.width,
      height: window.height,
      withAudio: audio,
    );
    shared.add(entry);
    _saveAll();
    notifyListeners();
    feedsChanged();
    return entry;
  }

  void unshare(SharedWindow window) {
    if (!shared.remove(window)) return;
    window.watchers.clear();
    _stopCapture(window);
    _saveAll();
    notifyListeners();
    feedsChanged();
  }

  /// Null when the stream is being served, otherwise why it is not.
  Future<Refusal?> subscribe(String deviceKey, Subscribe request) async {
    final window = _named(request.feed);
    if (window == null || !window.present) return Refusal.notFound;
    final replacing = window.isWatchedBy(deviceKey, request.streamId);
    final all = [for (final entry in shared) ...entry.watchers];
    if (!replacing && (all.length >= _maxStreams || all.where((watcher) => watcher.deviceKey == deviceKey).length >= _maxStreamsPerDevice)) {
      return Refusal.busy;
    }
    window.watchers
      ..removeWhere((watcher) => watcher.isStream(deviceKey, request.streamId))
      ..add(Watcher(
        deviceKey,
        request.streamId,
        wantsSound: request.audio && window.withAudio,
        rate: RateControl(minKbps: _floorBitrateKbps, maxKbps: request.bitrateKbps.clamp(_minBitrateKbps, _maxBitrateKbps)),
      ));
    notifyListeners();
    if (window.capturing) {
      unawaited(_channel.invokeMethod('keyframe', {'handle': window.handle}));
      return null;
    }
    window.capturing = true;
    try {
      await _channel.invokeMethod('start', {
        'handle': window.handle,
        'width': request.width.clamp(_minSize, _maxSize),
        'height': request.height.clamp(_minSize, _maxSize),
        'bitrateKbps': request.bitrateKbps.clamp(_minBitrateKbps, _maxBitrateKbps),
        'audio': window.withAudio,
      });
    } on PlatformException {
      window.capturing = false;
      window.watchers.clear();
      problem = 'Windows would not let "${window.feedName}" be captured. It may be protected, or minimized to nothing.';
      notifyListeners();
      return Refusal.failed;
    }
    return null;
  }

  void unsubscribe(String deviceKey, int streamId) {
    for (final window in shared) {
      final before = window.watchers.length;
      window.watchers.removeWhere((watcher) => watcher.isStream(deviceKey, streamId));
      if (window.watchers.length != before) {
        _stopCaptureIfIdle(window);
        notifyListeners();
      }
    }
  }

  void resend(String deviceKey, Nack nack) {
    for (final window in shared) {
      if (!window.isWatchedBy(deviceKey, nack.streamId)) continue;
      final fragments = window.store.fragments(nack.frameId, nack.indexes);
      if (fragments.isNotEmpty) sendVideo(deviceKey, nack.streamId, fragments);
    }
  }

  void onReport(String deviceKey, StreamReport report) {
    for (final window in shared) {
      for (final watcher in window.watchers) {
        if (!watcher.isStream(deviceKey, report.streamId)) continue;
        if (watcher.rate.onReport(report, monotonicNs()) != null && window.capturing) {
          // One encoder serves every watcher of a window, so it has to suit the one with the poorest link.
          final kbps = window.watchers.map((other) => other.rate.kbps).reduce((a, b) => a < b ? a : b);
          unawaited(_channel.invokeMethod('bitrate', {'handle': window.handle, 'bitrateKbps': kbps}));
        }
      }
    }
  }

  void requestKeyframe(String deviceKey, int streamId) {
    for (final window in shared) {
      if (window.isWatchedBy(deviceKey, streamId)) unawaited(_channel.invokeMethod('keyframe', {'handle': window.handle}));
    }
  }

  void dropDevice(String deviceKey) {
    var changed = false;
    for (final window in shared) {
      final before = window.watchers.length;
      window.watchers.removeWhere((watcher) => watcher.deviceKey == deviceKey);
      if (window.watchers.length != before) {
        changed = true;
        _stopCaptureIfIdle(window);
      }
    }
    if (changed) notifyListeners();
  }

  void dropAll() {
    for (final window in shared) {
      window.watchers.clear();
      _stopCapture(window);
    }
    notifyListeners();
  }

  SharedWindow? _named(String name) {
    for (final window in shared) {
      if (window.feedName == name) return window;
    }
    return null;
  }

  SharedWindow? _byHandle(int handle) {
    for (final window in shared) {
      if (window.present && window.handle == handle) return window;
    }
    return null;
  }

  void _saveAll() {
    saveShares?.call(jsonEncode([
      for (final window in shared)
        {'name': window.feedName, 'title': window.title, 'process': window.process, 'class': window.windowClass, 'audio': window.withAudio},
    ]));
  }

  void _load(String? text) {
    if (text == null) return;
    final Object? decoded;
    try {
      decoded = jsonDecode(text);
    } on FormatException {
      return;
    }
    if (decoded is! List) return;
    for (final entry in decoded.whereType<Map>().take(_maxSavedShares)) {
      final name = entry['name'];
      final title = entry['title'];
      final process = entry['process'];
      final windowClass = entry['class'];
      if (name is! String || title is! String || process is! String || windowClass is! String) continue;
      if ([name, title, process, windowClass].any((value) => value.length > _maxSavedText) || name.isEmpty || _named(name) != null) continue;
      shared.add(SharedWindow(feedName: name, title: title, process: process, windowClass: windowClass, withAudio: entry['audio'] == true));
    }
  }

  String _feedNameFor(ShareableWindow window) {
    var base = window.title.replaceAll(RegExp(r'[\x00-\x1F\x7F]'), ' ').trim();
    if (base.isEmpty) base = window.process;
    if (base.length > maxNameLength - 4) base = base.substring(0, maxNameLength - 4).trimRight();
    var name = base;
    for (var copy = 2; _named(name) != null; copy++) {
      name = '$base ($copy)';
    }
    return name;
  }

  void _stopCaptureIfIdle(SharedWindow window) {
    if (window.watchers.isEmpty) _stopCapture(window);
  }

  void _stopCapture(SharedWindow window) {
    if (!window.capturing) return;
    window.capturing = false;
    unawaited(_channel.invokeMethod('stop', {'handle': window.handle}));
  }

  Future<void> _fromRunner(MethodCall call) async {
    final fields = call.arguments as Map;
    final window = _byHandle(fields['handle'] as int);
    if (window == null) return;
    switch (call.method) {
      case 'video':
        _sendVideo(window, fields['timestamp'] as int, fields['keyframe'] as bool, fields['data'] as Uint8List);
      case 'audio':
        _sendAudio(window, fields['timestamp'] as int, fields['data'] as Uint8List);
      case 'closed':
        _lose(window);
    }
  }

  void _sendVideo(SharedWindow window, int timestamp, bool keyframe, Uint8List encoded) {
    if (window.watchers.isEmpty) return;
    final id = window.nextFrame;
    window.nextFrame = (id + 1).toSigned(32);
    final fragments = Fragmenter.split(Frame(id, _ticks(timestamp), keyframe, encoded));
    window.store.remember(id, fragments, monotonicNs());
    for (final watcher in window.watchers) {
      sendVideo(watcher.deviceKey, watcher.streamId, fragments);
    }
  }

  void _sendAudio(SharedWindow window, int timestamp, Uint8List frame) {
    for (final watcher in window.watchers) {
      if (watcher.wantsSound) sendAudio(watcher.deviceKey, watcher.streamId, _ticks(timestamp), frame);
    }
  }

  /// The runner counts in 100 ns, the wire in 90 kHz.
  int _ticks(int timestamp) => (timestamp * 9 ~/ 1000).toSigned(32);

  @override
  void dispose() {
    for (final window in shared) {
      _stopCapture(window);
    }
    super.dispose();
  }
}
