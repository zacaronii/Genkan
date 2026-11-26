# Patches to LRPS2

Genkan builds LRPS2 (libretro's PCSX2, `libretro/parallel-ps2`) from the commit `app/build.gradle.kts`
pins (`lrps2Commit`). `buildLrps2` applies the patches here, in name order, to a copy of that source
before building; a patch that no longer applies fails the build rather than building without it.
The patches' hash is part of the cached library's name, so changing one rebuilds.

These are changes to LRPS2's own code, so they are under its licence, GPL-3.0, like the rest of the
core (see NOTICE).

## 0001-no-initial-exec-tls-on-android.patch

The ARM64 recompiler keeps its write cursors (`armAsm`, `armAsmPtr`, `armAsmCapacity`) and the x86
emitter's in thread-local variables declared with the initial-exec TLS model
(`PCSX2_TLS_INITIAL_EXEC`, `common/Pcsx2Defs.h`), which saves a resolver call per access. glibc keeps
static-TLS room for a library loaded later, so the core loads in RetroArch on Linux; Android's
bionic keeps none and refuses to load it at all ("TLS symbol ... in dlopened ... using IE access
model"). The patch leaves the macro empty on Android, as it already is on MSVC, so those variables
take the default model there.

Drop it once LRPS2 leaves the model out on Android itself.

## 0002-hand-the-game-id-to-the-frontend.patch

Genkan names a PS2 game and its build the way PINE does for ARMSX2: the disc's serial and the ELF's
CRC (`SLPM-12345-1a2b3c4d`), so Yomikata's scripts and catalog entries fit both. libretro gives a
frontend neither. The core has both when a game starts (`host_on_game_changed` in
`libretro/main.c`, which also runs for the BIOS with the BIOS's own "serial"); the patch hands them
over there through a private environment call, `RETRO_ENVIRONMENT_PRIVATE | 0x4701` with a
`{ const char *serial; unsigned crc; }`. Genkan's libretrodroid keeps a disc's and ignores the
BIOS's (`libretrodroid/src/main/cpp/gameid.h`, tested by `gameIdTest`). A frontend that doesn't
know the call refuses it, and the core goes on as before.

Drop it if LRPS2 or libretro gains a way to tell the frontend which game it runs.
