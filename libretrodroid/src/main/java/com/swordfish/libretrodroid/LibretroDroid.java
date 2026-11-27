/*
 *     Copyright (C) 2019  Filippo Scognamiglio
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

package com.swordfish.libretrodroid;

import java.util.List;

public class LibretroDroid {

    static {
        System.loadLibrary("libretrodroid");
    }

    public static final int MOTION_SOURCE_DPAD = 0;
    public static final int MOTION_SOURCE_ANALOG_LEFT = 1;
    public static final int MOTION_SOURCE_ANALOG_RIGHT = 2;
    public static final int MOTION_SOURCE_POINTER = 3;

    public static final int SHADER_DEFAULT = 0;
    public static final int SHADER_CRT = 1;
    public static final int SHADER_LCD = 2;
    public static final int SHADER_SHARP = 3;
    public static final int SHADER_UPSCALE_CUT = 4;
    public static final int SHADER_UPSCALE_CUT2 = 5;
    public static final int SHADER_UPSCALE_CUT3 = 6;
    // Genkan: a shader whose sources come from the app (vertex, fragment, and whether it samples linearly).
    public static final int SHADER_CUSTOM = 7;
    public static final String SHADER_CUSTOM_PARAM_VERTEX = "vertex";
    public static final String SHADER_CUSTOM_PARAM_FRAGMENT = "fragment";
    public static final String SHADER_CUSTOM_PARAM_LINEAR = "linear";

    public static final String SHADER_UPSCALE_CUT_PARAM_USE_DYNAMIC_BLEND = "USE_DYNAMIC_BLEND";
    public static final String SHADER_UPSCALE_CUT_PARAM_BLEND_MIN_CONTRAST_EDGE = "BLEND_MIN_CONTRAST_EDGE";
    public static final String SHADER_UPSCALE_CUT_PARAM_BLEND_MAX_CONTRAST_EDGE = "BLEND_MAX_CONTRAST_EDGE";
    public static final String SHADER_UPSCALE_CUT_PARAM_BLEND_MIN_SHARPNESS = "BLEND_MIN_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT_PARAM_BLEND_MAX_SHARPNESS = "BLEND_MAX_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT_PARAM_STATIC_BLEND_SHARPNESS = "STATIC_BLEND_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT_PARAM_EDGE_USE_FAST_LUMA = "EDGE_USE_FAST_LUMA";
    public static final String SHADER_UPSCALE_CUT_PARAM_EDGE_MIN_VALUE = "EDGE_MIN_VALUE";
    public static final String SHADER_UPSCALE_CUT_PARAM_EDGE_MIN_CONTRAST = "EDGE_MIN_CONTRAST";

    public static final String SHADER_UPSCALE_CUT2_PARAM_USE_DYNAMIC_BLEND = "USE_DYNAMIC_BLEND";
    public static final String SHADER_UPSCALE_CUT2_PARAM_BLEND_MIN_CONTRAST_EDGE = "BLEND_MIN_CONTRAST_EDGE";
    public static final String SHADER_UPSCALE_CUT2_PARAM_BLEND_MAX_CONTRAST_EDGE = "BLEND_MAX_CONTRAST_EDGE";
    public static final String SHADER_UPSCALE_CUT2_PARAM_BLEND_MIN_SHARPNESS = "BLEND_MIN_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT2_PARAM_BLEND_MAX_SHARPNESS = "BLEND_MAX_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT2_PARAM_STATIC_BLEND_SHARPNESS = "STATIC_BLEND_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT2_PARAM_EDGE_USE_FAST_LUMA = "EDGE_USE_FAST_LUMA";
    public static final String SHADER_UPSCALE_CUT2_PARAM_SOFT_EDGES_SHARPENING = "SOFT_EDGES_SHARPENING";
    public static final String SHADER_UPSCALE_CUT2_PARAM_SOFT_EDGES_SHARPENING_AMOUNT = "SOFT_EDGES_SHARPENING_AMOUNT";
    public static final String SHADER_UPSCALE_CUT2_PARAM_HARD_EDGES_SEARCH_MAX_ERROR = "HARD_EDGES_SEARCH_MAX_ERROR";

    public static final String SHADER_UPSCALE_CUT3_PARAM_USE_DYNAMIC_BLEND = "USE_DYNAMIC_BLEND";
    public static final String SHADER_UPSCALE_CUT3_PARAM_BLEND_MIN_CONTRAST_EDGE = "BLEND_MIN_CONTRAST_EDGE";
    public static final String SHADER_UPSCALE_CUT3_PARAM_BLEND_MAX_CONTRAST_EDGE = "BLEND_MAX_CONTRAST_EDGE";
    public static final String SHADER_UPSCALE_CUT3_PARAM_BLEND_MIN_SHARPNESS = "BLEND_MIN_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT3_PARAM_BLEND_MAX_SHARPNESS = "BLEND_MAX_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT3_PARAM_STATIC_BLEND_SHARPNESS = "STATIC_BLEND_SHARPNESS";
    public static final String SHADER_UPSCALE_CUT3_PARAM_EDGE_USE_FAST_LUMA = "EDGE_USE_FAST_LUMA";
    public static final String SHADER_UPSCALE_CUT3_PARAM_SOFT_EDGES_SHARPENING = "SOFT_EDGES_SHARPENING";
    public static final String SHADER_UPSCALE_CUT3_PARAM_SOFT_EDGES_SHARPENING_AMOUNT = "SOFT_EDGES_SHARPENING_AMOUNT";
    public static final String SHADER_UPSCALE_CUT3_PARAM_HARD_EDGES_SEARCH_MAX_ERROR = "HARD_EDGES_SEARCH_MAX_ERROR";
    public static final String SHADER_UPSCALE_CUT3_PARAM_HARD_EDGES_SEARCH_MAX_DISTANCE = "HARD_EDGES_SEARCH_MAX_DISTANCE";

    public static final int ERROR_LOAD_LIBRARY = 0;
    public static final int ERROR_LOAD_GAME = 1;
    public static final int ERROR_GL_NOT_COMPATIBLE = 2;
    public static final int ERROR_SERIALIZATION = 3;
    public static final int ERROR_CHEAT = 4;
    /** Genkan: the Vulkan context failed after the game loaded: the player starts it again on GL. */
    public static final int ERROR_VULKAN = 5;
    public static final int ERROR_GENERIC = -1;

    public static native void create(
        int GLESVersion,
        String coreFilePath,
        String systemDir,
        String savesDir,
        Variable[] variables,
        GLRetroShader shaderConfig,
        float refreshRate,
        boolean preferLowLatencyAudio,
        boolean enableVirtualFileSystem,
        boolean enableMicrophone,
        boolean skipDuplicateFrames,
        ImmersiveMode immersiveMode,
        String language
    );

    /**
     * Genkan: whether the core may draw with Vulkan (only a core and step that ask for it), and a
     * failure to force, to see the way back to GL (0 none, 1 the probe, 2 the device, 3 a lost
     * device). After create and before the game loads.
     */
    public static native void offerVulkan(boolean offered, int forcedFailure);

    /** Genkan: whether the core draws with Vulkan (its context made). */
    public static native boolean usesVulkan();

    public static native void loadGameFromPath(String gameFilePath);
    public static native void loadGameFromBytes(byte[] gameFileBytes);
    public static native void loadGameFromVirtualFiles(List<DetachedVirtualFile> virtualFiles);
    public static native void resume();

    public static native void onSurfaceCreated();
    public static native void onSurfaceChanged(int width, int height);

    public static native void pause();
    public static native void destroy();

    public static native void step(GLRetroView retroView);

    public static native void reset();

    public static native void setRumbleEnabled(boolean enabled);
    public static native void setFrameSpeed(int speed);
    public static native void setAudioEnabled(boolean enabled);
    public static native void setShaderConfig(GLRetroShader shader);
    public static native void setViewport(float x, float y, float width, float height);
    public static native void setViewportAlignment(int viewportAlignment);

    public static native byte[] serializeState();
    public static native boolean unserializeState(byte[] state);

    public static native void setCheat(int index, boolean enable, String code);
    public static native void resetCheat();

    public static native byte[] serializeSRAM();
    public static native boolean unserializeSRAM(byte[] sram);

    public static native void updateVariable(Variable variable);
    public static native Variable[] getVariables();

    public static native int availableDisks();
    public static native int currentDisk();
    public static native void changeDisk(int index);

    // Genkan: a swap in two steps (libretrodroid.h).
    public static native boolean ejectDisk();

    public static native boolean insertDisk(int index);

    public static native void onMotionEvent(int port, int motionSource, float xAxis, float yAxis);
    public static native void onTouchEvent(float xAxis, float yAxis);

    public static native void onKeyEvent(int port, int action, int keyCode);

    public static native void refreshAspectRatio();

    public static native Controller[][] getControllers();
    public static native void setControllerType(int port, int type);
}
