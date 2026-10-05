import 'dart:async';

import 'package:flutter/services.dart';

final class NativeWindows {
  NativeWindows._() {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'closed') _closed.add((call.arguments as Map)['handle'] as int);
    });
  }

  static final NativeWindows instance = NativeWindows._();

  static const _channel = MethodChannel('manifold/windows');
  final StreamController<int> _closed = StreamController.broadcast();

  /// Handles of windows the user closed. Windows closed through [close] are not reported.
  Stream<int> get closed => _closed.stream;

  Future<int> open(String title, int width, int height) async =>
      (await _channel.invokeMethod<int>('open', {'title': title, 'width': width, 'height': height}))!;

  Future<void> close(int handle) => _channel.invokeMethod('close', {'handle': handle});
}
