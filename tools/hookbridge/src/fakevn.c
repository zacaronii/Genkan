// fakevn.exe: a tiny stand-in for a visual novel, for testing text hooks.
//
// It behaves like many old Windows VN engines: it imports version.dll (so a
// version.dll proxy next to it gets loaded), and it types each line out one
// character at a time with TextOutA using Shift-JIS (code page 932) text.
//
// Usage: fakevn.exe [seconds_per_line]   (exits after the last line)

#include <windows.h>
#include <stdio.h>
#include <stdlib.h>

static const wchar_t *LINES[] = {
    L"「おはよう。今日もいい天気だね」",
    L"彼女はそう言って、窓の外を眺めた。",
    L"桜の花びらが風に舞っている。",
    L"「放課後、図書室で待ってるから」",
};
#define LINE_COUNT (sizeof(LINES) / sizeof(LINES[0]))

static char sjis[LINE_COUNT][256];
static int sjisLen[LINE_COUNT];
static int lineIdx = 0, bytePos = 0, pause = 0, ticksPerLinePause = 20;
static HFONT font;

static void ToSjis(void) {
    for (int i = 0; i < (int)LINE_COUNT; i++)
        sjisLen[i] = WideCharToMultiByte(932, 0, LINES[i], -1, sjis[i], sizeof(sjis[i]), NULL, NULL) - 1;
}

static int CharBytes(const unsigned char *p) {
    // Shift-JIS lead bytes: 0x81-0x9F and 0xE0-0xFC.
    return ((*p >= 0x81 && *p <= 0x9F) || (*p >= 0xE0 && *p <= 0xFC)) ? 2 : 1;
}

static void Tick(HWND hwnd) {
    if (lineIdx >= (int)LINE_COUNT) {
        PostQuitMessage(0);
        return;
    }
    HDC dc = GetDC(hwnd);
    HGDIOBJ old = SelectObject(dc, font);
    if (pause > 0) {
        if (--pause == 0) {
            RECT r = {0, 0, 900, 200};
            FillRect(dc, &r, (HBRUSH)GetStockObject(WHITE_BRUSH));
            lineIdx++;
            bytePos = 0;
        }
    } else {
        const char *line = sjis[lineIdx];
        int n = CharBytes((const unsigned char *)line + bytePos);
        // Measure what is already drawn so the next character lands after it.
        SIZE sz = {0, 0};
        GetTextExtentPoint32A(dc, line, bytePos, &sz);
        TextOutA(dc, 20 + sz.cx, 40, line + bytePos, n); // one character, as many engines do
        bytePos += n;
        if (bytePos >= sjisLen[lineIdx]) {
            printf("fakevn: finished line %d\n", lineIdx + 1);
            fflush(stdout);
            pause = ticksPerLinePause;
        }
    }
    SelectObject(dc, old);
    ReleaseDC(hwnd, dc);
}

static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_TIMER: Tick(hwnd); return 0;
    case WM_DESTROY: PostQuitMessage(0); return 0;
    }
    return DefWindowProcA(hwnd, msg, wp, lp);
}

int WINAPI WinMain(HINSTANCE inst, HINSTANCE prev, LPSTR cmd, int show) {
    (void)prev; (void)show;
    int secondsPerLine = (cmd && *cmd) ? atoi(cmd) : 0;
    if (secondsPerLine > 0) ticksPerLinePause = secondsPerLine * 10;

    // A real import from version.dll, so the loader must load it (or our proxy).
    char self[MAX_PATH];
    GetModuleFileNameA(NULL, self, MAX_PATH);
    DWORD dummy;
    printf("fakevn: pid %lu, version info size %lu\n", GetCurrentProcessId(), GetFileVersionInfoSizeA(self, &dummy));
    fflush(stdout);

    ToSjis();
    font = CreateFontA(28, 0, 0, 0, FW_NORMAL, 0, 0, 0, SHIFTJIS_CHARSET, 0, 0, 0, 0, "MS Gothic");

    WNDCLASSA wc = {0};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = inst;
    wc.lpszClassName = "FakeVN";
    wc.hbrBackground = (HBRUSH)GetStockObject(WHITE_BRUSH);
    RegisterClassA(&wc);
    HWND hwnd = CreateWindowA("FakeVN", "Fake VN", WS_OVERLAPPEDWINDOW | WS_VISIBLE, 50, 50, 900, 200, NULL, NULL, inst, NULL);

    // Give the hook a few seconds to connect before the first line.
    Sleep(4000);
    SetTimer(hwnd, 1, 100, NULL);

    MSG msg;
    while (GetMessageA(&msg, NULL, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageA(&msg);
    }
    printf("fakevn: done\n");
    return 0;
}
