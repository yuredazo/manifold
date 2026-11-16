#include "window_capturer.h"

#include <d3d11.h>
#include <dwmapi.h>
#include <dxgi.h>
#include <mfapi.h>
#include <wrl/client.h>

#include <winrt/Windows.Foundation.Metadata.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <winrt/Windows.Security.Authorization.AppCapabilityAccess.h>

#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <thread>

#include "nv12_converter.h"
#include "secure/secure_client.h"

using Microsoft::WRL::ComPtr;
namespace capture = winrt::Windows::Graphics::Capture;
namespace directx = winrt::Windows::Graphics::DirectX;
using winrt::Windows::Graphics::SizeInt32;

namespace {


// Windows 11 outlines what is captured in yellow unless the app was granted borderless access. An unpackaged app asks once
// and the answer is kept. The request waits for the system, so it runs off the UI thread, where blocking on it is not allowed.
bool BorderlessAllowed() {
  static const bool allowed = [] {
    bool granted = false;
    std::thread asking([&granted] {
      try {
        winrt::init_apartment(winrt::apartment_type::multi_threaded);
        namespace metadata = winrt::Windows::Foundation::Metadata;
        if (!metadata::ApiInformation::IsMethodPresent(L"Windows.Graphics.Capture.GraphicsCaptureAccess", L"RequestAccessAsync")) return;
        const auto answer = capture::GraphicsCaptureAccess::RequestAccessAsync(capture::GraphicsCaptureAccessKind::Borderless).get();
        granted = answer == winrt::Windows::Security::Authorization::AppCapabilityAccess::AppCapabilityAccessStatus::Allowed;
      } catch (const winrt::hresult_error&) {
        // Windows without the API keeps the border.
      }
    });
    asking.join();
    return granted;
  }();
  return allowed;
}

}  // namespace

struct WindowCapturer::Impl {
  std::function<void()> closed;
  int64_t epoch = 0;
  int out_width = 0;
  int out_height = 0;

  FramePump pump;

  ComPtr<ID3D11Device> device;
  ComPtr<ID3D11DeviceContext> context;
  Nv12Converter converter;
  directx::Direct3D11::IDirect3DDevice winrt_device{nullptr};
  capture::GraphicsCaptureItem item{nullptr};
  capture::Direct3D11CaptureFramePool pool{nullptr};
  capture::GraphicsCaptureSession session{nullptr};
  winrt::event_token frame_token;
  winrt::event_token closed_token;
  SizeInt32 pool_size{};

  std::vector<uint8_t> packed;
  HWND window = nullptr;
  bool client_only = false;
  std::atomic<bool> running{false};
  bool media_started = false;

  std::mutex converting;
  // Where the picture comes from while a UAC prompt or the lock screen is up: the display itself, or the main display
  // for a window, since that is where the prompt appears.
  RECT secure_monitor{};
  bool secure_wanted = false;
  std::thread secure_thread;
  ComPtr<ID3D11Texture2D> secure_texture;
  int secure_width = 0;
  int secure_height = 0;

  // A window's capture is its visible frame, so the client area is found relative to the frame's top left corner.
  // Read each frame, since the window can be resized or moved between monitors while it is shared.
  RECT ClientCrop() const {
    RECT frame{};
    RECT client{};
    if (FAILED(DwmGetWindowAttribute(window, DWMWA_EXTENDED_FRAME_BOUNDS, &frame, sizeof(frame))) || !GetClientRect(window, &client)) return {};
    POINT origin{0, 0};
    if (!ClientToScreen(window, &origin)) return {};
    const LONG left = origin.x - frame.left;
    const LONG top = origin.y - frame.top;
    return {left, top, left + client.right, top + client.bottom};
  }

  bool CreateDevice() {
    if (!CreateCaptureDevice(device, context)) return false;
    ComPtr<IDXGIDevice> dxgi;
    if (FAILED(device.As(&dxgi))) return false;
    winrt::com_ptr<::IInspectable> inspectable;
    if (FAILED(CreateDirect3D11DeviceFromDXGIDevice(dxgi.Get(), inspectable.put()))) return false;
    winrt_device = inspectable.as<directx::Direct3D11::IDirect3DDevice>();
    return true;
  }

  void OnFrame(const capture::Direct3D11CaptureFramePool& sender) {
    auto frame = sender.TryGetNextFrame();
    if (!frame || !running) return;
    const auto content = frame.ContentSize();
    if (content.Width <= 0 || content.Height <= 0) return;
    if (content.Width != pool_size.Width || content.Height != pool_size.Height) {
      pool_size = content;
      sender.Recreate(winrt_device, directx::DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, content);
    }
    auto access = frame.Surface().as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
    ComPtr<ID3D11Texture2D> texture;
    if (FAILED(access->GetInterface(IID_PPV_ARGS(&texture)))) return;
    if (secure_client::Showing()) return;
    std::lock_guard<std::mutex> guard(converting);
    if (client_only) converter.SetCrop(ClientCrop());
    if (converter.Convert(texture.Get(), content.Width, content.Height, packed)) pump.Submit(packed, frame.SystemRelativeTime().count() - epoch);
  }

  bool SecureTexture(int width, int height) {
    if (secure_texture && secure_width == width && secure_height == height) return true;
    secure_texture.Reset();
    D3D11_TEXTURE2D_DESC description{};
    description.Width = static_cast<UINT>(width);
    description.Height = static_cast<UINT>(height);
    description.MipLevels = 1;
    description.ArraySize = 1;
    description.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    description.SampleDesc.Count = 1;
    description.Usage = D3D11_USAGE_DEFAULT;
    description.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    if (FAILED(device->CreateTexture2D(&description, nullptr, &secure_texture))) return false;
    secure_width = width;
    secure_height = height;
    return true;
  }

