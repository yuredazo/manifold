import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/activity.dart';

void main() {
  test('what a device does is listed newest first, and the list is capped', () {
    final activity = Activity(maxEntries: 3);
    for (var i = 0; i < 5; i++) {
      activity.record(device: 'phone', feed: 'feed $i', started: true);
    }

    expect(activity.entries.map((entry) => entry.feed), ['feed 4', 'feed 3', 'feed 2']);
  });

  test('starting and stopping are both listed, and a listener hears of each', () {
    final activity = Activity();
    var heard = 0;
    activity.addListener(() => heard++);

    activity.record(device: 'phone', feed: 'Screen', started: true);
    activity.record(device: 'phone', feed: 'Screen', started: false);

    expect(activity.entries.map((entry) => entry.started), [false, true]);
    expect(heard, 2);
  });
}
