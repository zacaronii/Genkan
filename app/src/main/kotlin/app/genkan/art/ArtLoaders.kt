package app.genkan.art

import android.content.Context
import android.util.Log
import app.genkan.romm.RomMImageAuthInterceptor
import app.genkan.romm.RomMSession
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The image loaders, over one disk cache ([ArtCache]): [screen] for what's on screen (the app's
 * singleton, many requests at once), and [background] for fetching ahead and the daily check, a
 * few at a time on a dispatcher of its own, so it never holds up what the screen waits for.
 */
@Singleton
class ArtLoaders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val session: RomMSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _versions = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** How many times each file (by [RomMArt.key]) was found replaced on the server; a screen draws [RomMArt] at that version. */
    val versions: StateFlow<Map<String, Int>> = _versions.asStateFlow()

    val strategy = ArtCacheStrategy()

    /** When each file (by key) was last fetched or checked in the background: not again until its check is due. */
    private val done = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Whether [path] was fetched or checked within the day, so the background can leave it without
     * reading or decoding it: noted in this process, or on disk with its entry written within the day
     * (a fetch and a 304 both rewrite the entry's metadata), which holds across a restart.
     */
    fun recentlyDone(path: String, now: Long = System.currentTimeMillis()): Boolean {
        val key = RomMArt.keyOf(path)
        val at = done[key] ?: ArtCache.writtenAt(diskCache, key)?.also { done[key] = it } ?: return false
        return now - at < ArtCacheStrategy.CHECK_EVERY_MS
    }

    /**
     * Fetches [path] into the disk cache on [background] (checking it on the server once a day),
     * notes it done, and lets the screens draw a replaced file once it's on disk. True when it's there.
     */
    suspend fun fetchToDisk(path: String): Boolean {
        val key = RomMArt.keyOf(path)
        val request = ImageRequest.Builder(context)
            .data(RomMArt(path))
            // Only the disk is filled: a token decode, thrown away.
            .size(DECODE_PX)
            .apply { extras[ArtCacheStrategy.REVALIDATE] = true }
            .build()
        val ok = runCatching { background.execute(request) }.getOrNull() is SuccessResult
        if (ok) done[key] = System.currentTimeMillis()
        // Only now is a replaced file on disk for the screen to load.
        settle(key)
        return ok
    }

    /** How full the disk cache is, as a share of its limit. */
    fun diskFill(): Double = diskCache.size.toDouble() / diskCache.maxSize.coerceAtLeast(1)

    /** Whether there's a server to fetch art from. */
    fun signedIn(): Boolean = session.currentAccount() != null

    /**
     * After a background request for [key] has finished: if its check found the file replaced
     * (now on disk), the screens draw it again. The old bitmap goes from memory first.
     */
    fun settle(key: String) {
        if (!strategy.takeReplaced(key)) return
        Log.i(TAG, "Art replaced on the server: $key")
        screen.memoryCache?.keys?.filter { it.key == key }?.forEach { screen.memoryCache?.remove(it) }
        _versions.update { it + (key to (it[key] ?: 0) + 1) }
    }

    val diskCache: DiskCache by lazy { ArtCache.diskCache(context) }

    val screen: ImageLoader by lazy { loaderWith(SCREEN_REQUESTS_PER_HOST, SCREEN_REQUESTS_PER_HOST * 2, memory = true) }

    val background: ImageLoader by lazy { loaderWith(BACKGROUND_REQUESTS, BACKGROUND_REQUESTS, memory = false) }

    private fun loaderWith(perHost: Int, total: Int, memory: Boolean): ImageLoader {
        val dispatcher = Dispatcher().apply {
            maxRequestsPerHost = perHost
            maxRequests = total
        }
        val client = okHttpClient.newBuilder()
            .dispatcher(dispatcher)
            .addInterceptor(RomMImageAuthInterceptor(session::credentials))
            .build()
        return ArtCache.loader(context, diskCache, { client }, { session.currentAccount()?.baseUrl }, strategy, memory)
    }

    /** At the app's start: Coil's old folder goes, once, and signing out empties the cache. */
    fun start() {
        scope.launch {
            if (ArtCache.removeOldDirectory(context)) Log.i(TAG, "Removed the old image cache")
        }
        scope.launch {
            session.signedOut.collect {
                screen.memoryCache?.clear()
                diskCache.clear()
                _versions.value = emptyMap()
                Log.i(TAG, "Signed out: the art cache is cleared")
            }
        }
    }

    private companion object {
        const val TAG = "ArtLoaders"
        /** Parallel fetches for the screen: the fifteen-odd covers and spines of a shelf at once. */
        const val SCREEN_REQUESTS_PER_HOST = 12
        /** Fetching ahead and checking: a few at a time. */
        const val BACKGROUND_REQUESTS = 2
        const val DECODE_PX = 32
    }
}