  void SecureLoop(int frame_rate) {
    const auto interval = std::chrono::milliseconds(1000 / std::max(frame_rate, 1));
    std::vector<uint8_t> pixels;
    std::vector<uint8_t> converted;
    int64_t sequence = 0;
    while (running) {
      if (!secure_client::Showing()) {
        sequence = 0;
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        continue;
      }
      int width = 0;
      int height = 0;
      if (secure_client::Frame(secure_monitor, sequence, pixels, width, height) && SecureTexture(width, height)) {
        context->UpdateSubresource(secure_texture.Get(), 0, nullptr, pixels.data(), static_cast<UINT>(width) * 4, 0);
        std::lock_guard<std::mutex> guard(converting);
        converter.SetCrop({});
        if (converter.Convert(secure_texture.Get(), width, height, converted)) pump.Submit(converted, NowIn100ns() - epoch);
      }
      std::this_thread::sleep_for(interval);
    }
  }
};

WindowCapturer::WindowCapturer() : impl_(std::make_unique<Impl>()) {}

WindowCapturer::~WindowCapturer() { Stop(); }

bool WindowCapturer::Start(HWND window, HMONITOR monitor, int width, int height, int bitrate_kbps, int64_t epoch_100ns,
                           bool cursor, Sink sink, std::function<void()> closed, int frame_rate, bool client_only) {
  Impl& state = *impl_;
  state.window = window;
  state.client_only = client_only && window != nullptr;
  if (state.running || !capture::GraphicsCaptureSession::IsSupported()) return false;
  if (FAILED(MFStartup(MF_VERSION, MFSTARTUP_LITE))) return false;
  state.media_started = true;
  state.closed = std::move(closed);
  state.epoch = epoch_100ns;

  try {
    if (!state.CreateDevice()) return false;
    auto interop = winrt::get_activation_factory<capture::GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
    const HRESULT created = window ? interop->CreateForWindow(window, winrt::guid_of<capture::GraphicsCaptureItem>(), winrt::put_abi(state.item))
                                    : interop->CreateForMonitor(monitor, winrt::guid_of<capture::GraphicsCaptureItem>(), winrt::put_abi(state.item));
    if (FAILED(created)) return false;
    const auto size = state.item.Size();
    if (size.Width <= 0 || size.Height <= 0) return false;
    state.out_width = StreamSide(width);
    state.out_height = StreamSide(height);
    if (!state.converter.Open(state.device.Get(), state.context.Get(), state.out_width, state.out_height, frame_rate)) return false;
    if (!state.pump.Start(state.out_width, state.out_height, bitrate_kbps, epoch_100ns, std::move(sink), frame_rate)) return false;

    state.pool_size = size;
    state.pool = capture::Direct3D11CaptureFramePool::CreateFreeThreaded(
        state.winrt_device, directx::DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, size);
    state.frame_token = state.pool.FrameArrived(
        [&state](const capture::Direct3D11CaptureFramePool& pool, const winrt::Windows::Foundation::IInspectable&) {
          state.OnFrame(pool);
        });
    state.closed_token = state.item.Closed([&state](const capture::GraphicsCaptureItem&, const winrt::Windows::Foundation::IInspectable&) {
      if (state.running && state.closed) state.closed();
    });
    state.session = state.pool.CreateCaptureSession(state.item);
    try {
      state.session.IsCursorCaptureEnabled(cursor);
    } catch (const winrt::hresult_error&) {
      // Windows before 10 version 2004 cannot leave the cursor out, so it stays in the picture.
    }
    if (BorderlessAllowed()) {
      try {
        state.session.IsBorderRequired(false);
      } catch (const winrt::hresult_error&) {
        // The border is only a hint to the user, so a refusal never stops the capture.
      }
    }
    state.running = true;
    state.session.StartCapture();
    if (window) {
      state.secure_monitor = secure_client::PrimaryMonitor();
    } else {
      MONITORINFO info{sizeof(info)};
      GetMonitorInfoW(monitor, &info);
      state.secure_monitor = info.rcMonitor;
    }
    secure_client::Want(state.secure_monitor);
    state.secure_wanted = true;
    state.secure_thread = std::thread([&state, frame_rate] { state.SecureLoop(frame_rate); });
    return true;
  } catch (const winrt::hresult_error&) {
    Stop();
    return false;
  }
}

void WindowCapturer::Stop() {
  Impl& state = *impl_;
  state.running = false;
  if (state.secure_thread.joinable()) state.secure_thread.join();
  if (state.secure_wanted) {
    secure_client::Unwant(state.secure_monitor);
    state.secure_wanted = false;
  }
  try {
    if (state.pool) state.pool.FrameArrived(state.frame_token);
    if (state.item) state.item.Closed(state.closed_token);
    if (state.session) state.session.Close();
    if (state.pool) state.pool.Close();
  } catch (const winrt::hresult_error&) {
    // The window may already be gone, which closes the session by itself.
  }
  state.pump.Stop();
  state.session = nullptr;
  state.pool = nullptr;
  state.item = nullptr;
  state.converter.Close();
  state.secure_texture.Reset();
  state.winrt_device = nullptr;
  state.context.Reset();
  state.device.Reset();
  if (state.media_started) {
    state.media_started = false;
    MFShutdown();
  }
}

void WindowCapturer::RequestKeyframe() { impl_->pump.RequestKeyframe(); }

void WindowCapturer::SetBitrate(int bitrate_kbps) { impl_->pump.SetBitrate(bitrate_kbps); }

