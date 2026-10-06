#ifndef RUNNER_H264_ENCODER_H_
#define RUNNER_H264_ENCODER_H_

#include <windows.h>
#include <strmif.h>
#include <codecapi.h>
#include <mftransform.h>
#include <wrl/client.h>

#include <atomic>
#include <cstdint>
#include <functional>
#include <vector>

struct EncodedVideo {
  std::vector<uint8_t> data;  // Annex B, with the stream parameters in front of every keyframe
  bool keyframe;
  int64_t timestamp;  // 100 ns units since the shared epoch
};

class H264Encoder {
 public:
  using Sink = std::function<void(EncodedVideo)>;

  bool Open(int width, int height, int bitrate_kbps, int frame_rate, int keyframe_every);
  void Close();

  // Safe to call from any thread.
  void RequestKeyframe() { force_keyframe_ = true; }

  // Safe to call from any thread.
  void SetBitrate(int bitrate_kbps) { pending_bitrate_kbps_ = bitrate_kbps; }

  void Encode(const std::vector<uint8_t>& nv12, int64_t time, int64_t duration, const Sink& sink);

 private:
  Microsoft::WRL::ComPtr<IMFTransform> transform_;
  Microsoft::WRL::ComPtr<ICodecAPI> codec_;
  std::vector<uint8_t> parameters_;
  DWORD output_size_ = 0;
  std::atomic<bool> force_keyframe_{false};
  std::atomic<int> pending_bitrate_kbps_{0};
};

#endif  // RUNNER_H264_ENCODER_H_
