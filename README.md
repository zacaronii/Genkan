# Genkan

A dual-screen launcher and Japanese reading companion for Android handhelds with two
screens (built for the AYN Thor). The top screen runs the launcher and the games it starts;
the bottom screen shows the focused game's details while you browse, and turns into a
reading companion once a game is running: a text backlog with tap-to-look-up, one-tap Anki
cards and reading stats.

Status: **Phases 1 and 2** done; **Phase 3** (OCR) works with Meiki and ML Kit; **Phase 4** (Anki
cards with picture and sentence audio) and **Phase 5** (reading stats) work: RomM sign-in, library, downloads, launching and play
sessions; the bottom-screen reader with a texthooker connection, backlog, thread picker and
a Yomitan dictionary popup. See
[docs/ROADMAP.md](docs/ROADMAP.md).

## Using it

- **Sign in:** enter your RomM server's address, then scan the QR code (or open the link)
  and approve the code in RomM's web UI. A pairing code from RomM's *Client API tokens*
  page works too.
- **Library:** each platform's games stand on a shelf as their cases; left and right flip
  through them (holding speeds up), and the focused game's disc or cartridge slides out when
  it rests. Up goes to the platforms, L1/R1 switch platform from anywhere, Y re-syncs, Start
  opens settings. Select switches to the cover grid and back, and the choice is remembered.
  Starting a game plays a short insert animation (the disc or cartridge goes into the machine)
  while it gets ready; any button skips it, and Settings → Playing switches it off.
- **Downloads:** A downloads the focused game (A again cancels), X twice deletes it. Games
  go to `ROMs/<platform>/` on internal storage, so the first download asks for
  *All files access*. Downloads resume after a crash or a dropped connection.
