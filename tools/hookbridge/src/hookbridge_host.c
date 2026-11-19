// hookbridge-host.exe: a minimal LunaHook host that sends hooked lines over a WebSocket.
//
// Started by the version.dll proxy inside the same Wine session as the game:
//   hookbridge-host.exe --pid <game pid> --kit <folder with LunaHost64.dll>
//                 [--port 6677] [--hookcode CODE] [--thread NAME] [--codepage 932]
//
// It loads LunaHost64.dll, connects to the game (whose LunaHook DLL is already
// loaded in-process by the proxy), and serves ws://127.0.0.1:<port>. Each line
// is one text frame of JSON: {"sentence": ..., "thread": ..., "hookcode": ...,
// "ctx": ..., "ctx2": ...}. Texthooker pages and Furoshiki read "sentence".
// Without --thread every text thread is sent, so a client can pick the dialogue.
// Settings can also come from <kit>\\hookbridge.ini: [hook] code=, thread=, pc_hooks=; [server] port=

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>

// ---------- LunaHost API (from LunaHostDll.cpp in LunaTranslator) ----------
typedef struct {
    DWORD processId;
    uint64_t addr;
    uint64_t ctx;
    uint64_t ctx2;
} ThreadParam;

typedef void (*ProcessEvent)(DWORD pid);
typedef void (*ThreadEventMaybeEmbed)(const wchar_t *hookcode, const char *hookname, ThreadParam tp, bool embedable);
typedef void (*ThreadEvent)(const wchar_t *hookcode, const char *hookname, ThreadParam tp);
typedef void (*OutputCallback)(const wchar_t *hookcode, const char *hookname, ThreadParam tp, const wchar_t *text);
typedef void (*HostInfoHandler)(int type, const wchar_t *log);
typedef void (*HookInsertHandler)(DWORD pid, uint64_t address, const wchar_t *hookcode);
typedef void (*EmbedCallback)(const wchar_t *text, ThreadParam tp);
typedef wchar_t *(*I18NQueryCallback)(const wchar_t *text);
typedef void (*EmuGameInfoCallback)(const wchar_t *id, const wchar_t *title, const wchar_t *version);

typedef void (*Luna_Start_t)(ProcessEvent, ProcessEvent, ThreadEventMaybeEmbed, ThreadEvent, OutputCallback,
                             HostInfoHandler, HookInsertHandler, EmbedCallback, I18NQueryCallback, EmuGameInfoCallback);
typedef void (*Luna_Settings_t)(int flushDelay, int defaultCodepage, int maxBufferSize, int maxHistorySize, bool enablePCHooks);
typedef void (*Luna_ConnectProcess_t)(DWORD pid);
typedef void (*Luna_InsertHookCode_t)(DWORD pid, const wchar_t *hookcode);

static Luna_InsertHookCode_t Luna_InsertHookCode;

// ---------- options ----------
static DWORD gamePid;
static int port = 6677, codepage = 932, pcHooks = 0; // host-side PC hooks hung the game in testing; LunaHook adds its own
static wchar_t kit[MAX_PATH] = L".";
static wchar_t hookcode[512];
static char threadFilter[256];
static FILE *logFile;
static HANDLE quitEvent;

static void Logf(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    char buf[4096];
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    printf("%s\n", buf);
    fflush(stdout);
    if (logFile) {
        fprintf(logFile, "%s\n", buf);
        fflush(logFile);
    }
}

static int ToUtf8(const wchar_t *w, char *out, int size) {
    int n = WideCharToMultiByte(CP_UTF8, 0, w ? w : L"", -1, out, size, NULL, NULL);
    return n > 0 ? n - 1 : 0;
}

