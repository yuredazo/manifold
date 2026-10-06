#ifndef RUNNER_CAMERA_CAPTURER_H_
#define RUNNER_CAMERA_CAPTURER_H_

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "frame_pump.h"
#include "frame_source.h"

struct CameraInfo {
  std::wstring id;  // the symbolic link, which Windows keeps while the camera stays on the same port
  std::wstring name;
  int width;  // the mode a first watcher is most likely to be given
  int height;
};

// A webcam through Media Foundation, fitted into the size asked for with black bars.
class CameraCapturer : public FrameSource {
 public:
  using Sink = H264Encoder::Sink;

  static std::vector<CameraInfo> List();

  CameraCapturer();
  ~CameraCapturer() override;

  CameraCapturer(const CameraCapturer&) = delete;
  CameraCapturer& operator=(const CameraCapturer&) = delete;

  // `closed` is called from another thread when the camera stops delivering, for instance when it is unplugged.
  bool Start(const std::wstring& id, int width, int height, int bitrate_kbps, int64_t epoch_100ns, Sink sink,
             std::function<void()> closed);
  void Stop() override;
  void RequestKeyframe() override;
  void SetBitrate(int bitrate_kbps) override;

  int width() const override;
  int height() const override;

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

#endif  // RUNNER_CAMERA_CAPTURER_H_
