#include "window_capturer.h"

#include <d3d11.h>
#include <dxgi.h>
#include <mfapi.h>
#include <wrl/client.h>

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>
#include <winrt/Windows.Graphics.DirectX.h>

#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <thread>

using Microsoft::WRL::ComPtr;
namespace capture = winrt::Windows::Graphics::Capture;
namespace directx = winrt::Windows::Graphics::DirectX;
using winrt::Windows::Graphics::SizeInt32;

namespace {

constexpr int kFrameRate = 30;
constexpr int kKeyframeEvery = 2 * kFrameRate;
constexpr auto kFrameInterval = std::chrono::milliseconds(1000 / kFrameRate);
constexpr auto kRepeatAfter = std::chrono::milliseconds(250);
constexpr int64_t kFrameDuration100ns = 10'000'000 / kFrameRate;

int Even(int value) { return std::max(16, value & ~1); }

}  // namespace

int64_t NowIn100ns() {
  LARGE_INTEGER frequency, counter;
  QueryPerformanceFrequency(&frequency);
  QueryPerformanceCounter(&counter);
  return counter.QuadPart / frequency.QuadPart * 10'000'000 + counter.QuadPart % frequency.QuadPart * 10'000'000 / frequency.QuadPart;
}

struct WindowCapturer::Impl {
  Sink sink;
  std::function<void()> closed;
  int64_t epoch = 0;
  int out_width = 0;
  int out_height = 0;

  H264Encoder encoder;

  ComPtr<ID3D11Device> device;
  ComPtr<ID3D11DeviceContext> context;
  ComPtr<ID3D11VideoDevice> video_device;
  ComPtr<ID3D11VideoContext> video_context;
  ComPtr<ID3D11VideoProcessorEnumerator> enumerator;
  ComPtr<ID3D11VideoProcessor> processor;
  ComPtr<ID3D11Texture2D> nv12;
  ComPtr<ID3D11VideoProcessorOutputView> output_view;
  ComPtr<ID3D11Texture2D> staging;
  directx::Direct3D11::IDirect3DDevice winrt_device{nullptr};
  capture::GraphicsCaptureItem item{nullptr};
  capture::Direct3D11CaptureFramePool pool{nullptr};
  capture::GraphicsCaptureSession session{nullptr};
  winrt::event_token frame_token;
  winrt::event_token closed_token;
  SizeInt32 pool_size{};
  SizeInt32 processor_input{};

  std::mutex lock;
  std::condition_variable wake;
  std::vector<uint8_t> latest;
  std::vector<uint8_t> packed;
  int64_t latest_time = 0;
  bool fresh = false;
  std::atomic<bool> running{false};
  std::thread encoder_thread;
  bool media_started = false;

