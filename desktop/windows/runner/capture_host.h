#ifndef RUNNER_CAPTURE_HOST_H_
#define RUNNER_CAPTURE_HOST_H_

#include <flutter/binary_messenger.h>
#include <flutter/method_channel.h>
#include <flutter/standard_method_codec.h>
#include <windows.h>

#include <cstdint>
#include <functional>
#include <future>
#include <map>
#include <memory>
#include <mutex>
#include <vector>

#include "app_audio_capturer.h"
#include "camera_capturer.h"
#include "window_capturer.h"

// Lets Dart share windows, displays and cameras. A channel may only be used from the platform thread, so results from the encoder threads
// wait in a queue and the message window is poked to collect them.
class CaptureHost {
 public:
  static constexpr UINT kMessage = WM_APP + 1;

  CaptureHost(flutter::BinaryMessenger* messenger, HWND message_window);
  ~CaptureHost();

  CaptureHost(const CaptureHost&) = delete;
  CaptureHost& operator=(const CaptureHost&) = delete;

  void Drain();

 private:
  struct Session {
    WindowCapturer video;
    CameraCapturer camera;
    bool is_camera = false;
    AppAudioCapturer audio;
    bool audio_requested = false;
    // Declared last, so it is waited for first when the session goes away.
    std::future<bool> audio_start;

    void RequestKeyframe() {
      if (is_camera) {
        camera.RequestKeyframe();
      } else {
        video.RequestKeyframe();
      }
    }
    void SetBitrate(int kbps) {
      if (is_camera) {
        camera.SetBitrate(kbps);
      } else {
        video.SetBitrate(kbps);
      }
    }
  };

  void Post(std::function<void()> task);
  void Start(const flutter::EncodableMap& arguments, std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);
  void StartCamera(const flutter::EncodableMap& arguments, std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);
  H264Encoder::Sink VideoSink(int64_t handle);
  std::function<void()> ClosedCallback(int64_t handle);
  void Stop(int64_t handle);
  void Send(const char* method, int64_t handle, flutter::EncodableMap fields);

  std::unique_ptr<flutter::MethodChannel<flutter::EncodableValue>> channel_;
  HWND message_window_;
  std::map<int64_t, std::unique_ptr<Session>> sessions_;
  std::mutex queue_lock_;
  std::vector<std::function<void()>> queue_;
};

#endif  // RUNNER_CAPTURE_HOST_H_
