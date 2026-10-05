import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:archive/archive_io.dart';
import 'package:cryptography/dart.dart';
import 'package:flutter/foundation.dart';

const _repo = 'yuredazo/manifold';
const _exeName = 'manifold_hub.exe';
const _maxDownloadBytes = 300 * 1024 * 1024;
const _maxApiBytes = 2 * 1024 * 1024;
const _networkTimeout = Duration(seconds: 20);

final _versionPattern = RegExp(r'^v?(\d+)\.(\d+)\.(\d+)');
final _assetPattern = RegExp(r'^manifold-hub-windows-v[0-9A-Za-z.\-]+\.zip$');

final class Release {
  const Release({required this.version, required this.page, required this.assetName, required this.assetUrl, required this.sha256});

  final String version;
  final Uri page;
  final String assetName;
  final Uri assetUrl;
  final String sha256;
}

sealed class UpdateState {
  const UpdateState();
}

final class Idle extends UpdateState {
  const Idle();
}

final class Checking extends UpdateState {
  const Checking();
}

final class UpToDate extends UpdateState {
  const UpToDate();
}

final class Available extends UpdateState {
  const Available(this.release);

  final Release release;
}

final class Downloading extends UpdateState {
  const Downloading(this.release, this.fraction);

  final Release release;
  final double? fraction;
}

final class Installing extends UpdateState {
  const Installing(this.release);

  final Release release;
}

final class UpdateFailed extends UpdateState {
  const UpdateFailed(this.message, {this.installing = false});

  final String message;

  /// Tells a failed install from a failed check, since the page words them differently.
  final bool installing;
}

bool isNewer(String candidate, String current) {
  final a = _versionPattern.firstMatch(candidate.trim());
  final b = _versionPattern.firstMatch(current.trim());
  if (a == null || b == null) return false;
  for (var part = 1; part <= 3; part++) {
    final difference = int.parse(a.group(part)!) - int.parse(b.group(part)!);
    if (difference != 0) return difference > 0;
  }
  return false;
}

final class Updater extends ChangeNotifier {
  Updater({
    required this.currentVersion,
    required this.installDirectory,
    required this.workDirectory,
    this.apiBase = 'https://api.github.com',
    this.assetPrefix = 'https://github.com/$_repo/releases/download/',
    this.exitApp = _exit,
    this.launch = launchPowerShell,
  });

  final String currentVersion;
  final Directory installDirectory;
  final Directory workDirectory;
  final String apiBase;

  /// A download link outside this prefix is refused even when the release data lists it.
  final String assetPrefix;
  final void Function() exitApp;

  final Future<void> Function(List<String> arguments) launch;

  UpdateState _state = const Idle();
  DateTime? _checkedAt;

  UpdateState get state => _state;

  DateTime? get checkedAt => _checkedAt;

  bool get busy => _state is Checking || _state is Downloading || _state is Installing;

  static void _exit() => exit(0);

  /// Not detached: PowerShell started that way never runs its script, and a normal child outlives this process anyway.
  static Future<void> launchPowerShell(List<String> arguments) => Process.start('powershell.exe', arguments);

  void _set(UpdateState state) {
    _state = state;
    notifyListeners();
  }

  /// Clears what an earlier update left behind. A copy that failed is kept and reported, since the user
  /// would otherwise be left on the old version without knowing why.
  void cleanUp() {
    if (!workDirectory.existsSync()) return;
    final log = File('${workDirectory.path}${Platform.pathSeparator}update.log');
    if (log.existsSync() && log.readAsStringSync().contains('failed')) {
      _set(UpdateFailed('The last update could not be copied over the installed files. Details: ${log.path}', installing: true));
      return;
    }
    try {
      workDirectory.deleteSync(recursive: true);
    } on FileSystemException {
      // Left for the next start.
    }
  }

