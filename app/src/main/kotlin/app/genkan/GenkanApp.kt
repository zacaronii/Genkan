package app.genkan

import android.app.Application
import app.genkan.download.DownloadQueue
import app.genkan.anki.AnkiMiner
import app.genkan.reader.ReaderSession
import app.genkan.words.HachidoriLink
import app.genkan.words.WordMarks
import app.genkan.stats.ReadingLogPusher
import app.genkan.saves.SaveSync
import app.genkan.azahar.AzaharText
import app.genkan.core.BuiltInText
import app.genkan.ppsspp.PpssppText
import app.genkan.romm.RomMImageAuthInterceptor
import app.genkan.romm.RomMSession
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class GenkanApp : Application(), SingletonImageLoader.Factory {

    // Lazy, so the built-in player's process (which shares this class) builds none of them.
    @Inject lateinit var okHttpClient: Lazy<OkHttpClient>
    @Inject lateinit var rommSession: Lazy<RomMSession>
    @Inject lateinit var readerSession: Lazy<ReaderSession>
    @Inject lateinit var ankiMiner: Lazy<AnkiMiner>
    @Inject lateinit var readingLog: Lazy<ReadingLogPusher>
    @Inject lateinit var saveSync: Lazy<SaveSync>
    @Inject lateinit var ppssppText: Lazy<PpssppText>
    @Inject lateinit var azaharText: Lazy<AzaharText>
    @Inject lateinit var builtInText: Lazy<BuiltInText>
    @Inject lateinit var downloadQueue: Lazy<DownloadQueue>
    @Inject lateinit var wordMarks: Lazy<WordMarks>
    @Inject lateinit var hachidori: Lazy<HachidoriLink>

    override fun onCreate() {
        super.onCreate()
        // The built-in player's process runs one game and nothing of the launcher's.
        if (getProcessName().endsWith(":core")) return
        // The reader listens for a texthooker from the start: a game's lines can arrive while
        // the bottom screen is released or not yet placed.
        readerSession.get().start()
        // Cards made offline go to Anki once it answers.
        ankiMiner.get().start()
        readingLog.get().start()
        saveSync.get().start()
        ppssppText.get().start()
        azaharText.get().start()
        builtInText.get().start()
        // Which words Anki has, for the backlog's marks; asked again after each card reaches Anki.
        wordMarks.get().start(ankiMiner.get().deliveries)
        // Hand marks and lookup counts shared with a Hachidori host, when one is set.
        hachidori.get().start()
        downloadQueue.get().resume()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Images get their own dispatcher: the shared one allows 5 requests per host, so the
        // fifteen-odd covers and spines of a shelf, plus the warmer's, queued behind each other
        // (and behind anything else on the shared client) for seconds after the focus stopped.
        val dispatcher = Dispatcher().apply { maxRequestsPerHost = IMAGE_REQUESTS_PER_HOST }
        val client = okHttpClient.get().newBuilder()
            .dispatcher(dispatcher)
            .addInterceptor(RomMImageAuthInterceptor(rommSession.get()::credentials))
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }

    private companion object {
        /** Parallel image fetches from the RomM server. */
        const val IMAGE_REQUESTS_PER_HOST = 12
    }
}
