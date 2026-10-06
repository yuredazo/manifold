import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:cryptography/dart.dart';

final class KeyPair {
  const KeyPair(this.private, this.public);

  final Uint8List private;
  final Uint8List public;
}

final class HandshakeException implements Exception {
  const HandshakeException(this.message);

  final String message;

  @override
  String toString() => 'HandshakeException: $message';
}

/// Same suite and byte layout as the Android hub.
abstract final class Crypto {
  static const keyLength = 32;
  static const tagLength = 16;
  static const hashLength = 32;

  static const _x25519 = DartX25519();
  static const _aead = DartChacha20.poly1305Aead();
  static const _sha256 = DartSha256();
  static final _random = Random.secure();

  static final _basePoint = SimplePublicKey(Uint8List(keyLength)..[0] = 9, type: KeyPairType.x25519);

  static KeyPair generateKeyPair() {
    final seed = Uint8List(keyLength);
    for (var i = 0; i < keyLength; i++) {
      seed[i] = _random.nextInt(256);
    }
    return keyPairFrom(seed);
  }

  static KeyPair keyPairFrom(Uint8List private) {
    final shared = _agree(private, _basePoint);
    return KeyPair(Uint8List.fromList(private), shared);
  }

  /// Fails if the other side sent a weak point, which gives an all-zero secret.
  static Uint8List diffieHellman(Uint8List private, Uint8List public) {
    final secret = _agree(private, SimplePublicKey(public, type: KeyPairType.x25519));
    if (secret.every((byte) => byte == 0)) throw const HandshakeException('key agreement failed');
    return secret;
  }

  static Uint8List _agree(Uint8List private, SimplePublicKey public) {
    final key = SimpleKeyPairData(
      private,
      publicKey: SimplePublicKey(Uint8List(keyLength), type: KeyPairType.x25519),
      type: KeyPairType.x25519,
    );
    final secret = _x25519.sharedSecretSync(keyPairData: key, remotePublicKey: public) as SecretKeyData;
    return Uint8List.fromList(secret.bytes);
  }

  static Uint8List sha256(List<Uint8List> parts) {
    final all = BytesBuilder(copy: false);
    for (final part in parts) {
      all.add(part);
    }
    return Uint8List.fromList(_sha256.hashSync(all.toBytes()).bytes);
  }

  static Uint8List hmac(Uint8List key, List<Uint8List> parts) {
    const block = 64;
    final shortKey = key.length > block ? sha256([key]) : key;
    final inner = Uint8List(block);
    final outer = Uint8List(block);
    for (var i = 0; i < block; i++) {
      final byte = i < shortKey.length ? shortKey[i] : 0;
      inner[i] = byte ^ 0x36;
      outer[i] = byte ^ 0x5c;
    }
    return sha256([outer, sha256([inner, ...parts])]);
  }

  static (Uint8List, Uint8List) hkdf(Uint8List chainingKey, Uint8List input) {
    final temp = hmac(chainingKey, [input]);
    final first = hmac(temp, [Uint8List.fromList([1])]);
    final second = hmac(temp, [first, Uint8List.fromList([2])]);
    return (first, second);
  }

  static Uint8List seal(Uint8List key, int counter, Uint8List associatedData, Uint8List plaintext) {
    final box = _aead.encryptSync(
      plaintext,
      secretKey: SecretKeyData(key),
      nonce: _nonce(counter),
      aad: associatedData,
    );
    return Uint8List.fromList([...box.cipherText, ...box.mac.bytes]);
  }

  static Uint8List? open(Uint8List key, int counter, Uint8List associatedData, Uint8List ciphertext) {
    if (ciphertext.length < tagLength) return null;
    final body = ciphertext.sublist(0, ciphertext.length - tagLength);
    final tag = ciphertext.sublist(ciphertext.length - tagLength);
    try {
      final plain = _aead.decryptSync(
        SecretBox(body, nonce: _nonce(counter), mac: Mac(tag)),
        secretKey: SecretKeyData(key),
        aad: associatedData,
      );
      return Uint8List.fromList(plain);
    } on SecretBoxAuthenticationError {
      return null;
    }
  }

  static Uint8List _nonce(int counter) {
    final nonce = Uint8List(12);
    ByteData.sublistView(nonce).setUint64(4, counter, Endian.little);
    return nonce;
  }
}
