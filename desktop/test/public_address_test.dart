import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/public_address.dart';

void main() {
  late HttpServer server;
  var status = 200;
  var body = '';

  setUp(() async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) {
      request.response
        ..statusCode = status
        ..write(body);
      request.response.close();
    });
  });

  tearDown(() => server.close(force: true));

  Uri service() => Uri.parse('http://127.0.0.1:${server.port}');

  test('an address the service returns is passed on', () async {
    status = 200;
    body = '203.0.113.7\n';

    expect(await findPublicAddress(service: service()), '203.0.113.7');
  });

  test('anything that is not an address is refused', () async {
    status = 200;
    body = '<html>blocked</html>';

    expect(await findPublicAddress(service: service()), isNull);
  });

  test('an error status is refused', () async {
    status = 500;
    body = '203.0.113.7';

    expect(await findPublicAddress(service: service()), isNull);
  });

  test('a service that cannot be reached gives null', () async {
    final uri = service();
    await server.close(force: true);

    expect(await findPublicAddress(service: uri, timeout: const Duration(seconds: 2)), isNull);
  });
}
