#include <d3d11.h>
#include <dxgi1_2.h>
#include <sddl.h>
#include <windows.h>
#include <wrl/client.h>
#include <wtsapi32.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "secure_link.h"

using Microsoft::WRL::ComPtr;
using namespace secure_link;

namespace {

constexpr DWORD kRetryMs = 2000;
constexpr DWORD kIdleMs = 100;
constexpr DWORD kFrameMs = 16;

constexpr DWORD kMouseFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_LEFTDOWN | MOUSEEVENTF_LEFTUP | MOUSEEVENTF_RIGHTDOWN | MOUSEEVENTF_RIGHTUP |
                              MOUSEEVENTF_MIDDLEDOWN | MOUSEEVENTF_MIDDLEUP | MOUSEEVENTF_XDOWN | MOUSEEVENTF_XUP | MOUSEEVENTF_WHEEL |
                              MOUSEEVENTF_HWHEEL | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
constexpr DWORD kKeyFlags = KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP | KEYEVENTF_SCANCODE | KEYEVENTF_UNICODE;

DWORD OwnSession() {
  DWORD session = 0;
  ProcessIdToSessionId(GetCurrentProcessId(), &session);
  return session;
}

std::wstring UserSid(DWORD session) {
  HANDLE token = nullptr;
  if (!WTSQueryUserToken(session, &token)) return {};
  std::wstring sid;
  BYTE buffer[256];
  DWORD size = 0;
  if (GetTokenInformation(token, TokenUser, buffer, sizeof(buffer), &size)) {
    LPWSTR text = nullptr;
    if (ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(buffer)->User.Sid, &text)) {
      sid = text;
      LocalFree(text);
    }
  }
  CloseHandle(token);
  return sid;
}

// Only SYSTEM and the signed-in user may open the pipe. The user gets read and write-data rights but not the right to
// add pipe instances, which plain write access would include. The hub trusts the pipe only because SYSTEM owns it.
HANDLE CreatePipe(DWORD session, const std::wstring& user) {
  const std::wstring sddl = L"O:SYD:P(A;;GA;;;SY)(A;;0x0012008b;;;" + user + L")";
  PSECURITY_DESCRIPTOR descriptor = nullptr;
  if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1, &descriptor, nullptr)) return INVALID_HANDLE_VALUE;
  SECURITY_ATTRIBUTES attributes{sizeof(attributes), descriptor, FALSE};
  HANDLE pipe = CreateNamedPipeW(PipeName(session).c_str(), PIPE_ACCESS_DUPLEX | FILE_FLAG_FIRST_PIPE_INSTANCE | FILE_FLAG_OVERLAPPED,
                                 PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS, 1, kPipeBuffer, kPipeBuffer, 0,
                                 &attributes);
  LocalFree(descriptor);
  return pipe;
}

bool ReadExactly(HANDLE pipe, void* into, DWORD size) { return Transfer(pipe, into, size, false); }

bool WaitForClient(HANDLE pipe) {
  OVERLAPPED overlapped{};
  overlapped.hEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  if (!overlapped.hEvent) return false;
  DWORD done = 0;
  const bool connected = ConnectNamedPipe(pipe, &overlapped) || GetLastError() == ERROR_PIPE_CONNECTED ||
                         (GetLastError() == ERROR_IO_PENDING && GetOverlappedResult(pipe, &overlapped, &done, TRUE));
  CloseHandle(overlapped.hEvent);
  return connected;
}

std::wstring InputDesktopName(HDESK* opened) {
  HDESK desktop = OpenInputDesktop(0, FALSE, GENERIC_ALL);
  if (!desktop) return {};
  wchar_t name[64]{};
  DWORD needed = 0;
  GetUserObjectInformationW(desktop, UOI_NAME, name, sizeof(name) - sizeof(wchar_t), &needed);
  if (opened) {
    *opened = desktop;
  } else {
    CloseDesktop(desktop);
  }
  return name;
}

