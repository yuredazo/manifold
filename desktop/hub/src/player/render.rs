use super::decoder::{Picture, PictureData};
use windows::Win32::Foundation::HWND;
use windows::Win32::Graphics::Direct3D::Fxc::D3DCompile;
use windows::Win32::Graphics::Direct3D::*;
use windows::Win32::Graphics::Direct3D11::*;
use windows::Win32::Graphics::Dxgi::Common::*;
use windows::Win32::Graphics::Dxgi::*;
use windows::core::{Interface, PCSTR, Result, s};

// The stacked layout of a picture with alpha: the top half is the colour, premultiplied, and the bottom half is the alpha as grey.
// It is joined here and drawn over a checkerboard, so transparent parts stay visible as such.
const SHADER: &str = r#"
Texture2D<float> luma : register(t0);
Texture2D<float2> chroma : register(t1);
SamplerState smooth : register(s0);
cbuffer Frame : register(b0) { float2 scale; float kr; float kb; float stacked; float fullRange; float2 unused; };
struct Out { float4 position : SV_Position; float2 uv : TEXCOORD0; };
Out vs(uint id : SV_VertexID) {
    Out o;
    float2 uv = float2((id << 1) & 2, id & 2);
    o.position = float4(uv * float2(2, -2) + float2(-1, 1), 0, 1);
    o.uv = uv;
    return o;
}
float level(float y) { return fullRange > 0.5 ? y : (y - 16.0 / 255.0) * (255.0 / 219.0); }
float3 rgb(float2 uv) {
    float y = level(luma.Sample(smooth, uv));
    float2 c = chroma.Sample(smooth, uv) - 0.5;
    if (fullRange < 0.5) c *= 255.0 / 224.0;
    float r = y + 2.0 * (1.0 - kr) * c.y;
    float b = y + 2.0 * (1.0 - kb) * c.x;
    float g = (y - kr * r - kb * b) / (1.0 - kr - kb);
    return saturate(float3(r, g, b));
}
float4 ps(Out i) : SV_Target {
    if (stacked < 0.5) return float4(rgb(i.uv * scale), 1);
    float3 color = rgb(float2(i.uv.x, i.uv.y * 0.5) * scale);
    float alpha = saturate(level(luma.Sample(smooth, float2(i.uv.x, 0.5 + i.uv.y * 0.5) * scale)));
    float2 cell = floor(i.position.xy / 12.0);
    float backdrop = lerp(0.353, 0.227, fmod(cell.x + cell.y, 2.0));
    return float4(saturate(color + backdrop * (1.0 - alpha)), 1);
}
"#;

struct Planes {
    luma: ID3D11ShaderResourceView,
    chroma: ID3D11ShaderResourceView,
    textures: Vec<ID3D11Texture2D>,
    size: (u32, u32),
    hardware: bool,
}

pub struct Renderer {
    pub device: ID3D11Device,
    context: ID3D11DeviceContext,
    swap: IDXGISwapChain1,
    target: Option<ID3D11RenderTargetView>,
    vertex: ID3D11VertexShader,
    pixel: ID3D11PixelShader,
    sampler: ID3D11SamplerState,
    constants: ID3D11Buffer,
    planes: Option<Planes>,
    shown: (u32, u32),
    stacked: bool,
}

fn compile(entry: PCSTR, target: PCSTR) -> Result<Vec<u8>> {
    let mut code = None;
    let mut errors = None;
    unsafe { D3DCompile(SHADER.as_ptr().cast(), SHADER.len(), None, None, None, entry, target, 0, 0, &mut code, Some(&mut errors))? };
    let code = code.unwrap();
    Ok(unsafe { std::slice::from_raw_parts(code.GetBufferPointer() as *const u8, code.GetBufferSize()) }.to_vec())
}

