#include "capture_host.h"

#include <dwmapi.h>

#include <algorithm>
#include <string>

#include "channel_args.h"
#include "utils.h"

namespace {

using flutter::EncodableList;
using flutter::EncodableMap;
using flutter::EncodableValue;

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

// Windows a person would call a window: shown, with a title, not a tool palette, not hidden on another
// desktop.
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

  auto* list = reinterpret_cast<EncodableList*>(parameter);
  list->push_back(EncodableValue(EncodableMap{
      {EncodableValue("handle"), EncodableValue(channel::FromWindow(window))},
      {EncodableValue("title"), EncodableValue(Utf8FromUtf16(title))},
      {EncodableValue("process"), EncodableValue(ProcessName(process_id))},
      {EncodableValue("class"), EncodableValue(Utf8FromUtf16(window_class))},
      {EncodableValue("width"), EncodableValue(static_cast<int>(std::max<LONG>(inside.right, 16)))},
      {EncodableValue("height"), EncodableValue(static_cast<int>(std::max<LONG>(inside.bottom, 16)))},
  }));
  return TRUE;
}

// Which of the windows Dart asks about still exist, since a window that nobody is capturing cannot say it
// closed.
EncodableList StillOpen(const EncodableMap& arguments) {
  EncodableList open;
  const auto* handles = channel::Find(arguments, "handles");
  const auto* list = handles ? std::get_if<EncodableList>(handles) : nullptr;
  if (!list) return open;
  for (const auto& entry : *list) {
    int64_t handle = 0;
    if (const auto* narrow = std::get_if<int32_t>(&entry)) {
      handle = *narrow;
    } else if (const auto* wide = std::get_if<int64_t>(&entry)) {
      handle = *wide;
    }
    if (IsWindow(channel::ToWindow(handle))) open.push_back(EncodableValue(handle));
  }
  return open;
}

}  // namespace

CaptureHost::CaptureHost(flutter::BinaryMessenger* messenger, HWND message_window) : message_window_(message_window) {
  channel_ = std::make_unique<flutter::MethodChannel<EncodableValue>>(messenger, "manifold/capture",
                                                                       &flutter::StandardMethodCodec::GetInstance());
  channel_->SetMethodCallHandler([this](const flutter::MethodCall<EncodableValue>& call,
                                        std::unique_ptr<flutter::MethodResult<EncodableValue>> result) {
    if (call.method_name() == "windows") {
      EncodableList list;
      EnumWindows(CollectWindow, reinterpret_cast<LPARAM>(&list));
      result->Success(EncodableValue(list));
      return;
    }
    const auto* arguments = std::get_if<EncodableMap>(call.arguments());
    if (!arguments) {
      result->Error("bad-arguments", "expected a map");
      return;
    }
    if (call.method_name() == "alive") {
      result->Success(EncodableValue(StillOpen(*arguments)));
    } else if (call.method_name() == "start") {
      Start(*arguments, std::move(result));
    } else if (call.method_name() == "stop") {
      Stop(channel::Int(*arguments, "handle"));
      result->Success();
    } else if (call.method_name() == "bitrate") {
      auto found = sessions_.find(channel::Int(*arguments, "handle"));
      if (found != sessions_.end()) found->second->video.SetBitrate(static_cast<int>(channel::Int(*arguments, "bitrateKbps")));
      result->Success();
    } else if (call.method_name() == "keyframe") {
      auto found = sessions_.find(channel::Int(*arguments, "handle"));
      if (found != sessions_.end()) found->second->video.RequestKeyframe();
      result->Success();
    } else {
      result->NotImplemented();
    }
  });
}

CaptureHost::~CaptureHost() {
  // The encoder threads call back into this object, so they have to be gone first.
  sessions_.clear();
}

void CaptureHost::Post(std::function<void()> task) {
  {
    std::lock_guard<std::mutex> guard(queue_lock_);
    queue_.push_back(std::move(task));
  }
  PostMessage(message_window_, kMessage, 0, 0);
}

