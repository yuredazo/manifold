#include <dwmapi.h>
#include <windows.h>

#include <algorithm>
#include <cstdint>
#include <future>
#include <map>
#include <memory>
#include <string>

#include "audio/app_audio_capturer.h"
#include "capture/camera_capturer.h"
#include "capture/frame_pump.h"
#include "capture/spout_capturer.h"
#include "capture/window_capturer.h"
#include "utils.h"

// Handle tags, kept identical to the Flutter runner's so a saved share means the same thing to either hub.
namespace {

constexpr int64_t kMonitorTag = int64_t{1} << 62;
constexpr int64_t kCameraTag = int64_t{1} << 61;
constexpr int64_t kSpoutTag = int64_t{1} << 60;

bool IsMonitor(int64_t handle) { return (handle & kMonitorTag) != 0; }
bool IsCamera(int64_t handle) { return (handle & kCameraTag) != 0 && !IsMonitor(handle); }
bool IsSpout(int64_t handle) { return (handle & kSpoutTag) != 0 && !IsMonitor(handle) && !IsCamera(handle); }
HWND ToWindow(int64_t handle) { return reinterpret_cast<HWND>(static_cast<intptr_t>(handle)); }
HMONITOR ToMonitor(int64_t handle) { return reinterpret_cast<HMONITOR>(static_cast<intptr_t>(handle & ~kMonitorTag)); }

template <typename Unit>
uint32_t Hash(const std::basic_string<Unit>& text) {
  uint32_t hash = 2166136261u;  // FNV-1a
  for (Unit unit : text) hash = (hash ^ static_cast<uint32_t>(unit)) * 16777619u;
  return hash;
}

}  // namespace

extern "C" {

struct ShareEntry {
  int64_t handle;
  const char* title;
  const char* process;
  const char* window_class;
  const char* device;
  int32_t width;
  int32_t height;
  int32_t primary;
};

typedef void (*EntrySink)(void* context, const ShareEntry* entry);
typedef void (*VideoSink)(int64_t handle, int64_t timestamp, int32_t keyframe, const uint8_t* data, size_t size);
typedef void (*AudioSink)(int64_t handle, int64_t timestamp, const uint8_t* data, size_t size);
typedef void (*ClosedSink)(int64_t handle);

struct StartRequest {
  int64_t handle;
  int32_t width;
  int32_t height;
  int32_t bitrate_kbps;
  int32_t audio;
  int32_t picture;
  int32_t cursor;
  int32_t client_only;
  int32_t alpha;
  int32_t fps;
  const char* device;
};

}  // extern "C"

namespace {

VideoSink video_sink = nullptr;
AudioSink audio_sink = nullptr;
ClosedSink closed_sink = nullptr;

struct Session {
  std::unique_ptr<FrameSource> video;
  AppAudioCapturer audio;
  // Declared last, so it is waited for first when the session goes away.
  std::future<bool> audio_start;
};

std::map<int64_t, std::unique_ptr<Session>> sessions;

struct Collected {
  std::string title, process, window_class, device;
  ShareEntry entry{};
};

void Emit(Collected& found, EntrySink sink, void* context) {
  found.entry.title = found.title.c_str();
  found.entry.process = found.process.c_str();
  found.entry.window_class = found.window_class.c_str();
  found.entry.device = found.device.c_str();
  sink(context, &found.entry);
}

std::string ProcessName(DWORD process_id) {
  HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, process_id);
  if (!process) return "";
  wchar_t path[MAX_PATH];
  DWORD length = MAX_PATH;
  std::string name;
  if (QueryFullProcessImageNameW(process, 0, path, &length)) {
    std::wstring full(path, length);
    name = Utf8FromUtf16(full.substr(full.find_last_of(L'\\') + 1).c_str());
  }
  CloseHandle(process);
  return name;
}

struct Listing {
  EntrySink sink;
  void* context;
};

