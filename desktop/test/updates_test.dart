import 'dart:convert';
import 'dart:io';

import 'package:archive/archive_io.dart';
import 'package:cryptography/dart.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/updates.dart';

String _sha256(List<int> bytes) => const DartSha256().hashSync(bytes).bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();

List<int> _zip(Map<String, String> files) {
  final archive = Archive();
  files.forEach((name, text) => archive.add(ArchiveFile.bytes(name, utf8.encode(text))));
  return ZipEncoder().encode(archive);
}

class _FakeGitHub {
  _FakeGitHub(this._server) {
    _server.listen((request) async {
      if (request.uri.path.endsWith('/releases/latest')) {
        if (failWith != null) {
          request.response.statusCode = failWith!;
          request.response.headers.set('x-ratelimit-reset', '1791179488');
        } else if (release == null) {
          request.response.statusCode = 404;
        } else {
          request.response.write(jsonEncode(release));
        }
      } else if (request.uri.path.startsWith('/download/')) {
        request.response.add(zip);
      } else {
        request.response.statusCode = 404;
      }
      await request.response.close();
    });
  }

  static Future<_FakeGitHub> start() async => _FakeGitHub(await HttpServer.bind(InternetAddress.loopbackIPv4, 0));

  final HttpServer _server;
  Map<String, dynamic>? release;
  int? failWith;
  List<int> zip = const [];

  String get base => 'http://127.0.0.1:${_server.port}';

  Map<String, dynamic> releaseOf(String tag, {String? digest, String? name, String? url}) => {
        'tag_name': tag,
        'html_url': 'https://github.com/yuredazo/manifold/releases/tag/$tag',
        'assets': [
          {
            'name': name ?? 'manifold-hub-windows-$tag.zip',
            'browser_download_url': url ?? '$base/download/manifold-hub-windows-$tag.zip',
            if (digest != null) 'digest': 'sha256:$digest',
          },
        ],
      };

  Future<void> close() => _server.close(force: true);
}

