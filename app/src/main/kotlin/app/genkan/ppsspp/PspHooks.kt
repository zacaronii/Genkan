// Hook addresses and text clean-up adapted from 0xDC00's Agent scripts (MIT):
// https://github.com/0xDC00/scripts PSP_ULJM06119_Dunamis15.js, PSP_NPJH50127_Tokimeki-Memorial-4.js
// and from LunaHook's PPSSPP table (GPL-3.0), LunaTranslator
// src/NativeImpl/LunaHook/LunaHook/emulators/ppsspp_1.cpp: My Merry May with be, Danganronpa 1-2, CLANNAD,
// Grisaia (with util/stringfilters.cpp's katakana remap).
package app.genkan.ppsspp

import app.genkan.memory.MemoryScan
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
    val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

    /**
     * Profiles from before the Yomikata catalog carried hooks, used only when it gives none for
     * the game: no catalog set up, or no copy of it on the device. They cover NPJH50127,
     * NPJH50515, NPJH50631, ULJM05338/05339, ULJM05603, ULJM06119 and ULJM06232/06233, all of which
     * the catalog now lists with hooks (the second ids of a pair as aliases); they can go once
     * Genkan always has a catalog.
     */
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
        // My Merry May with be
        "ULJM05603" to listOf(PspHook(0x0886F014L, "a3", SHIFT_JIS, ::myMerryMay)),
        // ダンガンロンパ 希望の学園と絶望の高校生 (PSP the Best), スーパーダンガンロンパ2
        "NPJH50515" to listOf(PspHook(0x088540B0L, "a0", Charsets.UTF_16LE, ::danganronpa)),
        "NPJH50631" to listOf(PspHook(0x088D1A24L, "a0", Charsets.UTF_16LE, ::danganronpa)),
        // グリザイアの果実 (both discs): hiragana stored as half-width katakana
        "ULJM06232" to listOf(PspHook(0x08840324L, "t0", SHIFT_JIS, ::grisaia)),
        "ULJM06233" to listOf(PspHook(0x08840324L, "t0", SHIFT_JIS, ::grisaia)),
        // CLANNAD (both discs)
        "ULJM05338" to listOf(PspHook(0x0880F240L, "a0", Charsets.UTF_16LE)),
        "ULJM05339" to listOf(PspHook(0x0880F240L, "a0", Charsets.UTF_16LE)),
    )

    private val MERRY_MAY_CODES = Regex("%K|%P|%p|%N|%V|%LC|%LE|%FS|%FE|%CFFFF|☆")

    fun myMerryMay(raw: String): String = raw.replace(MERRY_MAY_CODES, "").replace('├', 'ー').replace('┤', 'ー')

    private val KANA_REMAP: Map<Char, Char> = buildMap {
        "ｧｨｩｪｫｬｭｮｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜｦﾝｯ"
            .zip("ぁぃぅぇぉゃゅょあいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをんっ")
            .forEach { (from, to) -> put(from, to) }
        putAll(mapOf('｢' to '「', '｣' to '」', 'ｰ' to 'ー', '､' to '、', '?' to '？', '｡' to '。', '!' to '！', '○' to '〇', '･' to '…', 'ﾟ' to '？', 'ﾞ' to '！'))
    }

    fun grisaia(raw: String): String = raw.map { KANA_REMAP[it] ?: it }.joinToString("").replace(Regex("\\\$t(.*?)@"), "$1")

    fun danganronpa(raw: String): String = raw.replace("\n", "").replace(Regex("<CLT \\d+>(.*?)<CLT>"), "$1")

    fun dunamis15(raw: String): String = raw
        .replace(Regex("\\[[^\\]]+."), "")
        .replace(Regex("\\\\k|\\\\x|%C|%B"), "")
        .replace(Regex("%\\d+#[0-9a-fA-F]*;"), "")
        .replace(Regex("\n+"), "")

    /** The string at the start of [bytes], up to its NUL terminator (two bytes for UTF-16). */
    fun decode(bytes: ByteArray, charset: Charset): String {
        val end = if (MemoryScan.unit(charset) == 2) {
            (0 until bytes.size - 1 step 2).firstOrNull { bytes[it] == 0.toByte() && bytes[it + 1] == 0.toByte() } ?: (bytes.size and 1.inv())
        } else {
            bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        }
        return String(bytes, 0, end, charset)
    }
}
