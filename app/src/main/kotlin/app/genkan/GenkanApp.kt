package app.genkan

import android.app.Application
import app.genkan.art.ArtLoaders
import app.genkan.art.LibraryArtFetch
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
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GenkanApp : Application(), SingletonImageLoader.Factory {

    // Lazy, so the built-in player's process (which shares this class) builds none of them.
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
    @Inject lateinit var artLoaders: Lazy<ArtLoaders>
    @Inject lateinit var libraryArt: Lazy<LibraryArtFetch>

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
        artLoaders.get().start()
        // After each library sync, the whole library's shelf art, in the background.
        libraryArt.get().start()
    }

    /** The screen's image loader: see [ArtLoaders]. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = artLoaders.get().screen
}