- **Playing:** A on a downloaded game starts it on the top screen in the first installed
  emulator for its platform: RetroArch (NES, SNES, GB/GBC/GBA, N64; install the FCEUmm,
  Snes9x, mGBA and Mupen64Plus-Next cores from RetroArch's Online Updater), DuckStation
  (PS1), ARMSX2 (PS2), Dolphin (GameCube/Wii) or Azahar (3DS). DuckStation gets the Japanese
  PS1 BIOS (`ps-30j.bin`) from the platform's firmware in RomM, copied into its `bios` folder
  through Shizuku before the first game starts.
- **Play time:** each play of a RomM game is reported to RomM as a play session when you come
  back to Genkan. For exact times (sleep and other apps left out), allow *Usage access* from
  Genkan's settings; without it a session runs from launch until Genkan is back in front.
  Sessions under 10 seconds (an emulator that closed straight away) aren't reported.
- **Game details:** while you browse, the bottom screen shows the focused game: its disc or
  cartridge and logo over its fan art (or its blurred cover), RomM's details, your play
  time, reading and save, its summary and screenshots (tap one for the full view, swipe
  between them). Its buttons play or download the game as A does, open the manual, and
  delete the download (tap twice). The manual opens on the bottom screen: scroll through its
  pages, pinch or double-tap to zoom, and close it with Close or by pulling down from the
  first page.
- **Dictionaries:** put Yomitan dictionary zips (JMdict, a frequency list such as JPDB, a
  pitch-accent or kanji dictionary) in `Download/` or a folder directly inside it, then open
  *Dictionaries* in settings and choose *Import dictionaries from Download*. Importing again
  skips dictionaries that are already there and replaces ones with a new revision. On the
  same screen, A switches a dictionary on or off, L1/R1 change the popup's order and X twice
  removes one. Lookups use
  [hoshidicts](https://github.com/bee-san/hoshidicts), so conjugated forms find their
  dictionary entries (眺めた → 眺める).
- **Reader:** the bottom screen connects to a texthooker on `ws://127.0.0.1:6677`
  ([hookbridge](tools/hookbridge), or anything that sends plain-text or `{"sentence": …}`
  frames) and shows the current game's lines. When the frames name their threads, it asks
  once per game which thread is the dialogue. Tap a word to look it up: the popup renders
  each dictionary's own formatting, with furigana and pitch graphs; tap a kanji in the
  headword for its kanji-dictionary entry. To try it without a
  game: `python3 tools/hookbridge/test/fake_host.py` on a computer and
  `adb reverse tcp:6677 tcp:6677`.
- **Word marks and Hachidori:** as in [Hachidori](https://github.com/bee-san/hachidori), the popup's
  *✓ Known* and *⊘ Ignore* mark a word by its headword, and the first result shows how often it was
  looked up. Backlog words are underlined by their Anki state: solid when not in Anki, dashed while
  being learned, none once mature (interval 21 days or more) or marked; from the mining deck and
  any decks chosen in *Anki* settings. Under *Words* in settings, a Hachidori host's relay
  (`ws://host:8771/link`, the Hachidori Relay add-on) shares the marks and counts both ways; what's
  made offline waits on the device. Lookups and cards stay on the device. While linked, the popup
  follows Hachidori's settings (counts, results, frequency dictionary, its brief definitions), and
  the host's dictionaries can be fetched one by one, installed as Hachidori compiled them. A
  Hachidori backup in Download adds its dictionaries, marked words and lookup counts.
- **OCR:** for games without a texthooker, *OCR* on the reader's bar reads the top screen
  (Android asks once per session to allow screen capture). *Area* sets the part of the
  screen to read for each game, drawn over a picture of the top screen; by default it's the
  lower third. A line is read once its text stops changing, with
  [Meiki OCR](https://github.com/rtr46/meikiocr), trained on Japanese games and bundled with
  the app (about 150 ms a line on the Thor); the chip next to *Area* switches to ML Kit's
  Japanese model (faster, but it misreads game fonts more often). The build downloads Meiki's
  pinned models from Hugging Face once into `.gradle/meiki`.
- **Saves:** for Azahar (3DS), RetroArch, Dolphin and PPSSPP (PSP) games downloaded from RomM, Genkan syncs the save
  with RomM's device sync before the game starts and after it ends. A save about to be replaced
  is copied to `Genkan/save-backups` first; when both sides changed, the device keeps its copy
  and archives it in RomM; a delete on the server never removes a save from the device.
  Dolphin (GameCube, Wii) keeps its saves in its own `Android/data`,
  which other apps can't read, so those go through [Shizuku](https://github.com/RikkaApps/Shizuku):
  install and start it (it stops at every reboot), then *Emulator saves through Shizuku* under *Playing* in settings.
  Its save folders are shared by every game, so Genkan learns which files are a game's from
  what changed there while it ran. DuckStation's memory cards can't be synced: it writes them
  readable by itself only, which even Shizuku can't get past. For synced PS1 saves, use the
  built-in player.
- **PSP text from PPSSPP (opt-in):** *PSP text from PPSSPP* (settings, *Reading text*) turns on
  PPSSPP's remote debugger (a fixed port in its `ppsspp.ini`; switching off restores PPSSPP's own
  values). For games with a hook profile, Genkan sets non-pausing log breakpoints at the game's
  text routine and reads each line from memory, with OCR off. PPSSPP 1.20 opens that debugger to
  the whole network while a game runs, hence off by default. Profiles must match the game's
  version: デュナミス15 1.01 doesn't match the published hook, so more profiles are still to come.
  Hook profiles come from the Yomikata catalog (a few built into Genkan cover the same games
  when no catalog is set up); a game without one is read with OCR.
- **3DS text from Azahar (opt-in):** *3DS text from Azahar* (settings, *Reading text*) turns on
  Azahar's scripting RPC (`enable_rpc_server` in its `config.ini`; switching off restores Azahar's
  own value). The RPC only reads memory, so no profile is needed but OCR starts it off: the
  first line OCR reads is looked up in the game's memory (UTF-16, UTF-8 or Shift-JIS), then
  Genkan follows that string, and the game's pointers to it, and switches OCR off. A full
  scan takes a few seconds. Games that draw text without keeping it as a string can't be
  followed and stay on OCR. Games that keep a whole scene's script loaded (逆転裁判5) have no
  string to follow: OCR stays on to tell which page shows, and each line is swapped for that
  page's exact text from memory. Azahar's RPC listens on the whole network while a game runs.
- **Built-in player (opt-in):** *Built-in player* in settings runs GB, GBC and GBA games (mGBA),
  DS games (melonDS DS), PS1 games (PCSX ReARMed), PSP games (PPSSPP), Super Famicom games
  (bsnes-mercury), GameCube and Wii games (Dolphin) and PC Engine HuCard and CD-ROM² games
  (Beetle PCE Fast, with RomM's System Card 3 for CDs) and Dreamcast games (Flycast, with RomM's
  Dreamcast BIOS) and PS2 games (LRPS2, with RomM's PS2 BIOS; ARMSX2 stays a choice in Settings)
  inside Genkan instead of their installed
  emulator, in a process of its own. Genkan then reads the game's frames, sound and memory
  itself: OCR and card audio need no screen-capture prompt, and text is found in memory as for
  Azahar (a PSP scan takes a third of a second), with no hook profile or network debugger.
  GBA saves are RetroArch's mGBA `.srm` files and PSP saves go on PPSSPP's memory stick, so
  each is shared with the standalone emulator and synced; PS1, DS and PC Engine saves are kept in
  `Genkan/saves/<core>` and synced to RomM under the core's name. Dolphin keeps its whole User
  folder in `Genkan/saves/dolphin` (memory cards, config and shader cache); only its GameCube
  memory cards (`User/GC`) sync, as standalone Dolphin's, so the two share saves through RomM.
  Select+Start saves and quits. A DS game's upper screen
  is on the top display and its touch screen on the bottom one, over the reader; clicking
  either stick swaps the touch screen and the reader. PS1 games boot with the Japanese BIOS
  (`ps-30j.bin`, SCPH-5500) from the platform's firmware in RomM, fetched on first launch;
  without it the core's stand-in BIOS hangs some games. A multi-disc game without an `.m3u`
  gets one, disc 1 first, so discs can be changed.
- **Yomikata catalog:** a catalog of per-release reading rules (encodings, character tables,
  PSP hooks) kept in a GitHub repository. Set its *owner/name* and, while it's private, a
  fine-grained read-only token under *Yomikata* in settings. Genkan fetches the index, then each
  entry when its game starts, and keeps a copy for offline use. PSP hooks come from the catalog
  first and from Genkan's own profiles otherwise.
  Where the catalog has a script for a game in the built-in player (a Lua file that reads the game's
  memory each frame), Genkan runs it beside the game: its lines go to the backlog with their speaker,
  a choice shows its answers with the game's cursor, and text from menus never goes in. A game
  without one (or whose script can't run) is followed in memory from what OCR reads, with the entry's
  cleanup for its own codes; OCR stays on for the screens a script doesn't read.
- **Yomikata samples (opt-in):** some games (Moonlight Syndrome, Persona 2) keep their text in
  their own encoding, so memory search can't read it. With *Save samples while playing* on (under
  *Yomikata* in settings), each line OCR reads in the built-in player that isn't in memory as standard text is looked
  for by its shape (which characters repeat), and the matching places are saved with the bytes
  around them and what OCR read, in `Download/Genkan/yomikata-samples/<platform>-<serial>.json`.
  They contain script text, so they never go to the catalog itself: with a Yomikata inbox
  (`yomikata serve`) address and token set on the same page, Genkan sends each file that has
  grown whenever you come back from a game, and keeps trying until the inbox answers. The token is
  stored encrypted on the device, like the RomM sign-in.
- **Anki:** set an AnkiConnect address, deck and note type (Lapis or Kiku) under *Anki* in
  settings. *＋ Anki* in the popup makes a card, in the same markup as Amatsubame's (Yomitan's
  formats): the word with furigana, a short definition, the first dictionary in full and every
  dictionary's definitions (without their images), the line with the word in bold, pitch and
  its category, frequency (and FreqSort), the game and its platform, a picture of the top screen
  while OCR is on, and the line's voice once *Audio* on the reader's bar has the recording
  permission (what the game plays, cut from when the line appeared to when the next one did,
  silence trimmed). With a word-audio source set (a Yomitan custom audio URL with `{term}` and
  `{reading}`, such as a local audio server's `/audio/list`), the word's first recording goes in
  too, text-to-speech only when there's nothing else. Cards wait in an outbox when Anki can't be
  reached and are sent when it answers; the badge shows *Queued* or *In Anki*. To try it
  without Anki: `python3 scripts/fake_ankiconnect.py` and `adb reverse tcp:8765 tcp:8765`.
- **Reading stats:** while a game started from Genkan is being read, the reader counts its
  characters (kana and kanji, each line once; Furoshiki's rules) and active reading time
  (needs a line or a lookup in the last five minutes), shown on the reader's bar. Under
  *Reading log* in settings, a mochibridge address and API token send each session to
  kechimochi with minutes and characters; sessions are re-sent as they grow. When mochibridge
  asks for a RomM game's cover, Genkan sends the one it shows (RomM's own cover, once).
- **PC games:** games installed in GameNative show up as a *PC* shelf once GameNative's
  Frontend Sync export is on (it writes `Games/Steam/*.steam`). **Apps** lists Android apps.
- **PC game text ([hookbridge](tools/hookbridge)):** starting a PC game puts the hook kit in
  `Download/Genkan/hook`, which GameNative's containers see as `D:\Genkan\hook`. Once per game:
  turn on *Enable mod loaders* in its container settings (General), then from the game's
  *Open container* desktop run `D:\Genkan\hook\setup.bat`, which copies the hook into the
  game's folder (`setup-32bit.bat` or `setup-64bit.bat` if its guess is wrong). From then on
  the reader gets the game's text; pick the dialogue thread once (KiriKiri games: TextRender).
  A hooked game can take a minute or two longer to start.

## How the two screens work

- `HomeActivity` runs on the top screen (display 0). Its HOME entry point is an
  `activity-alias` that ships **disabled**, so installing Genkan never takes over Home.
  The setup screen enables it and then asks for the Home role.
- `LowerActivity` carries `SECONDARY_HOME`. Once Genkan is the default home, Android
  starts it on the second screen by itself. To release the bottom screen, Genkan disables
  the component, because `finish()` gets the activity respawned.
- After any touch on the bottom screen, an invisible `FocusDirectorActivity` hands focus
  back to the top screen, because controller input follows the last-touched screen.

## Build

Requires JDK 21 and the Android SDK (platform 37, build-tools, NDK 29, CMake 3.31.6).
Clone with `--recursive`, or run `git submodule update --init --recursive`, for the
dictionary engine in `third_party/hoshidicts` and Yomikata's reader runtime in `third_party/yomikata`
(which runs a game's Yomikata script in the built-in player). The GameCube core (`third_party/dolphin`) is
skipped by that: the first build fetches it with its externals (about 600 MB) and builds it
(a few minutes), once per pinned commit, into `.gradle/cores`.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

`local.properties` (gitignored) must point `sdk.dir` at your SDK, or set `ANDROID_HOME`.

### Screenshots

The library, the settings page and the bottom screen's game details are drawn on the JVM
from made-up data (Roborazzi on Robolectric, `app/src/test/kotlin/app/genkan/ui/ScreenshotTest.kt`),
at the Thor's sizes and density: 1920x1080 for the top screen and 1080x1240 for the bottom
one, both at 369 dpi. The settings page is shot once per group (`settings_<group>.png`).
The library is shot as the grid and as the shelf: a PlayStation shelf of jewel cases, a DS
shelf, each with the focused game's disc or card slid out, and a game without a cover. The
game details are shot for a game with everything (also scrolled to its screenshots), a sparse
one and an open screenshot, and the manual viewer at its first page and scrolled. No real
art is used: the tests draw stand-in covers, spines, logos, discs, fan art, screenshots and
manual pages. Record them after a UI change and commit the PNGs
with it:

```sh
./gradlew :app:recordRoborazziDebug     # writes app/src/test/screenshots/*.png
./gradlew :app:verifyRoborazziDebug     # fails if the screens no longer match them
```

Open the PNGs in `app/src/test/screenshots`, or see them in a pull request's diff. CI
compares them, in its one run of the unit tests, and fails when a shot differs from its
committed PNG (by 1% of its pixels or more); the comparisons are then uploaded as the
`screenshot-compare` artifact.

### Try it on the emulator

```sh
emulator -avd <api-33-arm64-avd> &
scripts/emulator-secondary-display.sh      # adds a 1080x1240 second display
./gradlew installDebug
```

Open Genkan from the app drawer, switch on **Genkan as Home** under *Device setup* in
settings, accept the prompt, and press Home. The companion should appear on the second display.

## Getting the stock launcher back

Before making Genkan the default home on a real device, keep wireless adb authorised.
If anything goes wrong:

```sh
scripts/recover-home.sh [serial]                       # disables Genkan's home entries, lists launchers
scripts/recover-home.sh [serial] <stock/.Component>    # also sets the stock launcher as home
```

Inside the app, switching **Genkan as Home** off does the same and opens the system's
default-home settings.

## License

GPL-3.0-only: version 3 of the GNU General Public License, not later versions. Genkan adapts
code from other GPL projects; see [NOTICE](NOTICE) for each file's source and pinned commit.
