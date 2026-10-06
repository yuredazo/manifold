#include "aac_encoder.h"

#include <mfapi.h>
#include <mferror.h>
#include <wmcodecdsp.h>

#include <algorithm>

#include "mft.h"

using Microsoft::WRL::ComPtr;

namespace {
constexpr int kAacBytesPerSecond = 16000;  // 128 kbit/s, one of the rates the encoder accepts
}

bool AacEncoder::Open() {
  if (FAILED(CoCreateInstance(CLSID_AACMFTEncoder, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&transform_)))) return false;

  ComPtr<IMFMediaType> output;
  MFCreateMediaType(&output);
  output->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
  output->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_AAC);
  output->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
  output->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, aac::kSampleRate);
  output->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, aac::kChannels);
  output->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, kAacBytesPerSecond);
  output->SetUINT32(MF_MT_AAC_PAYLOAD_TYPE, 0);
  output->SetUINT32(MF_MT_AAC_AUDIO_PROFILE_LEVEL_INDICATION, 0x29);
  if (FAILED(transform_->SetOutputType(0, output.Get(), 0))) return false;

  ComPtr<IMFMediaType> input;
  MFCreateMediaType(&input);
  input->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
  input->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM);
  input->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
  input->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, aac::kSampleRate);
  input->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, aac::kChannels);
  input->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, aac::kBytesPerFrame);
  input->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, aac::kSampleRate * aac::kBytesPerFrame);
  if (FAILED(transform_->SetInputType(0, input.Get(), 0))) return false;

  MFT_OUTPUT_STREAM_INFO info{};
  transform_->GetOutputStreamInfo(0, &info);
  output_size_ = std::max<DWORD>(info.cbSize, 4096);
  transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
  transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
  return true;
}

void AacEncoder::Close() { transform_.Reset(); }

void AacEncoder::Encode(const uint8_t* pcm, int64_t time, const Sink& sink) {
  if (!transform_) return;
  auto sample = mft::MakeSample(pcm, aac::kBytesPerAacFrame, time, aac::kFrameDuration100ns);
  if (!sample) return;
  mft::Feed(*transform_.Get(), *sample.Get(), output_size_, [&](IMFSample&, std::vector<uint8_t>&& bytes) {
    if (!bytes.empty()) sink(std::move(bytes));
  });
}
