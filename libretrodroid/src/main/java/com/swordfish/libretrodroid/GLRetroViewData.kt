/*
 *     Copyright (C) 2022  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.swordfish.libretrodroid

import android.content.Context

class GLRetroViewData(context: Context) {
    var coreFilePath: String? = null
    var gameFilePath: String? = null
    var gameFileBytes: ByteArray? = null
    var gameVirtualFiles: List<VirtualFile> = listOf()
    var systemDirectory: String = context.filesDir.absolutePath
    var savesDirectory: String = context.filesDir.absolutePath
    var variables: Array<Variable> = arrayOf()
    var saveRAMState: ByteArray? = null
    var shader: ShaderConfig = ShaderConfig.Default
    var viewportAlignment = ViewportAlignment.CENTER
    var rumbleEventsEnabled: Boolean = true
    var preferLowLatencyAudio: Boolean = true
    var skipDuplicateFrames: Boolean = false
    var enableMicrophone: Boolean = false
    var immersiveMode: ImmersiveMode? = null
    /**
     * Genkan: the libretro device to put in each port (by index) once the game is loaded, as
     * RetroArch tells every core; empty, none. Dolphin connects no controller until it's told.
     */
    var portDevices: IntArray = IntArray(0)
    /**
     * Genkan: the console has sticks of its own (a GameCube), so they are only its sticks: no
     * D-pad presses made of them (see StickMotion).
     */
    var sticksOnly: Boolean = false
    /** Genkan: the core may draw with Vulkan, shown through GL (libretrodroid's vulkanbridge.h). */
    var vulkan: Boolean = false
    /** Genkan: a Vulkan failure to force (LibretroDroid.offerVulkan), to see the way back to GL. */
    var vulkanFailure: Int = 0
}

enum class ViewportAlignment(val value: Int) {
    CENTER(0),
    TOP(1),
    BOTTOM(2)
}
