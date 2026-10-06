import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import 'about_page.dart';
import '../network/devices_page.dart';
import '../network/pairing_dialog.dart';
import '../share/share_page.dart';
import '../watch/watch_page.dart';
import 'hub.dart';

class HubApp extends StatelessWidget {
  const HubApp(this.hub, {super.key});

  final Hub hub;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Manifold',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFFE8847C), brightness: Brightness.dark),
      ),
      home: _Shell(hub),
    );
  }
}

enum _Page {
  watch('Watch', Icons.live_tv_outlined, Icons.live_tv),
  share('Share', Icons.present_to_all_outlined, Icons.present_to_all),
  devices('Devices', Icons.devices_outlined, Icons.devices),
  about('About', Icons.info_outline, Icons.info);

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
              NavigationRail(
                selectedIndex: _page.index,
                onDestinationSelected: (index) => setState(() => _page = _Page.values[index]),
                labelType: NavigationRailLabelType.all,
                leading: Padding(
                  padding: const EdgeInsets.only(top: 12, bottom: 16),
                  child: SvgPicture.asset('assets/manifold.svg', width: 40, height: 40),
                ),
                destinations: [
                  for (final page in _Page.values)
                    NavigationRailDestination(icon: Icon(page.icon), selectedIcon: Icon(page.selectedIcon), label: Text(page.title)),
                ],
              ),
              const VerticalDivider(width: 1),
              Expanded(
                child: switch (_page) {
                  _Page.watch => WatchPage(hub.network, hub.watching, openDevices: _openDevices),
                  _Page.share => SharePage(hub.network, hub.sharing, hub.activity, openDevices: _openDevices),
                  _Page.devices => DevicesPage(hub.network),
                  _Page.about => AboutPage(settings: hub.settings, updater: hub.updater, autostart: hub.autostart),
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
