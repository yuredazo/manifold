#ifndef RUNNER_APP_AUDIO_CAPTURER_H_
#define RUNNER_APP_AUDIO_CAPTURER_H_

#include <windows.h>

#include <cstdint>
#include <functional>
#include <memory>
#include <vector>

struct EncodedAudio {
  std::vector<uint8_t> data;  // one raw AAC-LC frame
  int64_t timestamp;          // 100 ns units since the shared epoch
};

class AppAudioCapturer {
 public:
  using Sink = std::function<void(EncodedAudio)>;

  AppAudioCapturer();
  ~AppAudioCapturer();

  AppAudioCapturer(const AppAudioCapturer&) = delete;
  AppAudioCapturer& operator=(const AppAudioCapturer&) = delete;

  // Returns false if Windows cannot capture that, for instance before Windows 10 build 20348.
  bool Start(DWORD process_id, bool all_but_process, int64_t epoch_100ns, Sink sink);
  void Stop();

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

#endif  // RUNNER_APP_AUDIO_CAPTURER_H_
