import 'sharing.dart';

/// Finds again a window that was shared before, the way OBS does: the same executable is required, then an exact title
/// wins, and otherwise any window of the same class. The title alone would lose a browser window whenever the page changes.
ShareableWindow? findWindow({
  required String title,
  required String windowClass,
  required String process,
  required Iterable<ShareableWindow> candidates,
}) {
  final sameApp = candidates.where((window) => window.process.toLowerCase() == process.toLowerCase());
  for (final window in sameApp) {
    if (window.title == title) return window;
  }
  if (windowClass.isEmpty) return null;
  for (final window in sameApp) {
    if (window.windowClass == windowClass) return window;
  }
  return null;
}
