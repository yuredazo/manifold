import 'dart:io';

import 'package:flutter/material.dart';
import 'package:media_kit/media_kit.dart';
import 'package:package_info_plus/package_info_plus.dart';

import 'autostart.dart';
import 'network.dart';
import 'settings.dart';
import 'storage.dart';
import 'ui/app.dart';
import 'updates.dart';
import 'window_control.dart';

const _listeningKey = 'listening';
const _sharesFile = 'shares.json';

Future<void> main(List<String> arguments) async {
  WidgetsFlutterBinding.ensureInitialized();
  MediaKit.ensureInitialized();

  final storage = Storage.open();
  final network = Network(
    storage.loadIdentity(),
    storage.loadDevices(),
    savedShares: storage.read(_sharesFile),
    saveShares: (text) => storage.write(_sharesFile, text),
  );
  // The switch on the Devices page is remembered, so the hub is reachable again after a restart.
  network.addListener(() => storage.write(_listeningKey, network.listening ? 'on' : 'off'));
  if (storage.read(_listeningKey) == 'on') network.start();

  final settings = Settings(storage);
  network.sharing.offerCameras = settings.offerCameras;
  settings.addListener(() => network.sharing.offerCameras = settings.offerCameras);
  final window = WindowControl(settings, network.sharing, hidden: arguments.contains(hiddenFlag));
  await window.start();
  final autostart = Autostart(executable: Platform.resolvedExecutable)..refresh();

  final updater = Updater(
    currentVersion: (await PackageInfo.fromPlatform()).version,
    installDirectory: File(Platform.resolvedExecutable).parent,
    workDirectory: Directory('${Directory.systemTemp.path}${Platform.pathSeparator}manifold-update'),
    exitApp: () => window.quit(),
  )..cleanUp();
  if (settings.checkOnLaunch) updater.checkOnLaunch();

  runApp(HubApp(network, settings, updater, autostart));
}
