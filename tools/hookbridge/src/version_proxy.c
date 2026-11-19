// version.dll proxy: drop it next to a game's .exe and run the game with
// WINEDLLOVERRIDES="version=n,b" (GameNative's "Load mods" sets this for you).
//
// 1. Every version.dll export jumps straight to the real system version.dll,
//    so the game behaves exactly as before.
// 2. On load it starts hookbridge-host.exe with the game's process ID, then loads
//    LunaHook32.dll into the game. LunaHook's hook DLL waits for its host to
//    connect, so nothing has to be injected from outside the game.
//
// The hook kit folder is found in this order:
//   %HOOKBRIDGE_DIR%, then <folder of this DLL>\hookbridge, then D:\Genkan\hook
// It holds hookbridge-host.exe, LunaHost64.dll, LunaHook32.dll, LunaHook64.dll and
// optionally a settings file passed to the host (hookbridge.ini).

#include <windows.h>
#include <stdio.h>

#define EXPORTS(X)                    \
    X(GetFileVersionInfoA)            \
    X(GetFileVersionInfoByHandle)     \
    X(GetFileVersionInfoExA)          \
    X(GetFileVersionInfoExW)          \
    X(GetFileVersionInfoSizeA)        \
    X(GetFileVersionInfoSizeExA)      \
    X(GetFileVersionInfoSizeExW)      \
    X(GetFileVersionInfoSizeW)        \
    X(GetFileVersionInfoW)            \
    X(VerFindFileA)                   \
    X(VerFindFileW)                   \
    X(VerInstallFileA)                \
    X(VerInstallFileW)                \
    X(VerLanguageNameA)               \
    X(VerLanguageNameW)               \
    X(VerQueryValueA)                 \
    X(VerQueryValueW)

// One pointer per export, filled from the real DLL.
#define DECLARE_PTR(name) void *real_##name;
EXPORTS(DECLARE_PTR)

// One jump stub per export. The stubs only jump, so the stack and calling
// convention are untouched. (32-bit and 64-bit symbol names differ.)
#if defined(_WIN64)
#define SYM(name) #name
#else
#define SYM(name) "_" #name
#endif
#if defined(_WIN64)
#define JMP(name) "    jmp *" SYM(real_##name) "(%rip)\n"
#else
#define JMP(name) "    jmp *" SYM(real_##name) "\n"
#endif
#define DEFINE_STUB(name) \
    __asm__(".globl " SYM(name) "\n" SYM(name) ":\n" JMP(name));
EXPORTS(DEFINE_STUB)

static HMODULE self;

static void Log(const char *fmt, const char *arg) {
    char buf[1024];
    snprintf(buf, sizeof(buf), fmt, arg);
    OutputDebugStringA(buf);
    fprintf(stderr, "%s\n", buf);
    fflush(stderr);
}

static BOOL LoadRealVersion(void) {
    wchar_t path[MAX_PATH];
    UINT n = GetSystemDirectoryW(path, MAX_PATH); // syswow64 for a 32-bit game
    lstrcpyW(path + n, L"\\version.dll");
    HMODULE real = LoadLibraryW(path);
    if (!real) return FALSE;
#define RESOLVE(name) real_##name = (void *)GetProcAddress(real, #name);
    EXPORTS(RESOLVE)
    return TRUE;
}

static BOOL DirHasHost(const wchar_t *dir) {
    wchar_t p[MAX_PATH];
    wsprintfW(p, L"%s\\hookbridge-host.exe", dir);
    return GetFileAttributesW(p) != INVALID_FILE_ATTRIBUTES;
}

static BOOL FindKit(wchar_t *dir) {
    if (GetEnvironmentVariableW(L"HOOKBRIDGE_DIR", dir, MAX_PATH) && DirHasHost(dir)) return TRUE;
    GetModuleFileNameW(self, dir, MAX_PATH);
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash) lstrcpyW(slash, L"\\hookbridge");
    if (DirHasHost(dir)) return TRUE;
    lstrcpyW(dir, L"D:\\Genkan\\hook");
    return DirHasHost(dir);
}

static DWORD WINAPI StartBridge(LPVOID unused) {
    (void)unused;
    wchar_t kit[MAX_PATH];
    if (!FindKit(kit)) {
        Log("hookbridge: no hook kit found (set HOOKBRIDGE_DIR or add a hookbridge folder)%s", "");
        return 0;
    }

    // Start the host, telling it which process to connect to.
    wchar_t cmd[2 * MAX_PATH];
    wsprintfW(cmd, L"\"%s\\hookbridge-host.exe\" --pid %lu --kit \"%s\"", kit, GetCurrentProcessId(), kit);
    STARTUPINFOW si = {sizeof(si)};
    PROCESS_INFORMATION pi;
    if (CreateProcessW(NULL, cmd, NULL, NULL, FALSE, 0, NULL, kit, &si, &pi)) {
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
        Log("hookbridge: host started%s", "");
    } else {
        Log("hookbridge: could not start the host%s", "");
        return 0;
    }

    // Load LunaHook's hook DLL into this (the game's) process.
    wchar_t hook[MAX_PATH];
#if defined(_WIN64)
    wsprintfW(hook, L"%s\\LunaHook64.dll", kit);
#else
    wsprintfW(hook, L"%s\\LunaHook32.dll", kit);
#endif
    Log(LoadLibraryW(hook) ? "hookbridge: LunaHook loaded in-process%s" : "hookbridge: LunaHook failed to load%s", "");
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID reserved) {
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        self = inst;
        DisableThreadLibraryCalls(inst);
        if (!LoadRealVersion()) return FALSE;
        // Never load other DLLs or start processes inside DllMain: do it on a thread.
        CloseHandle(CreateThread(NULL, 0, StartBridge, NULL, 0, NULL));
    }
    return TRUE;
}
