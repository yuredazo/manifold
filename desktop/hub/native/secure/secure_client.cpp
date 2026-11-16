#include "secure_client.h"

#include <aclapi.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

#include "secure_link.h"

using namespace secure_link;

extern "C" int32_t secure_service_state();

namespace {

constexpr DWORD kRetryMs = 2000;
constexpr int kMaxSide = 16384;

struct Mapped {
  RECT monitor{};
  HANDLE section = nullptr;
  const uint8_t* view = nullptr;
  int width = 0;
  int height = 0;
  uint32_t stride = 0;

  void Release() {
    if (view) UnmapViewOfFile(view);
    if (section) CloseHandle(section);
    *this = Mapped{};
  }
};

struct Wanted {
  RECT monitor;
  int count;
};

std::mutex lock;
std::once_flag started;
std::vector<Wanted> wanted;
Mapped slots[kMaxWatched];
HANDLE pipe = nullptr;
std::string helper_version;
std::atomic<bool> showing{false};

void ReleaseSlots() {
  for (auto& slot : slots) slot.Release();
}

// The helper starts its pictures over for every Watch, so the old ones are dropped with it.
void SendWatch() {
  if (!pipe) return;
  Watch watch{};
  for (const auto& entry : wanted) {
    if (watch.count == kMaxWatched) break;
    watch.monitors[watch.count++] = entry.monitor;
  }
  ReleaseSlots();
  WriteMessage(pipe, Kind::kWatch, &watch, sizeof(watch));
}

void Accept(const Slot& slot) {
  HANDLE section = reinterpret_cast<HANDLE>(slot.section);
  const bool sane = slot.index < kMaxWatched && slot.width > 0 && slot.height > 0 && slot.width <= kMaxSide && slot.height <= kMaxSide &&
                    slot.stride >= static_cast<uint32_t>(slot.width) * 4;
  if (!sane) {
    CloseHandle(section);
    return;
  }
  const auto* view = static_cast<const uint8_t*>(MapViewOfFile(section, FILE_MAP_READ, 0, 0, 0));
  MEMORY_BASIC_INFORMATION region{};
  const size_t needed = sizeof(FrameHeader) + size_t{slot.stride} * static_cast<size_t>(slot.height);
  if (!view || !VirtualQuery(view, &region, sizeof(region)) || region.RegionSize < needed) {
    if (view) UnmapViewOfFile(view);
    CloseHandle(section);
    return;
  }
  Mapped& into = slots[slot.index];
  into.Release();
  into = Mapped{slot.monitor, section, view, slot.width, slot.height, slot.stride};
}

bool ReadExactly(HANDLE from, void* into, DWORD size) { return Transfer(from, into, size, false); }

void Serve(HANDLE opened) {
  Header header{};
  Hello hello{};
  if (!ReadExactly(opened, &header, sizeof(header)) || header.kind != static_cast<uint32_t>(Kind::kHello) || header.size != sizeof(Hello) ||
      !ReadExactly(opened, &hello, sizeof(hello)) || hello.protocol != kProtocol) {
    return;
  }
  hello.version[sizeof(hello.version) - 1] = 0;
  {
    std::lock_guard<std::mutex> guard(lock);
    pipe = opened;
    helper_version = hello.version;
    SendWatch();
  }
  while (ReadExactly(opened, &header, sizeof(header))) {
    if (header.kind == static_cast<uint32_t>(Kind::kDesktop) && header.size == sizeof(Desktop)) {
      Desktop desktop{};
      if (!ReadExactly(opened, &desktop, sizeof(desktop))) break;
      showing = desktop.secure != 0;
    } else if (header.kind == static_cast<uint32_t>(Kind::kSlot) && header.size == sizeof(Slot)) {
      Slot slot{};
      if (!ReadExactly(opened, &slot, sizeof(slot))) break;
      std::lock_guard<std::mutex> guard(lock);
      Accept(slot);
    } else {
      break;
    }
  }
  std::lock_guard<std::mutex> guard(lock);
  pipe = nullptr;
  showing = false;
  ReleaseSlots();
}

// Any program of the user's can create this pipe name while the helper is not running, and it would then receive the
// phone's input. Only a SYSTEM process can make SYSTEM the owner of what it creates.
bool OwnedBySystem(HANDLE pipe) {
  PSID owner = nullptr;
  PSECURITY_DESCRIPTOR descriptor = nullptr;
  if (GetSecurityInfo(pipe, SE_KERNEL_OBJECT, OWNER_SECURITY_INFORMATION, &owner, nullptr, nullptr, nullptr, &descriptor) != ERROR_SUCCESS) return false;
  const bool system = owner && IsWellKnownSid(owner, WinLocalSystemSid);
  LocalFree(descriptor);
  return system;
}

void Run() {
  DWORD session = 0;
  ProcessIdToSessionId(GetCurrentProcessId(), &session);
  const std::wstring name = PipeName(session);
  while (true) {
    if (secure_service_state() == 2) {
      HANDLE opened = CreateFileW(name.c_str(), GENERIC_READ | FILE_WRITE_DATA, 0, nullptr, OPEN_EXISTING,
                                  FILE_FLAG_OVERLAPPED | SECURITY_SQOS_PRESENT | SECURITY_IDENTIFICATION, nullptr);
      if (opened != INVALID_HANDLE_VALUE) {
        if (OwnedBySystem(opened)) Serve(opened);
        CloseHandle(opened);
      }
    }
    Sleep(kRetryMs);
  }
}

void Start() {
  std::call_once(started, [] { std::thread(Run).detach(); });
}

}  // namespace

