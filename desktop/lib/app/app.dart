import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../network/devices_page.dart';
import '../network/pairing_dialog.dart';
import '../share/share_page.dart';
import '../watch/watch_page.dart';
import 'hub.dart';
import 'settings_page.dart';

const _fallbackSeed = Color(0xFFE8847C);

class HubApp extends StatelessWidget {
  const HubApp(this.hub, {super.key});

  final Hub hub;

  @override
  Widget build(BuildContext context) {
    return DynamicColorBuilder(
      builder: (light, dark) => MaterialApp(
        title: 'Manifold',
        debugShowCheckedModeBanner: false,
        theme: _theme(light ?? ColorScheme.fromSeed(seedColor: _fallbackSeed)),
        darkTheme: _theme(dark ?? ColorScheme.fromSeed(seedColor: _fallbackSeed, brightness: Brightness.dark)),
        home: _Shell(hub),
      ),
    );
  }
}

ThemeData _theme(ColorScheme colors) {
  final control = RoundedRectangleBorder(borderRadius: BorderRadius.circular(8));
  return ThemeData(
    useMaterial3: true,
    colorScheme: colors,
    dialogTheme: DialogThemeData(
      backgroundColor: colors.surface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14), side: BorderSide(color: colors.outlineVariant)),
    ),
    filledButtonTheme: FilledButtonThemeData(style: FilledButton.styleFrom(shape: control)),
    outlinedButtonTheme: OutlinedButtonThemeData(style: OutlinedButton.styleFrom(shape: control)),
    textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(shape: control)),
  );
}

enum _Page {
  watch('Watch', Icons.live_tv_outlined, Icons.live_tv),
  share('Share', Icons.present_to_all_outlined, Icons.present_to_all),
  devices('Devices', Icons.devices_outlined, Icons.devices),
  settings('Settings', Icons.tune, Icons.tune);

  const _Page(this.title, this.icon, this.selectedIcon);

  final String title;
  final IconData icon;
  final IconData selectedIcon;
}

class _Shell extends StatefulWidget {
  const _Shell(this.hub);

  final Hub hub;

  @override
  State<_Shell> createState() => _ShellState();
}

class _ShellState extends State<_Shell> {
  _Page _page = _Page.watch;
  bool _asking = false;

  @override
  void initState() {
    super.initState();
    widget.hub.updater.addListener(_offerUpdate);
  }

  @override
  void dispose() {
    widget.hub.updater.removeListener(_offerUpdate);
    super.dispose();
  }

  void _offerUpdate() {
    final release = widget.hub.updater.announcement;
    if (release == null || _asking) return;
    _asking = true;
    showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Version ${release.version} is available'),
        content: Text('You have ${widget.hub.updater.currentVersion}. The update downloads, then Manifold restarts.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Later')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Download and restart')),
        ],
      ),
    ).then((install) {
      _asking = false;
      widget.hub.updater.dismissAnnouncement();
      if (install == true) widget.hub.updater.install();
    });
  }

  void _openDevices() => setState(() => _page = _Page.devices);

  @override
  Widget build(BuildContext context) {
    final hub = widget.hub;
    return Scaffold(
      body: Stack(
        children: [
          Row(
            children: [
              _Rail(selected: _page, onSelect: (page) => setState(() => _page = page)),
              const VerticalDivider(width: 1),
              Expanded(
                child: switch (_page) {
                  _Page.watch => WatchPage(hub.network, hub.watching, openDevices: _openDevices),
                  _Page.share => SharePage(hub.network, hub.sharing, hub.activity, openDevices: _openDevices),
                  _Page.devices => DevicesPage(hub.network, hub.settings),
                  _Page.settings => SettingsPage(settings: hub.settings, updater: hub.updater, autostart: hub.autostart),
                },
              ),
            ],
          ),
          PairingDialogHost(hub.network),
        ],
      ),
    );
  }
}

class _Rail extends StatelessWidget {
  const _Rail({required this.selected, required this.onSelect});

  final _Page selected;
  final ValueChanged<_Page> onSelect;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 56,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Column(
          children: [
            SvgPicture.asset(
              'assets/manifold.svg',
              width: 28,
              height: 28,
              colorFilter: ColorFilter.mode(Theme.of(context).colorScheme.primary, BlendMode.srcIn),
            ),
            const SizedBox(height: 14),
            for (final page in _Page.values.where((page) => page != _Page.settings)) ...[
              _RailButton(page, selected: selected == page, onTap: () => onSelect(page)),
              const SizedBox(height: 6),
            ],
            const Spacer(),
            _RailButton(_Page.settings, selected: selected == _Page.settings, onTap: () => onSelect(_Page.settings)),
          ],
        ),
      ),
    );
  }
}

class _RailButton extends StatelessWidget {
  const _RailButton(this.page, {required this.selected, required this.onTap});

  final _Page page;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Tooltip(
      message: page.title,
      waitDuration: const Duration(milliseconds: 400),
      child: Semantics(
        button: true,
        selected: selected,
        label: page.title,
        child: Material(
          color: selected ? colors.primary.withValues(alpha: 0.18) : Colors.transparent,
          borderRadius: BorderRadius.circular(10),
          child: InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(10),
            child: SizedBox(
              width: 40,
              height: 40,
              child: Icon(selected ? page.selectedIcon : page.icon, size: 22, color: selected ? colors.primary : colors.onSurfaceVariant),
            ),
          ),
        ),
      ),
    );
  }
}