// The thread's desktop has to be the one in front for Desktop Duplication to see it and for SendInput to reach it.
class DesktopFollower {
 public:
  ~DesktopFollower() {
    if (desktop_) CloseDesktop(desktop_);
  }

  bool Follow() {
    HDESK desktop = nullptr;
    const std::wstring name = InputDesktopName(&desktop);
    if (!desktop) return false;
    if (name == name_) {
      CloseDesktop(desktop);
      return false;
    }
    if (!SetThreadDesktop(desktop)) {
      CloseDesktop(desktop);
      return false;
    }
    if (desktop_) CloseDesktop(desktop_);
    desktop_ = desktop;
    name_ = name;
    return true;
  }

 private:
  HDESK desktop_ = nullptr;
  std::wstring name_;
};

struct Duplication {
  RECT monitor{};
  ComPtr<ID3D11Device> device;
  ComPtr<ID3D11DeviceContext> context;
  ComPtr<IDXGIOutputDuplication> output;
  ComPtr<ID3D11Texture2D> staging;
  int width = 0;
  int height = 0;
};

bool Duplicate(const RECT& monitor, Duplication& into) {
  ComPtr<IDXGIFactory1> factory;
  if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) return false;
  ComPtr<IDXGIAdapter1> adapter;
  for (UINT a = 0; factory->EnumAdapters1(a, &adapter) != DXGI_ERROR_NOT_FOUND; ++a) {
    ComPtr<IDXGIOutput> output;
    for (UINT o = 0; adapter->EnumOutputs(o, &output) != DXGI_ERROR_NOT_FOUND; ++o) {
      DXGI_OUTPUT_DESC description{};
      if (FAILED(output->GetDesc(&description)) || !SameRect(description.DesktopCoordinates, monitor)) continue;
      ComPtr<IDXGIOutput1> output1;
      if (FAILED(output.As(&output1))) return false;
      if (FAILED(D3D11CreateDevice(adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION, &into.device, nullptr,
                                   &into.context))) {
        return false;
      }
      if (FAILED(output1->DuplicateOutput(into.device.Get(), &into.output))) return false;
      DXGI_OUTDUPL_DESC duplicated{};
      into.output->GetDesc(&duplicated);
      into.width = static_cast<int>(duplicated.ModeDesc.Width);
      into.height = static_cast<int>(duplicated.ModeDesc.Height);
      into.monitor = monitor;
      D3D11_TEXTURE2D_DESC staging{};
      staging.Width = duplicated.ModeDesc.Width;
      staging.Height = duplicated.ModeDesc.Height;
      staging.MipLevels = 1;
      staging.ArraySize = 1;
      staging.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
      staging.SampleDesc.Count = 1;
      staging.Usage = D3D11_USAGE_STAGING;
      staging.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
      return SUCCEEDED(into.device->CreateTexture2D(&staging, nullptr, &into.staging));
    }
  }
  return false;
}

struct SharedSlot {
  RECT monitor{};
  HANDLE section = nullptr;
  uint8_t* view = nullptr;
  int width = 0;
  int height = 0;

  void Release() {
    if (view) UnmapViewOfFile(view);
    if (section) CloseHandle(section);
    view = nullptr;
    section = nullptr;
  }
};

class Helper {
 public:
  explicit Helper(std::string version) : version_(std::move(version)) {}

  void Serve(HANDLE pipe) {
    ULONG process_id = 0;
    if (!GetNamedPipeClientProcessId(pipe, &process_id)) return;
    HANDLE client = OpenProcess(PROCESS_DUP_HANDLE, FALSE, process_id);
    if (!client) return;
    {
      std::lock_guard<std::mutex> guard(lock_);
      pipe_ = pipe;
      client_ = client;
    }
    Hello hello{kProtocol, {}};
    strncpy_s(hello.version, version_.c_str(), _TRUNCATE);
    Desktop desktop{secure_ ? 1u : 0u};
    if (Send(Kind::kHello, &hello, sizeof(hello)) && Send(Kind::kDesktop, &desktop, sizeof(desktop))) Read(pipe);
    std::lock_guard<std::mutex> guard(lock_);
    pipe_ = nullptr;
    CloseHandle(client_);
    client_ = nullptr;
    watched_.clear();
    resend_slots_ = true;
  }

