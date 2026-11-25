package app.genkan.armsx2

import app.genkan.ppsspp.Ini

/**
 * ARMSX2's settings file and the one switch Genkan changes in it: PINE, off unless asked for.
 * ARMSX2 keeps `PCSX2-Android.ini` in the folder its setup wizard was given (under shared storage),
 * else in its own Android/data folder, and reads PINE's switch as a game starts.
 */
object ArmsxIni {
    const val FILE = "PCSX2-Android.ini"
    /** Its own data folder, under shared storage, when the wizard wasn't given another. */
    const val OWN_FOLDER = "Android/data/com.armsx2/files"
    private const val SECTION = "EmuCore"
    private const val PINE = "EnablePINE"
    private const val SLOT = "PINESlot"
    /** ARMSX2's own default for the switch, put back when the file had none. */
    private const val PINE_DEFAULT = "false"

    /** The file's PINE switch as it was before Genkan changed it; "" when it had none. */
    fun pineValue(text: String): String = Ini.get(text, SECTION, PINE).orEmpty()

    /** The port PINE listens on: the file's slot, else ARMSX2's default. */
    fun port(text: String): Int = Ini.get(text, SECTION, SLOT)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: Pine.PORT

    /** [text] with PINE switched on. */
    fun withPine(text: String): String = Ini.set(text, SECTION, PINE, "true")

    /** [text] with PINE's switch put back to [saved] (what [pineValue] read), or ARMSX2's default. */
    fun restored(text: String, saved: String): String = Ini.set(text, SECTION, PINE, saved.ifEmpty { PINE_DEFAULT })
}
