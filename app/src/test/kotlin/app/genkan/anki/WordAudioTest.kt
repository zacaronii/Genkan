package app.genkan.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordAudioTest {

    @Test
    fun `the source URL takes the word and its reading`() {
        assertEquals(
            "https://audio.example/audio/list?term=%E5%85%89&reading=%E3%81%B2%E3%81%8B%E3%82%8A",
            WordAudio.listUrl("https://audio.example/audio/list?term={term}&reading={reading}", "光", "ひかり"),
        )
        // No reading: the word stands in.
        assertEquals("https://a.example/?t=a%20b&r=a%20b", WordAudio.listUrl("https://a.example/?t={term}&r={reading}", "a b", ""))
        assertNull(WordAudio.listUrl("", "光", "ひかり"))
        assertNull(WordAudio.listUrl("https://a.example/list", "光", "ひかり"))
    }

    @Test
    fun `the first recording is taken, text-to-speech only when there is nothing else`() {
        val list = """{"type":"audioSourceList","audioSources":[""" +
            """{"name":"TTS (voice)","url":"https://a.example/audio/tts?term=x"},""" +
            """{"name":"nhk16","url":"https://a.example/audio/get/nhk16/1.mp3"},""" +
            """{"name":"forvo","url":"https://a.example/audio/get/forvo/2.mp3"}]}"""
        assertEquals("https://a.example/audio/get/nhk16/1.mp3", WordAudio.choose(list))
        val ttsOnly = """{"type":"audioSourceList","audioSources":[{"name":"TTS (voice): 0","url":"https://a.example/audio/tts?term=x"}]}"""
        assertEquals("https://a.example/audio/tts?term=x", WordAudio.choose(ttsOnly))
        assertNull(WordAudio.choose("""{"type":"audioSourceList","audioSources":[]}"""))
        assertNull(WordAudio.choose("not json"))
    }
}
