import 'dart:io';

/// Files are readable by programs running as the same user, so anything secret has to be sealed by the caller.
final class Storage {
  Storage._(this._directory);

  Storage.at(Directory directory) : _directory = directory;

  final Directory _directory;

  static Storage open() {
    final base = Platform.environment['APPDATA'] ?? Directory.systemTemp.path;
    final directory = Directory('$base${Platform.pathSeparator}Manifold')..createSync(recursive: true);
    return Storage._(directory);
  }

  String? read(String name) {
    final file = File('${_directory.path}${Platform.pathSeparator}$name');
    return file.existsSync() ? file.readAsStringSync() : null;
  }

  void append(String name, String line, {int maxBytes = 100000}) {
    final file = File('${_directory.path}${Platform.pathSeparator}$name');
    file.writeAsStringSync('$line\n', mode: FileMode.append);
    if (file.lengthSync() <= maxBytes) return;
    final text = file.readAsStringSync();
    final cut = text.indexOf('\n', text.length ~/ 2);
    file.writeAsStringSync(cut < 0 ? '' : text.substring(cut + 1));
  }

  /// Written to a temporary file first, so a crash cannot leave half a file behind.
  void write(String name, String text) {
    final target = '${_directory.path}${Platform.pathSeparator}$name';
    final temporary = File('$target.tmp')..writeAsStringSync(text, flush: true);
    temporary.renameSync(target);
  }
}
