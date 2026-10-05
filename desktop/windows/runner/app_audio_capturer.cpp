#include "app_audio_capturer.h"

#include <audioclient.h>
#include <audioclientactivationparams.h>
#include <mfapi.h>
#include <mmdeviceapi.h>
#include <wrl/client.h>
#include <wrl/implements.h>

#include <atomic>
#include <cstring>
#include <future>
#include <memory>
#include <thread>

#include "aac_encoder.h"

using Microsoft::WRL::ComPtr;

namespace {

constexpr int64_t kSilenceGap100ns = 100 * 10'000LL;

class ActivationDone : public Microsoft::WRL::RuntimeClass<Microsoft::WRL::RuntimeClassFlags<Microsoft::WRL::ClassicCom>,
                                                           IActivateAudioInterfaceCompletionHandler, IAgileObject> {
 public:
  HRESULT STDMETHODCALLTYPE ActivateCompleted(IActivateAudioInterfaceAsyncOperation*) override {
    SetEvent(event_);
    return S_OK;
  }

  ~ActivationDone() override { CloseHandle(event_); }

  HANDLE event_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
};

}  // namespace

struct AppAudioCapturer::Impl {
  Sink sink;
  int64_t epoch = 0;
  std::atomic<bool> running{false};
  std::thread worker;
  bool media_started = false;

  ComPtr<IAudioClient> client;
  ComPtr<IAudioCaptureClient> capture;
  HANDLE packet_ready = nullptr;
  AacEncoder encoder;

  // Where the next AAC frame sits in time. It restarts whenever the application was silent,
  // because Windows delivers nothing then and counting samples alone would fall behind.
  std::vector<uint8_t> pending;
  int64_t segment_start = 0;
  int64_t segment_frames = 0;

  int64_t NextFrameTime() const { return segment_start + segment_frames * aac::kFrameDuration100ns; }

  bool Activate(DWORD process_id) {
    AUDIOCLIENT_ACTIVATION_PARAMS parameters{};
    parameters.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
    parameters.ProcessLoopbackParams.TargetProcessId = process_id;
    parameters.ProcessLoopbackParams.ProcessLoopbackMode = PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE;
    PROPVARIANT activation{};
    activation.vt = VT_BLOB;
    activation.blob.cbSize = sizeof(parameters);
    activation.blob.pBlobData = reinterpret_cast<BYTE*>(&parameters);

    ComPtr<ActivationDone> done = Microsoft::WRL::Make<ActivationDone>();
    ComPtr<IActivateAudioInterfaceAsyncOperation> operation;
    if (FAILED(ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, __uuidof(IAudioClient), &activation,
                                           done.Get(), &operation))) {
      return false;
    }
    if (WaitForSingleObject(done->event_, 5000) != WAIT_OBJECT_0) return false;
    HRESULT result = E_FAIL;
    ComPtr<IUnknown> unknown;
    if (FAILED(operation->GetActivateResult(&result, &unknown)) || FAILED(result)) return false;
    if (FAILED(unknown.As(&client))) return false;

    WAVEFORMATEX format{};
    format.wFormatTag = WAVE_FORMAT_PCM;
    format.nChannels = aac::kChannels;
    format.nSamplesPerSec = aac::kSampleRate;
    format.wBitsPerSample = 16;
    format.nBlockAlign = aac::kBytesPerFrame;
    format.nAvgBytesPerSec = aac::kSampleRate * aac::kBytesPerFrame;
    if (FAILED(client->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
                                  200000, 0, &format, nullptr))) {
      return false;
    }
    packet_ready = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (FAILED(client->SetEventHandle(packet_ready))) return false;
    return SUCCEEDED(client->GetService(IID_PPV_ARGS(&capture)));
  }

  void Run(std::promise<bool>& started, DWORD process_id) {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    const bool ready = Activate(process_id) && encoder.Open() && SUCCEEDED(client->Start());
    started.set_value(ready);
    if (ready) {
      while (running) {
        WaitForSingleObject(packet_ready, 100);
        ReadPackets();
      }
      client->Stop();
    }
    capture.Reset();
    client.Reset();
    encoder.Close();
    if (packet_ready) CloseHandle(packet_ready);
    CoUninitialize();
  }

  void ReadPackets() {
    UINT32 frames = 0;
    while (running && SUCCEEDED(capture->GetNextPacketSize(&frames)) && frames > 0) {
      BYTE* data = nullptr;
      DWORD flags = 0;
      UINT64 position = 0, time = 0;
      if (FAILED(capture->GetBuffer(&data, &frames, &flags, &position, &time))) return;
      const size_t bytes = static_cast<size_t>(frames) * aac::kBytesPerFrame;
      StartSegmentIfNeeded(static_cast<int64_t>(time));
      const size_t before = pending.size();
      pending.resize(before + bytes);
      if (flags & AUDCLNT_BUFFERFLAGS_SILENT) {
        std::memset(pending.data() + before, 0, bytes);
      } else {
        std::memcpy(pending.data() + before, data, bytes);
      }
      capture->ReleaseBuffer(frames);
      EncodePending();
    }
  }

  void StartSegmentIfNeeded(int64_t packet_time) {
    if (!pending.empty()) return;
    const bool gap = segment_frames > 0 && packet_time - NextFrameTime() > kSilenceGap100ns;
    if (segment_frames == 0 || gap) {
      segment_start = packet_time;
      segment_frames = 0;
    }
  }

  void EncodePending() {
    while (pending.size() >= aac::kBytesPerAacFrame) {
      encoder.Encode(pending.data(), NextFrameTime() - epoch, [this](std::vector<uint8_t> frame) {
        // The encoder keeps the order of what went in, so frames are timed by counting them.
        EncodedAudio audio{std::move(frame), NextFrameTime() - epoch};
        ++segment_frames;
        if (running && sink) sink(std::move(audio));
      });
      pending.erase(pending.begin(), pending.begin() + aac::kBytesPerAacFrame);
    }
  }
};

AppAudioCapturer::AppAudioCapturer() : impl_(std::make_unique<Impl>()) {}

AppAudioCapturer::~AppAudioCapturer() { Stop(); }

bool AppAudioCapturer::Start(DWORD process_id, int64_t epoch_100ns, Sink sink) {
  Impl& state = *impl_;
  if (state.running) return false;
  if (FAILED(MFStartup(MF_VERSION, MFSTARTUP_LITE))) return false;
  state.media_started = true;
  state.sink = std::move(sink);
  state.epoch = epoch_100ns;
  state.running = true;
  auto started = std::make_shared<std::promise<bool>>();
  auto outcome = started->get_future();
  state.worker = std::thread([&state, started, process_id] { state.Run(*started, process_id); });
  if (outcome.get()) return true;
  Stop();
  return false;
}

void AppAudioCapturer::Stop() {
  Impl& state = *impl_;
  state.running = false;
  if (state.worker.joinable()) state.worker.join();
  if (state.media_started) {
    state.media_started = false;
    MFShutdown();
  }
}
