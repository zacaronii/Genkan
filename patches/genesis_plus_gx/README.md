# Patches to Genesis Plus GX

Genkan builds Genesis Plus GX (`libretro/Genesis-Plus-GX`) from the commit `app/build.gradle.kts`
pins (`genesisPlusGxCommit`). `buildGenesisPlusGx` applies the patches here, in name order, to a
copy of that source before building; a patch that no longer applies fails the build rather than
building without it. The patches' hash is part of the cached library's name, so changing one
rebuilds.

These are changes to Genesis Plus GX's own code, so they are under its licence (non-commercial
redistribution; see NOTICE).

The file is Yomikata's own, byte for byte (`tools/cores/genesis_plus_gx-video-ram.patch` in its
repository), so the core Genkan plays and the one Yomikata's tools run are the same build. Its
context keeps the source's CRLF line endings: `.gitattributes` keeps git from changing them.

## genesis_plus_gx-video-ram.patch

libretro gives a frontend a core's video RAM through `retro_get_memory_data` and
`retro_get_memory_size` with `RETRO_MEMORY_VIDEO_RAM`; Genesis Plus GX answers only for save RAM and
work RAM. The patch answers for video RAM with the VDP's `vram`, 0x10000 bytes, so a Mega Drive
game's snapshot holds its 64 KB of video RAM as other consoles' do (a `vram` part, see
`RamSnapshots.partsOf`).

The VDP keeps its RAM as 16-bit words in the host's byte order, which on the Thor (little-endian)
is swapped against the console's own big-endian words. The patch hands it over as it is; Genkan
reads it back in the console's order (`CoreDef.videoRamWordSize`, as for the work RAM), so a
snapshot's `vram` part and a script's `vram` region are what Yomikata's harness gives.

It is offered upstream from Yomikata's copy once tested on both sides. Drop it if Genesis Plus GX
takes it, or gains the same answer otherwise.