BOOL CALLBACK CollectWindow(HWND window, LPARAM parameter) {
  if (!IsWindowVisible(window) || GetWindow(window, GW_OWNER) != nullptr) return TRUE;
  if (GetWindowLongW(window, GWL_EXSTYLE) & WS_EX_TOOLWINDOW) return TRUE;
  BOOL cloaked = FALSE;
  DwmGetWindowAttribute(window, DWMWA_CLOAKED, &cloaked, sizeof(cloaked));
  if (cloaked) return TRUE;
  wchar_t title[256];
  if (GetWindowTextW(window, title, ARRAYSIZE(title)) == 0) return TRUE;
  DWORD process_id = 0;
  GetWindowThreadProcessId(window, &process_id);
  if (process_id == GetCurrentProcessId()) return TRUE;
  RECT frame;
  if (!GetWindowRect(window, &frame) || frame.right - frame.left < 16 || frame.bottom - frame.top < 16) return TRUE;
  RECT inside;
  GetClientRect(window, &inside);
  wchar_t window_class[256] = L"";
  GetClassNameW(window, window_class, ARRAYSIZE(window_class));

  Collected found;
  found.title = Utf8FromUtf16(title);
  found.process = ProcessName(process_id);
  found.window_class = Utf8FromUtf16(window_class);
  found.entry.handle = static_cast<int64_t>(reinterpret_cast<intptr_t>(window));
  found.entry.width = static_cast<int32_t>(std::max<LONG>(inside.right, 16));
  found.entry.height = static_cast<int32_t>(std::max<LONG>(inside.bottom, 16));
  auto* listing = reinterpret_cast<Listing*>(parameter);
  Emit(found, listing->sink, listing->context);
  return TRUE;
}

BOOL CALLBACK CollectMonitor(HMONITOR monitor, HDC, LPRECT, LPARAM parameter) {
  MONITORINFOEXW info{};
  info.cbSize = sizeof(info);
  if (!GetMonitorInfoW(monitor, &info)) return TRUE;
  Collected found;
  found.title = Utf8FromUtf16(info.szDevice);
  found.entry.handle = static_cast<int64_t>(reinterpret_cast<intptr_t>(monitor)) | kMonitorTag;
  found.entry.width = info.rcMonitor.right - info.rcMonitor.left;
  found.entry.height = info.rcMonitor.bottom - info.rcMonitor.top;
  found.entry.primary = (info.dwFlags & MONITORINFOF_PRIMARY) != 0;
  auto* listing = reinterpret_cast<Listing*>(parameter);
  Emit(found, listing->sink, listing->context);
  return TRUE;
}

int FrameRateOf(int32_t asked) { return asked <= 0 ? FramePump::kFrameRate : std::min(asked, FramePump::kMaxFrameRate); }

H264Encoder::Sink VideoOf(int64_t handle) {
  return [handle](EncodedVideo frame) {
    if (video_sink) video_sink(handle, frame.timestamp, frame.keyframe ? 1 : 0, frame.data.data(), frame.data.size());
  };
}

std::function<void()> ClosedOf(int64_t handle) {
  return [handle] {
    if (closed_sink) closed_sink(handle);
  };
}

bool StartWindow(const StartRequest& request, Session& session) {
  const bool display = IsMonitor(request.handle);
  HWND window = display ? nullptr : ToWindow(request.handle);
  HMONITOR monitor = display ? ToMonitor(request.handle) : nullptr;
  DWORD process_id = 0;
  if (display) {
    MONITORINFO info{sizeof(info)};
    if (!GetMonitorInfoW(monitor, &info)) return false;
    // Everything this computer plays except this app, whose own player windows would feed the sound back.
    process_id = GetCurrentProcessId();
  } else {
    if (!IsWindow(window)) return false;
    GetWindowThreadProcessId(window, &process_id);
    if (process_id == GetCurrentProcessId()) return false;
  }
  if (!request.picture && !request.audio) return false;
  const int64_t epoch = NowIn100ns();
  if (request.picture) {
    auto capturer = std::make_unique<WindowCapturer>();
    if (!capturer->Start(window, monitor, request.width, request.height, request.bitrate_kbps, epoch, request.cursor != 0,
                         VideoOf(request.handle), ClosedOf(request.handle), FrameRateOf(request.fps), request.client_only != 0)) {
      return false;
    }
    session.video = std::move(capturer);
  }
  if (request.audio) {
    // Windows takes a second or two to hand out an application's sound, and the picture must not wait for it.
    const int64_t handle = request.handle;
    Session* owner = &session;
    session.audio_start = std::async(std::launch::async, [handle, process_id, display, epoch, owner] {
      return owner->audio.Start(process_id, display, epoch, [handle](EncodedAudio frame) {
        if (audio_sink) audio_sink(handle, frame.timestamp, frame.data.data(), frame.data.size());
      });
    });
  }
  return true;
}

}  // namespace

