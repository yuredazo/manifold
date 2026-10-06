import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'protocol/beacon.dart';
import 'protocol/devices.dart';

@immutable
final class NearbyDevice {
  const NearbyDevice({required this.id, required this.name, required this.host, required this.port});

  final String id;
  final String name;
  final String host;
  final int port;
}

final class NearbyBook {
  NearbyBook({this.ttl = const Duration(seconds: 4), this.limit = 16});

  final Duration ttl;
  final int limit;
  final Map<String, (NearbyDevice, Duration)> _heard = {};

  void hear(Beacon beacon, String host, Duration now) {
    _prune(now);
    if (_heard.length >= limit && !_heard.containsKey(beacon.id)) return;
    _heard[beacon.id] = (NearbyDevice(id: beacon.id, name: beacon.name, host: host, port: beacon.port), now);
  }

  List<NearbyDevice> current(Duration now) {
    _prune(now);
    return [for (final (device, _) in _heard.values) device]..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
  }

  void clear() => _heard.clear();

  void _prune(Duration now) => _heard.removeWhere((_, entry) => now - entry.$2 > ttl);
}

/// Finds devices on the same network that are open for pairing. It listens only while the pairing dialog is open and
/// announces only while this device lets others pair, so an idle hub says nothing.
final class Discovery extends ChangeNotifier {
  Discovery(this._identity);

  final Identity _identity;
  final NearbyBook _book = NearbyBook();
  final Stopwatch _clock = Stopwatch()..start();
  RawDatagramSocket? _socket;
  Timer? _prune;
  List<NearbyDevice> _nearby = const [];
  List<String> _keys = const [];

  String get _ownId => _identity.fingerprint.substring(0, 16);

  List<NearbyDevice> get nearby => _nearby;

  Future<void> listen() async {
    if (_socket != null) return;
    final RawDatagramSocket socket;
    try {
      socket = await RawDatagramSocket.bind(InternetAddress.anyIPv4, BeaconCodec.port, reuseAddress: true);
    } on SocketException catch (error) {
      debugPrint('discovery: cannot listen on port ${BeaconCodec.port}: ${error.message}');
      return;
    }
    socket.broadcastEnabled = true;
    _socket = socket;
    socket.listen((event) {
      if (event != RawSocketEvent.read) return;
      for (var datagram = socket.receive(); datagram != null; datagram = socket.receive()) {
        final beacon = BeaconCodec.decode(Uint8List.fromList(datagram.data));
        if (beacon == null || beacon.id == _ownId) continue;
        _book.hear(beacon, datagram.address.address, _clock.elapsed);
        _publish();
      }
    });
    _prune = Timer.periodic(const Duration(seconds: 1), (_) => _publish());
  }

  void stop() {
    _prune?.cancel();
    _prune = null;
    _socket?.close();
    _socket = null;
    _book.clear();
    _nearby = const [];
    _keys = const [];
    notifyListeners();
  }

  /// One beacon out of each private network this computer is on, so it leaves through the right adapter.
  Future<void> announce(int listenPort) async {
    final packet = BeaconCodec.encode(Beacon(_ownId, listenPort, _identity.name));
    for (final local in await _privateAddresses()) {
      try {
        final out = await RawDatagramSocket.bind(local, 0);
        out.broadcastEnabled = true;
        out.send(packet, InternetAddress('255.255.255.255'), BeaconCodec.port);
        out.close();
      } on SocketException catch (error) {
        debugPrint('discovery: cannot announce from ${local.address}: ${error.message}');
      }
    }
  }

  void _publish() {
    if (_socket == null) return;
    final now = _book.current(_clock.elapsed);
    final keys = [for (final device in now) '${device.id}@${device.host}'];
    if (listEquals(keys, _keys)) return;
    _keys = keys;
    _nearby = now;
    notifyListeners();
  }

  /// Private ranges only: a tunnel cannot carry a broadcast, and nothing should be announced there.
  Future<List<InternetAddress>> _privateAddresses() async {
    final interfaces = await NetworkInterface.list(type: InternetAddressType.IPv4);
    return [
      for (final interface in interfaces)
        for (final address in interface.addresses)
          if (_isPrivate(address.rawAddress)) address,
    ];
  }

  static bool _isPrivate(Uint8List a) => a[0] == 10 || (a[0] == 172 && a[1] >= 16 && a[1] <= 31) || (a[0] == 192 && a[1] == 168);

  @override
  void dispose() {
    stop();
    super.dispose();
  }
}
