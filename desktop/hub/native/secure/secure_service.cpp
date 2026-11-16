#include <shlobj.h>
#include <windows.h>

#include <cstdint>
#include <string>

namespace {

constexpr wchar_t kServiceName[] = L"ManifoldSecureDesktop";
constexpr wchar_t kDisplayName[] = L"Manifold secure desktop";
constexpr wchar_t kServiceFlag[] = L"--secure-service";
constexpr wchar_t kHelperFlag[] = L"--secure-helper";
constexpr DWORD kNoSession = 0xFFFFFFFF;
constexpr DWORD kCheckMs = 1000;
constexpr DWORD kStopWaitMs = 15000;

SERVICE_STATUS_HANDLE status_handle = nullptr;
HANDLE stop_event = nullptr;

std::wstring OwnPath() {
  wchar_t path[MAX_PATH]{};
  const DWORD length = GetModuleFileNameW(nullptr, path, MAX_PATH);
  return length > 0 && length < MAX_PATH ? std::wstring(path, length) : std::wstring();
}

// Program Files only lets administrators write, so nothing a user runs can swap the program this SYSTEM service starts.
// A folder of its own, so removing the service never touches a hub someone unpacked under Program Files.
std::wstring InstallFolder() {
  PWSTR program_files = nullptr;
  std::wstring folder;
  if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_ProgramFiles, 0, nullptr, &program_files))) folder = std::wstring(program_files) + L"\\Manifold Secure Desktop";
  CoTaskMemFree(program_files);
  return folder;
}

void Report(DWORD state) {
  SERVICE_STATUS status{};
  status.dwServiceType = SERVICE_WIN32_OWN_PROCESS;
  status.dwCurrentState = state;
  status.dwControlsAccepted = state == SERVICE_RUNNING ? SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN : 0;
  SetServiceStatus(status_handle, &status);
}

DWORD WINAPI OnControl(DWORD control, DWORD, void*, void*) {
  if (control == SERVICE_CONTROL_STOP || control == SERVICE_CONTROL_SHUTDOWN) {
    Report(SERVICE_STOP_PENDING);
    SetEvent(stop_event);
  }
  return NO_ERROR;
}

// The service's own SYSTEM token, moved into the signed-in session, so the helper can reach that session's desktops.
HANDLE StartHelper(DWORD session) {
  HANDLE own = nullptr;
  HANDLE token = nullptr;
  HANDLE process = nullptr;
  if (OpenProcessToken(GetCurrentProcess(), TOKEN_DUPLICATE, &own) &&
      DuplicateTokenEx(own, TOKEN_ASSIGN_PRIMARY | TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_ADJUST_DEFAULT | TOKEN_ADJUST_SESSIONID, nullptr,
                       SecurityImpersonation, TokenPrimary, &token) &&
      SetTokenInformation(token, TokenSessionId, &session, sizeof(session))) {
    const std::wstring exe = OwnPath();
    std::wstring command = L"\"" + exe + L"\" " + kHelperFlag;
    STARTUPINFOW startup{sizeof(startup)};
    wchar_t desktop[] = L"winsta0\\default";
    startup.lpDesktop = desktop;
    PROCESS_INFORMATION started{};
    if (CreateProcessAsUserW(token, exe.c_str(), command.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &startup, &started)) {
      CloseHandle(started.hThread);
      process = started.hProcess;
    }
  }
  if (token) CloseHandle(token);
  if (own) CloseHandle(own);
  return process;
}

void StopHelper(HANDLE& helper) {
  if (!helper) return;
  TerminateProcess(helper, 0);
  WaitForSingleObject(helper, kStopWaitMs);
  CloseHandle(helper);
  helper = nullptr;
}

void WINAPI ServiceMain(DWORD, LPWSTR*) {
  status_handle = RegisterServiceCtrlHandlerExW(kServiceName, OnControl, nullptr);
  if (!status_handle) return;
  stop_event = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  Report(SERVICE_RUNNING);
  HANDLE helper = nullptr;
  DWORD helper_session = kNoSession;
  while (WaitForSingleObject(stop_event, kCheckMs) == WAIT_TIMEOUT) {
    const DWORD session = WTSGetActiveConsoleSessionId();
    const bool exited = helper && WaitForSingleObject(helper, 0) == WAIT_OBJECT_0;
    if (helper && (exited || session != helper_session)) StopHelper(helper);
    if (!helper && session != kNoSession) {
      helper = StartHelper(session);
      helper_session = session;
    }
  }
  StopHelper(helper);
  CloseHandle(stop_event);
  Report(SERVICE_STOPPED);
}

bool WaitUntilStopped(SC_HANDLE service) {
  SERVICE_STATUS status{};
  if (!ControlService(service, SERVICE_CONTROL_STOP, &status) && GetLastError() != ERROR_SERVICE_NOT_ACTIVE) return false;
  for (DWORD waited = 0; waited < kStopWaitMs; waited += 250) {
    if (!QueryServiceStatus(service, &status) || status.dwCurrentState == SERVICE_STOPPED) return true;
    Sleep(250);
  }
  return false;
}

}  // namespace