void main() {
  test('versions compare by number, not by text', () {
    expect(isNewer('v1.0.1', '1.0.0'), isTrue);
    expect(isNewer('1.10.0', '1.9.0'), isTrue);
    expect(isNewer('v2.0.0', '1.99.99'), isTrue);
    expect(isNewer('v1.0.0', '1.0.0'), isFalse);
    expect(isNewer('0.9.0', '1.0.0'), isFalse);
    expect(isNewer('nightly', '1.0.0'), isFalse);
  });

  group('with a fake GitHub', () {
    late _FakeGitHub github;
    late Directory sandbox;
    late Directory install;
    late Directory work;
    var exited = false;
    var launched = <String>[];

    Updater updater({String current = '1.0.0'}) => Updater(
          currentVersion: current,
          installDirectory: install,
          workDirectory: work,
          apiBase: github.base,
          assetPrefix: '${github.base}/download/',
          exitApp: () => exited = true,
          launch: (arguments) async => launched = arguments,
        );

    setUp(() async {
      github = await _FakeGitHub.start();
      sandbox = Directory.systemTemp.createTempSync('manifold-update-test');
      install = Directory('${sandbox.path}/install')..createSync();
      File('${install.path}/manifold_hub.exe').writeAsStringSync('old');
      File('${install.path}/keep.txt').writeAsStringSync('mine');
      work = Directory('${sandbox.path}/work');
      exited = false;
      launched = [];
    });

    tearDown(() async {
      await github.close();
      sandbox.deleteSync(recursive: true);
    });

    test('no release published is not an error', () async {
      final subject = updater();
      await subject.check();
      expect(subject.state, isA<UpToDate>());
    });

    test('a rate limit says when to try again', () async {
      github.failWith = 403;
      final subject = updater();
      await subject.check();
      expect((subject.state as UpdateFailed).message, matches(r'Try again after \d\d:\d\d\.'));
    });

    test('the same version is up to date', () async {
      github.release = github.releaseOf('v1.0.0', digest: 'ab');
      final subject = updater();
      await subject.check();
      expect(subject.state, isA<UpToDate>());
    });

    test('a newer release offers the Windows zip with its checksum', () async {
      github.release = github.releaseOf('v1.2.0', digest: 'ABCD');
      final subject = updater();
      await subject.check();
      final state = subject.state as Available;
      expect(state.release.version, '1.2.0');
      expect(state.release.assetName, 'manifold-hub-windows-v1.2.0.zip');
      expect(state.release.sha256, 'abcd');
    });

    test('a release without a checksum is not offered', () async {
      github.release = github.releaseOf('v1.2.0');
      final subject = updater();
      await subject.check();
      expect(subject.state, isA<UpdateFailed>());
    });

    test('a download link outside the release host is refused', () async {
      github.release = github.releaseOf('v1.2.0', digest: 'ab', url: 'http://127.0.0.1:1/evil.zip');
      final subject = updater();
      await subject.check();
      expect(subject.state, isA<UpdateFailed>());
    });

    test('a file that is not the Windows build is ignored', () async {
      github.release = github.releaseOf('v1.2.0', digest: 'ab', name: 'manifold-hub-v1.2.0.apk');
      final subject = updater();
      await subject.check();
      expect(subject.state, isA<UpdateFailed>());
    });

    test('a release without a readable tag is not newer', () async {
      github.release = {'tag_name': 5};
      final subject = updater();
      await subject.check();
      expect(subject.state, isA<UpToDate>());
    });

    test('a download that does not match its checksum is thrown away', () async {
      github.zip = _zip({'manifold_hub.exe': 'new'});
      github.release = github.releaseOf('v1.2.0', digest: _sha256([1, 2, 3]));
      final subject = updater();
      await subject.check();
      await subject.install();
      expect((subject.state as UpdateFailed).message, contains('checksum'));
      expect(exited, isFalse);
      expect(launched, isEmpty);
      expect(File('${work.path}/manifold-hub-windows-v1.2.0.zip').existsSync(), isFalse);
    });

    test('a zip without the app in it is refused', () async {
      github.zip = _zip({'readme.txt': 'x'});
      github.release = github.releaseOf('v1.2.0', digest: _sha256(github.zip));
      final subject = updater();
      await subject.check();
      await subject.install();
      expect((subject.state as UpdateFailed).message, contains('does not contain'));
      expect(exited, isFalse);
    });

    test('a good download is copied over the install by the script and keeps files it does not replace', () async {
      github.zip = _zip({'manifold_hub.exe': 'new', 'data/app.so': 'library'});
      github.release = github.releaseOf('v1.2.0', digest: _sha256(github.zip));
      final subject = updater();
      await subject.check();
      await subject.install();

      expect(subject.state, isA<Installing>());
      expect(exited, isTrue);
      expect(File('${install.path}/manifold_hub.exe').readAsStringSync(), 'old', reason: 'nothing is touched before the app exits');

      final run = [...launched];
      run[run.indexOf('-ProcessId') + 1] = '99999999';
      await Process.run('powershell.exe', run);

      expect(File('${install.path}/manifold_hub.exe').readAsStringSync(), 'new');
      expect(File('${install.path}/data/app.so').readAsStringSync(), 'library');
      expect(File('${install.path}/keep.txt').readAsStringSync(), 'mine');
      expect(File('${work.path}/update.log').readAsStringSync(), isNot(contains('failed')));
    });

    test('a copy that failed is reported at the next start', () async {
      work.createSync(recursive: true);
      File('${work.path}/update.log').writeAsStringSync('copy failed with code 8');
      final subject = updater();
      subject.cleanUp();
      expect(subject.state, isA<UpdateFailed>());
      expect(work.existsSync(), isTrue);
    });

    test('leftovers of a finished update are removed at the next start', () async {
      work.createSync(recursive: true);
      File('${work.path}/update.log').writeAsStringSync('');
      updater().cleanUp();
      expect(work.existsSync(), isFalse);
    });
  });
}