namespace secure_client {

void Want(const RECT& monitor) {
  Start();
  std::lock_guard<std::mutex> guard(lock);
  auto found = std::find_if(wanted.begin(), wanted.end(), [&](const Wanted& entry) { return SameRect(entry.monitor, monitor); });
  if (found != wanted.end()) {
    ++found->count;
    return;
  }
  wanted.push_back({monitor, 1});
  SendWatch();
}

void Unwant(const RECT& monitor) {
  std::lock_guard<std::mutex> guard(lock);
  auto found = std::find_if(wanted.begin(), wanted.end(), [&](const Wanted& entry) { return SameRect(entry.monitor, monitor); });
  if (found == wanted.end() || --found->count > 0) return;
  wanted.erase(found);
  SendWatch();
}

bool Showing() { return showing; }

bool Frame(const RECT& monitor, int64_t& sequence, std::vector<uint8_t>& pixels, int& width, int& height) {
  std::lock_guard<std::mutex> guard(lock);
  for (const auto& slot : slots) {
    if (!slot.view || !SameRect(slot.monitor, monitor)) continue;
    const auto* header = reinterpret_cast<const FrameHeader*>(slot.view);
    const LONG64 before = header->sequence;
    if ((before & 1) != 0 || before == sequence) return false;
    const size_t row = static_cast<size_t>(slot.width) * 4;
    pixels.resize(row * static_cast<size_t>(slot.height));
    const uint8_t* rows = slot.view + sizeof(FrameHeader);
    for (int y = 0; y < slot.height; ++y) memcpy(pixels.data() + row * y, rows + size_t{slot.stride} * y, row);
    MemoryBarrier();
    if (header->sequence != before) return false;
    sequence = before;
    width = slot.width;
    height = slot.height;
    return true;
  }
  return false;
}

RECT PrimaryMonitor() {
  MONITORINFO info{sizeof(info)};
  GetMonitorInfoW(MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY), &info);
  return info.rcMonitor;
}

}  // namespace secure_client

extern "C" {

int32_t secure_desktop_showing() {
  Start();
  return secure_client::Showing() ? 1 : 0;
}

void secure_send_input(const Input* inputs, int32_t count) {
  std::lock_guard<std::mutex> guard(lock);
  if (!pipe || !showing) return;
  for (int32_t i = 0; i < count; ++i) WriteMessage(pipe, Kind::kInput, &inputs[i], sizeof(Input));
}

void secure_helper_version(char* out, int32_t size) {
  if (!out || size <= 0) return;
  Start();
  std::lock_guard<std::mutex> guard(lock);
  strncpy_s(out, static_cast<size_t>(size), pipe ? helper_version.c_str() : "", _TRUNCATE);
}

}  // extern "C"
