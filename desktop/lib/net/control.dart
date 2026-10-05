import 'dart:convert';
import 'dart:typed_data';

import 'session.dart';

const maxNameLength = 64;
const maxDimension = 8192;
const maxFeeds = 64;

/// Whether [name] can be used for a feed: 1 to 64 characters after trimming, no control characters.
bool isValidName(String name) {
  final trimmed = name.trim();
  if (trimmed.isEmpty || trimmed.length > maxNameLength) return false;
  return !trimmed.codeUnits.any((unit) => unit <= 0x1F || (unit >= 0x7F && unit <= 0x9F));
}

final class FeedInfo {
  const FeedInfo(this.name, this.width, this.height, this.fps, this.hasAudio);

  final String name;
  final int width;
  final int height;
  final int fps;
  final bool hasAudio;

  @override
  bool operator ==(Object other) =>
      other is FeedInfo && other.name == name && other.width == width && other.height == height && other.fps == fps && other.hasAudio == hasAudio;

  @override
  int get hashCode => Object.hash(name, width, height, fps, hasAudio);

  @override
  String toString() => 'FeedInfo($name, ${width}x$height, $fps fps, audio=$hasAudio)';
}

sealed class Control {
  const Control();
}

final class Ping extends Control {
  const Ping();
}

final class PairConfirm extends Control {
  const PairConfirm();
}

final class PairReject extends Control {
  const PairReject();
}

final class Bye extends Control {
  const Bye();
}

final class FeedList extends Control {
  const FeedList(this.feeds);

  final List<FeedInfo> feeds;
}

final class Subscribe extends Control {
  const Subscribe(this.streamId, this.feed, this.width, this.height, this.bitrateKbps, {this.audio = false, this.fps = defaultFps});

  static const defaultFps = 30;
  static const maxFps = 60;

  final int streamId;
  final String feed;
  final int width;
  final int height;
  final int bitrateKbps;
  final bool audio;

  /// Frames per second wanted. Older senders of this message leave it out, which means [defaultFps].
  final int fps;

  @override
  bool operator ==(Object other) =>
      other is Subscribe &&
      other.streamId == streamId &&
      other.feed == feed &&
      other.width == width &&
      other.height == height &&
      other.bitrateKbps == bitrateKbps &&
      other.audio == audio &&
      other.fps == fps;

  @override
  int get hashCode => Object.hash(streamId, feed, width, height, bitrateKbps, audio, fps);
}

/// A reason this version does not know reads as [failed].
enum Refusal {
  notShared(1),
  notFound(2),
  busy(3),
  failed(4);

  const Refusal(this.code);

  final int code;

  static Refusal of(int code) => values.firstWhere((reason) => reason.code == code, orElse: () => failed);
}

final class SubscribeRefused extends Control {
  const SubscribeRefused(this.streamId, this.reason);

  final int streamId;
  final Refusal reason;

  @override
  bool operator ==(Object other) => other is SubscribeRefused && other.streamId == streamId && other.reason == reason;

  @override
  int get hashCode => Object.hash(streamId, reason);
}

final class Unsubscribe extends Control {
  const Unsubscribe(this.streamId);

  final int streamId;
}

final class KeyframeRequest extends Control {
  const KeyframeRequest(this.streamId);

  final int streamId;
}

final class TimeRequest extends Control {
  const TimeRequest(this.sentAt);

  final int sentAt;
}

final class TimeReply extends Control {
  const TimeReply(this.sentAt);

  final int sentAt;
}

/// Empty [indexes] means the whole frame. Never resent itself.
final class Nack extends Control {
  const Nack({required this.streamId, required this.frameId, required this.indexes});

  static const maxIndexes = 200;

  final int streamId;
  final int frameId;
  final List<int> indexes;

  @override
  bool operator ==(Object other) =>
      other is Nack && other.streamId == streamId && other.frameId == frameId && _sameList(other.indexes, indexes);

  @override
  int get hashCode => Object.hash(streamId, frameId, Object.hashAll(indexes));

  static bool _sameList(List<int> a, List<int> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}

/// [jitterMs10] is in tenths of a millisecond. Never resent.
final class StreamReport extends Control {
  const StreamReport({
    required this.streamId,
    required this.windowMs,
    required this.fragments,
    required this.resendRequests,
    required this.lostFrames,
    required this.jitterMs10,
  });