  Future<void> check() async {
    if (busy) return;
    _set(const Checking());
    final client = HttpClient()..connectionTimeout = _networkTimeout;
    try {
      final request = await client.getUrl(Uri.parse('$apiBase/repos/$_repo/releases/latest'));
      request.headers
        ..set('Accept', 'application/vnd.github+json')
        ..set('X-GitHub-Api-Version', '2022-11-28')
        ..set('User-Agent', 'manifold-hub/$currentVersion');
      final response = await request.close().timeout(_networkTimeout);
      if (response.statusCode == 404) {
        await response.drain<void>();
        _checkedAt = DateTime.now();
        _set(const UpToDate());
        return;
      }
      if (response.statusCode != 200) {
        final resetHeader = response.headers.value('x-ratelimit-reset');
        await response.drain<void>();
        final limited = response.statusCode == 403 || response.statusCode == 429;
        _set(UpdateFailed(limited ? _limitMessage(resetHeader) : 'GitHub answered ${response.statusCode}.'));
        return;
      }
      final body = await _readLimited(response, _maxApiBytes).timeout(_networkTimeout);
      _checkedAt = DateTime.now();
      _set(_interpret(jsonDecode(utf8.decode(body))));
    } on TimeoutException {
      _set(const UpdateFailed('GitHub did not answer in time.'));
    } on SocketException {
      _set(const UpdateFailed('Could not reach GitHub.'));
    } on HandshakeException {
      _set(const UpdateFailed('Could not make a secure connection to GitHub.'));
    } on FormatException {
      _set(const UpdateFailed('GitHub sent an answer this version does not understand.'));
    } finally {
      client.close(force: true);
    }
  }

  static String _limitMessage(String? resetHeader) {
    final seconds = int.tryParse(resetHeader ?? '');
    if (seconds == null) return 'GitHub is limiting requests from this network. Try again later.';
    final at = DateTime.fromMillisecondsSinceEpoch(seconds * 1000);
    return 'GitHub is limiting requests from this network. Try again after ${at.hour.toString().padLeft(2, '0')}:${at.minute.toString().padLeft(2, '0')}.';
  }

  UpdateState _interpret(Object? json) {
    if (json is! Map<String, dynamic>) return const UpdateFailed('GitHub sent an answer this version does not understand.');
    final tag = json['tag_name'];
    if (tag is! String || !isNewer(tag, currentVersion)) return const UpToDate();
    final version = tag.startsWith('v') ? tag.substring(1) : tag;
    final listed = '${json['html_url']}';
    final page = Uri.parse(listed.startsWith('https://github.com/$_repo/') ? listed : 'https://github.com/$_repo/releases');
    final assets = json['assets'];
    if (assets is List) {
      for (final asset in assets.whereType<Map<String, dynamic>>()) {
        final name = asset['name'];
        final url = Uri.tryParse('${asset['browser_download_url']}');
        final digest = asset['digest'];
        if (name is! String || !_assetPattern.hasMatch(name) || url == null) continue;
        if (!url.toString().startsWith(assetPrefix)) continue;
        if (digest is! String || !digest.startsWith('sha256:')) continue;
        return Available(Release(version: version, page: page, assetName: name, assetUrl: url, sha256: digest.substring(7).toLowerCase()));
      }
    }
    return UpdateFailed('Version $version is out, but it has no Windows download that can be checked yet.');
  }

  Future<void> install() async {
    final current = _state;
    if (current is! Available) return;
    final release = current.release;
    final client = HttpClient()..connectionTimeout = _networkTimeout;
    try {
      if (!_canWrite(installDirectory)) {
        _set(UpdateFailed('Manifold cannot write to ${installDirectory.path}. Download version ${release.version} from the release page instead.', installing: true));
        return;
      }
      if (workDirectory.existsSync()) workDirectory.deleteSync(recursive: true);
      workDirectory.createSync(recursive: true);
      final zip = File('${workDirectory.path}${Platform.pathSeparator}${release.assetName}');
      _set(Downloading(release, null));
      await _download(client, release, zip);

      _set(Installing(release));
      final staging = Directory('${workDirectory.path}${Platform.pathSeparator}staging');
      await extractFileToDisk(zip.path, staging.path);
      if (!File('${staging.path}${Platform.pathSeparator}$_exeName').existsSync()) {
        throw const _UpdateError('The download does not contain the Manifold app.');
      }
      await _startSwap(staging);
      exitApp();
    } on _UpdateError catch (error) {
      _set(UpdateFailed(error.message, installing: true));
    } on TimeoutException {
      _set(const UpdateFailed('The download stalled.', installing: true));
    } on SocketException {
      _set(const UpdateFailed('The download was interrupted.', installing: true));
    } on FileSystemException catch (error) {
      _set(UpdateFailed('Could not write the update: ${error.message}.', installing: true));
    } finally {
      client.close(force: true);
    }
  }

