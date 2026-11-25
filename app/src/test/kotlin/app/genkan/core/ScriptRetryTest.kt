package app.genkan.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRetryTest {
    private val anotherBuild = "this script is for another build of the game"

    @Test
    fun `another build is tried again through the game's first minute`() {
        assertTrue(ScriptRetry.again(anotherBuild, nowMs = 59_999, startedMs = 0, stateLoadedMs = null))
        assertFalse(ScriptRetry.again(anotherBuild, nowMs = 60_000, startedMs = 0, stateLoadedMs = null))
    }

    @Test
    fun `and through a minute after a savestate load`() {
        assertTrue(ScriptRetry.again(anotherBuild, nowMs = 200_000, startedMs = 0, stateLoadedMs = 150_000))
        assertFalse(ScriptRetry.again(anotherBuild, nowMs = 210_000, startedMs = 0, stateLoadedMs = 150_000))
    }

    @Test
    fun `any other refusal is final`() {
        for (error in listOf("the script needs a later reader runtime (level 2, this one is 1)", "the script needs vram, which this host doesn't give out", "[string \"x.lua\"]:1: syntax error")) {
            assertFalse(error, ScriptRetry.again(error, nowMs = 1, startedMs = 0, stateLoadedMs = null))
        }
    }
}