  final int streamId;
  final int windowMs;
  final int fragments;
  final int resendRequests;
  final int lostFrames;
  final int jitterMs10;

  @override
  bool operator ==(Object other) =>
      other is StreamReport &&
      other.streamId == streamId &&
      other.windowMs == windowMs &&
      other.fragments == fragments &&
      other.resendRequests == resendRequests &&
      other.lostFrames == lostFrames &&
      other.jitterMs10 == jitterMs10;

  @override
  int get hashCode => Object.hash(streamId, windowMs, fragments, resendRequests, lostFrames, jitterMs10);
}

/// What the publisher sees of a stream, once a second. Times are in tenths of a millisecond so a fast
/// encoder still shows a number.
final class SenderStats extends Control {
  const SenderStats({required this.streamId, required this.bitrateKbps, required this.fps10, required this.encodeMs10, required this.sendMs10});

  final int streamId;
  final int bitrateKbps;
  final int fps10;
  final int encodeMs10;
  final int sendMs10;

  @override
  bool operator ==(Object other) =>
      other is SenderStats &&
      other.streamId == streamId &&
      other.bitrateKbps == bitrateKbps &&
      other.fps10 == fps10 &&
      other.encodeMs10 == encodeMs10 &&
      other.sendMs10 == sendMs10;

  @override
  int get hashCode => Object.hash(streamId, bitrateKbps, fps10, encodeMs10, sendMs10);
}

sealed class Decoded {
  const Decoded();
}

final class DecodedAck extends Decoded {
  const DecodedAck(this.id);

  final int id;
}

final class DecodedMessage extends Decoded {
  const DecodedMessage(this.id, this.control);

  final int id;
  final Control control;
}

/// A header this version can read with a kind or body it cannot. It is still acknowledged, or its
/// sender would resend it until it gave up on the link.
final class DecodedUnrecognized extends Decoded {
  const DecodedUnrecognized(this.id);

  final int id;
}

/// Input from the other device is parsed defensively: nothing throws.
abstract final class ControlCodec {
  static const _ack = 0;
  static const _ping = 1;
  static const _pairConfirm = 2;
  static const _pairReject = 3;
  static const _bye = 4;
  static const _feedList = 5;
  static const _subscribe = 6;
  static const _unsubscribe = 7;
  static const _keyframeRequest = 8;
  static const _timeRequest = 9;
  static const _timeReply = 10;
  static const _senderStats = 11;
  static const _nack = 12;
  static const _streamReport = 13;
  static const _subscribeRefused = 14;

  /// Pings and measurements are never acknowledged or resent: a late measurement is worth nothing.
  static bool needsAck(Control control) =>
      control is! Ping && control is! TimeRequest && control is! TimeReply && control is! SenderStats && control is! Nack && control is! StreamReport;

  static Uint8List encodeAck(int id) {
    final out = Uint8List(5);
    ByteData.sublistView(out).setInt32(1, id, Endian.big);
    return out;
  }

  static Uint8List encode(int id, Control control) {
    final (kind, body) = switch (control) {
      Ping() => (_ping, Uint8List(0)),
      PairConfirm() => (_pairConfirm, Uint8List(0)),
      PairReject() => (_pairReject, Uint8List(0)),
      Bye() => (_bye, Uint8List(0)),
      FeedList() => (_feedList, _encodeFeeds(control.feeds)),
      Subscribe() => (_subscribe, _encodeSubscribe(control)),
      SubscribeRefused() => (_subscribeRefused, Uint8List.fromList([...(_short(control.streamId)), control.reason.code])),
      Unsubscribe() => (_unsubscribe, _short(control.streamId)),
      KeyframeRequest() => (_keyframeRequest, _short(control.streamId)),
      TimeRequest() => (_timeRequest, _long(control.sentAt)),
      TimeReply() => (_timeReply, _long(control.sentAt)),
      SenderStats() => (_senderStats, _encodeSenderStats(control)),
      Nack() => (_nack, _encodeNack(control)),
      StreamReport() => (_streamReport, _encodeReport(control)),
    };
    final out = Uint8List(5 + body.length);
    out[0] = kind;
    ByteData.sublistView(out).setInt32(1, id, Endian.big);
    out.setRange(5, out.length, body);
    return out;
  }

