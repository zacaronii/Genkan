package app.genkan.art

import android.content.Context
import android.os.StatFs
import app.genkan.romm.RomMApiFactory
import coil3.Extras
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.getExtra
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.network.CacheStrategy
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import coil3.toUri
import okhttp3.Call
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * How artwork is kept on the device. Each RomM file is cached once, under its path ([RomMArt.key]),
 * and drawn from disk from then on with no request, offline too. Once a day at most, and only when
 * asked ([REVALIDATE], by the background check, never a screen's own load), a file is asked about
 * with the validator the server gave (ETag, or Last-Modified): a 304 keeps the copy, a new file
 * replaces it and is noted, for [takeReplaced] once the request is done (the new file is on disk
 * only then). A copy with no validator is fetched again when its check is due. An answer that
 * isn't an image (a proxy's sign-in page, an error page) is never kept: the cache is for good now,
 * and a page kept under an art's key would never be asked about again.
 */
class ArtCacheStrategy(
    private val now: () -> Long = System::currentTimeMillis,
) : CacheStrategy {
    private val replaced = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Whether the file under [key] was found replaced by a check; asked once its request is done. */
    fun takeReplaced(key: String): Boolean = replaced.remove(key)

    override suspend fun read(cacheResponse: NetworkResponse, networkRequest: NetworkRequest, options: Options): CacheStrategy.ReadResult {
        val due = options.getExtra(REVALIDATE) && now() - cacheResponse.responseMillis >= CHECK_EVERY_MS
        if (!due) return CacheStrategy.ReadResult(cacheResponse)
        val etag = cacheResponse.headers[ETAG]
        val modified = cacheResponse.headers[LAST_MODIFIED]
        // Nothing to ask with: the file is fetched again, plainly, and kept with what comes back.
        if (etag == null && modified == null) return CacheStrategy.ReadResult(networkRequest)
        val headers = networkRequest.headers.newBuilder().apply {
            etag?.let { set(IF_NONE_MATCH, it) }
            modified?.let { set(IF_MODIFIED_SINCE, it) }
        }.build()
        return CacheStrategy.ReadResult(networkRequest.copy(headers = headers))
    }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult {
        if (networkResponse.code == NOT_MODIFIED && cacheResponse != null) {
            // Still the same file: kept, and checked again in a day.
            return CacheStrategy.WriteResult(cacheResponse.copy(responseMillis = now(), headers = cacheResponse.headers + networkResponse.headers))
        }
        if (networkResponse.code !in 200..299) return CacheStrategy.WriteResult.DISABLED
        val type = networkResponse.headers[CONTENT_TYPE]
        if (type != null && !type.trim().startsWith("image/", ignoreCase = true)) return CacheStrategy.WriteResult.DISABLED
        if (cacheResponse != null) options.diskCacheKey?.let(replaced::add)
        return CacheStrategy.WriteResult(networkResponse.copy(responseMillis = now()))
    }

    private operator fun NetworkHeaders.plus(other: NetworkHeaders): NetworkHeaders =
        newBuilder().apply { other.asMap().forEach { (name, values) -> values.lastOrNull()?.let { set(name, it) } } }.build()

    companion object {
        /** Set on a request that may ask the server whether the file changed (the background check). */
        val REVALIDATE = Extras.Key(default = false)
        const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000
        private const val NOT_MODIFIED = 304
        private const val ETAG = "ETag"
        private const val CONTENT_TYPE = "Content-Type"
        private const val LAST_MODIFIED = "Last-Modified"
        private const val IF_NONE_MATCH = "If-None-Match"
        private const val IF_MODIFIED_SINCE = "If-Modified-Since"
    }
}

/** Loads [RomMArt] from the server signed in to now ([baseUrl]), cached under its [RomMArt.key]. */
class RomMArtFetcher(private val baseUrl: () -> String?, private val network: Fetcher.Factory<coil3.Uri>) : Fetcher.Factory<RomMArt> {
    override fun create(data: RomMArt, options: Options, imageLoader: ImageLoader): Fetcher? {
        val url = RomMApiFactory.assetUrl(baseUrl() ?: return null, data.path) ?: return null
        return network.create(url.toUri(), options.copy(diskCacheKey = data.key), imageLoader)
    }
}

