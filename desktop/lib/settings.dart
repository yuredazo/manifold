import 'package:flutter/foundation.dart';

import 'storage.dart';

const _checkKey = 'updates.check';
const _trayKey = 'close.tray';

final class Settings extends ChangeNotifier {
  Settings(this._storage)
      : _checkOnLaunch = _storage.read(_checkKey) != 'off',
        _closeToTray = _storage.read(_trayKey) == 'on';

  final Storage _storage;
  bool _checkOnLaunch;
  bool _closeToTray;

  bool get checkOnLaunch => _checkOnLaunch;

  bool get closeToTray => _closeToTray;

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