  static Decoded? decode(Uint8List bytes) {
    if (bytes.length < 5) return null;
    final view = ByteData.sublistView(bytes);
    final kind = bytes[0];
    final id = view.getInt32(1, Endian.big);
    final reader = _Reader(bytes, 5);
    try {
      final decoded = switch (kind) {
        _ack => DecodedAck(id),
        _ping => DecodedMessage(id, const Ping()),
        _pairConfirm => DecodedMessage(id, const PairConfirm()),
        _pairReject => DecodedMessage(id, const PairReject()),
        _bye => DecodedMessage(id, const Bye()),
        _feedList => DecodedMessage(id, FeedList(_readFeeds(reader))),
        _subscribe => _readSubscribe(reader)?.let((request) => DecodedMessage(id, request)),
        _subscribeRefused => DecodedMessage(id, SubscribeRefused(reader.u16(), Refusal.of(reader.u8()))),
        _unsubscribe => DecodedMessage(id, Unsubscribe(reader.u16())),
        _keyframeRequest => DecodedMessage(id, KeyframeRequest(reader.u16())),
        _timeRequest => DecodedMessage(id, TimeRequest(reader.i64())),
        _timeReply => DecodedMessage(id, TimeReply(reader.i64())),
        _nack => _readNack(reader)?.let((request) => DecodedMessage(id, request)),
        _streamReport => DecodedMessage(
            id,
            StreamReport(
              streamId: reader.u16(),
              windowMs: reader.u16(),
              fragments: reader.u16(),
              resendRequests: reader.u16(),
              lostFrames: reader.u16(),
              jitterMs10: reader.u16(),
            )),
        _senderStats => DecodedMessage(
            id,
            SenderStats(
              streamId: reader.u16(),
              bitrateKbps: reader.u16(),
              fps10: reader.u16(),
              encodeMs10: reader.u16(),
              sendMs10: reader.u16(),
            )),
        _ => null,
      };
      return decoded ?? DecodedUnrecognized(id);
    } on RangeError {
      return DecodedUnrecognized(id);
    }
  }

  static Uint8List _short(int value) {
    final out = Uint8List(2);
    ByteData.sublistView(out).setUint16(0, value, Endian.big);
    return out;
  }

  static Uint8List _long(int value) {
    final out = Uint8List(8);
    ByteData.sublistView(out).setInt64(0, value, Endian.big);
    return out;
  }

  /// A request for more pieces than fit is a request for the whole frame.
  static Uint8List _encodeNack(Nack request) {
    final indexes = request.indexes.length > Nack.maxIndexes ? const <int>[] : request.indexes;
    final out = Uint8List(8 + 2 * indexes.length);
    final view = ByteData.sublistView(out);
    view.setUint16(0, request.streamId, Endian.big);
    view.setInt32(2, request.frameId, Endian.big);
    view.setUint16(6, indexes.length, Endian.big);
    for (var i = 0; i < indexes.length; i++) {
      view.setUint16(8 + 2 * i, indexes[i], Endian.big);
    }
    return out;
  }

  static Nack? _readNack(_Reader reader) {
    final streamId = reader.u16();
    final frameId = reader.i32();
    final count = reader.u16();
    if (count > Nack.maxIndexes) return null;
    return Nack(streamId: streamId, frameId: frameId, indexes: [for (var i = 0; i < count; i++) reader.u16()]);
  }

  static Uint8List _encodeReport(StreamReport report) {
    final out = Uint8List(12);
    final view = ByteData.sublistView(out);
    view.setUint16(0, report.streamId, Endian.big);
    view.setUint16(2, report.windowMs.clamp(0, 0xFFFF), Endian.big);
    view.setUint16(4, report.fragments.clamp(0, 0xFFFF), Endian.big);
    view.setUint16(6, report.resendRequests.clamp(0, 0xFFFF), Endian.big);
    view.setUint16(8, report.lostFrames.clamp(0, 0xFFFF), Endian.big);
    view.setUint16(10, report.jitterMs10.clamp(0, 0xFFFF), Endian.big);
    return out;
  }

  static Uint8List _encodeSenderStats(SenderStats stats) {
    final out = Uint8List(10);
    final view = ByteData.sublistView(out);
    view.setUint16(0, stats.streamId, Endian.big);
    view.setUint16(2, stats.bitrateKbps.clamp(0, 0xFFFF), Endian.big);
    view.setUint16(4, stats.fps10.clamp(0, 0xFFFF), Endian.big);
    view.setUint16(6, stats.encodeMs10.clamp(0, 0xFFFF), Endian.big);
    view.setUint16(8, stats.sendMs10.clamp(0, 0xFFFF), Endian.big);
    return out;
  }

