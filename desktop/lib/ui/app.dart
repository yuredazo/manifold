import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../network.dart';
import '../settings.dart';
import '../updates.dart';
import 'about_page.dart';
import 'devices_page.dart';
import 'pairing_dialog.dart';
import 'share_page.dart';
import 'watch_page.dart';

class HubApp extends StatelessWidget {
  const HubApp(this.network, this.settings, this.updater, {super.key});

  final Network network;
  final Settings settings;
  final Updater updater;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Manifold',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFFE8847C), brightness: Brightness.dark),
      ),
      home: _Shell(network, settings, updater),
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
  const _Shell(this.network, this.settings, this.updater);

  final Network network;
  final Settings settings;
  final Updater updater;

  @override
  State<_Shell> createState() => _ShellState();
}

class _ShellState extends State<_Shell> {
  _Page _page = _Page.watch;

  void _openDevices() => setState(() => _page = _Page.devices);

  @override
  Widget build(BuildContext context) {
    final network = widget.network;
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
                  _Page.watch => WatchPage(network, openDevices: _openDevices),
                  _Page.share => SharePage(network, openDevices: _openDevices),
                  _Page.devices => DevicesPage(network),
                  _Page.about => AboutPage(settings: widget.settings, updater: widget.updater),
                },
              ),
            ],
          ),
          PairingDialogHost(network),
        ],
      ),
    );
  }
}
