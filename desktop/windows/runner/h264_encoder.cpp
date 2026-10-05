#include "h264_encoder.h"

#include <codecapi.h>
#include <mfapi.h>
#include <mferror.h>
#include <wmcodecdsp.h>

#include <algorithm>

#include "mft.h"

using Microsoft::WRL::ComPtr;

namespace {

// Decoders need the sequence and picture parameters in front of every keyframe to join a stream late.
bool HasSequenceParameters(const std::vector<uint8_t>& data) {
  const size_t limit = std::min<size_t>(data.size(), 48);
  for (size_t i = 0; i + 3 < limit; ++i) {
    if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 && (data[i + 3] & 0x1F) == 7) return true;
  }
  return false;
}

void SetCodecValue(ICodecAPI* codec, const GUID& property, ULONG value) {
  VARIANT variant;
  VariantInit(&variant);
  variant.vt = VT_UI4;
  variant.ulVal = value;
  codec->SetValue(&property, &variant);
}

}  // namespace

bool H264Encoder::Open(int width, int height, int bitrate_kbps, int frame_rate, int keyframe_every) {
  if (FAILED(CoCreateInstance(CLSID_CMSH264EncoderMFT, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&transform_)))) return false;
  transform_.As(&codec_);
  if (codec_) {
    // Frame-parallel worker threads hold about a dozen frames back, which is 400 ms at 30 fps.
    SetCodecValue(codec_.Get(), CODECAPI_AVEncNumWorkerThreads, 1);
  }

  ComPtr<IMFMediaType> output;
  MFCreateMediaType(&output);
  output->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
  output->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
  output->SetUINT32(MF_MT_AVG_BITRATE, bitrate_kbps * 1000);
  MFSetAttributeSize(output.Get(), MF_MT_FRAME_SIZE, width, height);
  MFSetAttributeRatio(output.Get(), MF_MT_FRAME_RATE, frame_rate, 1);
  MFSetAttributeRatio(output.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
  output->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
  // Baseline never reorders frames, so the receiver can show one the moment it is decoded.
  output->SetUINT32(MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Base);
  output->SetUINT32(MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709);
  if (FAILED(transform_->SetOutputType(0, output.Get(), 0))) return false;

  ComPtr<IMFMediaType> input;
  MFCreateMediaType(&input);
  input->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
  input->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
  MFSetAttributeSize(input.Get(), MF_MT_FRAME_SIZE, width, height);
  MFSetAttributeRatio(input.Get(), MF_MT_FRAME_RATE, frame_rate, 1);
  MFSetAttributeRatio(input.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
  input->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
  input->SetUINT32(MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709);
  if (FAILED(transform_->SetInputType(0, input.Get(), 0))) return false;

  if (codec_) {
    VARIANT low_latency;
    VariantInit(&low_latency);
    low_latency.vt = VT_BOOL;
    low_latency.boolVal = VARIANT_TRUE;
    codec_->SetValue(&CODECAPI_AVLowLatencyMode, &low_latency);
    SetCodecValue(codec_.Get(), CODECAPI_AVEncCommonRateControlMode, eAVEncCommonRateControlMode_CBR);
    SetCodecValue(codec_.Get(), CODECAPI_AVEncCommonMeanBitRate, bitrate_kbps * 1000);
    SetCodecValue(codec_.Get(), CODECAPI_AVEncMPVGOPSize, keyframe_every);
    SetCodecValue(codec_.Get(), CODECAPI_AVEncMPVDefaultBPictureCount, 0);
  }

  MFT_OUTPUT_STREAM_INFO info{};
  transform_->GetOutputStreamInfo(0, &info);
  output_size_ = std::max<DWORD>(info.cbSize, 1 << 20);

  ComPtr<IMFMediaType> current;
  UINT32 length = 0;
  if (SUCCEEDED(transform_->GetOutputCurrentType(0, &current)) &&
      SUCCEEDED(current->GetBlobSize(MF_MT_MPEG_SEQUENCE_HEADER, &length)) && length > 4) {
    std::vector<uint8_t> header(length);
    if (SUCCEEDED(current->GetBlob(MF_MT_MPEG_SEQUENCE_HEADER, header.data(), length, nullptr)) && HasSequenceParameters(header)) {
      parameters_ = std::move(header);
    }
  }
  transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
  transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
  return true;
}

void H264Encoder::Close() {
  if (transform_) transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
  transform_.Reset();
  codec_.Reset();
}

void H264Encoder::Encode(const std::vector<uint8_t>& nv12, int64_t time, int64_t duration, const Sink& sink) {
  if (!transform_) return;
  auto sample = mft::MakeSample(nv12.data(), nv12.size(), time, duration);
  if (!sample) return;
  if (codec_ && force_keyframe_.exchange(false)) SetCodecValue(codec_.Get(), CODECAPI_AVEncVideoForceKeyFrame, 1);
  const int new_bitrate_kbps = pending_bitrate_kbps_.exchange(0);
  if (codec_ && new_bitrate_kbps > 0) SetCodecValue(codec_.Get(), CODECAPI_AVEncCommonMeanBitRate, new_bitrate_kbps * 1000);

  mft::Feed(*transform_.Get(), *sample.Get(), output_size_, [&](IMFSample& finished, std::vector<uint8_t>&& bytes) {
    UINT32 clean = 0;
    finished.GetUINT32(MFSampleExtension_CleanPoint, &clean);
    LONGLONG stamp = 0;
    finished.GetSampleTime(&stamp);
    EncodedVideo frame{std::move(bytes), clean != 0, stamp};
    if (frame.keyframe && !parameters_.empty() && !HasSequenceParameters(frame.data)) {
      frame.data.insert(frame.data.begin(), parameters_.begin(), parameters_.end());
    }
    sink(std::move(frame));
  }, true);
}
