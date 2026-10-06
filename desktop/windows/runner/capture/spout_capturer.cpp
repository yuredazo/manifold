#include "spout_capturer.h"

#include <windows.h>
#include <wrl/client.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <thread>

#include <SpoutDX.h>

#include "nv12_converter.h"
#include "utils.h"

using Microsoft::WRL::ComPtr;

namespace {

constexpr auto kPollInterval = std::chrono::milliseconds(1000 / 60);
constexpr int kNameBytes = 256;

// Senders name themselves in the system code page, and the method channel only carries UTF-8.
std::string Utf8FromAnsi(const char* text) {
  const int length = MultiByteToWideChar(CP_ACP, 0, text, -1, nullptr, 0);
  if (length <= 1) return "";
  std::wstring wide(length, L'\0');
  MultiByteToWideChar(CP_ACP, 0, text, -1, wide.data(), length);
  return Utf8FromUtf16(wide.c_str());
}

std::string AnsiFromUtf8(const std::string& text) {
  if (text.empty()) return "";
  const int wide_length = MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0);
  std::wstring wide(wide_length, L'\0');
  MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), wide.data(), wide_length);
  const int length = WideCharToMultiByte(CP_ACP, 0, wide.data(), wide_length, nullptr, 0, nullptr, nullptr);
  std::string ansi(length, '\0');
  WideCharToMultiByte(CP_ACP, 0, wide.data(), wide_length, ansi.data(), length, nullptr, nullptr);
  return ansi;
}

}  // namespace

std::vector<SpoutSenderInfo> SpoutCapturer::List() {
  std::vector<SpoutSenderInfo> senders;
  spoutDX registry;
  for (const std::string& name : registry.GetSenderList()) {
    unsigned int width = 0, height = 0;
    HANDLE share_handle = nullptr;
    DWORD format = 0;
    if (!registry.GetSenderInfo(name.c_str(), width, height, share_handle, format) || width == 0 || height == 0) continue;
    senders.push_back({Utf8FromAnsi(name.c_str()), static_cast<int>(width), static_cast<int>(height)});
  }
  return senders;
}

struct SpoutCapturer::Impl {
  std::function<void()> closed;
  int64_t epoch = 0;
  int out_width = 0;
  int out_height = 0;

  FramePump pump;
  ComPtr<ID3D11Device> device;
  ComPtr<ID3D11DeviceContext> context;
  Nv12Converter converter;
  spoutDX receiver;
  std::vector<uint8_t> packed;
  std::atomic<bool> running{false};
  std::thread thread;

  void Loop() {
    unsigned int width = 0, height = 0;
    auto next = std::chrono::steady_clock::now();
    while (running) {
      next += kPollInterval;
      if (!receiver.ReceiveTexture()) {
        if (running && closed) closed();
        return;
      }
      // A sender that stops drawing sends no new frame, so a change of size is the cue for a picture of its own.
      const bool resized = receiver.GetSenderWidth() != width || receiver.GetSenderHeight() != height;
      if (receiver.IsFrameNew() || resized) {
        ID3D11Texture2D* texture = receiver.GetSenderTexture();
        width = receiver.GetSenderWidth();
        height = receiver.GetSenderHeight();
        if (texture && width > 0 && height > 0 && converter.Convert(texture, static_cast<int>(width), static_cast<int>(height), packed)) {
          pump.Submit(packed, NowIn100ns() - epoch);
        }
      }
      std::this_thread::sleep_until(next);
    }
  }
};

SpoutCapturer::SpoutCapturer() : impl_(std::make_unique<Impl>()) {}

SpoutCapturer::~SpoutCapturer() { Stop(); }

bool SpoutCapturer::Start(const std::string& name, int width, int height, int bitrate_kbps, int64_t epoch_100ns, Sink sink,
                          std::function<void()> closed) {
  Impl& state = *impl_;
  if (state.running) return false;
  const std::string sender = AnsiFromUtf8(name);
  if (sender.empty() || sender.size() >= kNameBytes) return false;

  // Only to know the sender is running; the receiver reads its details itself once it connects.
  unsigned int ignored_width = 0, ignored_height = 0;
  HANDLE ignored_handle = nullptr;
  DWORD ignored_format = 0;
  if (!state.receiver.GetSenderInfo(sender.c_str(), ignored_width, ignored_height, ignored_handle, ignored_format)) return false;

  state.closed = std::move(closed);
  state.epoch = epoch_100ns;
  state.out_width = StreamSide(width);
  state.out_height = StreamSide(height);
  if (!CreateCaptureDevice(state.device, state.context) ||
      !state.converter.Open(state.device.Get(), state.context.Get(), state.out_width, state.out_height, FramePump::kFrameRate) ||
      !state.pump.Start(state.out_width, state.out_height, bitrate_kbps, epoch_100ns, std::move(sink))) {
    Stop();
    return false;
  }
  state.receiver.OpenDirectX11(state.device.Get());
  state.receiver.SetReceiverName(sender.c_str());
  state.running = true;
  state.thread = std::thread([&state] { state.Loop(); });
  return true;
}

void SpoutCapturer::Stop() {
  Impl& state = *impl_;
  state.running = false;
  if (state.thread.joinable()) state.thread.join();
  state.pump.Stop();
  state.receiver.ReleaseReceiver();
  state.receiver.CloseDirectX11();
  state.converter.Close();
  state.context.Reset();
  state.device.Reset();
}

void SpoutCapturer::RequestKeyframe() { impl_->pump.RequestKeyframe(); }

void SpoutCapturer::SetBitrate(int bitrate_kbps) { impl_->pump.SetBitrate(bitrate_kbps); }

int SpoutCapturer::width() const { return impl_->out_width; }

int SpoutCapturer::height() const { return impl_->out_height; }
