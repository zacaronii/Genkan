package app.genkan.core

/**
 * Turns a single-pass RetroArch GLSL shader (the libretro/glsl-shaders kind: one file, its vertex
 * and fragment stages under `#if defined(VERTEX)` / `#elif defined(FRAGMENT)`) into the two
 * sources the player library's renderer takes, untouched otherwise.
 *
 * RetroArch hands such a shader `VertexCoord`, `TexCoord`, `MVPMatrix`, `Texture`, `TextureSize`,
 * `InputSize`, `OutputSize`, `FrameCount` and, as one pass is also the first, `OrigInputSize` and
 * `OrigTextureSize`; the renderer gives `vPosition`, `vCoordinate`,
 * `texture`, `textureSize` and `screenDensity` (the picture's scale on the screen). So the file's
 * own declarations of those names are left out and each name is defined as the renderer's
 * counterpart: `OutputSize` is `textureSize * screenDensity`, the size the picture is drawn at.
 * Its `#pragma parameter`s keep their defaults (PARAMETER_UNIFORM is never defined). A file's
 * `#define texture(…)` (a GLSL 1.30 shim) goes too: the renderer's sampler is called `texture`,
 * and Adreno's preprocessor fails on the two together (zfast_crt's, which never calls it).
 */
object RetroArchShader {
    data class Sources(val vertex: String, val fragment: String)

    /** The names RetroArch declares that the renderer gives under its own: their declarations go. */
    private val PROVIDED = setOf(
        "VertexCoord", "TexCoord", "COLOR", "MVPMatrix", "Texture", "TextureSize", "InputSize", "OutputSize",
        "OrigInputSize", "OrigTextureSize", "FrameCount", "FrameDirection",
    )

    /** A function-like macro named texture. */
    private val TEXTURE_MACRO = Regex("""^\s*#\s*define\s+texture\s*\(""")

    /**
     * A declaration line: a qualifier (or a file's macro for one), maybe a precision, a type, and
     * the one name it declares (group 2; its type is group 1).
     */
    private val DECLARATION = Regex("""^\s*(?:uniform|attribute|COMPAT_ATTRIBUTE|ATTRIBUTE|in|IN)\s+(?:\w+\s+)?(\w+)\s+(\w+)\s*;\s*(?://.*)?$""")

    private const val PRECISION = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
#define GENKAN_HIGHP highp
#else
#define GENKAN_HIGHP mediump
#endif
"""

    private const val NAMES = """
uniform GENKAN_HIGHP vec2 textureSize;
uniform mediump float screenDensity;
#define TextureSize textureSize
#define InputSize textureSize
#define OrigTextureSize textureSize
#define OrigInputSize textureSize
#define OutputSize (textureSize * screenDensity)
#define FrameCount 0
#define FrameDirection 1
"""

    private const val VERTEX = "#define VERTEX\n" + PRECISION + """
attribute vec4 vPosition;
attribute vec2 vCoordinate;
#define VertexCoord vPosition
#define COLOR vec4(1.0)
#define MVPMatrix mat4(1.0)
""" + NAMES

    private const val FRAGMENT = "#define FRAGMENT\n" + PRECISION + """
precision mediump float;
uniform lowp sampler2D texture;
#define Texture texture
""" + NAMES

    fun adapt(source: String): Sources {
        // TexCoord as the file declares it: most take a vec4, some a vec2.
        var texCoordType = "vec4"
        val body = source.lines().filterNot { line ->
            if (TEXTURE_MACRO.containsMatchIn(line)) return@filterNot true
            val declared = DECLARATION.find(line) ?: return@filterNot false
            val (type, name) = declared.destructured
            if (name == "TexCoord") texCoordType = type
            name in PROVIDED
        }.joinToString("\n")
        val texCoord = if (texCoordType == "vec2") "#define TexCoord vCoordinate\n" else "#define TexCoord vec4(vCoordinate, 0.0, 0.0)\n"
        return Sources(VERTEX + texCoord + "#line 1\n" + body, FRAGMENT + "#line 1\n" + body)
    }

    /** The names whose declarations [adapt] leaves out, for the tests. */
    internal val provided: Set<String> get() = PROVIDED
}