  bool CreateDevice() {
    const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
    if (FAILED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                                 D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT, levels,
                                 ARRAYSIZE(levels), D3D11_SDK_VERSION, &device, nullptr, &context))) {
      return false;
    }
    ComPtr<ID3D10Multithread> multithread;
    if (SUCCEEDED(device.As(&multithread))) multithread->SetMultithreadProtected(TRUE);
    if (FAILED(device.As(&video_device)) || FAILED(context.As(&video_context))) return false;

    ComPtr<IDXGIDevice> dxgi;
    if (FAILED(device.As(&dxgi))) return false;
    winrt::com_ptr<::IInspectable> inspectable;
    if (FAILED(CreateDirect3D11DeviceFromDXGIDevice(dxgi.Get(), inspectable.put()))) return false;
    winrt_device = inspectable.as<directx::Direct3D11::IDirect3DDevice>();
    return true;
  }

  bool CreateOutputTextures() {
    D3D11_TEXTURE2D_DESC description{};
    description.Width = out_width;
    description.Height = out_height;
    description.MipLevels = 1;
    description.ArraySize = 1;
    description.Format = DXGI_FORMAT_NV12;
    description.SampleDesc.Count = 1;
    description.Usage = D3D11_USAGE_DEFAULT;
    description.BindFlags = D3D11_BIND_RENDER_TARGET;
    if (FAILED(device->CreateTexture2D(&description, nullptr, &nv12))) return false;

    description.Usage = D3D11_USAGE_STAGING;
    description.BindFlags = 0;
    description.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    return SUCCEEDED(device->CreateTexture2D(&description, nullptr, &staging));
  }

  // The processor is made for one input size, so a window that was resized needs a new one.
  bool EnsureProcessor(const SizeInt32& input) {
    if (processor && input.Width == processor_input.Width && input.Height == processor_input.Height) return true;
    processor.Reset();
    enumerator.Reset();
    output_view.Reset();
    D3D11_VIDEO_PROCESSOR_CONTENT_DESC content{};
    content.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
    content.InputFrameRate = {kFrameRate, 1};
    content.InputWidth = input.Width;
    content.InputHeight = input.Height;
    content.OutputFrameRate = {kFrameRate, 1};
    content.OutputWidth = out_width;
    content.OutputHeight = out_height;
    content.Usage = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;
    if (FAILED(video_device->CreateVideoProcessorEnumerator(&content, &enumerator))) return false;
    if (FAILED(video_device->CreateVideoProcessor(enumerator.Get(), 0, &processor))) return false;
    D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC view{};
    view.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
    if (FAILED(video_device->CreateVideoProcessorOutputView(nv12.Get(), enumerator.Get(), &view, &output_view))) return false;

    D3D11_VIDEO_PROCESSOR_COLOR_SPACE rgb{};
    video_context->VideoProcessorSetStreamColorSpace(processor.Get(), 0, &rgb);
    D3D11_VIDEO_PROCESSOR_COLOR_SPACE yuv{};
    yuv.YCbCr_Matrix = 1;  // BT.709
    yuv.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
    video_context->VideoProcessorSetOutputColorSpace(processor.Get(), &yuv);
    D3D11_VIDEO_COLOR black{};
    black.YCbCr = {0.0625f, 0.5f, 0.5f, 1.0f};
    video_context->VideoProcessorSetOutputBackgroundColor(processor.Get(), TRUE, &black);
    processor_input = input;
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
    if (ConvertToNv12(texture.Get(), content)) Publish(frame.SystemRelativeTime().count() - epoch);
  }

  bool ConvertToNv12(ID3D11Texture2D* texture, const SizeInt32& content) {
    if (!EnsureProcessor(content)) return false;
    D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC description{};
    description.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
    ComPtr<ID3D11VideoProcessorInputView> input;
    if (FAILED(video_device->CreateVideoProcessorInputView(texture, enumerator.Get(), &description, &input))) return false;

    const double scale = std::min(static_cast<double>(out_width) / content.Width, static_cast<double>(out_height) / content.Height);
    const int fitted_width = std::max(2, static_cast<int>(content.Width * scale) & ~1);
    const int fitted_height = std::max(2, static_cast<int>(content.Height * scale) & ~1);
    const int left = (out_width - fitted_width) / 2 & ~1;
    const int top = (out_height - fitted_height) / 2 & ~1;
    RECT source{0, 0, content.Width, content.Height};
    RECT destination{left, top, left + fitted_width, top + fitted_height};
    RECT whole{0, 0, out_width, out_height};
    video_context->VideoProcessorSetStreamSourceRect(processor.Get(), 0, TRUE, &source);
    video_context->VideoProcessorSetStreamDestRect(processor.Get(), 0, TRUE, &destination);
    video_context->VideoProcessorSetOutputTargetRect(processor.Get(), TRUE, &whole);

    D3D11_VIDEO_PROCESSOR_STREAM stream{};
    stream.Enable = TRUE;
    stream.pInputSurface = input.Get();
    if (FAILED(video_context->VideoProcessorBlt(processor.Get(), output_view.Get(), 0, 1, &stream))) return false;

    context->CopyResource(staging.Get(), nv12.Get());
    D3D11_MAPPED_SUBRESOURCE mapped{};
    if (FAILED(context->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped))) return false;
    packed.resize(static_cast<size_t>(out_width) * out_height * 3 / 2);
    const auto* rows = static_cast<const uint8_t*>(mapped.pData);
    // The chroma plane follows the luma plane at the row pitch of the staging texture.
    const uint8_t* chroma_rows = rows + static_cast<size_t>(mapped.RowPitch) * out_height;
    uint8_t* luma_out = packed.data();
    uint8_t* chroma_out = luma_out + static_cast<size_t>(out_width) * out_height;
    for (int row = 0; row < out_height; ++row) {
      std::memcpy(luma_out + static_cast<size_t>(row) * out_width, rows + static_cast<size_t>(row) * mapped.RowPitch, out_width);
    }
    for (int row = 0; row < out_height / 2; ++row) {
      std::memcpy(chroma_out + static_cast<size_t>(row) * out_width, chroma_rows + static_cast<size_t>(row) * mapped.RowPitch, out_width);
    }
    context->Unmap(staging.Get(), 0);
    return true;
  }

  void Publish(int64_t time) {
    {
      std::lock_guard<std::mutex> guard(lock);
      latest.swap(packed);
      latest_time = time;
      fresh = true;
    }
    wake.notify_one();
  }

  void EncodeLoop() {
    auto last = std::chrono::steady_clock::now() - kFrameInterval;
    std::vector<uint8_t> picture;
    while (true) {
      std::unique_lock<std::mutex> guard(lock);
      wake.wait_for(guard, kFrameInterval, [this] { return !running || fresh; });
      if (!running) return;
      if (latest.empty()) continue;
      const auto now = std::chrono::steady_clock::now();
      if (!fresh && now - last < kRepeatAfter) continue;
      if (fresh && now - last < kFrameInterval) {
        guard.unlock();
        std::this_thread::sleep_for(kFrameInterval - (now - last));
        continue;
      }
      const int64_t time = fresh ? latest_time : NowIn100ns() - epoch;
      picture = latest;
      fresh = false;
      guard.unlock();
      last = now;
      encoder.Encode(picture, time, kFrameDuration100ns, [this](EncodedVideo frame) {
        if (running && sink) sink(std::move(frame));
      });
    }
  }
};

