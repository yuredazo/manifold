#ifndef RUNNER_AAC_ENCODER_H_
#define RUNNER_AAC_ENCODER_H_

#include <mftransform.h>
#include <wrl/client.h>

#include <cstdint>
#include <functional>
#include <vector>

namespace aac {
constexpr int kSampleRate = 48000;
constexpr int kChannels = 2;
constexpr int kBytesPerFrame = kChannels * 2;
constexpr int kSamplesPerFrame = 1024;
constexpr size_t kBytesPerAacFrame = static_cast<size_t>(kSamplesPerFrame) * kBytesPerFrame;
constexpr int64_t kFrameDuration100ns = 10'000'000LL * kSamplesPerFrame / kSampleRate;
}  // namespace aac

// Frames come out raw, without the header a player needs, which is added where the stream is played.
class AacEncoder {
 public:
  using Sink = std::function<void(std::vector<uint8_t>)>;

  bool Open();
  void Close();

  void Encode(const uint8_t* pcm, int64_t time, const Sink& sink);

 private:
  Microsoft::WRL::ComPtr<IMFTransform> transform_;
  DWORD output_size_ = 0;
};

#endif  // RUNNER_AAC_ENCODER_H_
