import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/app/settings.dart';
import 'package:manifold_hub/core/storage.dart';

void main() {
  late Directory appData;

  setUp(() {
    appData = Directory.systemTemp.createTempSync('manifold-settings-test');
  });

  tearDown(() => appData.deleteSync(recursive: true));

  Settings open() => Settings(Storage.at(appData));

  test('checking on launch is on, and the tray and cameras are off until chosen', () {
    final settings = open();
    expect(settings.checkOnLaunch, isTrue);
    expect(settings.closeToTray, isFalse);
    expect(settings.offerCameras, isFalse);
  });

  test('a choice survives a restart', () {
    open()
      ..checkOnLaunch = false
      ..closeToTray = true
      ..offerCameras = true;
    final again = open();
    expect(again.checkOnLaunch, isFalse);
    expect(again.closeToTray, isTrue);
    expect(again.offerCameras, isTrue);
  });
}
