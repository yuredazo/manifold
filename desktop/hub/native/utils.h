#ifndef NATIVE_UTILS_H_
#define NATIVE_UTILS_H_

#include <windows.h>

#include <string>

inline std::string Utf8FromUtf16(const wchar_t* text) {
  if (text == nullptr) return {};
  const int length = static_cast<int>(wcsnlen(text, 32767));
  const int size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text, length, nullptr, 0, nullptr, nullptr);
  if (size <= 0) return {};
  std::string out(size, '\0');
  if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text, length, out.data(), size, nullptr, nullptr) == 0) return {};
  return out;
}

inline std::wstring Utf16FromUtf8(const std::string& text) {
  if (text.empty()) return L"";
  const int length = MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0);
  std::wstring wide(length, L'\0');
  MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), wide.data(), length);
  return wide;
}

#endif  // NATIVE_UTILS_H_