  void CaptureLoop() {
    DesktopFollower follower;
    std::vector<Duplication> duplications;
    std::vector<SharedSlot> slots;
    while (true) {
      const std::wstring name = InputDesktopName(nullptr);
      const bool secure = !name.empty() && _wcsicmp(name.c_str(), L"Default") != 0;
      if (secure != secure_.exchange(secure)) {
        Desktop desktop{secure ? 1u : 0u};
        Send(Kind::kDesktop, &desktop, sizeof(desktop));
      }
      std::vector<RECT> watched;
      bool resend = false;
      {
        std::lock_guard<std::mutex> guard(lock_);
        watched = watched_;
        resend = std::exchange(resend_slots_, false);
      }
      if (!secure || watched.empty()) {
        duplications.clear();
        Sleep(kIdleMs);
        continue;
      }
      if (follower.Follow()) duplications.clear();
      if (resend) {
        for (auto& slot : slots) slot.Release();
        slots.clear();
      }
      for (size_t i = 0; i < watched.size(); ++i) {
        if (i >= duplications.size() || !SameRect(duplications[i].monitor, watched[i])) {
          duplications.resize(i);
          Duplication fresh;
          if (!Duplicate(watched[i], fresh)) break;
          duplications.push_back(std::move(fresh));
        }
        if (i >= slots.size()) slots.resize(i + 1);
        if (!CopyFrame(duplications[i], slots[i], static_cast<uint32_t>(i))) duplications.resize(i);
      }
      Sleep(kFrameMs);
    }
  }

 private:
  bool Send(Kind kind, const void* body, uint32_t size) {
    std::lock_guard<std::mutex> guard(lock_);
    return pipe_ && WriteMessage(pipe_, kind, body, size);
  }

  void Read(HANDLE pipe) {
    DesktopFollower follower;
    Header header{};
    while (ReadExactly(pipe, &header, sizeof(header))) {
      if (header.kind == static_cast<uint32_t>(Kind::kWatch) && header.size == sizeof(Watch)) {
        Watch watch{};
        if (!ReadExactly(pipe, &watch, sizeof(watch)) || watch.count > kMaxWatched) return;
        std::lock_guard<std::mutex> guard(lock_);
        watched_.assign(watch.monitors, watch.monitors + watch.count);
        resend_slots_ = true;
      } else if (header.kind == static_cast<uint32_t>(Kind::kInput) && header.size == sizeof(Input)) {
        Input input{};
        if (!ReadExactly(pipe, &input, sizeof(input))) return;
        Inject(input, follower);
      } else {
        return;
      }
    }
  }

  // Only while a secure desktop is in front: on the user's own desktop the hub sends input itself, and a SYSTEM process
  // injecting there would reach programs the user runs as administrator.
  void Inject(const Input& input, DesktopFollower& follower) {
    if (!secure_) return;
    follower.Follow();
    INPUT entry{};
    if (input.type == INPUT_MOUSE) {
      entry.type = INPUT_MOUSE;
      entry.mi.dx = input.dx;
      entry.mi.dy = input.dy;
      entry.mi.mouseData = input.data;
      entry.mi.dwFlags = input.flags & kMouseFlags;
    } else if (input.type == INPUT_KEYBOARD) {
      entry.type = INPUT_KEYBOARD;
      entry.ki.wScan = input.scan;
      entry.ki.dwFlags = input.flags & kKeyFlags;
    } else {
      return;
    }
    SendInput(1, &entry, sizeof(entry));
  }

