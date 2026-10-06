import 'dart:collection';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/network/protocol/audio.dart';
import 'package:manifold_hub/network/protocol/control.dart';
import 'package:manifold_hub/network/protocol/crypto.dart';
import 'package:manifold_hub/network/protocol/devices.dart';
import 'package:manifold_hub/network/protocol/endpoint.dart';
import 'package:manifold_hub/network/protocol/session.dart';
import 'package:manifold_hub/network/protocol/video.dart';
import 'package:manifold_hub/network/protocol/wire.dart';

typedef _Sent = ({Address from, Address to, Uint8List bytes});

class _World {
  int now = 1000000;
  final nodes = <Address, _Node>{};
  final log = <_Sent>[];
  bool Function(int, Uint8List) dropIf = (_, _) => false;
  bool cut = false;
  final _queue = Queue<_Sent>();
  int _sent = 0;

  void enqueue(Address from, Address to, Uint8List bytes) {
    final entry = (from: from, to: to, bytes: Uint8List.fromList(bytes));
    log.add(entry);
    _queue.add(entry);
  }

  void pump() {
    while (_queue.isNotEmpty) {
      final entry = _queue.removeFirst();
      if (cut || dropIf(_sent++, entry.bytes)) continue;
      nodes[entry.to]?.endpoint.onDatagram(entry.from, entry.bytes);
    }
  }

  void advance(int ms) {
    for (var left = ms; left > 0; left -= 100) {
      now += 100;
      for (final node in nodes.values) {
        node.endpoint.tick();
      }
      pump();
    }
  }
}

class _Node extends EndpointListener {
  _Node(this.world, String name, this.address) : identity = Identity(Crypto.generateKeyPair(), name) {
    start();
    world.nodes[address] = this;
  }

  final _World world;
  final Address address;
  final Identity identity;
  String? stored;
  late DeviceBook book;
  late Endpoint endpoint;
  final codes = <String>[];
  final paired = <Device>[];
  final failures = <String>[];
  final linksUp = <Device>[];
  final linksDown = <Device>[];
  final connectFailed = <Device>[];
  final feeds = <List<FeedInfo>>[];
  final subscribes = <Subscribe>[];
  final unsubscribes = <int>[];
  final refusals = <SubscribeRefused>[];
  final keyframeRequests = <int>[];
  final senderStats = <SenderStats>[];
  final nacks = <Nack>[];
  final video = <(int, Uint8List)>[];
  final audio = <AudioPacket>[];

  void start() {
    book = DeviceBook(stored, (text) => stored = text);
    endpoint = Endpoint(
      identity: identity,
      devices: book,
      clock: () => world.now,
      wallClock: () => world.now,
      transmit: (to, bytes) => world.enqueue(address, to, bytes),
      listener: this,
    );
  }

  String get publicKey => toHex(identity.keys.public);

  @override
  void onPairingCode(Address address, String remoteName, String code) => codes.add(code);

  @override
  void onPaired(Device device) => paired.add(device);

  @override
  void onPairingFailed(String reason) => failures.add(reason);

  @override
  void onLinkUp(Device device, Address address) => linksUp.add(device);

  @override
  void onLinkDown(Device device) => linksDown.add(device);

  @override
  void onConnectFailed(Device device) => connectFailed.add(device);

  @override
  void onFeeds(Device device, List<FeedInfo> offered) => feeds.add(offered);

  @override
  void onSubscribe(Device device, Subscribe request) => subscribes.add(request);

  @override
  void onSubscribeRefused(Device device, SubscribeRefused refusal) => refusals.add(refusal);

  @override
  void onUnsubscribe(Device device, int streamId) => unsubscribes.add(streamId);

  @override
  void onKeyframeRequest(Device device, int streamId) => keyframeRequests.add(streamId);

  @override
  void onNack(Device device, Nack nack) => nacks.add(nack);

  @override
  void onSenderStats(Device device, SenderStats stats) => senderStats.add(stats);

