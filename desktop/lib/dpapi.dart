import 'dart:ffi';
import 'dart:typed_data';

import 'package:ffi/ffi.dart';
import 'package:win32/win32.dart';

const _noPrompt = 0x1;

Uint8List protect(Uint8List plain) => _run(plain, (input, output) => CryptProtectData(input, null, null, null, _noPrompt, output).value);

Uint8List? unprotect(Uint8List sealed) {
  try {
    return _run(sealed, (input, output) => CryptUnprotectData(input, null, null, null, _noPrompt, output).value);
  } on StateError {
    return null;
  }
}

Uint8List _run(Uint8List bytes, bool Function(Pointer<CRYPT_INTEGER_BLOB> input, Pointer<CRYPT_INTEGER_BLOB> output) call) {
  return using((arena) {
    final data = arena<Uint8>(bytes.length)..asTypedList(bytes.length).setAll(0, bytes);
    final input = arena<CRYPT_INTEGER_BLOB>()
      ..ref.cbData = bytes.length
      ..ref.pbData = data;
    final output = arena<CRYPT_INTEGER_BLOB>();
    if (!call(input, output)) throw StateError('Windows could not transform the data');
    try {
      return Uint8List.fromList(output.ref.pbData.asTypedList(output.ref.cbData));
    } finally {
      HLOCAL(output.ref.pbData).close();
    }
  });
}