  bool CopyFrame(Duplication& duplication, SharedSlot& slot, uint32_t index) {
    DXGI_OUTDUPL_FRAME_INFO info{};
    ComPtr<IDXGIResource> resource;
    const HRESULT acquired = duplication.output->AcquireNextFrame(0, &info, &resource);
    if (acquired == DXGI_ERROR_WAIT_TIMEOUT) return true;
    if (FAILED(acquired)) return false;
    const bool fresh = info.LastPresentTime.QuadPart != 0 || !slot.view;
    ComPtr<ID3D11Texture2D> texture;
    if (fresh && SUCCEEDED(resource.As(&texture))) {
      duplication.context->CopyResource(duplication.staging.Get(), texture.Get());
      Publish(duplication, slot, index);
    }
    duplication.output->ReleaseFrame();
    return true;
  }

  void Publish(Duplication& duplication, SharedSlot& slot, uint32_t index) {
    const uint32_t stride = static_cast<uint32_t>(duplication.width) * 4;
    if (!slot.view || slot.width != duplication.width || slot.height != duplication.height || !SameRect(slot.monitor, duplication.monitor)) {
      slot.Release();
      const uint64_t size = sizeof(FrameHeader) + uint64_t{stride} * static_cast<uint64_t>(duplication.height);
      slot.section = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, static_cast<DWORD>(size >> 32), static_cast<DWORD>(size), nullptr);
      if (!slot.section) return;
      slot.view = static_cast<uint8_t*>(MapViewOfFile(slot.section, FILE_MAP_WRITE, 0, 0, 0));
      if (!slot.view) return slot.Release();
      slot.width = duplication.width;
      slot.height = duplication.height;
      slot.monitor = duplication.monitor;
      if (!Offer(slot, index, stride)) return slot.Release();
    }
    D3D11_MAPPED_SUBRESOURCE mapped{};
    if (FAILED(duplication.context->Map(duplication.staging.Get(), 0, D3D11_MAP_READ, 0, &mapped))) return;
    auto* header = reinterpret_cast<FrameHeader*>(slot.view);
    InterlockedIncrement64(&header->sequence);
    uint8_t* rows = slot.view + sizeof(FrameHeader);
    for (int y = 0; y < slot.height; ++y) {
      memcpy(rows + size_t{stride} * y, static_cast<const uint8_t*>(mapped.pData) + size_t{mapped.RowPitch} * y, stride);
    }
    InterlockedIncrement64(&header->sequence);
    duplication.context->Unmap(duplication.staging.Get(), 0);
  }

  // The section is handed to the hub read-only, as a handle already valid inside the hub's process.
  bool Offer(const SharedSlot& slot, uint32_t index, uint32_t stride) {
    HANDLE remote = nullptr;
    {
      std::lock_guard<std::mutex> guard(lock_);
      if (!client_ || !DuplicateHandle(GetCurrentProcess(), slot.section, client_, &remote, FILE_MAP_READ, FALSE, 0)) return false;
    }
    Slot message{index, reinterpret_cast<uint64_t>(remote), slot.monitor, slot.width, slot.height, stride};
    return Send(Kind::kSlot, &message, sizeof(message));
  }

  const std::string version_;
  std::mutex lock_;
  HANDLE pipe_ = nullptr;
  HANDLE client_ = nullptr;
  std::vector<RECT> watched_;
  bool resend_slots_ = false;
  std::atomic<bool> secure_{false};
};

}  // namespace

extern "C" int32_t secure_helper_run(const char* version) {
  const DWORD session = OwnSession();
  auto helper = std::make_unique<Helper>(version ? version : "");
  std::thread capture([&helper] { helper->CaptureLoop(); });
  capture.detach();
  while (true) {
    const std::wstring user = UserSid(session);
    HANDLE pipe = user.empty() ? INVALID_HANDLE_VALUE : CreatePipe(session, user);
    if (pipe == INVALID_HANDLE_VALUE) {
      Sleep(kRetryMs);
      continue;
    }
    ULONG client_session = 0;
    if (WaitForClient(pipe) && GetNamedPipeClientSessionId(pipe, &client_session) && client_session == session) helper->Serve(pipe);
    DisconnectNamedPipe(pipe);
    CloseHandle(pipe);
  }
}
