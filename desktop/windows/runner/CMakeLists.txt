cmake_minimum_required(VERSION 3.14)
project(runner LANGUAGES CXX)

# Define the application target. To change its name, change BINARY_NAME in the
# top-level CMakeLists.txt, not the value here, or `flutter run` will no longer
# work.
#
# Any new source files that you add to the application should be added here.
add_executable(${BINARY_NAME} WIN32
  "flutter_window.cpp"
  "main.cpp"
  "utils.cpp"
  "window_host.cpp"
  "capture_host.cpp"
  "window_capturer.cpp"
  "camera_capturer.cpp"
  "frame_pump.cpp"
  "nv12_converter.cpp"
  "spout_capturer.cpp"
  "app_audio_capturer.cpp"
  "h264_encoder.cpp"
  "aac_encoder.cpp"
  "mft.cpp"
  "win32_window.cpp"
  "${FLUTTER_MANAGED_DIR}/generated_plugin_registrant.cc"
  "Runner.rc"
  "runner.exe.manifest"
)

# Spout's receiver is built from source here instead of with its own CMake project, which also builds the OpenGL parts
# and a DLL. The archive is pinned by hash, so a changed download fails the build instead of changing the app.
include(FetchContent)
FetchContent_Declare(spout2
  URL "https://github.com/leadedge/Spout2/archive/f49e2f469f8cb25f559a6eaa61a3f5b8173fc100.tar.gz" # 2.007.017
  URL_HASH SHA256=9d93cadc7fea63d3e8b26384da8f8f23982a06a07adb0363d75630a99ab1f8f1
  SOURCE_SUBDIR does-not-exist
)
FetchContent_MakeAvailable(spout2)
set(SPOUT_SDK "${spout2_SOURCE_DIR}/SPOUTSDK")
add_library(spout_dx STATIC
  "${SPOUT_SDK}/SpoutDirectX/SpoutDX/SpoutDX.cpp"
  "${SPOUT_SDK}/SpoutGL/SpoutCopy.cpp"
  "${SPOUT_SDK}/SpoutGL/SpoutDirectX.cpp"
  "${SPOUT_SDK}/SpoutGL/SpoutFrameCount.cpp"
  "${SPOUT_SDK}/SpoutGL/SpoutSenderNames.cpp"
  "${SPOUT_SDK}/SpoutGL/SpoutSharedMemory.cpp"
  "${SPOUT_SDK}/SpoutGL/SpoutUtils.cpp"
)
target_include_directories(spout_dx PUBLIC "${SPOUT_SDK}/SpoutDirectX/SpoutDX" "${SPOUT_SDK}/SpoutGL")
target_compile_definitions(spout_dx PRIVATE NOMINMAX)
target_link_libraries(spout_dx PUBLIC d3d11 dxgi version winmm comctl32 comdlg32 shell32 ole32 uuid)
set(SPOUT_LICENSE_FILE "${spout2_SOURCE_DIR}/LICENSE" CACHE INTERNAL "")

# Apply the standard set of build settings. This can be removed for applications
# that need different build settings.
apply_standard_settings(${BINARY_NAME})

# Add preprocessor definitions for the build version.
target_compile_definitions(${BINARY_NAME} PRIVATE "FLUTTER_VERSION=\"${FLUTTER_VERSION}\"")
target_compile_definitions(${BINARY_NAME} PRIVATE "FLUTTER_VERSION_MAJOR=${FLUTTER_VERSION_MAJOR}")
target_compile_definitions(${BINARY_NAME} PRIVATE "FLUTTER_VERSION_MINOR=${FLUTTER_VERSION_MINOR}")
target_compile_definitions(${BINARY_NAME} PRIVATE "FLUTTER_VERSION_PATCH=${FLUTTER_VERSION_PATCH}")
target_compile_definitions(${BINARY_NAME} PRIVATE "FLUTTER_VERSION_BUILD=${FLUTTER_VERSION_BUILD}")

# Disable Windows macros that collide with C++ standard library functions.
target_compile_definitions(${BINARY_NAME} PRIVATE "NOMINMAX")

# C++/WinRT at C++17 includes <experimental/coroutine>, which Visual Studio 2026 turns into an error.
target_compile_definitions(${BINARY_NAME} PRIVATE "_SILENCE_EXPERIMENTAL_COROUTINE_DEPRECATION_WARNINGS")

# Add dependency libraries and include directories. Add any application-specific
# dependencies here.
target_link_libraries(${BINARY_NAME} PRIVATE flutter flutter_wrapper_app)
target_link_libraries(${BINARY_NAME} PRIVATE "dwmapi.lib" spout_dx)
target_link_libraries(${BINARY_NAME} PRIVATE d3d11 dxgi mfplat mf mfreadwrite mfuuid strmiids wmcodecdspuuid mmdevapi windowsapp winmm)
target_include_directories(${BINARY_NAME} PRIVATE "${CMAKE_SOURCE_DIR}")

# Run the Flutter tool portions of the build. This must not be removed.
add_dependencies(${BINARY_NAME} flutter_assemble)
