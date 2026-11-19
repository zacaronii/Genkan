// SPDX-FileCopyrightText: Furoshiki contributors
// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from Furoshiki home/tests/tests.cpp (sommepo/furoshiki @ 738ccf5), the Lines cases.

package app.genkan.reader

import app.genkan.reader.Lines.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineFeedTest {

    @Test
    fun `compare tells new lines from repeats and pieces`() {
        assertEquals(Verdict.NEW, Lines.compare("", "こんにちは"))
        assertEquals(Verdict.SAME, Lines.compare("こんにちは", "こんにちは"))
        assertEquals(Verdict.SAME, Lines.compare("こんにちは", "こんにちは。"))
        assertEquals(Verdict.CARRIES_ON, Lines.compare("この先の森", "この先の森には近づくな"))
        assertEquals(Verdict.PART_OF, Lines.compare("この先の森には近づくな", "この先の森"))
        assertEquals(Verdict.NEW, Lines.compare("はい", "いいえ"))
        assertEquals(Verdict.JUNK, Lines.compare("はい", "▼"))
        assertEquals(Verdict.JUNK, Lines.compare("はい", "Yes"))
        assertEquals(Verdict.JUNK, Lines.compare("はい", ""))
        assertEquals(Verdict.NEW, Lines.compare("青ルピーを手に入れた", "緑ルピーを手に入れた"))
        assertEquals(Verdict.NEW, Lines.compare("ありがとうございます", "本当にありがとうございます"))
    }

    @Test
    fun `normalise keeps one line and drops layout spaces`() {
        assertEquals("この先の森には近づくな", Lines.normalise("  この先の\n森には　近づくな "))
        assertEquals("Steins Gateの世界", Lines.normalise("Steins Gate の 世界"))
        assertTrue(Lines.isJunk("123 abc"))
        assertFalse(Lines.isJunk("ｱ"))
        assertFalse(Lines.isJunk("𠮟る"))
        assertEquals("はい行こう", Lines.key("「はい、行こう！」"))
    }

    @Test
    fun `a line's source id ignores spaces and punctuation`() {
        assertEquals(Lines.sourceId("はい、行こう"), Lines.sourceId("はい行こう。"))
        assertEquals(18, Lines.sourceId("はい").length)
        assertTrue(Lines.sourceId("はい") != Lines.sourceId("いいえ"))
    }

    @Test
    fun `message text reads plain frames and texthooker json`() {
        assertEquals("ただの行", Lines.messageText("ただの行"))
        assertEquals("文です", Lines.messageText("""{"sentence":"文です","process_path":"C:/game.exe"}"""))
        assertEquals("行です", Lines.messageText(""" {"text":"行です"}"""))
        assertEquals("", Lines.messageText("""{"type":"status"}"""))
        assertEquals("{かっこで始まる行", Lines.messageText("{かっこで始まる行"))
    }

    @Test
    fun `feed hands over whole lines once`() {
        // In order: a line waits a moment for more of itself, and is then a line.
        val feed = LineFeed()
        assertTrue(feed.take("この先", 1000).isEmpty() && feed.hasPending)
        assertTrue(feed.take("この先の森には", 1040).isEmpty())
        assertTrue(feed.take("この先の森には近づくな", 1080).isEmpty())
        assertEquals(LineFeed.Out("この先の森には近づくな"), feed.settle(1230))
        assertFalse(feed.hasPending)
        assertNull(feed.settle(1300))
        // The same again, and its beginning again, are dropped; English and marks too.
        assertTrue(feed.take("この先の森には近づくな。", 1500).isEmpty() && !feed.hasPending)
        assertTrue(feed.take("この先の森", 1600).isEmpty() && !feed.hasPending)
        assertTrue(feed.take("Loading...", 1700).isEmpty() && !feed.hasPending)
        // More of a line already handed over: the whole takes the part back.
        assertTrue(feed.take("この先の森には近づくな、と彼は言った", 2000).isEmpty() && feed.hasPending)
        assertEquals("この先の森には近づくな", feed.settle(2150)?.takesBack)
        // Another line while one waits: the one that waited is handed over at once.
        assertTrue(feed.take("はい、行こう", 3000).isEmpty())
        assertEquals(listOf(LineFeed.Out("はい、行こう")), feed.take("いいえ", 3050))
        assertEquals("いいえ", feed.settle(3200)?.text)
        // Long after, a line's beginning is a line of its own (はい after はい、行こう).
        assertTrue(feed.take("はい、行こう", 4000).isEmpty())
        assertTrue(feed.settle(4150) != null)
        assertTrue(feed.take("はい", 4150 + LineFeed.CARRY_MS + 1).isEmpty() && feed.hasPending)
        assertEquals(LineFeed.Out("はい"), feed.settle(20000))
    }
}
