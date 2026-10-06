import 'dart:io';

import 'package:flutter/material.dart';
import 'package:media_kit/media_kit.dart';
import 'package:package_info_plus/package_info_plus.dart';

import 'app/app.dart';
import 'app/autostart.dart';
import 'app/hub.dart';
import 'app/settings.dart';
import 'app/window_control.dart';
import 'core/storage.dart';
import 'network/network_storage.dart';
import 'network/network.dart';
import 'share/activity.dart';
import 'share/sharing.dart';
import 'update/updater.dart';
import 'watch/watching.dart';

const _listeningKey = 'listening';
const _sharesFile = 'shares.json';
const _linkLogFile = 'network.log';

Future<void> main(List<String> arguments) async {
  WidgetsFlutterBinding.ensureInitialized();
  MediaKit.ensureInitialized();

  final storage = Storage.open();
  final book = storage.loadDevices();
  final activity = Activity();
  late final Network network;
  final sharing = Sharing(
    book: book,
    sendVideo: (deviceKey, streamId, fragments) => network.sendVideo(deviceKey, streamId, fragments),
    sendAudio: (deviceKey, streamId, timestamp, frame) => network.sendAudio(deviceKey, streamId, timestamp, frame),
    refuse: (deviceKey, streamId, reason) => network.refuseSubscribe(deviceKey, streamId, reason),
    feedsChanged: () => network.offerToAll(),
    watchChanged: (deviceKey, feed, watching) => activity.record(device: book.find(deviceKey)?.name ?? 'A device', feed: feed, started: watching),
    saved: storage.read(_sharesFile),
    saveShares: (text) => storage.write(_sharesFile, text),
  );
  final watching = Watching(
    book: book,
    refused: (deviceKey, feed, reason) => network.recordRefusal(deviceKey, feed, reason),
    subscribe: (deviceKey, request) => network.subscribe(deviceKey, request),
    unsubscribe: (deviceKey, streamId) => network.unsubscribe(deviceKey, streamId),
    requestKeyframe: (deviceKey, streamId) => network.requestKeyframe(deviceKey, streamId),
    requestResend: (deviceKey, nack) => network.requestResend(deviceKey, nack),
    report: (deviceKey, report) => network.sendStreamReport(deviceKey, report),
    rttMs: (deviceKey) => network.rttMs(deviceKey),
  );
  network = Network(
    storage.loadIdentity(),
    book,
    features: [sharing, watching],
    log: (message) => storage.append(_linkLogFile, '${DateTime.now().toIso8601String()} $message'),
  );
  // The switch on the Devices page is remembered, so the hub is reachable again after a restart.
  network.addListener(() => storage.write(_listeningKey, network.listening ? 'on' : 'off'));
  if (storage.read(_listeningKey) == 'on') network.start();

  final settings = Settings(storage);
  sharing.offerCameras = settings.offerCameras;
  settings.addListener(() => sharing.offerCameras = settings.offerCameras);
  final window = WindowControl(settings, sharing, hidden: arguments.contains(hiddenFlag));
  await window.start();
  final autostart = Autostart(executable: Platform.resolvedExecutable)..refresh();

  final updater = Updater(
    currentVersion: (await PackageInfo.fromPlatform()).version,
    installDirectory: File(Platform.resolvedExecutable).parent,
    workDirectory: Directory('${Directory.systemTemp.path}${Platform.pathSeparator}manifold-update'),
    exitApp: () => window.quit(),
  )..cleanUp();
  if (settings.checkOnLaunch) updater.checkOnLaunch();

  runApp(HubApp(Hub(
    network: network,
    sharing: sharing,
    watching: watching,
    activity: activity,
    settings: settings,
    updater: updater,
    autostart: autostart,
  )));
}
