#include "nv12_converter.h"

#include <algorithm>
#include <cstring>

using Microsoft::WRL::ComPtr;

bool CreateCaptureDevice(ComPtr<ID3D11Device>& device, ComPtr<ID3D11DeviceContext>& context) {
  const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
  if (FAILED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT,
                               levels, ARRAYSIZE(levels), D3D11_SDK_VERSION, &device, nullptr, &context))) {
    return false;
  }
  ComPtr<ID3D10Multithread> multithread;
  if (SUCCEEDED(device.As(&multithread))) multithread->SetMultithreadProtected(TRUE);
  return true;
}

bool Nv12Converter::Open(ID3D11Device* device, ID3D11DeviceContext* context, int out_width, int out_height, int frame_rate) {
  Close();
  device_ = device;
  context_ = context;
  out_width_ = out_width;
  out_height_ = out_height;
  frame_rate_ = frame_rate;
  if (FAILED(device_.As(&video_device_)) || FAILED(context_.As(&video_context_))) return false;

  D3D11_TEXTURE2D_DESC description{};
  description.Width = out_width;
  description.Height = out_height;
  description.MipLevels = 1;
  description.ArraySize = 1;
  description.Format = DXGI_FORMAT_NV12;
  description.SampleDesc.Count = 1;
  description.Usage = D3D11_USAGE_DEFAULT;
  description.BindFlags = D3D11_BIND_RENDER_TARGET;
  if (FAILED(device_->CreateTexture2D(&description, nullptr, &nv12_))) return false;

  description.Usage = D3D11_USAGE_STAGING;
  description.BindFlags = 0;
  description.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
  return SUCCEEDED(device_->CreateTexture2D(&description, nullptr, &staging_));
}

void Nv12Converter::Close() {
  processor_.Reset();
  enumerator_.Reset();
  output_view_.Reset();
  nv12_.Reset();
  staging_.Reset();
  video_context_.Reset();
  video_device_.Reset();
  context_.Reset();
  device_.Reset();
  input_width_ = 0;
  input_height_ = 0;
}

// The processor is made for one input size, so a source that was resized needs a new one.
bool Nv12Converter::EnsureProcessor(int width, int height) {
  if (processor_ && width == input_width_ && height == input_height_) return true;
  processor_.Reset();
  enumerator_.Reset();
  output_view_.Reset();
  D3D11_VIDEO_PROCESSOR_CONTENT_DESC content{};
  content.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
  content.InputFrameRate = {static_cast<UINT>(frame_rate_), 1};
  content.InputWidth = width;
  content.InputHeight = height;
  content.OutputFrameRate = {static_cast<UINT>(frame_rate_), 1};
  content.OutputWidth = out_width_;
  content.OutputHeight = out_height_;
  content.Usage = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;
  if (FAILED(video_device_->CreateVideoProcessorEnumerator(&content, &enumerator_))) return false;
  if (FAILED(video_device_->CreateVideoProcessor(enumerator_.Get(), 0, &processor_))) return false;
  D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC view{};
  view.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
  if (FAILED(video_device_->CreateVideoProcessorOutputView(nv12_.Get(), enumerator_.Get(), &view, &output_view_))) return false;

  D3D11_VIDEO_PROCESSOR_COLOR_SPACE rgb{};
  video_context_->VideoProcessorSetStreamColorSpace(processor_.Get(), 0, &rgb);
  D3D11_VIDEO_PROCESSOR_COLOR_SPACE yuv{};
  yuv.YCbCr_Matrix = 1;  // BT.709
  yuv.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
  video_context_->VideoProcessorSetOutputColorSpace(processor_.Get(), &yuv);
  D3D11_VIDEO_COLOR black{};
  black.YCbCr = {0.0625f, 0.5f, 0.5f, 1.0f};
  video_context_->VideoProcessorSetOutputBackgroundColor(processor_.Get(), TRUE, &black);
  input_width_ = width;
  input_height_ = height;
  return true;
}

bool Nv12Converter::Convert(ID3D11Texture2D* source, int width, int height, std::vector<uint8_t>& packed) {
  if (!device_ || !EnsureProcessor(width, height)) return false;
  D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC description{};
  description.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
  ComPtr<ID3D11VideoProcessorInputView> input;
  if (FAILED(video_device_->CreateVideoProcessorInputView(source, enumerator_.Get(), &description, &input))) return false;

  const double scale = std::min(static_cast<double>(out_width_) / width, static_cast<double>(out_height_) / height);
  const int fitted_width = std::max(2, static_cast<int>(width * scale) & ~1);
  const int fitted_height = std::max(2, static_cast<int>(height * scale) & ~1);
  const int left = (out_width_ - fitted_width) / 2 & ~1;
  const int top = (out_height_ - fitted_height) / 2 & ~1;
  RECT from{0, 0, width, height};
  RECT to{left, top, left + fitted_width, top + fitted_height};
  RECT whole{0, 0, out_width_, out_height_};
  video_context_->VideoProcessorSetStreamSourceRect(processor_.Get(), 0, TRUE, &from);
  video_context_->VideoProcessorSetStreamDestRect(processor_.Get(), 0, TRUE, &to);
  video_context_->VideoProcessorSetOutputTargetRect(processor_.Get(), TRUE, &whole);

  D3D11_VIDEO_PROCESSOR_STREAM stream{};
  stream.Enable = TRUE;
  stream.pInputSurface = input.Get();
  if (FAILED(video_context_->VideoProcessorBlt(processor_.Get(), output_view_.Get(), 0, 1, &stream))) return false;

  context_->CopyResource(staging_.Get(), nv12_.Get());
  D3D11_MAPPED_SUBRESOURCE mapped{};
  if (FAILED(context_->Map(staging_.Get(), 0, D3D11_MAP_READ, 0, &mapped))) return false;
  packed.resize(static_cast<size_t>(out_width_) * out_height_ * 3 / 2);
  const auto* rows = static_cast<const uint8_t*>(mapped.pData);
  // The chroma plane follows the luma plane at the row pitch of the staging texture.
  const uint8_t* chroma_rows = rows + static_cast<size_t>(mapped.RowPitch) * out_height_;
  uint8_t* luma_out = packed.data();
  uint8_t* chroma_out = luma_out + static_cast<size_t>(out_width_) * out_height_;
  for (int row = 0; row < out_height_; ++row) {
    std::memcpy(luma_out + static_cast<size_t>(row) * out_width_, rows + static_cast<size_t>(row) * mapped.RowPitch, out_width_);
  }
  for (int row = 0; row < out_height_ / 2; ++row) {
    std::memcpy(chroma_out + static_cast<size_t>(row) * out_width_, chroma_rows + static_cast<size_t>(row) * mapped.RowPitch, out_width_);
  }
  context_->Unmap(staging_.Get(), 0);
  return true;
}
