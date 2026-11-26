package app.genkan.core

import app.genkan.yomikata.YomikataCatalog
import app.genkan.yomikata.YomikataIds
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Wii controller a game is played with, as its catalog entry says (`wii_controller`, Yomikata's
 * docs/format.md): the Wiimote with a Nunchuk (also for a game that wants the Wiimote alone), the
 * Classic Controller, or the Wiimote held sideways. Dolphin plugs it in through one core option.
 */
object WiiController {
    /** Dolphin's option for the device on every Wii port; its values are the catalog's own words. */
    const val OPTION = "dolphin_wiimote_default_device"
    const val NUNCHUK = "nunchuk"
    val CHOICES = setOf(NUNCHUK, "classic", "sideways")

    /** [value] when it's one of the catalog's choices, else null. */
    fun of(value: String?): String? = value?.takeIf { it in CHOICES }
}

/**
 * Which Wii controller a game plays with in the built-in player: its catalog entry's, else the
 * Nunchuk. Only what's on the device is read, as for [GameTuning]: the release id learned when the
 * game last ran, and the catalog's cached entry.
 */
@Singleton
class GameWiiController @Inject constructor(
    private val ids: YomikataIds,
    private val catalog: YomikataCatalog,
) {
    /** The controller for RomM rom [romId], or for release [releaseId] when known now; null when the release isn't known yet. */
    suspend fun resolve(romId: Long, releaseId: String? = null): String? {
        val release = releaseId ?: ids.idFor(romId).first() ?: return null
        return catalog.cachedEntry(release)?.wiiController ?: WiiController.NUNCHUK
    }
}
