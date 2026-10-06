#include "camera_capturer.h"

#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <wrl/client.h>
#include <wrl/implements.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <functional>
#include <mutex>

using Microsoft::WRL::ClassicCom;
using Microsoft::WRL::ComPtr;
using Microsoft::WRL::RuntimeClass;
using Microsoft::WRL::RuntimeClassFlags;

namespace {

constexpr DWORD kVideoStream = static_cast<DWORD>(MF_SOURCE_READER_FIRST_VIDEO_STREAM);
constexpr int kAnnouncedWidth = 1280;
constexpr int kAnnouncedHeight = 720;
constexpr DWORD kFlushTimeoutMs = 2000;
constexpr double kMinFps = 15.0;
constexpr double kSmoothFps = 24.0;
constexpr uint8_t kBlackLuma = 16;
constexpr uint8_t kNeutralChroma = 128;

struct Mode {
  UINT32 width = 0;
  UINT32 height = 0;
  double fps = 0;
  ComPtr<IMFMediaType> type;
};

bool IsUsableFormat(const GUID& subtype) {
  return subtype == MFVideoFormat_NV12 || subtype == MFVideoFormat_YUY2 || subtype == MFVideoFormat_MJPG ||
         subtype == MFVideoFormat_I420 || subtype == MFVideoFormat_IYUV || subtype == MFVideoFormat_RGB32 || subtype == MFVideoFormat_RGB24;
}

std::vector<Mode> ModesOf(IMFSourceReader& reader) {
  std::vector<Mode> modes;
  for (DWORD index = 0;; ++index) {
    ComPtr<IMFMediaType> type;
    if (FAILED(reader.GetNativeMediaType(kVideoStream, index, &type))) break;
    GUID subtype{};
    if (FAILED(type->GetGUID(MF_MT_SUBTYPE, &subtype)) || !IsUsableFormat(subtype)) continue;
    Mode mode;
    if (FAILED(MFGetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, &mode.width, &mode.height)) || mode.width == 0 || mode.height == 0) continue;
    UINT32 numerator = 0, denominator = 1;
    MFGetAttributeRatio(type.Get(), MF_MT_FRAME_RATE, &numerator, &denominator);
    mode.fps = denominator ? static_cast<double>(numerator) / denominator : 0;
    if (mode.fps < kMinFps) continue;
    mode.type = type;
    modes.push_back(std::move(mode));
  }
  return modes;
}

// The weights rank a wrong shape (black bars) below a mode smaller than asked for (upscaling), below a slow one, below any size difference.
double Cost(const Mode& mode, int want_width, int want_height) {
  const double shape = std::abs(static_cast<double>(mode.width) / mode.height - static_cast<double>(want_width) / want_height);
  double cost = shape > 0.05 ? 1e9 : 0;
  if (mode.width < static_cast<UINT32>(want_width) || mode.height < static_cast<UINT32>(want_height)) cost += 1e6;
  cost += std::abs(static_cast<double>(mode.width) * mode.height - static_cast<double>(want_width) * want_height) / 1000.0;
  if (mode.fps < kSmoothFps) cost += 500;
  return cost;
}

const Mode* BestMode(const std::vector<Mode>& modes, int want_width, int want_height) {
  const Mode* best = nullptr;
  double best_cost = 0;
  for (const auto& mode : modes) {
    const double cost = Cost(mode, want_width, want_height);
    if (!best || cost < best_cost) {
      best = &mode;
      best_cost = cost;
    }
  }
  return best;
}

ComPtr<IMFMediaSource> OpenSource(const std::wstring& id) {
  ComPtr<IMFAttributes> attributes;
  if (FAILED(MFCreateAttributes(&attributes, 2))) return nullptr;
  attributes->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID);
  attributes->SetString(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK, id.c_str());
  ComPtr<IMFMediaSource> source;
  if (FAILED(MFCreateDeviceSource(attributes.Get(), &source))) return nullptr;
  return source;
}