extern "C" {

int32_t secure_service_run() {
  std::wstring name = kServiceName;
  SERVICE_TABLE_ENTRYW table[] = {{name.data(), ServiceMain}, {nullptr, nullptr}};
  return StartServiceCtrlDispatcherW(table) ? 0 : 1;
}

// 0 when there is no service, 1 when it is installed but not running, 2 when it runs.
int32_t secure_service_state() {
  SC_HANDLE manager = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_CONNECT);
  if (!manager) return 0;
  SC_HANDLE service = OpenServiceW(manager, kServiceName, SERVICE_QUERY_STATUS);
  int32_t state = 0;
  if (service) {
    SERVICE_STATUS status{};
    state = QueryServiceStatus(service, &status) && status.dwCurrentState == SERVICE_RUNNING ? 2 : 1;
    CloseServiceHandle(service);
  }
  CloseServiceHandle(manager);
  return state;
}

// Needs administrator rights.
int32_t secure_install() {
  const std::wstring folder = InstallFolder();
  const std::wstring source = OwnPath();
  if (folder.empty() || source.empty()) return 1;
  const std::wstring target = folder + L"\\manifold_hub.exe";
  SC_HANDLE manager = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_CONNECT | SC_MANAGER_CREATE_SERVICE);
  if (!manager) return 2;
  SC_HANDLE service = OpenServiceW(manager, kServiceName, SERVICE_ALL_ACCESS);
  if (service) WaitUntilStopped(service);
  int32_t result = 0;
  CreateDirectoryW(folder.c_str(), nullptr);
  if (_wcsicmp(source.c_str(), target.c_str()) != 0 && !CopyFileW(source.c_str(), target.c_str(), FALSE)) result = 3;
  // Quoted, so a space in the path can never make Windows run a different program as SYSTEM.
  const std::wstring command = L"\"" + target + L"\" " + kServiceFlag;
  if (result == 0 && !service) {
    service = CreateServiceW(manager, kServiceName, kDisplayName, SERVICE_ALL_ACCESS, SERVICE_WIN32_OWN_PROCESS, SERVICE_AUTO_START,
                             SERVICE_ERROR_NORMAL, command.c_str(), nullptr, nullptr, nullptr, nullptr, nullptr);
    if (!service) result = 4;
  } else if (result == 0 &&
             !ChangeServiceConfigW(service, SERVICE_WIN32_OWN_PROCESS, SERVICE_AUTO_START, SERVICE_ERROR_NORMAL, command.c_str(), nullptr, nullptr,
                                   nullptr, nullptr, nullptr, kDisplayName)) {
    result = 4;
  }
  if (result == 0) {
    wchar_t description[] = L"Lets devices paired with Manifold see and answer UAC prompts and the lock screen.";
    SERVICE_DESCRIPTIONW text{description};
    ChangeServiceConfig2W(service, SERVICE_CONFIG_DESCRIPTION, &text);
    SC_ACTION restart{SC_ACTION_RESTART, 5000};
    SERVICE_FAILURE_ACTIONSW failure{86400, nullptr, nullptr, 1, &restart};
    ChangeServiceConfig2W(service, SERVICE_CONFIG_FAILURE_ACTIONS, &failure);
    if (!StartServiceW(service, 0, nullptr) && GetLastError() != ERROR_SERVICE_ALREADY_RUNNING) result = 5;
  }
  if (service) CloseServiceHandle(service);
  CloseServiceHandle(manager);
  return result;
}

// Needs administrator rights.
int32_t secure_uninstall() {
  SC_HANDLE manager = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_CONNECT);
  if (!manager) return 2;
  int32_t result = 0;
  SC_HANDLE service = OpenServiceW(manager, kServiceName, SERVICE_STOP | SERVICE_QUERY_STATUS | DELETE);
  if (service) {
    WaitUntilStopped(service);
    if (!DeleteService(service) && GetLastError() != ERROR_SERVICE_MARKED_FOR_DELETE) result = 4;
    CloseServiceHandle(service);
  }
  CloseServiceHandle(manager);
  const std::wstring folder = InstallFolder();
  if (folder.empty()) return result == 0 ? 6 : result;
  const std::wstring exe = folder + L"\\manifold_hub.exe";
  // The service process can still hold its exe for a moment after it reports stopped.
  bool removed = false;
  for (int attempt = 0; attempt < 20 && !removed; ++attempt) {
    removed = DeleteFileW(exe.c_str()) || GetLastError() == ERROR_FILE_NOT_FOUND || GetLastError() == ERROR_PATH_NOT_FOUND;
    if (!removed) Sleep(250);
  }
  removed = (RemoveDirectoryW(folder.c_str()) || GetLastError() == ERROR_FILE_NOT_FOUND) && removed;
  return result == 0 && !removed ? 6 : result;
}

}  // extern "C"