impl Renderer {
    pub fn new(window: HWND, stacked: bool) -> Result<Self> {
        let mut device = None;
        let mut context = None;
        let flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT;
        let levels = [D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0];
        unsafe {
            D3D11CreateDevice(None, D3D_DRIVER_TYPE_HARDWARE, Default::default(), flags, Some(&levels), D3D11_SDK_VERSION, Some(&mut device), None, Some(&mut context))?
        };
        let device: ID3D11Device = device.unwrap();
        let context = context.unwrap();
        // The decoder uses the device from its own threads.
        if let Ok(multithread) = device.cast::<ID3D11Multithread>() {
            unsafe {
                let _ = multithread.SetMultithreadProtected(true);
            }
        }
        let factory: IDXGIFactory2 = unsafe { device.cast::<IDXGIDevice>()?.GetAdapter()?.GetParent()? };
        let description = DXGI_SWAP_CHAIN_DESC1 {
            Format: DXGI_FORMAT_B8G8R8A8_UNORM,
            SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
            BufferUsage: DXGI_USAGE_RENDER_TARGET_OUTPUT,
            BufferCount: 2,
            Scaling: DXGI_SCALING_STRETCH,
            SwapEffect: DXGI_SWAP_EFFECT_FLIP_DISCARD,
            AlphaMode: DXGI_ALPHA_MODE_IGNORE,
            ..Default::default()
        };
        let swap = unsafe { factory.CreateSwapChainForHwnd(&device, window, &description, None, None)? };
        unsafe {
            let _ = factory.MakeWindowAssociation(window, DXGI_MWA_NO_ALT_ENTER);
        }
        let vertex_code = compile(s!("vs"), s!("vs_4_0"))?;
        let pixel_code = compile(s!("ps"), s!("ps_4_0"))?;
        let mut vertex = None;
        let mut pixel = None;
        let mut sampler = None;
        let mut constants = None;
        unsafe {
            device.CreateVertexShader(&vertex_code, None, Some(&mut vertex))?;
            device.CreatePixelShader(&pixel_code, None, Some(&mut pixel))?;
            let sampling = D3D11_SAMPLER_DESC {
                Filter: D3D11_FILTER_MIN_MAG_MIP_LINEAR,
                AddressU: D3D11_TEXTURE_ADDRESS_CLAMP,
                AddressV: D3D11_TEXTURE_ADDRESS_CLAMP,
                AddressW: D3D11_TEXTURE_ADDRESS_CLAMP,
                MaxLOD: f32::MAX,
                ..Default::default()
            };
            device.CreateSamplerState(&sampling, Some(&mut sampler))?;
            let buffer = D3D11_BUFFER_DESC {
                ByteWidth: 32,
                Usage: D3D11_USAGE_DEFAULT,
                BindFlags: D3D11_BIND_CONSTANT_BUFFER.0 as u32,
                ..Default::default()
            };
            device.CreateBuffer(&buffer, None, Some(&mut constants))?;
        }
        let mut renderer = Self {
            device,
            context,
            swap,
            target: None,
            vertex: vertex.unwrap(),
            pixel: pixel.unwrap(),
            sampler: sampler.unwrap(),
            constants: constants.unwrap(),
            planes: None,
            shown: (0, 0),
            stacked,
        };
        renderer.make_target()?;
        Ok(renderer)
    }

    fn make_target(&mut self) -> Result<()> {
        let back: ID3D11Texture2D = unsafe { self.swap.GetBuffer(0)? };
        let mut target = None;
        unsafe { self.device.CreateRenderTargetView(&back, None, Some(&mut target))? };
        self.target = target;
        Ok(())
    }

    pub fn resize(&mut self) -> Result<()> {
        self.target = None;
        unsafe {
            self.context.OMSetRenderTargets(None, None);
            self.swap.ResizeBuffers(0, 0, 0, DXGI_FORMAT_UNKNOWN, DXGI_SWAP_CHAIN_FLAG(0))?;
        }
        self.make_target()
    }

    fn planes_for(&mut self, size: (u32, u32), hardware: bool) -> Result<&Planes> {
        let fits = self.planes.as_ref().is_some_and(|planes| planes.size == size && planes.hardware == hardware);
        if !fits {
            self.planes = Some(if hardware { self.nv12_planes(size)? } else { self.split_planes(size)? });
        }
        Ok(self.planes.as_ref().unwrap())
    }

    fn texture(&self, width: u32, height: u32, format: DXGI_FORMAT) -> Result<ID3D11Texture2D> {
        let description = D3D11_TEXTURE2D_DESC {
            Width: width,
            Height: height,
            MipLevels: 1,
            ArraySize: 1,
            Format: format,
            SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
            Usage: D3D11_USAGE_DEFAULT,
            BindFlags: D3D11_BIND_SHADER_RESOURCE.0 as u32,
            ..Default::default()
        };
        let mut texture = None;
        unsafe { self.device.CreateTexture2D(&description, None, Some(&mut texture))? };
        Ok(texture.unwrap())
    }

    fn view(&self, texture: &ID3D11Texture2D, format: DXGI_FORMAT) -> Result<ID3D11ShaderResourceView> {
        let description = D3D11_SHADER_RESOURCE_VIEW_DESC {
            Format: format,
            ViewDimension: D3D11_SRV_DIMENSION_TEXTURE2D,
            Anonymous: D3D11_SHADER_RESOURCE_VIEW_DESC_0 { Texture2D: D3D11_TEX2D_SRV { MostDetailedMip: 0, MipLevels: 1 } },
        };
        let mut view = None;
        unsafe { self.device.CreateShaderResourceView(texture, Some(&description), Some(&mut view))? };
        Ok(view.unwrap())
    }

