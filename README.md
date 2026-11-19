# Genkan

A dual-screen launcher and Japanese reading companion for Android handhelds with two
screens (built for the AYN Thor). The top screen runs the launcher and the games it starts;
the bottom screen is a reading companion: a text backlog with tap-to-look-up, and, in
later phases, one-tap Anki cards and reading stats.

Status: **Phase 1** in progress: RomM sign-in, the library, downloads, launching and
play-session upload work. See [docs/ROADMAP.md](docs/ROADMAP.md).

## Using it

- **Sign in:** enter your RomM server's address, then scan the QR code (or open the link)
  and approve the code in RomM's web UI. A pairing code from RomM's *Client API tokens*
  page works too.
- **Library:** D-pad moves, L1/R1 switch platform, Y re-syncs, Start opens settings.
- **Downloads:** A downloads the focused game (A again cancels), X twice deletes it. Games
  go to `ROMs/<platform>/` on internal storage, so the first download asks for
  *All files access*. Downloads resume after a crash or a dropped connection.
- **Playing:** A on a downloaded game starts it on the top screen in the first installed
  emulator for its platform: RetroArch (NES, SNES, GB/GBC/GBA, N64; install the FCEUmm,
  Snes9x, mGBA and Mupen64Plus-Next cores from RetroArch's Online Updater), DuckStation
  (PS1), ARMSX2 (PS2), Dolphin (GameCube/Wii) or Azahar (3DS).
- **Play time:** each play of a RomM game is reported to RomM as a play session when you come
  back to Genkan. For exact times (sleep and other apps left out), allow *Usage access* from
  Genkan's settings; without it a session runs from launch until Genkan is back in front.
  Sessions under 10 seconds (an emulator that closed straight away) aren't reported.
- **PC games:** games installed in GameNative show up as a *PC* shelf once GameNative's
  Frontend Sync export is on (it writes `Games/Steam/*.steam`). **Apps** lists Android apps.

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

Requires JDK 21 and the Android SDK (platform 37, build-tools, NDK 29 from Phase 2 on).

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

`local.properties` (gitignored) must point `sdk.dir` at your SDK, or set `ANDROID_HOME`.

### Try it on the emulator

```sh
emulator -avd <api-33-arm64-avd> &
scripts/emulator-secondary-display.sh      # adds a 1080x1240 second display
./gradlew installDebug
```

Open Genkan from the app drawer, choose **Enable Genkan as Home**, accept the prompt,
and press Home. The companion should appear on the second display.

## Getting the stock launcher back

Before making Genkan the default home on a real device, keep wireless adb authorised.
If anything goes wrong:

```sh
scripts/recover-home.sh [serial]                       # disables Genkan's home entries, lists launchers
scripts/recover-home.sh [serial] <stock/.Component>    # also sets the stock launcher as home
```

Inside the app, **Disable Genkan as Home** does the same and opens the system's
default-home settings.

## License

GPL-3.0-or-later. Genkan adapts code from other GPL projects; see [NOTICE](NOTICE) for
each file's source and pinned commit.
