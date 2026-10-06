#ifndef RUNNER_NV12_CONVERTER_H_
#define RUNNER_NV12_CONVERTER_H_

#include <d3d11.h>
#include <wrl/client.h>

#include <cstdint>
#include <vector>

// The device is multithread protected, because capture callbacks and the encoder run on different threads.
bool CreateCaptureDevice(Microsoft::WRL::ComPtr<ID3D11Device>& device, Microsoft::WRL::ComPtr<ID3D11DeviceContext>& context);

// Scales a picture into a fixed output size with black bars and reads it back as tightly packed NV12.
class Nv12Converter {
 public:
  bool Open(ID3D11Device* device, ID3D11DeviceContext* context, int out_width, int out_height, int frame_rate);
  void Close();

  bool Convert(ID3D11Texture2D* source, int width, int height, std::vector<uint8_t>& packed);

 private:
  bool EnsureProcessor(int width, int height);

  Microsoft::WRL::ComPtr<ID3D11Device> device_;
  Microsoft::WRL::ComPtr<ID3D11DeviceContext> context_;
  Microsoft::WRL::ComPtr<ID3D11VideoDevice> video_device_;
  Microsoft::WRL::ComPtr<ID3D11VideoContext> video_context_;
  Microsoft::WRL::ComPtr<ID3D11VideoProcessorEnumerator> enumerator_;
  Microsoft::WRL::ComPtr<ID3D11VideoProcessor> processor_;
  Microsoft::WRL::ComPtr<ID3D11Texture2D> nv12_;
  Microsoft::WRL::ComPtr<ID3D11VideoProcessorOutputView> output_view_;
  Microsoft::WRL::ComPtr<ID3D11Texture2D> staging_;
  int out_width_ = 0;
  int out_height_ = 0;
  int frame_rate_ = 30;
  int input_width_ = 0;
  int input_height_ = 0;
};

#endif  // RUNNER_NV12_CONVERTER_H_
