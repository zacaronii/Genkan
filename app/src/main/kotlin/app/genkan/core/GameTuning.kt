package app.genkan.core

import android.os.Build
import app.genkan.yomikata.YomikataCatalog
import app.genkan.yomikata.YomikataIds
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which ladder step a game plays at in the built-in player (see [TuningLadders.resolve]): the
 * device's override, the catalog's profile for its release, or the core's default. Only what's on
 * the device is read, never the network: the release id learned when the game last ran, and the
 * catalog's cached entry.
 */
@Singleton
class GameTuning @Inject constructor(
    private val store: TuningStore,
    private val ids: YomikataIds,
    private val catalog: YomikataCatalog,
) {
    /**
     * The step for RomM rom [romId] on ladder [tuning] (its core's CoreDef.tuning); null when the
     * core has no ladder. [releaseId], when known now.
     */
    suspend fun resolve(romId: Long, tuning: String, releaseId: String? = null): ResolvedTuning? {
        val ladder = TuningLadders.of(tuning) ?: return null
        val release = releaseId ?: ids.idFor(romId).first()
        val bundled = release?.let { catalog.cachedEntry(it)?.tuning?.get(tuning) }
        return TuningLadders.resolve(ladder, store.override(romId)?.step, bundled, Build.MODEL.orEmpty())
    }
}
