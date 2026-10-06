import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'audio.dart';
import 'control.dart';
import 'control_channel.dart';
import 'crypto.dart';
import 'devices.dart';
import 'noise.dart';
import 'session.dart';
import 'video.dart';
import 'wire.dart';

abstract class EndpointListener {
  void onPairingCode(Address address, String remoteName, String code) {}

  void onPaired(Device device) {}

  void onPairingFailed(String reason) {}

  void onLinkUp(Device device, Address address) {}

  void onLinkDown(Device device) {}

  /// Heard again after a silence. Nothing is lost, but a stream has a hole in its timeline.
  void onLinkResumed(Device device) {}

  void onConnectFailed(Device device) {}

  void onFeeds(Device device, List<FeedInfo> feeds) {}

  void onSubscribe(Device device, Subscribe request) {}

  void onSubscribeRefused(Device device, SubscribeRefused refusal) {}

  void onUnsubscribe(Device device, int streamId) {}

  void onKeyframeRequest(Device device, int streamId) {}

  void onNack(Device device, Nack nack) {}

  void onStreamReport(Device device, StreamReport report) {}

  void onSenderStats(Device device, SenderStats stats) {}

  void onVideo(Device device, int streamId, Uint8List fragment) {}

  /// [timestamp] counts 90 kHz ticks on the same clock as the picture's.
  void onAudio(Device device, int streamId, int timestamp, Uint8List frame) {}
}

final class _Link {
  _Link(this.publicKey, this.address, this.session, this.index);

  final String publicKey;
  Address address;
  final Session session;
  final int index;
  late ControlChannel control;
  int heard = 0;
  int pinged = 0;
  int probed = 0;

  /// Negative until the first reply.
  double rttMs = -1;
  final List<double> rttSamples = [];
}

final class _Pairing {
  _Pairing(this.initiator, this.address, this.handshake, this.localIndex, this.startedAt);

  final bool initiator;
  Address address;
  final NoiseHandshake handshake;
  final int localIndex;
  final int startedAt;
  int remoteIndex = 0;
  Uint8List? lastSent;
  int lastSentAt = 0;
  int resends = 0;
  _Link? link;
  String remoteName = '';
  String code = '';
  bool heardData = false;
  bool localConfirmed = false;
  bool remoteConfirmed = false;

  bool get established => link != null;
}

final class _Dialing {
  _Dialing(this.device, this.address, this.index, this.handshake, this.sentAt);

  final Device device;
  final Address address;
  final int index;
  NoiseHandshake handshake;
  int sentAt;
  int attempts = 1;
}

/// No socket or clock of its own, so it can be tested without a network.
final class Endpoint {
  Endpoint({
    required this.identity,
    required this.devices,
    required this.clock,
    required this.transmit,
    required this.listener,
    KeyPair Function()? newKeyPair,
    int Function()? wallClock,
    this.log,
  })  : _newKeyPair = newKeyPair ?? Crypto.generateKeyPair,
        _wallClock = wallClock ?? (() => DateTime.now().millisecondsSinceEpoch);

  static const pairingWindowMs = 120000;
  static const handshakeTimeoutMs = 10000;
  static const handshakeResendMs = 1000;
  static const maxHandshakeResends = 5;
  static const confirmTimeoutMs = 120000;
  static const pingIntervalMs = 3000;
  static const linkTimeoutMs = 15000;

  // Pings and probes arrive every one to three seconds.
  static const resumeAfterSilenceMs = 2500;

  // Resent until the link would time out anyway.
  static const _establishedAttempts = linkTimeoutMs ~/ ControlChannel.resendAfterMs;
  static const probeIntervalMs = 1000;
  static const _rttSamples = 9;

  static final _prologue = Uint8List.fromList(ascii.encode('manifold-net/1'));

  final Identity identity;
  final DeviceBook devices;
  final int Function() clock;
  final void Function(Address, Uint8List) transmit;
  final EndpointListener listener;
  final KeyPair Function() _newKeyPair;

  final void Function(String message)? log;

  /// Stamps each hello. It has to keep growing across restarts, which [clock] does not.
  final int Function() _wallClock;

