import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'clock.dart';
import 'net/control.dart';
import 'net/devices.dart';
import 'net/rate_control.dart';
import 'net/video.dart';
import 'share_book.dart';
import 'share_models.dart';
import 'window_match.dart';

export 'share_models.dart';

const _maxStreamsPerDevice = 4;
const _maxStreams = 8;
const _minSize = 160;
const _maxSize = 3840;
const _minBitrateKbps = 500;
const _maxBitrateKbps = 20000;
const _floorBitrateKbps = 200;

const _checkEveryMs = 2000;

/// A window is only captured and encoded while somebody is watching it.
final class Sharing extends ChangeNotifier {
  Sharing({required this.sendVideo, required this.sendAudio, required this.feedsChanged, this.saveShares, this.watchChanged, String? saved}) {
    _channel.setMethodCallHandler(_fromRunner);
    shared.addAll(decodeShares(saved));
  }

  /// Called when a device starts watching a feed, with every stream it had open counted as one, and when it stops.
  final void Function(String deviceKey, String feed, bool watching)? watchChanged;

  Set<(String, String)> _watched = {};

  @override
  void notifyListeners() {
    _reportWatchers();
    super.notifyListeners();
  }

  void _reportWatchers() {
    final now = {
      for (final window in shared)
        for (final watcher in window.watchers) (watcher.deviceKey, window.feedName),
    };
    final before = _watched;
    _watched = now;
    final report = watchChanged;
    if (report == null) return;
    for (final (deviceKey, feed) in now.difference(before)) {
      report(deviceKey, feed, true);
    }
    for (final (deviceKey, feed) in before.difference(now)) {
      report(deviceKey, feed, false);
    }
  }

  static const _channel = MethodChannel('manifold/capture');

  final void Function(String deviceKey, int streamId, List<Uint8List> fragments) sendVideo;
  final void Function(String deviceKey, int streamId, int timestamp, Uint8List frame) sendAudio;

  final void Function() feedsChanged;
  final void Function(String)? saveShares;

  final List<SharedWindow> shared = [];

  int _lastCheck = -_checkEveryMs;

  String? problem;

  List<FeedInfo> get feeds => _offered((window) => true);

  /// What a device may see: windows and displays need `send`, cameras need `sendCamera`.
  List<FeedInfo> feedsFor(Device device) => _offered((window) => _allowed(window, device));

  bool _paused = false;

  bool get paused => _paused;

  /// Pausing withdraws every feed, ends every stream and stops every capture at once. The shares stay listed, so resuming brings them back.
  set paused(bool value) {
    if (value == _paused) return;
    _paused = value;
    if (value) {
      for (final window in shared) {
        window.watchers.clear();
        _stopCapture(window);
      }
    }
    notifyListeners();
    feedsChanged();
  }

  List<FeedInfo> _offered(bool Function(SharedWindow window) allowed) => [
        for (final window in shared.where((window) => !_paused && window.present && allowed(window)))
          if (window.soundOnly)
            FeedInfo(window.feedName, 0, 0, 0, true, soundOnly: true)
          else
            FeedInfo(window.feedName, window.width, window.height, 30, window.withAudio),
      ];

  /// A feed that does not exist is judged as a window, so asking for it is answered the same as before.
  bool permits(Device device, String feed) {
    if (_paused) return false;
    final window = _named(feed);
    return window == null ? device.send : _allowed(window, device);
  }

  /// Ends the streams of a device that are no longer allowed, without touching the ones that still are.
  void enforce(Device device) {
    var changed = false;
    for (final window in shared.where((window) => !_allowed(window, device))) {
      final before = window.watchers.length;
      window.watchers.removeWhere((watcher) => watcher.deviceKey == device.publicKey);
      if (window.watchers.length != before) {
        changed = true;
        _stopCaptureIfIdle(window);
      }
    }
    if (changed) notifyListeners();
  }

  bool _allowed(SharedWindow window, Device device) => window.camera ? device.sendCamera : device.send;

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

  Future<List<ShareableWindow>> displays() async {
    final found = await _channel.invokeListMethod<Map>('displays') ?? const [];
    return [
      for (final entry in found)
        ShareableWindow(
          handle: entry['handle'] as int,
          title: entry['title'] as String,
          process: '',
          windowClass: '',
          width: entry['width'] as int,
          height: entry['height'] as int,
          kind: ShareKind.display,
          primary: entry['primary'] as bool,
        ),
    ];
  }

