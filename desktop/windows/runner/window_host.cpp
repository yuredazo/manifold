#include "window_host.h"

#include <algorithm>
#include <cstdint>
#include <string>

#include "channel_args.h"
#include "resource.h"

namespace {

constexpr wchar_t kWindowClass[] = L"MANIFOLD_VIEWER_WINDOW";

// The window procedure has no way to carry this, and there is one host per process.
WindowHost* g_host = nullptr;

std::wstring Utf16FromUtf8(const std::string& text) {
  if (text.empty()) return L"";
  int length = MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0);
  std::wstring wide(length, 0);
  MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), wide.data(), length);
  return wide;
}

LRESULT CALLBACK Proc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
  if (message == WM_CLOSE) {
    if (g_host) g_host->OnClosedByUser(hwnd);
    DestroyWindow(hwnd);
    return 0;
  }
  return DefWindowProcW(hwnd, message, wparam, lparam);
}

bool RegisterWindowClass() {
  static bool registered = false;
  if (registered) return true;
  HINSTANCE instance = GetModuleHandle(nullptr);
  WNDCLASSW window_class{};
  window_class.hCursor = LoadCursor(nullptr, IDC_ARROW);
  window_class.lpszClassName = kWindowClass;
  window_class.hIcon = LoadIcon(instance, MAKEINTRESOURCE(IDI_APP_ICON));
  window_class.hbrBackground = static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH));
  window_class.hInstance = instance;
  window_class.lpfnWndProc = Proc;
  registered = RegisterClassW(&window_class) != 0;
  return registered;
}

}  // namespace

WindowHost::WindowHost(flutter::BinaryMessenger* messenger) {
  g_host = this;
  channel_ = std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
      messenger, "manifold/windows", &flutter::StandardMethodCodec::GetInstance());
  channel_->SetMethodCallHandler([this](const flutter::MethodCall<flutter::EncodableValue>& call,
                                        std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
    const auto* arguments = std::get_if<flutter::EncodableMap>(call.arguments());
    if (!arguments) {
      result->Error("bad-arguments", "expected a map");
      return;
    }
    if (call.method_name() == "open") {
      HWND hwnd = Open(channel::Text(*arguments, "title"), static_cast<int>(channel::Int(*arguments, "width")),
                       static_cast<int>(channel::Int(*arguments, "height")));
      if (!hwnd) {
        result->Error("open-failed", "the window could not be created");
        return;
      }
      result->Success(flutter::EncodableValue(channel::FromWindow(hwnd)));
    } else if (call.method_name() == "close") {
      HWND hwnd = channel::ToWindow(channel::Int(*arguments, "handle"));
      // Only windows this host made, so a stale or forged handle cannot close anything else.
      if (windows_.erase(hwnd)) DestroyWindow(hwnd);
      result->Success();
    } else {
      result->NotImplemented();
    }
  });
}

WindowHost::~WindowHost() {
  g_host = nullptr;
  for (HWND hwnd : windows_) DestroyWindow(hwnd);
}

HWND WindowHost::Open(const std::string& title, int width, int height) {
  if (!RegisterWindowClass()) return nullptr;
  const double scale = GetDpiForSystem() / 96.0;
  RECT frame{0, 0, static_cast<LONG>(std::clamp(width, 160, 4096) * scale),
             static_cast<LONG>(std::clamp(height, 90, 4096) * scale)};
  AdjustWindowRectExForDpi(&frame, WS_OVERLAPPEDWINDOW, FALSE, 0, GetDpiForSystem());
  HWND hwnd = CreateWindowExW(0, kWindowClass, Utf16FromUtf8(title).c_str(), WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                              CW_USEDEFAULT, CW_USEDEFAULT, frame.right - frame.left, frame.bottom - frame.top,
                              nullptr, nullptr, GetModuleHandle(nullptr), nullptr);
  if (hwnd) windows_.insert(hwnd);
  return hwnd;
}

void WindowHost::OnClosedByUser(HWND hwnd) {
  if (!windows_.erase(hwnd)) return;
  channel_->InvokeMethod("closed", std::make_unique<flutter::EncodableValue>(flutter::EncodableMap{
                                       {flutter::EncodableValue("handle"), flutter::EncodableValue(channel::FromWindow(hwnd))}}));
}