  @override
  void onVideo(Device device, int streamId, Uint8List fragment) => video.add((streamId, fragment));

  @override
  void onAudio(Device device, int streamId, int timestamp, Uint8List frame) => audio.add(AudioPacket(streamId, timestamp, frame));
}

({_World world, _Node alpha, _Node beta}) _pairedWorld() {
  final world = _World();
  final alpha = _Node(world, 'Alpha', const Address('10.0.0.1', 4000));
  final beta = _Node(world, 'Beta', const Address('10.0.0.2', 4000));
  beta.endpoint.openPairing();
  alpha.endpoint.pair(beta.address);
  world.pump();
  alpha.endpoint.confirmPairing(true);
  beta.endpoint.confirmPairing(true);
  world.pump();
  return (world: world, alpha: alpha, beta: beta);
}

({_World world, _Node alpha, _Node beta}) _unpairedWorld() {
  final world = _World();
  return (world: world, alpha: _Node(world, 'Alpha', const Address('10.0.0.1', 4000)), beta: _Node(world, 'Beta', const Address('10.0.0.2', 4000)));
}

void main() {
  test('two owners pair by comparing the same code', () {
    final (:world, :alpha, :beta) = _unpairedWorld();
    beta.endpoint.openPairing();

    alpha.endpoint.pair(beta.address);
    world.pump();

    expect(alpha.codes.length, 1);
    expect(alpha.codes, beta.codes);
    expect(RegExp(r'^\d{6}$').hasMatch(alpha.codes.single), isTrue);
    expect(alpha.paired.isEmpty && beta.paired.isEmpty, isTrue, reason: 'nothing is saved before both confirm');

    alpha.endpoint.confirmPairing(true);
    world.pump();
    expect(alpha.paired.isEmpty && beta.paired.isEmpty, isTrue, reason: 'one confirmation is not enough');

    beta.endpoint.confirmPairing(true);
    world.pump();

    expect(alpha.paired.single.name, 'Beta');
    expect(beta.paired.single.name, 'Alpha');
    expect(alpha.paired.single.fingerprint, beta.identity.fingerprint);
    expect(beta.paired.single.fingerprint, alpha.identity.fingerprint);
    expect(alpha.book.findKey(beta.identity.keys.public), isNotNull);
    expect(alpha.endpoint.isLinked(beta.publicKey) && beta.endpoint.isLinked(alpha.publicKey), isTrue);
  });

  test('a newly paired device has nothing switched on', () {
    final (:alpha, :beta, world: _) = _pairedWorld();
    final device = alpha.book.findKey(beta.identity.keys.public)!;
    expect(device.receive, isFalse);
    expect(device.send, isFalse);
  });

  test('different pairings give different codes', () {
    expect(_pairedWorld().alpha.codes.single, isNot(_pairedWorld().alpha.codes.single));
  });

  test('a device that declines ends the pairing for both', () {
    final (:world, :alpha, :beta) = _unpairedWorld();
    beta.endpoint.openPairing();
    alpha.endpoint.pair(beta.address);
    world.pump();

    beta.endpoint.confirmPairing(false);
    alpha.endpoint.confirmPairing(true);
    world.pump();

    expect(beta.failures, ['declined']);
    expect(alpha.failures.length, 1);
    expect(alpha.book.devices.value.isEmpty && beta.book.devices.value.isEmpty, isTrue);
  });

  test('pairing is ignored while the other device has it closed', () {
    final (:world, :alpha, :beta) = _unpairedWorld();

    alpha.endpoint.pair(beta.address);
    world.pump();
    world.advance(Endpoint.handshakeTimeoutMs + 1000);

    expect(alpha.failures, ['no answer']);
    expect(beta.codes, isEmpty);
    expect(world.log.length, 1 + Endpoint.maxHandshakeResends);
  });

  test('the window for pairing closes', () {
    final (:world, :alpha, :beta) = _unpairedWorld();
    beta.endpoint.openPairing(5000);
    world.advance(6000);

    alpha.endpoint.pair(beta.address);
    world.pump();

    expect(beta.codes, isEmpty);
  });

  test('a pairing nobody confirms ends and saves nothing', () {
    final (:world, :alpha, :beta) = _unpairedWorld();
    beta.endpoint.openPairing();
    alpha.endpoint.pair(beta.address);
    world.pump();
    alpha.endpoint.confirmPairing(true);

    world.advance(Endpoint.confirmTimeoutMs + 1000);

    expect(beta.failures, ['timed out']);
    expect(alpha.book.devices.value.isEmpty && beta.book.devices.value.isEmpty, isTrue);
  });

  test('pairing survives lost packets', () {
    final (:world, :alpha, :beta) = _unpairedWorld();
    beta.endpoint.openPairing();
    final seen = <int>{};
    world.dropIf = (_, bytes) => bytes[0] != Session.typeData && seen.add(bytes[0] * 31 + bytes[1]);

    alpha.endpoint.pair(beta.address);
    world.advance(8000);

    expect(alpha.codes, beta.codes);
    expect(alpha.codes.length, 1);
  });

  test('paired devices reconnect after a restart', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    alpha.start();
    beta.start();
    expect(alpha.endpoint.isLinked(beta.publicKey), isFalse);

    alpha.endpoint.connect(alpha.book.findKey(beta.identity.keys.public)!, beta.address);
    world.pump();

    expect(alpha.endpoint.isLinked(beta.publicKey), isTrue);
    expect(beta.endpoint.isLinked(alpha.publicKey), isTrue);
    expect(beta.linksUp.last.name, 'Alpha');
  });

  test('a peer that restarts and dials in replaces its old link and the old one is reported down', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    expect(alpha.linksUp, hasLength(1));

    // Only beta restarts, so alpha still holds the link that beta has forgotten.
    beta.start();
    beta.endpoint.connect(beta.book.findKey(alpha.identity.keys.public)!, alpha.address);
    world.pump();

    expect(alpha.linksDown, hasLength(1));
    expect(alpha.linksUp, hasLength(2));
    expect(alpha.endpoint.isLinked(beta.publicKey) && beta.endpoint.isLinked(alpha.publicKey), isTrue);
  });

  test('pairing again while linked reports the old link down', () {
    final (:world, :alpha, :beta) = _pairedWorld();

    beta.endpoint.openPairing();
    alpha.endpoint.pair(beta.address);
    world.pump();
    alpha.endpoint.confirmPairing(true);
    beta.endpoint.confirmPairing(true);
    world.pump();

    expect(alpha.linksDown, hasLength(1));
    expect(beta.linksDown, hasLength(1));
    expect(alpha.linksUp, hasLength(2));
  });

  test('the window for pairing closes once pairing succeeds', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    final stranger = _Node(world, 'Stranger', const Address('10.0.0.9', 4000));
    final codesBefore = beta.codes.length;

    stranger.endpoint.pair(beta.address);
    world.pump();

    expect(beta.codes, hasLength(codesBefore));
  });

  test('the round trip is measured once the link has been up for a second', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    expect(alpha.endpoint.rttMs(beta.publicKey), isNull);

    world.advance(Endpoint.probeIntervalMs + 500);

    expect(alpha.endpoint.rttMs(beta.publicKey), isNotNull);
    expect(beta.endpoint.rttMs(alpha.publicKey), isNotNull);
    expect(alpha.endpoint.rttMs('00' * 32), isNull, reason: 'a device that is not linked has no round trip');
  });

  test('a request to send fragments again reaches the publisher', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    const nack = Nack(streamId: 4, frameId: 77, indexes: [1, 5]);

    alpha.endpoint.requestRetransmit(beta.publicKey, nack);
    world.pump();

    expect(beta.nacks, [nack]);
  });

  test('sender stats reach the watching device', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    const stats = SenderStats(streamId: 3, bitrateKbps: 2000, fps10: 300, encodeMs10: 80, sendMs10: 5);

    beta.endpoint.sendSenderStats(alpha.publicKey, stats);
    world.pump();

    expect(alpha.senderStats, [stats]);
  });

  test('a device that was never paired gets no answer', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    final stranger = _Node(world, 'Stranger', const Address('10.0.0.9', 4000));
    final before = world.log.length;

    stranger.endpoint.connect(Device(publicKey: beta.publicKey, name: 'Beta'), beta.address);
    world.advance(7000);

    expect(beta.endpoint.isLinked(stranger.publicKey), isFalse);
    expect(stranger.connectFailed.length, 1);
    expect(world.log.skip(before).any((entry) => entry.from == beta.address && entry.to == stranger.address), isFalse, reason: 'the hub stayed silent');
  });

  test('a recorded hello cannot be replayed', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    alpha.start();
    beta.start();
    final mark = world.log.length;
    alpha.endpoint.connect(alpha.book.findKey(beta.identity.keys.public)!, beta.address);
    world.pump();
    final hello = world.log.skip(mark).firstWhere((entry) => entry.from == alpha.address && entry.bytes[0] == Wire.typeHello);
    int answers() => world.log.where((entry) => entry.from == beta.address && entry.bytes[0] == Wire.typeHello).length;
    final before = answers();

    beta.endpoint.onDatagram(hello.from, hello.bytes);
    world.pump();

    expect(answers(), before, reason: 'no second answer');
  });

  test('a silent link is dropped on both sides', () {
    final (:world, :alpha, :beta) = _pairedWorld();

    world.cut = true;
    world.advance(Endpoint.linkTimeoutMs + 2000);

    expect(alpha.endpoint.isLinked(beta.publicKey), isFalse);
    expect(beta.endpoint.isLinked(alpha.publicKey), isFalse);
    expect(alpha.linksDown.length, 1);
    expect(beta.linksDown.length, 1);
  });

  test('an idle link stays up on pings', () {
    final (:world, :alpha, :beta) = _pairedWorld();

    world.advance(60000);

    expect(alpha.endpoint.isLinked(beta.publicKey) && beta.endpoint.isLinked(alpha.publicKey), isTrue);
    expect(alpha.linksDown, isEmpty);
  });

  test('feed lists arrive once even when packets are lost or repeated', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    var dropped = 0;
    world.dropIf = (_, bytes) => bytes[0] == Session.typeData && dropped++ < 2;
    const offered = [FeedInfo('alpha', 720, 1080, 30, false), FeedInfo('camera', 1280, 720, 30, true)];

    alpha.endpoint.sendFeeds(beta.publicKey, offered);
    world.advance(3000);

    expect(beta.feeds, [offered]);
  });

  test('saying goodbye makes the other side drop the link at once', () {
    final (:world, :alpha, :beta) = _pairedWorld();

    alpha.endpoint.disconnect(beta.publicKey);
    world.pump();

    expect(beta.endpoint.isLinked(alpha.publicKey), isFalse);
    expect(beta.linksDown.length, 1);
  });

  test('unpairing forgets the device and it cannot reconnect', () {
    final (:world, :alpha, :beta) = _pairedWorld();

    beta.endpoint.unpair(alpha.publicKey);
    world.pump();
    alpha.endpoint.connect(alpha.book.findKey(beta.identity.keys.public)!, beta.address);
    world.advance(7000);

    expect(beta.book.devices.value, isEmpty);
    expect(beta.endpoint.isLinked(alpha.publicKey), isFalse);
  });

  test('pairing again keeps what was switched on', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    alpha.book.update(beta.publicKey, (device) => device.copyWith(receive: true));
    alpha.endpoint.disconnect(beta.publicKey);
    beta.endpoint.disconnect(alpha.publicKey);
    world.pump();

    beta.endpoint.openPairing();
    alpha.endpoint.pair(beta.address);
    world.pump();
    alpha.endpoint.confirmPairing(true);
    beta.endpoint.confirmPairing(true);
    world.pump();

    expect(alpha.book.findKey(beta.identity.keys.public)!.receive, isTrue);
  });

  test('two devices dialing at the same time end up with one working link', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    alpha.start();
    beta.start();

    alpha.endpoint.connect(alpha.book.findKey(beta.identity.keys.public)!, beta.address);
    beta.endpoint.connect(beta.book.findKey(alpha.identity.keys.public)!, alpha.address);
    world.pump();
    world.advance(2000);
    const offered = [FeedInfo('alpha', 720, 1080, 30, false)];
    alpha.endpoint.sendFeeds(beta.publicKey, offered);
    beta.endpoint.sendFeeds(alpha.publicKey, offered);
    world.advance(1000);

    expect(beta.feeds, [offered]);
    expect(alpha.feeds, [offered]);
    expect(alpha.endpoint.isLinked(beta.publicKey) && beta.endpoint.isLinked(alpha.publicKey), isTrue);
  });

  test('a device is not dialed twice', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    alpha.start();
    final device = alpha.book.findKey(beta.identity.keys.public)!;

    alpha.endpoint.connect(device, beta.address);
    final sent = world.log.length;
    alpha.endpoint.connect(device, beta.address);

    expect(world.log.length, sent);
    expect(alpha.endpoint.isDialing(beta.publicKey), isTrue);
  });

  test('a subscription reaches the publisher unchanged', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    const request = Subscribe(7, 'alpha', 720, 1080, 2500);

    alpha.endpoint.subscribe(beta.publicKey, request);
    alpha.endpoint.requestKeyframe(beta.publicKey, 7);
    alpha.endpoint.unsubscribe(beta.publicKey, 7);
    world.pump();

    expect(beta.subscribes, [request]);
    expect(beta.keyframeRequests, [7]);
    expect(beta.unsubscribes, [7]);
  });

  test('a refused subscription is answered with its reason', () {
    final (:world, :alpha, :beta) = _pairedWorld();

    beta.endpoint.refuseSubscribe(alpha.publicKey, 7, Refusal.notShared);
    world.pump();

    expect(alpha.refusals, [const SubscribeRefused(7, Refusal.notShared)]);
  });

  test('video crosses the link and is rebuilt into the same frame', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    final original = Frame(9, 90000, true, Uint8List.fromList([for (var i = 0; i < 40000; i++) (i * 7) & 0xFF]));

    beta.endpoint.sendVideo(alpha.publicKey, 12, Fragmenter.split(original));
    world.pump();

    expect(alpha.video.every((entry) => entry.$1 == 12), isTrue);
    final buffer = FrameBuffer();
    final rebuilt = [for (final entry in alpha.video) ...buffer.add(entry.$2, 0)].single.frame;
    expect(rebuilt.encoded, original.encoded);
    expect(rebuilt.keyframe, isTrue);
  });

  test('audio crosses the link with its stream and timestamp', () {
    final (:world, :alpha, :beta) = _pairedWorld();
    final frame = Uint8List.fromList([for (var i = 0; i < 340; i++) i & 0xFF]);

    beta.endpoint.sendAudio(alpha.publicKey, 12, 90000, frame);
    beta.endpoint.sendAudio(alpha.publicKey, 12, 91875, frame);
    world.pump();

    expect(alpha.audio.map((packet) => packet.streamId), [12, 12]);
    expect(alpha.audio.map((packet) => packet.timestamp), [90000, 91875]);
    expect(alpha.audio.first.frame, frame);
    expect(alpha.video, isEmpty, reason: 'audio is not mistaken for video');
  });

  test('audio for a device that is not linked goes nowhere', () {
    final (:world, alpha: _, :beta) = _pairedWorld();
    final before = world.log.length;

    beta.endpoint.sendAudio('00' * 32, 1, 0, Uint8List(100));

    expect(world.log.length, before);
  });

  test('video for a device that is not linked goes nowhere', () {
    final (:world, alpha: _, :beta) = _pairedWorld();
    final before = world.log.length;

    beta.endpoint.sendVideo('00' * 32, 1, [Uint8List(100)]);

    expect(world.log.length, before);
  });
}
