import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'clock.dart';
import 'net/control.dart';
import 'net/devices.dart';
import 'net/endpoint.dart';
import 'net/wire.dart';
import 'sharing.dart';
import 'watching.dart';

const listenPort = 47200;
const _tickEvery = Duration(milliseconds: 250);

// A quarter second tick is too coarse to wait a couple of frames for a missing piece.
const _pollEvery = Duration(milliseconds: 4);
const _connectEveryTicks = 20;

sealed class PairingUi {
  const PairingUi();
}

final class NoPairing extends PairingUi {
  const NoPairing();
}

final class Connecting extends PairingUi {
  const Connecting(this.address);

  final String address;
}

/// Set once the owner said the codes match and the other device has yet to answer.
final class CodeShown extends PairingUi {
  const CodeShown(this.remoteName, this.code, {this.confirmed = false});

  final String remoteName;
  final String code;
  final bool confirmed;
}

final class PairingFailed extends PairingUi {
  const PairingFailed(this.reason);

  final String reason;
}

final class Network extends ChangeNotifier {
  Network(this.identity, this.book, {String? savedShares, void Function(String)? saveShares}) {
    _endpoint = Endpoint(identity: identity, devices: book, clock: () => _clock.elapsedMilliseconds, transmit: _send, listener: _Reports(this));
    watching = Watching(
      subscribe: (deviceKey, request) => _endpoint.subscribe(deviceKey, request),
      unsubscribe: (deviceKey, streamId) => _endpoint.unsubscribe(deviceKey, streamId),
      requestKeyframe: (deviceKey, streamId) => _endpoint.requestKeyframe(deviceKey, streamId),
      requestResend: (deviceKey, nack) => _endpoint.requestRetransmit(deviceKey, nack),
      report: (deviceKey, report) => _endpoint.sendStreamReport(deviceKey, report),
      rttMs: _endpoint.rttMs,
    );
    sharing = Sharing(
      sendVideo: (deviceKey, streamId, fragments) => _endpoint.sendVideo(deviceKey, streamId, fragments),
      sendAudio: (deviceKey, streamId, timestamp, frame) => _endpoint.sendAudio(deviceKey, streamId, timestamp, frame),
      feedsChanged: _offerToAll,
      saved: savedShares,
      saveShares: saveShares,
    );
    book.devices.addListener(_devicesChanged);
  }

  final Identity identity;
  final DeviceBook book;
  late final Endpoint _endpoint;

  late final Sharing sharing;

  final Stopwatch _clock = Stopwatch()..start();
  final Map<String, InternetAddress> _resolved = {};
  RawDatagramSocket? _socket;
  Timer? _timer;
  Timer? _pollTimer;
  int _ticks = 0;

  bool listening = false;
  List<String> addresses = const [];

  /// Time on this class's own clock until which other devices may start pairing, in milliseconds.
  int pairingOpenUntil = 0;
  PairingUi pairing = const NoPairing();
  final Set<String> online = {};
  final Map<String, List<FeedInfo>> remoteFeeds = {};

  late final Watching watching;

  int get nowMs => _clock.elapsedMilliseconds;

  Future<void> start() async {
    if (_socket != null) return;
    final socket = await RawDatagramSocket.bind(InternetAddress.anyIPv4, listenPort);
    _socket = socket;
    socket.listen((event) {
      if (event != RawSocketEvent.read) return;
      for (var datagram = socket.receive(); datagram != null; datagram = socket.receive()) {
        _endpoint.onDatagram(Address(datagram.address.address, datagram.port), Uint8List.fromList(datagram.data));
      }
    });
    addresses = await _localAddresses();
    listening = true;
    _ticks = 0;
    _timer = Timer.periodic(_tickEvery, (_) => _tick());
    notifyListeners();
  }

  Future<void> stop() async {
    final socket = _socket;
    if (socket == null) return;
    await watching.stopAll();
    sharing.dropAll();
    for (final device in book.devices.value) {
      _endpoint.disconnect(device.publicKey);
    }
    _timer?.cancel();
    _timer = null;
    _pollTimer?.cancel();
    _pollTimer = null;
    socket.close();
    _socket = null;
    listening = false;
    online.clear();
    remoteFeeds.clear();
    pairingOpenUntil = 0;
    pairing = const NoPairing();
    notifyListeners();
  }

