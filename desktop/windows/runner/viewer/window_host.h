#ifndef RUNNER_WINDOW_HOST_H_
#define RUNNER_WINDOW_HOST_H_

#include <flutter/binary_messenger.h>
#include <flutter/method_channel.h>
#include <flutter/standard_method_codec.h>
#include <windows.h>

#include <memory>
#include <set>

class WindowHost {
 public:
  explicit WindowHost(flutter::BinaryMessenger* messenger);
  ~WindowHost();

  WindowHost(const WindowHost&) = delete;
  WindowHost& operator=(const WindowHost&) = delete;

  void OnClosedByUser(HWND hwnd);

 private:
  HWND Open(const std::string& title, int width, int height);

  std::unique_ptr<flutter::MethodChannel<flutter::EncodableValue>> channel_;
  std::set<HWND> windows_;
};

#endif  // RUNNER_WINDOW_HOST_H_