/**
 * A RomM file on disk that won't decode (a broken download) is dropped, so the next load asks the
 * server again. It's decided by what happened, per request: only a failure while the file was being
 * decoded counts, never a missing fetcher (signed out), a network error, a cancellation or memory
 * running out.
 */
private class DropUndecodable(private val diskCache: DiskCache) : coil3.EventListener() {
    @Volatile private var decoding = false

    override fun decodeStart(request: coil3.request.ImageRequest, decoder: coil3.decode.Decoder, options: Options) {
        decoding = true
    }

    override fun decodeEnd(request: coil3.request.ImageRequest, decoder: coil3.decode.Decoder, options: Options, result: coil3.decode.DecodeResult?) {
        decoding = false
    }

    override fun onError(request: coil3.request.ImageRequest, result: coil3.request.ErrorResult) {
        val art = request.data as? RomMArt ?: return
        val error = result.throwable
        if (!decoding || error is kotlinx.coroutines.CancellationException || error is OutOfMemoryError) return
        diskCache.remove(art.key)
    }

    class Factory(private val diskCache: DiskCache) : coil3.EventListener.Factory {
        override fun create(request: coil3.request.ImageRequest): coil3.EventListener = DropUndecodable(diskCache)
    }
}

/** The memory cache knows [RomMArt] by its key too: one bitmap per file, whatever its `ts`. */
object RomMArtKeyer : Keyer<RomMArt> {
    override fun key(data: RomMArt, options: Options): String = data.key
}

object ArtCache {
    /** Where the art is kept: app storage the system doesn't clear when space is low, and that isn't backed up. */
    fun directory(context: Context) = File(context.noBackupFilesDir, "art")

    /** Coil's own folder before this: in the cache folder, which Android may empty. Removed once. */
    fun oldDirectory(context: Context) = File(context.cacheDir, "coil3_disk_cache")

    /** Removes [oldDirectory], by that exact path; true when there was one to remove. */
    fun removeOldDirectory(context: Context): Boolean = oldDirectory(context).let { it.isDirectory && it.deleteRecursively() }

    /**
     * The disk limit for a volume of [volumeBytes]: 1 GB, never more than 5% of the volume and
     * never less than 250 MB. About 2.3 MB a game for what the shelf draws (with the box's front
     * and back) is 280 MB for 123 games, and the library grows.
     */
    fun limitFor(volumeBytes: Long): Long = minOf(LIMIT_BYTES, volumeBytes / 20).coerceAtLeast(FLOOR_BYTES)

    /** The disk cache, one for every loader in the process. */
    fun diskCache(context: Context): DiskCache {
        val dir = directory(context).apply { mkdirs() }
        val volume = runCatching { StatFs(dir.path).totalBytes }.getOrDefault(0L)
        return DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(limitFor(volume)).build()
    }

    /**
     * An image loader for Genkan's art: RomM files by path ([RomMArt]) and plain URLs, through
     * [callFactory] (which carries the sign-in), into [diskCache]. [memory] is false for the
     * background loader, which only fills the disk.
     */
    fun loader(
        context: PlatformContext,
        diskCache: DiskCache,
        callFactory: () -> Call.Factory,
        baseUrl: () -> String?,
        strategy: CacheStrategy,
        memory: Boolean = true,
    ): ImageLoader {
        val network = OkHttpNetworkFetcherFactory(callFactory = callFactory, cacheStrategy = { strategy })
        return ImageLoader.Builder(context)
            .diskCache { diskCache }
            .eventListenerFactory(DropUndecodable.Factory(diskCache))
            .apply { if (!memory) memoryCache(null as MemoryCache?) }
            .components {
                add(network)
                add(RomMArtFetcher(baseUrl, network))
                add(RomMArtKeyer)
            }
            .build()
    }

    private const val LIMIT_BYTES = 1L shl 30
    private const val FLOOR_BYTES = 250L shl 20
}