  Future<void> openPairing() async {
    await start();
    _endpoint.openPairing();
    pairingOpenUntil = nowMs + Endpoint.pairingWindowMs;
    notifyListeners();
  }

  void closePairing() {
    _endpoint.closePairing();
    pairingOpenUntil = 0;
    notifyListeners();
  }

  Future<void> pair(String text) async {
    final address = Address.parse(text, listenPort);
    if (address == null) {
      pairing = const PairingFailed('that is not a usable address');
      notifyListeners();
      return;
    }
    await start();
    pairing = Connecting(address.toString());
    notifyListeners();
    try {
      _resolved[address.host] = (await InternetAddress.lookup(address.host, type: InternetAddressType.IPv4)).first;
    } on SocketException {
      pairing = PairingFailed('could not find ${address.host}');
      notifyListeners();
      return;
    }
    try {
      _endpoint.pair(address);
    } on StateError {
      // A pairing is already in progress; its dialog is still showing.
    }
  }

  void confirmPairing(bool accept) {
    _endpoint.confirmPairing(accept);
    final shown = pairing;
    if (!accept) {
      pairing = const NoPairing();
    } else if (shown is CodeShown) {
      pairing = CodeShown(shown.remoteName, shown.code, confirmed: true);
    }
    notifyListeners();
  }

  void cancelPairing() {
    _endpoint.cancelPairing();
    pairing = const NoPairing();
    notifyListeners();
  }

  void dismissPairing() {
    pairing = const NoPairing();
    notifyListeners();
  }

  void setReceive(String publicKey, bool on) {
    book.update(publicKey, (device) => device.copyWith(receive: on));
  }

  void setSend(String publicKey, bool on) {
    book.update(publicKey, (device) => device.copyWith(send: on));
  }

  void unpair(String publicKey) {
    sharing.dropDevice(publicKey);
    _endpoint.unpair(publicKey);
    online.remove(publicKey);
    remoteFeeds.remove(publicKey);
    notifyListeners();
  }

  List<({Device device, FeedInfo feed})> get watchable => [
        for (final device in book.devices.value)
          if (device.receive && online.contains(device.publicKey))
            for (final feed in remoteFeeds[device.publicKey] ?? const <FeedInfo>[]) (device: device, feed: feed),
      ];

  void _tick() {
    _endpoint.tick();
    watching.tick(nowMs);
    if (_pollTimer == null && !watching.isEmpty) {
      _pollTimer = Timer.periodic(_pollEvery, (_) {
        if (watching.isEmpty) {
          _pollTimer?.cancel();
          _pollTimer = null;
        } else {
          watching.poll(monotonicNs());
        }
      });
    }
    unawaited(sharing.tick(nowMs));
    if (_ticks++ % _connectEveryTicks == 0) _connectKnownDevices();
    if (pairingOpenUntil != 0 && nowMs > pairingOpenUntil) {
      pairingOpenUntil = 0;
      notifyListeners();
    }
  }

  void _connectKnownDevices() {
    for (final device in book.devices.value) {
      final address = device.address == null ? null : Address.parse(device.address!, listenPort);
      if (address == null || _endpoint.isLinked(device.publicKey) || _endpoint.isDialing(device.publicKey)) continue;
      _endpoint.connect(device, address);
    }
  }

  void _devicesChanged() {
    // A device that stopped being received from loses its stream at once.
    watching.stopWhere((session) => book.find(session.deviceKey)?.receive != true);
    for (final device in book.devices.value) {
      if (!device.send) sharing.dropDevice(device.publicKey);
    }
    _offerToAll();
    notifyListeners();
  }

  void _send(Address to, Uint8List bytes) {
    final socket = _socket;
    if (socket == null) return;
    final target = _resolved[to.host] ?? InternetAddress.tryParse(to.host);
    if (target == null) return;
    socket.send(bytes, target, to.port);
  }

