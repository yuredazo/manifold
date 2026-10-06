import 'dart:convert';
import 'dart:io';

final _service = Uri.parse('https://api.ipify.org');

Future<String?> findPublicAddress({Uri? service, Duration timeout = const Duration(seconds: 6)}) async {
  final client = HttpClient()..connectionTimeout = timeout;
  try {
    final request = await client.getUrl(service ?? _service).timeout(timeout);
    final response = await request.close().timeout(timeout);
    if (response.statusCode != 200) return null;
    final text = await response.transform(utf8.decoder).join().timeout(timeout);
    final address = text.trim();
    return text.length <= 64 && InternetAddress.tryParse(address) != null ? address : null;
  } on Exception {
    return null;
  } finally {
    client.close(force: true);
  }
}
