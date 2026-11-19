// Hook addresses and text clean-up adapted from 0xDC00's Agent scripts (MIT):
// https://github.com/0xDC00/scripts PSP_ULJM06119_Dunamis15.js, PSP_NPJH50127_Tokimeki-Memorial-4.js
package app.genkan.ppsspp

import java.nio.charset.Charset

/**
 * Where a PSP game hands a line of text to its renderer: the code [address] whose entry sees the
 * string in [register] (a MIPS argument register, "a0" to "a3"), in [charset]. [clean] strips the
 * game's control codes and returns null for a call that isn't a line worth keeping.
 */
data class PspHook(
    val address: Long,
    val register: String,
    val charset: Charset,
    val clean: (String) -> String? = { it },
)

/** Known games, by the disc id PPSSPP reports (game.status), e.g. ULJM06119. */
object PspHooks {
    private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

    val byGameId: Map<String, List<PspHook>> = mapOf(
        // デュナミス15 (5pb./Kaleido): ruby as [reading]base, colour tags %0#rrggbbaa; ... %0#;
        "ULJM06119" to listOf(PspHook(0x0891D72CL, "a0", Charsets.UTF_8, ::dunamis15)),
        // ときめきメモリアル4: dialogue, mail and choices come through three calls.
        "NPJH50127" to listOf(
            PspHook(0x088719DCL, "a1", SHIFT_JIS) { it.replace(Regex("^�.*$|e_voice", RegexOption.MULTILINE), "") },
            PspHook(0x0899A510L, "a2", SHIFT_JIS) { it.replace("\\n", "\n") },
            // Dialogue passes here too; only lines without closing punctuation are choices.
            PspHook(0x08850270L, "a1", SHIFT_JIS) { s -> s.takeIf { Regex("[^…。！？]$").containsMatchIn(it) } },
        ),
    )

    fun dunamis15(raw: String): String = raw
        .replace(Regex("\\[[^\\]]+."), "")
        .replace(Regex("\\\\k|\\\\x|%C|%B"), "")
        .replace(Regex("%\\d+#[0-9a-fA-F]*;"), "")
        .replace(Regex("\n+"), "")

    /** The string at the start of [bytes], up to its NUL terminator. */
    fun decode(bytes: ByteArray, charset: Charset): String {
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return String(bytes, 0, end, charset)
    }
}
