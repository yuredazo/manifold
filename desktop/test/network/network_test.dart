import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/network/network.dart';
import 'package:manifold_hub/network/network_feature.dart';
import 'package:manifold_hub/network/protocol/crypto.dart';
import 'package:manifold_hub/network/protocol/devices.dart';

final class _Recorder with NetworkFeature {
  final List<String> unpaired = [];
  @override
  void onUnpaired(String publicKey) => unpaired.add(publicKey);
}

void main() {
  late DeviceBook book;
  late _Recorder first;
  late _Recorder second;
  late Network network;

  setUp(() {
    book = DeviceBook(null, (_) {});
    first = _Recorder();
    second = _Recorder();
    network = Network(Identity(Crypto.generateKeyPair(), 'test pc'), book, features: [first, second]);
  });

  tearDown(() => network.dispose());

  test('every feature hears when a device is unpaired', () {
    network.unpair('abc');

    expect(first.unpaired, ['abc']);
    expect(second.unpaired, ['abc']);
  });

  test('stopping a network that never started does nothing', () async {
    await network.stop();

    expect(network.listening, isFalse);
  });
}
