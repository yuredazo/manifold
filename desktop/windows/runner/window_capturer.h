#ifndef RUNNER_WINDOW_CAPTURER_H_
#define RUNNER_WINDOW_CAPTURER_H_

#include <windows.h>

#include <cstdint>
#include <functional>
#include <memory>

#include "frame_pump.h"

class WindowCapturer {
 public:
  using Sink = H264Encoder::Sink;

  WindowCapturer();
  ~WindowCapturer();

  WindowCapturer(const WindowCapturer&) = delete;
  WindowCapturer& operator=(const WindowCapturer&) = delete;

  // `closed` is called from another thread when the window goes away.
  bool Start(HWND window, HMONITOR monitor, int width, int height, int bitrate_kbps, int64_t epoch_100ns, bool cursor, Sink sink,
             std::function<void()> closed);
  void Stop();
  void RequestKeyframe();
  void SetBitrate(int bitrate_kbps);

  int width() const;
  int height() const;

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

#endif  // RUNNER_WINDOW_CAPTURER_H_
