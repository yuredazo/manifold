import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/core/dpapi.dart';
import 'package:manifold_hub/core/storage.dart';
import 'package:manifold_hub/network/network_storage.dart';
import 'package:manifold_hub/network/protocol/crypto.dart';
import 'package:manifold_hub/network/protocol/devices.dart';

void main() {
  late Directory directory;
  late Storage storage;

  setUp(() {
    directory = Directory.systemTemp.createTempSync('manifold_storage');
    storage = Storage.at(directory);
  });

  tearDown(() => directory.deleteSync(recursive: true));

  test('protected bytes differ from the plain ones and come back the same', () {
    final plain = Crypto.generateKeyPair().private;

    final sealed = protect(plain);

    expect(sealed, isNot(plain));
    expect(unprotect(sealed), plain);
  });

  test('bytes that were not protected for this user are not opened', () {
    expect(unprotect(Crypto.generateKeyPair().private), isNull);
  });

  test('a new identity is stored protected and loaded again unchanged', () {
    final first = storage.loadIdentity();

    final text = storage.read('identity.key')!;
    expect(text, startsWith('dpapi:'));
    expect(text, isNot(contains(toHex(first.keys.private))));
    expect(storage.loadIdentity().keys.private, first.keys.private);
  });

  test('a key written as plain hex by an earlier version is kept and then protected', () {
    final old = Crypto.generateKeyPair();
    storage.write('identity.key', toHex(old.private));

    final loaded = storage.loadIdentity();

    expect(loaded.keys.private, old.private);
    expect(storage.read('identity.key'), startsWith('dpapi:'));
  });

  test('a protected key that cannot be opened is replaced by a new identity', () {
    storage.write('identity.key', 'dpapi:${toHex(Crypto.generateKeyPair().private)}');

    final loaded = storage.loadIdentity();

    expect(loaded.keys.private, hasLength(Crypto.keyLength));
    expect(storage.loadIdentity().keys.private, loaded.keys.private);
  });
}
