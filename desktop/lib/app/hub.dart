import '../network/network.dart';
import '../share/activity.dart';
import '../share/sharing.dart';
import '../update/updater.dart';
import '../watch/watching.dart';
import 'autostart.dart';
import 'settings.dart';

/// Everything the window shows, built once in main. This is the only place that knows how the features are connected.
final class Hub {
  const Hub({
    required this.network,
    required this.sharing,
    required this.watching,
    required this.activity,
    required this.settings,
    required this.updater,
    required this.autostart,
  });

  final Network network;
  final Sharing sharing;
  final Watching watching;
  final Activity activity;
  final Settings settings;
  final Updater updater;
  final Autostart autostart;
}
