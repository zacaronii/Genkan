package app.genkan.library

import android.content.Context
import app.genkan.art.RomMArt
import app.genkan.launch.LocalShelves
import app.genkan.romm.RomMSession
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sign

/**
 * Fetches the artwork of the games around the focused one into Coil's disk cache, so moving along
 * the shelf finds it already on the device. Only games near the focus are fetched, never the
 * whole library. The requests go through the app's singleton ImageLoader, which carries the RomM
 * sign-in (see GenkanApp.newImageLoader).
 *
 * While the focus is moving, the warmer reaches [AHEAD] games in the direction of travel and only
 * [BEHIND] back, and keeps a fetch running as long as its path is still wanted: cancelling
 * everything on each step, 20 times a second while a direction is held, meant nothing ever
 * finished. Once the focus rests ([warmAround] with `moving = false`) it fetches every case the
 * shelf shows and the focused game's disc or cartridge.
 */
@Singleton
class ArtworkWarmer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: RomMSession,
) {
    private var inFlight: Map<String, Disposable> = emptyMap()
    private var lastPaths: List<String> = emptyList()
    private var lastGames: List<GameEntity>? = null
    private var lastIndex = -1
    private var direction = 0

    /** Paths already in the disk cache, newest last, so they aren't fetched again. */
    private val warmed = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > WARMED_MEMORY
    }

    /**
     * Warms around [index] in [games]: ahead of the focus while [moving], everything the shelf
     * shows when not. With the [fullBox] setting on, the faces the box draws too (see [warmPaths]).
     */
    fun warmAround(games: List<GameEntity>, index: Int, moving: Boolean = false, fullBox: Boolean = false, showsBack: Boolean = false) {
        if (games === lastGames && index != lastIndex) direction = (index - lastIndex).sign
        if (games !== lastGames) direction = 0
        lastGames = games
        lastIndex = index
        // Signed out: nothing is remembered as wanted, so the same focus warms once the sign-in
        // is back instead of matching lastPaths and returning early.
        val base = baseUrl()
        if (base == null) {
            inFlight.values.forEach { if (!it.isDisposed) it.dispose() }
            inFlight = emptyMap()
            lastPaths = emptyList()
            return
        }
        val paths = warmPaths(games, index, if (moving) direction else 0, fullBox, showsBack).filterNot { it in warmed }
        if (paths == lastPaths) return
        lastPaths = paths
        val wanted = paths.toSet()
        inFlight.filterKeys { it !in wanted }.values.forEach { if (!it.isDisposed) it.dispose() }
        val kept = inFlight
        inFlight = buildMap {
            for (path in paths) {
                val running = kept[path]?.takeUnless { it.isDisposed }
                val request = running ?: fetch(path) { warmed[path] = Unit }
                if (request != null) put(path, request)
            }
        }
    }

    /** The signed-in server's address, or null when signed out; replaceable in tests. */
    internal var baseUrl: () -> String? = { session.currentAccount()?.baseUrl }

    /** Starts fetching [path] into the disk cache, calling [onDone] when it is there; replaceable in tests. */
    internal var fetch: (path: String, onDone: () -> Unit) -> Disposable = { path, onDone ->
        // Disk only, decoded at a token size: the screen decodes at its own size later, so a
        // full-size bitmap now would cost CPU while the shelf animates, and push images that are
        // on screen out of the memory cache.
        SingletonImageLoader.get(context).enqueue(
            ImageRequest.Builder(context)
                .data(RomMArt(path))
                .size(WARM_DECODE_PX)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .listener(onSuccess = { _, _ -> onDone() })
                .build()
        )
    }

    companion object {
        /** How many games either side of the focused one get their disc or cartridge warmed at rest. */
        const val RADIUS = 3
        /** How many games either side get their cover and spine warmed at rest: the cases on screen. */
        const val VISIBLE = 7
        /** How far ahead of a moving focus covers and spines are fetched. */
        const val AHEAD = 12
        /** How far behind a moving focus they are kept, in case it stops and turns back. */
        const val BEHIND = 2
        /** Decode size for a warming request; the decode is thrown away. */
        private const val WARM_DECODE_PX = 32
        /** How many fetched paths are remembered as already on disk. */
        private const val WARMED_MEMORY = 512

        /**
         * The asset paths to warm for the focus at [index], nearest first. At rest ([direction]
         * 0): the cover (the large one, or the small one when there is no large) and spine of each
         * of the [VISIBLE] games either side, the logo of a game without a cover, and the disc or
         * cartridge cut-out within [RADIUS]. Moving (1 right, -1 left): covers, spines and logos
         * [AHEAD] games that way and [BEHIND] the other, with no cut-outs. With the [fullBox]
         * setting on, each of those games it can draw also has its box's front warmed with its
         * spine, since any of them may be the next focused; the back only for the focused game,
         * once the focus rests, and only when it may be shown ([showsBack]: R3 can turn the box at
         * any time), never for the games a moving focus passes. Games from local shelves have no
         * RomM artwork.
         */
        fun warmPaths(
            games: List<GameEntity>,
            index: Int,
            direction: Int,
            fullBox: Boolean = false,
            showsBack: Boolean = false,
        ): List<String> {
            if (games.isEmpty() || index !in games.indices) return emptyList()
            val offsets = if (direction == 0) {
                listOf(0) + (1..VISIBLE).flatMap { listOf(-it, it) }
            } else {
                listOf(0) + (1..AHEAD).map { it * direction } + (1..BEHIND).map { -it * direction }
            }
            return offsets.mapNotNull { offset -> games.getOrNull(index + offset)?.let { offset to it } }
                .filterNot { (_, game) -> LocalShelves.isLocal(game) }
                .flatMap { (offset, game) ->
                    val cover = game.coverLargePath ?: game.coverPath
                    val physical = game.physicalPath.takeIf { direction == 0 && kotlin.math.abs(offset) <= RADIUS }
                    val box = fullBox && game.hasBoxFaces()
                    val front = game.box2dPath.takeIf { box }
                    val back = game.box2dBackPath.takeIf { box && showsBack && direction == 0 && offset == 0 }
                    listOfNotNull(cover, game.logoPath.takeIf { cover == null }, game.box2dSidePath, front, back, physical)
                }
                .distinct()
        }
    }
}