  Future<void> _download(HttpClient client, Release release, File target) async {
    final request = await client.getUrl(release.assetUrl);
    request.headers.set('User-Agent', 'manifold-hub/$currentVersion');
    final response = await request.close().timeout(_networkTimeout);
    if (response.statusCode != 200) {
      await response.drain<void>();
      throw _UpdateError('The download failed (${response.statusCode}).');
    }
    final total = response.contentLength > 0 ? response.contentLength : null;
    if (total != null && total > _maxDownloadBytes) throw const _UpdateError('The download is larger than expected.');

    final hash = const DartSha256().newHashSink();
    final sink = target.openWrite();
    var received = 0;
    var reportedAt = 0;
    try {
      await for (final chunk in response.timeout(_networkTimeout)) {
        received += chunk.length;
        if (received > _maxDownloadBytes) throw const _UpdateError('The download is larger than expected.');
        hash.add(chunk);
        sink.add(chunk);
        if (received - reportedAt > 256 * 1024) {
          reportedAt = received;
          _set(Downloading(release, total == null ? null : received / total));
        }
      }
    } finally {
      await sink.close();
    }
    hash.close();
    final actual = _hex((await hash.hash()).bytes);
    if (actual != release.sha256) {
      target.deleteSync();
      throw const _UpdateError('The download does not match the checksum GitHub lists for it, so it was thrown away.');
    }
  }

  Future<void> _startSwap(Directory staging) async {
    final script = File('${workDirectory.path}${Platform.pathSeparator}swap.ps1')..writeAsStringSync(_swapScript, flush: true);
    final log = '${workDirectory.path}${Platform.pathSeparator}update.log';
    await launch([
      '-NoProfile',
      '-NonInteractive',
      '-ExecutionPolicy',
      'Bypass',
      '-WindowStyle',
      'Hidden',
      '-File',
      script.path,
      '-ProcessId',
      '$pid',
      '-Staging',
      staging.path,
      '-AppDir',
      installDirectory.path,
      '-Log',
      log,
    ]);
  }

  static bool _canWrite(Directory directory) {
    final probe = File('${directory.path}${Platform.pathSeparator}.write-test');
    try {
      probe.writeAsStringSync('');
      probe.deleteSync();
      return true;
    } on FileSystemException {
      return false;
    }
  }
}

final class _UpdateError implements Exception {
  const _UpdateError(this.message);

  final String message;
}

Future<Uint8List> _readLimited(Stream<List<int>> stream, int limit) async {
  final out = BytesBuilder(copy: false);
  await for (final chunk in stream) {
    out.add(chunk);
    if (out.length > limit) throw const FormatException('too large');
  }
  return out.takeBytes();
}

String _hex(List<int> bytes) => bytes.map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();

/// Waits for the old process, copies the new files over the install (robocopy exits with 8 or more when
/// something failed), and starts the app again whatever happened, so the user is never left with nothing running.
const _swapScript = r'''
param([int]$ProcessId, [string]$Staging, [string]$AppDir, [string]$Log)
Wait-Process -Id $ProcessId -Timeout 60 -ErrorAction SilentlyContinue
robocopy $Staging $AppDir /E /R:20 /W:1 /NFL /NDL /NJH /NJS /NP | Out-File -FilePath $Log -Encoding utf8
if ($LASTEXITCODE -ge 8) { Add-Content -Path $Log -Value "copy failed with code $LASTEXITCODE" }
Start-Process -FilePath (Join-Path $AppDir 'manifold_hub.exe') -WorkingDirectory $AppDir
''';
