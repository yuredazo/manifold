#ifndef RUNNER_WINDOW_CAPTURER_H_
#define RUNNER_WINDOW_CAPTURER_H_

#include <windows.h>

#include <cstdint>
#include <functional>
#include <memory>

#include "h264_encoder.h"

// A window that does not change produces no frames, so the last picture is encoded again a few times a second to keep the stream alive.
class WindowCapturer {
 public:
  using Sink = H264Encoder::Sink;

  WindowCapturer();
  ~WindowCapturer();

  WindowCapturer(const WindowCapturer&) = delete;
  WindowCapturer& operator=(const WindowCapturer&) = delete;

// `closed` is called from another thread when the window goes away.
  bool Start(HWND window, int width, int height, int bitrate_kbps, int64_t epoch_100ns, Sink sink,
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

// The shared time base for video and audio timestamps: the performance counter in 100 ns units.
int64_t NowIn100ns();

#endif  // RUNNER_WINDOW_CAPTURER_H_
