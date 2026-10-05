import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/ts_muxer.dart';

class _Parsed {
  _Parsed(Uint8List stream) {
    expect(stream.length % TsMuxer.packetSize, 0, reason: 'whole packets only');
    for (var offset = 0; offset < stream.length; offset += TsMuxer.packetSize) {
      final packet = stream.sublist(offset, offset + TsMuxer.packetSize);
      expect(packet[0], 0x47, reason: 'sync byte at $offset');
      final pid = ((packet[1] & 0x1F) << 8) | packet[2];
      final control = (packet[3] >> 4) & 3;
      final counter = packet[3] & 0x0F;
      var start = 4;
      if (control & 2 != 0) start += 1 + packet[4];
      packets.add((pid: pid, unitStart: packet[1] & 0x40 != 0, counter: counter, payload: control & 1 != 0 ? packet.sublist(start) : Uint8List(0), packet: packet));
      counters.putIfAbsent(pid, () => []).add(counter);
    }
  }

  final packets = <({int pid, bool unitStart, int counter, Uint8List payload, Uint8List packet})>[];
  final counters = <int, List<int>>{};

  Uint8List payloadOf(int pid) {
    final out = BytesBuilder();
    for (final packet in packets.where((p) => p.pid == pid)) {
      out.add(packet.payload);
    }
    return out.toBytes();
  }
}

int _pts(Uint8List pes) {
  final b = pes.sublist(9, 14);
  return ((b[0] >> 1) & 0x07) * (1 << 30) + (b[1] << 22) + ((b[2] >> 1) << 15) + (b[3] << 7) + (b[4] >> 1);
}

void main() {
  test('the tables carry a valid checksum and name both streams', () {
    final parsed = _Parsed(TsMuxer().tables(withAudio: true));

    expect(parsed.packets.map((p) => p.pid), [0, TsMuxer.pmtPid]);
    for (final packet in parsed.packets) {
      final payload = packet.payload;
      final section = payload.sublist(1, 1 + 3 + (((payload[2] & 0x0F) << 8) | payload[3]));
      expect(TsMuxer.crc32(section), 0, reason: 'a table including its checksum has checksum 0');
    }
    final map = parsed.packets[1].payload;
    expect(map.contains(0x1B) && map.contains(0x0F), isTrue);
  });

  test('the table gets shorter without audio', () {
    int lengthOf(bool audio) {
      final map = _Parsed(TsMuxer().tables(withAudio: audio)).packets[1].payload;
      return ((map[2] & 0x0F) << 8) | map[3];
    }

    expect(lengthOf(true) - lengthOf(false), 5);
  });

  test('a frame comes back out of the packets unchanged', () {
    final muxer = TsMuxer();
    final frame = Uint8List.fromList([0, 0, 0, 1, 0x65, ...List.generate(1000, (i) => (i * 13) & 0xFF)]);

    final parsed = _Parsed(muxer.videoFrame(frame, 123456, keyframe: true));
    final pes = parsed.payloadOf(TsMuxer.videoPid);

    expect(pes.sublist(0, 4), [0, 0, 1, 0xE0]);
    expect(_pts(pes), 123456);
    expect(pes.sublist(14), [0, 0, 0, 1, 0x09, 0xF0, ...frame]);
    expect(parsed.packets.first.unitStart, isTrue);
    expect(parsed.packets.skip(1).any((p) => p.unitStart), isFalse);
  });

  test('frames of every size fill whole packets exactly', () {
    final muxer = TsMuxer();
    for (var size = 0; size < 450; size++) {
      final frame = Uint8List.fromList(List.generate(size, (i) => (i + size) & 0xFF));
      final parsed = _Parsed(muxer.videoFrame(frame, size * 3000, keyframe: size.isEven));
      expect(parsed.payloadOf(TsMuxer.videoPid).sublist(14), [0, 0, 0, 1, 0x09, 0xF0, ...frame], reason: 'size $size');
    }
  });

  test('a large frame spreads over many packets', () {
    final frame = Uint8List.fromList(List.generate(150000, (i) => (i * 7) & 0xFF));
    final parsed = _Parsed(TsMuxer().videoFrame(frame, 90000, keyframe: false));

    expect(parsed.payloadOf(TsMuxer.videoPid).sublist(14), [0, 0, 0, 1, 0x09, 0xF0, ...frame]);
    expect(parsed.packets.length, greaterThan(800));
  });

  test('the continuity counter counts up per stream and wraps', () {
    final muxer = TsMuxer();
    final out = BytesBuilder();
    for (var i = 0; i < 12; i++) {
      out.add(muxer.videoFrame(Uint8List(2000), i * 3000, keyframe: false));
      out.add(muxer.audioFrame(Uint8List(300), i * 1920));
    }
    final parsed = _Parsed(out.toBytes());

    for (final pid in [TsMuxer.videoPid, TsMuxer.audioPid]) {
      final counters = parsed.counters[pid]!;
      for (var i = 1; i < counters.length; i++) {
        expect(counters[i], (counters[i - 1] + 1) & 0x0F, reason: 'pid $pid packet $i');
      }
    }
  });

  test('audio frames are carried with their own length and timestamp', () {
    final adts = Uint8List.fromList([0xFF, 0xF1, 0x4C, 0x80, 0x01, 0x3F, 0xFC, ...List.generate(100, (i) => i)]);

    final pes = _Parsed(TsMuxer().audioFrame(adts, 777)).payloadOf(TsMuxer.audioPid);

    expect(pes.sublist(0, 4), [0, 0, 1, 0xC0]);
    expect((pes[4] << 8) | pes[5], 3 + 5 + adts.length);
    expect(_pts(pes), 777);
    expect(pes.sublist(14), adts);
  });

  test('timestamps past 33 bits wrap instead of breaking the header', () {
    final pes = _Parsed(TsMuxer().audioFrame(Uint8List(10), (1 << 33) + 5)).payloadOf(TsMuxer.audioPid);
    expect(_pts(pes), 5);
  });

  test('a keyframe carries the clock and a random access flag', () {
    final first = _Parsed(TsMuxer().videoFrame(Uint8List(500), 90000, keyframe: true)).packets.first.packet;

    expect(first[3] & 0x20, isNot(0), reason: 'has an adaptation field');
    expect(first[5] & 0x40, isNot(0), reason: 'random access');
    expect(first[5] & 0x10, isNot(0), reason: 'carries the clock');
  });
}