  bool _offerCameras = false;

  bool get offerCameras => _offerCameras;

  /// Turning it off withdraws the cameras that are shared: they stay saved, but nothing finds them again.
  set offerCameras(bool value) {
    if (value == _offerCameras) return;
    _offerCameras = value;
    if (!value) {
      for (final window in shared.where((window) => window.camera)) {
        _lose(window);
      }
    }
    notifyListeners();
  }

  Future<List<ShareableWindow>> cameras() async {
    if (!_offerCameras) return const [];
    final found = await _channel.invokeListMethod<Map>('cameras') ?? const [];
    return [
      for (final entry in found)
        ShareableWindow(
          handle: entry['handle'] as int,
          title: entry['title'] as String,
          process: '',
          windowClass: '',
          width: entry['width'] as int,
          height: entry['height'] as int,
          kind: ShareKind.camera,
          device: entry['device'] as String,
        ),
    ];
  }

  /// A window that is being watched says so itself, but one that nobody watches is only noticed by asking.
  Future<void> tick(int nowMs) async {
    if (shared.isEmpty || nowMs - _lastCheck < _checkEveryMs) return;
    _lastCheck = nowMs;
    final present = shared.where((window) => window.present).toList();
    final windowsOpen = present.where((window) => window.kind == ShareKind.window).toList();
    if (windowsOpen.isNotEmpty) {
      final open = await _channel.invokeListMethod<int>('alive', {'handles': [for (final window in windowsOpen) window.handle]}) ?? const [];
      for (final window in windowsOpen) {
        if (!open.contains(window.handle)) _lose(window);
      }
    }
    if (present.any((window) => window.display)) {
      final connected = {for (final display in await displays()) display.handle};
      for (final window in present.where((window) => window.display)) {
        if (!connected.contains(window.handle)) _lose(window);
      }
    }
    if (present.any((window) => window.camera)) {
      final connected = {for (final camera in await cameras()) camera.handle};
      for (final window in present.where((window) => window.camera)) {
        if (!connected.contains(window.handle)) _lose(window);
      }
    }
    if (shared.any((window) => !window.present)) await _findAbsent();
  }

  Future<void> _findAbsent() async {
    final absent = shared.where((window) => !window.present).toList();
    final open = [
      if (absent.any((window) => window.kind == ShareKind.window)) ...await windows(),
      if (absent.any((window) => window.display)) ...await displays(),
      if (absent.any((window) => window.camera)) ...await cameras(),
    ];
    final taken = {for (final window in shared) if (window.present) window.handle};
    var found = false;
    for (final window in absent) {
      final match = findShare(window, open.where((entry) => !taken.contains(entry.handle)));
      if (match == null) continue;
      window
        ..handle = match.handle
        ..device = match.device
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

  /// [soundOnly] shares no picture, so it needs sound and does not apply to a camera.
  SharedWindow share(ShareableWindow window, {required bool audio, bool soundOnly = false, bool showCursor = true}) {
    final existing = _byHandle(window.handle);
    if (existing != null) return existing;
    final soundAlone = soundOnly && !window.camera;
    final entry = SharedWindow(
      feedName: feedNameFor(window, soundOnly: soundAlone, taken: (name) => _named(name) != null),
      handle: window.handle,
      title: window.title,
      process: window.process,
      windowClass: window.windowClass,
      width: window.width,
      height: window.height,
      withAudio: (audio || soundAlone) && !window.camera,
      kind: window.kind,
      soundOnly: soundAlone,
      showCursor: showCursor,
      device: window.device,
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

  Future<Refusal?> subscribe(String deviceKey, Subscribe request) async {
    final window = _named(request.feed);
    if (_paused || window == null || !window.present) return Refusal.notFound;
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
        if (window.soundOnly) 'picture': false,
        if (!window.showCursor) 'cursor': false,
        if (window.camera) 'device': window.device,
      });
    } on PlatformException {
      window.capturing = false;
      window.watchers.clear();
      problem = window.camera
          ? 'Windows would not open "${window.feedName}". Another app may be using it, or camera access for desktop apps is off in Windows settings.'
          : 'Windows would not let "${window.feedName}" be captured. It may be protected, or minimized to nothing.';
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

  void _saveAll() => saveShares?.call(encodeShares(shared));

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
