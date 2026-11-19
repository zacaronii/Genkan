package app.genkan

import android.app.Application
import app.genkan.anki.AnkiMiner
import app.genkan.reader.ReaderSession
import app.genkan.stats.ReadingLogPusher
import app.genkan.romm.RomMImageAuthInterceptor
import app.genkan.romm.RomMSession
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class GenkanApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var rommSession: RomMSession
    @Inject lateinit var readerSession: ReaderSession
    @Inject lateinit var ankiMiner: AnkiMiner
    @Inject lateinit var readingLog: ReadingLogPusher

    override fun onCreate() {
        super.onCreate()
        // The reader listens for a texthooker from the start: a game's lines can arrive while
        // the bottom screen is released or not yet placed.
        readerSession.start()
        // Cards made offline go to Anki once it answers.
        ankiMiner.start()
        readingLog.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = okHttpClient.newBuilder()
            .addInterceptor(RomMImageAuthInterceptor(rommSession::credentials))
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
}
