# Patches to Dolphin

Genkan builds Dolphin's libretro core from libretro's repository at the commit `app/build.gradle.kts`
pins (`dolphinCommit`). `buildDolphin` applies the patches here, in name order, to a copy of that
source before building; a patch that no longer applies fails the build rather than building
without it. The patches' hash is part of the cached library's name, so changing one rebuilds.

These are changes to Dolphin's own code, so they are under Dolphin's licence, GPL-2.0-or-later,
like the rest of the core (see NOTICE).

## 0001-efb-scale-and-widescreen-through-config.patch

Covers two options the core's `retro_run` (`Source/Core/DolphinLibretro/Main.cpp`) set while the
game runs: the internal resolution (`dolphin_efb_scale`, `iEFBScale`) and the widescreen hack
(`dolphin_widescreen_hack`, `bWidescreenHack`). Both were written to `g_Config` alone, while every
other option goes through Dolphin's config system (`Config::SetBase`). A change to any option
makes the core re-read all of them, and each config-system write refreshes `g_Config` from the
config layers (`VideoConfig::Refresh`), which still held the values from boot: the new EFB scale
or widescreen setting was undone before it took effect. Set at the start, both worked. The patch
writes them through `Config::SetBase`, as their neighbours are, so they change as the game runs.

The output (the OpenGL backbuffer handed to the frontend) is sized by the EFB scale too, but only
when the core starts (`GLContextLR::Initialize`): after a change it kept its old size, so a higher
scale was drawn into the old, smaller output, and a lower one into an output larger than the
frontend's framebuffer, cut off. The patch gives `GLContextLR` an `Update()` that sizes the output
from the current scale, and asks the presenter for a resize (`Presenter::ResizeSurface`) when the
scale changes, so the next frame is drawn at the new size. Only the OpenGL output is covered:
Genkan runs the core with it.

Drop it once libretro's Dolphin writes these options through the config system itself.
