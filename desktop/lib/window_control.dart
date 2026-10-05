import 'package:flutter/foundation.dart';
import 'package:tray_manager/tray_manager.dart';
import 'package:window_manager/window_manager.dart';

import 'settings.dart';

const _showKey = 'show';
const _exitKey = 'exit';

final class WindowControl with WindowListener, TrayListener {
  WindowControl(this._settings);

  final Settings _settings;
  bool _trayShown = false;

  Future<void> start() async {
    await windowManager.ensureInitialized();
    // The plugin creates its taskbar object here, and setSkipTaskbar crashes the app without it.
    await windowManager.waitUntilReadyToShow();
    await windowManager.setPreventClose(true);
    windowManager.addListener(this);
    trayManager.addListener(this);
    _settings.addListener(_syncTray);
    await _syncTray();
  }

  Future<void> show() async {
    await windowManager.setSkipTaskbar(false);
    await windowManager.show();
    await windowManager.focus();
  }

  /// The tray icon goes first, since Windows keeps a killed process's icon until the mouse passes over it.
  /// windowManager.destroy() crashes the engine on shutdown, so the window is closed the ordinary way.
  Future<void> quit() async {
    await trayManager.destroy();
    await windowManager.setPreventClose(false);
    await windowManager.close();
  }

  // A second launch shows the hidden window natively, which does not bring the taskbar button back.
  @override
  void onWindowEvent(String eventName) {
    if (eventName == 'show') windowManager.setSkipTaskbar(false);
  }

  @override
  void onWindowClose() {
    if (_settings.closeToTray) {
      windowManager.setSkipTaskbar(true);
      windowManager.hide();
    } else {
      quit();
    }
  }

  @override
  void onTrayIconMouseDown() => show();

  @override
  void onTrayIconRightMouseDown() => trayManager.popUpContextMenu();

  @override
  void onTrayMenuItemClick(MenuItem menuItem) {
    switch (menuItem.key) {
      case _showKey:
        show();
      case _exitKey:
        quit();
    }
  }

  Future<void> _syncTray() async {
    final wanted = _settings.closeToTray;
    if (wanted == _trayShown) return;
    _trayShown = wanted;
    try {
      if (wanted) {
        await trayManager.setIcon('assets/tray.ico');
        await trayManager.setToolTip('Manifold');
        await trayManager.setContextMenu(Menu(items: [MenuItem(key: _showKey, label: 'Show Manifold'), MenuItem.separator(), MenuItem(key: _exitKey, label: 'Exit')]));
      } else {
        await trayManager.destroy();
      }
    } catch (error) {
      // Without the tray icon a hidden window cannot be brought back, so the setting is turned off again.
      debugPrint('tray: $error');
      _trayShown = false;
      _settings.closeToTray = false;
    }
  }
}
