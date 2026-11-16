#pragma once

#include <windows.h>

#include <cstdint>
#include <vector>

// The hub's end of the link to the helper the secure desktop service runs. Without the service it never connects, and
// Showing stays false.
namespace secure_client {

void Want(const RECT& monitor);
void Unwant(const RECT& monitor);
bool Showing();
// Copies the newest picture of `monitor` when it is newer than `sequence`, as tightly packed BGRA.
bool Frame(const RECT& monitor, int64_t& sequence, std::vector<uint8_t>& pixels, int& width, int& height);
RECT PrimaryMonitor();

}  // namespace secure_client

