import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/network/protocol/crypto.dart';
import 'package:manifold_hub/network/protocol/noise.dart';

Uint8List _hex(String text) => Uint8List.fromList([for (var i = 0; i < text.length; i += 2) int.parse(text.substring(i, i + 2), radix: 16)]);

class _Vector {
  _Vector(this.name, this.fields, this.messages);

  final String name;
  final Map<String, String> fields;
  final List<(Uint8List, Uint8List)> messages;

  Uint8List bytes(String key) => _hex(fields[key]!);
}

List<_Vector> _vectors() {
  final text = File('test/fixtures/noise_vectors.txt').readAsStringSync().replaceAll('\r\n', '\n');
  return [
    for (final block in text.split('\n\n').where((block) => block.trim().isNotEmpty))
      () {
        final lines = block.split('\n').where((line) => line.trim().isNotEmpty).toList();
        final fields = <String, String>{};
        final messages = <(Uint8List, Uint8List)>[];
        for (final line in lines.skip(1)) {
          final split = line.indexOf('=');
          final key = line.substring(0, split);
          final value = line.substring(split + 1);
          if (key == 'message') {
            final parts = value.split(',');
            messages.add((_hex(parts[0]), _hex(parts[1])));
          } else {
            fields[key] = value;
          }
        }
        return _Vector(lines.first.replaceAll(RegExp(r'[\[\]]'), '').trim(), fields, messages);
      }(),
  ];
}

void _run(_Vector vector, Pattern pattern) {
  final initStatic = Crypto.keyPairFrom(vector.bytes('init_static'));
  final respStatic = Crypto.keyPairFrom(vector.bytes('resp_static'));
  final prologue = vector.bytes('init_prologue');
  final initEphemeral = Crypto.keyPairFrom(vector.bytes('init_ephemeral'));
  final respEphemeral = Crypto.keyPairFrom(vector.bytes('resp_ephemeral'));

  final initiator = NoiseHandshake(pattern, true, initStatic,
      remoteStatic: pattern.initiatorKnowsResponder ? respStatic.public : null, prologue: prologue, newEphemeral: () => initEphemeral);
  final responder = NoiseHandshake(pattern, false, respStatic, prologue: prologue, newEphemeral: () => respEphemeral);

  final handshakeMessages = pattern.messages.length;
  for (var index = 0; index < handshakeMessages; index++) {
    final (payload, expected) = vector.messages[index];
    final writer = index.isEven ? initiator : responder;
    final reader = index.isEven ? responder : initiator;
    final sent = writer.writeMessage(payload);
    expect(sent, expected, reason: '${vector.name} handshake message $index');
    expect(reader.readMessage(sent), payload, reason: '${vector.name} payload $index');
  }

  expect(initiator.complete && responder.complete, isTrue);
  expect(initiator.handshakeHash, vector.bytes('handshake_hash'));
  expect(responder.handshakeHash, vector.bytes('handshake_hash'));
  expect(initiator.remoteStaticKey, respStatic.public);
  expect(responder.remoteStaticKey, initStatic.public);

  final fromInitiator = initiator.split();
  final fromResponder = responder.split();
  expect(fromInitiator.send, fromResponder.receive);
  expect(fromInitiator.receive, fromResponder.send);

  final counters = [0, 0];
  for (var offset = 0; offset < vector.messages.length - handshakeMessages; offset++) {
    final index = handshakeMessages + offset;
    final (payload, expected) = vector.messages[index];
    final side = index % 2;
    final sendKey = side == 0 ? fromInitiator.send : fromResponder.send;
    final sealed = Crypto.seal(sendKey, counters[side], Uint8List(0), payload);
    expect(sealed, expected, reason: '${vector.name} transport message $index');
    final receiveKey = side == 0 ? fromResponder.receive : fromInitiator.receive;
    expect(Crypto.open(receiveKey, counters[side], Uint8List(0), sealed), payload);
    counters[side]++;
  }
}

void main() {
  test('XX matches the official Noise vector', () {
    _run(_vectors().firstWhere((v) => v.name == Pattern.xx.protocolName), Pattern.xx);
  });

  test('IK matches the official Noise vector', () {
    _run(_vectors().firstWhere((v) => v.name == Pattern.ik.protocolName), Pattern.ik);
  });
}
