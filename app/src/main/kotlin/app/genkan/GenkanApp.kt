package app.genkan

import android.app.Application
import app.genkan.anki.AnkiMiner
import app.genkan.reader.ReaderSession
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
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = okHttpClient.get().newBuilder()
            .addInterceptor(RomMImageAuthInterceptor(rommSession.get()::credentials))
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
}
