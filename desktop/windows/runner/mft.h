#ifndef RUNNER_MFT_H_
#define RUNNER_MFT_H_

#include <mfapi.h>
#include <mftransform.h>
#include <wrl/client.h>

#include <cstdint>
#include <functional>
#include <vector>

namespace mft {

Microsoft::WRL::ComPtr<IMFSample> MakeSample(const uint8_t* bytes, size_t size, int64_t time, int64_t duration);

using Output = std::function<void(IMFSample& sample, std::vector<uint8_t>&& bytes)>;

void Drain(IMFTransform& transform, DWORD output_size, const Output& output);

bool Feed(IMFTransform& transform, IMFSample& input, DWORD output_size, const Output& output, bool flush = false);

}  // namespace mft

#endif  // RUNNER_MFT_H_