  final Random _random = Random.secure();
  int _pairingOpenUntil = 0;
  _Pairing? _pairing;
  final Map<String, _Dialing> _dialing = {};
  final Map<String, _Link> _links = {};
  final Map<int, _Link> _linksByIndex = {};
  final Map<String, int> _lastHello = {};

  void openPairing([int durationMs = pairingWindowMs]) {
    _pairingOpenUntil = clock() + durationMs;
  }

  void closePairing() {
    _pairingOpenUntil = 0;
  }

  void pair(Address address) {
    if (_pairing != null) throw StateError('a pairing is already in progress');
    final handshake = NoiseHandshake(Pattern.xx, true, identity.keys, prologue: _prologue, newEphemeral: _newKeyPair);
    final attempt = _Pairing(true, address, handshake, _newIndex(), clock());
    _pairing = attempt;
    _sendHandshake(attempt, HandshakePacket(Wire.typePair, 0, attempt.localIndex, 0, handshake.writeMessage()));
  }

  void confirmPairing(bool accept) {
    final current = _pairing;
    final link = current?.link;
    if (current == null || link == null) return;
    if (accept) {
      current.localConfirmed = true;
      link.control.send(const PairConfirm(), clock());
      _completePairingIfBoth(current);
    } else {
      link.control.send(const PairReject(), clock());
      _endPairing('declined');
    }
  }

  void cancelPairing() => _endPairing('cancelled');

  void connect(Device device, Address address) {
    if (_links.containsKey(device.publicKey) || _dialing.containsKey(device.publicKey)) return;
    final key = fromHex(device.publicKey);
    if (key == null) return;
    final attempt = _Dialing(device, address, _newIndex(), _helloHandshake(key), clock());
    _dialing[device.publicKey] = attempt;
    _sendHello(attempt);
  }

  void disconnect(String publicKey) {
    _dialing.remove(publicKey);
    final link = _links[publicKey];
    if (link == null) return;
    link.control.send(const Bye(), clock());
    _drop(link, 'closed from this device');
  }

  void unpair(String publicKey) {
    disconnect(publicKey);
    devices.remove(publicKey);
  }

  void sendFeeds(String publicKey, List<FeedInfo> feeds) => _links[publicKey]?.control.send(FeedList(feeds), clock());

  void subscribe(String publicKey, Subscribe request) => _links[publicKey]?.control.send(request, clock());

  void refuseSubscribe(String publicKey, int streamId, Refusal reason) =>
      _links[publicKey]?.control.send(SubscribeRefused(streamId, reason), clock());

  void unsubscribe(String publicKey, int streamId) => _links[publicKey]?.control.send(Unsubscribe(streamId), clock());

  void requestKeyframe(String publicKey, int streamId) => _links[publicKey]?.control.send(KeyframeRequest(streamId), clock());

  /// Video is not resent when lost: a late frame is no use, and the receiver asks for a keyframe instead.
  void sendVideo(String publicKey, int streamId, List<Uint8List> fragments) {
    final link = _links[publicKey];
    if (link == null) return;
    for (final fragment in fragments) {
      transmit(link.address, link.session.seal(StreamKind.video, VideoPacket.encode(streamId, fragment)));
    }
  }

  /// Like video, audio is not resent: a late frame would only be heard as a glitch.
  void sendAudio(String publicKey, int streamId, int timestamp, Uint8List frame) {
    final link = _links[publicKey];
    if (link == null) return;
    transmit(link.address, link.session.seal(StreamKind.audio, AudioPacket.encode(streamId, timestamp, frame)));
  }

  void requestRetransmit(String publicKey, Nack nack) => _links[publicKey]?.control.send(nack, clock());

  void sendStreamReport(String publicKey, StreamReport report) => _links[publicKey]?.control.send(report, clock());

  void sendSenderStats(String publicKey, SenderStats stats) => _links[publicKey]?.control.send(stats, clock());

  double? rttMs(String publicKey) {
    final rtt = _links[publicKey]?.rttMs;
    return rtt == null || rtt < 0 ? null : rtt;
  }