  Future<List<String>> _localAddresses() async {
    final interfaces = await NetworkInterface.list(type: InternetAddressType.IPv4);
    return [
      for (final interface in interfaces)
        for (final address in interface.addresses)
          if (!address.isLoopback) '${address.address}:$listenPort',
    ];
  }

  @override
  void dispose() {
    book.devices.removeListener(_devicesChanged);
    sharing.dispose();
    watching.dispose();
    _timer?.cancel();
    _pollTimer?.cancel();
    _socket?.close();
    super.dispose();
  }

  void _pairingCode(String remoteName, String code) {
    pairing = CodeShown(remoteName, code);
    notifyListeners();
  }

  void _paired() {
    pairing = const NoPairing();
    pairingOpenUntil = 0;
    notifyListeners();
  }

  void _pairingFailed(String reason) {
    pairing = PairingFailed(reason);
    notifyListeners();
  }

  void _linkUp(Device device, Address address) {
    online.add(device.publicKey);
    book.update(device.publicKey, (known) => known.copyWith(address: address.toString()));
    _offerFeeds(device);
    notifyListeners();
  }

  void _linkDown(Device device) {
    online.remove(device.publicKey);
    remoteFeeds.remove(device.publicKey);
    watching.stopWhere((session) => session.deviceKey == device.publicKey);
    sharing.dropDevice(device.publicKey);
    notifyListeners();
  }

  void _feeds(Device device, List<FeedInfo> feeds) {
    remoteFeeds[device.publicKey] = feeds;
    watching.stopWhere((session) => session.deviceKey == device.publicKey && !feeds.any((feed) => feed.name == session.feedName));
    notifyListeners();
  }

  void _offerFeeds(Device device) {
    final current = book.find(device.publicKey);
    if (current == null || !_endpoint.isLinked(current.publicKey)) return;
    _endpoint.sendFeeds(current.publicKey, current.send ? sharing.feeds : const []);
  }

  void _offerToAll() {
    for (final device in book.devices.value) {
      _offerFeeds(device);
    }
  }

  void _subscribed(Device device, Subscribe request) {
    if (book.find(device.publicKey)?.send != true) return;
    unawaited(sharing.subscribe(device.publicKey, request));
  }

  void _video(Device device, int streamId, Uint8List fragment) {
    watching.onVideo(device, streamId, fragment);
  }

  void _audio(Device device, int streamId, int timestamp, Uint8List frame) {
    watching.onAudio(device, streamId, timestamp, frame);
  }
}

final class _Reports extends EndpointListener {
  _Reports(this._network);

  final Network _network;

  @override
  void onPairingCode(Address address, String remoteName, String code) => _network._pairingCode(remoteName, code);

  @override
  void onPaired(Device device) => _network._paired();

  @override
  void onPairingFailed(String reason) => _network._pairingFailed(reason);

  @override
  void onLinkUp(Device device, Address address) => _network._linkUp(device, address);

  @override
  void onLinkDown(Device device) => _network._linkDown(device);

  @override
  void onFeeds(Device device, List<FeedInfo> feeds) => _network._feeds(device, feeds);

  @override
  void onSubscribe(Device device, Subscribe request) => _network._subscribed(device, request);

  @override
  void onUnsubscribe(Device device, int streamId) => _network.sharing.unsubscribe(device.publicKey, streamId);

  @override
  void onKeyframeRequest(Device device, int streamId) => _network.sharing.requestKeyframe(device.publicKey, streamId);

  @override
  void onNack(Device device, Nack nack) => _network.sharing.resend(device.publicKey, nack);

  @override
  void onStreamReport(Device device, StreamReport report) => _network.sharing.onReport(device.publicKey, report);

  @override
  void onVideo(Device device, int streamId, Uint8List fragment) => _network._video(device, streamId, fragment);

  @override
  void onAudio(Device device, int streamId, int timestamp, Uint8List frame) => _network._audio(device, streamId, timestamp, frame);
}
