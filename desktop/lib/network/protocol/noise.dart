import 'dart:convert';
import 'dart:typed_data';

import 'crypto.dart';

enum Token { e, s, ee, es, se, ss }

enum Pattern {
  xx(
    'Noise_XX_25519_ChaChaPoly_SHA256',
    false,
    [
      [Token.e],
      [Token.e, Token.ee, Token.s, Token.es],
      [Token.s, Token.se],
    ],
  ),
  ik(
    'Noise_IK_25519_ChaChaPoly_SHA256',
    true,
    [
      [Token.e, Token.es, Token.s, Token.ss],
      [Token.e, Token.ee, Token.se],
    ],
  );

  const Pattern(this.protocolName, this.initiatorKnowsResponder, this.messages);

  final String protocolName;
  final bool initiatorKnowsResponder;
  final List<List<Token>> messages;
}

final class TransportKeys {
  const TransportKeys({required this.send, required this.receive});

  final Uint8List send;
  final Uint8List receive;
}

final class _SymmetricState {
  _SymmetricState(Uint8List protocolName) {
    hash = protocolName.length <= Crypto.hashLength
        ? (Uint8List(Crypto.hashLength)..setRange(0, protocolName.length, protocolName))
        : Crypto.sha256([protocolName]);
    chainingKey = hash;
  }

  late Uint8List chainingKey;
  late Uint8List hash;
  Uint8List? _key;
  int _nonce = 0;

  bool get hasKey => _key != null;

  void mixHash(Uint8List data) {
    hash = Crypto.sha256([hash, data]);
  }

  void mixKey(Uint8List input) {
    final (next, temp) = Crypto.hkdf(chainingKey, input);
    chainingKey = next;
    _key = temp;
    _nonce = 0;
  }

  Uint8List encryptAndHash(Uint8List plaintext) {
    final key = _key;
    final out = key == null ? plaintext : Crypto.seal(key, _nonce++, hash, plaintext);
    mixHash(out);
    return out;
  }

  Uint8List decryptAndHash(Uint8List ciphertext) {
    final key = _key;
    final Uint8List out;
    if (key == null) {
      out = ciphertext;
    } else {
      out = Crypto.open(key, _nonce, hash, ciphertext) ?? (throw const HandshakeException('handshake message failed authentication'));
      _nonce++;
    }
    mixHash(ciphertext);
    return out;
  }

  (Uint8List, Uint8List) split() => Crypto.hkdf(chainingKey, Uint8List(0));
}

/// Failures throw [HandshakeException]. Trusting [remoteStaticKey] is the caller's job.
final class NoiseHandshake {
  NoiseHandshake(
    this._pattern,
    this._initiator,
    this._staticKey, {
    Uint8List? remoteStatic,
    Uint8List? prologue,
    KeyPair Function()? newEphemeral,
  })  : remoteStaticKey = remoteStatic,
        _newEphemeral = newEphemeral ?? Crypto.generateKeyPair,
        _symmetric = _SymmetricState(Uint8List.fromList(ascii.encode(_pattern.protocolName))) {
    _symmetric.mixHash(prologue ?? Uint8List(0));
    if (_pattern.initiatorKnowsResponder) {
      final responderStatic = _initiator
          ? (remoteStatic ?? (throw ArgumentError('${_pattern.name} needs the responder\'s static key')))
          : _staticKey.public;
      _symmetric.mixHash(responderStatic);
    }
  }

  final Pattern _pattern;
  final bool _initiator;
  final KeyPair _staticKey;
  final KeyPair Function() _newEphemeral;
  final _SymmetricState _symmetric;
  KeyPair? _ephemeral;
  Uint8List? _remoteEphemeral;
  int _step = 0;

  Uint8List? remoteStaticKey;

  bool get complete => _step == _pattern.messages.length;

  Uint8List get handshakeHash => _symmetric.hash;

  bool get _myTurn => (_step % 2 == 0) == _initiator;

  Uint8List writeMessage([Uint8List? payload]) {
    if (complete) throw StateError('the handshake is finished');
    if (!_myTurn) throw StateError('it is the other side\'s turn');
    final out = BytesBuilder(copy: false);
    for (final token in _pattern.messages[_step]) {
      switch (token) {
        case Token.e:
          final fresh = _newEphemeral();
          _ephemeral = fresh;
          out.add(fresh.public);
          _symmetric.mixHash(fresh.public);
        case Token.s:
          out.add(_symmetric.encryptAndHash(_staticKey.public));
        default:
          _mixSecret(token);
      }
    }
    out.add(_symmetric.encryptAndHash(payload ?? Uint8List(0)));
    _step++;
    return out.toBytes();
  }

  Uint8List readMessage(Uint8List message) {
    if (complete) throw StateError('the handshake is finished');
    if (_myTurn) throw StateError('it is this side\'s turn to write');
    var offset = 0;
    Uint8List take(int count) {
      if (message.length - offset < count) throw const HandshakeException('handshake message too short');
      final part = message.sublist(offset, offset + count);
      offset += count;
      return part;
    }

    for (final token in _pattern.messages[_step]) {
      switch (token) {
        case Token.e:
          final theirs = take(Crypto.keyLength);
          _remoteEphemeral = theirs;
          _symmetric.mixHash(theirs);
        case Token.s:
          final length = Crypto.keyLength + (_symmetric.hasKey ? Crypto.tagLength : 0);
          remoteStaticKey = _symmetric.decryptAndHash(take(length));
        default:
          _mixSecret(token);
      }
    }
    final payload = _symmetric.decryptAndHash(message.sublist(offset));
    _step++;
    return payload;
  }

  TransportKeys split() {
    if (!complete) throw StateError('the handshake is not finished');
    final (first, second) = _symmetric.split();
    return _initiator ? TransportKeys(send: first, receive: second) : TransportKeys(send: second, receive: first);
  }

  void _mixSecret(Token token) {
    final secret = switch (token) {
      Token.ee => _dh(_ephemeral, _remoteEphemeral),
      Token.es => _initiator ? _dh(_ephemeral, remoteStaticKey) : _dh(_staticKey, _remoteEphemeral),
      Token.se => _initiator ? _dh(_staticKey, _remoteEphemeral) : _dh(_ephemeral, remoteStaticKey),
      Token.ss => _dh(_staticKey, remoteStaticKey),
      _ => throw StateError('not a key agreement token'),
    };
    _symmetric.mixKey(secret);
  }

  Uint8List _dh(KeyPair? own, Uint8List? theirs) {
    if (own == null || theirs == null) throw const HandshakeException('handshake message came out of order');
    return Crypto.diffieHellman(own.private, theirs);
  }
}