  bool isLinked(String publicKey) => _links.containsKey(publicKey);

  bool isDialing(String publicKey) => _dialing.containsKey(publicKey);

  void onDatagram(Address from, Uint8List datagram) {
    if (datagram.isEmpty) return;
    switch (datagram[0]) {
      case Wire.typePair || Wire.typeHello:
        final packet = Wire.decodeHandshake(datagram);
        if (packet == null) return;
        if (packet.type == Wire.typePair) {
          _onPair(from, packet);
        } else {
          _onHello(from, packet);
        }
      case Session.typeData:
        _onData(from, datagram);
    }
  }

  void _onPair(Address from, HandshakePacket packet) {
    switch (packet.step) {
      case 0:
        _startedByOther(from, packet);
      case 1:
        _answeredByOther(from, packet);
      case 2:
        _finishedByOther(from, packet);
    }
  }

  void _startedByOther(Address from, HandshakePacket packet) {
    final current = _pairing;
    if (current != null) {
      // The same request again means our answer was lost.
      if (!current.initiator && !current.established && current.remoteIndex == packet.senderIndex) {
        final last = current.lastSent;
        if (last != null) transmit(from, last);
      }
      return;
    }
    if (clock() > _pairingOpenUntil) return;
    final handshake = NoiseHandshake(Pattern.xx, false, identity.keys, prologue: _prologue, newEphemeral: _newKeyPair);
    try {
      handshake.readMessage(packet.message);
    } on HandshakeException {
      return;
    }
    final attempt = _Pairing(false, from, handshake, _newIndex(), clock());
    attempt.remoteIndex = packet.senderIndex;
    _pairing = attempt;
    final reply = handshake.writeMessage(_nameBytes(identity.name));
    _sendHandshake(attempt, HandshakePacket(Wire.typePair, 1, attempt.localIndex, attempt.remoteIndex, reply));
  }

  void _answeredByOther(Address from, HandshakePacket packet) {
    final current = _pairing;
    if (current == null || !current.initiator || current.established || packet.receiverIndex != current.localIndex) return;
    final Uint8List payload;
    try {
      payload = current.handshake.readMessage(packet.message);
    } on HandshakeException {
      return;
    }
    current.address = from;
    current.remoteIndex = packet.senderIndex;
    current.remoteName = _readName(payload);
    final third = current.handshake.writeMessage(_nameBytes(identity.name));
    _sendHandshake(current, HandshakePacket(Wire.typePair, 2, current.localIndex, current.remoteIndex, third));
    _establishPairing(current);
  }

  void _finishedByOther(Address from, HandshakePacket packet) {
    final current = _pairing;
    if (current == null ||
        current.initiator ||
        current.established ||
        packet.receiverIndex != current.localIndex ||
        packet.senderIndex != current.remoteIndex) {
      return;
    }
    final Uint8List payload;
    try {
      payload = current.handshake.readMessage(packet.message);
    } on HandshakeException {
      return;
    }
    current.address = from;
    current.remoteName = _readName(payload);
    _establishPairing(current);
  }

  void _establishPairing(_Pairing current) {
    final key = current.handshake.remoteStaticKey;
    if (key == null) return _endPairing('the other device sent no key');
    final session = Session(localIndex: current.localIndex, remoteIndex: current.remoteIndex, keys: current.handshake.split());
    final link = _newLink(toHex(key), current.address, session, current.localIndex);
    current.link = link;
    current.code = _pairingCode(current.handshake.handshakeHash);
    _linksByIndex[current.localIndex] = link;
    listener.onPairingCode(current.address, current.remoteName, current.code);
  }

  void _onHello(Address from, HandshakePacket packet) {
    switch (packet.step) {
      case 0:
        _helloReceived(from, packet);
      case 1:
        _helloAnswered(from, packet);
    }
  }

