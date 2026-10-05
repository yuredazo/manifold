
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/net/control.dart';
import 'package:manifold_hub/net/devices.dart';
import 'package:manifold_hub/net/video.dart';
import 'package:manifold_hub/sharing.dart';

const _channel = MethodChannel('manifold/capture');

class _Harness {
  _Harness({String? saved}) {
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(_channel, (call) async {
      calls.add(call);
      if (call.method == 'start' && failStart) throw PlatformException(code: 'capture-failed');
      if (call.method == 'windows') {
        return [
          for (final window in open)
            {'handle': window.handle, 'title': window.title, 'process': window.process, 'class': window.windowClass, 'width': window.width, 'height': window.height},
        ];
      }
      if (call.method == 'displays') {
        return [
          for (final display in screens)
            {'handle': display.handle, 'title': display.title, 'width': display.width, 'height': display.height, 'primary': display.primary},
        ];
      }
      if (call.method == 'cameras') {
        return [
          for (final camera in webcams)
            {'handle': camera.handle, 'title': camera.title, 'device': camera.device, 'width': camera.width, 'height': camera.height},
        ];
      }
      if (call.method == 'alive') return [for (final handle in (call.arguments as Map)['handles'] as List) if (!gone.contains(handle)) handle];
      return null;
    });
    sharing = Sharing(
      sendVideo: (deviceKey, streamId, fragments) => video.add((deviceKey, streamId, fragments)),
      sendAudio: (deviceKey, streamId, timestamp, frame) => audio.add((deviceKey, streamId, timestamp, frame)),
      feedsChanged: () => feedChanges++,
      watchChanged: (deviceKey, feed, watching) => watchEvents.add((deviceKey, feed, watching)),
      saved: saved,
      saveShares: saves.add,
    )..offerCameras = true;
  }

  late final Sharing sharing;
  final List<MethodCall> calls = [];
  final List<(String, int, List<Uint8List>)> video = [];
  final List<(String, int, int, Uint8List)> audio = [];
  final List<(String, String, bool)> watchEvents = [];
  int feedChanges = 0;
  bool failStart = false;

  final Set<int> gone = {};
  final List<ShareableWindow> open = [];
  final List<ShareableWindow> screens = [];
  final List<ShareableWindow> webcams = [];
  final List<String> saves = [];

  List<String> get methods => calls.map((call) => call.method).toList();

  Future<void> fromRunner(String method, Map<String, Object?> fields) async {
    final binding = TestDefaultBinaryMessengerBinding.instance;
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      _channel.name,
      const StandardMethodCodec().encodeMethodCall(MethodCall(method, fields)),
      (_) {},
    );
  }

  void close() {
    sharing.dispose();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(_channel, null);
  }
}

ShareableWindow _window(int handle, String title, {String process = 'app.exe', String windowClass = 'AppWindow'}) =>
    ShareableWindow(handle: handle, title: title, process: process, windowClass: windowClass, width: 1280, height: 720);

ShareableWindow _display(int handle, String deviceName, {bool primary = false}) => ShareableWindow(
      handle: handle,
      title: deviceName,
      process: '',
      windowClass: '',
      width: 2560,
      height: 1440,
      kind: ShareKind.display,
      primary: primary,
    );

ShareableWindow _camera(int handle, String name, {String device = r'\\?\usb#cam-1'}) => ShareableWindow(
      handle: handle,
      title: name,
      process: '',
      windowClass: '',
      width: 1280,
      height: 720,
      kind: ShareKind.camera,
      device: device,
    );

String _saved(List<Map<String, Object?>> entries) => jsonEncode(entries);

Map<String, Object?> _entry(
  String name, {
  String title = 'Notes',
  String process = 'app.exe',
  String windowClass = 'AppWindow',
  bool audio = false,
  bool display = false,
}) =>
    {'name': name, 'title': title, 'process': process, 'class': windowClass, 'audio': audio, 'display': display};

