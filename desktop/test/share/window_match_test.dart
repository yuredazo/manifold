import 'package:flutter_test/flutter_test.dart';
import 'package:manifold_hub/share/sharing.dart';
import 'package:manifold_hub/share/window_match.dart';

ShareableWindow _window(int handle, String title, {String process = 'app.exe', String windowClass = 'Main'}) =>
    ShareableWindow(handle: handle, title: title, process: process, windowClass: windowClass, width: 100, height: 100);

void main() {
  test('an exact title wins over an earlier window of the same class', () {
    final found = findWindow(
      title: 'Notes',
      windowClass: 'Main',
      process: 'app.exe',
      candidates: [_window(1, 'Other'), _window(2, 'Notes')],
    );

    expect(found?.handle, 2);
  });

  test('without the title, a window of the same class is taken', () {
    final found = findWindow(title: 'Old page', windowClass: 'Main', process: 'app.exe', candidates: [_window(1, 'New page')]);

    expect(found?.handle, 1);
  });

  test('another executable never matches, whatever the title and class', () {
    final found = findWindow(title: 'Notes', windowClass: 'Main', process: 'app.exe', candidates: [_window(1, 'Notes', process: 'other.exe')]);

    expect(found, isNull);
  });

  test('the executable is compared without regard to case', () {
    final found = findWindow(title: 'Notes', windowClass: 'Main', process: 'Notepad.EXE', candidates: [_window(1, 'Notes', process: 'notepad.exe')]);

    expect(found?.handle, 1);
  });

  test('a different class with a different title is not the same window', () {
    final found = findWindow(title: 'Notes', windowClass: 'Main', process: 'app.exe', candidates: [_window(1, 'Dialog', windowClass: 'Dialog')]);

    expect(found, isNull);
  });

  test('an unknown class matches by title only', () {
    final found = findWindow(title: 'Notes', windowClass: '', process: 'app.exe', candidates: [_window(1, 'Other', windowClass: '')]);

    expect(found, isNull);
  });
}