WindowCapturer::WindowCapturer() : impl_(std::make_unique<Impl>()) {}

WindowCapturer::~WindowCapturer() { Stop(); }

bool WindowCapturer::Start(HWND window, HMONITOR monitor, int width, int height, int bitrate_kbps, int64_t epoch_100ns,
                           Sink sink, std::function<void()> closed) {
  Impl& state = *impl_;
  if (state.running || !capture::GraphicsCaptureSession::IsSupported()) return false;
  if (FAILED(MFStartup(MF_VERSION, MFSTARTUP_LITE))) return false;
  state.media_started = true;
  state.sink = std::move(sink);
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
    state.out_width = Even(std::clamp(width, 16, 4096));
    state.out_height = Even(std::clamp(height, 16, 4096));
    if (!state.CreateOutputTextures()) return false;
    if (!state.encoder.Open(state.out_width, state.out_height, std::clamp(bitrate_kbps, 200, 20000), kFrameRate, kKeyframeEvery)) {
      return false;
    }

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
    state.running = true;
    state.encoder_thread = std::thread([&state] { state.EncodeLoop(); });
    state.session.StartCapture();
    return true;
  } catch (const winrt::hresult_error&) {
    Stop();
    return false;
  }
}

void WindowCapturer::Stop() {
  Impl& state = *impl_;
  state.running = false;
  state.wake.notify_all();
  try {
    if (state.pool) state.pool.FrameArrived(state.frame_token);
    if (state.item) state.item.Closed(state.closed_token);
    if (state.session) state.session.Close();
    if (state.pool) state.pool.Close();
  } catch (const winrt::hresult_error&) {
    // The window may already be gone, which closes the session by itself.
  }
  if (state.encoder_thread.joinable()) state.encoder_thread.join();
  state.session = nullptr;
  state.pool = nullptr;
  state.item = nullptr;
  state.encoder.Close();
  state.processor.Reset();
  state.enumerator.Reset();
  state.output_view.Reset();
  state.nv12.Reset();
  state.staging.Reset();
  state.winrt_device = nullptr;
  state.video_context.Reset();
  state.video_device.Reset();
  state.context.Reset();
  state.device.Reset();
  if (state.media_started) {
    state.media_started = false;
    MFShutdown();
  }
}

void WindowCapturer::RequestKeyframe() { impl_->encoder.RequestKeyframe(); }

void WindowCapturer::SetBitrate(int bitrate_kbps) { impl_->encoder.SetBitrate(std::clamp(bitrate_kbps, 200, 20000)); }

int WindowCapturer::width() const { return impl_->out_width; }

int WindowCapturer::height() const { return impl_->out_height; }