    // One NV12 texture, read as its two planes: D3D11 picks the plane from the view's format.
    fn nv12_planes(&self, size: (u32, u32)) -> Result<Planes> {
        let texture = self.texture(size.0, size.1, DXGI_FORMAT_NV12)?;
        Ok(Planes {
            luma: self.view(&texture, DXGI_FORMAT_R8_UNORM)?,
            chroma: self.view(&texture, DXGI_FORMAT_R8G8_UNORM)?,
            textures: vec![texture],
            size,
            hardware: true,
        })
    }

    fn split_planes(&self, size: (u32, u32)) -> Result<Planes> {
        let luma = self.texture(size.0, size.1, DXGI_FORMAT_R8_UNORM)?;
        let chroma = self.texture(size.0.div_ceil(2), size.1.div_ceil(2), DXGI_FORMAT_R8G8_UNORM)?;
        Ok(Planes {
            luma: self.view(&luma, DXGI_FORMAT_R8_UNORM)?,
            chroma: self.view(&chroma, DXGI_FORMAT_R8G8_UNORM)?,
            textures: vec![luma, chroma],
            size,
            hardware: false,
        })
    }

    pub fn upload(&mut self, picture: &Picture) -> Result<()> {
        let format = &picture.format;
        let hardware = matches!(picture.data, PictureData::Texture { .. });
        let size = match &picture.data {
            PictureData::Texture { texture, .. } => {
                let mut description = D3D11_TEXTURE2D_DESC::default();
                unsafe { texture.GetDesc(&mut description) };
                (description.Width, description.Height)
            }
            PictureData::Memory { .. } => format.coded,
        };
        let planes = self.planes_for(size, hardware)?;
        let textures = planes.textures.clone();
        match &picture.data {
            PictureData::Texture { texture, index } => unsafe {
                self.context.CopySubresourceRegion(&textures[0], 0, 0, 0, 0, texture, *index, None);
            },
            PictureData::Memory { bytes, pitch } => unsafe {
                let rows = format.coded.1 as usize;
                self.context.UpdateSubresource(&textures[0], 0, None, bytes.as_ptr().cast(), *pitch, 0);
                let chroma = &bytes[(*pitch as usize * rows).min(bytes.len())..];
                self.context.UpdateSubresource(&textures[1], 0, None, chroma.as_ptr().cast(), *pitch, 0);
            },
        }
        self.shown = format.shown;
        let scale = (format.shown.0 as f32 / size.0 as f32, format.shown.1 as f32 / size.1 as f32);
        let constants: [f32; 8] = [scale.0, scale.1, format.kr, format.kb, if self.stacked { 1.0 } else { 0.0 }, if format.full_range { 1.0 } else { 0.0 }, 0.0, 0.0];
        unsafe { self.context.UpdateSubresource(&self.constants, 0, None, constants.as_ptr().cast(), 0, 0) };
        Ok(())
    }

    pub fn draw(&mut self) -> Result<()> {
        let Some(target) = self.target.clone() else { return Ok(()) };
        let description = unsafe { self.swap.GetDesc1()? };
        let (width, height) = (description.Width as f32, description.Height as f32);
        unsafe {
            self.context.ClearRenderTargetView(&target, &[0.0, 0.0, 0.0, 1.0]);
            if let Some(planes) = &self.planes
                && self.shown.0 > 0
                && self.shown.1 > 0
            {
                let picture_height = if self.stacked { self.shown.1 as f32 / 2.0 } else { self.shown.1 as f32 };
                let aspect = self.shown.0 as f32 / picture_height;
                let (fit_width, fit_height) = if width / height > aspect { (height * aspect, height) } else { (width, width / aspect) };
                let viewport = D3D11_VIEWPORT {
                    TopLeftX: ((width - fit_width) / 2.0).round(),
                    TopLeftY: ((height - fit_height) / 2.0).round(),
                    Width: fit_width.round(),
                    Height: fit_height.round(),
                    MinDepth: 0.0,
                    MaxDepth: 1.0,
                };
                self.context.RSSetViewports(Some(&[viewport]));
                self.context.OMSetRenderTargets(Some(&[Some(target.clone())]), None);
                self.context.IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
                self.context.VSSetShader(&self.vertex, None);
                self.context.PSSetShader(&self.pixel, None);
                self.context.PSSetShaderResources(0, Some(&[Some(planes.luma.clone()), Some(planes.chroma.clone())]));
                self.context.PSSetSamplers(0, Some(&[Some(self.sampler.clone())]));
                self.context.PSSetConstantBuffers(0, Some(&[Some(self.constants.clone())]));
                self.context.Draw(3, 0);
            }
            self.swap.Present(1, DXGI_PRESENT(0)).ok()?;
        }
        Ok(())
    }
}
