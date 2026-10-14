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

namespace {

// The colour is written premultiplied, which is what Android treats frames as.
constexpr char kSplitShader[] = R"(
Texture2D source : register(t0);
SamplerState linear_clamp : register(s0);
struct Vertex { float4 position : SV_Position; float2 uv : TEXCOORD0; };
Vertex vs(uint id : SV_VertexID) {
  Vertex v;
  v.uv = float2((id << 1) & 2, id & 2);
  v.position = float4(v.uv * float2(2, -2) + float2(-1, 1), 0, 1);
  return v;
}
struct Split { float4 color : SV_Target0; float4 grey : SV_Target1; };
Split ps(Vertex v) {
  float4 pixel = source.Sample(linear_clamp, v.uv);
  Split s;
  s.color = float4(pixel.rgb * pixel.a, 1);
  s.grey = float4(pixel.a, pixel.a, pixel.a, 1);
  return s;
}
)";

}  // namespace

bool Nv12Converter::Open(ID3D11Device* device, ID3D11DeviceContext* context, int out_width, int out_height, int frame_rate, bool alpha) {
  Close();
  device_ = device;
  context_ = context;
  out_width_ = out_width;
  out_height_ = out_height;
  frame_rate_ = frame_rate;
  frame_height_ = alpha ? out_height * 2 : out_height;
  if (FAILED(device_.As(&video_device_)) || FAILED(context_.As(&video_context_))) return false;

  D3D11_TEXTURE2D_DESC description{};
  description.Width = out_width;
  description.Height = frame_height_;
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
  if (FAILED(device_->CreateTexture2D(&description, nullptr, &staging_))) return false;
  return !alpha || OpenAlphaPass();
}

bool Nv12Converter::OpenAlphaPass() {
  ComPtr<ID3DBlob> vertex_code, pixel_code, errors;
  if (FAILED(D3DCompile(kSplitShader, sizeof(kSplitShader) - 1, nullptr, nullptr, nullptr, "vs", "vs_4_0", 0, 0, &vertex_code, &errors)) ||
      FAILED(D3DCompile(kSplitShader, sizeof(kSplitShader) - 1, nullptr, nullptr, nullptr, "ps", "ps_4_0", 0, 0, &pixel_code, &errors))) {
    return false;
  }
  if (FAILED(device_->CreateVertexShader(vertex_code->GetBufferPointer(), vertex_code->GetBufferSize(), nullptr, &split_vertex_)) ||
      FAILED(device_->CreatePixelShader(pixel_code->GetBufferPointer(), pixel_code->GetBufferSize(), nullptr, &split_pixel_))) {
    return false;
  }
  D3D11_SAMPLER_DESC sampler{};
  sampler.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
  sampler.AddressU = sampler.AddressV = sampler.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
  return SUCCEEDED(device_->CreateSamplerState(&sampler, &sampler_));
}

