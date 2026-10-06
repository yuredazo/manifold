#ifndef RUNNER_SPOUT_CAPTURER_H_
#define RUNNER_SPOUT_CAPTURER_H_

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "frame_pump.h"
#include "frame_source.h"

struct SpoutSenderInfo {
  std::string name;  // UTF-8
  int width;
  int height;
};

// A Spout sender on this PC, read from the texture it shares between programs and fitted into the size asked for.
class SpoutCapturer : public FrameSource {
 public:
  using Sink = H264Encoder::Sink;

  static std::vector<SpoutSenderInfo> List();

  SpoutCapturer();
  ~SpoutCapturer() override;

  SpoutCapturer(const SpoutCapturer&) = delete;
  SpoutCapturer& operator=(const SpoutCapturer&) = delete;

  // `closed` is called from another thread when the sender stops.
  bool Start(const std::string& name, int width, int height, int bitrate_kbps, int64_t epoch_100ns, Sink sink,
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

#endif  // RUNNER_SPOUT_CAPTURER_H_
