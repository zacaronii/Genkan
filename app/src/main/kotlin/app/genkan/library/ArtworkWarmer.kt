package app.genkan.library

import android.content.Context
import app.genkan.launch.LocalShelves
import app.genkan.romm.RomMApiFactory
import app.genkan.romm.RomMSession
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the large cover and the disc or cartridge cut-out of the games around the focused one
 * into Coil's disk cache, so moving along the shelf finds them already on the device. Only the
 * neighbours are fetched, never the whole library, and a new focus cancels what the last one
 * still had in flight. The requests go through the app's singleton ImageLoader, which carries
 * the RomM sign-in (see GenkanApp.newImageLoader).
 */
@Singleton
class ArtworkWarmer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: RomMSession,
) {
    private var inFlight: List<Disposable> = emptyList()
    private var lastPaths: List<String> = emptyList()

    /** Warms the artwork of the games within [RADIUS] of [index] in [games]. */
    fun warmAround(games: List<GameEntity>, index: Int) {
        val paths = neighbourPaths(games, index, RADIUS)
        if (paths == lastPaths) return
        lastPaths = paths
        inFlight.forEach { if (!it.isDisposed) it.dispose() }
        val baseUrl = session.currentAccount()?.baseUrl ?: return
        val loader = SingletonImageLoader.get(context)
        inFlight = paths.mapNotNull { RomMApiFactory.assetUrl(baseUrl, it) }.map { url ->
            // Disk only: the screen decodes at its own size later, so a full-size bitmap in the
            // memory cache now would only push out images that are on screen.
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build()
            )
        }
    }

    companion object {
        /** How many games either side of the focused one are warmed. */
        const val RADIUS = 3

        /**
         * The asset paths to warm for the focused game at [index] and the [radius] games either
         * side of it: the large cover (or the small one when there is no large) and the physical
         * cut-out. Nearest games come first; games from local shelves have no RomM artwork.
         */
        fun neighbourPaths(games: List<GameEntity>, index: Int, radius: Int): List<String> {
            if (games.isEmpty() || index !in games.indices) return emptyList()
            val order = listOf(index) + (1..radius).flatMap { listOf(index - it, index + it) }
            return order.mapNotNull { games.getOrNull(it) }
                .filterNot { LocalShelves.isLocal(it) }
                .flatMap { listOfNotNull(it.coverLargePath ?: it.coverPath, it.physicalPath) }
                .distinct()
        }
    }
}