// Stopping flushes the pending read instead of waiting on it: a camera that has stopped delivering would never end a
// blocking read, and shutting the camera down under one hangs.
class ReadCallback : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IMFSourceReaderCallback> {
 public:
  ReadCallback() : flushed_(CreateEventW(nullptr, TRUE, FALSE, nullptr)) {}
  ~ReadCallback() override { CloseHandle(flushed_); }

  // Replaced under the same lock the handler runs in, so a read that finishes late never calls into a capturer that is gone.
  void SetHandler(std::function<void(HRESULT, DWORD, IMFSample*)> handler) {
    std::lock_guard<std::mutex> guard(lock_);
    handler_ = std::move(handler);
  }

  void ArmFlush() { ResetEvent(flushed_); }
  bool Flushed(DWORD milliseconds) const { return WaitForSingleObject(flushed_, milliseconds) == WAIT_OBJECT_0; }

  STDMETHODIMP OnReadSample(HRESULT status, DWORD, DWORD flags, LONGLONG, IMFSample* sample) override {
    std::lock_guard<std::mutex> guard(lock_);
    if (handler_) handler_(status, flags, sample);
    return S_OK;
  }
  STDMETHODIMP OnEvent(DWORD, IMFMediaEvent*) override { return S_OK; }
  STDMETHODIMP OnFlush(DWORD) override {
    SetEvent(flushed_);
    return S_OK;
  }

 private:
  HANDLE flushed_;
  std::mutex lock_;
  std::function<void(HRESULT, DWORD, IMFSample*)> handler_;
};

ComPtr<IMFSourceReader> OpenReader(IMFMediaSource* source, IMFSourceReaderCallback* callback = nullptr) {
  ComPtr<IMFAttributes> attributes;
  if (FAILED(MFCreateAttributes(&attributes, 2))) return nullptr;
  // Lets the reader turn what the camera sends (YUY2, Motion JPEG) into NV12.
  attributes->SetUINT32(MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, TRUE);
  if (callback) attributes->SetUnknown(MF_SOURCE_READER_ASYNC_CALLBACK, callback);
  ComPtr<IMFSourceReader> reader;
  if (FAILED(MFCreateSourceReaderFromMediaSource(source, attributes.Get(), &reader))) return nullptr;
  return reader;
}

}  // namespace

std::vector<CameraInfo> CameraCapturer::List() {
  std::vector<CameraInfo> cameras;
  if (FAILED(MFStartup(MF_VERSION, MFSTARTUP_LITE))) return cameras;
  ComPtr<IMFAttributes> filter;
  IMFActivate** devices = nullptr;
  UINT32 count = 0;
  if (SUCCEEDED(MFCreateAttributes(&filter, 1)) &&
      SUCCEEDED(filter->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID)) &&
      SUCCEEDED(MFEnumDeviceSources(filter.Get(), &devices, &count))) {
    for (UINT32 i = 0; i < count; ++i) {
      wchar_t* id = nullptr;
      wchar_t* name = nullptr;
      devices[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK, &id, nullptr);
      devices[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &name, nullptr);
      if (id) {
        CameraInfo info{id, name ? name : L"Camera", kAnnouncedWidth, kAnnouncedHeight};
        // A camera that is busy or unreadable is still offered, at the usual size.
        if (auto source = OpenSource(info.id)) {
          if (auto reader = OpenReader(source.Get())) {
            const auto modes = ModesOf(*reader.Get());
            if (const Mode* best = BestMode(modes, kAnnouncedWidth, kAnnouncedHeight)) {
              info.width = static_cast<int>(best->width);
              info.height = static_cast<int>(best->height);
            }
          }
          source->Shutdown();
        }
        cameras.push_back(std::move(info));
      }
      CoTaskMemFree(id);
      CoTaskMemFree(name);
      devices[i]->Release();
    }
  }
  CoTaskMemFree(devices);
  MFShutdown();
  return cameras;
}

struct CameraCapturer::Impl {
  std::function<void()> closed;
  int64_t epoch = 0;
  int out_width = 0;
  int out_height = 0;
  int source_width = 0;
  int source_height = 0;

