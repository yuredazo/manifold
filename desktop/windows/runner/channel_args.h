#ifndef RUNNER_CHANNEL_ARGS_H_
#define RUNNER_CHANNEL_ARGS_H_

#include <flutter/encodable_value.h>
#include <windows.h>

#include <cstdint>
#include <string>

namespace channel {

inline const flutter::EncodableValue* Find(const flutter::EncodableMap& arguments, const char* key) {
  auto found = arguments.find(flutter::EncodableValue(key));
  return found == arguments.end() ? nullptr : &found->second;
}

// A missing or mistyped argument reads as 0, false or an empty string: the callers check what they need.
inline int64_t Int(const flutter::EncodableMap& arguments, const char* key) {
  const auto* value = Find(arguments, key);
  if (!value) return 0;
  if (const auto* narrow = std::get_if<int32_t>(value)) return *narrow;
  if (const auto* wide = std::get_if<int64_t>(value)) return *wide;
  return 0;
}

inline bool Bool(const flutter::EncodableMap& arguments, const char* key) {
  const auto* value = Find(arguments, key);
  const auto* flag = value ? std::get_if<bool>(value) : nullptr;
  return flag && *flag;
}

inline std::string Text(const flutter::EncodableMap& arguments, const char* key) {
  const auto* value = Find(arguments, key);
  const auto* text = value ? std::get_if<std::string>(value) : nullptr;
  return text ? *text : "";
}

inline int64_t FromWindow(HWND window) { return static_cast<int64_t>(reinterpret_cast<intptr_t>(window)); }

inline HWND ToWindow(int64_t handle) { return reinterpret_cast<HWND>(static_cast<intptr_t>(handle)); }

// A bit no handle uses, so a window and a monitor never share a session key.
constexpr int64_t kMonitorTag = int64_t{1} << 62;

inline bool IsMonitor(int64_t handle) { return (handle & kMonitorTag) != 0; }

inline int64_t FromMonitor(HMONITOR monitor) { return static_cast<int64_t>(reinterpret_cast<intptr_t>(monitor)) | kMonitorTag; }

inline HMONITOR ToMonitor(int64_t handle) { return reinterpret_cast<HMONITOR>(static_cast<intptr_t>(handle & ~kMonitorTag)); }

}  // namespace channel

#endif  // RUNNER_CHANNEL_ARGS_H_
