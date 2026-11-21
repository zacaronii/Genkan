package app.genkan.dictionary

/**
 * hoshidicts through JNI (src/main/cpp/dictionary_jni.cpp). Text goes in and out as UTF-8
 * bytes; results come back as JSON. Use [Dictionaries], which owns the native session.
 */
internal object NativeDictionaries {
    init {
        System.loadLibrary("genkan_dictionary")
    }

    external fun importZip(zip: ByteArray, outputDir: ByteArray, lowRam: Boolean): ByteArray

    /** 0 when one of the dictionaries would not open. */
    external fun open(
        terms: Array<ByteArray>,
        frequencies: Array<ByteArray>,
        pitches: Array<ByteArray>,
        kanji: Array<ByteArray>,
    ): Long

    external fun close(handle: Long)

    external fun scanLength(handle: Long): Int

    external fun lookup(handle: Long, text: ByteArray, maxResults: Int, scanLength: Int, frequencyDictionary: ByteArray): ByteArray

    /** The words of a line, for marking them: JSON [{"s", "l", "e", "r"}] (see dictionary_jni.cpp). */
    external fun scan(handle: Long, text: ByteArray, scanLength: Int, frequencyDictionary: ByteArray): ByteArray

    external fun kanji(handle: Long, character: ByteArray): ByteArray

    external fun styles(handle: Long): ByteArray

    external fun media(handle: Long, dictionary: ByteArray, path: ByteArray): ByteArray?
}
