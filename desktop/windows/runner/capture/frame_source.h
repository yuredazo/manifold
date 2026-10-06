#ifndef RUNNER_FRAME_SOURCE_H_
#define RUNNER_FRAME_SOURCE_H_

#include <algorithm>

// H.264 needs even sides, and 4096 is the largest the encoder is set up for.
inline int StreamSide(int requested) { return std::max(16, std::clamp(requested, 16, 4096) & ~1); }

class FrameSource {
 public:
  virtual ~FrameSource() = default;

  virtual void Stop() = 0;
  virtual void RequestKeyframe() = 0;
  virtual void SetBitrate(int bitrate_kbps) = 0;

  virtual int width() const = 0;
  virtual int height() const = 0;
};

#endif  // RUNNER_FRAME_SOURCE_H_
