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

  /// Written to a temporary file first, so a crash cannot leave half a file behind.
  void write(String name, String text) {
    final target = '${_directory.path}${Platform.pathSeparator}$name';
    final temporary = File('$target.tmp')..writeAsStringSync(text, flush: true);
    temporary.renameSync(target);
  }
}
