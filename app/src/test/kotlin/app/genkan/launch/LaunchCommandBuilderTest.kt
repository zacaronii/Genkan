package app.genkan.launch

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LaunchCommandBuilderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val builder = LaunchCommandBuilder(
        externalRoot = "/storage/emulated/0",
        contentUri = { "content://app.genkan.fileprovider/storage/ROMs/${it.parentFile!!.name}/${it.name}" },
    )

    private fun emulator(id: String) = EmulatorCatalog.emulators.single { it.id == id }
    private fun extras(command: LaunchCommand) = command.extras.associate { it.key to it }

    @Test
    fun `RetroArch gets the ROM path, the platform's core and its own config`() {
        val rom = File("/storage/emulated/0/ROMs/gba/Golden Sun.gba")
        val command = builder.build(emulator("retroarch_64"), rom, "gba", null)!!
        assertEquals(Intent.ACTION_MAIN, command.action)
        assertEquals("com.retroarch.browser.retroactivity.RetroActivityFuture", command.activity)
        val extras = extras(command)
        assertEquals(ResolvedExtra.StringExtra("ROM", rom.absolutePath), extras["ROM"])
        assertEquals(
            ResolvedExtra.StringExtra("LIBRETRO", "/data/data/com.retroarch.aarch64/cores/mgba_libretro_android.so"),
            extras["LIBRETRO"],
        )
        assertEquals(
            ResolvedExtra.StringExtra("CONFIGFILE", "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg"),
            extras["CONFIGFILE"],
        )
        assertTrue(command.flags and Intent.FLAG_ACTIVITY_NO_HISTORY != 0)
        assertEquals(LaunchMethod.INTENT, command.method)
    }

    @Test
    fun `RetroArch has a core for every platform it is offered for`() {
        for (platform in emulator("retroarch").platforms) {
            assertTrue(platform, builder.build(emulator("retroarch"), File("/x/$platform/a.bin"), platform, null) != null)
        }
        assertNull(builder.build(emulator("retroarch"), File("/x/a.iso"), "ps2", null))
    }

    @Test
    fun `DuckStation gets a plain path from build 10000, a content URI before`() {
        val rom = File("/storage/emulated/0/ROMs/psx/Crash Bandicoot (USA).chd")
        val modern = extras(builder.build(emulator("duckstation"), rom, "psx", "0.1-10232")!!)
        assertEquals(ResolvedExtra.StringExtra("bootPath", rom.absolutePath), modern["bootPath"])
        assertEquals(ResolvedExtra.BoolExtra("resumeState", false), modern["resumeState"])
        val old = builder.build(emulator("duckstation"), rom, "psx", "0.1-7000")!!
        assertTrue((extras(old)["bootPath"] as ResolvedExtra.StringExtra).value.startsWith("content://"))
        assertEquals(old.grantReadUris.single(), (extras(old)["bootPath"] as ResolvedExtra.StringExtra).value)
    }

    @Test
    fun `an m3u always goes to DuckStation as a path, whatever the build`() {
        val m3u = File("/storage/emulated/0/ROMs/psx/FF7/FF7.m3u")
        val extras = extras(builder.build(emulator("duckstation"), m3u, "psx", null)!!)
        assertEquals(ResolvedExtra.StringExtra("bootPath", m3u.absolutePath), extras["bootPath"])
    }

    @Test
    fun `Dolphin gets a granted content URI in AutoStartFile, launched from the shell`() {
        val command = builder.build(emulator("dolphin"), File("/storage/emulated/0/ROMs/ngc/Wind Waker.rvz"), "ngc", null)!!
        val uri = (extras(command)["AutoStartFile"] as ResolvedExtra.UriExtra).uri
        assertTrue(uri.startsWith("content://"))
        assertEquals(listOf(uri), command.grantReadUris)
        assertEquals(LaunchMethod.SHELL, command.method)
    }

    @Test
    fun `Azahar and ARMSX2 are VIEW launches with the ROM as granted data`() {
        val azahar = builder.build(emulator("azahar"), File("/storage/emulated/0/ROMs/3ds/Pokemon Y.3ds"), "3ds", null)!!
        assertEquals(Intent.ACTION_VIEW, azahar.action)
        assertEquals(azahar.dataUri, azahar.grantReadUris.single())
        assertEquals(ResolvedExtra.StringExtra("SelectedGame", "/storage/emulated/0/ROMs/3ds/Pokemon Y.3ds"), extras(azahar)["SelectedGame"])
        assertTrue(emulator("azahar").drawsBothScreens)
        val armsx2 = builder.build(emulator("armsx2_refresh"), File("/storage/emulated/0/ROMs/ps2/FFX.chd"), "ps2", null)!!
        assertEquals(Intent.ACTION_VIEW, armsx2.action)
        assertTrue(armsx2.dataUri!!.startsWith("content://"))
    }

    @Test
    fun `the shell command targets the top screen and quotes awkward names`() {
        val command = builder.build(emulator("azahar"), File("/storage/emulated/0/ROMs/3ds/Kirby's Dream (USA).3ds"), "3ds", null)!!
        val shell = command.toShellArgv().last()
        assertTrue(shell, shell.startsWith("/system/bin/am start --display 0 "))
        assertTrue(shell, shell.contains("""--es 'SelectedGame' '/storage/emulated/0/ROMs/3ds/Kirby'\''s Dream (USA).3ds'"""))
        assertTrue(shell, shell.contains("--grant-read-uri-permission"))
    }

    @Test
    fun `every catalog platform uses a RomM slug Genkan's library knows`() {
        val rommSlugs = setOf("nes", "snes", "gb", "gbc", "gba", "n64", "psx", "ps2", "psp", "ngc", "wii", "3ds")
        EmulatorCatalog.emulators.forEach { assertTrue(it.id, rommSlugs.containsAll(it.platforms)) }
    }

    @Test
    fun `a multi-file game boots its playlist, else its cue, else its biggest file`() {
        val game = tmp.newFolder("Xenosaga II")
        File(game, ".Disc 1.chd").writeBytes(ByteArray(10))
        File(game, ".Disc 2.chd").writeBytes(ByteArray(20))
        assertEquals(".Disc 2.chd", LaunchCommandBuilder.bootFileIn(game)!!.name)
        File(game, "Xenosaga II.m3u").writeText(".Disc 1.chd\n.Disc 2.chd\n")
        assertEquals("Xenosaga II.m3u", LaunchCommandBuilder.bootFileIn(game)!!.name)
    }
}
