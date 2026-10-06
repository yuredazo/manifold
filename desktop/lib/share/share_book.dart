import 'dart:convert';

import 'share_models.dart';

const _maxSavedShares = 32;
const _maxSavedText = 512;

String encodeShares(Iterable<SharedWindow> shares) => jsonEncode([
      for (final window in shares)
        {
          'name': window.feedName,
          'title': window.title,
          'process': window.process,
          'class': window.windowClass,
          'audio': window.withAudio,
          'display': window.display,
          if (window.camera) ...{'camera': true, 'device': window.device},
          if (window.spout) 'spout': true,
          if (window.soundOnly) 'soundOnly': true,
          if (!window.showCursor) 'cursor': false,
        },
    ]);

/// The file sits where other programs of the same user can write to it, so anything odd in it is skipped rather than trusted.
List<SharedWindow> decodeShares(String? text) {
  if (text == null) return const [];
  final Object? decoded;
  try {
    decoded = jsonDecode(text);
  } on FormatException {
    return const [];
  }
  if (decoded is! List) return const [];
  final shares = <SharedWindow>[];
  for (final entry in decoded.whereType<Map>().take(_maxSavedShares)) {
    final name = entry['name'];
    final title = entry['title'];
    final process = entry['process'];
    final windowClass = entry['class'];
    if (name is! String || title is! String || process is! String || windowClass is! String) continue;
    if ([name, title, process, windowClass].any((value) => value.length > _maxSavedText) || name.isEmpty) continue;
    if (shares.any((share) => share.feedName == name)) continue;
    final device = entry['device'];
    final camera = entry['camera'] == true;
    final spout = !camera && entry['spout'] == true;
    final soundOnly = entry['soundOnly'] == true && !camera && !spout;
    if (device is String && device.length > _maxSavedText) continue;
    shares.add(SharedWindow(
      feedName: name,
      title: title,
      process: process,
      windowClass: windowClass,
      withAudio: (entry['audio'] == true || soundOnly) && !camera && !spout,
      kind: camera
          ? ShareKind.camera
          : spout
              ? ShareKind.spout
              : entry['display'] == true
                  ? ShareKind.display
                  : ShareKind.window,
      soundOnly: soundOnly,
      showCursor: entry['cursor'] != false,
      device: device is String ? device : '',
    ));
  }
  return shares;
}
