#include "mft.h"

#include <mferror.h>

#include <cstring>

namespace mft {

using Microsoft::WRL::ComPtr;

ComPtr<IMFSample> MakeSample(const uint8_t* bytes, size_t size, int64_t time, int64_t duration) {
  ComPtr<IMFMediaBuffer> buffer;
  if (FAILED(MFCreateMemoryBuffer(static_cast<DWORD>(size), &buffer))) return nullptr;
  BYTE* destination = nullptr;
  if (FAILED(buffer->Lock(&destination, nullptr, nullptr))) return nullptr;
  std::memcpy(destination, bytes, size);
  buffer->Unlock();
  buffer->SetCurrentLength(static_cast<DWORD>(size));
  ComPtr<IMFSample> sample;
  if (FAILED(MFCreateSample(&sample))) return nullptr;
  sample->AddBuffer(buffer.Get());
  sample->SetSampleTime(time);
  sample->SetSampleDuration(duration);
  return sample;
}

void Drain(IMFTransform& transform, DWORD output_size, const Output& output) {
  while (true) {
    ComPtr<IMFSample> sample;
    ComPtr<IMFMediaBuffer> buffer;
    MFCreateSample(&sample);
    MFCreateMemoryBuffer(output_size, &buffer);
    sample->AddBuffer(buffer.Get());
    MFT_OUTPUT_DATA_BUFFER result{};
    result.pSample = sample.Get();
    DWORD status = 0;
    const HRESULT outcome = transform.ProcessOutput(0, 1, &result, &status);
    if (result.pEvents) result.pEvents->Release();
    if (FAILED(outcome)) return;

    ComPtr<IMFMediaBuffer> contiguous;
    if (FAILED(sample->ConvertToContiguousBuffer(&contiguous))) return;
    BYTE* bytes = nullptr;
    DWORD length = 0;
    if (FAILED(contiguous->Lock(&bytes, nullptr, &length))) return;
    std::vector<uint8_t> copy(bytes, bytes + length);
    contiguous->Unlock();
    output(*sample.Get(), std::move(copy));
  }
}

bool Feed(IMFTransform& transform, IMFSample& input, DWORD output_size, const Output& output, bool flush) {
  HRESULT outcome = transform.ProcessInput(0, &input, 0);
  if (outcome == MF_E_NOTACCEPTING) {
    Drain(transform, output_size, output);
    outcome = transform.ProcessInput(0, &input, 0);
  }
  if (FAILED(outcome)) return false;
  Drain(transform, output_size, output);
  if (flush) {
    transform.ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0);
    Drain(transform, output_size, output);
  }
  return true;
}

}  // namespace mft
