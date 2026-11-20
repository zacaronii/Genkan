# hookbridge: LunaHook bridge proof of concept

Gets a Windows game's text out of Wine as WebSocket lines, without injecting anything
from outside the game. Built and tested on 2026-11-16 under Wine 9.0 on x86_64 Linux.
Genkan's Phase H; its reader (Phase 2) is the Android client.

**Status:** works in GameNative on an ARM handheld (AYN Thor, GameNative 1.4.0, Proton 11
arm64ec) with a real KiriKiri Z game; first proven under x86 Wine.

## What was proven

A fake VN (`fakevn.exe`) typed four Shift-JIS lines one character at a time with
`TextOutA`. With the proxy and host in place, a WebSocket client on
`ws://127.0.0.1:6677` received exactly:

```
[TextOutA] 「おはよう。今日もいい天気だね」
[TextOutA] 彼女はそう言って、窓の外を眺めた。
[TextOutA] 桜の花びらが風に舞っている。
[TextOutA] 「放課後、図書室で待ってるから」
```

This worked for both a 32-bit and a 64-bit game.

1. **In-process loading works.** Wine loaded the `version.dll` proxy from the game's
   folder (`WINEDLLOVERRIDES="version=n,b"`, which GameNative's *Load mods* sets). The
   proxy loaded `LunaHook32.dll`/`LunaHook64.dll` into the game. LunaHook's hook DLL waits
   for a host, and `Luna_ConnectProcess(pid)` connected it. No `CreateRemoteThread`, no
   injector.
2. **A 64-bit host serves a 32-bit game**, as LunaTranslator's own x64 build does.
3. **Thread choice works.** Without a filter, LunaHook reports several threads (the
   per-character `GetTextExtentPoint32A` and `MultiByteToWideChar` threads are junk);
   `thread=TextOutA` in `hookbridge.ini` leaves only the clean lines. A real VN will need the
   same one-time pick, or a hook code.
4. **Gotcha found:** asking the host to insert PC hooks (`Luna_Settings(..., true)`)
   froze the game. LunaHook's DLL already hooks the GDI text functions itself, so this is
   now off by default (`--pc-hooks` or `pc_hooks=1` turns it on).

## In GameNative (2026-11-20)

A 32-bit KiriKiri Z visual novel (marco.exe, which imports `version.dll`) under GameNative's
Proton 11 arm64ec on the Thor:

- With *Enable mod loaders* on in the game's container settings (General tab), Wine loaded
  the proxy from the game folder. Without it Wine kept its built-in `version.dll`.
- LunaHook survived x86-on-ARM translation: it matched the KiriKiri engine, inserted its
  `EmbedKrkrZ` and `KiriKiriZ` hooks, and lines came out in Japanese.
- Genkan's reader, another Android app, reached `ws://127.0.0.1:6677` and showed both
  threads in its picker.

Getting the proxy in place: GameNative installs games in its private storage, which adb and
Shizuku can't reach, but each container maps the game folder as `A:` and Android's Download
folder as `D:`. So the kit goes in `Download/Genkan/hook`, plus a batch file that copies
the proxy across (`copy /y D:\Genkan\hook\version32.dll A:\version.dll`), run once from the
game's *Open container* desktop. The proxy writes `hookbridge-proxy.log` in the kit, since
the game folder can't be read from outside.

## Layout

```
src/fakevn.c          test "VN": imports version.dll, types SJIS lines with TextOutA
src/version_proxy.c   version.dll proxy: forwards all 17 exports, starts host, loads LunaHook
src/version.def       export list
src/hookbridge_host.c       host: LunaHost64.dll + WebSocket server (127.0.0.1 only)
build.sh              builds everything with MinGW
get_lunahook.sh       fetches LunaHost64/LunaHook32/LunaHook64 from LunaTranslator v12.1.3.11
test/run_test.sh      end-to-end test under Wine + Xvfb
test/listen.py        WebSocket listener (pip install websockets)
build/game/           32-bit test: fakevn.exe, version.dll, hookbridge/ (the hook kit)
build/game64/         64-bit test: fakevn64.exe, version.dll, hookbridge/
```

## How it runs

1. Game starts. Wine loads `version.dll` from the game folder (DLL override).
2. The proxy loads the real `syswow64\version.dll` (or `system32` for 64-bit) and forwards
   every call to it.
3. On a separate thread, it finds the hook kit in this order: `%HOOKBRIDGE_DIR%`, then
   `<game dir>\hookbridge`, then `D:\Genkan\hook` (Android Downloads in GameNative).
4. It starts `hookbridge-host.exe --pid <game pid> --kit <kit>`, then `LoadLibrary`s
   `LunaHook32.dll` or `LunaHook64.dll` into the game.
5. The host calls `Luna_Start(...)`, `Luna_Settings(...)` and `Luna_ConnectProcess(pid)`.
   Lines arrive in its output callback and go out as JSON frames:
   `{"sentence": "...", "thread": "TextOutA", "hookcode": "HS10@0:gdi32.dll:TextOutA", "ctx": "...", "ctx2": "..."}`
   Texthooker pages and Furoshiki's desktop line source read `sentence`.
6. The host exits when the game does. Its log is `<kit>\hookbridge-host.log`.

## Per-game settings: `<kit>\hookbridge.ini`

```ini
[hook]
thread=TextOutA        ; only send this thread (name as logged); omit to send all
code=HS10@0:gdi32.dll:TextOutA   ; optional LunaHook hook code to insert on connect
pc_hooks=0             ; leave 0 (see gotcha above)
[server]
port=6677
```

## Build and test (Ubuntu 24.04)

```sh
sudo dpkg --add-architecture i386 && sudo apt-get update
sudo apt-get install -y wine wine32:i386 wine64 gcc-mingw-w64-i686 gcc-mingw-w64-x86-64 xvfb p7zip-full fonts-noto-cjk
pip install websockets
./get_lunahook.sh && ./build.sh
test/run_test.sh
```

## Next steps

1. **ARM translation** (now shown to work in GameNative; this was the plan for checking it). Create an Ubuntu 24.04 ARM64 VM (UTM, OrbStack or
   Parallels; keep the default 4K page size). Install Box64 with x86 Wine (WoW64), and
   separately FEX-Emu with x86 Wine, then run `test/run_test.sh` under each. Pass means
   LunaHook's code patching survives translation, as it would in GameNative.
2. **Real VNs.** On the Mac, use CrossOver/Wine (Rosetta) or Steam inside the VM. Copy the
   right `version.dll` (32-bit from `build/game`, 64-bit from `build/game64`) and the
   `hookbridge` folder next to the game's .exe. Run with `WINEDLLOVERRIDES="version=n,b"`,
   watch `hookbridge-host.log` for thread names, then put the dialogue thread in
   `hookbridge.ini`. Set the prefix's locale to Japanese (`LC_ALL=ja_JP.UTF-8`) for older
   engines.
3. **If a game never loads `version.dll`**, build the same proxy as `winmm.dll`,
   `dinput8.dll`, `dsound.dll`, `winhttp.dll`, `dwmapi.dll` or `dwrite.dll` (the names
   GameNative's Load mods detects). Only the export list and the real DLL's name change.
4. **Android side.** Genkan's reader connects to `ws://127.0.0.1:6677` and uses `thread`
   for its thread picker. Test it against this host from the Android emulator
   using the Mac's IP (temporarily bind the host to `0.0.0.0` for that).
5. **On the Thor.** Done for one game (see "In GameNative" above). Next: pick a thread per game,
   and have Genkan install the kit and the proxy itself.

## Licences

LunaHook (`LunaHost64.dll`, `LunaHook32.dll`, `LunaHook64.dll`) is GPL-3.0, from
https://github.com/HIllya51/LunaTranslator (release v12.1.3.11). The code in `src/` was
written for this test. Distributing it together with LunaHook means GPL-3.0 applies.