  FramePump pump;
  ComPtr<IMFMediaSource> source;
  ComPtr<IMFSourceReader> reader;
  ComPtr<ReadCallback> callback;
  std::vector<uint8_t> packed;
  std::atomic<bool> running{false};
  bool media_started = false;

  // Nearest-pixel scaling is only a fallback, since the camera mode is chosen to match the size asked for.
  void Pack(const uint8_t* luma, const uint8_t* chroma, int pitch) {
    const size_t luma_size = static_cast<size_t>(out_width) * out_height;
    packed.resize(luma_size * 3 / 2);
    uint8_t* out_luma = packed.data();
    uint8_t* out_chroma = out_luma + luma_size;
    std::memset(out_luma, kBlackLuma, luma_size);
    std::memset(out_chroma, kNeutralChroma, luma_size / 2);

    const double scale = std::min(static_cast<double>(out_width) / source_width, static_cast<double>(out_height) / source_height);
    const int fitted_width = std::max(2, static_cast<int>(source_width * scale) & ~1);
    const int fitted_height = std::max(2, static_cast<int>(source_height * scale) & ~1);
    const int left = (out_width - fitted_width) / 2 & ~1;
    const int top = (out_height - fitted_height) / 2 & ~1;

    if (fitted_width == source_width && fitted_height == source_height) {
      for (int row = 0; row < fitted_height; ++row) {
        std::memcpy(out_luma + static_cast<size_t>(top + row) * out_width + left, luma + static_cast<size_t>(row) * pitch, fitted_width);
      }
      for (int row = 0; row < fitted_height / 2; ++row) {
        std::memcpy(out_chroma + static_cast<size_t>(top / 2 + row) * out_width + left, chroma + static_cast<size_t>(row) * pitch, fitted_width);
      }
      return;
    }
    for (int row = 0; row < fitted_height; ++row) {
      const uint8_t* from = luma + static_cast<size_t>(row * source_height / fitted_height) * pitch;
      uint8_t* to = out_luma + static_cast<size_t>(top + row) * out_width + left;
      for (int column = 0; column < fitted_width; ++column) to[column] = from[column * source_width / fitted_width];
    }
    const int chroma_columns = fitted_width / 2;
    for (int row = 0; row < fitted_height / 2; ++row) {
      const uint8_t* from = chroma + static_cast<size_t>(row * (source_height / 2) / (fitted_height / 2)) * pitch;
      uint8_t* to = out_chroma + static_cast<size_t>(top / 2 + row) * out_width + left;
      for (int column = 0; column < chroma_columns; ++column) {
        const int source_column = column * (source_width / 2) / chroma_columns;
        to[column * 2] = from[source_column * 2];
        to[column * 2 + 1] = from[source_column * 2 + 1];
      }
    }
  }

  bool TakeSample(IMFSample& sample) {
    ComPtr<IMFMediaBuffer> buffer;
    if (FAILED(sample.ConvertToContiguousBuffer(&buffer))) return false;
    DWORD length = 0;
    buffer->GetCurrentLength(&length);

    BYTE* data = nullptr;
    LONG pitch = source_width;
    ComPtr<IMF2DBuffer> flat;
    const bool two_dimensional = SUCCEEDED(buffer.As(&flat));
    if (two_dimensional) {
      if (FAILED(flat->Lock2D(&data, &pitch)) || pitch <= 0) return false;
    } else if (FAILED(buffer->Lock(&data, nullptr, nullptr))) {
      return false;
    }
    // The chroma plane follows the luma plane, which may be padded to a taller height than the picture.
    const int padded_height = std::max(source_height, static_cast<int>(static_cast<int64_t>(length) * 2 / 3 / pitch));
    Pack(data, data + static_cast<size_t>(pitch) * padded_height, pitch);
    if (two_dimensional) {
      flat->Unlock2D();
    } else {
      buffer->Unlock();
    }
    return true;
  }

  void RequestSample() { reader->ReadSample(kVideoStream, 0, nullptr, nullptr, nullptr, nullptr); }

