#ifndef RUNNER_FRAME_PUMP_H_
#define RUNNER_FRAME_PUMP_H_

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

#include "h264_encoder.h"

// The shared time base for video and audio timestamps: the performance counter in 100 ns units.
int64_t NowIn100ns();

// Encodes the latest picture at up to 30 frames a second. A source that does not change produces no frames, so the
// last picture is encoded again a few times a second to keep the stream alive.
class FramePump {
 public:
  static constexpr int kFrameRate = 30;

  bool Start(int width, int height, int bitrate_kbps, int64_t epoch_100ns, H264Encoder::Sink sink);
  void Stop();

  // Takes the picture and leaves `nv12` holding the previous one, to be reused.
  void Submit(std::vector<uint8_t>& nv12, int64_t time);

  void RequestKeyframe() { encoder_.RequestKeyframe(); }
  void SetBitrate(int bitrate_kbps);

 private:
  void Loop();

  H264Encoder encoder_;
  H264Encoder::Sink sink_;
  int64_t epoch_ = 0;

  std::mutex lock_;
  std::condition_variable wake_;
  std::vector<uint8_t> latest_;
  int64_t latest_time_ = 0;
  bool fresh_ = false;
  std::atomic<bool> running_{false};
  std::thread thread_;
};

#endif  // RUNNER_FRAME_PUMP_H_
