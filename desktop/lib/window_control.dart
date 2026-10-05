import 'package:flutter/foundation.dart';
import 'package:tray_manager/tray_manager.dart';
import 'package:window_manager/window_manager.dart';

import 'settings.dart';
import 'sharing.dart';

const _showKey = 'show';
const _pauseKey = 'pause';
const _exitKey = 'exit';

final class WindowControl with WindowListener, TrayListener {
  /// A hub started [hidden] has no window to bring back but the tray icon, so it always has one.
  WindowControl(this._settings, this._sharing, {this.hidden = false});

  final Settings _settings;
  final Sharing _sharing;
  final bool hidden;
  bool _trayShown = false;
  bool? _menuPaused;

  Future<void> start() async {
    await windowManager.ensureInitialized();
    // The plugin creates its taskbar object here, and setSkipTaskbar crashes the app without it.
    await windowManager.waitUntilReadyToShow();
    await windowManager.setPreventClose(true);
    windowManager.addListener(this);
    trayManager.addListener(this);
    _settings.addListener(_syncTray);
    _sharing.addListener(_syncMenu);
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
    if (_trayShown) {
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
      case _pauseKey:
        _sharing.paused = !_sharing.paused;
      case _exitKey:
        quit();
    }
  }

  Future<void> _syncMenu() async {
    if (!_trayShown || _menuPaused == _sharing.paused) return;
    _menuPaused = _sharing.paused;
    await trayManager.setContextMenu(_menu());
  }

  Menu _menu() => Menu(items: [
        MenuItem(key: _showKey, label: 'Show Manifold'),
        MenuItem(key: _pauseKey, label: _sharing.paused ? 'Resume sharing' : 'Stop all sharing'),
        MenuItem.separator(),
        MenuItem(key: _exitKey, label: 'Exit'),
      ]);

  Future<void> _syncTray() async {
    final wanted = _settings.closeToTray || hidden;
    if (wanted == _trayShown) return;
    _trayShown = wanted;
    try {
      if (wanted) {
        await trayManager.setIcon('assets/tray.ico');
        await trayManager.setToolTip('Manifold');
        _menuPaused = _sharing.paused;
        await trayManager.setContextMenu(_menu());
      } else {
        await trayManager.destroy();
      }
    } catch (error) {
      // Without the tray icon a hidden window cannot be brought back, so the setting is turned off again.
      debugPrint('tray: $error');
      _trayShown = false;
      _settings.closeToTray = false;
      if (hidden) await show();
    }
  }
}