  // Unknown keys and replays get no answer at all.
  void _helloReceived(Address from, HandshakePacket packet) {
    final handshake = NoiseHandshake(Pattern.ik, false, identity.keys, prologue: _prologue, newEphemeral: _newKeyPair);
    final Uint8List payload;
    try {
      payload = handshake.readMessage(packet.message);
    } on HandshakeException {
      return;
    }
    final remote = handshake.remoteStaticKey;
    if (remote == null) return;
    final device = devices.findKey(remote);
    if (device == null || payload.length < 8) return;
    // Both devices dialing at once would leave each holding a different session. The device
    // with the smaller key keeps its own attempt; the other drops its own and answers.
    if (_dialing.containsKey(device.publicKey)) {
      if (toHex(identity.keys.public).compareTo(device.publicKey) < 0) return;
      _dialing.remove(device.publicKey);
    }
    // Each hello carries a time that must be newer than the last, so a recorded one is useless.
    final sentAt = ByteData.sublistView(payload).getInt64(0, Endian.big);
    if (sentAt <= (_lastHello[device.publicKey] ?? -0x8000000000000000)) return;
    _lastHello[device.publicKey] = sentAt;

    final reply = handshake.writeMessage();
    final index = _newIndex();
    final session = Session(localIndex: index, remoteIndex: packet.senderIndex, keys: handshake.split());
    transmit(from, Wire.encode(HandshakePacket(Wire.typeHello, 1, index, packet.senderIndex, reply)));
    _bringUp(device.publicKey, from, session, index);
  }

  void _helloAnswered(Address from, HandshakePacket packet) {
    final attempt = _dialing.values.where((entry) => entry.index == packet.receiverIndex).firstOrNull;
    if (attempt == null) return;
    try {
      attempt.handshake.readMessage(packet.message);
    } on HandshakeException {
      return;
    }
    _dialing.remove(attempt.device.publicKey);
    final session = Session(localIndex: attempt.index, remoteIndex: packet.senderIndex, keys: attempt.handshake.split());
    _bringUp(attempt.device.publicKey, from, session, attempt.index);
  }

  void _bringUp(String publicKey, Address address, Session session, int index) {
    // A peer that restarts dials in again before the old link has timed out. Whatever was
    // running over the old link has to hear that it ended, because the peer forgot it.
    final old = _links[publicKey];
    if (old != null) _drop(old, 'the other device dialed in again');
    final link = _newLink(publicKey, address, session, index);
    link.control.giveUpAfter = _establishedAttempts;
    _links[publicKey] = link;
    _linksByIndex[index] = link;
    final device = devices.find(publicKey);
    if (device != null) listener.onLinkUp(device, address);
  }

  void _onData(Address from, Uint8List datagram) {
    final index = Wire.dataReceiver(datagram);
    if (index == null) return;
    final link = _linksByIndex[index];
    if (link == null) return;
    final opened = link.session.open(datagram);
    if (opened == null) return;
    final heardAt = clock();
    final silentFor = heardAt - link.heard;
    link.heard = heardAt;
    link.address = from;
    if (silentFor > resumeAfterSilenceMs && identical(_links[link.publicKey], link)) {
      final device = devices.find(link.publicKey);
      if (device != null) listener.onLinkResumed(device);
    }
    final current = _pairing;
    if (current != null && identical(current.link, link)) current.heardData = true;
    switch (opened.stream) {
      case StreamKind.control:
        link.control.receive(opened.payload);
      case StreamKind.video:
        _onVideoPacket(link, opened.payload);
      case StreamKind.audio:
        _onAudioPacket(link, opened.payload);
    }
  }

  void _onVideoPacket(_Link link, Uint8List payload) {
    if (!identical(_links[link.publicKey], link)) return;
    final device = devices.find(link.publicKey);
    final streamId = VideoPacket.streamOf(payload);
    if (device == null || streamId == null) return;
    listener.onVideo(device, streamId, VideoPacket.fragmentOf(payload));
  }

  void _onAudioPacket(_Link link, Uint8List payload) {
    if (!identical(_links[link.publicKey], link)) return;
    final device = devices.find(link.publicKey);
    final packet = AudioPacket.decode(payload);
    if (device == null || packet == null) return;
    listener.onAudio(device, packet.streamId, packet.timestamp, packet.frame);
  }