void CaptureHost::Drain() {
  std::vector<std::function<void()>> tasks;
  {
    std::lock_guard<std::mutex> guard(queue_lock_);
    tasks.swap(queue_);
  }
  for (auto& task : tasks) task();
}

void CaptureHost::Send(const char* method, int64_t handle, EncodableMap fields) {
  // A frame that was queued just before its share ended is dropped.
  if (sessions_.find(handle) == sessions_.end()) return;
  fields[EncodableValue("handle")] = EncodableValue(handle);
  channel_->InvokeMethod(method, std::make_unique<EncodableValue>(std::move(fields)));
}

void CaptureHost::Start(const EncodableMap& arguments, std::unique_ptr<flutter::MethodResult<EncodableValue>> result) {
  const int64_t handle = channel::Int(arguments, "handle");
  HWND window = channel::ToWindow(handle);
  DWORD process_id = 0;
  if (!IsWindow(window)) {
    result->Error("no-window", "the window is gone");
    return;
  }
  GetWindowThreadProcessId(window, &process_id);
  if (process_id == GetCurrentProcessId()) {
    result->Error("own-window", "this app's own windows cannot be shared");
    return;
  }
  Stop(handle);

  auto session = std::make_unique<Session>();
  const int64_t epoch = NowIn100ns();
  const bool started = session->video.Start(
      window, static_cast<int>(channel::Int(arguments, "width")), static_cast<int>(channel::Int(arguments, "height")),
      static_cast<int>(channel::Int(arguments, "bitrateKbps")), epoch,
      [this, handle](EncodedVideo frame) {
        Post([this, handle, frame = std::move(frame)]() mutable {
          Send("video", handle,
               EncodableMap{{EncodableValue("timestamp"), EncodableValue(frame.timestamp)},
                            {EncodableValue("keyframe"), EncodableValue(frame.keyframe)},
                            {EncodableValue("data"), EncodableValue(std::move(frame.data))}});
        });
      },
      [this, handle] {
        Post([this, handle] {
          if (sessions_.find(handle) == sessions_.end()) return;
          Send("closed", handle, {});
          Stop(handle);
        });
      });
  if (!started) {
    result->Error("capture-failed", "Windows could not capture that window");
    return;
  }
  if (channel::Bool(arguments, "audio")) {
    session->audio_requested = true;
    // Windows takes a second or two to hand out the sound of an application, and this thread is the
    // one that sends the picture on, so the picture must not wait for it.
    Session* owner = session.get();
    session->audio_start = std::async(std::launch::async, [this, handle, process_id, epoch, owner] {
      return owner->audio.Start(process_id, epoch, [this, handle](EncodedAudio frame) {
        Post([this, handle, frame = std::move(frame)]() mutable {
          Send("audio", handle,
               EncodableMap{{EncodableValue("timestamp"), EncodableValue(frame.timestamp)},
                            {EncodableValue("data"), EncodableValue(std::move(frame.data))}});
        });
      });
    });
  }
  const int width = session->video.width();
  const int height = session->video.height();
  const bool with_audio = session->audio_requested;
  sessions_[handle] = std::move(session);
  result->Success(EncodableValue(EncodableMap{{EncodableValue("width"), EncodableValue(width)},
                                              {EncodableValue("height"), EncodableValue(height)},
                                              {EncodableValue("audio"), EncodableValue(with_audio)}}));
}

void CaptureHost::Stop(int64_t handle) {
  auto found = sessions_.find(handle);
  if (found == sessions_.end()) return;
  // Removed first, so whatever the encoder threads still queue for it is ignored.
  std::unique_ptr<Session> session = std::move(found->second);
  sessions_.erase(found);
  if (session->audio_start.valid()) session->audio_start.wait();
  session->audio.Stop();
  session->video.Stop();
}