// ---------- SHA-1 and base64, for the WebSocket handshake ----------
typedef struct { uint32_t h[5]; uint64_t len; uint8_t buf[64]; size_t n; } Sha1;
#define ROL(x, c) (((x) << (c)) | ((x) >> (32 - (c))))
static void Sha1Block(Sha1 *s, const uint8_t *p) {
    uint32_t w[80], a = s->h[0], b = s->h[1], c = s->h[2], d = s->h[3], e = s->h[4];
    for (int i = 0; i < 16; i++) w[i] = (uint32_t)p[4 * i] << 24 | p[4 * i + 1] << 16 | p[4 * i + 2] << 8 | p[4 * i + 3];
    for (int i = 16; i < 80; i++) w[i] = ROL(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
    for (int i = 0; i < 80; i++) {
        uint32_t f, k;
        if (i < 20) { f = (b & c) | (~b & d); k = 0x5A827999; }
        else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
        else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
        else { f = b ^ c ^ d; k = 0xCA62C1D6; }
        uint32_t t = ROL(a, 5) + f + e + k + w[i];
        e = d; d = c; c = ROL(b, 30); b = a; a = t;
    }
    s->h[0] += a; s->h[1] += b; s->h[2] += c; s->h[3] += d; s->h[4] += e;
}
static void Sha1Init(Sha1 *s) {
    s->h[0] = 0x67452301; s->h[1] = 0xEFCDAB89; s->h[2] = 0x98BADCFE; s->h[3] = 0x10325476; s->h[4] = 0xC3D2E1F0;
    s->len = 0; s->n = 0;
}
static void Sha1Update(Sha1 *s, const void *data, size_t len) {
    const uint8_t *p = data;
    s->len += len;
    while (len--) {
        s->buf[s->n++] = *p++;
        if (s->n == 64) { Sha1Block(s, s->buf); s->n = 0; }
    }
}
static void Sha1Final(Sha1 *s, uint8_t out[20]) {
    uint64_t bits = s->len * 8;
    uint8_t pad = 0x80;
    Sha1Update(s, &pad, 1);
    pad = 0;
    while (s->n != 56) Sha1Update(s, &pad, 1);
    for (int i = 7; i >= 0; i--) { uint8_t b = (uint8_t)(bits >> (8 * i)); Sha1Update(s, &b, 1); }
    for (int i = 0; i < 20; i++) out[i] = (uint8_t)(s->h[i / 4] >> (24 - 8 * (i % 4)));
}
static void Base64(const uint8_t *in, int len, char *out) {
    static const char t[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    int o = 0;
    for (int i = 0; i < len; i += 3) {
        uint32_t v = in[i] << 16 | (i + 1 < len ? in[i + 1] << 8 : 0) | (i + 2 < len ? in[i + 2] : 0);
        out[o++] = t[v >> 18 & 63];
        out[o++] = t[v >> 12 & 63];
        out[o++] = i + 1 < len ? t[v >> 6 & 63] : '=';
        out[o++] = i + 2 < len ? t[v & 63] : '=';
    }
    out[o] = 0;
}

// ---------- WebSocket server ----------
#define MAX_CLIENTS 16
static SOCKET clients[MAX_CLIENTS];
static CRITICAL_SECTION clientLock;

static void SendFrame(SOCKET s, const char *text, size_t len) {
    uint8_t head[10];
    int h = 0;
    head[h++] = 0x81; // FIN + text
    if (len < 126) head[h++] = (uint8_t)len;
    else if (len < 65536) { head[h++] = 126; head[h++] = (uint8_t)(len >> 8); head[h++] = (uint8_t)len; }
    else { head[h++] = 127; for (int i = 7; i >= 0; i--) head[h++] = (uint8_t)((uint64_t)len >> (8 * i)); }
    send(s, (const char *)head, h, 0);
    send(s, text, (int)len, 0);
}

static void Broadcast(const char *text) {
    size_t len = strlen(text);
    EnterCriticalSection(&clientLock);
    for (int i = 0; i < MAX_CLIENTS; i++)
        if (clients[i] != INVALID_SOCKET) SendFrame(clients[i], text, len);
    LeaveCriticalSection(&clientLock);
}

static void DropClient(SOCKET s) {
    EnterCriticalSection(&clientLock);
    for (int i = 0; i < MAX_CLIENTS; i++)
        if (clients[i] == s) clients[i] = INVALID_SOCKET;
    LeaveCriticalSection(&clientLock);
    closesocket(s);
}

// Reads (and ignores) what a client sends, until it closes.
static DWORD WINAPI ClientReader(LPVOID arg) {
    SOCKET s = (SOCKET)(uintptr_t)arg;
    char buf[1024];
    int n;
    while ((n = recv(s, buf, sizeof(buf), 0)) > 0) {
        int op = buf[0] & 0x0F;
        if (op == 0x8) break; // close
        if (op == 0x9 && n >= 2) { // ping: answer with a pong carrying the same (unmasked) payload
            int len = buf[1] & 0x7F, off = 2;
            if (len < 126 && n >= off + 4 + len) {
                uint8_t pong[2 + 125];
                pong[0] = 0x8A; pong[1] = (uint8_t)len;
                for (int i = 0; i < len; i++) pong[2 + i] = buf[off + 4 + i] ^ buf[off + (i % 4)];
                EnterCriticalSection(&clientLock);
                send(s, (const char *)pong, 2 + len, 0);
                LeaveCriticalSection(&clientLock);
            }
        }
    }
    DropClient(s);
    Logf("hookbridge-host: a client left");
    return 0;
}

static BOOL Handshake(SOCKET s) {
    char req[4096];
    int got = 0;
    while (got < (int)sizeof(req) - 1) {
        int n = recv(s, req + got, sizeof(req) - 1 - got, 0);
        if (n <= 0) return FALSE;
        got += n;
        req[got] = 0;
        if (strstr(req, "\r\n\r\n")) break;
    }
    char *key = strstr(req, "Sec-WebSocket-Key:");
    if (!key) key = strstr(req, "sec-websocket-key:");
    if (!key) return FALSE;
    key += 18;
    while (*key == ' ') key++;
    char *end = strstr(key, "\r\n");
    if (!end) return FALSE;
    *end = 0;
    char joined[256];
    snprintf(joined, sizeof(joined), "%s258EAFA5-E914-47DA-95CA-C5AB0DC85B11", key);
    Sha1 sha;
    uint8_t digest[20];
    Sha1Init(&sha);
    Sha1Update(&sha, joined, strlen(joined));
    Sha1Final(&sha, digest);
    char accept[64], resp[256];
    Base64(digest, 20, accept);
    int len = snprintf(resp, sizeof(resp),
                       "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                       "Sec-WebSocket-Accept: %s\r\n\r\n", accept);
    return send(s, resp, len, 0) == len;
}

static DWORD WINAPI Server(LPVOID unused) {
    (void)unused;
    SOCKET listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    BOOL yes = TRUE;
    setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, (const char *)&yes, sizeof(yes));
    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET;
    addr.sin_port = htons((u_short)port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK); // 127.0.0.1 only
    if (bind(listener, (struct sockaddr *)&addr, sizeof(addr)) || listen(listener, 4)) {
        Logf("hookbridge-host: cannot listen on 127.0.0.1:%d (error %d)", port, WSAGetLastError());
        return 1;
    }
    Logf("hookbridge-host: serving ws://127.0.0.1:%d", port);
    for (;;) {
        SOCKET s = accept(listener, NULL, NULL);
        if (s == INVALID_SOCKET) continue;
        if (!Handshake(s)) { closesocket(s); continue; }
        EnterCriticalSection(&clientLock);
        int placed = 0;
        for (int i = 0; i < MAX_CLIENTS && !placed; i++)
            if (clients[i] == INVALID_SOCKET) { clients[i] = s; placed = 1; }
        LeaveCriticalSection(&clientLock);
        if (!placed) { closesocket(s); continue; }
        Logf("hookbridge-host: a client joined");
        CloseHandle(CreateThread(NULL, 0, ClientReader, (LPVOID)(uintptr_t)s, 0, NULL));
    }
}

// ---------- JSON ----------
static void JsonString(char **o, const char *s) {
    *(*o)++ = '"';
    for (; *s; s++) {
        unsigned char c = (unsigned char)*s;
        if (c == '"' || c == '\\') { *(*o)++ = '\\'; *(*o)++ = (char)c; }
        else if (c == '\n') { *(*o)++ = '\\'; *(*o)++ = 'n'; }
        else if (c == '\r') { *(*o)++ = '\\'; *(*o)++ = 'r'; }
        else if (c == '\t') { *(*o)++ = '\\'; *(*o)++ = 't'; }
        else if (c < 0x20) *o += sprintf(*o, "\\u%04x", c);
        else *(*o)++ = (char)c;
    }
    *(*o)++ = '"';
}

// ---------- LunaHost callbacks ----------
static void OnConnect(DWORD pid) {
    Logf("hookbridge-host: connected to process %lu", pid);
    if (hookcode[0] && Luna_InsertHookCode) {
        Luna_InsertHookCode(pid, hookcode);
        char hc[1024];
        ToUtf8(hookcode, hc, sizeof(hc));
        Logf("hookbridge-host: inserted hook code %s", hc);
    }
}
static void OnDisconnect(DWORD pid) {
    Logf("hookbridge-host: process %lu disconnected", pid);
    if (pid == gamePid) SetEvent(quitEvent);
}
static void OnThreadCreate(const wchar_t *code, const char *name, ThreadParam tp, bool embedable) {
    (void)embedable;
    char hc[1024];
    ToUtf8(code, hc, sizeof(hc));
    Logf("hookbridge-host: new text thread \"%s\" %s ctx=%llx ctx2=%llx", name ? name : "", hc,
         (unsigned long long)tp.ctx, (unsigned long long)tp.ctx2);
}
static void OnThreadDestroy(const wchar_t *code, const char *name, ThreadParam tp) { (void)code; (void)name; (void)tp; }
static void OnOutput(const wchar_t *code, const char *name, ThreadParam tp, const wchar_t *text) {
    if (!name) name = "";
    if (threadFilter[0] ? strcmp(name, threadFilter) != 0
                        : (!strcmp(name, "Console") || !strcmp(name, "Clipboard")))
        return;
    static char textUtf8[65536], codeUtf8[1024], json[150000];
    ToUtf8(text, textUtf8, sizeof(textUtf8));
    ToUtf8(code, codeUtf8, sizeof(codeUtf8));
    char *o = json;
    o += sprintf(o, "{\"sentence\":");
    JsonString(&o, textUtf8);
    o += sprintf(o, ",\"thread\":");
    JsonString(&o, name);
    o += sprintf(o, ",\"hookcode\":");
    JsonString(&o, codeUtf8);
    o += sprintf(o, ",\"ctx\":\"%llx\",\"ctx2\":\"%llx\"}", (unsigned long long)tp.ctx, (unsigned long long)tp.ctx2);
    Logf("hookbridge-host: line [%s] %s", name, textUtf8);
    Broadcast(json);
}
static void OnHostInfo(int type, const wchar_t *log) {
    char msg[4096];
    ToUtf8(log, msg, sizeof(msg));
    Logf("hookbridge-host: info(%d) %s", type, msg);
}
static void OnHookInsert(DWORD pid, uint64_t address, const wchar_t *code) {
    (void)pid; (void)address; (void)code;
}
static void OnEmbed(const wchar_t *text, ThreadParam tp) { (void)text; (void)tp; }
static wchar_t *OnI18N(const wchar_t *text) { (void)text; return NULL; } // keep LunaHook's own wording
static void OnEmuGameInfo(const wchar_t *id, const wchar_t *title, const wchar_t *version) {
    char a[512], b[512];
    ToUtf8(id, a, sizeof(a));
    ToUtf8(title, b, sizeof(b));
    (void)version;
    Logf("hookbridge-host: emulated game %s %s", a, b);
}

int wmain(int argc, wchar_t **argv) {
    for (int i = 1; i < argc; i++) {
        if (!wcscmp(argv[i], L"--pid") && i + 1 < argc) gamePid = wcstoul(argv[++i], NULL, 10);
        else if (!wcscmp(argv[i], L"--kit") && i + 1 < argc) lstrcpynW(kit, argv[++i], MAX_PATH);
        else if (!wcscmp(argv[i], L"--port") && i + 1 < argc) port = _wtoi(argv[++i]);
        else if (!wcscmp(argv[i], L"--codepage") && i + 1 < argc) codepage = _wtoi(argv[++i]);
        else if (!wcscmp(argv[i], L"--hookcode") && i + 1 < argc) lstrcpynW(hookcode, argv[++i], 512);
        else if (!wcscmp(argv[i], L"--pc-hooks")) pcHooks = 1;
        else if (!wcscmp(argv[i], L"--thread") && i + 1 < argc) ToUtf8(argv[++i], threadFilter, sizeof(threadFilter));
    }
    wchar_t logPath[MAX_PATH];
    wsprintfW(logPath, L"%s\\hookbridge-host.log", kit);
    logFile = _wfopen(logPath, L"w");
    if (!gamePid) { Logf("hookbridge-host: no --pid given"); return 1; }

    // Optional per-game settings: <kit>\hookbridge.ini, [hook] code=... thread=...
    wchar_t ini[MAX_PATH], value[512];
    wsprintfW(ini, L"%s\\hookbridge.ini", kit);
    if (!hookcode[0] && GetPrivateProfileStringW(L"hook", L"code", L"", value, 512, ini)) lstrcpyW(hookcode, value);
    if (!threadFilter[0] && GetPrivateProfileStringW(L"hook", L"thread", L"", value, 512, ini)) ToUtf8(value, threadFilter, sizeof(threadFilter));
    port = GetPrivateProfileIntW(L"server", L"port", port, ini);

    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    InitializeCriticalSection(&clientLock);
    for (int i = 0; i < MAX_CLIENTS; i++) clients[i] = INVALID_SOCKET;
    quitEvent = CreateEventW(NULL, TRUE, FALSE, NULL);
    CloseHandle(CreateThread(NULL, 0, Server, NULL, 0, NULL));

    wchar_t dll[MAX_PATH];
    wsprintfW(dll, L"%s\\LunaHost64.dll", kit);
    HMODULE host = LoadLibraryW(dll);
    if (!host) { Logf("hookbridge-host: cannot load LunaHost64.dll (error %lu)", GetLastError()); return 1; }
    Luna_Start_t Luna_Start = (Luna_Start_t)GetProcAddress(host, "Luna_Start");
    Luna_Settings_t Luna_Settings = (Luna_Settings_t)GetProcAddress(host, "Luna_Settings");
    Luna_ConnectProcess_t Luna_ConnectProcess = (Luna_ConnectProcess_t)GetProcAddress(host, "Luna_ConnectProcess");
    Luna_InsertHookCode = (Luna_InsertHookCode_t)GetProcAddress(host, "Luna_InsertHookCode");
    if (!Luna_Start || !Luna_Settings || !Luna_ConnectProcess) { Logf("hookbridge-host: LunaHost64.dll is missing exports"); return 1; }

    Luna_Start(OnConnect, OnDisconnect, OnThreadCreate, OnThreadDestroy, OnOutput, OnHostInfo, OnHookInsert, OnEmbed,
               OnI18N, OnEmuGameInfo);
    // flush delay ms, code page, buffer size, history size, PC hooks (GDI text functions) on
    pcHooks = GetPrivateProfileIntW(L"hook", L"pc_hooks", pcHooks, ini);
    Luna_Settings(500, codepage, 3000, 1000000, pcHooks != 0);
    Logf("hookbridge-host: connecting to process %lu", gamePid);
    Luna_ConnectProcess(gamePid);

    // Stay up while the game runs.
    HANDLE game = OpenProcess(SYNCHRONIZE, FALSE, gamePid);
    HANDLE waits[2] = {quitEvent, game};
    WaitForMultipleObjects(game ? 2 : 1, waits, FALSE, INFINITE);
    Logf("hookbridge-host: game ended, exiting");
    return 0;
}