  void _handleControl(_Link link, Control control) {
    final current = _pairing;
    if (current != null && identical(current.link, link)) {
      switch (control) {
        case PairConfirm():
          current.remoteConfirmed = true;
          _completePairingIfBoth(current);
        case PairReject():
          _endPairing('the other device declined');
        default:
          break;
      }
      return;
    }
    final device = devices.find(link.publicKey);
    if (device == null) return;
    switch (control) {
      case FeedList():
        listener.onFeeds(device, control.feeds);
      case Subscribe():
        listener.onSubscribe(device, control);
      case SubscribeRefused():
        listener.onSubscribeRefused(device, control);
      case Unsubscribe():
        listener.onUnsubscribe(device, control.streamId);
      case KeyframeRequest():
        listener.onKeyframeRequest(device, control.streamId);
      case TimeRequest():
        link.control.send(TimeReply(control.sentAt), clock());
      case TimeReply():
        _measureRtt(link, control.sentAt);
      case SenderStats():
        listener.onSenderStats(device, control);
      case Nack():
        listener.onNack(device, control);
      case StreamReport():
        listener.onStreamReport(device, control);
      case Bye():
        _drop(link, 'the other device said goodbye');
      default:
        break;
    }
  }

  void tick() {
    final now = clock();
    final current = _pairing;
    if (current != null) _tickPairing(current, now);
    for (final attempt in _dialing.values.toList()) {
      _tickDialing(attempt, now);
    }
    for (final link in _links.values.toList()) {
      _tickLink(link, now);
    }
  }

  void _tickPairing(_Pairing current, int now) {
    final link = current.link;
    if (link == null) {
      if (now - current.startedAt > handshakeTimeoutMs) return _endPairing('no answer');
      _resendHandshake(current, now);
      return;
    }
    if (now - current.startedAt > confirmTimeoutMs) return _endPairing('timed out');
    // The third message may have been lost, and the other side cannot continue without it.
    if (current.initiator && !current.heardData) _resendHandshake(current, now);
    link.control.tick(now);
  }

  void _resendHandshake(_Pairing current, int now) {
    final last = current.lastSent;
    if (last == null || now - current.lastSentAt < handshakeResendMs || current.resends >= maxHandshakeResends) return;
    current.resends++;
    current.lastSentAt = now;
    transmit(current.address, last);
  }

  void _tickDialing(_Dialing attempt, int now) {
    if (now - attempt.sentAt < handshakeResendMs) return;
    if (attempt.attempts >= maxHandshakeResends) {
      _dialing.remove(attempt.device.publicKey);
      listener.onConnectFailed(attempt.device);
      return;
    }
    final key = fromHex(attempt.device.publicKey);
    if (key == null) return;
    // A fresh handshake each time: the other side refuses a hello it has already seen.
    attempt.handshake = _helloHandshake(key);
    attempt.attempts++;
    attempt.sentAt = now;
    _sendHello(attempt);
  }

  void _measureRtt(_Link link, int sentAt) {
    link.rttSamples.add((clock() - sentAt).clamp(0, 1 << 30).toDouble());
    if (link.rttSamples.length > _rttSamples) link.rttSamples.removeAt(0);
    // The first reply after a quiet spell can be slow while the radio wakes. A median does not carry that
    // along.
    link.rttMs = (List.of(link.rttSamples)..sort())[link.rttSamples.length ~/ 2];
  }

  void _tickLink(_Link link, int now) {
    link.control.tick(now);
    if (!_links.containsValue(link)) return;
    if (now - link.heard > linkTimeoutMs) return _drop(link, 'nothing heard for ${(now - link.heard) / 1000} s');
    if (now - link.probed >= probeIntervalMs) {
      link.probed = now;
      link.control.send(TimeRequest(now), now);
    }
    if (now - link.pinged >= pingIntervalMs) {
      link.pinged = now;
      link.control.send(const Ping(), now);
    }
  }

