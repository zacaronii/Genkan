package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RetroArchShaderTest {
    private val sample = """
        #if defined(VERTEX)
        COMPAT_ATTRIBUTE vec4 VertexCoord;
        COMPAT_ATTRIBUTE vec4 TexCoord;
        COMPAT_VARYING vec4 TEX0;
        uniform mat4 MVPMatrix;
        uniform COMPAT_PRECISION vec2 TextureSize;
        uniform COMPAT_PRECISION vec2 OutputSize; // the viewport
        uniform COMPAT_PRECISION float SCANLINE;
        void main() { gl_Position = MVPMatrix * VertexCoord; TEX0 = TexCoord; }
        #elif defined(FRAGMENT)
        uniform sampler2D Texture;
        COMPAT_VARYING vec4 TEX0;
        void main() { gl_FragColor = texture2D(Texture, TEX0.xy); }
        #endif
    """.trimIndent()

    @Test
    fun `the declarations the renderer provides go, the file's own stay`() {
        val out = RetroArchShader.adapt(sample)
        for (stage in listOf(out.vertex, out.fragment)) {
            assertFalse(stage.contains("COMPAT_ATTRIBUTE vec4 VertexCoord;"))
            assertFalse(stage.contains("uniform mat4 MVPMatrix;"))
            assertFalse(stage.contains("uniform COMPAT_PRECISION vec2 OutputSize;"))
            assertFalse(stage.contains("uniform sampler2D Texture;"))
            assertTrue(stage.contains("uniform COMPAT_PRECISION float SCANLINE;"))
            assertTrue(stage.contains("COMPAT_VARYING vec4 TEX0;"))
            assertTrue(stage.contains("#define OutputSize (textureSize * screenDensity)"))
        }
        assertTrue(out.vertex.startsWith("#define VERTEX\n"))
        assertTrue(out.vertex.contains("#define TexCoord vec4(vCoordinate, 0.0, 0.0)"))
        assertTrue(out.fragment.startsWith("#define FRAGMENT\n"))
        assertTrue(out.fragment.contains("#define Texture texture"))
    }

    @Test
    fun `a file that takes TexCoord as a vec2 gets one`() {
        val out = RetroArchShader.adapt("#if defined(VERTEX)\nIN  vec2 TexCoord;\nIN  vec4 VertexCoord;\n#endif")
        assertTrue(out.vertex.contains("#define TexCoord vCoordinate\n"))
        assertFalse(out.vertex.contains("IN  vec2 TexCoord;"))
    }

    @Test
    fun `no bundled shader keeps a declaration of a provided name`() {
        val declaration = Regex("""^\s*(?:uniform|attribute|COMPAT_ATTRIBUTE|ATTRIBUTE|in|IN)\s+(?:\w+\s+)?\w+\s+(\w+)\s*;""")
        val files = File("src/main/assets/shaders").listFiles().orEmpty().filter { it.name.endsWith(".glsl") }
        assertEquals(12, files.size)
        for (file in files) {
            val out = RetroArchShader.adapt(file.readText())
            for (stage in listOf(out.vertex, out.fragment)) {
                val left = stage.lines().mapNotNull { declaration.find(it)?.groupValues?.get(1) }.filter { it in RetroArchShader.provided }
                assertEquals(file.name, emptyList<String>(), left)
            }
        }
    }

    @Test
    fun `a file's texture() macro goes, as the sampler has that name`() {
        val out = RetroArchShader.adapt("#elif defined(FRAGMENT)\n#define texture(c, d) COMPAT_TEXTURE(c, d)\n#define Source Texture\n")
        assertFalse(out.fragment.contains("#define texture("))
        assertTrue(out.fragment.contains("#define Source Texture"))
    }

    @Test
    fun `after adapting, a bundled shader declares no uniform but its own parameters`() {
        val uniform = Regex("""^\s*uniform\s+(?:\w+\s+)?\w+\s+(\w+)\s*;""")
        val parameter = Regex("""#pragma parameter (\w+)""")
        for (file in File("src/main/assets/shaders").listFiles().orEmpty().filter { it.name.endsWith(".glsl") }) {
            val text = file.readText()
            val parameters = parameter.findAll(text).map { it.groupValues[1] }.toSet()
            val out = RetroArchShader.adapt(text)
            for (stage in listOf(out.vertex, out.fragment)) {
                val unknown = stage.lines().mapNotNull { uniform.find(it)?.groupValues?.get(1) }
                    .filterNot { it in parameters || it in setOf("textureSize", "screenDensity", "texture") }
                assertEquals(file.name, emptyList<String>(), unknown)
            }
        }
    }
}
