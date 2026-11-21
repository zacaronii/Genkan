# Roadmap

Each phase ends with a check that takes minutes to run.

| # | Delivers | Done when |
|---|---|---|
| **0** | Project skeleton, CI, disabled-by-default HOME alias, `SECONDARY_HOME` companion, setup screen with the Home role request, adb recovery script, bottom-screen refocus and audio-focus workarounds | On an API 33 emulator with a 1080×1240 second display, enabling Genkan as home puts the companion on display 1, and the recovery script restores the stock launcher |
| **1** | Launcher + [RomM](https://github.com/rommapp/romm): device login, platform/ROM grid with covers, gamepad navigation, app grid, download queue, launching standalone emulators and PC launchers on the top screen, session end detection, play-session upload | A game downloaded from RomM is played and quit, and RomM shows the session with the right duration |
| **2** | Bottom-screen reader: Yomitan-format dictionaries through [hoshidicts](https://github.com/bee-san/hoshidicts), tap-to-look-up popup, backlog, line cleaner, WebSocket text-hook source, thread picker | Hooked lines appear on the second display, and tapping a conjugated verb finds its dictionary form |
| **3** | Screen capture + OCR of the top screen, per-game crop box, several OCR engines | A dialogue box from an emulator gives clean lines in the backlog |
| **4** | One-tap mining to an [AnkiConnect](https://foosoft.net/projects/anki-connect/) server, queued offline: Lapis/Kiku note types, sentence, furigana, glossary, frequency, picture or animated WebP, trimmed sentence audio, duplicate badge | A card with media shows up in desktop Anki; with Wi-Fi off it waits in the outbox and arrives on reconnect |
| **5** | Reading stats (characters and active time), pushed to an external activity log | A reading session shows up with both minutes and characters |
| **6** | Save sync through RomM's device-sync protocol | A save made on the device shows up in RomM and can be pulled back |
| **7** | Text from emulator memory over IPC (PPSSPP debugger, Azahar RPC) | One PSP and one 3DS game produce lines with OCR off |
| **8** | Built-in libretro cores with memory, audio and frame access | Card audio and frames come from the core directly |
| **9** | [Hachidori](https://github.com/bee-san/hachidori) features and sync: known/ignored words, lookup counts, Anki word marks in the backlog, then a link to a Hachidori host sharing counts and marks both ways, its popup settings and dictionaries, and backup import | A lookup made offline shows up in Hachidori after the next sync |

A separate spike (phase H, [tools/hookbridge](../tools/hookbridge)) gets a Windows game's
text out of Wine: a `version.dll` proxy loads [LunaHook](https://github.com/HIllya51/LunaTranslator)
inside the game and a small host sends each line over a WebSocket. It works under x86 Wine;
whether it survives x86 translation on ARM (FEX/Box64, as in GameNative) is still untested.
If it doesn't, OCR (phase 3) is the fallback.

## Device requirements carried from earlier research

- Release the bottom screen only by disabling its component; `finish()` makes the OS respawn it.
- If the companion lands on the default display, the OS refused placement: release it and fall back to one screen.
- Controller input follows the last-touched screen; refocus the top screen after bottom-screen taps.
- Apps with a window on the second display can be muted by the vendor's game assistant; hold audio focus while playing.
- Never remove a window within ~400 ms of adding another.
- Read display metrics through a per-display window context.
- Filter display lists to real screens: the bottom panel is presentation-flagged, and emulators add virtual displays.
- The vendor button (scancode 194) is mapped to HOME; don't treat it as Home.
- Launch external emulators on the right screen at launch time; their tasks can't be moved later.
