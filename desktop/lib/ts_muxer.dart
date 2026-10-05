import 'dart:typed_data';

/// Timestamps are 90 kHz ticks, as in the video packets.
final class TsMuxer {
  static const packetSize = 188;
  static const pmtPid = 0x1000;
  static const videoPid = 0x100;
  static const audioPid = 0x101;

  /// The decoder clock runs this far ahead of the presentation time, so a frame is never due before it
  /// arrives.
  static const _clockLead = 6300;
  static const _timestampMask = (1 << 33) - 1;

  final _counters = <int, int>{};

  /// Repeat them often, and before every keyframe.
  Uint8List tables({bool withAudio = false}) {
    final out = BytesBuilder(copy: false)
      ..add(_tablePacket(0, _programAssociation()))
      ..add(_tablePacket(pmtPid, _programMap(withAudio)));
    return out.toBytes();
  }

  Uint8List videoFrame(Uint8List annexB, int pts, {required bool keyframe}) {
    // A delimiter in front of each frame lets decoders find frame boundaries without waiting for the next
    // one.
    final unit = Uint8List.fromList([0, 0, 0, 1, 0x09, 0xF0, ...annexB]);
    return _packetize(videoPid, _pes(0xE0, unit, pts, boundedLength: false), pcr: (pts - _clockLead) & _timestampMask, randomAccess: keyframe);
  }

  Uint8List audioFrame(Uint8List adts, int pts) => _packetize(audioPid, _pes(0xC0, adts, pts, boundedLength: true));

  Uint8List _pes(int streamId, Uint8List payload, int pts, {required bool boundedLength}) {
    const headerData = 5;
    final length = boundedLength ? 3 + headerData + payload.length : 0;
    final out = BytesBuilder(copy: false)
      ..add([0, 0, 1, streamId, length >> 8, length & 0xFF, 0x80, 0x80, headerData])
      ..add(_timestamp(0x20, pts & _timestampMask))
      ..add(payload);
    return out.toBytes();
  }

  Uint8List _timestamp(int prefix, int value) => Uint8List.fromList([
        prefix | ((value >> 29) & 0x0E) | 1,
        (value >> 22) & 0xFF,
        ((value >> 14) & 0xFE) | 1,
        (value >> 7) & 0xFF,
        ((value << 1) & 0xFE) | 1,
      ]);

  Uint8List _packetize(int pid, Uint8List pes, {int? pcr, bool randomAccess = false}) {
    final out = BytesBuilder(copy: false);
    var offset = 0;
    var first = true;
    while (offset < pes.length) {
      final remaining = pes.length - offset;
      final minimum = first && pcr != null ? 8 : (first && randomAccess ? 2 : 0);
      final adaptationSize = remaining >= 184 - minimum ? minimum : 184 - remaining;
      final adaptation = _adaptation(adaptationSize, pcr: first ? pcr : null, randomAccess: first && randomAccess);
      final payloadSize = 184 - adaptation.length;

      final packet = Uint8List(packetSize);
      packet[0] = 0x47;
      packet[1] = (first ? 0x40 : 0) | ((pid >> 8) & 0x1F);
      packet[2] = pid & 0xFF;
      packet[3] = (adaptation.isEmpty ? 0x10 : 0x30) | _next(pid);
      packet.setRange(4, 4 + adaptation.length, adaptation);
      packet.setRange(4 + adaptation.length, packetSize, pes.sublist(offset, offset + payloadSize));
      out.add(packet);
      offset += payloadSize;
      first = false;
    }
    return out.toBytes();
  }

  Uint8List _adaptation(int size, {int? pcr, bool randomAccess = false}) {
    if (size == 0) return Uint8List(0);
    final field = Uint8List(size)..fillRange(0, size, 0xFF);
    field[0] = size - 1;
    if (size == 1) return field;
    field[1] = (randomAccess ? 0x40 : 0) | (pcr != null ? 0x10 : 0);
    if (pcr != null) {
      field[2] = (pcr >> 25) & 0xFF;
      field[3] = (pcr >> 17) & 0xFF;
      field[4] = (pcr >> 9) & 0xFF;
      field[5] = (pcr >> 1) & 0xFF;
      field[6] = ((pcr & 1) << 7) | 0x7E;
      field[7] = 0;
    }
    return field;
  }

  int _next(int pid) {
    final counter = _counters[pid] ?? 0;
    _counters[pid] = (counter + 1) & 0x0F;
    return counter;
  }

  Uint8List _tablePacket(int pid, Uint8List section) {
    final packet = Uint8List(packetSize)..fillRange(0, packetSize, 0xFF);
    packet[0] = 0x47;
    packet[1] = 0x40 | ((pid >> 8) & 0x1F);
    packet[2] = pid & 0xFF;
    packet[3] = 0x10 | _next(pid);
    packet[4] = 0;
    packet.setRange(5, 5 + section.length, section);
    return packet;
  }

  Uint8List _programAssociation() => _section(0x00, [
        0x00, 0x01, // transport stream id
        0xC1, 0x00, 0x00, // version 0, current, section 0 of 0
        0x00, 0x01, // program 1
        0xE0 | (pmtPid >> 8), pmtPid & 0xFF,
      ]);

  Uint8List _programMap(bool withAudio) => _section(0x02, [
        0x00, 0x01, // program 1
        0xC1, 0x00, 0x00,
        0xE0 | (videoPid >> 8), videoPid & 0xFF, // the clock comes with the video
        0xF0, 0x00, // no program descriptors
        0x1B, 0xE0 | (videoPid >> 8), videoPid & 0xFF, 0xF0, 0x00, // H.264
        if (withAudio) ...[0x0F, 0xE0 | (audioPid >> 8), audioPid & 0xFF, 0xF0, 0x00], // AAC with ADTS
      ]);

  Uint8List _section(int tableId, List<int> body) {
    final length = body.length + 4;
    final withoutCrc = [tableId, 0xB0 | ((length >> 8) & 0x0F), length & 0xFF, ...body];
    final crc = _crc32(withoutCrc);
    return Uint8List.fromList([...withoutCrc, crc >> 24, (crc >> 16) & 0xFF, (crc >> 8) & 0xFF, crc & 0xFF]);
  }

  /// The CRC-32 that MPEG tables use: polynomial 0x04C11DB7, no reflection, no final inversion.
  static int crc32(List<int> bytes) => _crc32(bytes);

  static int _crc32(List<int> bytes) {
    var crc = 0xFFFFFFFF;
    for (final byte in bytes) {
      crc ^= byte << 24;
      for (var bit = 0; bit < 8; bit++) {
        crc = (crc & 0x80000000) != 0 ? ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF : (crc << 1) & 0xFFFFFFFF;
      }
    }
    return crc;
  }
}