extern "C" {

void capture_set_sinks(VideoSink video, AudioSink audio, ClosedSink closed) {
  video_sink = video;
  audio_sink = audio;
  closed_sink = closed;
}

void capture_list_windows(EntrySink sink, void* context) {
  Listing listing{sink, context};
  EnumWindows(CollectWindow, reinterpret_cast<LPARAM>(&listing));
}

void capture_list_displays(EntrySink sink, void* context) {
  Listing listing{sink, context};
  EnumDisplayMonitors(nullptr, nullptr, CollectMonitor, reinterpret_cast<LPARAM>(&listing));
}

void capture_list_cameras(EntrySink sink, void* context) {
  for (const auto& camera : CameraCapturer::List()) {
    Collected found;
    found.title = Utf8FromUtf16(camera.name.c_str());
    found.device = Utf8FromUtf16(camera.id.c_str());
    found.entry.handle = kCameraTag | Hash(camera.id);
    found.entry.width = camera.width;
    found.entry.height = camera.height;
    Emit(found, sink, context);
  }
}

void capture_list_spouts(EntrySink sink, void* context) {
  for (const auto& sender : SpoutCapturer::List()) {
    Collected found;
    found.title = sender.name;
    found.entry.handle = kSpoutTag | Hash(sender.name);
    found.entry.width = sender.width;
    found.entry.height = sender.height;
    Emit(found, sink, context);
  }
}

int32_t capture_window_alive(int64_t handle) { return IsWindow(ToWindow(handle)) ? 1 : 0; }

void capture_stop(int64_t handle);

int32_t capture_start(const StartRequest* request) {
  capture_stop(request->handle);
  auto session = std::make_unique<Session>();
  const std::string device = request->device ? request->device : "";
  const int64_t epoch = NowIn100ns();
  if (IsCamera(request->handle)) {
    auto capturer = std::make_unique<CameraCapturer>();
    if (!capturer->Start(Utf16FromUtf8(device), request->width, request->height, request->bitrate_kbps, epoch, VideoOf(request->handle),
                         ClosedOf(request->handle))) {
      return 0;
    }
    session->video = std::move(capturer);
  } else if (IsSpout(request->handle)) {
    auto capturer = std::make_unique<SpoutCapturer>();
    if (!capturer->Start(device, request->width, request->height, request->bitrate_kbps, epoch, VideoOf(request->handle),
                         ClosedOf(request->handle), request->alpha != 0, FrameRateOf(request->fps))) {
      return 0;
    }
    session->video = std::move(capturer);
  } else if (!StartWindow(*request, *session)) {
    return 0;
  }
  sessions[request->handle] = std::move(session);
  return 1;
}

void capture_stop(int64_t handle) {
  auto found = sessions.find(handle);
  if (found == sessions.end()) return;
  std::unique_ptr<Session> session = std::move(found->second);
  sessions.erase(found);
  if (session->audio_start.valid()) session->audio_start.wait();
  session->audio.Stop();
  if (session->video) session->video->Stop();
}

void capture_bitrate(int64_t handle, int32_t kbps) {
  auto found = sessions.find(handle);
  if (found != sessions.end() && found->second->video) found->second->video->SetBitrate(kbps);
}

void capture_keyframe(int64_t handle) {
  auto found = sessions.find(handle);
  if (found != sessions.end() && found->second->video) found->second->video->RequestKeyframe();
}

}  // extern "C"
