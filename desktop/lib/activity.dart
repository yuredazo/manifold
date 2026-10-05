import 'package:flutter/foundation.dart';

@immutable
final class ActivityEntry {
  const ActivityEntry({required this.time, required this.device, required this.feed, required this.started});

  final DateTime time;
  final String device;
  final String feed;
  final bool started;
}

/// What the paired devices did with the feeds, newest first.
final class Activity extends ChangeNotifier {
  Activity({this.maxEntries = 30});

  final int maxEntries;
  final List<ActivityEntry> entries = [];

  void record({required String device, required String feed, required bool started}) {
    entries.insert(0, ActivityEntry(time: DateTime.now(), device: device, feed: feed, started: started));
    if (entries.length > maxEntries) entries.removeRange(maxEntries, entries.length);
    notifyListeners();
  }
}