  void _completePairingIfBoth(_Pairing current) {
    final link = current.link;
    if (link == null || !current.localConfirmed || !current.remoteConfirmed) return;
    // Pairing again keeps what the owner had switched on for this device.
    final known = devices.find(link.publicKey) ?? Device(publicKey: link.publicKey, name: current.remoteName);
    devices.put(known.copyWith(name: current.remoteName, address: current.address.toString()));
    _pairing = null;
    // One pairing is what the owner opened the window for.
    _pairingOpenUntil = 0;
    final old = _links[link.publicKey];
    if (old != null) _drop(old, 'replaced by a new pairing');
    link.control.giveUpAfter = _establishedAttempts;
    _links[link.publicKey] = link;
    final stored = devices.find(link.publicKey)!;
    listener.onPaired(stored);
    listener.onLinkUp(stored, current.address);
  }

  void _endPairing(String reason) {
    final current = _pairing;
    if (current == null) return;
    _pairing = null;
    final link = current.link;
    if (link != null) _linksByIndex.remove(link.index);
    listener.onPairingFailed(reason);
  }

  void _drop(_Link link, String reason) {
    final known = identical(_links[link.publicKey], link);
    if (known) _links.remove(link.publicKey);
    _linksByIndex.remove(link.index);
    if (!known) return;
    final device = devices.find(link.publicKey) ?? Device(publicKey: link.publicKey, name: '');
    log?.call('link to ${device.name.isEmpty ? link.publicKey.substring(0, 8) : device.name} down: $reason');
    listener.onLinkDown(device);
  }

  _Link _newLink(String publicKey, Address address, Session session, int index) {
    final link = _Link(publicKey, address, session, index);
    link.control = ControlChannel(
      transmit: (payload) => transmit(link.address, session.seal(StreamKind.control, payload)),
      onMessage: (control) => _handleControl(link, control),
      onFailed: () {
        final current = _pairing;
        if (current != null && identical(current.link, link)) {
          _endPairing('lost contact');
        } else {
          _drop(link, 'a message got no answer after ${link.control.giveUpAfter} tries');
        }
      },
    );
    link.heard = clock();
    link.pinged = link.heard;
    return link;
  }

  void _sendHandshake(_Pairing current, HandshakePacket packet) {
    final bytes = Wire.encode(packet);
    current.lastSent = bytes;
    current.lastSentAt = clock();
    current.resends = 0;
    transmit(current.address, bytes);
  }

  NoiseHandshake _helloHandshake(Uint8List remoteKey) =>
      NoiseHandshake(Pattern.ik, true, identity.keys, remoteStatic: remoteKey, prologue: _prologue, newEphemeral: _newKeyPair);

  void _sendHello(_Dialing attempt) {
    final stamp = Uint8List(8);
    ByteData.sublistView(stamp).setInt64(0, _wallClock(), Endian.big);
    final message = attempt.handshake.writeMessage(Uint8List.fromList([...stamp, ..._nameBytes(identity.name)]));
    transmit(attempt.address, Wire.encode(HandshakePacket(Wire.typeHello, 0, attempt.index, 0, message)));
  }

  int _newIndex() {
    while (true) {
      final candidate = _random.nextInt(1 << 32) - (1 << 31);
      final pairingIndex = _pairing?.localIndex;
      final free = !_linksByIndex.containsKey(candidate) && pairingIndex != candidate && !_dialing.values.any((entry) => entry.index == candidate);
      if (candidate != 0 && free) return candidate;
    }
  }

  String _pairingCode(Uint8List handshakeHash) {
    final number = ByteData.sublistView(handshakeHash).getUint32(0, Endian.big);
    return (number % 1000000).toString().padLeft(6, '0');
  }

  Uint8List _nameBytes(String name) => Uint8List.fromList(utf8.encode(name.length > maxNameLength ? name.substring(0, maxNameLength) : name));

  String _readName(Uint8List bytes) {
    final cleaned = utf8.decode(bytes, allowMalformed: true).replaceAll('\t', ' ').replaceAll('\n', ' ').trim();
    final name = cleaned.length > maxNameLength ? cleaned.substring(0, maxNameLength) : cleaned;
    return name.isEmpty ? 'Unknown device' : name;
  }
}
