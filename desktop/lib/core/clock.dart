final Stopwatch _monotonic = Stopwatch()..start();

/// Nanoseconds on a clock that only moves forward.
int monotonicNs() => _monotonic.elapsedMicroseconds * 1000;
