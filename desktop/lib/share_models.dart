import 'package:flutter/foundation.dart';

import 'net/control.dart';
import 'net/rate_control.dart';
import 'net/retransmit_store.dart';

enum ShareKind { window, display, camera }

@immutable
final class ShareableWindow {
  const ShareableWindow({
    required this.handle,
    required this.title,
    required this.process,
    required this.windowClass,
    required this.width,
    required this.height,
    this.kind = ShareKind.window,
    this.primary = false,
    this.device = '',
  });

  final int handle;

  /// The device name, such as `\\.\DISPLAY1`, for a display, and the name of the camera for a camera.
  final String title;
  final String process;
  final String windowClass;

  final int width;
  final int height;
  final ShareKind kind;
  final bool primary;
  final String device;

  bool get display => kind == ShareKind.display;
  bool get camera => kind == ShareKind.camera;

  String get label => display ? displayLabel(title) : title;
}

String displayLabel(String deviceName) {
  final number = RegExp(r'(\d+)$').firstMatch(deviceName)?.group(1);
  return number == null ? 'Display' : 'Display $number';
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
    this.kind = ShareKind.window,
    this.soundOnly = false,
    this.showCursor = true,
    this.device = '',
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
  final ShareKind kind;

  /// Only the sound of the window or the computer is shared, announced as a feed with no size.
  final bool soundOnly;

  final bool showCursor;

  /// The same camera on another USB port has another one, so the name is the fallback when matching.
  String device;

  /// Zero while the window is not open. The share stays saved and is not offered until a matching window shows up.
  int handle;
  int width;
  int height;

  bool get present => handle != 0;

  bool get display => kind == ShareKind.display;
  bool get camera => kind == ShareKind.camera;

  final List<Watcher> watchers = [];
  final RetransmitStore store = RetransmitStore();
  bool capturing = false;
  int nextFrame = 0;

  bool isWatchedBy(String deviceKey, int streamId) => watchers.any((watcher) => watcher.isStream(deviceKey, streamId));
}

/// [taken] says whether a name is already used by another share.
String feedNameFor(ShareableWindow window, {required bool soundOnly, required bool Function(String name) taken}) {
  String unused(String base) {
    var name = base;
    for (var copy = 2; taken(name); copy++) {
      name = '$base ($copy)';
    }
    return name;
  }

  // Every display plays the same sound, so one that is shared alone is named for the computer.
  if (soundOnly && window.display) return unused('PC sound');
  const suffix = ' (sound)';
  final room = maxNameLength - 4 - (soundOnly ? suffix.length : 0);
  var base = window.label.replaceAll(RegExp(r'[\x00-\x1F\x7F]'), ' ').trim();
  if (base.isEmpty) base = window.process;
  if (base.length > room) base = base.substring(0, room).trimRight();
  return unused(soundOnly ? '$base$suffix' : base);
}