  static Uint8List _encodeSubscribe(Subscribe request) {
    final name = utf8.encode(request.feed);
    if (name.length > 255) throw ArgumentError('feed name too long');
    final fpsBytes = request.fps == Subscribe.defaultFps ? 0 : 1;
    final out = Uint8List(10 + name.length + fpsBytes);
    final view = ByteData.sublistView(out);
    view.setUint16(0, request.streamId, Endian.big);
    view.setUint16(2, request.width, Endian.big);
    view.setUint16(4, request.height, Endian.big);
    view.setUint16(6, request.bitrateKbps, Endian.big);
    out[8] = name.length;
    out.setRange(9, 9 + name.length, name);
    out[9 + name.length] = request.audio ? 1 : 0;
    if (fpsBytes == 1) out[10 + name.length] = request.fps.clamp(1, Subscribe.maxFps);
    return out;
  }

  /// A request with a name or a size the hub would not accept from a local app is not a request.
  static Subscribe? _readSubscribe(_Reader reader) {
    final streamId = reader.u16();
    final width = reader.u16();
    final height = reader.u16();
    final bitrate = reader.u16();
    final name = reader.string(reader.u8());
    if (!isValidName(name)) return null;
    if (width < 1 || width > maxDimension || height < 1 || height > maxDimension || bitrate < 1) return null;
    // Older senders of this message stop after the name, or after the audio byte.
    final audio = reader.hasMore && reader.u8() != 0;
    final fps = reader.hasMore ? reader.u8().clamp(1, Subscribe.maxFps) : Subscribe.defaultFps;
    return Subscribe(streamId, name.trim(), width, height, bitrate, audio: audio, fps: fps);
  }

  static Uint8List _encodeFeeds(List<FeedInfo> feeds) {
    final encoded = <Uint8List>[];
    var used = 2;
    for (final feed in feeds.take(maxFeeds)) {
      final name = utf8.encode(feed.name);
      if (name.length > 255) continue;
      final entry = Uint8List(1 + name.length + 6);
      final view = ByteData.sublistView(entry);
      entry[0] = name.length;
      entry.setRange(1, 1 + name.length, name);
      view.setUint16(1 + name.length, feed.width, Endian.big);
      view.setUint16(3 + name.length, feed.height, Endian.big);
      entry[5 + name.length] = feed.fps;
      entry[6 + name.length] = feed.hasAudio ? 1 : 0;
      if (used + entry.length > Session.maxPayload - 5) break;
      encoded.add(entry);
      used += entry.length;
    }
    final out = BytesBuilder(copy: false)..add(_short(encoded.length));
    for (final entry in encoded) {
      out.add(entry);
    }
    return out.toBytes();
  }

  static List<FeedInfo> _readFeeds(_Reader reader) {
    final count = reader.u16();
    final feeds = <FeedInfo>[];
    for (var i = 0; i < (count < maxFeeds ? count : maxFeeds); i++) {
      final name = reader.string(reader.u8());
      final width = reader.u16();
      final height = reader.u16();
      final fps = reader.u8();
      final audio = reader.u8() != 0;
      if (isValidName(name) && width <= maxDimension && height <= maxDimension) {
        feeds.add(FeedInfo(name.trim(), width, height, fps, audio));
      }
    }
    return feeds;
  }
}

extension _Let<T> on T {
  R let<R>(R Function(T value) block) => block(this);
}

class _Reader {
  _Reader(this._bytes, this._offset) : _view = ByteData.sublistView(_bytes);

  final Uint8List _bytes;
  final ByteData _view;
  int _offset;

  bool get hasMore => _offset < _bytes.length;

  int u8() => _bytes[_offset++];

  int u16() {
    final value = _view.getUint16(_offset, Endian.big);
    _offset += 2;
    return value;
  }

  int i32() {
    final value = _view.getInt32(_offset, Endian.big);
    _offset += 4;
    return value;
  }

  int i64() {
    final value = _view.getInt64(_offset, Endian.big);
    _offset += 8;
    return value;
  }

  String string(int length) {
    if (_offset + length > _bytes.length) throw RangeError('short');
    final text = utf8.decode(_bytes.sublist(_offset, _offset + length), allowMalformed: true);
    _offset += length;
    return text;
  }
}
