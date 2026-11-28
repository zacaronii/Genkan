# Patches to Azahar

Genkan builds Azahar's libretro core (`azahar-emu/azahar`, `src/citra_libretro`) from the commit
`app/build.gradle.kts` pins (`azaharCommit`). `buildAzahar` applies the patches here, in name order,
to a copy of that source before building; a patch that no longer applies fails the build rather
than building without it. The patches' hash is part of the cached library's name, so changing one
rebuilds.

These are changes to Azahar's own code, so they are under its licence, GPL-2.0-or-later, like the
rest of the core (see NOTICE).

## 0001-fcram-and-heap-maps.patch

Yomikata's patch for its 3DS scripts, copied unchanged from Yomikata's `tools/cores/azahar-fcram.patch`
at its commit 3285d7a8fa1b67ce0576bafc92a9905ce851ddec (Yomikata #289); sha256
`9a3cbbd2f2e7af168f1f0cc3a88d275a447e39b4c7e9fd79d2fe2a1efeae9123`. Two changes to
`src/citra_libretro/citra_libretro.cpp`, both on the frontend's side:

- `retro_get_memory_data`/`_size(RETRO_MEMORY_SYSTEM_RAM)` give the FCRAM whole (128 MB, 256 MB on a
  New 3DS), as the older Citra core did: a frontend reading a game's memory as one block gets the
  linear heap (0x14000000) page for page.
- `retro_run` sets the memory maps again whenever the count of the running process's memory areas
  changes: the game maps its heap (0x08000000) and linear heap after the maps were first set, and
  without this a frontend never sees them.

Update it from Yomikata when Yomikata's changes; drop it once Azahar does both itself (an upstream
pull request is to be offered after testing).