  // One read is outstanding at a time, so pictures are handled one after the other.
  void OnSample(HRESULT status, DWORD flags, IMFSample* sample) {
    if (!running) return;
    if (FAILED(status) || (flags & (MF_SOURCE_READERF_ERROR | MF_SOURCE_READERF_ENDOFSTREAM))) {
      if (closed) closed();
      return;
    }
    if (sample && TakeSample(*sample)) pump.Submit(packed, NowIn100ns() - epoch);
    RequestSample();
  }
};

CameraCapturer::CameraCapturer() : impl_(std::make_unique<Impl>()) {}

CameraCapturer::~CameraCapturer() { Stop(); }

bool CameraCapturer::Start(const std::wstring& id, int width, int height, int bitrate_kbps, int64_t epoch_100ns, Sink sink,
                           std::function<void()> closed) {
  Impl& state = *impl_;
  if (state.running || FAILED(MFStartup(MF_VERSION, MFSTARTUP_LITE))) return false;
  state.media_started = true;
  state.closed = std::move(closed);
  state.epoch = epoch_100ns;
  state.out_width = StreamSide(width);
  state.out_height = StreamSide(height);

  state.source = OpenSource(id);
  if (!state.source) {
    Stop();
    return false;
  }
  state.callback = Microsoft::WRL::Make<ReadCallback>();
  state.callback->SetHandler([&state](HRESULT status, DWORD flags, IMFSample* sample) { state.OnSample(status, flags, sample); });
  state.reader = OpenReader(state.source.Get(), state.callback.Get());
  if (!state.reader) {
    Stop();
    return false;
  }
  const auto modes = ModesOf(*state.reader.Get());
  const Mode* mode = BestMode(modes, state.out_width, state.out_height);
  if (!mode || FAILED(state.reader->SetCurrentMediaType(kVideoStream, nullptr, mode->type.Get()))) {
    Stop();
    return false;
  }
  ComPtr<IMFMediaType> nv12;
  if (FAILED(MFCreateMediaType(&nv12)) || FAILED(nv12->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video)) ||
      FAILED(nv12->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12)) || FAILED(state.reader->SetCurrentMediaType(kVideoStream, nullptr, nv12.Get()))) {
    Stop();
    return false;
  }
  ComPtr<IMFMediaType> current;
  UINT32 actual_width = 0, actual_height = 0;
  if (FAILED(state.reader->GetCurrentMediaType(kVideoStream, &current)) ||
      FAILED(MFGetAttributeSize(current.Get(), MF_MT_FRAME_SIZE, &actual_width, &actual_height))) {
    Stop();
    return false;
  }
  state.source_width = static_cast<int>(actual_width);
  state.source_height = static_cast<int>(actual_height);

  if (!state.pump.Start(state.out_width, state.out_height, bitrate_kbps, epoch_100ns, std::move(sink))) {
    Stop();
    return false;
  }
  state.running = true;
  state.RequestSample();
  return true;
}

void CameraCapturer::Stop() {
  Impl& state = *impl_;
  state.running = false;
  if (state.reader && state.callback) {
    state.callback->ArmFlush();
    if (SUCCEEDED(state.reader->Flush(static_cast<DWORD>(MF_SOURCE_READER_ALL_STREAMS)))) state.callback->Flushed(kFlushTimeoutMs);
  }
  if (state.callback) state.callback->SetHandler(nullptr);
  if (state.source) state.source->Shutdown();
  state.pump.Stop();
  state.reader.Reset();
  state.callback.Reset();
  state.source.Reset();
  if (state.media_started) {
    state.media_started = false;
    MFShutdown();
  }
}

void CameraCapturer::RequestKeyframe() { impl_->pump.RequestKeyframe(); }

void CameraCapturer::SetBitrate(int bitrate_kbps) { impl_->pump.SetBitrate(bitrate_kbps); }

int CameraCapturer::width() const { return impl_->out_width; }

int CameraCapturer::height() const { return impl_->out_height; }
