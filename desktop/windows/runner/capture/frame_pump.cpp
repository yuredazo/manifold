#include "frame_pump.h"

#include <timeapi.h>

#include <algorithm>
#include <chrono>

namespace {

constexpr int kKeyframeEvery = 2 * FramePump::kFrameRate;
constexpr auto kFrameInterval = std::chrono::milliseconds(1000 / FramePump::kFrameRate);
constexpr auto kRepeatAfter = std::chrono::milliseconds(250);
constexpr int64_t kFrameDuration100ns = 10'000'000 / FramePump::kFrameRate;
constexpr int kMinBitrateKbps = 200;
constexpr int kMaxBitrateKbps = 20000;

}  // namespace

int64_t NowIn100ns() {
  LARGE_INTEGER frequency, counter;
  QueryPerformanceFrequency(&frequency);
  QueryPerformanceCounter(&counter);
  return counter.QuadPart / frequency.QuadPart * 10'000'000 + counter.QuadPart % frequency.QuadPart * 10'000'000 / frequency.QuadPart;
}

bool FramePump::Start(int width, int height, int bitrate_kbps, int64_t epoch_100ns, H264Encoder::Sink sink) {
  if (running_) return false;
  if (!encoder_.Open(width, height, std::clamp(bitrate_kbps, kMinBitrateKbps, kMaxBitrateKbps), kFrameRate, kKeyframeEvery)) return false;
  // The pacing waits below round up to the 15.6 ms system tick otherwise, and a 33 ms frame interval becomes 47 ms.
  timeBeginPeriod(1);
  sink_ = std::move(sink);
  epoch_ = epoch_100ns;
  latest_.clear();
  fresh_ = false;
  running_ = true;
  thread_ = std::thread([this] { Loop(); });
  return true;
}

void FramePump::Stop() {
  running_ = false;
  wake_.notify_all();
  if (thread_.joinable()) {
    thread_.join();
    timeEndPeriod(1);
  }
  encoder_.Close();
}

void FramePump::Submit(std::vector<uint8_t>& nv12, int64_t time) {
  if (!running_) return;
  {
    std::lock_guard<std::mutex> guard(lock_);
    latest_.swap(nv12);
    latest_time_ = time;
    fresh_ = true;
  }
  wake_.notify_one();
}

void FramePump::SetBitrate(int bitrate_kbps) { encoder_.SetBitrate(std::clamp(bitrate_kbps, kMinBitrateKbps, kMaxBitrateKbps)); }

void FramePump::Loop() {
  auto last = std::chrono::steady_clock::now() - kFrameInterval;
  std::vector<uint8_t> picture;
  while (true) {
    std::unique_lock<std::mutex> guard(lock_);
    wake_.wait_for(guard, kFrameInterval, [this] { return !running_ || fresh_; });
    if (!running_) return;
    if (latest_.empty()) continue;
    const auto now = std::chrono::steady_clock::now();
    if (!fresh_ && now - last < kRepeatAfter) continue;
    if (fresh_ && now - last < kFrameInterval) {
      guard.unlock();
      std::this_thread::sleep_for(kFrameInterval - (now - last));
      continue;
    }
    const int64_t time = fresh_ ? latest_time_ : NowIn100ns() - epoch_;
    picture = latest_;
    fresh_ = false;
    guard.unlock();
    last = now;
    encoder_.Encode(picture, time, kFrameDuration100ns, [this](EncodedVideo frame) {
      if (running_ && sink_) sink_(std::move(frame));
    });
  }
}
