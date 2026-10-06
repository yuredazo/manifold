import 'package:flutter/foundation.dart';

import '../core/storage.dart';

const _checkKey = 'updates.check';
const _trayKey = 'close.tray';
const _camerasKey = 'share.cameras';

final class Settings extends ChangeNotifier {
  Settings(this._storage)
      : _checkOnLaunch = _storage.read(_checkKey) != 'off',
        _closeToTray = _storage.read(_trayKey) == 'on',
        _offerCameras = _storage.read(_camerasKey) == 'on';

  final Storage _storage;
  bool _checkOnLaunch;
  bool _closeToTray;
  bool _offerCameras;

  bool get checkOnLaunch => _checkOnLaunch;

  bool get closeToTray => _closeToTray;

  /// Off by default: it keeps cameras out of the Share list altogether, so one cannot be shared by mistake.
  bool get offerCameras => _offerCameras;

  set offerCameras(bool value) {
    if (value == _offerCameras) return;
    _offerCameras = value;
    _storage.write(_camerasKey, value ? 'on' : 'off');
    notifyListeners();
  }

  set checkOnLaunch(bool value) {
    if (value == _checkOnLaunch) return;
    _checkOnLaunch = value;
    _storage.write(_checkKey, value ? 'on' : 'off');
    notifyListeners();
  }

  set closeToTray(bool value) {
    if (value == _closeToTray) return;
    _closeToTray = value;
    _storage.write(_trayKey, value ? 'on' : 'off');
    notifyListeners();
  }
}