void Nv12Converter::Close() {
  processor_.Reset();
  enumerator_.Reset();
  output_view_.Reset();
  nv12_.Reset();
  staging_.Reset();
  split_vertex_.Reset();
  split_pixel_.Reset();
  sampler_.Reset();
  color_target_.Reset();
  grey_target_.Reset();
  color_input_.Reset();
  grey_input_.Reset();
  color_.Reset();
  grey_.Reset();
  split_width_ = 0;
  split_height_ = 0;
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

bool Nv12Converter::SplitAlpha(ID3D11Texture2D* source, int width, int height) {
  if (!color_ || width != split_width_ || height != split_height_) {
    D3D11_TEXTURE2D_DESC description{};
    description.Width = width;
    description.Height = height;
    description.MipLevels = 1;
    description.ArraySize = 1;
    description.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    description.SampleDesc.Count = 1;
    description.Usage = D3D11_USAGE_DEFAULT;
    description.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    if (FAILED(device_->CreateTexture2D(&description, nullptr, &color_)) || FAILED(device_->CreateTexture2D(&description, nullptr, &grey_)) ||
        FAILED(device_->CreateRenderTargetView(color_.Get(), nullptr, &color_target_)) ||
        FAILED(device_->CreateRenderTargetView(grey_.Get(), nullptr, &grey_target_)) || !ViewOf(color_.Get(), color_input_) ||
        !ViewOf(grey_.Get(), grey_input_)) {
      color_.Reset();
      return false;
    }
    split_width_ = width;
    split_height_ = height;
  }
  ComPtr<ID3D11ShaderResourceView> view;
  if (FAILED(device_->CreateShaderResourceView(source, nullptr, &view))) return false;

  ID3D11RenderTargetView* targets[] = {color_target_.Get(), grey_target_.Get()};
  context_->OMSetRenderTargets(2, targets, nullptr);
  const D3D11_VIEWPORT viewport{0, 0, static_cast<float>(width), static_cast<float>(height), 0, 1};
  context_->RSSetViewports(1, &viewport);
  context_->IASetInputLayout(nullptr);
  context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
  context_->VSSetShader(split_vertex_.Get(), nullptr, 0);
  context_->PSSetShader(split_pixel_.Get(), nullptr, 0);
  context_->PSSetSamplers(0, 1, sampler_.GetAddressOf());
  ID3D11ShaderResourceView* views[] = {view.Get()};
  context_->PSSetShaderResources(0, 1, views);
  context_->Draw(3, 0);

  ID3D11ShaderResourceView* none[] = {nullptr};
  context_->PSSetShaderResources(0, 1, none);
  context_->OMSetRenderTargets(0, nullptr, nullptr);
  return true;
}

bool Nv12Converter::ViewOf(ID3D11Texture2D* picture, ComPtr<ID3D11VideoProcessorInputView>& view) {
  D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC description{};
  description.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
  return SUCCEEDED(video_device_->CreateVideoProcessorInputView(picture, enumerator_.Get(), &description, &view));
}

bool Nv12Converter::ScaleInto(ID3D11VideoProcessorInputView* input, int width, int height, int half) {
  const double scale = std::min(static_cast<double>(out_width_) / width, static_cast<double>(out_height_) / height);
  const int fitted_width = std::max(2, static_cast<int>(width * scale) & ~1);
  const int fitted_height = std::max(2, static_cast<int>(height * scale) & ~1);
  const int left = (out_width_ - fitted_width) / 2 & ~1;
  const int top = (out_height_ - fitted_height) / 2 & ~1;
  const int base = half * out_height_;
  RECT from{0, 0, width, height};
  RECT to{left, base + top, left + fitted_width, base + top + fitted_height};
  RECT target{0, base, out_width_, base + out_height_};
  video_context_->VideoProcessorSetStreamSourceRect(processor_.Get(), 0, TRUE, &from);
  video_context_->VideoProcessorSetStreamDestRect(processor_.Get(), 0, TRUE, &to);
  video_context_->VideoProcessorSetOutputTargetRect(processor_.Get(), TRUE, &target);

  D3D11_VIDEO_PROCESSOR_STREAM stream{};
  stream.Enable = TRUE;
  stream.pInputSurface = input;
  return SUCCEEDED(video_context_->VideoProcessorBlt(processor_.Get(), output_view_.Get(), 0, 1, &stream));
}

bool Nv12Converter::Convert(ID3D11Texture2D* source, int width, int height, std::vector<uint8_t>& packed) {
  if (!device_ || !EnsureProcessor(width, height)) return false;
  if (frame_height_ != out_height_) {
    if (!SplitAlpha(source, width, height) || !ScaleInto(color_input_.Get(), width, height, 0) || !ScaleInto(grey_input_.Get(), width, height, 1)) return false;
  } else {
    ComPtr<ID3D11VideoProcessorInputView> input;
    if (!ViewOf(source, input) || !ScaleInto(input.Get(), width, height, 0)) return false;
  }

  context_->CopyResource(staging_.Get(), nv12_.Get());
  D3D11_MAPPED_SUBRESOURCE mapped{};
  if (FAILED(context_->Map(staging_.Get(), 0, D3D11_MAP_READ, 0, &mapped))) return false;
  packed.resize(static_cast<size_t>(out_width_) * frame_height_ * 3 / 2);
  const auto* rows = static_cast<const uint8_t*>(mapped.pData);
  // The chroma plane follows the luma plane at the row pitch of the staging texture.
  const uint8_t* chroma_rows = rows + static_cast<size_t>(mapped.RowPitch) * frame_height_;
  uint8_t* luma_out = packed.data();
  uint8_t* chroma_out = luma_out + static_cast<size_t>(out_width_) * frame_height_;
  for (int row = 0; row < frame_height_; ++row) {
    std::memcpy(luma_out + static_cast<size_t>(row) * out_width_, rows + static_cast<size_t>(row) * mapped.RowPitch, out_width_);
  }
  for (int row = 0; row < frame_height_ / 2; ++row) {
    std::memcpy(chroma_out + static_cast<size_t>(row) * out_width_, chroma_rows + static_cast<size_t>(row) * mapped.RowPitch, out_width_);
  }
  context_->Unmap(staging_.Get(), 0);
  return true;
}
