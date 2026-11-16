#ifndef RUNNER_SECURE_LINK_H_
#define RUNNER_SECURE_LINK_H_

#include <windows.h>

#include <cstdint>
#include <string>

// The pipe between the hub and the helper that runs as SYSTEM in the same session. Every message is a Header followed
// by exactly `size` bytes of the struct its kind names; anything else ends the connection.
namespace secure_link {

constexpr uint32_t kProtocol = 1;
constexpr uint32_t kMaxWatched = 4;
constexpr DWORD kPipeBuffer = 64 * 1024;

enum class Kind : uint32_t { kHello = 1, kDesktop = 2, kSlot = 3, kWatch = 4, kInput = 5 };

#pragma pack(push, 1)
struct Header {
  uint32_t kind;
  uint32_t size;
};

struct Hello {
  uint32_t protocol;
  char version[32];
};

struct Desktop {
  uint32_t secure;
};

// A shared picture of one display, as a section handle already valid in the receiving process.
struct Slot {
  uint32_t index;
  uint64_t section;
  RECT monitor;
  int32_t width;
  int32_t height;
  uint32_t stride;
};

struct Watch {
  uint32_t count;
  RECT monitors[kMaxWatched];
};

struct Input {
  uint32_t type;
  int32_t dx;
  int32_t dy;
  uint32_t data;
  uint32_t flags;
  uint16_t scan;
};
#pragma pack(pop)

// The section starts with this, then the rows. The helper makes `sequence` odd while it writes and even when done.
struct FrameHeader {
  volatile LONG64 sequence;
  int64_t reserved;
};

inline std::wstring PipeName(DWORD session) { return L"\\\\.\\pipe\\manifold-secure-desktop-" + std::to_wstring(session); }

inline bool SameRect(const RECT& a, const RECT& b) {
  return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom;
}

// Both ends open the pipe for overlapped I/O: on a synchronous handle a read that waits for the next message would hold
// up every write from the other thread until one arrived.
inline bool Transfer(HANDLE pipe, void* bytes, DWORD size, bool write) {
  OVERLAPPED overlapped{};
  overlapped.hEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  if (!overlapped.hEvent) return false;
  auto* cursor = static_cast<uint8_t*>(bytes);
  bool ok = true;
  while (ok && size > 0) {
    DWORD done = 0;
    ResetEvent(overlapped.hEvent);
    const BOOL started = write ? WriteFile(pipe, cursor, size, nullptr, &overlapped) : ReadFile(pipe, cursor, size, nullptr, &overlapped);
    ok = (started || GetLastError() == ERROR_IO_PENDING) && GetOverlappedResult(pipe, &overlapped, &done, TRUE) && done > 0;
    cursor += done;
    size -= done;
  }
  CloseHandle(overlapped.hEvent);
  return ok;
}

inline bool WriteMessage(HANDLE pipe, Kind kind, const void* body, uint32_t size) {
  Header header{static_cast<uint32_t>(kind), size};
  return Transfer(pipe, &header, sizeof(header), true) && Transfer(pipe, const_cast<void*>(body), size, true);
}

}  // namespace secure_link

#endif  // RUNNER_SECURE_LINK_H_