Subscribe _request(String feed, int streamId, {bool audio = false, int width = 1280, int height = 720, int kbps = 3000}) =>
    Subscribe(streamId, feed, width, height, kbps, audio: audio);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _Harness harness;
  setUp(() => harness = _Harness());
  tearDown(() => harness.close());

  group('offering windows', () {
    test('a title becomes a feed name that is valid and different from the others', () {
      final sharing = harness.sharing;

      final first = sharing.share(_window(1, 'Notes'), audio: false);
      final second = sharing.share(_window(2, 'Notes'), audio: false);
      final control = sharing.share(_window(3, 'bad\nname'), audio: false);
      final long = sharing.share(_window(4, 'x' * 200), audio: false);
      final untitled = sharing.share(_window(5, '   ', process: 'paint.exe'), audio: false);

      expect(first.feedName, 'Notes');
      expect(second.feedName, 'Notes (2)');
      expect(control.feedName, 'bad name');
      for (final window in sharing.shared) {
        expect(isValidName(window.feedName), isTrue, reason: window.feedName);
      }
      expect(long.feedName.length, lessThanOrEqualTo(maxNameLength));
      expect(untitled.feedName, 'paint.exe');
    });

    test('sharing the same window twice keeps one feed', () {
      final sharing = harness.sharing;

      final first = sharing.share(_window(1, 'Notes'), audio: true);
      final again = sharing.share(_window(1, 'Notes'), audio: false);

      expect(again, same(first));
      expect(sharing.shared, hasLength(1));
      expect(harness.feedChanges, 1);
    });

    test('the feed list carries the size and the sound of each window, and changes are announced', () {
      final sharing = harness.sharing;
      final window = sharing.share(_window(1, 'Notes'), audio: true);

      expect(sharing.feeds, [const FeedInfo('Notes', 1280, 720, 30, true)]);
      sharing.unshare(window);

      expect(sharing.feeds, isEmpty);
      expect(harness.feedChanges, 2);
    });
  });

  group('watching', () {
    test('the first watcher starts the capture at the size asked for, later ones only ask for a keyframe', () async {
      final sharing = harness.sharing;
      final window = sharing.share(_window(7, 'Notes'), audio: true);

      await sharing.subscribe('phone', _request('Notes', 1, width: 720, height: 1280, kbps: 2500, audio: true));
      await sharing.subscribe('tablet', _request('Notes', 1));

      expect(harness.methods, ['start', 'keyframe']);
      expect(harness.calls.first.arguments, {'handle': 7, 'width': 720, 'height': 1280, 'bitrateKbps': 2500, 'audio': true});
      expect(window.watchers, hasLength(2));
    });

    test('sizes and bitrates are kept in range', () async {
      final sharing = harness.sharing;
      sharing.share(_window(7, 'Notes'), audio: false);

      await sharing.subscribe('phone', _request('Notes', 1, width: 60000, height: 10, kbps: 1));

      final arguments = harness.calls.single.arguments as Map;
      expect(arguments['width'], 3840);
      expect(arguments['height'], 160);
      expect(arguments['bitrateKbps'], 500);
    });

    test('a request for a feed that is not offered is refused as not found', () async {
      final refusal = await harness.sharing.subscribe('phone', _request('Nothing', 1));

      expect(refusal, Refusal.notFound);
      expect(harness.calls, isEmpty);
    });

    test('the capture stops when the last watcher leaves', () async {
      final sharing = harness.sharing;
      sharing.share(_window(7, 'Notes'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 1));
      await sharing.subscribe('phone', _request('Notes', 2));

      sharing.unsubscribe('phone', 1);
      expect(harness.methods.last, 'keyframe', reason: 'one watcher is still there');
      sharing.unsubscribe('phone', 2);

      expect(harness.methods.last, 'stop');
    });

    test('one device gets four streams and the computer eight in all', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);

      for (var id = 1; id <= 6; id++) {
        await sharing.subscribe('phone', _request('Notes', id));
      }
      for (var id = 1; id <= 6; id++) {
        await sharing.subscribe('tablet-$id', _request('Notes', 1));
      }

      expect(sharing.shared.single.watchers.where((watcher) => watcher.deviceKey == 'phone'), hasLength(4));
      expect(sharing.shared.single.watchers, hasLength(8));
    });

    test('a stream over the limit is refused as busy, and one within it is not', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);

      for (var id = 1; id <= 4; id++) {
        expect(await sharing.subscribe('phone', _request('Notes', id)), isNull);
      }

      expect(await sharing.subscribe('phone', _request('Notes', 5)), Refusal.busy);
    });

    test('a device that goes away takes only its own streams with it', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 1));
      await sharing.subscribe('tablet', _request('Notes', 1));

      sharing.dropDevice('phone');
      expect(sharing.shared.single.watchers.map((watcher) => watcher.deviceKey), ['tablet']);
      expect(harness.methods, ['start', 'keyframe']);
      sharing.dropDevice('tablet');

      expect(harness.methods, ['start', 'keyframe', 'stop']);
    });

    test('unsharing a window that is being watched stops its capture', () async {
      final sharing = harness.sharing;
      final window = sharing.share(_window(1, 'Notes'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 1));

      sharing.unshare(window);

      expect(harness.methods, ['start', 'stop']);
    });

    test('a window Windows will not capture is dropped and the owner is told', () async {
      final sharing = harness.sharing;
      harness.failStart = true;
      final window = sharing.share(_window(1, 'Notes'), audio: false);

      await sharing.subscribe('phone', _request('Notes', 1));

      expect(window.watchers, isEmpty);
      expect(window.capturing, isFalse);
      expect(sharing.problem, contains('Notes'));
      sharing.dismissProblem();
      expect(sharing.problem, isNull);
    });
  });

  group('loss recovery', () {
    test('what a watcher reports missing is sent again, unchanged', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 4));
      final encoded = Uint8List.fromList([for (var i = 0; i < 5000; i++) (i * 13) & 0xFF]);
      await harness.fromRunner('video', {'handle': 1, 'timestamp': 0, 'keyframe': true, 'data': encoded});
      final original = harness.video.single.$3;
      harness.video.clear();

      sharing.resend('phone', const Nack(streamId: 4, frameId: 0, indexes: [1]));

      expect(harness.video.single.$1, 'phone');
      expect(harness.video.single.$2, 4);
      expect(harness.video.single.$3, [original[1]]);
    });

    test('a device that is not watching the stream is not answered', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 4));
      await harness.fromRunner('video', {'handle': 1, 'timestamp': 0, 'keyframe': true, 'data': Uint8List(3000)});
      harness.video.clear();

      sharing.resend('tablet', const Nack(streamId: 4, frameId: 0, indexes: []));
      sharing.resend('phone', const Nack(streamId: 9, frameId: 0, indexes: []));

      expect(harness.video, isEmpty);
    });

    test('a report of lost frames lowers the bitrate of the encoder', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 4, kbps: 3000));

      sharing.onReport('phone', const StreamReport(streamId: 4, windowMs: 500, fragments: 300, resendRequests: 0, lostFrames: 2, jitterMs10: 0));
      await pumpEventQueue();

      final call = harness.calls.lastWhere((call) => call.method == 'bitrate');
      expect((call.arguments as Map)['handle'], 1);
      expect((call.arguments as Map)['bitrateKbps'], 2550);
    });

    test('the encoder follows the watcher with the poorest link', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 4, kbps: 3000));
      await sharing.subscribe('tablet', _request('Notes', 9, kbps: 6000));

      sharing.onReport('tablet', const StreamReport(streamId: 9, windowMs: 500, fragments: 300, resendRequests: 0, lostFrames: 1, jitterMs10: 0));
      await pumpEventQueue();
      expect((harness.calls.lastWhere((call) => call.method == 'bitrate').arguments as Map)['bitrateKbps'], 3000, reason: 'the phone is still the smaller of the two');

      for (var i = 0; i < 4; i++) {
        sharing.onReport('tablet', StreamReport(streamId: 9, windowMs: 500, fragments: 300, resendRequests: 0, lostFrames: 2, jitterMs10: i));
      }
      await pumpEventQueue();
      expect(harness.methods.where((method) => method == 'bitrate'), isNotEmpty);
    });

    test('a report about a stream nobody watches changes nothing', () async {
      harness.sharing.share(_window(1, 'Notes'), audio: false);

      harness.sharing.onReport('phone', const StreamReport(streamId: 4, windowMs: 500, fragments: 1, resendRequests: 0, lostFrames: 5, jitterMs10: 0));
      await pumpEventQueue();

      expect(harness.methods, isNot(contains('bitrate')));
    });
  });

  group('windows that close', () {
    test('a window nobody watches that is found to be gone stays saved but is no longer offered, and devices are told', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      sharing.share(_window(2, 'Paint'), audio: false);
      harness.gone.add(1);

      await sharing.tick(10000);

      expect(sharing.shared.map((window) => window.feedName), ['Notes', 'Paint']);
      expect(sharing.feeds.map((feed) => feed.name), ['Paint']);
      expect(harness.feedChanges, 3);
    });

    test('windows are not asked about more often than every couple of seconds', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);

      await sharing.tick(10000);
      await sharing.tick(10500);
      await sharing.tick(12500);

      expect(harness.methods.where((method) => method == 'alive'), hasLength(2));
    });

    test('nothing is asked while nothing is shared', () async {
      await harness.sharing.tick(10000);

      expect(harness.calls, isEmpty);
    });
  });

  group('what comes back from the runner', () {
    test('video goes to every watcher, rebuilds into the same frame, and is counted in 90 kHz', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 4));
      await sharing.subscribe('tablet', _request('Notes', 9));
      final encoded = Uint8List.fromList([for (var i = 0; i < 5000; i++) (i * 13) & 0xFF]);

      await harness.fromRunner('video', {'handle': 1, 'timestamp': 10000000, 'keyframe': true, 'data': encoded});

      expect(harness.video.map((sent) => (sent.$1, sent.$2)).toSet(), {('phone', 4), ('tablet', 9)});
      final buffer = FrameBuffer();
      final frame = [for (final fragment in harness.video.first.$3) ...buffer.add(fragment, 0)].single.frame;
      expect(frame.encoded, encoded);
      expect(frame.keyframe, isTrue);
      expect(frame.timestamp, 90000, reason: 'one second');
    });

    test('video of a window nobody watches is not sent', () async {
      harness.sharing.share(_window(1, 'Notes'), audio: false);

      await harness.fromRunner('video', {'handle': 1, 'timestamp': 0, 'keyframe': true, 'data': Uint8List(10)});

      expect(harness.video, isEmpty);
    });

    test('sound goes only to the streams that asked for it', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: true);
      await sharing.subscribe('phone', _request('Notes', 1, audio: true));
      await sharing.subscribe('tablet', _request('Notes', 2));

      await harness.fromRunner('audio', {'handle': 1, 'timestamp': 20000000, 'data': Uint8List.fromList([1, 2, 3])});

      expect(harness.audio, hasLength(1));
      expect(harness.audio.single.$1, 'phone');
      expect(harness.audio.single.$3, 180000);
    });

    test('a window shared without sound never sends any, whatever the watcher asks', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 1, audio: true));

      await harness.fromRunner('audio', {'handle': 1, 'timestamp': 0, 'data': Uint8List(3)});

      expect(harness.audio, isEmpty);
    });

    test('a window that closes stops being offered and its watchers are let go', () async {
      final sharing = harness.sharing;
      final window = sharing.share(_window(1, 'Notes'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 1));

      await harness.fromRunner('closed', {'handle': 1});

      expect(sharing.shared, hasLength(1));
      expect(window.present, isFalse);
      expect(window.watchers, isEmpty);
      expect(sharing.feeds, isEmpty);
    });

    test('a share whose window is not open cannot be watched', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      await harness.fromRunner('closed', {'handle': 1});
      harness.calls.clear();

      await sharing.subscribe('phone', _request('Notes', 1));

      expect(harness.calls, isEmpty);
    });
  });

  group('saved shares', () {
    test('what is shared is saved, and stopping a share removes it', () {
      final sharing = harness.sharing;

      final window = sharing.share(_window(1, 'Notes', windowClass: 'Pad'), audio: true);
      expect(jsonDecode(harness.saves.last), [_entry('Notes', title: 'Notes', windowClass: 'Pad', audio: true)]);

      sharing.unshare(window);
      expect(jsonDecode(harness.saves.last), isEmpty);
    });

    test('after a restart a saved share is found again by its title, and offered', () async {
      harness.close();
      harness = _Harness(saved: _saved([_entry('Notes', windowClass: 'Pad')]));
      harness.open.add(_window(5, 'Notes', windowClass: 'Pad'));
      final sharing = harness.sharing;
      expect(sharing.feeds, isEmpty);

      await sharing.tick(0);

      expect(sharing.shared.single.handle, 5);
      expect(sharing.feeds.map((feed) => feed.name), ['Notes']);
      expect(harness.feedChanges, 1);
    });

    test('a window whose title changed is still found as one of the same type', () async {
      harness.close();
      harness = _Harness(saved: _saved([_entry('Browser', title: 'First page', process: 'browser.exe', windowClass: 'Chrome')]));
      harness.open
        ..add(_window(3, 'Other program', process: 'other.exe', windowClass: 'Chrome'))
        ..add(_window(4, 'Second page', process: 'browser.exe', windowClass: 'Chrome'));

      await harness.sharing.tick(0);

      expect(harness.sharing.shared.single.handle, 4);
    });

    test('an exact title beats a window of the same type', () async {
      harness.close();
      harness = _Harness(saved: _saved([_entry('Notes', title: 'Notes')]));
      harness.open
        ..add(_window(3, 'Something else'))
        ..add(_window(4, 'Notes'));

      await harness.sharing.tick(0);

      expect(harness.sharing.shared.single.handle, 4);
    });

    test('a window of another program is never taken, even with the same title', () async {
      harness.close();
      harness = _Harness(saved: _saved([_entry('Notes', process: 'notepad.exe')]));
      harness.open.add(_window(3, 'Notes', process: 'other.exe'));

      await harness.sharing.tick(0);

      expect(harness.sharing.shared.single.present, isFalse);
      expect(harness.sharing.feeds, isEmpty);
    });

    test('two saved shares never end up on the same window', () async {
      harness.close();
      harness = _Harness(saved: _saved([_entry('One', title: 'One'), _entry('Two', title: 'Two')]));
      harness.open.add(_window(3, 'Three'));

      await harness.sharing.tick(0);

      expect(harness.sharing.shared.where((window) => window.present), hasLength(1));
    });

    test('a window that closed is found again when it opens, with the handle it has then', () async {
      final sharing = harness.sharing;
      final window = sharing.share(_window(1, 'Notes'), audio: false);
      await harness.fromRunner('closed', {'handle': 1});
      expect(sharing.feeds, isEmpty);

      harness.open.add(_window(9, 'Notes'));
      await sharing.tick(10000);

      expect(window.handle, 9);
      expect(sharing.feeds.map((feed) => feed.name), ['Notes']);
    });

    test('a saved text that is damaged is ignored instead of breaking the hub', () {
      for (final text in ['not json', '{}', '[1, "x", {"name": 3}, {"name": "", "title": "t", "process": "p", "class": "c"}]']) {
        final other = _Harness(saved: text);
        expect(other.sharing.shared, isEmpty, reason: text);
        other.close();
      }
    });

    test('the number and size of saved shares is limited', () {
      final many = _Harness(saved: _saved([for (var i = 0; i < 100; i++) _entry('Window $i')]));
      expect(many.sharing.shared, hasLength(32));
      many.close();

      final long = _Harness(saved: _saved([_entry('Long', title: 'x' * 5000)]));
      expect(long.sharing.shared, isEmpty);
      long.close();
    });
  });

  group('displays', () {
    const second = r'\\.\DISPLAY2';

    test('a display is named after its number and saved as a display', () {
      final window = harness.sharing.share(_display(1 << 62 | 5, second), audio: true);

      expect(window.feedName, 'Display 2');
      expect(jsonDecode(harness.saves.last), [_entry('Display 2', title: second, process: '', windowClass: '', audio: true, display: true)]);
    });

    test('the capture of a display starts with the handle the runner gave', () async {
      final handle = 1 << 62 | 5;
      harness.sharing.share(_display(handle, second), audio: true);

      await harness.sharing.subscribe('phone', _request('Display 2', 1));

      expect((harness.calls.single.arguments as Map)['handle'], handle);
      expect((harness.calls.single.arguments as Map)['audio'], true);
    });

    test('after a restart a saved display is found again by its device name', () async {
      harness.close();
      harness = _Harness(saved: _saved([_entry('Display 2', title: second, process: '', windowClass: '', display: true)]));
      harness.screens
        ..add(_display(1 << 62 | 4, r'\\.\DISPLAY1', primary: true))
        ..add(_display(1 << 62 | 5, second));
      harness.open.add(_window(9, second));

      await harness.sharing.tick(0);

      expect(harness.sharing.shared.single.handle, 1 << 62 | 5);
      expect(harness.sharing.feeds.map((feed) => feed.name), ['Display 2']);
    });

    test('a display that is unplugged waits and is found again when it comes back', () async {
      final handle = 1 << 62 | 5;
      final window = harness.sharing.share(_display(handle, second), audio: false);
      harness.screens.add(_display(handle, second));

      await harness.sharing.tick(10000);
      expect(window.present, isTrue);
      expect(harness.methods, ['displays']);

      harness.screens.clear();
      await harness.sharing.tick(20000);
      expect(window.present, isFalse);
      expect(harness.sharing.feeds, isEmpty);

      harness.screens.add(_display(1 << 62 | 8, second));
      await harness.sharing.tick(30000);
      expect(window.handle, 1 << 62 | 8);
    });

    test('a window is never taken for a display of the same name', () async {
      harness.close();
      harness = _Harness(saved: _saved([_entry('Display 2', title: second, process: '', windowClass: '', display: true)]));
      harness.open.add(_window(9, second, process: '', windowClass: ''));

      await harness.sharing.tick(0);

      expect(harness.sharing.shared.single.present, isFalse);
    });
  });

  group('who is watching', () {
    test('a device is reported when it starts and when its last stream ends, not for every stream', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);

      await sharing.subscribe('phone', _request('Notes', 1));
      await sharing.subscribe('phone', _request('Notes', 2));
      expect(harness.watchEvents, [('phone', 'Notes', true)]);

      sharing.unsubscribe('phone', 1);
      expect(harness.watchEvents, hasLength(1), reason: 'one stream is still open');
      sharing.unsubscribe('phone', 2);

      expect(harness.watchEvents, [('phone', 'Notes', true), ('phone', 'Notes', false)]);
    });

    test('stopping everything, or a device going away, reports each device that was watching', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      sharing.share(_window(2, 'Paint'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 1));
      await sharing.subscribe('tablet', _request('Paint', 1));
      harness.watchEvents.clear();

      sharing.dropDevice('phone');
      expect(harness.watchEvents, [('phone', 'Notes', false)]);
      harness.watchEvents.clear();

      sharing.paused = true;
      expect(harness.watchEvents, [('tablet', 'Paint', false)]);
    });

    test('unsharing a feed that is being watched reports it under the name it had', () async {
      final sharing = harness.sharing;
      final window = sharing.share(_window(1, 'Notes'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 1));
      harness.watchEvents.clear();

      sharing.unshare(window);

      expect(harness.watchEvents, [('phone', 'Notes', false)]);
    });
  });

  group('stopping everything', () {
    const device = Device(publicKey: 'phone', name: 'phone', send: true, sendCamera: true);

    test('pausing ends every stream and capture at once, offers nothing and refuses new watchers, and resuming offers it all again', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      sharing.share(_camera(1 << 61 | 3, 'Webcam'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 1));
      await sharing.subscribe('phone', _request('Webcam', 2));
      final changes = harness.feedChanges;

      sharing.paused = true;

      expect(sharing.shared.expand((window) => window.watchers), isEmpty);
      expect(harness.methods.where((method) => method == 'stop'), hasLength(2));
      expect(sharing.feedsFor(device), isEmpty);
      expect(sharing.permits(device, 'Notes'), isFalse);
      expect(await sharing.subscribe('tablet', _request('Notes', 1)), Refusal.notFound);
      expect(harness.feedChanges, changes + 1, reason: 'devices are told the feeds are gone');

      sharing.paused = false;

      expect(sharing.feedsFor(device).map((feed) => feed.name), ['Notes', 'Webcam']);
      expect(await sharing.subscribe('tablet', _request('Notes', 1)), isNull);
    });

    test('pausing twice, or with nothing shared, changes nothing', () {
      harness.sharing.paused = true;
      harness.sharing.paused = true;

      expect(harness.feedChanges, 1);
      expect(harness.calls, isEmpty);
    });
  });

  group('pointer', () {
    test('the pointer is part of the picture unless the share says otherwise, and the runner is told only then', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      sharing.share(_window(2, 'Paint'), audio: false, showCursor: false);

      await sharing.subscribe('phone', _request('Notes', 1));
      await sharing.subscribe('phone', _request('Paint', 2));

      expect((harness.calls.first.arguments as Map).containsKey('cursor'), isFalse);
      expect((harness.calls.last.arguments as Map)['cursor'], false);
    });

    test('a share without the pointer is saved and comes back without it', () {
      harness.sharing.share(_window(1, 'Paint'), audio: false, showCursor: false);
      harness.sharing.share(_window(2, 'Notes'), audio: false);
      final saved = harness.saves.last;
      harness.close();
      harness = _Harness(saved: saved);

      expect(harness.sharing.shared.map((window) => window.showCursor), [false, true]);
    });
  });

  group('sound only', () {
    test('a window shared for its sound alone is named for it, announced without a size, and always has sound', () {
      final window = harness.sharing.share(_window(1, 'Spotify'), audio: false, soundOnly: true);

      expect(window.feedName, 'Spotify (sound)');
      expect(window.withAudio, isTrue);
      expect(harness.sharing.feeds, [const FeedInfo('Spotify (sound)', 0, 0, 0, true, soundOnly: true)]);
      expect(harness.sharing.feeds.single.soundOnly, isTrue);
    });

    test('a display shared for its sound alone is named for the computer', () {
      final first = harness.sharing.share(_display(1 << 62 | 5, r'\\.\DISPLAY2'), audio: true, soundOnly: true);

      expect(first.feedName, 'PC sound');
      expect(harness.sharing.share(_display(1 << 62 | 6, r'\\.\DISPLAY1'), audio: true, soundOnly: true).feedName, 'PC sound (2)');
    });

    test('a long title still leaves room for the suffix', () {
      final window = harness.sharing.share(_window(1, 'x' * 200), audio: true, soundOnly: true);

      expect(window.feedName.length, lessThanOrEqualTo(maxNameLength));
      expect(window.feedName, endsWith(' (sound)'));
    });

    test('the capture is started without a picture, and the runner is not asked for one otherwise', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Spotify'), audio: true, soundOnly: true);
      sharing.share(_window(2, 'Notes'), audio: true);

      await sharing.subscribe('phone', _request('Spotify (sound)', 1, audio: true));
      await sharing.subscribe('phone', _request('Notes', 2, audio: true));

      expect((harness.calls.first.arguments as Map)['picture'], false);
      expect((harness.calls.last.arguments as Map).containsKey('picture'), isFalse);
    });

    test('a camera cannot be shared without a picture', () {
      final window = harness.sharing.share(_camera(1 << 61 | 7, 'Webcam'), audio: true, soundOnly: true);

      expect(window.soundOnly, isFalse);
      expect(window.feedName, 'Webcam');
    });

    test('a sound-only share is saved and comes back as one', () async {
      harness.sharing.share(_window(1, 'Spotify'), audio: false, soundOnly: true);
      final saved = harness.saves.last;
      harness.close();
      harness = _Harness(saved: saved);

      final restored = harness.sharing.shared.single;
      expect(restored.soundOnly, isTrue);
      expect(restored.withAudio, isTrue);
      expect(restored.feedName, 'Spotify (sound)');
    });
  });

  group('cameras', () {
    const handle = 1 << 61 | 77;

    test('a camera is named after itself, saved with its device, and never shared with sound', () {
      final window = harness.sharing.share(_camera(handle, 'Logitech C920'), audio: true);

      expect(window.feedName, 'Logitech C920');
      expect(window.withAudio, isFalse);
      expect(harness.sharing.feeds, [const FeedInfo('Logitech C920', 1280, 720, 30, false)]);
      final saved = jsonDecode(harness.saves.last) as List;
      expect(saved.single, {..._entry('Logitech C920', title: 'Logitech C920', process: '', windowClass: ''), 'camera': true, 'device': r'\\?\usb#cam-1'});
    });

    test('the capture of a camera starts with the device the runner gave', () async {
      harness.sharing.share(_camera(handle, 'Logitech C920'), audio: false);

      await harness.sharing.subscribe('phone', _request('Logitech C920', 1));

      expect(harness.calls.single.arguments, {'handle': handle, 'width': 1280, 'height': 720, 'bitrateKbps': 3000, 'audio': false, 'device': r'\\?\usb#cam-1'});
    });

    test('a camera that cannot be opened is dropped and the owner is told why', () async {
      harness.failStart = true;
      final window = harness.sharing.share(_camera(handle, 'Logitech C920'), audio: false);

      expect(await harness.sharing.subscribe('phone', _request('Logitech C920', 1)), Refusal.failed);

      expect(window.watchers, isEmpty);
      expect(harness.sharing.problem, contains('Another app may be using it'));
    });

    test('a camera that is unplugged waits and is found again by its device, even with a new handle', () async {
      final window = harness.sharing.share(_camera(handle, 'Logitech C920'), audio: false);
      harness.webcams.add(_camera(handle, 'Logitech C920'));

      await harness.sharing.tick(10000);
      expect(window.present, isTrue);
      expect(harness.methods, ['cameras']);

      harness.webcams.clear();
      await harness.sharing.tick(20000);
      expect(window.present, isFalse);
      expect(harness.sharing.feeds, isEmpty);

      harness.webcams.add(_camera(1 << 61 | 99, 'Logitech C920'));
      await harness.sharing.tick(30000);
      expect(window.handle, 1 << 61 | 99);
    });

    test('after a restart a saved camera is found again by its name when it moved to another port', () async {
      harness.close();
      harness = _Harness(saved: _saved([{..._entry('Webcam', title: 'Webcam', process: '', windowClass: ''), 'camera': true, 'device': 'old-port'}]));
      harness.webcams
        ..add(_camera(1 << 61 | 1, 'Other camera', device: 'other'))
        ..add(_camera(1 << 61 | 2, 'Webcam', device: 'new-port'));

      await harness.sharing.tick(0);

      expect(harness.sharing.shared.single.handle, 1 << 61 | 2);
      expect(harness.sharing.shared.single.device, 'new-port');
    });

    test('windows and cameras are allowed separately, and a feed that does not exist is judged as a window', () {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      sharing.share(_camera(handle, 'Webcam'), audio: false);
      const windowsOnly = Device(publicKey: 'a', name: 'tablet', send: true);
      const cameraOnly = Device(publicKey: 'b', name: 'phone', sendCamera: true);
      const nothing = Device(publicKey: 'c', name: 'laptop');

      expect(sharing.feedsFor(windowsOnly).map((feed) => feed.name), ['Notes']);
      expect(sharing.feedsFor(cameraOnly).map((feed) => feed.name), ['Webcam']);
      expect(sharing.feedsFor(nothing), isEmpty);
      expect(sharing.permits(windowsOnly, 'Notes'), isTrue);
      expect(sharing.permits(windowsOnly, 'Webcam'), isFalse);
      expect(sharing.permits(cameraOnly, 'Webcam'), isTrue);
      expect(sharing.permits(cameraOnly, 'Notes'), isFalse);
      expect(sharing.permits(windowsOnly, 'Missing'), isTrue);
      expect(sharing.permits(cameraOnly, 'Missing'), isFalse);
    });

    test('a new Sharing offers no cameras until told to', () async {
      harness.webcams.add(_camera(handle, 'Webcam'));
      final fresh = Sharing(sendVideo: (device, stream, fragments) {}, sendAudio: (device, stream, timestamp, frame) {}, feedsChanged: () {});

      expect(fresh.offerCameras, isFalse);
      expect(await fresh.cameras(), isEmpty);
      fresh.dispose();
    });

    test('with cameras switched off none is offered, a shared one is withdrawn, and it comes back when switched on', () async {
      final sharing = harness.sharing;
      final window = sharing.share(_camera(handle, 'Webcam'), audio: false);
      await sharing.subscribe('phone', _request('Webcam', 1));
      harness.webcams.add(_camera(handle, 'Webcam'));

      sharing.offerCameras = false;

      expect(window.present, isFalse);
      expect(window.watchers, isEmpty);
      expect(harness.methods.last, 'stop');
      expect(sharing.feeds, isEmpty);
      harness.calls.clear();
      expect(await sharing.cameras(), isEmpty);
      await sharing.tick(10000);
      expect(window.present, isFalse);
      expect(harness.methods, isNot(contains('cameras')), reason: 'the runner is not asked about cameras at all');

      sharing.offerCameras = true;
      await sharing.tick(20000);
      expect(window.present, isTrue);
    });

    test('taking away the camera ends its streams and leaves the window streams alone', () async {
      final sharing = harness.sharing;
      sharing.share(_window(1, 'Notes'), audio: false);
      final webcam = sharing.share(_camera(handle, 'Webcam'), audio: false);
      await sharing.subscribe('phone', _request('Notes', 1));
      await sharing.subscribe('phone', _request('Webcam', 2));

      sharing.enforce(const Device(publicKey: 'phone', name: 'phone', send: true));

      expect(webcam.watchers, isEmpty);
      expect(webcam.capturing, isFalse);
      expect(sharing.shared.first.watchers, hasLength(1));
      expect(harness.methods.last, 'stop');
    });

    test('a window or a display is never taken for a camera of the same name', () async {
      harness.close();
      harness = _Harness(saved: _saved([{..._entry('Webcam', title: 'Webcam', process: '', windowClass: ''), 'camera': true}]));
      harness.open.add(_window(9, 'Webcam', process: '', windowClass: ''));
      harness.screens.add(_display(1 << 62 | 4, 'Webcam'));

      await harness.sharing.tick(0);

      expect(harness.sharing.shared.single.present, isFalse);
    });
  });
}
