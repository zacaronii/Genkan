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
